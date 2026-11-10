package com.bramhastra.baton.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.bramhastra.baton.DemoFixture;
import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.jayway.jsonpath.JsonPath;

/**
 * A lead's conflict decision stays made: re-accepting the losing claim and reconstructing again must not reopen
 * the conflict the other way round, and two claims must never end up superseding each other (both shown as fact).
 */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
class ConflictDecisionTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Value("${baton.demo.password}")
    String password;

    @Test
    void aResolvedConflictStaysResolvedAfterReacceptingAndReconstructing() throws Exception {
        DemoFixture demo = new DemoFixture(mvc, jdbc, password).createProject("Annapurna (conflict decisions)").loadSourcesAndReconstruct();
        String neha = demo.login("neha@annapurna.test");
        String hid = demo.handoverId;
        Map<String, Object> nov29 = launch(demo, neha, "2026-11-29");
        Map<String, Object> nov15 = launch(demo, neha, "2026-11-15");

        mvc.perform(post("/api/claims/" + nov29.get("id") + "/resolve-conflict").header("Authorization", neha)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"otherClaimId\":\"" + nov15.get("id") + "\"}"))
                .andExpect(status().isOk());

        // The lead changes their mind about the rejected claim and accepts it again, then reconstructs.
        for (String state : List.of("PENDING", "ACCEPTED")) {
            Map<String, Object> c = launch(demo, neha, "2026-11-15");
            mvc.perform(patch("/api/claims/" + c.get("id")).header("Authorization", neha).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":" + c.get("version") + ",\"reviewState\":\"" + state + "\"}"))
                    .andExpect(status().isOk());
        }
        demo.reconstruct();

        // No conflict reopens, so it cannot be resolved the other way round...
        String body = demo.getJson(neha, "/api/handovers/" + hid + "/claims?size=200");
        List<String> statuses = JsonPath.read(body, "$.items[?(@.subject == 'public launch date')].status");
        assertThat(statuses).doesNotContain("CONFLICTING");
        mvc.perform(post("/api/claims/" + nov15.get("id") + "/resolve-conflict").header("Authorization", neha)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"otherClaimId\":\"" + nov29.get("id") + "\"}"))
                .andExpect(status().isConflict());
        // ...the newer date is still current, and the old one is stale (kept in history, out of the handover).
        assertThat(launch(demo, neha, "2026-11-29").get("status")).isNotEqualTo("STALE");
        assertThat(launch(demo, neha, "2026-11-15").get("status")).isEqualTo("STALE");
        Integer mutual = jdbc.sql("""
                SELECT count(*) FROM claim_link a JOIN claim_link b
                  ON a.from_claim_id = b.to_claim_id AND a.to_claim_id = b.from_claim_id
                WHERE a.kind = 'SUPERSEDES' AND b.kind = 'SUPERSEDES'
                """).query(Integer.class).single();
        assertThat(mutual).isZero();
    }

    private static Map<String, Object> launch(DemoFixture demo, String token, String value) throws Exception {
        String body = demo.getJson(token, "/api/handovers/" + demo.handoverId + "/claims?size=200");
        List<Map<String, Object>> found = JsonPath.read(body,
                "$.items[?(@.subject == 'public launch date' && @.attributes.value == '" + value + "')]");
        assertThat(found).as(value).isNotEmpty();
        return found.get(0);
    }
}
