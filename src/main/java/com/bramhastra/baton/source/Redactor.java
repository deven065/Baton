package com.bramhastra.baton.source;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Replaces secrets and personal data with tokens such as [EMAIL_1] or [API_KEY_2] before any text is
 * stored as a chunk or sent to an LLM. The same value gets the same token within one document, so
 * "mail [EMAIL_1]" stays meaningful. Originals are returned so the caller can store them encrypted.
 */
@Component
public class Redactor {

    public enum Kind { EMAIL, PHONE, API_KEY, PASSWORD, SECRET }

    /** A rule redacts either the whole match (group 0) or one named group "v". */
    private record Rule(Kind kind, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            // Private key blocks first, they span lines
            new Rule(Kind.SECRET, Pattern.compile(
                    "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")),
            // Well-known token formats
            new Rule(Kind.API_KEY, Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")),
            new Rule(Kind.API_KEY, Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}\\b")),
            new Rule(Kind.API_KEY, Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b")),
            new Rule(Kind.API_KEY, Pattern.compile("\\bgsk_[A-Za-z0-9]{20,}\\b")),
            new Rule(Kind.API_KEY, Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}\\b")),
            new Rule(Kind.API_KEY, Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b")),
            new Rule(Kind.API_KEY, Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b")),
            new Rule(Kind.API_KEY, Pattern.compile("\\b(?:rzp|pk|sk)_(?:live|test)_[A-Za-z0-9]{10,}\\b")),
            // key = value style secrets: redact only the value
            new Rule(Kind.API_KEY, Pattern.compile(
                    "(?i)\\b(?:api[_-]?key|secret(?:[_-]?key)?|access[_-]?token|auth[_-]?token|token|client[_-]?secret)"
                            + "\\s*[:=]\\s*[\"']?(?<v>[A-Za-z0-9_\\-./+=]{12,})")),
            new Rule(Kind.PASSWORD, Pattern.compile(
                    "(?i)\\b(?:password|passwd|pwd|passcode)\\b\\s*(?:\\bis\\b|:|=)\\s*[\"']?(?<v>[^\\s\"',;]{4,})")),
            // Personal data
            new Rule(Kind.EMAIL, Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")),
            new Rule(Kind.PHONE, Pattern.compile("(?<![\\w-])\\+?\\d[\\d -]{8,16}\\d(?![\\w-])")));

    private static final Pattern TOKEN = Pattern.compile("\\[(?:EMAIL|PHONE|API_KEY|PASSWORD|SECRET)_\\d+]");

    public Result redact(String text) {
        Map<String, String> tokenByValue = new LinkedHashMap<>();
        Map<Kind, Integer> counters = new EnumMap<>(Kind.class);
        List<Redaction> redactions = new ArrayList<>();
        String current = text;

        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(current);
            StringBuilder out = new StringBuilder();
            int last = 0;
            boolean hasGroup = rule.pattern().pattern().contains("(?<v>");
            while (m.find()) {
                int start = hasGroup ? m.start("v") : m.start();
                int end = hasGroup ? m.end("v") : m.end();
                String value = current.substring(start, end);
                if (TOKEN.matcher(value).matches()
                        || (rule.kind() == Kind.PHONE && !looksLikePhone(value))) {
                    continue;
                }
                String token = tokenByValue.get(value);
                if (token == null) {
                    int n = counters.merge(rule.kind(), 1, Integer::sum);
                    token = "[" + rule.kind().name() + "_" + n + "]";
                    tokenByValue.put(value, token);
                    redactions.add(new Redaction(token, rule.kind(), value));
                }
                out.append(current, last, start).append(token);
                last = end;
            }
            out.append(current.substring(last));
            current = out.toString();
        }
        return new Result(current, redactions);
    }

    /** At least 10 digits, and not an ISO date or time range such as 2026-10-20. */
    private static boolean looksLikePhone(String value) {
        long digits = value.chars().filter(Character::isDigit).count();
        if (digits < 10 || digits > 15) {
            return false;
        }
        return !value.matches("\\d{4}-\\d{2}-\\d{2}.*");
    }

    public record Redaction(String token, Kind kind, String original) {
    }

    public record Result(String text, List<Redaction> redactions) {
    }
}
