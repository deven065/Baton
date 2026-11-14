package com.bramhastra.baton.handover;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.identity.AppUser;
import com.bramhastra.baton.identity.AppUserRepository;
import com.bramhastra.baton.identity.ProjectMemberRepository;
import com.bramhastra.baton.job.JobKind;
import com.bramhastra.baton.job.JobRepository;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.CurrentUserProvider;

import jakarta.validation.Valid;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
public class HandoverController {

    private final HandoverRepository handovers;
    private final HandoverAccess handoverAccess;
    private final AccessPolicy access;
    private final CurrentUserProvider currentUser;
    private final AppUserRepository users;
    private final ProjectMemberRepository members;
    private final JobRepository jobs;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final com.bramhastra.baton.sync.SyncService sync;

    public HandoverController(HandoverRepository handovers, HandoverAccess handoverAccess, AccessPolicy access,
                              CurrentUserProvider currentUser, AppUserRepository users,
                              ProjectMemberRepository members, JobRepository jobs, AuditService audit,
                              JdbcClient jdbc, ObjectMapper json, com.bramhastra.baton.sync.SyncService sync) {
        this.handovers = handovers;
        this.handoverAccess = handoverAccess;
        this.access = access;
        this.currentUser = currentUser;
        this.users = users;
        this.members = members;
        this.jobs = jobs;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.sync = sync;
    }

    /** The lead starts a handover for a departing member. */
    @PostMapping("/projects/{projectId}/handovers")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public HandoverView create(@PathVariable UUID projectId, @Valid @RequestBody CreateHandover req) {
        CurrentUser me = currentUser.get();
        access.requireLead(me, projectId);
        if (members.findByProjectIdAndUserId(projectId, req.departingUserId()).isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of("departingUserId", "Must be a member of this project"));
        }
        AppUser departing = users.findById(req.departingUserId()).orElseThrow(ApiException::notFound);
        String scenario = req.scenario() == null ? "PLANNED" : req.scenario();
        UUID id = handovers.insert(projectId, departing.getId(), req.title().strip(), scenario,
                aliases(departing, req.aliases()), me.id());
        audit.record(me.id(), "HANDOVER_CREATED", "handover", id, projectId,
                Map.of("departingUserId", departing.getId().toString(), "scenario", scenario));
        return view(handovers.findById(id).orElseThrow());
    }

    /** The lead renames a handover (for example a title typed by mistake). Recorded in the audit log. */
    @PatchMapping("/handovers/{id}")
    @Transactional
    public HandoverView rename(@PathVariable UUID id, @Valid @RequestBody RenameHandover req) {
        CurrentUser me = currentUser.get();
        Handover h = handoverAccess.requireLead(me, id);
        String title = req.title().strip();
        if (title.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed", Map.of("title", "Enter a title"));
        }
        handovers.rename(id, title);
        audit.record(me.id(), "HANDOVER_RENAMED", "handover", id, h.projectId(), Map.of("from", h.title(), "to", title));
        return view(handovers.findById(id).orElseThrow());
    }

    @GetMapping("/projects/{projectId}/handovers")
    public List<HandoverView> list(@PathVariable UUID projectId) {
        CurrentUser me = currentUser.get();
        boolean lead = access.requireMember(me, projectId) == com.bramhastra.baton.identity.ProjectRole.PROJECT_LEAD;
        return handovers.listForProject(projectId).stream()
                .filter(h -> lead || me.id().equals(h.departingUserId()))
                .map(this::view).toList();
    }

    @GetMapping("/handovers/{handoverId}")
    public HandoverView get(@PathVariable UUID handoverId) {
        return view(handoverAccess.requireWorkspace(currentUser.get(), handoverId));
    }

    /** Flow B: queue reconstruction. Only one run at a time per handover. */
    @PostMapping("/handovers/{handoverId}/reconstruct")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public ReconstructStarted reconstruct(@PathVariable UUID handoverId) {
        CurrentUser me = currentUser.get();
        Handover h = handoverAccess.requireLead(me, handoverId);
        if (h.status() == HandoverStatus.RECONSTRUCTING) {
            throw new ApiException(ErrorCode.RUN_IN_PROGRESS, "A reconstruction is already running");
        }
        boolean syncing = jdbc.sql("""
                SELECT count(*) FROM source WHERE project_id = :p AND status IN ('PENDING', 'SYNCING')
                """).param("p", h.projectId()).query(Integer.class).single() > 0;
        if (syncing) {
            throw new ApiException(ErrorCode.RUN_IN_PROGRESS, "Wait until all sources have finished syncing");
        }
        HandoverStatus next = HandoverStateMachine.next(h.status(), HandoverStateMachine.Event.RECONSTRUCT);
        handovers.transition(h.id(), h.status(), next);
        UUID jobId = jobs.enqueue(JobKind.RECONSTRUCT, h.projectId(), me.id(), Map.of("handoverId", h.id().toString()));
        audit.record(me.id(), "RECONSTRUCT_REQUESTED", "handover", h.id(), h.projectId(), Map.of("jobId", jobId.toString()));
        sync.dismissChangeNotices(h.id()); // reconstructing is the answer to "new evidence"
        return new ReconstructStarted(h.id(), jobId);
    }

    /** Latest run: status, stage, warnings ("2 of 55 batches not analysed"). */
    @GetMapping("/handovers/{handoverId}/runs/latest")
    public Map<String, Object> latestRun(@PathVariable UUID handoverId) {
        Handover h = handoverAccess.requireWorkspace(currentUser.get(), handoverId);
        return jdbc.sql("""
                SELECT id, status, stage, stats::text AS stats, warnings::text AS warnings, prompt_version, model,
                       error, started_at, finished_at, job_id
                FROM extraction_run WHERE handover_id = :h ORDER BY started_at DESC LIMIT 1
                """)
                .param("h", h.id())
                .query((rs, n) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getObject("id"));
                    m.put("jobId", rs.getObject("job_id"));
                    m.put("status", rs.getString("status"));
                    m.put("stage", rs.getString("stage"));
                    m.put("stats", json.readTree(rs.getString("stats")));
                    m.put("warnings", json.readTree(rs.getString("warnings")));
                    m.put("promptVersion", rs.getString("prompt_version"));
                    m.put("model", rs.getString("model"));
                    m.put("error", rs.getString("error"));
                    m.put("startedAt", rs.getTimestamp("started_at").toInstant());
                    m.put("finishedAt", rs.getTimestamp("finished_at") == null ? null
                            : rs.getTimestamp("finished_at").toInstant());
                    return m;
                })
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    private HandoverView view(Handover h) {
        return new HandoverView(h.id(), h.projectId(), h.departingUserId(), h.departingName(), h.title(),
                h.scenario(), h.status(), h.currentVersionNo(), h.departingAliases(), h.createdAt(), h.updatedAt());
    }

    /** Names the person may appear under in sources: full name, first name, email local part, extras. */
    static List<String> aliases(AppUser u, List<String> extra) {
        Set<String> out = new LinkedHashSet<>();
        out.add(u.getDisplayName().strip());
        out.add(u.getDisplayName().strip().split("\\s+")[0]);
        out.add(u.getEmail().substring(0, u.getEmail().indexOf('@')));
        if (extra != null) {
            extra.stream().filter(a -> a != null && !a.isBlank()).map(String::strip).forEach(out::add);
        }
        List<String> list = new ArrayList<>();
        out.forEach(a -> list.add(a.toLowerCase(Locale.ROOT)));
        return list.stream().distinct().toList();
    }

    public record RenameHandover(@NotBlank @Size(max = 200) String title) {
    }

    public record CreateHandover(@NotNull UUID departingUserId, @NotBlank @Size(max = 200) String title,
                                 @Pattern(regexp = "PLANNED|SUDDEN|SPLIT") String scenario,
                                 @Size(max = 10) List<@Size(max = 60) String> aliases) {
    }

    public record HandoverView(UUID id, UUID projectId, UUID departingUserId, String departingName, String title,
                               String scenario, HandoverStatus status, Integer currentVersionNo,
                               List<String> departingAliases, Instant createdAt, Instant updatedAt) {
    }

    public record ReconstructStarted(UUID handoverId, UUID jobId) {
    }
}
