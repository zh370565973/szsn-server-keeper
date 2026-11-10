package com.szsn.srm.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.szsn.srm.auth.OperationLogRepository;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.PageResult;
import com.szsn.srm.db.DatabaseMigrator;
import com.szsn.srm.resource.Operator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class TaskTemplateServiceTest {
    private static final Operator OPERATOR = new Operator("admin", "127.0.0.1", "req-1");
    private static final String CPU = """
            {"name":"日间压测","description":"只压 CPU","resourceMode":"CPU","cpuTargetPct":30,"runMode":"FINITE","durationSeconds":60}
            """;

    @Test
    void createsCpuTemplateAndRejectsIllegalShapesWithoutWriting() {
        Fixture fixture = fixture();
        TaskTemplateView created = fixture.service.create(CPU, OPERATOR);
        assertEquals("日间压测", created.name());
        assertEquals("只压 CPU", created.description());
        assertEquals("CPU", created.resourceMode());
        assertEquals(30D, created.cpuTargetPct());
        assertNull(created.memoryMode());
        assertNull(created.memoryTargetBytes());
        assertNull(created.memoryTargetPct());
        assertEquals("FINITE", created.runMode());
        assertEquals(60L, created.durationSeconds());
        assertEquals(1L, created.version());
        assertEquals(fixture.clock.millis(), created.createdAt());
        assertEquals(1, count(fixture.jdbc, "RESOURCE_TASK"));
        assertEquals(1, countWhere(fixture.jdbc, "OPERATION_LOG", "ACTION = 'TASK_CREATE'"));

        assertInvalid(fixture, """
                {"name":"越界","resourceMode":"CPU","cpuTargetPct":0,"runMode":"FINITE","durationSeconds":60}
                """);
        assertInvalid(fixture, """
                {"name":"越界","resourceMode":"CPU","cpuTargetPct":61,"runMode":"FINITE","durationSeconds":60}
                """);
        assertInvalid(fixture, """
                {"name":"带内存","resourceMode":"CPU","cpuTargetPct":30,"memoryMode":"EXTRA_BYTES","memoryTargetBytes":4194304,"runMode":"FINITE","durationSeconds":60}
                """);
        assertInvalid(fixture, """
                {"name":"缺 CPU","resourceMode":"MEMORY","cpuTargetPct":30,"memoryMode":"EXTRA_BYTES","memoryTargetBytes":4194304,"runMode":"CONTINUOUS"}
                """);
        assertInvalid(fixture, """
                {"name":"两种内存","resourceMode":"MEMORY","memoryMode":"EXTRA_BYTES","memoryTargetBytes":4194304,"memoryTargetPct":10,"runMode":"CONTINUOUS"}
                """);
        assertInvalid(fixture, """
                {"name":"未对齐","resourceMode":"MEMORY","memoryMode":"EXTRA_BYTES","memoryTargetBytes":4194305,"runMode":"CONTINUOUS"}
                """);
        assertInvalid(fixture, """
                {"name":"太短","resourceMode":"CPU","cpuTargetPct":30,"runMode":"FINITE","durationSeconds":9}
                """);
        assertInvalid(fixture, """
                {"name":"持续却有时长","resourceMode":"CPU","cpuTargetPct":30,"runMode":"CONTINUOUS","durationSeconds":60}
                """);
        assertInvalid(fixture, """
                {"name":"   ","resourceMode":"CPU","cpuTargetPct":30,"runMode":"FINITE","durationSeconds":60}
                """);
        assertInvalid(fixture, """
                {"name":"%s","description":"%s","resourceMode":"CPU","cpuTargetPct":30,"runMode":"FINITE","durationSeconds":60}
                """.formatted("名".repeat(65), "说".repeat(501)));
        assertInvalid(fixture, CPU.replace("}", ",\"expectedVersion\":1}"));
        ApiException malformed = assertThrows(ApiException.class, () -> fixture.service.create("[]", OPERATOR));
        assertEquals(HttpStatus.BAD_REQUEST, malformed.status());
        assertEquals("VALIDATION_ERROR", malformed.code());
        assertEquals(1, count(fixture.jdbc, "RESOURCE_TASK"));
        assertEquals(1, countWhere(fixture.jdbc, "OPERATION_LOG", "ACTION = 'TASK_CREATE'"));

        TaskTemplateView memory = fixture.service.create("""
                {"name":"系统占用","resourceMode":"MEMORY","memoryMode":"SYSTEM_PERCENT","memoryTargetPct":70,"runMode":"CONTINUOUS"}
                """, OPERATOR);
        assertNull(memory.cpuTargetPct());
        assertEquals("SYSTEM_PERCENT", memory.memoryMode());
        assertEquals(70D, memory.memoryTargetPct());
        assertNull(memory.durationSeconds());
        TaskTemplateView bytes = fixture.service.create("""
                {"name":"固定容量","resourceMode":"COMBINED","cpuTargetPct":1,"memoryMode":"EXTRA_BYTES","memoryTargetBytes":4194304,"runMode":"FINITE","durationSeconds":604800}
                """, OPERATOR);
        assertEquals(1D, bytes.cpuTargetPct());
        assertEquals(4_194_304L, bytes.memoryTargetBytes());
        assertEquals("", bytes.description());
    }

    @Test
    void updateReplacesTemplateWithoutChangingStartedExecution() {
        ManualClock clock = new ManualClock(Instant.parse("2026-11-10T08:00:00Z"));
        Fixture fixture = fixture(clock);
        TaskTemplateView created = fixture.service.create(CPU, OPERATOR);
        String snapshot = "{\"resourceMode\":\"CPU\",\"cpuTargetPct\":30,\"runMode\":\"FINITE\",\"durationSeconds\":60}";
        String executionId = UUID.randomUUID().toString();
        fixture.jdbc.update("""
                INSERT INTO TASK_EXECUTION (ID, TASK_ID, CONFIG_SNAPSHOT_JSON, STATUS, VERSION, REQUEST_SOURCE)
                VALUES (?, ?, ?, 'RUNNING', 4, 'MANUAL')
                """, executionId, created.id(), snapshot);

        clock.advanceMillis(5_000);
        ApiException stale = assertThrows(ApiException.class, () -> fixture.service.update(created.id(), """
                {"name":"被拒绝","resourceMode":"CPU","cpuTargetPct":40,"runMode":"FINITE","durationSeconds":60,"expectedVersion":9}
                """, OPERATOR));
        assertEquals(HttpStatus.CONFLICT, stale.status());
        assertEquals("VERSION_CONFLICT", stale.code());
        assertEquals(1L, stale.details().get("version"));
        assertEquals("日间压测", fixture.service.get(created.id()).name());

        ApiException missingVersion = assertThrows(ApiException.class, () -> fixture.service.update(created.id(), """
                {"name":"缺版本","resourceMode":"CPU","cpuTargetPct":40,"runMode":"FINITE","durationSeconds":60}
                """, OPERATOR));
        assertEquals(HttpStatus.BAD_REQUEST, missingVersion.status());

        TaskTemplateView updated = fixture.service.update(created.id(), """
                {"name":"改成内存","description":"","resourceMode":"MEMORY","memoryMode":"EXTRA_BYTES","memoryTargetBytes":8388608,"runMode":"FINITE","durationSeconds":120,"expectedVersion":1}
                """, OPERATOR);
        assertEquals(2L, updated.version());
        assertEquals("MEMORY", updated.resourceMode());
        assertNull(updated.cpuTargetPct());
        assertEquals(8_388_608L, updated.memoryTargetBytes());
        assertEquals(fixture.clock.millis() - 5_000, updated.createdAt());
        assertEquals(fixture.clock.millis(), updated.updatedAt());

        assertEquals(snapshot, fixture.jdbc.queryForObject(
                "SELECT CONFIG_SNAPSHOT_JSON FROM TASK_EXECUTION WHERE ID = ?", String.class, executionId));
        assertEquals("RUNNING", fixture.jdbc.queryForObject(
                "SELECT STATUS FROM TASK_EXECUTION WHERE ID = ?", String.class, executionId));
        assertEquals(4L, fixture.jdbc.queryForObject(
                "SELECT VERSION FROM TASK_EXECUTION WHERE ID = ?", Long.class, executionId));
        assertEquals(created.id(), fixture.jdbc.queryForObject(
                "SELECT TASK_ID FROM TASK_EXECUTION WHERE ID = ?", String.class, executionId));
        assertEquals(1, count(fixture.jdbc, "TASK_EXECUTION"));
    }

    @Test
    void deleteIsSoftAndConflictsWhenAScheduleReferencesTheTemplate() {
        Fixture fixture = fixture();
        TaskTemplateView created = fixture.service.create(CPU, OPERATOR);
        String snapshot = "历史快照保持原样";
        String executionId = UUID.randomUUID().toString();
        fixture.jdbc.update("""
                INSERT INTO TASK_EXECUTION (ID, TASK_ID, CONFIG_SNAPSHOT_JSON, STATUS, VERSION)
                VALUES (?, ?, ?, 'STOPPED', 2)
                """, executionId, created.id(), snapshot);
        insertSchedule(fixture, "s-2", created.id(), "WEEKLY");
        insertSchedule(fixture, "s-1", created.id(), "DAILY");

        ApiException conflict = assertThrows(ApiException.class, () -> fixture.service.delete(created.id(), OPERATOR));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals("TEMPLATE_REFERENCED", conflict.code());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> schedules = (List<Map<String, Object>>) conflict.details().get("schedules");
        assertEquals(List.of("s-1", "s-2"), schedules.stream().map(item -> item.get("id")).toList());
        assertEquals(List.of("DAILY", "WEEKLY"), schedules.stream().map(item -> item.get("type")).toList());
        assertNull(deletedAt(fixture, created.id()));
        assertEquals(1L, fixture.service.get(created.id()).version());

        fixture.jdbc.update("DELETE FROM TASK_SCHEDULE WHERE TASK_ID = ?", created.id());
        Map<String, Object> deleted = fixture.service.delete(created.id(), OPERATOR);
        assertEquals(Boolean.TRUE, deleted.get("deleted"));
        assertEquals(created.id(), deleted.get("id"));
        assertTrue(deletedAt(fixture, created.id()) != null);
        ApiException missing = assertThrows(ApiException.class, () -> fixture.service.get(created.id()));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
        assertEquals(0, fixture.service.list(1, 20, null).total());
        assertEquals(snapshot, fixture.jdbc.queryForObject(
                "SELECT CONFIG_SNAPSHOT_JSON FROM TASK_EXECUTION WHERE ID = ?", String.class, executionId));
        assertEquals(1, count(fixture.jdbc, "RESOURCE_TASK"));
        ApiException deleteAgain = assertThrows(ApiException.class, () -> fixture.service.delete(created.id(), OPERATOR));
        assertEquals("NOT_FOUND", deleteAgain.code());
    }

    @Test
    void listFiltersByNameAndPage() {
        ManualClock clock = new ManualClock(Instant.parse("2026-11-10T00:00:00Z"));
        Fixture fixture = fixture(clock);
        TaskTemplateView first = fixture.service.create(named("甲_计划"), OPERATOR);
        clock.advanceMillis(1);
        TaskTemplateView second = fixture.service.create(named("甲%计划"), OPERATOR);
        clock.advanceMillis(1);
        fixture.service.create(named("其他"), OPERATOR);
        PageResult<TaskTemplateView> underscore = fixture.service.list(1, 20, "甲_");
        assertEquals(1, underscore.total());
        assertEquals(first.id(), underscore.items().get(0).id());
        PageResult<TaskTemplateView> percent = fixture.service.list(1, 20, "甲%");
        assertEquals(List.of(second.id()), percent.items().stream().map(TaskTemplateView::id).toList());
        PageResult<TaskTemplateView> page = fixture.service.list(2, 2, " ");
        assertEquals(3, page.total());
        assertEquals(1, page.items().size());
        assertEquals(first.id(), page.items().get(0).id());
    }

    @Test
    void runConfigOmitsDescriptionAndBindDoesNotRewriteSnapshot() {
        Fixture fixture = fixture();
        TaskTemplateView created = fixture.service.create(CPU, OPERATOR);
        String config = fixture.service.runConfig(created.id());
        assertTrue(config.contains("\"cpuTargetPct\":30"));
        assertTrue(!config.contains("description"));
        assertTrue(!config.contains("expectedVersion"));
        String executionId = UUID.randomUUID().toString();
        String snapshot = "FROZEN";
        fixture.jdbc.update("""
                INSERT INTO TASK_EXECUTION (ID, CONFIG_SNAPSHOT_JSON, STATUS, VERSION)
                VALUES (?, ?, 'STARTING', 1)
                """, executionId, snapshot);
        fixture.service.bindExecution(created.id(), executionId);
        assertEquals(created.id(), fixture.jdbc.queryForObject(
                "SELECT TASK_ID FROM TASK_EXECUTION WHERE ID = ?", String.class, executionId));
        assertEquals(snapshot, fixture.jdbc.queryForObject(
                "SELECT CONFIG_SNAPSHOT_JSON FROM TASK_EXECUTION WHERE ID = ?", String.class, executionId));
        assertEquals(1L, fixture.jdbc.queryForObject(
                "SELECT VERSION FROM TASK_EXECUTION WHERE ID = ?", Long.class, executionId));
        fixture.service.bindExecution("other-task", executionId);
        assertEquals(created.id(), fixture.jdbc.queryForObject(
                "SELECT TASK_ID FROM TASK_EXECUTION WHERE ID = ?", String.class, executionId));
        fixture.service.delete(created.id(), OPERATOR);
        ApiException missing = assertThrows(ApiException.class, () -> fixture.service.runConfig(created.id()));
        assertEquals("NOT_FOUND", missing.code());
    }

    private static void assertInvalid(Fixture fixture, String json) {
        ApiException exception = assertThrows(ApiException.class, () -> fixture.service.create(json, OPERATOR));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.status());
        assertEquals("TARGET_INVALID", exception.code());
    }

    private static String named(String name) {
        return """
                {"name":"%s","resourceMode":"CPU","cpuTargetPct":30,"runMode":"FINITE","durationSeconds":60}
                """.formatted(name);
    }

    private static void insertSchedule(Fixture fixture, String id, String taskId, String type) {
        fixture.jdbc.update("""
                INSERT INTO TASK_SCHEDULE (
                    ID, TASK_ID, TYPE, SCHEDULE_JSON, TIMEZONE, ENABLED, VERSION, CREATED_AT, UPDATED_AT
                ) VALUES (?, ?, ?, '{}', 'Asia/Shanghai', TRUE, 1, 1, 1)
                """, id, taskId, type);
    }

    private static Long deletedAt(Fixture fixture, String id) {
        List<Long> values = fixture.jdbc.query("SELECT DELETED_AT FROM RESOURCE_TASK WHERE ID = ?",
                (rs, row) -> rs.getObject(1) == null ? null : rs.getLong(1), id);
        return values.get(0);
    }

    private static int count(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static int countWhere(JdbcTemplate jdbc, String table, String where) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
        return count == null ? 0 : count;
    }

    private static Fixture fixture() {
        return fixture(Clock.fixed(Instant.parse("2026-11-10T08:00:00Z"), ZoneOffset.UTC));
    }

    private static Fixture fixture(Clock clock) {
        try {
            DataSource dataSource = dataSource();
            new DatabaseMigrator(dataSource).migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            TaskTemplateService service = new TaskTemplateService(jdbc, new DataSourceTransactionManager(dataSource),
                    new OperationLogRepository(jdbc), clock);
            return new Fixture(jdbc, service, clock);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static SimpleDriverDataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.h2.Driver.class);
        dataSource.setUrl("jdbc:h2:mem:task" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;MODE=REGULAR;LOCK_TIMEOUT=10000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private record Fixture(JdbcTemplate jdbc, TaskTemplateService service, Clock clock) {
    }

    private static final class ManualClock extends Clock {
        private Instant now;

        private ManualClock(Instant now) {
            this.now = now;
        }

        private void advanceMillis(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
