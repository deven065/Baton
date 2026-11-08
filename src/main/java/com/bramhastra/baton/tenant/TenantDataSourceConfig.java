package com.bramhastra.baton.tenant;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Two kinds of database:
 * <ul>
 * <li>"home" — Baton's own database (spring.datasource.*): the {@code control} schema with the workspace
 * registry, and the demo workspace's data;</li>
 * <li>each company's own database, reached through the {@link TenantDataSource} that every repository uses.</li>
 * </ul>
 */
@Configuration
public class TenantDataSourceConfig {

    @Bean
    HikariDataSource homeDataSource(ObjectProvider<JdbcConnectionDetails> details, DataSourceProperties props) {
        HikariConfig c = new HikariConfig();
        JdbcConnectionDetails d = details.getIfAvailable();
        c.setJdbcUrl(d != null ? d.getJdbcUrl() : props.determineUrl());
        c.setUsername(d != null ? d.getUsername() : props.determineUsername());
        c.setPassword(d != null ? d.getPassword() : props.determinePassword());
        c.setPoolName("home");
        c.setMaximumPoolSize(6);
        return new HikariDataSource(c);
    }

    @Bean
    @Primary
    DataSource dataSource(TenantRegistry registry, @Value("${baton.tenants.fallback-workspace:}") String fallback) {
        return new TenantDataSource(registry, fallback);
    }
}
