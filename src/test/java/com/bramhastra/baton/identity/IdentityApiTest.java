package com.bramhastra.baton.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
class IdentityApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Value("${baton.demo.password}")
    String demoPassword;

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + demoPassword + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private UUID annapurnaId() {
        return jdbc.sql("SELECT id FROM project WHERE name = :n").param("n", DemoDataSeeder.PROJECT_NAME)
                .query(UUID.class).single();
    }

    @Test
    void loginReturnsTokenAndMeReflectsPersona() throws Exception {
        String token = login("rahul@annapurna.test");
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Rahul Desai"))
                .andExpect(jsonPath("$.clearance").value("INTERNAL"))
                .andExpect(jsonPath("$.groups[0]").value("design"));
    }

    @Test
    void wrongPasswordIsUnauthenticated() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"priya@annapurna.test\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void missingTokenIsUnauthenticated() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void invalidBodyIsValidationFailed() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.email").exists());
    }

    @Test
    void memberSeesProjectWithRole() throws Exception {
        String token = login("neha@annapurna.test");
        mvc.perform(get("/api/projects/" + annapurnaId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myRole").value("PROJECT_LEAD"))
                .andExpect(jsonPath("$.members.length()").value(4));
    }

    /** Leak test: a non-member gets 404, never 403, so the project's existence is not confirmed. */
    @Test
    void nonMemberGetsNotFoundNotForbidden() throws Exception {
        String token = login("admin@annapurna.test");
        mvc.perform(get("/api/projects/" + annapurnaId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mvc.perform(get("/api/projects").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void auditLogIsAppendOnly() throws Exception {
        login("arjun@annapurna.test");
        Integer logins = jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'LOGIN'").query(Integer.class).single();
        assertThat(logins).isPositive();
        try {
            jdbc.sql("DELETE FROM audit_log").update();
            throw new AssertionError("audit_log delete should have been rejected");
        } catch (org.springframework.dao.DataAccessException expected) {
            assertThat(expected.getMostSpecificCause().getMessage()).contains("append-only");
        }
    }
}
