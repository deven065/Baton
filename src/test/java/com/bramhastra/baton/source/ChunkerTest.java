package com.bramhastra.baton.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ChunkerTest {

    private final Chunker chunker = new Chunker();

    @Test
    void shortTextIsOneChunk() {
        List<Chunker.Piece> pieces = chunker.split("  Launch moved to Nov 29.  ");
        assertThat(pieces).hasSize(1);
        assertThat(pieces.get(0).text()).isEqualTo("Launch moved to Nov 29.");
    }

    @Test
    void chunksAreExactSubstringsWithinLimitsAndCoverEverything() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("Paragraph ").append(i).append(": the canteen app needs owner review before launch. ")
                    .append("Payment flow was discussed again.\n\n");
        }
        String text = sb.toString();
        List<Chunker.Piece> pieces = chunker.split(text);

        assertThat(pieces.size()).isGreaterThan(3);
        for (Chunker.Piece p : pieces) {
            assertThat(text.substring(p.start(), p.end())).isEqualTo(p.text());
            assertThat(p.text().length()).isLessThanOrEqualTo(Chunker.MAX);
        }
        for (int i = 0; i < 60; i++) {
            String marker = "Paragraph " + i + ":";
            assertThat(pieces).anyMatch(p -> p.text().contains(marker));
        }
        for (int i = 1; i < pieces.size(); i++) {
            assertThat(pieces.get(i).start()).isLessThan(pieces.get(i - 1).end()); // overlap
            assertThat(pieces.get(i).start()).isGreaterThan(pieces.get(i - 1).start()); // progress
        }
    }

    @Test
    void longTextWithoutSpacesStillTerminates() {
        String text = "x".repeat(5000);
        List<Chunker.Piece> pieces = chunker.split(text);
        assertThat(pieces).isNotEmpty();
        assertThat(pieces.get(pieces.size() - 1).end()).isEqualTo(5000);
    }
}
