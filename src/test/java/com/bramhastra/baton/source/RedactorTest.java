package com.bramhastra.baton.source;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class RedactorTest {

    private final Redactor redactor = new Redactor();

    public static final String FAKE_GROQ_KEY = "gsk_" + "FAKEplantedKEY1234567890abcdefXYZ";
    static final String FAKE_AWS_KEY = "AKIA" + "ABCDEFGHIJKLMNOP";

    @Test
    void redactsPlantedSecretsAndPersonalData() {
        String text = """
                Render deploy uses GROQ_API_KEY=%s and AWS %s.
                Vendor contact: ramesh.vendor@canteenfoods.example, phone +91 98765 43210.
                Admin password: Hunter2024!
                """.formatted(FAKE_GROQ_KEY, FAKE_AWS_KEY);

        Redactor.Result r = redactor.redact(text);

        assertThat(r.text())
                .doesNotContain(FAKE_GROQ_KEY, FAKE_AWS_KEY, "ramesh.vendor@canteenfoods.example",
                        "98765 43210", "Hunter2024!")
                .contains("[API_KEY_1]", "[API_KEY_2]", "[EMAIL_1]", "[PHONE_1]", "[PASSWORD_1]");
        assertThat(r.redactions()).extracting(Redactor.Redaction::original)
                .contains(FAKE_GROQ_KEY, FAKE_AWS_KEY, "Hunter2024!");
    }

    @Test
    void redactsTokensPastedInChatPhrasing() {
        String fake = "cf_FAKE_" + "t0ken_9f8e7d6c5b4a";
        Redactor.Result r = redactor.redact("token if anyone needs it: " + fake);
        assertThat(r.text()).doesNotContain(fake).contains("[API_KEY_1]");
        // Ordinary words and identifiers without digits after "key" stay readable.
        String plain = "The key decision: keep the registration-page-redesign branch for now.";
        assertThat(redactor.redact(plain).text()).isEqualTo(plain);
    }

    @Test
    void sameValueGetsSameToken() {
        Redactor.Result r = redactor.redact("Mail priya@club.example. Again: priya@club.example; also arjun@club.example");
        assertThat(r.text()).isEqualTo("Mail [EMAIL_1]. Again: [EMAIL_1]; also [EMAIL_2]");
        assertThat(r.redactions()).hasSize(2);
    }

    @Test
    void leavesDatesAndOrdinaryWordsAlone() {
        String text = "Launch moved to 2026-11-29 after the password reset bug; build 20261020 passed.";
        assertThat(redactor.redact(text).text()).isEqualTo(text);
    }

    @Test
    void redactsPrivateKeyBlocks() {
        String text = "key:\n-----BEGIN RSA PRIVATE KEY-----\nMIIabc\ndef\n-----END RSA PRIVATE KEY-----\nend";
        assertThat(redactor.redact(text).text()).isEqualTo("key:\n[SECRET_1]\nend");
    }
}
