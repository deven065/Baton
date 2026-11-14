package com.bramhastra.baton.room;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live side of a project's team room: who is listening, and who is in the video call.
 *
 * <p>Browsers listen over server-sent events and speak over ordinary requests. Video never passes through the
 * server: it only relays the small setup messages two browsers swap to find each other (WebRTC signalling), so the
 * call costs the server almost nothing whatever the picture quality. State lives in memory because one API
 * instance runs; a restart simply ends calls and the browsers reconnect.
 */
@Component
public class RoomHub {

    /** A call is a mesh (everyone sends to everyone), so it is capped where 4K uplinks stay realistic. */
    public static final int MAX_IN_CALL = 4;
    private static final long TIMEOUT_MS = 60 * 60 * 1000L;

    public record Participant(UUID userId, String name) {
    }

    private record Listener(UUID userId, SseEmitter emitter) {
    }

    private final Map<UUID, List<Listener>> listeners = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, String>> calls = new ConcurrentHashMap<>();

    /** Opens a stream for one browser tab. The first event says who is already in a call. */
    public SseEmitter subscribe(UUID projectId, UUID userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Listener me = new Listener(userId, emitter);
        List<Listener> list = listeners.computeIfAbsent(projectId, k -> new CopyOnWriteArrayList<>());
        list.add(me);
        Runnable gone = () -> dropListener(projectId, me);
        emitter.onCompletion(gone);
        emitter.onTimeout(gone);
        emitter.onError(e -> gone.run());
        send(emitter, "hello", Map.of("inCall", participants(projectId)));
        return emitter;
    }

    public void broadcast(UUID projectId, String event, Object data) {
        List<Listener> list = listeners.get(projectId);
        if (list != null) {
            list.forEach(l -> {
                if (!send(l.emitter(), event, data)) {
                    list.remove(l);
                }
            });
        }
    }

    public void sendTo(UUID projectId, UUID userId, String event, Object data) {
        List<Listener> list = listeners.get(projectId);
        if (list != null) {
            list.stream().filter(l -> l.userId().equals(userId)).forEach(l -> {
                if (!send(l.emitter(), event, data)) {
                    list.remove(l);
                }
            });
        }
    }

    /** Adds someone to the project's call. False when it is full; joining twice is harmless. */
    public synchronized boolean join(UUID projectId, UUID userId, String name) {
        Map<UUID, String> call = calls.computeIfAbsent(projectId, k -> new LinkedHashMap<>());
        if (!call.containsKey(userId) && call.size() >= MAX_IN_CALL) {
            return false;
        }
        boolean added = call.put(userId, name) == null;
        if (added) {
            broadcast(projectId, "call-joined", new Participant(userId, name));
        }
        return true;
    }

    public synchronized void leave(UUID projectId, UUID userId) {
        Map<UUID, String> call = calls.get(projectId);
        if (call != null && call.remove(userId) != null) {
            if (call.isEmpty()) {
                calls.remove(projectId);
            }
            broadcast(projectId, "call-left", Map.of("userId", userId));
        }
    }

    public synchronized boolean inCall(UUID projectId, UUID userId) {
        Map<UUID, String> call = calls.get(projectId);
        return call != null && call.containsKey(userId);
    }

    public synchronized List<Participant> participants(UUID projectId) {
        Map<UUID, String> call = calls.get(projectId);
        List<Participant> out = new ArrayList<>();
        if (call != null) {
            call.forEach((id, name) -> out.add(new Participant(id, name)));
        }
        return out;
    }

    /** A person whose last tab closed is out of the call too, so nobody is left waiting on a ghost. */
    private void dropListener(UUID projectId, Listener l) {
        List<Listener> list = listeners.get(projectId);
        if (list == null) {
            return;
        }
        list.remove(l);
        if (list.stream().noneMatch(o -> o.userId().equals(l.userId()))) {
            leave(projectId, l.userId());
        }
        if (list.isEmpty()) {
            listeners.remove(projectId, list);
        }
    }

    /** A comment line every 15 s keeps proxies (Render, Vercel) from closing an idle stream. */
    @Scheduled(fixedDelay = 15_000)
    void heartbeat() {
        listeners.forEach((projectId, list) -> list.forEach(l -> {
            try {
                l.emitter().send(SseEmitter.event().comment("hb"));
            } catch (IOException | IllegalStateException ex) {
                dropListener(projectId, l);
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
