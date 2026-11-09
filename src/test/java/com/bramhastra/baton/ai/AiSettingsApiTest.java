package com.bramhastra.baton.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.bramhastra.baton.DemoFixture;
import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

/** Phase 5: a workspace uses its own AI provider account, and only redacted text ever reaches it. */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiSettingsApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper json;

    @Value("${baton.demo.password}")
    String demoPassword;

    record Call(String auth, String body) {
    }

    final List<Call> calls = new CopyOnWriteArrayList<>();
    HttpServer provider;
    String admin;

    @BeforeAll
    void fakeProvider() throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(new Call(ex.getRequestHeaders().getFirst("Authorization"), body));
            String reply;
            int code = 200;
            if (!"Bearer company-key-12345678".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                code = 401;
                reply = "{\"error\":\"bad key\"}";
            } else {
                String content = body.contains("connectivity check") ? "OK" : "{\\\"claims\\\": []}";
                reply = "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}],"
                        + "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":7}}";
            }
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        provider.start();
        admin = new DemoFixture(mvc, jdbc, demoPassword).login("admin@annapurna.test");
    }

    @AfterAll
    void cleanUp() throws Exception {
        // The demo workspace is shared with other test classes: put it back on the offline extractor.
        send(delete("/api/admin/ai"), admin, null);
        provider.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
    }

    private ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        req.header("Authorization", token);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    @Test
    void reconstructionUsesTheCompanysOwnKeyAndSendsOnlyRedactedText() throws Exception {
        send(get("/api/admin/ai"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.configured").value(false));

        // A wrong key fails the test, and nothing is saved by testing.
        send(post("/api/admin/ai/test"), admin, Map.of("provider", "CUSTOM", "baseUrl", url(), "model", "company-model",
                "apiKey", "wrong-key-0000")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("check the API key")));
        send(post("/api/admin/ai/test"), admin, Map.of("provider", "CUSTOM", "baseUrl", url(), "model", "company-model",
                "apiKey", "company-key-12345678")).andExpect(jsonPath("$.ok").value(true));
        assertThat(jdbc.sql("SELECT count(*) FROM ai_setting").query(Integer.class).single()).isZero();

        send(put("/api/admin/ai"), admin, Map.of("provider", "CUSTOM", "baseUrl", url(), "model", "company-model",
                "apiKey", "company-key-12345678", "enabled", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyHint").value("5678"))
                .andExpect(jsonPath("$.apiKey").doesNotExist());
        // Stored encrypted: the plain key is nowhere in the row or the audit log.
        String row = jdbc.sql("SELECT encode(api_key_ciphertext, 'escape') FROM ai_setting").query(String.class).single();
        assertThat(row).doesNotContain("company-key");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE details::text LIKE '%company-key%'")
                .query(Integer.class).single()).isZero();
        // A lead (not an admin) cannot see or change it.
        String neha = new DemoFixture(mvc, jdbc, demoPassword).login("neha@annapurna.test");
        send(get("/api/admin/ai"), neha, null).andExpect(status().isForbidden());

        calls.clear();
        DemoFixture demo = new DemoFixture(mvc, jdbc, demoPassword).createProject("Annapurna (AI test)").loadSourcesAndReconstruct();
        assertThat(calls).isNotEmpty().allMatch(c -> "Bearer company-key-12345678".equals(c.auth()));
        String sent = String.join("\n", calls.stream().map(Call::body).toList());
        assertThat(sent).contains("\"model\":\"company-model\"");
        // Personal data was redacted at ingest, before any AI call: placeholders go out, addresses do not.
        assertThat(sent).contains("[EMAIL_").doesNotContainPattern("[A-Za-z0-9._%+-]+@gmail\\.com");

        String run = demo.getJson(neha, "/api/handovers/" + demo.handoverId + "/runs/latest");
        assertThat(run).contains("company-model");
        send(get("/api/admin/ai"), admin, null)
                .andExpect(jsonPath("$.usage.calls").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.usage.tokensIn").value(org.hamcrest.Matchers.greaterThan(0)));

        // Switched off: back to the offline extractor, the key stays stored for later.
        send(put("/api/admin/ai"), admin, Map.of("provider", "CUSTOM", "baseUrl", url(), "model", "company-model", "enabled", false))
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.keyHint").value("5678"));
        send(delete("/api/admin/ai"), admin, null).andExpect(status().isNoContent());
        send(get("/api/admin/ai"), admin, null).andExpect(jsonPath("$.settings.configured").value(false));
    }

    @Test
    void providerAddressesMustBePublicHttps() {
        assertThatThrownBy(() -> ProviderUrl.check("http://8.8.8.8/v1", false)).hasMessageContaining("https://");
        assertThatThrownBy(() -> ProviderUrl.check("https://127.0.0.1/v1", false)).hasMessageContaining("private");
        assertThatThrownBy(() -> ProviderUrl.check("https://169.254.169.254/latest", false)).hasMessageContaining("private");
        assertThatThrownBy(() -> ProviderUrl.check("https://10.0.0.5/v1", false)).hasMessageContaining("private");
        assertThatThrownBy(() -> ProviderUrl.check("https://user:pw@8.8.8.8/v1", false)).hasMessageContaining("plain address");
        assertThat(ProviderUrl.check("https://8.8.8.8/v1/", false)).isEqualTo("https://8.8.8.8/v1");
    }
}
