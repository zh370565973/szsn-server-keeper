package com.szsn.srm.task;

/**
 * 仍有效的任务模板。已软删除的模板不通过查询返回。
 */
public record TaskTemplateView(
        String id,
        String name,
        String description,
        String resourceMode,
        Double cpuTargetPct,
        String memoryMode,
        Long memoryTargetBytes,
        Double memoryTargetPct,
        String runMode,
        Long durationSeconds,
        long version,
        long createdAt,
        long updatedAt) {
}
