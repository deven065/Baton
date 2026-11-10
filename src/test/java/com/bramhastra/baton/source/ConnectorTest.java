package com.bramhastra.baton.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com.bramhastra.baton.source.connector.ChatExportConnector;
import com.bramhastra.baton.source.connector.DocumentConnector;
import com.bramhastra.baton.source.connector.RawDocument;
import com.bramhastra.baton.source.connector.SourceConnector.FetchRequest;
import com.bramhastra.baton.source.connector.SourceConnector.StagedFile;
import com.bramhastra.baton.source.connector.TaskCsvConnector;

import tools.jackson.databind.json.JsonMapper;

class ConnectorTest {

    private static FetchRequest req(SourceType type, String filename, String content) {
        return new FetchRequest(type, "t", Map.of(),
                new StagedFile(filename, null, content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void taskCsvRowsBecomeCitableDocuments() throws Exception {
        String csv = "﻿Issue key,Summary,Status,Assignee,Reporter,Updated\n"
                + "ANN-12,Migrate payments to Razorpay,In Progress,Priya,Neha,2026-10-18 14:00\n"
                + "ANN-13,Fix canteen menu cache,To Do,,Arjun,2026-10-19 09:30\n";
        List<RawDocument> docs = new TaskCsvConnector().fetch(req(SourceType.TASK_CSV, "jira.csv", csv), s -> { });

        assertThat(docs).hasSize(2);
        RawDocument first = docs.get(0);
        assertThat(first.title()).isEqualTo("ANN-12: Migrate payments to Razorpay");
        assertThat(first.author()).isEqualTo("Neha");
        assertThat(first.authoredAt()).isEqualTo(Instant.parse("2026-10-18T14:00:00Z"));
        assertThat(first.text()).contains("Assignee: Priya", "Status: In Progress");
        assertThat(docs.get(1).text()).doesNotContain("Assignee:");
    }

    @Test
    void slackZipGroupsByChannelDayWithNames() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("users.json"));
            zip.write("[{\"id\":\"U1\",\"name\":\"priya\",\"profile\":{\"real_name\":\"Priya Sharma\"}}]"
                    .getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("dev/2026-10-20.json"));
            zip.write("""
                    [{"type":"message","user":"U1","text":"pushed launch to Nov 29 after canteen feedback","ts":"1792490400.000100"},
                     {"type":"message","subtype":"channel_join","user":"U2","text":"joined","ts":"1792490500.000100"},
                     {"type":"message","user":"U1","text":"ping <@U1> &amp; see <https://example.com|doc>","ts":"1792494000.000200"}]
                    """.getBytes(StandardCharsets.UTF_8));
        }
        StagedFile file = new StagedFile("slack.zip", "application/zip", bytes.toByteArray());
        List<RawDocument> docs = new ChatExportConnector(JsonMapper.builder().build())
                .fetch(new FetchRequest(SourceType.CHAT_EXPORT, "t", Map.of(), file), s -> { });

        assertThat(docs).hasSize(1);
        RawDocument day = docs.get(0);
        assertThat(day.title()).isEqualTo("#dev · 2026-10-20");
        assertThat(day.text()).startsWith("[10:00] Priya Sharma: pushed launch to Nov 29 after canteen feedback")
                .contains("ping @Priya Sharma & see doc (https://example.com)")
                .doesNotContain("joined");
    }

    private static List<RawDocument> chat(String filename, byte[] bytes) throws Exception {
        return new ChatExportConnector(JsonMapper.builder().build())
                .fetch(new FetchRequest(SourceType.CHAT_EXPORT, "t", Map.of(), new StagedFile(filename, null, bytes)), s -> { });
    }

    @Test
    void whatsAppAndroidExportIsReadDayFirstWithContinuationLines() throws Exception {
        String txt = """
                08/10/2026, 21:02 - Messages and calls are end-to-end encrypted. No one outside of this chat can read them.
                08/10/2026, 21:02 - Priya Sharma created group "Annapurna Core"
                14/10/2026, 21:14 - Priya Sharma: We decided to use Cloudinary for menu photos
                instead of storing them in Postgres.
                14/10/2026, 21:15 - Arjun Mehta: <Media omitted>
                15/10/2026, 09:05 - Arjun Mehta: ok
                """;
        List<RawDocument> docs = chat("WhatsApp Chat with Annapurna Core.txt", txt.getBytes(StandardCharsets.UTF_8));

        assertThat(docs).hasSize(2);
        assertThat(docs.get(0).title()).isEqualTo("Annapurna Core · 2026-10-14");
        assertThat(docs.get(0).authoredAt()).isEqualTo(Instant.parse("2026-10-14T00:00:00Z"));
        assertThat(docs.get(0).text())
                .isEqualTo("[21:14] Priya Sharma: We decided to use Cloudinary for menu photos\n    instead of storing them in Postgres.");
        assertThat(docs.get(1).text()).isEqualTo("[09:05] Arjun Mehta: ok");
    }

    @Test
    void whatsAppIphoneExportWithTwelveHourClockAndInvisibleMarks() throws Exception {
        String txt = "‎[10/20/26, 9:14:05 PM] Priya Sharma: Payments go live after the vendor sends the key\n"
                + "[10/20/26, 9:15:00 AM] Rahul Desai: ‎image omitted\n";
        List<RawDocument> docs = chat("_chat.txt", txt.getBytes(StandardCharsets.UTF_8));

        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).title()).isEqualTo("WhatsApp chat · 2026-10-20"); // month-first: 20 > 12
        assertThat(docs.get(0).text()).isEqualTo("[21:14] Priya Sharma: Payments go live after the vendor sends the key");
    }

    @Test
    void whatsAppZipWithMediaReadsOnlyTheChat() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("_chat.txt"));
            zip.write("14/10/2026, 21:14 - Priya Sharma: The domain is on my card\n".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("00000012-PHOTO-2026-10-14.jpg"));
            zip.write(new byte[] {1, 2, 3});
        }
        List<RawDocument> docs = chat("WhatsApp Chat - Annapurna Core.zip", bytes.toByteArray());
        assertThat(docs).singleElement().satisfies(d -> {
            assertThat(d.title()).isEqualTo("Annapurna Core · 2026-10-14");
            assertThat(d.text()).isEqualTo("[21:14] Priya Sharma: The domain is on my card");
        });
    }

    @Test
    void discordChatExporterJsonUsesNicknamesAndLocalTime() throws Exception {
        String json = """
                {"guild":{"name":"Annapurna Club"},"channel":{"id":"9","name":"design","category":"Text Channels"},
                 "messages":[
                  {"id":"1","type":"Default","timestamp":"2026-10-21T18:30:00.123+05:30","content":"<@42> owns the Figma file for the order flow",
                   "author":{"id":"7","name":"neha","nickname":"Neha Kulkarni"},"mentions":[{"id":"42","name":"rahul","nickname":"Rahul Desai"}]},
                  {"id":"2","type":"GuildMemberJoin","timestamp":"2026-10-21T18:31:00+05:30","content":"","author":{"id":"8","name":"bot"}},
                  {"id":"3","type":"Reply","timestamp":"2026-10-21T18:35:00+05:30","content":"on it <:thumbsup:123456>",
                   "author":{"id":"42","name":"rahul","nickname":""}}]}
                """;
        List<RawDocument> docs = chat("Annapurna Club - Text Channels - design [9].json", json.getBytes(StandardCharsets.UTF_8));

        assertThat(docs).singleElement().satisfies(d -> {
            assertThat(d.title()).isEqualTo("#design · 2026-10-21");
            assertThat(d.text()).isEqualTo("[18:30] Neha Kulkarni: @Rahul Desai owns the Figma file for the order flow\n"
                    + "[18:35] rahul: on it :thumbsup:");
        });
    }

    @Test
    void discordCsvExportAndUnknownFilesAreTold() throws Exception {
        String csv = "AuthorID,Author,Date,Content,Attachments,Reactions\n"
                + "7,neha,2026-10-22T10:00:00.0000000+05:30,\"Waiting for the committee to approve the new logo\",,\n";
        List<RawDocument> docs = chat("Annapurna Club - Text Channels - design [9].csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(docs).singleElement().satisfies(d ->
                assertThat(d.text()).isEqualTo("[10:00] neha: Waiting for the committee to approve the new logo"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> chat("notes.txt", "just some notes".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("not a WhatsApp chat export");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> chat("tasks.csv", "Issue key,Summary\n".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("not a Discord export");
    }

    @Test
    void meetingNotesDateAndTitleFromContent() throws Exception {
        String md = "# Weekly sync\n\nDate: 2026-10-08\n\nDecision: launch on 15 November.";
        List<RawDocument> docs = new DocumentConnector().fetch(req(SourceType.MEETING_NOTES, "sync.md", md), s -> { });
        assertThat(docs.get(0).title()).isEqualTo("Weekly sync");
        assertThat(docs.get(0).authoredAt()).isEqualTo(Instant.parse("2026-10-08T00:00:00Z"));
    }
}
