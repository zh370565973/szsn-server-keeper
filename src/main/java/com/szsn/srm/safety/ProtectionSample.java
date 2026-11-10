package com.szsn.srm.safety;

public record ProtectionSample(
        Double cpuBusyPct,
        long cpuAgeMs,
        Double stealPct,
        Double iowaitPct,
        Long availableBytes,
        Long memTotalBytes,
        long memAgeMs,
        Long heapUsedBytes,
        Long heapMaxBytes,
        boolean swapPresent,
        Double swapRateBytesPerSec) {
}
