package com.bramhastra.baton.reconstruct.stage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.reconstruct.ClaimRepository;
import com.bramhastra.baton.reconstruct.PipelineContext;
import com.bramhastra.baton.reconstruct.PipelineStage;

/**
 * Merges near-duplicate claims from this run (same type, very similar title, or same decision on the same
 * subject with the same value). Evidence moves to the kept claim, so two sources make one stronger claim.
 */
@Component
@Order(40)
public class ConsolidateStage implements PipelineStage {

    private static final Set<String> STOP = Set.of("the", "a", "an", "to", "of", "on", "in", "for", "and", "is",
            "be", "we", "it", "at", "with", "by", "after", "was", "will");

    private final ClaimRepository claims;

    public ConsolidateStage(ClaimRepository claims) {
        this.claims = claims;
    }

    @Override
    public String name() {
        return "CONSOLIDATE";
    }

    @Override
    public int weight() {
        return 5;
    }

    @Override
    public void execute(PipelineContext ctx) {
        List<ClaimRepository.ClaimRow> rows = claims.claims(ctx.handover().id()).stream()
                .filter(c -> ctx.run().id().equals(c.runId()) && c.reviewState().equals("PENDING"))
                .toList();
        // Claims the lead already reviewed (accepted, edited or rejected) survive a re-run; a fresh copy of one of
        // them would resurrect a rejected claim or undo a decision, so it is dropped.
        List<ClaimRepository.ClaimRow> reviewed = claims.claims(ctx.handover().id()).stream()
                .filter(c -> !c.reviewState().equals("PENDING")).toList();
        List<ClaimRepository.ClaimRow> kept = new ArrayList<>();
        Set<UUID> removed = new HashSet<>();
        int merged = 0;
        for (ClaimRepository.ClaimRow c : rows) {
            if (reviewed.stream().anyMatch(r -> same(r, c))) {
                claims.delete(c.id());
                removed.add(c.id());
                merged++;
                continue;
            }
            ClaimRepository.ClaimRow into = kept.stream().filter(k -> same(k, c)).findFirst().orElse(null);
            if (into == null) {
                kept.add(c);
                continue;
            }
            claims.moveEvidence(c.id(), into.id());
            if (c.critical() && !into.critical()) {
                claims.setCritical(into.id());
            }
            claims.delete(c.id());
            removed.add(c.id());
            merged++;
        }
        ctx.count("merged", merged);
    }

    static boolean same(ClaimRepository.ClaimRow a, ClaimRepository.ClaimRow b) {
        if (!a.type().equals(b.type())) {
            return false;
        }
        if (a.type().equals("DECISION") && a.subject() != null && a.subject().equals(b.subject())) {
            String va = a.attr("value");
            String vb = b.attr("value");
            if (va != null && vb != null) {
                return ConflictStage.normaliseValue(va).equals(ConflictStage.normaliseValue(vb));
            }
        }
        if (Objects.equals(a.attr("externalRef"), b.attr("externalRef")) && a.attr("externalRef") != null) {
            return true;
        }
        return jaccard(tokens(a.title()), tokens(b.title())) >= 0.75;
    }

    static Set<String> tokens(String s) {
        return Arrays.stream(s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t -> t.length() > 1 && !STOP.contains(t))
                .collect(Collectors.toSet());
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }
}
