package com.bramhastra.baton.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

class SafeLogTest {

    @Test
    void keepsTypesAndSqlStateButNeverTheMessage() {
        Exception e = new IllegalStateException("Payroll for Priya: 1,20,000",
                new SQLException("duplicate key value (email)=(priya@acme.test)", "23505"));
        String line = SafeLog.describe(e);
        assertThat(line).startsWith("IllegalStateException <- SQLException[SQLState 23505]")
                .contains("at SafeLogTest.keepsTypesAndSqlStateButNeverTheMessage")
                .doesNotContain("Priya").doesNotContain("priya@acme.test").doesNotContain("1,20,000");
    }
}
