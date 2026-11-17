package com.example.intern.security;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Small in-memory sliding-window limiter (single server instance). */
@Component
public class RateLimiter {

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /** @return true if the action is allowed, false if the limit for this key was exceeded. */
    public boolean allow(String key, int maxHits, long windowMillis) {
        long now = System.currentTimeMillis();
        if (hits.size() > 5000) {
            hits.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    Long last = e.getValue().peekLast();
                    return last == null || now - last > windowMillis;
                }
            });
        }
        Deque<Long> queue = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && now - queue.peekFirst() > windowMillis) {
                queue.pollFirst();
            }
            if (queue.size() >= maxHits) {
                return false;
            }
            queue.addLast(now);
            return true;
        }
    }

    public void reset(String key) {
        hits.remove(key);
    }
}
