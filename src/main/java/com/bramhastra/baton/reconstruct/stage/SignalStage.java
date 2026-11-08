package com.bramhastra.baton.reconstruct.stage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.reconstruct.ClaimRepository;
import com.bramhastra.baton.reconstruct.PipelineContext;
import com.bramhastra.baton.reconstruct.PipelineStage;
import com.bramhastra.baton.source.SourceRepository;

/**
 * Repository signals, without AI: open pull requests and issues by or assigned to the leaver, and task
 * rows that are theirs or have no owner. These come from systems of record, so they verify on their own.
 */
@Component
@Order(10)
public class SignalStage implements PipelineStage {

    private static final Set<String> DONE = Set.of("done", "closed", "resolved", "complete", "completed",
            "cancelled", "canceled", "won't do", "merged");

    private final JdbcClient jdbc;
    private final ClaimRepository claims;

    public SignalStage(JdbcClient jdbc, ClaimRepository claims) {
        this.jdbc = jdbc;
        this.claims = claims;
    }

    @Override
    public String name() {
        return "SIGNAL";
    }

    @Override
    public int weight() {
        return 5;
    }

    private record Row(UUID chunkId, String text, Sensitivity sensitivity, List<String> acl, String sourceType,
                       String externalRef, String url) {
    }

    @Override
    public void execute(PipelineContext ctx) {
        claims.deleteRunClaims(ctx.run().id(), "REPO_SIGNAL");
        List<Row> rows = jdbc.sql("""
                SELECT k.id, k.text, k.sensitivity, k.acl_groups, s.type, d.external_ref, d.url
                FROM chunk k JOIN document d ON d.id = k.document_id JOIN source s ON s.id = d.source_id
                WHERE s.project_id = :p AND s.status = 'READY' AND k.seq = 0
                  AND (s.type = 'TASK_CSV' OR (s.type = 'GITHUB' AND (d.external_ref LIKE 'pr/%' OR d.external_ref LIKE 'issue/%')))
                """)
                .param("p", ctx.handover().projectId())
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2),
                        Sensitivity.ofLevel(rs.getShort(3)), SourceRepository.textArray(rs.getArray(4)),
                        rs.getString(5), rs.getString(6), rs.getString(7)))
                .list();

        int found = 0;
        for (Row row : rows) {
            Map<String, String> fields = fields(row.text());
            if (row.sourceType().equals("TASK_CSV")) {
                found += task(ctx, row, fields);
            } else {
                found += gitHub(ctx, row, fields);
            }
        }
        ctx.count("signals", found);
    }

    private int task(PipelineContext ctx, Row row, Map<String, String> f) {
        String status = first(f, "status", "state", "list");
        if (status != null && DONE.contains(status.toLowerCase(Locale.ROOT))) {
            return 0;
        }
        String assigneeKey = firstKey(f, "assignee", "owner", "assigned to", "members");
        String assignee = assigneeKey == null ? null : f.get(assigneeKey);
        boolean theirs = assignee != null && matchesAlias(assignee, ctx.aliases());
        boolean unowned = assignee == null || assignee.isBlank();
        if (!theirs && !unowned) {
            return 0;
        }
        String summaryKey = firstKey(f, "summary", "title", "name", "card name", "task");
        String key = first(f, "issue key", "key", "id", "card id", "number");
        String summary = summaryKey == null ? null : f.get(summaryKey);
        if (summary == null) {
            return 0;
        }
        String title = (key != null ? key + ": " : "") + summary;
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (theirs) {
            attrs.put("owner", ctx.handover().departingName());
            attrs.put("ownerIsDeparting", true);
        }
        if (status != null) {
            attrs.put("status", status);
        }
        String due = first(f, "due date", "due", "deadline");
        if (due != null) {
            attrs.put("dueDate", due);
        }
        if (key != null) {
            attrs.put("externalRef", key);
        }
        UUID id = claims.insert(new ClaimRepository.NewClaim(ctx.handover().id(), ctx.run().id(), "OPEN_WORK",
                truncate(title, 300), theirs ? "Assigned to " + assignee + " on the task board." : "No one is assigned on the task board.",
                attrs, "task " + (key != null ? key.toLowerCase(Locale.ROOT) : summary.toLowerCase(Locale.ROOT)),
                row.sensitivity(), row.acl(), false, "REPO_SIGNAL", List.of(), List.of()));
        claims.insertEvidenceDraft(id, row.chunkId(), summaryKey + ": " + summary);
        if (theirs) {
            claims.insertEvidenceDraft(id, row.chunkId(), assigneeKey + ": " + assignee);
        } else if (status != null) {
            String statusKey = firstKey(f, "status", "state", "list");
            claims.insertEvidenceDraft(id, row.chunkId(), statusKey + ": " + status);
        }
        return 1;
    }

    private int gitHub(PipelineContext ctx, Row row, Map<String, String> f) {
        String firstLine = row.text().lines().findFirst().orElse("");
        String state = f.get("state");
        if (state == null || !state.equalsIgnoreCase("open")) {
            return 0;
        }
        String author = f.getOrDefault("author", "");
        String assignees = f.getOrDefault("assignees", "");
        boolean byThem = matchesAlias(author, ctx.aliases());
        boolean assigned = matchesAlias(assignees, ctx.aliases());
        if (!byThem && !assigned) {
            return 0;
        }
        boolean pr = firstLine.startsWith("Pull request");
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("owner", ctx.handover().departingName());
        attrs.put("ownerIsDeparting", true);
        attrs.put("status", "open");
        attrs.put("externalRef", row.externalRef());
        if (row.url() != null) {
            attrs.put("url", row.url());
        }
        UUID id = claims.insert(new ClaimRepository.NewClaim(ctx.handover().id(), ctx.run().id(), "OPEN_WORK",
                truncate((pr ? "Finish open " : "Resolve open ") + firstLine, 300),
                (pr ? "Open pull request " : "Open issue ") + (byThem ? "authored" : "assigned") + " by the departing member.",
                attrs, row.externalRef(), row.sensitivity(), row.acl(), pr, "REPO_SIGNAL", List.of(), List.of()));
        claims.insertEvidenceDraft(id, row.chunkId(), firstLine);
        claims.insertEvidenceDraft(id, row.chunkId(), byThem ? "Author: " + author : "Assignees: " + assignees);
        return 1;
    }

    /** "Key: value" lines, keys lowercased. */
    static Map<String, String> fields(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int i = line.indexOf(": ");
            if (i > 0 && i < 40) {
                out.putIfAbsent(line.substring(0, i).strip(), line.substring(i + 2).strip());
            }
        }
        return out;
    }

    private static String firstKey(Map<String, String> f, String... candidates) {
        for (String c : candidates) {
            for (String k : f.keySet()) {
                if (k.equalsIgnoreCase(c)) {
                    return k;
                }
            }
        }
        return null;
    }

    private static String first(Map<String, String> f, String... candidates) {
        String k = firstKey(f, candidates);
        return k == null ? null : f.get(k);
    }

    static boolean matchesAlias(String value, List<String> aliases) {
        String v = " " + value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9@._-]+", " ") + " ";
        List<String> parts = new ArrayList<>(aliases);
        return parts.stream().anyMatch(a -> v.contains(" " + a + " "));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
