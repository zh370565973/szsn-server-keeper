package com.szsn.srm.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.szsn.srm.common.Units;
import org.junit.jupiter.api.Test;

class SwapRateWindowTest {
    @Test
    void firstSampleAndMissingSwapDoNotInventPressure() {
        SwapRateWindow window = new SwapRateWindow();
        assertNull(window.observe(true, 10L * Units.MIB, 1_000));
        assertNull(window.observe(false, 10L * Units.MIB, 2_000));
        assertNull(window.observe(true, null, 3_000));
    }

    @Test
    void computesRateFromAdjacentSamples() {
        SwapRateWindow window = new SwapRateWindow();
        assertNull(window.observe(true, 0L, 0));
        Double rate = window.observe(true, 16L * Units.MIB, 1_000);
        assertEquals(16D * Units.MIB, rate, 1e-9);
    }

    @Test
    void clockStepOrCounterDropClearsRate() {
        SwapRateWindow window = new SwapRateWindow();
        window.observe(true, 8L * Units.MIB, 1_000);
        assertNull(window.observe(true, 10L * Units.MIB, 500));
        window.observe(true, 10L * Units.MIB, 2_000);
        assertNull(window.observe(true, 9L * Units.MIB, 3_000));
    }
}
