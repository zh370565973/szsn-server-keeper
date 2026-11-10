package com.szsn.srm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.Units;
import com.szsn.srm.monitor.Quality;
import com.szsn.srm.safety.SafetySignal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MemoryEngineTest {
    private MemoryEngine engine;
    private ResourceLoadManager sessions;
    private FakeMemoryAllocator allocator;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.detach();
        }
        if (sessions != null) {
            sessions.emergencyStop();
        }
    }

    @Test
    void extraBytesGrowsTowardFixedTarget() {
        Harness harness = harness(ample(8L * Units.GIB, 6L * Units.GIB));
        SessionClaim claim = sessions.claim("mem-extra");
        long target = 16L * Units.MIB;
        MemoryEngineView started = engine.startExtraBytes(claim, target);
        assertEquals(MemoryEngine.MODE_EXTRA_BYTES, started.memoryMode());
        assertEquals(0L, started.heldBytes());

        MemoryEngineView after = engine.controlTick();
        assertEquals(target, after.heldBytes());
        assertEquals(target, allocator.heldBytes());
        assertFalse(after.targetLimited());
    }

    @Test
    void extraBytesRejectsOverBudgetWithoutAllocation() {
        Harness harness = harness(ample(8L * Units.GIB, 6L * Units.GIB));
        SessionClaim claim = sessions.claim("mem-reject");
        long before = allocator.heldBytes();
        ApiException denied = assertThrows(ApiException.class,
                () -> engine.startExtraBytes(claim, Units.DEFAULT_TASK_CAP + Units.BLOCK));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, denied.status());
        assertEquals(MemoryBudgetGate.BUDGET_EXCEEDED, denied.code());
        assertEquals(before, allocator.heldBytes());
    }

    @Test
    void systemPercentDoesNotAllocateWhenAlreadyAboveTarget() {
        // total 8GiB, available 2GiB → used 75%; target 40%
        Harness harness = harness(ample(8L * Units.GIB, 2L * Units.GIB));
        allocator.grow(32L * Units.MIB);
        SessionClaim claim = sessions.claim("mem-above");
        engine.startSystemPercent(claim, 40);
        long before = allocator.heldBytes();

        MemoryEngineView view = engine.controlTick();
        assertTrue(view.systemAboveTarget());
        assertTrue(view.limitedReasons().contains(SystemPercentPlanner.SYSTEM_ABOVE_TARGET));
        assertTrue(allocator.heldBytes() <= before);
        assertFalse(view.limitedReasons().stream().anyMatch(r -> r.equals("GROW")));
    }

    @Test
    void systemPercentMarksTargetLimitedWhenBudgetCaps() {
        // 堆预算收紧：heapMax=512MiB → loadCap=min(512MiB任务上限, 256MiB)=256MiB；
        // heapRoom = 512 - used - heapReserve(256) - 64 = 512-200-256-64 ≈ 很小
        // 用较小 heap 让 additionalBytes 远小于追到 50% 所需
        long total = 8L * Units.GIB;
        long available = total * 90 / 100; // used 10%, target 50% → 需约 3.2GiB
        long heapMax = 512L * Units.MIB;
        long heapUsed = 200L * Units.MIB;
        Harness harness = harness(new MemorySample(total, available, heapMax, heapUsed, 0L));
        SessionClaim claim = sessions.claim("mem-limited");
        engine.startSystemPercent(claim, 50);

        MemoryEngineView view = engine.controlTick();
        assertTrue(view.targetLimited() || view.limitedReasons().contains(SystemPercentPlanner.TARGET_LIMITED));
        assertTrue(view.limitedReasons().contains(SystemPercentPlanner.TARGET_LIMITED));
        long held = allocator.heldBytes();
        assertTrue(held > 0 || view.heldBytes() == 0);
        // 不得按系统缺口无限增长；持有量不超过任务/堆预算量级
        assertTrue(held <= Units.DEFAULT_TASK_CAP);
        assertTrue(held < 3L * Units.GIB);
    }

    @Test
    void systemPercentGrowsWhenBelowTargetAndBudgetAllows() {
        Harness harness = harness(ample(8L * Units.GIB, 7L * Units.GIB)); // used 12.5%
        SessionClaim claim = sessions.claim("mem-grow");
        engine.startSystemPercent(claim, 30);
        MemoryEngineView view = engine.controlTick();
        assertTrue(allocator.heldBytes() >= Units.BLOCK);
        assertFalse(view.systemAboveTarget());
    }

    @Test
    void systemPercentCooldownBlocksRegrowAfterRelease() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        // 先高于目标以触发释放
        AtomicReference<MemorySample> sample = new AtomicReference<>(ample(8L * Units.GIB, 2L * Units.GIB));
        Harness harness = harness(sample, clock);
        allocator.grow(64L * Units.MIB);
        SessionClaim claim = sessions.claim("mem-cool");
        engine.startSystemPercent(claim, 40);

        MemoryEngineView released = engine.controlTick();
        assertTrue(released.systemAboveTarget());
        assertTrue(allocator.heldBytes() < 64L * Units.MIB);
        long afterRelease = allocator.heldBytes();

        // 系统降到低于目标，但仍在 10s 冷却内 → 不增
        sample.set(ample(8L * Units.GIB, 7L * Units.GIB));
        clock.addAndGet(1_000L);
        MemoryEngineView cooled = engine.controlTick();
        assertTrue(cooled.regrowCooldown());
        assertTrue(cooled.limitedReasons().contains(SystemPercentPlanner.REGROW_COOLDOWN));
        assertEquals(afterRelease, allocator.heldBytes());

        clock.addAndGet(MemoryEngine.REGROW_COOLDOWN_MS);
        MemoryEngineView grown = engine.controlTick();
        assertFalse(grown.regrowCooldown());
        assertTrue(allocator.heldBytes() > afterRelease);
    }

    private Harness harness(MemorySample sample) {
        return harness(new AtomicReference<>(sample), new AtomicLong());
    }

    private Harness harness(AtomicReference<MemorySample> sample, AtomicLong clock) {
        sessions = new ResourceLoadManager();
        allocator = new FakeMemoryAllocator();
        MemoryBudgetGate gate = new MemoryBudgetGate(allocator, sample::get, Units.DEFAULT_TASK_CAP);
        AtomicReference<SafetySignal> safety = new AtomicReference<>(healthy());
        ChunkedMemoryGrower grower = new ChunkedMemoryGrower(
                allocator,
                gate,
                sample::get,
                () -> safety.get().forbidMemoryGrowth(),
                () -> clock.get() * 1_000_000L,
                nanos -> clock.addAndGet(Math.max(1L, nanos / 1_000_000L)),
                held -> {
                });
        engine = new MemoryEngine(sessions, allocator, gate, sample::get, grower, safety::get, clock::get);
        return new Harness(sample, safety, clock);
    }

    private static MemorySample ample(long total, long available) {
        return new MemorySample(total, available, 2L * Units.GIB, 200L * Units.MIB, 0L);
    }

    private static SafetySignal healthy() {
        return new SafetySignal(
                0L, 1L, 0L, Quality.VALID, true, null,
                false, false, false, false, false, List.of());
    }

    private record Harness(
            AtomicReference<MemorySample> sample,
            AtomicReference<SafetySignal> safety,
            AtomicLong clock) {
    }
}
