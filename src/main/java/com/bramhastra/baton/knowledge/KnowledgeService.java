package com.bramhastra.baton.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.source.SourceRepository;

/**
 * The always-on side of Baton. Every time a source is read (when it is added, re-synced by hand, or picked up by the
 * background sync), its passages are mined for decisions and the reasoning behind them and kept at project level,
 * so the project's knowledge builds up continuously instead of being gathered in the two weeks before someone leaves.
 *
 * <p>A decision is visible to exactly the people who can see the passage it was cut from, and a lead can confirm or
 * reject each one; that choice survives later reads of the same source.
 */
@Service
public class KnowledgeService {

    static final Set<String> SKIP_TYPES = Set.of("TESTIMONY");
    private static final Duration WEEK = Duration.ofDays(7);

    private final JdbcClient jdbc;
    private final AccessPolicy access;
    private final AuditService audit;
    private final SourceRepository sources;
    private final int syncIntervalMinutes;

    public KnowledgeService(JdbcClient jdbc, AccessPolicy access, AuditService audit, SourceRepository sources,
                            @Value("${baton.sync.interval-minutes:60}") int syncIntervalMinutes) {
        this.sources = sources;
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.syncIntervalMinutes = syncIntervalMinutes;
    }

    // ───────────── capturing ─────────────

    private record ChunkRow(UUID chunkId, UUID documentId, String text, short sensitivity, String acl, String title, String author,
                            Instant authoredAt, String url, String ref, String type) {
    }

    /** Reads one source's passages and keeps the decisions in them. Returns how many decisions the source now holds. */
    @Transactional
    public int mine(UUID projectId, UUID sourceId) {
        List<ChunkRow> chunks = jdbc.sql("""
                SELECT k.id, k.document_id, k.text, k.sensitivity, CAST(k.acl_groups AS text), d.title, d.author, d.authored_at,
                       d.url, d.external_ref, s.type
                FROM chunk k JOIN document d ON d.id = k.document_id JOIN source s ON s.id = d.source_id
                WHERE s.id = :s AND s.project_id = :p AND s.type <> 'TESTIMONY' ORDER BY d.id, k.seq
                """).param("s", sourceId).param("p", projectId)
                .query((rs, n) -> new ChunkRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getShort(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(), rs.getString(9), rs.getString(10),
                        rs.getString(11)))
                .list();

        List<String> kept = new ArrayList<>();
        for (ChunkRow c : chunks) {
            for (DecisionDetector.Found f : DecisionDetector.detect(
                    new DecisionDetector.Passage(c.title(), c.author(), c.authoredAt(), c.text()))) {
                String fingerprint = sha256(sourceId + "|" + (c.ref() == null ? c.title() : c.ref()) + "|" + DecisionDetector.key(f.title()));
                if (kept.contains(fingerprint)) {
                    continue;
                }
                kept.add(fingerprint);
                jdbc.sql("""
                        INSERT INTO decision (project_id, source_id, document_id, chunk_id, fingerprint, doc_ref, doc_title, source_type,
                                              url, title, reasoning, alternatives, decided_by, decided_at, quote, topic, confidence,
                                              sensitivity, acl_groups)
                        VALUES (:p, :s, :d, :k, :fp, :ref, :dt, :st, :url, :title, :why, CAST(:alts AS text[]), :by, :at, :quote,
                                :topic, :conf, :sens, CAST(:acl AS text[]))
                        ON CONFLICT (project_id, fingerprint) DO UPDATE SET
                            document_id = EXCLUDED.document_id, chunk_id = EXCLUDED.chunk_id, doc_title = EXCLUDED.doc_title,
                            url = EXCLUDED.url, reasoning = EXCLUDED.reasoning, alternatives = EXCLUDED.alternatives,
                            decided_by = EXCLUDED.decided_by, decided_at = EXCLUDED.decided_at, quote = EXCLUDED.quote,
                            topic = EXCLUDED.topic, confidence = EXCLUDED.confidence, sensitivity = EXCLUDED.sensitivity,
                            acl_groups = EXCLUDED.acl_groups, updated_at = now()
                        """)
                        .param("p", projectId).param("s", sourceId).param("d", c.documentId()).param("k", c.chunkId())
                        .param("fp", fingerprint).param("ref", cut(c.ref(), 600)).param("dt", cut(c.title(), 300))
                        .param("st", c.type()).param("url", cut(c.url(), 1000)).param("title", cut(f.title(), 400))
                        .param("why", f.reasoning()).param("alts", AccessPolicy.toPgArray(f.alternatives()))
                        .param("by", cut(f.decidedBy(), 120)).param("at", f.decidedAt() == null ? null : Timestamp.from(f.decidedAt()))
                        .param("quote", f.quote()).param("topic", cut(f.topic(), 120))
                        .param("conf", Math.round(f.confidence() * 100) / 100.0).param("sens", c.sensitivity())
                        .param("acl", c.acl() == null ? "{}" : c.acl())
                        .update();
            }
        }
        // A decision the source no longer says is gone with it.
        if (kept.isEmpty()) {
            jdbc.sql("DELETE FROM decision WHERE source_id = :s").param("s", sourceId).update();
        } else {
            jdbc.sql("DELETE FROM decision WHERE source_id = :s AND fingerprint NOT IN (:fps)").param("s", sourceId)
                    .param("fps", kept).update();
        }
        jdbc.sql("UPDATE source SET mined_at = now() WHERE id = :s").param("s", sourceId).update();
        return kept.size();
    }

    /** Mines every ready source that has not been mined yet: how knowledge captured before this existed shows up. */
    @Transactional
    public int mineMissing(UUID projectId) {
        List<UUID> todo = jdbc.sql("""
                SELECT id FROM source WHERE project_id = :p AND status = 'READY' AND mined_at IS NULL AND type <> 'TESTIMONY'
                """).param("p", projectId).query(UUID.class).list();
        todo.forEach(s -> mine(projectId, s));
        return todo.size();
    }

    /** Mines everything again, for the lead's "Capture now". */
    @Transactional
    public int mineAll(CurrentUser me, UUID projectId) {
        access.requireLead(me, projectId);
        List<UUID> all = jdbc.sql("SELECT id FROM source WHERE project_id = :p AND status = 'READY' AND type <> 'TESTIMONY'")
                .param("p", projectId).query(UUID.class).list();
        all.forEach(s -> mine(projectId, s));
        return jdbc.sql("SELECT count(*) FROM decision WHERE project_id = :p").param("p", projectId).query(Integer.class).single();
    }

    // ───────────── the decision log ─────────────

    public record DecisionView(UUID id, String title, String reasoning, List<String> alternatives, String decidedBy,
                               Instant decidedAt, String quote, String topic, double confidence, boolean hasReason, String review,
                               String docTitle, String sourceTitle, String sourceType, UUID sourceId, UUID chunkId, String url) {
    }

    public record Facet(String name, int count) {
    }

    public record Log(List<DecisionView> items, List<Facet> topics, List<Facet> people, int total, int withReason) {
    }

    @Transactional
    public Log log(CurrentUser me, UUID projectId, String q, String topic, String person, boolean includeRejected, int limit) {
        access.requireMember(me, projectId);
        mineMissing(projectId);
        Map<String, Object> params = new LinkedHashMap<>(access.sqlParams(me));
        params.put("p", projectId);
        StringBuilder where = new StringBuilder("d.project_id = :p AND " + access.sqlPredicate("d"));
        if (!includeRejected) {
            where.append(" AND d.review <> 'REJECTED'");
        }
        if (topic != null && !topic.isBlank()) {
            where.append(" AND lower(d.topic) = lower(:topic)");
            params.put("topic", topic.strip());
        }
        if (person != null && !person.isBlank()) {
            where.append(" AND lower(d.decided_by) = lower(:person)");
            params.put("person", person.strip());
        }
        List<String> terms = terms(q);
        for (int i = 0; i < terms.size(); i++) {
            where.append(" AND (lower(d.title) LIKE :t").append(i).append(" OR lower(coalesce(d.reasoning, '')) LIKE :t").append(i)
                    .append(" OR lower(coalesce(d.topic, '')) LIKE :t").append(i).append(" OR lower(d.quote) LIKE :t").append(i)
                    .append(" OR lower(array_to_string(d.alternatives, ' ')) LIKE :t").append(i).append(')');
            params.put("t" + i, "%" + terms.get(i) + "%");
        }
        List<DecisionView> items = jdbc.sql("""
                SELECT d.id, d.title, d.reasoning, d.alternatives, d.decided_by, d.decided_at, d.quote, d.topic, d.confidence,
                       d.review, d.doc_title, s.title AS source_title, d.source_type, d.source_id, d.chunk_id, d.url
                FROM decision d JOIN source s ON s.id = d.source_id
                WHERE %s
                ORDER BY (d.review = 'CONFIRMED') DESC, d.decided_at DESC NULLS LAST, d.confidence DESC
                LIMIT :limit
                """.formatted(where)).params(params).param("limit", Math.max(1, Math.min(limit, 200)))
                .query((rs, n) -> new DecisionView(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("reasoning"),
                        SourceRepository.textArray(rs.getArray("alternatives")), rs.getString("decided_by"),
                        rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant(),
                        rs.getString("quote"), rs.getString("topic"), rs.getDouble("confidence"),
                        rs.getString("reasoning") != null, rs.getString("review"), rs.getString("doc_title"),
                        rs.getString("source_title"), rs.getString("source_type"), rs.getObject("source_id", UUID.class),
                        rs.getObject("chunk_id", UUID.class), rs.getString("url")))
                .list();

        // Facets are counted over everything the viewer may see, not just the current filter, so they stay a menu.
        Map<String, Object> fp = new LinkedHashMap<>(access.sqlParams(me));
        fp.put("p", projectId);
        String visible = "d.project_id = :p AND d.review <> 'REJECTED' AND " + access.sqlPredicate("d");
        List<Facet> topics = jdbc.sql("SELECT d.topic, count(*) FROM decision d WHERE " + visible
                + " AND d.topic IS NOT NULL AND d.topic <> '' GROUP BY d.topic ORDER BY count(*) DESC, d.topic LIMIT 30")
                .params(fp).query((rs, n) -> new Facet(rs.getString(1), rs.getInt(2))).list();
        List<Facet> people = jdbc.sql("SELECT d.decided_by, count(*) FROM decision d WHERE " + visible
                + " AND d.decided_by IS NOT NULL GROUP BY d.decided_by ORDER BY count(*) DESC, d.decided_by LIMIT 30")
                .params(fp).query((rs, n) -> new Facet(rs.getString(1), rs.getInt(2))).list();
        int total = jdbc.sql("SELECT count(*) FROM decision d WHERE " + visible).params(fp).query(Integer.class).single();
        int withReason = jdbc.sql("SELECT count(*) FROM decision d WHERE " + visible + " AND d.reasoning IS NOT NULL")
                .params(fp).query(Integer.class).single();
        return new Log(items, topics, people, total, withReason);
    }

    /** The lead's call on one decision: it is right (CONFIRMED), not a decision at all (REJECTED), or undecided (AUTO). */
    @Transactional
    public void review(CurrentUser me, UUID id, String review) {
        if (review == null || !Set.of("AUTO", "CONFIRMED", "REJECTED").contains(review)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of("review", "CONFIRMED, REJECTED or AUTO"));
        }
        UUID projectId = jdbc.sql("SELECT project_id FROM decision WHERE id = :id").param("id", id).query(UUID.class).optional()
                .orElseThrow(ApiException::notFound);
        access.requireLead(me, projectId);
        // A decision the lead may not see does not exist for them.
        boolean visible = jdbc.sql("SELECT count(*) FROM decision d WHERE d.id = :id AND " + access.sqlPredicate("d"))
                .param("id", id).params(access.sqlParams(me)).query(Integer.class).single() > 0;
        if (!visible) {
            throw ApiException.notFound();
        }
        jdbc.sql("""
                UPDATE decision SET review = :r, reviewed_by = CASE WHEN :r = 'AUTO' THEN NULL ELSE CAST(:me AS uuid) END,
                                    reviewed_at = CASE WHEN :r = 'AUTO' THEN NULL ELSE now() END
                WHERE id = :id
                """).param("r", review).param("me", me.id().toString()).param("id", id).update();
        audit.record(me.id(), "DECISION_" + review, "decision", id, projectId, Map.of());
    }

    // ───────────── capture status ─────────────

    public record SourceCapture(UUID id, String title, String type, String status, boolean live, boolean autoSync,
                                Instant lastCheckedAt, Instant lastChangedAt, int decisions) {
    }

    public record Recent(UUID id, String title, String decidedBy, Instant decidedAt, boolean hasReason) {
    }

    public record Capture(boolean watching, int checkEveryMinutes, List<SourceCapture> sources, int decisions, int decisionsThisWeek,
                          int withReason, Instant lastCapturedAt, List<Recent> recent) {
    }

    @Transactional
    public Capture capture(CurrentUser me, UUID projectId) {
        access.requireMember(me, projectId);
        mineMissing(projectId);
        List<com.bramhastra.baton.source.SourceRow> visible = sources.listVisible(projectId, me);
        Map<UUID, Integer> perSource = new LinkedHashMap<>();
        Map<String, Object> params = new LinkedHashMap<>(access.sqlParams(me));
        params.put("p", projectId);
        jdbc.sql("SELECT d.source_id, count(*) FROM decision d WHERE d.project_id = :p AND d.review <> 'REJECTED' AND "
                + access.sqlPredicate("d") + " GROUP BY d.source_id").params(params)
                .query((rs, n) -> perSource.put(rs.getObject(1, UUID.class), rs.getInt(2))).list();
        List<SourceCapture> srcs = visible.stream().filter(s -> !SKIP_TYPES.contains(s.type().name()))
                .map(s -> new SourceCapture(s.id(), s.title(), s.type().name(), s.status().name(), s.type().isLive(), s.autoSync(),
                        s.lastCheckedAt(), s.lastChangedAt() != null ? s.lastChangedAt() : s.lastSyncedAt(), perSource.getOrDefault(s.id(), 0)))
                .toList();
        List<Recent> recent = jdbc.sql("""
                SELECT d.id, d.title, d.decided_by, d.decided_at, d.reasoning IS NOT NULL
                FROM decision d WHERE d.project_id = :p AND d.review <> 'REJECTED' AND %s
                ORDER BY d.created_at DESC LIMIT 6
                """.formatted(access.sqlPredicate("d"))).params(params)
                .query((rs, n) -> new Recent(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(), rs.getBoolean(5)))
                .list();
        Map<String, Object> stats = jdbc.sql("""
                SELECT count(*) AS n, count(*) FILTER (WHERE d.created_at > :since) AS week,
                       count(*) FILTER (WHERE d.reasoning IS NOT NULL) AS why, max(d.updated_at) AS last
                FROM decision d WHERE d.project_id = :p AND d.review <> 'REJECTED' AND %s
                """.formatted(access.sqlPredicate("d"))).params(params).param("since", Timestamp.from(Instant.now().minus(WEEK)))
                .query().singleRow();
        Timestamp last = (Timestamp) stats.get("last");
        boolean watching = srcs.stream().anyMatch(s -> s.live() && s.autoSync());
        return new Capture(watching, syncIntervalMinutes, srcs, ((Number) stats.get("n")).intValue(), ((Number) stats.get("week")).intValue(),
                ((Number) stats.get("why")).intValue(), last == null ? null : last.toInstant(), recent);
    }

    // ───────────── helpers ─────────────

    /** Words of a search box, lower-cased, without the little words that match everything. */
    static List<String> terms(String q) {
        List<String> out = new ArrayList<>();
        if (q == null) {
            return out;
        }
        for (String w : q.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 3 && !Set.of("the", "and", "for", "why", "did", "does", "what", "who", "how", "was", "were", "are", "our",
                    "we", "use", "with", "from", "that", "this", "decide", "decided", "decision", "choose", "chose").contains(w)
                    && out.size() < 6) {
                out.add(w);
            }
        }
        return out;
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
