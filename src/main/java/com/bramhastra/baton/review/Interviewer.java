package com.bramhastra.baton.review;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.ai.LlmClient;
import com.bramhastra.baton.common.SafeLog;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * The interviewer in AI interview mode. After the person leaving answers a question, it reads the answer and decides
 * whether one targeted follow-up is needed: a vague owner, no place to check, no date for a change, unsure wording.
 *
 * <p>A language model writes the follow-up when the workspace has one; otherwise, or when the model fails or replies
 * with something unusable, built-in rules do. The answer it reads is already redacted, it is wrapped as data so
 * instructions inside it are not followed, and whatever comes back is checked before it is shown: one short question,
 * not a repeat, never asking for a secret.
 */
@Component
public class Interviewer {

    private static final Logger log = LoggerFactory.getLogger(Interviewer.class);
    public static final String PROMPT_ID = "interview.v1";

    public record FollowUp(String question, String reason, String askedBy) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Output(String followUp, String reason) {
    }

    private static final Pattern UNSURE = Pattern.compile(
            "\\b(not sure|i think|i guess|maybe|probably|perhaps|not certain|don'?t remember|can'?t remember|"
                    + "no idea|somewhere|someone|somebody|ask around|kind of|sort of|tbd|later|i believe)\\b");
    private static final Pattern PERSON = Pattern.compile(
            "(@\\w+)|\\b(lead|manager|engineer|admin|owner|team|desk|ops|support|vendor|founder|head|cto|ceo)\\b"
                    + "|(?<!^)(?<![.!?]\\s)\\b[A-Z][a-z]{2,}\\b");
    private static final Pattern PLACE = Pattern.compile(
            "(https?://|www\\.|\\.md\\b|\\.pdf\\b|\\.docx?\\b|#[\\w-]+|\\b(repo|repository|wiki|drive|folder|doc|docs|"
                    + "document|readme|runbook|channel|notion|confluence|jira|sheet|spreadsheet|vault|1password|"
                    + "bitwarden|dashboard|console|inbox|email)\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile(
            "\\b(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[/.-]\\d{1,2}([/.-]\\d{2,4})?|(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s*\\d"
                    + "|\\d{1,2}\\s*(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)|yesterday|last (week|month|year)|"
                    + "(mon|tues|wednes|thurs|fri|satur|sun)day|\\bq[1-4]\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_ASK = Pattern.compile("\\b(password|passcode|api key|secret key|token|pin)\\b.*\\b(what|share|send|tell|give)\\b"
            + "|\\b(what|share|send|tell|give)\\b.*\\b(password|passcode|api key|secret key|token|pin)\\b", Pattern.CASE_INSENSITIVE);

    private final LlmClient llm;
    private final ObjectMapper json;
    private final String model;
    private final String system;

    public Interviewer(LlmClient llm, ObjectMapper json,
                       @Value("${baton.groq.interview-model:${baton.groq.extract-model}}") String model) throws IOException {
        this.llm = llm;
        this.json = json;
        this.model = model;
        try (InputStream in = new ClassPathResource("prompts/" + PROMPT_ID + ".txt").getInputStream()) {
            this.system = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** One follow-up for this answer, or empty when the answer is already specific enough to act on. */
    public Optional<FollowUp> followUp(String kind, String question, String why, String answer) {
        try {
            String user = "<question>" + clean(question) + "</question>\n<why>" + clean(why) + "</why>\n<answer>"
                    + clean(answer) + "</answer>";
            LlmClient.LlmResponse r = llm.complete(new LlmClient.LlmRequest(PROMPT_ID, model, system, user, 0.2, 200, true));
            Output o = parse(r.content());
            if (o == null || o.followUp() == null || o.followUp().isBlank() || "null".equalsIgnoreCase(o.followUp().strip())) {
                return Optional.empty();
            }
            return accept(o.followUp(), o.reason(), question, "ai").or(() -> rules(kind, answer));
        } catch (LlmClient.LlmUnavailableException | LlmClient.LlmClientError e) {
            // No model in this workspace (offline), or it failed: the built-in interviewer takes over.
            if (e.getMessage() == null || !e.getMessage().startsWith("Offline")) {
                log.warn("Interviewer fell back to rules: {}", SafeLog.describe(e));
            }
            return rules(kind, answer);
        } catch (RuntimeException e) {
            log.warn("Interviewer reply was unusable: {}", SafeLog.describe(e));
            return rules(kind, answer);
        }
    }

    /** The built-in interviewer: targeted questions for the gaps that most often leave a successor stuck. */
    static Optional<FollowUp> rules(String kind, String answer) {
        String a = answer == null ? "" : answer.strip();
        String lower = a.toLowerCase(Locale.ROOT);
        boolean unsure = UNSURE.matcher(lower).find();
        boolean brief = a.length() < 40;
        boolean person = PERSON.matcher(a).find();
        boolean place = PLACE.matcher(a).find();
        boolean dated = DATE.matcher(a).find();
        String q = null;
        String why = null;
        switch (kind) {
            case "MISSING_OWNER" -> {
                if (!person || unsure) {
                    q = "Who exactly will own this after you leave (name and role), and do they already know?";
                    why = "No named owner";
                }
            }
            case "MISSING_EVIDENCE" -> {
                if (!place) {
                    q = "Where can the next person check this for themselves: a link, file, channel or person?";
                    why = "Nowhere to check it";
                }
            }
            case "CONFLICT" -> {
                if (!dated || unsure) {
                    q = "When did it change, and who made that decision?";
                    why = "No date or decision-maker for the change";
                }
            }
            case "STALE_DOC" -> {
                if (brief || unsure || !place) {
                    q = "Which document should be updated, and what should it say now?";
                    why = "No fix for the outdated document";
                }
            }
            default -> {
                if (brief || (!place && !person)) {
                    q = "Could you give the concrete steps, and say where the details are written down?";
                    why = "Too short to act on";
                }
            }
        }
        if (q == null && unsure) {
            q = "You sounded unsure. Who would know for certain, and how can the next person reach them?";
            why = "Unsure answer";
        }
        return q == null ? Optional.empty() : Optional.of(new FollowUp(q, why, "rules"));
    }

    /** Keeps a model's follow-up only when it is one short, new, safe question. */
    static Optional<FollowUp> accept(String followUp, String reason, String original, String by) {
        String q = followUp.strip().replaceAll("\\s+", " ");
        if (q.startsWith("\"") && q.endsWith("\"") && q.length() > 2) {
            q = q.substring(1, q.length() - 1).strip();
        }
        if (q.length() < 10 || q.length() > 300 || !q.endsWith("?") || q.contains("<") || q.contains("{")
                || q.chars().filter(ch -> ch == '?').count() > 2 || SECRET_ASK.matcher(q).find()
                || q.equalsIgnoreCase(original == null ? "" : original.strip())) {
            return Optional.empty();
        }
        String why = reason == null || reason.isBlank() ? "The answer left something open" : reason.strip();
        return Optional.of(new FollowUp(q, why.length() > 120 ? why.substring(0, 120) : why, by));
    }

    private Output parse(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String s = content.strip();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("```\\s*$", "");
        }
        return json.readValue(s, Output.class);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace("</", "< /");
    }
}
