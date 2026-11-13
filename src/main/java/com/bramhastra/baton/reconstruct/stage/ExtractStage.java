package com.bramhastra.baton.reconstruct.stage;

import com.bramhastra.baton.common.SafeLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.ai.LlmClient;
import com.bramhastra.baton.reconstruct.ClaimRepository;
import com.bramhastra.baton.reconstruct.PipelineContext;
import com.bramhastra.baton.reconstruct.PipelineStage;
import com.bramhastra.baton.reconstruct.ReconstructJobHandler;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * Sends batches of REDACTED chunks to the model, wrapped in &lt;chunk&gt; untrusted-data tags with short ids
 * (c1, c2...) so a hallucinated id cannot point at a real chunk. Stores claims and draft evidence only;
 * the verifier decides what counts as proof. A bad batch is retried once, then skipped and reported.
 */
@Component
@Order(20)
public class ExtractStage implements PipelineStage {

    private static final Logger log = LoggerFactory.getLogger(ExtractStage.class);

    static final Set<String> TYPES = Set.of("DECISION", "OPEN_WORK", "OWNER", "RISK", "DEPENDENCY", "BLOCKER",
            "RECURRING_ISSUE", "CONTACT", "OPEN_QUESTION", "PROCESS", "ACCESS");

    private final BatchPlanner planner;
    private final LlmClient llm;
    private final ClaimRepository claims;
    private final ObjectMapper json;
    private final String model;
    private final String systemPrompt;

    public ExtractStage(BatchPlanner planner, LlmClient llm, ClaimRepository claims, ObjectMapper json,
                        @Value("${baton.groq.extract-model}") String model) throws IOException {
        this.planner = planner;
        this.llm = llm;
        this.claims = claims;
        this.json = json;
        this.model = model;
        try (InputStream in = new ClassPathResource("prompts/" + ReconstructJobHandler.PROMPT_VERSION + ".txt").getInputStream()) {
            this.systemPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public String name() {
        return "EXTRACT";
    }

    @Override
    public int weight() {
        return 60;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Output(List<Extracted> claims) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Extracted(String type, String title, String body, String subject, String value,
                     Map<String, Object> attributes, Boolean critical, String reasoning, List<Ev> evidence) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ev(String chunk, String quote) {
    }

    @Override
    public void execute(PipelineContext ctx) {
        claims.deleteRunClaims(ctx.run().id(), "EXTRACTED");
        List<BatchPlanner.Batch> batches = planner.plan(ctx.handover().projectId(), ctx.aliases(), Instant.now());
        ctx.count("batches", batches.size());
        int failed = 0;
        int startPercent = 5;
        for (int b = 0; b < batches.size(); b++) {
            BatchPlanner.Batch batch = batches.get(b);
            ctx.reportWithin(startPercent, weight(), (double) b / Math.max(1, batches.size()),
                    "Batch " + (b + 1) + "/" + batches.size());
            try {
                int n = extract(ctx, batch);
                ctx.count("extracted", n);
            } catch (LlmClient.LlmUnavailableException | LlmClient.LlmClientError | BadOutput e) {
                failed++;
                log.warn("Batch {} failed: {}", b + 1, SafeLog.describe(e));
            }
        }
        if (failed > 0) {
            ctx.warn(failed + " of " + batches.size() + " batches not analysed (AI errors); re-run to retry them");
        }
        ctx.count("batchesFailed", failed);
        if (!batches.isEmpty() && failed == batches.size()) {
            throw new StageSoftFailure("no batch could be analysed; only repository signals are available");
        }
    }

    private int extract(PipelineContext ctx, BatchPlanner.Batch batch) {
        Map<String, BatchPlanner.Candidate> byShortId = new LinkedHashMap<>();
        Set<String> flagged = new HashSet<>();
        StringBuilder user = new StringBuilder();
        user.append("Departing member: ").append(ctx.handover().departingName())
                .append(" (may appear as: ").append(String.join(", ", ctx.aliases())).append(")\n");
        user.append("Today is ").append(LocalDate.now(ZoneOffset.UTC)).append(". Chunk dates are when the source was written.\n\n");
        int i = 1;
        for (BatchPlanner.Candidate c : batch.chunks()) {
            String id = "c" + i++;
            byShortId.put(id, c);
            if (PromptGuard.suspicious(c.text())) {
                flagged.add(id);
                ctx.warn("Source \"" + c.title() + "\" contains instructions aimed at the AI; they were treated as data");
            }
            user.append("<chunk id=\"").append(id).append("\" source=\"").append(c.sourceType())
                    .append("\" title=\"").append(attr(c.title())).append("\" author=\"").append(attr(c.author()))
                    .append("\" date=\"").append(c.authoredAt() == null ? "" : c.authoredAt().atZone(ZoneOffset.UTC).toLocalDate())
                    .append("\">\n").append(c.text().replace("</chunk", "</ chunk")).append("\n</chunk>\n\n");
        }

        LlmClient.LlmRequest req = new LlmClient.LlmRequest(ReconstructJobHandler.PROMPT_VERSION, model, systemPrompt,
                user.toString(), 0.1, 4096, true);
        LlmClient.LlmResponse response = llm.complete(req);
        ctx.modelUsed(response.model());
        tokens(ctx, response);
        Output out;
        try {
            out = parse(response.content());
        } catch (BadOutput first) {
            LlmClient.LlmRequest repair = new LlmClient.LlmRequest(req.promptId(), model, systemPrompt,
                    req.user() + "\nYour previous reply was not valid JSON (" + first.getMessage()
                            + "). Reply with ONLY the JSON object.", 0.0, 4096, true);
            LlmClient.LlmResponse again = llm.complete(repair);
            tokens(ctx, again);
            out = parse(again.content());
        }

        int stored = 0;
        for (Extracted e : out.claims() == null ? List.<Extracted>of() : out.claims()) {
            String type = e.type() == null ? "" : e.type().strip().toUpperCase(Locale.ROOT);
            if (!TYPES.contains(type) || e.title() == null || e.title().isBlank()) {
                continue;
            }
            List<Ev> evidence = e.evidence() == null ? List.of() : e.evidence();
            List<Ev> known = evidence.stream().filter(ev -> ev.chunk() != null && byShortId.containsKey(ev.chunk().strip())
                    && ev.quote() != null && !ev.quote().isBlank()).toList();
            int unknown = evidence.size() - known.size();
            boolean reasoned = e.reasoning() != null && !e.reasoning().isBlank();
            if (known.isEmpty() && !reasoned) {
                continue; // nothing to check and no stated reasoning: drop it
            }
            Map<String, Object> attrs = new LinkedHashMap<>();
            if (e.attributes() != null) {
                e.attributes().forEach((k, v) -> {
                    if (v != null && attrs.size() < 12) {
                        attrs.put(k, v.toString().length() > 300 ? v.toString().substring(0, 300) : v.toString());
                    }
                });
            }
            if (e.value() != null && !e.value().isBlank()) {
                attrs.put("value", e.value().strip());
            }
            if (reasoned) {
                attrs.put("reasoning", e.reasoning().strip());
            }
            List<String> flags = new ArrayList<>();
            if (known.stream().anyMatch(ev -> flagged.contains(ev.chunk().strip()))) {
                flags.add("PROMPT_INJECTION_SUSPECTED");
            }
            List<String> reasons = new ArrayList<>();
            if (unknown > 0) {
                reasons.add(unknown + " citation(s) pointed at text that was not provided and were dropped");
            }
            String subject = e.subject() == null || e.subject().isBlank() ? null
                    : truncate(e.subject().strip().toLowerCase(Locale.ROOT), 200);
            UUID claimId = claims.insert(new ClaimRepository.NewClaim(ctx.handover().id(), ctx.run().id(), type,
                    truncate(e.title().strip(), 300), e.body() == null ? null : truncate(e.body().strip(), 2000),
                    attrs, subject, batch.sensitivity(), batch.acl(), Boolean.TRUE.equals(e.critical()),
                    "EXTRACTED", flags, reasons));
            for (Ev ev : known) {
                claims.insertEvidenceDraft(claimId, byShortId.get(ev.chunk().strip()).chunkId(), truncate(ev.quote(), 1000));
            }
            stored++;
        }
        return stored;
    }

    /** Token use per run, so the benchmark can report cost. Cached replays cost nothing and are not counted. */
    static void tokens(PipelineContext ctx, LlmClient.LlmResponse r) {
        if (!"cache".equals(r.source())) {
            ctx.count("tokensIn", r.tokensIn());
            ctx.count("tokensOut", r.tokensOut());
        }
    }

    private Output parse(String content) {
        if (content == null || content.isBlank()) {
            throw new BadOutput("empty reply");
        }
        String s = content.strip();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("```\\s*$", "");
        }
        try {
            Output o = json.readValue(s, Output.class);
            if (o == null || o.claims() == null) {
                throw new BadOutput("missing \"claims\" array");
            }
            return o;
        } catch (tools.jackson.core.JacksonException e) {
            throw new BadOutput("invalid JSON");
        }
    }

    private static String attr(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("\n", " ");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    static class BadOutput extends RuntimeException {
        BadOutput(String message) {
            super(message);
        }
    }
}
