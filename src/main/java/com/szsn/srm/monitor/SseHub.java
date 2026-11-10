package com.szsn.srm.monitor;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class SseHub {
    public static final int MAX_CONNECTIONS = 5;

    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final int maxConnections;

    public SseHub() {
        this(MAX_CONNECTIONS);
    }

    public SseHub(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public Client connect(String sessionId, SseSink sink, BooleanSupplier sessionAlive) {
        if (clients.size() >= maxConnections) {
            return null;
        }
        Client client = new Client(sessionId, sink, sessionAlive);
        clients.add(client);
        client.start();
        return client;
    }

    public void publish(LiveFrame frame) {
        for (Client client : clients) {
            client.offer(frame);
        }
    }

    public void closeSession(String sessionId) {
        for (Client client : clients) {
            if (sessionId != null && sessionId.equals(client.sessionId)) {
                client.close();
            }
        }
    }

    public void shutdown() {
        for (Client client : clients) {
            client.close();
        }
    }

    public int size() {
        return clients.size();
    }

    public interface SseSink {
        void send(String event, String id, Object data) throws IOException;

        void close();
    }

    public record LiveFrame(MetricSnapshot metric, ResourcePulse resource, SafetyPulse safety) {
    }

    public record ResourcePulse(String controlSwitch, boolean emergencyLatched, boolean controlCapable, List<String> controlDisabledReasons) {
    }

    public record SafetyPulse(
            Quality quality,
            long ageMs,
            boolean storageHealthy,
            String storageFault,
            boolean forbidCpuGrowth,
            boolean zeroDuty,
            boolean forbidMemoryGrowth,
            boolean safetyStop,
            boolean latch,
            List<String> reasons) {

        public SafetyPulse(Quality quality, long ageMs, boolean storageHealthy, String storageFault) {
            this(quality, ageMs, storageHealthy, storageFault, true, true, true, false, false, List.of());
        }
    }

    public final class Client {
        private final String sessionId;
        private final SseSink sink;
        private final BooleanSupplier sessionAlive;
        private final AtomicReference<LiveFrame> pending = new AtomicReference<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Object lock = new Object();
        private int coalesced;
        private Thread thread;

        private Client(String sessionId, SseSink sink, BooleanSupplier sessionAlive) {
            this.sessionId = sessionId;
            this.sink = sink;
            this.sessionAlive = sessionAlive;
        }

        public int coalesced() {
            return coalesced;
        }

        public boolean isRunning() {
            return running.get();
        }

        private void start() {
            thread = new Thread(this::loop, "srm-sse");
            thread.setDaemon(true);
            thread.start();
        }

        public void offer(LiveFrame frame) {
            synchronized (lock) {
                if (pending.getAndSet(frame) != null) {
                    coalesced++;
                }
                lock.notifyAll();
            }
        }

        public void close() {
            if (running.compareAndSet(true, false)) {
                clients.remove(this);
                synchronized (lock) {
                    lock.notifyAll();
                }
                sink.close();
                if (thread != null) {
                    thread.interrupt();
                }
            }
        }

        private void loop() {
            long lastSent = System.currentTimeMillis();
            while (running.get()) {
                if (!sessionAlive.getAsBoolean()) {
                    close();
                    return;
                }
                LiveFrame frame = take(lastSent);
                if (frame == null) {
                    if (!running.get()) {
                        return;
                    }
                    if (System.currentTimeMillis() - lastSent >= 15_000) {
                        sendHeartbeat();
                        lastSent = System.currentTimeMillis();
                    }
                    continue;
                }
                try {
                    String id = frame.metric().bootId() + ":" + frame.metric().sequence();
                    sink.send("metric", id, frame.metric());
                    sink.send("resource", id, frame.resource());
                    sink.send("safety", id, frame.safety());
                    lastSent = System.currentTimeMillis();
                } catch (IOException exception) {
                    close();
                    return;
                }
            }
        }

        private LiveFrame take(long lastSent) {
            synchronized (lock) {
                LiveFrame frame = pending.getAndSet(null);
                if (frame != null || !running.get()) {
                    return frame;
                }
                long waitMs = Math.max(1, Math.min(1_000, 15_000 - (System.currentTimeMillis() - lastSent)));
                try {
                    lock.wait(waitMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return pending.getAndSet(null);
            }
        }

        private void sendHeartbeat() {
            try {
                sink.send("heartbeat", null, java.util.Map.of("serverTime", System.currentTimeMillis()));
            } catch (IOException exception) {
                close();
            }
        }
    }
}
