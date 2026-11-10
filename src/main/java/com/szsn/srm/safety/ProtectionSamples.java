package com.szsn.srm.safety;

import com.szsn.srm.monitor.MetricSnapshot;

/**
 * 把监控快照转成保护窗口和健康窗口样本。换页速率由调用方提供，可为 null。
 */
public final class ProtectionSamples {
    private ProtectionSamples() {
    }

    public static ProtectionSample protection(MetricSnapshot snapshot, Double swapRateBytesPerSec) {
        if (snapshot == null) {
            throw new IllegalArgumentException("快照不能为空");
        }
        MetricSnapshot.CpuView cpu = snapshot.cpu();
        MetricSnapshot.MemoryView memory = snapshot.memory();
        MetricSnapshot.JvmView jvm = snapshot.jvm();
        boolean swapPresent = memory != null && memory.swapTotalBytes() != null && memory.swapTotalBytes() > 0;
        return new ProtectionSample(
                cpu == null ? null : cpu.overallBusyPct(),
                snapshot.ageMs(),
                cpu == null ? null : cpu.stealPct(),
                cpu == null ? null : cpu.iowaitPct(),
                memory == null ? null : memory.availableBytes(),
                memory == null ? null : memory.totalBytes(),
                snapshot.ageMs(),
                jvm == null ? null : jvm.heapUsedBytes(),
                jvm == null ? null : jvm.heapMaxBytes(),
                swapPresent,
                swapRateBytesPerSec);
    }

    public static KeyMetricSample keyMetric(MetricSnapshot snapshot, Double swapRateBytesPerSec) {
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
                swapRateBytesPerSec);
    }
}
