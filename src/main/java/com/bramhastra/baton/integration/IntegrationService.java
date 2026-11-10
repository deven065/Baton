package com.bramhastra.baton.integration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.security.CryptoService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A workspace's own credentials for GitHub, Slack and Google Drive, kept encrypted in the company's database.
 * Secrets are decrypted only in memory while a connector reads; the API never returns them.
 */
@Service
public class IntegrationService {

    public enum Kind { GITHUB, SLACK, GOOGLE_DRIVE }

    public record View(Kind kind, boolean connected, String hint, Instant updatedAt, String updatedByName) {
    }

    private final JdbcClient jdbc;
    private final CryptoService crypto;
    private final AuditService audit;
    private final ObjectMapper json;

    public IntegrationService(JdbcClient jdbc, CryptoService crypto, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<View> list() {
        Map<String, View> stored = new java.util.HashMap<>();
        jdbc.sql("""
                SELECT i.kind, i.hint, i.updated_at, u.display_name FROM integration i
                LEFT JOIN app_user u ON u.id = i.updated_by
                """).query((rs, n) -> new View(Kind.valueOf(rs.getString(1)), true, rs.getString(2),
                rs.getTimestamp(3).toInstant(), rs.getString(4))).list().forEach(v -> stored.put(v.kind().name(), v));
        List<View> out = new ArrayList<>();
        for (Kind k : Kind.values()) {
            out.add(stored.getOrDefault(k.name(), new View(k, false, null, null, null)));
        }
        return out;
    }

    /** The decrypted secret for the current workspace, if connected. */
    @Transactional(readOnly = true)
    public Optional<String> secret(Kind kind) {
        return jdbc.sql("SELECT secret_ciphertext, secret_iv FROM integration WHERE kind = :k").param("k", kind.name())
                .query((rs, n) -> new String(crypto.decrypt(rs.getBytes(1), rs.getBytes(2)), StandardCharsets.UTF_8))
                .optional();
    }

    public String require(Kind kind) {
        return secret(kind).orElseThrow(() -> new IllegalArgumentException(label(kind)
                + " is not connected. An admin can connect it under Organisation → Connections."));
    }

    /** Checks the shape of a secret and returns the hint to show for it. Throws with a message for the admin. */
    public String hintFor(Kind kind, String secret) {
        String s = secret == null ? "" : secret.strip();
        switch (kind) {
            case GITHUB -> {
                if (s.length() < 20 || s.length() > 400 || s.chars().anyMatch(Character::isWhitespace)) {
                    throw invalid("Paste a GitHub token (a fine-grained, read-only token is best)");
                }
                return "token ending " + s.substring(s.length() - 4);
            }
            case SLACK -> {
                if (!s.startsWith("xoxb-") || s.length() > 400 || s.chars().anyMatch(Character::isWhitespace)) {
                    throw invalid("Paste the Bot User OAuth Token of your Slack app (it starts with xoxb-)");
                }
                return "bot token ending " + s.substring(s.length() - 4);
            }
            case GOOGLE_DRIVE -> {
                try {
                    JsonNode key = json.readTree(s);
                    String email = key.path("client_email").asString("");
                    if (!"service_account".equals(key.path("type").asString("")) || email.isBlank()
                            || !key.path("private_key").asString("").contains("PRIVATE KEY")) {
                        throw invalid("That is not a service-account key file");
                    }
                    return email.length() > 110 ? email.substring(0, 110) : email;
                } catch (ApiException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw invalid("Paste the whole JSON key file of a Google service account");
                }
            }
            default -> throw invalid("Unknown connection");
        }
    }

    @Transactional
    public View save(UUID actor, Kind kind, String secret) {
        String hint = hintFor(kind, secret);
        CryptoService.Sealed sealed = crypto.encrypt(secret.strip().getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO integration (kind, secret_ciphertext, secret_iv, hint, updated_by, updated_at)
                VALUES (:k, :c, :iv, :h, :by, now())
                ON CONFLICT (kind) DO UPDATE SET secret_ciphertext = :c, secret_iv = :iv, hint = :h, updated_by = :by, updated_at = now()
                """).param("k", kind.name()).param("c", sealed.ciphertext()).param("iv", sealed.iv()).param("h", hint)
                .param("by", actor).update();
        audit.record(actor, "INTEGRATION_CONNECTED", "integration", null, null, Map.of("kind", kind.name()));
        return list().stream().filter(v -> v.kind() == kind).findFirst().orElseThrow();
    }

    @Transactional
    public void remove(UUID actor, Kind kind) {
        if (jdbc.sql("DELETE FROM integration WHERE kind = :k").param("k", kind.name()).update() > 0) {
            audit.record(actor, "INTEGRATION_REMOVED", "integration", null, null, Map.of("kind", kind.name()));
        }
    }

    public static String label(Kind kind) {
        return switch (kind) {
            case GITHUB -> "GitHub";
            case SLACK -> "Slack";
            case GOOGLE_DRIVE -> "Google Drive";
        };
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed", Map.of("secret", message));
    }
}
