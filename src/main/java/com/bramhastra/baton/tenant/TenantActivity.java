package com.bramhastra.baton.tenant;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Which workspaces have had work recently. The job worker only polls those, so an idle company's database
 * (often serverless, billed while awake) is not woken every second by Baton.
 */
@Component
public class TenantActivity {

    private static final Duration WARM = Duration.ofMinutes(10);

    private final Map<UUID, Instant> lastTouched = new ConcurrentHashMap<>();
    private final TenantRegistry registry;

    public TenantActivity(TenantRegistry registry) {
        this.registry = registry;
    }

    public void touch(UUID tenantId) {
        lastTouched.put(tenantId, Instant.now());
    }

    public void touchCurrent() {
        TenantContext.current().ifPresent(t -> touch(t.id()));
    }

    /** Active workspaces touched within the last few minutes. */
    public List<Tenant> warm() {
        Instant cutoff = Instant.now().minus(WARM);
        lastTouched.values().removeIf(i -> i.isBefore(cutoff));
        return registry.active().stream().filter(t -> lastTouched.containsKey(t.id())).toList();
    }
}
