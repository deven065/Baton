package com.bramhastra.baton.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bramhastra.baton.knowledge.ExpertService.Expert;
import com.bramhastra.baton.knowledge.ExpertService.Verdict;
import com.bramhastra.baton.knowledge.MessageParser.Message;
import com.bramhastra.baton.knowledge.ProjectAskService.Fact;

class KnowledgeUnitTest {

    private static final Instant DAY = Instant.parse("2026-11-05T00:00:00Z");

    // ───────────── who said what ─────────────

    @Test
    void chatLinesGitHubCommentsAndNotesAreAttributedToTheRightPerson() {
        String text = """
                # Payments chat
                Date: 2026-11-05
                [09:10] Priya Sharma: first line
                    continued on the next line
                [09:20] Rahul Desai: ok
                — deven065 (2026-10-12T09:30:00Z): a comment
                Plain paragraph
                """;
        List<Message> m = MessageParser.parse(text, "Doc Author", DAY);
        assertThat(m).extracting(Message::author).contains("Priya Sharma", "Rahul Desai", "deven065");
        Message priya = m.stream().filter(x -> "Priya Sharma".equals(x.author())).findFirst().orElseThrow();
        assertThat(priya.text()).isEqualTo("first line\ncontinued on the next line");
        assertThat(priya.at()).isEqualTo(Instant.parse("2026-11-05T09:10:00Z"));
        assertThat(m.stream().filter(x -> "deven065".equals(x.author())).findFirst().orElseThrow().at())
                .isEqualTo(Instant.parse("2026-10-12T09:30:00Z"));
        // Offsets cut the original text back out word for word.
        assertThat(text.substring(priya.start(), priya.end())).contains("first line").contains("continued");
    }

    // ───────────── expert scoring ─────────────

    @Test
    void wordFormsMatchAndQuestionWordsAreDropped() {
        assertThat(ExpertService.sameWord("payments", "payment")).isTrue();
        assertThat(ExpertService.sameWord("billing", "bill")).isTrue();
        assertThat(ExpertService.sameWord("api", "apis")).isFalse(); // too short to guess
        assertThat(ExpertService.sameWord("kafka", "rabbitmq")).isFalse();
        assertThat(ExpertService.terms("Who understands the billing module?")).containsExactly("billing");
        assertThat(ExpertService.terms("who knows about payments and refunds")).containsExactly("payments", "refunds");
        assertThat(ExpertService.distinctHits("Payments refund flow is done", List.of("payment", "refunds", "kafka"))).isEqualTo(2);
    }

    @Test
    void recentWorkCountsMoreThanOldWork() {
        Instant now = Instant.parse("2026-11-14T00:00:00Z");
        double today = ExpertService.recency(now, now);
        double halfYear = ExpertService.recency(now.minus(java.time.Duration.ofDays(180)), now);
        double ancient = ExpertService.recency(now.minus(java.time.Duration.ofDays(1500)), now);
        assertThat(today).isGreaterThan(0.99);
        assertThat(halfYear).isBetween(0.6, 0.72);
        assertThat(ancient).isBetween(0.35, 0.4).isLessThan(halfYear);
        assertThat(ExpertService.typeWeight("GITHUB")).isGreaterThan(ExpertService.typeWeight("CHAT_EXPORT"));
    }

    private static Expert expert(String name, double share, int passages, boolean leaving) {
        return new Expert(name, UUID.randomUUID(), "MEMBER", true, leaving, 50, share, passages, DAY, DAY, List.of());
    }

    @Test
    void theVerdictNamesASinglePointOfFailureAndABackup() {
        // One person wrote almost everything, nobody else has touched it.
        Verdict alone = ExpertService.verdict("payments", List.of(expert("Priya Sharma", 0.97, 8, false), expert("Arjun Mehta", 0.03, 1, false)));
        assertThat(alone.singlePointOfFailure()).isTrue();
        assertThat(alone.level()).isEqualTo("HIGH");
        assertThat(alone.backup()).isNull();
        assertThat(alone.message()).contains("Priya", "Nobody else can cover");

        // The same, and the person is leaving.
        assertThat(ExpertService.verdict("payments", List.of(expert("Priya Sharma", 1.0, 8, true))).level()).isEqualTo("CRITICAL");

        // A clear second person is the backup.
        Verdict covered = ExpertService.verdict("payments", List.of(expert("Priya Sharma", 0.78, 8, false), expert("Arjun Mehta", 0.22, 3, false)));
        assertThat(covered.singlePointOfFailure()).isTrue();
        assertThat(covered.level()).isEqualTo("MEDIUM");
        assertThat(covered.backup()).isEqualTo("Arjun Mehta");
        assertThat(covered.message()).contains("Backup: Arjun Mehta");

        // Leaving and a backup exists: say who takes over.
        assertThat(ExpertService.verdict("payments", List.of(expert("Priya Sharma", 0.78, 8, true), expert("Arjun Mehta", 0.22, 3, false))).message())
                .contains("is leaving", "start the handover to Arjun");

        // Shared knowledge is fine.
        Verdict shared = ExpertService.verdict("payments", List.of(expert("Priya Sharma", 0.5, 5, false), expert("Arjun Mehta", 0.4, 4, false)));
        assertThat(shared.singlePointOfFailure()).isFalse();
        assertThat(shared.level()).isEqualTo("LOW");

        assertThat(ExpertService.verdict("payments", List.of()).level()).isEqualTo("NONE");
    }

    // ───────────── how sure an answer is ─────────────

    private static Fact fact(String kind, double score, Set<String> covered, boolean structured, String detail, String source) {
        return new Fact(kind, "title", null, "quote " + score, "src", "Priya", DAY, null, UUID.randomUUID(), detail, score, covered,
                structured, false, source);
    }

    @Test
    void confidenceComesFromCoverageAndIndependentSources_notFromTheModel() {
        List<String> terms = List.of("kafka", "rabbitmq", "replay");
        Set<String> all = Set.of("kafka", "rabbitmq", "replay");
        // A decision covering the question, in one source only: medium.
        assertThat(ProjectAskService.confidence(List.of(fact("DECISION", 9, all, true, "Decision", "a")), terms)).isEqualTo("MEDIUM");
        // The lead confirmed it: high.
        assertThat(ProjectAskService.confidence(List.of(fact("DECISION", 9, all, true, "Confirmed by the lead", "a")), terms)).isEqualTo("HIGH");
        // Two independent sources agree: high.
        assertThat(ProjectAskService.confidence(List.of(fact("DECISION", 9, all, true, "Decision", "a"), fact("CLAIM", 6, all, true, "Single source", "b")), terms))
                .isEqualTo("HIGH");
        // One loose passage, even covering every word: low.
        assertThat(ProjectAskService.confidence(List.of(fact("PASSAGE", 4, all, false, "Passage", "a")), terms)).isEqualTo("LOW");
        // Only part of the question is covered: low.
        assertThat(ProjectAskService.confidence(List.of(fact("DECISION", 9, Set.of("kafka"), true, "Decision", "a")), terms)).isEqualTo("LOW");
        assertThat(ProjectAskService.confidence(List.of(), terms)).isEqualTo("NONE");
    }

    @Test
    void aFactSharingOneWordWithALongQuestionIsNotAnAnswer() {
        List<String> terms = ProjectAskService.terms("What is our policy on refunds for cancelled canteen orders?");
        assertThat(terms).containsExactly("policy", "refunds", "cancelled", "canteen", "orders");
        Fact weak = new Fact("PASSAGE", "t", null, "q", "s", null, null, null, null, "Passage", 3.0, Set.of("orders"), false, false, "a");
        assertThat(ProjectAskService.pick(List.of(weak), terms)).isEmpty();
        Fact fine = new Fact("PASSAGE", "t", null, "q2", "s", null, null, null, null, "Passage", 4.5, Set.of("refunds", "orders"), false, false, "a");
        assertThat(ProjectAskService.pick(List.of(weak, fine), terms)).containsExactly(fine);
    }

    @Test
    void theBuiltInAnswerStatesTheReasonOrAdmitsItIsMissing() {
        Fact withWhy = new Fact("DECISION", "Choose Kafka over RabbitMQ", "we need message replay", "q", "s", "Arjun Mehta", DAY, null, null, "Decision",
                9, Set.of("kafka"), true, true, "a");
        assertThat(ProjectAskService.extractive(withWhy, true, "MEDIUM"))
                .isEqualTo("Decision: Choose Kafka over RabbitMQ. Why: we need message replay. Decided by Arjun Mehta on 5 Nov 2026.");
        Fact noWhy = new Fact("DECISION", "Public launch on 15 November", null, "q", "s", "Neha Kulkarni", DAY, null, null, "Decision",
                9, Set.of("launch"), true, false, "a");
        assertThat(ProjectAskService.extractive(noWhy, true, "MEDIUM")).contains("record this decision but not the reason", "ask Neha");
        assertThat(ProjectAskService.extractive(noWhy, false, "MEDIUM")).doesNotContain("not the reason");
    }
}
