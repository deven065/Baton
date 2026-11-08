package com.bramhastra.baton.tenant;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * The application's only DataSource: every connection goes to the current workspace's database.
 * With no workspace selected it refuses, so a forgotten context can never read another company's data.
 * (Tests may name a fallback workspace via baton.tenants.fallback-workspace.)
 */
public class TenantDataSource extends AbstractDataSource {

    private final TenantRegistry registry;
    private final String fallbackSlug;

    public TenantDataSource(TenantRegistry registry, String fallbackSlug) {
        this.registry = registry;
        this.fallbackSlug = fallbackSlug == null || fallbackSlug.isBlank() ? null : fallbackSlug;
    }

    private DataSource target() {
        Optional<Tenant> t = TenantContext.current();
        if (t.isEmpty() && fallbackSlug != null) {
            t = registry.bySlug(fallbackSlug);
        }
        return registry.dataSource(t.orElseThrow(() -> new IllegalStateException("No workspace selected for this thread")));
    }

    @Override
    public Connection getConnection() throws SQLException {
        return target().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return target().getConnection(username, password);
    }
}
