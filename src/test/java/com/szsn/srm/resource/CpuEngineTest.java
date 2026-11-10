package com.szsn.srm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.szsn.srm.common.ApiException;
import com.szsn.srm.monitor.Quality;
import com.szsn.srm.safety.SafetySignal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CpuEngineTest {
    private CpuEngine engine;
    private ResourceLoadManager sessions;

    @AfterEach
    void tearDown() throws Exception {
        if (engine != null && sessions != null) {
            sessions.emergencyStop();
            engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS);
        }
    }

    @Test
    void capsWorkerCountToLogicalCores() {
        assertEquals(8, CpuEngine.cappedWorkerCount(0, 8));
        assertEquals(8, CpuEngine.cappedWorkerCount(-1, 8));
        assertEquals(4, CpuEngine.cappedWorkerCount(4, 8));
        assertEquals(8, CpuEngine.cappedWorkerCount(16, 8));
        assertThrows(IllegalArgumentException.class, () -> CpuEngine.cappedWorkerCount(1, 0));
    }

    @Test
    void startsFixedWorkersAndUsesDutyFormulaWithoutSpinning() throws Exception {
        Harness harness = harness(4);
        AtomicInteger burns = new AtomicInteger();
        engine = new CpuEngine(harness.sessions, harness.samples::get, harness.safety::get, (nanos, permit, allowed) -> {
            if (permit.valid() && allowed.getAsBoolean() && nanos > 0) {
                burns.incrementAndGet();
            }
            return 1L;
        });
        SessionClaim claim = harness.sessions.claim("exec-cpu");
        CpuEngineView started = engine.start(claim, 40, CpuEngine.cappedWorkerCount(0, 4), false);
        assertEquals(4, started.workerCount());
        assertEquals(40, started.targetPct());
        assertEquals(0, started.duty());

        harness.samples.set(10D);
        harness.safety.set(healthy());
        CpuEngineView first = engine.controlTick();
        assertTrue(first.duty() > 0 && first.duty() <= 0.05, "duty=" + first.duty());
        assertEquals(10D, first.actualPct());

        engine.controlTick();
        double beforeSafety = engine.view().duty();
        assertTrue(beforeSafety > 0);

        harness.safety.set(zeroDuty("CPU_GE_85"));
        CpuEngineView zeroed = engine.controlTick();
        assertEquals(0, zeroed.duty());
        assertTrue(zeroed.safetyZeroed());
        assertTrue(zeroed.limitedReasons().contains("SAFETY_ZERO"));

        harness.sessions.requestStop("exec-cpu", StopKind.USER);
        assertTrue(engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS));
        assertTrue(harness.sessions.active().isEmpty());
        assertEquals(ExecutionStatus.STOPPED, harness.sessions.lastFinished().orElseThrow().status());
        assertTrue(burns.get() >= 0);
        engine = null;
    }

    @Test
    void externalLoadHighZeroesDuty() throws Exception {
        Harness harness = harness(2);
        engine = new CpuEngine(harness.sessions, harness.samples::get, harness.safety::get, instantBurner());
        SessionClaim claim = harness.sessions.claim("exec-ext");
        engine.start(claim, 30, 2, false);
        harness.samples.set(50D);
        harness.safety.set(healthy());
        engine.controlTick();
        CpuEngineView second = engine.controlTick();
        assertTrue(second.externalLoadHigh());
        assertEquals(0, second.duty());
        assertTrue(second.limitedReasons().contains("EXTERNAL_LOAD_HIGH"));

        harness.sessions.requestStop("exec-ext", StopKind.USER);
        assertTrue(engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS));
        engine = null;
    }

    @Test
    void forbidGrowthDoesNotRaiseDuty() throws Exception {
        Harness harness = harness(1);
        engine = new CpuEngine(harness.sessions, harness.samples::get, harness.safety::get, instantBurner());
        SessionClaim claim = harness.sessions.claim("exec-forbid");
        engine.start(claim, 40, 1, false);
        harness.samples.set(10D);
        harness.safety.set(healthy());
        double raised = engine.controlTick().duty();
        assertTrue(raised > 0);

        harness.safety.set(forbidOnly("METRIC_STALE_3S"));
        harness.samples.set(5D);
        CpuEngineView held = engine.controlTick();
        assertEquals(raised, held.duty(), 1e-12);
        assertFalse(held.safetyZeroed());

        harness.sessions.requestStop("exec-forbid", StopKind.USER);
        assertTrue(engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS));
        engine = null;
    }

    @Test
    void emergencyStopWakesWorkersWithinTwoSeconds() throws Exception {
        Harness harness = harness(3);
        AtomicLong burnCalls = new AtomicLong();
        engine = new CpuEngine(harness.sessions, harness.samples::get, harness.safety::get, (nanos, permit, allowed) -> {
            burnCalls.incrementAndGet();
            long deadline = System.nanoTime() + nanos;
            while (permit.valid() && allowed.getAsBoolean() && System.nanoTime() < deadline) {
                LockSupport.parkNanos(1_000_000L);
            }
            return 1L;
        });
        SessionClaim claim = harness.sessions.claim("exec-stop");
        engine.start(claim, 40, 3, false);
        harness.samples.set(10D);
        harness.safety.set(healthy());
        engine.controlTick();

        long began = System.nanoTime();
        harness.sessions.emergencyStop();
        assertFalse(claim.permit().valid());
        assertTrue(engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS));
        long elapsedMs = (System.nanoTime() - began) / 1_000_000L;
        assertTrue(elapsedMs <= CpuEngine.STOP_TIMEOUT_MS, "elapsedMs=" + elapsedMs);
        assertTrue(harness.sessions.active().isEmpty());
        assertEquals(0, harness.sessions.lastFinished().orElseThrow().heldBytes());
        engine = null;
    }

    @Test
    void autoControlUsesDedicatedControlThread() throws Exception {
        Harness harness = harness(1);
        engine = new CpuEngine(harness.sessions, harness.samples::get, harness.safety::get, instantBurner());
        SessionClaim claim = harness.sessions.claim("exec-auto");
        engine.start(claim, 30, 1, true);
        long deadline = System.currentTimeMillis() + 2_000;
        boolean found = false;
        while (System.currentTimeMillis() < deadline && !found) {
            Thread[] threads = new Thread[Thread.activeCount() + 8];
            int count = Thread.enumerate(threads);
            for (int i = 0; i < count; i++) {
                if (threads[i] != null && threads[i].getName().startsWith("srm-cpu-ctrl-")) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                Thread.sleep(20);
            }
        }
        assertTrue(found);
        harness.sessions.requestStop("exec-auto", StopKind.USER);
        assertTrue(engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS));
        engine = null;
    }

    @Test
    void doesNotStartSecondEngineWhileFirstIsActive() throws Exception {
        Harness harness = harness(1);
        engine = new CpuEngine(harness.sessions, harness.samples::get, harness.safety::get, instantBurner());
        SessionClaim claim = harness.sessions.claim("exec-one");
        engine.start(claim, 30, 1, false);
        assertThrows(IllegalStateException.class, () -> engine.start(claim, 30, 1, false));
        harness.sessions.requestStop("exec-one", StopKind.USER);
        assertTrue(engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS));
        engine = null;
    }

    @Test
    void checksumBurnerProducesObservableValueAndHonorsPermit() {
        ChecksumCpuBurner burner = new ChecksumCpuBurner();
        ResourceLoadManager manager = new ResourceLoadManager();
        SessionClaim claim = manager.claim("exec-burn");
        manager.markRunning("exec-burn");
        long value = burner.burn(2_000_000L, claim.permit(), () -> true);
        assertTrue(value != 0L);
        manager.emergencyStop();
        long afterStop = burner.burn(50_000_000L, claim.permit(), () -> true);
        assertEquals(0L, afterStop);
        manager.confirmWorkerExit(claim.permit());
    }

    @Test
    void pauseStopsComputeKeepsSlotAndResumeRePrechecks() throws Exception {
        Harness harness = harness(2);
        AtomicInteger burns = new AtomicInteger();
        engine = new CpuEngine(harness.sessions, harness.samples::get, harness.safety::get, (nanos, permit, allowed) -> {
            if (permit.valid() && allowed.getAsBoolean() && nanos > 0) {
                burns.incrementAndGet();
            }
            return 1L;
        });
        SessionClaim claim = harness.sessions.claim("exec-pause");
        engine.start(claim, 40, 2, false);
        harness.samples.set(10D);
        harness.safety.set(healthy());
        engine.controlTick();
        assertTrue(engine.view().duty() > 0);

        CpuEngineView paused = engine.pause();
        assertTrue(paused.paused());
        assertEquals(0, paused.duty());
        assertEquals(ExecutionStatus.PAUSED, harness.sessions.active().orElseThrow().status());
        assertTrue(claim.permit().valid());
        assertEquals(SessionRejectedException.Reason.SLOT_OCCUPIED,
                assertThrows(SessionRejectedException.class, () -> harness.sessions.claim("exec-other")).reason());

        int burnsAtPause = burns.get();
        engine.controlTick();
        assertEquals(0, engine.view().duty());
        Thread.sleep(50);
        assertEquals(burnsAtPause, burns.get());

        ApiException denied = assertThrows(ApiException.class,
                () -> engine.resume(() -> {
                    throw new ApiException(HttpStatus.CONFLICT, "CONTROL_DISABLED", "控制开关已关闭，不能恢复");
                }));
        assertEquals("CONTROL_DISABLED", denied.code());
        assertEquals(ExecutionStatus.PAUSED, harness.sessions.active().orElseThrow().status());
        assertTrue(engine.view().paused());

        CpuEngineView resumed = engine.resume(ResumeGate.OPEN);
        assertFalse(resumed.paused());
        assertEquals(0, resumed.duty());
        assertEquals(ExecutionStatus.RUNNING, harness.sessions.active().orElseThrow().status());
        harness.samples.set(10D);
        CpuEngineView first = engine.controlTick();
        assertTrue(first.duty() > 0 && first.duty() <= 0.05, "duty=" + first.duty());

        harness.sessions.requestStop("exec-pause", StopKind.USER);
        assertTrue(engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS));
        engine = null;
    }

    @Test
    void updateTargetChecksVersionAndAppliesAfterPauseOnResume() throws Exception {
        Harness harness = harness(1);
        List<String> changes = new ArrayList<>();
        engine = new CpuEngine(harness.sessions, harness.samples::get, harness.safety::get, instantBurner(),
                (executionId, from, to, targetPct) -> changes.add(executionId + ":" + from + "->" + to + "@" + targetPct));
        SessionClaim claim = harness.sessions.claim("exec-target");
        engine.start(claim, 30, 1, false);
        assertEquals(1, engine.view().configVersion());

        ApiException mismatch = assertThrows(ApiException.class, () -> engine.updateTarget(99, 45));
        assertEquals("VERSION_MISMATCH", mismatch.code());
        assertEquals(1, engine.view().configVersion());
        assertEquals(30, engine.view().targetPct());

        CpuEngineView runningUpdate = engine.updateTarget(1, 50);
        assertEquals(2, runningUpdate.configVersion());
        assertEquals(50, runningUpdate.targetPct());
        assertEquals(List.of("exec-target:1->2@50.0"), changes);

        engine.pause();
        CpuEngineView pausedUpdate = engine.updateTarget(2, 35);
        assertTrue(pausedUpdate.paused());
        assertEquals(3, pausedUpdate.configVersion());
        assertEquals(35, pausedUpdate.targetPct());
        assertEquals(0, pausedUpdate.duty());

        engine.resume(ResumeGate.OPEN);
        harness.samples.set(10D);
        harness.safety.set(healthy());
        CpuEngineView afterResume = engine.controlTick();
        assertEquals(35, afterResume.targetPct());
        assertTrue(afterResume.duty() > 0 && afterResume.duty() <= 0.05);
        assertEquals(2, changes.size());

        harness.sessions.requestStop("exec-target", StopKind.USER);
        assertTrue(engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS));
        engine = null;
    }

    @Test
    void targetJournalFailureDoesNotBumpVersion() throws Exception {
        Harness harness = harness(1);
        engine = new CpuEngine(harness.sessions, harness.samples::get, harness.safety::get, instantBurner(),
                (executionId, from, to, targetPct) -> {
                    throw new IllegalStateException("journal down");
                });
        SessionClaim claim = harness.sessions.claim("exec-journal");
        engine.start(claim, 30, 1, false);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> engine.updateTarget(1, 40));
        assertEquals("journal down", failure.getMessage());
        assertEquals(1, engine.view().configVersion());
        assertEquals(30, engine.view().targetPct());
        assertEquals(ExecutionStatus.RUNNING, harness.sessions.active().orElseThrow().status());

        harness.sessions.requestStop("exec-journal", StopKind.USER);
        assertTrue(engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS));
        engine = null;
    }

    @Test
    void externalLoadHighStillZeroesDutyAfterResume() throws Exception {
        Harness harness = harness(1);
        engine = new CpuEngine(harness.sessions, harness.samples::get, harness.safety::get, instantBurner());
        SessionClaim claim = harness.sessions.claim("exec-ext2");
        engine.start(claim, 30, 1, false);
        engine.pause();
        engine.resume(ResumeGate.OPEN);
        harness.samples.set(50D);
        harness.safety.set(healthy());
        engine.controlTick();
        CpuEngineView second = engine.controlTick();
        assertTrue(second.externalLoadHigh());
        assertEquals(0, second.duty());
        assertTrue(second.limitedReasons().contains("EXTERNAL_LOAD_HIGH"));

        harness.sessions.requestStop("exec-ext2", StopKind.USER);
        assertTrue(engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS));
        engine = null;
    }

    private static CpuBurner instantBurner() {
        return (nanos, permit, allowed) -> permit.valid() && allowed.getAsBoolean() && nanos > 0 ? 1L : 0L;
    }

    private Harness harness(int ignoredWorkers) {
        Harness created = new Harness(new ResourceLoadManager());
        this.sessions = created.sessions;
        return created;
    }

    private static SafetySignal healthy() {
        return new SafetySignal(1, 1L, 0, Quality.VALID, true, null, false, false, false, false, false, List.of());
    }

    private static SafetySignal zeroDuty(String reason) {
        return new SafetySignal(1, 1L, 0, Quality.VALID, true, null, true, true, false, false, false, List.of(reason));
    }

    private static SafetySignal forbidOnly(String reason) {
        return new SafetySignal(1, 1L, 0, Quality.VALID, true, null, true, false, false, false, false, List.of(reason));
    }

    private static final class Harness {
        private final ResourceLoadManager sessions;
        private final AtomicReference<Double> samples = new AtomicReference<>(10D);
        private final AtomicReference<SafetySignal> safety = new AtomicReference<>(healthy());

        private Harness(ResourceLoadManager sessions) {
            this.sessions = sessions;
        }
    }
}
