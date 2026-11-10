package com.bramhastra.baton.source.connector;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Component;

import com.bramhastra.baton.source.SourceType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Chat exports from Slack, WhatsApp and Discord, recognised by their contents:
 * <ul>
 *   <li>Slack: the full export .zip (channel/YYYY-MM-DD.json plus users.json) or one channel's JSON array;</li>
 *   <li>WhatsApp: "Export chat" as .txt, or the .zip WhatsApp makes when media is included;</li>
 *   <li>Discord: a DiscordChatExporter JSON or CSV file, or a .zip of them.</li>
 * </ul>
 * Messages are grouped into one document per channel per day, one line per message as "[HH:mm] Name: text",
 * so each quote still shows who said it and when.
 */
@Component
public class ChatExportConnector implements SourceConnector {

    private static final Pattern MENTION = Pattern.compile("<@([A-Z0-9]+)>");
    private static final Pattern LINK = Pattern.compile("<(https?://[^|>]+)(?:\\|([^>]+))?>");
    private static final Pattern CHANNEL_REF = Pattern.compile("<#[A-Z0-9]+\\|([^>]+)>");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final long MAX_UNZIPPED = 100L * 1024 * 1024;
    private static final List<String> EXTENSIONS = List.of("zip", "json", "txt", "csv");
    private static final List<String> SLACK_META = List.of("users.json", "channels.json", "groups.json", "dms.json",
            "mpims.json", "integration_logs.json", "canvases.json");

    private final ObjectMapper json;

    public ChatExportConnector(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public boolean supports(SourceType type) {
        return type == SourceType.CHAT_EXPORT;
    }

    @Override
    public void validate(Map<String, Object> config, StagedFile file) {
        if (file == null) {
            throw new IllegalArgumentException("A chat export file is required");
        }
        if (!EXTENSIONS.contains(file.extension())) {
            throw new UnsupportedOperationException(
                    "Chat exports must be a Slack export (.zip or .json), a WhatsApp export (.txt or .zip) "
                            + "or a Discord export (.json or .csv)");
        }
    }

    @Override
    public List<RawDocument> fetch(FetchRequest request, Consumer<String> status) throws Exception {
        StagedFile file = request.file();
        List<ChatMessage> messages = new ArrayList<>();
        String kind = switch (file.extension()) {
            case "json" -> readJson(file.filename(), file.bytes(), messages);
            case "txt" -> readText(file.filename(), file.bytes(), messages);
            case "csv" -> readCsv(file.filename(), file.bytes(), messages);
            default -> readZip(file, messages);
        };
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("No messages found in that " + kind + " export");
        }
        boolean whatsApp = kind.equals("WhatsApp");
        List<RawDocument> docs = toDocuments(messages, whatsApp ? "" : "#", whatsApp ? "text/plain" : "application/json");
        status.accept("Read " + docs.size() + " channel-days from " + kind);
        return docs;
    }

    private String readJson(String filename, byte[] bytes, List<ChatMessage> into) {
        JsonNode root = json.readTree(bytes);
        if (DiscordExport.looksLike(root)) {
            into.addAll(DiscordExport.parse(root, DiscordExport.channelFrom(filename)));
            return "Discord";
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("That JSON is not a Slack or Discord export");
        }
        addSlack(into, stripExtension(filename), root, Map.of());
        return "Slack";
    }

    private static String readText(String filename, byte[] bytes, List<ChatMessage> into) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!WhatsAppExport.looksLike(text)) {
            throw new IllegalArgumentException("That text file is not a WhatsApp chat export");
        }
        into.addAll(WhatsAppExport.parse(text, WhatsAppExport.channelFrom(filename)));
        return "WhatsApp";
    }

    private static String readCsv(String filename, byte[] bytes, List<ChatMessage> into) throws java.io.IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!DiscordExport.looksLikeCsv(text)) {
            throw new IllegalArgumentException("That CSV is not a Discord export (expected AuthorID,Author,Date,Content columns)");
        }
        into.addAll(DiscordExport.parseCsv(text, DiscordExport.channelFrom(filename)));
        return "Discord";
    }

    private String readZip(StagedFile file, List<ChatMessage> into) throws Exception {
        Map<String, byte[]> entries = new TreeMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file.bytes()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || name.contains("..") || !(name.endsWith(".json") || name.endsWith(".txt"))) {
                    continue; // media in WhatsApp zips, attachments
                }
                byte[] bytes = zip.readAllBytes();
                total += bytes.length;
                if (total > MAX_UNZIPPED) {
                    throw new IllegalArgumentException("Export is too large when unzipped");
                }
                entries.put(name, bytes);
            }
        }

        if (entries.keySet().stream().noneMatch(n -> n.endsWith(".json"))) {
            for (var e : entries.entrySet()) {
                String text = new String(e.getValue(), StandardCharsets.UTF_8);
                if (WhatsAppExport.looksLike(text)) {
                    String base = e.getKey().substring(e.getKey().lastIndexOf('/') + 1);
                    String channel = WhatsAppExport.channelFrom(base.equals("_chat.txt") ? file.filename() : base);
                    into.addAll(WhatsAppExport.parse(text, channel));
                }
            }
            if (into.isEmpty()) {
                throw new IllegalArgumentException("That .zip has no Slack, WhatsApp or Discord messages");
            }
            return "WhatsApp";
        }

        Map<String, String> users = new HashMap<>();
        byte[] usersJson = entries.get("users.json");
        if (usersJson != null) {
            for (JsonNode u : json.readTree(usersJson)) {
                String real = text(u.path("profile"), "real_name");
                users.put(text(u, "id"), real != null && !real.isBlank() ? real : text(u, "name"));
            }
        }
        String kind = "Slack";
        for (var e : entries.entrySet()) {
            String[] parts = e.getKey().split("/");
            if (!e.getKey().endsWith(".json") || (parts.length == 1 && SLACK_META.contains(parts[0]))) {
                continue;
            }
            JsonNode root = json.readTree(e.getValue());
            if (DiscordExport.looksLike(root)) {
                into.addAll(DiscordExport.parse(root, DiscordExport.channelFrom(parts[parts.length - 1])));
                kind = "Discord";
            } else if (parts.length >= 2) {
                addSlack(into, parts[parts.length - 2], root, users);
            }
        }
        return kind;
    }

    /** One document per channel per day; refs stay "channel/day" as Slack sources always had. */
    private static List<RawDocument> toDocuments(List<ChatMessage> messages, String prefix, String mime) {
        Map<String, Map<LocalDate, List<ChatMessage>>> byChannelDay = new TreeMap<>();
        for (ChatMessage m : messages) {
            byChannelDay.computeIfAbsent(m.channel(), k -> new TreeMap<>())
                    .computeIfAbsent(m.at().toLocalDate(), k -> new ArrayList<>()).add(m);
        }
        List<RawDocument> docs = new ArrayList<>();
        for (var channel : byChannelDay.entrySet()) {
            for (var day : channel.getValue().entrySet()) {
                List<ChatMessage> dayMessages = day.getValue();
                dayMessages.sort(Comparator.comparing(ChatMessage::at));
                StringBuilder text = new StringBuilder();
                for (ChatMessage m : dayMessages) {
                    text.append('[').append(HM.format(m.at())).append("] ").append(m.author()).append(": ")
                            .append(m.text().replace("\n", "\n    ")).append('\n');
                }
                Instant dayStart = day.getKey().atStartOfDay().toInstant(ZoneOffset.UTC);
                docs.add(new RawDocument(channel.getKey() + "/" + day.getKey(),
                        prefix + channel.getKey() + " · " + day.getKey(), null, dayStart, null, mime,
                        text.toString().strip()));
            }
        }
        return docs;
    }

    private static void addSlack(List<ChatMessage> into, String channel, JsonNode array, Map<String, String> users) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode m : array) {
            if (m.has("subtype") && !"thread_broadcast".equals(text(m, "subtype"))) {
                continue; // joins, leaves, bot noise
            }
            if (text(m, "ts") == null) {
                continue;
            }
            String body = clean(text(m, "text"), users);
            if (body == null || body.isBlank()) {
                continue;
            }
            into.add(new ChatMessage(channel, LocalDateTime.ofInstant(ts(m), ZoneOffset.UTC), authorOf(m, users), body));
        }
    }

    private static Instant ts(JsonNode m) {
        BigDecimal seconds = new BigDecimal(text(m, "ts"));
        return Instant.ofEpochMilli(seconds.movePointRight(3).longValue());
    }

    private static String authorOf(JsonNode m, Map<String, String> users) {
        String real = text(m.path("user_profile"), "real_name");
        if (real != null && !real.isBlank()) {
            return real;
        }
        String id = text(m, "user");
        if (id != null && users.containsKey(id)) {
            return users.get(id);
        }
        String name = text(m, "username");
        return name != null ? name : (id != null ? id : "unknown");
    }

    static String clean(String body, Map<String, String> users) {
        if (body == null) {
            return null;
        }
        Matcher m = MENTION.matcher(body);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("@" + users.getOrDefault(m.group(1), m.group(1))));
        }
        m.appendTail(sb);
        String s = CHANNEL_REF.matcher(sb.toString()).replaceAll("#$1");
        Matcher l = LINK.matcher(s);
        sb = new StringBuilder();
        while (l.find()) {
            String label = l.group(2);
            l.appendReplacement(sb, Matcher.quoteReplacement(label != null ? label + " (" + l.group(1) + ")" : l.group(1)));
        }
        l.appendTail(sb);
        return sb.toString().replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
    }

    static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        return v.isValueNode() ? v.asString() : v.toString();
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot < 0 ? filename : filename.substring(0, dot)).replaceFirst("^#", "");
    }
}
