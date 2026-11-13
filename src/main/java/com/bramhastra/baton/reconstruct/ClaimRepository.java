package com.bramhastra.baton.reconstruct;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.source.SourceRepository;
import com.bramhastra.baton.source.SourceType;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Write side of claims for the pipeline. Plain SQL, so each statement is explicit and reviewable. */
@Repository
public class ClaimRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> LIST = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ClaimRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record NewClaim(UUID handoverId, UUID runId, String type, String title, String body,
                           Map<String, Object> attributes, String subject, Sensitivity sensitivity,
                           List<String> aclGroups, boolean critical, String origin, List<String> flags,
                           List<String> reasons) {
    }

    public UUID insert(NewClaim c) {
        return jdbc.sql("""
                INSERT INTO claim (handover_id, extraction_run_id, type, title, body, attributes, status, confidence,
                                   reasons, sensitivity, acl_groups, critical, origin, subject, flags)
                VALUES (:h, :run, :type, :title, :body, CAST(:attributes AS jsonb), 'MISSING_EVIDENCE', 0,
                        CAST(:reasons AS jsonb), :sens, CAST(:acl AS text[]), :critical, :origin, :subject,
                        CAST(:flags AS text[]))
                RETURNING id
                """)
                .param("h", c.handoverId()).param("run", c.runId()).param("type", c.type())
                .param("title", c.title()).param("body", c.body())
                .param("attributes", json.writeValueAsString(c.attributes()))
                .param("reasons", json.writeValueAsString(c.reasons()))
                .param("sens", c.sensitivity().level())
                .param("acl", AccessPolicy.toPgArray(c.aclGroups()))
                .param("critical", c.critical()).param("origin", c.origin()).param("subject", c.subject())
                .param("flags", AccessPolicy.toPgArray(c.flags()))
                .query(UUID.class).single();
    }

    /** Draft evidence: the model's quote, not yet checked. The verifier fills in the rest. */
    public void insertEvidenceDraft(UUID claimId, UUID chunkId, String modelQuote) {
        jdbc.sql("""
                INSERT INTO evidence (claim_id, chunk_id, quote, model_quote, match_score, valid)
                VALUES (:c, :k, :q, :q, 0, FALSE)
                """)
                .param("c", claimId).param("k", chunkId).param("q", modelQuote).update();
    }

    /** Re-run: machine claims nobody has reviewed yet are rebuilt; reviewed ones are kept. */
    public void deleteUnreviewedMachineClaims(UUID handoverId) {
        jdbc.sql("""
                DELETE FROM claim WHERE handover_id = :h AND review_state = 'PENDING'
                  AND origin IN ('EXTRACTED', 'REPO_SIGNAL')
                """).param("h", handoverId).update();
    }

    public void deleteRunClaims(UUID runId, String origin) {
        jdbc.sql("DELETE FROM claim WHERE extraction_run_id = :r AND origin = :o")
                .param("r", runId).param("o", origin).update();
    }

    // ───────────── verification ─────────────

    public record PendingEvidence(UUID id, String modelQuote, String chunkText) {
    }

    public List<PendingEvidence> unverifiedEvidence(UUID handoverId) {
        return jdbc.sql("""
                SELECT e.id, e.model_quote, k.text FROM evidence e
                JOIN claim c ON c.id = e.claim_id JOIN chunk k ON k.id = e.chunk_id
                WHERE c.handover_id = :h AND e.verified_at IS NULL
                """)
                .param("h", handoverId)
                .query((rs, n) -> new PendingEvidence(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .list();
    }

    public void markEvidence(UUID id, boolean valid, double score, Integer start, Integer end, String quote) {
        jdbc.sql("""
                UPDATE evidence SET valid = :v, match_score = :s, start_offset = :st, end_offset = :en,
                                    quote = :q, verified_at = now()
                WHERE id = :id
                """)
                .param("id", id).param("v", valid).param("s", score).param("st", start).param("en", end)
                .param("q", quote).update();
    }

    // ───────────── reading claims for later stages ─────────────

    public record ClaimRow(UUID id, String type, String title, String body, Map<String, Object> attributes,
                           String subject, String status, Sensitivity sensitivity, List<String> aclGroups,
                           boolean critical, String origin, String reviewState, List<String> flags,
                           List<String> reasons, UUID runId, Instant createdAt) {

        public String attr(String key) {
            Object v = attributes.get(key);
            return v == null ? null : v.toString();
        }
    }

    public List<ClaimRow> claims(UUID handoverId) {
        return jdbc.sql("SELECT * FROM claim WHERE handover_id = :h ORDER BY created_at, id")
                .param("h", handoverId).query(this::mapClaim).list();
    }

    public record EvidenceRow(UUID id, UUID claimId, UUID chunkId, String quote, double matchScore, boolean valid,
                              UUID sourceId, SourceType sourceType, String sourceTitle, Instant authoredAt,
                              Sensitivity chunkSensitivity, List<String> chunkAcl, String documentTitle) {
    }

    public List<EvidenceRow> evidence(UUID handoverId) {
        return jdbc.sql("""
                SELECT e.id, e.claim_id, e.chunk_id, e.quote, e.match_score, e.valid,
                       s.id AS source_id, s.type AS source_type, s.title AS source_title, d.authored_at,
                       k.sensitivity, k.acl_groups, d.title AS document_title
                FROM evidence e JOIN claim c ON c.id = e.claim_id
                JOIN chunk k ON k.id = e.chunk_id JOIN document d ON d.id = k.document_id
                JOIN source s ON s.id = d.source_id
                WHERE c.handover_id = :h
                """)
                .param("h", handoverId)
                .query((rs, n) -> new EvidenceRow(rs.getObject("id", UUID.class), rs.getObject("claim_id", UUID.class),
                        rs.getObject("chunk_id", UUID.class), rs.getString("quote"), rs.getDouble("match_score"),
                        rs.getBoolean("valid"), rs.getObject("source_id", UUID.class),
                        SourceType.valueOf(rs.getString("source_type")), rs.getString("source_title"),
                        instant(rs.getTimestamp("authored_at")), Sensitivity.ofLevel(rs.getShort("sensitivity")),
                        SourceRepository.textArray(rs.getArray("acl_groups")), rs.getString("document_title")))
                .list();
    }

    public void moveEvidence(UUID fromClaim, UUID toClaim) {
        jdbc.sql("UPDATE evidence SET claim_id = :to WHERE claim_id = :from")
                .param("from", fromClaim).param("to", toClaim).update();
    }

    public void delete(UUID claimId) {
        jdbc.sql("DELETE FROM claim WHERE id = :id").param("id", claimId).update();
    }

    /** Merges keys into the claim's attributes (used for the model check verdict). */
    public void mergeAttributes(UUID claimId, Map<String, Object> attrs) {
        jdbc.sql("UPDATE claim SET attributes = attributes || CAST(:a AS jsonb), updated_at = now() WHERE id = :id")
                .param("id", claimId).param("a", json.writeValueAsString(attrs)).update();
    }

    public void setCritical(UUID claimId) {
        jdbc.sql("UPDATE claim SET critical = TRUE WHERE id = :id").param("id", claimId).update();
    }

    public void updateScore(UUID claimId, String status, double confidence, Sensitivity sensitivity,
                            List<String> acl, List<String> reasons) {
        jdbc.sql("""
                UPDATE claim SET status = :st, confidence = :c, sensitivity = :s, acl_groups = CAST(:acl AS text[]),
                                 reasons = CAST(:r AS jsonb), updated_at = now()
                WHERE id = :id
                """)
                .param("id", claimId).param("st", status).param("c", confidence).param("s", sensitivity.level())
                .param("acl", AccessPolicy.toPgArray(acl)).param("r", json.writeValueAsString(reasons))
                .update();
    }

    // ───────────── links ─────────────

    public record LinkRow(UUID from, UUID to, String kind, boolean resolved) {
    }

    public List<LinkRow> links(UUID handoverId) {
        return jdbc.sql("""
                SELECT l.from_claim_id, l.to_claim_id, l.kind, l.resolved FROM claim_link l
                JOIN claim c ON c.id = l.from_claim_id WHERE c.handover_id = :h
                """)
                .param("h", handoverId)
                .query((rs, n) -> new LinkRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getBoolean(4)))
                .list();
    }

    public void deleteUnresolvedConflicts(UUID handoverId) {
        jdbc.sql("""
                DELETE FROM claim_link l USING claim c
                WHERE c.id = l.from_claim_id AND c.handover_id = :h AND l.kind = 'CONFLICTS_WITH' AND NOT l.resolved
                """).param("h", handoverId).update();
    }

    public void link(UUID from, UUID to, String kind) {
        jdbc.sql("""
                INSERT INTO claim_link (from_claim_id, to_claim_id, kind) VALUES (:f, :t, :k)
                ON CONFLICT DO NOTHING
                """).param("f", from).param("t", to).param("k", kind).update();
    }

    private ClaimRow mapClaim(ResultSet rs, int n) throws SQLException {
        return new ClaimRow(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("title"),
                rs.getString("body"), json.readValue(rs.getString("attributes"), MAP), rs.getString("subject"),
                rs.getString("status"), Sensitivity.ofLevel(rs.getShort("sensitivity")),
                SourceRepository.textArray(rs.getArray("acl_groups")), rs.getBoolean("critical"),
                rs.getString("origin"), rs.getString("review_state"),
                SourceRepository.textArray(rs.getArray("flags")), json.readValue(rs.getString("reasons"), LIST),
                rs.getObject("extraction_run_id", UUID.class), rs.getTimestamp("created_at").toInstant());
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
