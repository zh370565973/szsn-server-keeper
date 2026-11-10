package com.szsn.srm.auth;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {
    static final Duration WINDOW = Duration.ofMinutes(5);
    static final Duration COOLDOWN = Duration.ofMinutes(5);
    static final int MAX_FAILURES = 5;

    private final java.time.Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public LoginRateLimiter(java.time.Clock clock) {
        this.clock = clock;
    }

    public boolean coolingDown(String key) {
        Bucket bucket = buckets.get(key);
        return bucket != null && clock.millis() < bucket.cooldownUntil;
    }

    public void recordFailure(String key) {
        long now = clock.millis();
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket());
        synchronized (bucket) {
            if (now < bucket.cooldownUntil) {
                return;
            }
            while (!bucket.failures.isEmpty() && now - bucket.failures.peekFirst() > WINDOW.toMillis()) {
                bucket.failures.removeFirst();
            }
            bucket.failures.addLast(now);
            if (bucket.failures.size() >= MAX_FAILURES) {
                bucket.cooldownUntil = now + COOLDOWN.toMillis();
                bucket.failures.clear();
            }
        }
    }

    public void recordSuccess(String key) {
        buckets.remove(key);
    }

    public static String key(String username, String remoteAddr) {
        return (username == null ? "" : username) + "|" + (remoteAddr == null ? "" : remoteAddr);
    }

    private static final class Bucket {
        private final Deque<Long> failures = new ArrayDeque<>();
        private long cooldownUntil;
    }
}
