package com.bramhastra.baton.job;

import com.bramhastra.baton.common.SafeLog;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.tenant.Tenant;
import com.bramhastra.baton.tenant.TenantActivity;
import com.bramhastra.baton.tenant.TenantContext;
import com.bramhastra.baton.tenant.TenantRegistry;

import jakarta.annotation.PreDestroy;

/**
 * Polls each warm workspace's job table every second and runs claimed jobs on a small shared pool, each job
 * inside its own workspace (so it only ever touches that company's database).
 * Progress is persisted (so a reload or another instance sees it) and pushed over SSE.
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobRepository jobs;
    private final SseHub sse;
    private final Map<JobKind, JobHandler> handlers = new EnumMap<>(JobKind.class);
    private final boolean enabled;
    private final Semaphore slots;
    private final ExecutorService executor;
    private final Map<UUID, Tenant> running = new ConcurrentHashMap<>();
    private final TenantActivity activity;
    private final TenantRegistry registry;

    public JobWorker(JobRepository jobs, SseHub sse, List<JobHandler> handlerList, TenantActivity activity,
                     TenantRegistry registry,
                     @Value("${baton.jobs.enabled:true}") boolean enabled,
                     @Value("${baton.jobs.concurrency:2}") int concurrency) {
        this.jobs = jobs;
        this.activity = activity;
        this.registry = registry;
        this.sse = sse;
        handlerList.forEach(h -> handlers.put(h.kind(), h));
        this.enabled = enabled;
        this.slots = new Semaphore(concurrency);
        this.executor = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "baton-job");
            t.setDaemon(true);
            return t;
        });
    }

    @Scheduled(fixedDelay = 1000)
    void poll() {
        if (!enabled) {
            return;
        }
        for (Tenant tenant : activity.warm()) {
            if (!pollTenant(tenant)) {
                return; // no free slots left
            }
        }
    }

    /** Claims jobs for one workspace while slots are free. Returns false when the pool is full. */
    private boolean pollTenant(Tenant tenant) {
        while (slots.tryAcquire()) {
            Optional<Job> next;
            try {
                next = TenantContext.call(tenant, jobs::claimNext);
            } catch (RuntimeException e) {
                slots.release();
                // One company's database being down must not stop the others' jobs.
                log.warn("Could not claim job in workspace '{}': {}", tenant.slug(), SafeLog.describe(e));
                return true;
            }
            if (next.isEmpty()) {
                slots.release();
                return true;
            }
            Job job = next.get();
            running.put(job.id(), tenant);
            executor.submit(() -> {
                try {
                    TenantContext.run(tenant, () -> run(job));
                } finally {
                    running.remove(job.id());
                    slots.release();
                }
            });
        }
        return false;
    }

    private void run(Job job) {
        JobHandler handler = handlers.get(job.kind());
        JobProgress[] last = {new JobProgress("START", 0, "Starting", null)};
        JobHandler.ProgressSink sink = p -> {
            last[0] = p;
            jobs.saveProgress(job.id(), p);
            sse.publish(job.id(), p);
        };
        try {
            if (handler == null) {
                throw new IllegalStateException("No handler for " + job.kind());
            }
            sink.report(last[0]);
            handler.handle(job, sink);
            JobProgress done = new JobProgress("DONE", 100, "Finished", last[0].counts());
            jobs.succeed(job.id(), done);
            sse.publish(job.id(), done);
            sse.finish(job.id(), "SUCCEEDED");
        } catch (Exception e) {
            log.warn("Job {} ({}) failed: {}", job.id(), job.kind(), SafeLog.describe(e));
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            JobProgress failed = new JobProgress(last[0].stage(), last[0].percent(), "Failed: " + message,
                    last[0].counts());
            jobs.fail(job.id(), message, failed);
            sse.publish(job.id(), failed);
            sse.finish(job.id(), "FAILED");
        }
    }

    @Scheduled(fixedDelay = 30_000)
    void heartbeat() {
        Map<Tenant, List<UUID>> byTenant = new java.util.HashMap<>();
        running.forEach((jobId, t) -> byTenant.computeIfAbsent(t, k -> new java.util.ArrayList<>()).add(jobId));
        byTenant.forEach((t, ids) -> {
            try {
                TenantContext.run(t, () -> jobs.heartbeat(Set.copyOf(ids)));
            } catch (RuntimeException e) {
                log.warn("Heartbeat failed in workspace '{}': {}", t.slug(), SafeLog.describe(e));
            }
        });
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void reap() {
        for (Tenant t : activity.warm()) {
            try {
                int n = TenantContext.call(t, jobs::reapStuck);
                if (n > 0) {
                    log.info("Requeued or failed {} stuck job(s) in workspace '{}'", n, t.slug());
                }
            } catch (RuntimeException e) {
                log.warn("Reaping failed in workspace '{}': {}", t.slug(), SafeLog.describe(e));
            }
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
