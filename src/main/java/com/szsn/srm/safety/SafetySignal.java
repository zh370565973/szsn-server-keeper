package com.szsn.srm.safety;

import com.szsn.srm.monitor.Quality;
import java.util.List;

/**
 * 安全线程对外只读状态。引擎尚未接入时仍发布 forbid/zeroDuty 标志，供页面与后续 Worker 读取。
 */
public record SafetySignal(
        long evaluatedAt,
        Long snapshotSequence,
        long ageMs,
        Quality quality,
        boolean storageHealthy,
        String storageFault,
        boolean forbidCpuGrowth,
        boolean zeroDuty,
        boolean forbidMemoryGrowth,
        boolean safetyStop,
        boolean latch,
        List<String> reasons) {

    public static SafetySignal unavailable(long evaluatedAt, boolean storageHealthy, String storageFault) {
        return new SafetySignal(
                evaluatedAt,
                null,
                Long.MAX_VALUE,
                Quality.UNAVAILABLE,
                storageHealthy,
                storageFault,
                true,
                true,
                true,
                false,
                false,
                List.of("NO_SAMPLE"));
    }

    public SafetyPulseView pulse() {
        return new SafetyPulseView(quality, ageMs, storageHealthy, storageFault, forbidCpuGrowth, zeroDuty,
                forbidMemoryGrowth, safetyStop, latch, reasons);
    }

    public record SafetyPulseView(
            Quality quality,
            long ageMs,
            boolean storageHealthy,
            String storageFault,
            boolean forbidCpuGrowth,
            boolean zeroDuty,
            boolean forbidMemoryGrowth,
            boolean safetyStop,
            boolean latch,
            List<String> reasons) {
    }
}
