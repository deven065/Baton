package com.bramhastra.baton.room;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.common.RateLimiter;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.CurrentUserProvider;
import com.bramhastra.baton.source.Redactor;

/**
 * A project's team room: a running chat and a video call for the people on the project (lead, person leaving and
 * successors alike). Only members can read or take part; everyone else gets "not found".
 */
@RestController
@RequestMapping("/api/projects/{projectId}/room")
public class RoomController {

    static final int MAX_MESSAGE = 2000;
    private static final int MAX_SIGNAL = 20_000;
    private static final Set<String> SIGNALS = Set.of("offer", "answer", "ice");
    private static final Set<Redactor.Kind> FORBIDDEN = Set.of(Redactor.Kind.API_KEY, Redactor.Kind.PASSWORD,
            Redactor.Kind.SECRET);

    public record Message(UUID id, UUID senderId, String senderName, String body, Instant createdAt) {
    }

    public record SendRequest(String body) {
    }

    public record SignalRequest(UUID to, String kind, String data) {
    }

    public record IceServer(List<String> urls, String username, String credential) {
    }

    public record JoinView(List<RoomHub.Participant> others, int maxInCall, List<IceServer> iceServers) {
    }

    private final JdbcClient jdbc;
    private final RoomHub hub;
    private final AccessPolicy access;
    private final CurrentUserProvider currentUser;
    private final Redactor redactor;
    private final RateLimiter limiter;
    private final String stun;
    private final String turnUrl;
    private final String turnUser;
    private final String turnCredential;

    public RoomController(JdbcClient jdbc, RoomHub hub, AccessPolicy access, CurrentUserProvider currentUser,
                          Redactor redactor, RateLimiter limiter,
                          @Value("${baton.call.stun:stun:stun.l.google.com:19302}") String stun,
                          @Value("${baton.call.turn-url:}") String turnUrl,
                          @Value("${baton.call.turn-username:}") String turnUser,
                          @Value("${baton.call.turn-credential:}") String turnCredential) {
        this.jdbc = jdbc;
        this.hub = hub;
        this.access = access;
        this.currentUser = currentUser;
        this.redactor = redactor;
        this.limiter = limiter;
        this.stun = stun;
        this.turnUrl = turnUrl;
        this.turnUser = turnUser;
        this.turnCredential = turnCredential;
    }

    /** The latest messages, oldest first. {@code before} pages further back. */
    @GetMapping("/chat")
    public List<Message> messages(@PathVariable UUID projectId, @RequestParam(required = false) Instant before,
                                  @RequestParam(defaultValue = "100") int limit) {
        access.requireMember(currentUser.get(), projectId);
        List<Message> latest = jdbc.sql("""
                SELECT m.id, m.sender_id, COALESCE(u.display_name, 'Former member') AS sender, m.body, m.created_at
                FROM chat_message m LEFT JOIN app_user u ON u.id = m.sender_id
                WHERE m.project_id = :p AND (CAST(:before AS timestamptz) IS NULL OR m.created_at < CAST(:before AS timestamptz))
                ORDER BY m.created_at DESC LIMIT :n
                """)
                .param("p", projectId).param("before", before == null ? null : java.sql.Timestamp.from(before))
                .param("n", Math.max(1, Math.min(limit, 200)))
                .query((rs, i) -> new Message(rs.getObject("id", UUID.class), rs.getObject("sender_id", UUID.class),
                        rs.getString("sender"), rs.getString("body"), rs.getTimestamp("created_at").toInstant()))
                .list();
        return latest.reversed();
    }

    @PostMapping("/chat")
    @ResponseStatus(HttpStatus.CREATED)
    public Message send(@PathVariable UUID projectId, @RequestBody SendRequest req) {
        CurrentUser me = currentUser.get();
        access.requireMember(me, projectId);
        String text = req.body() == null ? "" : req.body().strip();
        if (text.isEmpty() || text.length() > MAX_MESSAGE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of("body", "Write a message of 1 to 2000 characters"));
        }
        if (redactor.redact(text).redactions().stream().anyMatch(r -> FORBIDDEN.contains(r.kind()))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of("body", "That looks like a key or password. Say where it is kept instead of pasting it"));
        }
        limiter.acquire("chat:" + me.id(), 30, Duration.ofMinutes(1));
        UUID id = UUID.randomUUID();
        Instant at = jdbc.sql("""
                INSERT INTO chat_message (id, project_id, sender_id, body) VALUES (:id, :p, :u, :b)
                RETURNING created_at
                """).param("id", id).param("p", projectId).param("u", me.id()).param("b", text)
                .query((rs, i) -> rs.getTimestamp("created_at").toInstant()).single();
        Message message = new Message(id, me.id(), me.displayName(), text, at);
        hub.broadcast(projectId, "chat", message);
        return message;
    }

    /** Live events for this project: new messages, and people joining or leaving the call. */
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID projectId) {
        access.requireMember(currentUser.get(), projectId);
        return hub.subscribe(projectId, currentUser.get().id());
    }

    /** Joins the call. Returns who is already in it (to ring) and the network servers browsers should use. */
    @PostMapping("/call/join")
    public JoinView join(@PathVariable UUID projectId) {
        CurrentUser me = currentUser.get();
        access.requireMember(me, projectId);
        if (!hub.join(projectId, me.id(), me.displayName())) {
            throw new ApiException(ErrorCode.NOT_ALLOWED,
                    "The call is full. Up to " + RoomHub.MAX_IN_CALL + " people can be on it at once.");
        }
        List<RoomHub.Participant> others = hub.participants(projectId).stream()
                .filter(p -> !p.userId().equals(me.id())).toList();
        return new JoinView(others, RoomHub.MAX_IN_CALL, iceServers());
    }

    @PostMapping("/call/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable UUID projectId) {
        CurrentUser me = currentUser.get();
        access.requireMember(me, projectId);
        hub.leave(projectId, me.id());
    }

    /** Passes one setup message (offer, answer or network candidate) to another person on the same call. */
    @PostMapping("/call/signal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signal(@PathVariable UUID projectId, @RequestBody SignalRequest req) {
        CurrentUser me = currentUser.get();
        access.requireMember(me, projectId);
        if (req.to() == null || req.kind() == null || !SIGNALS.contains(req.kind()) || req.data() == null
                || req.data().length() > MAX_SIGNAL) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of("signal", "Send an offer, answer or ice message to someone on the call"));
        }
        // Both ends must be on the call; nothing is relayed to anyone else.
        if (!hub.inCall(projectId, me.id()) || !hub.inCall(projectId, req.to())) {
            throw ApiException.notFound();
        }
        limiter.acquire("signal:" + me.id(), 1200, Duration.ofMinutes(1));
        hub.sendTo(projectId, req.to(), "signal", Map.of("from", me.id(), "fromName", me.displayName(),
                "kind", req.kind(), "data", req.data()));
    }

    private List<IceServer> iceServers() {
        List<IceServer> servers = new java.util.ArrayList<>();
        servers.add(new IceServer(List.of(stun), null, null));
        if (!turnUrl.isBlank()) {
            servers.add(new IceServer(List.of(turnUrl.split("\\s*,\\s*")), turnUser, turnCredential));
        }
        return servers;
    }
}
