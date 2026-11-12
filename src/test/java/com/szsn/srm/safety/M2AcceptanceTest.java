package com.szsn.srm.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.Units;
import com.szsn.srm.monitor.MetricSnapshot;
import com.szsn.srm.monitor.Quality;
import com.szsn.srm.monitor.SnapshotStore;
import com.szsn.srm.resource.ControlAudit;
import com.szsn.srm.resource.ControlState;
import com.szsn.srm.resource.ControlSwitch;
import com.szsn.srm.resource.CpuBurner;
import com.szsn.srm.resource.CpuEngine;
import com.szsn.srm.resource.CpuEngineView;
import com.szsn.srm.resource.ExecutionStatus;
import com.szsn.srm.resource.LatchStore;
import com.szsn.srm.resource.Operator;
import com.szsn.srm.resource.ResourceLoadManager;
import com.szsn.srm.resource.SessionClaim;
import com.szsn.srm.resource.SessionView;
import com.szsn.srm.resource.StopKind;
import com.szsn.srm.resource.StoredLatch;
import com.szsn.srm.system.StorageHealth;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * M2 验收：AT-04 / AT-09 / AT-10 / AT-13 / AT-14。
 * 使用可注入时钟与采集器，瞬时燃烧器，不占满开发机 CPU，不分配内存。
 */
class M2AcceptanceTest {
    private CpuEngine engine;
    private ResourceLoadManager sessions;

    @AfterEach
    void tearDown() throws Exception {
        if (sessions != null) {
            sessions.emergencyStop();
        }
        if (engine != null) {
            engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS);
        }
    }

    @Test
    void at04ExternalCpuSpikeZeroesDutyThenRampsGradually() throws Exception {
        CpuHarness cpu = cpuHarness();
        engine = cpu.engine;
        sessions = cpu.sessions;
        SessionClaim claim = sessions.claim("at04");
        engine.start(claim, 30, 2, false);

        cpu.samples.set(10D);
        cpu.safety.set(healthy());
        double ramping = engine.controlTick().duty();
        assertTrue(ramping > 0 && ramping <= 0.05, "duty=" + ramping);

        cpu.samples.set(50D);
        engine.controlTick();
        CpuEngineView zeroed = engine.controlTick();
        assertTrue(zeroed.externalLoadHigh());
        assertEquals(0, zeroed.duty());
        assertTrue(zeroed.limitedReasons().contains("EXTERNAL_LOAD_HIGH"));

        cpu.samples.set(10D);
        CpuEngineView recovered = engine.controlTick();
        assertFalse(recovered.externalLoadHigh());
        assertTrue(recovered.duty() > 0 && recovered.duty() <= 0.05, "duty=" + recovered.duty());
        assertTrue(recovered.duty() <= ramping + 1e-12);

        sessions.requestStop("at04", StopKind.USER);
        assertTrue(engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS));
        engine = null;
    }

    @Test
    void at09UserAndDurationStopConfirmExitWithinTwoSeconds() throws Exception {
        CpuHarness user = cpuHarness();
        engine = user.engine;
        sessions = user.sessions;
        SessionClaim claim = sessions.claim("at09-user");
        engine.start(claim, 40, 3, false);
        user.samples.set(10D);
        user.safety.set(healthy());
        engine.controlTick();
        sessions.observeHeldBytes("at09-user", 0);

        long began = System.nanoTime();
        sessions.requestStop("at09-user", StopKind.USER);
        assertTrue(engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS));
        long elapsedMs = (System.nanoTime() - began) / 1_000_000L;
        assertTrue(elapsedMs <= CpuEngine.STOP_TIMEOUT_MS, "elapsedMs=" + elapsedMs);
        assertTrue(sessions.active().isEmpty());
        SessionView finished = sessions.lastFinished().orElseThrow();
        assertEquals(ExecutionStatus.STOPPED, finished.status());
        assertEquals(0, finished.heldBytes());
        engine = null;

        CpuHarness duration = cpuHarness();
        engine = duration.engine;
        sessions = duration.sessions;
        SessionClaim timed = sessions.claim("at09-duration");
        engine.start(timed, 30, 2, false);
        began = System.nanoTime();
        sessions.requestStop("at09-duration", StopKind.DURATION);
        assertTrue(engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS));
        elapsedMs = (System.nanoTime() - began) / 1_000_000L;
        assertTrue(elapsedMs <= CpuEngine.STOP_TIMEOUT_MS, "elapsedMs=" + elapsedMs);
        assertEquals(ExecutionStatus.COMPLETED, sessions.lastFinished().orElseThrow().status());
        assertEquals(0, sessions.lastFinished().orElseThrow().heldBytes());
        engine = null;
    }

    @Test
    void at10ConcurrentAdjustAndEmergencyRejectsOldGenerationAndNewStarts() throws Exception {
        MemoryStore store = new MemoryStore();
        MutableClock clock = new MutableClock(2_000_000L);
        ResourceLoadManager manager = new ResourceLoadManager();
        LatchHealthWindow health = new LatchHealthWindow();
        StorageHealth storage = new StorageHealth();
        ControlSwitch control = new ControlSwitch(manager, store, health, storage, clock, ControlAudit.NONE);
        control.setEnabled(true, operator());

        AtomicReference<SafetySignal> safety = new AtomicReference<>(healthy());
        AtomicReference<Double> samples = new AtomicReference<>(10D);
        engine = new CpuEngine(manager, samples::get, safety::get, instantBurner());
        sessions = manager;

        SessionClaim claim = manager.claim("at10");
        engine.start(claim, 40, 2, false);
        samples.set(10D);
        engine.controlTick();

        CountDownLatch startedAdjust = new CountDownLatch(1);
        CountDownLatch releaseAdjust = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> adjust = pool.submit(() -> {
                startedAdjust.countDown();
                await(releaseAdjust);
                try {
                    engine.updateTarget(1, 55);
                } catch (RuntimeException ignored) {
                    // 急停竞态下允许失败
                }
            });
            Future<ControlState> emergency = pool.submit(() -> {
                await(startedAdjust);
                return control.emergencyStop(operator());
            });
            assertTrue(startedAdjust.await(2, TimeUnit.SECONDS));
            releaseAdjust.countDown();
            ControlState latched = emergency.get(2, TimeUnit.SECONDS);
            adjust.get(2, TimeUnit.SECONDS);

            assertFalse(claim.permit().valid());
            assertEquals("OFF", latched.controlSwitch());
            assertTrue(latched.emergencyLatched());
            assertEquals("EMERGENCY_STOP", latched.latchReason());
            assertTrue(engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS));

            ApiException denied = assertThrows(ApiException.class, control::admit);
            assertEquals("CONTROL_LATCHED", denied.code());
            assertEquals(HttpStatus.CONFLICT, denied.status());

            AtomicInteger launches = new AtomicInteger();
            assertThrows(ApiException.class, () -> {
                control.admit();
                launches.incrementAndGet();
            });
            assertEquals(0, launches.get());

            feedHealthy(health, clock, LatchHealthWindow.REQUIRED_MS);
            control.resetLatch(operator());
            assertEquals("OFF", control.state().controlSwitch());
            assertThrows(ApiException.class, control::admit);
        } finally {
            pool.shutdownNow();
        }
        engine = null;
    }

    @Test
    void at13StaleMetricsForbidThenStopAndMissingTemperatureDoesNotTrigger() {
        MutableClock clock = new MutableClock(3_000_000L);
        ResourceLoadManager manager = new ResourceLoadManager();
        LatchHealthWindow health = new LatchHealthWindow();
        StorageHealth storage = new StorageHealth();
        ControlSwitch control = new ControlSwitch(manager, new MemoryStore(), health, storage, clock, ControlAudit.NONE);
        SafetyLoop loop = new SafetyLoop(new SnapshotStore(), control, health, storage, clock);

        manager.claim("at13");
        manager.markRunning("at13");

        long observed = clock.millis() - 4_000;
        loop.evaluate(snapshot(1, observed, clock.millis(), 20D, List.of("cpu.temperature")));
        assertTrue(loop.current().forbidCpuGrowth());
        assertTrue(loop.current().forbidMemoryGrowth());
        assertFalse(loop.current().safetyStop());
        assertFalse(control.state().emergencyLatched());
        assertTrue(loop.current().reasons().contains("METRIC_STALE_3S"));

        observed = clock.millis() - 11_000;
        loop.evaluate(snapshot(2, observed, clock.millis(), 20D, List.of("cpu.temperature")));
        assertTrue(loop.current().safetyStop());
        assertTrue(control.state().emergencyLatched());
        assertEquals("METRIC_STALE_10S", control.state().latchReason());

        MutableClock freshClock = new MutableClock(5_000_000L);
        ResourceLoadManager freshSessions = new ResourceLoadManager();
        LatchHealthWindow freshHealth = new LatchHealthWindow();
        StorageHealth freshStorage = new StorageHealth();
        ControlSwitch freshControl = new ControlSwitch(freshSessions, new MemoryStore(), freshHealth, freshStorage,
                freshClock, ControlAudit.NONE);
        SafetyLoop freshLoop = new SafetyLoop(new SnapshotStore(), freshControl, freshHealth, freshStorage, freshClock);
        freshLoop.evaluate(snapshot(3, freshClock.millis(), freshClock.millis(), 20D, List.of("cpu.temperature")));
        assertFalse(freshLoop.current().forbidCpuGrowth());
        assertFalse(freshLoop.current().zeroDuty());
        assertFalse(freshLoop.current().safetyStop());
        assertFalse(freshControl.state().emergencyLatched());
        assertEquals(Quality.PARTIAL, freshLoop.current().quality());
    }

    @Test
    void at14PressureWindowsHysteresisAndLatchRequireManualReset() {
        ProtectionEvaluator evaluator = new ProtectionEvaluator();
        assertFalse(evaluator.onSample(cpuSample(94)).zeroDuty());
        assertFalse(evaluator.onSample(cpuSample(94)).zeroDuty());
        assertTrue(evaluator.onSample(cpuSample(94)).zeroDuty());
        for (int i = 0; i < 9; i++) {
            assertTrue(evaluator.onSample(cpuSample(70)).zeroDuty());
        }
        assertFalse(evaluator.onSample(cpuSample(70)).zeroDuty());

        ProtectionEvaluator steal = new ProtectionEvaluator();
        for (int i = 0; i < 9; i++) {
            assertFalse(steal.onSample(stealSample(25, 0)).zeroDuty());
        }
        assertTrue(steal.onSample(stealSample(25, 0)).zeroDuty());
        for (int i = 0; i < 9; i++) {
            assertTrue(steal.onSample(stealSample(5, 5)).zeroDuty());
        }
        assertFalse(steal.onSample(stealSample(5, 5)).zeroDuty());

        MutableClock clock = new MutableClock(4_000_000L);
        ResourceLoadManager manager = new ResourceLoadManager();
        LatchHealthWindow health = new LatchHealthWindow();
        StorageHealth storage = new StorageHealth();
        ControlSwitch control = new ControlSwitch(manager, new MemoryStore(), health, storage, clock, ControlAudit.NONE);
        control.setEnabled(true, operator());
        SafetyLoop loop = new SafetyLoop(new SnapshotStore(), control, health, storage, clock);
        SessionClaim claim = manager.claim("at14");
        manager.markRunning("at14");

        for (int i = 0; i < 3; i++) {
            loop.evaluate(snapshot(10 + i, clock.millis(), clock.millis(), 97D, List.of()));
            clock.advance(1_000);
        }
        assertTrue(control.state().emergencyLatched());
        assertEquals("CPU_GE_97", control.state().latchReason());
        assertEquals("OFF", control.state().controlSwitch());
        assertFalse(claim.permit().valid());

        for (int i = 0; i < 40; i++) {
            loop.evaluate(snapshot(100 + i, clock.millis(), clock.millis(), 20D, List.of()));
            clock.advance(1_000);
        }
        assertTrue(control.state().emergencyLatched());
        assertEquals("OFF", control.state().controlSwitch());
        assertThrows(ApiException.class, control::admit);

        manager.confirmWorkerExit(claim.permit());
        feedHealthy(health, clock, LatchHealthWindow.REQUIRED_MS);
        assertTrue(control.state().latchResetReady());
        control.resetLatch(operator());
        assertFalse(control.state().emergencyLatched());
        assertEquals("OFF", control.state().controlSwitch());
    }

    private CpuHarness cpuHarness() {
        ResourceLoadManager manager = new ResourceLoadManager();
        AtomicReference<Double> samples = new AtomicReference<>(10D);
        AtomicReference<SafetySignal> safety = new AtomicReference<>(healthy());
        CpuEngine created = new CpuEngine(manager, samples::get, safety::get, instantBurner());
        return new CpuHarness(manager, created, samples, safety);
    }

    private static CpuBurner instantBurner() {
        return (nanos, permit, allowed) -> permit.valid() && allowed.getAsBoolean() && nanos > 0 ? 1L : 0L;
    }

    private static SafetySignal healthy() {
        return new SafetySignal(1, 1L, 0, Quality.VALID, true, null, false, false, false, false, false, List.of());
    }

    private static ProtectionSample cpuSample(double cpu) {
        return new ProtectionSample(cpu, 0, 0D, 0D, 4L * Units.GIB, 8L * Units.GIB, 0, 100L * Units.MIB, Units.GIB, false, null);
    }

    private static ProtectionSample stealSample(double steal, double iowait) {
        return new ProtectionSample(20D, 0, steal, iowait, 4L * Units.GIB, 8L * Units.GIB, 0, 100L * Units.MIB, Units.GIB, false, null);
    }

    private static MetricSnapshot snapshot(long sequence, long observedAt, long now, double cpu, List<String> missing) {
        List<String> fields = missing == null ? List.of() : List.copyOf(missing);
        MetricSnapshot raw = new MetricSnapshot(
                sequence,
                "boot",
                observedAt,
                Math.max(0, now - observedAt),
                Quality.VALID,
                fields,
                true,
                new MetricSnapshot.HostView("h", "os", "1", "k", "cpu", 4, "17", 1L, 1L),
                new MetricSnapshot.CpuView(cpu, 1D, 1D, 1D, 0D, 0D, null, null, null),
                new MetricSnapshot.MemoryView(8L * Units.GIB, 4L * Units.GIB, 4L * Units.GIB, null, 0L, 0L),
                new MetricSnapshot.JvmView(100L * Units.MIB, 100L * Units.MIB, Units.GIB, 1L, 0L, 0L, 1, 1L, 1D),
                List.of(),
                List.of(),
                new MetricSnapshot.NetworkView(null, null, null, null, List.of()));
        return raw.freshen(now);
    }

    private static void feedHealthy(LatchHealthWindow health, MutableClock clock, long spanMs) {
        long start = clock.millis();
        for (long at = start; at <= start + spanMs; at += 1_000) {
            clock.set(at);
            health.observe(new KeyMetricSample(
                    at, 20D, 0, 4L * Units.GIB, 8L * Units.GIB, 0, 100L * Units.MIB, Units.GIB, false, null));
        }
    }

    private static Operator operator() {
        return new Operator("admin", "127.0.0.1", "req-m2");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record CpuHarness(
            ResourceLoadManager sessions,
            CpuEngine engine,
            AtomicReference<Double> samples,
            AtomicReference<SafetySignal> safety) {
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong now;
        private final ZoneOffset zone = ZoneOffset.UTC;

        private MutableClock(long start) {
            this.now = new AtomicLong(start);
        }

        void advance(long millis) {
            now.addAndGet(millis);
        }

        void set(long millis) {
            now.set(millis);
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
    }

    private static final class MemoryStore implements LatchStore {
        private final AtomicReference<StoredLatch> row = new AtomicReference<>();

        @Override
        public Optional<StoredLatch> load() {
            return Optional.ofNullable(row.get());
        }

        @Override
        public void save(StoredLatch latch) {
            row.set(latch);
        }
    }
}
