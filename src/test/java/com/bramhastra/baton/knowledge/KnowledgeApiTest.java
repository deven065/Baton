package com.bramhastra.baton.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.bramhastra.baton.DemoFixture;
import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.jayway.jsonpath.JsonPath;

/**
 * The always-on knowledge layer end to end: decisions with their why captured from sources, the lead's review, the
 * expert finder with its single-point-of-failure verdict, risk alerts, and cited Q&A that admits when it does not know.
 * The sample sources are in demo/knowledge.
 */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Value("${baton.demo.password}")
    String demoPassword;

    DemoFixture demo;
    String neha;
    String priya;
    String arjun;
    String rahul;
    String admin;
    String base;

    @BeforeAll
    void setUp() throws Exception {
        demo = new DemoFixture(mvc, jdbc, demoPassword).createProject("Always-on knowledge");
        neha = demo.login("neha@annapurna.test");
        priya = demo.login("priya@annapurna.test");
        arjun = demo.login("arjun@annapurna.test");
        rahul = demo.login("rahul@annapurna.test");
        admin = demo.login("admin@annapurna.test");
        base = "/api/projects/" + demo.projectId;
        for (String f : List.of("2026-10-12-pr-41-order-queue.md", "2026-11-05-payments-chat.md", "2026-02-01-payments-review.md",
                "2026-11-10-launch-and-push.md")) {
            upload(f, "INTERNAL");
        }
        upload("2026-10-20-vendor-contract-renewal.md", "RESTRICTED");
    }

    private void upload(String file, String sensitivity) throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("demo", "knowledge", file));
        var req = multipart(base + "/sources").file(new MockMultipartFile("file", file, "text/markdown", bytes));
        req.header("Authorization", neha).param("type", "DOCUMENT").param("title", file.replace(".md", "")).param("sensitivity", sensitivity)
                .param("authorisationAttested", "true");
        String body = mvc.perform(req).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(demo.awaitJob(neha, JsonPath.read(body, "$.jobId"))).isEqualTo("SUCCEEDED");
    }

    private ResultActions as(String token, String method, String path, Object body) throws Exception {
        var req = switch (method) {
            case "GET" -> get(path);
            case "PATCH" -> patch(path);
            default -> post(path);
        };
        req.header("Authorization", token);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(new tools.jackson.databind.ObjectMapper().writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    private String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    private List<Map<String, Object>> decisions(String token, String query) throws Exception {
        return JsonPath.read(body(as(token, "GET", base + "/decisions" + query, null).andExpect(status().isOk())), "$.items");
    }

    private Map<String, Object> decision(String token, String titleContains) throws Exception {
        return decisions(token, "").stream().filter(d -> d.get("title").toString().contains(titleContains)).findFirst()
                .orElseThrow(() -> new AssertionError("no decision containing " + titleContains));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ask(String token, String question) throws Exception {
        return JsonPath.read(body(as(token, "POST", base + "/ask", Map.of("question", question)).andExpect(status().isOk())), "$");
    }

    // ───────────── 1. decisions with the why, captured as sources are read ─────────────

    @Test
    @Order(1)
    void decisionsAreCapturedFromEverySourceWithTheirReasonsAndWhoMadeThem() throws Exception {
        Map<String, Object> kafka = decision(neha, "Kafka over RabbitMQ");
        assertThat(kafka.get("reasoning")).isEqualTo("because we need message replay and ordering per key");
        assertThat(kafka.get("decidedBy")).isEqualTo("Arjun Mehta");
        assertThat(kafka.get("topic")).isEqualTo("Kafka");
        assertThat((List<String>) kafka.get("alternatives")).containsExactly("RabbitMQ");
        assertThat(kafka.get("decidedAt").toString()).startsWith("2026-10-12T10:20");
        assertThat(kafka.get("hasReason")).isEqualTo(true);

        assertThat(decision(neha, "OneSignal").get("reasoning")).isEqualTo("the Firebase free-tier quota ran out during the Diwali food drive");
        Map<String, Object> launch = decision(neha, "public launch to Nov 29");
        assertThat(launch.get("reasoning").toString()).startsWith("after canteen feedback");
        assertThat(launch.get("decidedBy")).isEqualTo("Neha Kulkarni");
        assertThat(decision(neha, "Razorpay").get("reasoning")).isEqualTo("because the club has no international customers");

        // Nothing is invented: every quote and every reason is words from a stored passage.
        for (Map<String, Object> d : decisions(neha, "")) {
            assertThat(jdbc.sql("SELECT count(*) FROM chunk WHERE position(:q in text) > 0").param("q", d.get("quote").toString())
                    .query(Integer.class).single()).as("quote of " + d.get("title")).isPositive();
            if (d.get("reasoning") != null) {
                assertThat(jdbc.sql("SELECT count(*) FROM chunk WHERE position(:q in text) > 0").param("q", d.get("reasoning").toString().replace("…", ""))
                        .query(Integer.class).single()).as("reason of " + d.get("title")).isPositive();
            }
        }

        // Filters and the menu of topics and people.
        assertThat(decisions(neha, "?q=kafka")).hasSize(1);
        assertThat(decisions(neha, "?person=Neha Kulkarni")).hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(d -> assertThat(d.get("decidedBy")).isEqualTo("Neha Kulkarni"));
        String log = body(as(neha, "GET", base + "/decisions", null));
        assertThat((List<String>) JsonPath.read(log, "$.topics[*].name")).contains("Kafka", "OneSignal");
        assertThat((List<String>) JsonPath.read(log, "$.people[*].name")).contains("Arjun Mehta", "Priya Sharma");
        assertThat(((Number) JsonPath.read(log, "$.withReason")).intValue()).isGreaterThanOrEqualTo(4);

        // A decision cut from a restricted source is visible only to people cleared for it.
        assertThat(decisions(neha, "?q=sharma")).extracting(d -> d.get("title").toString()).anyMatch(t -> t.toLowerCase().contains("renew the contract"));
        assertThat(decisions(rahul, "?q=sharma")).isEmpty();
        assertThat(decisions(priya, "")).hasSizeLessThan(decisions(neha, "").size());

        // Admins manage the workspace, not the project's knowledge; strangers find nothing.
        as(admin, "GET", base + "/decisions", null).andExpect(status().isNotFound());
    }

    // ───────────── 2. the lead's review survives reading the sources again ─────────────

    @Test
    @Order(2)
    void theLeadConfirmsOrRejectsAndTheChoiceSurvivesCapturingAgain() throws Exception {
        String id = decision(neha, "Kafka over RabbitMQ").get("id").toString();
        as(priya, "PATCH", "/api/decisions/" + id, Map.of("review", "CONFIRMED")).andExpect(status().isForbidden());
        as(neha, "PATCH", "/api/decisions/" + id, Map.of("review", "MAYBE")).andExpect(status().isBadRequest());
        as(neha, "PATCH", "/api/decisions/" + id, Map.of("review", "CONFIRMED")).andExpect(status().isOk());
        assertThat(decisions(neha, "").get(0).get("id")).isEqualTo(id); // confirmed ones come first
        assertThat(decision(neha, "Kafka over RabbitMQ").get("review")).isEqualTo("CONFIRMED");

        // Reading every source again keeps the same decision, and the lead's call with it.
        as(priya, "POST", base + "/capture/scan", null).andExpect(status().isForbidden());
        as(neha, "POST", base + "/capture/scan", null).andExpect(status().isOk());
        assertThat(decision(neha, "Kafka over RabbitMQ")).containsEntry("id", id).containsEntry("review", "CONFIRMED");

        // Rejecting hides it from the log (and from every answer) until it is put back.
        as(neha, "PATCH", "/api/decisions/" + id, Map.of("review", "REJECTED")).andExpect(status().isOk());
        assertThat(decisions(neha, "?q=kafka")).isEmpty();
        assertThat(decisions(neha, "?q=kafka&rejected=true")).hasSize(1);
        as(neha, "PATCH", "/api/decisions/" + id, Map.of("review", "AUTO")).andExpect(status().isOk());
        assertThat(decisions(neha, "?q=kafka")).hasSize(1);
        // A decision Rahul cannot see does not exist for a lead who cannot see it either (here: it is simply not found).
        as(neha, "PATCH", "/api/decisions/" + java.util.UUID.randomUUID(), Map.of("review", "CONFIRMED")).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action IN ('DECISION_CONFIRMED', 'DECISION_REJECTED', 'DECISION_AUTO')")
                .query(Integer.class).single()).isGreaterThanOrEqualTo(3);
    }

    // ───────────── 3. always-on capture ─────────────

    @Test
    @Order(3)
    void captureStatusShowsWhatIsWatchedAndSourcesNeverReadForDecisionsAreCaughtUp() throws Exception {
        String status = body(as(priya, "GET", base + "/capture", null).andExpect(status().isOk()));
        assertThat((List<String>) JsonPath.read(status, "$.sources[*].title")).hasSize(4); // the restricted one is hidden from Priya
        assertThat(((Number) JsonPath.read(status, "$.decisions")).intValue()).isGreaterThanOrEqualTo(4);
        assertThat(((Number) JsonPath.read(status, "$.decisionsThisWeek")).intValue()).isGreaterThanOrEqualTo(4);
        assertThat((List<?>) JsonPath.read(status, "$.recent")).isNotEmpty();
        assertThat(((Number) JsonPath.read(status, "$.checkEveryMinutes")).intValue()).isPositive();
        assertThat(JsonPath.<Boolean>read(status, "$.watching")).isFalse(); // only uploaded files here, nothing live
        assertThat((List<String>) JsonPath.read(body(as(neha, "GET", base + "/capture", null)), "$.sources[*].title")).hasSize(5);

        // Knowledge read before this layer existed is mined the next time anyone looks.
        int before = decisions(neha, "").size();
        jdbc.sql("DELETE FROM decision WHERE project_id = :p").param("p", demo.projectId).update();
        jdbc.sql("UPDATE source SET mined_at = NULL WHERE project_id = :p").param("p", demo.projectId).update();
        assertThat(decisions(neha, "")).hasSize(before);
        as(admin, "GET", base + "/capture", null).andExpect(status().isNotFound());
    }

    // ───────────── 4. who knows what ─────────────

    @Test
    @Order(4)
    void theFinderRanksPeopleByWhatTheyWroteAndFlagsASinglePointOfFailure() throws Exception {
        String r = body(as(rahul, "GET", base + "/experts?q=Who understands payments?", null)
                .andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(r, "$.experts[0].name")).isEqualTo("Priya Sharma");
        assertThat(JsonPath.<Double>read(r, "$.experts[0].share")).isGreaterThan(0.8);
        assertThat(JsonPath.<Boolean>read(r, "$.experts[0].onProject")).isTrue();
        assertThat(JsonPath.<String>read(r, "$.experts[0].evidence[0].quote").toLowerCase()).contains("payments");
        assertThat((List<String>) JsonPath.read(r, "$.experts[*].name")).contains("Arjun Mehta"); // reviewed an early prototype, long ago
        assertThat(JsonPath.<Boolean>read(r, "$.verdict.singlePointOfFailure")).isTrue();
        assertThat(JsonPath.<String>read(r, "$.verdict.level")).isIn("HIGH", "MEDIUM");
        assertThat(JsonPath.<String>read(r, "$.verdict.message")).contains("Priya");

        // The person who decided something is an expert on it.
        String k = body(as(neha, "GET", base + "/experts?q=kafka", null));
        assertThat(JsonPath.<String>read(k, "$.experts[0].name")).isEqualTo("Arjun Mehta");
        assertThat((List<String>) JsonPath.read(k, "$.experts[0].evidence[*].why")).anyMatch(w -> w != null && w.startsWith("Made the decision"));

        // Nothing written about it, or nothing the asker may see, means no names.
        assertThat((List<?>) JsonPath.read(body(as(neha, "GET", base + "/experts?q=kubernetes", null)), "$.experts")).isEmpty();
        assertThat(JsonPath.<String>read(body(as(neha, "GET", base + "/experts?q=kubernetes", null)), "$.verdict.level")).isEqualTo("NONE");
        assertThat((List<?>) JsonPath.read(body(as(neha, "GET", base + "/experts?q=Who knows", null)), "$.terms")).isEmpty();
        assertThat((List<?>) JsonPath.read(body(as(rahul, "GET", base + "/experts?q=sharma", null)), "$.experts")).isEmpty();
        assertThat((List<?>) JsonPath.read(body(as(neha, "GET", base + "/experts?q=sharma", null)), "$.experts")).isNotEmpty();
        as(admin, "GET", base + "/experts?q=payments", null).andExpect(status().isNotFound());
    }

    // ───────────── 5. cited answers ─────────────

    @Test
    @Order(5)
    void answersCiteTheirSourcesAndAdmitWhenTheyDoNotKnow() throws Exception {
        Map<String, Object> why = ask(rahul, "Why did we choose Kafka over RabbitMQ?");
        assertThat(why.get("found")).isEqualTo(true);
        assertThat(why.get("confidence")).isIn("MEDIUM", "HIGH");
        assertThat(why.get("answer").toString()).contains("message replay and ordering per key").contains("Arjun Mehta");
        List<Map<String, Object>> sources = (List<Map<String, Object>>) why.get("sources");
        assertThat(sources.get(0)).containsEntry("kind", "DECISION").containsEntry("n", 1);
        assertThat(sources.get(0).get("quote").toString()).contains("Kafka over RabbitMQ");
        assertThat(sources.get(0).get("source").toString()).contains("pr-41-order-queue");

        assertThat(ask(neha, "Why are we using OneSignal for push?").get("answer").toString()).contains("free-tier quota");
        assertThat(ask(neha, "Why is the public launch on Nov 29?").get("answer").toString()).contains("canteen feedback");

        // One loose passage is not enough to state as fact: say so, and say who to ask.
        Map<String, Object> thin = ask(neha, "Who restarts the menu cache?");
        assertThat(thin.get("confidence")).isEqualTo("LOW");
        assertThat(thin.get("answer").toString()).startsWith("I'm not sure.").contains("check it with Rahul");
        assertThat(((Map<String, Object>) thin.get("askInstead")).get("name")).isEqualTo("Rahul Desai");

        // Nothing in the sources: an honest "I don't know", never a guess.
        Map<String, Object> none = ask(neha, "What is our Kubernetes cluster size?");
        assertThat(none).containsEntry("found", false).containsEntry("confidence", "NONE");
        assertThat(none.get("answer").toString()).startsWith("I don't know");
        assertThat((List<?>) none.get("sources")).isEmpty();

        // What the asker may not see is never cited, and never even hinted at.
        Map<String, Object> hidden = ask(rahul, "Why did we renew the contract with Sharma Foods?");
        assertThat(hidden).containsEntry("found", false);
        assertThat((List<?>) hidden.get("sources")).isEmpty();
        assertThat(hidden.toString()).doesNotContain("spreadsheet export");
        Map<String, Object> visible = ask(neha, "Why did we renew the contract with Sharma Foods?");
        assertThat(visible).containsEntry("found", true);
        assertThat(visible.get("answer").toString()).contains("spreadsheet export");

        as(neha, "POST", base + "/ask", Map.of("question", "hi")).andExpect(status().isBadRequest());
        as(admin, "POST", base + "/ask", Map.of("question", "Why Kafka?")).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'PROJECT_ASKED'").query(Integer.class).single()).isGreaterThanOrEqualTo(5);
    }

    // ───────────── 6. risk alerts (last, because it starts a handover) ─────────────

    @Test
    @Order(6)
    void alertsSayWhoIsTheOnlyPersonToHaveTouchedWhatAndEscalateWhenTheyLeave() throws Exception {
        String url = base + "/risk/alerts";
        as(priya, "GET", url, null).andExpect(status().isForbidden());
        as(admin, "GET", url, null).andExpect(status().isOk());

        String before = body(as(neha, "GET", url + "?days=180", null).andExpect(status().isOk()));
        List<Map<String, Object>> items = JsonPath.read(before, "$.items");
        Map<String, Object> sole = items.stream().filter(a -> "SOLE_CONTRIBUTOR".equals(a.get("kind")) && "Priya Sharma".equals(a.get("person")))
                .findFirst().orElseThrow(() -> new AssertionError("no alert about Priya: " + items));
        assertThat(sole.get("text").toString()).startsWith("Only Priya has touched").contains("in the last 6 months").contains("passages");
        assertThat(sole.get("severity")).isIn("MEDIUM", "HIGH");
        assertThat(sole.get("leaving")).isEqualTo(false);
        assertThat(sole.get("suggestion").toString()).contains("Nobody else has written about");
        assertThat((List<?>) sole.get("evidence")).isNotEmpty();

        // Priya hands in her notice: the same finding becomes critical, and the cost of losing her is spelled out.
        as(neha, "POST", "/api/projects/" + demo.projectId + "/handovers",
                Map.of("departingUserId", jdbc.sql("SELECT id FROM app_user WHERE email = 'priya@annapurna.test'").query(String.class).single(),
                        "title", "Priya leaving", "scenario", "PLANNED")).andExpect(status().isCreated());
        List<Map<String, Object>> after = JsonPath.read(body(as(neha, "GET", url, null)), "$.items");
        assertThat(after.stream().filter(a -> "SOLE_CONTRIBUTOR".equals(a.get("kind")) && "Priya Sharma".equals(a.get("person"))).findFirst().orElseThrow())
                .containsEntry("severity", "CRITICAL").containsEntry("leaving", true);
        assertThat(after.stream().filter(a -> "LEAVING_COVERAGE".equals(a.get("kind"))).findFirst().orElseThrow().get("text").toString())
                .startsWith("Priya is leaving, and ").contains("lose their only contributor");
        assertThat(after.get(0).get("severity")).isEqualTo("CRITICAL"); // the worst first
        // The finder now says it too.
        assertThat(JsonPath.<String>read(body(as(neha, "GET", base + "/experts?q=payments", null)), "$.verdict.level")).isEqualTo("CRITICAL");
    }
}
