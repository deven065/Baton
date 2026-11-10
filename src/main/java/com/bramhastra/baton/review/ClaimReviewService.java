package com.bramhastra.baton.review;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.evidence.QuoteMatcher;
import com.bramhastra.baton.handover.Handover;
import com.bramhastra.baton.handover.HandoverAccess;
import com.bramhastra.baton.handover.HandoverRepository;
import com.bramhastra.baton.handover.HandoverStateMachine;
import com.bramhastra.baton.handover.HandoverStatus;
import com.bramhastra.baton.reconstruct.stage.ScoreStage;
import com.bramhastra.baton.review.ReviewStateMachine.ReviewState;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.source.SourceRepository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The lead's review actions: accept, edit, reject, ask the leaver (needs-info), restore, resolve a conflict,
 * and add a claim by hand. Every change is optimistic-locked, recorded as a revision and audited in the
 * same transaction (LLD L7).
 */
@Service
public class ClaimReviewService {

    static final Set<String> TYPES = Set.of("DECISION", "OPEN_WORK", "OWNER", "RISK", "DEPENDENCY", "BLOCKER",
            "RECURRING_ISSUE", "CONTACT", "OPEN_QUESTION", "PROCESS", "ACCESS");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final HandoverRepository handovers;
    private final HandoverAccess handoverAccess;
    private final ClaimQueryService query;
    private final ScoreStage scorer;
    private final QuoteMatcher matcher;
    private final AccessPolicy access;
    private final AuditService audit;

    public ClaimReviewService(JdbcClient jdbc, ObjectMapper json, HandoverRepository handovers,
                              HandoverAccess handoverAccess, ClaimQueryService query, ScoreStage scorer,
                              QuoteMatcher matcher, AccessPolicy access, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.handovers = handovers;
        this.handoverAccess = handoverAccess;
        this.query = query;
        this.scorer = scorer;
        this.matcher = matcher;
        this.access = access;
        this.audit = audit;
    }

    record Locked(UUID id, UUID handoverId, int version, ReviewState reviewState, String title, String body,
                  Map<String, Object> attributes, boolean critical) {
        Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reviewState", reviewState.name());
            m.put("title", title);
            m.put("body", body);
            m.put("attributes", attributes);
            m.put("critical", critical);
            return m;
        }
    }

    public record Patch(Integer version, String reviewState, String title, String body,
                        Map<String, Object> attributes, Boolean critical, String reason) {
    }

    @Transactional
    public ClaimQueryService.ClaimView patch(CurrentUser me, UUID claimId, Patch p) {
        Locked c = lock(claimId);
        Handover h = handoverAccess.requireLead(me, c.handoverId());
        query.get(claimId, me).orElseThrow(ApiException::notFound);
        if (p.version() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of("version", "Send the version you edited"));
        }
        if (p.version() != c.version()) {
            throw new ApiException(ErrorCode.CLAIM_STALE, "Someone else changed this claim; reload and retry",
                    query.get(claimId, me).orElseThrow());
        }
        openForEdits(me, h);

        String title = p.title() == null ? c.title() : p.title().strip();
        String body = p.body() == null ? c.body() : p.body().strip();
        Map<String, Object> attributes = new LinkedHashMap<>(c.attributes());
        if (p.attributes() != null) {
            p.attributes().forEach((k, v) -> {
                if (v == null) {
                    attributes.remove(k);
                } else {
                    attributes.put(k, v);
                }
            });
        }
        boolean critical = p.critical() == null ? c.critical() : p.critical();
        if (title.isBlank() || title.length() > 300) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of("title", "Title is required (max 300 characters)"));
        }
        boolean contentChanged = !title.equals(c.title()) || !Objects.equals(body, c.body())
                || !attributes.equals(c.attributes()) || critical != c.critical();

        ReviewState to;
        if (contentChanged) {
            if (p.reviewState() != null && !p.reviewState().equals("EDITED")) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                        Map.of("reviewState", "An edit always makes the claim EDITED; accept it in a second step"));
            }
            to = ReviewState.EDITED;
        } else if (p.reviewState() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Nothing to change");
        } else {
            try {
                to = ReviewState.valueOf(p.reviewState());
            } catch (IllegalArgumentException e) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                        Map.of("reviewState", "Unknown review state"));
            }
        }
        ReviewStateMachine.check(c.reviewState(), to);

        if (to == ReviewState.NEEDS_INFO) {
            String question = p.reason() != null && !p.reason().isBlank() ? p.reason().strip()
                    : "Can you clarify or confirm this: \"" + c.title() + "\"?";
            jdbc.sql("""
                    INSERT INTO gap_question (handover_id, claim_id, kind, question, priority, status)
                    VALUES (:h, :c, 'OTHER', :q, 1, 'OPEN')
                    """).param("h", h.id()).param("c", c.id())
                    .param("q", question.length() > 1000 ? question.substring(0, 1000) : question).update();
        }

        Locked after = new Locked(c.id(), c.handoverId(), c.version() + 1, to, title, body, attributes, critical);
        jdbc.sql("""
                UPDATE claim SET review_state = :rs, title = :t, body = :b, attributes = CAST(:a AS jsonb),
                                 critical = :cr, version = version + 1, updated_at = now()
                WHERE id = :id AND version = :v
                """)
                .param("rs", to.name()).param("t", title).param("b", body)
                .param("a", json.writeValueAsString(attributes)).param("cr", critical)
                .param("id", c.id()).param("v", c.version()).update();
        revision(c.id(), after.version(), me.id(), c.snapshot(), after.snapshot(), p.reason());
        audit.record(me.id(), "CLAIM_REVIEWED", "claim", c.id(), h.projectId(),
                Map.of("from", c.reviewState().name(), "to", to.name(), "edited", contentChanged));
        return query.get(claimId, me).orElseThrow();
    }

    /**
     * The lead picks which of two conflicting claims is current. The conflict is marked resolved,
     * the winner supersedes the loser, the loser is rejected with a reason, and the matching exit
     * question is closed.
     */
    @Transactional
    public ClaimQueryService.ClaimView resolveConflict(CurrentUser me, UUID winnerId, UUID loserId, String reason) {
        Locked winner = lock(winnerId);
        Locked loser = lock(loserId);
        if (!winner.handoverId().equals(loser.handoverId())) {
            throw ApiException.notFound();
        }
        Handover h = handoverAccess.requireLead(me, winner.handoverId());
        query.get(winnerId, me).orElseThrow(ApiException::notFound);
        query.get(loserId, me).orElseThrow(ApiException::notFound);
        openForEdits(me, h);
        int n = jdbc.sql("""
                UPDATE claim_link SET resolved = TRUE
                WHERE kind = 'CONFLICTS_WITH' AND NOT resolved
                  AND ((from_claim_id = :a AND to_claim_id = :b) OR (from_claim_id = :b AND to_claim_id = :a))
                """).param("a", winnerId).param("b", loserId).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "These claims have no open conflict");
        }
        // A lead who changes their mind replaces the earlier decision; the two never supersede each other.
        jdbc.sql("DELETE FROM claim_link WHERE kind = 'SUPERSEDES' AND from_claim_id = :l AND to_claim_id = :w")
                .param("w", winnerId).param("l", loserId).update();
        jdbc.sql("""
                INSERT INTO claim_link (from_claim_id, to_claim_id, kind, resolved) VALUES (:w, :l, 'SUPERSEDES', TRUE)
                ON CONFLICT DO NOTHING
                """).param("w", winnerId).param("l", loserId).update();
        String why = "Superseded by \"" + winner.title() + "\"" + (reason == null || reason.isBlank() ? "" : ": " + reason.strip());
        // Choosing the current claim is a review decision too, so a re-run keeps it (and this decision).
        if (winner.reviewState() == ReviewState.PENDING || winner.reviewState() == ReviewState.NEEDS_INFO) {
            Locked chosen = new Locked(winner.id(), winner.handoverId(), winner.version() + 1, ReviewState.ACCEPTED,
                    winner.title(), winner.body(), winner.attributes(), winner.critical());
            jdbc.sql("UPDATE claim SET review_state = 'ACCEPTED', version = version + 1, updated_at = now() WHERE id = :id")
                    .param("id", winnerId).update();
            revision(winnerId, chosen.version(), me.id(), winner.snapshot(), chosen.snapshot(),
                    "Chosen as current over \"" + loser.title() + "\"" + (reason == null || reason.isBlank() ? "" : ": " + reason.strip()));
        }
        if (loser.reviewState() != ReviewState.REJECTED) {
            Locked after = new Locked(loser.id(), loser.handoverId(), loser.version() + 1, ReviewState.REJECTED,
                    loser.title(), loser.body(), loser.attributes(), loser.critical());
            jdbc.sql("UPDATE claim SET review_state = 'REJECTED', version = version + 1, updated_at = now() WHERE id = :id")
                    .param("id", loserId).update();
            revision(loserId, after.version(), me.id(), loser.snapshot(), after.snapshot(), why);
        }
        jdbc.sql("""
                UPDATE gap_question SET status = 'SKIPPED', answer_text = :why, answered_by = :me, answered_at = now()
                WHERE kind = 'CONFLICT' AND status IN ('OPEN', 'UNANSWERED')
                  AND ((claim_id = :a AND related_claim_id = :b) OR (claim_id = :b AND related_claim_id = :a))
                """).param("why", "Resolved by the lead. " + why).param("me", me.id())
                .param("a", winnerId).param("b", loserId).update();
        scorer.rescore(h.id());
        audit.record(me.id(), "CONFLICT_RESOLVED", "claim", winnerId, h.projectId(),
                Map.of("winner", winnerId.toString(), "loser", loserId.toString()));
        return query.get(winnerId, me).orElseThrow();
    }

    public record NewEvidence(UUID chunkId, String quote) {
    }

    public record NewClaim(String type, String title, String body, Map<String, Object> attributes, Boolean critical,
                           List<NewEvidence> evidence) {
    }

    /** A lead-authored claim must cite source text; the quote is verified on save like any other. */
    @Transactional
    public ClaimQueryService.ClaimView addClaim(CurrentUser me, UUID handoverId, NewClaim req) {
        Handover h = handoverAccess.requireLead(me, handoverId);
        openForEdits(me, h);
        Map<String, String> problems = new LinkedHashMap<>();
        if (req.type() == null || !TYPES.contains(req.type())) {
            problems.put("type", "Must be one of " + new TreeSet<>(TYPES));
        }
        if (req.title() == null || req.title().isBlank() || req.title().length() > 300) {
            problems.put("title", "Title is required (max 300 characters)");
        }
        if (req.evidence() == null || req.evidence().isEmpty()) {
            problems.put("evidence", "Cite at least one source quote");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed", problems);
        }

        record Located(UUID chunkId, String quote, double score, int start, int end, Sensitivity s, List<String> acl) {
        }
        List<Located> located = new ArrayList<>();
        for (int i = 0; i < req.evidence().size(); i++) {
            NewEvidence ev = req.evidence().get(i);
            var chunk = jdbc.sql("""
                    SELECT c.text, c.sensitivity, c.acl_groups FROM chunk c
                    JOIN document d ON d.id = c.document_id JOIN source s ON s.id = d.source_id
                    WHERE c.id = :id AND s.project_id = :p AND
                    """ + access.sqlPredicate("c"))
                    .param("id", ev.chunkId()).param("p", h.projectId()).params(access.sqlParams(me))
                    .query((rs, n) -> new Object[] {rs.getString(1), rs.getShort(2), SourceRepository.textArray(rs.getArray(3))})
                    .optional().orElse(null);
            if (chunk == null) {
                problems.put("evidence[" + i + "].chunkId", "Not found");
                continue;
            }
            String text = (String) chunk[0];
            QuoteMatcher.MatchResult m = matcher.match(ev.quote(), text);
            if (!m.valid()) {
                problems.put("evidence[" + i + "].quote", "Quote not found in the source text");
                continue;
            }
            @SuppressWarnings("unchecked")
            List<String> acl = (List<String>) chunk[2];
            located.add(new Located(ev.chunkId(), text.substring(m.start(), m.end()), m.score(), m.start(), m.end(),
                    Sensitivity.ofLevel((Short) chunk[1]), acl));
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed", problems);
        }
        Sensitivity sens = Sensitivity.max(located.stream().map(Located::s).toList());
        Set<String> acl = new TreeSet<>();
        located.forEach(l -> acl.addAll(l.acl()));
        Map<String, Object> attributes = req.attributes() == null ? Map.of() : req.attributes();
        UUID id = jdbc.sql("""
                INSERT INTO claim (handover_id, type, title, body, attributes, status, confidence, reasons, sensitivity,
                                   acl_groups, critical, review_state, origin)
                VALUES (:h, :type, :title, :body, CAST(:a AS jsonb), 'SINGLE_SOURCE', 0, '[]'::jsonb, :s,
                        CAST(:acl AS text[]), :cr, 'ACCEPTED', 'HUMAN')
                RETURNING id
                """)
                .param("h", h.id()).param("type", req.type()).param("title", req.title().strip())
                .param("body", req.body()).param("a", json.writeValueAsString(attributes)).param("s", sens.level())
                .param("acl", AccessPolicy.toPgArray(acl)).param("cr", Boolean.TRUE.equals(req.critical()))
                .query(UUID.class).single();
        for (Located l : located) {
            jdbc.sql("""
                    INSERT INTO evidence (claim_id, chunk_id, quote, model_quote, match_score, start_offset, end_offset,
                                          valid, verified_at)
                    VALUES (:c, :k, :q, :q, :s, :st, :en, TRUE, now())
                    """).param("c", id).param("k", l.chunkId()).param("q", l.quote()).param("s", l.score())
                    .param("st", l.start()).param("en", l.end()).update();
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reviewState", "ACCEPTED");
        after.put("title", req.title().strip());
        after.put("body", req.body());
        after.put("attributes", attributes);
        revision(id, 0, me.id(), null, after, "Added by the lead");
        scorer.rescore(h.id());
        audit.record(me.id(), "CLAIM_ADDED", "claim", id, h.projectId(), Map.of("type", req.type()));
        return query.get(id, me).orElseThrow();
    }

    public record RevisionView(int version, UUID actorId, String actorName, Map<String, Object> before,
                               Map<String, Object> after, String reason, Instant at) {
    }

    public List<RevisionView> revisions(CurrentUser me, UUID claimId) {
        UUID handoverId = jdbc.sql("SELECT handover_id FROM claim WHERE id = :id").param("id", claimId)
                .query(UUID.class).optional().orElseThrow(ApiException::notFound);
        handoverAccess.requireWorkspace(me, handoverId);
        query.get(claimId, me).orElseThrow(ApiException::notFound);
        return jdbc.sql("""
                SELECT r.version, r.actor_id, u.display_name, r.before::text AS before, r.after::text AS after,
                       r.reason, r.created_at
                FROM claim_revision r JOIN app_user u ON u.id = r.actor_id
                WHERE r.claim_id = :id ORDER BY r.created_at, r.version
                """).param("id", claimId)
                .query((rs, n) -> new RevisionView(rs.getInt(1), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4) == null ? null : json.readValue(rs.getString(4), MAP),
                        json.readValue(rs.getString(5), MAP), rs.getString(6), rs.getTimestamp(7).toInstant()))
                .list();
    }

    // ───────────── shared helpers ─────────────

    /**
     * Review changes are allowed while IN_REVIEW. Editing an approved or shared handover reopens it for
     * review (its approved versions stay frozen); a new version is created on the next approval.
     */
    public void openForEdits(CurrentUser me, Handover h) {
        if (h.status() == HandoverStatus.IN_REVIEW) {
            return;
        }
        HandoverStatus next = HandoverStateMachine.next(h.status(), HandoverStateMachine.Event.EDIT_AFTER_APPROVAL);
        handovers.transition(h.id(), h.status(), next);
        audit.record(me.id(), "HANDOVER_REOPENED", "handover", h.id(), h.projectId(), Map.of("from", h.status().name()));
    }

    Locked lock(UUID claimId) {
        return jdbc.sql("""
                SELECT id, handover_id, version, review_state, title, body, attributes::text, critical
                FROM claim WHERE id = :id FOR UPDATE
                """).param("id", claimId)
                .query((rs, n) -> new Locked(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3),
                        ReviewState.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6),
                        json.readValue(rs.getString(7), MAP), rs.getBoolean(8)))
                .optional().orElseThrow(ApiException::notFound);
    }

    void revision(UUID claimId, int version, UUID actorId, Map<String, Object> before, Map<String, Object> after,
                  String reason) {
        jdbc.sql("""
                INSERT INTO claim_revision (claim_id, version, actor_id, before, after, reason)
                VALUES (:c, :v, :a, CAST(:b AS jsonb), CAST(:af AS jsonb), :r)
                """)
                .param("c", claimId).param("v", version).param("a", actorId)
                .param("b", before == null ? null : json.writeValueAsString(before))
                .param("af", json.writeValueAsString(after))
                .param("r", reason == null ? null : reason.length() > 500 ? reason.substring(0, 500) : reason)
                .update();
    }
}
