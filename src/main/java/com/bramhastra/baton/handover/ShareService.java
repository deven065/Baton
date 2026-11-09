package com.bramhastra.baton.handover;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.identity.AppUser;
import com.bramhastra.baton.identity.AppUserRepository;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.mail.Mailer;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.tenant.Tenant;
import com.bramhastra.baton.tenant.TenantContext;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Sharing an approved, frozen version with the people taking over, and what they see. A recipient only ever sees
 * the frozen snapshot, filtered by their own clearance and groups at read time; every view is audited.
 */
@Service
public class ShareService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final Duration DEFAULT_TTL = Duration.ofDays(30);
    private static final Duration MAX_TTL = Duration.ofDays(90);

    private final JdbcClient jdbc;
    private final HandoverAccess access;
    private final HandoverRepository handovers;
    private final AppUserRepository users;
    private final AccessPolicy policy;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Mailer mailer;
    private final ApprovalService approval;

    public ShareService(JdbcClient jdbc, HandoverAccess access, HandoverRepository handovers, AppUserRepository users,
                        AccessPolicy policy, AuditService audit, ObjectMapper json, Mailer mailer, ApprovalService approval) {
        this.mailer = mailer;
        this.approval = approval;
        this.jdbc = jdbc;
        this.access = access;
        this.handovers = handovers;
        this.users = users;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
    }

    // ───────────── lead side ─────────────

    public record ShareRow(UUID id, UUID recipientId, String recipientName, String recipientEmail, Sensitivity clearance,
                           String roleLabel, int versionNo, Instant createdAt, Instant expiresAt, Instant revokedAt,
                           Instant acknowledgedAt, Instant lastViewedAt, String state) {
    }

    @Transactional
    public ShareRow create(CurrentUser me, UUID handoverId, UUID recipientId, String roleLabel, Instant expiresAt) {
        Handover h = access.requireLead(me, handoverId);
        if (h.currentVersionNo() == null || (h.status() != HandoverStatus.APPROVED && h.status() != HandoverStatus.SHARED)) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Approve the handover before sharing it");
        }
        AppUser r = users.findById(recipientId).filter(AppUser::isActive)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                        Map.of("recipientId", "No active person with that id in this workspace")));
        if (r.getId().equals(h.departingUserId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of("recipientId", "The person leaving cannot be a recipient of their own handover"));
        }
        Instant now = Instant.now();
        Instant exp = expiresAt == null ? now.plus(DEFAULT_TTL) : expiresAt;
        if (!exp.isAfter(now) || exp.isAfter(now.plus(MAX_TTL))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of("expiresAt", "Choose a date within the next 90 days"));
        }
        UUID versionId = jdbc.sql("SELECT id FROM handover_version WHERE handover_id = :h AND version_no = :n")
                .param("h", h.id()).param("n", h.currentVersionNo()).query(UUID.class).single();
        boolean dup = jdbc.sql("""
                SELECT count(*) FROM handover_share
                WHERE handover_version_id = :v AND recipient_user_id = :r AND revoked_at IS NULL AND expires_at > now()
                """).param("v", versionId).param("r", r.getId()).query(Integer.class).single() > 0;
        if (dup) {
            throw new ApiException(ErrorCode.ALREADY_EXISTS, "This person already has an active share of this version");
        }
        UUID id = jdbc.sql("""
                INSERT INTO handover_share (handover_version_id, recipient_user_id, role_label, created_by, expires_at)
                VALUES (:v, :r, :label, :me, :exp) RETURNING id
                """).param("v", versionId).param("r", r.getId())
                .param("label", roleLabel == null || roleLabel.isBlank() ? "Successor" : roleLabel.strip())
                .param("me", me.id()).param("exp", java.sql.Timestamp.from(exp)).query(UUID.class).single();
        if (h.status() == HandoverStatus.APPROVED) {
            handovers.transition(h.id(), h.status(),
                    HandoverStateMachine.next(h.status(), HandoverStateMachine.Event.FIRST_SHARE));
        }
        audit.record(me.id(), "SHARE_CREATED", "handover_share", id, h.projectId(),
                Map.of("recipient", r.getId().toString(), "version", h.currentVersionNo()));
        Tenant t = TenantContext.require();
        // The email names the handover and who shared it, nothing from inside it.
        mailer.send(r.getEmail(), me.displayName() + " shared a handover with you on Baton", """
                Hi %s,

                %s shared "%s" with you as %s. It is what you need to pick up the work: where things stand,
                who to ask, and a checklist for your first days.

                Open it here (sign in to the %s workspace): %s

                The share expires on %s.
                """.formatted(r.getDisplayName(), me.displayName(), h.title(),
                roleLabel == null || roleLabel.isBlank() ? "Successor" : roleLabel.strip(), t.slug(),
                mailer.link("/login?ws=" + t.slug() + "&next=/handover"),
                java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy").withZone(java.time.ZoneOffset.UTC).format(exp)));
        return list(me, handoverId).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<ShareRow> list(CurrentUser me, UUID handoverId) {
        access.requireLead(me, handoverId);
        return jdbc.sql("""
                SELECT s.id, s.recipient_user_id, u.display_name, u.email, u.clearance, s.role_label, v.version_no,
                       s.created_at, s.expires_at, s.revoked_at, s.acknowledged_at,
                       (SELECT max(a.at) FROM audit_log a WHERE a.action = 'SHARE_VIEWED' AND a.entity_id = s.id) AS viewed
                FROM handover_share s JOIN handover_version v ON v.id = s.handover_version_id
                JOIN app_user u ON u.id = s.recipient_user_id
                WHERE v.handover_id = :h ORDER BY s.created_at DESC
                """).param("h", handoverId).query((rs, n) -> {
                    Instant revoked = ts(rs.getTimestamp("revoked_at"));
                    Instant expires = ts(rs.getTimestamp("expires_at"));
                    Instant ack = ts(rs.getTimestamp("acknowledged_at"));
                    Instant viewed = ts(rs.getTimestamp("viewed"));
                    String state = revoked != null ? "REVOKED" : expires.isBefore(Instant.now()) ? "EXPIRED"
                            : ack != null ? "ACKNOWLEDGED" : viewed != null ? "OPENED" : "SENT";
                    return new ShareRow(rs.getObject("id", UUID.class), rs.getObject("recipient_user_id", UUID.class),
                            rs.getString("display_name"), rs.getString("email"), Sensitivity.ofLevel(rs.getShort("clearance")),
                            rs.getString("role_label"), rs.getInt("version_no"), ts(rs.getTimestamp("created_at")), expires,
                            revoked, ack, viewed, state);
                }).list();
    }

    @Transactional
    public void revoke(CurrentUser me, UUID shareId) {
        UUID handoverId = handoverOf(shareId);
        Handover h = access.requireLead(me, handoverId);
        int n = jdbc.sql("UPDATE handover_share SET revoked_at = now() WHERE id = :s AND revoked_at IS NULL")
                .param("s", shareId).update();
        if (n > 0) {
            audit.record(me.id(), "SHARE_REVOKED", "handover_share", shareId, h.projectId(), Map.of());
        }
    }

    private UUID handoverOf(UUID shareId) {
        return jdbc.sql("""
                SELECT v.handover_id FROM handover_share s JOIN handover_version v ON v.id = s.handover_version_id
                WHERE s.id = :s
                """).param("s", shareId).query(UUID.class).optional().orElseThrow(ApiException::notFound);
    }

    private static Instant ts(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    // ───────────── recipient side ─────────────

    public record MyShare(UUID id, String handoverTitle, String projectName, String departingName, int versionNo,
                          String roleLabel, Instant expiresAt, Instant acknowledgedAt) {
    }

    /** Active shares addressed to me (expired and revoked ones disappear). */
    @Transactional(readOnly = true)
    public List<MyShare> mine(CurrentUser me) {
        return jdbc.sql("""
                SELECT s.id, h.title, p.name, u.display_name, v.version_no, s.role_label, s.expires_at, s.acknowledged_at
                FROM handover_share s JOIN handover_version v ON v.id = s.handover_version_id
                JOIN handover h ON h.id = v.handover_id JOIN project p ON p.id = h.project_id
                JOIN app_user u ON u.id = h.departing_user_id
                WHERE s.recipient_user_id = :me AND s.revoked_at IS NULL AND s.expires_at > now()
                ORDER BY s.created_at DESC
                """).param("me", me.id())
                .query((rs, n) -> new MyShare(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("name"),
                        rs.getString("display_name"), rs.getInt("version_no"), rs.getString("role_label"),
                        ts(rs.getTimestamp("expires_at")), ts(rs.getTimestamp("acknowledged_at"))))
                .list();
    }

    public record ChecklistItem(UUID id, String phase, int position, String text, String claimId, boolean done) {
    }

    public record RecipientView(UUID shareId, String roleLabel, Instant expiresAt, Instant acknowledgedAt, int versionNo,
                                String sha256, Instant approvedAt, String approvedBy, Map<String, Object> handover,
                                List<Map<String, Object>> claims, int hiddenCount, List<Map<String, Object>> timeline,
                                List<ChecklistItem> checklist, Integer previousVersionNo, List<ApprovalService.Change> changes) {
    }

    public record Share(UUID id, UUID versionId, UUID recipient, String roleLabel, Instant expiresAt,
                        Instant revokedAt, Instant acknowledgedAt, UUID projectId, UUID handoverId,
                        Integer previousVersionNo) {
    }

    /** Only the recipient, only while active. Others get 404 so a share id reveals nothing. */
    Share requireMine(CurrentUser me, UUID shareId) {
        Share s = jdbc.sql("""
                SELECT s.id, s.handover_version_id, s.recipient_user_id, s.role_label, s.expires_at, s.revoked_at,
                       s.acknowledged_at, h.project_id, h.id AS handover_id, s.previous_version_no
                FROM handover_share s JOIN handover_version v ON v.id = s.handover_version_id
                JOIN handover h ON h.id = v.handover_id WHERE s.id = :s
                """).param("s", shareId)
                .query((rs, n) -> new Share(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), ts(rs.getTimestamp(5)), ts(rs.getTimestamp(6)),
                        ts(rs.getTimestamp(7)), rs.getObject(8, UUID.class), rs.getObject(9, UUID.class),
                        (Integer) rs.getObject(10)))
                .optional().filter(x -> x.recipient().equals(me.id())).orElseThrow(ApiException::notFound);
        if (s.revokedAt() != null || s.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(ErrorCode.SHARE_INACTIVE,
                    s.revokedAt() != null ? "This handover share was revoked" : "This handover share has expired");
        }
        return s;
    }

    /** The frozen version as this recipient may see it: claims filtered by their own clearance and groups. */
    public record Visible(Share share, int versionNo, String sha256, Instant approvedAt, String approvedBy,
                          Map<String, Object> snapshot, List<Map<String, Object>> claims, Set<String> ids, int total) {
    }

    @SuppressWarnings("unchecked")
    public Visible visible(CurrentUser me, UUID shareId) {
        Share s = requireMine(me, shareId);
        Map<String, Object> v = jdbc.sql("""
                SELECT v.version_no, v.sha256, v.approved_at, u.display_name, v.snapshot::text AS snapshot
                FROM handover_version v JOIN app_user u ON u.id = v.approved_by WHERE v.id = :v
                """).param("v", s.versionId()).query().singleRow();
        Map<String, Object> snapshot = json.readValue((String) v.get("snapshot"), MAP);
        List<Map<String, Object>> all = (List<Map<String, Object>>) snapshot.get("claims");
        List<Map<String, Object>> visible = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> c : all) {
            if (canSee(me, c)) {
                visible.add(c);
                ids.add((String) c.get("id"));
            }
        }
        return new Visible(s, ((Number) v.get("version_no")).intValue(), (String) v.get("sha256"),
                ((java.sql.Timestamp) v.get("approved_at")).toInstant(), (String) v.get("display_name"),
                snapshot, visible, ids, all.size());
    }

    @SuppressWarnings("unchecked")
    boolean canSee(CurrentUser me, Map<String, Object> claim) {
        Sensitivity level = Sensitivity.ofLevel(((Number) claim.get("sensitivity")).intValue());
        return policy.canSee(me, level, (List<String>) claim.get("aclGroups"));
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public RecipientView view(CurrentUser me, UUID shareId) {
        Visible vis = visible(me, shareId);
        Share s = vis.share();
        Map<String, Object> snapshot = vis.snapshot();
        List<Map<String, Object>> timeline = ((List<Map<String, Object>>) snapshot.get("timeline")).stream()
                .filter(t -> vis.ids().contains((String) t.get("claimId"))).toList();
        List<ChecklistItem> checklist = checklist(me, s);
        // Moved here from an older version: what changed, limited to what this person may see.
        List<ApprovalService.Change> changes = List.of();
        if (s.previousVersionNo() != null) {
            Handover h = handovers.findById(s.handoverId()).orElseThrow(ApiException::notFound);
            changes = approval.changes(h, s.previousVersionNo(), vis.versionNo(), c -> canSee(me, c));
        }
        audit.record(me.id(), "SHARE_VIEWED", "handover_share", s.id(), s.projectId(),
                Map.of("visible", vis.claims().size(), "hidden", vis.total() - vis.claims().size()));
        Map<String, Object> handover = Map.of("title", snapshot.get("title"), "scenario", snapshot.get("scenario"),
                "departingName", snapshot.get("departingName"), "project", snapshot.get("project"));
        return new RecipientView(s.id(), s.roleLabel(), s.expiresAt(), s.acknowledgedAt(), vis.versionNo(), vis.sha256(),
                vis.approvedAt(), vis.approvedBy(), handover, vis.claims(), vis.total() - vis.claims().size(), timeline,
                checklist, s.previousVersionNo(), changes);
    }

    /** Checklist items of a share's version that its recipient may see, with their progress. */
    List<ChecklistItem> checklist(CurrentUser viewer, Share s) {
        return jdbc.sql("""
                SELECT i.id, i.phase, i.position, i.text, i.claim_id, i.sensitivity, i.acl_groups,
                       (p.share_id IS NOT NULL) AS done
                FROM checklist_item i LEFT JOIN checklist_progress p ON p.checklist_item_id = i.id AND p.share_id = :s
                WHERE i.handover_version_id = :v ORDER BY i.phase, i.position
                """).param("s", s.id()).param("v", s.versionId()).query((rs, n) -> {
                    Sensitivity level = Sensitivity.ofLevel(rs.getShort("sensitivity"));
                    List<String> acl = com.bramhastra.baton.source.SourceRepository.textArray(rs.getArray("acl_groups"));
                    if (!policy.canSee(viewer, level, acl)) {
                        return null;
                    }
                    UUID claim = rs.getObject("claim_id", UUID.class);
                    return new ChecklistItem(rs.getObject("id", UUID.class), rs.getString("phase"), rs.getInt("position"),
                            rs.getString("text"), claim == null ? null : claim.toString(), rs.getBoolean("done"));
                }).list().stream().filter(java.util.Objects::nonNull).toList();
    }

    /**
     * After a new version is approved: moves every active recipient of an older version onto it, keeping their
     * view name and expiry. Their old share is revoked; the new one remembers which version they came from.
     */
    @Transactional
    public int moveToLatest(CurrentUser me, UUID handoverId) {
        Handover h = access.requireLead(me, handoverId);
        if (h.currentVersionNo() == null || (h.status() != HandoverStatus.APPROVED && h.status() != HandoverStatus.SHARED)) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Approve the new version first");
        }
        UUID latest = jdbc.sql("SELECT id FROM handover_version WHERE handover_id = :h AND version_no = :n")
                .param("h", h.id()).param("n", h.currentVersionNo()).query(UUID.class).single();
        List<Map<String, Object>> old = jdbc.sql("""
                SELECT s.id, s.recipient_user_id, s.role_label, s.expires_at, v.version_no
                FROM handover_share s JOIN handover_version v ON v.id = s.handover_version_id
                WHERE v.handover_id = :h AND v.version_no < :n AND s.revoked_at IS NULL AND s.expires_at > now()
                """).param("h", h.id()).param("n", h.currentVersionNo()).query().listOfRows();
        Tenant t = TenantContext.require();
        int moved = 0;
        for (Map<String, Object> row : old) {
            UUID recipient = (UUID) row.get("recipient_user_id");
            boolean already = jdbc.sql("""
                    SELECT count(*) FROM handover_share WHERE handover_version_id = :v AND recipient_user_id = :r
                    AND revoked_at IS NULL AND expires_at > now()
                    """).param("v", latest).param("r", recipient).query(Integer.class).single() > 0;
            if (!already) {
                jdbc.sql("""
                        INSERT INTO handover_share (handover_version_id, recipient_user_id, role_label, created_by, expires_at, previous_version_no)
                        VALUES (:v, :r, :l, :me, :e, :prev)
                        """).param("v", latest).param("r", recipient).param("l", row.get("role_label"))
                        .param("me", me.id()).param("e", row.get("expires_at")).param("prev", row.get("version_no")).update();
                users.findById(recipient).filter(AppUser::isActive).ifPresent(r -> mailer.send(r.getEmail(),
                        "An updated handover is ready: " + h.title(), """
                        Hi %s,

                        %s approved version %d of "%s". Open it to see what changed since the version you had:
                        %s
                        """.formatted(r.getDisplayName(), me.displayName(), h.currentVersionNo(), h.title(),
                        mailer.link("/login?ws=" + t.slug() + "&next=/handover"))));
                moved++;
            }
            jdbc.sql("UPDATE handover_share SET revoked_at = now() WHERE id = :s").param("s", row.get("id")).update();
        }
        if (!old.isEmpty() && h.status() == HandoverStatus.APPROVED) {
            handovers.transition(h.id(), h.status(),
                    HandoverStateMachine.next(h.status(), HandoverStateMachine.Event.FIRST_SHARE));
        }
        audit.record(me.id(), "SHARES_MOVED", "handover", h.id(), h.projectId(),
                Map.of("toVersion", h.currentVersionNo(), "moved", moved));
        return moved;
    }

    @Transactional
    public void setDone(CurrentUser me, UUID shareId, UUID itemId, boolean done) {
        Share s = requireMine(me, shareId);
        boolean belongs = jdbc.sql("SELECT count(*) FROM checklist_item WHERE id = :i AND handover_version_id = :v")
                .param("i", itemId).param("v", s.versionId()).query(Integer.class).single() > 0;
        if (!belongs) {
            throw ApiException.notFound();
        }
        if (done) {
            jdbc.sql("""
                    INSERT INTO checklist_progress (share_id, checklist_item_id) VALUES (:s, :i) ON CONFLICT DO NOTHING
                    """).param("s", s.id()).param("i", itemId).update();
        } else {
            jdbc.sql("DELETE FROM checklist_progress WHERE share_id = :s AND checklist_item_id = :i")
                    .param("s", s.id()).param("i", itemId).update();
        }
    }

    @Transactional
    public Instant acknowledge(CurrentUser me, UUID shareId) {
        Share s = requireMine(me, shareId);
        if (s.acknowledgedAt() != null) {
            return s.acknowledgedAt();
        }
        jdbc.sql("UPDATE handover_share SET acknowledged_at = now() WHERE id = :s").param("s", s.id()).update();
        audit.record(me.id(), "SHARE_ACKNOWLEDGED", "handover_share", s.id(), s.projectId(), Map.of());
        return Instant.now();
    }
}
