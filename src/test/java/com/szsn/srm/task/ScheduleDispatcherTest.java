package com.szsn.srm.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.szsn.srm.auth.OperationLogRepository;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.db.DatabaseMigrator;
import com.szsn.srm.resource.Operator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class ScheduleDispatcherTest {
    private static final Operator OPERATOR = new Operator("admin", "127.0.0.1", "req-dispatch");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void offlineGapWritesOneMisfireAndDoesNotCatchUp() throws Exception {
        ManualClock clock = new ManualClock(1_700_000_000_000L);
        Fixture fixture = fixture(clock);
        long first = clock.millis() - 610_000L;
        ScheduleView schedule = interval(fixture, clock.millis() + 86_400_000L);
        fixture.jdbc.update("UPDATE TASK_SCHEDULE SET NEXT_FIRE_AT = ? WHERE ID = ?", first, schedule.id());

        fixture.dispatcher.scan();

        assertEquals(0, fixture.launched.size());
        assertEquals(1, count(fixture.jdbc, "TASK_EXECUTION"));
        assertEquals("SKIPPED", text(fixture.jdbc, "STATUS", schedule.id()));
        assertEquals(ScheduleDispatcher.MISFIRE, text(fixture.jdbc, "REASON", schedule.id()));
        assertEquals(11, JSON.readTree(text(fixture.jdbc, "STATS_JSON", schedule.id())).path("missedCount").asInt());
        assertEquals(first, number(fixture.jdbc, "SCHEDULED_AT", schedule.id()));
        ScheduleView after = fixture.schedules.get(schedule.id());
        assertTrue(after.enabled());
        assertTrue(after.nextFireAt() > clock.millis());
        assertEquals(first + 660_000L, after.nextFireAt());
    }

    @Test
    void lateFireBeyondFiveSecondsIsMisfireWhileStillWatching() {
        ManualClock clock = new ManualClock(1_700_000_000_000L);
        Fixture fixture = fixture(clock);
        ScheduleView schedule = interval(fixture, clock.millis() + 86_400_000L);
        fixture.dispatcher.scan();
        clock.set(clock.millis() + 6_000L);
        fixture.dispatcher.scan();
        long due = clock.millis() - 6_000L;
        fixture.jdbc.update("UPDATE TASK_SCHEDULE SET NEXT_FIRE_AT = ? WHERE ID = ?", due, schedule.id());
        clock.set(clock.millis() + 1_000L);
        fixture.dispatcher.scan();

        assertEquals(0, fixture.launched.size());
        assertEquals(ScheduleDispatcher.MISFIRE, text(fixture.jdbc, "REASON", schedule.id()));
        assertTrue(fixture.schedules.get(schedule.id()).nextFireAt() > clock.millis());
    }

    @Test
    void simultaneousDueRunsOnlyTheEarlierSchedule() {
        ManualClock clock = new ManualClock(1_700_000_000_000L);
        Fixture fixture = fixture(clock);
        ScheduleView earlier = interval(fixture, clock.millis() + 86_400_000L);
        ScheduleView later = interval(fixture, clock.millis() + 90_000_000L);
        fixture.dispatcher.scan();
        long due = clock.millis() + 1_000L;
        fixture.jdbc.update("UPDATE TASK_SCHEDULE SET NEXT_FIRE_AT = ? WHERE ID = ?", due, earlier.id());
        fixture.jdbc.update("UPDATE TASK_SCHEDULE SET NEXT_FIRE_AT = ? WHERE ID = ?", due + 1_000L, later.id());
        clock.set(due + 1_000L);
        fixture.dispatcher.scan();

        assertEquals(1, fixture.launched.size());
        assertEquals("STARTING", text(fixture.jdbc, "STATUS", earlier.id()));
        assertEquals("SCHEDULE", text(fixture.jdbc, "REQUEST_SOURCE", earlier.id()));
        assertEquals(due, number(fixture.jdbc, "SCHEDULED_AT", earlier.id()));
        assertTrue(text(fixture.jdbc, "CONFIG_SNAPSHOT_JSON", earlier.id()).contains("\"cpuTargetPct\":30"));
        assertEquals("SKIPPED", text(fixture.jdbc, "STATUS", later.id()));
        assertEquals(ScheduleDispatcher.CONFLICT, text(fixture.jdbc, "REASON", later.id()));
        assertEquals(0, countWhere(fixture.jdbc, "TASK_EXECUTION", "STATUS IN ('RUNNING', 'PAUSED')"));
        assertTrue(fixture.schedules.get(earlier.id()).nextFireAt() > clock.millis());
        assertTrue(fixture.schedules.get(later.id()).nextFireAt() > clock.millis());
    }

    @Test
    void sameInstantRunsTheSmallerScheduleId() {
        ManualClock clock = new ManualClock(1_700_000_000_000L);
        Fixture fixture = fixture(clock);
        ScheduleView left = interval(fixture, clock.millis() + 86_400_000L);
        ScheduleView right = interval(fixture, clock.millis() + 90_000_000L);
        fixture.dispatcher.scan();
        String winner = left.id().compareTo(right.id()) < 0 ? left.id() : right.id();
        String loser = winner.equals(left.id()) ? right.id() : left.id();
        long due = clock.millis() + 1_000L;
        fixture.jdbc.update("UPDATE TASK_SCHEDULE SET NEXT_FIRE_AT = ? WHERE ID = ?", due, left.id());
        fixture.jdbc.update("UPDATE TASK_SCHEDULE SET NEXT_FIRE_AT = ? WHERE ID = ?", due, right.id());
        clock.set(due);
        fixture.dispatcher.scan();

        assertEquals(1, fixture.launched.size());
        assertEquals("STARTING", text(fixture.jdbc, "STATUS", winner));
        assertEquals(ScheduleDispatcher.CONFLICT, text(fixture.jdbc, "REASON", loser));
    }

    @Test
    void pausedSessionAndClosedSwitchSkipWithoutLaunch() {
        ManualClock clock = new ManualClock(1_700_000_000_000L);
        Fixture fixture = fixture(clock);
        ScheduleView paused = interval(fixture, clock.millis() + 86_400_000L);
        ScheduleView closed = interval(fixture, clock.millis() + 90_000_000L);
        fixture.dispatcher.scan();
        long due = clock.millis() + 1_000L;
        fixture.jdbc.update("UPDATE TASK_SCHEDULE SET NEXT_FIRE_AT = ? WHERE ID = ?", due, paused.id());
        fixture.gate.occupied = true;
        clock.set(due);
        fixture.dispatcher.scan();
        assertEquals(ScheduleDispatcher.CONFLICT, text(fixture.jdbc, "REASON", paused.id()));

        fixture.gate.occupied = false;
        fixture.gate.open = false;
        fixture.jdbc.update("UPDATE TASK_SCHEDULE SET NEXT_FIRE_AT = ? WHERE ID = ?", due, closed.id());
        clock.set(due + 1_000L);
        fixture.dispatcher.scan();
        assertEquals(ScheduleDispatcher.CONTROL_DISABLED, text(fixture.jdbc, "REASON", closed.id()));
        assertEquals(0, fixture.launched.size());

        fixture.gate.open = true;
        fixture.dispatcher.scan();
        assertEquals(0, fixture.launched.size());
    }

    @Test
    void clockRollbackDoesNotRepeatAndDuplicateFireDoesNotLaunchAgain() {
        ManualClock clock = new ManualClock(1_700_000_000_000L);
        Fixture fixture = fixture(clock);
        ScheduleView schedule = once(fixture, clock.millis() + 60_000L);
        fixture.dispatcher.scan();
        long due = clock.millis() + 1_000L;
        fixture.jdbc.update("UPDATE TASK_SCHEDULE SET NEXT_FIRE_AT = ? WHERE ID = ?", due, schedule.id());
        clock.set(due);
        fixture.dispatcher.scan();
        assertEquals(1, fixture.launched.size());
        assertEquals("STARTING", text(fixture.jdbc, "STATUS", schedule.id()));
        ScheduleView advanced = fixture.schedules.get(schedule.id());
        assertFalse(advanced.enabled());
        assertNull(advanced.nextFireAt());

        clock.set(due - 10_000L);
        fixture.dispatcher.scan();
        assertEquals(1, fixture.launched.size());
        assertEquals(1, count(fixture.jdbc, "TASK_EXECUTION"));

        fixture.jdbc.update("""
                UPDATE TASK_SCHEDULE
                SET ENABLED = TRUE, NEXT_FIRE_AT = ?, VERSION = VERSION
                WHERE ID = ?
                """, due, schedule.id());
        clock.set(due + 1_000L);
        fixture.dispatcher.scan();
        assertEquals(1, fixture.launched.size());
        assertEquals(1, count(fixture.jdbc, "TASK_EXECUTION"));
        assertFalse(fixture.schedules.get(schedule.id()).enabled());
    }

    @Test
    void failedLaunchDoesNotStayStartingOrBlockTheNextSchedule() {
        ManualClock clock = new ManualClock(1_700_000_000_000L);
        Gate gate = new Gate();
        List<String> launched = new ArrayList<>();
        int[] calls = {0};
        Fixture fixture = fixture(clock, gate, (executionId, snapshot) -> {
            calls[0]++;
            if (calls[0] == 1) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CONTROL_UNAVAILABLE", "负载控制不可用");
            }
            launched.add(executionId);
        });
        ScheduleView first = interval(fixture, clock.millis() + 86_400_000L);
        ScheduleView second = interval(fixture, clock.millis() + 90_000_000L);
        fixture.dispatcher.scan();
        long due = clock.millis() + 1_000L;
        fixture.jdbc.update("UPDATE TASK_SCHEDULE SET NEXT_FIRE_AT = ? WHERE ID = ?", due, first.id());
        fixture.jdbc.update("UPDATE TASK_SCHEDULE SET NEXT_FIRE_AT = ? WHERE ID = ?", due + 500L, second.id());
        clock.set(due + 500L);
        fixture.dispatcher.scan();

        assertEquals("FAILED", text(fixture.jdbc, "STATUS", first.id()));
        assertEquals("CONTROL_UNAVAILABLE", text(fixture.jdbc, "REASON", first.id()));
        assertEquals("STARTING", text(fixture.jdbc, "STATUS", second.id()));
        assertEquals(1, launched.size());
        assertEquals(0, countWhere(fixture.jdbc, "TASK_EXECUTION", "STATUS = 'STARTING' AND SCHEDULE_ID = '" + first.id() + "'"));
    }

    private static ScheduleView interval(Fixture fixture, long anchor) {
        TaskTemplateView task = fixture.templates.create(finite("间隔", 60), OPERATOR);
        return fixture.schedules.create("""
                {"taskId":"%s","type":"FIXED_INTERVAL","anchorAt":%d,"intervalSeconds":60,"enabled":true}
                """.formatted(task.id(), anchor), OPERATOR);
    }

    private static ScheduleView once(Fixture fixture, long startAt) {
        TaskTemplateView task = fixture.templates.create(finite("一次", 60), OPERATOR);
        return fixture.schedules.create("""
                {"taskId":"%s","type":"ONCE","startAt":%d,"enabled":true}
                """.formatted(task.id(), startAt), OPERATOR);
    }

    private static String finite(String name, long durationSeconds) {
        return """
                {"name":"%s","resourceMode":"CPU","cpuTargetPct":30,"runMode":"FINITE","durationSeconds":%d}
                """.formatted(name, durationSeconds);
    }

    private static int count(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static int countWhere(JdbcTemplate jdbc, String table, String where) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
        return count == null ? 0 : count;
    }

    private static String text(JdbcTemplate jdbc, String column, String scheduleId) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM TASK_EXECUTION WHERE SCHEDULE_ID = ?", String.class, scheduleId);
    }

    private static long number(JdbcTemplate jdbc, String column, String scheduleId) {
        Long value = jdbc.queryForObject(
                "SELECT " + column + " FROM TASK_EXECUTION WHERE SCHEDULE_ID = ?", Long.class, scheduleId);
        return value == null ? -1L : value;
    }

    private static Fixture fixture(ManualClock clock) {
        Gate gate = new Gate();
        List<String> launched = new ArrayList<>();
        return fixture(clock, gate, (executionId, snapshot) -> launched.add(executionId));
    }

    private static Fixture fixture(ManualClock clock, Gate gate, ScheduleLauncher launcher) {
        try {
            DataSource dataSource = dataSource();
            new DatabaseMigrator(dataSource).migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
            OperationLogRepository logs = new OperationLogRepository(jdbc);
            TaskTemplateService templates = new TaskTemplateService(jdbc, transactions, logs, clock);
            ScheduleService schedules = new ScheduleService(jdbc, transactions, logs, clock);
            List<String> launched = new ArrayList<>();
            ScheduleLauncher recording = (executionId, snapshot) -> {
                launched.add(executionId);
                launcher.launch(executionId, snapshot);
            };
            ScheduleDispatcher dispatcher = new ScheduleDispatcher(
                    jdbc, transactions, templates, gate, recording, clock);
            return new Fixture(jdbc, templates, schedules, dispatcher, gate, launched, clock);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static SimpleDriverDataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.h2.Driver.class);
        dataSource.setUrl("jdbc:h2:mem:dispatch" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;MODE=REGULAR;LOCK_TIMEOUT=10000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private record Fixture(JdbcTemplate jdbc, TaskTemplateService templates, ScheduleService schedules,
                           ScheduleDispatcher dispatcher, Gate gate, List<String> launched, Clock clock) {
    }

    private static final class Gate implements ScheduleAdmission {
        private boolean open = true;
        private boolean occupied;

        @Override
        public boolean controlOpen() {
            return open;
        }

        @Override
        public boolean sessionOccupied() {
            return occupied;
        }
    }

    private static final class ManualClock extends Clock {
        private long now;

        private ManualClock(long now) {
            this.now = now;
        }

        private void set(long now) {
            this.now = now;
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
            return Instant.ofEpochMilli(now);
        }
    }
}
