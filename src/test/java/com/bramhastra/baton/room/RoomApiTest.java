package com.bramhastra.baton.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.bramhastra.baton.DemoFixture;
import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.jayway.jsonpath.JsonPath;

/** The team room: chat for the people on a project, and the set-up side of its video call. */
@SpringBootTest(properties = {"baton.embeddings.enabled=false", "baton.ai.mock=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfigurationAccess.Config.class)
class RoomApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Value("${baton.demo.password}")
    String demoPassword;

    private ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        req.header("Authorization", token);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(new tools.jackson.databind.ObjectMapper().writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    @Test
    void membersChatAndOutsidersCannot() throws Exception {
        DemoFixture demo = new DemoFixture(mvc, jdbc, demoPassword).createProject("Room chat");
        String neha = demo.login("neha@annapurna.test");
        String priya = demo.login("priya@annapurna.test");
        String admin = demo.login("admin@annapurna.test");
        String url = "/api/projects/" + demo.projectId + "/room/chat";

        send(post(url), neha, Map.of("body", "  Who has the vendor's number?  ")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderName").value("Neha Kulkarni")).andExpect(jsonPath("$.body").value("Who has the vendor's number?"));
        send(post(url), priya, Map.of("body", "I do. I'll add it to my exit answers.")).andExpect(status().isCreated());

        // Oldest first, for every member, with who said what.
        String list = send(get(url), priya, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> names = JsonPath.read(list, "$[*].senderName");
        assertThat(names).containsExactly("Neha Kulkarni", "Priya Sharma");

        // Rules: not empty, not too long, no keys or passwords.
        send(post(url), neha, Map.of("body", "   ")).andExpect(status().isBadRequest());
        send(post(url), neha, Map.of("body", "x".repeat(2001))).andExpect(status().isBadRequest());
        send(post(url), neha, Map.of("body", "the admin password is Hunter2024! ok?")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.body").value(org.hamcrest.Matchers.containsString("key or password")));

        // An organisation admin who is not on the project, or a made-up project, finds nothing.
        send(get(url), admin, null).andExpect(status().isNotFound());
        send(post(url), admin, Map.of("body", "hello")).andExpect(status().isNotFound());
        send(get("/api/projects/" + UUID.randomUUID() + "/room/chat"), neha, null).andExpect(status().isNotFound());
    }

    @Test
    void theCallPassesSetupMessagesOnlyBetweenPeopleOnIt() throws Exception {
        DemoFixture demo = new DemoFixture(mvc, jdbc, demoPassword).createProject("Room call");
        String neha = demo.login("neha@annapurna.test");
        String priya = demo.login("priya@annapurna.test");
        String arjun = demo.login("arjun@annapurna.test");
        String admin = demo.login("admin@annapurna.test");
        String base = "/api/projects/" + demo.projectId + "/room/call/";
        UUID nehaId = jdbc.sql("SELECT id FROM app_user WHERE email = 'neha@annapurna.test'").query(UUID.class).single();
        UUID priyaId = jdbc.sql("SELECT id FROM app_user WHERE email = 'priya@annapurna.test'").query(UUID.class).single();
        UUID arjunId = jdbc.sql("SELECT id FROM app_user WHERE email = 'arjun@annapurna.test'").query(UUID.class).single();

        send(post(base + "join"), neha, null).andExpect(status().isOk()).andExpect(jsonPath("$.others.length()").value(0))
                .andExpect(jsonPath("$.maxInCall").value(4)).andExpect(jsonPath("$.iceServers[0].urls[0]").value(org.hamcrest.Matchers.startsWith("stun:")));
        send(post(base + "join"), priya, null).andExpect(status().isOk()).andExpect(jsonPath("$.others[0].name").value("Neha Kulkarni"));
        send(post(base + "join"), admin, null).andExpect(status().isNotFound());

        Map<String, Object> offer = Map.of("to", priyaId.toString(), "kind", "offer", "data", "{\"sdp\":\"v=0\"}");
        send(post(base + "signal"), neha, offer).andExpect(status().isNoContent());
        send(post(base + "signal"), neha, Map.of("to", priyaId.toString(), "kind", "shout", "data", "x")).andExpect(status().isBadRequest());
        send(post(base + "signal"), neha, Map.of("to", priyaId.toString(), "kind", "ice", "data", "x".repeat(20_001))).andExpect(status().isBadRequest());
        // Arjun is on the project but not on the call, so he can neither send into it nor be sent to.
        send(post(base + "signal"), arjun, Map.of("to", nehaId.toString(), "kind", "offer", "data", "{}")).andExpect(status().isNotFound());
        send(post(base + "signal"), neha, Map.of("to", arjunId.toString(), "kind", "offer", "data", "{}")).andExpect(status().isNotFound());

        send(post(base + "leave"), priya, null).andExpect(status().isNoContent());
        send(post(base + "signal"), neha, offer).andExpect(status().isNotFound());
    }

    @Test
    void aCallHoldsFourPeopleAndNoMore() {
        RoomHub hub = new RoomHub();
        UUID project = UUID.randomUUID();
        for (int i = 0; i < RoomHub.MAX_IN_CALL; i++) {
            assertThat(hub.join(project, UUID.randomUUID(), "Person " + i)).isTrue();
        }
        assertThat(hub.join(project, UUID.randomUUID(), "One too many")).isFalse();
        UUID first = hub.participants(project).getFirst().userId();
        assertThat(hub.join(project, first, "Again")).isTrue(); // already in: harmless
        hub.leave(project, first);
        assertThat(hub.join(project, UUID.randomUUID(), "Now there is room")).isTrue();
        assertThat(hub.participants(project)).hasSize(RoomHub.MAX_IN_CALL);
    }
}
