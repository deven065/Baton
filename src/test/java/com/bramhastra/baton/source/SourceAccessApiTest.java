package com.bramhastra.baton.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import com.bramhastra.baton.DemoFixture;
import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.jayway.jsonpath.JsonPath;

/** Per-team access control: who can see which source, why not, and changing it everywhere at once. */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
class SourceAccessApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Value("${baton.demo.password}")
    String demoPassword;

    private ResultActions patchSource(String token, String id, Object body) throws Exception {
        return mvc.perform(patch("/api/sources/" + id).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(new tools.jackson.databind.ObjectMapper().writeValueAsString(body)));
    }

    @Test
    void theLeadSeesWhoCanSeeWhatAndChangesItEverywhere() throws Exception {
        DemoFixture demo = new DemoFixture(mvc, jdbc, demoPassword).createProject("Team access").loadSourcesAndReconstruct();
        String neha = demo.login("neha@annapurna.test");
        String priya = demo.login("priya@annapurna.test");
        String rahul = demo.login("rahul@annapurna.test");
        String url = "/api/projects/" + demo.projectId + "/access";

        String map = demo.getJson(neha, url);
        String archId = ((List<String>) JsonPath.read(map, "$.sources[?(@.title == 'Architecture notes')].id")).get(0);
        String rahulId = ((List<String>) JsonPath.read(map, "$.people[?(@.name == 'Rahul Desai')].userId")).get(0);
        List<String> rahulSees = JsonPath.read(map, "$.people[?(@.name == 'Rahul Desai')].canSee[*]");
        assertThat(rahulSees).doesNotContain(archId);
        String why = JsonPath.read(map, "$.people[?(@.name == 'Rahul Desai')].blocked['" + archId + "']").toString();
        assertThat(why).contains("needs Confidential clearance");
        assertThat((List<String>) JsonPath.read(map, "$.teams")).isNotEmpty();

        // Only the lead sees the map or changes access.
        mvc.perform(get(url).header("Authorization", priya)).andExpect(status().isForbidden());
        patchSource(priya, archId, Map.of("sensitivity", "PUBLIC")).andExpect(status().isForbidden());

        // Bad input, and changes that would lock the lead out, are refused.
        patchSource(neha, archId, Map.of("sensitivity", "TOP_SECRET")).andExpect(status().isBadRequest());
        patchSource(neha, archId, Map.of("aclGroups", List.of("no-such-team"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.aclGroups").value("No such team: no-such-team"));
        jdbc.sql("INSERT INTO user_group (name) VALUES ('outsiders') ON CONFLICT DO NOTHING").update();
        patchSource(neha, archId, Map.of("aclGroups", List.of("outsiders"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.sensitivity").value(org.hamcrest.Matchers.containsString("lose access")));

        // Opening it to everyone on the project changes the source, its documents, passages and claims at once.
        int rahulClaimsBefore = claimCount(demo, rahul);
        patchSource(neha, archId, Map.of("sensitivity", "INTERNAL", "aclGroups", List.of())).andExpect(status().isOk())
                .andExpect(jsonPath("$.sensitivity").value("INTERNAL"));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM chunk k JOIN document d ON d.id = k.document_id
                WHERE d.source_id = CAST(:s AS uuid) AND (k.sensitivity <> 1 OR cardinality(k.acl_groups) > 0)
                """).param("s", archId).query(Integer.class).single()).isZero();
        assertThat((List<String>) JsonPath.read(demo.getJson(neha, url), "$.people[?(@.userId == '" + rahulId + "')].canSee[*]"))
                .contains(archId);
        assertThat(claimCount(demo, rahul)).isGreaterThanOrEqualTo(rahulClaimsBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'SOURCE_ACCESS_CHANGED' AND entity_id = CAST(:s AS uuid)")
                .param("s", archId).query(Integer.class).single()).isEqualTo(1);
    }

    private int claimCount(DemoFixture demo, String token) throws Exception {
        String body = mvc.perform(get("/api/handovers/" + demo.handoverId + "/claims/stats").header("Authorization", token))
                .andReturn().getResponse().getContentAsString();
        try {
            return ((Number) JsonPath.read(body, "$.total")).intValue();
        } catch (RuntimeException e) {
            return 0; // not allowed to read the handover's claims
        }
    }
}
