package com.bramhastra.baton.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.bramhastra.baton.TestcontainersConfigurationAccess;
import com.bramhastra.baton.mail.Mailer;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.ObjectMapper;

/** Gap fill: "forgot password" and email invitations, with a recording mailer in place of the provider. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfigurationAccess.Config.class, PasswordResetApiTest.Mail.class})
class PasswordResetApiTest {

    record Sent(String to, String subject, String text) {
    }

    static final List<Sent> OUTBOX = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Mail {
        @Bean
        @Primary
        Mailer recordingMailer() {
            return new Mailer() {
                public boolean enabled() {
                    return true;
                }

                public void send(String to, String subject, String text) {
                    OUTBOX.add(new Sent(to, subject, text));
                }

                public String link(String pathAndQuery) {
                    return "https://app.example" + pathAndQuery;
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Value("${baton.demo.password}")
    String demoPassword;

    @BeforeEach
    void clear() {
        OUTBOX.clear();
    }

    private ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        req.header("X-Forwarded-For", "198.51.100." + (int) (Math.random() * 250));
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    private String login(String email, String password) throws Exception {
        return JsonPath.read(send(post("/api/auth/login"), null, Map.of("workspace", "demo", "email", email, "password", password))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private static String token(Sent mail) {
        Matcher m = Pattern.compile("/reset-password\\?ws=demo&token=([A-Za-z0-9_-]+)").matcher(mail.text());
        assertThat(m.find()).as(mail.text()).isTrue();
        return m.group(1);
    }

    @Test
    void newPeopleGetALinkToChooseTheirOwnPassword() throws Exception {
        send(get("/api/auth/options"), null, null).andExpect(jsonPath("$.passwordResetByEmail").value(true));
        String admin = login("admin@annapurna.test", demoPassword);
        String email = "new." + UUID.randomUUID().toString().substring(0, 6) + "@annapurna.test";
        send(post("/api/admin/users"), admin, Map.of("displayName", "Nia New", "email", email))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.invitedByEmail").value(true));
        assertThat(OUTBOX).hasSize(1);
        Sent invite = OUTBOX.get(0);
        assertThat(invite.to()).isEqualTo(email);
        assertThat(invite.subject()).contains("added you");

        String t = token(invite);
        send(post("/api/auth/reset-password"), null, Map.of("workspace", "demo", "token", t, "newPassword", "nia-chosen-password"))
                .andExpect(status().isNoContent());
        String nia = login(email, "nia-chosen-password");
        send(get("/api/me"), nia, null).andExpect(jsonPath("$.mustChangePassword").value(false));
        // A link works once.
        send(post("/api/auth/reset-password"), null, Map.of("workspace", "demo", "token", t, "newPassword", "another-password-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.token").exists());
    }

    @Test
    void forgotPasswordNeverRevealsWhetherAnAccountExists() throws Exception {
        send(post("/api/auth/forgot-password"), null, Map.of("workspace", "demo", "email", "nobody@annapurna.test"))
                .andExpect(status().isAccepted());
        send(post("/api/auth/forgot-password"), null, Map.of("workspace", "no-such-workspace", "email", "rahul@annapurna.test"))
                .andExpect(status().isAccepted());
        assertThat(OUTBOX).isEmpty();

        send(post("/api/auth/forgot-password"), null, Map.of("workspace", "Demo", "email", "Arjun@annapurna.test"))
                .andExpect(status().isAccepted());
        assertThat(OUTBOX).hasSize(1);
        assertThat(OUTBOX.get(0).to()).isEqualTo("arjun@annapurna.test");

        send(post("/api/auth/reset-password"), null, Map.of("workspace", "demo", "token", "made-up-token", "newPassword", "arjun-new-password"))
                .andExpect(status().isBadRequest());
        send(post("/api/auth/reset-password"), null, Map.of("workspace", "demo", "token", token(OUTBOX.get(0)), "newPassword", "short"))
                .andExpect(status().isBadRequest());
        send(post("/api/auth/reset-password"), null, Map.of("workspace", "demo", "token", token(OUTBOX.get(0)), "newPassword", "arjun-new-password"))
                .andExpect(status().isNoContent());
        login("arjun@annapurna.test", "arjun-new-password");
        // Put the demo password back for the other tests sharing this database.
        send(post("/api/auth/forgot-password"), null, Map.of("workspace", "demo", "email", "arjun@annapurna.test"))
                .andExpect(status().isAccepted());
        send(post("/api/auth/reset-password"), null, Map.of("workspace", "demo", "token", token(OUTBOX.get(1)), "newPassword", demoPassword.length() >= 12 ? demoPassword : demoPassword + "-padding"))
                .andExpect(status().isNoContent());
    }
}
