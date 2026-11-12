package com.szsn.srm.safety;

import com.szsn.srm.common.Units;
import com.szsn.srm.monitor.MetricSnapshot;
import com.szsn.srm.resource.MemoryBudget;
import java.util.ArrayList;
import java.util.List;

/**
 * 判断一份样本能否计入“连续健康”。达到锁存级条件，或 CPU/内存样本超过 3 秒，就打断窗口。
 * 本类不写锁存，也不启动安全线程。
 */
public final class KeyMetricHealth {
    public static final long FRESH_MS = 3_000;
    public static final double CPU_LATCH_PCT = 97D;
    public static final double HEAP_LATCH_RATIO = 0.95D;
    public static final double SWAP_RATE_LIMIT = 16D * Units.MIB;

    private KeyMetricHealth() {
    }

    public static KeyMetricSample from(MetricSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("快照不能为空");
        }
        MetricSnapshot.CpuView cpu = snapshot.cpu();
        MetricSnapshot.MemoryView memory = snapshot.memory();
        MetricSnapshot.JvmView jvm = snapshot.jvm();
        boolean swapPresent = memory != null && memory.swapTotalBytes() != null && memory.swapTotalBytes() > 0;
        return new KeyMetricSample(
                snapshot.observedAt(),
                cpu == null ? null : cpu.overallBusyPct(),
                snapshot.ageMs(),
                memory == null ? null : memory.availableBytes(),
                memory == null ? null : memory.totalBytes(),
                snapshot.ageMs(),
                jvm == null ? null : jvm.heapUsedBytes(),
                jvm == null ? null : jvm.heapMaxBytes(),
                swapPresent,
                null);
    }

    public static Verdict assess(KeyMetricSample sample) {
        if (sample == null) {
            throw new IllegalArgumentException("样本不能为空");
        }
        List<String> reasons = new ArrayList<>();
        Double cpu = sample.cpuBusyPct();
        if (cpu == null || !Double.isFinite(cpu) || sample.cpuAgeMs() > FRESH_MS) {
            reasons.add("CPU_NOT_FRESH");
        } else if (cpu >= CPU_LATCH_PCT) {
            reasons.add("CPU_GE_97");
        }
        Long available = sample.availableBytes();
        Long total = sample.memTotalBytes();
        if (available == null || total == null || available < 0 || total < 0 || sample.memAgeMs() > FRESH_MS) {
            reasons.add("MEMORY_NOT_FRESH");
        } else if (available < MemoryBudget.systemForbid(total)) {
            reasons.add("AVAILABLE_BELOW_RESERVE");
        }
        Long heapUsed = sample.heapUsedBytes();
        Long heapMax = sample.heapMaxBytes();
        if (heapUsed == null || heapMax == null || heapUsed < 0 || heapMax <= 0) {
            reasons.add("HEAP_UNKNOWN");
        } else if (heapUsed.doubleValue() / heapMax.doubleValue() >= HEAP_LATCH_RATIO) {
            reasons.add("HEAP_GE_95");
        }
        if (sample.swapPresent() && sample.swapRateBytesPerSec() != null) {
            double rate = sample.swapRateBytesPerSec();
            if (!Double.isFinite(rate)) {
                reasons.add("SWAP_RATE_UNKNOWN");
            } else if (rate >= SWAP_RATE_LIMIT) {
                reasons.add("SWAP_RATE");
            }
        }
        return new Verdict(reasons.isEmpty(), List.copyOf(reasons));
    }

    public record Verdict(boolean healthy, List<String> reasons) {
    }
}
