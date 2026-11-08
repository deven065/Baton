package com.bramhastra.baton.tenant;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The workspace the current thread works for. Every database call goes to that workspace's own database
 * (see {@link TenantDataSource}), so it must be set before any repository, transaction or JdbcClient call:
 * by {@link TenantFilter} for API requests, and explicitly for background jobs and startup tasks.
 */
public final class TenantContext {

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<Tenant> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Tenant require() {
        Tenant t = CURRENT.get();
        if (t == null) {
            throw new IllegalStateException("No workspace selected for this thread");
        }
        return t;
    }

    /** Runs {@code work} as {@code tenant}, then restores whatever was set before. */
    public static <T> T call(Tenant tenant, Supplier<T> work) {
        Tenant previous = CURRENT.get();
        CURRENT.set(tenant);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void run(Tenant tenant, Runnable work) {
        call(tenant, () -> {
            work.run();
            return null;
        });
    }

    static void set(Tenant tenant) {
        CURRENT.set(tenant);
    }

    static void clear() {
        CURRENT.remove();
    }
}
