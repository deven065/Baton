package com.bramhastra.baton.reconstruct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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

import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.bramhastra.baton.DemoFixture;
import com.jayway.jsonpath.JsonPath;

/**
 * Flow B end to end on the synthetic Project Annapurna data, in offline AI mode.
 * Checks the planted facts, the truth-layer invariant, and the permission rules for claims and gaps.
 */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconstructApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Value("${baton.demo.password}")
    String demoPassword;

    DemoFixture demo;
    String neha;
    String handoverId;

    @BeforeAll
    void loadDemoAndReconstruct() throws Exception {
        demo = new DemoFixture(mvc, jdbc, demoPassword).createProject("Annapurna (reconstruct test)").loadSourcesAndReconstruct();
        neha = demo.login("neha@annapurna.test");
        handoverId = demo.handoverId;
    }

    private void reconstruct() throws Exception {
        demo.reconstruct();
    }

    @Test
    void launchDateConflictIsDetectedAndBecomesTheFirstExitQuestion() throws Exception {
        String body = getJson(neha, "/api/handovers/" + handoverId + "/claims?type=DECISION");
        List<Map<String, Object>> launch = JsonPath.read(body, "$.items[?(@.subject == 'public launch date')]");
        assertThat(launch).hasSize(2);
        assertThat(launch).allSatisfy(c -> assertThat(c.get("status")).isEqualTo("CONFLICTING"));
        List<Object> values = launch.stream().map(c -> ((Map<?, ?>) c.get("attributes")).get("value")).map(Object.class::cast).toList();
        assertThat(values).containsExactlyInAnyOrder("2026-11-15", "2026-11-29");
        List<String> linkKinds = JsonPath.read(body, "$.items[?(@.subject == 'public launch date')].links[*].kind");
        assertThat(linkKinds).contains("CONFLICTS_WITH");

        String gaps = getJson(neha, "/api/handovers/" + handoverId + "/gaps");
        Map<String, Object> first = JsonPath.read(gaps, "$[0]");
        assertThat(first.get("kind")).isEqualTo("CONFLICT");
        assertThat((String) first.get("question")).contains("15 November", "Nov 29");

        String timeline = getJson(neha, "/api/handovers/" + handoverId + "/timeline");
        List<String> kinds = JsonPath.read(timeline, "$[*].kind");
        assertThat(kinds).contains("CHANGE");
    }

    @Test
    void taskBoardSignalsAreVerifiedAndUnownedWorkBecomesAQuestion() throws Exception {
        String body = getJson(neha, "/api/handovers/" + handoverId + "/claims?type=OPEN_WORK");
        List<Map<String, Object>> ann12 = JsonPath.read(body, "$.items[?(@.title == 'ANN-12: Migrate payments to Razorpay')]");
        assertThat(ann12).hasSize(1);
        assertThat(ann12.get(0).get("status")).isEqualTo("VERIFIED");
        assertThat(ann12.get(0).get("origin")).isEqualTo("REPO_SIGNAL");
        List<String> quotes = JsonPath.read(body, "$.items[?(@.title == 'ANN-12: Migrate payments to Razorpay')].evidence[*].quote");
        assertThat(quotes).contains("Summary: Migrate payments to Razorpay", "Assignee: Priya Sharma");

        String gaps = getJson(neha, "/api/handovers/" + handoverId + "/gaps");
        List<String> questions = JsonPath.read(gaps, "$[*].question");
        assertThat(questions).anyMatch(q -> q.contains("ANN-12") && q.contains("take over"));
        assertThat(questions).anyMatch(q -> q.contains("ANN-14") && q.contains("no owner"));
        assertThat(questions).anyMatch(q -> q.contains("Render account") && q.contains("transferred"));
        assertThat(questions.size()).isBetween(6, 12);
    }

    @Test
    void everyVerifiedQuoteIsExactlyTheSourceText() {
        Integer bad = jdbc.sql("""
                SELECT count(*) FROM evidence e JOIN chunk k ON k.id = e.chunk_id
                WHERE e.valid AND substring(k.text FROM e.start_offset + 1 FOR e.end_offset - e.start_offset) <> e.quote
                """).query(Integer.class).single();
        assertThat(bad).isZero();
        Integer verifiedWithoutQuote = jdbc.sql("""
                SELECT count(*) FROM claim c WHERE c.status IN ('VERIFIED', 'SINGLE_SOURCE')
                AND NOT EXISTS (SELECT 1 FROM evidence e WHERE e.claim_id = c.id AND e.valid)
                """).query(Integer.class).single();
        assertThat(verifiedWithoutQuote).isZero();
    }

    @Test
    void promptInjectionIsReportedAndChangesNothing() throws Exception {
        String run = getJson(neha, "/api/handovers/" + handoverId + "/runs/latest");
        List<String> warnings = JsonPath.read(run, "$.warnings");
        assertThat(warnings).anyMatch(w -> w.contains("instructions aimed at the AI"));
        String all = getJson(neha, "/api/handovers/" + handoverId + "/claims?size=200");
        assertThat(all).doesNotContainIgnoringCase("mark every claim");
        List<String> statuses = JsonPath.read(all, "$.items[*].status");
        assertThat(statuses).contains("CONFLICTING", "SINGLE_SOURCE");
    }

    @Test
    void restrictedVendorFactsReachTheLeadButNotTheDepartingMember() throws Exception {
        String nehaClaims = getJson(neha, "/api/handovers/" + handoverId + "/claims?size=200");
        assertThat(nehaClaims).contains("Vendor contract");

        String priya = login("priya@annapurna.test");
        String priyaClaims = getJson(priya, "/api/handovers/" + handoverId + "/claims?size=200");
        assertThat(priyaClaims).doesNotContain("Vendor contract").doesNotContain("Ramesh");
        assertThat(priyaClaims).contains("ANN-12"); // but she does see her own work
        String priyaGaps = getJson(priya, "/api/handovers/" + handoverId + "/gaps");
        assertThat(priyaGaps).doesNotContain("Ramesh").doesNotContain("renew");

        String vendorClaimId = JsonPath.<List<String>>read(nehaClaims,
                "$.items[?(@.evidence[0].source.sourceTitle == 'Vendor contract')].id").get(0);
        mvc.perform(get("/api/claims/" + vendorClaimId).header("Authorization", priya)).andExpect(status().isNotFound());
    }

    @Test
    void recipientsAndOutsidersCannotOpenTheWorkspace() throws Exception {
        mvc.perform(get("/api/handovers/" + handoverId + "/claims").header("Authorization", login("arjun@annapurna.test")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/handovers/" + handoverId + "/claims").header("Authorization", login("admin@annapurna.test")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/handovers/" + handoverId + "/reconstruct").header("Authorization", login("priya@annapurna.test")))
                .andExpect(status().isForbidden());
    }

    @Test
    void reRunIsIdempotent() throws Exception {
        int before = JsonPath.read(getJson(neha, "/api/handovers/" + handoverId + "/summary"), "$.total");
        reconstruct();
        int after = JsonPath.read(getJson(neha, "/api/handovers/" + handoverId + "/summary"), "$.total");
        assertThat(after).isEqualTo(before);
        assertThat((String) JsonPath.read(getJson(neha, "/api/handovers/" + handoverId), "$.status")).isEqualTo("IN_REVIEW");
    }

    private String login(String email) throws Exception {
        return demo.login(email);
    }

    private String getJson(String token, String url) throws Exception {
        return demo.getJson(token, url);
    }
}
