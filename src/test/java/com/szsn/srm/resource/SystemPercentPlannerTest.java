package com.szsn.srm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.szsn.srm.common.Units;
import org.junit.jupiter.api.Test;

class SystemPercentPlannerTest {
    private static final long TOTAL = 8L * Units.GIB;

    @Test
    void holdsInsideDeadZone() {
        // used = 50% of 8GiB; target 50% → |diff|=0
        long available = TOTAL / 2;
        SystemPercentPlanner.Decision decision = SystemPercentPlanner.decide(
                50, TOTAL, available, 0, 64L * Units.MIB, false);
        assertEquals(SystemPercentPlanner.Action.HOLD, decision.action());
        assertTrue(decision.reasons().contains(SystemPercentPlanner.DEAD_ZONE));
        assertFalse(decision.systemAboveTarget());
    }

    @Test
    void doesNotAllocateWhenSystemAlreadyAboveTarget() {
        // used ≈ 60%; target 40% → above, no grow
        long available = TOTAL * 40 / 100;
        SystemPercentPlanner.Decision decision = SystemPercentPlanner.decide(
                40, TOTAL, available, 32L * Units.MIB, 64L * Units.MIB, false);
        assertTrue(decision.systemAboveTarget());
        assertTrue(decision.reasons().contains(SystemPercentPlanner.SYSTEM_ABOVE_TARGET));
        assertTrue(decision.action() == SystemPercentPlanner.Action.RELEASE
                || decision.action() == SystemPercentPlanner.Action.HOLD);
        assertEquals(0L, decision.action() == SystemPercentPlanner.Action.GROW ? 1L : 0L);
    }

    @Test
    void releasesHeldWhenAboveTarget() {
        long available = TOTAL * 30 / 100; // used 70%
        long held = 64L * Units.MIB;
        SystemPercentPlanner.Decision decision = SystemPercentPlanner.decide(
                40, TOTAL, available, held, 128L * Units.MIB, false);
        assertEquals(SystemPercentPlanner.Action.RELEASE, decision.action());
        assertTrue(decision.amountBytes() > 0);
        assertTrue(decision.amountBytes() <= held);
        assertTrue(decision.systemAboveTarget());
    }

    @Test
    void marksTargetLimitedWhenBudgetCapsGrow() {
        // used 10%; target 50% → large deficit; budget only 8MiB
        long available = TOTAL * 90 / 100;
        long budget = 8L * Units.MIB;
        SystemPercentPlanner.Decision decision = SystemPercentPlanner.decide(
                50, TOTAL, available, 0, budget, false);
        assertEquals(SystemPercentPlanner.Action.GROW, decision.action());
        assertEquals(budget, decision.amountBytes());
        assertTrue(decision.targetLimited());
        assertTrue(decision.reasons().contains(SystemPercentPlanner.TARGET_LIMITED));
        assertTrue(decision.idealGrowBytes() > budget);
    }

    @Test
    void holdsGrowDuringRegrowCooldown() {
        long available = TOTAL * 90 / 100;
        SystemPercentPlanner.Decision decision = SystemPercentPlanner.decide(
                50, TOTAL, available, 0, 64L * Units.MIB, true);
        assertEquals(SystemPercentPlanner.Action.HOLD, decision.action());
        assertTrue(decision.reasons().contains(SystemPercentPlanner.REGROW_COOLDOWN));
        assertEquals(0L, decision.amountBytes());
    }

    @Test
    void holdsWhenBudgetTooSmallForOneBlock() {
        long available = TOTAL * 90 / 100;
        SystemPercentPlanner.Decision decision = SystemPercentPlanner.decide(
                50, TOTAL, available, 0, Units.MIB, false);
        assertEquals(SystemPercentPlanner.Action.HOLD, decision.action());
        assertTrue(decision.targetLimited());
        assertTrue(decision.reasons().contains(SystemPercentPlanner.TARGET_LIMITED));
    }
}
