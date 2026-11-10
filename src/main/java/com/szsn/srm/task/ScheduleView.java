package com.szsn.srm.task;

import java.util.List;

/**
 * 已保存的定时计划。没有到期扫描，{@code nextFireAt} 只是按当前规则算出的下一次时间。
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
