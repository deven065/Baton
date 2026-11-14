package com.bramhastra.baton.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.bramhastra.baton.DemoFixture;
import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.jayway.jsonpath.JsonPath;

/**
 * The lead writes their own exit-interview questions: they appear first, the person leaving answers them like the
 * generated ones, a new reconstruction keeps them, and only the lead can add or take them back.
 */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
class LeadQuestionsTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Value("${baton.demo.password}")
    String demoPassword;

    private ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        req.header("Authorization", token);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(new tools.jackson.databind.ObjectMapper().writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    @Test
    void theLeadAddsQuestionsThePersonLeavingAnswersAndReconstructionKeepsThem() throws Exception {
        DemoFixture demo = new DemoFixture(mvc, jdbc, demoPassword).createProject("Own questions").loadSourcesAndReconstruct();
        String neha = demo.login("neha@annapurna.test");
        String priya = demo.login("priya@annapurna.test");
        String arjun = demo.login("arjun@annapurna.test");
        String url = "/api/handovers/" + demo.handoverId + "/gaps";

        String created = send(post(url), neha, Map.of("question", "Who at the vendor do we call when the spreadsheet export breaks?"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.custom").value(true))
                .andExpect(jsonPath("$.kind").value("OTHER")).andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        String gapId = JsonPath.read(created, "$.id");

        // It leads the list for the person leaving, and generated questions are marked as not the lead's own.
        List<Map<String, Object>> list = JsonPath.read(demo.getJson(priya, url), "$");
        assertThat(list.get(0).get("id")).isEqualTo(gapId);
        assertThat(list.stream().filter(g -> !Boolean.TRUE.equals(g.get("custom")))).isNotEmpty();

        // Rules: three to 500 characters, no keys, no repeats, lead only.
        send(post(url), neha, Map.of("question", "no")).andExpect(status().isBadRequest());
        send(post(url), neha, Map.of("question", "x".repeat(501))).andExpect(status().isBadRequest());
        send(post(url), neha, Map.of("question", "The admin password is Hunter2024! right?")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.question").value(org.hamcrest.Matchers.containsString("key or password")));
        send(post(url), neha, Map.of("question", "  who at the VENDOR do we call when the spreadsheet export breaks?  "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.question").value("That question is already on the list"));
        send(post(url), priya, Map.of("question", "A question from the person leaving")).andExpect(status().isForbidden());
        send(post(url), arjun, Map.of("question", "A question from a successor")).andExpect(status().isForbidden());

        // A new reconstruction regenerates its own questions but keeps the lead's.
        demo.reconstruct();
        List<Map<String, Object>> after = JsonPath.read(demo.getJson(priya, url), "$");
        assertThat(after).anyMatch(g -> g.get("id").equals(gapId) && Boolean.TRUE.equals(g.get("custom")));

        // The person leaving answers it like any other question; the answer is stored as cited testimony.
        send(post("/api/gaps/" + gapId + "/answer"), priya, Map.of("answer", "Call the operations desk first; the vendor only answers them.", "sensitivity", "INTERNAL"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ANSWERED"));

        // Taking questions back: only your own, only while unanswered.
        String second = send(post(url), neha, Map.of("question", "Where is the domain registered and who pays for it?"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String secondId = JsonPath.read(second, "$.id");
        send(delete(url + "/" + secondId), priya, null).andExpect(status().isForbidden());
        send(delete(url + "/" + gapId), neha, null).andExpect(status().isNotFound()); // answered: stays on record
        send(delete(url + "/" + secondId), neha, null).andExpect(status().isNoContent());
        String generated = ((List<Map<String, Object>>) JsonPath.read(demo.getJson(neha, url), "$")).stream()
                .filter(g -> !Boolean.TRUE.equals(g.get("custom")) && "OPEN".equals(g.get("status"))).findFirst().orElseThrow().get("id").toString();
        send(delete(url + "/" + generated), neha, null).andExpect(status().isConflict());

        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action IN ('GAP_ADDED', 'GAP_REMOVED') AND entity_type = 'gap_question'")
                .query(Integer.class).single()).isGreaterThanOrEqualTo(3);
    }
}
