package com.bramhastra.baton.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.bramhastra.baton.identity.DemoDataSeeder;
import com.bramhastra.baton.security.CryptoService;
import com.bramhastra.baton.tenant.Tenant;
import com.bramhastra.baton.tenant.TenantContext;
import com.bramhastra.baton.tenant.TenantRegistry;
import com.jayway.jsonpath.JsonPath;

/** Flow A end to end, plus the permission-leak checks for sources, chunks and search. */
@SpringBootTest(properties = "baton.embeddings.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
class SourceIngestApiTest {

    @Autowired
    TenantRegistry tenants;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CryptoService crypto;

    @Value("${baton.demo.password}")
    String demoPassword;

    UUID projectId;

    static final String NOTES = """
            # Vendor and deploy notes

            Date: 2026-10-08

            Canteen vendor contact is ramesh.vendor@canteenfoods.example.
            Render deploy key: GROQ_API_KEY=%s
            Decision: public launch on 15 November, owner Priya.
            """.formatted(RedactorTest.FAKE_GROQ_KEY);

    @BeforeEach
    void project() {
        projectId = jdbc.sql("SELECT id FROM project WHERE name = :n").param("n", DemoDataSeeder.PROJECT_NAME)
                .query(UUID.class).single();
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + demoPassword + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }

    private MockMultipartHttpServletRequestBuilder upload(String token, String type, String filename, String content) {
        MockMultipartHttpServletRequestBuilder b = multipart("/api/projects/" + projectId + "/sources")
                .file(new MockMultipartFile("file", filename, "text/markdown", content.getBytes(StandardCharsets.UTF_8)));
        b.header("Authorization", token);
        b.param("type", type);
        b.param("title", "Vendor and deploy notes");
        return b;
    }

    private String awaitJob(String token, String jobId) throws Exception {
        for (int i = 0; i < 100; i++) {
            String body = mvc.perform(get("/api/jobs/" + jobId).header("Authorization", token))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String st = JsonPath.read(body, "$.status");
            if (st.equals("SUCCEEDED") || st.equals("FAILED")) {
                return body;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Job did not finish");
    }

    @Test
    void confidentialNotesAreRedactedIndexedAndPermissionScoped() throws Exception {
        String neha = login("neha@annapurna.test");
        String created = mvc.perform(upload(neha, "MEETING_NOTES", "2026-10-08-vendor.md", NOTES)
                        .param("sensitivity", "CONFIDENTIAL")
                        .param("aclGroups", "engineering")
                        .param("authorisationAttested", "true"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String sourceId = JsonPath.read(created, "$.source.id");
        String job = awaitJob(neha, JsonPath.read(created, "$.jobId"));
        assertThat((String) JsonPath.read(job, "$.status")).isEqualTo("SUCCEEDED");
        assertThat((Integer) JsonPath.read(job, "$.progress.counts.redactions")).isEqualTo(2);

        // No raw secret or email anywhere in stored chunk text; tokens instead
        List<String> texts = jdbc.sql("""
                SELECT c.text FROM chunk c JOIN document d ON d.id = c.document_id WHERE d.source_id = :s
                """).param("s", UUID.fromString(sourceId)).query(String.class).list();
        assertThat(texts).isNotEmpty();
        assertThat(String.join("\n", texts))
                .doesNotContain(RedactorTest.FAKE_GROQ_KEY, "ramesh.vendor@canteenfoods.example")
                .contains("[API_KEY_1]", "[EMAIL_1]", "15 November");

        // Originals are kept only AES-GCM encrypted, and decrypt back
        byte[][] sealed = jdbc.sql("""
                SELECT t.ciphertext, t.iv FROM redaction_token t JOIN document d ON d.id = t.document_id
                WHERE d.source_id = :s AND t.token = '[API_KEY_1]'
                """).param("s", UUID.fromString(sourceId))
                .query((rs, n) -> new byte[][] {rs.getBytes(1), rs.getBytes(2)}).single();
        // Decryption uses the workspace's own data key, so the test must say which workspace it is in.
        Tenant demoWs = tenants.bySlug(TenantRegistry.DEMO_SLUG).orElseThrow();
        assertThat(new String(TenantContext.call(demoWs, () -> crypto.decrypt(sealed[0], sealed[1])), StandardCharsets.UTF_8))
                .isEqualTo(RedactorTest.FAKE_GROQ_KEY);

        // Staged upload is deleted after ingest
        assertThat(jdbc.sql("SELECT count(*) FROM source_blob WHERE source_id = :s")
                .param("s", UUID.fromString(sourceId)).query(Integer.class).single()).isZero();

        // Arjun (CONFIDENTIAL, engineering) finds it; the document date came from the file name
        String arjun = login("arjun@annapurna.test");
        String hits = mvc.perform(get("/api/projects/" + projectId + "/search").param("q", "public launch November")
                        .header("Authorization", arjun))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> hitChunks = JsonPath.read(hits, "$[*].chunkId");
        assertThat(hitChunks).isNotEmpty();
        String chunkId = hitChunks.get(0);
        mvc.perform(get("/api/chunks/" + chunkId).header("Authorization", arjun))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentTitle").value("Vendor and deploy notes"))
                .andExpect(jsonPath("$.authoredAt").value("2026-10-08T00:00:00Z"));

        // Rahul (INTERNAL, design) gets nothing: not in search, 404 on the chunk and the source
        String rahul = login("rahul@annapurna.test");
        mvc.perform(get("/api/projects/" + projectId + "/search").param("q", "public launch November")
                        .header("Authorization", rahul))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/chunks/" + chunkId).header("Authorization", rahul))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/sources/" + sourceId).header("Authorization", rahul))
                .andExpect(status().isNotFound());
        String rahulSources = mvc.perform(get("/api/projects/" + projectId + "/sources").header("Authorization", rahul))
                .andReturn().getResponse().getContentAsString();
        assertThat(rahulSources).doesNotContain(sourceId);

        // A non-member cannot see the job either
        String admin = login("admin@annapurna.test");
        mvc.perform(get("/api/jobs/" + JsonPath.read(created, "$.jobId")).header("Authorization", admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void sourceWithoutAttestationIsRejected() throws Exception {
        String neha = login("neha@annapurna.test");
        mvc.perform(upload(neha, "DOCUMENT", "notes.md", "hello"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ATTESTATION_REQUIRED"));
    }

    @Test
    void unsupportedFileTypeIs415() throws Exception {
        String neha = login("neha@annapurna.test");
        mvc.perform(upload(neha, "DOCUMENT", "tool.exe", "MZ").param("authorisationAttested", "true"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_TYPE"));
    }

    @Test
    void cannotClassifyAboveOwnClearance() throws Exception {
        String rahul = login("rahul@annapurna.test");
        mvc.perform(upload(rahul, "DOCUMENT", "notes.md", "hello").param("authorisationAttested", "true")
                        .param("sensitivity", "RESTRICTED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.sensitivity").exists());
    }
}
