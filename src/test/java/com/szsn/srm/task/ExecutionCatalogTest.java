package com.szsn.srm.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.szsn.srm.auth.OperationLogRepository;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.db.DatabaseMigrator;
import com.szsn.srm.resource.Operator;
import com.szsn.srm.task.ExecutionQueryService.ExecutionDetail;
import com.szsn.srm.task.ExecutionQueryService.ExecutionSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class ExecutionCatalogTest {
    private static final Operator OPERATOR = new Operator("admin", "127.0.0.1", "req-catalog");

    @Test
    void scheduleListShowsLatestSkipAndIgnoresLaterFailures() {
        Fixture fixture = fixture();
        TaskTemplateView task = fixture.templates.create(finite("日间"), OPERATOR);
        ScheduleView skipped = fixture.schedules.create(once(task.id(), fixture.clock.millis() + 3_600_000), OPERATOR);
        ScheduleView quiet = fixture.schedules.create(once(task.id(), fixture.clock.millis() + 7_200_000), OPERATOR);
        assertNull(skipped.lastSkipReason());
        assertNull(quiet.lastSkippedAt());

        insertExecution(fixture.jdbc, "skip-old", task.id(), skipped.id(), 1_000, "SKIPPED", "SKIPPED_CONFLICT", null);
        insertExecution(fixture.jdbc, "skip-new", task.id(), skipped.id(), 2_000, "SKIPPED", "SKIPPED_MISFIRE", "{}");
        fixture.jdbc.update("UPDATE TASK_EXECUTION SET STATS_JSON = '{\"missedCount\":11}' WHERE ID = 'skip-new'");
        insertExecution(fixture.jdbc, "failed-later", task.id(), skipped.id(), 3_000, "FAILED", "FAILED", null);

        ScheduleView listed = fixture.schedules.list(1, 20).items().stream()
                .filter(item -> item.id().equals(skipped.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("SKIPPED_MISFIRE", listed.lastSkipReason());
        assertEquals(2_000L, listed.lastSkippedAt());
        assertEquals(11L, listed.lastSkipMissedCount());
        ScheduleView loaded = fixture.schedules.get(skipped.id());
        assertEquals(listed.lastSkipReason(), loaded.lastSkipReason());
        assertEquals(11L, loaded.lastSkipMissedCount());
        assertNull(fixture.schedules.get(quiet.id()).lastSkipReason());
        assertNull(fixture.schedules.get(quiet.id()).lastSkipMissedCount());
    }

    @Test
    void executionQueryFiltersAndReadsSnapshotWithoutWriting() {
        Fixture fixture = fixture();
        insertExecution(fixture.jdbc, "run-1", "task-a", null, 5_000, "RUNNING", null,
                "{\"name\":\"日间检查\",\"cpuTargetPct\":30}");
        fixture.jdbc.update("""
                UPDATE TASK_EXECUTION
                SET STARTED_AT = 5000, SCHEDULED_AT = NULL, ENDED_AT = NULL,
                    STATS_JSON = '{"validCoverageMs":1000,"cpuBusyAvgPct":12.5,"heldBytesPeak":4096}'
                WHERE ID = 'run-1'
                """);
        insertExecution(fixture.jdbc, "skip-1", "task-b", "schedule-1", 1_000, "SKIPPED", "SKIPPED_CONFLICT",
                "{\"name\":\"跳过的\"}");
        insertExecution(fixture.jdbc, "safe-1", "task-a", null, 9_000, "SAFETY_STOPPED", "SAFETY_STOP",
                "not-json");
        fixture.jdbc.update("UPDATE TASK_EXECUTION SET ENDED_AT = 9000, LAST_HEARTBEAT_AT = 8500 WHERE ID = 'safe-1'");
        int before = count(fixture.jdbc);

        var page = fixture.executions.list(1, 1, null, null, null, null);
        assertEquals(3, page.total());
        assertEquals("safe-1", page.items().get(0).id());
        assertEquals("run-1", fixture.executions.list(2, 1, null, null, null, null).items().get(0).id());
        assertEquals("skip-1", fixture.executions.list(1, 20, "SKIPPED", null, null, null).items().get(0).id());
        assertEquals(2, fixture.executions.list(1, 20, null, "task-a", null, null).total());
        assertEquals("run-1", fixture.executions.list(1, 20, null, null, 4_000L, 6_000L).items().get(0).id());

        ExecutionSummary summary = fixture.executions.list(1, 20, null, null, null, null).items().stream()
                .filter(item -> item.id().equals("run-1"))
                .findFirst()
                .orElseThrow();
        assertEquals("日间检查", summary.name());
        assertEquals(5_000L, summary.startedAt());

        ExecutionDetail detail = fixture.executions.get("run-1");
        assertTrue(detail.configSnapshotReadable());
        assertEquals("日间检查", detail.configSnapshot().get("name"));
        assertEquals(12.5d, detail.stats().get("cpuBusyAvgPct"));
        assertEquals(1000L, detail.stats().get("validCoverageMs"));
        assertEquals(4096L, detail.stats().get("heldBytesPeak"));
        assertTrue(detail.safetyEvents().isEmpty());
        assertTrue(detail.targetChanges().isEmpty());

        ExecutionDetail safety = fixture.executions.get("safe-1");
        assertFalse(safety.configSnapshotReadable());
        assertEquals("not-json", safety.configSnapshotJson());
        assertEquals(1, safety.safetyEvents().size());
        assertEquals("SAFETY_STOP", safety.safetyEvents().get(0).reason());
        assertEquals(9_000L, safety.safetyEvents().get(0).at());
        assertTrue(safety.targetChanges().isEmpty());

        ApiException missing = assertThrows(ApiException.class, () -> fixture.executions.get("missing"));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
        ApiException badStatus = assertThrows(ApiException.class,
                () -> fixture.executions.list(1, 20, "NOPE", null, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, badStatus.status());
        ApiException reversed = assertThrows(ApiException.class,
                () -> fixture.executions.list(1, 20, null, null, 7_000L, 6_000L));
        assertEquals("VALIDATION_ERROR", reversed.code());
        assertEquals(before, count(fixture.jdbc));
    }

    private static void insertExecution(JdbcTemplate jdbc, String id, String taskId, String scheduleId, long at,
                                        String status, String reason, String snapshot) {
        jdbc.update("""
                INSERT INTO TASK_EXECUTION (
                    ID, TASK_ID, SCHEDULE_ID, SCHEDULED_AT, CONFIG_SNAPSHOT_JSON,
                    STATUS, REASON, VERSION, ENDED_AT, REQUEST_SOURCE
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, 'SCHEDULE')
                """, id, taskId, scheduleId, at, snapshot == null ? "{}" : snapshot, status, reason, at);
    }

    private static int count(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM TASK_EXECUTION", Integer.class);
        return count == null ? 0 : count;
    }

    private static String finite(String name) {
        return """
                {"name":"%s","resourceMode":"CPU","cpuTargetPct":30,"runMode":"FINITE","durationSeconds":60}
                """.formatted(name);
    }

    private static String once(String taskId, long startAt) {
        return """
                {"taskId":"%s","type":"ONCE","startAt":%d,"enabled":true}
                """.formatted(taskId, startAt);
    }

    private static Fixture fixture() {
        try {
            DataSource dataSource = dataSource();
            new DatabaseMigrator(dataSource).migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Clock clock = Clock.fixed(Instant.parse("2026-11-10T08:00:00Z"), ZoneOffset.UTC);
            DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
            OperationLogRepository logs = new OperationLogRepository(jdbc);
            TaskTemplateService templates = new TaskTemplateService(jdbc, transactions, logs, clock);
            ScheduleService schedules = new ScheduleService(jdbc, transactions, logs, clock);
            return new Fixture(jdbc, templates, schedules, new ExecutionQueryService(jdbc), clock);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static SimpleDriverDataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.h2.Driver.class);
        dataSource.setUrl("jdbc:h2:mem:catalog" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;MODE=REGULAR;LOCK_TIMEOUT=10000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private record Fixture(JdbcTemplate jdbc, TaskTemplateService templates, ScheduleService schedules,
                           ExecutionQueryService executions, Clock clock) {
    }
}
