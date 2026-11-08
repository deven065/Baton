package com.bramhastra.baton.reconstruct.stage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.evidence.ClaimScorer;
import com.bramhastra.baton.source.SourceRepository;
import com.bramhastra.baton.source.SourceType;

/**
 * Chooses which chunks the model reads and groups them into batches (LLD BatchPlanner).
 *
 * score = 3·mentionsDeparting + 2·authoredByThem + 1·recent(30d) + trustTier/3; keep the top BUDGET.
 * Batches never mix access classes (sensitivity + groups), so anything the model infers from a batch can
 * be labelled with exactly that class, and nobody sees an inference drawn from text they could not see.
 */
@Component
public class BatchPlanner {

    static final int BUDGET = 1500;
    static final int MAX_TOKENS_PER_BATCH = 6000;

    private final JdbcClient jdbc;

    public BatchPlanner(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Candidate(UUID chunkId, String text, Sensitivity sensitivity, List<String> acl, String title,
                            String author, Instant authoredAt, SourceType sourceType) {
    }

    public record Batch(Sensitivity sensitivity, List<String> acl, List<Candidate> chunks) {
    }

    public List<Batch> plan(UUID projectId, List<String> aliases, Instant now) {
        List<Candidate> all = jdbc.sql("""
                SELECT k.id, k.text, k.sensitivity, k.acl_groups, d.title, d.author, d.authored_at, s.type
                FROM chunk k JOIN document d ON d.id = k.document_id JOIN source s ON s.id = d.source_id
                WHERE s.project_id = :p AND s.status = 'READY' AND s.type NOT IN ('TASK_CSV', 'TESTIMONY')
                """)
                .param("p", projectId)
                .query((rs, n) -> new Candidate(rs.getObject(1, UUID.class), rs.getString(2),
                        Sensitivity.ofLevel(rs.getShort(3)), SourceRepository.textArray(rs.getArray(4)).stream().sorted().toList(),
                        rs.getString(5), rs.getString(6),
                        rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(),
                        SourceType.valueOf(rs.getString(8))))
                .list();

        List<Candidate> chosen = all.stream()
                .sorted(Comparator.comparingDouble((Candidate c) -> -score(c, aliases, now)))
                .limit(BUDGET)
                .toList();

        Map<String, List<Candidate>> byClass = new LinkedHashMap<>();
        for (Candidate c : chosen) {
            byClass.computeIfAbsent(c.sensitivity().name() + "|" + String.join(",", c.acl()), k -> new ArrayList<>()).add(c);
        }
        List<Batch> batches = new ArrayList<>();
        for (List<Candidate> group : byClass.values()) {
            group.sort(Comparator.comparing(Candidate::authoredAt, Comparator.nullsFirst(Comparator.naturalOrder())));
            List<Candidate> current = new ArrayList<>();
            int tokens = 0;
            for (Candidate c : group) {
                int t = estimateTokens(c.text()) + 40;
                if (!current.isEmpty() && tokens + t > MAX_TOKENS_PER_BATCH) {
                    batches.add(new Batch(group.get(0).sensitivity(), group.get(0).acl(), current));
                    current = new ArrayList<>();
                    tokens = 0;
                }
                current.add(c);
                tokens += t;
            }
            if (!current.isEmpty()) {
                batches.add(new Batch(group.get(0).sensitivity(), group.get(0).acl(), current));
            }
        }
        return batches;
    }

    static double score(Candidate c, List<String> aliases, Instant now) {
        String text = c.text().toLowerCase(Locale.ROOT);
        boolean mentions = aliases.stream().anyMatch(a -> text.contains(a));
        boolean authored = c.author() != null && SignalStage.matchesAlias(c.author(), aliases);
        boolean recent = c.authoredAt() != null && Duration.between(c.authoredAt(), now).toDays() <= 30;
        return 3 * (mentions ? 1 : 0) + 2 * (authored ? 1 : 0) + (recent ? 1 : 0)
                + ClaimScorer.trustTier(c.sourceType()) / 3.0;
    }

    static int estimateTokens(String text) {
        return (int) Math.ceil(text.length() / 3.5);
    }
}
