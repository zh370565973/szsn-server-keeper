package com.szsn.srm.resource;

import java.util.List;

/**
 * 内存引擎只读视图。成功启动响应不假定分块增长已完成。
 * heldBytes 归零只表示负载引用已清除，不代表 RSS 已下降。
 */
public record MemoryEngineView(
        String executionId,
        boolean running,
        boolean paused,
        String memoryMode,
        Long memoryTargetBytes,
        Double memoryTargetPct,
        long heldBytes,
        Long systemUsedBytes,
        Double systemUsedPct,
        boolean systemAboveTarget,
        boolean targetLimited,
        boolean regrowCooldown,
        List<String> limitedReasons) {
}
