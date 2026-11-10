package com.szsn.srm.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class ScheduleServiceTest {
    private static final Operator OPERATOR = new Operator("admin", "127.0.0.1", "req-sched");

    @Test
    void createsFourShapesWithoutCronOrAnExecution() {
        ManualClock clock = new ManualClock(Instant.parse("2026-11-10T00:00:00Z"));
        Fixture fixture = fixture(clock);
        TaskTemplateView task = fixture.templates.create(finite("日间", 60), OPERATOR);

        ScheduleView once = fixture.schedules.create(once(task.id(), clock.millis() + 7_200_000, true), OPERATOR);
        assertEquals("ONCE", once.type());
        assertEquals("UTC", once.timezone());
        assertEquals(clock.millis() + 7_200_000, once.startAt());
        assertEquals(once.startAt(), once.nextFireAt());
        assertTrue(once.enabled());
        assertEquals(1L, once.version());

        ScheduleView daily = fixture.schedules.create("""
                {"taskId":"%s","type":"DAILY","timezone":"Asia/Shanghai","localTime":"09:00:00","enabled":true}
                """.formatted(task.id()), OPERATOR);
        assertEquals(Instant.parse("2026-11-10T01:00:00Z").toEpochMilli(), daily.nextFireAt());
        assertNull(daily.weekdays());
        assertNull(daily.anchorAt());

        ScheduleView weekly = fixture.schedules.create("""
                {"taskId":"%s","type":"WEEKLY","timezone":"Asia/Shanghai","localTime":"09:00:00","weekdays":[3,1],"enabled":false}
                """.formatted(task.id()), OPERATOR);
        assertEquals(java.util.List.of(1, 3), weekly.weekdays());
        assertFalse(weekly.enabled());
        assertEquals(Instant.parse("2026-11-11T01:00:00Z").toEpochMilli(), weekly.nextFireAt());

        long anchor = clock.millis() - 90_000;
        ScheduleView interval = fixture.schedules.create("""
                {"taskId":"%s","type":"FIXED_INTERVAL","anchorAt":%d,"intervalSeconds":60,"enabled":true}
                """.formatted(task.id(), anchor), OPERATOR);
        assertEquals(anchor + 120_000, interval.nextFireAt());
        assertEquals(60L, interval.intervalSeconds());
        assertEquals("UTC", interval.timezone());

        PageResult<ScheduleView> page = fixture.schedules.list(1, 2);
        assertEquals(4, page.total());
        assertEquals(java.util.List.of(interval.id(), daily.id()), page.items().stream().map(ScheduleView::id).toList());
        assertEquals(0, count(fixture.jdbc, "TASK_EXECUTION"));
        assertEquals(4, countWhere(fixture.jdbc, "OPERATION_LOG", "ACTION = 'SCHEDULE_CREATE'"));

        assertInvalid(fixture, """
                {"taskId":"%s","type":"CRON","cron":"0 * * * * *","enabled":true}
                """.formatted(task.id()));
        assertInvalid(fixture, """
                {"taskId":"%s","type":"DAILY","timezone":"Asia/Shanghai","localTime":"09:00:00","cron":"* * * * *","enabled":true}
                """.formatted(task.id()));
        assertInvalid(fixture, """
                {"taskId":"%s","type":"DAILY","timezone":"Mars/Base","localTime":"09:00:00","enabled":true}
                """.formatted(task.id()));
        assertInvalid(fixture, """
                {"taskId":"%s","type":"DAILY","timezone":"Asia/Shanghai","localTime":"09:00","enabled":true}
                """.formatted(task.id()));
        assertInvalid(fixture, """
                {"taskId":"%s","type":"WEEKLY","timezone":"Asia/Shanghai","localTime":"09:00:00","weekdays":[1,1],"enabled":true}
                """.formatted(task.id()));
        assertInvalid(fixture, """
                {"taskId":"%s","type":"FIXED_INTERVAL","anchorAt":%d,"intervalSeconds":59,"enabled":true}
                """.formatted(task.id(), clock.millis()));
        ApiException past = assertThrows(ApiException.class,
                () -> fixture.schedules.create(once(task.id(), clock.millis(), true), OPERATOR));
        assertEquals("一次性计划的开始时间必须晚于当前时间", past.getMessage());
        ApiException malformed = assertThrows(ApiException.class, () -> fixture.schedules.create("[]", OPERATOR));
        assertEquals(HttpStatus.BAD_REQUEST, malformed.status());
        assertEquals("VALIDATION_ERROR", malformed.code());
        assertEquals(4, count(fixture.jdbc, "TASK_SCHEDULE"));
        assertEquals(0, count(fixture.jdbc, "TASK_EXECUTION"));
    }

    @Test
    void bindsOnlyFiniteTemplatesUpTo24HoursAndInterval() {
        Fixture fixture = fixture();
        TaskTemplateView day = fixture.templates.create(finite("一天", 86_400), OPERATOR);
        TaskTemplateView week = fixture.templates.create(finite("七天", 604_800), OPERATOR);
        TaskTemplateView longerThanGap = fixture.templates.create(finite("两分钟", 120), OPERATOR);
        TaskTemplateView exact = fixture.templates.create(finite("一分", 60), OPERATOR);
        TaskTemplateView continuous = fixture.templates.create("""
                {"name":"持续","resourceMode":"CPU","cpuTargetPct":30,"runMode":"CONTINUOUS"}
                """, OPERATOR);
        long startAt = fixture.clock.millis() + 60_000;

        ScheduleView created = fixture.schedules.create(once(day.id(), startAt, true), OPERATOR);
        assertEquals(day.id(), created.taskId());

        ApiException overDay = assertThrows(ApiException.class,
                () -> fixture.schedules.create(once(week.id(), startAt, true), OPERATOR));
        assertEquals("TEMPLATE_NOT_SCHEDULABLE", overDay.code());
        assertEquals(ScheduleConstraint.DURATION_EXCEEDS_24H, overDay.details().get("reason"));

        ApiException overInterval = assertThrows(ApiException.class, () -> fixture.schedules.create("""
                {"taskId":"%s","type":"FIXED_INTERVAL","anchorAt":%d,"intervalSeconds":60,"enabled":true}
                """.formatted(longerThanGap.id(), startAt), OPERATOR));
        assertEquals(ScheduleConstraint.DURATION_EXCEEDS_INTERVAL, overInterval.details().get("reason"));

        ScheduleView matched = fixture.schedules.create("""
                {"taskId":"%s","type":"FIXED_INTERVAL","anchorAt":%d,"intervalSeconds":60,"enabled":true}
                """.formatted(exact.id(), startAt), OPERATOR);
        assertEquals(60L, matched.intervalSeconds());

        ApiException notFinite = assertThrows(ApiException.class,
                () -> fixture.schedules.create(once(continuous.id(), startAt, true), OPERATOR));
        assertEquals(ScheduleConstraint.NOT_FINITE, notFinite.details().get("reason"));

        ApiException missing = assertThrows(ApiException.class,
                () -> fixture.schedules.create(once(UUID.randomUUID().toString(), startAt, true), OPERATOR));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
        TaskTemplateView removable = fixture.templates.create(finite("可删", 60), OPERATOR);
        fixture.templates.delete(removable.id(), OPERATOR);
        ApiException deleted = assertThrows(ApiException.class,
                () -> fixture.schedules.create(once(removable.id(), startAt, true), OPERATOR));
        assertEquals("任务模板不存在", deleted.getMessage());
        assertEquals(2, count(fixture.jdbc, "TASK_SCHEDULE"));
        assertEquals(0, count(fixture.jdbc, "TASK_EXECUTION"));

        ApiException widened = assertThrows(ApiException.class, () -> fixture.templates.update(exact.id(), """
                {"name":"一分","resourceMode":"CPU","cpuTargetPct":30,"runMode":"FINITE","durationSeconds":120,"expectedVersion":1}
                """, OPERATOR));
        assertEquals(ScheduleConstraint.DURATION_EXCEEDS_INTERVAL,
                ((java.util.List<?>) widened.details().get("schedules")).stream()
                        .map(item -> ((java.util.Map<?, ?>) item).get("reason")).findFirst().orElse(null));
        assertEquals(60L, fixture.templates.get(exact.id()).durationSeconds());
        TaskTemplateView stillExact = fixture.templates.update(exact.id(), """
                {"name":"一分","resourceMode":"CPU","cpuTargetPct":40,"runMode":"FINITE","durationSeconds":60,"expectedVersion":1}
                """, OPERATOR);
        assertEquals(40D, stillExact.cpuTargetPct());
        assertEquals(matched.intervalSeconds(), fixture.schedules.get(matched.id()).intervalSeconds());
    }

    @Test
    void updateAndDeleteDoNotTouchExecutionsAndEnableRecomputesNextFire() {
        ManualClock clock = new ManualClock(Instant.parse("2026-11-10T00:00:00Z"));
        Fixture fixture = fixture(clock);
        TaskTemplateView task = fixture.templates.create(finite("计划", 60), OPERATOR);
        ScheduleView created = fixture.schedules.create("""
                {"taskId":"%s","type":"DAILY","timezone":"Asia/Shanghai","localTime":"09:00:00","enabled":true}
                """.formatted(task.id()), OPERATOR);
        String executionId = UUID.randomUUID().toString();
        String snapshot = "冻结快照";
        fixture.jdbc.update("""
                INSERT INTO TASK_EXECUTION (
                    ID, TASK_ID, SCHEDULE_ID, SCHEDULED_AT, CONFIG_SNAPSHOT_JSON, STATUS, VERSION
                ) VALUES (?, ?, ?, ?, ?, 'RUNNING', 4)
                """, executionId, task.id(), created.id(), created.nextFireAt(), snapshot);

        ApiException stale = assertThrows(ApiException.class, () -> fixture.schedules.update(created.id(), """
                {"taskId":"%s","type":"DAILY","timezone":"Asia/Shanghai","localTime":"10:00:00","enabled":true,"expectedVersion":9}
                """.formatted(task.id()), OPERATOR));
        assertEquals("VERSION_CONFLICT", stale.code());
        ApiException missingVersion = assertThrows(ApiException.class, () -> fixture.schedules.update(created.id(), """
                {"taskId":"%s","type":"DAILY","timezone":"Asia/Shanghai","localTime":"10:00:00","enabled":true}
                """.formatted(task.id()), OPERATOR));
        assertEquals(HttpStatus.BAD_REQUEST, missingVersion.status());

        ScheduleView updated = fixture.schedules.update(created.id(), """
                {"taskId":"%s","type":"DAILY","timezone":"Asia/Shanghai","localTime":"10:00:00","enabled":true,"expectedVersion":1}
                """.formatted(task.id()), OPERATOR);
        assertEquals(2L, updated.version());
        assertEquals(Instant.parse("2026-11-10T02:00:00Z").toEpochMilli(), updated.nextFireAt());
        assertUnchangedExecution(fixture, executionId, snapshot, created.id());

        ScheduleView disabled = fixture.schedules.setEnabled(created.id(),
                "{\"enabled\":false,\"expectedVersion\":2}", OPERATOR);
        assertFalse(disabled.enabled());
        assertEquals(updated.nextFireAt(), disabled.nextFireAt());
        clock.advanceMillis(3 * 60 * 60 * 1000);
        ScheduleView enabled = fixture.schedules.setEnabled(created.id(),
                "{\"enabled\":true,\"expectedVersion\":3}", OPERATOR);
        assertTrue(enabled.enabled());
        assertEquals(Instant.parse("2026-11-11T02:00:00Z").toEpochMilli(), enabled.nextFireAt());
        assertUnchangedExecution(fixture, executionId, snapshot, created.id());

        ScheduleView oneShot = fixture.schedules.create(once(task.id(), clock.millis() + 1_000, true), OPERATOR);
        clock.advanceMillis(2_000);
        ScheduleView switchedOff = fixture.schedules.setEnabled(oneShot.id(),
                "{\"enabled\":false,\"expectedVersion\":1}", OPERATOR);
        assertFalse(switchedOff.enabled());
        assertEquals(oneShot.nextFireAt(), switchedOff.nextFireAt());
        ApiException late = assertThrows(ApiException.class, () -> fixture.schedules.setEnabled(oneShot.id(),
                "{\"enabled\":true,\"expectedVersion\":2}", OPERATOR));
        assertEquals("SCHEDULE_INVALID", late.code());
        assertFalse(fixture.schedules.get(oneShot.id()).enabled());
        assertEquals(2L, fixture.schedules.get(oneShot.id()).version());

        java.util.Map<String, Object> deleted = fixture.schedules.delete(created.id(), OPERATOR);
        assertEquals(Boolean.TRUE, deleted.get("deleted"));
        assertUnchangedExecution(fixture, executionId, snapshot, created.id());
        ApiException gone = assertThrows(ApiException.class, () -> fixture.schedules.get(created.id()));
        assertEquals(HttpStatus.NOT_FOUND, gone.status());
        assertEquals(1, count(fixture.jdbc, "TASK_EXECUTION"));
    }

    @Test
    void rejectsThe101stSchedule() {
        Fixture fixture = fixture();
        TaskTemplateView task = fixture.templates.create(finite("上限", 60), OPERATOR);
        long startAt = fixture.clock.millis() + 60_000;
        for (int index = 0; index < 99; index++) {
            fixture.jdbc.update("""
                    INSERT INTO TASK_SCHEDULE (
                        ID, TASK_ID, TYPE, SCHEDULE_JSON, TIMEZONE, ENABLED, NEXT_FIRE_AT, VERSION, CREATED_AT, UPDATED_AT
                    ) VALUES (?, ?, 'ONCE', ?, 'UTC', TRUE, ?, 1, 1, 1)
                    """, "limit-" + index, task.id(), "{\"startAt\":" + startAt + "}", startAt);
        }
        fixture.schedules.create(once(task.id(), startAt, true), OPERATOR);
        ApiException limit = assertThrows(ApiException.class,
                () -> fixture.schedules.create(once(task.id(), startAt + 1, true), OPERATOR));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, limit.status());
        assertEquals("SCHEDULE_LIMIT", limit.code());
        assertEquals(100, count(fixture.jdbc, "TASK_SCHEDULE"));
        assertEquals(0, count(fixture.jdbc, "TASK_EXECUTION"));
    }

    private static void assertInvalid(Fixture fixture, String json) {
        ApiException exception = assertThrows(ApiException.class, () -> fixture.schedules.create(json, OPERATOR));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.status());
        assertEquals("SCHEDULE_INVALID", exception.code());
    }

    private static void assertUnchangedExecution(Fixture fixture, String executionId, String snapshot, String scheduleId) {
        assertEquals(snapshot, fixture.jdbc.queryForObject(
                "SELECT CONFIG_SNAPSHOT_JSON FROM TASK_EXECUTION WHERE ID = ?", String.class, executionId));
        assertEquals("RUNNING", fixture.jdbc.queryForObject(
                "SELECT STATUS FROM TASK_EXECUTION WHERE ID = ?", String.class, executionId));
        assertEquals(4L, fixture.jdbc.queryForObject(
                "SELECT VERSION FROM TASK_EXECUTION WHERE ID = ?", Long.class, executionId));
        assertEquals(scheduleId, fixture.jdbc.queryForObject(
                "SELECT SCHEDULE_ID FROM TASK_EXECUTION WHERE ID = ?", String.class, executionId));
    }

    private static String finite(String name, long durationSeconds) {
        return """
                {"name":"%s","resourceMode":"CPU","cpuTargetPct":30,"runMode":"FINITE","durationSeconds":%d}
                """.formatted(name, durationSeconds);
    }

    private static String once(String taskId, long startAt, boolean enabled) {
        return """
                {"taskId":"%s","type":"ONCE","startAt":%d,"enabled":%s}
                """.formatted(taskId, startAt, enabled);
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
        return fixture(new ManualClock(Instant.parse("2026-11-10T08:00:00Z")));
    }

    private static Fixture fixture(ManualClock clock) {
        try {
            DataSource dataSource = dataSource();
            new DatabaseMigrator(dataSource).migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
            OperationLogRepository logs = new OperationLogRepository(jdbc);
            TaskTemplateService templates = new TaskTemplateService(jdbc, transactions, logs, clock);
            ScheduleService schedules = new ScheduleService(jdbc, transactions, logs, clock);
            return new Fixture(jdbc, templates, schedules, clock);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static SimpleDriverDataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.h2.Driver.class);
        dataSource.setUrl("jdbc:h2:mem:schedule" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;MODE=REGULAR;LOCK_TIMEOUT=10000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private record Fixture(JdbcTemplate jdbc, TaskTemplateService templates, ScheduleService schedules, Clock clock) {
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
