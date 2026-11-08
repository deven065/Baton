package com.bramhastra.baton.source;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CurrentUser;

/**
 * Hybrid, permission-scoped retrieval (LLD L10): keyword (tsvector) and vector (pgvector) rankings
 * fused with reciprocal rank fusion. The permission filter runs BEFORE ranking, in the "allowed" CTE,
 * so a restricted chunk can never be retrieved, ranked, or later sent to an LLM.
 */
@Service
public class SearchService {

    private final JdbcClient jdbc;
    private final AccessPolicy access;
    private final Embedder embedder;

    public SearchService(JdbcClient jdbc, AccessPolicy access, Embedder embedder) {
        this.jdbc = jdbc;
        this.access = access;
        this.embedder = embedder;
    }

    public List<Hit> search(UUID projectId, CurrentUser viewer, String query, int limit) {
        Optional<float[]> qvec = embedder.embed(query);
        String allowed = """
                WITH allowed AS (
                  SELECT c.* FROM chunk c
                  JOIN document d ON d.id = c.document_id
                  JOIN source s ON s.id = d.source_id
                  WHERE s.project_id = :projectId AND %s
                ),
                kw AS (
                  SELECT id, ts_rank(tsv, plainto_tsquery('english', :q)) r FROM allowed
                  WHERE tsv @@ plainto_tsquery('english', :q) ORDER BY r DESC LIMIT 20
                )
                """.formatted(access.sqlPredicate("c"));
        String vec = qvec.isPresent() ? """
                , vec AS (
                  SELECT id, 1 - (embedding <=> CAST(:qvec AS vector)) r FROM allowed
                  WHERE embedding IS NOT NULL
                  ORDER BY embedding <=> CAST(:qvec AS vector) LIMIT 20
                )
                """ : "";
        String fused = qvec.isPresent()
                ? "SELECT id, ROW_NUMBER() OVER (ORDER BY r DESC) rnk FROM kw UNION ALL "
                        + "SELECT id, ROW_NUMBER() OVER (ORDER BY r DESC) rnk FROM vec"
                : "SELECT id, ROW_NUMBER() OVER (ORDER BY r DESC) rnk FROM kw";
        String sql = allowed + vec + """
                , ranked AS (
                  SELECT id, SUM(1.0 / (60 + rnk)) AS rrf FROM (%s) x GROUP BY id
                  ORDER BY rrf DESC LIMIT :limit
                )
                SELECT r.rrf, c.id AS chunk_id, c.seq, c.sensitivity,
                       ts_headline('english', c.text, plainto_tsquery('english', :q),
                                   'MaxFragments=2, MaxWords=35, MinWords=12, StartSel=**, StopSel=**') AS snippet,
                       d.id AS document_id, d.title AS document_title, d.author, d.authored_at, d.url,
                       s.id AS source_id, s.type AS source_type, s.title AS source_title
                FROM ranked r
                JOIN chunk c ON c.id = r.id
                JOIN document d ON d.id = c.document_id
                JOIN source s ON s.id = d.source_id
                ORDER BY r.rrf DESC
                """.formatted(fused);

        JdbcClient.StatementSpec stmt = jdbc.sql(sql)
                .param("projectId", projectId)
                .param("q", query)
                .param("limit", limit)
                .params(access.sqlParams(viewer));
        if (qvec.isPresent()) {
            stmt = stmt.param("qvec", Embedder.toPgVector(qvec.get()));
        }
        return stmt.query(this::map).list();
    }

    private Hit map(ResultSet rs, int n) throws SQLException {
        Timestamp at = rs.getTimestamp("authored_at");
        return new Hit(rs.getObject("chunk_id", UUID.class), rs.getInt("seq"), rs.getDouble("rrf"),
                rs.getString("snippet"), Sensitivity.ofLevel(rs.getShort("sensitivity")),
                rs.getObject("document_id", UUID.class), rs.getString("document_title"), rs.getString("author"),
                at == null ? null : at.toInstant(), rs.getString("url"), rs.getObject("source_id", UUID.class),
                SourceType.valueOf(rs.getString("source_type")), rs.getString("source_title"));
    }

    public record Hit(UUID chunkId, int seq, double score, String snippet, Sensitivity sensitivity, UUID documentId,
                      String documentTitle, String author, Instant authoredAt, String url, UUID sourceId,
                      SourceType sourceType, String sourceTitle) {
    }
}
