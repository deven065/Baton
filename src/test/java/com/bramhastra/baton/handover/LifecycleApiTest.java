package com.bramhastra.baton.handover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.jayway.jsonpath.JsonPath;

/** Phase 3: approval gate, frozen versions, shares and what a successor sees. */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LifecycleApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Value("${baton.demo.password}")
    String demoPassword;

    DemoFixture demo;
    String neha;
    String hid;

    @BeforeAll
    void setUp() throws Exception {
        demo = new DemoFixture(mvc, jdbc, demoPassword).createProject("Annapurna (lifecycle test)").loadSourcesAndReconstruct();
        neha = demo.login("neha@annapurna.test");
        hid = demo.handoverId;
    }

    private ResultActions postJson(String token, String url, String json) throws Exception {
        return mvc.perform(post(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private UUID user(String email) {
        return jdbc.sql("SELECT id FROM app_user WHERE email = :e").param("e", email).query(UUID.class).single();
    }

    @Test
    void approveFreezeShareAndRecipientView() throws Exception {
        // The reconstructed demo has conflicts and unreviewed critical claims: approval is blocked.
        String gate = demo.getJson(neha, "/api/handovers/" + hid + "/approval");
        assertThat((Boolean) JsonPath.read(gate, "$.canApprove")).isFalse();
        postJson(neha, "/api/handovers/" + hid + "/approve", "{}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("APPROVAL_BLOCKED"));

        // A member who is not the lead cannot approve.
        String priya = demo.login("priya@annapurna.test");
        postJson(priya, "/api/handovers/" + hid + "/approve", "{}").andExpect(status().isForbidden());

        // Review everything: conflicts rejected, the rest accepted.
        UUID h = UUID.fromString(hid);
        jdbc.sql("UPDATE claim SET review_state = 'REJECTED' WHERE handover_id = :h AND status = 'CONFLICTING'")
                .param("h", h).update();
        jdbc.sql("UPDATE claim SET review_state = 'ACCEPTED' WHERE handover_id = :h AND review_state <> 'REJECTED'")
                .param("h", h).update();
        gate = demo.getJson(neha, "/api/handovers/" + hid + "/approval");
        assertThat((Boolean) JsonPath.read(gate, "$.canApprove")).as(gate).isTrue();

        String v1 = postJson(neha, "/api/handovers/" + hid + "/approve", "{}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.versionNo").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(v1, "$.sha256")).hasSize(64);
        assertThat((Integer) JsonPath.read(v1, "$.checklistItems")).isPositive();
        assertThat((String) JsonPath.read(demo.getJson(neha, "/api/handovers/" + hid), "$.status")).isEqualTo("APPROVED");
        assertThat((List<?>) JsonPath.read(demo.getJson(neha, "/api/handovers/" + hid + "/versions"), "$")).hasSize(1);
        assertThat((List<?>) JsonPath.read(demo.getJson(neha, "/api/handovers/" + hid + "/diff?from=1&to=0"), "$")).isEmpty();

        // The person leaving cannot receive their own handover.
        postJson(neha, "/api/handovers/" + hid + "/shares", "{\"recipientId\":\"" + user("priya@annapurna.test") + "\"}")
                .andExpect(status().isBadRequest());
        postJson(neha, "/api/handovers/" + hid + "/shares",
                "{\"recipientId\":\"" + user("arjun@annapurna.test") + "\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}")
                .andExpect(status().isBadRequest());

        String arjunShare = JsonPath.read(postJson(neha, "/api/handovers/" + hid + "/shares",
                "{\"recipientId\":\"" + user("arjun@annapurna.test") + "\",\"roleLabel\":\"Backend successor\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String rahulShare = JsonPath.read(postJson(neha, "/api/handovers/" + hid + "/shares",
                "{\"recipientId\":\"" + user("rahul@annapurna.test") + "\",\"roleLabel\":\"Design successor\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        postJson(neha, "/api/handovers/" + hid + "/shares", "{\"recipientId\":\"" + user("rahul@annapurna.test") + "\"}")
                .andExpect(status().isConflict());
        assertThat((String) JsonPath.read(demo.getJson(neha, "/api/handovers/" + hid), "$.status")).isEqualTo("SHARED");

        // Each successor sees the frozen version filtered by their own clearance and groups.
        String arjun = demo.login("arjun@annapurna.test");
        String rahul = demo.login("rahul@annapurna.test");
        String av = demo.getJson(arjun, "/api/shares/" + arjunShare);
        String rv = demo.getJson(rahul, "/api/shares/" + rahulShare);
        List<Integer> rahulLevels = JsonPath.read(rv, "$.claims[*].sensitivity");
        assertThat(rahulLevels).isNotEmpty().allMatch(l -> l <= 1);
        assertThat((Integer) JsonPath.read(rv, "$.hiddenCount")).isPositive();
        assertThat(((List<?>) JsonPath.read(av, "$.claims")).size()).isGreaterThan(rahulLevels.size());
        assertThat((String) JsonPath.read(av, "$.sha256")).isEqualTo(JsonPath.read(v1, "$.sha256"));

        // Nobody else can open a share, not even the lead.
        mvc.perform(get("/api/shares/" + rahulShare).header("Authorization", arjun)).andExpect(status().isNotFound());
        mvc.perform(get("/api/shares/" + rahulShare).header("Authorization", neha)).andExpect(status().isNotFound());

        // Checklist progress and acknowledgement.
        String item = JsonPath.read(rv, "$.checklist[0].id");
        mvc.perform(put("/api/shares/" + rahulShare + "/checklist/" + item).header("Authorization", rahul)
                .contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}")).andExpect(status().isNoContent());
        assertThat((Boolean) JsonPath.read(demo.getJson(rahul, "/api/shares/" + rahulShare), "$.checklist[0].done")).isTrue();
        postJson(rahul, "/api/shares/" + rahulShare + "/acknowledge", "{}").andExpect(status().isOk());
        List<Map<String, Object>> rows = JsonPath.read(demo.getJson(neha, "/api/handovers/" + hid + "/shares"), "$");
        assertThat(rows).anyMatch(r -> r.get("id").equals(rahulShare) && r.get("state").equals("ACKNOWLEDGED"));
        assertThat(rows).anyMatch(r -> r.get("id").equals(arjunShare) && r.get("state").equals("OPENED"));

        // Revoked shares stop working at once and disappear from the recipient's list.
        postJson(neha, "/api/shares/" + rahulShare + "/revoke", "{}").andExpect(status().isNoContent());
        mvc.perform(get("/api/shares/" + rahulShare).header("Authorization", rahul))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("SHARE_INACTIVE"));
        assertThat((List<?>) JsonPath.read(demo.getJson(rahul, "/api/shares"), "$")).isEmpty();
        assertThat((List<?>) JsonPath.read(demo.getJson(arjun, "/api/shares"), "$")).hasSize(1);

        int views = jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'SHARE_VIEWED' AND entity_id = :s")
                .param("s", UUID.fromString(arjunShare)).query(Integer.class).single();
        assertThat(views).isEqualTo(1);
    }
}
