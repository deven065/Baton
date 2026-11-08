package com.bramhastra.baton.source.connector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.bramhastra.baton.source.SourceType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only GitHub REST connector: README, Markdown docs, issues and pull requests (with comments),
 * and recent commit messages. Config: {"repo": "owner/name", "branch": "optional"}.
 * The token, if any, comes from the server environment and should be a read-only token for one repo.
 */
@Component
public class GitHubConnector implements SourceConnector {

    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    static final int MAX_DOCS = 40;
    static final int MAX_ISSUES = 200;
    static final int MAX_COMMENTED_ISSUES = 60;

    private final RestClient http;
    private final ObjectMapper json;

    public GitHubConnector(ObjectMapper json,
                           @Value("${baton.github.api-url:https://api.github.com}") String apiUrl,
                           @Value("${baton.github.token:}") String token) {
        RestClient.Builder b = RestClient.builder().baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.USER_AGENT, "baton-handover");
        if (token != null && !token.isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        this.http = b.build();
        this.json = json;
    }

    @Override
    public boolean supports(SourceType type) {
        return type == SourceType.GITHUB;
    }

    @Override
    public void validate(Map<String, Object> config, StagedFile file) {
        Object repo = config.get("repo");
        if (repo == null || !REPO.matcher(repo.toString()).matches()) {
            throw new IllegalArgumentException("config.repo must look like owner/name");
        }
    }

    @Override
    public List<RawDocument> fetch(FetchRequest request, Consumer<String> status) {
        String repo = request.config().get("repo").toString();
        List<RawDocument> docs = new ArrayList<>();

        JsonNode info = get("/repos/" + repo);
        String branch = request.config().get("branch") != null
                ? request.config().get("branch").toString() : text(info, "default_branch");
        String repoUrl = text(info, "html_url");

        status.accept("Reading README and docs");
        try {
            String readme = raw("/repos/" + repo + "/readme?ref=" + branch);
            docs.add(new RawDocument("README.md", repo + " · README", null, null, repoUrl + "#readme",
                    "text/markdown", readme));
        } catch (HttpClientErrorException.NotFound e) {
            // no README
        }
        JsonNode tree = get("/repos/" + repo + "/git/trees/" + branch + "?recursive=1");
        int docCount = 0;
        for (JsonNode item : tree.path("tree")) {
            String path = text(item, "path");
            if (!"blob".equals(text(item, "type")) || path == null || !path.toLowerCase().endsWith(".md")
                    || path.equalsIgnoreCase("README.md") || path.contains("node_modules/")) {
                continue;
            }
            if (++docCount > MAX_DOCS) {
                break;
            }
            String content = raw("/repos/" + repo + "/contents/" + path + "?ref=" + branch);
            docs.add(new RawDocument(path, repo + " · " + path, null, null,
                    repoUrl + "/blob/" + branch + "/" + path, "text/markdown", content));
        }

        status.accept("Reading issues and pull requests");
        int commented = 0;
        int fetched = 0;
        for (int page = 1; fetched < MAX_ISSUES; page++) {
            JsonNode issues = get("/repos/" + repo + "/issues?state=all&sort=updated&per_page=100&page=" + page);
            if (!issues.isArray() || issues.isEmpty()) {
                break;
            }
            for (JsonNode issue : issues) {
                if (++fetched > MAX_ISSUES) {
                    break;
                }
                boolean pr = issue.has("pull_request");
                int number = issue.path("number").asInt();
                StringBuilder text = new StringBuilder();
                text.append(pr ? "Pull request" : "Issue").append(" #").append(number).append(": ")
                        .append(text(issue, "title")).append('\n');
                text.append("State: ").append(text(issue, "state")).append('\n');
                text.append("Author: ").append(text(issue.path("user"), "login")).append('\n');
                List<String> assignees = new ArrayList<>();
                issue.path("assignees").forEach(a -> assignees.add(text(a, "login")));
                if (!assignees.isEmpty()) {
                    text.append("Assignees: ").append(String.join(", ", assignees)).append('\n');
                }
                List<String> labels = new ArrayList<>();
                issue.path("labels").forEach(l -> labels.add(text(l, "name")));
                if (!labels.isEmpty()) {
                    text.append("Labels: ").append(String.join(", ", labels)).append('\n');
                }
                text.append("Created: ").append(text(issue, "created_at")).append('\n');
                if (text(issue, "closed_at") != null) {
                    text.append("Closed: ").append(text(issue, "closed_at")).append('\n');
                }
                String body = text(issue, "body");
                if (body != null && !body.isBlank()) {
                    text.append('\n').append(body.strip()).append('\n');
                }
                if (issue.path("comments").asInt() > 0 && commented < MAX_COMMENTED_ISSUES) {
                    commented++;
                    for (JsonNode c : get("/repos/" + repo + "/issues/" + number + "/comments?per_page=50")) {
                        text.append("\n— ").append(text(c.path("user"), "login")).append(" (")
                                .append(text(c, "created_at")).append("): ")
                                .append(String.valueOf(text(c, "body")).strip()).append('\n');
                    }
                }
                docs.add(new RawDocument((pr ? "pr/" : "issue/") + number,
                        repo + " · " + (pr ? "PR" : "Issue") + " #" + number + ": " + text(issue, "title"),
                        text(issue.path("user"), "login"), instant(text(issue, "created_at")),
                        text(issue, "html_url"), "text/markdown", text.toString().strip()));
            }
            if (issues.size() < 100) {
                break;
            }
        }

        status.accept("Reading recent commits");
        StringBuilder log = new StringBuilder();
        for (JsonNode c : get("/repos/" + repo + "/commits?sha=" + branch + "&per_page=100")) {
            JsonNode commit = c.path("commit");
            String author = c.path("author").isObject() ? text(c.path("author"), "login")
                    : text(commit.path("author"), "name");
            String sha = text(c, "sha");
            log.append(sha == null ? "" : sha.substring(0, 7)).append(' ')
                    .append(text(commit.path("author"), "date")).append(' ')
                    .append(author).append(": ")
                    .append(String.valueOf(text(commit, "message")).lines().findFirst().orElse("")).append('\n');
        }
        if (!log.isEmpty()) {
            docs.add(new RawDocument("commits/" + branch, repo + " · recent commits on " + branch, null,
                    Instant.now(), repoUrl + "/commits/" + branch, "text/plain", log.toString().strip()));
        }
        status.accept("Read " + docs.size() + " GitHub documents");
        return docs;
    }

    private JsonNode get(String uri) {
        String body = http.get().uri(uri).retrieve().body(String.class);
        return json.readTree(body == null ? "null" : body);
    }

    private String raw(String uri) {
        String body = http.get().uri(uri).header(HttpHeaders.ACCEPT, "application/vnd.github.raw+json")
                .retrieve().body(String.class);
        return body == null ? "" : body;
    }

    private static Instant instant(String iso) {
        return iso == null ? null : Instant.parse(iso);
    }

    private static String text(JsonNode node, String field) {
        return ChatExportConnector.text(node, field);
    }
}
