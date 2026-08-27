package com.example.finalproject.global.sse.repository;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class SseEmitterRepository {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> userEmitters = new ConcurrentHashMap<>();

    public void save(Long userId, String emitterId, SseEmitter emitter) {
        emitters.put(emitterId, emitter);
        userEmitters.compute(userId, (ignored, ids) -> {
            Set<String> emitterIds = ids == null ? ConcurrentHashMap.newKeySet() : ids;
            emitterIds.add(emitterId);
            return emitterIds;
        });
    }

    public SseEmitter get(String emitterId) {
        return emitters.get(emitterId);
    }

    public Set<String> getEmitterIds(Long userId) {
        return userEmitters.getOrDefault(userId, Set.of());
    }

    public Set<Long> getUserIds() {
        return Set.copyOf(userEmitters.keySet());
    }

    public void remove(Long userId, String emitterId) {
        emitters.remove(emitterId);
        userEmitters.computeIfPresent(userId, (ignored, ids) -> {
            ids.remove(emitterId);
            return ids.isEmpty() ? null : ids;
        });
    }

    public void remove(Long userId, SseEmitter emitter) {
        getEmitterIds(userId).stream()
                .filter(emitterId -> emitters.get(emitterId) == emitter)
                .forEach(emitterId -> remove(userId, emitterId));
    }
}
