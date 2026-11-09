package com.bramhastra.baton.ai;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.security.CryptoService;

/**
 * A workspace's own AI provider, kept in the company's database. The key is encrypted with the workspace key
 * and decrypted only in memory when a call is made; it is never returned by the API (only its last 4 characters).
 */
@Service
public class AiSettingsService {

    public enum Provider {
        GROQ("https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
        OPENAI("https://api.openai.com/v1", "gpt-4o-mini"),
        OPENROUTER("https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct"),
        CUSTOM(null, null);

        public final String baseUrl;
        public final String defaultModel;

        Provider(String baseUrl, String defaultModel) {
            this.baseUrl = baseUrl;
            this.defaultModel = defaultModel;
        }
    }

    /** What a call needs. Lives only in memory. */
    public record Active(Provider provider, String baseUrl, String model, String apiKey, Instant updatedAt) {
    }

    public record View(boolean configured, boolean enabled, Provider provider, String baseUrl, String model,
                       String keyHint, Instant updatedAt, String updatedByName) {
    }

    public record Usage(int calls, int failed, long tokensIn, long tokensOut, List<Map<String, Object>> byDay) {
    }

    private final JdbcClient jdbc;
    private final CryptoService crypto;
    private final AuditService audit;
    private final boolean allowPrivate;

    public AiSettingsService(JdbcClient jdbc, CryptoService crypto, AuditService audit,
                             @Value("${baton.tenants.allow-private-hosts:false}") boolean allowPrivate) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.audit = audit;
        this.allowPrivate = allowPrivate;
    }

    /** The provider to use for the current workspace, if it has one switched on. */
    @Transactional(readOnly = true)
    public Optional<Active> active() {
        return jdbc.sql("""
                SELECT provider, base_url, model, api_key_ciphertext, api_key_iv, updated_at
                FROM ai_setting WHERE enabled
                """).query((rs, n) -> new Active(Provider.valueOf(rs.getString("provider")), rs.getString("base_url"),
                rs.getString("model"),
                new String(crypto.decrypt(rs.getBytes("api_key_ciphertext"), rs.getBytes("api_key_iv")), StandardCharsets.UTF_8),
                rs.getTimestamp("updated_at").toInstant())).optional();
    }

    @Transactional(readOnly = true)
    public View view() {
        return jdbc.sql("""
                SELECT s.provider, s.base_url, s.model, s.key_hint, s.enabled, s.updated_at, u.display_name
                FROM ai_setting s LEFT JOIN app_user u ON u.id = s.updated_by
                """).query((rs, n) -> new View(true, rs.getBoolean("enabled"), Provider.valueOf(rs.getString("provider")),
                rs.getString("base_url"), rs.getString("model"), rs.getString("key_hint"),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("display_name")))
                .optional().orElse(new View(false, false, null, null, null, null, null, null));
    }

    /** Resolves what the admin typed into a provider, address and model, refusing unsafe addresses. */
    public Active resolve(Provider provider, String baseUrl, String model, String apiKey) {
        if (provider == null) {
            throw invalid("provider", "Choose a provider");
        }
        String url;
        try {
            url = ProviderUrl.check(provider == Provider.CUSTOM ? baseUrl : provider.baseUrl, allowPrivate);
        } catch (IllegalArgumentException e) {
            throw invalid("baseUrl", e.getMessage());
        }
        String m = model == null || model.isBlank() ? provider.defaultModel : model.strip();
        if (m == null || m.length() > 120) {
            throw invalid("model", "Enter the model name, e.g. llama-3.3-70b-versatile");
        }
        return new Active(provider, url, m, apiKey == null ? null : apiKey.strip(), null);
    }

    /** Saves the settings. A blank key keeps the stored one (so the admin never has to see it again). */
    @Transactional
    public View save(UUID actor, Provider provider, String baseUrl, String model, String apiKey, boolean enabled) {
        Active a = resolve(provider, baseUrl, model, apiKey);
        boolean newKey = a.apiKey() != null && !a.apiKey().isEmpty();
        boolean exists = jdbc.sql("SELECT count(*) FROM ai_setting").query(Integer.class).single() > 0;
        if (!newKey && !exists) {
            throw invalid("apiKey", "Paste your API key");
        }
        if (newKey) {
            if (a.apiKey().length() < 8 || a.apiKey().length() > 400 || a.apiKey().chars().anyMatch(Character::isWhitespace)) {
                throw invalid("apiKey", "That does not look like an API key");
            }
            CryptoService.Sealed sealed = crypto.encrypt(a.apiKey().getBytes(StandardCharsets.UTF_8));
            String hint = a.apiKey().substring(a.apiKey().length() - 4);
            jdbc.sql("""
                    INSERT INTO ai_setting (id, provider, base_url, model, api_key_ciphertext, api_key_iv, key_hint, enabled, updated_by, updated_at)
                    VALUES (1, :p, :u, :m, :c, :iv, :h, :e, :by, now())
                    ON CONFLICT (id) DO UPDATE SET provider = :p, base_url = :u, model = :m, api_key_ciphertext = :c,
                        api_key_iv = :iv, key_hint = :h, enabled = :e, updated_by = :by, updated_at = now()
                    """).param("p", a.provider().name()).param("u", a.baseUrl()).param("m", a.model())
                    .param("c", sealed.ciphertext()).param("iv", sealed.iv()).param("h", hint).param("e", enabled)
                    .param("by", actor).update();
        } else {
            jdbc.sql("""
                    UPDATE ai_setting SET provider = :p, base_url = :u, model = :m, enabled = :e, updated_by = :by, updated_at = now()
                    """).param("p", a.provider().name()).param("u", a.baseUrl()).param("m", a.model())
                    .param("e", enabled).param("by", actor).update();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("provider", a.provider().name());
        details.put("model", a.model());
        details.put("enabled", enabled);
        details.put("keyChanged", newKey);
        audit.record(actor, "AI_SETTINGS_CHANGED", "ai_setting", null, null, details);
        return view();
    }

    /** Deletes the key and settings; reconstruction goes back to the offline extractor. */
    @Transactional
    public void remove(UUID actor) {
        if (jdbc.sql("DELETE FROM ai_setting").update() > 0) {
            audit.record(actor, "AI_SETTINGS_REMOVED", "ai_setting", null, null, Map.of());
        }
    }

    /** The stored key, for "Test" without retyping it. */
    @Transactional(readOnly = true)
    public Optional<String> storedKey() {
        return jdbc.sql("SELECT api_key_ciphertext, api_key_iv FROM ai_setting")
                .query((rs, n) -> new String(crypto.decrypt(rs.getBytes(1), rs.getBytes(2)), StandardCharsets.UTF_8))
                .optional();
    }

    public void recordCall(Provider provider, String model, String promptId, int tokensIn, int tokensOut, boolean ok) {
        jdbc.sql("""
                INSERT INTO ai_call (provider, model, prompt_id, tokens_in, tokens_out, ok) VALUES (:p, :m, :id, :ti, :to, :ok)
                """).param("p", provider.name()).param("m", model).param("id", promptId).param("ti", tokensIn)
                .param("to", tokensOut).param("ok", ok).update();
    }

    @Transactional(readOnly = true)
    public Usage usage() {
        Timestamp since = Timestamp.from(Instant.now().minusSeconds(30L * 86400));
        Map<String, Object> total = jdbc.sql("""
                SELECT count(*) AS calls, count(*) FILTER (WHERE NOT ok) AS failed,
                       COALESCE(sum(tokens_in), 0) AS tin, COALESCE(sum(tokens_out), 0) AS tout
                FROM ai_call WHERE at >= :s
                """).param("s", since).query().singleRow();
        List<Map<String, Object>> days = jdbc.sql("""
                SELECT to_char(date_trunc('day', at), 'YYYY-MM-DD') AS day, count(*) AS calls,
                       COALESCE(sum(tokens_in + tokens_out), 0) AS tokens
                FROM ai_call WHERE at >= :s GROUP BY 1 ORDER BY 1
                """).param("s", since).query().listOfRows();
        return new Usage(((Number) total.get("calls")).intValue(), ((Number) total.get("failed")).intValue(),
                ((Number) total.get("tin")).longValue(), ((Number) total.get("tout")).longValue(), days);
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed", Map.of(field, message));
    }
}
