package com.szsn.srm.history;

import java.util.List;
import java.util.function.IntSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 每小时清理过期监控桶、终态执行记录、操作日志和已过期的幂等键。
 * 每一批单独提交，最多 {@link RetentionPolicy#BATCH_LIMIT} 行，不包进一个长事务。
 * 非终态执行实例不删除；监控桶过期后执行摘要仍留在执行记录里。
 */
@Service
public class RetentionCleaner {
    private static final String OPEN_STATUSES =
            "'STARTING', 'RUNNING', 'PAUSING', 'PAUSED', 'STOPPING'";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate batches;
    private final int batchLimit;
    private final int executionCap;
    private final int logCap;

    @Autowired
    public RetentionCleaner(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this(jdbc, transactions, RetentionPolicy.BATCH_LIMIT, RetentionPolicy.EXECUTION_CAP, RetentionPolicy.LOG_CAP);
    }

    RetentionCleaner(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                     int batchLimit, int executionCap, int logCap) {
        this.jdbc = jdbc;
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.batches = template;
        this.batchLimit = batchLimit;
        this.executionCap = executionCap;
        this.logCap = logCap;
    }

    public PurgeCounts purge(long now) {
        int metrics = drain(() -> deleteOldMetrics(now));
        int logs = drain(() -> deleteOldLogs(now)) + drain(this::deleteExcessLogs);
        int executions = drain(() -> deleteOldExecutions(now)) + drain(this::deleteExcessExecutions);
        int dedup = drain(() -> deleteExpiredDedup(now));
        return new PurgeCounts(metrics, executions, logs, dedup);
    }

    private int drain(IntSupplier batch) {
        int total = 0;
        while (true) {
            Integer deleted = batches.execute(status -> batch.getAsInt());
            int count = deleted == null ? 0 : deleted;
            if (count <= 0) {
                return total;
            }
            total += count;
            if (count < batchLimit) {
                return total;
            }
        }
    }

    private int deleteOldMetrics(long now) {
        List<Long> keys = jdbc.query("""
                SELECT BUCKET_START FROM RESOURCE_METRIC
                WHERE BUCKET_START < ?
                ORDER BY BUCKET_START
                LIMIT ?
                """, (rs, row) -> rs.getLong(1), RetentionPolicy.metricCutoff(now), batchLimit);
        return deleteLongs("RESOURCE_METRIC", "BUCKET_START", keys);
    }

    private int deleteOldLogs(long now) {
        long cutoff = now - RetentionPolicy.LOG_RETENTION_MS;
        List<String> ids = jdbc.query("""
                SELECT ID FROM OPERATION_LOG
                WHERE CREATED_AT < ?
                ORDER BY CREATED_AT, ID
                LIMIT ?
                """, (rs, row) -> rs.getString(1), cutoff, batchLimit);
        return deleteStrings("OPERATION_LOG", "ID", ids);
    }

    private int deleteExcessLogs() {
        int excess = count("OPERATION_LOG") - logCap;
        if (excess <= 0) {
            return 0;
        }
        List<String> ids = jdbc.query("""
                SELECT ID FROM OPERATION_LOG
                ORDER BY CREATED_AT, ID
                LIMIT ?
                """, (rs, row) -> rs.getString(1), Math.min(batchLimit, excess));
        return deleteStrings("OPERATION_LOG", "ID", ids);
    }

    private int deleteOldExecutions(long now) {
        long cutoff = now - RetentionPolicy.EXECUTION_RETENTION_MS;
        List<String> ids = jdbc.query("""
                SELECT ID FROM TASK_EXECUTION
                WHERE STATUS NOT IN (%s)
                  AND COALESCE(ENDED_AT, STARTED_AT, SCHEDULED_AT) < ?
                ORDER BY COALESCE(ENDED_AT, STARTED_AT, SCHEDULED_AT), ID
                LIMIT ?
                """.formatted(OPEN_STATUSES), (rs, row) -> rs.getString(1), cutoff, batchLimit);
        return deleteStrings("TASK_EXECUTION", "ID", ids);
    }

    private int deleteExcessExecutions() {
        int excess = count("TASK_EXECUTION") - executionCap;
        if (excess <= 0) {
            return 0;
        }
        List<String> ids = jdbc.query("""
                SELECT ID FROM TASK_EXECUTION
                WHERE STATUS NOT IN (%s)
                ORDER BY COALESCE(ENDED_AT, STARTED_AT, SCHEDULED_AT, 0), ID
                LIMIT ?
                """.formatted(OPEN_STATUSES), (rs, row) -> rs.getString(1), Math.min(batchLimit, excess));
        return deleteStrings("TASK_EXECUTION", "ID", ids);
    }

    private int deleteExpiredDedup(long now) {
        List<String> keys = jdbc.query("""
                SELECT IDEMPOTENCY_KEY FROM REQUEST_DEDUP
                WHERE EXPIRES_AT <= ?
                ORDER BY EXPIRES_AT, IDEMPOTENCY_KEY
                LIMIT ?
                """, (rs, row) -> rs.getString(1), now, batchLimit);
        return deleteStrings("REQUEST_DEDUP", "IDEMPOTENCY_KEY", keys);
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private int deleteLongs(String table, String column, List<Long> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        return jdbc.update(deleteSql(table, column, keys.size()), keys.toArray());
    }

    private int deleteStrings(String table, String column, List<String> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        return jdbc.update(deleteSql(table, column, keys.size()), keys.toArray());
    }

    private static String deleteSql(String table, String column, int size) {
        return "DELETE FROM " + table + " WHERE " + column + " IN (" + "?,".repeat(size - 1) + "?)";
    }

    public record PurgeCounts(int metrics, int executions, int logs, int dedup) {
    }
}
