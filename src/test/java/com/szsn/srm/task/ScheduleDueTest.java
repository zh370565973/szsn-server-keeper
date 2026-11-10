package com.szsn.srm.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ScheduleDueTest {
    @Test
    void intervalJumpCountsMissesWithoutWalkingEachOne() {
        ScheduleSpec.Definition interval = new ScheduleSpec.Definition(
                "FIXED_INTERVAL", "UTC", null, null, null, 0L, 60L);
        long first = 400_000L;
        long now = first + 610_000L;
        ScheduleDue.Plan plan = ScheduleDue.plan(interval, first, now, false, now);
        assertEquals(11L, plan.missedCount());
        assertEquals(first, plan.firstAt());
        assertEquals(first + 600_000L, plan.lastAt());
        assertFalse(plan.startLatest());
        assertEquals(first + 660_000L, plan.nextAt());
        assertFalse(plan.disable());
    }

    @Test
    void startsOnlyTheOccurrenceInsideGraceWhileWatching() {
        ScheduleSpec.Definition once = new ScheduleSpec.Definition(
                "ONCE", "UTC", 10_000L, null, null, null, null);
        ScheduleDue.Plan onTime = ScheduleDue.plan(once, 10_000L, 15_000L, true, 0L);
        assertTrue(onTime.startLatest());
        assertEquals(0L, onTime.missedCount());
        assertTrue(onTime.disable());
        assertNull(onTime.nextAt());

        ScheduleDue.Plan late = ScheduleDue.plan(once, 10_000L, 15_001L, true, 0L);
        assertFalse(late.startLatest());
        assertEquals(1L, late.missedCount());

        ScheduleDue.Plan unwitnessed = ScheduleDue.plan(once, 10_000L, 10_000L, false, 0L);
        assertFalse(unwitnessed.startLatest());
        assertEquals(1L, unwitnessed.missedCount());

        ScheduleDue.Plan offline = ScheduleDue.plan(once, 10_000L, 10_000L, true, 10_001L);
        assertFalse(offline.startLatest());
    }

    @Test
    void dailyGapKeepsOneSummaryAndTheNextFutureInstant() {
        long first = Instant.parse("2026-11-10T00:00:00Z").toEpochMilli();
        long now = Instant.parse("2026-11-12T00:00:01Z").toEpochMilli();
        ScheduleSpec.Definition daily = new ScheduleSpec.Definition(
                "DAILY", "UTC", null, "00:00:00", null, null, null);
        ScheduleDue.Plan plan = ScheduleDue.plan(daily, first, now, false, now);
        assertEquals(3L, plan.missedCount());
        assertEquals(Instant.parse("2026-11-12T00:00:00Z").toEpochMilli(), plan.lastAt());
        assertEquals(Instant.parse("2026-11-13T00:00:00Z").toEpochMilli(), plan.nextAt());
        assertFalse(plan.startLatest());
    }

    @Test
    void watchedGapStartsOnlyTheLatestAndCountsTheEarlierMisses() {
        ScheduleSpec.Definition interval = new ScheduleSpec.Definition(
                "FIXED_INTERVAL", "UTC", null, null, null, 0L, 60L);
        long last = 120_000L;
        long now = last + 1_000L;
        ScheduleDue.Plan plan = ScheduleDue.plan(interval, last - 60_000L, now, true, 0L);
        assertTrue(plan.startLatest());
        assertEquals(1L, plan.missedCount());
        assertEquals(last - 60_000L, plan.firstAt());
        assertEquals(last, plan.lastAt());
        assertEquals(last + 60_000L, plan.nextAt());
    }
}
