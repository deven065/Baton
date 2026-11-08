package com.bramhastra.baton.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** sha256(promptId + model + system + user) → response. Recorded Groq answers replay offline. */
@Component
public class LlmCache {

    private final JdbcClient jdbc;

    public LlmCache(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static String key(LlmClient.LlmRequest r) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] {r.promptId(), r.model(), r.system(), r.user()}) {
                md.update(part.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<String> get(String key) {
        return jdbc.sql("SELECT response FROM llm_cache WHERE key = :k").param("k", key).query(String.class).optional();
    }

    public void put(String key, LlmClient.LlmRequest r, LlmClient.LlmResponse response) {
        jdbc.sql("""
                INSERT INTO llm_cache (key, prompt_id, model, response, tokens_in, tokens_out)
                VALUES (:k, :p, :m, :r, :ti, :to) ON CONFLICT (key) DO NOTHING
                """)
                .param("k", key).param("p", r.promptId()).param("m", r.model()).param("r", response.content())
                .param("ti", response.tokensIn()).param("to", response.tokensOut())
                .update();
    }
}
