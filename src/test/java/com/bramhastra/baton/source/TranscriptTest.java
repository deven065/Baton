package com.bramhastra.baton.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.bramhastra.baton.source.connector.Transcript;

class TranscriptTest {

    @Test
    void readsTeamsStyleVttWithVoiceTags() {
        String vtt = """
                WEBVTT

                1
                00:00:00.000 --> 00:00:07.200
                <v Priya Sharma>Hi Arjun, this is my handover call.</v>

                2
                00:00:07.800 --> 00:00:13.495
                <v Arjun Mehta>Go ahead.</v>

                3
                00:01:14.095 --> 00:01:24.270
                <v Priya Sharma>The webhook secret is rotated every ninety days.</v>
                """;
        List<Transcript.Segment> s = Transcript.parse(vtt);
        assertThat(s).extracting(Transcript.Segment::speaker).containsExactly("Priya Sharma", "Arjun Mehta", "Priya Sharma");
        assertThat(s.get(2).start()).isEqualTo(74.0);
        assertThat(Transcript.render(s)).contains("[00:01:14] Priya Sharma: The webhook secret is rotated every ninety days.");
    }

    @Test
    void readsSrtWithNamePrefixesAndJoinsTheSameSpeaker() {
        String srt = """
                1
                00:00:01,000 --> 00:00:03,000
                Neha: Launch is on 29 November.

                2
                00:00:03,500 --> 00:00:05,000
                Neha: Tell the design team.

                3
                01:02:03,000 --> 01:02:05,000
                Rahul: Will do.
                """;
        List<Transcript.Segment> s = Transcript.parse(srt);
        assertThat(s).hasSize(2);
        assertThat(s.get(0).text()).isEqualTo("Launch is on 29 November. Tell the design team.");
        assertThat(Transcript.clock(s.get(1).start())).isEqualTo("01:02:03");
    }

    @Test
    void aQuoteFindsTheMomentItWasSaid() {
        String chunk = "[00:00:00] Priya: Hello.\n[00:00:14] Priya: The secret rotates every ninety days.\n[00:01:05] Arjun: Thanks.\n";
        assertThat(Transcript.secondsAt(chunk, chunk.indexOf("rotates"))).isEqualTo(14);
        assertThat(Transcript.secondsAt(chunk, chunk.indexOf("Thanks"))).isEqualTo(65);
        assertThat(Transcript.secondsAt("no stamps here", 3)).isNull();
    }
}
