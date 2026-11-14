package com.bramhastra.baton.staleness;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bramhastra.baton.staleness.StaleDetector.Change;
import com.bramhastra.baton.staleness.StaleDetector.DocChunk;
import com.bramhastra.baton.staleness.StaleDetector.Effect;
import com.bramhastra.baton.staleness.StaleDetector.Finding;
import com.bramhastra.baton.staleness.StaleDetector.Kind;

class StaleDetectorTest {

    private static final Instant DOC_DATE = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant CHANGE_DATE = Instant.parse("2026-11-05T09:00:00Z");

    private static DocChunk doc(String text, Instant at) {
        return new DocChunk(UUID.randomUUID(), UUID.randomUUID(), "s:runbook.md", "Runbook", at, text);
    }

    private static Change change(String message) {
        return new Change("commit 1a2b3c4", message, CHANGE_DATE, UUID.randomUUID(), null);
    }

    @Test
    void readsWhatACommitDid() {
        assertThat(StaleDetector.effects("Rename DATABASE_URL to DB_URL in config"))
                .containsExactly(new Effect(Kind.RENAMED, "DATABASE_URL", "DB_URL"));
        assertThat(StaleDetector.effects("Switch push notifications from Firebase to OneSignal"))
                .containsExactly(new Effect(Kind.REPLACED, "Firebase", "OneSignal"));
        assertThat(StaleDetector.effects("Replace `npm run deploy` with `npm run release`"))
                .containsExactly(new Effect(Kind.REPLACED, "npm run deploy", "npm run release"));
        assertThat(StaleDetector.effects("Remove /api/v1/export endpoint and LEGACY_TOKEN"))
                .containsExactly(new Effect(Kind.REMOVED, "/api/v1/export", null), new Effect(Kind.REMOVED, "LEGACY_TOKEN", null));
        assertThat(StaleDetector.effects("Upgrade to Java 21")).containsExactly(new Effect(Kind.VERSION, "java", "21"));
        assertThat(StaleDetector.effects("bump node 18 -> 20")).containsExactly(new Effect(Kind.VERSION, "node", "20"));
        // Ordinary words are not identifiers, so everyday commits flag nothing.
        assertThat(StaleDetector.effects("Remove unused imports")).isEmpty();
        assertThat(StaleDetector.effects("Fix typo in readme")).isEmpty();
        assertThat(StaleDetector.effects("Move the button to the left")).isEmpty();
    }

    @Test
    void flagsADocThatStillSaysTheOldThing() {
        List<Finding> f = StaleDetector.detect(
                List.of(doc("## Setup\nSet DATABASE_URL to the Neon string before starting.\nThen run it.", DOC_DATE)),
                List.of(change("Rename DATABASE_URL to DB_URL")));
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.kind()).isEqualTo(Kind.RENAMED);
            assertThat(x.term()).isEqualTo("DATABASE_URL");
            assertThat(x.replacement()).isEqualTo("DB_URL");
            assertThat(x.quote()).isEqualTo("Set DATABASE_URL to the Neon string before starting.");
        });

        assertThat(StaleDetector.detect(List.of(doc("We build on Java 17 and Postgres 16.", DOC_DATE)),
                List.of(change("Upgrade to Java 21")))).singleElement()
                .satisfies(x -> {
                    assertThat(x.term()).isEqualTo("Java 17");
                    assertThat(x.replacement()).isEqualTo("Java 21");
                });

        assertThat(StaleDetector.detect(List.of(doc("Push notifications go through Firebase.", DOC_DATE)),
                List.of(change("Switch push notifications from Firebase to OneSignal")))).hasSize(1);
    }

    @Test
    void leavesAloneDocsThatAreAlreadyRightOrNewer() {
        // Already mentions the new name.
        assertThat(StaleDetector.detect(List.of(doc("DATABASE_URL was renamed; set DB_URL now.", DOC_DATE)),
                List.of(change("Rename DATABASE_URL to DB_URL")))).isEmpty();
        // Written after the change.
        assertThat(StaleDetector.detect(List.of(doc("Set DATABASE_URL first.", Instant.parse("2026-11-20T00:00:00Z"))),
                List.of(change("Rename DATABASE_URL to DB_URL")))).isEmpty();
        // Same version.
        assertThat(StaleDetector.detect(List.of(doc("Requires Java 21.", DOC_DATE)), List.of(change("Upgrade to Java 21")))).isEmpty();
        // Mentions that already say it is not used.
        assertThat(StaleDetector.detect(List.of(doc("We decided to use Postgres instead of Firebase for reports.", DOC_DATE)),
                List.of(change("Switch push notifications from Firebase to OneSignal")))).isEmpty();
        assertThat(StaleDetector.detect(List.of(doc("We moved from Firebase last year. Firebase is still used for push.", DOC_DATE)),
                List.of(change("Switch push notifications from Firebase to OneSignal")))).hasSize(1);
        // A longer identifier that only contains the old one.
        assertThat(StaleDetector.detect(List.of(doc("Set DATABASE_URL_READONLY for replicas.", DOC_DATE)),
                List.of(change("Rename DATABASE_URL to DB_URL")))).isEmpty();
    }

    @Test
    void readsCommitLogsFromGitHubAndFromGitLog() {
        String github = """
                1a2b3c4 2026-11-05T09:00:00Z deven065: Rename DATABASE_URL to DB_URL
                5d6e7f8 2026-11-04T08:00:00Z deven065: Add team chat
                9a8b7c6 2026-11-03T07:00:00Z deven065: Fix: login redirect
                """;
        List<Change> c = StaleDetector.commits(github, UUID.randomUUID(), "https://github.com/o/r/commits/main");
        assertThat(c).hasSize(3);
        assertThat(c.get(0).ref()).isEqualTo("commit 1a2b3c4");
        assertThat(c.get(0).url()).isEqualTo("https://github.com/o/r/commit/1a2b3c4");
        assertThat(c.get(2).text()).isEqualTo("Fix: login redirect");
        assertThat(StaleDetector.isCommitLog(github)).isTrue();

        String gitLog = "abcdef1 2026-11-05 14:30:00 +0530 Priya Sharma: Upgrade to Java 21\n";
        assertThat(StaleDetector.commits(gitLog, UUID.randomUUID(), null)).singleElement()
                .satisfies(x -> assertThat(x.at()).isEqualTo(Instant.parse("2026-11-05T09:00:00Z")));
        assertThat(StaleDetector.isCommitLog("Our runbook mentions commit 1a2b3c4 once.")).isFalse();
    }
}
