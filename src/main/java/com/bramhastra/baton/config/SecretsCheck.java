package com.bramhastra.baton.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Checks the development secrets from application.properties are not used against a database outside this
 * machine. With the published token secret anyone could sign in as anyone, so the server refuses to start; with
 * the published master key sealed connection details could be opened, so that is logged as a security error.
 */
@Component
public class SecretsCheck {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SecretsCheck.class);

    static final String DEV_JWT_SECRET = "local-dev-secret-change-me-at-least-32-bytes!!";
    static final String DEV_CRYPTO_KEY = "bG9jYWwtZGV2LW9ubHktYWVzLWtleS0zMi1ieXRlcyE=";

    private final String jwtSecret;
    private final String cryptoKey;
    private final String databaseUrl;

    public SecretsCheck(@Value("${baton.jwt.secret}") String jwtSecret, @Value("${baton.crypto.key}") String cryptoKey,
                        @Value("${spring.datasource.url:}") String databaseUrl) {
        this.jwtSecret = jwtSecret;
        this.cryptoKey = cryptoKey;
        this.databaseUrl = databaseUrl;
    }

    @PostConstruct
    void check() {
        for (String p : problems(jwtSecret, cryptoKey, databaseUrl)) {
            if (p.startsWith("JWT_SECRET")) {
                throw new IllegalStateException(p);
            }
            // Changing the master key later would lock existing workspaces out, so this one is reported, not fatal.
            LOG.error("SECURITY: {}", p);
        }
    }

    static List<String> problems(String jwtSecret, String cryptoKey, String databaseUrl) {
        List<String> out = new ArrayList<>();
        if (isLocal(databaseUrl)) {
            return out;
        }
        if (DEV_JWT_SECRET.equals(jwtSecret)) {
            out.add("JWT_SECRET is not set: refusing to run with the development token secret outside this machine");
        }
        if (DEV_CRYPTO_KEY.equals(cryptoKey)) {
            out.add("CRYPTO_KEY is not set: refusing to run with the development master key outside this machine");
        }
        return out;
    }

    static boolean isLocal(String jdbcUrl) {
        String u = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(java.util.Locale.ROOT);
        return u.isEmpty() || u.contains("//localhost") || u.contains("//127.0.0.1") || u.contains("//[::1]")
                || u.contains("//host.docker.internal");
    }
}
