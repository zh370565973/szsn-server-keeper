package com.szsn.srm.history;

/**
 * 历史、执行记录和操作日志的保留边界。数值来自 PRD：曲线 7 天，执行记录 90 天且最多 10 万条，
 * 操作日志 30 天且最多 20 万条，每批最多 1000 行。
 */
public final class RetentionPolicy {
    public static final int BATCH_LIMIT = 1000;
    public static final int EXECUTION_CAP = 100_000;
    public static final int LOG_CAP = 200_000;
    public static final long METRIC_RETENTION_MS = 7L * 24 * 60 * 60 * 1000;
    public static final long EXECUTION_RETENTION_MS = 90L * 24 * 60 * 60 * 1000;
    public static final long LOG_RETENTION_MS = 30L * 24 * 60 * 60 * 1000;

    private RetentionPolicy() {
    }

    public static long metricCutoff(long now) {
        return now - METRIC_RETENTION_MS;
    }

    public static boolean pastMetricRetention(long from, long now) {
        return from < metricCutoff(now);
    }
}
