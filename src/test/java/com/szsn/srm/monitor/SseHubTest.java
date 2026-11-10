package com.szsn.srm.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SseHubTest {
    @Test
    void sixthConnectionIsRejected() {
        SseHub hub = new SseHub(5);
        List<SseHub.Client> clients = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            clients.add(hub.connect("s" + i, sink(), () -> true));
        }
        assertNull(hub.connect("overflow", sink(), () -> true));
        clients.forEach(SseHub.Client::close);
        assertEquals(0, hub.size());
    }

    @Test
    void slowClientDoesNotBlockPublishAndOnlyLatestRemains() throws Exception {
        SseHub hub = new SseHub(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong seen = new AtomicLong();
        SseHub.Client client = hub.connect("session", new SseHub.SseSink() {
            @Override
            public void send(String event, String id, Object data) throws IOException {
                if (!"metric".equals(event)) {
                    return;
                }
                seen.set(Long.parseLong(id.substring(id.indexOf(':') + 1)));
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }

            @Override
            public void close() {
                release.countDown();
            }
        }, () -> true);
        assertNotNull(client);
        hub.publish(frame(1));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        long started = System.nanoTime();
        hub.publish(frame(2));
        hub.publish(frame(3));
        assertTrue((System.nanoTime() - started) / 1_000_000 < 200);
        release.countDown();
        Thread.sleep(200);
        assertEquals(3, seen.get());
        assertTrue(client.coalesced() >= 1);
        client.close();
    }

    @Test
    void closingSessionStopsTheClient() {
        SseHub hub = new SseHub();
        SseHub.Client client = hub.connect("abc", sink(), () -> true);
        hub.closeSession("abc");
        assertTrue(client != null && !client.isRunning());
    }

    private static SseHub.SseSink sink() {
        return new SseHub.SseSink() {
            @Override
            public void send(String event, String id, Object data) {
            }

            @Override
            public void close() {
            }
        };
    }

    private static SseHub.LiveFrame frame(long sequence) {
        MetricSnapshot snapshot = new MetricSnapshot(
                sequence,
                "boot",
                1,
                0,
                Quality.VALID,
                List.of(),
                true,
                new MetricSnapshot.HostView(null, null, null, null, null, 1, "17", 1L, 1L),
                new MetricSnapshot.CpuView(1D, 1D, 1D, 1D, 1D, 1D, null, null, null),
                new MetricSnapshot.MemoryView(2L, 1L, 1L, null, 0L, 0L),
                new MetricSnapshot.JvmView(1L, 1L, 1L, 1L, 0L, 0L, 1, 1L, 1D),
                List.of(),
                List.of(),
                new MetricSnapshot.NetworkView(null, null, null, null, List.of()));
        return new SseHub.LiveFrame(snapshot, new SseHub.ResourcePulse("OFF", false, false, List.of()),
                new SseHub.SafetyPulse(Quality.VALID, 0, true, null));
    }
}
