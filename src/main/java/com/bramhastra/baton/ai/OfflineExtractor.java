package com.bramhastra.baton.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Deterministic, rule-based stand-in for the LLM, used when there is no Groq key or the demo must run
 * offline. It reads the same prompt the model would get and returns the same JSON shape, so the rest of
 * the pipeline (verifier, scorer, gaps) is exercised exactly as in production. Quotes are copied verbatim.
 */
@Component
public class OfflineExtractor {

    public static final String MODEL = "offline-rules-v1";

    private static final Pattern CHUNK = Pattern.compile(
            "<chunk id=\"(c\\d+)\"([^>]*)>\\n?(.*?)\\n?</chunk>", Pattern.DOTALL);
    private static final Pattern ATTR_DATE = Pattern.compile("date=\"(\\d{4}-\\d{2}-\\d{2})?\"");
    private static final Pattern ATTR_SOURCE = Pattern.compile("source=\"([A-Z_]+)\"");
    private static final Pattern CHAT_LINE = Pattern.compile("^\\[(\\d{2}:\\d{2})] ([^:]{1,60}): (.+)$");
    private static final Pattern MONTH_DAY = Pattern.compile(
            "(?i)\\b(\\d{1,2})(?:st|nd|rd|th)? (jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\b"
                    + "|\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]* (\\d{1,2})(?:st|nd|rd|th)?\\b"
                    + "|\\b(20\\d{2})-(\\d{2})-(\\d{2})\\b");
    private static final List<String> MONTHS = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug",
            "sep", "oct", "nov", "dec");
    private static final Pattern INJECTION = Pattern.compile(
            "(?i)ignore (all )?(previous|prior|above) (instructions|rules)|mark (every|all) .* verified|"
                    + "you are now|system prompt");

    private record Rule(String type, Pattern pattern, boolean critical) {
    }

    // Order matters: the first matching rule wins.
    private static final List<Rule> RULES = List.of(
            new Rule("ACCESS", Pattern.compile("(?i)\\b(account|admin|billing|domain|credentials?|login)\\b.*\\b(under|owner|my|personal|transfer|move)\\b|\\b(under|on) my (personal )?(gmail|email|card)"), true),
            new Rule("BLOCKER", Pattern.compile("(?i)\\bblocked\\b|\\bwaiting (on|for)\\b"), true),
            new Rule("DECISION", Pattern.compile("(?i)\\bdecision\\b|\\bdecided\\b|\\bmoved (it )?to\\b|\\bpushed\\b.*\\bto\\b|\\bwe (will|'ll) (use|go with)\\b|\\binstead of\\b|\\bchose\\b|\\bagreed\\b"), false),
            new Rule("RECURRING_ISSUE", Pattern.compile("(?i)\\bagain\\b|\\bevery (week|monday|tuesday|wednesday|thursday|friday|day|time|morning)\\b|\\bkeeps\\b"), false),
            new Rule("RISK", Pattern.compile("(?i)\\brisk\\b|\\bfails?\\b|\\bbreaks?\\b|\\bworried\\b|\\bfragile\\b|\\bexpires?\\b"), true),
            new Rule("DEPENDENCY", Pattern.compile("(?i)\\bdepends on\\b|\\bdependency\\b|\\brelies on\\b|\\bthird[- ]party\\b"), false),
            new Rule("CONTACT", Pattern.compile("(?i)\\bcontact\\b|\\[EMAIL_\\d+]|\\[PHONE_\\d+]"), false),
            new Rule("OPEN_WORK", Pattern.compile("(?i)\\btodo\\b|\\bstill need\\b|\\bnext step\\b|\\bneed to\\b|\\bnot (yet )?done\\b|\\bhalf[- ]done\\b"), false),
            new Rule("PROCESS", Pattern.compile("(?i)\\bhow to\\b|\\bstep by step\\b|^steps?:|^(run|deploy|release)\\b|\\bto deploy\\b"), false),
            new Rule("OWNER", Pattern.compile("(?i)\\bowns?\\b|\\bowner\\b|\\bresponsible for\\b"), false),
            new Rule("OPEN_QUESTION", Pattern.compile("\\?\\s*$"), false));

    private static final Map<String, String> SUBJECTS = new LinkedHashMap<>();

    static {
        SUBJECTS.put("launch", "public launch date");
        SUBJECTS.put("deploy", "deployment");
        SUBJECTS.put("payment", "payment gateway");
        SUBJECTS.put("razorpay", "payment gateway");
        SUBJECTS.put("database", "database");
        SUBJECTS.put("postgres", "database");
        SUBJECTS.put("firebase", "database");
        SUBJECTS.put("renew", "vendor contract");
        SUBJECTS.put("vendor", "vendor");
        SUBJECTS.put("menu", "menu");
        SUBJECTS.put("hosting", "hosting provider");
        SUBJECTS.put("render", "hosting provider");
    }

    private final ObjectMapper json;

    public OfflineExtractor(ObjectMapper json) {
        this.json = json;
    }

    public LlmClient.LlmResponse complete(LlmClient.LlmRequest req) {
        if (!req.promptId().startsWith("extract")) {
            throw new LlmClient.LlmUnavailableException("Offline mode only supports extraction");
        }
        List<Map<String, Object>> claims = new ArrayList<>();
        Matcher m = CHUNK.matcher(req.user());
        while (m.find()) {
            String chunkId = m.group(1);
            String attrs = m.group(2);
            String body = m.group(3);
            Matcher src = ATTR_SOURCE.matcher(attrs);
            if (src.find() && src.group(1).equals("TASK_CSV")) {
                continue; // task rows are handled by repository signals, without AI
            }
            Matcher d = ATTR_DATE.matcher(attrs);
            String chunkDate = d.find() ? d.group(1) : null;
            for (String raw : body.split("\n")) {
                // Meeting transcripts: drop the "[00:12:34] Speaker: " prefix; the quote finds its moment anyway.
                String line = TRANSCRIPT_PREFIX.matcher(raw.strip()).replaceFirst("");
                for (String sentence : sentences(line.strip())) {
                    Map<String, Object> claim = classify(chunkId, chunkDate, sentence);
                    if (claim != null) {
                        claims.add(claim);
                    }
                }
            }
        }
        String content = json.writeValueAsString(Map.of("claims", claims));
        return new LlmClient.LlmResponse(content, MODEL, "offline", 0, 0);
    }

    private Map<String, Object> classify(String chunkId, String chunkDate, String sentence) {
        String author = null;
        String text = sentence;
        Matcher chat = CHAT_LINE.matcher(sentence);
        if (chat.matches()) {
            author = chat.group(2).strip();
            text = chat.group(3).strip();
        }
        text = text.replaceFirst("^(?:[-*•]|\\d+[.)])\\s+", "");
        if (text.length() < 12 || text.startsWith("#") || text.matches("(?i)^(date|attendees|agenda|state|author|created|closed|labels|assignees):.*")
                || INJECTION.matcher(text).find()) {
            return null;
        }
        for (Rule rule : RULES) {
            if (!rule.pattern().matcher(text).find()) {
                continue;
            }
            String quote = text.length() > 300 ? text.substring(0, 300) : text;
            String title = text.replaceFirst("(?i)^(decision|risk|todo|note|blocker|contact)\\s*[:-]\\s*", "");
            title = Character.toUpperCase(title.charAt(0)) + title.substring(1);
            if (title.length() > 120) {
                title = title.substring(0, 117) + "...";
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            if (author != null) {
                attributes.put(rule.type().equals("DECISION") ? "decidedBy" : "mentionedBy", author);
            }
            if (chunkDate != null) {
                attributes.put("eventDate", chunkDate);
            }
            Matcher because = Pattern.compile("(?i)\\b(because|after|since|due to) (.+)$").matcher(text);
            if (rule.type().equals("DECISION") && because.find()) {
                attributes.put("reason", because.group(2).replaceAll("[.!]+$", ""));
            }
            Matcher owner = Pattern.compile("\\b[Oo]wner:? ([A-Z][a-z]+)|\\b([A-Z][a-z]+) (?:to|will|owns|is responsible for) [a-z]")
                    .matcher(text.replaceFirst("^[A-Z][a-z]+ [a-z]+: ", ""));
            if (owner.find()) {
                String name = owner.group(1) != null ? owner.group(1) : owner.group(2);
                if (!Set.of("The", "This", "That", "We", "It", "Payments", "Next").contains(name)) {
                    attributes.put("owner", name);
                }
            }
            Map<String, Object> claim = new LinkedHashMap<>();
            claim.put("type", rule.type());
            claim.put("title", title);
            claim.put("subject", subjectOf(text));
            claim.put("value", rule.type().equals("DECISION") ? valueOf(text, chunkDate) : null);
            claim.put("attributes", attributes);
            claim.put("critical", rule.critical());
            claim.put("evidence", List.of(Map.of("chunk", chunkId, "quote", quote)));
            return claim;
        }
        return null;
    }

    static String subjectOf(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : SUBJECTS.entrySet()) {
            if (lower.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** A date mentioned in the sentence, as YYYY-MM-DD using the chunk's year; else the sentence itself. */
    static String valueOf(String text, String chunkDate) {
        Matcher m = MONTH_DAY.matcher(text);
        if (m.find()) {
            if (m.group(5) != null) {
                return m.group(5) + "-" + m.group(6) + "-" + m.group(7);
            }
            String day = m.group(1) != null ? m.group(1) : m.group(4);
            String mon = (m.group(2) != null ? m.group(2) : m.group(3)).toLowerCase(Locale.ROOT);
            String year = chunkDate != null ? chunkDate.substring(0, 4) : "2026";
            return "%s-%02d-%02d".formatted(year, MONTHS.indexOf(mon) + 1, Integer.parseInt(day));
        }
        return text.length() > 80 ? text.substring(0, 80) : text;
    }

    static final Pattern TRANSCRIPT_PREFIX = Pattern.compile("^\\[\\d{1,2}:\\d{2}:\\d{2}]\\s*(?:[^:\\n]{1,40}:\\s+)?");

    /** Splits a line into sentences, keeping each as an exact substring of the line. */
    static List<String> sentences(String line) {
        List<String> out = new ArrayList<>();
        if (line.isEmpty()) {
            return out;
        }
        Matcher chat = CHAT_LINE.matcher(line);
        if (chat.matches()) {
            out.add(line); // keep chat lines whole so the speaker stays attached
            return out;
        }
        int start = 0;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && line.charAt(i + 1) == ' ') {
                out.add(line.substring(start, i + 1).strip());
                start = i + 2;
            }
        }
        if (start < line.length()) {
            out.add(line.substring(start).strip());
        }
        return out;
    }
}
