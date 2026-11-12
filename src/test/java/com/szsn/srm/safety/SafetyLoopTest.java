package com.szsn.srm.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.szsn.srm.common.Units;
import com.szsn.srm.monitor.MetricSnapshot;
import com.szsn.srm.monitor.Quality;
import com.szsn.srm.monitor.SnapshotStore;
import com.szsn.srm.resource.ControlAudit;
import com.szsn.srm.resource.ControlSwitch;
import com.szsn.srm.resource.ExecutionStatus;
import com.szsn.srm.resource.LatchStore;
import com.szsn.srm.resource.ResourceLoadManager;
import com.szsn.srm.resource.SessionClaim;
import com.szsn.srm.resource.StoredLatch;
import com.szsn.srm.system.StorageHealth;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SafetyLoopTest {
    @Test
    void usesDedicatedSafetyThreadNotSharedWithCaller() throws Exception {
        Harness harness = harness();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        harness.loop.start();
        try {
            harness.store.add(healthy(1, harness.clock.millis()));
            long deadline = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < deadline) {
                SafetySignal signal = harness.loop.current();
                if (signal.snapshotSequence() != null && signal.snapshotSequence() == 1L) {
                    break;
                }
                Thread.sleep(50);
            }
            assertEquals(1L, harness.loop.current().snapshotSequence());
            Thread[] threads = new Thread[Thread.activeCount() + 8];
            int count = Thread.enumerate(threads);
            boolean found = false;
            for (int i = 0; i < count; i++) {
                if (threads[i] != null && SafetyLoop.THREAD_NAME.equals(threads[i].getName())) {
                    found = true;
                    threadName.set(threads[i].getName());
                    started.countDown();
                    break;
                }
            }
            assertTrue(found);
            assertEquals(SafetyLoop.THREAD_NAME, threadName.get());
            assertTrue(started.await(1, TimeUnit.SECONDS));
        } finally {
            harness.loop.stop();
        }
    }

    @Test
    void cpuPressureForbidsGrowthThenLatchesAfterThreeSamples() {
        Harness harness = harness();
        SessionClaim claim = harness.sessions.claim("exec-cpu");
        harness.sessions.markRunning("exec-cpu");

        harness.loop.evaluate(cpu(1, harness.now.get(), 94));
        assertFalse(harness.loop.current().zeroDuty());
        harness.advance(1_000);
        harness.loop.evaluate(cpu(2, harness.now.get(), 94));
        assertFalse(harness.loop.current().zeroDuty());
        harness.advance(1_000);
        harness.loop.evaluate(cpu(3, harness.now.get(), 94));
        assertTrue(harness.loop.current().zeroDuty());
        assertTrue(harness.loop.current().forbidCpuGrowth());
        assertFalse(harness.control.state().emergencyLatched());

        harness.advance(1_000);
        harness.loop.evaluate(cpu(4, harness.now.get(), 97));
        harness.advance(1_000);
        harness.loop.evaluate(cpu(5, harness.now.get(), 97));
        harness.advance(1_000);
        harness.loop.evaluate(cpu(6, harness.now.get(), 97));

        assertTrue(harness.control.state().emergencyLatched());
        assertEquals("CPU_GE_97", harness.control.state().latchReason());
        assertEquals("OFF", harness.control.state().controlSwitch());
        assertFalse(claim.permit().valid());
        assertEquals(ExecutionStatus.STOPPING, harness.sessions.active().orElseThrow().status());
    }

    @Test
    void staleMetricsForbidThenStopAndLatch() {
        Harness harness = harness();
        harness.sessions.claim("exec-stale");
        harness.sessions.markRunning("exec-stale");

        long observed = harness.now.get() - 4_000;
        harness.loop.evaluate(cpuAt(1, observed, harness.now.get(), 10));
        assertTrue(harness.loop.current().forbidCpuGrowth());
        assertTrue(harness.loop.current().forbidMemoryGrowth());
        assertFalse(harness.loop.current().safetyStop());
        assertFalse(harness.control.state().emergencyLatched());

        observed = harness.now.get() - 11_000;
        harness.loop.evaluate(cpuAt(2, observed, harness.now.get(), 10));
        assertTrue(harness.loop.current().safetyStop());
        assertTrue(harness.control.state().emergencyLatched());
        assertEquals("METRIC_STALE_10S", harness.control.state().latchReason());
    }

    @Test
    void missingSwapRateDoesNotInventPressure() {
        Harness harness = harness();
        harness.loop.evaluate(withSwap(1, harness.now.get(), 0L));
        assertFalse(harness.loop.current().reasons().contains("SWAP_RATE"));
        assertFalse(harness.control.state().emergencyLatched());

        harness.advance(1_000);
        harness.loop.evaluate(withSwap(2, harness.now.get(), 16L * Units.MIB));
        for (int i = 0; i < 3; i++) {
            harness.advance(1_000);
            harness.loop.evaluate(withSwap(3 + i, harness.now.get(), 16L * Units.MIB + (i + 1) * 16L * Units.MIB));
        }
        harness.advance(1_000);
        harness.loop.evaluate(withSwap(7, harness.now.get(), 16L * Units.MIB + 5L * 16L * Units.MIB));
        assertTrue(harness.control.state().emergencyLatched());
        assertEquals("SWAP_RATE", harness.control.state().latchReason());
    }

    @Test
    void storageFaultLatchesWithoutWaitingOnHistoryQueue() throws Exception {
        Harness harness = harness();
        SessionClaim claim = harness.sessions.claim("exec-storage");
        harness.sessions.markRunning("exec-storage");
        CountDownLatch historyBlocked = new CountDownLatch(1);
        CountDownLatch releaseHistory = new CountDownLatch(1);
        Thread history = new Thread(() -> {
            historyBlocked.countDown();
            try {
                releaseHistory.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "srm-history-blocker");
        history.start();
        assertTrue(historyBlocked.await(2, TimeUnit.SECONDS));

        harness.storage.markFault("历史写入失败");
        harness.loop.evaluate(healthy(9, harness.now.get()));

        assertTrue(harness.control.state().emergencyLatched());
        assertEquals(SafetyLoop.STORAGE_REASON, harness.control.state().latchReason());
        assertFalse(claim.permit().valid());
        releaseHistory.countDown();
        history.join(2_000);
    }

    @Test
    void healthWindowReceivesSwapRateFromSafetyLoop() {
        Harness harness = harness();
        long start = harness.now.get();
        for (long at = start; at <= start + LatchHealthWindow.REQUIRED_MS; at += 1_000) {
            harness.now.set(at);
            harness.loop.evaluate(healthy(at, at));
        }
        assertTrue(harness.health.view().ready());
        assertTrue(harness.health.view().reasons().isEmpty());
    }

    @Test
    void safetyLatchDoesNotRequireDatabaseBeforeWorkerInvalidation() throws Exception {
        BlockingStore store = new BlockingStore();
        Harness harness = harness(store);
        SessionClaim claim = harness.sessions.claim("exec-block");
        harness.sessions.markRunning("exec-block");
        Thread worker = new Thread(() -> harness.loop.evaluate(cpu(1, harness.now.get(), 97)), "test-eval");
        // warm streaks then latch on third
        harness.loop.evaluate(cpu(1, harness.now.get(), 97));
        harness.advance(1_000);
        harness.loop.evaluate(cpu(2, harness.now.get(), 97));
        harness.advance(1_000);
        worker = new Thread(() -> harness.loop.evaluate(cpu(3, harness.now.get(), 97)), "test-eval");
        worker.start();
        assertTrue(store.entered.await(2, TimeUnit.SECONDS));
        assertFalse(claim.permit().valid());
        assertTrue(harness.control.state().emergencyLatched());
        store.release.countDown();
        worker.join(2_000);
        assertTrue(harness.control.state().latchPersisted());
    }

    private static Harness harness() {
        return harness(new MemoryStore());
    }

    private static Harness harness(LatchStore store) {
        MutableClock clock = new MutableClock(1_000_000L);
        ResourceLoadManager sessions = new ResourceLoadManager();
        LatchHealthWindow health = new LatchHealthWindow();
        StorageHealth storage = new StorageHealth();
        ControlSwitch control = new ControlSwitch(sessions, store, health, storage, clock, ControlAudit.NONE);
        SnapshotStore snapshots = new SnapshotStore();
        SafetyLoop loop = new SafetyLoop(snapshots, control, health, storage, clock);
        return new Harness(sessions, control, health, storage, snapshots, loop, clock, clock.now);
    }

    private static MetricSnapshot healthy(long sequence, long observedAt) {
        return snapshot(sequence, observedAt, observedAt, 20D, 0D, 0D, 4L * Units.GIB, 8L * Units.GIB, 0L, 0L,
                100L * Units.MIB, Units.GIB);
    }

    private static MetricSnapshot cpu(long sequence, long now, double busy) {
        return cpuAt(sequence, now, now, busy);
    }

    private static MetricSnapshot cpuAt(long sequence, long observedAt, long now, double busy) {
        return snapshot(sequence, observedAt, now, busy, 0D, 0D, 4L * Units.GIB, 8L * Units.GIB, 0L, 0L,
                100L * Units.MIB, Units.GIB);
    }

    private static MetricSnapshot withSwap(long sequence, long now, long swapUsed) {
        return snapshot(sequence, now, now, 20D, 0D, 0D, 4L * Units.GIB, 8L * Units.GIB, Units.GIB, swapUsed,
                100L * Units.MIB, Units.GIB);
    }

    private static MetricSnapshot snapshot(long sequence, long observedAt, long now, double cpu, double steal,
                                          double iowait, long available, long total, long swapTotal, long swapUsed,
                                          long heapUsed, long heapMax) {
        List<String> missing = new ArrayList<>();
        MetricSnapshot raw = new MetricSnapshot(
                sequence,
                "boot",
                observedAt,
                Math.max(0, now - observedAt),
                Quality.VALID,
                missing,
                true,
                new MetricSnapshot.HostView("h", "os", "1", "k", "cpu", 4, "17", 1L, 1L),
                new MetricSnapshot.CpuView(cpu, 1D, 1D, 1D, iowait, steal, null, null, null),
                new MetricSnapshot.MemoryView(total, available, total - available, null, swapTotal, swapUsed),
                new MetricSnapshot.JvmView(heapUsed, heapUsed, heapMax, 1L, 0L, 0L, 1, 1L, 1D),
                List.of(),
                List.of(),
                new MetricSnapshot.NetworkView(null, null, null, null, List.of()));
        return raw.freshen(now);
    }

    private record Harness(
            ResourceLoadManager sessions,
            ControlSwitch control,
            LatchHealthWindow health,
            StorageHealth storage,
            SnapshotStore store,
            SafetyLoop loop,
            Clock clock,
            AtomicLong now) {
        void advance(long millis) {
            now.addAndGet(millis);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong now;
        private final ZoneOffset zone = ZoneOffset.UTC;

        private MutableClock(long start) {
            this.now = new AtomicLong(start);
        }

        @Override
        public ZoneOffset getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }

        @Override
        public long millis() {
            return now.get();
        }
    }

    private static final class MemoryStore implements LatchStore {
        private final AtomicReference<StoredLatch> row = new AtomicReference<>();
        private final AtomicLong saves = new AtomicLong();

        @Override
        public Optional<StoredLatch> load() {
            return Optional.ofNullable(row.get());
        }

        @Override
        public void save(StoredLatch latch) {
            row.set(latch);
            saves.incrementAndGet();
        }
    }

    private static final class BlockingStore implements LatchStore {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<StoredLatch> row = new AtomicReference<>();

        @Override
        public Optional<StoredLatch> load() {
            return Optional.ofNullable(row.get());
        }

        @Override
        public void save(StoredLatch latch) {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            row.set(latch);
        }
    }
}
