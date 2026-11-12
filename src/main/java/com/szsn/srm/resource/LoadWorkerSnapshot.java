package com.szsn.srm.resource;

import java.util.List;

/**
 * 负载子进程最近一次汇报。进程已经退出时 {@code alive} 为 false，驻留内存为 0。
 */
public record LoadWorkerSnapshot(
        String executionId,
        boolean paused,
        boolean alive,
        long heldBytes,
        long loadRssBytes,
        long loadHeapUsedBytes,
        Double cpuTargetPct,
        Double actualPct,
        Double duty,
        Integer workerCount,
        Double ewma,
        long version,
        boolean externalLoadHigh,
        boolean safetyZeroed,
        boolean stopTimeout,
        String memoryMode,
        Long memoryTargetBytes,
        Double memoryTargetPct,
        Double systemUsedPct,
        boolean targetLimited,
        List<String> limitedReasons) {

    static LoadWorkerSnapshot idle() {
        return new LoadWorkerSnapshot(
                null, false, false, 0, 0, 0,
                null, null, null, null, null, 0,
                false, false, false,
                null, null, null, null, false, List.of());
    }
}
