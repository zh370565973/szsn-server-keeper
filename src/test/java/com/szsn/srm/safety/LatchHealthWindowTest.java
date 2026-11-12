package com.szsn.srm.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.szsn.srm.common.Units;
import com.szsn.srm.monitor.MetricSnapshot;
import com.szsn.srm.monitor.Quality;
import java.util.List;
import org.junit.jupiter.api.Test;

class LatchHealthWindowTest {
    @Test
    void thirtySecondsOfFreshSamplesBecomeReady() {
        LatchHealthWindow window = new LatchHealthWindow();
        assertFalse(window.view().ready());
        assertEquals(List.of("NO_SAMPLE"), window.view().reasons());
        for (long at = 0; at <= LatchHealthWindow.REQUIRED_MS; at += 1_000) {
            window.observe(healthy(at));
        }
        assertEquals(LatchHealthWindow.REQUIRED_MS, window.view().healthyForMs());
        assertTrue(window.view().ready());
        assertTrue(window.view().reasons().isEmpty());
    }

    @Test
    void gapUnhealthySampleAndClockStepRestartTheStreak() {
        LatchHealthWindow window = new LatchHealthWindow();
        window.observe(healthy(0));
        window.observe(healthy(2_000));
        assertEquals(2_000, window.view().healthyForMs());
        window.observe(healthy(2_000 + LatchHealthWindow.MAX_GAP_MS + 1));
        assertEquals(0, window.view().healthyForMs());
        assertFalse(window.view().ready());

        window.observe(healthy(10_000));
        window.observe(sample(11_000, 97D, 0, 4L * Units.GIB, 100L * Units.MIB, false, null));
        assertEquals(0, window.view().healthyForMs());
        assertTrue(window.view().reasons().contains("CPU_GE_97"));

        window.observe(healthy(20_000));
        window.observe(healthy(19_000));
        assertEquals(List.of("CLOCK_STEP"), window.view().reasons());
        assertFalse(window.view().ready());
    }

    @Test
    void latchLevelConditionsAndMissingCriticalFieldsAreUnhealthy() {
        assertTrue(KeyMetricHealth.assess(healthy(0)).healthy());
        assertTrue(reasons(sample(0, 20D, 3_000, 4L * Units.GIB, 100L * Units.MIB, false, null)).isEmpty());
        assertTrue(reasons(sample(0, 20D, 0, 4L * Units.GIB, 100L * Units.MIB, true, null)).isEmpty());
        assertTrue(reasons(sample(0, 20D, 0, 4L * Units.GIB, 100L * Units.MIB, true, 16D * Units.MIB - 1)).isEmpty());

        assertTrue(reasons(sample(0, 20D, 3_001, 4L * Units.GIB, 100L * Units.MIB, false, null)).contains("CPU_NOT_FRESH"));
        assertTrue(reasons(sample(0, null, 0, 4L * Units.GIB, 100L * Units.MIB, false, null)).contains("CPU_NOT_FRESH"));
        assertTrue(reasons(sample(0, 20D, 0, Units.GIB, 100L * Units.MIB, false, null)).contains("AVAILABLE_BELOW_RESERVE"));
        assertTrue(reasons(new KeyMetricSample(0, 20D, 0, 4L * Units.GIB, 8L * Units.GIB, 0, 950L, 1_000L, false, null))
                .contains("HEAP_GE_95"));
        assertTrue(reasons(sample(0, 20D, 0, 4L * Units.GIB, null, false, null)).contains("HEAP_UNKNOWN"));
        assertTrue(reasons(sample(0, 20D, 0, 4L * Units.GIB, 100L * Units.MIB, true, 16D * Units.MIB)).contains("SWAP_RATE"));
    }

    @Test
    void snapshotWithoutSwapRateDoesNotInventPressure() {
        MetricSnapshot snapshot = new MetricSnapshot(
                1,
                "boot",
                1_000,
                0,
                Quality.PARTIAL,
                List.of(),
                true,
                null,
                new MetricSnapshot.CpuView(12D, null, null, null, 1D, 0D, null, null, null),
                new MetricSnapshot.MemoryView(8L * Units.GIB, 4L * Units.GIB, 4L * Units.GIB, null, 2L * Units.GIB, 0L),
                new MetricSnapshot.JvmView(128L * Units.MIB, 256L * Units.MIB, Units.GIB, null, null, null, 20, null, null),
                List.of(),
                List.of(),
                null);
        KeyMetricSample sample = KeyMetricHealth.from(snapshot);
        assertTrue(sample.swapPresent());
        assertNull(sample.swapRateBytesPerSec());
        assertTrue(KeyMetricHealth.assess(sample).healthy());
    }

    private static List<String> reasons(KeyMetricSample sample) {
        return KeyMetricHealth.assess(sample).reasons();
    }

    private static KeyMetricSample healthy(long observedAt) {
        return sample(observedAt, 20D, 0, 4L * Units.GIB, 100L * Units.MIB, false, null);
    }

    private static KeyMetricSample sample(long observedAt, Double cpu, long ageMs, long available, Long heapUsed,
                                          boolean swapPresent, Double swapRate) {
        long heapMax = heapUsed == null ? Units.GIB : Math.max(heapUsed, Units.GIB);
        return new KeyMetricSample(observedAt, cpu, ageMs, available, 8L * Units.GIB, ageMs, heapUsed, heapMax,
                swapPresent, swapRate);
    }
}
