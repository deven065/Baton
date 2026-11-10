package com.bramhastra.baton.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.jayway.jsonpath.JsonPath;

/** The shared demo workspace, with the production lock on: nobody can break it for the next visitor. */
@SpringBootTest(properties = "baton.demo.locked=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
class DemoGuardTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Value("${baton.demo.password}")
    String password;

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"demo\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }

    private String id(String email) {
        return jdbc.sql("SELECT id FROM app_user WHERE email = :e").param("e", email).query(UUID.class).single().toString();
    }

    @Test
    void demoPersonasAndSettingsCannotBeChanged() throws Exception {
        String admin = login("admin@annapurna.test");
        String json = MediaType.APPLICATION_JSON_VALUE;

        mvc.perform(post("/api/me/password").header("Authorization", login("arjun@annapurna.test")).contentType(json)
                        .content("{\"currentPassword\":\"" + password + "\",\"newPassword\":\"vandal-password-123\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("DEMO_LOCKED"));
        mvc.perform(patch("/api/admin/users/" + id("neha@annapurna.test")).header("Authorization", admin)
                        .contentType(json).content("{\"active\":false}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("DEMO_LOCKED"));
        mvc.perform(post("/api/admin/users/" + id("priya@annapurna.test") + "/reset-password").header("Authorization", admin))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/ai").header("Authorization", admin).contentType(json)
                        .content("{\"provider\":\"OPENAI\",\"apiKey\":\"sk-vandal\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/workspace/disconnect").header("Authorization", admin).contentType(json).content("{}"))
                .andExpect(status().isForbidden());

        // Path tricks do not get around it.
        int tricked = mvc.perform(post("/api/me//password").header("Authorization", login("rahul@annapurna.test"))
                        .contentType(json).content("{\"currentPassword\":\"" + password + "\",\"newPassword\":\"vandal-password-123\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(tricked).isGreaterThanOrEqualTo(400);

        // Everyone can still sign in with the published password, and reading still works.
        login("arjun@annapurna.test");
        mvc.perform(get("/api/admin/users").header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/ai").header("Authorization", admin)).andExpect(status().isOk());
    }

    @Test
    void addingThingsStillWorksInTheDemo() throws Exception {
        mvc.perform(post("/api/admin/groups").header("Authorization", login("admin@annapurna.test"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"demo-guard-" + UUID.randomUUID().toString().substring(0, 6) + "\"}"))
                .andExpect(status().isCreated());
    }
}
