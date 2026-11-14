package com.bramhastra.baton.knowledge;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.insight.RiskService;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CurrentUser;

/**
 * Risk alerts for an engineering manager: the sentences the radar's numbers add up to. "Only Priya has touched
 * payments in the last 6 months", "if Priya leaves, 4 topics lose their only contributor". Each alert names the
 * person, shows the evidence, says when they were last active and suggests who could cover.
 *
 * <p>Topics come from what the project has already decided and claimed; "touched" means wrote a passage that mentions
 * the topic, counted per person (a chat line counts for the one who said it). Everything is filtered by what the
 * viewer may see, so an alert never reveals a source the viewer cannot open.
 */
@Service
public class RiskAlertService {

    static final int MAX_TOPICS = 25;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;
    private final AccessPolicy access;
    private final ExpertService experts;
    private final RiskService radar;
    private final KnowledgeService knowledge;

    public RiskAlertService(JdbcClient jdbc, AccessPolicy access, ExpertService experts, RiskService radar, KnowledgeService knowledge) {
        this.jdbc = jdbc;
        this.access = access;
        this.experts = experts;
        this.radar = radar;
        this.knowledge = knowledge;
    }

    public record Alert(String kind, String severity, String text, String topic, String person, UUID personUserId, boolean leaving,
                        int passages, Instant lastActive, List<ExpertService.Evidence> evidence, String suggestion, String backup) {
    }

    public record Summary(int critical, int high, int medium, int low) {
    }

    public record Alerts(int windowDays, Summary summary, List<Alert> items) {
    }

    @Transactional
    public Alerts alerts(CurrentUser me, UUID projectId, int windowDays) {
        if (me.isAdmin()) {
            boolean exists = jdbc.sql("SELECT count(*) FROM project WHERE id = :p").param("p", projectId).query(Integer.class).single() > 0;
            if (!exists) {
                throw ApiException.notFound();
            }
        } else {
            access.requireLead(me, projectId);
        }
        int days = Math.max(30, Math.min(windowDays, 730));
        Instant since = Instant.now().minus(Duration.ofDays(days));
        knowledge.mineMissing(projectId);

        List<Alert> items = new ArrayList<>();
        Map<UUID, List<String>> onlyTheirs = new LinkedHashMap<>();
        Map<UUID, String> names = new LinkedHashMap<>();
        for (String topic : topics(me, projectId)) {
            List<String> terms = ExpertService.terms(topic);
            if (terms.isEmpty()) {
                continue;
            }
            int minHits = Math.min(2, terms.size());
            ExpertService.Aggregate recent = experts.aggregate(me, projectId, terms, since, minHits);
            ExpertService.Aggregate ever = experts.aggregate(me, projectId, terms, null, minHits);
            List<ExpertService.Person> ranked = recent.people().values().stream().filter(p -> p.passages >= 1).toList();
            // "Only X has ever written about it", for the "if X leaves" summary.
            List<ExpertService.Person> allTime = ever.people().values().stream().filter(p -> p.passages >= 1).toList();
            if (allTime.size() == 1 && allTime.get(0).userId != null) {
                onlyTheirs.computeIfAbsent(allTime.get(0).userId, k -> new ArrayList<>()).add(topic);
                names.putIfAbsent(allTime.get(0).userId, allTime.get(0).label);
            }
            if (ranked.size() == 1 && ranked.get(0).passages >= 2) {
                ExpertService.Person only = ranked.get(0);
                ExpertService.Person cover = allTime.stream().filter(p -> p != only && !same(p, only))
                        .max(Comparator.comparingDouble(p -> p.score)).orElse(null);
                items.add(sole(topic, only, cover, days));
            }
        }

        // If someone leaves, which topics lose their only contributor.
        for (var e : onlyTheirs.entrySet()) {
            boolean leaving = jdbc.sql("SELECT count(*) FROM handover WHERE project_id = :p AND status <> 'ARCHIVED' AND departing_user_id = :u")
                    .param("p", projectId).param("u", e.getKey()).query(Integer.class).single() > 0;
            if (e.getValue().size() >= 2 || leaving) {
                String first = names.get(e.getKey()).split("\\s+")[0];
                items.add(new Alert("LEAVING_COVERAGE", leaving ? "CRITICAL" : "HIGH",
                        (leaving ? first + " is leaving, and " : "If " + first + " leaves, ") + e.getValue().size() + " topic"
                                + (e.getValue().size() == 1 ? "" : "s") + " lose their only contributor: "
                                + e.getValue().stream().limit(6).collect(Collectors.joining(", ")) + ".",
                        null, names.get(e.getKey()), e.getKey(), leaving, e.getValue().size(), null, List.of(),
                        leaving ? "Start or finish " + first + "'s handover and pair a teammate on each topic."
                                : "Ask " + first + " to document these and bring in a second person.", null));
            }
        }

        // Access that only one person holds (accounts, keys, domains).
        for (RiskService.AccessItem a : radar.radar(me, projectId).access()) {
            if (a.sources().size() == 1) {
                String who = a.sources().get(0);
                boolean leaving = leavingMatches(projectId, who);
                items.add(new Alert("SOLE_ACCESS", leaving ? "CRITICAL" : "HIGH",
                        "Only " + who.split("\\s+")[0] + " knows how to reach " + a.title() + ".", a.subject(), who, null, leaving, 1,
                        null, List.of(), "Add a second owner and write down where it is kept (never the secret itself).", null));
            }
        }

        items.sort(Comparator.comparingInt((Alert a) -> rank(a.severity())).thenComparingInt(a -> -a.passages()));
        int[] c = new int[4];
        items.forEach(a -> c[Math.min(3, rank(a.severity()))]++);
        return new Alerts(days, new Summary(c[0], c[1], c[2], c[3]), items);
    }

    private Alert sole(String topic, ExpertService.Person only, ExpertService.Person cover, int days) {
        String first = only.label.split("\\s+")[0];
        String severity = only.leaving ? "CRITICAL" : only.passages >= 4 ? "HIGH" : "MEDIUM";
        String months = days >= 330 ? (Math.round(days / 365.0) <= 1 ? "year" : Math.round(days / 365.0) + " years")
                : days % 30 == 0 ? (days / 30) + " months" : days + " days";
        String text = "Only " + first + " has touched " + topic + " in the last " + months + " (" + only.passages + " passage"
                + (only.passages == 1 ? "" : "s") + (only.last == null ? "" : ", last on " + DAY.format(only.last)) + ")"
                + (only.leaving ? ", and " + first + " is leaving." : ".");
        String suggestion = cover != null
                ? "Pair " + cover.label.split("\\s+")[0] + " with " + first + ": " + cover.label.split("\\s+")[0] + " has worked on " + topic + " before."
                : "Nobody else has written about " + topic + ". Ask " + first + " to document it and name a backup.";
        List<ExpertService.Evidence> ev = only.evidence.stream().sorted(Comparator.comparingDouble((ExpertService.Weighted w) -> -w.weight()))
                .limit(2).map(ExpertService.Weighted::evidence).toList();
        return new Alert("SOLE_CONTRIBUTOR", severity, text, topic, only.label, only.userId, only.leaving, only.passages, only.last, ev,
                suggestion, cover == null ? null : cover.label);
    }

    private boolean leavingMatches(UUID projectId, String author) {
        return jdbc.sql("""
                SELECT u.display_name, u.email FROM handover h JOIN app_user u ON u.id = h.departing_user_id
                WHERE h.project_id = :p AND h.status <> 'ARCHIVED'
                """).param("p", projectId).query((rs, n) -> RiskService.matches(author, rs.getString(1), rs.getString(2))).list()
                .stream().anyMatch(Boolean::booleanValue);
    }

    private static boolean same(ExpertService.Person a, ExpertService.Person b) {
        return a.userId != null ? a.userId.equals(b.userId) : RiskService.norm(a.label).equals(RiskService.norm(b.label));
    }

    /** What the project has decided or claimed things about, most-mentioned first. */
    private List<String> topics(CurrentUser me, UUID projectId) {
        Map<String, Object> params = new LinkedHashMap<>(access.sqlParams(me));
        params.put("p", projectId);
        Map<String, Integer> count = new LinkedHashMap<>();
        Map<String, String> label = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT topic, count(*) FROM decision d WHERE d.project_id = :p AND d.review <> 'REJECTED' AND coalesce(d.topic, '') <> ''
                  AND %s GROUP BY topic
                """.formatted(access.sqlPredicate("d"))).params(params).query((rs, n) -> {
                    String t = rs.getString(1).strip();
                    count.merge(t.toLowerCase(Locale.ROOT), rs.getInt(2), Integer::sum);
                    label.putIfAbsent(t.toLowerCase(Locale.ROOT), t);
                    return null;
                }).list();
        jdbc.sql("""
                SELECT c.subject, count(*) FROM claim c JOIN handover h ON h.id = c.handover_id
                WHERE h.project_id = :p AND c.review_state <> 'REJECTED' AND c.status <> 'STALE' AND coalesce(c.subject, '') <> ''
                  AND %s GROUP BY c.subject
                """.formatted(access.claimSqlPredicate("c"))).params(params).query((rs, n) -> {
                    String t = rs.getString(1).strip();
                    if (t.length() <= 40) {
                        count.merge(t.toLowerCase(Locale.ROOT), rs.getInt(2), Integer::sum);
                        label.putIfAbsent(t.toLowerCase(Locale.ROOT), t);
                    }
                    return null;
                }).list();
        return count.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(MAX_TOPICS)
                .map(e -> label.get(e.getKey())).toList();
    }

    private static int rank(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            default -> 3;
        };
    }

    static Set<String> severities() {
        return Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    }
}
