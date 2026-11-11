package com.szsn.srm.task;

import java.util.List;

/**
 * 已保存的定时计划。{@code nextFireAt} 是下一次触发时间。
 * {@code lastSkipReason} 是该计划最近一次 SKIPPED 记录的原因，没有跳过时为空。到期扫描不在这个对象里。
 */
public record ScheduleView(
        String id,
        String taskId,
        String type,
        String timezone,
        Long startAt,
        String localTime,
        List<Integer> weekdays,
        Long anchorAt,
        Long intervalSeconds,
        boolean enabled,
        Long nextFireAt,
        long version,
        long createdAt,
        long updatedAt,
        String lastSkipReason,
        Long lastSkippedAt,
        Long lastSkipMissedCount) {

    public ScheduleView withLastSkip(String reason, Long skippedAt, Long missedCount) {
        return new ScheduleView(id, taskId, type, timezone, startAt, localTime, weekdays, anchorAt, intervalSeconds,
                enabled, nextFireAt, version, createdAt, updatedAt, reason, skippedAt, missedCount);
    }
}
