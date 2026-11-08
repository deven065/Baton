package com.bramhastra.baton.tenant;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.identity.AuthController;
import com.bramhastra.baton.identity.FirstAdminService;

/**
 * Workspace sign-up: checks the company's own database, sets it up, registers the workspace and creates its
 * first admin. Nothing but the workspace name and sealed connection details is stored on Baton's side.
 */
@Service
public class WorkspaceService {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])$");
    private static final List<String> RESERVED = List.of(TenantRegistry.DEMO_SLUG, "admin", "api", "app", "www",
            "baton", "login", "signup", "support", "help", "status");

    private final TenantRegistry registry;
    private final FirstAdminService admins;
    private final boolean allowPrivateHosts;

    public WorkspaceService(TenantRegistry registry, FirstAdminService admins,
                            @Value("${baton.tenants.allow-private-hosts:false}") boolean allowPrivateHosts) {
        this.registry = registry;
        this.admins = admins;
        this.allowPrivateHosts = allowPrivateHosts;
    }

    public record Registration(String slug, String name, String databaseUrl, String databaseUser, String databasePassword,
                               String adminName, String adminEmail, String adminPassword) {
    }

    public AuthController.LoginResponse register(Registration r) {
        String slug = r.slug().strip().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(slug).matches()) {
            throw invalid("slug", "Use 3–40 lowercase letters, digits or hyphens, starting and ending with a letter or digit");
        }
        if (RESERVED.contains(slug) || registry.slugTaken(slug)) {
            throw new ApiException(ErrorCode.WORKSPACE_TAKEN, "That workspace name is taken");
        }

        DatabaseUrl db;
        try {
            db = DatabaseUrl.parse(r.databaseUrl(), r.databaseUser(), r.databasePassword());
        } catch (IllegalArgumentException e) {
            throw invalid("database", e.getMessage());
        }
        boolean privateHost;
        try {
            privateHost = db.isPrivateHost();
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.DATABASE_UNREACHABLE, e.getMessage());
        }
        if (privateHost && !allowPrivateHosts) {
            throw new ApiException(ErrorCode.DATABASE_UNSUITABLE,
                    "Use a database reachable over the internet (for example Neon, Supabase or AWS RDS). "
                            + "Private and local addresses are not accepted.");
        }
        String jdbcUrl = db.jdbcUrl(!privateHost);
        DriverManagerDataSource probe = new DriverManagerDataSource(jdbcUrl, db.username(), db.password());
        check(probe);
        TenantRegistry.migrate(probe);

        Tenant tenant;
        try {
            tenant = registry.create(slug, r.name().strip(), jdbcUrl, db.username(), db.password());
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.WORKSPACE_TAKEN, "That workspace name is taken");
        }
        try {
            return TenantContext.call(tenant,
                    () -> admins.create(tenant, r.adminName().strip(), r.adminEmail().strip(), r.adminPassword()));
        } catch (RuntimeException e) {
            registry.forget(tenant.id());
            throw e;
        }
    }

    /** Reachable, recent enough, pgvector available, and empty (so we never mix with someone's other data). */
    private static void check(DriverManagerDataSource probe) {
        try (Connection c = probe.getConnection(); Statement st = c.createStatement()) {
            if (c.getMetaData().getDatabaseMajorVersion() < 14) {
                throw new ApiException(ErrorCode.DATABASE_UNSUITABLE, "Baton needs PostgreSQL 14 or newer");
            }
            try (ResultSet rs = st.executeQuery("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                    """)) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    throw new ApiException(ErrorCode.DATABASE_UNSUITABLE,
                            "That database already has tables. Create a new, empty database for Baton.");
                }
            }
            try {
                st.execute("CREATE EXTENSION IF NOT EXISTS vector");
            } catch (SQLException e) {
                throw new ApiException(ErrorCode.DATABASE_UNSUITABLE,
                        "The pgvector extension is not available in that database. Enable it (CREATE EXTENSION vector) "
                                + "or use a provider that offers it, such as Neon or Supabase.");
            }
        } catch (SQLException e) {
            // pgjdbc messages name the host and reason, never the password.
            throw new ApiException(ErrorCode.DATABASE_UNREACHABLE, "Could not connect to the database: " + e.getMessage());
        }
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed", Map.of(field, message));
    }
}
