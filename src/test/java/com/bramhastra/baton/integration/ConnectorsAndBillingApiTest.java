package com.bramhastra.baton.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.bramhastra.baton.DemoFixture;
import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

/** Phases 6 and 7: Slack and Google Drive connectors with the workspace's own credentials; plans and payments. */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true",
        "baton.billing.free.people=2", "baton.billing.free.projects=1",
        "baton.billing.razorpay.key-id=rzp_test_key", "baton.billing.razorpay.key-secret=rzp_test_secret",
        "baton.billing.razorpay.webhook-secret=whsec_test"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
class ConnectorsAndBillingApiTest {

    static final HttpServer FAKE;
    static final List<String> SEEN = new CopyOnWriteArrayList<>();

    static {
        try {
            FAKE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        FAKE.createContext("/", ConnectorsAndBillingApiTest::serve);
        FAKE.start();
    }

    private static void serve(HttpExchange ex) throws java.io.IOException {
        String path = ex.getRequestURI().getPath();
        String query = String.valueOf(ex.getRequestURI().getQuery());
        String auth = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
        SEEN.add(path + " " + auth);
        String out;
        if (path.startsWith("/slack/")) {
            if (!auth.equals("Bearer xoxb-test-token-1234")) {
                out = "{\"ok\":false,\"error\":\"invalid_auth\"}";
            } else if (path.endsWith("/auth.test")) {
                out = "{\"ok\":true,\"team\":\"Northfield\"}";
            } else if (path.endsWith("/conversations.list")) {
                out = "{\"ok\":true,\"channels\":[{\"id\":\"C0123456789\",\"name\":\"dev\"}]}";
            } else if (path.endsWith("/users.list")) {
                out = "{\"ok\":true,\"members\":[{\"id\":\"U1\",\"real_name\":\"Priya Sharma\"},{\"id\":\"U2\",\"real_name\":\"Arjun Mehta\"}]}";
            } else if (path.endsWith("/conversations.history")) {
                long now = System.currentTimeMillis() / 1000;
                out = "{\"ok\":true,\"has_more\":false,\"messages\":["
                        + "{\"user\":\"U1\",\"ts\":\"" + (now - 3600) + ".0001\",\"text\":\"Heads up <@U2>: the staging database password is rotated by me every month, mail priya.sharma@gmail.com if it breaks.\"},"
                        + "{\"user\":\"U2\",\"ts\":\"" + (now - 3500) + ".0002\",\"text\":\"Decision: we ship the menu redesign on Friday after the canteen review.\"},"
                        + "{\"subtype\":\"channel_join\",\"user\":\"U2\",\"ts\":\"" + (now - 3400) + ".0003\",\"text\":\"joined\"}]}";
            } else {
                out = "{\"ok\":false,\"error\":\"unknown_method\"}";
            }
        } else if (path.equals("/token")) {
            out = "{\"access_token\":\"ya29.test\",\"expires_in\":3600}";
        } else if (path.equals("/drive/files")) {
            out = query.contains("FOLDERID1234567") && auth.equals("Bearer ya29.test")
                    ? "{\"files\":[{\"id\":\"doc1\",\"name\":\"Runbook\",\"mimeType\":\"application/vnd.google-apps.document\",\"modifiedTime\":\"2026-10-20T10:00:00Z\",\"lastModifyingUser\":{\"displayName\":\"Neha Kulkarni\"}},"
                    + "{\"id\":\"img1\",\"name\":\"logo.png\",\"mimeType\":\"image/png\",\"size\":\"100\"},"
                    + "{\"id\":\"txt1\",\"name\":\"notes.txt\",\"mimeType\":\"text/plain\",\"size\":\"60\",\"modifiedTime\":\"2026-10-21T10:00:00Z\"}]}"
                    : "{\"files\":[]}";
        } else if (path.equals("/drive/files/doc1/export")) {
            out = "Deploy runbook. The production deploy is run by Neha every Thursday from the release branch.";
        } else if (path.equals("/drive/files/txt1")) {
            out = "Vendor contact for the canteen spreadsheet is the operations desk.";
        } else if (path.equals("/rzp/payment_links")) {
            out = auth.startsWith("Basic ") ? "{\"id\":\"plink_test1\",\"short_url\":\"https://rzp.io/i/test1\"}" : "{}";
        } else {
            out = "{}";
        }
        byte[] b = out.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + FAKE.getAddress().getPort();
        r.add("baton.slack.api-url", () -> base + "/slack");
        r.add("baton.google.drive-url", () -> base + "/drive");
        r.add("baton.google.token-url", () -> base + "/token");
        r.add("baton.billing.razorpay.api-url", () -> base + "/rzp");
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
    JdbcConnectionDetails db;

    @Autowired
    ObjectMapper json;

    @Value("${baton.demo.password}")
    String demoPassword;

    JdbcClient home;

    @Autowired
    void setHome(@Qualifier("homeDataSource") DataSource ds) {
        this.home = JdbcClient.create(ds);
    }

    private ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) {
            req.header("Authorization", token.startsWith("Bearer ") ? token : "Bearer " + token);
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    private String addSource(DemoFixture demo, String token, String type, String param, String value) throws Exception {
        String body = mvc.perform(multipart("/api/projects/" + demo.projectId + "/sources").header("Authorization", token)
                        .param("type", type).param("title", type + " test").param("sensitivity", "INTERNAL")
                        .param("authorisationAttested", "true").param(param, value))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(demo.awaitJob(token, JsonPath.read(body, "$.jobId"))).isEqualTo("SUCCEEDED");
        return JsonPath.read(body, "$.source.id");
    }

    @Test
    void slackAndDriveReadWithTheWorkspacesOwnCredentials() throws Exception {
        DemoFixture demo = new DemoFixture(mvc, jdbc, demoPassword).createProject("Annapurna (connectors test)");
        String admin = demo.login("admin@annapurna.test");
        String neha = demo.login("neha@annapurna.test");

        // Not connected yet: adding a Slack source is refused with a pointer to Connections.
        mvc.perform(multipart("/api/projects/" + demo.projectId + "/sources").header("Authorization", neha)
                        .param("type", "SLACK").param("title", "x").param("authorisationAttested", "true").param("channel", "#dev"))
                .andExpect(status().isBadRequest());

        // Only admins connect tools; the secret is validated, tested and never returned.
        send(put("/api/admin/integrations/SLACK"), neha, Map.of("secret", "xoxb-test-token-1234")).andExpect(status().isForbidden());
        send(put("/api/admin/integrations/SLACK"), admin, Map.of("secret", "not-a-slack-token")).andExpect(status().isBadRequest());
        send(post("/api/admin/integrations/SLACK/test"), admin, Map.of("secret", "xoxb-wrong-token-0000"))
                .andExpect(jsonPath("$.ok").value(false));
        send(post("/api/admin/integrations/SLACK/test"), admin, Map.of("secret", "xoxb-test-token-1234"))
                .andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.message").value("Connected to the Slack workspace Northfield"));
        send(put("/api/admin/integrations/SLACK"), admin, Map.of("secret", "xoxb-test-token-1234"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hint").value("bot token ending 1234"))
                .andExpect(jsonPath("$.secret").doesNotExist());
        assertThat(jdbc.sql("SELECT encode(secret_ciphertext, 'escape') FROM integration WHERE kind = 'SLACK'")
                .query(String.class).single()).doesNotContain("xoxb");

        String slack = addSource(demo, neha, "SLACK", "channel", "#dev");
        String chunks = jdbc.sql("""
                SELECT string_agg(k.text, ' ') FROM chunk k JOIN document d ON d.id = k.document_id WHERE d.source_id = :s
                """).param("s", UUID.fromString(slack)).query(String.class).single();
        // Names resolved, joins skipped, and the email redacted before it was stored.
        assertThat(chunks).contains("Priya Sharma:").contains("@Arjun Mehta").contains("menu redesign")
                .doesNotContain("joined").doesNotContain("priya.sharma@gmail.com").contains("[EMAIL_");
        assertThat(jdbc.sql("SELECT author FROM document WHERE source_id = :s").param("s", UUID.fromString(slack))
                .query(String.class).list()).isNotEmpty();

        // Google Drive with a service-account key (a throwaway RSA key generated here).
        var kp = KeyPairGenerator.getInstance("RSA");
        kp.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(kp.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String key = json.writeValueAsString(Map.of("type", "service_account", "client_email", "baton@test-project.iam.gserviceaccount.com",
                "private_key", pem, "token_uri", "http://169.254.169.254/steal"));
        send(put("/api/admin/integrations/GOOGLE_DRIVE"), admin, Map.of("secret", "{\"type\":\"user\"}")).andExpect(status().isBadRequest());
        send(put("/api/admin/integrations/GOOGLE_DRIVE"), admin, Map.of("secret", key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hint").value("baton@test-project.iam.gserviceaccount.com"));
        send(post("/api/admin/integrations/GOOGLE_DRIVE/test"), admin, Map.of()).andExpect(jsonPath("$.ok").value(true));
        // The token address in the pasted key file is ignored: nothing was sent anywhere but the configured endpoint.
        assertThat(SEEN).anyMatch(s -> s.startsWith("/token"));

        String driveSource = addSource(demo, neha, "GOOGLE_DRIVE", "folder", "https://drive.google.com/drive/folders/FOLDERID1234567?usp=sharing");
        List<String> titles = jdbc.sql("SELECT title FROM document WHERE source_id = :s ORDER BY title")
                .param("s", UUID.fromString(driveSource)).query(String.class).list();
        assertThat(titles).containsExactlyInAnyOrder("Runbook", "notes.txt");

        // Live sources can be synced again; anyone signed in can see what is connected, never the secrets.
        String again = send(post("/api/sources/" + slack + "/sync"), neha, null).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(demo.awaitJob(neha, JsonPath.read(again, "$.jobId"))).isEqualTo("SUCCEEDED");
        String listed = demo.getJson(neha, "/api/integrations");
        assertThat((List<?>) JsonPath.read(listed, "$[?(@.connected == true)]")).hasSize(2);
        assertThat(listed).doesNotContain("xoxb").doesNotContain("PRIVATE KEY");

        // Leave the shared demo workspace as it was.
        send(delete("/api/admin/integrations/SLACK"), admin, null).andExpect(status().isNoContent());
        send(delete("/api/admin/integrations/GOOGLE_DRIVE"), admin, null).andExpect(status().isNoContent());
    }

    @Test
    void freePlanLimitsAndPayingLiftsThem() throws Exception {
        String dbName = "bill_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        home.sql("CREATE DATABASE " + dbName).update();
        URI u = URI.create(db.getJdbcUrl().substring(5));
        String ws = "bill-" + UUID.randomUUID().toString().substring(0, 8);
        String admin = JsonPath.read(send(post("/api/workspaces"), null, Map.of("slug", ws, "name", "Billing Co",
                "database", Map.of("url", "postgresql://" + u.getHost() + ":" + u.getPort() + "/" + dbName,
                        "username", db.getUsername(), "password", db.getPassword()),
                "admin", Map.of("displayName", "Asha Admin", "email", "asha@bill.example", "password", "company-admin-password")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.token");
        UUID tenantId = home.sql("SELECT id FROM control.tenant WHERE slug = :s").param("s", ws).query(UUID.class).single();

        send(get("/api/admin/billing"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("FREE")).andExpect(jsonPath("$.limits.people").value(2))
                .andExpect(jsonPath("$.usage.PEOPLE").value(1)).andExpect(jsonPath("$.paymentsEnabled").value(true));

        // Free: two people and one project.
        String ben = JsonPath.read(send(post("/api/admin/users"), admin, Map.of("displayName", "Ben", "email", "ben@bill.example"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.user.id");
        send(post("/api/admin/users"), admin, Map.of("displayName", "Cara", "email", "cara@bill.example"))
                .andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.error").value("PLAN_LIMIT"));
        send(post("/api/projects"), admin, Map.of("name", "One", "leadUserId", ben)).andExpect(status().isCreated());
        send(post("/api/projects"), admin, Map.of("name", "Two", "leadUserId", ben)).andExpect(status().isPaymentRequired());

        send(post("/api/admin/billing/checkout"), admin, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://rzp.io/i/test1"));

        // The webhook is trusted only with a valid signature, for the right amount, once.
        String event = json.writeValueAsString(Map.of("event", "payment_link.paid", "payload", Map.of("payment_link", Map.of("entity",
                Map.of("id", "plink_" + ws, "status", "paid", "amount_paid", 499900, "currency", "INR",
                        "notes", Map.of("tenant", tenantId.toString()))))));
        mvc.perform(post("/api/billing/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).content(event)
                .header("X-Razorpay-Signature", "0000")).andExpect(status().isUnauthorized());
        String cheap = event.replace("499900", "100");
        mvc.perform(post("/api/billing/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).content(cheap)
                .header("X-Razorpay-Signature", sign(cheap))).andExpect(status().isOk());
        send(get("/api/admin/billing"), admin, null).andExpect(jsonPath("$.plan").value("FREE"));

        mvc.perform(post("/api/billing/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).content(event)
                .header("X-Razorpay-Signature", sign(event))).andExpect(status().isOk());
        String paid = send(get("/api/admin/billing"), admin, null).andExpect(jsonPath("$.plan").value("TEAM"))
                .andReturn().getResponse().getContentAsString();
        String until = JsonPath.read(paid, "$.paidUntil");
        mvc.perform(post("/api/billing/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).content(event)
                .header("X-Razorpay-Signature", sign(event))).andExpect(status().isOk());
        assertThat((String) JsonPath.read(send(get("/api/admin/billing"), admin, null).andReturn().getResponse().getContentAsString(),
                "$.paidUntil")).isEqualTo(until);

        send(post("/api/admin/users"), admin, Map.of("displayName", "Cara", "email", "cara@bill.example")).andExpect(status().isCreated());
        send(post("/api/projects"), admin, Map.of("name", "Two", "leadUserId", ben)).andExpect(status().isCreated());

        // When the paid month runs out, nothing is removed; only adding is limited again.
        home.sql("UPDATE control.tenant SET paid_until = now() - interval '1 day' WHERE id = :id").param("id", tenantId).update();
        send(get("/api/admin/billing"), admin, null).andExpect(jsonPath("$.plan").value("FREE")).andExpect(jsonPath("$.usage.PEOPLE").value(3));
        send(post("/api/admin/users"), admin, Map.of("displayName", "Dev", "email", "dev@bill.example")).andExpect(status().isPaymentRequired());
        send(get("/api/admin/users"), admin, null).andExpect(jsonPath("$.length()").value(3));
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whsec_test".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
