package com.bramhastra.baton.source;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CryptoService;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.source.connector.RawDocument;
import com.bramhastra.baton.source.connector.SourceConnector.StagedFile;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class SourceRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AccessPolicy access;

    public SourceRepository(JdbcClient jdbc, ObjectMapper json, AccessPolicy access) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
    }

    // ───────────── sources ─────────────

    public UUID insert(UUID projectId, SourceType type, String title, Map<String, Object> config,
                       Sensitivity sensitivity, List<String> aclGroups, UUID attestedBy) {
        return jdbc.sql("""
                INSERT INTO source (project_id, type, title, config, sensitivity, acl_groups,
                                    authorisation_attested, attested_by)
                VALUES (:projectId, :type, :title, CAST(:config AS jsonb), :sensitivity, CAST(:acl AS text[]),
                        TRUE, :attestedBy)
                RETURNING id
                """)
                .param("projectId", projectId)
                .param("type", type.name())
                .param("title", title)
                .param("config", json.writeValueAsString(config))
                .param("sensitivity", sensitivity.level())
                .param("acl", AccessPolicy.toPgArray(aclGroups))
                .param("attestedBy", attestedBy)
                .query(UUID.class).single();
    }

    public Optional<SourceRow> findById(UUID id) {
        return jdbc.sql("SELECT * FROM source WHERE id = :id").param("id", id).query(this::mapSource).optional();
    }

    /** Sources in the project that the viewer may see (same rule as chunks). */
    public List<SourceRow> listVisible(UUID projectId, CurrentUser viewer) {
        return jdbc.sql("SELECT * FROM source s WHERE s.project_id = :projectId AND "
                        + access.sqlPredicate("s") + " ORDER BY s.created_at DESC")
                .param("projectId", projectId)
                .params(access.sqlParams(viewer))
                .query(this::mapSource).list();
    }

    public boolean isVisible(SourceRow s, CurrentUser viewer) {
        return access.canSee(viewer, s.sensitivity(), s.aclGroups());
    }

    public void setStatus(UUID id, SourceRow.SourceStatus status) {
        jdbc.sql("UPDATE source SET status = :status, last_error = NULL WHERE id = :id")
                .param("id", id).param("status", status.name()).update();
    }

    public void markReady(UUID id, int documents, int chunks, int redactions) {
        jdbc.sql("""
                UPDATE source SET status = 'READY', document_count = :d, chunk_count = :c, redaction_count = :r,
                                  last_synced_at = now(), last_error = NULL
                WHERE id = :id
                """)
                .param("id", id).param("d", documents).param("c", chunks).param("r", redactions).update();
    }

    public void markFailed(UUID id, String error) {
        jdbc.sql("UPDATE source SET status = 'FAILED', last_error = :e WHERE id = :id")
                .param("id", id).param("e", error).update();
    }

    // ───────────── staged uploads (encrypted, deleted after ingest) ─────────────

    public void stageFile(UUID sourceId, StagedFile file, CryptoService.Sealed sealed) {
        jdbc.sql("""
                INSERT INTO source_blob (source_id, filename, mime, ciphertext, iv)
                VALUES (:id, :filename, :mime, :ct, :iv)
                ON CONFLICT (source_id) DO UPDATE SET filename = EXCLUDED.filename, mime = EXCLUDED.mime,
                    ciphertext = EXCLUDED.ciphertext, iv = EXCLUDED.iv, created_at = now()
                """)
                .param("id", sourceId).param("filename", file.filename()).param("mime", file.mime())
                .param("ct", sealed.ciphertext()).param("iv", sealed.iv())
                .update();
    }

    public Optional<SealedFile> loadStaged(UUID sourceId) {
        return jdbc.sql("SELECT filename, mime, ciphertext, iv FROM source_blob WHERE source_id = :id")
                .param("id", sourceId)
                .query((rs, n) -> new SealedFile(rs.getString("filename"), rs.getString("mime"),
                        rs.getBytes("ciphertext"), rs.getBytes("iv")))
                .optional();
    }

    public void deleteStaged(UUID sourceId) {
        jdbc.sql("DELETE FROM source_blob WHERE source_id = :id").param("id", sourceId).update();
    }

    public record SealedFile(String filename, String mime, byte[] ciphertext, byte[] iv) {
    }

    // ───────────── documents, chunks, tokens ─────────────

    public void deleteDocuments(UUID sourceId) {
        jdbc.sql("DELETE FROM document WHERE source_id = :id").param("id", sourceId).update();
    }

    public UUID insertDocument(SourceRow source, RawDocument doc, String redactedTitle) {
        return jdbc.sql("""
                INSERT INTO document (source_id, external_ref, title, author, authored_at, url, mime,
                                      sensitivity, acl_groups)
                VALUES (:sourceId, :ref, :title, :author, :authoredAt, :url, :mime, :sensitivity,
                        CAST(:acl AS text[]))
                RETURNING id
                """)
                .param("sourceId", source.id())
                .param("ref", truncate(doc.externalRef(), 500))
                .param("title", truncate(redactedTitle, 300))
                .param("author", truncate(doc.author(), 120))
                .param("authoredAt", doc.authoredAt() == null ? null : Timestamp.from(doc.authoredAt()))
                .param("url", truncate(doc.url(), 1000))
                .param("mime", truncate(doc.mime(), 100))
                .param("sensitivity", source.sensitivity().level())
                .param("acl", AccessPolicy.toPgArray(source.aclGroups()))
                .query(UUID.class).single();
    }

    public void insertChunk(UUID documentId, SourceRow source, Chunker.Piece piece, float[] embedding) {
        jdbc.sql("""
                INSERT INTO chunk (document_id, seq, text, embedding, sensitivity, acl_groups, start_offset, end_offset)
                VALUES (:documentId, :seq, :text, CAST(:embedding AS vector), :sensitivity, CAST(:acl AS text[]),
                        :start, :end)
                """)
                .param("documentId", documentId)
                .param("seq", piece.seq())
                .param("text", piece.text())
                .param("embedding", embedding == null ? null : Embedder.toPgVector(embedding))
                .param("sensitivity", source.sensitivity().level())
                .param("acl", AccessPolicy.toPgArray(source.aclGroups()))
                .param("start", piece.start())
                .param("end", piece.end())
                .update();
    }

    public void insertRedactionToken(UUID documentId, Redactor.Redaction r, CryptoService.Sealed sealed) {
        jdbc.sql("""
                INSERT INTO redaction_token (document_id, token, kind, ciphertext, iv)
                VALUES (:documentId, :token, :kind, :ct, :iv)
                """)
                .param("documentId", documentId)
                .param("token", r.token())
                .param("kind", r.kind().name())
                .param("ct", sealed.ciphertext())
                .param("iv", sealed.iv())
                .update();
    }

    public List<DocumentSummary> listDocuments(UUID sourceId) {
        return jdbc.sql("""
                SELECT d.id, d.title, d.author, d.authored_at, d.url, count(c.id) AS chunks
                FROM document d LEFT JOIN chunk c ON c.document_id = d.id
                WHERE d.source_id = :id
                GROUP BY d.id ORDER BY d.authored_at DESC NULLS LAST, d.title
                """)
                .param("id", sourceId)
                .query((rs, n) -> new DocumentSummary(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getString("author"), instant(rs.getTimestamp("authored_at")), rs.getString("url"),
                        rs.getInt("chunks")))
                .list();
    }

    public record DocumentSummary(UUID id, String title, String author, Instant authoredAt, String url,
                                  int chunks) {
    }

    // ───────────── mapping ─────────────

    private SourceRow mapSource(ResultSet rs, int row) throws SQLException {
        return new SourceRow(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                SourceType.valueOf(rs.getString("type")),
                rs.getString("title"),
                json.readValue(rs.getString("config"), MAP),
                Sensitivity.ofLevel(rs.getShort("sensitivity")),
                textArray(rs.getArray("acl_groups")),
                rs.getObject("attested_by", UUID.class),
                SourceRow.SourceStatus.valueOf(rs.getString("status")),
                rs.getInt("document_count"),
                rs.getInt("chunk_count"),
                rs.getInt("redaction_count"),
                rs.getString("last_error"),
                instant(rs.getTimestamp("last_synced_at")),
                instant(rs.getTimestamp("created_at")));
    }

    public static List<String> textArray(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
