package com.bramhastra.baton.reconstruct;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bramhastra.baton.DemoFixture;
import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.bramhastra.baton.ai.LlmClient;
import com.bramhastra.baton.ai.OfflineExtractor;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.json.JsonMapper;

/**
 * The model-graded check (CHECK stage) end to end. Extraction runs the offline rules; the "model" that checks the
 * claims is scripted: it calls every claim about the vendor unsupported and everything else supported. Disputed
 * claims must stop being shown as fact, carry the reason, and need a person's decision before approval.
 */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
class ModelCheckTest {

    private static final Pattern CLAIM = Pattern.compile("<claim id=\"(k\\d+)\">\\n([^\\n]*)");

    @TestBean(name = "llmClient")
    LlmClient llmClient;

    static LlmClient llmClient() {
        OfflineExtractor offline = new OfflineExtractor(JsonMapper.builder().build());
        return req -> {
            if (!req.promptId().startsWith("check")) {
                return offline.complete(req);
            }
            List<String> checks = new ArrayList<>();
            Matcher m = CLAIM.matcher(req.user());
            while (m.find()) {
                boolean vendor = m.group(2).toLowerCase().contains("vendor");
                checks.add("{\"id\":\"%s\",\"verdict\":\"%s\",\"reason\":\"%s\"}".formatted(m.group(1),
                        vendor ? "UNSUPPORTED" : "SUPPORTED", vendor ? "scripted dispute" : "stated directly"));
            }
            return new LlmClient.LlmResponse("{\"checks\":[" + String.join(",", checks) + "]}", "test-judge", "test", 100, 20);
        };
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Value("${baton.demo.password}")
    String demoPassword;

    @Test
    void disputedClaimsAreNoLongerShownAsFactAndNeedADecision() throws Exception {
        DemoFixture demo = new DemoFixture(mvc, jdbc, demoPassword).createProject("Model check").loadSourcesAndReconstruct();
        String neha = demo.login("neha@annapurna.test");
        List<Map<String, Object>> claims = JsonPath.read(
                demo.getJson(neha, "/api/handovers/" + demo.handoverId + "/claims?size=200"), "$.items");

        List<Map<String, Object>> checked = claims.stream().filter(c -> attrs(c).get("modelCheck") != null).toList();
        assertThat(checked).as("extracted claims with a verified quote are checked").isNotEmpty();

        List<Map<String, Object>> disputed = checked.stream().filter(c -> "UNSUPPORTED".equals(attrs(c).get("modelCheck"))).toList();
        assertThat(disputed).as("the scripted judge disputes the vendor claims").isNotEmpty();
        for (Map<String, Object> c : disputed) {
            assertThat(c.get("status")).isIn("INFERRED", "CONFLICTING", "STALE", "MISSING_EVIDENCE");
            assertThat(c.get("reasons").toString()).contains("model check").contains("scripted dispute");
        }
        assertThat(checked.stream().filter(c -> "SUPPORTED".equals(attrs(c).get("modelCheck"))))
                .anyMatch(c -> List.of("VERIFIED", "SINGLE_SOURCE").contains(c.get("status")));

        Map<String, Object> stats = JsonPath.read(demo.getJson(neha, "/api/handovers/" + demo.handoverId + "/runs/latest"), "$.stats");
        assertThat(((Number) stats.get("modelChecked")).intValue()).isEqualTo(checked.size());
        assertThat(((Number) stats.get("modelDisputed")).intValue()).isEqualTo(disputed.size());
        assertThat(((Number) stats.get("tokensIn")).intValue()).isPositive();

        String gate = demo.getJson(neha, "/api/handovers/" + demo.handoverId + "/approval");
        assertThat((Boolean) JsonPath.read(gate, "$.canApprove")).as("disputed claims block approval until decided").isFalse();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attrs(Map<String, Object> c) {
        Object a = c.get("attributes");
        return a == null ? Map.of() : (Map<String, Object>) a;
    }
}
