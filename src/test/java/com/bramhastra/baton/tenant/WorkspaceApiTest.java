package com.bramhastra.baton.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.bramhastra.baton.identity.DemoDataSeeder;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.ObjectMapper;

/** A company workspace on its own database: set-up, isolation from the demo workspace, and refusals. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
class WorkspaceApiTest {

    @Autowired
    MockMvc mvc;

    JdbcClient home;

    @Autowired
    void setHome(@Qualifier("homeDataSource") javax.sql.DataSource ds) {
        this.home = JdbcClient.create(ds);
    }

    @Autowired
    JdbcConnectionDetails db;

    @Autowired
    ObjectMapper json;

    @Value("${baton.demo.password}")
    String demoPassword;

    private static final String ADMIN_PASSWORD = "acme-admin-password-1";

    /** A fresh, empty database in the same Postgres server, standing in for a company's own database. */
    private String newDatabase() {
        String name = "company_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        home.sql("CREATE DATABASE " + name).update();
        URI u = URI.create(db.getJdbcUrl().substring(5));
        return "postgresql://" + u.getHost() + ":" + u.getPort() + "/" + name;
    }

    private ResultActions register(String slug, String dbUrl) throws Exception {
        Map<String, Object> body = Map.of(
                "slug", slug,
                "name", "Acme Robotics",
                "database", Map.of("url", dbUrl, "username", db.getUsername(), "password", db.getPassword()),
                "admin", Map.of("displayName", "Asha Rao", "email", "asha@acme.example", "password", ADMIN_PASSWORD));
        return mvc.perform(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private ResultActions login(String workspace, String email, String password) throws Exception {
        Map<String, String> body = workspace == null ? Map.of("email", email, "password", password)
                : Map.of("workspace", workspace, "email", email, "password", password);
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private static String slug() {
        return "acme-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void newWorkspaceIsIsolatedFromTheDemo() throws Exception {
        String slug = slug();
        String body = register(slug, newDatabase())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.orgRole").value("ADMIN"))
                .andExpect(jsonPath("$.user.workspace.slug").value(slug))
                .andReturn().getResponse().getContentAsString();
        String acme = JsonPath.read(body, "$.token");

        // The company's database starts empty: none of the demo's projects are visible.
        mvc.perform(get("/api/projects").header("Authorization", "Bearer " + acme))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + acme))
                .andExpect(jsonPath("$.workspace.name").value("Acme Robotics"));

        // A demo project id means nothing in the company's database.
        UUID demoProject = home.sql("SELECT id FROM public.project WHERE name = :n")
                .param("n", DemoDataSeeder.PROJECT_NAME).query(UUID.class).list().getFirst();
        mvc.perform(get("/api/projects/" + demoProject).header("Authorization", "Bearer " + acme))
                .andExpect(status().isNotFound());

        // Accounts do not cross workspaces in either direction.
        login(slug, "neha@annapurna.test", demoPassword).andExpect(status().isUnauthorized());
        login("demo", "asha@acme.example", ADMIN_PASSWORD).andExpect(status().isUnauthorized());
        login(slug, "asha@acme.example", ADMIN_PASSWORD).andExpect(status().isOk());
        // No workspace given = the demo workspace.
        login(null, "neha@annapurna.test", demoPassword).andExpect(status().isOk());

        // Baton's own registry holds the workspace name and sealed connection details only.
        Map<String, Object> row = home.sql("SELECT * FROM control.tenant WHERE slug = :s").param("s", slug)
                .query().singleRow();
        String all = row.values().toString();
        assertThat(all).doesNotContain("asha@acme.example").doesNotContain("company_").doesNotContain(db.getPassword());
        assertThat(home.sql("SELECT count(*) FROM public.app_user WHERE email = 'asha@acme.example'")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void takenAndInvalidNamesAreRefused() throws Exception {
        String slug = slug();
        register(slug, newDatabase()).andExpect(status().isCreated());
        register(slug, newDatabase()).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("WORKSPACE_TAKEN"));
        register("demo", newDatabase()).andExpect(status().isConflict());
        register("Not A Slug!", newDatabase()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.slug").exists());
    }

    @Test
    void unsuitableDatabasesAreRefusedAndNothingIsRegistered() throws Exception {
        // Baton's own database already has tables.
        URI u = URI.create(db.getJdbcUrl().substring(5));
        String slug = slug();
        register(slug, "postgresql://" + u.getHost() + ":" + u.getPort() + u.getPath())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("DATABASE_UNSUITABLE"));
        // Nothing listening on that port.
        register(slug, "postgresql://localhost:1/nothing")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("DATABASE_UNREACHABLE"));
        assertThat(home.sql("SELECT count(*) FROM control.tenant WHERE slug = :s").param("s", slug)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void workspaceLookupAndUnknownWorkspaceLogin() throws Exception {
        mvc.perform(get("/api/workspaces/demo")).andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("demo"));
        mvc.perform(get("/api/workspaces/no-such-company")).andExpect(status().isNotFound());
        login("no-such-company", "neha@annapurna.test", demoPassword)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid workspace, email or password"));
    }
}
