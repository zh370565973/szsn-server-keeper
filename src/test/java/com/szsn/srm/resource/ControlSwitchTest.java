package com.szsn.srm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szsn.srm.auth.OperationLogRepository;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.Units;
import com.szsn.srm.db.DatabaseMigrator;
import com.szsn.srm.safety.KeyMetricSample;
import com.szsn.srm.safety.LatchHealthWindow;
import com.szsn.srm.system.StorageHealth;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class ControlSwitchTest {
    private static final String CONFIG = """
            {"resourceMode":"CPU","cpuTargetPct":30,"runMode":"FINITE","durationSeconds":60}
            """;

    @Test
    void everyProcessStartsWithTheSwitchOff() {
        Harness harness = memory();
        assertEquals("OFF", harness.control.state().controlSwitch());
        assertFalse(harness.control.state().emergencyLatched());

        harness.control.emergencyStop(operator());
        Harness restarted = new Harness(harness.sessions, new ControlSwitch(
                new ResourceLoadManager(), harness.store, new LatchHealthWindow(), harness.storage, clock(), ControlAudit.NONE),
                harness.store, harness.health, harness.storage);
        assertEquals("OFF", restarted.control.state().controlSwitch());
        assertTrue(restarted.control.state().emergencyLatched());
        assertEquals(ControlSwitch.EMERGENCY_REASON, restarted.control.state().latchReason());
    }

    @Test
    void unreadableLatchFailsClosedAndStaysOff() {
        MemoryStore store = new MemoryStore();
        store.loadFailure = new IllegalStateException("down");
        ControlSwitch control = new ControlSwitch(new ResourceLoadManager(), store, new LatchHealthWindow(),
                new StorageHealth(), clock(), ControlAudit.NONE);
        assertEquals("OFF", control.state().controlSwitch());
        assertTrue(control.state().emergencyLatched());
        assertEquals(ControlSwitch.UNREAD_REASON, control.state().latchReason());
        assertFalse(control.state().latchPersisted());
        ApiException denied = assertThrows(ApiException.class, control::admit);
        assertEquals("CONTROL_LATCHED", denied.code());
    }

    @Test
    void emergencyStopInvalidatesTheWorkerBeforeTheLatchWriteReturns() throws Exception {
        MemoryStore store = new MemoryStore();
        store.block = true;
        Harness harness = harness(store, new LatchHealthWindow(), new StorageHealth(), ControlAudit.NONE);
        SessionClaim claim = harness.sessions.claim("exec-1");
        harness.sessions.markRunning("exec-1");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ControlState> stopped = executor.submit(() -> harness.control.emergencyStop(operator()));
            assertTrue(store.entered.await(2, TimeUnit.SECONDS));
            assertFalse(claim.permit().valid());
            assertEquals("OFF", harness.control.state().controlSwitch());
            assertTrue(harness.control.state().emergencyLatched());
            assertEquals(ExecutionStatus.STOPPING, harness.sessions.active().orElseThrow().status());
            store.release.countDown();
            ControlState state = stopped.get(2, TimeUnit.SECONDS);
            assertTrue(state.latchPersisted());
            assertEquals(1, store.saves.get());
            harness.control.emergencyStop(operator());
            assertEquals(1, store.saves.get());
            assertEquals(clock().millis(), store.row.get().at());
            harness.sessions.confirmWorkerExit(claim.permit());
            assertEquals(ExecutionStatus.SAFETY_STOPPED, harness.sessions.lastFinished().orElseThrow().status());
        } finally {
            store.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedLatchWriteKeepsTheInMemoryLatchAndRetriesLater() {
        MemoryStore store = new MemoryStore();
        store.saveFailure = new IllegalStateException("disk");
        Harness harness = harness(store, new LatchHealthWindow(), new StorageHealth(), ControlAudit.NONE);
        SessionClaim claim = harness.sessions.claim("exec-1");
        ControlState failed = harness.control.emergencyStop(operator());
        assertFalse(claim.permit().valid());
        assertEquals("OFF", failed.controlSwitch());
        assertTrue(failed.emergencyLatched());
        assertFalse(failed.latchPersisted());
        assertTrue(store.row.get() == null);

        store.saveFailure = null;
        ControlState retried = harness.control.emergencyStop(operator());
        assertTrue(retried.latchPersisted());
        assertTrue(store.row.get().latched());
        ApiException rejected = assertThrows(ApiException.class, () -> harness.control.setEnabled(true, operator()));
        assertEquals(HttpStatus.CONFLICT, rejected.status());
        assertEquals("CONTROL_LATCHED", rejected.code());
        assertEquals("OFF", harness.control.state().controlSwitch());
    }

    @Test
    void turningTheSwitchOffStopsTheSessionAndTurningItOnDoesNotStartOne() {
        MemoryStore store = new MemoryStore();
        Harness harness = harness(store, new LatchHealthWindow(), new StorageHealth(), ControlAudit.NONE);
        SessionClaim claim = harness.sessions.claim("exec-1");
        harness.sessions.markRunning("exec-1");
        store.block = true;
        ControlState off = harness.control.setEnabled(false, operator());
        assertEquals("OFF", off.controlSwitch());
        assertFalse(claim.permit().valid());
        assertEquals(0, store.saves.get());
        assertEquals(ExecutionStatus.STOPPING, harness.sessions.active().orElseThrow().status());
        harness.sessions.confirmWorkerExit(claim.permit());
        assertEquals(ExecutionStatus.STOPPED, harness.sessions.lastFinished().orElseThrow().status());

        ControlState on = harness.control.setEnabled(true, operator());
        assertEquals("ON", on.controlSwitch());
        assertFalse(on.emergencyLatched());
        assertTrue(harness.sessions.active().isEmpty());
        harness.control.admit();
    }

    @Test
    void resetRequiresAPersonNoSessionThirtyHealthySecondsAndAWritableDatabase() {
        LatchHealthWindow health = new LatchHealthWindow();
        StorageHealth storage = new StorageHealth();
        Harness harness = harness(new MemoryStore(), health, storage, ControlAudit.NONE);
        SessionClaim claim = harness.sessions.claim("exec-1");
        harness.sessions.markRunning("exec-1");
        harness.control.emergencyStop(operator());
        feed(health, 0, LatchHealthWindow.REQUIRED_MS);
        assertTrue(harness.control.state().emergencyLatched());
        ApiException active = assertThrows(ApiException.class, () -> harness.control.resetLatch(operator()));
        assertEquals("SESSION_ACTIVE", active.code());

        harness.sessions.confirmWorkerExit(claim.permit());
        LatchHealthWindow restartedWindow = new LatchHealthWindow();
        Harness restarted = new Harness(harness.sessions, new ControlSwitch(harness.sessions, harness.store, restartedWindow,
                storage, clock(), ControlAudit.NONE), harness.store, restartedWindow, storage);
        ApiException shortWindow = assertThrows(ApiException.class, () -> restarted.control.resetLatch(operator()));
        assertEquals("METRICS_UNHEALTHY", shortWindow.code());
        assertEquals(0L, shortWindow.details().get("healthyForMs"));

        feed(restartedWindow, 0, LatchHealthWindow.REQUIRED_MS - 1_000);
        assertFalse(restarted.control.state().latchResetReady());
        storage.markFault("数据库不可写");
        feed(restartedWindow, LatchHealthWindow.REQUIRED_MS - 1_000, 1_000);
        ApiException stored = assertThrows(ApiException.class, () -> restarted.control.resetLatch(operator()));
        assertEquals("STORAGE_UNAVAILABLE", stored.code());
        assertTrue(restarted.control.state().emergencyLatched());

        StorageHealth writable = new StorageHealth();
        Harness ready = new Harness(harness.sessions, new ControlSwitch(harness.sessions, harness.store, restartedWindow,
                writable, clock(), ControlAudit.NONE), harness.store, restartedWindow, writable);
        assertTrue(ready.control.state().latchResetReady());
        ControlState cleared = ready.control.resetLatch(operator());
        assertFalse(cleared.emergencyLatched());
        assertEquals("OFF", cleared.controlSwitch());
        assertFalse(ready.store.load().orElseThrow().latched());
        feed(restartedWindow, LatchHealthWindow.REQUIRED_MS, 30_000);
        assertFalse(ready.control.state().emergencyLatched());
        assertEquals("OFF", ready.control.state().controlSwitch());
    }

    @Test
    void persistedLatchSurvivesRestartAndStillBootsOff() throws Exception {
        JdbcHarness jdbc = database();
        jdbc.control.emergencyStop(operator());
        String json = jdbc.jdbc.queryForObject("SELECT VALUE_JSON FROM SYS_CONFIG WHERE CONFIG_KEY = ?", String.class, JdbcLatchStore.KEY);
        assertTrue(json.contains("\"latched\":true"));
        assertEquals(1, count(jdbc.jdbc, "SYS_CONFIG"));
        assertEquals(1, jdbc.jdbc.queryForObject("SELECT COUNT(*) FROM OPERATION_LOG WHERE ACTION = 'EMERGENCY_STOP'", Integer.class));

        ControlSwitch restarted = new ControlSwitch(new ResourceLoadManager(), jdbc.store, new LatchHealthWindow(),
                new StorageHealth(), clock(), ControlAudit.NONE);
        assertEquals("OFF", restarted.state().controlSwitch());
        assertTrue(restarted.state().emergencyLatched());

        jdbc.jdbc.update("UPDATE SYS_CONFIG SET VALUE_JSON = ? WHERE CONFIG_KEY = ?", "{", JdbcLatchStore.KEY);
        ControlSwitch unreadable = new ControlSwitch(new ResourceLoadManager(), jdbc.store, new LatchHealthWindow(),
                new StorageHealth(), clock(), ControlAudit.NONE);
        assertEquals("OFF", unreadable.state().controlSwitch());
        assertEquals(ControlSwitch.UNREAD_REASON, unreadable.state().latchReason());
    }

    @Test
    void closedSwitchAndLatchRejectStartWithoutWritingAnExecution() throws Exception {
        JdbcHarness jdbc = database();
        AtomicInteger launches = new AtomicInteger();
        ExecutionStartService service = new ExecutionStartService(jdbc.dataSource,
                new DataSourceTransactionManager(jdbc.dataSource), clock(), jdbc.control);
        ApiException disabled = assertThrows(ApiException.class, () -> service.start(UUID.randomUUID().toString(), CONFIG,
                start -> launches.incrementAndGet()));
        assertEquals("CONTROL_DISABLED", disabled.code());
        assertEquals(0, launches.get());
        assertEquals(0, count(jdbc.jdbc, "TASK_EXECUTION"));

        jdbc.control.emergencyStop(operator());
        ApiException latched = assertThrows(ApiException.class, () -> service.start(UUID.randomUUID().toString(), CONFIG,
                start -> launches.incrementAndGet()));
        assertEquals("CONTROL_LATCHED", latched.code());
        assertEquals(0, count(jdbc.jdbc, "TASK_EXECUTION"));
        assertThrows(ApiException.class, () -> jdbc.control.setEnabled(true, operator()));

        feed(jdbc.health, 0, LatchHealthWindow.REQUIRED_MS);
        jdbc.control.resetLatch(operator());
        assertEquals("OFF", jdbc.control.state().controlSwitch());
        ApiException stillOff = assertThrows(ApiException.class, () -> service.start(UUID.randomUUID().toString(), CONFIG,
                start -> launches.incrementAndGet()));
        assertEquals("CONTROL_DISABLED", stillOff.code());

        jdbc.control.setEnabled(true, operator());
        service.start(UUID.randomUUID().toString(), CONFIG, start -> launches.incrementAndGet());
        assertEquals(1, launches.get());
        assertEquals(1, count(jdbc.jdbc, "TASK_EXECUTION"));
    }

    @Test
    void concurrentEmergencyStopPersistsOneLatch() throws Exception {
        JdbcHarness jdbc = database();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                tasks.add(executor.submit(() -> jdbc.control.emergencyStop(operator())));
            }
            for (Future<?> task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals("OFF", jdbc.control.state().controlSwitch());
        assertTrue(jdbc.control.state().emergencyLatched());
        assertEquals(1, jdbc.jdbc.queryForObject(
                "SELECT COUNT(*) FROM SYS_CONFIG WHERE CONFIG_KEY = ?", Integer.class, JdbcLatchStore.KEY));
    }

    private static void feed(LatchHealthWindow health, long start, long span) {
        for (long at = start; at <= start + span; at += 1_000) {
            health.observe(new KeyMetricSample(at, 20D, 0, 4L * Units.GIB, 8L * Units.GIB, 0, 100L * Units.MIB,
                    Units.GIB, false, null));
        }
    }

    private static Harness memory() {
        return harness(new MemoryStore(), new LatchHealthWindow(), new StorageHealth(), ControlAudit.NONE);
    }

    private static Harness harness(LatchStore store, LatchHealthWindow health, StorageHealth storage, ControlAudit audit) {
        ResourceLoadManager sessions = new ResourceLoadManager();
        ControlSwitch control = new ControlSwitch(sessions, store, health, storage, clock(), audit);
        return new Harness(sessions, control, store, health, storage);
    }

    private static JdbcHarness database() throws Exception {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.h2.Driver.class);
        dataSource.setUrl("jdbc:h2:mem:latch" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;MODE=REGULAR;LOCK_TIMEOUT=10000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        DatabaseMigrator migrator = new DatabaseMigrator(dataSource);
        migrator.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        JdbcLatchStore store = new JdbcLatchStore(jdbc, mapper, clock(), migrator);
        LatchHealthWindow health = new LatchHealthWindow();
        StorageHealth storage = new StorageHealth();
        ControlSwitch control = new ControlSwitch(new ResourceLoadManager(), store, health, storage, clock(),
                new JdbcControlAudit(new OperationLogRepository(jdbc)));
        return new JdbcHarness(dataSource, jdbc, store, health, control);
    }

    private static int count(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static Operator operator() {
        return new Operator("tester", "127.0.0.1", "req-1");
    }

    private static Clock clock() {
        return Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC);
    }

    private record Harness(ResourceLoadManager sessions, ControlSwitch control, LatchStore store, LatchHealthWindow health,
                           StorageHealth storage) {
    }

    private record JdbcHarness(DataSource dataSource, JdbcTemplate jdbc, JdbcLatchStore store, LatchHealthWindow health,
                               ControlSwitch control) {
    }

    private static final class MemoryStore implements LatchStore {
        private final AtomicReference<StoredLatch> row = new AtomicReference<>();
        private final AtomicInteger saves = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean block;
        private volatile RuntimeException loadFailure;
        private volatile RuntimeException saveFailure;

        @Override
        public Optional<StoredLatch> load() {
            if (loadFailure != null) {
                throw loadFailure;
            }
            return Optional.ofNullable(row.get());
        }

        @Override
        public void save(StoredLatch latch) {
            saves.incrementAndGet();
            if (block) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("锁存写入未放开");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            if (saveFailure != null) {
                throw saveFailure;
            }
            row.set(latch);
        }
    }
}
