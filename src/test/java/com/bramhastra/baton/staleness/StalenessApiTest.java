package com.bramhastra.baton.staleness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.bramhastra.baton.DemoFixture;
import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.jayway.jsonpath.JsonPath;

/** Staleness detection end to end: docs and a commit log are read, stale passages are found, the lead decides. */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
class StalenessApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Value("${baton.demo.password}")
    String demoPassword;

    static final String RUNBOOK = """
            # Runbook

            ## Deploy
            Run `npm run deploy` from the repo root.
            Set DATABASE_URL to the Neon connection string before starting.
            The API is built on Java 17.
            Support questions go to the ops desk.
            """;

    static final String HISTORY = """
            a1b2c3d 2026-11-08T10:00:00Z deven065: Rename DATABASE_URL to DB_URL
            b2c3d4e 2026-11-07T10:00:00Z deven065: Replace `npm run deploy` with `npm run release`
            c3d4e5f 2026-11-06T10:00:00Z deven065: Upgrade to Java 21
            d4e5f6a 2026-11-05T10:00:00Z deven065: Remove unused imports
            """;

    private String upload(DemoFixture demo, String token, String title, String file, String text) throws Exception {
        var req = multipart("/api/projects/" + demo.projectId + "/sources")
                .file(new MockMultipartFile("file", file, "text/plain", text.getBytes(StandardCharsets.UTF_8)));
        req.header("Authorization", token);
        req.param("type", "DOCUMENT").param("title", title).param("sensitivity", "INTERNAL").param("authorisationAttested", "true");
        String body = mvc.perform(req).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.jobId");
    }

    @Test
    void docsThatContradictLaterCodeChangesAreFlaggedAndTheLeadDecides() throws Exception {
        DemoFixture demo = new DemoFixture(mvc, jdbc, demoPassword).createProject("Stale docs");
        String neha = demo.login("neha@annapurna.test");
        String priya = demo.login("priya@annapurna.test");
        assertThat(demo.awaitJob(neha, upload(demo, neha, "Runbook", "runbook.md", RUNBOOK))).isEqualTo("SUCCEEDED");
        assertThat(demo.awaitJob(neha, upload(demo, neha, "Repository history", "history.txt", HISTORY))).isEqualTo("SUCCEEDED");

        // Reading the sources runs the check by itself.
        String url = "/api/projects/" + demo.projectId + "/staleness";
        List<Map<String, Object>> found = JsonPath.read(demo.getJson(neha, url), "$");
        assertThat(found).extracting(f -> f.get("term"))
                .containsExactlyInAnyOrder("DATABASE_URL", "npm run deploy", "Java 17");
        Map<String, Object> rename = found.stream().filter(f -> f.get("term").equals("DATABASE_URL")).findFirst().orElseThrow();
        assertThat(rename.get("kind")).isEqualTo("RENAMED");
        assertThat(rename.get("replacement")).isEqualTo("DB_URL");
        assertThat(rename.get("changeRef")).isEqualTo("commit a1b2c3d");
        assertThat(rename.get("docTitle")).isEqualTo("Runbook");
        assertThat(rename.get("docQuote")).isEqualTo("Set DATABASE_URL to the Neon connection string before starting.");
        assertThat(rename.get("status")).isEqualTo("OPEN");

        // Only the lead manages it.
        mvc.perform(get(url).header("Authorization", priya)).andExpect(status().isForbidden());

        // The lead decides; a decision survives a re-scan.
        String id = rename.get("id").toString();
        mvc.perform(post("/api/staleness/" + id + "/decision").header("Authorization", neha)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISMISSED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISMISSED"));
        mvc.perform(post("/api/staleness/" + id + "/decision").header("Authorization", neha)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"MAYBE\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/staleness/" + id + "/decision").header("Authorization", priya)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}")).andExpect(status().isForbidden());
        mvc.perform(post(url + "/scan").header("Authorization", neha)).andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(2));
        assertThat(jdbc.sql("SELECT status FROM stale_finding WHERE id = CAST(:id AS uuid)").param("id", id)
                .query(String.class).single()).isEqualTo("DISMISSED");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'STALE_DOC_DISMISSED'").query(Integer.class).single()).isPositive();

        // The exit interview asks the person leaving about outdated docs they can see.
        demo.loadSourcesAndReconstruct();
        List<Map<String, Object>> gaps = JsonPath.read(demo.getJson(priya, "/api/handovers/" + demo.handoverId + "/gaps"), "$");
        assertThat(gaps).filteredOn(g -> "STALE_DOC".equals(g.get("kind"))).isNotEmpty()
                .allSatisfy(g -> assertThat(g.get("question").toString()).contains("\"Runbook\" still says").contains("out of date"));
    }
}
