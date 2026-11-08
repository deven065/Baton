package com.bramhastra.baton.job;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Fans job progress out to browsers over server-sent events. */
@Component
public class SseHub {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID jobId, JobProgress current, boolean finished, String status) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        send(emitter, "progress", current);
        if (finished) {
            send(emitter, "done", Map.of("status", status));
            emitter.complete();
            return emitter;
        }
        List<SseEmitter> list = emitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    public void publish(UUID jobId, JobProgress progress) {
        List<SseEmitter> list = emitters.get(jobId);
        if (list != null) {
            list.forEach(e -> {
                if (!send(e, "progress", progress)) {
                    list.remove(e);
                }
            });
        }
    }

    public void finish(UUID jobId, String status) {
        List<SseEmitter> list = emitters.remove(jobId);
        if (list != null) {
            list.forEach(e -> {
                send(e, "done", Map.of("status", status));
                e.complete();
            });
        }
    }

    /** Comment line every 15 s keeps proxies (Render, Vercel) from closing idle streams. */
    @Scheduled(fixedDelay = 15_000)
    void heartbeat() {
        emitters.values().forEach(list -> list.forEach(e -> {
            try {
                e.send(SseEmitter.event().comment("hb"));
            } catch (IOException | IllegalStateException ex) {
                list.remove(e);
            }
        }));
    }

    private static boolean send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
            return true;
        } catch (IOException | IllegalStateException ex) {
            return false;
        }
    }
}
