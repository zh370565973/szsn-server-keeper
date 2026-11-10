package com.szsn.srm.task;

import java.util.List;

/**
 * 已保存的定时计划。{@code nextFireAt} 是下一次触发时间，到期扫描不在这个对象里。
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
        long updatedAt) {
}
