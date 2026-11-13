package com.bramhastra.baton.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
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
import com.sun.net.httpserver.HttpServer;

/**
 * Meeting recordings end to end: a Teams-style transcript works with no AI at all; an audio file is refused until
 * the workspace has a provider, then transcribed (fake Whisper), labelled by speaker (fake chat model), stored with
 * timestamps and kept encrypted for playback; claims citing it carry the second it was said; the lead can delete the
 * audio and keep the transcript.
 */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecordingApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Value("${baton.demo.password}")
    String demoPassword;

    HttpServer provider;
    final List<String> paths = new CopyOnWriteArrayList<>();
    DemoFixture demo;
    String admin;
    String neha;

    @BeforeAll
    void setUp() throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/audio/transcriptions", ex -> {
            paths.add("transcriptions " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1)
                    .contains("whisper-1"));
            reply(ex, """
                    {"text":"...","segments":[
                      {"start":0.0,"end":6.5,"text":" Hi Arjun, I'm Priya. This is my handover call."},
                      {"start":7.8,"end":12.0,"text":" Hi Priya, Arjun here."},
                      {"start":74.2,"end":80.0,"text":" Decision: the dark mode toggle moves to the January release."}]}
                    """);
        });
        provider.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            paths.add("chat");
            String content = body.contains("speaker turns")
                    ? "{\\\"speakers\\\":[{\\\"i\\\":1,\\\"speaker\\\":\\\"Priya Sharma\\\"},{\\\"i\\\":2,\\\"speaker\\\":\\\"Arjun Mehta\\\"},{\\\"i\\\":3,\\\"speaker\\\":\\\"Priya Sharma\\\"}]}"
                    : "{\\\"claims\\\":[]}";
            reply(ex, "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
        });
        provider.start();
        demo = new DemoFixture(mvc, jdbc, demoPassword).createProject("Recordings");
        admin = demo.login("admin@annapurna.test");
        neha = demo.login("neha@annapurna.test");
    }

    @AfterAll
    void tearDown() throws Exception {
        mvc.perform(delete("/api/admin/ai").header("Authorization", admin));
        provider.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String body) throws java.io.IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private ResultActions upload(String name, byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/projects/" + demo.projectId + "/sources")
                .file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
                .param("type", "MEETING_RECORDING").param("title", "Handover call").param("sensitivity", "INTERNAL")
                .param("meetingDate", "2026-11-05").param("authorisationAttested", "true")
                .header("Authorization", neha));
    }

    private String sourceOf(String body) {
        return JsonPath.read(body, "$.source.id");
    }

    @Test
    void transcriptFileWorksWithoutAnyAiAndClaimsKnowTheMoment() throws Exception {
        byte[] vtt = Files.readAllBytes(Path.of("demo", "recordings", "annapurna-handover-call.vtt"));
        String body = upload("annapurna-handover-call.vtt", vtt).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(demo.awaitJob(neha, JsonPath.read(body, "$.jobId"))).isEqualTo("SUCCEEDED");
        String source = sourceOf(body);
        String text = jdbc.sql("SELECT string_agg(k.text, '') FROM chunk k JOIN document d ON d.id = k.document_id WHERE d.source_id = :s")
                .param("s", UUID.fromString(source)).query(String.class).single();
        assertThat(text).contains("] Priya Sharma: First, payments.").contains("[00:00:07] Arjun Mehta:");
        assertThat(jdbc.sql("SELECT authored_at::date::text FROM document WHERE source_id = :s")
                .param("s", UUID.fromString(source)).query(String.class).single()).isEqualTo("2026-11-05");

        // No audio was uploaded, so there is nothing to play.
        mvc.perform(get("/api/sources/" + source + "/media").header("Authorization", neha)).andExpect(status().isNotFound());

        // Reconstruct (offline rules) and check that evidence from the call carries its second.
        UUID priya = jdbc.sql("SELECT id FROM app_user WHERE email = 'priya@annapurna.test'").query(UUID.class).single();
        String created = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/projects/" + demo.projectId + "/handovers").header("Authorization", neha)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departingUserId\":\"" + priya + "\",\"title\":\"Call test\",\"scenario\":\"PLANNED\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        demo.handoverId = JsonPath.read(created, "$.id");
        demo.reconstruct();
        List<Map<String, Object>> evidence = JsonPath.read(
                demo.getJson(neha, "/api/handovers/" + demo.handoverId + "/claims?size=200"),
                "$.items[*].evidence[?(@.source.type == 'MEETING_RECORDING')]");
        assertThat(evidence).isNotEmpty();
        assertThat(evidence).allSatisfy(e -> {
            assertThat(e.get("atSeconds")).isNotNull();
            assertThat(e.get("sourceId")).isEqualTo(source);
        });
        // "It still breaks every Monday morning." was said at 0:50 in the call; quotes no longer carry the prefix.
        assertThat(evidence).anyMatch(e -> e.get("quote").toString().equals("It still breaks every Monday morning.")
                && ((Number) e.get("atSeconds")).intValue() == 50);
        assertThat(evidence).noneMatch(e -> e.get("quote").toString().startsWith("[00:"));
    }

    @Test
    void audioIsTranscribedByTheWorkspaceProviderKeptForPlaybackAndDeletable() throws Exception {
        byte[] wav = Files.readAllBytes(Path.of("demo", "recordings", "annapurna-handover-call.wav"));

        // Without a provider: refused with a clear way forward.
        mvc.perform(delete("/api/admin/ai").header("Authorization", admin));
        upload("call.wav", wav).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(".vtt")));

        mvc.perform(put("/api/admin/ai").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"CUSTOM\",\"baseUrl\":\"http://127.0.0.1:" + provider.getAddress().getPort()
                        + "/v1\",\"model\":\"company-model\",\"apiKey\":\"company-key-12345678\",\"enabled\":true}"))
                .andExpect(status().isOk());
        String body = upload("call.wav", wav).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(demo.awaitJob(neha, JsonPath.read(body, "$.jobId"))).isEqualTo("SUCCEEDED");
        assertThat(paths).contains("transcriptions true", "chat");
        String source = sourceOf(body);
        String text = jdbc.sql("SELECT string_agg(k.text, '') FROM chunk k JOIN document d ON d.id = k.document_id WHERE d.source_id = :s")
                .param("s", UUID.fromString(source)).query(String.class).single();
        assertThat(text).contains("suggested by AI")
                .contains("[00:00:00] Priya Sharma: Hi Arjun")
                .contains("[00:01:14] Priya Sharma: Decision: the dark mode toggle moves to the January release.");

        // The audio plays back, byte for byte, for people who can see the source; never cached.
        byte[] played = mvc.perform(get("/api/sources/" + source + "/media").header("Authorization", neha))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "audio/wav"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(played).isEqualTo(wav);
        String rahul = demo.login("rahul@annapurna.test");
        mvc.perform(delete("/api/sources/" + source + "/media").header("Authorization", rahul)).andExpect(status().isForbidden());

        // The lead deletes the audio; the transcript stays.
        mvc.perform(delete("/api/sources/" + source + "/media").header("Authorization", neha)).andExpect(status().isNoContent());
        mvc.perform(get("/api/sources/" + source + "/media").header("Authorization", neha)).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM chunk k JOIN document d ON d.id = k.document_id WHERE d.source_id = :s")
                .param("s", UUID.fromString(source)).query(Integer.class).single()).isPositive();
    }
}
