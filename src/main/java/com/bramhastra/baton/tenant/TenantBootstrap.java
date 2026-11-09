package com.bramhastra.baton.tenant;

import com.bramhastra.baton.common.SafeLog;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Before the app serves anything: create/upgrade the control schema, register the demo workspace (if enabled),
 * upgrade every workspace's schema, and mark them warm so jobs interrupted by a restart resume.
 */
@Component
public class TenantBootstrap implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(TenantBootstrap.class);

    private final DataSource home;
    private final TenantRegistry registry;
    private final TenantActivity activity;
    private final boolean demo;
    private final String demoName;

    public TenantBootstrap(@Qualifier("homeDataSource") DataSource home, TenantRegistry registry, TenantActivity activity,
                           @Value("${baton.demo.seed:false}") boolean demo,
                           @Value("${baton.tenants.demo-name:Northfield Tech Club (demo)}") String demoName) {
        this.home = home;
        this.registry = registry;
        this.activity = activity;
        this.demo = demo;
        this.demoName = demoName;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Flyway.configure().dataSource(home).schemas("control").createSchemas(true)
                .locations("classpath:db/control").load().migrate();
        registry.reload();
        if (demo) {
            registry.ensureDemo(demoName);
        }
        for (Tenant t : registry.active()) {
            try {
                TenantRegistry.migrate(registry.dataSource(t));
                activity.touch(t.id());
            } catch (RuntimeException e) {
                // One company's database being unreachable must not stop Baton for everyone else.
                log.warn("Workspace '{}' could not be migrated: {}", t.slug(), SafeLog.describe(e));
            }
        }
        log.info("Workspaces ready: {}", registry.active().size());
    }
}
