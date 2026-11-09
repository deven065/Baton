package com.bramhastra.baton.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.ObjectMapper;

/** Phase 4: limits on public endpoints, and a company disconnecting its workspace. */
@SpringBootTest(properties = {"baton.limits.login-per-ip=6", "baton.limits.login-failures-per-account=4",
        "baton.limits.lookup-per-ip=3"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
class HardeningApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcConnectionDetails db;

    @Autowired
    ObjectMapper json;

    @Value("${baton.demo.password}")
    String demoPassword;

    JdbcClient home;

    @Autowired
    void setHome(@Qualifier("homeDataSource") DataSource ds) {
        this.home = JdbcClient.create(ds);
    }

    private static final String PASSWORD = "company-admin-password";
    private String ws;
    private String dbName;
    private String admin;

    /** Every request in this class comes from its own made-up address so the per-address limits stay separate. */
    private static String ip() {
        return "198.51.100." + (int) (Math.random() * 250 + 1) + "." + UUID.randomUUID().toString().substring(0, 4);
    }

    @BeforeEach
    void newCompany() throws Exception {
        dbName = "hard_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        home.sql("CREATE DATABASE " + dbName).update();
        URI u = URI.create(db.getJdbcUrl().substring(5));
        ws = "hd-" + UUID.randomUUID().toString().substring(0, 8);
        String body = send(post("/api/workspaces"), null, ip(), Map.of(
                "slug", ws, "name", "Hardening Co",
                "database", Map.of("url", "postgresql://" + u.getHost() + ":" + u.getPort() + "/" + dbName,
                        "username", db.getUsername(), "password", db.getPassword()),
                "admin", Map.of("displayName", "Asha Admin", "email", "asha@hard.example", "password", PASSWORD)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        admin = JsonPath.read(body, "$.token");
    }

    private ResultActions send(MockHttpServletRequestBuilder req, String token, String ip, Object body) throws Exception {
        req.header("X-Forwarded-For", ip);
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    private ResultActions login(String ip, String workspace, String email, String password) throws Exception {
        return send(post("/api/auth/login"), null, ip, Map.of("workspace", workspace, "email", email, "password", password));
    }

    @Test
    void wrongPasswordsLockOneAccountForAWhile() throws Exception {
        for (int i = 0; i < 4; i++) {
            login(ip(), ws, "asha@hard.example", "wrong-password-" + i).andExpect(status().isUnauthorized());
        }
        // Locked now, even with the right password and from a new address.
        login(ip(), ws, "ASHA@hard.example", PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("TOO_MANY_REQUESTS"))
                .andExpect(header().exists("Retry-After"));
        // Other accounts are unaffected.
        login(ip(), "demo", "neha@annapurna.test", demoPassword).andExpect(status().isOk());
    }

    @Test
    void oneAddressCannotTryEndlessly() throws Exception {
        String attacker = ip();
        for (int i = 0; i < 6; i++) {
            login(attacker, ws, "user" + i + "@hard.example", "guess").andExpect(status().isUnauthorized());
        }
        login(attacker, ws, "asha@hard.example", PASSWORD).andExpect(status().isTooManyRequests());
        // A spoofed first X-Forwarded-For entry does not escape: the proxy-appended last entry is used.
        login("203.0.113.50, " + attacker, ws, "asha@hard.example", PASSWORD).andExpect(status().isTooManyRequests());
        login(ip(), ws, "asha@hard.example", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void workspaceNamesCannotBeEnumeratedQuickly() throws Exception {
        String from = ip();
        for (int i = 0; i < 3; i++) {
            send(get("/api/workspaces/nope-" + i), null, from, null).andExpect(status().isNotFound());
        }
        send(get("/api/workspaces/" + ws), null, from, null).andExpect(status().isTooManyRequests());
    }

    @Test
    void disconnectingLeavesTheDataWithTheCompanyAndNothingWithBaton() throws Exception {
        send(get("/api/admin/workspace"), admin, ip(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(ws))
                .andExpect(jsonPath("$.demo").value(false))
                .andExpect(jsonPath("$.database.database").value(dbName))
                .andExpect(jsonPath("$.database.password").doesNotExist());

        send(post("/api/admin/workspace/disconnect"), admin, ip(), Map.of("confirm", "wrong-name", "password", PASSWORD))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.confirm").exists());
        send(post("/api/admin/workspace/disconnect"), admin, ip(), Map.of("confirm", ws, "password", "not-my-password"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.password").exists());

        String body = send(post("/api/admin/workspace/disconnect"), admin, ip(), Map.of("confirm", ws, "password", PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        byte[] key = Base64.getDecoder().decode((String) JsonPath.read(body, "$.recoveryKey"));
        assertThat(key).hasSize(32);

        // Baton holds nothing about the company any more.
        assertThat(home.sql("SELECT count(*) FROM control.tenant WHERE slug = :s").param("s", ws)
                .query(Integer.class).single()).isZero();
        send(get("/api/me"), admin, ip(), null).andExpect(status().isUnauthorized());
        login(ip(), ws, "asha@hard.example", PASSWORD).andExpect(status().isUnauthorized());
        send(get("/api/workspaces/" + ws), null, ip(), null).andExpect(status().isNotFound());

        // The company's database still has its people and an audit entry saying it was disconnected.
        URI u = URI.create(db.getJdbcUrl().substring(5));
        String theirs = "jdbc:postgresql://" + u.getHost() + ":" + u.getPort() + "/" + dbName;
        try (var conn = java.sql.DriverManager.getConnection(theirs, db.getUsername(), db.getPassword());
             var st = conn.createStatement()) {
            var rs = st.executeQuery("SELECT (SELECT count(*) FROM app_user), "
                    + "(SELECT count(*) FROM audit_log WHERE action = 'WORKSPACE_DISCONNECTED')");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.getInt(2)).isEqualTo(1);
        }
    }

    @Test
    void theDemoCannotBeDisconnected() throws Exception {
        String token = JsonPath.read(login(ip(), "demo", "admin@annapurna.test", demoPassword)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
        send(post("/api/admin/workspace/disconnect"), token, ip(), Map.of("confirm", "demo", "password", demoPassword))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("NOT_ALLOWED"));
        send(get("/api/admin/workspace"), token, ip(), null).andExpect(jsonPath("$.demo").value(true));
    }
}
