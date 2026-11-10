package com.bramhastra.baton.source.connector;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.bramhastra.baton.integration.IntegrationService;
import com.bramhastra.baton.source.SourceType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only Slack connector: the last 90 days of one channel (up to 1,000 messages, with thread replies),
 * one document per day. Config: {"channel": "#dev" or a channel id}. Uses the workspace's own bot token;
 * the bot must be a member of the channel. Scopes: channels:history, channels:read, groups:history,
 * groups:read, users:read.
 */
@Component
public class SlackConnector implements SourceConnector {

    static final int MAX_MESSAGES = 1000;
    static final int MAX_THREADS = 30;
    private static final Pattern CHANNEL = Pattern.compile("#?[a-z0-9][a-z0-9._-]{0,79}|[CG][A-Z0-9]{8,}");
    private static final Pattern MENTION = Pattern.compile("<@([A-Z0-9]+)>");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

    private final RestClient http;
    private final ObjectMapper json;
    private final IntegrationService integrations;

    public SlackConnector(ObjectMapper json, IntegrationService integrations,
                          @Value("${baton.slack.api-url:https://slack.com/api}") String apiUrl) {
        this.http = RestClient.builder().baseUrl(apiUrl).defaultHeader(HttpHeaders.USER_AGENT, "baton-handover").build();
        this.json = json;
        this.integrations = integrations;
    }

    @Override
    public boolean supports(SourceType type) {
        return type == SourceType.SLACK;
    }

    @Override
    public void validate(Map<String, Object> config, StagedFile file) {
        Object channel = config.get("channel");
        if (channel == null || !CHANNEL.matcher(channel.toString()).matches()) {
            throw new IllegalArgumentException("Enter a channel name like #dev, or a channel id");
        }
        integrations.require(IntegrationService.Kind.SLACK);
    }

    /** Checks a token: returns the Slack workspace name. */
    public String test(String token) {
        return call(token, "/auth.test").path("team").asString("Slack");
    }

    @Override
    public List<RawDocument> fetch(FetchRequest request, Consumer<String> status) {
        String token = integrations.require(IntegrationService.Kind.SLACK);
        String wanted = request.config().get("channel").toString().replaceFirst("^#", "");

        status.accept("Finding the channel");
        String channelId = null;
        String channelName = wanted;
        if (wanted.matches("[CG][A-Z0-9]{8,}")) {
            channelId = wanted;
            channelName = call(token, "/conversations.info?channel=" + wanted).path("channel").path("name").asString(wanted);
        } else {
            String cursor = "";
            for (int page = 0; page < 10 && channelId == null; page++) {
                JsonNode list = call(token, "/conversations.list?types=public_channel,private_channel&exclude_archived=true&limit=200"
                        + (cursor.isEmpty() ? "" : "&cursor=" + cursor));
                for (JsonNode c : list.path("channels")) {
                    if (wanted.equalsIgnoreCase(c.path("name").asString(""))) {
                        channelId = c.path("id").asString();
                    }
                }
                cursor = list.path("response_metadata").path("next_cursor").asString("");
                if (cursor.isEmpty()) {
                    break;
                }
            }
            if (channelId == null) {
                throw new IllegalArgumentException("No channel named #" + wanted + " that the Slack app can see");
            }
        }

        Map<String, String> names = new HashMap<>();
        for (JsonNode m : call(token, "/users.list?limit=500").path("members")) {
            String display = m.path("profile").path("display_name").asString("");
            String real = m.path("real_name").asString("");
            names.put(m.path("id").asString(), !real.isBlank() ? real : !display.isBlank() ? display : m.path("name").asString("someone"));
        }

        status.accept("Reading #" + channelName);
        String oldest = String.valueOf(Instant.now().minus(Duration.ofDays(90)).getEpochSecond());
        List<JsonNode> messages = new ArrayList<>();
        String cursor = "";
        while (messages.size() < MAX_MESSAGES) {
            JsonNode page = call(token, "/conversations.history?channel=" + channelId + "&limit=200&oldest=" + oldest
                    + (cursor.isEmpty() ? "" : "&cursor=" + cursor));
            page.path("messages").forEach(messages::add);
            cursor = page.path("response_metadata").path("next_cursor").asString("");
            if (cursor.isEmpty() || !page.path("has_more").asBoolean(false)) {
                break;
            }
        }

        record Line(Instant at, String author, String text) {
        }
        Map<LocalDate, List<Line>> byDay = new TreeMap<>();
        int threads = 0;
        for (JsonNode m : messages) {
            if (m.has("subtype") && !"thread_broadcast".equals(m.path("subtype").asString())) {
                continue; // joins, leaves, bot notices
            }
            List<JsonNode> thread = new ArrayList<>(List.of(m));
            if (m.path("reply_count").asInt() > 0 && threads < MAX_THREADS) {
                threads++;
                thread.clear();
                call(token, "/conversations.replies?channel=" + channelId + "&ts=" + m.path("ts").asString() + "&limit=100")
                        .path("messages").forEach(thread::add);
            }
            for (int i = 0; i < thread.size(); i++) {
                JsonNode t = thread.get(i);
                String text = t.path("text").asString("").strip();
                if (text.isEmpty()) {
                    continue;
                }
                Matcher mm = MENTION.matcher(text);
                text = mm.replaceAll(r -> Matcher.quoteReplacement("@" + names.getOrDefault(r.group(1), "someone")));
                Instant at = Instant.ofEpochSecond((long) Double.parseDouble(t.path("ts").asString("0")));
                String author = names.getOrDefault(t.path("user").asString(""), t.path("username").asString("someone"));
                byDay.computeIfAbsent(at.atZone(ZoneOffset.UTC).toLocalDate(), d -> new ArrayList<>())
                        .add(new Line(at, author, (i > 0 ? "↳ " : "") + text));
            }
        }

        List<RawDocument> docs = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Line>> day : byDay.entrySet()) {
            List<Line> lines = day.getValue();
            lines.sort(Comparator.comparing(Line::at));
            StringBuilder text = new StringBuilder();
            Map<String, Integer> posts = new HashMap<>();
            for (Line l : lines) {
                text.append('[').append(TIME.format(l.at())).append("] ").append(l.author()).append(": ").append(l.text()).append('\n');
                posts.merge(l.author(), 1, Integer::sum);
            }
            String top = posts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            docs.add(new RawDocument("slack/" + channelId + "/" + day.getKey(), "#" + channelName + " · " + day.getKey(), top,
                    day.getKey().atStartOfDay().toInstant(ZoneOffset.UTC), null, "text/plain", text.toString().strip()));
        }
        status.accept("Read " + messages.size() + " Slack messages over " + docs.size() + " days");
        return docs;
    }

    private JsonNode call(String token, String uri) {
        String body = http.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(String.class);
        JsonNode root = json.readTree(body == null ? "{}" : body);
        if (!root.path("ok").asBoolean(false)) {
            String error = root.path("error").asString("unknown_error");
            throw new IllegalArgumentException(switch (error) {
                case "not_in_channel" -> "Add the Slack app to the channel first: type /invite @your-app in it";
                case "invalid_auth", "token_revoked", "account_inactive", "not_authed" ->
                        "Slack refused the token. Reconnect Slack under Organisation → Connections";
                case "missing_scope" -> "The Slack app is missing a permission (needs channels:history, channels:read, "
                        + "groups:history, groups:read, users:read)";
                case "channel_not_found" -> "Slack cannot find that channel";
                case "ratelimited" -> "Slack is rate-limiting this app; try again in a minute";
                default -> "Slack error: " + error;
            });
        }
        return root;
    }
}
