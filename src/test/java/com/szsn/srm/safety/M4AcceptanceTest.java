package com.szsn.srm.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.szsn.srm.auth.OperationLogRepository;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.SrmProperties;
import com.szsn.srm.db.DatabaseMigrator;
import com.szsn.srm.history.HistoryRepository;
import com.szsn.srm.history.HistoryService;
import com.szsn.srm.monitor.CollectorLoop;
import com.szsn.srm.monitor.MetricSnapshot;
import com.szsn.srm.monitor.Quality;
import com.szsn.srm.resource.ControlAudit;
import com.szsn.srm.resource.ControlSwitch;
import com.szsn.srm.resource.ExecutionRecovery;
import com.szsn.srm.resource.ExecutionStartService;
import com.szsn.srm.resource.JdbcLatchStore;
import com.szsn.srm.resource.LatchStore;
import com.szsn.srm.resource.Operator;
import com.szsn.srm.resource.ResourceLoadManager;
import com.szsn.srm.resource.SessionClaim;
import com.szsn.srm.resource.StartedExecution;
import com.szsn.srm.resource.StoredLatch;
import com.szsn.srm.system.ControlAvailability;
import com.szsn.srm.system.EnvironmentProbe;
import com.szsn.srm.system.EnvironmentReport;
import com.szsn.srm.system.StorageHealth;
import com.szsn.srm.system.SystemController;
import com.szsn.srm.task.LiveScheduleAdmission;
import com.szsn.srm.task.ScheduleDispatcher;
import com.szsn.srm.task.ScheduleService;
import com.szsn.srm.task.ScheduleView;
import com.szsn.srm.task.TaskTemplateService;
import com.szsn.srm.task.TaskTemplateView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * M4 验收：AT-11、AT-12、AT-15、AT-20。
 * 使用文件型 H2 覆盖并发写入、进程重启后的迁移与恢复、写入失败和磁盘空间不足。
 * 不启动生产燃烧器，也不分配负载内存。
 */
class M4AcceptanceTest {
    private static final Operator OPERATOR = new Operator("admin", "127.0.0.1", "m4-06");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CPU_CONFIG = """
            {"name":"验收启动","resourceMode":"CPU","cpuTargetPct":30,"runMode":"FINITE","durationSeconds":60}
            """;

    @Test
    void at11ConcurrentStartsAndClockRollbackDoNotRunTwice() throws Exception {
        try (Database database = Database.open()) {
            ManualClock clock = new ManualClock(1_700_000_000_000L);
            Services services = services(database, clock);
            int inserted = raceSameScheduleFire(database.jdbc);
            assertEquals(1, inserted);
            assertEquals(1, countWhere(database.jdbc, "SCHEDULE_ID = 'same-fire'"));

            services.control.setEnabled(true, OPERATOR);
            AtomicInteger launches = new AtomicInteger();
            String key = UUID.randomUUID().toString();
            List<String> ids = raceStarts(services.starts, key, launches);
            assertEquals(1, launches.get());
            assertEquals(1, ids.stream().distinct().count());
            StartedExecution replay = services.starts.start(key, CPU_CONFIG, start -> launches.incrementAndGet());
            assertTrue(replay.replay());
            assertEquals(ids.get(0), replay.executionId());
            assertEquals(1, launches.get());
            database.jdbc.update("UPDATE TASK_EXECUTION SET STATUS = 'STOPPED', ENDED_AT = ? WHERE ID = ?",
                    clock.millis(), ids.get(0));

            ResourceLoadManager sessions = new ResourceLoadManager();
            List<String> scheduled = new ArrayList<>();
            ScheduleDispatcher dispatcher = dispatcher(database, clock, services.templates, services.control, sessions,
                    (executionId, snapshot) -> {
                        sessions.claim(executionId);
                        sessions.markRunning(executionId);
                        scheduled.add(executionId);
                    });
            ScheduleView earlier = interval(services, clock.millis() + 86_400_000L, "同时甲");
            ScheduleView later = interval(services, clock.millis() + 90_000_000L, "同时乙");
            long due = clock.millis();
            database.jdbc.update("UPDATE TASK_SCHEDULE SET NEXT_FIRE_AT = ? WHERE ID = ?", due, earlier.id());
            database.jdbc.update("UPDATE TASK_SCHEDULE SET NEXT_FIRE_AT = ? WHERE ID = ?", due, later.id());
            raceScans(dispatcher);

            assertEquals(1, scheduled.size());
            assertEquals(1, countWhere(database.jdbc, "STATUS = 'STARTING'"));
            assertEquals(1, countWhere(database.jdbc, "REASON = 'SKIPPED_CONFLICT'"));
            assertTrue(sessions.active().isPresent());
            int rows = count(database.jdbc, "TASK_EXECUTION");
            clock.set(clock.millis() - 10_000L);
            dispatcher.scan();
            assertEquals(rows, count(database.jdbc, "TASK_EXECUTION"));
            assertEquals(1, scheduled.size());
        }
    }

    @Test
    void at12RestartMigratesInterruptsOpenWorkAndDoesNotCatchUp() throws Exception {
        ManualClock clock = new ManualClock(1_700_000_000_000L);
        String executionId;
        String scheduleId;
        long heartbeat = clock.millis() - 60_000L;
        try (Database database = Database.open()) {
            Services services = services(database, clock);
            executionId = insertOpen(database.jdbc, "RUNNING", heartbeat, 12_000L, 9_000L);
            ScheduleView schedule = interval(services, clock.millis() + 86_400_000L, "离线");
            scheduleId = schedule.id();
            database.jdbc.update("UPDATE TASK_SCHEDULE SET NEXT_FIRE_AT = ? WHERE ID = ?",
                    clock.millis() - 610_000L, scheduleId);
            database.reopen();

            DatabaseMigrator migrator = new DatabaseMigrator(database.dataSource);
            migrator.migrate();
            assertEquals(1, count(database.jdbc, "SCHEMA_VERSION"));
            ExecutionRecovery recovery = new ExecutionRecovery(database.jdbc, clock, migrator);
            assertEquals(1, recovery.recover());
            assertEquals("INTERRUPTED", text(database.jdbc, "STATUS", executionId));
            assertEquals(ExecutionRecovery.REASON, text(database.jdbc, "REASON", executionId));
            assertEquals(heartbeat, number(database.jdbc, "LAST_HEARTBEAT_AT", executionId));
            assertEquals(12_000L, number(database.jdbc, "ELAPSED_DURATION_MS", executionId));
            assertEquals(9_000L, number(database.jdbc, "ACTIVE_DURATION_MS", executionId));
            assertEquals(clock.millis(), number(database.jdbc, "ENDED_AT", executionId));

            Services restarted = services(database, clock);
            assertEquals("OFF", restarted.control.state().controlSwitch());
            assertFalse(restarted.control.state().emergencyLatched());
            AtomicInteger launches = new AtomicInteger();
            ScheduleDispatcher dispatcher = dispatcher(database, clock, restarted.templates, restarted.control,
                    new ResourceLoadManager(), (executionId1, snapshot) -> launches.incrementAndGet());
            dispatcher.scan();
            assertEquals(0, launches.get());
            assertEquals("SKIPPED_MISFIRE", textWhere(database.jdbc, "REASON", "SCHEDULE_ID = ?", scheduleId));
            Long nextFireAt = database.jdbc.queryForObject(
                    "SELECT NEXT_FIRE_AT FROM TASK_SCHEDULE WHERE ID = ?", Long.class, scheduleId);
            assertTrue(nextFireAt != null && nextFireAt > clock.millis());
            assertEquals(0, countWhere(database.jdbc,
                    "STATUS IN ('STARTING', 'RUNNING', 'PAUSING', 'PAUSED', 'STOPPING')"));

            database.jdbc.update("UPDATE SCHEMA_VERSION SET CHECKSUM = ? WHERE VERSION = 1", "tampered");
            IllegalStateException refused = assertThrows(IllegalStateException.class, migrator::migrate);
            assertTrue(refused.getMessage().contains("校验和"));
            assertEquals("INTERRUPTED", text(database.jdbc, "STATUS", executionId));
        }
    }

    @Test
    void at15WriteFailureAndLowDiskStopLoadRejectStartsAndShowFault() throws Exception {
        try (Database database = Database.open()) {
            ManualClock clock = new ManualClock(20_000L);
            StorageHealth storage = new StorageHealth();
            HistoryService history = new HistoryService(new HistoryRepository(database.jdbc, JSON), storage, clock);
            history.accept(sample(0));
            history.accept(sample(10_000L));
            assertEquals(1, count(database.jdbc, "RESOURCE_METRIC"));
            database.jdbc.execute("ALTER TABLE RESOURCE_METRIC RENAME TO RESOURCE_METRIC_HELD");
            history.accept(sample(20_000L));
            history.accept(sample(30_000L));
            assertEquals(1, count(database.jdbc, "RESOURCE_METRIC_HELD"));
            assertEquals("历史写入失败", storage.fault());

            ResourceLoadManager sessions = new ResourceLoadManager();
            ControlSwitch control = control(database, clock, storage, sessions);
            control.setEnabled(true, OPERATOR);
            SessionClaim claim = sessions.claim("exec-write-fault");
            sessions.markRunning("exec-write-fault");
            safety(storage, control, clock).evaluate(sample(20_000L));
            assertFalse(claim.permit().valid());
            assertTrue(control.state().emergencyLatched());
            assertEquals(SafetyLoop.STORAGE_REASON, control.state().latchReason());
            assertEquals("OFF", control.state().controlSwitch());
            Map<String, Object> info = systemInfo(storage, control, capableReport());
            assertEquals(Boolean.FALSE, info.get("storageHealthy"));
            assertEquals("历史写入失败", info.get("storageFault"));
            assertEquals(Boolean.FALSE, info.get("controlCapable"));
            assertTrue(((List<?>) info.get("controlDisabledReasons")).contains("历史写入失败"));

            ExecutionStartService starts = new ExecutionStartService(database.dataSource,
                    new DataSourceTransactionManager(database.dataSource), clock, control);
            int before = count(database.jdbc, "TASK_EXECUTION");
            ApiException rejected = assertThrows(ApiException.class,
                    () -> starts.start(UUID.randomUUID().toString(), CPU_CONFIG, start -> {
                        throw new AssertionError("写入失败后不应启动");
                    }));
            assertEquals("CONTROL_LATCHED", rejected.code());
            assertEquals(before, count(database.jdbc, "TASK_EXECUTION"));
        }
    }

    @Test
    void at15LowDiskShowsFaultAndEmergencyStopDoesNotWaitForDatabase() throws Exception {
        try (Database database = Database.open()) {
            ManualClock clock = new ManualClock(1_700_000_000_000L);
            StorageHealth storage = new StorageHealth();
            SrmProperties properties = new SrmProperties();
            CollectorLoop collector = new CollectorLoop(null, null, null, null, new LowDiskProbe(), storage,
                    null, null, properties);
            EnvironmentReport report = collector.environment();
            assertTrue(report.controlDisabledReasons().contains("数据盘剩余空间不足"));
            assertEquals("数据盘剩余空间不足", storage.fault());

            ResourceLoadManager sessions = new ResourceLoadManager();
            ControlSwitch control = control(database, clock, storage, sessions);
            control.setEnabled(true, OPERATOR);
            SessionClaim claim = sessions.claim("exec-disk");
            sessions.markRunning("exec-disk");
            safety(storage, control, clock).evaluate(sample(clock.millis()));
            assertFalse(claim.permit().valid());
            assertEquals(SafetyLoop.STORAGE_REASON, control.state().latchReason());
            Map<String, Object> info = new SystemController(
                    new ControlAvailability(collector::environment, storage), storage, properties,
                    new StandardEnvironment(), control).info().data();
            assertEquals(Boolean.FALSE, info.get("storageHealthy"));
            assertEquals("数据盘剩余空间不足", info.get("storageFault"));
            assertEquals(Boolean.FALSE, info.get("controlCapable"));
            assertTrue(((List<?>) info.get("controlDisabledReasons")).contains("数据盘剩余空间不足"));

            database.jdbc.update("DELETE FROM SYS_CONFIG");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            DatabaseMigrator migrator = new DatabaseMigrator(database.dataSource);
            JdbcLatchStore persisted = new JdbcLatchStore(database.jdbc, JSON, clock, migrator);
            LatchStore blocking = new LatchStore() {
                @Override
                public Optional<StoredLatch> load() {
                    return persisted.load();
                }

                @Override
                public void save(StoredLatch latch) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("急停写库测试未放行");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    persisted.save(latch);
                }
            };
            ResourceLoadManager emergencySessions = new ResourceLoadManager();
            ControlSwitch emergency = new ControlSwitch(emergencySessions, blocking, new LatchHealthWindow(),
                    new StorageHealth(), clock, ControlAudit.NONE);
            SessionClaim running = emergencySessions.claim("exec-emergency");
            emergencySessions.markRunning("exec-emergency");
            Thread stopper = new Thread(() -> emergency.emergencyStop(OPERATOR), "m4-emergency");
            stopper.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(running.permit().valid());
            assertTrue(emergency.state().emergencyLatched());
            assertEquals("OFF", emergency.state().controlSwitch());
            assertEquals(0, countWhere(database.jdbc, "CONFIG_KEY = 'emergency.latch'", "SYS_CONFIG"));
            assertTrue(stopper.isAlive());
            release.countDown();
            stopper.join(2_000);
            assertFalse(stopper.isAlive());
            assertEquals(1, countWhere(database.jdbc, "CONFIG_KEY = 'emergency.latch'", "SYS_CONFIG"));
        }
    }

    @Test
    void at20TemplateEditLeavesExecutionSnapshotAndReferencedDeleteConflicts() throws Exception {
        try (Database database = Database.open()) {
            ManualClock clock = new ManualClock(1_700_000_000_000L);
            Services services = services(database, clock);
            TaskTemplateView created = services.templates.create("""
                    {"name":"日间压测","description":"只压 CPU","resourceMode":"CPU","cpuTargetPct":30,"runMode":"FINITE","durationSeconds":60}
                    """, OPERATOR);
            services.control.setEnabled(true, OPERATOR);
            StartedExecution started = services.starts.start(UUID.randomUUID().toString(),
                    services.templates.runConfig(created.id()), start -> {
                    });
            String snapshot = text(database.jdbc, "CONFIG_SNAPSHOT_JSON", started.executionId());
            long version = number(database.jdbc, "VERSION", started.executionId());

            TaskTemplateView edited = services.templates.update(created.id(), """
                    {"name":"夜间压测","description":"已改模板","resourceMode":"CPU","cpuTargetPct":40,"runMode":"FINITE","durationSeconds":120,"expectedVersion":1}
                    """, OPERATOR);
            assertEquals("夜间压测", edited.name());
            assertEquals(40D, edited.cpuTargetPct());
            assertEquals(snapshot, text(database.jdbc, "CONFIG_SNAPSHOT_JSON", started.executionId()));
            assertEquals(version, number(database.jdbc, "VERSION", started.executionId()));
            assertEquals("STARTING", text(database.jdbc, "STATUS", started.executionId()));
            assertTrue(snapshot.contains("\"cpuTargetPct\":30"));

            ScheduleView schedule = services.schedules.create("""
                    {"taskId":"%s","type":"FIXED_INTERVAL","anchorAt":%d,"intervalSeconds":120,"enabled":true}
                    """.formatted(created.id(), clock.millis() + 86_400_000L), OPERATOR);
            ApiException conflict = assertThrows(ApiException.class,
                    () -> services.templates.delete(created.id(), OPERATOR));
            assertEquals("TEMPLATE_REFERENCED", conflict.code());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> references = (List<Map<String, Object>>) conflict.details().get("schedules");
            assertEquals(schedule.id(), references.get(0).get("id"));
            assertNullDeleted(database.jdbc, created.id());
            assertEquals(snapshot, text(database.jdbc, "CONFIG_SNAPSHOT_JSON", started.executionId()));
        }
    }

    private static int raceSameScheduleFire(JdbcTemplate jdbc) throws Exception {
        AtomicInteger inserted = new AtomicInteger();
        race(index -> {
            try {
                jdbc.update("""
                        INSERT INTO TASK_EXECUTION (
                            ID, SCHEDULE_ID, SCHEDULED_AT, CONFIG_SNAPSHOT_JSON, STATUS, VERSION
                        ) VALUES (?, 'same-fire', ?, '{}', 'SKIPPED', 1)
                        """, UUID.randomUUID().toString(), 1_700_000_000_000L);
                inserted.incrementAndGet();
            } catch (DataIntegrityViolationException ignored) {
                // 同一个计划触发点只能有一行。
            }
        });
        return inserted.get();
    }

    private static List<String> raceStarts(ExecutionStartService starts, String key, AtomicInteger launches)
            throws Exception {
        List<String> ids = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        race(index -> {
            StartedExecution started = starts.start(key, CPU_CONFIG, start -> launches.incrementAndGet());
            synchronized (ids) {
                ids.add(started.executionId());
            }
        }, errors);
        assertTrue(errors.isEmpty(), String.valueOf(errors));
        return ids;
    }

    private static void raceScans(ScheduleDispatcher dispatcher) throws Exception {
        race(index -> dispatcher.scan());
    }

    private static void race(Attempt attempt) throws Exception {
        race(attempt, null);
    }

    private static void race(Attempt attempt, List<Throwable> errors) throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                int current = index;
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        attempt.run(current);
                    } catch (RuntimeException exception) {
                        if (errors == null) {
                            throw exception;
                        }
                        synchronized (errors) {
                            errors.add(exception);
                        }
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(15, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static ScheduleView interval(Services services, long anchor, String name) {
        TaskTemplateView task = services.templates.create("""
                {"name":"%s","resourceMode":"CPU","cpuTargetPct":30,"runMode":"FINITE","durationSeconds":60}
                """.formatted(name), OPERATOR);
        return services.schedules.create("""
                {"taskId":"%s","type":"FIXED_INTERVAL","anchorAt":%d,"intervalSeconds":60,"enabled":true}
                """.formatted(task.id(), anchor), OPERATOR);
    }

    private static String insertOpen(JdbcTemplate jdbc, String status, long heartbeat, long elapsed, long active) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO TASK_EXECUTION (
                    ID, CONFIG_SNAPSHOT_JSON, STATUS, VERSION, STARTED_AT, LAST_HEARTBEAT_AT,
                    ELAPSED_DURATION_MS, ACTIVE_DURATION_MS
                ) VALUES (?, ?, ?, 2, ?, ?, ?, ?)
                """, id, CPU_CONFIG, status, heartbeat - elapsed, heartbeat, elapsed, active);
        return id;
    }

    private static Services services(Database database, ManualClock clock) {
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(database.dataSource);
        OperationLogRepository logs = new OperationLogRepository(database.jdbc);
        TaskTemplateService templates = new TaskTemplateService(database.jdbc, transactions, logs, clock);
        ScheduleService schedules = new ScheduleService(database.jdbc, transactions, logs, clock);
        StorageHealth storage = new StorageHealth();
        ResourceLoadManager sessions = new ResourceLoadManager();
        ControlSwitch control = control(database, clock, storage, sessions);
        ExecutionStartService starts = new ExecutionStartService(database.dataSource, transactions, clock, control);
        return new Services(templates, schedules, control, starts);
    }

    private static ControlSwitch control(Database database, ManualClock clock, StorageHealth storage,
                                         ResourceLoadManager sessions) {
        DatabaseMigrator migrator = new DatabaseMigrator(database.dataSource);
        return new ControlSwitch(sessions, new JdbcLatchStore(database.jdbc, JSON, clock, migrator),
                new LatchHealthWindow(), storage, clock, ControlAudit.NONE);
    }

    private static ScheduleDispatcher dispatcher(Database database, ManualClock clock, TaskTemplateService templates,
                                                 ControlSwitch control, ResourceLoadManager sessions,
                                                 com.szsn.srm.task.ScheduleLauncher launcher) {
        return new ScheduleDispatcher(database.jdbc, new DataSourceTransactionManager(database.dataSource),
                templates, new LiveScheduleAdmission(control, sessions), launcher, clock);
    }

    private static SafetyLoop safety(StorageHealth storage, ControlSwitch control, ManualClock clock) {
        return new SafetyLoop(new com.szsn.srm.monitor.SnapshotStore(), control, new LatchHealthWindow(),
                storage, clock);
    }

    private static Map<String, Object> systemInfo(StorageHealth storage, ControlSwitch control, EnvironmentReport report) {
        SrmProperties properties = new SrmProperties();
        ControlAvailability availability = new ControlAvailability(() -> report, storage);
        return new SystemController(availability, storage, properties, new StandardEnvironment(), control).info().data();
    }

    private static EnvironmentReport capableReport() {
        return new EnvironmentReport("host", "Linux", "1", "1", "cpu", 4, "17", 8L << 30, "none",
                true, List.of(), 100L << 30, 0.5);
    }

    private static MetricSnapshot sample(long observedAt) {
        return new MetricSnapshot(
                1,
                "boot",
                observedAt,
                0,
                Quality.VALID,
                List.of(),
                true,
                new MetricSnapshot.HostView("h", "Linux", "1", "1", "cpu", 4, "17", 1L, 1L),
                new MetricSnapshot.CpuView(10D, null, null, null, null, null, null, null, null),
                new MetricSnapshot.MemoryView(8L << 30, 4L << 30, 4L << 30, null, null, null),
                new MetricSnapshot.JvmView(null, null, null, null, null, null, null, null, null),
                List.of(),
                List.of(),
                new MetricSnapshot.NetworkView(0D, 0D, 0L, 0L, List.of()));
    }

    private static void assertNullDeleted(JdbcTemplate jdbc, String taskId) {
        Long deletedAt = jdbc.queryForObject("SELECT DELETED_AT FROM RESOURCE_TASK WHERE ID = ?", Long.class, taskId);
        assertEquals(null, deletedAt);
    }

    private static int count(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static int countWhere(JdbcTemplate jdbc, String where) {
        return countWhere(jdbc, where, "TASK_EXECUTION");
    }

    private static int countWhere(JdbcTemplate jdbc, String where, String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
        return count == null ? 0 : count;
    }

    private static String text(JdbcTemplate jdbc, String column, String id) {
        return jdbc.queryForObject("SELECT " + column + " FROM TASK_EXECUTION WHERE ID = ?", String.class, id);
    }

    private static String textWhere(JdbcTemplate jdbc, String column, String where, Object argument) {
        return jdbc.queryForObject("SELECT " + column + " FROM TASK_EXECUTION WHERE " + where, String.class, argument);
    }

    private static long number(JdbcTemplate jdbc, String column, String id) {
        Long value = jdbc.queryForObject("SELECT " + column + " FROM TASK_EXECUTION WHERE ID = ?", Long.class, id);
        return value == null ? -1L : value;
    }

    private record Services(TaskTemplateService templates, ScheduleService schedules, ControlSwitch control,
                            ExecutionStartService starts) {
    }

    @FunctionalInterface
    private interface Attempt {
        void run(int index) throws Exception;
    }

    private static final class LowDiskProbe extends EnvironmentProbe {
        private LowDiskProbe() {
            super(null);
        }

        @Override
        public EnvironmentReport probe(Path dataDir) {
            return new EnvironmentReport("host", "Linux", "1", "1", "cpu", 4, "17", 8L << 30, "none",
                    false, List.of("数据盘剩余空间不足"), 1024L, 0.01);
        }
    }

    private static final class Database implements AutoCloseable {
        private final Path directory;
        private final String url;
        private SimpleDriverDataSource dataSource;
        private JdbcTemplate jdbc;

        private Database(Path directory, String url) {
            this.directory = directory;
            this.url = url;
        }

        private static Database open() throws IOException {
            Path directory = Path.of("target", "m4-h2-" + UUID.randomUUID().toString().replace("-", ""));
            Files.createDirectories(directory);
            Path file = directory.resolve("keeper").toAbsolutePath();
            String url = "jdbc:h2:file:" + file.toString().replace('\\', '/')
                    + ";MODE=REGULAR;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE";
            Database database = new Database(directory, url);
            database.connect();
            try {
                new DatabaseMigrator(database.dataSource).migrate();
            } catch (IOException exception) {
                database.close();
                throw exception;
            }
            return database;
        }

        private void reopen() throws IOException {
            shutdown();
            connect();
        }

        private void connect() {
            SimpleDriverDataSource source = new SimpleDriverDataSource();
            source.setDriverClass(org.h2.Driver.class);
            source.setUrl(url);
            source.setUsername("sa");
            source.setPassword("");
            dataSource = source;
            jdbc = new JdbcTemplate(source);
        }

        private void shutdown() {
            try {
                if (jdbc != null) {
                    jdbc.execute("SHUTDOWN");
                }
            } catch (RuntimeException ignored) {
                // SHUTDOWN 会关掉当前连接。
            }
        }

        @Override
        public void close() {
            shutdown();
            delete(directory);
        }

        private static void delete(Path root) {
            if (root == null || !Files.exists(root)) {
                return;
            }
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // 测试进程结束后由 target 清理。
                    }
                });
            } catch (IOException ignored) {
                // 同上。
            }
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
