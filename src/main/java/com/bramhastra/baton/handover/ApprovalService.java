package com.bramhastra.baton.handover;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.source.SourceRepository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The approval gate and frozen versions. Approval is checked on the server, freezes every claim a recipient could
 * see (with its verified quotes) into an immutable snapshot hashed with SHA-256, and generates the checklist.
 */
@Service
public class ApprovalService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final HandoverAccess access;
    private final HandoverRepository handovers;
    private final AuditService audit;
    private final ObjectMapper json;

    public ApprovalService(JdbcClient jdbc, HandoverAccess access, HandoverRepository handovers, AuditService audit,
                           ObjectMapper json) {
        this.jdbc = jdbc;
        this.access = access;
        this.handovers = handovers;
        this.audit = audit;
        this.json = json;
    }

    // ───────────── gate ─────────────

    public record Check(String key, boolean ok, boolean blocking, String title, String detail, int count) {
    }

    public record Gate(boolean canApprove, List<Check> checks, HandoverStatus status, Integer currentVersionNo) {
    }

    @Transactional(readOnly = true)
    public Gate gate(CurrentUser me, UUID handoverId) {
        Handover h = access.requireLead(me, handoverId);
        List<Check> checks = checks(h);
        boolean blocked = checks.stream().anyMatch(c -> c.blocking() && !c.ok());
        return new Gate(!blocked && h.status() == HandoverStatus.IN_REVIEW, checks, h.status(), h.currentVersionNo());
    }

    private int count(String where, UUID h) {
        return jdbc.sql("SELECT count(*) FROM claim WHERE handover_id = :h AND " + where).param("h", h)
                .query(Integer.class).single();
    }

    private List<Check> checks(Handover h) {
        int total = count("review_state <> 'REJECTED' AND status <> 'STALE'", h.id());
        int conflicts = count("status = 'CONFLICTING' AND review_state <> 'REJECTED'", h.id());
        int critical = count("critical AND review_state IN ('PENDING', 'NEEDS_INFO')", h.id());
        int inferred = count("status = 'INFERRED' AND review_state IN ('PENDING', 'NEEDS_INFO')", h.id());
        int pending = count("review_state IN ('PENDING', 'NEEDS_INFO')", h.id());
        int unverified = jdbc.sql("""
                SELECT count(*) FROM claim c WHERE c.handover_id = :h AND c.status = 'VERIFIED'
                AND NOT EXISTS (SELECT 1 FROM evidence e WHERE e.claim_id = c.id AND e.valid)
                """).param("h", h.id()).query(Integer.class).single();
        int openGaps = jdbc.sql("""
                SELECT count(*) FROM gap_question WHERE handover_id = :h AND status IN ('OPEN', 'UNANSWERED')
                """).param("h", h.id()).query(Integer.class).single();
        List<Check> out = new ArrayList<>();
        out.add(new Check("claims", total > 0, true, total > 0 ? total + " claims to hand over" : "Nothing to hand over yet",
                total > 0 ? "Rejected and stale claims are left out" : "Run reconstruction first", total));
        out.add(new Check("quotes", unverified == 0, true,
                unverified == 0 ? "Every verified claim has a matching quote" : unverified + " verified claims lack a matching quote",
                "Checked by code, never by the model", unverified));
        out.add(new Check("conflicts", conflicts == 0, true,
                conflicts == 0 ? "No unresolved conflicts" : conflicts + " conflicting claims",
                "Sources that disagree must be resolved", conflicts));
        out.add(new Check("critical", critical == 0, true,
                critical == 0 ? "Critical claims reviewed" : critical + " critical claims not reviewed",
                "Risks, access to transfer and blockers need a decision", critical));
        out.add(new Check("inferred", inferred == 0, true,
                inferred == 0 ? "Inferred claims decided" : inferred + " inferred claims need a decision",
                "AI reasoning without a quote is never shown as fact", inferred));
        out.add(new Check("gaps", openGaps == 0, false,
                openGaps == 0 ? "Exit interview complete" : openGaps + " exit questions unanswered",
                "Recommended, not required (a sudden exit may never answer)", openGaps));
        out.add(new Check("pending", pending == 0, false,
                pending == 0 ? "Every claim reviewed" : pending + " claims still waiting for review",
                "Pending claims are included as they are", pending));
        return out;
    }

    // ───────────── approve ─────────────

    public record VersionView(int versionNo, String sha256, UUID approvedBy, String approvedByName, Instant approvedAt,
                              int claims, int checklistItems) {
    }

    @Transactional
    public VersionView approve(CurrentUser me, UUID handoverId) {
        Handover h = access.requireLead(me, handoverId);
        HandoverStatus next = HandoverStateMachine.next(h.status(), HandoverStateMachine.Event.APPROVE);
        List<Check> failed = checks(h).stream().filter(c -> c.blocking() && !c.ok()).toList();
        if (!failed.isEmpty()) {
            throw new ApiException(ErrorCode.APPROVAL_BLOCKED, "Resolve the blockers before approving",
                    failed.stream().map(Check::title).toList());
        }
        Map<String, Object> snapshot = snapshot(h);
        String canonical = json.writeValueAsString(snapshot);
        String sha = sha256(canonical);
        int versionNo = jdbc.sql("SELECT COALESCE(max(version_no), 0) + 1 FROM handover_version WHERE handover_id = :h")
                .param("h", h.id()).query(Integer.class).single();
        UUID versionId = jdbc.sql("""
                INSERT INTO handover_version (handover_id, version_no, snapshot, sha256, approved_by)
                VALUES (:h, :n, CAST(:s AS jsonb), :sha, :me) RETURNING id
                """).param("h", h.id()).param("n", versionNo).param("s", canonical).param("sha", sha)
                .param("me", me.id()).query(UUID.class).single();
        int items = writeChecklist(versionId, snapshot);
        handovers.transition(h.id(), h.status(), next);
        jdbc.sql("UPDATE handover SET current_version_no = :n WHERE id = :h").param("n", versionNo).param("h", h.id()).update();
        audit.record(me.id(), "HANDOVER_APPROVED", "handover", h.id(), h.projectId(),
                Map.of("version", versionNo, "sha256", sha));
        @SuppressWarnings("unchecked")
        int claims = ((List<Object>) snapshot.get("claims")).size();
        return new VersionView(versionNo, sha, me.id(), me.displayName(), Instant.now(), claims, items);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Everything a recipient may need, in a fixed order so the hash is reproducible. */
    private Map<String, Object> snapshot(Handover h) {
        Map<String, Object> project = jdbc.sql("SELECT name, description FROM project WHERE id = :p")
                .param("p", h.projectId()).query().singleRow();
        List<Map<String, Object>> claims = jdbc.sql("""
                SELECT id, type, title, body, subject, status, confidence, sensitivity, acl_groups, critical, review_state
                FROM claim WHERE handover_id = :h AND review_state <> 'REJECTED' AND status <> 'STALE'
                ORDER BY id
                """).param("h", h.id()).query((rs, n) -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("id", rs.getObject("id", UUID.class).toString());
                    c.put("type", rs.getString("type"));
                    c.put("title", rs.getString("title"));
                    c.put("body", rs.getString("body"));
                    c.put("subject", rs.getString("subject"));
                    c.put("status", rs.getString("status"));
                    c.put("confidence", rs.getBigDecimal("confidence").toPlainString());
                    c.put("sensitivity", rs.getShort("sensitivity"));
                    c.put("aclGroups", SourceRepository.textArray(rs.getArray("acl_groups")).stream().sorted().toList());
                    c.put("critical", rs.getBoolean("critical"));
                    c.put("reviewState", rs.getString("review_state"));
                    return c;
                }).list();
        Map<String, List<Map<String, Object>>> evidence = new HashMap<>();
        if (!claims.isEmpty()) {
            jdbc.sql("""
                    SELECT e.claim_id, e.id, e.quote, d.title, d.authored_at, s.type, s.title AS source_title
                    FROM evidence e JOIN chunk k ON k.id = e.chunk_id JOIN document d ON d.id = k.document_id
                    JOIN source s ON s.id = d.source_id
                    WHERE e.valid AND e.claim_id IN (SELECT id FROM claim WHERE handover_id = :h)
                    ORDER BY e.id
                    """).param("h", h.id()).query((rs, n) -> {
                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("quote", rs.getString("quote"));
                        e.put("sourceType", rs.getString("type"));
                        e.put("source", rs.getString("source_title"));
                        e.put("document", rs.getString("title"));
                        Timestamp at = rs.getTimestamp("authored_at");
                        e.put("date", at == null ? null : at.toInstant().toString());
                        evidence.computeIfAbsent(rs.getObject("claim_id", UUID.class).toString(), k -> new ArrayList<>()).add(e);
                        return null;
                    }).list();
        }
        claims.forEach(c -> c.put("evidence", evidence.getOrDefault((String) c.get("id"), List.of())));
        List<Map<String, Object>> timeline = jdbc.sql("""
                SELECT t.at, t.kind, t.title, t.claim_id FROM timeline_event t JOIN claim c ON c.id = t.claim_id
                WHERE t.handover_id = :h AND c.review_state <> 'REJECTED' AND c.status <> 'STALE'
                ORDER BY t.at, t.claim_id
                """).param("h", h.id()).query((rs, n) -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("at", rs.getTimestamp("at").toInstant().toString());
                    t.put("kind", rs.getString("kind"));
                    t.put("title", rs.getString("title"));
                    t.put("claimId", rs.getObject("claim_id", UUID.class).toString());
                    return t;
                }).list();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("format", 1);
        s.put("handoverId", h.id().toString());
        s.put("title", h.title());
        s.put("scenario", h.scenario());
        s.put("departingName", h.departingName());
        s.put("project", Map.of("name", project.get("name"),
                "description", Objects.toString(project.get("description"), "")));
        s.put("claims", claims);
        s.put("timeline", timeline);
        return s;
    }

    /** Deterministic checklist from claim types (no AI): what to do on day 1, in week 1 and in month 1. */
    private int writeChecklist(UUID versionId, Map<String, Object> snapshot) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> claims = (List<Map<String, Object>>) snapshot.get("claims");
        record Item(String phase, String text, Map<String, Object> claim) {
        }
        List<Item> items = new ArrayList<>();
        for (Map<String, Object> c : claims) {
            String t = (String) c.get("title");
            boolean critical = Boolean.TRUE.equals(c.get("critical"));
            switch ((String) c.get("type")) {
                case "ACCESS" -> items.add(new Item("DAY1", "Get access transferred: " + t, c));
                case "PROCESS" -> items.add(new Item("DAY1", "Learn how it’s done: " + t, c));
                case "RISK" -> items.add(new Item(critical ? "DAY1" : "MONTH1", "Check this risk: " + t, c));
                case "BLOCKER" -> items.add(new Item("WEEK1", "Unblock: " + t, c));
                case "OPEN_WORK" -> items.add(new Item("WEEK1", "Pick up: " + t, c));
                case "CONTACT", "OWNER" -> items.add(new Item("WEEK1", "Know who to ask: " + t, c));
                case "DEPENDENCY" -> items.add(new Item("WEEK1", "Understand the dependency: " + t, c));
                case "RECURRING_ISSUE" -> items.add(new Item("MONTH1", "Watch for: " + t, c));
                case "OPEN_QUESTION" -> items.add(new Item("MONTH1", "Settle: " + t, c));
                default -> { /* decisions are context, not tasks */ }
            }
        }
        Map<String, Integer> position = new HashMap<>();
        for (Item i : items) {
            String text = i.text().length() > 500 ? i.text().substring(0, 497) + "…" : i.text();
            @SuppressWarnings("unchecked")
            List<String> acl = (List<String>) i.claim().get("aclGroups");
            jdbc.sql("""
                    INSERT INTO checklist_item (handover_version_id, phase, position, text, claim_id, sensitivity, acl_groups)
                    VALUES (:v, :p, :pos, :t, :c, :s, CAST(:acl AS text[]))
                    """).param("v", versionId).param("p", i.phase()).param("pos", position.merge(i.phase(), 1, Integer::sum))
                    .param("t", text).param("c", UUID.fromString((String) i.claim().get("id")))
                    .param("s", ((Number) i.claim().get("sensitivity")).shortValue())
                    .param("acl", com.bramhastra.baton.security.AccessPolicy.toPgArray(acl)).update();
        }
        return items.size();
    }

    // ───────────── versions and diff ─────────────

    @Transactional(readOnly = true)
    public List<VersionView> versions(CurrentUser me, UUID handoverId) {
        access.requireLead(me, handoverId);
        return jdbc.sql("""
                SELECT v.version_no, v.sha256, v.approved_by, u.display_name, v.approved_at,
                       jsonb_array_length(v.snapshot -> 'claims') AS claims,
                       (SELECT count(*) FROM checklist_item i WHERE i.handover_version_id = v.id) AS items
                FROM handover_version v JOIN app_user u ON u.id = v.approved_by
                WHERE v.handover_id = :h ORDER BY v.version_no DESC
                """).param("h", handoverId)
                .query((rs, n) -> new VersionView(rs.getInt("version_no"), rs.getString("sha256"),
                        rs.getObject("approved_by", UUID.class), rs.getString("display_name"),
                        rs.getTimestamp("approved_at").toInstant(), rs.getInt("claims"), rs.getInt("items")))
                .list();
    }

    public record Change(String kind, String claimId, String type, String before, String after) {
    }

    /** What changed between two versions, or between the latest version and the handover as it is now (to = 0). */
    @Transactional(readOnly = true)
    public List<Change> diff(CurrentUser me, UUID handoverId, int from, int to) {
        Handover h = access.requireLead(me, handoverId);
        Map<String, Map<String, Object>> a = claimsById(version(h.id(), from));
        Map<String, Map<String, Object>> b = claimsById(to == 0 ? snapshot(h) : version(h.id(), to));
        List<Change> out = new ArrayList<>();
        b.forEach((id, c) -> {
            Map<String, Object> old = a.get(id);
            if (old == null) {
                out.add(new Change("ADDED", id, (String) c.get("type"), null, (String) c.get("title")));
            } else if (!Objects.equals(old.get("title"), c.get("title")) || !Objects.equals(old.get("status"), c.get("status"))) {
                out.add(new Change("CHANGED", id, (String) c.get("type"),
                        old.get("title") + " [" + old.get("status") + "]", c.get("title") + " [" + c.get("status") + "]"));
            }
        });
        a.forEach((id, c) -> {
            if (!b.containsKey(id)) {
                out.add(new Change("REMOVED", id, (String) c.get("type"), (String) c.get("title"), null));
            }
        });
        return out;
    }

    private Map<String, Object> version(UUID handoverId, int versionNo) {
        String s = jdbc.sql("SELECT snapshot::text FROM handover_version WHERE handover_id = :h AND version_no = :n")
                .param("h", handoverId).param("n", versionNo).query(String.class).optional()
                .orElseThrow(ApiException::notFound);
        return json.readValue(s, MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> claimsById(Map<String, Object> snapshot) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) snapshot.get("claims")) {
            out.put((String) c.get("id"), c);
        }
        return out;
    }
}
