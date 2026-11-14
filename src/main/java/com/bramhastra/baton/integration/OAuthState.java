package com.bramhastra.baton.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The {@code state} parameter of a one-click connection: which workspace, which admin and which tool, signed with a
 * key derived from the server's JWT secret and valid for ten minutes. It is deliberately not a JWT: the same key signs
 * sign-in tokens, and this value passes through browser URLs, so it must never be usable as a bearer token.
 */
@Component
public class OAuthState {

    static final Duration TTL = Duration.ofMinutes(10);

    public record Payload(UUID tenantId, UUID userId, IntegrationService.Kind kind) {
    }

    private final byte[] key;

    public OAuthState(@Value("${baton.jwt.secret}") String secret) {
        try {
            // A separate key for this purpose: HMAC of a label under the JWT secret.
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            this.key = m.doFinal("baton-oauth-state-v1".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String sign(UUID tenantId, UUID userId, IntegrationService.Kind kind) {
        return sign(tenantId, userId, kind, Instant.now().plus(TTL));
    }

    String sign(UUID tenantId, UUID userId, IntegrationService.Kind kind, Instant expires) {
        String body = tenantId + "|" + userId + "|" + kind.name() + "|" + expires.getEpochSecond() + "|" + UUID.randomUUID();
        String b = Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        return b + "." + mac(b);
    }

    /** The payload, or null for anything forged, tampered with, for another tool, or expired. */
    public Payload verify(String state, IntegrationService.Kind expectedKind) {
        try {
            int dot = state == null ? -1 : state.indexOf('.');
            if (dot < 1) {
                return null;
            }
            String b = state.substring(0, dot);
            if (!MessageDigest.isEqual(mac(b).getBytes(StandardCharsets.UTF_8), state.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            String[] p = new String(Base64.getUrlDecoder().decode(b), StandardCharsets.UTF_8).split("\\|");
            if (p.length != 5 || Instant.now().getEpochSecond() > Long.parseLong(p[3])) {
                return null;
            }
            IntegrationService.Kind kind = IntegrationService.Kind.valueOf(p[2]);
            return kind == expectedKind ? new Payload(UUID.fromString(p[0]), UUID.fromString(p[1]), kind) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String mac(String data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
