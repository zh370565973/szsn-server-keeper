package com.szsn.srm.safety;

/**
 * 解除急停锁存时使用的关键指标样本。换页速率可以缺省，缺省不代表压力为零。
 */
public record KeyMetricSample(
        long observedAt,
        Double cpuBusyPct,
        long cpuAgeMs,
        Long availableBytes,
        Long memTotalBytes,
        long memAgeMs,
        Long heapUsedBytes,
        Long heapMaxBytes,
        boolean swapPresent,
        Double swapRateBytesPerSec) {
}
