package com.example.intern.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Maps the job ids shown to the browser to Flask job ids, and remembers who started each job. */
@Component
public class AiJobRegistry {

    public record JobInfo(Long ownerId, String flaskJobId, long createdAtMillis) { }

    private static final long TTL_MILLIS = 2 * 60 * 60 * 1000L;

    private final Map<String, JobInfo> jobs = new ConcurrentHashMap<>();
    private final java.util.Set<String> notified = ConcurrentHashMap.newKeySet();

    public String register(Long ownerId, String flaskJobId) {
        purgeExpired();
        String id = UUID.randomUUID().toString().replace("-", "");
        jobs.put(id, new JobInfo(ownerId, flaskJobId, System.currentTimeMillis()));
        return id;
    }

    /** Returns the job only if it belongs to this user (otherwise null, indistinguishable from "not found"). */
    public JobInfo find(String id, Long ownerId) {
        if (id == null) return null;
        JobInfo info = jobs.get(id);
        return (info != null && info.ownerId().equals(ownerId)) ? info : null;
    }

    /** True only the first time it is called for a job, so the "finished" notification is sent once. */
    public boolean markNotified(String id) {
        return notified.add(id);
    }

    private void purgeExpired() {
        long cutoff = System.currentTimeMillis() - TTL_MILLIS;
        jobs.entrySet().removeIf(e -> e.getValue().createdAtMillis() < cutoff);
    }
}
