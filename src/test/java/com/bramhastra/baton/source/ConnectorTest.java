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

    @Test
    void meetingNotesDateAndTitleFromContent() throws Exception {
        String md = "# Weekly sync\n\nDate: 2026-10-08\n\nDecision: launch on 15 November.";
        List<RawDocument> docs = new DocumentConnector().fetch(req(SourceType.MEETING_NOTES, "sync.md", md), s -> { });
        assertThat(docs.get(0).title()).isEqualTo("Weekly sync");
        assertThat(docs.get(0).authoredAt()).isEqualTo(Instant.parse("2026-10-08T00:00:00Z"));
    }
}
