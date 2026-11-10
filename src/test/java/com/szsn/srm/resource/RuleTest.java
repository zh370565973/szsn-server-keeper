package com.szsn.srm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.szsn.srm.common.Units;
import com.szsn.srm.history.HistoryService;
import com.szsn.srm.monitor.MeminfoParser;
import com.szsn.srm.monitor.ProcStatMath;
import com.szsn.srm.monitor.Quality;
import com.szsn.srm.monitor.SnapshotQuality;
import com.szsn.srm.safety.ProtectionEvaluator;
import com.szsn.srm.safety.ProtectionSample;
import org.junit.jupiter.api.Test;

class RuleTest {
    @Test
    void memoryBudgetUsesTheTightestAlignedRoom() {
        MemoryBudget.Result result = MemoryBudget.compute(new MemoryBudget.Input(
                8L * Units.GIB,
                4L * Units.GIB,
                Units.GIB,
                200L * Units.MIB,
                0,
                512L * Units.MIB));
        assertEquals(528482304L, result.additionalBytes());
        assertTrue(result.limitingFactors().contains("HEAP_AVAILABLE"));
        assertEquals(0, result.additionalBytes() % Units.BLOCK);
    }

    @Test
    void memoryBudgetRejectsNegativeCapacityAndUnknownHeap() {
        assertThrows(IllegalArgumentException.class, () -> MemoryBudget.compute(new MemoryBudget.Input(
                Units.GIB, -1, Units.GIB, 0, 0, Units.MIB)));
        MemoryBudget.Result unknownHeap = MemoryBudget.compute(new MemoryBudget.Input(
                8L * Units.GIB, 4L * Units.GIB, 0, 0, 0, Units.DEFAULT_TASK_CAP));
        assertEquals(0, unknownHeap.additionalBytes());
        assertTrue(unknownHeap.limitingFactors().contains("HEAP_MAX_UNAVAILABLE"));
    }

    @Test
    void memoryBudgetIsZeroWhenSystemReserveIsAlreadyConsumed() {
        MemoryBudget.Result result = MemoryBudget.compute(new MemoryBudget.Input(
                8L * Units.GIB, Units.GIB, Units.GIB, 200L * Units.MIB, 0, Units.DEFAULT_TASK_CAP));
        assertEquals(0, result.additionalBytes());
    }

    @Test
    void cpuDutyRampsSlowlyAndSafetyZeroesImmediately() {
        CpuDutyCalculator calculator = new CpuDutyCalculator();
        CpuDutyCalculator.DutyStep first = calculator.step(10, 40, false);
        assertTrue(first.duty() <= 0.05);
        calculator.step(10, 40, false);
        double beforeSafety = calculator.duty();
        assertTrue(beforeSafety > 0);
        CpuDutyCalculator.DutyStep safety = calculator.step(10, 40, true);
        assertEquals(0, safety.duty());
        assertTrue(safety.safetyZeroed());
    }

    @Test
    void externalLoadZeroesDutyUntilCpuFallsBelowTarget() {
        CpuDutyCalculator calculator = new CpuDutyCalculator();
        calculator.step(50, 30, false);
        CpuDutyCalculator.DutyStep second = calculator.step(50, 30, false);
        assertTrue(second.externalLoadHigh());
        assertEquals(0, second.duty());
        CpuDutyCalculator.DutyStep stillHigh = calculator.step(34, 30, false);
        assertEquals(0, stillHigh.duty());
        CpuDutyCalculator.DutyStep recovered = calculator.step(20, 30, false);
        assertFalse(recovered.externalLoadHigh());
        assertTrue(recovered.duty() <= 0.05);
    }

    @Test
    void resetDutyStartsFromZeroWithoutClearingEwma() {
        CpuDutyCalculator calculator = new CpuDutyCalculator();
        calculator.step(10, 40, false);
        calculator.step(10, 40, false);
        assertTrue(calculator.duty() > 0);
        double ewma = calculator.ewma();
        calculator.resetDuty();
        assertEquals(0, calculator.duty());
        assertEquals(ewma, calculator.ewma(), 1e-12);
        CpuDutyCalculator.DutyStep next = calculator.step(10, 40, false);
        assertTrue(next.duty() > 0 && next.duty() <= 0.05);
    }

    @Test
    void dutyDecreaseIsCappedAndDeadbandHolds() {
        CpuDutyCalculator calculator = new CpuDutyCalculator();
        for (int i = 0; i < 30; i++) {
            calculator.step(0, 100, false);
        }
        double high = calculator.duty();
        CpuDutyCalculator.DutyStep down = calculator.step(0, 0, false);
        assertTrue(high - down.duty() <= 0.1500001);
        CpuDutyCalculator hold = new CpuDutyCalculator();
        hold.step(30, 30, false);
        CpuDutyCalculator.DutyStep kept = hold.step(31, 30, false);
        assertTrue(kept.hold());
    }

    @Test
    void protectionWindowsLatchAndRelease() {
        ProtectionEvaluator evaluator = new ProtectionEvaluator();
        assertFalse(evaluator.onSample(sample(90, 0)).zeroDuty());
        assertFalse(evaluator.onSample(sample(90, 0)).zeroDuty());
        assertTrue(evaluator.onSample(sample(90, 0)).zeroDuty());
        for (int i = 0; i < 9; i++) {
            assertTrue(evaluator.onSample(sample(70, 0)).zeroDuty());
        }
        assertFalse(evaluator.onSample(sample(70, 0)).zeroDuty());

        ProtectionEvaluator severe = new ProtectionEvaluator();
        severe.onSample(sample(96, 0));
        severe.onSample(sample(96, 0));
        var latched = severe.onSample(sample(96, 0));
        assertTrue(latched.safetyStop());
        assertTrue(latched.latch());
    }

    @Test
    void staleSamplesForbidGrowthThenStop() {
        ProtectionEvaluator evaluator = new ProtectionEvaluator();
        var stale = evaluator.onSample(sample(10, 4_000));
        assertTrue(stale.forbidCpuGrowth());
        assertTrue(stale.forbidMemoryGrowth());
        assertFalse(stale.safetyStop());
        var dead = evaluator.onSample(sample(10, 11_000));
        assertTrue(dead.safetyStop());
        assertTrue(dead.latch());
    }

    @Test
    void memoryAndSwapThresholds() {
        ProtectionEvaluator low = new ProtectionEvaluator();
        long total = 8L * Units.GIB;
        long reserve = MemoryBudget.systemReserve(total);
        var guard = low.onSample(memory(total, reserve + Units.GUARD - 1));
        assertTrue(guard.forbidMemoryGrowth());
        assertFalse(guard.safetyStop());
        low.onSample(memory(total, reserve - 1));
        var stop = low.onSample(memory(total, reserve - 1));
        assertTrue(stop.safetyStop());

        ProtectionEvaluator swap = new ProtectionEvaluator();
        ProtectionSample hot = new ProtectionSample(10D, 0, 0D, 0D, total, total, 0, 10L, 100L, true, 16D * Units.MIB);
        for (int i = 0; i < 4; i++) {
            assertFalse(swap.onSample(hot).safetyStop());
        }
        assertTrue(swap.onSample(hot).latch());
    }

    @Test
    void procStatIgnoresGuestAndResets() {
        String first = "cpu  100 0 50 800 50 0 0 0 40 0\ncpu0 1 0 0 1 0 0 0 0 0 0\n";
        String second = "cpu  200 0 100 1500 100 0 0 0 90 0\n";
        ProcStatMath.Percents percents = ProcStatMath.between(
                ProcStatMath.parseAggregate(first), ProcStatMath.parseAggregate(second));
        assertEquals(16.666666666666668, percents.overallBusyPct(), 1e-9);
        assertEquals(null, ProcStatMath.between(
                ProcStatMath.parseAggregate("cpu  10 0 0 10 0 0 0 0 0 0"),
                ProcStatMath.parseAggregate("cpu  1 0 0 1 0 0 0 0 0 0")));
    }

    @Test
    void meminfoKeepsMissingCacheAsNull() {
        MeminfoParser.MemoryReading reading = MeminfoParser.parse("""
                MemTotal:       2048 kB
                MemAvailable:   1024 kB
                SwapTotal:      0 kB
                SwapFree:       0 kB
                """);
        assertEquals(2048L * 1024, reading.totalBytes());
        assertEquals(1024L * 1024, reading.availableBytes());
        assertEquals(null, reading.cacheBytes());
        assertEquals(0L, reading.swapUsedBytes());
    }

    @Test
    void snapshotQualityDoesNotTreatFreshPartialAsStale() {
        assertEquals(Quality.PARTIAL, SnapshotQuality.assess(true, true, 0));
        assertEquals(Quality.STALE, SnapshotQuality.assess(true, false, 3_001));
        assertEquals(Quality.UNAVAILABLE, SnapshotQuality.assess(false, false, 0));
        assertEquals(Quality.UNAVAILABLE, SnapshotQuality.assess(true, false, 10_001));
    }

    @Test
    void executionStopIsIdempotentAndResumeRestarts() {
        assertEquals(ExecutionStatus.RUNNING, ExecutionTransitions.startSucceeded(ExecutionStatus.STARTING));
        assertEquals(ExecutionStatus.PAUSED, ExecutionTransitions.pauseCompleted(
                ExecutionTransitions.beginPause(ExecutionStatus.RUNNING)));
        assertEquals(ExecutionStatus.STARTING, ExecutionTransitions.resumeAccepted(ExecutionStatus.PAUSED));
        assertEquals(ExecutionStatus.PAUSED, ExecutionTransitions.resumeRejected(ExecutionStatus.PAUSED));
        ExecutionStatus stopping = ExecutionTransitions.requestStop(ExecutionStatus.RUNNING);
        assertEquals(ExecutionStatus.STOPPED, ExecutionTransitions.finishStop(stopping, StopKind.USER));
        assertEquals(ExecutionStatus.STOPPED, ExecutionTransitions.requestStop(ExecutionStatus.STOPPED));
        assertEquals(ExecutionStatus.COMPLETED, ExecutionTransitions.finishStop(
                ExecutionTransitions.requestStop(ExecutionStatus.PAUSED), StopKind.DURATION));
        assertThrows(IllegalStateException.class, () -> ExecutionTransitions.startSucceeded(ExecutionStatus.RUNNING));
    }

    @Test
    void historyBucketCountStaysWithinLimit() {
        assertEquals(10_000, HistoryService.chooseBucket(1_000 * 10_000, null));
        assertEquals(60_000, HistoryService.chooseBucket(3_000 * 10_000L, null));
        long huge = 5_000L * 3_600_000;
        long bucket = HistoryService.chooseBucket(huge, 10L);
        assertTrue((huge + bucket - 1) / bucket <= 2000);
    }

    private static ProtectionSample sample(double cpu, long age) {
        return new ProtectionSample(cpu, age, 0D, 0D, 8L * Units.GIB, 8L * Units.GIB, age, 100L, 1_000L, false, null);
    }

    private static ProtectionSample memory(long total, long available) {
        return new ProtectionSample(10D, 0, 0D, 0D, available, total, 0, 100L, 1_000L, false, null);
    }
}
