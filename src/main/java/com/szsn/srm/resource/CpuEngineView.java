package com.szsn.srm.resource;

import java.util.List;

/**
 * CPU 引擎只读视图。成功启动响应不假定调节已完成。
 */
public record CpuEngineView(
        String executionId,
        boolean running,
        double targetPct,
        Double actualPct,
        double duty,
        double ewma,
        int workerCount,
        boolean externalLoadHigh,
        boolean safetyZeroed,
        boolean stopTimeout,
        long checksum,
        List<String> limitedReasons) {
}
