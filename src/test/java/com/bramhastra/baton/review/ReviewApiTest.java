package com.bramhastra.baton.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import com.bramhastra.baton.DemoFixture;
import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.bramhastra.baton.source.RedactorTest;
import com.jayway.jsonpath.JsonPath;

/** M4: review actions, exit interview as testimony, conflict resolution and the view-as preview. */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Value("${baton.demo.password}")
    String demoPassword;

    DemoFixture demo;
    String neha;
    String priya;
    String hid;

    @BeforeAll
    void setUp() throws Exception {
        demo = new DemoFixture(mvc, jdbc, demoPassword).createProject("Annapurna (review test)").loadSourcesAndReconstruct();
        neha = demo.login("neha@annapurna.test");
        priya = demo.login("priya@annapurna.test");
        hid = demo.handoverId;
    }

    private Map<String, Object> claim(String jsonPathFilter) throws Exception {
        String body = demo.getJson(neha, "/api/handovers/" + hid + "/claims?size=200");
        List<Map<String, Object>> found = JsonPath.read(body, "$.items[?(" + jsonPathFilter + ")]");
        assertThat(found).as(jsonPathFilter).isNotEmpty();
        return found.get(0);
    }

    private Map<String, Object> gap(String token, String contains) throws Exception {
        List<Map<String, Object>> gaps = JsonPath.read(demo.getJson(token, "/api/handovers/" + hid + "/gaps"), "$[*]");
        return gaps.stream().filter(g -> ((String) g.get("question")).contains(contains)).findFirst()
                .orElseThrow(() -> new AssertionError("No gap containing " + contains));
    }

    private ResultActions patchClaim(String token, Object id, String json) throws Exception {
        return mvc.perform(patch("/api/claims/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions postJson(String token, String url, String json) throws Exception {
        return mvc.perform(post(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void acceptEditAndOptimisticLocking() throws Exception {
        Map<String, Object> c = claim("@.type == 'DEPENDENCY'");
        int v = (Integer) c.get("version");
        patchClaim(neha, c.get("id"), "{\"version\":" + v + ",\"reviewState\":\"ACCEPTED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewState").value("ACCEPTED"))
                .andExpect(jsonPath("$.version").value(v + 1));

        // stale version → 409 with the current claim
        patchClaim(neha, c.get("id"), "{\"version\":" + v + ",\"reviewState\":\"EDITED\",\"title\":\"x\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CLAIM_STALE"))
                .andExpect(jsonPath("$.details.version").value(v + 1));

        // edit → EDITED, with before/after in the revision history
        patchClaim(neha, c.get("id"), "{\"version\":" + (v + 1) + ",\"title\":\"Menu API depends on the vendor's weekly spreadsheet\",\"reason\":\"clarified\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewState").value("EDITED"));
        String revisions = demo.getJson(neha, "/api/claims/" + c.get("id") + "/revisions");
        List<String> reasons = JsonPath.read(revisions, "$[*].reason");
        assertThat(reasons).contains("clarified");
        assertThat((String) JsonPath.read(revisions, "$[1].before.reviewState")).isEqualTo("ACCEPTED");
        assertThat((String) JsonPath.read(revisions, "$[1].after.reviewState")).isEqualTo("EDITED");
    }

    @Test
    void stateMachineRejectsIllegalMovesAndRestoreWorks() throws Exception {
        Map<String, Object> c = claim("@.type == 'RECURRING_ISSUE'");
        int v = (Integer) c.get("version");
        patchClaim(neha, c.get("id"), "{\"version\":" + v + ",\"reviewState\":\"REJECTED\",\"reason\":\"noise\"}")
                .andExpect(status().isOk());
        patchClaim(neha, c.get("id"), "{\"version\":" + (v + 1) + ",\"reviewState\":\"ACCEPTED\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_TRANSITION"));
        patchClaim(neha, c.get("id"), "{\"version\":" + (v + 1) + ",\"reviewState\":\"PENDING\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewState").value("PENDING"));
    }

    @Test
    void onlyTheLeadReviewsAndOnlyTheLeaverAnswers() throws Exception {
        Map<String, Object> c = claim("@.type == 'RISK'");
        patchClaim(priya, c.get("id"), "{\"version\":" + c.get("version") + ",\"reviewState\":\"ACCEPTED\"}")
                .andExpect(status().isForbidden());
        Map<String, Object> g = gap(neha, "deploy to production");
        postJson(neha, "/api/gaps/" + g.get("id") + "/answer", "{\"answer\":\"I would rather not\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void needsInfoAsksTheLeaverAndTheAnswerBecomesEvidence() throws Exception {
        Map<String, Object> c = claim("@.type == 'BLOCKER'");
        int evidenceBefore = ((List<?>) c.get("evidence")).size();
        patchClaim(neha, c.get("id"), "{\"version\":" + c.get("version")
                + ",\"reviewState\":\"NEEDS_INFO\",\"reason\":\"Who at the vendor is sending the Razorpay key, and by when?\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewState").value("NEEDS_INFO"));

        Map<String, Object> g = gap(priya, "Who at the vendor");
        assertThat(g.get("kind")).isEqualTo("OTHER");
        postJson(priya, "/api/gaps/" + g.get("id") + "/answer",
                "{\"answer\":\"Ramesh promised the live key by 15 November; mail him at ramesh@canteenfoods.example.\",\"sensitivity\":\"INTERNAL\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.answer").value("Ramesh promised the live key by 15 November; mail him at [EMAIL_1]."));

        String after = demo.getJson(neha, "/api/claims/" + c.get("id"));
        assertThat((String) JsonPath.read(after, "$.reviewState")).isEqualTo("PENDING");
        List<Map<String, Object>> ev = JsonPath.read(after, "$.evidence");
        assertThat(ev).hasSize(evidenceBefore + 1);
        assertThat(after).contains("\"type\":\"TESTIMONY\"").doesNotContain("ramesh@canteenfoods.example");
        // the answer is cited exactly
        Integer bad = jdbc.sql("""
                SELECT count(*) FROM evidence e JOIN chunk k ON k.id = e.chunk_id
                WHERE e.valid AND substring(k.text FROM e.start_offset + 1 FOR e.end_offset - e.start_offset) <> e.quote
                """).query(Integer.class).single();
        assertThat(bad).isZero();
    }

    @Test
    void ownerAnswerNamesTheSuccessor() throws Exception {
        Map<String, Object> g = gap(priya, "ANN-12");
        postJson(priya, "/api/gaps/" + g.get("id") + "/answer",
                "{\"answer\":\"Arjun takes over ANN-12; the webhook retry fix is the next step.\",\"owner\":\"Arjun Mehta\"}")
                .andExpect(status().isOk());
        Map<String, Object> ann12 = claim("@.title == 'ANN-12: Migrate payments to Razorpay'");
        Map<?, ?> attrs = (Map<?, ?>) ann12.get("attributes");
        assertThat(attrs.get("owner")).isEqualTo("Arjun Mehta");
        assertThat(attrs.get("ownerIsDeparting")).isEqualTo(false);
        assertThat(ann12.get("status")).isEqualTo("VERIFIED");
        assertThat(ann12.get("reasons").toString()).contains("2 sources");
    }

    @Test
    void checklistAnswerCreatesATestimonyClaim() throws Exception {
        Map<String, Object> g = gap(priya, "deploy to production");
        postJson(priya, "/api/gaps/" + g.get("id") + "/answer",
                "{\"answer\":\"Push to main; Render auto-deploys the backend and Vercel the frontend. Arjun has Render access.\"}")
                .andExpect(status().isOk());
        Map<String, Object> c = claim("@.origin == 'TESTIMONY' && @.type == 'PROCESS'");
        assertThat((String) c.get("title")).startsWith("How to deploy to production");
        assertThat(c.get("status")).isEqualTo("SINGLE_SOURCE");
        // answering twice is refused
        postJson(priya, "/api/gaps/" + g.get("id") + "/answer", "{\"answer\":\"again and again\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void secretsInAnswersAreRefused() throws Exception {
        Map<String, Object> g = gap(priya, "API keys and secrets");
        postJson(priya, "/api/gaps/" + g.get("id") + "/answer",
                "{\"answer\":\"The key is " + RedactorTest.FAKE_GROQ_KEY + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.answer").exists());
        Integer leaked = jdbc.sql("SELECT count(*) FROM chunk WHERE text LIKE :k").param("k", "%" + RedactorTest.FAKE_GROQ_KEY + "%")
                .query(Integer.class).single();
        assertThat(leaked).isZero();
    }

    @Test
    void leadResolvesTheLaunchConflict() throws Exception {
        Map<String, Object> nov29 = claim("@.subject == 'public launch date' && @.attributes.value == '2026-11-29'");
        Map<String, Object> nov15 = claim("@.subject == 'public launch date' && @.attributes.value == '2026-11-15'");
        postJson(neha, "/api/claims/" + nov29.get("id") + "/resolve-conflict",
                "{\"otherClaimId\":\"" + nov15.get("id") + "\",\"reason\":\"Priya confirmed in the exit interview\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SINGLE_SOURCE"));
        String old = demo.getJson(neha, "/api/claims/" + nov15.get("id"));
        assertThat((String) JsonPath.read(old, "$.reviewState")).isEqualTo("REJECTED");
        List<String> kinds = JsonPath.read(old, "$.links[*].kind");
        assertThat(kinds).contains("SUPERSEDES");
        String gaps = demo.getJson(neha, "/api/handovers/" + hid + "/gaps");
        List<String> conflictStatus = JsonPath.read(gaps, "$[?(@.kind == 'CONFLICT')].status");
        assertThat(conflictStatus).containsOnly("SKIPPED");
        // resolving again is refused
        postJson(neha, "/api/claims/" + nov29.get("id") + "/resolve-conflict",
                "{\"otherClaimId\":\"" + nov15.get("id") + "\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void humanClaimsMustQuoteTheSourceExactly() throws Exception {
        UUID chunk = jdbc.sql("""
                SELECT k.id FROM chunk k JOIN document d ON d.id = k.document_id JOIN source s ON s.id = d.source_id
                WHERE s.project_id = :p AND s.title = 'Architecture notes'
                """).param("p", demo.projectId).query(UUID.class).single();
        postJson(neha, "/api/handovers/" + hid + "/claims",
                "{\"type\":\"PROCESS\",\"title\":\"Secrets are in Render\",\"evidence\":[{\"chunkId\":\"" + chunk
                        + "\",\"quote\":\"Secrets are emailed to the team every Friday\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details['evidence[0].quote']").exists());
        postJson(neha, "/api/handovers/" + hid + "/claims",
                "{\"type\":\"PROCESS\",\"title\":\"Secrets live in the Render dashboard\",\"evidence\":[{\"chunkId\":\"" + chunk
                        + "\",\"quote\":\"environment secrets live in the Render dashboard\"}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("HUMAN"))
                .andExpect(jsonPath("$.reviewState").value("ACCEPTED"))
                .andExpect(jsonPath("$.sensitivity").value("CONFIDENTIAL"))
                .andExpect(jsonPath("$.evidence[0].quote").value("Environment secrets live in the Render dashboard"));
    }

    @Test
    void viewAsShowsExactlyWhatEachRecipientMaySee() throws Exception {
        UUID rahul = jdbc.sql("SELECT id FROM app_user WHERE email = 'rahul@annapurna.test'").query(UUID.class).single();
        UUID arjun = jdbc.sql("SELECT id FROM app_user WHERE email = 'arjun@annapurna.test'").query(UUID.class).single();
        String asRahul = demo.getJson(neha, "/api/handovers/" + hid + "/preview?as=" + rahul);
        List<String> rahulSens = JsonPath.read(asRahul, "$.claims[*].sensitivity");
        assertThat(rahulSens).isNotEmpty().allMatch(s -> s.equals("INTERNAL") || s.equals("PUBLIC"));
        assertThat((Integer) JsonPath.read(asRahul, "$.hiddenFromThemCount")).isPositive();
        assertThat(asRahul).doesNotContain("Postgres instead of Firebase").doesNotContain("Ramesh Patil");

        String asArjun = demo.getJson(neha, "/api/handovers/" + hid + "/preview?as=" + arjun);
        assertThat(asArjun).contains("Postgres instead of Firebase").doesNotContain("Ramesh Patil");

        mvc.perform(get("/api/handovers/" + hid + "/preview?as=" + arjun).header("Authorization", priya))
                .andExpect(status().isForbidden());
    }
}
