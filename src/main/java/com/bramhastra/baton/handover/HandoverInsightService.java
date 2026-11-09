package com.bramhastra.baton.handover;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.identity.AppUserRepository;
import com.bramhastra.baton.security.CurrentUser;

/** For the lead: has the handover drifted from what was approved, and how far are the successors? */
@Service
public class HandoverInsightService {

    private final HandoverAccess access;
    private final ApprovalService approval;
    private final ShareService shares;
    private final AppUserRepository users;
    private final JdbcClient jdbc;

    public HandoverInsightService(HandoverAccess access, ApprovalService approval, ShareService shares,
                                  AppUserRepository users, JdbcClient jdbc) {
        this.access = access;
        this.approval = approval;
        this.shares = shares;
        this.users = users;
        this.jdbc = jdbc;
    }

    // ───────────── living handovers ─────────────

    public record Drift(Integer versionNo, Instant approvedAt, String status, List<ApprovalService.Change> changes,
                        int sourcesUpdated, int answersSince, int recipientsOnOlderVersion) {
    }

    /** What has changed since the latest approved version: claims, re-synced sources, new exit answers. */
    @Transactional(readOnly = true)
    public Drift drift(CurrentUser me, UUID handoverId) {
        Handover h = access.requireLead(me, handoverId);
        if (h.currentVersionNo() == null) {
            return new Drift(null, null, h.status().name(), List.of(), 0, 0, 0);
        }
        Instant approvedAt = jdbc.sql("SELECT approved_at FROM handover_version WHERE handover_id = :h AND version_no = :n")
                .param("h", h.id()).param("n", h.currentVersionNo()).query(Timestamp.class).single().toInstant();
        Timestamp since = Timestamp.from(approvedAt);
        int sources = jdbc.sql("""
                SELECT count(*) FROM source WHERE project_id = :p AND type <> 'TESTIMONY' AND last_synced_at > :t
                """).param("p", h.projectId()).param("t", since).query(Integer.class).single();
        int answers = jdbc.sql("""
                SELECT count(*) FROM gap_question WHERE handover_id = :h AND status = 'ANSWERED' AND answered_at > :t
                """).param("h", h.id()).param("t", since).query(Integer.class).single();
        int older = jdbc.sql("""
                SELECT count(*) FROM handover_share s JOIN handover_version v ON v.id = s.handover_version_id
                WHERE v.handover_id = :h AND v.version_no < :n AND s.revoked_at IS NULL AND s.expires_at > now()
                """).param("h", h.id()).param("n", h.currentVersionNo()).query(Integer.class).single();
        return new Drift(h.currentVersionNo(), approvedAt, h.status().name(),
                approval.changes(h, h.currentVersionNo(), 0), sources, answers, older);
    }

    // ───────────── successor progress ─────────────

    /** When each phase of the checklist is due, counted from the day the version was shared with the person. */
    static final Map<String, Duration> DUE = Map.of("DAY1", Duration.ofDays(1), "WEEK1", Duration.ofDays(7),
            "MONTH1", Duration.ofDays(30));

    public record Phase(String phase, int done, int total, Instant dueAt, boolean overdue) {
    }

    public record Successor(UUID shareId, UUID recipientId, String name, String roleLabel, int versionNo,
                            Instant sharedAt, Instant lastViewedAt, Instant acknowledgedAt, int percent,
                            List<Phase> phases, List<String> overdueItems, int openQuestions, int answeredQuestions) {
    }

    @Transactional(readOnly = true)
    public List<Successor> progress(CurrentUser me, UUID handoverId) {
        Handover h = access.requireLead(me, handoverId);
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT s.id, s.handover_version_id, s.recipient_user_id, s.role_label, s.created_at, s.expires_at,
                       s.acknowledged_at, s.previous_version_no, v.version_no,
                       (SELECT max(a.at) FROM audit_log a WHERE a.action = 'SHARE_VIEWED' AND a.entity_id = s.id) AS viewed
                FROM handover_share s JOIN handover_version v ON v.id = s.handover_version_id
                WHERE v.handover_id = :h AND s.revoked_at IS NULL AND s.expires_at > now()
                ORDER BY s.created_at
                """).param("h", h.id()).query().listOfRows();
        Instant now = Instant.now();
        List<Successor> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            UUID recipient = (UUID) r.get("recipient_user_id");
            var user = users.findById(recipient).orElse(null);
            if (user == null) {
                continue;
            }
            Instant shared = ((Timestamp) r.get("created_at")).toInstant();
            ShareService.Share share = new ShareService.Share((UUID) r.get("id"), (UUID) r.get("handover_version_id"),
                    recipient, (String) r.get("role_label"), ((Timestamp) r.get("expires_at")).toInstant(), null,
                    r.get("acknowledged_at") == null ? null : ((Timestamp) r.get("acknowledged_at")).toInstant(),
                    h.projectId(), h.id(), (Integer) r.get("previous_version_no"));
            // Counted over what that person can actually see.
            List<ShareService.ChecklistItem> items = shares.checklist(CurrentUser.of(user), share);
            List<Phase> phases = new ArrayList<>();
            List<String> overdue = new ArrayList<>();
            int done = 0;
            for (String p : List.of("DAY1", "WEEK1", "MONTH1")) {
                List<ShareService.ChecklistItem> mine = items.stream().filter(i -> i.phase().equals(p)).toList();
                int d = (int) mine.stream().filter(ShareService.ChecklistItem::done).count();
                Instant due = shared.plus(DUE.get(p));
                boolean late = now.isAfter(due) && d < mine.size();
                if (late) {
                    mine.stream().filter(i -> !i.done()).limit(5).forEach(i -> overdue.add(i.text()));
                }
                phases.add(new Phase(p, d, mine.size(), due, late));
                done += d;
            }
            Map<String, Object> q = jdbc.sql("""
                    SELECT count(*) FILTER (WHERE status IN ('OPEN', 'UNANSWERED')) AS open,
                           count(*) FILTER (WHERE status IN ('ANSWERED', 'SKIPPED')) AS answered
                    FROM gap_question WHERE asked_by = :u AND handover_id = :h
                    """).param("u", recipient).param("h", h.id()).query().singleRow();
            out.add(new Successor((UUID) r.get("id"), recipient, user.getDisplayName(), (String) r.get("role_label"),
                    ((Number) r.get("version_no")).intValue(), shared,
                    r.get("viewed") == null ? null : ((Timestamp) r.get("viewed")).toInstant(), share.acknowledgedAt(),
                    items.isEmpty() ? 0 : Math.round(100f * done / items.size()), phases, overdue,
                    ((Number) q.get("open")).intValue(), ((Number) q.get("answered")).intValue()));
        }
        return out;
    }
}
