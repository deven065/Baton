package com.bramhastra.baton.reconstruct;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.bramhastra.baton.DemoFixture;
import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The evaluation from the proposal, run on every build: all seven Project Annapurna sources (Slack, WhatsApp,
 * Discord, task board, notes and documents) are reconstructed in offline mode and scored against the 25
 * facts in demo/annapurna/planted-facts.json, the same file demo/evaluate.py uses against a running server.
 * Writes target/evaluation-report.md.
 */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlantedFactsEvaluationTest {

    /** Offline, rule-based extraction finds this many; a model should find more. Raise it when the rules improve. */
    static final int OFFLINE_RECALL_FLOOR = 23;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper json;

    @Value("${baton.demo.password}")
    String demoPassword;

    DemoFixture demo;
    JsonNode spec;
    List<Map<String, Object>> claims;
    List<Map<String, Object>> gaps;

    @BeforeAll
    void reconstructAllSevenSources() throws Exception {
        demo = new DemoFixture(mvc, jdbc, demoPassword).createProject("Annapurna (evaluation)").loadSourcesAndReconstruct(true);
        spec = json.readTree(Files.readString(Path.of("demo", "annapurna", "planted-facts.json")));
        String neha = demo.login("neha@annapurna.test");
        claims = JsonPath.read(demo.getJson(neha, "/api/handovers/" + demo.handoverId + "/claims?size=200"), "$.items");
        gaps = JsonPath.read(demo.getJson(neha, "/api/handovers/" + demo.handoverId + "/gaps"), "$");
    }

    @Test
    void recallOnThePlantedFactsIsReportedAndDoesNotRegress() throws Exception {
        List<String> rows = new ArrayList<>();
        int found = 0;
        for (JsonNode f : spec.path("facts")) {
            String hit = match(f);
            if (hit != null) {
                found++;
            }
            rows.add("| %s | %s | %s | %s | %s |".formatted(f.path("id").asString(), f.path("what").asString(),
                    f.path("source").asString(), hit != null ? "yes" : "**no**", hit == null ? "" : hit.replace("|", "/")));
        }
        int total = spec.path("facts").size();
        String report = String.join("\n", List.of(
                "# Baton evaluation: Project Annapurna (offline mode, from the test suite)", "",
                "- **Recall: %d of %d planted facts (%d%%)**".formatted(found, total, 100 * found / total),
                "- Claims: %d · exit questions: %d".formatted(claims.size(), gaps.size()), "",
                "| Fact | What was planted | Source | Found | Matched by |", "|---|---|---|---|---|"))
                + "\n" + String.join("\n", rows) + "\n";
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "evaluation-report.md"), report, StandardCharsets.UTF_8);
        System.out.println(report);

        assertThat(total).isEqualTo(25);
        assertThat(found).as("planted facts found offline; see target/evaluation-report.md").isGreaterThanOrEqualTo(OFFLINE_RECALL_FLOOR);
    }

    @Test
    void injectedInstructionsNeverBecomeClaims() throws Exception {
        String everything = (json.writeValueAsString(claims) + json.writeValueAsString(gaps)).toLowerCase(Locale.ROOT);
        for (JsonNode t : spec.path("checks").path("neverInClaims")) {
            assertThat(everything).doesNotContain(t.asString().toLowerCase(Locale.ROOT));
        }
    }

    @Test
    void plantedSecretsAreNeverStored() {
        // Chunks are what the AI is sent and what quotes are cut from; nothing else keeps source text.
        String stored = String.join("\n", jdbc.sql("""
                SELECT k.text FROM chunk k JOIN document d ON d.id = k.document_id JOIN source s ON s.id = d.source_id
                WHERE s.project_id = :p
                """).param("p", demo.projectId).query(String.class).list());
        assertThat(stored).contains("[API_KEY_", "Cloudinary", "Figma"); // the new sources were read
        for (JsonNode s : spec.path("checks").path("secretsNeverStored")) {
            assertThat(stored).doesNotContain(s.asString());
        }
    }

    @Test
    void restrictedFactsStayHiddenFromThePersonLeaving() throws Exception {
        for (var entry : spec.path("checks").path("hiddenFrom").properties()) {
            String token = demo.login(entry.getKey());
            String seen = demo.getJson(token, "/api/handovers/" + demo.handoverId + "/claims?size=200")
                    + demo.getJson(token, "/api/handovers/" + demo.handoverId + "/gaps");
            for (JsonNode w : entry.getValue()) {
                assertThat(seen.toLowerCase(Locale.ROOT)).doesNotContain(w.asString().toLowerCase(Locale.ROOT));
            }
        }
    }

    /** Same rule as demo/evaluate.py: every word in "all", in a claim of an allowed type (or a question). */
    private String match(JsonNode fact) {
        List<String> words = new ArrayList<>();
        fact.path("all").forEach(w -> words.add(w.asString().toLowerCase(Locale.ROOT)));
        String kind = fact.path("kind").asString();
        if (kind.equals("gap")) {
            return gaps.stream().map(g -> (String) g.get("question"))
                    .filter(q -> words.stream().allMatch(q.toLowerCase(Locale.ROOT)::contains)).findFirst().orElse(null);
        }
        List<String> types = new ArrayList<>();
        fact.path("types").forEach(t -> types.add(t.asString()));
        List<Map<String, Object>> hits = claims.stream()
                .filter(c -> kind.equals("conflict") ? "CONFLICTING".equals(c.get("status")) : types.isEmpty() || types.contains(c.get("type")))
                .filter(c -> words.stream().allMatch(text(c)::contains)).toList();
        if (kind.equals("conflict")) {
            return hits.size() >= 2 ? hits.size() + " conflicting claims" : null;
        }
        return hits.isEmpty() ? null : "%s \"%s\" (%s)".formatted(hits.get(0).get("type"), hits.get(0).get("title"), hits.get(0).get("status"));
    }

    @SuppressWarnings("unchecked")
    private static String text(Map<String, Object> c) {
        StringBuilder sb = new StringBuilder().append(c.get("title")).append(' ').append(c.get("body"));
        for (Map<String, Object> e : (List<Map<String, Object>>) c.get("evidence")) {
            sb.append(' ').append(e.get("quote"));
        }
        Map<String, Object> attributes = (Map<String, Object>) c.get("attributes");
        if (attributes != null) {
            attributes.values().forEach(v -> sb.append(' ').append(v));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
