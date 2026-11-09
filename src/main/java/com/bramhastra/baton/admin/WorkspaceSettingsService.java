package com.bramhastra.baton.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.common.RateLimiter;
import com.bramhastra.baton.identity.AppUserRepository;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.tenant.DatabaseUrl;
import com.bramhastra.baton.tenant.Tenant;
import com.bramhastra.baton.tenant.TenantContext;
import com.bramhastra.baton.tenant.TenantRegistry;

/** The workspace itself, for its admins: where its data lives, and disconnecting it from Baton. */
@Service
public class WorkspaceSettingsService {

    private final TenantRegistry registry;
    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final RateLimiter limits;

    public WorkspaceSettingsService(TenantRegistry registry, AppUserRepository users, PasswordEncoder passwords,
                                    AuditService audit, JdbcClient jdbc, RateLimiter limits) {
        this.registry = registry;
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
        this.jdbc = jdbc;
        this.limits = limits;
    }

    public record DatabaseView(String host, int port, String database) {
    }

    public record WorkspaceView(String slug, String name, Instant createdAt, boolean demo, DatabaseView database,
                                int people, int projects) {
    }

    public WorkspaceView get(CurrentUser me) {
        AdminService.requireAdmin(me);
        Tenant t = TenantContext.require();
        DatabaseUrl db = registry.connection(t);
        int people = jdbc.sql("SELECT count(*) FROM app_user WHERE active").query(Integer.class).single();
        int projects = jdbc.sql("SELECT count(*) FROM project").query(Integer.class).single();
        return new WorkspaceView(t.slug(), t.name(), t.createdAt(), t.usesHomeDb(),
                db == null ? null : new DatabaseView(db.host(), db.port(), db.database()), people, projects);
    }

    public record Disconnected(String recoveryKey, Instant disconnectedAt) {
    }

    /**
     * Removes the workspace from Baton. Requires the admin to type the workspace name and their password.
     * Baton forgets the connection details and the data key; the company's database keeps all its data.
     * The data key is returned once so the company can still read the encrypted original files in it.
     */
    public Disconnected disconnect(CurrentUser me, String confirm, String password) {
        AdminService.requireAdmin(me);
        Tenant t = TenantContext.require();
        if (t.usesHomeDb()) {
            throw new ApiException(ErrorCode.NOT_ALLOWED, "The demo workspace cannot be disconnected");
        }
        limits.acquire("disconnect:" + t.id(), 5, Duration.ofMinutes(15));
        if (confirm == null || !confirm.strip().equalsIgnoreCase(t.slug())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of("confirm", "Type the workspace name exactly: " + t.slug()));
        }
        boolean ok = users.findById(me.id()).filter(u -> passwords.matches(password == null ? "" : password, u.getPasswordHash()))
                .isPresent();
        if (!ok) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of("password", "That password is not right"));
        }
        int busy = jdbc.sql("SELECT count(*) FROM job WHERE status IN ('QUEUED', 'RUNNING')").query(Integer.class).single();
        if (busy > 0) {
            throw new ApiException(ErrorCode.NOT_ALLOWED,
                    busy + " background job" + (busy > 1 ? "s are" : " is") + " still running. Try again in a minute.");
        }
        String key = Base64.getEncoder().encodeToString(registry.exportDataKey(t));
        // Recorded in the company's own audit log before Baton lets go of the database.
        audit.record(me.id(), "WORKSPACE_DISCONNECTED", "workspace", t.id(), null, Map.of("slug", t.slug()));
        registry.disconnect(t.id());
        return new Disconnected(key, Instant.now());
    }
}
