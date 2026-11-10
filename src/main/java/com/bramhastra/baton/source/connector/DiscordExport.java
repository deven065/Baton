package com.bramhastra.baton.source.connector;

import java.io.StringReader;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import tools.jackson.databind.JsonNode;

/**
 * Discord channel exports made with DiscordChatExporter, the usual way to export Discord: its JSON
 * ({@code {"channel":{…},"messages":[…]}}) or CSV ({@code AuthorID,Author,Date,Content,…}) format.
 */
final class DiscordExport {

    private static final Set<String> KEEP = Set.of("Default", "Reply", "ThreadStarterMessage");
    private static final Pattern MENTION = Pattern.compile("<@!?(\\d+)>");
    private static final Pattern CUSTOM_EMOJI = Pattern.compile("<a?(:\\w+:)\\d+>");

    private DiscordExport() {
    }

    static boolean looksLike(JsonNode root) {
        return root != null && root.isObject() && root.path("messages").isArray();
    }

    static List<ChatMessage> parse(JsonNode root, String fallbackChannel) {
        String channel = ChatExportConnector.text(root.path("channel"), "name");
        if (channel == null || channel.isBlank()) {
            channel = fallbackChannel;
        }
        List<ChatMessage> out = new ArrayList<>();
        for (JsonNode m : root.path("messages")) {
            String type = ChatExportConnector.text(m, "type");
            if (type != null && !KEEP.contains(type)) {
                continue; // joins, pins, calls
            }
            String content = ChatExportConnector.text(m, "content");
            String stamp = ChatExportConnector.text(m, "timestamp");
            if (content == null || content.isBlank() || stamp == null) {
                continue;
            }
            Map<String, String> names = new HashMap<>();
            for (JsonNode u : m.path("mentions")) {
                names.put(ChatExportConnector.text(u, "id"), nameOf(u));
            }
            out.add(new ChatMessage(channel, OffsetDateTime.parse(stamp).toLocalDateTime(), nameOf(m.path("author")),
                    clean(content, names)));
        }
        return out;
    }

    static boolean looksLikeCsv(String text) {
        String first = text.lines().findFirst().orElse("").replace("﻿", "");
        return first.startsWith("AuthorID,Author,Date,Content") || first.startsWith("\"AuthorID\",\"Author\",\"Date\",\"Content\"");
    }

    static List<ChatMessage> parseCsv(String text, String channel) throws java.io.IOException {
        List<ChatMessage> out = new ArrayList<>();
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
        for (CSVRecord r : format.parse(new StringReader(text.replace("﻿", "")))) {
            String content = r.get("Content");
            if (content == null || content.isBlank()) {
                continue;
            }
            out.add(new ChatMessage(channel, OffsetDateTime.parse(r.get("Date").strip()).toLocalDateTime(),
                    r.get("Author").replaceFirst("#0+$", ""), clean(content, Map.of())));
        }
        return out;
    }

    /** "Annapurna Club - Text Channels - design [1234567890].csv" → "design". */
    static String channelFrom(String filename) {
        String base = filename.replaceAll("(?i)\\.(csv|json)$", "").replaceAll(" \\[\\d+]$", "");
        int dash = base.lastIndexOf(" - ");
        return (dash >= 0 ? base.substring(dash + 3) : base).replaceFirst("^#", "").strip();
    }

    private static String nameOf(JsonNode user) {
        String nick = ChatExportConnector.text(user, "nickname");
        if (nick != null && !nick.isBlank()) {
            return nick;
        }
        String name = ChatExportConnector.text(user, "name");
        return name != null ? name : "unknown";
    }

    private static String clean(String content, Map<String, String> names) {
        Matcher m = MENTION.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("@" + names.getOrDefault(m.group(1), m.group(1))));
        }
        m.appendTail(sb);
        return CUSTOM_EMOJI.matcher(sb.toString()).replaceAll("$1").strip();
    }
}
