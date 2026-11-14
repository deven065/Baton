package com.bramhastra.baton.staleness;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Staleness detection: finds documentation that still describes something a later code change renamed, replaced,
 * removed or upgraded.
 *
 * <p>Code changes are read from commit messages (the commit log a GitHub source keeps, or an uploaded
 * {@code git log}) and closed pull requests. Each message is parsed for what it did to which identifier:
 * "Rename DATABASE_URL to DB_URL", "Replace Firebase with OneSignal", "Remove /api/v1/export", "Upgrade to Java 21".
 * A document is stale when it still mentions the old identifier (and not the new one), or an older version, and it
 * was not written after the change. Everything here is plain rules, no AI, so every finding can be explained by
 * pointing at one line of the document and one code change.
 */
public final class StaleDetector {

    private StaleDetector() {
    }

    public enum Kind { RENAMED, REPLACED, REMOVED, VERSION }

    /** One code change: a commit or a closed pull request. */
    public record Change(String ref, String text, Instant at, UUID chunkId, String url) {
    }

    /** What a change did to one identifier. For VERSION, {@code term} is the technology and {@code replacement} the new version. */
    public record Effect(Kind kind, String term, String replacement) {
    }

    /** One passage of documentation. */
    public record DocChunk(UUID documentId, UUID chunkId, String docRef, String title, Instant authoredAt, String text) {
    }

    public record Finding(DocChunk doc, Change change, Kind kind, String term, String replacement, String quote) {
    }

    static final int MAX_FINDINGS = 300;

    // "1a2b3c4 2026-11-14T10:35:04Z Deven: Rename X to Y" (GitHub source) or git log --pretty="%h %ad %an: %s" --date=iso
    private static final Pattern COMMIT = Pattern.compile(
            "^([0-9a-f]{7,40})\\s+(\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?)?(?:\\s?(?:Z|[+-]\\d{2}:?\\d{2}))?)\\s+(.+?):\\s+(.+)$",
            Pattern.MULTILINE);

    private static final Pattern RENAME = Pattern.compile("\\b(renam(?:e|ed|es|ing))\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPLACE = Pattern.compile(
            "\\b(replac(?:e|ed|es|ing)|switch(?:ed|es|ing)?|migrat(?:e|ed|es|ing)|mov(?:e|ed|es|ing)|swap(?:ped|s)?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REMOVE = Pattern.compile(
            "\\b(remov(?:e|ed|es|ing)|delet(?:e|ed|es|ing)|drop(?:ped|s)?|deprecat(?:e|ed|es|ing)|get rid of|retir(?:e|ed|es))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UPGRADE = Pattern.compile(
            "\\b(upgrad(?:e|ed|es|ing)|bump(?:ed|s)?|updat(?:e|ed|es|ing)|mov(?:e|ed|es)|migrat(?:e|ed|es)|switch(?:ed)?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SEPARATOR = Pattern.compile("\\s(?:to|with|by|into|for|in favou?r of)\\s|\\s?(?:->|→|=>)\\s?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern.compile("`([^`]{2,80})`|([A-Za-z0-9_@/][\\w@./:{}-]*[\\w}/])");

    /** Technologies whose version a doc may state, and the words that name them. */
    private static final Map<String, String> TECH = new LinkedHashMap<>();
    static {
        TECH.put("java", "Java|JDK|OpenJDK");
        TECH.put("node", "Node(?:\\.js|js)?");
        TECH.put("python", "Python");
        TECH.put("postgres", "Postgres(?:QL)?|PostgreSQL");
        TECH.put("spring boot", "Spring Boot");
        TECH.put("react", "React");
        TECH.put("vite", "Vite");
        TECH.put("typescript", "TypeScript");
        TECH.put("go", "Go(?=\\s+v?\\d)|Golang");
        TECH.put("ruby", "Ruby");
        TECH.put("php", "PHP");
        TECH.put("django", "Django");
        TECH.put("rails", "Rails");
        TECH.put("kotlin", "Kotlin");
        TECH.put("next.js", "Next(?:\\.js|js)");
        TECH.put("angular", "Angular");
        TECH.put("vue", "Vue(?:\\.js)?");
        TECH.put("mysql", "MySQL");
        TECH.put("redis", "Redis");
        TECH.put("gradle", "Gradle");
    }

    private static final Set<String> NOT_A_NAME = Set.of("the", "a", "an", "all", "old", "new", "unused", "dead", "legacy",
            "support", "some", "this", "that", "it", "them", "readme", "docs", "doc", "code", "tests", "test", "file",
            "files", "feature", "features", "from", "and", "or", "of", "in", "on", "for", "with", "to", "by", "into", "api",
            "app", "ui", "bug", "fix", "wip", "pr", "main", "master", "dev", "branch", "version", "dependency", "dependencies");

    // ───────────── reading code changes ─────────────

    /** The commits in a commit-log document. */
    public static List<Change> commits(String text, UUID chunkId, String docUrl) {
        List<Change> out = new ArrayList<>();
        Matcher m = COMMIT.matcher(text == null ? "" : text);
        while (m.find()) {
            Instant at = parseDate(m.group(2));
            if (at == null) {
                continue;
            }
            String sha = m.group(1);
            String url = docUrl == null ? null
                    : docUrl.contains("/commits/") ? docUrl.substring(0, docUrl.indexOf("/commits/")) + "/commit/" + sha : docUrl;
            out.add(new Change("commit " + sha.substring(0, Math.min(7, sha.length())), m.group(4).strip(), at, chunkId, url));
        }
        return out;
    }

    /** True when a document is a commit log rather than documentation (three or more commit lines). */
    public static boolean isCommitLog(String text) {
        Matcher m = COMMIT.matcher(text == null ? "" : text);
        int n = 0;
        while (m.find() && n < 3) {
            n++;
        }
        return n >= 3;
    }

    /** What a commit message or pull request title did, to which identifiers. */
    public static List<Effect> effects(String message) {
        List<Effect> out = new ArrayList<>();
        if (message == null || message.isBlank()) {
            return out;
        }
        String msg = message.strip();
        version(msg).ifPresent(out::add);

        Matcher rename = RENAME.matcher(msg);
        Matcher replace = REPLACE.matcher(msg);
        if (rename.find()) {
            pair(msg.substring(rename.end()), Kind.RENAMED).ifPresent(out::add);
        } else if (replace.find()) {
            String rest = msg.substring(replace.end());
            Matcher from = Pattern.compile("^\\s*(?:\\S+\\s+){0,3}?from\\s", Pattern.CASE_INSENSITIVE).matcher(rest);
            pair(from.find() ? rest.substring(from.end()) : rest, Kind.REPLACED)
                    .filter(e -> out.stream().noneMatch(v -> v.kind() == Kind.VERSION))
                    .ifPresent(out::add);
        }
        Matcher remove = REMOVE.matcher(msg);
        if (remove.find() && out.stream().noneMatch(e -> e.kind() == Kind.RENAMED || e.kind() == Kind.REPLACED)) {
            String rest = msg.substring(remove.end());
            int cut = indexOfSeparator(rest);
            for (String t : identifiers(cut < 0 ? rest : rest.substring(0, cut), 3)) {
                out.add(new Effect(Kind.REMOVED, t, null));
            }
        }
        return out;
    }

    // ───────────── matching documents ─────────────

    /** Every passage of documentation that still says what a later change altered. */
    public static List<Finding> detect(List<DocChunk> docs, List<Change> changes) {
        List<Finding> out = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        // Newest change first, so a passage is explained by the most recent thing that made it wrong.
        List<Change> ordered = new ArrayList<>(changes);
        ordered.sort((a, b) -> b.at().compareTo(a.at()));
        for (Change c : ordered) {
            for (Effect e : effects(c.text())) {
                for (DocChunk d : docs) {
                    if (d.authoredAt() != null && d.authoredAt().isAfter(c.at())) {
                        continue; // written after the change: it may already describe the new state
                    }
                    Optional<Finding> f = match(d, c, e);
                    if (f.isPresent() && seen.putIfAbsent(d.docRef() + "|" + f.get().term(), true) == null) {
                        out.add(f.get());
                        if (out.size() >= MAX_FINDINGS) {
                            return out;
                        }
                    }
                }
            }
        }
        return out;
    }

    static Optional<Finding> match(DocChunk d, Change c, Effect e) {
        String text = d.text();
        if (e.kind() == Kind.VERSION) {
            Matcher m = Pattern.compile("\\b(?:" + TECH.get(e.term()) + ")\\s*v?(\\d+(?:\\.\\d+)*)", Pattern.CASE_INSENSITIVE)
                    .matcher(text);
            while (m.find()) {
                if (!major(m.group(1)).equals(major(e.replacement()))) {
                    return Optional.of(new Finding(d, c, Kind.VERSION, m.group().strip(),
                            label(e.term()) + " " + e.replacement(), quote(text, m.start(), m.end())));
                }
            }
            return Optional.empty();
        }
        Matcher m = occurrence(e.term()).matcher(text);
        boolean found = false;
        while (m.find()) {
            if (!negated(text, m.start())) {
                found = true;
                break;
            }
        }
        if (!found) {
            return Optional.empty();
        }
        if (e.replacement() != null && occurrence(e.replacement()).matcher(text).find()) {
            return Optional.empty(); // the document already mentions the new name
        }
        return Optional.of(new Finding(d, c, e.kind(), e.term(), e.replacement(), quote(text, m.start(), m.end())));
    }

    // ───────────── helpers ─────────────

    /** Words right before a mention that say the thing is not (or no longer) used: "instead of Firebase". */
    private static final Pattern NOT_IN_USE = Pattern.compile(
            "(instead of|rather than|not|no longer|never|without|away from|(moved|switched|migrated|changed) from|"
                    + "replaced|replacing|dropped|removed|deprecated|retired|used to use|previously used)\\s*[`\"'(]*$",
            Pattern.CASE_INSENSITIVE);

    static boolean negated(String text, int start) {
        int from = Math.max(0, start - 40);
        String before = text.substring(from, start);
        int stop = Math.max(before.lastIndexOf('.'), Math.max(before.lastIndexOf('\n'), before.lastIndexOf(';')));
        return NOT_IN_USE.matcher(stop >= 0 ? before.substring(stop + 1) : before).find();
    }

    private static Optional<Effect> pair(String rest, Kind kind) {
        int sep = indexOfSeparator(rest);
        if (sep < 0) {
            return Optional.empty();
        }
        List<String> before = identifiers(rest.substring(0, sep), 5);
        Matcher after = SEPARATOR.matcher(rest);
        after.find(sep);
        List<String> next = identifiers(rest.substring(after.end()), 1);
        if (before.isEmpty() || next.isEmpty()) {
            return Optional.empty();
        }
        String old = before.get(before.size() - 1); // the identifier right before "to"/"with"
        return old.equalsIgnoreCase(next.get(0)) ? Optional.empty() : Optional.of(new Effect(kind, old, next.get(0)));
    }

    private static int indexOfSeparator(String s) {
        Matcher m = SEPARATOR.matcher(s);
        return m.find() ? m.start() : -1;
    }

    /** Identifier-looking words: `code`, paths, ENV_VARS, dotted.names, CamelCase or Proper names. */
    static List<String> identifiers(String s, int max) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find() && out.size() < max) {
            String t = m.group(1) != null ? m.group(1).strip() : m.group(2);
            if (m.group(1) != null || looksLikeIdentifier(t)) {
                out.add(t);
            }
        }
        return out;
    }

    static boolean looksLikeIdentifier(String t) {
        if (t.length() < 3 || NOT_A_NAME.contains(t.toLowerCase(Locale.ROOT))) {
            return false;
        }
        boolean code = t.matches(".*[._/:@{}-].*") || t.matches(".*\\d.*") || t.matches("[A-Z][A-Z0-9_]{2,}")
                || t.matches("[a-z]+[A-Z].*") || t.matches("[A-Z][a-z]+[A-Z].*");
        boolean properName = t.matches("[A-Z][a-z]{2,}");
        return code || properName;
    }

    /** Matches a term where it stands on its own: code exactly, names in any case. */
    private static Pattern occurrence(String term) {
        boolean name = term.matches("[A-Z][a-z]{2,}");
        String body = Pattern.quote(term);
        return Pattern.compile("(?<![\\w/@.-])" + body + "(?![\\w/@-]|\\.\\w)", name ? Pattern.CASE_INSENSITIVE : 0);
    }

    private static Optional<Effect> version(String msg) {
        if (!UPGRADE.matcher(msg).find()) {
            return Optional.empty();
        }
        for (Map.Entry<String, String> t : TECH.entrySet()) {
            Matcher m = Pattern.compile("\\b(?:" + t.getValue() + ")\\s*v?(\\d+(?:\\.\\d+)*)(?:\\s*(?:->|→|to)\\s*v?(\\d+(?:\\.\\d+)*))?",
                    Pattern.CASE_INSENSITIVE).matcher(msg);
            String newest = null;
            while (m.find()) {
                newest = m.group(2) != null ? m.group(2) : m.group(1);
            }
            if (newest != null) {
                return Optional.of(new Effect(Kind.VERSION, t.getKey(), newest));
            }
        }
        return Optional.empty();
    }

    private static String label(String tech) {
        return switch (tech) {
            case "node" -> "Node";
            case "postgres" -> "PostgreSQL";
            case "spring boot" -> "Spring Boot";
            case "typescript" -> "TypeScript";
            case "next.js" -> "Next.js";
            case "mysql" -> "MySQL";
            case "php" -> "PHP";
            default -> Character.toUpperCase(tech.charAt(0)) + tech.substring(1);
        };
    }

    private static String major(String v) {
        int dot = v.indexOf('.');
        return dot < 0 ? v : v.substring(0, dot);
    }

    /** The line around a match, trimmed to a readable length. */
    static String quote(String text, int start, int end) {
        int ls = text.lastIndexOf('\n', start - 1) + 1;
        int le = text.indexOf('\n', end);
        if (le < 0) {
            le = text.length();
        }
        if (le - ls > 280) {
            ls = Math.max(ls, start - 120);
            le = Math.min(le, end + 140);
        }
        String q = text.substring(ls, le).strip().replaceAll("^[#>*\\-\\s]+", "");
        return q.length() > 300 ? q.substring(0, 300) : q;
    }

    static Instant parseDate(String s) {
        String v = s.strip().replace(' ', 'T');
        // "2026-11-14T10:35:04T+0530" from git's iso format: put the offset back in place.
        v = v.replaceFirst("T([+-]\\d{2}):?(\\d{2})$", "$1:$2").replaceFirst("T(Z)$", "$1");
        for (DateTimeFormatter f : List.of(DateTimeFormatter.ISO_OFFSET_DATE_TIME, DateTimeFormatter.ISO_INSTANT)) {
            try {
                return OffsetDateTime.parse(v, f).toInstant();
            } catch (DateTimeParseException e) {
                // next format
            }
        }
        try {
            return java.time.LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            // next
        }
        try {
            return LocalDate.parse(v.length() >= 10 ? v.substring(0, 10) : v).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
