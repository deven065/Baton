package com.bramhastra.baton.reconstruct.stage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.ai.LlmClient;
import com.bramhastra.baton.common.SafeLog;
import com.bramhastra.baton.reconstruct.ClaimRepository;
import com.bramhastra.baton.reconstruct.PipelineContext;
import com.bramhastra.baton.reconstruct.PipelineStage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * Model-graded quality check. Code has already proved each quote exists in the source (VERIFY); this stage asks
 * the model a narrower question: does the quote actually support the claim as written? The verdict is stored on
 * the claim ({@code modelCheck}: SUPPORTED, PARTIAL or UNSUPPORTED) and {@link com.bramhastra.baton.evidence.ClaimScorer}
 * turns an UNSUPPORTED verdict into a claim that a person must decide before approval. The model can only make a
 * claim weaker, never stronger. In offline mode there is no model, so the stage records that nothing was checked.
 */
@Component
@Order(45)
public class CheckStage implements PipelineStage {

    private static final Logger log = LoggerFactory.getLogger(CheckStage.class);
    public static final String PROMPT_ID = "check.v1";
    static final int BATCH = 12;
    static final Set<String> VERDICTS = Set.of("SUPPORTED", "PARTIAL", "UNSUPPORTED");

    private final ClaimRepository claims;
    private final LlmClient llm;
    private final ObjectMapper json;
    private final String model;
    private final String systemPrompt;

    public CheckStage(ClaimRepository claims, LlmClient llm, ObjectMapper json,
                      @Value("${baton.groq.check-model:${baton.groq.extract-model}}") String model) throws IOException {
        this.claims = claims;
        this.llm = llm;
        this.json = json;
        this.model = model;
        try (InputStream in = new ClassPathResource("prompts/" + PROMPT_ID + ".txt").getInputStream()) {
            this.systemPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public String name() {
        return "CHECK";
    }

    @Override
    public int weight() {
        return 8;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Output(List<Verdict> checks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Verdict(String id, String verdict, String reason) {
    }

    record Item(UUID claimId, String claim, List<String> quotes) {
    }

    @Override
    public void execute(PipelineContext ctx) {
        UUID handoverId = ctx.handover().id();
        Map<UUID, List<String>> quotes = claims.evidence(handoverId).stream().filter(ClaimRepository.EvidenceRow::valid)
                .collect(Collectors.groupingBy(ClaimRepository.EvidenceRow::claimId,
                        Collectors.mapping(ClaimRepository.EvidenceRow::quote, Collectors.toList())));
        // Only machine claims nobody has reviewed yet, with a verified quote, and not already checked.
        List<Item> todo = new ArrayList<>();
        for (ClaimRepository.ClaimRow c : claims.claims(handoverId)) {
            if (!"PENDING".equals(c.reviewState()) || c.attr("modelCheck") != null || !quotes.containsKey(c.id())
                    || !"EXTRACTED".equals(c.origin())) {
                continue;
            }
            String text = c.title() + (c.body() == null || c.body().isBlank() ? "" : ". " + c.body());
            todo.add(new Item(c.id(), text, quotes.get(c.id()).stream().limit(3).toList()));
        }
        if (todo.isEmpty()) {
            return;
        }
        int checked = 0;
        int disputed = 0;
        int failed = 0;
        int startPercent = 77; // the stages before this one carry 80 of 103 weight
        for (int i = 0; i < todo.size(); i += BATCH) {
            List<Item> batch = todo.subList(i, Math.min(todo.size(), i + BATCH));
            ctx.reportWithin(startPercent, weight(), (double) i / todo.size(),
                    "Checking claims " + (i + 1) + "–" + (i + batch.size()) + " of " + todo.size());
            Map<String, Verdict> verdicts;
            try {
                verdicts = check(ctx, batch);
            } catch (LlmClient.LlmUnavailableException e) {
                if (checked == 0 && failed == 0 && e.getMessage() != null && e.getMessage().startsWith("Offline")) {
                    ctx.count("modelCheckSkipped", todo.size());
                    return; // offline mode: no model to ask
                }
                failed += batch.size();
                log.warn("Check batch failed: {}", SafeLog.describe(e));
                continue;
            } catch (LlmClient.LlmClientError | ExtractStage.BadOutput e) {
                failed += batch.size();
                log.warn("Check batch failed: {}", SafeLog.describe(e));
                continue;
            }
            for (int k = 0; k < batch.size(); k++) {
                Verdict v = verdicts.get("k" + (k + 1));
                if (v == null) {
                    failed++;
                    continue;
                }
                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put("modelCheck", v.verdict());
                if (v.reason() != null && !v.reason().isBlank()) {
                    attrs.put("modelCheckReason", truncate(v.reason().strip(), 300));
                }
                claims.mergeAttributes(batch.get(k).claimId(), attrs);
                checked++;
                if (!"SUPPORTED".equals(v.verdict())) {
                    disputed++;
                }
            }
        }
        ctx.count("modelChecked", checked);
        ctx.count("modelDisputed", disputed);
        if (failed > 0) {
            ctx.count("modelCheckFailed", failed);
            ctx.warn(failed + " claims could not be checked by the model; they keep their code-verified status");
        }
    }

    Map<String, Verdict> check(PipelineContext ctx, List<Item> batch) {
        StringBuilder user = new StringBuilder();
        for (int k = 0; k < batch.size(); k++) {
            Item it = batch.get(k);
            user.append("<claim id=\"k").append(k + 1).append("\">\n").append(clean(it.claim())).append('\n');
            for (String q : it.quotes()) {
                user.append("<quote>").append(clean(q)).append("</quote>\n");
            }
            user.append("</claim>\n\n");
        }
        LlmClient.LlmResponse r = llm.complete(new LlmClient.LlmRequest(PROMPT_ID, model, systemPrompt,
                user.toString(), 0.0, 1500, true));
        ExtractStage.tokens(ctx, r);
        return parse(r.content());
    }

    Map<String, Verdict> parse(String content) {
        if (content == null || content.isBlank()) {
            throw new ExtractStage.BadOutput("empty reply");
        }
        String s = content.strip();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("```\\s*$", "");
        }
        Output o;
        try {
            o = json.readValue(s, Output.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw new ExtractStage.BadOutput("invalid JSON");
        }
        Map<String, Verdict> out = new LinkedHashMap<>();
        if (o == null || o.checks() == null) {
            return out;
        }
        for (Verdict v : o.checks()) {
            if (v == null || v.id() == null || v.verdict() == null) {
                continue;
            }
            String verdict = v.verdict().strip().toUpperCase(Locale.ROOT);
            if (VERDICTS.contains(verdict)) {
                out.put(v.id().strip(), new Verdict(v.id().strip(), verdict, v.reason()));
            }
        }
        return out;
    }

    private static String clean(String s) {
        return s.replace("</quote", "</ quote").replace("</claim", "</ claim");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
