package com.bramhastra.baton.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseUrlTest {

    @Test
    void parsesNeonStyleUrlAndUsesTheDirectHost() {
        DatabaseUrl u = DatabaseUrl.parse(
                "postgresql://acme:s3cret@ep-cool-name-123-pooler.c-4.ap-southeast-1.aws.neon.tech/baton?sslmode=require&channel_binding=require",
                null, null);
        assertThat(u.host()).isEqualTo("ep-cool-name-123.c-4.ap-southeast-1.aws.neon.tech");
        assertThat(u.username()).isEqualTo("acme");
        assertThat(u.password()).isEqualTo("s3cret");
        assertThat(u.database()).isEqualTo("baton");
        String jdbc = u.jdbcUrl(true);
        assertThat(jdbc).startsWith("jdbc:postgresql://ep-cool-name-123.c-4.ap-southeast-1.aws.neon.tech:5432/baton?")
                .contains("sslmode=require").doesNotContain("channel_binding").doesNotContain("s3cret");
    }

    @Test
    void separateCredentialsWinAndJdbcUrlsAreAccepted() {
        DatabaseUrl u = DatabaseUrl.parse("jdbc:postgresql://db.example.com:6543/acme", "svc", "pw");
        assertThat(u.port()).isEqualTo(6543);
        assertThat(u.username()).isEqualTo("svc");
        assertThat(u.jdbcUrl(true)).contains("sslmode=require");
    }

    @Test
    void rejectsIncompleteOrForeignUrls() {
        assertThatThrownBy(() -> DatabaseUrl.parse("mysql://x/y", "u", "p")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DatabaseUrl.parse("postgres://host.example.com/", "u", "p"))
                .hasMessageContaining("database name");
        assertThatThrownBy(() -> DatabaseUrl.parse("postgres://host.example.com/db", null, null))
                .hasMessageContaining("user and password");
    }

    @Test
    void detectsLocalAndPrivateHosts() {
        assertThat(DatabaseUrl.parse("postgres://127.0.0.1/db", "u", "p").isPrivateHost()).isTrue();
        assertThat(DatabaseUrl.parse("postgres://localhost/db", "u", "p").isPrivateHost()).isTrue();
        assertThat(DatabaseUrl.parse("postgres://10.1.2.3/db", "u", "p").isPrivateHost()).isTrue();
        assertThat(DatabaseUrl.parse("postgres://169.254.169.254/db", "u", "p").isPrivateHost()).isTrue();
    }
}
