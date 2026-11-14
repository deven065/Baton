package com.bramhastra.baton.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.handover.HandoverRepository;
import com.bramhastra.baton.handover.HandoverStateMachine;
import com.bramhastra.baton.handover.HandoverStatus;
import com.bramhastra.baton.job.JobKind;
import com.bramhastra.baton.job.JobRepository;
import com.bramhastra.baton.mail.Mailer;
import com.bramhastra.baton.source.SourceRepository;
import com.bramhastra.baton.source.SourceRow;
import com.bramhastra.baton.tenant.TenantActivity;
import com.bramhastra.baton.tenant.TenantContext;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps handovers current on their own. Every few minutes the scheduler asks each workspace which live sources
 * with automatic sync are due; they are read again by the ordinary ingest job in "scheduled" mode, which compares
 * a fingerprint with the last read and re-indexes only when something changed. A change raises a notice for the
 * lead of each handover in the project (and an email when email is on); a handover still in review is
 * reconstructed again by itself, an approved or shared one is left alone: its recipients keep the frozen version
 * and the lead is offered the next one.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    /** What a read changed, for the notice. */
    public record Change(int added, int changed, int removed, List<String> titles) {
        public int total() {
            return added + changed + removed;
        }

        String describe() {
            List<String> parts = new ArrayList<>();
            if (added > 0) {
                parts.add(added + " new");
            }
            if (changed > 0) {
                parts.add(changed + " updated");
            }
            if (removed > 0) {
                parts.add(removed + " removed");
            }
            String what = String.join(", ", parts);
            return titles.isEmpty() ? what : what + " (" + String.join("; ", titles) + ")";
        }
    }

    public record Notice(UUID id, UUID handoverId, String kind, String message, Map<String, Object> details,
                         Instant createdAt, Instant updatedAt) {
    }

    private final JdbcClient jdbc;
    private final SourceRepository sources;
    private final JobRepository jobs;
    private final HandoverRepository handovers;
    private final AuditService audit;
    private final Mailer mailer;
    private final TenantActivity activity;
    private final ObjectMapper json;
    private final int intervalMinutes;

    public SyncService(JdbcClient jdbc, SourceRepository sources, JobRepository jobs, HandoverRepository handovers,
                       AuditService audit, Mailer mailer, TenantActivity activity, ObjectMapper json,
                       @Value("${baton.sync.interval-minutes:60}") int intervalMinutes) {
        this.jdbc = jdbc;
        this.sources = sources;
        this.jobs = jobs;
        this.handovers = handovers;
        this.audit = audit;
        this.mailer = mailer;
        this.activity = activity;
        this.json = json;
        this.intervalMinutes = intervalMinutes;
    }

    /** In the current workspace: queue a scheduled read for every live source that is due. Returns how many. */
    public int enqueueDue() {
        List<SourceRow> due = sources.dueForAutoSync(intervalMinutes, 25);
        for (SourceRow s : due) {
            // Marked checked at once, so the next tick cannot queue the same source twice while this one runs.
            jdbc.sql("UPDATE source SET last_checked_at = now() WHERE id = :id").param("id", s.id()).update();
            jobs.enqueue(JobKind.INGEST_SOURCE, s.projectId(), null,
                    Map.of("sourceId", s.id().toString(), "scheduled", "true"));
        }
        if (!due.isEmpty()) {
            activity.touchCurrent(); // the job worker only polls workspaces that have had work recently
            log.info("Queued {} scheduled source read(s) in workspace '{}'", due.size(),
                    TenantContext.current().map(t -> t.slug()).orElse("?"));
        }
        return due.size();
    }

    // ───────────── notices ─────────────

    /** A scheduled read found new evidence in a source. */
    public void onChanged(SourceRow source, Change change) {
        audit.record(null, "SOURCE_CHANGE_DETECTED", "source", source.id(), source.projectId(),
                Map.of("added", change.added(), "changed", change.changed(), "removed", change.removed()));
        dismissKindForSource(source.projectId(), "SYNC_FAILED", source.id());
        String message = source.title() + " has new evidence: " + change.describe() + ".";
        for (Map<String, Object> h : openHandovers(source.projectId())) {
            UUID handoverId = (UUID) h.get("id");
            String status = (String) h.get("status");
            Object version = h.get("current_version_no");
            // A handover still in review is reconstructed again by itself; an approved one waits for the lead.
            boolean reconstructing = status.equals("IN_REVIEW") && hasRun(handoverId) && tryReconstruct(source.projectId(), handoverId);
            String advice = reconstructing ? " Baton is reconstructing the handover again; the claims you reviewed are kept."
                    : version != null ? " Open the next version to review it; recipients keep version " + version + " until then."
                    : "";
            boolean created = upsertNotice(handoverId, "SOURCE_CHANGED", message + advice,
                    Map.of("sourceId", source.id().toString(), "sourceTitle", source.title(),
                            "added", change.added(), "changed", change.changed(), "removed", change.removed(),
                            "reconstructing", reconstructing));
            if (created) {
                emailLeads(source.projectId(), (String) h.get("title"), message + advice);
            }
        }
    }

    /** A scheduled read failed: tell the lead once, until a read works again. */
    public void onFailed(SourceRow source, String error) {
        sources.markSyncFailed(source.id(), error);
        audit.record(null, "SOURCE_SYNC_FAILED", "source", source.id(), source.projectId(), Map.of("error", error));
        String message = source.title() + " could not be read, so this handover may be missing new evidence: " + error;
        for (Map<String, Object> h : openHandovers(source.projectId())) {
            boolean created = upsertNotice((UUID) h.get("id"), "SYNC_FAILED", message,
                    Map.of("sourceId", source.id().toString(), "sourceTitle", source.title()));
            if (created) {
                emailLeads(source.projectId(), (String) h.get("title"), message);
            }
        }
    }

    /** A read worked: earlier failure notices about this source no longer apply. */
    public void onRecovered(SourceRow source) {
        dismissKindForSource(source.projectId(), "SYNC_FAILED", source.id());
    }

    private List<Map<String, Object>> openHandovers(UUID projectId) {
        return jdbc.sql("""
                SELECT id, title, status, current_version_no FROM handover
                WHERE project_id = :p AND status IN ('DRAFT', 'IN_REVIEW', 'RECONSTRUCTING', 'APPROVED', 'SHARED')
                """).param("p", projectId).query().listOfRows();
    }

    private boolean hasRun(UUID handoverId) {
        return jdbc.sql("SELECT count(*) FROM extraction_run WHERE handover_id = :h").param("h", handoverId)
                .query(Integer.class).single() > 0;
    }

    /** Reconstructs a handover that is still in review, unless another source is mid-read. Best effort. */
    private boolean tryReconstruct(UUID projectId, UUID handoverId) {
        try {
            boolean busy = jdbc.sql("SELECT count(*) FROM source WHERE project_id = :p AND status IN ('PENDING', 'SYNCING')")
                    .param("p", projectId).query(Integer.class).single() > 0;
            if (busy) {
                return false;
            }
            handovers.transition(handoverId, HandoverStatus.IN_REVIEW,
                    HandoverStateMachine.next(HandoverStatus.IN_REVIEW, HandoverStateMachine.Event.RECONSTRUCT));
            UUID jobId = jobs.enqueue(JobKind.RECONSTRUCT, projectId, null, Map.of("handoverId", handoverId.toString()));
            audit.record(null, "RECONSTRUCT_REQUESTED", "handover", handoverId, projectId,
                    Map.of("jobId", jobId.toString(), "reason", "new evidence from a scheduled sync"));
            return true;
        } catch (RuntimeException e) {
            log.info("Not reconstructing handover {} automatically: {}", handoverId, e.getMessage());
            return false;
        }
    }

    /** Creates or refreshes the open notice. True when it is new (the lead has not been told yet). */
    boolean upsertNotice(UUID handoverId, String kind, String message, Map<String, Object> details) {
        int updated = jdbc.sql("""
                UPDATE handover_notice SET message = :m, details = CAST(:d AS jsonb), updated_at = now()
                WHERE handover_id = :h AND kind = :k AND dismissed_at IS NULL
                """).param("m", message).param("d", json.writeValueAsString(details)).param("h", handoverId)
                .param("k", kind).update();
        if (updated > 0) {
            return false;
        }
        jdbc.sql("""
                INSERT INTO handover_notice (handover_id, kind, message, details)
                VALUES (:h, :k, :m, CAST(:d AS jsonb))
                """).param("h", handoverId).param("k", kind).param("m", message)
                .param("d", json.writeValueAsString(details)).update();
        return true;
    }

    private void dismissKindForSource(UUID projectId, String kind, UUID sourceId) {
        jdbc.sql("""
                UPDATE handover_notice n SET dismissed_at = now()
                FROM handover h
                WHERE n.handover_id = h.id AND h.project_id = :p AND n.kind = :k AND n.dismissed_at IS NULL
                  AND n.details ->> 'sourceId' = :s
                """).param("p", projectId).param("k", kind).param("s", sourceId.toString()).update();
    }

    private void emailLeads(UUID projectId, String handoverTitle, String message) {
        if (!mailer.enabled()) {
            return;
        }
        List<String> to = jdbc.sql("""
                SELECT u.email FROM project_member m JOIN app_user u ON u.id = m.user_id
                WHERE m.project_id = :p AND m.role = 'PROJECT_LEAD'
                """).param("p", projectId).query(String.class).list();
        String slug = TenantContext.current().map(t -> t.slug()).orElse("");
        for (String email : to) {
            mailer.send(email, "Baton: new evidence for " + handoverTitle,
                    message + "\n\nOpen Baton: " + mailer.link("/app/approval?ws=" + slug) + "\n");
        }
    }

    // ───────────── reading and dismissing (lead) ─────────────

    public List<Notice> notices(UUID handoverId) {
        return jdbc.sql("""
                SELECT id, handover_id, kind, message, details::text AS details, created_at, updated_at
                FROM handover_notice WHERE handover_id = :h AND dismissed_at IS NULL ORDER BY updated_at DESC
                """).param("h", handoverId).query((rs, n) -> new Notice(rs.getObject("id", UUID.class),
                rs.getObject("handover_id", UUID.class), rs.getString("kind"), rs.getString("message"),
                json.readValue(rs.getString("details"), new TypeReference<Map<String, Object>>() {
                }), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    public void dismiss(UUID handoverId, UUID noticeId) {
        jdbc.sql("UPDATE handover_notice SET dismissed_at = now() WHERE id = :id AND handover_id = :h AND dismissed_at IS NULL")
                .param("id", noticeId).param("h", handoverId).update();
    }

    /** The lead acted (reconstructed or reopened): "new evidence" notices are answered. */
    public void dismissChangeNotices(UUID handoverId) {
        jdbc.sql("UPDATE handover_notice SET dismissed_at = now() WHERE handover_id = :h AND kind = 'SOURCE_CHANGED' AND dismissed_at IS NULL")
                .param("h", handoverId).update();
    }
}
