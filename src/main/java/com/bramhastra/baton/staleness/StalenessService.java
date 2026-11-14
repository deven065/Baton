package com.bramhastra.baton.staleness;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CurrentUser;

/**
 * Runs {@link StaleDetector} over a project's sources and keeps the findings. A scan happens after every source is
 * read and before every reconstruction, and the lead can start one. The lead marks each finding as an outdated
 * document or as still correct; that decision survives later reads of the same source.
 */
@Service
public class StalenessService {

    /** Source types whose documents count as documentation (chat, tasks and testimony do not). */
    private static final Set<String> DOC_TYPES = Set.of("DOCUMENT", "MEETING_NOTES", "GOOGLE_DRIVE", "GITHUB");
    private static final Pattern PR_CLOSED = Pattern.compile("^Closed:\\s*(\\S+)", Pattern.MULTILINE);
    private static final Pattern PR_TITLE = Pattern.compile("^Pull request #(\\d+):\\s*(.+)$", Pattern.MULTILINE);

    private final JdbcClient jdbc;
    private final AccessPolicy access;
    private final AuditService audit;

    public StalenessService(JdbcClient jdbc, AccessPolicy access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    private record Row(UUID chunkId, int seq, String text, UUID documentId, String title, Instant authoredAt,
                       String externalRef, String url, String sourceType, UUID sourceId) {
    }

    public record FindingView(UUID id, String docTitle, String docQuote, String term, String replacement, String kind,
                              String changeRef, String changeText, Instant changeAt, String changeUrl, String status,
                              Instant detectedAt, UUID documentId) {
    }

    /** Re-checks every document in the project against every code change. Returns how many findings are open. */
    @Transactional
    public int scan(UUID projectId) {
        List<Row> rows = jdbc.sql("""
                SELECT k.id, k.seq, k.text, d.id, d.title, d.authored_at, d.external_ref, d.url, s.type, s.id
                FROM chunk k JOIN document d ON d.id = k.document_id JOIN source s ON s.id = d.source_id
                WHERE s.project_id = :p AND s.status = 'READY' AND s.type <> 'TESTIMONY'
                ORDER BY d.id, k.seq
                """).param("p", projectId)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getObject(10, UUID.class)))
                .list();

        Map<UUID, List<Row>> byDoc = new LinkedHashMap<>();
        rows.forEach(r -> byDoc.computeIfAbsent(r.documentId(), k -> new ArrayList<>()).add(r));

        List<StaleDetector.Change> changes = new ArrayList<>();
        List<StaleDetector.DocChunk> docs = new ArrayList<>();
        for (List<Row> doc : byDoc.values()) {
            Row first = doc.get(0);
            String ref = first.externalRef() == null ? "" : first.externalRef();
            String whole = String.join("\n", doc.stream().map(Row::text).toList());
            if (ref.startsWith("commits/") || StaleDetector.isCommitLog(whole)) {
                for (Row r : doc) {
                    changes.addAll(StaleDetector.commits(r.text(), r.chunkId(), first.url()));
                }
            } else if (ref.startsWith("pr/")) {
                pullRequest(first, whole).ifPresent(changes::add);
            } else if (DOC_TYPES.contains(first.sourceType()) && !ref.startsWith("issue/")
                    && (!"GITHUB".equals(first.sourceType()) || ref.toLowerCase().endsWith(".md"))) {
                String docRef = first.sourceId() + ":" + (ref.isBlank() ? first.title() : ref);
                for (Row r : doc) {
                    docs.add(new StaleDetector.DocChunk(r.documentId(), r.chunkId(), docRef, first.title(), first.authoredAt(), r.text()));
                }
            }
        }

        List<StaleDetector.Finding> found = StaleDetector.detect(docs, changes);
        List<UUID> kept = new ArrayList<>();
        for (StaleDetector.Finding f : found) {
            UUID id = jdbc.sql("""
                    INSERT INTO stale_finding (project_id, doc_ref, document_id, chunk_id, doc_title, doc_quote, term, replacement,
                                               kind, change_ref, change_text, change_at, change_chunk_id, change_url)
                    VALUES (:p, :ref, :d, :k, :title, :quote, :term, :rep, :kind, :cref, :ctext, :cat, :ck, :curl)
                    ON CONFLICT (project_id, doc_ref, term, change_ref) DO UPDATE SET
                        document_id = EXCLUDED.document_id, chunk_id = EXCLUDED.chunk_id, doc_title = EXCLUDED.doc_title,
                        doc_quote = EXCLUDED.doc_quote, change_chunk_id = EXCLUDED.change_chunk_id,
                        change_url = EXCLUDED.change_url, change_text = EXCLUDED.change_text
                    RETURNING id
                    """)
                    .param("p", projectId).param("ref", cut(f.doc().docRef(), 600)).param("d", f.doc().documentId())
                    .param("k", f.doc().chunkId()).param("title", cut(f.doc().title(), 300)).param("quote", cut(f.quote(), 600))
                    .param("term", cut(f.term(), 200)).param("rep", f.replacement() == null ? null : cut(f.replacement(), 200))
                    .param("kind", f.kind().name()).param("cref", cut(f.change().ref(), 80))
                    .param("ctext", cut(f.change().text(), 600)).param("cat", Timestamp.from(f.change().at()))
                    .param("ck", f.change().chunkId()).param("curl", f.change().url() == null ? null : cut(f.change().url(), 1000))
                    .query(UUID.class).single();
            kept.add(id);
        }
        // An open finding that no longer shows up has been fixed (the doc changed or the change went away).
        // Decisions the lead already made stay as history.
        if (kept.isEmpty()) {
            jdbc.sql("DELETE FROM stale_finding WHERE project_id = :p AND status = 'OPEN'").param("p", projectId).update();
        } else {
            jdbc.sql("DELETE FROM stale_finding WHERE project_id = :p AND status = 'OPEN' AND id NOT IN (:ids)")
                    .param("p", projectId).param("ids", kept).update();
        }
        return jdbc.sql("SELECT count(*) FROM stale_finding WHERE project_id = :p AND status = 'OPEN' AND document_id IS NOT NULL")
                .param("p", projectId).query(Integer.class).single();
    }

    /** Findings the viewer may see: they must be cleared for both the document and the code change. */
    @Transactional(readOnly = true)
    public List<FindingView> list(CurrentUser me, UUID projectId) {
        access.requireLead(me, projectId);
        return jdbc.sql("""
                SELECT f.* FROM stale_finding f
                JOIN chunk k ON k.id = f.chunk_id JOIN chunk c ON c.id = f.change_chunk_id
                WHERE f.project_id = :p AND f.document_id IS NOT NULL AND %s AND %s
                ORDER BY CASE f.status WHEN 'OPEN' THEN 0 WHEN 'CONFIRMED' THEN 1 ELSE 2 END, f.change_at DESC, f.doc_title
                """.formatted(access.sqlPredicate("k"), access.sqlPredicate("c")))
                .param("p", projectId).params(access.sqlParams(me))
                .query((rs, n) -> new FindingView(rs.getObject("id", UUID.class), rs.getString("doc_title"),
                        rs.getString("doc_quote"), rs.getString("term"), rs.getString("replacement"), rs.getString("kind"),
                        rs.getString("change_ref"), rs.getString("change_text"), rs.getTimestamp("change_at").toInstant(),
                        rs.getString("change_url"), rs.getString("status"), rs.getTimestamp("detected_at").toInstant(),
                        rs.getObject("document_id", UUID.class)))
                .list();
    }

    /** The lead's call: the document is out of date (CONFIRMED), still correct (DISMISSED), or undecided again (OPEN). */
    @Transactional
    public FindingView decide(CurrentUser me, UUID findingId, String status) {
        UUID projectId = jdbc.sql("SELECT project_id FROM stale_finding WHERE id = :id").param("id", findingId)
                .query(UUID.class).optional().orElseThrow(ApiException::notFound);
        access.requireLead(me, projectId);
        if (status == null || !Set.of("OPEN", "CONFIRMED", "DISMISSED").contains(status)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of("status", "CONFIRMED (out of date), DISMISSED (still correct) or OPEN"));
        }
        FindingView mine = list(me, projectId).stream().filter(f -> f.id().equals(findingId)).findFirst()
                .orElseThrow(ApiException::notFound);
        jdbc.sql("""
                UPDATE stale_finding SET status = :s, decided_by = CASE WHEN :s = 'OPEN' THEN NULL ELSE CAST(:me AS uuid) END,
                                         decided_at = CASE WHEN :s = 'OPEN' THEN NULL ELSE now() END
                WHERE id = :id
                """).param("s", status).param("me", me.id().toString()).param("id", findingId).update();
        audit.record(me.id(), "STALE_DOC_" + status, "stale_finding", findingId, projectId,
                Map.of("document", mine.docTitle(), "term", mine.term(), "change", mine.changeRef()));
        return list(me, projectId).stream().filter(f -> f.id().equals(findingId)).findFirst().orElseThrow();
    }

    /** Open findings the person leaving may see, newest change first, for exit-interview questions. */
    @Transactional(readOnly = true)
    public List<FindingView> openFor(UUID projectId, UUID userId, int limit) {
        Short clearance = jdbc.sql("SELECT clearance FROM app_user WHERE id = :u").param("u", userId).query(Short.class)
                .optional().orElse(null);
        if (clearance == null) {
            return List.of();
        }
        List<String> groups = jdbc.sql("""
                SELECT g.name FROM user_group g JOIN user_group_member m ON m.group_id = g.id WHERE m.user_id = :u
                """).param("u", userId).query(String.class).list();
        return jdbc.sql("""
                SELECT f.* FROM stale_finding f
                JOIN chunk k ON k.id = f.chunk_id JOIN chunk c ON c.id = f.change_chunk_id
                WHERE f.project_id = :p AND f.status = 'OPEN' AND f.document_id IS NOT NULL AND %s AND %s
                ORDER BY f.change_at DESC LIMIT :n
                """.formatted(access.sqlPredicate("k"), access.sqlPredicate("c")))
                .param("p", projectId).param("clearance", clearance).param("groups", AccessPolicy.toPgArray(groups))
                .param("n", limit)
                .query((rs, n) -> new FindingView(rs.getObject("id", UUID.class), rs.getString("doc_title"),
                        rs.getString("doc_quote"), rs.getString("term"), rs.getString("replacement"), rs.getString("kind"),
                        rs.getString("change_ref"), rs.getString("change_text"), rs.getTimestamp("change_at").toInstant(),
                        rs.getString("change_url"), rs.getString("status"), rs.getTimestamp("detected_at").toInstant(),
                        rs.getObject("document_id", UUID.class)))
                .list();
    }

    /** A closed pull request is a code change dated when it closed. */
    private static java.util.Optional<StaleDetector.Change> pullRequest(Row first, String text) {
        Matcher title = PR_TITLE.matcher(text);
        Matcher closed = PR_CLOSED.matcher(text);
        if (!title.find() || !closed.find()) {
            return java.util.Optional.empty();
        }
        Instant at = StaleDetector.parseDate(closed.group(1));
        return at == null ? java.util.Optional.empty()
                : java.util.Optional.of(new StaleDetector.Change("PR #" + title.group(1), title.group(2).strip(), at,
                        first.chunkId(), first.url()));
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
