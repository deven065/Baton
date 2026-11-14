package com.bramhastra.baton.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.ObjectMapper;

/** Phase 2: an admin running a real company workspace — people, groups, projects, first-login passwords. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
class CompanyAdminApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcConnectionDetails db;

    @Autowired
    ObjectMapper json;

    JdbcClient home;

    @Autowired
    void setHome(@Qualifier("homeDataSource") DataSource ds) {
        this.home = JdbcClient.create(ds);
    }

    private static final String ADMIN_PASSWORD = "company-admin-password";
    private String ws;
    private String admin;

    @BeforeEach
    void newCompany() throws Exception {
        String dbName = "company_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        home.sql("CREATE DATABASE " + dbName).update();
        URI u = URI.create(db.getJdbcUrl().substring(5));
        ws = "co-" + UUID.randomUUID().toString().substring(0, 8);
        String body = send(post("/api/workspaces"), null, Map.of(
                "slug", ws, "name", "Test Co",
                "database", Map.of("url", "postgresql://" + u.getHost() + ":" + u.getPort() + "/" + dbName,
                        "username", db.getUsername(), "password", db.getPassword()),
                "admin", Map.of("displayName", "Asha Admin", "email", "asha@co.example", "password", ADMIN_PASSWORD)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        admin = JsonPath.read(body, "$.token");
    }

    private ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    private String login(String email, String password) throws Exception {
        String body = send(post("/api/auth/login"), null, Map.of("workspace", ws, "email", email, "password", password))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private String createUser(String name, String email, String clearance, List<String> groups) throws Exception {
        return send(post("/api/admin/users"), admin, Map.of("displayName", name, "email", email,
                "clearance", clearance, "groups", groups))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.mustChangePassword").value(true))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void adminOnboardsAPersonWhoMustChangeTheTemporaryPassword() throws Exception {
        send(post("/api/admin/groups"), admin, Map.of("name", "Engineering", "description", "Builds things"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("engineering"));
        String created = createUser("Ben Lead", "ben@co.example", "CONFIDENTIAL", List.of("engineering"));
        String temp = JsonPath.read(created, "$.temporaryPassword");
        assertThat(temp).hasSize(16);

        // Same email twice is refused.
        send(post("/api/admin/users"), admin, Map.of("displayName", "Dup", "email", "BEN@co.example"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("ALREADY_EXISTS"));

        String ben = login("ben@co.example", temp);
        send(get("/api/me"), ben, null).andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.groups[0]").value("engineering"))
                .andExpect(jsonPath("$.clearance").value("CONFIDENTIAL"));
        send(post("/api/me/password"), ben, Map.of("currentPassword", "wrong-password", "newPassword", "ben-own-password-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.currentPassword").exists());
        send(post("/api/me/password"), ben, Map.of("currentPassword", temp, "newPassword", "ben-own-password-1"))
                .andExpect(status().isNoContent());
        String again = login("ben@co.example", "ben-own-password-1");
        send(get("/api/me"), again, null).andExpect(jsonPath("$.mustChangePassword").value(false));

        // Not an admin: no admin API.
        send(get("/api/admin/users"), again, null).andExpect(status().isForbidden());
        // The audit trail is in the company's own database and readable by its admin.
        send(get("/api/admin/audit?limit=50"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action == 'USER_CREATED')]").exists())
                .andExpect(jsonPath("$[?(@.action == 'PASSWORD_CHANGED')]").exists());
        // Filters: several event types at once, one person, a date range and text; and every event type with counts.
        String benId = JsonPath.read(send(get("/api/me"), again, null).andReturn().getResponse().getContentAsString(), "$.id");
        send(get("/api/admin/audit?action=PASSWORD_CHANGED,LOGIN&actor=" + benId), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action == 'PASSWORD_CHANGED')]").exists())
                .andExpect(jsonPath("$[?(@.action == 'USER_CREATED')]").doesNotExist())
                .andExpect(jsonPath("$[?(@.actorName != 'Ben Lead')]").doesNotExist());
        send(get("/api/admin/audit?from=2000-01-01&to=2000-12-31"), admin, null).andExpect(jsonPath("$.length()").value(0));
        send(get("/api/admin/audit").param("q", "ben lead"), admin, null).andExpect(jsonPath("$[0].actorName").value("Ben Lead"));
        send(get("/api/admin/audit").param("q", "100%_sure"), admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        send(get("/api/admin/audit/actions"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action == 'USER_CREATED' && @.count >= 1)]").exists());
        send(get("/api/admin/audit/actions"), again, null).andExpect(status().isForbidden());
    }

    @Test
    void theWorkspaceCannotBeLockedOut() throws Exception {
        String me = JsonPath.read(send(get("/api/me"), admin, null).andReturn().getResponse().getContentAsString(), "$.id");
        send(patch("/api/admin/users/" + me), admin, Map.of("active", false))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("NOT_ALLOWED"));
        send(patch("/api/admin/users/" + me), admin, Map.of("orgRole", "MEMBER"))
                .andExpect(status().isConflict());
        // Resetting someone's password issues a new temporary one and forces a change again.
        String id = JsonPath.read(createUser("Cara", "cara@co.example", "INTERNAL", List.of()), "$.user.id");
        String temp = JsonPath.read(send(post("/api/admin/users/" + id + "/reset-password"), admin, null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.temporaryPassword");
        String cara = login("cara@co.example", temp);
        send(get("/api/me"), cara, null).andExpect(jsonPath("$.mustChangePassword").value(true));
        // Deactivated people cannot sign in.
        send(patch("/api/admin/users/" + id), admin, Map.of("active", false)).andExpect(status().isOk());
        send(post("/api/auth/login"), null, Map.of("workspace", ws, "email", "cara@co.example", "password", temp))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void projectsTeamsAndAHandoverStartedByTheLead() throws Exception {
        String benId = JsonPath.read(createUser("Ben Lead", "ben@co.example", "RESTRICTED", List.of()), "$.user.id");
        String caraCreated = createUser("Cara Leaving", "cara@co.example", "CONFIDENTIAL", List.of());
        String caraId = JsonPath.read(caraCreated, "$.user.id");

        String project = JsonPath.read(send(post("/api/projects"), admin,
                Map.of("name", "Rocket", "description", "Launch site", "leadUserId", benId))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        send(post("/api/projects"), admin, Map.of("name", "rocket", "leadUserId", benId))
                .andExpect(status().isConflict());
        send(get("/api/admin/projects"), admin, null)
                .andExpect(jsonPath("$[0].name").value("Rocket"))
                .andExpect(jsonPath("$[0].leads[0]").value("Ben Lead"));
        // The admin manages the team list but cannot open the project itself.
        send(get("/api/projects/" + project + "/team"), admin, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].role").value("PROJECT_LEAD"))
                .andExpect(jsonPath("$[0].clearance").isString());
        send(get("/api/projects/" + project), admin, null).andExpect(status().isNotFound());

        String benTemp = JsonPath.read(send(post("/api/admin/users/" + benId + "/reset-password"),
                admin, null).andReturn().getResponse().getContentAsString(), "$.temporaryPassword");
        String benFirst = login("ben@co.example", benTemp);
        // A temporary password opens nothing until it is changed.
        send(get("/api/directory"), benFirst, null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("PASSWORD_CHANGE_REQUIRED"));
        send(post("/api/me/password"), benFirst, Map.of("currentPassword", benTemp, "newPassword", "ben-own-password-2"))
                .andExpect(status().isNoContent());
        String ben = login("ben@co.example", "ben-own-password-2");
        // The lead builds the team and can see the directory to do it.
        send(get("/api/directory"), ben, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        send(post("/api/projects/" + project + "/members"), ben, Map.of("userId", caraId, "role", "MEMBER"))
                .andExpect(status().isNoContent());
        // The only lead cannot step down or leave.
        send(patch("/api/projects/" + project + "/members/" + benId), ben, Map.of("role", "MEMBER"))
                .andExpect(status().isConflict());
        send(delete("/api/projects/" + project + "/members/" + benId), ben, null).andExpect(status().isConflict());
        // ...and starts Cara's handover.
        String handover = JsonPath.read(send(post("/api/projects/" + project + "/handovers"), ben,
                Map.of("departingUserId", caraId, "title", "cara@co.example", "scenario", "PLANNED"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        // A title typed by mistake can be fixed by the lead, and only the lead; the change is audited.
        send(patch("/api/handovers/" + handover), ben, Map.of("title", "  Cara's handover  "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Cara's handover"));
        send(patch("/api/handovers/" + handover), ben, Map.of("title", "   ")).andExpect(status().isBadRequest());
        send(patch("/api/handovers/" + handover), admin, Map.of("title", "Admin was here")).andExpect(status().isNotFound());
        send(get("/api/admin/audit?action=HANDOVER_RENAMED"), admin, null)
                .andExpect(jsonPath("$[0].details").value(org.hamcrest.Matchers.containsString("cara@co.example")));
        // Someone with an open handover cannot be removed from the project.
        send(delete("/api/projects/" + project + "/members/" + caraId), ben, null)
                .andExpect(status().isConflict());
    }

    @Test
    void aDatabaseUserWhoCannotCreateTablesGetsTheFixNotAnError() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String dbName = "limited_" + suffix;
        String role = "reader_" + suffix;
        home.sql("CREATE DATABASE " + dbName).update();
        home.sql("CREATE ROLE " + role + " LOGIN PASSWORD 'reader-password'").update();
        URI u = URI.create(db.getJdbcUrl().substring(5));
        send(post("/api/workspaces"), null, Map.of(
                "slug", "ro-" + suffix.substring(0, 8), "name", "Read Only Co",
                "database", Map.of("url", "postgresql://" + u.getHost() + ":" + u.getPort() + "/" + dbName,
                        "username", role, "password", "reader-password"),
                "admin", Map.of("displayName", "Ro Admin", "email", "ro@co.example", "password", ADMIN_PASSWORD)))
                .andExpect(jsonPath("$.error").value("DATABASE_UNSUITABLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "GRANT USAGE, CREATE ON SCHEMA public TO \"" + role + "\"")));
    }

    @Test
    void unusedGroupsCanBeDeleted() throws Exception {
        String id = JsonPath.read(send(post("/api/admin/groups"), admin, Map.of("name", "design"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        send(post("/api/admin/groups"), admin, Map.of("name", "Design")).andExpect(status().isConflict());
        send(post("/api/admin/groups"), admin, Map.of("name", "!bad")).andExpect(status().isBadRequest());
        send(delete("/api/admin/groups/" + id), admin, null).andExpect(status().isNoContent());
        send(get("/api/admin/groups"), admin, null).andExpect(jsonPath("$.length()").value(0));
    }
}
