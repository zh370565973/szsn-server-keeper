package com.szsn.srm.safety;

import com.szsn.srm.common.Units;
import com.szsn.srm.resource.MemoryBudget;
import java.util.ArrayList;
import java.util.List;

/**
 * 保护窗口判定。只返回动作，不操作线程或分配负载。
 * 85% 与 steal/iowait 带滞回；达到锁存级别的条件由调用方持久化，本对象不自动解锁。
 */
public final class ProtectionEvaluator {
    private static final long STALE_FORBID_MS = 3_000;
    private static final long STALE_STOP_MS = 10_000;
    private static final double SWAP_RATE = 16D * Units.MIB;

    private int cpu85Streak;
    private int cpu95Streak;
    private int below75Streak;
    private boolean cpuPressure;
    private int stealHighStreak;
    private int stealLowStreak;
    private boolean stealPressure;
    private int lowMemoryStreak;
    private int heap85Streak;
    private int swapHighStreak;

    public ProtectionDecision onSample(ProtectionSample sample) {
        List<String> reasons = new ArrayList<>();
        boolean forbidCpu = false;
        boolean zeroDuty = false;
        boolean forbidMemory = false;
        boolean stop = false;
        boolean latch = false;

        if (sample.cpuAgeMs() > STALE_STOP_MS || sample.memAgeMs() > STALE_STOP_MS) {
            stop = true;
            latch = true;
            zeroDuty = true;
            forbidCpu = true;
            forbidMemory = true;
            reasons.add("METRIC_STALE_10S");
        } else if (sample.cpuAgeMs() > STALE_FORBID_MS || sample.memAgeMs() > STALE_FORBID_MS) {
            forbidCpu = true;
            forbidMemory = true;
            reasons.add("METRIC_STALE_3S");
        }

        boolean cpuValid = sample.cpuBusyPct() != null && sample.cpuAgeMs() <= STALE_FORBID_MS;
        if (cpuValid) {
            double cpu = sample.cpuBusyPct();
            cpu95Streak = cpu >= 95 ? cpu95Streak + 1 : 0;
            cpu85Streak = cpu >= 85 ? cpu85Streak + 1 : 0;
            below75Streak = cpu < 75 ? below75Streak + 1 : 0;
            if (cpu95Streak >= 3) {
                stop = true;
                latch = true;
                zeroDuty = true;
                reasons.add("CPU_GE_95");
            }
            if (cpu85Streak >= 3) {
                cpuPressure = true;
            }
            if (cpuPressure && below75Streak >= 10) {
                cpuPressure = false;
            }
            if (cpuPressure) {
                zeroDuty = true;
                forbidCpu = true;
                reasons.add("CPU_GE_85");
            }
            applySteal(sample, reasons);
            if (stealPressure) {
                zeroDuty = true;
                forbidCpu = true;
            }
        }

        boolean memoryValid = sample.availableBytes() != null && sample.memTotalBytes() != null && sample.memAgeMs() <= STALE_FORBID_MS;
        if (memoryValid) {
            long reserve = MemoryBudget.systemReserve(sample.memTotalBytes());
            long available = sample.availableBytes();
            lowMemoryStreak = available < reserve ? lowMemoryStreak + 1 : 0;
            if (available < reserve + Units.GUARD) {
                forbidMemory = true;
                reasons.add("AVAILABLE_BELOW_RESERVE_GUARD");
            }
            if (lowMemoryStreak >= 2) {
                stop = true;
                latch = true;
                forbidMemory = true;
                reasons.add("AVAILABLE_BELOW_RESERVE");
            }
        }

        if (sample.heapUsedBytes() != null && sample.heapMaxBytes() != null && sample.heapMaxBytes() > 0) {
            double ratio = sample.heapUsedBytes().doubleValue() / sample.heapMaxBytes().doubleValue();
            heap85Streak = ratio >= 0.85 ? heap85Streak + 1 : 0;
            if (ratio >= 0.80) {
                forbidMemory = true;
                reasons.add("HEAP_GE_80");
            }
            if (heap85Streak >= 2) {
                stop = true;
                latch = true;
                forbidMemory = true;
                reasons.add("HEAP_GE_85");
            }
        }

        if (sample.swapPresent() && sample.swapRateBytesPerSec() != null) {
            swapHighStreak = sample.swapRateBytesPerSec() >= SWAP_RATE ? swapHighStreak + 1 : 0;
            if (swapHighStreak >= 5) {
                stop = true;
                latch = true;
                reasons.add("SWAP_RATE");
            }
        }

        if (stop) {
            zeroDuty = true;
            forbidCpu = true;
            forbidMemory = true;
        }
        return new ProtectionDecision(forbidCpu, zeroDuty, forbidMemory, stop, latch, List.copyOf(reasons));
    }

    private void applySteal(ProtectionSample sample, List<String> reasons) {
        if (sample.stealPct() == null || sample.iowaitPct() == null) {
            return;
        }
        boolean high = sample.stealPct() >= 20 || sample.iowaitPct() >= 20;
        boolean low = sample.stealPct() < 10 && sample.iowaitPct() < 10;
        if (high) {
            stealHighStreak++;
            stealLowStreak = 0;
        } else if (low) {
            stealLowStreak++;
            stealHighStreak = 0;
        } else {
            stealHighStreak = 0;
            stealLowStreak = 0;
        }
        if (stealHighStreak >= 10) {
            stealPressure = true;
        }
        if (stealPressure && stealLowStreak >= 10) {
            stealPressure = false;
        }
        if (stealPressure) {
            reasons.add("STEAL_OR_IOWAIT");
        }
    }
}
