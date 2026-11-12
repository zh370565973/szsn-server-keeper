package com.szsn.srm.resource;

/**
 * 交给负载子进程的一次启动参数。子进程不打开数据库。
 */
public record LoadWorkerRequest(
        String executionId,
        String resourceMode,
        String memoryMode,
        Long memoryTargetBytes,
        Double memoryTargetPct,
        double cpuTargetPct,
        int workerCount) {
}
