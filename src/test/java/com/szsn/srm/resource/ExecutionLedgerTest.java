package com.szsn.srm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szsn.srm.db.DatabaseMigrator;
import com.szsn.srm.monitor.MetricSnapshot;
import com.szsn.srm.monitor.Quality;
import com.szsn.srm.safety.LatchHealthWindow;
import com.szsn.srm.system.StorageHealth;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class ExecutionLedgerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SNAPSHOT = "{\"resourceMode\":\"CPU\",\"cpuTargetPct\":40,\"runMode\":\"FINITE\",\"durationSeconds\":60}";

    @Test
    void averagesExcludeNullsAndKeepSystemMemoryApartFromHeldBytes() {
        ExecutionStats stats = new ExecutionStats();
        stats.add(2_000, Quality.STALE, 99D, 99L, 99L);
        stats.add(1_000, Quality.VALID, null, 100L, 1L);
        stats.add(1_000, Quality.PARTIAL, 40D, null, 5L);

        JsonNode node = read(stats.toJson());
        assertEquals(2_000L, node.get("validCoverageMs").asLong());
        assertEquals(40D, node.get("cpuBusyAvgPct").asDouble());
        assertEquals(1_000L, node.get("cpuBusyCoverageMs").asLong());
        assertEquals(100D, node.get("systemMemoryUsedAvgBytes").asDouble());
        assertEquals(1_000L, node.get("systemMemoryUsedCoverageMs").asLong());
        assertEquals(3D, node.get("heldBytesAvg").asDouble());
        assertEquals(5D, node.get("heldBytesPeak").asDouble());
        assertTrue(node.get("systemMemoryUsedAvgBytes").asDouble() != node.get("heldBytesAvg").asDouble());
    }

    @Test
    void statusChangesArePersistedImmediatelyAndSnapshotStays() throws Exception {
        Fixture fixture = fixture();
        String id = insertExecution(fixture.jdbc, "STARTING", 1, null, null, null, null, null);
        MutableNanos nanos = new MutableNanos();
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        ExecutionLedger ledger = new ExecutionLedger(fixture.dataSource, clock, nanos);
        ResourceLoadManager sessions = new ResourceLoadManager(ledger);

        SessionClaim claim = sessions.claim(id);
        assertEquals(1L, version(fixture.jdbc, id));
        assertEquals("STARTING", status(fixture.jdbc, id));

        sessions.markRunning(id);
        assertEquals("RUNNING", status(fixture.jdbc, id));
        assertEquals(2L, version(fixture.jdbc, id));
        assertEquals(clock.millis(), column(fixture.jdbc, "STARTED_AT", id));

        sessions.beginPause(id);
        assertEquals("PAUSING", status(fixture.jdbc, id));
        sessions.completePause(id);
        assertEquals("PAUSED", status(fixture.jdbc, id));
        assertEquals(4L, version(fixture.jdbc, id));

        sessions.requestStop(id, StopKind.USER);
        assertEquals("STOPPING", status(fixture.jdbc, id));
        sessions.confirmWorkerExit(claim.permit());
        assertEquals("STOPPED", status(fixture.jdbc, id));
        assertEquals("USER_STOP", text(fixture.jdbc, "REASON", id));
        assertEquals(clock.millis(), column(fixture.jdbc, "ENDED_AT", id));
        assertEquals(SNAPSHOT, text(fixture.jdbc, "CONFIG_SNAPSHOT_JSON", id));
        assertTrue(sessions.active().isEmpty());
    }

    @Test
    void heartbeatUsesMonotonicDurationAndWeightedSamples() throws Exception {
        Fixture fixture = fixture();
        String id = insertExecution(fixture.jdbc, "STARTING", 1, null, null, null, null, null);
        MutableNanos nanos = new MutableNanos();
        MutableClock clock = new MutableClock(50_000L);
        ExecutionLedger ledger = new ExecutionLedger(fixture.dataSource, clock, nanos);
        ledger.record(id, ExecutionStatus.RUNNING);
        assertNull(column(fixture.jdbc, "LAST_HEARTBEAT_AT", id));

        clock.set(1_000L);
        for (int second = 1; second <= 29; second++) {
            nanos.set(TimeUnit.SECONDS.toNanos(second));
            ledger.tick(nanos.nanoTime(), session(id, ExecutionStatus.RUNNING, held(second)), sample(second));
        }
        assertNull(column(fixture.jdbc, "LAST_HEARTBEAT_AT", id));

        nanos.set(TimeUnit.SECONDS.toNanos(30));
        ledger.tick(nanos.nanoTime(), session(id, ExecutionStatus.RUNNING, held(30)), sample(30));

        assertEquals(1_000L, column(fixture.jdbc, "LAST_HEARTBEAT_AT", id));
        assertEquals(30_000L, column(fixture.jdbc, "ELAPSED_DURATION_MS", id));
        assertEquals(30_000L, column(fixture.jdbc, "ACTIVE_DURATION_MS", id));
        assertEquals(50_000L, column(fixture.jdbc, "STARTED_AT", id));
        JsonNode stats = JSON.readTree(text(fixture.jdbc, "STATS_JSON", id));
        assertEquals(30_000L, stats.get("validCoverageMs").asLong());
        assertEquals(10D, stats.get("cpuBusyAvgPct").asDouble());
        assertEquals(15_000L, stats.get("cpuBusyCoverageMs").asLong());
        assertEquals(2_000D, stats.get("systemMemoryUsedAvgBytes").asDouble());
        assertEquals(3_000D, stats.get("systemMemoryUsedPeakBytes").asDouble());
        assertEquals(30D, stats.get("heldBytesAvg").asDouble());
        assertEquals(50D, stats.get("heldBytesPeak").asDouble());
        assertEquals(SNAPSHOT, text(fixture.jdbc, "CONFIG_SNAPSHOT_JSON", id));
    }

    @Test
    void pausedTimeStaysInElapsedButNotInActiveDuration() {
        Fixture fixture = fixture();
        String id = insertExecution(fixture.jdbc, "RUNNING", 2, 10_000L, null, null, null, null);
        MutableNanos nanos = new MutableNanos();
        MutableClock clock = new MutableClock(10_000L);
        ExecutionLedger ledger = new ExecutionLedger(fixture.dataSource, clock, nanos);

        nanos.set(0L);
        ledger.record(id, ExecutionStatus.RUNNING);
        nanos.set(TimeUnit.SECONDS.toNanos(10));
        ledger.record(id, ExecutionStatus.PAUSED);
        nanos.set(TimeUnit.SECONDS.toNanos(20));
        ledger.record(id, ExecutionStatus.RUNNING);
        nanos.set(TimeUnit.SECONDS.toNanos(30));
        ledger.tick(nanos.nanoTime(), session(id, ExecutionStatus.RUNNING, 0L), sample(1));

        assertEquals(30_000L, column(fixture.jdbc, "ELAPSED_DURATION_MS", id));
        assertEquals(20_000L, column(fixture.jdbc, "ACTIVE_DURATION_MS", id));
    }

    @Test
    void restartMarksLeftoverExecutionsInterruptedWithoutRestoringLoad() throws Exception {
        Fixture fixture = fixture();
        MutableClock clock = new MutableClock(90_000L);
        String running = insertExecution(fixture.jdbc, "RUNNING", 3, 1_000L, 5_000L, 4_000L, 4_000L, "{\"heldBytesAvg\":8}");
        String starting = insertExecution(fixture.jdbc, "STARTING", 1, 2_000L, null, null, null, null);
        String stopped = insertExecution(fixture.jdbc, "STOPPED", 4, 3_000L, 4_000L, 1_000L, 1_000L, null);
        fixture.jdbc.update("UPDATE TASK_EXECUTION SET REASON = 'USER_STOP', ENDED_AT = 4000 WHERE ID = ?", stopped);
        long nextFire = 123_456L;
        fixture.jdbc.update("""
                INSERT INTO TASK_SCHEDULE (
                    ID, TASK_ID, TYPE, SCHEDULE_JSON, TIMEZONE, ENABLED, NEXT_FIRE_AT, VERSION, CREATED_AT, UPDATED_AT
                ) VALUES (?, ?, 'ONCE', '{}', 'UTC', TRUE, ?, 1, 1, 1)
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), nextFire);

        ResourceLoadManager sessions = new ResourceLoadManager();
        ExecutionRecovery recovery = new ExecutionRecovery(fixture.jdbc, clock, fixture.migrator);
        assertEquals(2, recovery.recover());

        assertEquals("INTERRUPTED", status(fixture.jdbc, running));
        assertEquals(ExecutionRecovery.REASON, text(fixture.jdbc, "REASON", running));
        assertEquals(90_000L, column(fixture.jdbc, "ENDED_AT", running));
        assertEquals(5_000L, column(fixture.jdbc, "LAST_HEARTBEAT_AT", running));
        assertEquals(4_000L, column(fixture.jdbc, "ELAPSED_DURATION_MS", running));
        assertEquals(4_000L, column(fixture.jdbc, "ACTIVE_DURATION_MS", running));
        assertEquals("{\"heldBytesAvg\":8}", text(fixture.jdbc, "STATS_JSON", running));
        assertEquals(SNAPSHOT, text(fixture.jdbc, "CONFIG_SNAPSHOT_JSON", running));
        assertEquals(4L, version(fixture.jdbc, running));

        assertEquals("INTERRUPTED", status(fixture.jdbc, starting));
        assertNull(column(fixture.jdbc, "LAST_HEARTBEAT_AT", starting));
        assertNull(column(fixture.jdbc, "ELAPSED_DURATION_MS", starting));
        assertEquals(90_000L, column(fixture.jdbc, "ENDED_AT", starting));
        assertEquals(SNAPSHOT, text(fixture.jdbc, "CONFIG_SNAPSHOT_JSON", starting));

        assertEquals("STOPPED", status(fixture.jdbc, stopped));
        assertEquals("USER_STOP", text(fixture.jdbc, "REASON", stopped));
        assertEquals(4_000L, column(fixture.jdbc, "ENDED_AT", stopped));
        assertEquals(4L, version(fixture.jdbc, stopped));
        assertEquals(nextFire, fixture.jdbc.queryForObject("SELECT NEXT_FIRE_AT FROM TASK_SCHEDULE", Long.class));
        assertEquals(3, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM TASK_EXECUTION", Integer.class));
        assertEquals(0, fixture.jdbc.queryForObject("""
                SELECT COUNT(*) FROM TASK_EXECUTION
                WHERE STATUS IN ('STARTING', 'RUNNING', 'PAUSING', 'PAUSED', 'STOPPING')
                """, Integer.class));
        assertTrue(sessions.active().isEmpty());
        assertEquals(0, recovery.recover());

        ControlSwitch control = new ControlSwitch(sessions, new JdbcLatchStore(fixture.jdbc, JSON, clock, fixture.migrator),
                new LatchHealthWindow(), new StorageHealth(), clock, ControlAudit.NONE);
        assertEquals("OFF", control.state().controlSwitch());
        assertNull(control.state().activeExecution());
    }

    private static SessionView session(String id, ExecutionStatus status, long heldBytes) {
        return new SessionView(id, status, 1L, null, false, heldBytes);
    }

    private static long held(int second) {
        return second % 2 == 0 ? 50L : 10L;
    }

    private static MetricSnapshot sample(int second) {
        boolean odd = second % 2 == 1;
        return new MetricSnapshot(
                second,
                "boot",
                second * 1_000L,
                0,
                Quality.VALID,
                List.of(),
                true,
                new MetricSnapshot.HostView("h", "Linux", "1", "1", "cpu", 4, "17", 1L, 1L),
                new MetricSnapshot.CpuView(odd ? 10D : null, null, null, null, null, null, null, null, null),
                new MetricSnapshot.MemoryView(8L << 30, null, odd ? 1_000L : 3_000L, null, null, null),
                new MetricSnapshot.JvmView(null, null, null, null, null, null, null, null, null),
                List.of(),
                List.of(),
                new MetricSnapshot.NetworkView(null, null, null, null, List.of()));
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String insertExecution(JdbcTemplate jdbc, String status, long version, Long startedAt,
                                          Long heartbeatAt, Long elapsed, Long active, String stats) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO TASK_EXECUTION (
                    ID, CONFIG_SNAPSHOT_JSON, STATUS, VERSION, STARTED_AT, LAST_HEARTBEAT_AT,
                    ELAPSED_DURATION_MS, ACTIVE_DURATION_MS, STATS_JSON
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (java.sql.PreparedStatement statement) -> {
            statement.setString(1, id);
            statement.setString(2, SNAPSHOT);
            statement.setString(3, status);
            statement.setLong(4, version);
            setLong(statement, 5, startedAt);
            setLong(statement, 6, heartbeatAt);
            setLong(statement, 7, elapsed);
            setLong(statement, 8, active);
            if (stats == null) {
                statement.setNull(9, Types.CLOB);
            } else {
                statement.setString(9, stats);
            }
        });
        return id;
    }

    private static void setLong(java.sql.PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static String status(JdbcTemplate jdbc, String id) {
        return text(jdbc, "STATUS", id);
    }

    private static long version(JdbcTemplate jdbc, String id) {
        return column(jdbc, "VERSION", id);
    }

    private static String text(JdbcTemplate jdbc, String column, String id) {
        return jdbc.queryForObject("SELECT " + column + " FROM TASK_EXECUTION WHERE ID = ?", String.class, id);
    }

    private static Long column(JdbcTemplate jdbc, String column, String id) {
        return jdbc.queryForObject("SELECT " + column + " FROM TASK_EXECUTION WHERE ID = ?", Long.class, id);
    }

    private static Fixture fixture() {
        try {
            SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
            dataSource.setDriverClass(org.h2.Driver.class);
            dataSource.setUrl("jdbc:h2:mem:ledger" + UUID.randomUUID().toString().replace("-", "")
                    + ";DB_CLOSE_DELAY=-1;MODE=REGULAR");
            dataSource.setUsername("sa");
            dataSource.setPassword("");
            DatabaseMigrator migrator = new DatabaseMigrator(dataSource);
            migrator.migrate();
            return new Fixture(dataSource, new JdbcTemplate(dataSource), migrator);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(DataSource dataSource, JdbcTemplate jdbc, DatabaseMigrator migrator) {
    }

    private static final class MutableNanos implements ExecutionLedger.NanoSource {
        private long value;

        private void set(long value) {
            this.value = value;
        }

        @Override
        public long nanoTime() {
            return value;
        }
    }

    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        private void set(long millis) {
            this.millis = millis;
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
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}
