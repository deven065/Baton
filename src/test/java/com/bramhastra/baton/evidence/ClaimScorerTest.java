package com.bramhastra.baton.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.evidence.ClaimScorer.ClaimStatus;
import com.bramhastra.baton.source.SourceType;

class ClaimScorerTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private final ClaimScorer scorer = new ClaimScorer(Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID chat = UUID.randomUUID();
    private final UUID notes = UUID.randomUUID();

    private ClaimScorer.Ev ev(UUID source, SourceType type, String date, boolean valid, Sensitivity s, String... acl) {
        return new ClaimScorer.Ev(source, type, Instant.parse(date + "T00:00:00Z"), valid ? 1.0 : 0.4, valid, s, List.of(acl));
    }

    @Test
    void aClaimTheLeadReplacedIsStaleAtOnceHoweverRecent() {
        var fresh = ev(UUID.randomUUID(), SourceType.MEETING_NOTES, "2026-10-08", true, Sensitivity.INTERNAL);
        var superseded = scorer.score(new ClaimScorer.Input(false, false, true, Sensitivity.INTERNAL, List.of(),
                List.of(fresh), 0, List.of()));
        assertThat(superseded.status()).isEqualTo(ClaimScorer.ClaimStatus.STALE);
    }

    private ClaimScorer.ScoreResult score(boolean reasoning, boolean conflict, ClaimScorer.Ev... evidence) {
        int invalid = (int) List.of(evidence).stream().filter(e -> !e.valid()).count();
        return scorer.score(new ClaimScorer.Input(reasoning, conflict, false, Sensitivity.INTERNAL, List.of(),
                List.of(evidence), invalid, List.of()));
    }

    @Test
    void systemOfRecordAloneIsVerified() {
        var r = score(false, false, ev(UUID.randomUUID(), SourceType.TASK_CSV, "2026-10-18", true, Sensitivity.INTERNAL));
        assertThat(r.status()).isEqualTo(ClaimStatus.VERIFIED);
    }

    @Test
    void oneChatQuoteIsSingleSourceAndTwoSourcesVerify() {
        assertThat(score(false, false, ev(chat, SourceType.CHAT_EXPORT, "2026-10-20", true, Sensitivity.INTERNAL)).status())
                .isEqualTo(ClaimStatus.SINGLE_SOURCE);
        assertThat(score(false, false, ev(chat, SourceType.CHAT_EXPORT, "2026-10-20", true, Sensitivity.INTERNAL),
                ev(notes, SourceType.MEETING_NOTES, "2026-10-08", true, Sensitivity.INTERNAL)).status())
                .isEqualTo(ClaimStatus.VERIFIED);
    }

    @Test
    void conflictWinsAndCostsConfidence() {
        var plain = score(false, false, ev(chat, SourceType.CHAT_EXPORT, "2026-10-20", true, Sensitivity.INTERNAL));
        var conflicting = score(false, true, ev(chat, SourceType.CHAT_EXPORT, "2026-10-20", true, Sensitivity.INTERNAL));
        assertThat(conflicting.status()).isEqualTo(ClaimStatus.CONFLICTING);
        assertThat(conflicting.confidence()).isLessThan(plain.confidence());
    }

    @Test
    void noValidQuoteIsInferredOnlyWithReasoning() {
        var badQuote = ev(chat, SourceType.CHAT_EXPORT, "2026-10-20", false, Sensitivity.INTERNAL);
        assertThat(score(true, false, badQuote).status()).isEqualTo(ClaimStatus.INFERRED);
        var missing = score(false, false, badQuote);
        assertThat(missing.status()).isEqualTo(ClaimStatus.MISSING_EVIDENCE);
        assertThat(missing.reasons()).anyMatch(r -> r.contains("could not be verified"));
    }

    @Test
    void sensitivityIsTheMaximumOfAllEvidenceEvenUnverified() {
        var r = score(false, false,
                ev(chat, SourceType.CHAT_EXPORT, "2026-10-20", true, Sensitivity.INTERNAL),
                ev(notes, SourceType.DOCUMENT, "2026-10-20", false, Sensitivity.RESTRICTED, "operations"));
        assertThat(r.sensitivity()).isEqualTo(Sensitivity.RESTRICTED);
        assertThat(r.aclGroups()).containsExactly("operations");
    }

    @Test
    void recencyDecaysBetween14And120Days() {
        assertThat(ClaimScorer.recency(3)).isEqualTo(1.0);
        assertThat(ClaimScorer.recency(67)).isEqualTo(0.5);
        assertThat(ClaimScorer.recency(500)).isEqualTo(0.0);
    }

    @Test
    void theModelCheckCanOnlyWeakenAClaim() {
        var fact = score(false, false, ev(chat, SourceType.CHAT_EXPORT, "2026-11-01", true, Sensitivity.INTERNAL));
        assertThat(fact.status()).isEqualTo(ClaimStatus.SINGLE_SOURCE);

        var unsupported = ClaimScorer.applyModelCheck(fact, "UNSUPPORTED", "the message is a joke");
        assertThat(unsupported.status()).isEqualTo(ClaimStatus.INFERRED);
        assertThat(unsupported.confidence()).isLessThan(fact.confidence());
        assertThat(unsupported.reasons()).anyMatch(r -> r.contains("model check") && r.contains("the message is a joke"));

        var partial = ClaimScorer.applyModelCheck(fact, "PARTIAL", null);
        assertThat(partial.status()).isEqualTo(ClaimStatus.SINGLE_SOURCE);
        assertThat(partial.confidence()).isLessThan(fact.confidence());

        assertThat(ClaimScorer.applyModelCheck(fact, "SUPPORTED", "ok")).isSameAs(fact);
        assertThat(ClaimScorer.applyModelCheck(fact, null, null)).isSameAs(fact);

        // A conflict stays a conflict: the check never hides a disagreement.
        var conflict = score(false, true, ev(chat, SourceType.CHAT_EXPORT, "2026-11-01", true, Sensitivity.INTERNAL));
        assertThat(ClaimScorer.applyModelCheck(conflict, "UNSUPPORTED", "x").status()).isEqualTo(ClaimStatus.CONFLICTING);
    }
}
