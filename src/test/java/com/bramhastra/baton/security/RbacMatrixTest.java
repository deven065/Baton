package com.bramhastra.baton.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.bramhastra.baton.DemoFixture;
import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.ObjectMapper;

/**
 * Role-based access cannot be bypassed by calling the API directly, editing ids in the URL, using another
 * project's or another company's ids, or forging a token. Every endpoint is called by every kind of person, and
 * everyone not allowed must get 401, 403 or 404 — never a success and never someone else's data. Request bodies are
 * valid, so a refusal can only come from the permission check.
 */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RbacMatrixTest {

    /** Everyone the matrix tries. */
    enum Who {
        ADMIN,      // organisation admin, not on the project
        LEAD,       // project lead
        LEAVER,     // the person leaving (project member)
        MEMBER,     // a project member with no role in this handover
        RECIPIENT,  // project member the approved version is shared with
        OUTSIDER,   // same company, highest clearance, but not on the project
        FOREIGN,    // admin of another company's workspace
        FORGED,     // the lead's token with its claims edited (signature no longer matches)
        ANONYMOUS   // no token
    }

    record Call(HttpMethod method, String path, Object body, Set<Who> allowed, boolean multipart) {
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

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

    final Map<Who, String> token = new LinkedHashMap<>();
    DemoFixture demo;
    String p, h, claim, otherClaim, gap, source, chunk, job, share, item, group, arjunId, rahulId, adminUserId;
    int claimVersion;

    @BeforeAll
    void setUp() throws Exception {
        demo = new DemoFixture(mvc, jdbc, demoPassword).createProject("Annapurna (RBAC matrix)").loadSourcesAndReconstruct();
        p = demo.projectId.toString();
        h = demo.handoverId;
        token.put(Who.ADMIN, demo.login("admin@annapurna.test"));
        token.put(Who.LEAD, demo.login("neha@annapurna.test"));
        token.put(Who.LEAVER, demo.login("priya@annapurna.test"));
        token.put(Who.MEMBER, demo.login("arjun@annapurna.test"));
        token.put(Who.RECIPIENT, demo.login("rahul@annapurna.test"));
        arjunId = id("arjun@annapurna.test");
        rahulId = id("rahul@annapurna.test");
        adminUserId = id("admin@annapurna.test");

        // An outsider in the same company with the highest clearance and every group, on no project.
        String outsider = "outsider." + UUID.randomUUID().toString().substring(0, 6) + "@annapurna.test";
        UUID oid = jdbc.sql("""
                INSERT INTO app_user (email, display_name, password_hash, org_role, clearance)
                SELECT :e, 'Olive Outsider', password_hash, 'MEMBER', 3 FROM app_user WHERE email = 'neha@annapurna.test'
                RETURNING id
                """).param("e", outsider).query(UUID.class).single();
        jdbc.sql("INSERT INTO user_group_member (user_id, group_id) SELECT :u, id FROM user_group").param("u", oid).update();
        token.put(Who.OUTSIDER, demo.login(outsider));

        // Another company, in its own database.
        String dbName = "rbac_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        home.sql("CREATE DATABASE " + dbName).update();
        URI u = URI.create(db.getJdbcUrl().substring(5));
        String body = mvc.perform(MockMvcRequestBuilders.post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "203.0.113." + (int) (Math.random() * 200))
                .content(json.writeValueAsString(Map.of("slug", "rbac-" + UUID.randomUUID().toString().substring(0, 8),
                        "name", "Other Co", "database", Map.of("url", "postgresql://" + u.getHost() + ":" + u.getPort() + "/" + dbName,
                                "username", db.getUsername(), "password", db.getPassword()),
                        "admin", Map.of("displayName", "Fay Foreign", "email", "fay@other.example", "password", "other-company-password")))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        token.put(Who.FOREIGN, "Bearer " + JsonPath.read(body, "$.token"));

        // The lead's token with its payload edited to claim to be the admin.
        String[] parts = token.get(Who.LEAD).substring(7).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replaceFirst("\"sub\":\"[^\"]+\"", "\"sub\":\"" + adminUserId + "\"");
        token.put(Who.FORGED, "Bearer " + parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2]);

        // Ids to aim at.
        UUID hid = UUID.fromString(h);
        claim = jdbc.sql("SELECT id FROM claim WHERE handover_id = :h AND sensitivity <= 1 ORDER BY id LIMIT 1").param("h", hid).query(String.class).single();
        claimVersion = jdbc.sql("SELECT version FROM claim WHERE id = :c").param("c", UUID.fromString(claim)).query(Integer.class).single();
        otherClaim = jdbc.sql("SELECT id FROM claim WHERE handover_id = :h AND id <> :c ORDER BY id LIMIT 1")
                .param("h", hid).param("c", UUID.fromString(claim)).query(String.class).single();
        gap = jdbc.sql("SELECT id FROM gap_question WHERE handover_id = :h ORDER BY id LIMIT 1").param("h", hid).query(String.class).single();
        source = jdbc.sql("SELECT id FROM source WHERE project_id = :p AND sensitivity <= 1 ORDER BY id LIMIT 1").param("p", demo.projectId).query(String.class).single();
        chunk = jdbc.sql("""
                SELECT k.id FROM chunk k JOIN document d ON d.id = k.document_id WHERE d.source_id = :s ORDER BY k.id LIMIT 1
                """).param("s", UUID.fromString(source)).query(String.class).single();
        job = jdbc.sql("SELECT id FROM job WHERE project_id = :p ORDER BY created_at LIMIT 1").param("p", demo.projectId).query(String.class).single();
        group = jdbc.sql("SELECT id FROM user_group WHERE name = 'design'").query(String.class).single();

        // Approve and share with Rahul, so the lifecycle and recipient endpoints have something to aim at.
        jdbc.sql("UPDATE claim SET review_state = 'REJECTED' WHERE handover_id = :h AND status = 'CONFLICTING'").param("h", hid).update();
        jdbc.sql("UPDATE claim SET review_state = 'ACCEPTED' WHERE handover_id = :h AND review_state IN ('PENDING', 'NEEDS_INFO')").param("h", hid).update();
        send(new Call(HttpMethod.POST, "/api/handovers/" + h + "/approve", Map.of(), Set.of(), false), Who.LEAD);
        String shareBody = send(new Call(HttpMethod.POST, "/api/handovers/" + h + "/shares", Map.of("recipientId", rahulId), Set.of(), false), Who.LEAD)
                .getResponse().getContentAsString();
        share = JsonPath.read(shareBody, "$.id");
        item = jdbc.sql("""
                SELECT i.id FROM checklist_item i JOIN handover_share s ON s.handover_version_id = i.handover_version_id
                WHERE s.id = :s ORDER BY i.id LIMIT 1
                """).param("s", UUID.fromString(share)).query(String.class).single();
        claimVersion = jdbc.sql("SELECT version FROM claim WHERE id = :c").param("c", UUID.fromString(claim)).query(Integer.class).single();
    }

    private String id(String email) {
        return jdbc.sql("SELECT id FROM app_user WHERE email = :e").param("e", email).query(String.class).single();
    }

    private org.springframework.test.web.servlet.MvcResult send(Call c, Who who) throws Exception {
        org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?> req;
        if (c.multipart()) {
            req = MockMvcRequestBuilders.multipart(c.path())
                    .file(new MockMultipartFile("file", "note.md", "text/markdown", "Owner: Neha runs the deploys.".getBytes()))
                    .param("type", "DOCUMENT").param("title", "RBAC probe").param("sensitivity", "INTERNAL")
                    .param("authorisationAttested", "true");
        } else {
            req = MockMvcRequestBuilders.request(c.method(), c.path());
            if (c.body() != null) {
                req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(c.body()));
            }
        }
        if (who != Who.ANONYMOUS) {
            req.header("Authorization", token.get(who));
        }
        req.header("X-Forwarded-For", "198.18." + who.ordinal() + ".1");
        return mvc.perform(req).andReturn();
    }

    private static Set<Who> only(Who... who) {
        return who.length == 0 ? EnumSet.noneOf(Who.class) : EnumSet.of(who[0], who);
    }

    private List<Call> calls() {
        Set<Who> members = only(Who.LEAD, Who.LEAVER, Who.MEMBER, Who.RECIPIENT);
        Set<Who> leadOrLeaver = only(Who.LEAD, Who.LEAVER);
        Set<Who> lead = only(Who.LEAD);
        Set<Who> admin = only(Who.ADMIN);
        Set<Who> leadOrAdmin = only(Who.LEAD, Who.ADMIN);
        Set<Who> recipient = only(Who.RECIPIENT);
        String hv = "/api/handovers/" + h;
        List<Call> c = new ArrayList<>();
        // projects, sources, evidence, search, jobs
        c.add(new Call(HttpMethod.GET, "/api/projects/" + p, null, members, false));
        c.add(new Call(HttpMethod.GET, "/api/projects/" + p + "/handovers", null, members, false));
        c.add(new Call(HttpMethod.POST, "/api/projects/" + p + "/handovers", Map.of("departingUserId", arjunId, "title", "x", "scenario", "PLANNED"), lead, false));
        c.add(new Call(HttpMethod.GET, "/api/projects/" + p + "/sources", null, members, false));
        c.add(new Call(HttpMethod.POST, "/api/projects/" + p + "/sources", null, members, true));
        c.add(new Call(HttpMethod.GET, "/api/sources/" + source, null, members, false));
        c.add(new Call(HttpMethod.GET, "/api/sources/" + source + "/documents", null, members, false));
        c.add(new Call(HttpMethod.POST, "/api/sources/" + source + "/sync", null, lead, false));
        c.add(new Call(HttpMethod.GET, "/api/chunks/" + chunk, null, members, false));
        c.add(new Call(HttpMethod.GET, "/api/projects/" + p + "/search?q=deploy", null, members, false));
        c.add(new Call(HttpMethod.GET, "/api/jobs/" + job, null, members, false));
        c.add(new Call(HttpMethod.GET, "/api/projects/" + p + "/risk", null, leadOrAdmin, false));
        // the handover, claims and review
        c.add(new Call(HttpMethod.GET, hv, null, leadOrLeaver, false));
        c.add(new Call(HttpMethod.POST, hv + "/reconstruct", null, lead, false));
        c.add(new Call(HttpMethod.GET, hv + "/runs/latest", null, leadOrLeaver, false));
        c.add(new Call(HttpMethod.GET, hv + "/claims", null, leadOrLeaver, false));
        c.add(new Call(HttpMethod.GET, hv + "/gaps", null, leadOrLeaver, false));
        c.add(new Call(HttpMethod.GET, hv + "/timeline", null, leadOrLeaver, false));
        c.add(new Call(HttpMethod.GET, hv + "/summary", null, leadOrLeaver, false));
        c.add(new Call(HttpMethod.GET, "/api/claims/" + claim, null, leadOrLeaver, false));
        c.add(new Call(HttpMethod.GET, "/api/claims/" + claim + "/revisions", null, leadOrLeaver, false));
        c.add(new Call(HttpMethod.PATCH, "/api/claims/" + claim, Map.of("version", claimVersion, "reviewState", "REJECTED", "reason", "probe"), lead, false));
        c.add(new Call(HttpMethod.POST, "/api/claims/" + claim + "/resolve-conflict", Map.of("otherClaimId", otherClaim, "reason", "probe"), lead, false));
        c.add(new Call(HttpMethod.POST, hv + "/claims", Map.of("type", "RISK", "title", "Probe claim", "evidence", List.of()), lead, false));
        c.add(new Call(HttpMethod.POST, "/api/gaps/" + gap + "/answer", Map.of("answer", "Probe answer from someone"), only(Who.LEAVER), false));
        c.add(new Call(HttpMethod.POST, "/api/gaps/" + gap + "/skip", Map.of("reason", "probe"), leadOrLeaver, false));
        c.add(new Call(HttpMethod.GET, hv + "/preview?as=" + rahulId, null, lead, false));
        // approval, versions, sharing, insight, export
        c.add(new Call(HttpMethod.GET, hv + "/approval", null, lead, false));
        c.add(new Call(HttpMethod.POST, hv + "/approve", Map.of(), lead, false));
        c.add(new Call(HttpMethod.GET, hv + "/versions", null, lead, false));
        c.add(new Call(HttpMethod.GET, hv + "/diff?from=1&to=0", null, lead, false));
        c.add(new Call(HttpMethod.GET, hv + "/drift", null, lead, false));
        c.add(new Call(HttpMethod.POST, hv + "/reopen", Map.of(), lead, false));
        c.add(new Call(HttpMethod.GET, hv + "/shares", null, lead, false));
        c.add(new Call(HttpMethod.POST, hv + "/shares", Map.of("recipientId", arjunId), lead, false));
        c.add(new Call(HttpMethod.POST, hv + "/shares/move-to-latest", Map.of(), lead, false));
        c.add(new Call(HttpMethod.POST, "/api/shares/" + share + "/revoke", Map.of(), lead, false));
        c.add(new Call(HttpMethod.GET, hv + "/progress", null, lead, false));
        c.add(new Call(HttpMethod.GET, hv + "/versions/1/export", null, lead, false));
        // the recipient's share
        c.add(new Call(HttpMethod.GET, "/api/shares/" + share, null, recipient, false));
        c.add(new Call(HttpMethod.PUT, "/api/shares/" + share + "/checklist/" + item, Map.of("done", true), recipient, false));
        c.add(new Call(HttpMethod.POST, "/api/shares/" + share + "/acknowledge", Map.of(), recipient, false));
        c.add(new Call(HttpMethod.POST, "/api/shares/" + share + "/ask", Map.of("question", "Who owns payments?"), recipient, false));
        c.add(new Call(HttpMethod.GET, "/api/shares/" + share + "/questions", null, recipient, false));
        c.add(new Call(HttpMethod.POST, "/api/shares/" + share + "/questions", Map.of("question", "Where is the runbook?"), recipient, false));
        // teams and projects
        c.add(new Call(HttpMethod.GET, "/api/projects/" + p + "/team", null, leadOrAdmin, false));
        c.add(new Call(HttpMethod.POST, "/api/projects/" + p + "/members", Map.of("userId", adminUserId, "role", "MEMBER"), leadOrAdmin, false));
        c.add(new Call(HttpMethod.PATCH, "/api/projects/" + p + "/members/" + arjunId, Map.of("role", "PROJECT_LEAD"), leadOrAdmin, false));
        c.add(new Call(HttpMethod.DELETE, "/api/projects/" + p + "/members/" + arjunId, null, leadOrAdmin, false));
        c.add(new Call(HttpMethod.GET, "/api/directory", null, leadOrAdmin, false));
        c.add(new Call(HttpMethod.POST, "/api/projects", Map.of("name", "Probe project", "leadUserId", arjunId), admin, false));
        // organisation administration
        c.add(new Call(HttpMethod.GET, "/api/admin/users", null, admin, false));
        c.add(new Call(HttpMethod.POST, "/api/admin/users", Map.of("displayName", "Probe", "email", "probe@example.test"), admin, false));
        c.add(new Call(HttpMethod.PATCH, "/api/admin/users/" + arjunId, Map.of("orgRole", "ADMIN"), admin, false));
        c.add(new Call(HttpMethod.POST, "/api/admin/users/" + arjunId + "/reset-password", Map.of(), admin, false));
        c.add(new Call(HttpMethod.GET, "/api/admin/groups", null, admin, false));
        c.add(new Call(HttpMethod.POST, "/api/admin/groups", Map.of("name", "probe-group"), admin, false));
        c.add(new Call(HttpMethod.DELETE, "/api/admin/groups/" + group, null, admin, false));
        c.add(new Call(HttpMethod.GET, "/api/admin/audit", null, admin, false));
        c.add(new Call(HttpMethod.GET, "/api/admin/audit?action=LOGIN,USER_CREATED&q=a&from=2026-01-01", null, admin, false));
        c.add(new Call(HttpMethod.GET, "/api/admin/audit/actions", null, admin, false));
        c.add(new Call(HttpMethod.GET, "/api/admin/projects", null, admin, false));
        c.add(new Call(HttpMethod.GET, "/api/admin/workspace", null, admin, false));
        c.add(new Call(HttpMethod.POST, "/api/admin/workspace/disconnect", Map.of("confirm", "demo", "password", "anything-at-all"), admin, false));
        c.add(new Call(HttpMethod.GET, "/api/admin/ai", null, admin, false));
        c.add(new Call(HttpMethod.PUT, "/api/admin/ai", Map.of("provider", "GROQ", "apiKey", "gsk_probe_key_123456"), admin, false));
        c.add(new Call(HttpMethod.DELETE, "/api/admin/ai", null, admin, false));
        c.add(new Call(HttpMethod.POST, "/api/admin/ai/test", Map.of("provider", "GROQ", "apiKey", "gsk_probe_key_123456"), admin, false));
        c.add(new Call(HttpMethod.PUT, "/api/admin/integrations/SLACK", Map.of("secret", "xoxb-probe-token-123456"), admin, false));
        c.add(new Call(HttpMethod.DELETE, "/api/admin/integrations/SLACK", null, admin, false));
        c.add(new Call(HttpMethod.POST, "/api/admin/integrations/SLACK/test", Map.of("secret", "xoxb-probe-token-123456"), admin, false));
        c.add(new Call(HttpMethod.GET, "/api/admin/billing", null, admin, false));
        c.add(new Call(HttpMethod.POST, "/api/admin/billing/checkout", Map.of(), admin, false));
        return c;
    }

    /** Endpoints that act on the caller's own workspace and take no id from this one. */
    private boolean ownWorkspace(Call c) {
        String path = c.path();
        return (path.startsWith("/api/admin/") && !path.contains(arjunId) && !path.contains(group))
                || path.equals("/api/directory") || path.equals("/api/projects");
    }

    @Test
    void nobodyGetsInWhereTheyAreNotAllowed() throws Exception {
        List<String> leaks = new ArrayList<>();
        for (Call c : calls()) {
            for (Who who : Who.values()) {
                if (c.allowed().contains(who)) {
                    continue;
                }
                var res = send(c, who).getResponse();
                int code = res.getStatus();
                if (who == Who.FOREIGN && ownWorkspace(c)) {
                    // Another company's admin legitimately manages their own workspace with these endpoints. What
                    // must never happen is seeing anything of this company: its people, projects or handovers.
                    String body = res.getContentAsString().toLowerCase();
                    if (body.contains("annapurna") || body.contains(p) || body.contains(h) || body.contains(arjunId)) {
                        leaks.add(c.method() + " " + c.path() + " as FOREIGN returned this company's data");
                    }
                    continue;
                }
                if (code != 401 && code != 403 && code != 404) {
                    leaks.add(c.method() + " " + c.path().replace(h, "{handover}").replace(p, "{project}") + " as " + who + " -> " + code);
                }
            }
        }
        assertThat(leaks).as("Requests that were not refused").isEmpty();
    }

    @Test
    void theRightPeopleCanStillReadWhatTheyNeed() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Call c : calls()) {
            if (c.method() != HttpMethod.GET) {
                continue; // reads only: allowed writes are covered by the feature tests
            }
            for (Who who : c.allowed()) {
                int code = send(c, who).getResponse().getStatus();
                if (code != 200) {
                    wrong.add("GET " + c.path().replace(h, "{handover}").replace(p, "{project}") + " as " + who + " -> " + code);
                }
            }
        }
        assertThat(wrong).as("Allowed reads that failed").isEmpty();
    }

    @Test
    void listsOnlyShowWhatEachPersonMaySee() throws Exception {
        // A member who is not leaving sees no handovers in the list; the leaver sees only their own.
        String asMember = send(new Call(HttpMethod.GET, "/api/projects/" + p + "/handovers", null, Set.of(), false), Who.MEMBER).getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(asMember, "$")).isEmpty();
        // The recipient's share list never includes other people's shares.
        String mine = send(new Call(HttpMethod.GET, "/api/shares", null, Set.of(), false), Who.MEMBER).getResponse().getContentAsString();
        assertThat(mine).doesNotContain(share);
        // Search never returns passages above the searcher's clearance or outside their groups.
        String hits = send(new Call(HttpMethod.GET, "/api/projects/" + p + "/search?q=vendor%20contract", null, Set.of(), false), Who.RECIPIENT)
                .getResponse().getContentAsString();
        List<Integer> levels = JsonPath.read(hits, "$[*].sensitivity");
        assertThat(levels.stream().map(String::valueOf)).noneMatch(l -> l.equals("CONFIDENTIAL") || l.equals("RESTRICTED"));
        assertThat(hits).doesNotContain("\"CONFIDENTIAL\"").doesNotContain("\"RESTRICTED\"");
    }

    private int call(String tokenValue, HttpMethod method, String path, Object body) throws Exception {
        var req = MockMvcRequestBuilders.request(method, path).header("Authorization", tokenValue);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    private String loginAs(String email, String password) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders.post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "198.19." + (int) (Math.random() * 250) + ".1")
                .content(json.writeValueAsString(Map.of("workspace", "demo", "email", email, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }

    /** Creates a person through the admin API and has them choose their own password. */
    private String[] person(String orgRole) throws Exception {
        String email = "rbac." + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        String created = mvc.perform(MockMvcRequestBuilders.post("/api/admin/users").header("Authorization", token.get(Who.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("displayName", "Rbac Probe", "email", email, "orgRole", orgRole))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.user.id");
        String temp = JsonPath.read(created, "$.temporaryPassword");
        String t = loginAs(email, temp);
        assertThat(call(t, HttpMethod.POST, "/api/me/password", Map.of("currentPassword", temp, "newPassword", "probe-own-password-1"))).isEqualTo(204);
        return new String[] {id, loginAs(email, "probe-own-password-1")};
    }

    @Test
    void aTemporaryPasswordCannotBeUsedToSkipTheChange() throws Exception {
        String email = "rbac.temp." + UUID.randomUUID().toString().substring(0, 6) + "@example.test";
        String created = mvc.perform(MockMvcRequestBuilders.post("/api/admin/users").header("Authorization", token.get(Who.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("displayName", "Temp Person", "email", email, "orgRole", "ADMIN"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String temp = JsonPath.read(created, "$.temporaryPassword");
        String t = loginAs(email, temp);
        // Even an admin account cannot use anything with a temporary password, except its profile and the change.
        assertThat(call(t, HttpMethod.GET, "/api/me", null)).isEqualTo(200);
        assertThat(call(t, HttpMethod.GET, "/api/admin/users", null)).isEqualTo(403);
        assertThat(call(t, HttpMethod.GET, "/api/projects", null)).isEqualTo(403);
        assertThat(call(t, HttpMethod.POST, "/api/admin/groups", Map.of("name", "sneaky"))).isEqualTo(403);
        assertThat(call(t, HttpMethod.POST, "/api/me/password", Map.of("currentPassword", temp, "newPassword", "temp-person-own-1"))).isEqualTo(204);
        assertThat(call(t, HttpMethod.GET, "/api/admin/users", null)).isEqualTo(200);
    }

    @Test
    void permissionChangesApplyToTokensAlreadyIssued() throws Exception {
        // Demoted: the same token loses admin access at once.
        String[] a = person("ADMIN");
        assertThat(call(a[1], HttpMethod.GET, "/api/admin/users", null)).isEqualTo(200);
        assertThat(call(token.get(Who.ADMIN), HttpMethod.PATCH, "/api/admin/users/" + a[0], Map.of("orgRole", "MEMBER"))).isEqualTo(200);
        assertThat(call(a[1], HttpMethod.GET, "/api/admin/users", null)).isEqualTo(403);

        // Removed from the project: its data disappears for that token at once.
        String[] m = person("MEMBER");
        assertThat(call(token.get(Who.LEAD), HttpMethod.POST, "/api/projects/" + p + "/members", Map.of("userId", m[0], "role", "MEMBER"))).isIn(200, 201, 204);
        assertThat(call(m[1], HttpMethod.GET, "/api/projects/" + p, null)).isEqualTo(200);
        assertThat(call(m[1], HttpMethod.GET, "/api/projects/" + p + "/sources", null)).isEqualTo(200);
        assertThat(call(token.get(Who.LEAD), HttpMethod.DELETE, "/api/projects/" + p + "/members/" + m[0], null)).isIn(200, 204);
        assertThat(call(m[1], HttpMethod.GET, "/api/projects/" + p, null)).isEqualTo(404);
        assertThat(call(m[1], HttpMethod.GET, "/api/chunks/" + chunk, null)).isEqualTo(404);

        // Deactivated: signed out everywhere at once.
        assertThat(call(token.get(Who.ADMIN), HttpMethod.PATCH, "/api/admin/users/" + m[0], Map.of("active", false))).isEqualTo(200);
        assertThat(call(m[1], HttpMethod.GET, "/api/me", null)).isEqualTo(401);
    }
}
