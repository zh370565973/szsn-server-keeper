package com.szsn.srm.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.szsn.srm.common.ApiException;
import com.szsn.srm.task.ScheduleSpec.Definition;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleCalendarTest {
    @Test
    void dailySkipsMissingLocalTimeAndKeepsOnlyTheFirstOverlap() {
        long gapNow = Instant.parse("2026-03-08T06:00:00Z").toEpochMilli();
        long skipped = ScheduleCalendar.nextFire(daily("America/New_York", "02:30:00"), gapNow);
        assertEquals(Instant.parse("2026-03-09T06:30:00Z").toEpochMilli(), skipped);

        long beforeOverlap = Instant.parse("2026-11-01T05:00:00Z").toEpochMilli();
        long first = ScheduleCalendar.nextFire(daily("America/New_York", "01:30:00"), beforeOverlap);
        assertEquals(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(), first);

        long betweenOverlap = Instant.parse("2026-11-01T06:00:00Z").toEpochMilli();
        long nextDay = ScheduleCalendar.nextFire(daily("America/New_York", "01:30:00"), betweenOverlap);
        assertEquals(Instant.parse("2026-11-02T06:30:00Z").toEpochMilli(), nextDay);
    }

    @Test
    void weeklyUsesIsoDaysAndIntervalUsesTheUtcAnchor() {
        long tuesdayMorning = Instant.parse("2026-11-10T00:00:00Z").toEpochMilli();
        long monday = ScheduleCalendar.nextFire(
                new Definition("WEEKLY", "Asia/Shanghai", null, "09:00:00", List.of(1), null, null),
                tuesdayMorning);
        assertEquals(Instant.parse("2026-11-16T01:00:00Z").toEpochMilli(), monday);

        long exactly = Instant.parse("2026-11-10T08:00:00Z").toEpochMilli();
        long sameInstant = ScheduleCalendar.nextFire(
                new Definition("WEEKLY", "UTC", null, "08:00:00", List.of(2), null, null), exactly);
        assertEquals(exactly, sameInstant);

        long anchor = 1_000_000L;
        assertEquals(anchor, ScheduleCalendar.nextFire(interval(anchor, 60), anchor));
        assertEquals(anchor + 60_000L, ScheduleCalendar.nextFire(interval(anchor, 60), anchor + 1));
        assertEquals(anchor + 60_000L, ScheduleCalendar.nextFire(interval(anchor, 60), anchor + 60_000L));
        assertEquals(anchor + 120_000L, ScheduleCalendar.nextFire(interval(anchor, 60), anchor + 60_001L));

        ApiException past = assertThrows(ApiException.class,
                () -> ScheduleCalendar.nextFire(new Definition("ONCE", "UTC", 1_000L, null, null, null, null), 1_000L));
        assertEquals("SCHEDULE_INVALID", past.code());
        assertEquals(1_001L, ScheduleCalendar.nextFire(
                new Definition("ONCE", "UTC", 1_001L, null, null, null, null), 1_000L));
    }

    private static Definition daily(String timezone, String localTime) {
        return new Definition("DAILY", timezone, null, localTime, null, null, null);
    }

    private static Definition interval(long anchorAt, long intervalSeconds) {
        return new Definition("FIXED_INTERVAL", "UTC", null, null, null, anchorAt, intervalSeconds);
    }
}
