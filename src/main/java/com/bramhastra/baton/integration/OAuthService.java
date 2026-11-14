package com.bramhastra.baton.integration;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.bramhastra.baton.integration.IntegrationService.Kind;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * "Connect with Slack / Google / GitHub": the standard OAuth authorization-code flow instead of pasted keys.
 * Baton's operator registers one app per tool and sets its client id and secret in the server environment; a
 * company admin then clicks Connect, approves on the tool's own page, and Baton stores the resulting token encrypted
 * in that company's database exactly like a pasted one. Without the operator's app credentials the buttons are
 * hidden and pasting a key still works.
 *
 * <ul>
 *   <li>Slack: a bot token (xoxb-) with read-only history scopes, the same token a pasted Slack app would give.</li>
 *   <li>GitHub: a user token for the chosen account. Register a GitHub App (read-only Contents, Issues, Pull
 *       requests) with user-token expiry off to keep it read-only; an OAuth App with the {@code repo} scope also works
 *       but can write.</li>
 *   <li>Google: the user's refresh token with the read-only Drive scope; access tokens are minted on demand.</li>
 * </ul>
 */
@Service
public class OAuthService {

    public record Provider(String name, String authorizeUrl, String tokenUrl, String clientId, String clientSecret,
                           String scope) {
        boolean configured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }

    public record Connected(String secret, String hint) {
    }

    private final Map<Kind, Provider> providers = new LinkedHashMap<>();
    private final String publicUrl;
    private final String githubApi;
    private final String googleUserinfo;
    private final RestClient http = RestClient.create();
    private final ObjectMapper json;

    public OAuthService(ObjectMapper json,
                        @Value("${baton.public-url:}") String publicUrl,
                        @Value("${baton.oauth.slack.client-id:}") String slackId,
                        @Value("${baton.oauth.slack.client-secret:}") String slackSecret,
                        @Value("${baton.oauth.slack.authorize-url:https://slack.com/oauth/v2/authorize}") String slackAuth,
                        @Value("${baton.oauth.slack.token-url:https://slack.com/api/oauth.v2.access}") String slackToken,
                        @Value("${baton.oauth.github.client-id:}") String githubId,
                        @Value("${baton.oauth.github.client-secret:}") String githubSecret,
                        @Value("${baton.oauth.github.authorize-url:https://github.com/login/oauth/authorize}") String githubAuth,
                        @Value("${baton.oauth.github.token-url:https://github.com/login/oauth/access_token}") String githubToken,
                        @Value("${baton.oauth.github.scope:repo}") String githubScope,
                        @Value("${baton.github.api-url:https://api.github.com}") String githubApi,
                        @Value("${baton.oauth.google.client-id:}") String googleId,
                        @Value("${baton.oauth.google.client-secret:}") String googleSecret,
                        @Value("${baton.oauth.google.authorize-url:https://accounts.google.com/o/oauth2/v2/auth}") String googleAuth,
                        @Value("${baton.google.token-url:https://oauth2.googleapis.com/token}") String googleToken,
                        @Value("${baton.oauth.google.userinfo-url:https://openidconnect.googleapis.com/v1/userinfo}") String googleUserinfo) {
        this.json = json;
        this.publicUrl = publicUrl == null ? "" : publicUrl.strip().replaceAll("/+$", "");
        this.githubApi = githubApi.replaceAll("/+$", "");
        this.googleUserinfo = googleUserinfo;
        providers.put(Kind.SLACK, new Provider("Slack", slackAuth, slackToken, slackId, slackSecret,
                "channels:history,channels:read,groups:history,groups:read,users:read"));
        providers.put(Kind.GITHUB, new Provider("GitHub", githubAuth, githubToken, githubId, githubSecret, githubScope));
        providers.put(Kind.GOOGLE_DRIVE, new Provider("Google", googleAuth, googleToken, googleId, googleSecret,
                "https://www.googleapis.com/auth/drive.readonly email"));
    }

    /** Which tools can be connected with one click on this server. Jira has no such flow (API token only). */
    public Map<String, Boolean> available() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Kind k : Kind.values()) {
            out.put(k.name(), isAvailable(k));
        }
        return out;
    }

    public boolean isAvailable(Kind kind) {
        Provider p = providers.get(kind);
        return p != null && p.configured() && !publicUrl.isEmpty();
    }

    public String redirectUri(Kind kind) {
        return publicUrl + "/api/oauth/" + kind.name().toLowerCase(java.util.Locale.ROOT) + "/callback";
    }

    /** The tool's own authorization page, with Baton's signed state. */
    public String authorizeUrl(Kind kind, String state) {
        if (!isAvailable(kind)) {
            throw new IllegalArgumentException("One-click connection is not set up on this server. Paste a key instead.");
        }
        Provider p = providers.get(kind);
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", p.clientId());
        q.put("redirect_uri", redirectUri(kind));
        q.put("state", state);
        if (kind == Kind.GOOGLE_DRIVE) {
            q.put("response_type", "code");
            q.put("access_type", "offline"); // a refresh token, so syncs keep working without the admin present
            q.put("prompt", "consent");
        }
        q.put("scope", p.scope());
        StringBuilder sb = new StringBuilder(p.authorizeUrl()).append(p.authorizeUrl().contains("?") ? '&' : '?');
        q.forEach((k, v) -> sb.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'));
        return sb.substring(0, sb.length() - 1);
    }

    /** Trades the one-time code for the token Baton will keep. */
    public Connected complete(Kind kind, String code) {
        Provider p = providers.get(kind);
        if (p == null || !isAvailable(kind)) {
            throw new IllegalArgumentException("One-click connection is not set up on this server");
        }
        JsonNode token = tokenRequest(p, kind, code);
        return switch (kind) {
            case SLACK -> {
                if (!token.path("ok").asBoolean(false) || token.path("access_token").asString("").isBlank()) {
                    throw new IllegalArgumentException("Slack did not approve the connection"
                            + (token.path("error").asString("").isBlank() ? "" : " (" + token.path("error").asString() + ")"));
                }
                String team = token.path("team").path("name").asString("Slack");
                yield new Connected(token.path("access_token").asString(), "Slack workspace " + team + " (one click)");
            }
            case GITHUB -> {
                String access = token.path("access_token").asString("");
                if (access.isBlank()) {
                    throw new IllegalArgumentException("GitHub did not approve the connection"
                            + (token.path("error_description").asString("").isBlank() ? "" : ": " + token.path("error_description").asString()));
                }
                String login = "";
                try {
                    login = json.readTree(http.get().uri(URI.create(githubApi + "/user"))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + access).header(HttpHeaders.USER_AGENT, "baton-handover")
                            .retrieve().body(String.class)).path("login").asString("");
                } catch (RuntimeException ignored) {
                    // the hint is cosmetic
                }
                yield new Connected(access, login.isBlank() ? "GitHub account (one click)" : "GitHub account " + login + " (one click)");
            }
            case GOOGLE_DRIVE -> {
                String refresh = token.path("refresh_token").asString("");
                if (refresh.isBlank()) {
                    throw new IllegalArgumentException("Google did not give long-lived access. Remove Baton at "
                            + "myaccount.google.com/permissions and connect again");
                }
                String email = "";
                try {
                    email = json.readTree(http.get().uri(URI.create(googleUserinfo))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.path("access_token").asString())
                            .retrieve().body(String.class)).path("email").asString("");
                } catch (RuntimeException ignored) {
                    // the hint is cosmetic
                }
                yield new Connected(json.writeValueAsString(Map.of("type", "oauth", "refresh_token", refresh)),
                        email.isBlank() ? "Google account (one click)" : "Google account " + email + " (one click)");
            }
            default -> throw new IllegalArgumentException("This tool cannot be connected with one click");
        };
    }

    /** Google: a fresh access token for the stored refresh token. */
    public String refreshGoogle(String refreshToken) {
        Provider p = providers.get(Kind.GOOGLE_DRIVE);
        if (p == null || !p.configured()) {
            throw new IllegalArgumentException("Google one-click connection is not set up on this server. Reconnect Google Drive with a service account");
        }
        String body = "grant_type=refresh_token&refresh_token=" + enc(refreshToken) + "&client_id=" + enc(p.clientId())
                + "&client_secret=" + enc(p.clientSecret());
        try {
            JsonNode n = json.readTree(http.post().uri(URI.create(p.tokenUrl())).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body).retrieve().body(String.class));
            String access = n.path("access_token").asString("");
            if (access.isEmpty()) {
                throw new IllegalArgumentException("Google did not renew access. Reconnect Google Drive");
            }
            return access;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            throw new IllegalArgumentException("Google refused to renew access (it was probably revoked). Reconnect Google Drive");
        }
    }

    private JsonNode tokenRequest(Provider p, Kind kind, String code) {
        String body = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(redirectUri(kind))
                + "&client_id=" + enc(p.clientId()) + "&client_secret=" + enc(p.clientSecret());
        try {
            return json.readTree(http.post().uri(URI.create(p.tokenUrl())).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new IllegalArgumentException(p.name() + " refused the connection (HTTP " + e.getStatusCode().value() + ")");
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new IllegalArgumentException("Could not reach " + p.name() + " to finish the connection");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
