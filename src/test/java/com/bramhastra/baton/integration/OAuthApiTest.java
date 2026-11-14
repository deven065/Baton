package com.bramhastra.baton.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.bramhastra.baton.DemoFixture;
import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.bramhastra.baton.tenant.TenantRegistry;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * "Connect with Slack / GitHub / Google" end to end against fake provider servers: the admin starts, the provider
 * sends the browser back with a code, Baton trades it for a token and stores it encrypted in the workspace, and the
 * stored connection works for syncing. Also the ways it must refuse: a forged, tampered, expired or other-tool state,
 * a provider that says no, someone who is not an admin, and a server without the operator's app credentials.
 */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true",
        "baton.public-url=https://api.baton.test", "baton.app-url=https://app.baton.test",
        "baton.oauth.slack.client-id=slack-client", "baton.oauth.slack.client-secret=slack-secret",
        "baton.oauth.github.client-id=gh-client", "baton.oauth.github.client-secret=gh-secret",
        "baton.oauth.google.client-id=g-client", "baton.oauth.google.client-secret=g-secret"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
class OAuthApiTest {

    static final HttpServer FAKE;
    static final List<String> SEEN = new CopyOnWriteArrayList<>();

    static {
        try {
            FAKE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        FAKE.createContext("/", OAuthApiTest::serve);
        FAKE.start();
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                m.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private static void serve(HttpExchange ex) throws java.io.IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String auth = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
        SEEN.add(path + " " + body + " " + auth);
        Map<String, String> f = form(body);
        String out = "{}";
        int code = 200;
        switch (path) {
            case "/slack/token" -> out = f.get("code").equals("good-code")
                    && f.get("client_secret").equals("slack-secret") && f.get("redirect_uri").equals("https://api.baton.test/api/oauth/slack/callback")
                    ? "{\"ok\":true,\"access_token\":\"xoxb-oauth-token-5678\",\"team\":{\"name\":\"Northfield Club\"}}"
                    : "{\"ok\":false,\"error\":\"invalid_code\"}";
            case "/slack/auth.test" -> out = auth.equals("Bearer xoxb-oauth-token-5678")
                    ? "{\"ok\":true,\"team\":\"Northfield Club\"}" : "{\"ok\":false,\"error\":\"invalid_auth\"}";
            case "/github/token" -> out = f.get("code").equals("good-code") && f.get("client_secret").equals("gh-secret")
                    ? "{\"access_token\":\"ghu_oauthtoken1234567890\",\"token_type\":\"bearer\"}"
                    : "{\"error\":\"bad_verification_code\",\"error_description\":\"The code passed is incorrect or expired.\"}";
            case "/github/user" -> out = auth.equals("Bearer ghu_oauthtoken1234567890") ? "{\"login\":\"northfield-club\"}" : "{\"message\":\"Bad credentials\"}";
            case "/google/token" -> {
                if (f.get("grant_type").equals("authorization_code")) {
                    out = f.get("code").equals("good-code") && f.get("client_secret").equals("g-secret")
                            ? "{\"access_token\":\"ya29.first\",\"refresh_token\":\"1//refresh-abc\",\"expires_in\":3600}"
                            : "{\"error\":\"invalid_grant\"}";
                } else if (f.get("grant_type").equals("refresh_token")) {
                    out = f.get("refresh_token").equals("1//refresh-abc") ? "{\"access_token\":\"ya29.renewed\",\"expires_in\":3600}" : "{\"error\":\"invalid_grant\"}";
                    code = f.get("refresh_token").equals("1//refresh-abc") ? 200 : 400;
                }
            }
            case "/google/userinfo" -> out = "{\"email\":\"club.drive@gmail.example\"}";
            default -> code = 404;
        }
        byte[] b = out.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + FAKE.getAddress().getPort();
        r.add("baton.oauth.slack.token-url", () -> base + "/slack/token");
        r.add("baton.slack.api-url", () -> base + "/slack");
        r.add("baton.oauth.github.token-url", () -> base + "/github/token");
        r.add("baton.github.api-url", () -> base + "/github");
        r.add("baton.google.token-url", () -> base + "/google/token");
        r.add("baton.oauth.google.userinfo-url", () -> base + "/google/userinfo");
    }

    @AfterAll
    static void stop() {
        FAKE.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OAuthState states;

    @Autowired
    TenantRegistry tenants;

    @org.springframework.beans.factory.annotation.Value("${baton.demo.password}")
    String demoPassword;

    private String adminToken() throws Exception {
        return new DemoFixture(mvc, jdbc, demoPassword).login("admin@annapurna.test");
    }

    private UUID tenantId() {
        return tenants.bySlug("demo").orElseThrow().id();
    }

    private UUID adminId() {
        return jdbc.sql("SELECT id FROM app_user WHERE email = 'admin@annapurna.test'").query(UUID.class).single();
    }

    private String callback(String kind, String query) throws Exception {
        return mvc.perform(get("/api/oauth/" + kind + "/callback?" + query)).andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");
    }

    private void forget(String kind) throws Exception {
        mvc.perform(delete("/api/admin/integrations/" + kind).header("Authorization", adminToken()));
    }

    @Test
    void theServerSaysWhichToolsCanBeConnectedWithOneClick() throws Exception {
        mvc.perform(get("/api/admin/integrations/oauth").header("Authorization", adminToken())).andExpect(status().isOk())
                .andExpect(jsonPath("$.SLACK").value(true)).andExpect(jsonPath("$.GITHUB").value(true))
                .andExpect(jsonPath("$.GOOGLE_DRIVE").value(true)).andExpect(jsonPath("$.JIRA").value(false));
        String neha = new DemoFixture(mvc, jdbc, demoPassword).login("neha@annapurna.test");
        mvc.perform(get("/api/admin/integrations/oauth").header("Authorization", neha)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/integrations/SLACK/oauth/start").header("Authorization", neha)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/integrations/JIRA/oauth/start").header("Authorization", adminToken())).andExpect(status().isBadRequest());
    }

    @Test
    void startSendsTheAdminToTheToolWithBatonsRedirectAndASignedState() throws Exception {
        String url = JsonPath.read(mvc.perform(post("/api/admin/integrations/SLACK/oauth/start").header("Authorization", adminToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.url");
        URI uri = URI.create(url);
        Map<String, String> q = form(uri.getRawQuery());
        assertThat(uri.getHost()).isEqualTo("slack.com");
        assertThat(q).containsEntry("client_id", "slack-client").containsEntry("redirect_uri", "https://api.baton.test/api/oauth/slack/callback");
        assertThat(q.get("scope")).contains("channels:history").contains("users:read").doesNotContain("write");
        OAuthState.Payload p = states.verify(q.get("state"), IntegrationService.Kind.SLACK);
        assertThat(p).isNotNull();
        assertThat(p.tenantId()).isEqualTo(tenantId());
        assertThat(p.userId()).isEqualTo(adminId());

        String g = JsonPath.read(mvc.perform(post("/api/admin/integrations/GOOGLE_DRIVE/oauth/start").header("Authorization", adminToken()))
                .andReturn().getResponse().getContentAsString(), "$.url");
        Map<String, String> gq = form(URI.create(g).getRawQuery());
        assertThat(gq).containsEntry("access_type", "offline").containsEntry("response_type", "code");
        assertThat(gq.get("scope")).contains("drive.readonly");
    }

    @Test
    void slackIsConnectedAndTheStoredTokenWorks() throws Exception {
        String state = states.sign(tenantId(), adminId(), IntegrationService.Kind.SLACK);
        String location = callback("slack", "code=good-code&state=" + state);
        assertThat(location).isEqualTo("https://app.baton.test/app/admin/connections?connected=slack");

        String admin = adminToken();
        mvc.perform(get("/api/integrations").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind == 'SLACK')].connected").value(true))
                .andExpect(jsonPath("$[?(@.kind == 'SLACK')].hint").value("Slack workspace Northfield Club (one click)"));
        // The token Slack gave is the one used for reads: the stored connection passes the Test button.
        mvc.perform(post("/api/admin/integrations/SLACK/test").header("Authorization", admin)).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Northfield Club")));
        assertThat(jdbc.sql("SELECT details ->> 'how' FROM audit_log WHERE action = 'INTEGRATION_CONNECTED' ORDER BY id DESC LIMIT 1")
                .query(String.class).single()).isEqualTo("oauth");
        forget("SLACK");
    }

    @Test
    void githubIsConnectedWithTheAccountsLoginAsTheHint() throws Exception {
        String state = states.sign(tenantId(), adminId(), IntegrationService.Kind.GITHUB);
        assertThat(callback("github", "code=good-code&state=" + state)).endsWith("?connected=github");
        mvc.perform(get("/api/integrations").header("Authorization", adminToken()))
                .andExpect(jsonPath("$[?(@.kind == 'GITHUB')].hint").value("GitHub account northfield-club (one click)"));
        mvc.perform(post("/api/admin/integrations/GITHUB/test").header("Authorization", adminToken()))
                .andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("northfield-club")));
        forget("GITHUB");
    }

    @Test
    void googleKeepsARefreshTokenAndRenewsAccessWhenSyncing() throws Exception {
        String state = states.sign(tenantId(), adminId(), IntegrationService.Kind.GOOGLE_DRIVE);
        assertThat(callback("google_drive", "code=good-code&state=" + state)).endsWith("?connected=google_drive");
        mvc.perform(get("/api/integrations").header("Authorization", adminToken()))
                .andExpect(jsonPath("$[?(@.kind == 'GOOGLE_DRIVE')].hint").value("Google account club.drive@gmail.example (one click)"));
        // Testing the stored connection mints a fresh access token from the refresh token.
        SEEN.clear();
        mvc.perform(post("/api/admin/integrations/GOOGLE_DRIVE/test").header("Authorization", adminToken()))
                .andExpect(jsonPath("$.ok").value(true));
        assertThat(SEEN).anyMatch(s -> s.startsWith("/google/token") && s.contains("grant_type=refresh_token") && s.contains("refresh_token=1%2F%2Frefresh-abc"));
        // The refresh token is stored encrypted, never in the clear.
        assertThat(jdbc.sql("SELECT position('refresh-abc'::bytea in secret_ciphertext) FROM integration WHERE kind = 'GOOGLE_DRIVE'")
                .query(Integer.class).single()).isZero();
        forget("GOOGLE_DRIVE");
    }

    @Test
    void forgedTamperedExpiredOrWrongToolStatesStoreNothing() throws Exception {
        String good = states.sign(tenantId(), adminId(), IntegrationService.Kind.SLACK);
        String tampered = good.substring(0, good.length() - 3) + (good.endsWith("AAA") ? "BBB" : "AAA");
        String expired = states.sign(tenantId(), adminId(), IntegrationService.Kind.SLACK, Instant.now().minusSeconds(5));
        String otherTool = states.sign(tenantId(), adminId(), IntegrationService.Kind.GITHUB);
        String unknownTenant = states.sign(UUID.randomUUID(), adminId(), IntegrationService.Kind.SLACK);

        for (String bad : List.of(tampered, expired, otherTool, "garbage", "a.b")) {
            assertThat(callback("slack", "code=good-code&state=" + bad)).contains("connectError=");
        }
        assertThat(callback("slack", "code=good-code")).contains("connectError=");
        assertThat(callback("slack", "code=good-code&state=" + unknownTenant)).contains("connectError=");
        assertThat(jdbc.sql("SELECT count(*) FROM integration WHERE kind = 'SLACK'").query(Integer.class).single()).isZero();

        // A state is not a sign-in token: it cannot be used as a bearer token.
        mvc.perform(get("/api/integrations").header("Authorization", "Bearer " + good)).andExpect(status().isUnauthorized());
    }

    @Test
    void whenTheToolRefusesNothingIsStoredAndTheAdminSeesWhy() throws Exception {
        String state = states.sign(tenantId(), adminId(), IntegrationService.Kind.SLACK);
        String location = callback("slack", "code=wrong-code&state=" + state);
        assertThat(URLDecoder.decode(location, StandardCharsets.UTF_8)).contains("connectError=Slack did not approve the connection (invalid_code)");
        // The person clicked "Cancel" on the tool's page.
        assertThat(callback("slack", "error=access_denied&state=" + states.sign(tenantId(), adminId(), IntegrationService.Kind.SLACK)))
                .contains("did+not+approve");
        assertThat(jdbc.sql("SELECT count(*) FROM integration WHERE kind = 'SLACK'").query(Integer.class).single()).isZero();
    }
}
