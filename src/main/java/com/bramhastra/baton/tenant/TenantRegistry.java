package com.bramhastra.baton.tenant;

import com.bramhastra.baton.common.SafeLog;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.annotation.PreDestroy;

/**
 * Knows every workspace and how to reach its database. Connection pools are opened lazily and kept small,
 * because each one points at a company's own Postgres.
 */
@Component
public class TenantRegistry {

    private static final Logger log = LoggerFactory.getLogger(TenantRegistry.class);
    public static final String DEMO_SLUG = "demo";

    private final JdbcClient control;
    private final DataSource home;
    private final MasterKey master;
    private final Map<UUID, Tenant> byId = new ConcurrentHashMap<>();
    private final Map<UUID, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final Map<UUID, SecretKey> keys = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public TenantRegistry(@Qualifier("homeDataSource") DataSource home, MasterKey master) {
        // A private client (not a JdbcClient bean): Boot makes JdbcClient beans wait for the main DataSource,
        // and the main DataSource needs this registry.
        this.control = JdbcClient.create(home);
        this.home = home;
        this.master = master;
    }

    private static Tenant map(ResultSet rs, int n) throws SQLException {
        return new Tenant(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                rs.getBoolean("uses_home_db"), "ACTIVE".equals(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant());
    }

    /** (Re)loads the registry from the control schema. */
    public void reload() {
        control.sql("SELECT id, slug, name, uses_home_db, status, created_at FROM control.tenant")
                .query(TenantRegistry::map).list()
                .forEach(t -> byId.put(t.id(), t));
    }

    public List<Tenant> active() {
        return byId.values().stream().filter(Tenant::active).toList();
    }

    public Optional<Tenant> byId(UUID id) {
        Tenant t = byId.get(id);
        if (t == null) {
            reload();
            t = byId.get(id);
        }
        return Optional.ofNullable(t);
    }

    public Optional<Tenant> bySlug(String slug) {
        if (slug == null) {
            return Optional.empty();
        }
        String s = slug.strip().toLowerCase(java.util.Locale.ROOT);
        Optional<Tenant> hit = byId.values().stream().filter(t -> t.slug().equals(s)).findFirst();
        if (hit.isEmpty()) {
            reload();
            hit = byId.values().stream().filter(t -> t.slug().equals(s)).findFirst();
        }
        return hit;
    }

    public boolean slugTaken(String slug) {
        return control.sql("SELECT count(*) FROM control.tenant WHERE slug = :s").param("s", slug)
                .query(Integer.class).single() > 0;
    }

    /** The database for a workspace: a small pool to the company's own Postgres (or Baton's, for the demo). */
    public DataSource dataSource(Tenant t) {
        if (t.usesHomeDb()) {
            return home;
        }
        return pools.computeIfAbsent(t.id(), id -> {
            Map<String, Object> row = control.sql("""
                    SELECT db_url_sealed, db_user_sealed, db_password_sealed FROM control.tenant WHERE id = :id
                    """).param("id", id).query().singleRow();
            HikariConfig c = new HikariConfig();
            c.setPoolName("tenant-" + t.slug());
            c.setJdbcUrl(master.open((String) row.get("db_url_sealed")));
            c.setUsername(master.open((String) row.get("db_user_sealed")));
            c.setPassword(master.open((String) row.get("db_password_sealed")));
            c.setMaximumPoolSize(4);
            c.setMinimumIdle(0);
            c.setIdleTimeout(120_000);
            c.setMaxLifetime(600_000);
            c.setConnectionTimeout(10_000);
            c.setInitializationFailTimeout(-1);
            return new HikariDataSource(c);
        });
    }

    /** The workspace's own AES-256 data key. */
    public SecretKey dataKey(Tenant t) {
        return keys.computeIfAbsent(t.id(), id -> {
            String sealed = control.sql("SELECT data_key_sealed FROM control.tenant WHERE id = :id").param("id", id)
                    .query(String.class).single();
            return new SecretKeySpec(master.openBytes(sealed), "AES");
        });
    }

    /** Creates or upgrades a workspace's schema. Safe to run repeatedly. */
    public static void migrate(DataSource ds) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    }

    /** The built-in demo workspace: lives in Baton's own database and keeps the master key as its data key. */
    public Tenant ensureDemo(String name) {
        Optional<Tenant> existing = bySlug(DEMO_SLUG);
        if (existing.isPresent()) {
            return existing.get();
        }
        control.sql("""
                INSERT INTO control.tenant (slug, name, uses_home_db, data_key_sealed)
                VALUES (:slug, :name, TRUE, :key) ON CONFLICT (slug) DO NOTHING
                """)
                .param("slug", DEMO_SLUG).param("name", name).param("key", master.seal(master.rawForDemo()))
                .update();
        reload();
        return bySlug(DEMO_SLUG).orElseThrow();
    }

    /** Registers a company workspace whose database has already been checked and migrated. */
    public Tenant create(String slug, String name, String jdbcUrl, String user, String password) {
        byte[] dataKey = new byte[32];
        random.nextBytes(dataKey);
        UUID id = control.sql("""
                INSERT INTO control.tenant (slug, name, db_url_sealed, db_user_sealed, db_password_sealed, data_key_sealed)
                VALUES (:slug, :name, :url, :user, :pass, :key) RETURNING id
                """)
                .param("slug", slug).param("name", name)
                .param("url", master.seal(jdbcUrl)).param("user", master.seal(user)).param("pass", master.seal(password))
                .param("key", master.seal(dataKey))
                .query(UUID.class).single();
        reload();
        return byId(id).orElseThrow();
    }

    /** Until when the workspace's paid plan runs; null if it never paid. */
    public java.time.Instant paidUntil(UUID id) {
        return control.sql("SELECT paid_until FROM control.tenant WHERE id = :id").param("id", id)
                .query((rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant())
                .optional().orElse(null);
    }

    /**
     * Records a payment once and extends the plan by a month from whichever is later, now or the current end.
     * Returns the new end, or null if this payment was already recorded.
     */
    public java.time.Instant recordPayment(Tenant t, String providerRef, long amountMinor, String currency) {
        int inserted = control.sql("""
                INSERT INTO control.payment (provider_ref, tenant_id, tenant_slug, amount_minor, currency)
                VALUES (:ref, :id, :slug, :amount, :cur) ON CONFLICT (provider_ref) DO NOTHING
                """).param("ref", providerRef).param("id", t.id()).param("slug", t.slug()).param("amount", amountMinor)
                .param("cur", currency).update();
        if (inserted == 0) {
            return null;
        }
        return control.sql("""
                UPDATE control.tenant SET paid_until = GREATEST(COALESCE(paid_until, now()), now()) + interval '1 month'
                WHERE id = :id RETURNING paid_until
                """).param("id", t.id()).query((rs, n) -> rs.getTimestamp(1).toInstant()).single();
    }

    /** Where a company workspace's database is (host, port, database name), for its admins. Null for the demo. */
    public DatabaseUrl connection(Tenant t) {
        if (t.usesHomeDb()) {
            return null;
        }
        Map<String, Object> row = control.sql("""
                SELECT db_url_sealed, db_user_sealed, db_password_sealed FROM control.tenant WHERE id = :id
                """).param("id", t.id()).query().singleRow();
        return DatabaseUrl.parse(master.open((String) row.get("db_url_sealed")),
                master.open((String) row.get("db_user_sealed")), master.open((String) row.get("db_password_sealed")));
    }

    /** The workspace's raw data key, handed to its admin once when they disconnect. */
    public byte[] exportDataKey(Tenant t) {
        return dataKey(t).getEncoded();
    }

    /**
     * Disconnects a company: deletes everything Baton holds about it (its sealed connection details and data key)
     * and closes its pool. The company's own database is not touched.
     */
    public void disconnect(UUID id) {
        forget(id);
    }

    /** Removes a half-created workspace (registration failed after the row was written). */
    public void forget(UUID id) {
        control.sql("DELETE FROM control.tenant WHERE id = :id").param("id", id).update();
        byId.remove(id);
        keys.remove(id);
        HikariDataSource ds = pools.remove(id);
        if (ds != null) {
            ds.close();
        }
    }

    @PreDestroy
    void close() {
        pools.values().forEach(ds -> {
            try {
                ds.close();
            } catch (RuntimeException e) {
                log.debug("Closing pool failed: {}", SafeLog.describe(e));
            }
        });
    }
}
