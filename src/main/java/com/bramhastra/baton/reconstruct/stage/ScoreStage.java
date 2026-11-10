package com.bramhastra.baton.reconstruct.stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.evidence.ClaimScorer;
import com.bramhastra.baton.reconstruct.ClaimRepository;
import com.bramhastra.baton.reconstruct.PipelineContext;
import com.bramhastra.baton.reconstruct.PipelineStage;

/** Applies {@link ClaimScorer} to every claim of the handover. Pure, so safe to recompute any time. */
@Component
@Order(60)
public class ScoreStage implements PipelineStage {

    private final ClaimRepository claims;
    private final ClaimScorer scorer;

    public ScoreStage(ClaimRepository claims, ClaimScorer scorer) {
        this.claims = claims;
        this.scorer = scorer;
    }

    @Override
    public String name() {
        return "SCORE";
    }

    @Override
    public int weight() {
        return 5;
    }

    @Override
    public void execute(PipelineContext ctx) {
        rescore(ctx.handover().id());
    }

    /** Also used after a gap answer or an edit (M4). */
    public void rescore(UUID handoverId) {
        Map<UUID, List<ClaimRepository.EvidenceRow>> evidence = claims.evidence(handoverId).stream()
                .collect(Collectors.groupingBy(ClaimRepository.EvidenceRow::claimId));
        List<ClaimRepository.LinkRow> links = claims.links(handoverId);
        List<ClaimRepository.ClaimRow> all = claims.claims(handoverId);
        java.util.Set<UUID> rejected = all.stream().filter(x -> x.reviewState().equals("REJECTED"))
                .map(ClaimRepository.ClaimRow::id).collect(Collectors.toSet());
        for (ClaimRepository.ClaimRow c : all) {
            List<ClaimRepository.EvidenceRow> ev = evidence.getOrDefault(c.id(), List.of());
            boolean conflict = links.stream().anyMatch(l -> l.kind().equals("CONFLICTS_WITH") && !l.resolved()
                    && (l.from().equals(c.id()) || l.to().equals(c.id())));
            // Superseded only by a claim that is itself still in play (a rejected claim supersedes nothing).
            boolean superseded = links.stream().anyMatch(l -> l.kind().equals("SUPERSEDES") && l.to().equals(c.id())
                    && !rejected.contains(l.from()));
            String reasoning = c.attr("reasoning");
            List<String> extra = new ArrayList<>();
            if (c.flags().contains("PROMPT_INJECTION_SUSPECTED")) {
                extra.add("source text contains instructions aimed at the AI; check it carefully");
            }
            c.reasons().stream().filter(r -> r.contains("were dropped")).forEach(extra::add);
            int invalid = (int) ev.stream().filter(e -> !e.valid()).count();
            ClaimScorer.ScoreResult r = scorer.score(new ClaimScorer.Input(
                    reasoning != null && !reasoning.isBlank(), conflict, superseded, c.sensitivity(), c.aclGroups(),
                    ev.stream().map(e -> new ClaimScorer.Ev(e.sourceId(), e.sourceType(), e.authoredAt(),
                            e.matchScore(), e.valid(), e.chunkSensitivity(), e.chunkAcl())).toList(),
                    invalid, extra));
            claims.updateScore(c.id(), r.status().name(), r.confidence(), r.sensitivity(), r.aclGroups(), r.reasons());
        }
    }
}
