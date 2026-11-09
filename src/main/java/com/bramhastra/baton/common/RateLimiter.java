package com.bramhastra.baton.common;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * In-memory sliding-window limits for the endpoints anyone can call: sign-in, workspace sign-up and lookup.
 * One API instance runs, so memory is enough; counters reset on restart, which only ever loosens a limit.
 */
@Component
public class RateLimiter {

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /** Refuses with 429 when {@code key} already has {@code max} hits inside {@code window}. Does not count. */
    public void check(String key, int max, Duration window) {
        Deque<Long> q = hits.get(key);
        if (q == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (q) {
            prune(q, now - window.toMillis());
            if (q.size() >= max) {
                long retry = Math.max(1, (q.peekFirst() + window.toMillis() - now + 999) / 1000);
                long minutes = Math.max(1, (retry + 59) / 60);
                throw new ApiException(ErrorCode.TOO_MANY_REQUESTS,
                        "Too many attempts. Try again in " + minutes + (minutes == 1 ? " minute." : " minutes."),
                        Map.of("retryAfterSeconds", retry));
            }
        }
    }

    /** Counts one hit for {@code key}. */
    public void hit(String key, Duration window) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now - window.toMillis());
            q.addLast(now);
        }
    }

    /** Check, then count. */
    public void acquire(String key, int max, Duration window) {
        check(key, max, window);
        hit(key, window);
    }

    public void reset(String key) {
        hits.remove(key);
    }

    private static void prune(Deque<Long> q, long before) {
        while (!q.isEmpty() && q.peekFirst() < before) {
            q.pollFirst();
        }
    }

    /** Drops keys with no hits in the last day so the map does not grow forever. */
    @Scheduled(fixedDelay = 600_000)
    void sweep() {
        long before = System.currentTimeMillis() - Duration.ofDays(1).toMillis();
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), before);
                return e.getValue().isEmpty();
            }
        });
    }

    /**
     * The caller's address. Behind Render's proxy the real client is the last entry the proxy appended to
     * X-Forwarded-For; earlier entries are whatever the client sent and cannot be trusted.
     */
    public static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            String last = parts[parts.length - 1].strip();
            if (!last.isEmpty()) {
                return last;
            }
        }
        return request.getRemoteAddr();
    }
}
