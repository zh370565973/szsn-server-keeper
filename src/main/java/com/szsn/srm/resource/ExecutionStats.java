package com.szsn.srm.resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szsn.srm.monitor.Quality;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次执行的统计摘要。均值按有效采样时长加权，空值不计入该项。
 * 整机内存使用和负载 heldBytes 分开累计，不混称内存占用。
 */
final class ExecutionStats {
    static final long SAMPLE_CAP_MS = 2_000L;

    private static final ObjectMapper JSON = new ObjectMapper();

    private long validCoverageMs;
    private final Weighted cpuBusy = new Weighted();
    private final Weighted systemMemoryUsed = new Weighted();
    private final Weighted heldBytes = new Weighted();

    void add(long durationMs, Quality quality, Double cpuBusyPct, Long systemMemoryUsedBytes, Long held) {
        if (durationMs <= 0 || (quality != Quality.VALID && quality != Quality.PARTIAL)) {
            return;
        }
        long span = Math.min(durationMs, SAMPLE_CAP_MS);
        validCoverageMs += span;
        cpuBusy.add(cpuBusyPct, span);
        systemMemoryUsed.add(systemMemoryUsedBytes == null ? null : systemMemoryUsedBytes.doubleValue(), span);
        heldBytes.add(held == null ? null : held.doubleValue(), span);
    }

    Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("validCoverageMs", validCoverageMs);
        cpuBusy.write(values, "cpuBusyAvgPct", "cpuBusyPeakPct", "cpuBusyCoverageMs");
        systemMemoryUsed.write(values, "systemMemoryUsedAvgBytes", "systemMemoryUsedPeakBytes",
                "systemMemoryUsedCoverageMs");
        heldBytes.write(values, "heldBytesAvg", "heldBytesPeak", "heldBytesCoverageMs");
        return values;
    }

    String toJson() {
        try {
            return JSON.writeValueAsString(toMap());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("执行统计无法序列化", exception);
        }
    }

    private static final class Weighted {
        private double weighted;
        private long weight;
        private double peak;
        private boolean seen;

        private void add(Double value, long span) {
            if (value == null || !Double.isFinite(value)) {
                return;
            }
            weighted += value * span;
            weight += span;
            peak = seen ? Math.max(peak, value) : value;
            seen = true;
        }

        private void write(Map<String, Object> values, String avgKey, String peakKey, String coverageKey) {
            if (weight <= 0) {
                return;
            }
            values.put(avgKey, weighted / weight);
            values.put(peakKey, peak);
            values.put(coverageKey, weight);
        }
    }
}
