package com.bramhastra.baton.insight;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.source.SourceRepository;

/**
 * Knowledge-risk radar for a project, before anyone resigns: who wrote most of what the project knows, which
 * topics have only one person as a source, which accounts sit with one person, and which areas went quiet.
 * Computed from the project's own sources and claims, filtered by what the viewer may see. No AI involved.
 */
@Service
public class RiskService {

    private static final Duration QUIET = Duration.ofDays(60);

    private final JdbcClient jdbc;
    private final AccessPolicy policy;

    public RiskService(JdbcClient jdbc, AccessPolicy policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    public record Person(UUID userId, String name, String role, double share, int chunks, List<String> soleTopics,
                         List<String> access, boolean leaving, String level) {
    }

    public record Topic(String label, List<String> sources, String soleSource, int claims, Instant lastActivity,
                        boolean quiet) {
    }

    public record AccessItem(String title, String subject, List<String> sources) {
    }

    public record Radar(int busFactor, int totalChunks, int people, List<Person> team, List<Topic> topics,
                        List<AccessItem> access, List<String> outsideAuthors, boolean hasClaims) {
    }

    @Transactional(readOnly = true)
    public Radar radar(CurrentUser me, UUID projectId) {
        if (me.isAdmin()) {
            // Admins see every project in their own workspace, and nothing else: an id from anywhere else is 404.
            boolean exists = jdbc.sql("SELECT count(*) FROM project WHERE id = :p").param("p", projectId)
                    .query(Integer.class).single() > 0;
            if (!exists) {
                throw ApiException.notFound();
            }
        } else {
            policy.requireLead(me, projectId);
        }
        List<Map<String, Object>> members = jdbc.sql("""
                SELECT u.id, u.display_name, u.email, pm.role FROM project_member pm JOIN app_user u ON u.id = pm.user_id
                WHERE pm.project_id = :p AND u.active ORDER BY u.display_name
                """).param("p", projectId).query().listOfRows();
        Set<UUID> leaving = new java.util.HashSet<>(jdbc.sql("""
                SELECT departing_user_id FROM handover WHERE project_id = :p AND status <> 'ARCHIVED'
                """).param("p", projectId).query(UUID.class).list());

        // Who wrote what, counted in passages the viewer can see.
        Map<String, Integer> chunksByAuthor = new HashMap<>();
        Map<String, String> authorLabel = new HashMap<>();
        jdbc.sql("""
                SELECT d.author, k.sensitivity, k.acl_groups FROM chunk k JOIN document d ON d.id = k.document_id
                JOIN source s ON s.id = d.source_id
                WHERE s.project_id = :p AND s.type <> 'TESTIMONY' AND d.author IS NOT NULL AND d.author <> ''
                """).param("p", projectId).query(rs -> {
                    if (policy.canSee(me, Sensitivity.ofLevel(rs.getShort(2)), SourceRepository.textArray(rs.getArray(3)))) {
                        String key = norm(rs.getString(1));
                        chunksByAuthor.merge(key, 1, Integer::sum);
                        authorLabel.putIfAbsent(key, rs.getString(1).strip());
                    }
                });
        int total = chunksByAuthor.values().stream().mapToInt(Integer::intValue).sum();

        // Topics: claim subjects, with the distinct authors of the evidence behind them.
        Map<String, String> topicLabel = new LinkedHashMap<>();
        Map<String, Set<String>> topicAuthors = new HashMap<>();
        Map<String, Integer> topicClaims = new HashMap<>();
        Map<String, Instant> topicLast = new HashMap<>();
        List<AccessItem> accessItems = new ArrayList<>();
        Map<UUID, Set<String>> accessAuthors = new LinkedHashMap<>();
        Map<UUID, String[]> accessText = new HashMap<>();
        jdbc.sql("""
                SELECT c.id, c.type, c.title, c.subject, c.sensitivity, c.acl_groups, d.author, d.authored_at
                FROM claim c JOIN handover h ON h.id = c.handover_id
                LEFT JOIN evidence e ON e.claim_id = c.id AND e.valid
                LEFT JOIN chunk k ON k.id = e.chunk_id LEFT JOIN document d ON d.id = k.document_id
                WHERE h.project_id = :p AND c.review_state <> 'REJECTED' AND c.status <> 'STALE'
                """).param("p", projectId).query(rs -> {
                    if (!policy.canSee(me, Sensitivity.ofLevel(rs.getShort("sensitivity")),
                            SourceRepository.textArray(rs.getArray("acl_groups")))) {
                        return;
                    }
                    String author = rs.getString("author");
                    String subject = rs.getString("subject");
                    Timestamp at = rs.getTimestamp("authored_at");
                    UUID id = rs.getObject("id", UUID.class);
                    if (subject != null && !subject.isBlank()) {
                        String key = subject.strip().toLowerCase(Locale.ROOT);
                        topicLabel.putIfAbsent(key, subject.strip());
                        Set<String> a = topicAuthors.computeIfAbsent(key, k -> new LinkedHashSet<>());
                        if (author != null && !author.isBlank()) {
                            a.add(norm(author));
                            authorLabel.putIfAbsent(norm(author), author.strip());
                        }
                        topicClaims.merge(key, 1, Integer::sum);
                        if (at != null) {
                            topicLast.merge(key, at.toInstant(), (x, y) -> x.isAfter(y) ? x : y);
                        }
                    }
                    if ("ACCESS".equals(rs.getString("type"))) {
                        accessText.putIfAbsent(id, new String[] {rs.getString("title"), subject});
                        Set<String> a = accessAuthors.computeIfAbsent(id, k -> new LinkedHashSet<>());
                        if (author != null && !author.isBlank()) {
                            a.add(norm(author));
                        }
                    }
                });

        Instant now = Instant.now();
        List<Topic> topics = new ArrayList<>();
        topicLabel.forEach((key, label) -> {
            List<String> sources = topicAuthors.getOrDefault(key, Set.of()).stream().map(authorLabel::get).toList();
            Instant last = topicLast.get(key);
            topics.add(new Topic(label, sources, sources.size() == 1 ? sources.get(0) : null, topicClaims.get(key), last,
                    last != null && last.isBefore(now.minus(QUIET))));
        });
        topics.sort(Comparator.comparing((Topic t) -> t.soleSource() == null).thenComparing(t -> -t.claims()));
        accessAuthors.forEach((id, authors) -> accessItems.add(new AccessItem(accessText.get(id)[0], accessText.get(id)[1],
                authors.stream().map(authorLabel::get).toList())));

        // Match free-text authors (chat names, document authors) to team members.
        Map<String, UUID> authorToMember = new HashMap<>();
        for (String a : authorLabel.keySet()) {
            for (Map<String, Object> m : members) {
                if (matches(a, (String) m.get("display_name"), (String) m.get("email"))) {
                    authorToMember.put(a, (UUID) m.get("id"));
                    break;
                }
            }
        }
        List<Person> team = new ArrayList<>();
        for (Map<String, Object> m : members) {
            UUID id = (UUID) m.get("id");
            int chunks = chunksByAuthor.entrySet().stream().filter(e -> id.equals(authorToMember.get(e.getKey())))
                    .mapToInt(Map.Entry::getValue).sum();
            List<String> sole = topics.stream().filter(t -> t.soleSource() != null
                    && id.equals(authorToMember.get(norm(t.soleSource())))).map(Topic::label).toList();
            List<String> acc = accessItems.stream().filter(a -> a.sources().size() == 1
                    && id.equals(authorToMember.get(norm(a.sources().get(0))))).map(AccessItem::title).toList();
            double share = total == 0 ? 0 : (double) chunks / total;
            String level = sole.size() >= 3 || share >= 0.4 || acc.size() >= 2 ? "HIGH"
                    : !sole.isEmpty() || share >= 0.2 || !acc.isEmpty() ? "MEDIUM" : "LOW";
            team.add(new Person(id, (String) m.get("display_name"), (String) m.get("role"), share, chunks, sole, acc,
                    leaving.contains(id), level));
        }
        team.sort(Comparator.comparing((Person p) -> rank(p.level())).thenComparing(p -> -p.share()));

        // Bus factor: the fewest people whose writing covers half of what the project knows.
        List<Integer> sorted = chunksByAuthor.values().stream().sorted(Comparator.reverseOrder()).toList();
        int bus = 0;
        int covered = 0;
        for (int c : sorted) {
            if (covered * 2 >= total) {
                break;
            }
            covered += c;
            bus++;
        }
        List<String> outside = authorLabel.keySet().stream().filter(a -> !authorToMember.containsKey(a)
                && chunksByAuthor.containsKey(a)).map(authorLabel::get).sorted().toList();
        return new Radar(bus, total, members.size(), team, topics, accessItems, outside, !topicLabel.isEmpty() || !accessItems.isEmpty());
    }

    private static int rank(String level) {
        return switch (level) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }

    public static String norm(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", "").replaceAll("\\s+", " ");
    }

    /** "Priya", "priya.sharma", "Priya Sharma" and "priya@acme.test" all match Priya Sharma. */
    public static boolean matches(String author, String displayName, String email) {
        String a = norm(author);
        String n = norm(displayName);
        if (a.isEmpty()) {
            return false;
        }
        String first = n.contains(" ") ? n.substring(0, n.indexOf(' ')) : n;
        String local = email == null ? "" : norm(email.substring(0, Math.max(0, email.indexOf('@'))).replace('.', ' '));
        String compact = a.replace(" ", "");
        return a.equals(n) || a.equals(first) || compact.equals(n.replace(" ", "")) || (!local.isEmpty() && a.equals(local))
                || (a.length() >= 3 && n.startsWith(a + " "));
    }
}
