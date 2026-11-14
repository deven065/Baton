package com.bramhastra.baton.source.connector;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.bramhastra.baton.ai.ProviderUrl;
import com.bramhastra.baton.integration.IntegrationService;
import com.bramhastra.baton.source.SourceType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only Jira Cloud connector: the issues of one project (up to 500, most recently updated first), one document
 * per issue in the same "Field: value" shape as a task-board CSV export, so repository signals treat them the same
 * way (open issues owned by the person leaving, or by nobody). Config: {"project": "ANN"}. The workspace's own
 * connection is a JSON secret {"site": "https://acme.atlassian.net", "email": "...", "token": "..."} where the token
 * is an Atlassian API token; it can only read what that account can read.
 */
@Component
public class JiraConnector implements SourceConnector {

    static final int MAX_ISSUES = 500;
    private static final int PAGE = 100;
    private static final Pattern PROJECT = Pattern.compile("[A-Za-z][A-Za-z0-9_]{1,19}");

    private final RestClient http = RestClient.create();
    private final ObjectMapper json;
    private final IntegrationService integrations;
    private final boolean allowPrivate;

    public JiraConnector(ObjectMapper json, IntegrationService integrations,
                         @Value("${baton.tenants.allow-private-hosts:false}") boolean allowPrivate) {
        this.json = json;
        this.integrations = integrations;
        this.allowPrivate = allowPrivate;
    }

    /** The parsed connection. {@code site} has been checked to be a public https address. */
    public record Connection(String site, String email, String token) {
        String basic() {
            return "Basic " + Base64.getEncoder().encodeToString((email + ":" + token).getBytes(StandardCharsets.UTF_8));
        }
    }

    public Connection parse(String secret) {
        try {
            JsonNode n = json.readTree(secret);
            String site = ProviderUrl.check(n.path("site").asString("").strip(), allowPrivate);
            String email = n.path("email").asString("").strip();
            String token = n.path("token").asString("").strip();
            if (email.isEmpty() || token.isEmpty()) {
                throw new IllegalArgumentException("Jira needs the account email and an API token");
            }
            return new Connection(site, email, token);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The Jira connection is not valid JSON with site, email and token");
        }
    }

    @Override
    public boolean supports(SourceType type) {
        return type == SourceType.JIRA;
    }

    @Override
    public void validate(Map<String, Object> config, StagedFile file) {
        Object project = config.get("project");
        if (project == null || !PROJECT.matcher(project.toString().strip()).matches()) {
            throw new IllegalArgumentException("Enter the Jira project key, like ANN");
        }
        config.put("project", project.toString().strip().toUpperCase(java.util.Locale.ROOT));
        integrations.require(IntegrationService.Kind.JIRA);
    }

    /** Checks a connection: returns who Jira says we are. */
    public String test(String secret) {
        Connection c = parse(secret);
        try {
            JsonNode me = get(c, "/rest/api/3/myself");
            return me.path("displayName").asString(c.email());
        } catch (HttpClientErrorException e) {
            throw new IllegalArgumentException("Jira refused the email and API token (HTTP " + e.getStatusCode().value() + ")");
        }
    }

    @Override
    public List<RawDocument> fetch(FetchRequest request, Consumer<String> status) {
        Connection c = parse(integrations.require(IntegrationService.Kind.JIRA));
        String project = request.config().get("project").toString().toUpperCase(java.util.Locale.ROOT);
        status.accept("Reading Jira project " + project);
        List<RawDocument> docs = new ArrayList<>();
        String next = null;
        try {
            do {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("jql", "project = \"" + project + "\" ORDER BY updated DESC");
                body.put("maxResults", Math.min(PAGE, MAX_ISSUES - docs.size()));
                body.put("fields", List.of("summary", "status", "assignee", "reporter", "updated", "duedate", "priority",
                        "issuetype", "labels", "description"));
                if (next != null) {
                    body.put("nextPageToken", next);
                }
                JsonNode page = json.readTree(http.post().uri(URI.create(c.site() + "/rest/api/3/search/jql"))
                        .header(HttpHeaders.AUTHORIZATION, c.basic()).accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body))
                        .retrieve().body(String.class));
                for (JsonNode issue : page.path("issues")) {
                    docs.add(toDocument(c, issue));
                }
                status.accept("Read " + docs.size() + " Jira issues");
                next = page.path("isLast").asBoolean(true) ? null : page.path("nextPageToken").asString(null);
            } while (next != null && docs.size() < MAX_ISSUES);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            throw new IllegalArgumentException("Jira refused access. Check the API token and that the account can see project " + project);
        } catch (HttpClientErrorException e) {
            throw new IllegalArgumentException("Jira could not read project " + project + " (HTTP " + e.getStatusCode().value()
                    + "). Check the project key");
        }
        if (docs.isEmpty()) {
            throw new IllegalArgumentException("Jira returned no issues for project " + project + ". Check the key and the account's access");
        }
        return docs;
    }

    private RawDocument toDocument(Connection c, JsonNode issue) {
        String key = issue.path("key").asString();
        JsonNode f = issue.path("fields");
        StringBuilder text = new StringBuilder();
        line(text, "Issue key", key);
        line(text, "Summary", f.path("summary").asString(null));
        line(text, "Status", f.path("status").path("name").asString(null));
        line(text, "Type", f.path("issuetype").path("name").asString(null));
        line(text, "Priority", f.path("priority").path("name").asString(null));
        line(text, "Assignee", f.path("assignee").path("displayName").asString(null));
        line(text, "Reporter", f.path("reporter").path("displayName").asString(null));
        line(text, "Updated", f.path("updated").asString(null));
        line(text, "Due date", f.path("duedate").asString(null));
        List<String> labels = new ArrayList<>();
        f.path("labels").forEach(l -> labels.add(l.asString()));
        line(text, "Labels", String.join(", ", labels));
        String description = adf(f.path("description"));
        if (!description.isBlank()) {
            text.append("Description: ").append(description.strip().replaceAll("\\s*\\n\\s*", " ")).append('\n');
        }
        Instant updated = null;
        try {
            updated = java.time.OffsetDateTime.parse(f.path("updated").asString("").replaceFirst("([+-]\\d{2})(\\d{2})$", "$1:$2")).toInstant();
        } catch (RuntimeException ignored) {
            // no usable date
        }
        String summary = f.path("summary").asString("Issue");
        return new RawDocument(key, key + ": " + (summary.length() > 250 ? summary.substring(0, 249) + "…" : summary),
                f.path("reporter").path("displayName").asString(null), updated, c.site() + "/browse/" + key,
                "text/plain", text.toString().strip());
    }

    private static void line(StringBuilder sb, String name, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(name).append(": ").append(value.strip()).append('\n');
        }
    }

    /** Jira's rich-text description (Atlassian Document Format) as plain text. */
    static String adf(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isString()) {
            return node.asString();
        }
        StringBuilder sb = new StringBuilder();
        if ("text".equals(node.path("type").asString())) {
            sb.append(node.path("text").asString(""));
        }
        for (JsonNode child : node.path("content")) {
            sb.append(adf(child));
        }
        String type = node.path("type").asString("");
        if (type.equals("paragraph") || type.equals("heading") || type.equals("listItem") || type.equals("hardBreak")) {
            sb.append('\n');
        }
        return sb.toString();
    }

    private JsonNode get(Connection c, String path) {
        return json.readTree(http.get().uri(URI.create(c.site() + path))
                .header(HttpHeaders.AUTHORIZATION, c.basic()).accept(MediaType.APPLICATION_JSON)
                .retrieve().body(String.class));
    }
}
