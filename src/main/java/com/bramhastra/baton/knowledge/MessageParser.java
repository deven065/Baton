package com.bramhastra.baton.knowledge;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a passage into who said what. Chat exports and Slack days are stored as {@code [14:05] Priya: text} (with
 * continuation lines indented), GitHub threads as {@code — login (2026-10-12T09:00:00Z): text}, task rows and issues
 * carry an {@code Author:} line, meeting notes a {@code Date:} line. Everything else is read as plain prose with no
 * speaker. Offsets point into the original text so a quote can always be cut back out word for word.
 */
public final class MessageParser {

    private MessageParser() {
    }

    /** One message or paragraph. {@code start}/{@code end} are offsets into the parsed text. */
    public record Message(String author, Instant at, String text, int start, int end) {
    }

    private static final Pattern CHAT = Pattern.compile("^\\[(\\d{1,2}):(\\d{2})(?::\\d{2})?]\\s+([^:\\n]{1,60}?):\\s+(.*)$");
    private static final Pattern COMMENT = Pattern.compile("^[—–-]\\s*([^()\\n]{1,40}?)\\s*\\(([^)]{6,40})\\):\\s*(.*)$");
    private static final Pattern AUTHOR_LINE = Pattern.compile("^(?:Author|Reporter|Created by|Owner)\\s*:\\s*(.{2,60})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_LINE = Pattern.compile("^(?:Date|Created|Meeting date)\\s*:\\s*(\\d{4}-\\d{2}-\\d{2}).*$", Pattern.CASE_INSENSITIVE);

    /**
     * @param docAuthor the document's own author, used for lines with no speaker (may be null)
     * @param docAt     the document's date: the day a chat passage belongs to, and the fallback for any message
     */
    public static List<Message> parse(String text, String docAuthor, Instant docAt) {
        List<Message> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String author = docAuthor;
        Instant at = docAt;
        LocalDate day = docAt == null ? null : docAt.atZone(ZoneOffset.UTC).toLocalDate();
        int pos = 0;
        // The message being built: its speaker, time and where it started.
        String curAuthor = null;
        Instant curAt = null;
        StringBuilder cur = null;
        int curStart = 0;
        int curEnd = 0;
        boolean chatLike = false;
        for (String raw : text.split("\n", -1)) {
            int lineStart = pos;
            pos += raw.length() + 1;
            String line = raw.stripTrailing();
            Matcher chat = CHAT.matcher(line);
            Matcher comment = COMMENT.matcher(line);
            if (chat.matches() || comment.matches()) {
                flush(out, curAuthor, curAt, cur, curStart, curEnd);
                chatLike = true;
                if (chat.reset().matches()) {
                    curAuthor = chat.group(3).strip();
                    curAt = day == null ? docAt : day.atTime(LocalTime.of(Math.min(23, Integer.parseInt(chat.group(1))),
                            Math.min(59, Integer.parseInt(chat.group(2))))).toInstant(ZoneOffset.UTC);
                    cur = new StringBuilder(chat.group(4));
                } else {
                    comment.reset().matches();
                    curAuthor = comment.group(1).strip();
                    curAt = parseInstant(comment.group(2), docAt);
                    cur = new StringBuilder(comment.group(3));
                }
                curStart = lineStart;
                curEnd = lineStart + raw.length();
                continue;
            }
            if (chatLike && cur != null && (raw.startsWith("    ") || raw.startsWith("\t")) && !line.isBlank()) {
                cur.append('\n').append(line.strip());
                curEnd = lineStart + raw.length();
                continue;
            }
            flush(out, curAuthor, curAt, cur, curStart, curEnd);
            curAuthor = null;
            curAt = null;
            cur = null;
            if (line.isBlank()) {
                continue;
            }
            Matcher a = AUTHOR_LINE.matcher(line.strip());
            if (a.matches()) {
                author = a.group(1).strip();
            }
            Matcher d = DATE_LINE.matcher(line.strip());
            if (d.matches()) {
                at = parseInstant(d.group(1), docAt);
                day = at == null ? day : at.atZone(ZoneOffset.UTC).toLocalDate();
            }
            out.add(new Message(chatLike ? null : author, at, line.strip(), lineStart + (raw.length() - raw.stripLeading().length()),
                    lineStart + raw.length()));
        }
        flush(out, curAuthor, curAt, cur, curStart, curEnd);
        return out;
    }

    private static void flush(List<Message> out, String author, Instant at, StringBuilder text, int start, int end) {
        if (text != null && author != null && !text.toString().isBlank()) {
            out.add(new Message(author, at, text.toString().strip(), start, end));
        }
    }

    static Instant parseInstant(String s, Instant fallback) {
        String v = s.strip().replace(' ', 'T');
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException e) {
            // next
        }
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException e) {
            // next
        }
        try {
            return LocalDate.parse(v.length() >= 10 ? v.substring(0, 10) : v).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
