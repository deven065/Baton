package com.bramhastra.baton.handover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.bramhastra.baton.DemoFixture;
import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.jayway.jsonpath.JsonPath;

/** Ask the handover, living handovers, successor progress, signed export, and the knowledge-risk radar. */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HandoverFeaturesApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Value("${baton.demo.password}")
    String demoPassword;

    DemoFixture demo;
    String neha, priya, rahul, arjun, hid;

    @BeforeAll
    void setUp() throws Exception {
        demo = new DemoFixture(mvc, jdbc, demoPassword).createProject("Annapurna (features test)").loadSourcesAndReconstruct();
        neha = demo.login("neha@annapurna.test");
        priya = demo.login("priya@annapurna.test");
        rahul = demo.login("rahul@annapurna.test");
        arjun = demo.login("arjun@annapurna.test");
        hid = demo.handoverId;
        reviewEverything();
        post(neha, "/api/handovers/" + hid + "/approve", "{}").andExpect(status().isCreated());
        for (String email : List.of("rahul@annapurna.test", "arjun@annapurna.test")) {
            post(neha, "/api/handovers/" + hid + "/shares", "{\"recipientId\":\"" + user(email) + "\"}")
                    .andExpect(status().isCreated());
        }
    }

    private void reviewEverything() {
        UUID h = UUID.fromString(hid);
        jdbc.sql("UPDATE claim SET review_state = 'REJECTED' WHERE handover_id = :h AND status = 'CONFLICTING'").param("h", h).update();
        jdbc.sql("UPDATE claim SET review_state = 'ACCEPTED' WHERE handover_id = :h AND review_state IN ('PENDING', 'NEEDS_INFO')")
                .param("h", h).update();
    }

    private ResultActions post(String token, String url, String json) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private UUID user(String email) {
        return jdbc.sql("SELECT id FROM app_user WHERE email = :e").param("e", email).query(UUID.class).single();
    }

    private String shareOf(String token) throws Exception {
        return JsonPath.read(demo.getJson(token, "/api/shares"), "$[0].id");
    }

    @Test
    void theWholeLifecycle() throws Exception {
        // ── Ask the handover: answered from what Rahul may see, with the claims it rests on.
        String rahulShare = shareOf(rahul);
        String a = post(rahul, "/api/shares/" + rahulShare + "/ask", "{\"question\":\"Who owns the payments migration?\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(a, "$.found")).isTrue();
        assertThat((String) JsonPath.read(a, "$.mode")).isEqualTo("search");
        assertThat((List<?>) JsonPath.read(a, "$.citations")).isNotEmpty();
        String none = post(rahul, "/api/shares/" + rahulShare + "/ask", "{\"question\":\"zebra quantum xylophone\"}")
                .andReturn().getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(none, "$.found")).isFalse();
        // Someone else's share answers nothing.
        post(arjun, "/api/shares/" + rahulShare + "/ask", "{\"question\":\"payments\"}").andExpect(status().isNotFound());

        // ── Not covered: sent to Priya, who answers it in her exit interview.
        post(rahul, "/api/shares/" + rahulShare + "/questions", "{\"question\":\"Where are the Figma files for the menu screens?\"}")
                .andExpect(status().isCreated());
        List<Map<String, Object>> gaps = JsonPath.read(demo.getJson(priya, "/api/handovers/" + hid + "/gaps"), "$[?(@.question =~ /.*Figma.*/)]");
        assertThat(gaps).hasSize(1);
        post(priya, "/api/gaps/" + gaps.get(0).get("id") + "/answer",
                "{\"answer\":\"The Figma files are in the Design team project, page Menu v3; Rahul has edit access.\",\"sensitivity\":\"INTERNAL\"}")
                .andExpect(status().isOk());
        String mine = demo.getJson(rahul, "/api/shares/" + rahulShare + "/questions");
        assertThat((String) JsonPath.read(mine, "$[0].status")).isEqualTo("ANSWERED");
        assertThat((String) JsonPath.read(mine, "$[0].answer")).contains("Menu v3");

        // ── Living handover: the answer is a new claim the approved v1 does not have.
        String drift = demo.getJson(neha, "/api/handovers/" + hid + "/drift");
        assertThat((Integer) JsonPath.read(drift, "$.answersSince")).isEqualTo(1);
        assertThat((List<?>) JsonPath.read(drift, "$.changes[?(@.kind == 'ADDED')]")).isNotEmpty();
        post(neha, "/api/handovers/" + hid + "/reopen", "{}").andExpect(status().isNoContent());
        reviewEverything();
        post(neha, "/api/handovers/" + hid + "/approve", "{}").andExpect(status().isCreated());
        assertThat((Integer) JsonPath.read(demo.getJson(neha, "/api/handovers/" + hid + "/drift"), "$.recipientsOnOlderVersion")).isEqualTo(2);
        String moved = post(neha, "/api/handovers/" + hid + "/shares/move-to-latest", "{}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(moved, "$.moved")).isEqualTo(2);
        String newShare = shareOf(rahul);
        assertThat(newShare).isNotEqualTo(rahulShare);
        String view = demo.getJson(rahul, "/api/shares/" + newShare);
        assertThat((Integer) JsonPath.read(view, "$.versionNo")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(view, "$.previousVersionNo")).isEqualTo(1);
        assertThat((List<String>) JsonPath.read(view, "$.changes[*].after")).anyMatch(t -> t != null && t.contains("Figma"));
        mvc.perform(get("/api/shares/" + rahulShare).header("Authorization", rahul)).andExpect(status().isForbidden());

        // ── Successor progress: per person, by phase, with overdue items once a phase is past due.
        String arjunShare = shareOf(arjun);
        String item = JsonPath.read(demo.getJson(arjun, "/api/shares/" + arjunShare), "$.checklist[0].id");
        mvc.perform(put("/api/shares/" + arjunShare + "/checklist/" + item).header("Authorization", arjun)
                .contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}")).andExpect(status().isNoContent());
        jdbc.sql("UPDATE handover_share SET created_at = now() - interval '10 days' WHERE id = :s")
                .param("s", UUID.fromString(arjunShare)).update();
        String progress = demo.getJson(neha, "/api/handovers/" + hid + "/progress");
        List<Map<String, Object>> people = JsonPath.read(progress, "$[?(@.name == 'Arjun Mehta')]");
        assertThat(people).hasSize(1);
        assertThat((Integer) people.get(0).get("percent")).isPositive();
        List<Boolean> overdue = JsonPath.read(progress, "$[?(@.name == 'Arjun Mehta')].phases[?(@.phase == 'WEEK1')].overdue");
        assertThat(overdue).containsExactly(true);
        assertThat((List<?>) JsonPath.read(progress, "$[?(@.name == 'Rahul Desai')]")).hasSize(1);
        mvc.perform(get("/api/handovers/" + hid + "/progress").header("Authorization", rahul)).andExpect(status().isForbidden());

        // ── Signed export of v2, verified; a changed file is caught.
        byte[] zip = mvc.perform(get("/api/handovers/" + hid + "/versions/2/export").header("Authorization", neha))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<String, byte[]> files = unzip(zip);
        assertThat(files).containsKeys("handover.json", "checklist.json", "shares.json", "audit.json", "report.html",
                "manifest.json", "manifest.sig", "VERIFY.txt");
        String stored = jdbc.sql("SELECT sha256 FROM handover_version WHERE handover_id = :h AND version_no = 2")
                .param("h", UUID.fromString(hid)).query(String.class).single();
        assertThat(ExportService.sha256(files.get("handover.json"))).isEqualTo(stored);
        String ok = verify(zip);
        assertThat((Boolean) JsonPath.read(ok, "$.valid")).as(ok).isTrue();
        files.put("handover.json", new String(files.get("handover.json")).replace("Menu v3", "Menu v4").getBytes());
        String bad = verify(zipOf(files));
        assertThat((Boolean) JsonPath.read(bad, "$.valid")).isFalse();
        assertThat((Boolean) JsonPath.read(bad, "$.filesOk")).isFalse();

        // ── Knowledge-risk radar: the lead sees who holds what; a member does not.
        String radar = demo.getJson(neha, "/api/projects/" + demo.projectId + "/risk");
        assertThat((Integer) JsonPath.read(radar, "$.busFactor")).isPositive();
        assertThat((List<?>) JsonPath.read(radar, "$.team")).hasSize(4);
        assertThat((List<?>) JsonPath.read(radar, "$.topics")).isNotEmpty();
        assertThat((List<Boolean>) JsonPath.read(radar, "$.team[?(@.name == 'Priya Sharma')].leaving")).containsExactly(true);
        mvc.perform(get("/api/projects/" + demo.projectId + "/risk").header("Authorization", rahul)).andExpect(status().isForbidden());
    }

    private String verify(byte[] zip) throws Exception {
        return mvc.perform(multipart("/api/exports/verify").file(new MockMultipartFile("file", "export.zip", "application/zip", zip))
                .header("Authorization", neha)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.put(e.getName(), in.readAllBytes());
            }
        }
        return out;
    }

    private static byte[] zipOf(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                z.putNextEntry(new ZipEntry(f.getKey()));
                z.write(f.getValue());
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
