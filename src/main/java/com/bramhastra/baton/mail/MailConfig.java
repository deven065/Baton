package com.bramhastra.baton.mail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.bramhastra.baton.common.SafeLog;

import tools.jackson.databind.ObjectMapper;

@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    Mailer mailer(@Value("${baton.mail.resend-api-key:}") String apiKey,
                  @Value("${baton.mail.from:Baton <onboarding@resend.dev>}") String from,
                  @Value("${baton.app-url:http://localhost:5173}") String appUrl,
                  ObjectMapper json) {
        String base = appUrl.replaceAll("/+$", "");
        if (apiKey == null || apiKey.isBlank()) {
            log.info("Email: off (set RESEND_API_KEY to send password-reset and invitation emails)");
            return new Disabled(base);
        }
        log.info("Email: on (Resend)");
        return new Resend(apiKey.strip(), from, base, json);
    }

    static final class Disabled implements Mailer {
        private final String base;

        Disabled(String base) {
            this.base = base;
        }

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void send(String to, String subject, String text) {
            // nothing to send with
        }

        @Override
        public String link(String pathAndQuery) {
            return base + pathAndQuery;
        }
    }

    /** Resend's HTTP API (https://resend.com/docs/api-reference/emails/send-email); no SMTP library needed. */
    static final class Resend implements Mailer {
        private final String apiKey;
        private final String from;
        private final String base;
        private final ObjectMapper json;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "mail");
            t.setDaemon(true);
            return t;
        });

        Resend(String apiKey, String from, String base, ObjectMapper json) {
            this.apiKey = apiKey;
            this.from = from;
            this.base = base;
            this.json = json;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void send(String to, String subject, String text) {
            String body = json.writeValueAsString(Map.of("from", from, "to", List.of(to), "subject", subject, "text", text));
            pool.execute(() -> {
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.resend.com/emails"))
                            .timeout(Duration.ofSeconds(20))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                    int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                    if (status / 100 != 2) {
                        // Never log the address or the text: only that it failed and how.
                        log.warn("Email not sent: provider answered HTTP {}", status);
                    }
                } catch (Exception e) {
                    log.warn("Email not sent: {}", SafeLog.describe(e));
                }
            });
        }

        @Override
        public String link(String pathAndQuery) {
            return base + pathAndQuery;
        }
    }
}
