package com.bramhastra.baton.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.bramhastra.baton.knowledge.DecisionDetector.Found;
import com.bramhastra.baton.knowledge.DecisionDetector.Passage;

class DecisionDetectorTest {

    private static final Instant DAY = Instant.parse("2026-10-14T00:00:00Z");

    private static List<Found> detect(String text) {
        return DecisionDetector.detect(new Passage("doc", null, DAY, text));
    }

    private static List<Found> detect(String text, String author) {
        return DecisionDetector.detect(new Passage("doc", author, DAY, text));
    }

    /** Whatever is returned as a quote or a reason must be words from the passage, never invented. */
    private static void assertVerbatim(String text, Found f) {
        assertThat(text).contains(f.quote());
        if (f.hasReason()) {
            assertThat(text).contains(f.reasoning().replace("…", ""));
        }
    }

    @Test
    void aDecisionWithItsWhyAndWhatItReplaced() {
        String text = "We decided to use Postgres instead of Firebase because we need SQL reports for the canteen.";
        List<Found> f = detect(text);
        assertThat(f).singleElement().satisfies(d -> {
            assertThat(d.title()).isEqualTo("Use Postgres instead of Firebase");
            assertThat(d.reasoning()).isEqualTo("because we need SQL reports for the canteen");
            assertThat(d.alternatives()).containsExactly("Firebase");
            assertThat(d.topic()).isEqualTo("Postgres");
            assertThat(d.confidence()).isGreaterThan(0.9);
            assertVerbatim(text, d);
        });
    }

    @Test
    void meetingNoteLabelsAndWhoSaidItInChat() {
        assertThat(detect("# Weekly sync\n\nDate: 2026-10-08\n\n- Decision: public launch on 15 November.\n- Priya owns the payments migration."))
                .singleElement().satisfies(d -> {
                    assertThat(d.title()).isEqualTo("Public launch on 15 November");
                    assertThat(d.topic()).isEqualTo("public launch");
                    assertThat(d.decidedAt()).isEqualTo(Instant.parse("2026-10-08T00:00:00Z"));
                });

        String chat = "[21:14] Priya Sharma: We decided to use Cloudinary for menu photos instead of uploading them to our own server.\n"
                + "[21:16] Arjun Mehta: makes sense";
        assertThat(detect(chat)).singleElement().satisfies(d -> {
            assertThat(d.decidedBy()).isEqualTo("Priya Sharma");
            assertThat(d.decidedAt()).isEqualTo(Instant.parse("2026-10-14T21:14:00Z"));
            assertThat(d.topic()).isEqualTo("Cloudinary");
            assertVerbatim(chat, d);
        });
    }

    @Test
    void reschedulingWithAReasonAfterIt() {
        String text = "[10:02] Priya: Pushed public launch to Nov 29 after canteen feedback on the menu flow.";
        assertThat(detect(text)).singleElement().satisfies(d -> {
            assertThat(d.title()).isEqualTo("Push public launch to Nov 29");
            assertThat(d.reasoning()).isEqualTo("after canteen feedback on the menu flow");
            assertThat(d.topic()).isEqualTo("public launch");
        });
    }

    @Test
    void aPullRequestThreadWithTheReasonInTheSameBreath() {
        String text = """
                Pull request #41: Use a message queue for orders
                State: closed

                — deven065 (2026-10-12T09:30:00Z): We chose Kafka over RabbitMQ because we need message replay and ordering per key.
                — priya (2026-10-12T10:05:00Z): LGTM, shipping it.
                """;
        assertThat(detect(text)).singleElement().satisfies(d -> {
            assertThat(d.title()).isEqualTo("Choose Kafka over RabbitMQ because we need message replay and ordering per key".split(" because")[0]);
            assertThat(d.reasoning()).isEqualTo("because we need message replay and ordering per key");
            assertThat(d.alternatives()).containsExactly("RabbitMQ");
            assertThat(d.decidedBy()).isEqualTo("deven065");
            assertThat(d.decidedAt()).isEqualTo(Instant.parse("2026-10-12T09:30:00Z"));
            assertThat(d.topic()).isEqualTo("Kafka");
            assertVerbatim(text, d);
        });
    }

    @Test
    void theReasonCanComeInTheNextMessageOrBeforeTheDecision() {
        String chat = "[10:00] Neha: Going with OneSignal for push.\n[10:01] Neha: because the Firebase free-tier quota ran out";
        assertThat(detect(chat)).singleElement().satisfies(d -> {
            assertThat(d.title()).isEqualTo("Go with OneSignal for push");
            assertThat(d.reasoning()).isEqualTo("the Firebase free-tier quota ran out");
            assertVerbatim(chat, d);
        });

        String before = "Because the free-tier quota ran out, we decided to move to OneSignal.";
        assertThat(detect(before)).singleElement().satisfies(d -> {
            assertThat(d.reasoning()).isEqualTo("the free-tier quota ran out");
            assertThat(d.title()).isEqualTo("Move to OneSignal");
        });
    }

    @Test
    void anAdrRecordUsesItsContextAsTheWhy() {
        String adr = """
                # ADR 7: Message queue

                ## Status
                Accepted

                ## Context
                Orders must never be lost when the API restarts, and the warehouse reads them in order.

                ## Decision
                We will use Kafka for the order stream.

                ## Alternatives considered
                - RabbitMQ
                - Amazon SQS
                """;
        assertThat(detect(adr, "Deven")).singleElement().satisfies(d -> {
            assertThat(d.title()).isEqualTo("Use Kafka for the order stream");
            assertThat(d.reasoning()).startsWith("Orders must never be lost");
            assertThat(d.alternatives()).containsExactly("RabbitMQ", "Amazon SQS");
            assertThat(d.decidedBy()).isEqualTo("Deven");
            assertThat(d.confidence()).isGreaterThan(0.9);
            assertVerbatim(adr, d);
        });
    }

    @Test
    void questionsHedgesDenialsAndRequestsAreNotDecisions() {
        for (String s : List.of(
                "Should we use Kafka instead of RabbitMQ?",
                "We haven't decided on the vendor yet.",
                "If we decide to move to Kafka we will need more RAM.",
                "Maybe we should go with Kafka.",
                "Please decide by Friday.",
                "We need to decide between Kafka and RabbitMQ.",
                "Not sure we agreed on anything.",
                "I was thinking about switching from Jenkins to GitHub Actions.",
                "Proposal: use Redis for sessions.",
                "We use Postgres on Neon.",
                "I'll use the staging database for the tests.")) {
            assertThat(detect(s)).as(s).isEmpty();
        }
    }

    @Test
    void aWeakPhraseNeedsAReasonOrAnAlternativeToCount() {
        assertThat(detect("We will use Redis because sessions must survive restarts.")).singleElement()
                .satisfies(d -> assertThat(d.reasoning()).isEqualTo("because sessions must survive restarts"));
        assertThat(detect("We'll use Redis instead of Memcached.")).singleElement()
                .satisfies(d -> assertThat(d.alternatives()).containsExactly("Memcached"));
    }

    @Test
    void movingAwayFromSomething() {
        String text = "We switched from Jenkins to GitHub Actions since builds kept timing out.";
        assertThat(detect(text)).singleElement().satisfies(d -> {
            assertThat(d.title()).isEqualTo("Switch from Jenkins to GitHub Actions");
            assertThat(d.alternatives()).contains("Jenkins");
            assertThat(d.reasoning()).isEqualTo("since builds kept timing out");
        });
    }

    @Test
    void theSameDecisionIsReportedOnceAndOrdinaryTextIsIgnored() {
        assertThat(detect("# Notes\n\nDecision: keep the weekly sync on Mondays.\nDecision: keep the weekly sync on Mondays.")).hasSize(1);
        assertThat(detect("The API is built on Java 17 and runs on Render.\nThe menu cache breaks again every Monday.")).isEmpty();
        assertThat(detect("")).isEmpty();
        assertThat(DecisionDetector.key("Use  Postgres, instead of Firebase!")).isEqualTo("use postgres instead of firebase");
    }
}
