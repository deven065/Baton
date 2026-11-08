package com.bramhastra.baton.reconstruct.stage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.reconstruct.ClaimRepository;
import com.bramhastra.baton.reconstruct.PipelineContext;
import com.bramhastra.baton.reconstruct.PipelineStage;

/**
 * Links contradicting claims: decisions (or owners) on the same subject with different values, e.g.
 * "launch on 15 November" in meeting notes vs "pushed launch to Nov 29" in chat. Both stay visible and
 * the conflict blocks approval until someone resolves it.
 */
@Component
@Order(50)
public class ConflictStage implements PipelineStage {

    private static final Set<String> TYPES = Set.of("DECISION", "OWNER");

    private final ClaimRepository claims;

    public ConflictStage(ClaimRepository claims) {
        this.claims = claims;
    }

    @Override
    public String name() {
        return "CONFLICT";
    }

    @Override
    public int weight() {
        return 5;
    }

    @Override
    public void execute(PipelineContext ctx) {
        claims.deleteUnresolvedConflicts(ctx.handover().id());
        Map<String, List<ClaimRepository.ClaimRow>> bySubject = new LinkedHashMap<>();
        for (ClaimRepository.ClaimRow c : claims.claims(ctx.handover().id())) {
            if (TYPES.contains(c.type()) && c.subject() != null && !c.reviewState().equals("REJECTED")
                    && valueOf(c) != null) {
                bySubject.computeIfAbsent(c.type() + "|" + c.subject(), k -> new ArrayList<>()).add(c);
            }
        }
        int conflicts = 0;
        for (List<ClaimRepository.ClaimRow> group : bySubject.values()) {
            group.sort(Comparator.comparing(ConflictStage::when));
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    ClaimRepository.ClaimRow older = group.get(i);
                    ClaimRepository.ClaimRow newer = group.get(j);
                    if (!normaliseValue(valueOf(older)).equals(normaliseValue(valueOf(newer)))) {
                        claims.link(newer.id(), older.id(), "CONFLICTS_WITH");
                        conflicts++;
                    }
                }
            }
        }
        ctx.count("conflicts", conflicts);
    }

    private static String valueOf(ClaimRepository.ClaimRow c) {
        return c.type().equals("OWNER") ? c.attr("owner") : c.attr("value");
    }

    private static String when(ClaimRepository.ClaimRow c) {
        String d = c.attr("eventDate");
        return d != null ? d : c.createdAt().toString();
    }

    static String normaliseValue(String v) {
        return v.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", " ").strip();
    }
}
