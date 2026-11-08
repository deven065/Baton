package com.bramhastra.baton.review;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.handover.Handover;
import com.bramhastra.baton.handover.HandoverAccess;
import com.bramhastra.baton.reconstruct.stage.ScoreStage;
import com.bramhastra.baton.review.ReviewStateMachine.ReviewState;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CryptoService;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.source.Embedder;
import com.bramhastra.baton.source.Redactor;
import com.bramhastra.baton.source.SourceRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Exit interview. Each answer from the departing member is stored as a TESTIMONY document and chunk
 * (redacted like any source), linked as evidence to the claims the question was about, and the claims
 * are re-scored, all in one transaction (LLD L7). Answers to checklist questions become new claims.
 */
@Service
public class TestimonyService {

    static final int MAX_ANSWER = 2000;
    private static final Set<Redactor.Kind> FORBIDDEN = Set.of(Redactor.Kind.API_KEY, Redactor.Kind.PASSWORD,
            Redactor.Kind.SECRET);

    private final JdbcClient jdbc;
    private final HandoverAccess handoverAccess;
    private final Redactor redactor;
    private final CryptoService crypto;
    private final Embedder embedder;
    private final ScoreStage scorer;
    private final ClaimReviewService review;
    private final ClaimQueryService query;
    private final AuditService audit;
    private final ObjectMapper json;

    public TestimonyService(JdbcClient jdbc, HandoverAccess handoverAccess, Redactor redactor, CryptoService crypto,
                            Embedder embedder, ScoreStage scorer, ClaimReviewService review, ClaimQueryService query,
                            AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.handoverAccess = handoverAccess;
        this.redactor = redactor;
        this.crypto = crypto;
        this.embedder = embedder;
        this.scorer = scorer;
        this.review = review;
        this.query = query;
        this.audit = audit;
        this.json = json;
    }

    record Gap(UUID id, UUID handoverId, UUID claimId, UUID relatedClaimId, String kind, String question, String status) {
    }

    public record Answer(String answer, Sensitivity sensitivity, String owner) {
    }

    @Transactional
    public ClaimQueryService.GapView answer(CurrentUser me, UUID gapId, Answer req) {
        Gap g = lockGap(gapId);
        Handover h = handoverAccess.requireWorkspace(me, g.handoverId());
        if (!me.id().equals(h.departingUserId())) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "Only the departing member answers exit questions");
        }
        visibleOrNotFound(me, g);
        if (!Set.of("OPEN", "UNANSWERED").contains(g.status())) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "This question is already " + g.status());
        }
        String text = req.answer() == null ? "" : req.answer().strip();
        Map<String, String> problems = new LinkedHashMap<>();
        if (text.isEmpty() || text.length() > MAX_ANSWER) {
            problems.put("answer", "Answer must be 1 to " + MAX_ANSWER + " characters");
        }
        Sensitivity declared = req.sensitivity() == null ? Sensitivity.INTERNAL : req.sensitivity();
        if (!declared.atMost(me.clearance())) {
            problems.put("sensitivity", "You cannot classify above your own clearance");
        }
        Redactor.Result redacted = redactor.redact(text);
        if (redacted.redactions().stream().anyMatch(r -> FORBIDDEN.contains(r.kind()))) {
            problems.put("answer", "Don't paste keys or passwords; say where they are kept and who can rotate them");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed", problems);
        }
        review.openForEdits(me, h);

        // The answer is at least as sensitive as the claims it talks about.
        List<UUID> linked = java.util.stream.Stream.of(g.claimId(), g.relatedClaimId()).filter(java.util.Objects::nonNull).toList();
        Sensitivity sens = declared;
        Set<String> acl = new TreeSet<>();
        for (UUID c : linked) {
            var row = jdbc.sql("SELECT sensitivity, acl_groups FROM claim WHERE id = :id").param("id", c)
                    .query((rs, n) -> new Object[] {Sensitivity.ofLevel(rs.getShort(1)), SourceRepository.textArray(rs.getArray(2))})
                    .single();
            if (((Sensitivity) row[0]).ordinal() > sens.ordinal()) {
                sens = (Sensitivity) row[0];
            }
            @SuppressWarnings("unchecked")
            List<String> groups = (List<String>) row[1];
            acl.addAll(groups);
        }

        // Store as testimony: Q and A in one chunk; the evidence quote is exactly the answer.
        UUID sourceId = testimonySource(h, me);
        String chunkText = "Q: " + g.question() + "\nA: " + redacted.text();
        int start = chunkText.indexOf("\nA: ") + 4;
        UUID documentId = jdbc.sql("""
                INSERT INTO document (source_id, external_ref, title, author, authored_at, mime, sensitivity, acl_groups)
                VALUES (:s, :ref, :t, :a, :at, 'text/plain', :sens, CAST(:acl AS text[]))
                RETURNING id
                """)
                .param("s", sourceId).param("ref", "gap/" + g.id())
                .param("t", "Exit interview: " + truncate(g.question(), 200)).param("a", h.departingName())
                .param("at", Timestamp.from(Instant.now())).param("sens", sens.level())
                .param("acl", AccessPolicy.toPgArray(acl))
                .query(UUID.class).single();
        for (Redactor.Redaction r : redacted.redactions()) {
            CryptoService.Sealed sealed = crypto.encrypt(r.original().getBytes(StandardCharsets.UTF_8));
            jdbc.sql("""
                    INSERT INTO redaction_token (document_id, token, kind, ciphertext, iv) VALUES (:d, :t, :k, :c, :iv)
                    """).param("d", documentId).param("t", r.token()).param("k", r.kind().name())
                    .param("c", sealed.ciphertext()).param("iv", sealed.iv()).update();
        }
        String embedding = embedder.embed(chunkText).map(Embedder::toPgVector).orElse(null);
        UUID chunkId = jdbc.sql("""
                INSERT INTO chunk (document_id, seq, text, embedding, sensitivity, acl_groups, start_offset, end_offset)
                VALUES (:d, 0, :t, CAST(:e AS vector), :s, CAST(:acl AS text[]), 0, :len)
                RETURNING id
                """)
                .param("d", documentId).param("t", chunkText).param("e", embedding).param("s", sens.level())
                .param("acl", AccessPolicy.toPgArray(acl)).param("len", chunkText.length())
                .query(UUID.class).single();
        jdbc.sql("""
                UPDATE source SET document_count = document_count + 1, chunk_count = chunk_count + 1,
                                  redaction_count = redaction_count + :r, last_synced_at = now()
                WHERE id = :id
                """).param("r", redacted.redactions().size()).param("id", sourceId).update();

        String quote = chunkText.substring(start);
        boolean quotable = quote.length() >= 12;
        if (linked.isEmpty()) {
            createClaimFromAnswer(me, h, g, quote, chunkId, start, sens, acl);
        }
        for (UUID claimId : linked) {
            if (quotable) {
                addEvidence(claimId, chunkId, quote, start, chunkText.length());
            }
            applyToClaim(me, claimId, g, req.owner());
        }

        jdbc.sql("""
                UPDATE gap_question SET status = 'ANSWERED', answer_text = :a, answer_sensitivity = :s,
                                        answered_by = :me, answered_at = now(), testimony_chunk_id = :c
                WHERE id = :id
                """).param("a", redacted.text()).param("s", sens.level()).param("me", me.id())
                .param("c", chunkId).param("id", g.id()).update();
        scorer.rescore(h.id());
        audit.record(me.id(), "GAP_ANSWERED", "gap_question", g.id(), h.projectId(),
                Map.of("kind", g.kind(), "sensitivity", sens.name(), "redactions", redacted.redactions().size()));
        return query.gaps(h.id(), me).stream().filter(x -> x.id().equals(g.id())).findFirst().orElseThrow();
    }

    /** Lead or leaver can skip a question; in a sudden exit it stays visible as unanswered for the lead. */
    @Transactional
    public ClaimQueryService.GapView skip(CurrentUser me, UUID gapId, String reason) {
        Gap g = lockGap(gapId);
        Handover h = handoverAccess.requireWorkspace(me, g.handoverId());
        visibleOrNotFound(me, g);
        if (!Set.of("OPEN", "UNANSWERED").contains(g.status())) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "This question is already " + g.status());
        }
        jdbc.sql("""
                UPDATE gap_question SET status = 'SKIPPED', answer_text = :r, answered_by = :me, answered_at = now()
                WHERE id = :id
                """).param("r", reason == null || reason.isBlank() ? "Skipped" : truncate(reason.strip(), 500))
                .param("me", me.id()).param("id", g.id()).update();
        audit.record(me.id(), "GAP_SKIPPED", "gap_question", g.id(), h.projectId(), Map.of("kind", g.kind()));
        return query.gaps(h.id(), me).stream().filter(x -> x.id().equals(g.id())).findFirst().orElseThrow();
    }

    // ───────────── helpers ─────────────

    private void applyToClaim(CurrentUser me, UUID claimId, Gap g, String owner) {
        ClaimReviewService.Locked c = review.lock(claimId);
        Map<String, Object> attributes = new LinkedHashMap<>(c.attributes());
        ReviewState state = c.reviewState();
        boolean changed = false;
        if (owner != null && !owner.isBlank() && g.kind().equals("MISSING_OWNER")) {
            attributes.put("owner", truncate(owner.strip(), 120));
            attributes.put("ownerIsDeparting", false);
            attributes.put("ownerSource", "exit interview");
            changed = true;
        }
        if (state == ReviewState.NEEDS_INFO) {
            ReviewStateMachine.check(state, ReviewState.PENDING); // answer received
            state = ReviewState.PENDING;
            changed = true;
        }
        if (!changed) {
            return;
        }
        ClaimReviewService.Locked after = new ClaimReviewService.Locked(c.id(), c.handoverId(), c.version() + 1, state,
                c.title(), c.body(), attributes, c.critical());
        jdbc.sql("""
                UPDATE claim SET attributes = CAST(:a AS jsonb), review_state = :rs, version = version + 1, updated_at = now()
                WHERE id = :id
                """).param("a", json.writeValueAsString(attributes)).param("rs", state.name()).param("id", claimId).update();
        review.revision(claimId, after.version(), me.id(), c.snapshot(), after.snapshot(), "Exit interview answer");
    }

    private void createClaimFromAnswer(CurrentUser me, Handover h, Gap g, String answer, UUID chunkId, int start,
                                       Sensitivity sens, Set<String> acl) {
        String q = g.question().toLowerCase(Locale.ROOT);
        String type;
        String title;
        boolean critical = false;
        if (q.contains("deploy")) {
            type = "PROCESS";
            title = "How to deploy to production";
        } else if (q.contains("accounts")) {
            type = "ACCESS";
            title = "Accounts and admin rights to transfer";
            critical = true;
        } else if (q.contains("secrets")) {
            type = "PROCESS";
            title = "Where secrets are kept and how to rotate them";
            critical = true;
        } else if (q.contains("locally")) {
            type = "PROCESS";
            title = "How to run the project locally";
        } else if (q.contains("deadline")) {
            type = "OPEN_WORK";
            title = "Next deadline and what must be done before it";
            critical = true;
        } else {
            type = "OPEN_QUESTION";
            title = truncate(g.question(), 280);
        }
        UUID claimId = jdbc.sql("""
                INSERT INTO claim (handover_id, type, title, body, attributes, status, confidence, reasons, sensitivity,
                                   acl_groups, critical, review_state, origin)
                VALUES (:h, :type, :title, :body, CAST(:a AS jsonb), 'SINGLE_SOURCE', 0, '[]'::jsonb, :s,
                        CAST(:acl AS text[]), :cr, 'PENDING', 'TESTIMONY')
                RETURNING id
                """)
                .param("h", h.id()).param("type", type).param("title", title + " (from " + h.departingName() + ")")
                .param("body", truncate(answer, 2000))
                .param("a", json.writeValueAsString(Map.of("answeredBy", h.departingName(), "gapId", g.id().toString())))
                .param("s", sens.level()).param("acl", AccessPolicy.toPgArray(acl)).param("cr", critical)
                .query(UUID.class).single();
        if (answer.length() >= 12) {
            addEvidence(claimId, chunkId, answer, start, start + answer.length());
        }
        review.revision(claimId, 0, me.id(), null, Map.of("reviewState", "PENDING", "title", title), "Created from exit interview");
        jdbc.sql("UPDATE gap_question SET claim_id = :c WHERE id = :g").param("c", claimId).param("g", g.id()).update();
    }

    private void addEvidence(UUID claimId, UUID chunkId, String quote, int start, int end) {
        jdbc.sql("""
                INSERT INTO evidence (claim_id, chunk_id, quote, model_quote, match_score, start_offset, end_offset,
                                      valid, verified_at)
                VALUES (:c, :k, :q, :q, 1.0, :s, :e, TRUE, now())
                """).param("c", claimId).param("k", chunkId).param("q", quote).param("s", start).param("e", end).update();
    }

    /** One TESTIMONY source per handover; each answer carries its own sensitivity at document level. */
    private UUID testimonySource(Handover h, CurrentUser me) {
        return jdbc.sql("""
                SELECT id FROM source WHERE project_id = :p AND type = 'TESTIMONY' AND config->>'handoverId' = :h
                """).param("p", h.projectId()).param("h", h.id().toString()).query(UUID.class).optional()
                .orElseGet(() -> jdbc.sql("""
                        INSERT INTO source (project_id, type, title, config, sensitivity, acl_groups,
                                            authorisation_attested, attested_by, status, last_synced_at)
                        VALUES (:p, 'TESTIMONY', :t, CAST(:c AS jsonb), 1, '{}', TRUE, :me, 'READY', now())
                        RETURNING id
                        """).param("p", h.projectId()).param("t", "Exit interview · " + h.departingName())
                        .param("c", json.writeValueAsString(Map.of("handoverId", h.id().toString())))
                        .param("me", me.id()).query(UUID.class).single());
    }

    private void visibleOrNotFound(CurrentUser me, Gap g) {
        boolean visible = query.gaps(g.handoverId(), me).stream().anyMatch(x -> x.id().equals(g.id()));
        if (!visible) {
            throw ApiException.notFound();
        }
    }

    private Gap lockGap(UUID id) {
        return jdbc.sql("""
                SELECT id, handover_id, claim_id, related_claim_id, kind, question, status
                FROM gap_question WHERE id = :id FOR UPDATE
                """).param("id", id)
                .query((rs, n) -> new Gap(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6),
                        rs.getString(7)))
                .optional().orElseThrow(ApiException::notFound);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
