package com.szsn.srm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.szsn.srm.common.Units;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 4MiB 分块、逐块检查停止标志/预算/样本时效，以及 32MiB/s 限速。
 */
class ChunkedMemoryGrowerTest {
    @Test
    void growsInFourMibChunksAtMostThirtyTwoMibPerSecond() {
        FakeMemoryAllocator allocator = new FakeMemoryAllocator();
        AtomicReference<MemorySample> sample = new AtomicReference<>(freshAmple());
        MemoryBudgetGate gate = new MemoryBudgetGate(allocator, sample::get, Units.DEFAULT_TASK_CAP);
        AtomicLong now = new AtomicLong();
        List<Long> sleeps = new ArrayList<>();
        ChunkedMemoryGrower grower = new ChunkedMemoryGrower(
                allocator,
                gate,
                sample::get,
                () -> false,
                now::get,
                nanos -> {
                    sleeps.add(nanos);
                    now.addAndGet(nanos);
                },
                held -> {
                });

        ResourceLoadManager sessions = new ResourceLoadManager();
        SessionClaim claim = sessions.claim("mem-rate");
        long target = 16L * Units.MIB; // 4 chunks
        ChunkedMemoryGrower.GrowResult result = grower.growToward(target, claim.permit());

        assertEquals(ChunkedMemoryGrower.StopReason.COMPLETED, result.reason());
        assertEquals(target, result.heldBytes());
        assertEquals(target, result.grownBytes());
        assertEquals(3, sleeps.size());
        for (long sleep : sleeps) {
            assertEquals(ChunkedMemoryGrower.CHUNK_INTERVAL_NANOS, sleep);
        }
        assertEquals(3L * ChunkedMemoryGrower.CHUNK_INTERVAL_NANOS, now.get());
    }

    @Test
    void rechecksStopFlagBeforeEachChunk() {
        FakeMemoryAllocator allocator = new FakeMemoryAllocator();
        AtomicReference<MemorySample> sample = new AtomicReference<>(freshAmple());
        MemoryBudgetGate gate = new MemoryBudgetGate(allocator, sample::get, Units.DEFAULT_TASK_CAP);
        AtomicLong now = new AtomicLong();
        ResourceLoadManager sessions = new ResourceLoadManager();
        SessionClaim claim = sessions.claim("mem-stop");
        AtomicBoolean stopAfterFirst = new AtomicBoolean();
        ChunkedMemoryGrower grower = new ChunkedMemoryGrower(
                allocator,
                gate,
                sample::get,
                () -> false,
                now::get,
                nanos -> {
                    now.addAndGet(nanos);
                    if (stopAfterFirst.get()) {
                        sessions.emergencyStop();
                    }
                },
                held -> stopAfterFirst.set(true));

        ChunkedMemoryGrower.GrowResult result = grower.growToward(32L * Units.MIB, claim.permit());
        assertEquals(ChunkedMemoryGrower.StopReason.STOPPED, result.reason());
        assertEquals("STOP_FLAG", result.detail());
        assertEquals(Units.BLOCK, result.heldBytes());
        assertEquals(Units.BLOCK, result.grownBytes());
        assertTrue(!claim.permit().valid());
    }

    @Test
    void stopsWhenLatestBudgetCannotCoverOneChunk() {
        FakeMemoryAllocator allocator = new FakeMemoryAllocator();
        AtomicReference<MemorySample> sample = new AtomicReference<>(freshAmple());
        MemoryBudgetGate gate = new MemoryBudgetGate(allocator, sample::get, Units.DEFAULT_TASK_CAP);
        AtomicLong now = new AtomicLong();
        ChunkedMemoryGrower grower = new ChunkedMemoryGrower(
                allocator,
                gate,
                sample::get,
                () -> false,
                now::get,
                nanos -> {
                    now.addAndGet(nanos);
                    // 第一块后把可用内存压到不足以再分配一块
                    sample.set(new MemorySample(
                            8L * Units.GIB,
                            MemoryBudget.systemReserve(8L * Units.GIB) + Units.GUARD + Units.MIB,
                            Units.GIB,
                            200L * Units.MIB,
                            0L));
                },
                held -> {
                });

        ResourceLoadManager sessions = new ResourceLoadManager();
        SessionClaim claim = sessions.claim("mem-budget");
        ChunkedMemoryGrower.GrowResult result = grower.growToward(64L * Units.MIB, claim.permit());
        assertEquals(ChunkedMemoryGrower.StopReason.BUDGET_EXHAUSTED, result.reason());
        assertEquals(Units.BLOCK, result.heldBytes());
        assertTrue(result.detail() != null && !result.detail().isBlank());
    }

    @Test
    void stopsWhenSampleBecomesStale() {
        FakeMemoryAllocator allocator = new FakeMemoryAllocator();
        AtomicReference<MemorySample> sample = new AtomicReference<>(freshAmple());
        MemoryBudgetGate gate = new MemoryBudgetGate(allocator, sample::get, Units.DEFAULT_TASK_CAP);
        AtomicLong now = new AtomicLong();
        ChunkedMemoryGrower grower = new ChunkedMemoryGrower(
                allocator,
                gate,
                sample::get,
                () -> false,
                now::get,
                nanos -> {
                    now.addAndGet(nanos);
                    sample.set(new MemorySample(
                            8L * Units.GIB,
                            4L * Units.GIB,
                            Units.GIB,
                            200L * Units.MIB,
                            SnapshotAge.STALE));
                },
                held -> {
                });

        ResourceLoadManager sessions = new ResourceLoadManager();
        SessionClaim claim = sessions.claim("mem-stale");
        ChunkedMemoryGrower.GrowResult result = grower.growToward(16L * Units.MIB, claim.permit());
        assertEquals(ChunkedMemoryGrower.StopReason.METRIC_STALE, result.reason());
        assertEquals("METRIC_STALE_3S", result.detail());
        assertEquals(Units.BLOCK, result.heldBytes());
    }

    @Test
    void missingAgeIsTreatedAsStale() {
        FakeMemoryAllocator allocator = new FakeMemoryAllocator();
        MemorySampleSource samples = () -> new MemorySample(
                8L * Units.GIB, 4L * Units.GIB, Units.GIB, 200L * Units.MIB, null);
        MemoryBudgetGate gate = new MemoryBudgetGate(allocator, samples, Units.DEFAULT_TASK_CAP);
        ChunkedMemoryGrower grower = new ChunkedMemoryGrower(allocator, gate, samples);
        ResourceLoadManager sessions = new ResourceLoadManager();
        SessionClaim claim = sessions.claim("mem-no-age");
        ChunkedMemoryGrower.GrowResult result = grower.growToward(Units.BLOCK, claim.permit());
        assertEquals(ChunkedMemoryGrower.StopReason.METRIC_STALE, result.reason());
        assertEquals(0, allocator.heldBytes());
    }

    @Test
    void forbidGrowthStopsWithoutAllocatingFurther() {
        FakeMemoryAllocator allocator = new FakeMemoryAllocator();
        AtomicReference<MemorySample> sample = new AtomicReference<>(freshAmple());
        MemoryBudgetGate gate = new MemoryBudgetGate(allocator, sample::get, Units.DEFAULT_TASK_CAP);
        AtomicBoolean forbid = new AtomicBoolean(true);
        ChunkedMemoryGrower grower = new ChunkedMemoryGrower(
                allocator,
                gate,
                sample::get,
                forbid::get,
                () -> 0L,
                nanos -> {
                },
                held -> {
                });
        ResourceLoadManager sessions = new ResourceLoadManager();
        SessionClaim claim = sessions.claim("mem-forbid");
        ChunkedMemoryGrower.GrowResult result = grower.growToward(8L * Units.MIB, claim.permit());
        assertEquals(ChunkedMemoryGrower.StopReason.FORBIDDEN, result.reason());
        assertEquals(0, result.heldBytes());
    }

    @Test
    void reduceTargetReleasesImmediatelyWithoutRateLimit() {
        FakeMemoryAllocator allocator = new FakeMemoryAllocator();
        AtomicReference<MemorySample> sample = new AtomicReference<>(freshAmple());
        MemoryBudgetGate gate = new MemoryBudgetGate(allocator, sample::get, Units.DEFAULT_TASK_CAP);
        List<Long> sleeps = new ArrayList<>();
        ChunkedMemoryGrower grower = new ChunkedMemoryGrower(
                allocator,
                gate,
                sample::get,
                () -> false,
                () -> 0L,
                nanos -> sleeps.add(nanos),
                held -> {
                });
        gate.growTo(24L * Units.MIB);
        ResourceLoadManager sessions = new ResourceLoadManager();
        SessionClaim claim = sessions.claim("mem-shrink");
        ChunkedMemoryGrower.GrowResult result = grower.growToward(8L * Units.MIB, claim.permit());
        assertEquals(ChunkedMemoryGrower.StopReason.COMPLETED, result.reason());
        assertEquals(8L * Units.MIB, result.heldBytes());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void rejectsUnalignedTarget() {
        FakeMemoryAllocator allocator = new FakeMemoryAllocator();
        MemoryBudgetGate gate = MemoryBudgetGate.withDefaults(allocator, ChunkedMemoryGrowerTest::freshAmple);
        ChunkedMemoryGrower grower = new ChunkedMemoryGrower(allocator, gate, ChunkedMemoryGrowerTest::freshAmple);
        ResourceLoadManager sessions = new ResourceLoadManager();
        SessionClaim claim = sessions.claim("mem-align");
        assertThrows(IllegalArgumentException.class, () -> grower.growToward(Units.MIB, claim.permit()));
        assertEquals(0, allocator.heldBytes());
    }

    private static MemorySample freshAmple() {
        return new MemorySample(
                8L * Units.GIB,
                4L * Units.GIB,
                Units.GIB,
                200L * Units.MIB,
                0L);
    }

    private static final class SnapshotAge {
        static final long STALE = ChunkedMemoryGrower.STALE_FORBID_MS + 1;
    }
}
