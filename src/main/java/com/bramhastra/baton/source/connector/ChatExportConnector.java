package com.bramhastra.baton.source.connector;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
 * Slack export: either the full export .zip (channel/YYYY-MM-DD.json plus users.json) or a single
 * channel JSON array. Messages are grouped into one document per channel per day, one line per
 * message as "[HH:mm] Name: text", so each quote still shows who said it and when.
 */
@Component
public class ChatExportConnector implements SourceConnector {

    private static final Pattern MENTION = Pattern.compile("<@([A-Z0-9]+)>");
    private static final Pattern LINK = Pattern.compile("<(https?://[^|>]+)(?:\\|([^>]+))?>");
    private static final Pattern CHANNEL_REF = Pattern.compile("<#[A-Z0-9]+\\|([^>]+)>");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);
    private static final long MAX_UNZIPPED = 100L * 1024 * 1024;

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
            throw new IllegalArgumentException("A Slack export (.zip or .json) is required");
        }
        if (!file.extension().equals("zip") && !file.extension().equals("json")) {
            throw new UnsupportedOperationException("Chat exports must be a Slack .zip or .json");
        }
    }

    @Override
    public List<RawDocument> fetch(FetchRequest request, Consumer<String> status) throws Exception {
        StagedFile file = request.file();
        Map<String, String> users = new HashMap<>();
        // channel -> day -> messages
        Map<String, Map<String, List<JsonNode>>> byChannelDay = new TreeMap<>();

        if (file.extension().equals("json")) {
            String channel = stripExtension(file.filename());
            addMessages(byChannelDay, channel, json.readTree(file.bytes()));
        } else {
            long total = 0;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file.bytes()))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory() || !entry.getName().endsWith(".json")) {
                        continue;
                    }
                    byte[] bytes = zip.readAllBytes();
                    total += bytes.length;
                    if (total > MAX_UNZIPPED) {
                        throw new IllegalArgumentException("Export is too large when unzipped");
                    }
                    String name = entry.getName().replace('\\', '/');
                    if (name.contains("..")) {
                        continue;
                    }
                    String[] parts = name.split("/");
                    if (parts.length == 1 && parts[0].equals("users.json")) {
                        for (JsonNode u : json.readTree(bytes)) {
                            String real = text(u.path("profile"), "real_name");
                            users.put(text(u, "id"), real != null && !real.isBlank() ? real : text(u, "name"));
                        }
                    } else if (parts.length >= 2) {
                        addMessages(byChannelDay, parts[parts.length - 2], json.readTree(bytes));
                    }
                }
            }
        }

        List<RawDocument> docs = new ArrayList<>();
        for (var channel : byChannelDay.entrySet()) {
            for (var day : channel.getValue().entrySet()) {
                StringBuilder text = new StringBuilder();
                List<JsonNode> messages = day.getValue();
                messages.sort((a, b) -> ts(a).compareTo(ts(b)));
                for (JsonNode m : messages) {
                    String body = clean(text(m, "text"), users);
                    if (body == null || body.isBlank()) {
                        continue;
                    }
                    text.append('[').append(HM.format(ts(m))).append("] ")
                            .append(authorOf(m, users)).append(": ")
                            .append(body.replace("\n", "\n    ")).append('\n');
                }
                if (text.isEmpty()) {
                    continue;
                }
                Instant dayStart = LocalDate.parse(day.getKey()).atStartOfDay().toInstant(ZoneOffset.UTC);
                docs.add(new RawDocument(channel.getKey() + "/" + day.getKey(),
                        "#" + channel.getKey() + " · " + day.getKey(), null, dayStart, null,
                        "application/json", text.toString().strip()));
            }
        }
        status.accept("Read " + docs.size() + " channel-days");
        return docs;
    }

    private void addMessages(Map<String, Map<String, List<JsonNode>>> into, String channel, JsonNode array) {
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
            String day = ts(m).atZone(ZoneOffset.UTC).toLocalDate().toString();
            into.computeIfAbsent(channel, k -> new TreeMap<>())
                    .computeIfAbsent(day, k -> new ArrayList<>()).add(m);
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
