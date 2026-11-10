package com.bramhastra.baton.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretsCheckTest {

    private static final String REMOTE = "jdbc:postgresql://ep-cool-db.ap-southeast-1.aws.neon.tech/neondb?sslmode=require";
    private static final String STRONG = "a-real-secret-that-is-long-enough-for-hs256-0123456789";
    private static final String STRONG_KEY = "c29tZS1vdGhlci0zMi1ieXRlLWtleS1mb3ItdGVzdHMh";

    @Test
    void developmentSecretsAreFineOnThisMachine() {
        assertThat(SecretsCheck.problems(SecretsCheck.DEV_JWT_SECRET, SecretsCheck.DEV_CRYPTO_KEY,
                "jdbc:postgresql://localhost:5433/baton")).isEmpty();
    }

    @Test
    void aRemoteDeploymentWithTheDevelopmentTokenSecretRefusesToStart() {
        assertThat(SecretsCheck.problems(SecretsCheck.DEV_JWT_SECRET, STRONG_KEY, REMOTE)).singleElement()
                .asString().startsWith("JWT_SECRET");
        assertThatThrownBy(() -> new SecretsCheck(SecretsCheck.DEV_JWT_SECRET, STRONG_KEY, REMOTE).check())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theDevelopmentMasterKeyIsReportedButDoesNotStopTheServer() {
        assertThat(SecretsCheck.problems(STRONG, SecretsCheck.DEV_CRYPTO_KEY, REMOTE)).singleElement()
                .asString().startsWith("CRYPTO_KEY");
        new SecretsCheck(STRONG, SecretsCheck.DEV_CRYPTO_KEY, REMOTE).check();
        assertThat(SecretsCheck.problems(STRONG, STRONG_KEY, REMOTE)).isEmpty();
    }
}
