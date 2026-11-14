package com.bramhastra.baton.knowledge;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.insight.RiskService;
import com.bramhastra.baton.knowledge.MessageParser.Message;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CurrentUser;

/**
 * "Who knows what": ask who understands the billing module and get a ranked list with the evidence behind each name.
 *
 * <p>Expertise is what people actually wrote, line by line. A chat passage counts for the person who said the line, not
 * for everyone in the channel; a document counts for its author; an owner named in a claim, or the person who made a
 * decision about the topic, counts too. Recent work counts more than old work. Only passages the asker may see are
 * read, so the answer never reveals who knows something the asker could not look up.
 *
 * <p>The verdict says whether the top name is a single point of failure, and who the backup is if there is one.
 */
@Service
public class ExpertService {

    private static final Set<String> GENERIC = Set.copyOf(java.util.Arrays.asList("who", "whom", "whose", "understand", "understands", "understood", "knows", "know",
            "known", "expert", "experts", "about", "the", "module", "modules", "code", "area", "areas", "part", "parts", "system",
            "service", "services", "work", "works", "worked", "working", "handles", "handle", "handled", "responsible", "owns", "owner",
            "ask", "can", "should", "talk", "someone", "person", "people", "best", "most", "familiar", "with", "our", "does", "how",
            "what", "where", "when", "any", "anyone", "everything", "stuff", "thing", "things", "and", "for", "are", "was", "has", "have",
            "this", "that", "from", "into", "knowledge", "help", "need", "tell", "show", "find", "list", "have", "get", "got", "used",
            "who's", "whos", "who", "which", "have", "been", "did", "done", "make", "made"));

    private static final Duration HALF_LIFE = Duration.ofDays(180);
    /** A very long document that mentions a topic many times should not outweigh a handful of real contributions. */
    private static final int MAX_CREDITS_PER_DOCUMENT = 5;

    private final JdbcClient jdbc;
    private final AccessPolicy access;

    public ExpertService(JdbcClient jdbc, AccessPolicy access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    // ───────────── result types ─────────────

    public record Evidence(String quote, String source, String sourceType, Instant at, String url, UUID chunkId, String why) {
    }

    public record Expert(String name, UUID userId, String role, boolean onProject, boolean leaving, int score, double share,
                         int passages, Instant firstActive, Instant lastActive, List<Evidence> evidence) {
    }

    public record Verdict(String level, boolean singlePointOfFailure, String backup, UUID backupUserId, String message) {
    }

    public record Experts(String query, List<String> terms, List<Expert> experts, Verdict verdict, int passages) {
    }

    /** Everything found for a set of terms: used directly by the finder and by the risk alerts. */
    public record Aggregate(Map<String, Person> people, int passages) {
    }

    public static final class Person {
        public String label;
        public UUID userId;
        public String role;
        public boolean leaving;
        public double score;
        public int passages;
        public Instant first;
        public Instant last;
        final List<Weighted> evidence = new ArrayList<>();
        final Map<UUID, Integer> perDocument = new HashMap<>();
    }

    record Weighted(double weight, Evidence evidence) {
    }

    private record Member(UUID id, String name, String email, String role) {
    }

    // ───────────── the finder ─────────────

    @Transactional(readOnly = true)
    public Experts find(CurrentUser me, UUID projectId, String query, int limit) {
        access.requireMember(me, projectId);
        List<String> terms = terms(query);
        if (terms.isEmpty()) {
            return new Experts(query, terms, List.of(), new Verdict("NONE", false, null, null,
                    "Say what you are looking for, for example “who understands billing?”."), 0);
        }
        Aggregate agg = aggregate(me, projectId, terms, null);
        List<Person> ranked = agg.people().values().stream().sorted(Comparator.comparingDouble((Person p) -> -p.score)).toList();
        double total = ranked.stream().mapToDouble(p -> p.score).sum();
        double top = ranked.isEmpty() ? 1 : ranked.get(0).score;
        List<Expert> experts = new ArrayList<>();
        for (Person p : ranked.stream().limit(Math.max(1, Math.min(limit, 20))).toList()) {
            List<Evidence> ev = p.evidence.stream().sorted(Comparator.comparingDouble((Weighted w) -> -w.weight()))
                    .limit(3).map(Weighted::evidence).toList();
            experts.add(new Expert(p.label, p.userId, p.role, p.userId != null, p.leaving, (int) Math.max(1, Math.round(100 * p.score / top)),
                    total == 0 ? 0 : p.score / total, p.passages, p.first, p.last, ev));
        }
        return new Experts(query, terms, experts, verdict(String.join(" ", terms), experts), agg.passages());
    }

    static Verdict verdict(String topic, List<Expert> experts) {
        if (experts.isEmpty()) {
            return new Verdict("NONE", false, null, null, "Nobody has written about this in the sources you can see.");
        }
        Expert top = experts.get(0);
        long contributors = experts.stream().filter(e -> e.share() >= 0.05).count();
        Expert backup = experts.stream().skip(1).filter(e -> e.share() >= 0.12 && e.passages() >= 1).findFirst().orElse(null);
        boolean spof = top.share() >= 0.7 || contributors <= 1;
        String first = top.name().split("\\s+")[0];
        if (!spof) {
            return new Verdict("LOW", false, backup == null ? null : backup.name(), backup == null ? null : backup.userId(),
                    "Knowledge about " + topic + " is shared: " + first + " leads, and " + (backup == null ? "others have" : backup.name().split("\\s+")[0] + " has")
                            + " worked on it too.");
        }
        String load = contributors <= 1 ? "is the only person who has written about " + topic
                : "wrote " + Math.round(top.share() * 100) + "% of what is known about " + topic;
        if (backup == null) {
            return new Verdict(top.leaving() ? "CRITICAL" : "HIGH", true, null, null, first + " " + load + ". Nobody else can cover it"
                    + (top.leaving() ? ", and " + first + " is leaving." : ". Choose a backup now."));
        }
        return new Verdict(top.leaving() ? "HIGH" : "MEDIUM", true, backup.name(), backup.userId(), first + " " + load + ". Backup: "
                + backup.name() + " (" + backup.passages() + " passage" + (backup.passages() == 1 ? "" : "s") + ")"
                + (top.leaving() ? ", and " + first + " is leaving, so start the handover to " + backup.name().split("\\s+")[0] + "." : "."));
    }

    // ───────────── the engine ─────────────

    /**
     * Credits every person for what they wrote about the terms, optionally only since a date.
     * Pass {@code since = null} for all time.
     */
    @Transactional(readOnly = true)
    public Aggregate aggregate(CurrentUser me, UUID projectId, List<String> terms, Instant since) {
        return aggregate(me, projectId, terms, since, 1);
    }

    /** As above, but a line only counts when it mentions at least {@code minHits} of the terms (for multi-word topics). */
    @Transactional(readOnly = true)
    public Aggregate aggregate(CurrentUser me, UUID projectId, List<String> terms, Instant since, int minHits) {
        List<Member> members = jdbc.sql("""
                SELECT u.id, u.display_name, u.email, pm.role FROM project_member pm JOIN app_user u ON u.id = pm.user_id
                WHERE pm.project_id = :p AND u.active
                """).param("p", projectId)
                .query((rs, n) -> new Member(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4))).list();
        Set<UUID> leaving = new HashSet<>(jdbc.sql("""
                SELECT departing_user_id FROM handover WHERE project_id = :p AND status <> 'ARCHIVED'
                """).param("p", projectId).query(UUID.class).list());

        Map<String, Person> people = new LinkedHashMap<>();
        Instant now = Instant.now();
        String tsq = String.join(" | ", terms.stream().map(t -> t.replaceAll("[^a-z0-9]", "")).filter(t -> t.length() >= 2)
                .map(t -> t + ":*").toList());
        int passages = 0;
        if (!tsq.isEmpty()) {
            Map<String, Object> params = new LinkedHashMap<>(access.sqlParams(me));
            params.put("p", projectId);
            params.put("tsq", tsq);
            record Row(UUID chunkId, UUID documentId, String text, String title, String author, Instant at, String url, String source,
                       String type) {
            }
            List<Row> rows = jdbc.sql("""
                    SELECT k.id, d.id, k.text, d.title, d.author, d.authored_at, d.url, s.title, s.type
                    FROM chunk k JOIN document d ON d.id = k.document_id JOIN source s ON s.id = d.source_id,
                         to_tsquery('english', :tsq) q
                    WHERE s.project_id = :p AND k.tsv @@ q AND %s
                    ORDER BY ts_rank(k.tsv, q) DESC LIMIT 250
                    """.formatted(access.sqlPredicate("k"))).params(params)
                    .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getString(7),
                            rs.getString(8), rs.getString(9)))
                    .list();
            for (Row r : rows) {
                boolean counted = false;
                for (Message m : MessageParser.parse(r.text(), r.author(), r.at())) {
                    int hits = distinctHits(m.text(), terms);
                    String who = m.author() != null ? m.author() : r.author();
                    Instant when = m.at() != null ? m.at() : r.at();
                    if (hits < Math.max(1, minHits) || who == null || who.isBlank()) {
                        continue;
                    }
                    if (since != null && (when == null || when.isBefore(since))) {
                        continue;
                    }
                    double w = (1 + 0.5 * Math.min(2, hits - 1)) * recency(when, now) * typeWeight(r.type());
                    Person p = person(people, who, members, leaving);
                    if (p.perDocument.merge(r.documentId(), 1, Integer::sum) > MAX_CREDITS_PER_DOCUMENT) {
                        continue;
                    }
                    credit(p, w, when, new Evidence(trim(m.text()), r.source() + " · " + r.title(), r.type(), when, r.url(), r.chunkId(), null));
                    counted = true;
                }
                if (counted) {
                    passages++;
                }
            }
            // Decisions about the topic count for whoever made them.
            for (Map<String, Object> d : jdbc.sql("""
                    SELECT d.title, d.decided_by, d.decided_at, d.quote, d.url, d.chunk_id, d.doc_title, d.source_type
                    FROM decision d WHERE d.project_id = :p AND d.review <> 'REJECTED' AND d.decided_by IS NOT NULL AND %s
                      AND to_tsvector('english', d.title || ' ' || coalesce(d.topic, '')) @@ to_tsquery('english', :tsq)
                    """.formatted(access.sqlPredicate("d"))).params(params).query().listOfRows()) {
                Timestamp ts = (Timestamp) d.get("decided_at");
                Instant when = ts == null ? null : ts.toInstant();
                if (since != null && (when == null || when.isBefore(since))) {
                    continue;
                }
                Person p = person(people, (String) d.get("decided_by"), members, leaving);
                credit(p, 2.0 * recency(when, now), when, new Evidence(trim((String) d.get("quote")), (String) d.get("doc_title"),
                        (String) d.get("source_type"), when, (String) d.get("url"), (UUID) d.get("chunk_id"),
                        "Made the decision: " + d.get("title")));
            }
            // A named owner of work on the topic.
            for (Map<String, Object> c : jdbc.sql("""
                    SELECT c.title, c.attributes->>'owner' AS owner, c.updated_at
                    FROM claim c JOIN handover h ON h.id = c.handover_id
                    WHERE h.project_id = :p AND c.review_state <> 'REJECTED' AND c.status <> 'STALE'
                      AND coalesce(c.attributes->>'owner', '') <> '' AND %s
                      AND to_tsvector('english', c.title || ' ' || coalesce(c.subject, '')) @@ to_tsquery('english', :tsq)
                    """.formatted(access.claimSqlPredicate("c"))).params(params).query().listOfRows()) {
                Timestamp ts = (Timestamp) c.get("updated_at");
                Instant when = ts == null ? null : ts.toInstant();
                if (since != null && (when == null || when.isBefore(since))) {
                    continue;
                }
                Person p = person(people, (String) c.get("owner"), members, leaving);
                credit(p, 3.0, when, new Evidence(trim((String) c.get("title")), "Named as the owner", "CLAIM", when, null, null,
                        "Named owner"));
            }
        }
        return new Aggregate(people, passages);
    }

    private static void credit(Person p, double weight, Instant when, Evidence evidence) {
        p.score += weight;
        p.passages++;
        if (when != null) {
            p.first = p.first == null || when.isBefore(p.first) ? when : p.first;
            p.last = p.last == null || when.isAfter(p.last) ? when : p.last;
        }
        p.evidence.add(new Weighted(weight, evidence));
    }

    /** Finds or creates the entry for a name, merging "Priya", "priya.sharma" and "Priya Sharma" into one team member. */
    private static Person person(Map<String, Person> people, String label, List<Member> members, Set<UUID> leaving) {
        String clean = label.strip();
        for (Member m : members) {
            if (RiskService.matches(clean, m.name(), m.email())) {
                return people.computeIfAbsent(m.id().toString(), k -> {
                    Person p = new Person();
                    p.label = m.name();
                    p.userId = m.id();
                    p.role = m.role();
                    p.leaving = leaving.contains(m.id());
                    return p;
                });
            }
        }
        return people.computeIfAbsent("name:" + RiskService.norm(clean), k -> {
            Person p = new Person();
            p.label = clean;
            return p;
        });
    }

    // ───────────── scoring pieces ─────────────

    static double recency(Instant when, Instant now) {
        if (when == null) {
            return 0.6;
        }
        double ageDays = Math.max(0, Duration.between(when, now).toDays());
        return 0.35 + 0.65 * Math.pow(0.5, ageDays / HALF_LIFE.toDays());
    }

    static double typeWeight(String type) {
        return switch (type) {
            case "GITHUB", "JIRA", "TESTIMONY" -> 1.0;
            case "DOCUMENT", "GOOGLE_DRIVE" -> 0.9;
            case "MEETING_NOTES", "TASK_CSV" -> 0.8;
            case "CHAT_EXPORT", "SLACK", "MEETING_RECORDING" -> 0.7;
            default -> 0.8;
        };
    }

    /** How many of the terms a message mentions. Forms of a word match: "payment" finds "payments". */
    static int distinctHits(String text, List<String> terms) {
        Set<String> words = new HashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 2) {
                words.add(w);
            }
        }
        int hits = 0;
        for (String t : terms) {
            if (words.stream().anyMatch(w -> sameWord(w, t))) {
                hits++;
            }
        }
        return hits;
    }

    static boolean sameWord(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        int min = Math.min(a.length(), b.length());
        if (min < 4) {
            return false;
        }
        int common = 0;
        while (common < min && a.charAt(common) == b.charAt(common)) {
            common++;
        }
        return common == min || common >= 5 && common >= min - 2;
    }

    /** What a person is asking about, without the question words. */
    public static List<String> terms(String query) {
        List<String> out = new ArrayList<>();
        if (query == null) {
            return out;
        }
        for (String w : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 2 && !GENERIC.contains(w) && !out.contains(w) && out.size() < 5) {
                out.add(w);
            }
        }
        return out;
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > 260 ? t.substring(0, 260).replaceFirst("\\s+\\S*$", "") + "…" : t;
    }
}
