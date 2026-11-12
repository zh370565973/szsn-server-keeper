package com.szsn.srm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.szsn.srm.common.ApiException;
import com.szsn.srm.db.DatabaseMigrator;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ExecutionStartServiceTest {
    private static final String CPU = """
            {"runMode":"FINITE","durationSeconds":60.0,"cpuTargetPct":40.0,"resourceMode":"CPU"}
            """;
    private static final String CPU_REORDERED = """
            {"resourceMode":"CPU","cpuTargetPct":40,"runMode":"FINITE","durationSeconds":60}
            """;
    private static final String CPU_CANONICAL =
            "{\"resourceMode\":\"CPU\",\"cpuTargetPct\":40,\"runMode\":\"FINITE\",\"durationSeconds\":60}";
    private static final String COMBINED = """
            {
              "name": "CPU40与内存256MiB测试",
              "resourceMode": "COMBINED",
              "cpuTargetPct": 40,
              "memoryMode": "EXTRA_BYTES",
              "memoryTargetBytes": 268435456,
              "runMode": "FINITE",
              "durationSeconds": 3600
            }
            """;

    @Test
    void persistsStartingSnapshotBeforeWorkerLaunch() throws Exception {
        Fixture fixture = fixture();
        String key = key();
        AtomicInteger launches = new AtomicInteger();
        StartedExecution started = fixture.service.start(key, CPU, start -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(ExecutionStatus.STARTING.name(), fixture.jdbc.queryForObject(
                    "SELECT STATUS FROM TASK_EXECUTION WHERE ID = ?", String.class, start.executionId()));
            assertEquals(CPU_CANONICAL, fixture.jdbc.queryForObject(
                    "SELECT CONFIG_SNAPSHOT_JSON FROM TASK_EXECUTION WHERE ID = ?", String.class, start.executionId()));
            assertEquals(1L, start.version());
            launches.incrementAndGet();
        });
        assertFalse(started.replay());
        assertEquals(ExecutionStatus.STARTING, started.status());
        assertEquals(CPU_CANONICAL, started.configSnapshotJson());
        assertEquals(1, launches.get());
        assertEquals("MANUAL", fixture.jdbc.queryForObject(
                "SELECT REQUEST_SOURCE FROM TASK_EXECUTION WHERE ID = ?", String.class, started.executionId()));
        assertEquals(started.executionId(), fixture.jdbc.queryForObject(
                "SELECT EXECUTION_ID FROM REQUEST_DEDUP WHERE IDEMPOTENCY_KEY = ?", String.class, key));
        long created = fixture.jdbc.queryForObject("SELECT CREATED_AT FROM REQUEST_DEDUP", Long.class);
        long expires = fixture.jdbc.queryForObject("SELECT EXPIRES_AT FROM REQUEST_DEDUP", Long.class);
        assertEquals(ExecutionStartService.IDEMPOTENCY_WINDOW_MS, expires - created);
    }

    @Test
    void combinedSnapshotKeepsTheRequestedConfiguration() {
        Fixture fixture = fixture();
        StartedExecution started = fixture.service.start(key(), COMBINED, start -> {
        });
        assertEquals(
                "{\"name\":\"CPU40与内存256MiB测试\",\"resourceMode\":\"COMBINED\",\"cpuTargetPct\":40,"
                        + "\"memoryMode\":\"EXTRA_BYTES\",\"memoryTargetBytes\":268435456,\"runMode\":\"FINITE\",\"durationSeconds\":3600}",
                started.configSnapshotJson());
        assertEquals(ExecutionStatus.STARTING.name(), fixture.jdbc.queryForObject(
                "SELECT STATUS FROM TASK_EXECUTION WHERE ID = ?", String.class, started.executionId()));
    }

    @Test
    void transactionFailureDoesNotLaunchOrLeaveRows() throws Exception {
        DataSource raw = dataSource();
        new DatabaseMigrator(raw).migrate();
        DataSource failing = new SecondWriteFailsDataSource(raw);
        ExecutionStartService service = new ExecutionStartService(failing, new DataSourceTransactionManager(failing), clock());
        AtomicInteger launches = new AtomicInteger();
        assertThrows(DataAccessException.class, () -> service.start(key(), CPU, start -> launches.incrementAndGet()));
        assertEquals(0, launches.get());
        JdbcTemplate jdbc = new JdbcTemplate(raw);
        assertEquals(0, count(jdbc, "TASK_EXECUTION"));
        assertEquals(0, count(jdbc, "REQUEST_DEDUP"));
    }

    @Test
    void sameKeyWithin24HoursReturnsTheSameExecutionWithoutAnotherLaunch() {
        ManualClock clock = new ManualClock(Instant.parse("2026-01-01T00:00:00Z"));
        Fixture fixture = fixture(clock);
        String key = "3AC238A3-958C-4CF5-8216-E11CE3B59242";
        AtomicInteger launches = new AtomicInteger();
        StartedExecution first = fixture.service.start(key, CPU, start -> launches.incrementAndGet());
        clock.advance(Duration.ofHours(24).minusMillis(1));
        StartedExecution second = fixture.service.start(key.toLowerCase(), CPU_REORDERED, start -> launches.incrementAndGet());
        assertEquals(first.executionId(), second.executionId());
        assertTrue(second.replay());
        assertEquals(ExecutionStatus.STARTING, second.status());
        assertEquals(1, launches.get());
        assertEquals(1, count(fixture.jdbc, "TASK_EXECUTION"));
        assertEquals(key.toLowerCase(), fixture.jdbc.queryForObject(
                "SELECT IDEMPOTENCY_KEY FROM TASK_EXECUTION WHERE ID = ?", String.class, first.executionId()));
    }

    @Test
    void sameKeyWithDifferentConfigConflictsAndDoesNotLaunch() {
        Fixture fixture = fixture();
        String key = key();
        AtomicInteger launches = new AtomicInteger();
        StartedExecution first = fixture.service.start(key, CPU, start -> launches.incrementAndGet());
        ApiException conflict = assertThrows(ApiException.class,
                () -> fixture.service.start(key, CPU.replace("40.0", "41"), start -> launches.incrementAndGet()));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.code());
        assertEquals(1, launches.get());
        assertEquals(1, count(fixture.jdbc, "TASK_EXECUTION"));
        assertEquals(first.executionId(), fixture.jdbc.queryForObject(
                "SELECT EXECUTION_ID FROM REQUEST_DEDUP WHERE IDEMPOTENCY_KEY = ?", String.class, key));
        assertEquals(CPU_CANONICAL, fixture.jdbc.queryForObject(
                "SELECT CONFIG_SNAPSHOT_JSON FROM TASK_EXECUTION WHERE ID = ?", String.class, first.executionId()));
    }

    @Test
    void expiredKeyStartsANewExecutionAndKeepsTheOldRow() {
        ManualClock clock = new ManualClock(Instant.parse("2026-03-01T00:00:00Z"));
        Fixture fixture = fixture(clock);
        String key = key();
        AtomicInteger launches = new AtomicInteger();
        StartedExecution first = fixture.service.start(key, CPU, start -> launches.incrementAndGet());
        clock.advance(Duration.ofHours(24));
        StartedExecution second = fixture.service.start(key, CPU_REORDERED, start -> launches.incrementAndGet());
        assertFalse(second.replay());
        assertNotEquals(first.executionId(), second.executionId());
        assertEquals(2, launches.get());
        assertEquals(2, count(fixture.jdbc, "TASK_EXECUTION"));
        assertEquals(1, count(fixture.jdbc, "REQUEST_DEDUP"));
        assertEquals(second.executionId(), fixture.jdbc.queryForObject(
                "SELECT EXECUTION_ID FROM REQUEST_DEDUP WHERE IDEMPOTENCY_KEY = ?", String.class, key));
        assertEquals(ExecutionStatus.STARTING.name(), fixture.jdbc.queryForObject(
                "SELECT STATUS FROM TASK_EXECUTION WHERE ID = ?", String.class, first.executionId()));
    }

    @Test
    void launchFailureIsStoredAsFailedAndRetryDoesNotLaunch() {
        Fixture fixture = fixture();
        String key = key();
        AtomicInteger launches = new AtomicInteger();
        SessionRejectedException failure = assertThrows(SessionRejectedException.class, () -> fixture.service.start(key, CPU, start -> {
            launches.incrementAndGet();
            assertEquals(ExecutionStatus.STARTING.name(), fixture.jdbc.queryForObject(
                    "SELECT STATUS FROM TASK_EXECUTION WHERE ID = ?", String.class, start.executionId()));
            throw new SessionRejectedException(SessionRejectedException.Reason.SLOT_OCCUPIED, "已有活动会话");
        }));
        assertEquals(SessionRejectedException.Reason.SLOT_OCCUPIED, failure.reason());
        String executionId = fixture.jdbc.queryForObject("SELECT ID FROM TASK_EXECUTION", String.class);
        assertEquals(ExecutionStatus.FAILED.name(), fixture.jdbc.queryForObject(
                "SELECT STATUS FROM TASK_EXECUTION WHERE ID = ?", String.class, executionId));
        assertEquals("START_FAILED", fixture.jdbc.queryForObject(
                "SELECT REASON FROM TASK_EXECUTION WHERE ID = ?", String.class, executionId));
        StartedExecution replay = fixture.service.start(key, CPU_REORDERED, start -> launches.incrementAndGet());
        assertEquals(executionId, replay.executionId());
        assertEquals(ExecutionStatus.FAILED, replay.status());
        assertEquals(2L, replay.version());
        assertTrue(replay.replay());
        assertEquals(1, launches.get());
    }

    @Test
    void slotIsClaimedOnlyAfterTheSnapshotIsCommitted() {
        Fixture fixture = fixture();
        ResourceLoadManager manager = new ResourceLoadManager();
        String key = key();
        StartedExecution started = fixture.service.start(key, CPU, start -> {
            assertEquals(CPU_CANONICAL, fixture.jdbc.queryForObject(
                    "SELECT CONFIG_SNAPSHOT_JSON FROM TASK_EXECUTION WHERE ID = ?", String.class, start.executionId()));
            SessionClaim claim = manager.claim(start.executionId());
            assertTrue(claim.permit().valid());
            assertEquals(ExecutionStatus.STARTING, manager.active().orElseThrow().status());
        });
        ApiException conflict = assertThrows(ApiException.class, () -> fixture.service.start(key, """
                {"resourceMode":"MEMORY","memoryMode":"SYSTEM_PERCENT","memoryTargetPct":50,"runMode":"CONTINUOUS"}
                """, start -> manager.claim(start.executionId())));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.code());
        String otherKey = key();
        SessionRejectedException occupied = assertThrows(SessionRejectedException.class,
                () -> fixture.service.start(otherKey, CPU, start -> manager.claim(start.executionId())));
        assertEquals(SessionRejectedException.Reason.SLOT_OCCUPIED, occupied.reason());
        assertEquals(started.executionId(), manager.active().orElseThrow().executionId());
        assertEquals(ExecutionStatus.FAILED.name(), fixture.jdbc.queryForObject(
                "SELECT STATUS FROM TASK_EXECUTION WHERE IDEMPOTENCY_KEY = ?", String.class, otherKey));
    }

    @Test
    void concurrentSameKeyLaunchesOnce() throws Exception {
        Fixture fixture = fixture();
        String key = key();
        AtomicInteger launches = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                futures.add(pool.submit(() -> {
                    ready.await();
                    try {
                        return fixture.service.start(key, CPU_REORDERED, start -> launches.incrementAndGet());
                    } catch (RuntimeException exception) {
                        return exception;
                    }
                }));
            }
            ready.countDown();
            String executionId = null;
            for (Future<Object> future : futures) {
                Object result = future.get(15, TimeUnit.SECONDS);
                assertTrue(result instanceof StartedExecution, String.valueOf(result));
                StartedExecution started = (StartedExecution) result;
                if (executionId == null) {
                    executionId = started.executionId();
                }
                assertEquals(executionId, started.executionId());
            }
            assertEquals(1, launches.get());
            assertEquals(1, count(fixture.jdbc, "TASK_EXECUTION"));
            assertEquals(1, count(fixture.jdbc, "REQUEST_DEDUP"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidRequestDoesNotPersist() {
        Fixture fixture = fixture();
        AtomicInteger launches = new AtomicInteger();
        WorkerLaunch launch = start -> launches.incrementAndGet();
        ApiException missing = assertThrows(ApiException.class, () -> fixture.service.start(" ", CPU, launch));
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", missing.code());
        ApiException malformedKey = assertThrows(ApiException.class, () -> fixture.service.start("not-a-uuid", CPU, launch));
        assertEquals("IDEMPOTENCY_KEY_INVALID", malformedKey.code());
        ApiException malformedBody = assertThrows(ApiException.class, () -> fixture.service.start(key(), "{", launch));
        assertEquals(HttpStatus.BAD_REQUEST, malformedBody.status());
        ApiException memoryOnCpu = assertThrows(ApiException.class, () -> fixture.service.start(key(), """
                {"resourceMode":"CPU","cpuTargetPct":30,"memoryTargetBytes":1048576,"runMode":"FINITE","durationSeconds":60}
                """, launch));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, memoryOnCpu.status());
        assertEquals("TARGET_INVALID", memoryOnCpu.code());
        assertEquals(0, launches.get());
        assertEquals(0, count(fixture.jdbc, "TASK_EXECUTION"));
        assertEquals(0, count(fixture.jdbc, "REQUEST_DEDUP"));
    }

    @Test
    void prdBoundsRejectBeforePersistAndEdgesPrepare() {
        Fixture fixture = fixture();
        AtomicInteger launches = new AtomicInteger();
        WorkerLaunch launch = start -> launches.incrementAndGet();
        String[] rejected = {
                "{\"resourceMode\":\"CPU\",\"cpuTargetPct\":30,\"runMode\":\"FINITE\",\"durationSeconds\":9}",
                "{\"resourceMode\":\"CPU\",\"cpuTargetPct\":30,\"runMode\":\"FINITE\",\"durationSeconds\":604801}",
                "{\"resourceMode\":\"MEMORY\",\"memoryMode\":\"SYSTEM_PERCENT\",\"memoryTargetPct\":0,\"runMode\":\"CONTINUOUS\"}",
                "{\"resourceMode\":\"MEMORY\",\"memoryMode\":\"SYSTEM_PERCENT\",\"memoryTargetPct\":91,\"runMode\":\"CONTINUOUS\"}",
                "{\"resourceMode\":\"MEMORY\",\"memoryMode\":\"EXTRA_BYTES\",\"memoryTargetBytes\":1048576,\"runMode\":\"CONTINUOUS\"}",
                "{\"resourceMode\":\"MEMORY\",\"memoryMode\":\"EXTRA_BYTES\",\"memoryTargetBytes\":4194305,\"runMode\":\"CONTINUOUS\"}"
        };
        for (String body : rejected) {
            ApiException exception = assertThrows(ApiException.class,
                    () -> fixture.service.start(key(), body, launch));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.status());
            assertEquals("TARGET_INVALID", exception.code());
        }
        assertEquals(0, launches.get());
        assertEquals(0, count(fixture.jdbc, "TASK_EXECUTION"));
        assertEquals(0, count(fixture.jdbc, "REQUEST_DEDUP"));

        StartConfig.prepare(key(), """
                {"resourceMode":"CPU","cpuTargetPct":30,"runMode":"FINITE","durationSeconds":10}
                """);
        StartConfig.prepare(key(), """
                {"resourceMode":"CPU","cpuTargetPct":30,"runMode":"FINITE","durationSeconds":604800}
                """);
        StartConfig.prepare(key(), """
                {"resourceMode":"MEMORY","memoryMode":"SYSTEM_PERCENT","memoryTargetPct":1,"runMode":"CONTINUOUS"}
                """);
        StartConfig.prepare(key(), """
                {"resourceMode":"MEMORY","memoryMode":"SYSTEM_PERCENT","memoryTargetPct":70,"runMode":"CONTINUOUS"}
                """);
        StartConfig.prepare(key(), """
                {"resourceMode":"MEMORY","memoryMode":"EXTRA_BYTES","memoryTargetBytes":4194304,"runMode":"CONTINUOUS"}
                """);
        assertEquals(0, count(fixture.jdbc, "TASK_EXECUTION"));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static int count(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static Fixture fixture() {
        return fixture(clock());
    }

    private static Fixture fixture(Clock clock) {
        try {
            DataSource dataSource = dataSource();
            new DatabaseMigrator(dataSource).migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            ExecutionStartService service = new ExecutionStartService(dataSource, new DataSourceTransactionManager(dataSource), clock);
            return new Fixture(jdbc, service);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static SimpleDriverDataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.h2.Driver.class);
        dataSource.setUrl("jdbc:h2:mem:start" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;MODE=REGULAR;LOCK_TIMEOUT=10000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static Clock clock() {
        return Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC);
    }

    private record Fixture(JdbcTemplate jdbc, ExecutionStartService service) {
    }

    private static final class ManualClock extends Clock {
        private Instant now;

        private ManualClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
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

    private static final class SecondWriteFailsDataSource extends DelegatingDataSource {
        private final AtomicInteger writes = new AtomicInteger();

        private SecondWriteFailsDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return proxyConnection(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return proxyConnection(super.getConnection(username, password));
        }

        private Connection proxyConnection(Connection target) {
            return (Connection) Proxy.newProxyInstance(ExecutionStartServiceTest.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if ("prepareStatement".equals(method.getName()) && args != null && args.length == 1) {
                            return proxyStatement((PreparedStatement) invoke(method, target, args));
                        }
                        return invoke(method, target, args);
                    });
        }

        private PreparedStatement proxyStatement(PreparedStatement target) {
            return (PreparedStatement) Proxy.newProxyInstance(ExecutionStartServiceTest.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                        if ("executeUpdate".equals(method.getName()) && (args == null || args.length == 0)
                                && writes.incrementAndGet() == 2) {
                            throw new SQLException("第二次写入失败");
                        }
                        return invoke(method, target, args);
                    });
        }

        private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }
}
