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
    /** The call everyone on the project can join. A direct call is a separate room with just two people. */
    public static final String GENERAL = "general";
    private static final long TIMEOUT_MS = 60 * 60 * 1000L;

    public record Participant(UUID userId, String name) {
    }

    private record Listener(UUID userId, SseEmitter emitter) {
    }

    private final Map<UUID, List<Listener>> listeners = new ConcurrentHashMap<>();
    /** project -> room -> who is in it. A person is in at most one room of a project at a time. */
    private final Map<UUID, Map<String, Map<UUID, String>>> calls = new ConcurrentHashMap<>();

    /** The room for a call between two specific people: the same key whichever of them asks first. */
    public static String directRoom(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? "dm:" + a + ":" + b : "dm:" + b + ":" + a;
    }

    public static boolean isDirect(String room) {
        return room.startsWith("dm:");
    }

    /** True when the room is a direct call that this person is one of the two people of. */
    public static boolean directRoomHas(String room, UUID user) {
        return isDirect(room) && java.util.Arrays.asList(room.substring(3).split(":")).contains(user.toString());
    }

    public static UUID otherInDirect(String room, UUID me) {
        for (String id : room.substring(3).split(":")) {
            if (!id.equals(me.toString())) {
                return UUID.fromString(id);
            }
        }
        return null;
    }

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

    /** Adds someone to a call room. False when it is full; joining twice is harmless. Leaves any other room first. */
    public synchronized boolean join(UUID projectId, String room, UUID userId, String name) {
        int cap = isDirect(room) ? 2 : MAX_IN_CALL;
        Map<UUID, String> here = calls.computeIfAbsent(projectId, k -> new ConcurrentHashMap<>()).computeIfAbsent(room, k -> new LinkedHashMap<>());
        if (!here.containsKey(userId) && here.size() >= cap) {
            return false;
        }
        String current = roomOf(projectId, userId);
        if (current != null && !current.equals(room)) {
            leave(projectId, userId);
            here = calls.computeIfAbsent(projectId, k -> new ConcurrentHashMap<>()).computeIfAbsent(room, k -> new LinkedHashMap<>());
        }
        boolean added = here.put(userId, name) == null;
        if (added) {
            announce(projectId, room, "call-joined", Map.of("userId", userId, "name", name, "room", room));
        }
        return true;
    }

    /** Takes the person out of whatever room they are in. Returns that room, or null when they were in none. */
    public synchronized String leave(UUID projectId, UUID userId) {
        Map<String, Map<UUID, String>> rooms = calls.get(projectId);
        if (rooms == null) {
            return null;
        }
        for (var e : new ArrayList<>(rooms.entrySet())) {
            if (e.getValue().remove(userId) != null) {
                if (e.getValue().isEmpty()) {
                    rooms.remove(e.getKey());
                }
                if (rooms.isEmpty()) {
                    calls.remove(projectId);
                }
                announce(projectId, e.getKey(), "call-left", Map.of("userId", userId, "room", e.getKey()));
                return e.getKey();
            }
        }
        return null;
    }

    /** The general call is announced to the whole project; a direct call only to its two people. */
    private void announce(UUID projectId, String room, String event, Object data) {
        if (isDirect(room)) {
            for (String id : room.substring(3).split(":")) {
                sendTo(projectId, UUID.fromString(id), event, data);
            }
        } else {
            broadcast(projectId, event, data);
        }
    }

    public synchronized String roomOf(UUID projectId, UUID userId) {
        Map<String, Map<UUID, String>> rooms = calls.get(projectId);
        if (rooms != null) {
            for (var e : rooms.entrySet()) {
                if (e.getValue().containsKey(userId)) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    /** Both people are in the same room, so setup messages may pass between them. */
    public synchronized boolean together(UUID projectId, UUID a, UUID b) {
        String room = roomOf(projectId, a);
        return room != null && room.equals(roomOf(projectId, b));
    }

    public synchronized List<Participant> participants(UUID projectId, String room) {
        Map<String, Map<UUID, String>> rooms = calls.get(projectId);
        List<Participant> out = new ArrayList<>();
        if (rooms != null && rooms.get(room) != null) {
            rooms.get(room).forEach((id, name) -> out.add(new Participant(id, name)));
        }
        return out;
    }

    /** Who is in the general call: what the room button and the "is on a call" line show. */
    public List<Participant> participants(UUID projectId) {
        return participants(projectId, GENERAL);
    }

    /** People with a browser open on this project's team room right now. */
    public java.util.Set<UUID> online(UUID projectId) {
        List<Listener> list = listeners.get(projectId);
        java.util.Set<UUID> out = new java.util.LinkedHashSet<>();
        if (list != null) {
            list.forEach(l -> out.add(l.userId()));
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
