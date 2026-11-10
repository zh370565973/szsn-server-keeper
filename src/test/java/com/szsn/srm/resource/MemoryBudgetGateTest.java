package com.szsn.srm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.Units;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 假分配器接入三重预算：超预算 422 且无分配；预算内只改计数。
 */
class MemoryBudgetGateTest {
    @Test
    void fixedCapacityOverBudgetReturns422WithoutGrowing() {
        FakeMemoryAllocator allocator = new FakeMemoryAllocator();
        AtomicReference<MemorySample> sample = new AtomicReference<>(ampleSample());
        MemoryBudgetGate gate = new MemoryBudgetGate(allocator, sample::get, Units.DEFAULT_TASK_CAP);

        MemoryBudget.Result budget = gate.currentBudget();
        assertTrue(budget.additionalBytes() > 0);
        long over = budget.additionalBytes() + Units.BLOCK;

        ApiException denied = assertThrows(ApiException.class, () -> gate.requireFixedCapacity(over));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, denied.status());
        assertEquals(MemoryBudgetGate.BUDGET_EXCEEDED, denied.code());
        assertEquals(over, denied.details().get("requestedBytes"));
        assertEquals(budget.additionalBytes(), ((Number) denied.details().get("allowedAdditionalBytes")).longValue());
        assertTrue(denied.details().containsKey("limitingFactors"));
        assertEquals(0, allocator.heldBytes());

        assertThrows(ApiException.class, () -> gate.growTo(over));
        assertEquals(0, allocator.heldBytes());
    }

    @Test
    void withinBudgetGrowsFakeHeldBytesOnly() {
        FakeMemoryAllocator allocator = new FakeMemoryAllocator();
        MemoryBudgetGate gate = MemoryBudgetGate.withDefaults(allocator, MemoryBudgetGateTest::ampleSample);

        long beforeAdditional = gate.currentBudget().additionalBytes();
        long target = 16L * Units.MIB;
        assertEquals(target, gate.growTo(target));
        assertEquals(target, allocator.heldBytes());
        assertEquals(target, gate.heldBytes());
        assertTrue(gate.currentBudget().additionalBytes() < beforeAdditional);
    }

    @Test
    void increaseMustFitRemainingBudget() {
        FakeMemoryAllocator allocator = new FakeMemoryAllocator();
        MemoryBudgetGate gate = MemoryBudgetGate.withDefaults(allocator, MemoryBudgetGateTest::ampleSample);
        long first = 32L * Units.MIB;
        gate.growTo(first);

        MemoryBudget.Result budget = gate.currentBudget();
        long overTotal = first + budget.additionalBytes() + Units.BLOCK;
        ApiException denied = assertThrows(ApiException.class, () -> gate.growTo(overTotal));
        assertEquals(MemoryBudgetGate.BUDGET_EXCEEDED, denied.code());
        assertEquals(first, allocator.heldBytes());
    }

    @Test
    void reduceTargetOnlyClearsFakeHeld() {
        FakeMemoryAllocator allocator = new FakeMemoryAllocator();
        MemoryBudgetGate gate = MemoryBudgetGate.withDefaults(allocator, MemoryBudgetGateTest::ampleSample);
        gate.growTo(24L * Units.MIB);
        assertEquals(8L * Units.MIB, gate.growTo(8L * Units.MIB));
        assertEquals(8L * Units.MIB, allocator.heldBytes());
        allocator.clear();
        assertEquals(0, gate.heldBytes());
    }

    @Test
    void missingCriticalSampleYieldsZeroBudget() {
        FakeMemoryAllocator allocator = new FakeMemoryAllocator();
        MemoryBudgetGate gate = MemoryBudgetGate.withDefaults(allocator, () -> null);
        assertEquals(0, gate.currentBudget().additionalBytes());
        ApiException denied = assertThrows(ApiException.class, () -> gate.requireFixedCapacity(Units.MIB));
        assertEquals(MemoryBudgetGate.BUDGET_EXCEEDED, denied.code());
        assertEquals(0, allocator.heldBytes());
    }

    private static MemorySample ampleSample() {
        return new MemorySample(
                8L * Units.GIB,
                4L * Units.GIB,
                Units.GIB,
                200L * Units.MIB);
    }
}
