package com.bramhastra.baton.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;

/**
 * Jira as a live source, and automatic sync end to end with a fake Jira: a first read, a scheduled check that finds
 * nothing (and re-indexes nothing), a scheduled check that finds new evidence (notice for the lead, the handover in
 * review is reconstructed again, an approved one is left alone), a failing read (the source keeps its last snapshot,
 * the lead is told, and the notice clears when a read works again), and who may do what.
 */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true", "baton.sync.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutoSyncApiTest {

    static final String ISSUE_PRIYA = """
            {"key":"ANN-12","fields":{"summary":"Migrate payments to Razorpay","status":{"name":"In Progress"},
             "assignee":{"displayName":"Priya Sharma"},"reporter":{"displayName":"Neha Kulkarni"},
             "updated":"2026-10-18T14:00:00.000+0530","duedate":"2026-11-20","issuetype":{"name":"Task"},
             "description":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Webhook retries must be idempotent."}]}]}}}""";
    static final String ISSUE_DONE = """
            {"key":"ANN-10","fields":{"summary":"Login with college email","status":{"name":"Done"},
             "assignee":{"displayName":"Priya Sharma"},"reporter":{"displayName":"Neha Kulkarni"},"updated":"2026-09-30T10:00:00.000+0530"}}""";
    static final String ISSUE_NEW = """
            {"key":"ANN-31","fields":{"summary":"Rotate the canteen POS credentials","status":{"name":"To Do"},
             "reporter":{"displayName":"Neha Kulkarni"},"updated":"2026-11-12T10:00:00.000+0530"}}""";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SyncService sync;

    @Value("${baton.demo.password}")
    String demoPassword;

    HttpServer jira;
    final AtomicReference<List<String>> issues = new AtomicReference<>(List.of(ISSUE_PRIYA, ISSUE_DONE));
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicInteger searches = new AtomicInteger();

    DemoFixture demo;
    String admin;
    String neha;
    String rahul;
    String sourceId;

    @BeforeAll
    void setUp() throws Exception {
        jira = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jira.createContext("/rest/api/3/myself", ex -> reply(ex, ex.getRequestHeaders().getFirst("Authorization") == null ? 401 : 200,
                "{\"displayName\":\"Neha Kulkarni\"}"));
        jira.createContext("/rest/api/3/search/jql", ex -> {
            searches.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (status.get() != 200) {
                reply(ex, status.get(), "{\"errorMessages\":[\"nope\"]}");
            } else if (!body.contains("project = \\\"ANN\\\"")) {
                reply(ex, 400, "{}");
            } else {
                reply(ex, 200, "{\"isLast\":true,\"issues\":[" + String.join(",", issues.get()) + "]}");
            }
        });
        jira.start();
        demo = new DemoFixture(mvc, jdbc, demoPassword).createProject("Auto sync");
        admin = demo.login("admin@annapurna.test");
        neha = demo.login("neha@annapurna.test");
        rahul = demo.login("rahul@annapurna.test");
    }

    @AfterAll
    void tearDown() throws Exception {
        mvc.perform(delete("/api/admin/integrations/JIRA").header("Authorization", admin));
        jira.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private String site() {
        return "http://127.0.0.1:" + jira.getAddress().getPort();
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String token, Object body)
            throws Exception {
        req.header("Authorization", token);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(new tools.jackson.databind.ObjectMapper().writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    private void awaitJob(UUID jobId) throws Exception {
        for (int i = 0; i < 300; i++) {
            String s = jdbc.sql("SELECT status FROM job WHERE id = :id").param("id", jobId).query(String.class).single();
            if (s.equals("SUCCEEDED") || s.equals("FAILED")) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("job did not finish");
    }

    /** Pretends an hour has passed, queues the due scheduled reads and waits for them. Returns the jobs. */
    private List<UUID> runScheduled() throws Exception {
        jdbc.sql("UPDATE source SET last_checked_at = now() - interval '2 hours' WHERE id = :id")
                .param("id", UUID.fromString(sourceId)).update();
        int before = jdbc.sql("SELECT count(*) FROM job WHERE payload ->> 'scheduled' = 'true'").query(Integer.class).single();
        assertThat(sync.enqueueDue()).isEqualTo(1);
        List<UUID> jobs = jdbc.sql("SELECT id FROM job WHERE payload ->> 'scheduled' = 'true' ORDER BY created_at DESC LIMIT :n")
                .param("n", 1).query(UUID.class).list();
        assertThat(jdbc.sql("SELECT count(*) FROM job WHERE payload ->> 'scheduled' = 'true'").query(Integer.class).single())
                .isEqualTo(before + 1);
        for (UUID j : jobs) {
            awaitJob(j);
        }
        return jobs;
    }

    private void awaitHandoverStatus(String handoverId, String wanted) throws Exception {
        for (int i = 0; i < 300; i++) {
            if (wanted.equals(jdbc.sql("SELECT status FROM handover WHERE id = :id").param("id", UUID.fromString(handoverId))
                    .query(String.class).single())) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("handover never reached " + wanted);
    }

    @Test
    @Order(1)
    void jiraIsConnectedWithAnApiTokenAndTestedBeforeUse() throws Exception {
        send(put("/api/admin/integrations/JIRA"), admin, Map.of("secret", "{\"site\":\"" + site() + "\",\"email\":\"neha@annapurna.test\",\"token\":\"atl-token-123456\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hint").value("neha@annapurna.test at 127.0.0.1"));
        send(post("/api/admin/integrations/JIRA/test"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Neha Kulkarni")));
        // Shape errors are caught before anything is stored.
        send(put("/api/admin/integrations/JIRA"), admin, Map.of("secret", "just-a-token")).andExpect(status().isBadRequest());
        send(put("/api/admin/integrations/JIRA"), neha, Map.of("secret", "{}")).andExpect(status().isForbidden());
    }

    @Test
    @Order(2)
    void aJiraSourceIsReadAndOpenIssuesBecomeSignals() throws Exception {
        String body = mvc.perform(multipart("/api/projects/" + demo.projectId + "/sources").header("Authorization", neha)
                        .param("type", "JIRA").param("title", "Jira ANN").param("sensitivity", "INTERNAL")
                        .param("project", "ann").param("authorisationAttested", "true"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.source.autoSync").value(true))
                .andReturn().getResponse().getContentAsString();
        sourceId = JsonPath.read(body, "$.source.id");
        assertThat(demo.awaitJob(neha, JsonPath.read(body, "$.jobId"))).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("SELECT count(*) FROM document WHERE source_id = :s AND content_hash IS NOT NULL")
                .param("s", UUID.fromString(sourceId)).query(Integer.class).single()).isEqualTo(2);
        String text = jdbc.sql("SELECT string_agg(k.text, ' ') FROM chunk k JOIN document d ON d.id = k.document_id WHERE d.source_id = :s")
                .param("s", UUID.fromString(sourceId)).query(String.class).single();
        assertThat(text).contains("Issue key: ANN-12").contains("Assignee: Priya Sharma")
                .contains("Description: Webhook retries must be idempotent.");

        UUID priya = jdbc.sql("SELECT id FROM app_user WHERE email = 'priya@annapurna.test'").query(UUID.class).single();
        String created = mvc.perform(post("/api/projects/" + demo.projectId + "/handovers").header("Authorization", neha)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departingUserId\":\"" + priya + "\",\"title\":\"Auto sync handover\",\"scenario\":\"PLANNED\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        demo.handoverId = JsonPath.read(created, "$.id");
        demo.reconstruct();
        List<Map<String, Object>> claims = JsonPath.read(
                demo.getJson(neha, "/api/handovers/" + demo.handoverId + "/claims?size=200"), "$.items");
        assertThat(claims).anyMatch(c -> c.get("title").toString().contains("ANN-12")); // open and Priya's: a signal
        assertThat(claims).noneMatch(c -> c.get("title").toString().contains("ANN-10")); // done: not a signal
        // The handover is now in review and has had a run: the setting for the next tests.
        assertThat(jdbc.sql("SELECT status FROM handover WHERE id = :id").param("id", UUID.fromString(demo.handoverId))
                .query(String.class).single()).isEqualTo("IN_REVIEW");
    }

    @Test
    @Order(3)
    void aScheduledCheckWithNothingNewChangesNothingAndDoesNotReindex() throws Exception {
        UUID doc = jdbc.sql("SELECT id FROM document WHERE source_id = :s AND external_ref = 'ANN-12'")
                .param("s", UUID.fromString(sourceId)).query(UUID.class).single();
        var changedBefore = jdbc.sql("SELECT last_changed_at FROM source WHERE id = :s").param("s", UUID.fromString(sourceId))
                .query(java.sql.Timestamp.class).single();
        int searchesBefore = searches.get();

        runScheduled();

        assertThat(searches.get()).isGreaterThan(searchesBefore); // it did look
        // Same document row (not deleted and re-created), same change time, no notice.
        assertThat(jdbc.sql("SELECT count(*) FROM document WHERE id = :d").param("d", doc).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT last_changed_at FROM source WHERE id = :s").param("s", UUID.fromString(sourceId))
                .query(java.sql.Timestamp.class).single()).isEqualTo(changedBefore);
        send(get("/api/handovers/" + demo.handoverId + "/notices"), neha, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.sql("SELECT last_checked_at > now() - interval '1 minute' FROM source WHERE id = :s")
                .param("s", UUID.fromString(sourceId)).query(Boolean.class).single()).isTrue();
    }

    @Test
    @Order(4)
    void newEvidenceRaisesANoticeAndAHandoverInReviewIsReconstructedAgain() throws Exception {
        issues.set(List.of(ISSUE_PRIYA, ISSUE_DONE, ISSUE_NEW));
        int runsBefore = jdbc.sql("SELECT count(*) FROM extraction_run WHERE handover_id = :h")
                .param("h", UUID.fromString(demo.handoverId)).query(Integer.class).single();

        runScheduled();

        assertThat(jdbc.sql("SELECT count(*) FROM document WHERE source_id = :s").param("s", UUID.fromString(sourceId))
                .query(Integer.class).single()).isEqualTo(3);
        List<Map<String, Object>> notices = JsonPath.read(
                send(get("/api/handovers/" + demo.handoverId + "/notices"), neha, null).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), "$");
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0).get("kind")).isEqualTo("SOURCE_CHANGED");
        assertThat(notices.get(0).get("message").toString()).contains("Jira ANN").contains("1 new").contains("ANN-31")
                .contains("reconstructing the handover again");

        // The handover in review was reconstructed by itself, and the new issue is now a claim.
        awaitHandoverStatus(demo.handoverId, "IN_REVIEW");
        assertThat(jdbc.sql("SELECT count(*) FROM extraction_run WHERE handover_id = :h")
                .param("h", UUID.fromString(demo.handoverId)).query(Integer.class).single()).isEqualTo(runsBefore + 1);
        List<Map<String, Object>> claims = JsonPath.read(
                demo.getJson(neha, "/api/handovers/" + demo.handoverId + "/claims?size=200"), "$.items");
        assertThat(claims).anyMatch(c -> c.get("title").toString().contains("ANN-31"));

        // The audit log says who did it: nobody, the scheduler.
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'SOURCE_CHANGE_DETECTED' AND actor_id IS NULL AND entity_id = :s")
                .param("s", UUID.fromString(sourceId)).query(Integer.class).single()).isEqualTo(1);

        // A second change updates the same notice in place instead of piling up.
        issues.set(List.of(ISSUE_PRIYA, ISSUE_NEW));
        runScheduled();
        awaitHandoverStatus(demo.handoverId, "IN_REVIEW");
        assertThat(JsonPath.<List<?>>read(send(get("/api/handovers/" + demo.handoverId + "/notices"), neha, null)
                .andReturn().getResponse().getContentAsString(), "$")).hasSize(1);
    }

    @Test
    @Order(5)
    void anApprovedHandoverIsNotReopenedItsLeadIsToldToOpenTheNextVersion() throws Exception {
        jdbc.sql("UPDATE handover SET status = 'APPROVED', current_version_no = 2 WHERE id = :id")
                .param("id", UUID.fromString(demo.handoverId)).update();
        int runsBefore = jdbc.sql("SELECT count(*) FROM extraction_run WHERE handover_id = :h")
                .param("h", UUID.fromString(demo.handoverId)).query(Integer.class).single();
        jdbc.sql("UPDATE handover_notice SET dismissed_at = now() WHERE handover_id = :h").param("h", UUID.fromString(demo.handoverId)).update();
        issues.set(List.of(ISSUE_PRIYA, ISSUE_NEW, ISSUE_DONE));

        runScheduled();

        assertThat(jdbc.sql("SELECT status FROM handover WHERE id = :id").param("id", UUID.fromString(demo.handoverId))
                .query(String.class).single()).isEqualTo("APPROVED");
        assertThat(jdbc.sql("SELECT count(*) FROM extraction_run WHERE handover_id = :h")
                .param("h", UUID.fromString(demo.handoverId)).query(Integer.class).single()).isEqualTo(runsBefore);
        String body = send(get("/api/handovers/" + demo.handoverId + "/notices"), neha, null).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$[0].message")).contains("Open the next version").contains("version 2");
        // Starting the next version answers the notice.
        send(post("/api/handovers/" + demo.handoverId + "/reopen"), neha, null).andExpect(status().isNoContent());
        send(get("/api/handovers/" + demo.handoverId + "/notices"), neha, null).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @Order(6)
    void aFailingReadKeepsTheLastSnapshotTellsTheLeadAndClearsWhenItWorksAgain() throws Exception {
        status.set(401);
        runScheduled();
        // Still READY with its documents, so reconstruction keeps working; the reason is shown beside it.
        String src = mvc.perform(get("/api/sources/" + sourceId).header("Authorization", neha)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(src, "$.status")).isEqualTo("READY");
        assertThat(JsonPath.<String>read(src, "$.syncError")).contains("refused access");
        assertThat(JsonPath.<Integer>read(src, "$.documentCount")).isPositive();
        String notices = send(get("/api/handovers/" + demo.handoverId + "/notices"), neha, null).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(notices, "$[0].kind")).isEqualTo("SYNC_FAILED");

        status.set(200);
        runScheduled();
        send(get("/api/handovers/" + demo.handoverId + "/notices"), neha, null).andExpect(jsonPath("$.length()").value(0));
        assertThat(JsonPath.<Object>read(mvc.perform(get("/api/sources/" + sourceId).header("Authorization", neha)).andReturn()
                .getResponse().getContentAsString(), "$.syncError")).isNull();
    }

    @Test
    @Order(7)
    void autoSyncCanBeSwitchedOffByTheLeadOnly() throws Exception {
        send(patch("/api/sources/" + sourceId), rahul, Map.of("autoSync", false)).andExpect(status().isForbidden());
        send(patch("/api/sources/" + sourceId), neha, Map.of("autoSync", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.autoSync").value(false));
        jdbc.sql("UPDATE source SET last_checked_at = now() - interval '5 hours' WHERE id = :id").param("id", UUID.fromString(sourceId)).update();
        assertThat(sync.enqueueDue()).isZero(); // off means not read in the background
        send(patch("/api/sources/" + sourceId), neha, Map.of("autoSync", "yes")).andExpect(status().isBadRequest());
        send(patch("/api/sources/" + sourceId), neha, Map.of("autoSync", true)).andExpect(jsonPath("$.autoSync").value(true));
    }

    @Test
    @Order(8)
    void noticesAreForTheLeadAndCanBeDismissed() throws Exception {
        send(get("/api/handovers/" + demo.handoverId + "/notices"), rahul, null).andExpect(status().isForbidden());
        UUID notice = jdbc.sql("INSERT INTO handover_notice (handover_id, kind, message) VALUES (:h, 'SOURCE_CHANGED', 'x') RETURNING id")
                .param("h", UUID.fromString(demo.handoverId)).query(UUID.class).single();
        send(post("/api/handovers/" + demo.handoverId + "/notices/" + notice + "/dismiss"), rahul, null).andExpect(status().isForbidden());
        send(post("/api/handovers/" + demo.handoverId + "/notices/" + notice + "/dismiss"), neha, null).andExpect(status().isNoContent());
        send(get("/api/handovers/" + demo.handoverId + "/notices"), neha, null).andExpect(jsonPath("$.length()").value(0));
    }
}
