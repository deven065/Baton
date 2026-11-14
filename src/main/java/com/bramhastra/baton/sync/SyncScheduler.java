package com.bramhastra.baton.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.common.SafeLog;
import com.bramhastra.baton.tenant.Tenant;
import com.bramhastra.baton.tenant.TenantContext;
import com.bramhastra.baton.tenant.TenantRegistry;

/**
 * Every tick, in every active workspace, queue the scheduled reads that are due. The tick is deliberately slow
 * (default 30 minutes): a company's database is often serverless and billed while awake, so Baton wakes it a few
 * times an hour rather than every second. Reads themselves run on the ordinary job worker.
 */
@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final TenantRegistry registry;
    private final SyncService sync;
    private final boolean enabled;

    public SyncScheduler(TenantRegistry registry, SyncService sync, @Value("${baton.sync.enabled:true}") boolean enabled) {
        this.registry = registry;
        this.sync = sync;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${baton.sync.tick-minutes:30}", initialDelayString = "${baton.sync.initial-delay-minutes:3}",
            timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    void tick() {
        if (!enabled) {
            return;
        }
        for (Tenant t : registry.active()) {
            try {
                TenantContext.run(t, sync::enqueueDue);
            } catch (RuntimeException e) {
                // One company's database being down must not stop the others' syncs.
                log.warn("Scheduled sync skipped in workspace '{}': {}", t.slug(), SafeLog.describe(e));
            }
        }
    }
}
