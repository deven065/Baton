package com.bramhastra.baton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

/**
 * Builds an isolated copy of the Project Annapurna team and data for one test class: a fresh project with
 * the seeded personas as members, the five demo sources, and a reconstructed handover for Priya.
 * Test classes share one database, so each one works in its own project.
 */
public class DemoFixture {

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final String password;

    public UUID projectId;
    public String handoverId;

    public DemoFixture(MockMvc mvc, JdbcClient jdbc, String password) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.password = password;
    }

    public DemoFixture createProject(String name) {
        projectId = jdbc.sql("INSERT INTO project (name, description) VALUES (:n, 'test copy') RETURNING id")
                .param("n", name).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO project_member (project_id, user_id, role)
                SELECT :p, id, CASE WHEN email = 'neha@annapurna.test' THEN 'PROJECT_LEAD' ELSE 'MEMBER' END
                FROM app_user WHERE email IN ('neha@annapurna.test', 'priya@annapurna.test',
                                              'arjun@annapurna.test', 'rahul@annapurna.test')
                """).param("p", projectId).update();
        return this;
    }

    public DemoFixture loadSourcesAndReconstruct() throws Exception {
        return loadSourcesAndReconstruct(false);
    }

    /** With chatApps, also the WhatsApp and Discord exports: all seven sources behind the 25 planted facts. */
    public DemoFixture loadSourcesAndReconstruct(boolean chatApps) throws Exception {
        String neha = login("neha@annapurna.test");
        List<String> jobs = new ArrayList<>();
        jobs.add(upload(neha, "MEETING_NOTES", "Weekly sync", "INTERNAL", null, "2026-10-08-weekly-sync.md"));
        jobs.add(upload(neha, "CHAT_EXPORT", "Slack #dev", "INTERNAL", null, "dev.json"));
        jobs.add(upload(neha, "TASK_CSV", "Jira export", "INTERNAL", null, "tasks.csv"));
        jobs.add(upload(neha, "DOCUMENT", "Architecture notes", "CONFIDENTIAL", "engineering", "architecture.md"));
        jobs.add(upload(neha, "DOCUMENT", "Vendor contract", "RESTRICTED", "operations", "vendor-contract.md"));
        if (chatApps) {
            jobs.add(upload(neha, "CHAT_EXPORT", "WhatsApp Annapurna Core", "INTERNAL", null, "WhatsApp Chat with Annapurna Core.txt"));
            jobs.add(upload(neha, "CHAT_EXPORT", "Discord #design", "INTERNAL", null, "discord-design.json"));
        }
        for (String job : jobs) {
            assertThat(awaitJob(neha, job)).isEqualTo("SUCCEEDED");
        }
        UUID priya = jdbc.sql("SELECT id FROM app_user WHERE email = 'priya@annapurna.test'").query(UUID.class).single();
        String created = mvc.perform(post("/api/projects/" + projectId + "/handovers").header("Authorization", neha)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departingUserId\":\"" + priya + "\",\"title\":\"Priya's handover\",\"scenario\":\"PLANNED\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        handoverId = JsonPath.read(created, "$.id");
        reconstruct();
        return this;
    }

    public void reconstruct() throws Exception {
        String neha = login("neha@annapurna.test");
        String started = mvc.perform(post("/api/handovers/" + handoverId + "/reconstruct").header("Authorization", neha))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(awaitJob(neha, JsonPath.read(started, "$.jobId"))).isEqualTo("SUCCEEDED");
    }

    public String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }

    public String getJson(String token, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String upload(String token, String type, String title, String sensitivity, String groups, String file)
            throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("demo", "annapurna", file));
        var req = multipart("/api/projects/" + projectId + "/sources")
                .file(new MockMultipartFile("file", file, "application/octet-stream", bytes));
        req.header("Authorization", token);
        req.param("type", type).param("title", title).param("sensitivity", sensitivity)
                .param("authorisationAttested", "true");
        if (groups != null) {
            req.param("aclGroups", groups);
        }
        String body = mvc.perform(req).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.jobId");
    }

    public String awaitJob(String token, String jobId) throws Exception {
        for (int i = 0; i < 300; i++) {
            String body = getJson(token, "/api/jobs/" + jobId);
            String st = JsonPath.read(body, "$.status");
            if (st.equals("SUCCEEDED") || st.equals("FAILED")) {
                if (st.equals("FAILED")) {
                    System.err.println("Job failed: " + body);
                }
                return st;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Job did not finish");
    }
}
