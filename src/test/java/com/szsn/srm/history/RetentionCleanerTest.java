package com.szsn.srm.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.szsn.srm.db.DatabaseMigrator;
import com.szsn.srm.system.StorageHealth;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class RetentionCleanerTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final String SUMMARY = "{\"heldBytesAvg\":4096,\"cpuBusyAvgPct\":12}";

    @Test
    void policyMatchesTheRetentionContract() {
        assertEquals(7L * 24 * 60 * 60 * 1000, RetentionPolicy.METRIC_RETENTION_MS);
        assertEquals(90L * 24 * 60 * 60 * 1000, RetentionPolicy.EXECUTION_RETENTION_MS);
        assertEquals(30L * 24 * 60 * 60 * 1000, RetentionPolicy.LOG_RETENTION_MS);
        assertEquals(1000, RetentionPolicy.BATCH_LIMIT);
        assertEquals(100_000, RetentionPolicy.EXECUTION_CAP);
        assertEquals(200_000, RetentionPolicy.LOG_CAP);
    }

    @Test
    void purgeKeepsActiveInstancesAndExecutionSummaries() {
        Fixture fixture = fixture("retain");
        RetentionCleaner cleaner = new RetentionCleaner(fixture.jdbc, fixture.transactions, 2, 100, 100);
        long metricCutoff = NOW - RetentionPolicy.METRIC_RETENTION_MS;
        long executionCutoff = NOW - RetentionPolicy.EXECUTION_RETENTION_MS;
        long logCutoff = NOW - RetentionPolicy.LOG_RETENTION_MS;

        insertMetric(fixture.jdbc, metricCutoff - 1);
        insertMetric(fixture.jdbc, metricCutoff);
        String active = insertExecution(fixture.jdbc, "RUNNING", NOW - 200L * 86_400_000L, null, SUMMARY);
        String expired = insertExecution(fixture.jdbc, "STOPPED", null, executionCutoff - 1, SUMMARY);
        String kept = insertExecution(fixture.jdbc, "STOPPED", null, executionCutoff, SUMMARY);
        String skipped = insertExecution(fixture.jdbc, "SKIPPED", null, executionCutoff - 1, "{\"missedCount\":3}");
        fixture.jdbc.update("""
                UPDATE TASK_EXECUTION SET SCHEDULED_AT = ?, STARTED_AT = NULL, ENDED_AT = NULL WHERE ID = ?
                """, executionCutoff - 1, skipped);
        insertLog(fixture.jdbc, logCutoff - 1);
        insertLog(fixture.jdbc, logCutoff);
        insertDedup(fixture.jdbc, "expired-key", NOW);
        insertDedup(fixture.jdbc, "live-key", NOW + 1);

        RetentionCleaner.PurgeCounts counts = cleaner.purge(NOW);

        assertEquals(1, counts.metrics());
        assertEquals(2, counts.executions());
        assertEquals(1, counts.logs());
        assertEquals(1, counts.dedup());
        assertEquals(1, count(fixture.jdbc, "RESOURCE_METRIC"));
        assertEquals(metricCutoff, fixture.jdbc.queryForObject("SELECT BUCKET_START FROM RESOURCE_METRIC", Long.class));
        assertEquals("RUNNING", status(fixture.jdbc, active));
        assertEquals(SUMMARY, stats(fixture.jdbc, active));
        assertEquals(0, countWhere(fixture.jdbc, "TASK_EXECUTION", "ID = '" + expired + "'"));
        assertEquals(0, countWhere(fixture.jdbc, "TASK_EXECUTION", "ID = '" + skipped + "'"));
        assertEquals(SUMMARY, stats(fixture.jdbc, kept));
        assertEquals(1, count(fixture.jdbc, "OPERATION_LOG"));
        assertEquals(logCutoff, fixture.jdbc.queryForObject("SELECT CREATED_AT FROM OPERATION_LOG", Long.class));
        assertEquals(1, countWhere(fixture.jdbc, "REQUEST_DEDUP", "IDEMPOTENCY_KEY = 'live-key'"));
        assertEquals(0, countWhere(fixture.jdbc, "REQUEST_DEDUP", "IDEMPOTENCY_KEY = 'expired-key'"));
    }

    @Test
    void batchesStaySmallAndSurviveAnOuterRollback() {
        Fixture fixture = fixture("batch");
        RecordingJdbc jdbc = new RecordingJdbc(fixture.dataSource);
        RetentionCleaner cleaner = new RetentionCleaner(jdbc, fixture.transactions, 2, 100, 100);
        long cutoff = NOW - RetentionPolicy.METRIC_RETENTION_MS;
        for (int i = 1; i <= 5; i++) {
            insertMetric(fixture.jdbc, cutoff - i);
        }
        insertMetric(fixture.jdbc, cutoff);

        TransactionTemplate outer = new TransactionTemplate(fixture.transactions);
        outer.executeWithoutResult(status -> {
            cleaner.purge(NOW);
            status.setRollbackOnly();
        });

        assertEquals(1, count(fixture.jdbc, "RESOURCE_METRIC"));
        List<String> deletes = jdbc.sqls.stream().filter(sql -> sql.startsWith("DELETE FROM RESOURCE_METRIC")).toList();
        assertEquals(3, deletes.size());
        assertTrue(deletes.stream().allMatch(sql -> placeholders(sql) <= 2));
    }

    @Test
    void countCapRemovesOldestTerminalRowsButNotTheActiveOne() {
        Fixture fixture = fixture("cap");
        RetentionCleaner cleaner = new RetentionCleaner(fixture.jdbc, fixture.transactions, 2, 2, 2);
        String active = insertExecution(fixture.jdbc, "RUNNING", NOW - 5_000, null, SUMMARY);
        String oldest = insertExecution(fixture.jdbc, "STOPPED", null, NOW - 400, SUMMARY);
        String middle = insertExecution(fixture.jdbc, "COMPLETED", null, NOW - 300, SUMMARY);
        String newer = insertExecution(fixture.jdbc, "FAILED", null, NOW - 200, SUMMARY);
        String newest = insertExecution(fixture.jdbc, "INTERRUPTED", null, NOW - 100, SUMMARY);
        insertLog(fixture.jdbc, NOW - 40);
        insertLog(fixture.jdbc, NOW - 30);
        insertLog(fixture.jdbc, NOW - 20);
        insertLog(fixture.jdbc, NOW - 10);

        cleaner.purge(NOW);

        assertEquals(2, count(fixture.jdbc, "TASK_EXECUTION"));
        assertEquals("RUNNING", status(fixture.jdbc, active));
        assertEquals(SUMMARY, stats(fixture.jdbc, active));
        assertEquals(0, countWhere(fixture.jdbc, "TASK_EXECUTION", "ID = '" + oldest + "'"));
        assertEquals(0, countWhere(fixture.jdbc, "TASK_EXECUTION", "ID = '" + middle + "'"));
        assertEquals(0, countWhere(fixture.jdbc, "TASK_EXECUTION", "ID = '" + newer + "'"));
        assertEquals(1, countWhere(fixture.jdbc, "TASK_EXECUTION", "ID = '" + newest + "'"));
        assertEquals(2, count(fixture.jdbc, "OPERATION_LOG"));
        assertEquals(NOW - 10, fixture.jdbc.queryForObject("SELECT MAX(CREATED_AT) FROM OPERATION_LOG", Long.class));
        assertEquals(NOW - 20, fixture.jdbc.queryForObject("SELECT MIN(CREATED_AT) FROM OPERATION_LOG", Long.class));
    }

    @Test
    void historyQueryFlagsRangesBeforeTheMetricCutoff() {
        Fixture fixture = fixture("query");
        Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        HistoryService history = new HistoryService(
                new HistoryRepository(fixture.jdbc, new ObjectMapper()), new StorageHealth(), clock);
        long cutoff = RetentionPolicy.metricCutoff(NOW);

        HistoryService.HistoryView inside = history.query(NOW - 3_600_000L, NOW, null);
        HistoryService.HistoryView edge = history.query(cutoff, NOW, null);
        HistoryService.HistoryView expired = history.query(cutoff - 1, NOW, null);

        assertFalse(inside.pastRetention());
        assertFalse(edge.pastRetention());
        assertTrue(expired.pastRetention());
        assertEquals(cutoff, expired.retainedFrom());
        assertTrue(expired.buckets().isEmpty());
    }

    private static int placeholders(String sql) {
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }

    private static void insertMetric(JdbcTemplate jdbc, long bucketStart) {
        jdbc.update("""
                INSERT INTO RESOURCE_METRIC (BUCKET_START, SAMPLE_COUNT, COVERAGE_MS, METRICS_JSON)
                VALUES (?, 1, 1000, '{}')
                """, bucketStart);
    }

    private static String insertExecution(JdbcTemplate jdbc, String status, Long startedAt, Long endedAt, String stats) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO TASK_EXECUTION (
                    ID, CONFIG_SNAPSHOT_JSON, STATUS, VERSION, STARTED_AT, ENDED_AT, STATS_JSON
                ) VALUES (?, '{}', ?, 1, ?, ?, ?)
                """, (PreparedStatement statement) -> {
            statement.setString(1, id);
            statement.setString(2, status);
            setLong(statement, 3, startedAt);
            setLong(statement, 4, endedAt);
            statement.setString(5, stats);
        });
        return id;
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void insertLog(JdbcTemplate jdbc, long createdAt) {
        jdbc.update("""
                INSERT INTO OPERATION_LOG (ID, ACTOR, ACTION, RESULT, CREATED_AT)
                VALUES (?, 'tester', 'NOTE', 'OK', ?)
                """, UUID.randomUUID().toString(), createdAt);
    }

    private static void insertDedup(JdbcTemplate jdbc, String key, long expiresAt) {
        jdbc.update("""
                INSERT INTO REQUEST_DEDUP (IDEMPOTENCY_KEY, REQUEST_HASH, CREATED_AT, EXPIRES_AT)
                VALUES (?, 'hash', ?, ?)
                """, key, expiresAt - 1, expiresAt);
    }

    private static String status(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("SELECT STATUS FROM TASK_EXECUTION WHERE ID = ?", String.class, id);
    }

    private static String stats(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("SELECT STATS_JSON FROM TASK_EXECUTION WHERE ID = ?", String.class, id);
    }

    private static int count(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static int countWhere(JdbcTemplate jdbc, String table, String where) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
        return count == null ? 0 : count;
    }

    private static Fixture fixture(String name) {
        try {
            SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
            dataSource.setDriverClass(org.h2.Driver.class);
            dataSource.setUrl("jdbc:h2:mem:" + name + UUID.randomUUID().toString().replace("-", "")
                    + ";DB_CLOSE_DELAY=-1;MODE=REGULAR");
            dataSource.setUsername("sa");
            dataSource.setPassword("");
            new DatabaseMigrator(dataSource).migrate();
            return new Fixture(dataSource, new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(SimpleDriverDataSource dataSource, JdbcTemplate jdbc,
                           DataSourceTransactionManager transactions) {
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final List<String> sqls = new ArrayList<>();

        private RecordingJdbc(SimpleDriverDataSource dataSource) {
            super(dataSource);
        }

        @Override
        public int update(String sql, Object... args) {
            sqls.add(sql);
            return super.update(sql, args);
        }
    }
}
