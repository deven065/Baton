package com.bramhastra.baton.source.connector;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.source.SourceType;

/**
 * Jira, Trello or GitHub Projects CSV export. Each row becomes one document, because a task board
 * is a system of record and each task should be citable on its own.
 */
@Component
public class TaskCsvConnector implements SourceConnector {

    private static final List<String> ID_COLS = List.of("issue key", "key", "id", "card id", "number", "#");
    private static final List<String> TITLE_COLS = List.of("summary", "title", "name", "card name", "task");
    private static final List<String> AUTHOR_COLS = List.of("reporter", "creator", "created by", "author");
    private static final List<String> DATE_COLS = List.of("updated", "last activity", "created", "created at", "date");
    private static final List<String> URL_COLS = List.of("url", "link", "card url");

    @Override
    public boolean supports(SourceType type) {
        return type == SourceType.TASK_CSV;
    }

    @Override
    public void validate(Map<String, Object> config, StagedFile file) {
        if (file == null) {
            throw new IllegalArgumentException("A CSV file is required");
        }
        if (!file.extension().equals("csv")) {
            throw new UnsupportedOperationException("Task exports must be .csv");
        }
    }

    @Override
    public List<RawDocument> fetch(FetchRequest request, Consumer<String> status) throws Exception {
        String content = new String(request.file().bytes(), StandardCharsets.UTF_8);
        if (content.startsWith("﻿")) {
            content = content.substring(1);
        }
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setTrim(true).setAllowMissingColumnNames(true).get();
        List<RawDocument> docs = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(new StringReader(content), format)) {
            List<String> headers = parser.getHeaderNames();
            for (CSVRecord row : parser) {
                String id = first(row, headers, ID_COLS);
                String title = first(row, headers, TITLE_COLS);
                StringBuilder text = new StringBuilder();
                for (String h : headers) {
                    if (h == null || h.isBlank() || !row.isMapped(h)) {
                        continue;
                    }
                    String v = row.get(h);
                    if (v != null && !v.isBlank()) {
                        text.append(h).append(": ").append(v.strip()).append('\n');
                    }
                }
                if (text.isEmpty()) {
                    continue;
                }
                String docTitle = (id != null ? id + ": " : "") + (title != null ? title : "Task " + (docs.size() + 1));
                docs.add(new RawDocument(id != null ? id : "row-" + row.getRecordNumber(), truncate(docTitle, 300),
                        first(row, headers, AUTHOR_COLS), parseDate(first(row, headers, DATE_COLS)),
                        first(row, headers, URL_COLS), "text/csv", text.toString().strip()));
            }
        }
        status.accept("Read " + docs.size() + " tasks");
        return docs;
    }

    private static String first(CSVRecord row, List<String> headers, List<String> candidates) {
        for (String c : candidates) {
            for (String h : headers) {
                if (h != null && h.strip().toLowerCase(Locale.ROOT).equals(c) && row.isMapped(h)) {
                    String v = row.get(h);
                    if (v != null && !v.isBlank()) {
                        return v.strip();
                    }
                }
            }
        }
        return null;
    }

    static Instant parseDate(String v) {
        if (v == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (RuntimeException ignored) {
            // try the next format
        }
        for (String pattern : List.of("yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "dd/MMM/yy h:mm a",
                "M/d/yyyy H:mm", "d/M/yyyy H:mm")) {
            try {
                return LocalDateTime.parse(v, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
                        .toInstant(ZoneOffset.UTC);
            } catch (RuntimeException ignored) {
                // try the next format
            }
        }
        try {
            return LocalDate.parse(v.length() >= 10 ? v.substring(0, 10) : v).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
