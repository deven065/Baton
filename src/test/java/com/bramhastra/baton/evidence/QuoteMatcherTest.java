package com.bramhastra.baton.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QuoteMatcherTest {

    private final QuoteMatcher matcher = new QuoteMatcher();

    private static final String CHUNK = "[10:00] Priya Sharma: Pushed public launch to Nov 29 after canteen feedback.\n"
            + "[10:05] Arjun Mehta: ok,   I'll tell the design folks";

    @Test
    void exactQuoteIsValidAndOffsetsCutTheOriginal() {
        QuoteMatcher.MatchResult r = matcher.match("Pushed public launch to Nov 29", CHUNK);
        assertThat(r.valid()).isTrue();
        assertThat(r.score()).isEqualTo(1.0);
        assertThat(CHUNK.substring(r.start(), r.end())).isEqualTo("Pushed public launch to Nov 29");
    }

    @Test
    void caseAndWhitespaceDifferencesStillMatchExactly() {
        QuoteMatcher.MatchResult r = matcher.match("ok, I'll  TELL the design folks", CHUNK);
        assertThat(r.valid()).isTrue();
        assertThat(CHUNK.substring(r.start(), r.end())).isEqualTo("ok,   I'll tell the design folks");
    }

    @Test
    void smallSlipIsAcceptedButParaphraseIsRejected() {
        assertThat(matcher.match("Pushed the public launch to Nov 29 after canteen feedback", CHUNK).valid()).isTrue();
        QuoteMatcher.MatchResult para = matcher.match("The launch was postponed to late November by Priya", CHUNK);
        assertThat(para.valid()).isFalse();
        assertThat(para.score()).isLessThan(QuoteMatcher.VALID_THRESHOLD);
    }

    @Test
    void inventedOrTooShortQuotesAreInvalid() {
        assertThat(matcher.match("we will launch on 15 November", CHUNK).valid()).isFalse();
        assertThat(matcher.match("Nov 29", CHUNK).valid()).isFalse();
        assertThat(matcher.match(null, CHUNK).valid()).isFalse();
    }
}
