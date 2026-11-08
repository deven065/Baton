package com.bramhastra.baton.source;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.CurrentUserProvider;

@RestController
@RequestMapping("/api")
public class ChunkController {

    private final JdbcClient jdbc;
    private final AccessPolicy access;
    private final CurrentUserProvider currentUser;
    private final SearchService search;

    public ChunkController(JdbcClient jdbc, AccessPolicy access, CurrentUserProvider currentUser,
                           SearchService search) {
        this.jdbc = jdbc;
        this.access = access;
        this.currentUser = currentUser;
        this.search = search;
    }

    /**
     * Evidence drawer: one chunk with its document and source. The access rule is part of the WHERE
     * clause, so a restricted chunk id returns 404 exactly like a missing one (STRIDE: IDOR).
     */
    @GetMapping("/chunks/{chunkId}")
    public ChunkView chunk(@PathVariable UUID chunkId) {
        CurrentUser me = currentUser.get();
        return jdbc.sql("""
                SELECT c.id, c.seq, c.text, c.sensitivity, c.start_offset, c.end_offset,
                       d.id AS document_id, d.title AS document_title, d.author, d.authored_at, d.url,
                       s.id AS source_id, s.type AS source_type, s.title AS source_title, s.project_id
                FROM chunk c
                JOIN document d ON d.id = c.document_id
                JOIN source s ON s.id = d.source_id
                JOIN project_member pm ON pm.project_id = s.project_id AND pm.user_id = :userId
                WHERE c.id = :chunkId AND %s
                """.formatted(access.sqlPredicate("c")))
                .param("chunkId", chunkId)
                .param("userId", me.id())
                .params(access.sqlParams(me))
                .query((rs, n) -> {
                    Timestamp at = rs.getTimestamp("authored_at");
                    return new ChunkView(rs.getObject("id", UUID.class), rs.getInt("seq"), rs.getString("text"),
                            Sensitivity.ofLevel(rs.getShort("sensitivity")), rs.getInt("start_offset"),
                            rs.getInt("end_offset"), rs.getObject("document_id", UUID.class),
                            rs.getString("document_title"), rs.getString("author"),
                            at == null ? null : at.toInstant(), rs.getString("url"),
                            rs.getObject("source_id", UUID.class), SourceType.valueOf(rs.getString("source_type")),
                            rs.getString("source_title"), rs.getObject("project_id", UUID.class));
                })
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    @GetMapping("/projects/{projectId}/search")
    public List<SearchService.Hit> search(@PathVariable UUID projectId, @RequestParam String q,
                                          @RequestParam(defaultValue = "8") int limit) {
        CurrentUser me = currentUser.get();
        access.requireMember(me, projectId);
        if (q.isBlank() || q.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Query must be 1–500 characters");
        }
        return search.search(projectId, me, q.strip(), Math.max(1, Math.min(limit, 50)));
    }

    public record ChunkView(UUID id, int seq, String text, Sensitivity sensitivity, int startOffset, int endOffset,
                            UUID documentId, String documentTitle, String author, Instant authoredAt, String url,
                            UUID sourceId, SourceType sourceType, String sourceTitle, UUID projectId) {
    }
}
