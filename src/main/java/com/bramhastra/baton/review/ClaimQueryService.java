package com.bramhastra.baton.review;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.source.SourceRepository;
import com.bramhastra.baton.source.SourceType;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Read side for claims, gaps and the timeline. Every query carries the access rule in its WHERE clause. */
@Service
public class ClaimQueryService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> LIST = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final AccessPolicy access;
    private final ObjectMapper json;

    public ClaimQueryService(JdbcClient jdbc, AccessPolicy access, ObjectMapper json) {
        this.jdbc = jdbc;
        this.access = access;
        this.json = json;
    }

    public record Filter(String type, String status, String reviewState, Boolean critical) {
    }

    public record Page<T>(List<T> items, int page, int size, long total) {
    }

    public Page<ClaimView> list(UUID handoverId, CurrentUser viewer, Filter f, int page, int size) {
        StringBuilder where = new StringBuilder("cl.handover_id = :h AND " + access.claimSqlPredicate("cl"));
        Map<String, Object> params = new HashMap<>(access.sqlParams(viewer));
        params.put("h", handoverId);
        if (f.type() != null) {
            where.append(" AND cl.type = :type");
            params.put("type", f.type());
        }
        if (f.status() != null) {
            where.append(" AND cl.status = :status");
            params.put("status", f.status());
        }
        if (f.reviewState() != null) {
            where.append(" AND cl.review_state = :rs");
            params.put("rs", f.reviewState());
        }
        if (f.critical() != null) {
            where.append(" AND cl.critical = :crit");
            params.put("crit", f.critical());
        }
        long total = jdbc.sql("SELECT count(*) FROM claim cl WHERE " + where).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", page * size);
        List<ClaimView> items = jdbc.sql("SELECT cl.* FROM claim cl WHERE " + where + """
                 ORDER BY cl.critical DESC,
                   CASE cl.status WHEN 'CONFLICTING' THEN 0 WHEN 'MISSING_EVIDENCE' THEN 1 WHEN 'INFERRED' THEN 2
                     WHEN 'SINGLE_SOURCE' THEN 3 WHEN 'VERIFIED' THEN 4 ELSE 5 END,
                   cl.type, cl.created_at
                 LIMIT :limit OFFSET :offset
                """).params(params).query(this::mapClaim).list();
        return new Page<>(attach(items, viewer), page, size, total);
    }

    public Optional<ClaimView> get(UUID claimId, CurrentUser viewer) {
        List<ClaimView> one = jdbc.sql("SELECT cl.* FROM claim cl WHERE cl.id = :id AND " + access.claimSqlPredicate("cl"))
                .param("id", claimId).params(access.sqlParams(viewer)).query(this::mapClaim).list();
        return attach(one, viewer).stream().findFirst();
    }

    /** Adds valid evidence and links. Links to claims the viewer cannot see are left out. */
    private List<ClaimView> attach(List<ClaimView> claims, CurrentUser viewer) {
        if (claims.isEmpty()) {
            return claims;
        }
        List<UUID> ids = claims.stream().map(ClaimView::id).toList();
        Map<UUID, List<EvidenceView>> evidence = new HashMap<>();
        Map<UUID, Integer> invalid = new HashMap<>();
        jdbc.sql("""
                SELECT e.claim_id, e.id, e.chunk_id, e.quote, e.match_score, e.start_offset, e.end_offset, e.valid,
                       d.title, d.author, d.authored_at, d.url, s.type, s.title AS source_title
                FROM evidence e JOIN chunk k ON k.id = e.chunk_id JOIN document d ON d.id = k.document_id
                JOIN source s ON s.id = d.source_id
                WHERE e.claim_id IN (:ids)
                ORDER BY e.match_score DESC
                """).param("ids", ids).query((rs, n) -> {
                    UUID claimId = rs.getObject("claim_id", UUID.class);
                    if (!rs.getBoolean("valid")) {
                        invalid.merge(claimId, 1, Integer::sum);
                        return null;
                    }
                    Timestamp at = rs.getTimestamp("authored_at");
                    evidence.computeIfAbsent(claimId, k -> new ArrayList<>()).add(new EvidenceView(
                            rs.getObject("id", UUID.class), rs.getObject("chunk_id", UUID.class), rs.getString("quote"),
                            rs.getDouble("match_score"), (Integer) rs.getObject("start_offset"),
                            (Integer) rs.getObject("end_offset"),
                            new SourceRef(SourceType.valueOf(rs.getString("type")), rs.getString("source_title"),
                                    rs.getString("title"), rs.getString("author"),
                                    at == null ? null : at.toInstant(), rs.getString("url"))));
                    return null;
                }).list();

        Map<UUID, List<LinkView>> links = new HashMap<>();
        Map<String, Object> params = new HashMap<>(access.sqlParams(viewer));
        params.put("ids", ids);
        jdbc.sql("""
                SELECT l.from_claim_id, l.to_claim_id, l.kind, l.resolved FROM claim_link l
                JOIN claim cl ON cl.id = CASE WHEN l.from_claim_id IN (:ids) THEN l.to_claim_id ELSE l.from_claim_id END
                WHERE (l.from_claim_id IN (:ids) OR l.to_claim_id IN (:ids)) AND
                """ + access.claimSqlPredicate("cl"))
                .params(params)
                .query((rs, n) -> {
                    UUID from = rs.getObject(1, UUID.class);
                    UUID to = rs.getObject(2, UUID.class);
                    String kind = rs.getString(3);
                    boolean resolved = rs.getBoolean(4);
                    if (ids.contains(from)) {
                        links.computeIfAbsent(from, k -> new ArrayList<>()).add(new LinkView(kind, to, resolved));
                    }
                    if (ids.contains(to)) {
                        links.computeIfAbsent(to, k -> new ArrayList<>()).add(new LinkView(kind, from, resolved));
                    }
                    return null;
                }).list();

        return claims.stream().map(c -> c.with(evidence.getOrDefault(c.id(), List.of()),
                links.getOrDefault(c.id(), List.of()), invalid.getOrDefault(c.id(), 0))).toList();
    }

    public List<GapView> gaps(UUID handoverId, CurrentUser viewer) {
        String visible = "EXISTS (SELECT 1 FROM claim cl WHERE cl.id = %s AND " + access.claimSqlPredicate("cl") + ")";
        return jdbc.sql("""
                SELECT g.* FROM gap_question g
                WHERE g.handover_id = :h
                  AND (g.claim_id IS NULL OR %s)
                  AND (g.related_claim_id IS NULL OR %s)
                ORDER BY CASE g.status WHEN 'OPEN' THEN 0 WHEN 'UNANSWERED' THEN 1 ELSE 2 END, g.priority, g.created_at
                """.formatted(visible.formatted("g.claim_id"), visible.formatted("g.related_claim_id")))
                .param("h", handoverId).params(access.sqlParams(viewer))
                .query((rs, n) -> new GapView(rs.getObject("id", UUID.class), rs.getString("kind"),
                        rs.getString("question"), rs.getInt("priority"), rs.getString("status"),
                        rs.getObject("claim_id", UUID.class), rs.getObject("related_claim_id", UUID.class),
                        rs.getString("answer_text"), ts(rs.getTimestamp("answered_at"))))
                .list();
    }

    public List<TimelineView> timeline(UUID handoverId, CurrentUser viewer) {
        return jdbc.sql("""
                SELECT t.at, t.kind, t.title, t.claim_id, cl.status FROM timeline_event t JOIN claim cl ON cl.id = t.claim_id
                WHERE t.handover_id = :h AND
                """ + access.claimSqlPredicate("cl") + " ORDER BY t.at, t.title")
                .param("h", handoverId).params(access.sqlParams(viewer))
                .query((rs, n) -> new TimelineView(ts(rs.getTimestamp(1)), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getString(5)))
                .list();
    }

    public Map<String, Object> summary(UUID handoverId, CurrentUser viewer) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> params = new HashMap<>(access.sqlParams(viewer));
        params.put("h", handoverId);
        String base = " FROM claim cl WHERE cl.handover_id = :h AND " + access.claimSqlPredicate("cl");
        out.put("total", jdbc.sql("SELECT count(*)" + base).params(params).query(Long.class).single());
        for (String col : List.of("status", "type", "review_state")) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            jdbc.sql("SELECT cl." + col + ", count(*)" + base + " GROUP BY 1 ORDER BY 1").params(params)
                    .query((rs, n) -> counts.put(rs.getString(1), rs.getInt(2))).list();
            out.put(col.equals("review_state") ? "byReviewState" : "by" + Character.toUpperCase(col.charAt(0)) + col.substring(1), counts);
        }
        out.put("critical", jdbc.sql("SELECT count(*)" + base + " AND cl.critical").params(params).query(Long.class).single());
        out.put("openGaps", gaps(handoverId, viewer).stream().filter(g -> !g.status().equals("ANSWERED")).count());
        return out;
    }

    private ClaimView mapClaim(ResultSet rs, int n) throws SQLException {
        return new ClaimView(rs.getObject("id", UUID.class), rs.getString("type"), rs.getInt("version"),
                rs.getString("title"), rs.getString("body"), json.readValue(rs.getString("attributes"), MAP),
                rs.getString("subject"), rs.getString("status"), rs.getDouble("confidence"),
                json.readValue(rs.getString("reasons"), LIST), Sensitivity.ofLevel(rs.getShort("sensitivity")),
                SourceRepository.textArray(rs.getArray("acl_groups")), rs.getBoolean("critical"),
                rs.getString("review_state"), rs.getString("origin"),
                SourceRepository.textArray(rs.getArray("flags")), List.of(), List.of(), 0,
                ts(rs.getTimestamp("created_at")), ts(rs.getTimestamp("updated_at")));
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    // ───────────── views (shape from LLD L8) ─────────────

    public record SourceRef(SourceType type, String sourceTitle, String title, String author, Instant date, String url) {
    }

    public record EvidenceView(UUID id, UUID chunkId, String quote, double matchScore, Integer start, Integer end,
                               SourceRef source) {
    }

    public record LinkView(String kind, UUID claimId, boolean resolved) {
    }

    public record ClaimView(UUID id, String type, int version, String title, String body, Map<String, Object> attributes,
                            String subject, String status, double confidence, List<String> reasons,
                            Sensitivity sensitivity, List<String> aclGroups, boolean critical, String reviewState,
                            String origin, List<String> flags, List<EvidenceView> evidence, List<LinkView> links,
                            int unverifiedCitations, Instant createdAt, Instant updatedAt) {
        ClaimView with(List<EvidenceView> ev, List<LinkView> ln, int unverified) {
            return new ClaimView(id, type, version, title, body, attributes, subject, status, confidence, reasons,
                    sensitivity, aclGroups, critical, reviewState, origin, flags, ev, ln, unverified, createdAt, updatedAt);
        }
    }

    public record GapView(UUID id, String kind, String question, int priority, String status, UUID claimId,
                          UUID relatedClaimId, String answer, Instant answeredAt) {
    }

    public record TimelineView(Instant at, String kind, String title, UUID claimId, String status) {
    }
}
