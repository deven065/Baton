package com.bramhastra.baton.reconstruct.stage;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.evidence.QuoteMatcher;
import com.bramhastra.baton.reconstruct.ClaimRepository;
import com.bramhastra.baton.reconstruct.PipelineContext;
import com.bramhastra.baton.reconstruct.PipelineStage;

/**
 * The truth layer. Every draft quote is located in its stored chunk text by code. A valid match replaces
 * the quote with the exact source substring, so a verified quote can never differ from the source.
 */
@Component
@Order(30)
public class VerifyStage implements PipelineStage {

    private final ClaimRepository claims;
    private final QuoteMatcher matcher;

    public VerifyStage(ClaimRepository claims, QuoteMatcher matcher) {
        this.claims = claims;
        this.matcher = matcher;
    }

    @Override
    public String name() {
        return "VERIFY";
    }

    @Override
    public int weight() {
        return 10;
    }

    @Override
    public void execute(PipelineContext ctx) {
        int valid = 0;
        int invalid = 0;
        for (ClaimRepository.PendingEvidence e : claims.unverifiedEvidence(ctx.handover().id())) {
            QuoteMatcher.MatchResult r = matcher.match(e.modelQuote(), e.chunkText());
            if (r.valid()) {
                claims.markEvidence(e.id(), true, r.score(), r.start(), r.end(), e.chunkText().substring(r.start(), r.end()));
                valid++;
            } else {
                claims.markEvidence(e.id(), false, r.score(), r.start() < 0 ? null : r.start(),
                        r.end() < 0 ? null : r.end(), e.modelQuote());
                invalid++;
            }
        }
        ctx.count("quotesVerified", valid);
        ctx.count("quotesRejected", invalid);
    }
}
