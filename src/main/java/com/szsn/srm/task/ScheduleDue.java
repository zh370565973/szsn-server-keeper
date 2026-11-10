package com.szsn.srm.task;

/**
 * 判断一次到期要启动、记漏执行，还是两者都有。不写数据库，也不启动负载。
 * 固定间隔用除法直接跳过中间点；本地时间按日历逐次前进，不逐条补写执行记录。
 */
final class ScheduleDue {
    static final long GRACE_MS = 5_000L;
    private static final int MAX_LOCAL_STEPS = 200_000;

    private ScheduleDue() {
    }

    /**
     * @param watching    相邻两次扫描间隔不超过宽限，表示服务一直在线
     * @param onlineSince 本进程开始观察计划的时间；更早的触发点视为离线
     */
    static Plan plan(ScheduleSpec.Definition definition, long nextFireAt, long now, boolean watching, long onlineSince) {
        if (definition == null) {
            throw new IllegalArgumentException("计划定义不能为空");
        }
        if (nextFireAt > now) {
            return null;
        }
        DueSpan span = span(definition, nextFireAt, now);
        boolean onTime = span.lastAt >= onlineSince && now - span.lastAt <= GRACE_MS;
        boolean startLatest = watching && onTime;
        long missed = startLatest ? span.count - 1 : span.count;
        return new Plan(span.firstAt, span.lastAt, missed, startLatest, span.nextAt, span.disable);
    }

    private static DueSpan span(ScheduleSpec.Definition definition, long first, long now) {
        if ("ONCE".equals(definition.type())) {
            return new DueSpan(first, first, 1, null, true);
        }
        if ("FIXED_INTERVAL".equals(definition.type())) {
            return spanInterval(first, definition.intervalSeconds(), now);
        }
        long cursor = first;
        long last = first;
        long count = 0;
        while (cursor <= now) {
            count++;
            last = cursor;
            if (count > MAX_LOCAL_STEPS) {
                throw new IllegalStateException("漏执行跨度过大");
            }
            long upcoming = ScheduleCalendar.nextAfter(definition, cursor);
            if (upcoming <= cursor) {
                throw new IllegalStateException("下一次触发时间没有前进");
            }
            cursor = upcoming;
        }
        return new DueSpan(first, last, count, cursor, false);
    }

    private static DueSpan spanInterval(long first, long intervalSeconds, long now) {
        if (intervalSeconds <= 0) {
            throw new IllegalArgumentException("intervalSeconds 不合法");
        }
        try {
            long intervalMs = Math.multiplyExact(intervalSeconds, 1000L);
            long elapsed = Math.subtractExact(now, first);
            long steps = elapsed / intervalMs;
            long count = Math.addExact(steps, 1);
            long last = Math.addExact(first, Math.multiplyExact(steps, intervalMs));
            long next = Math.addExact(last, intervalMs);
            return new DueSpan(first, last, count, next, false);
        } catch (ArithmeticException exception) {
            return new DueSpan(first, first, 1, null, true);
        }
    }

    record Plan(long firstAt, long lastAt, long missedCount, boolean startLatest, Long nextAt, boolean disable) {
    }

    private record DueSpan(long firstAt, long lastAt, long count, Long nextAt, boolean disable) {
    }
}
