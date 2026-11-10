package com.szsn.srm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.szsn.srm.common.ApiException;
import com.szsn.srm.db.DatabaseMigrator;
import com.szsn.srm.monitor.MetricSnapshot;
import com.szsn.srm.monitor.Quality;
import com.szsn.srm.monitor.SnapshotStore;
import com.szsn.srm.safety.LatchHealthWindow;
import com.szsn.srm.safety.SafetyLoop;
import com.szsn.srm.safety.SafetySignal;
import com.szsn.srm.system.ControlAvailability;
import com.szsn.srm.system.EnvironmentReport;
import com.szsn.srm.system.StorageHealth;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * 用瞬时燃烧器覆盖资源页 CPU 受理语义，不占满开发机。
 */
class CpuControlServiceTest {
    private CpuEngine engine;
    private CpuControlService service;

    @AfterEach
    void tearDown() throws Exception {
        if (service != null) {
            service.destroy();
        }
        if (engine != null) {
            engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS);
        }
    }

    @Test
    void startAcceptedWithoutAssumingEngineCompletedAndStatusExposesCpuFields() throws Exception {
        Harness harness = harness(true);
        engine = harness.engine;
        service = harness.service;
        harness.switchOn();
        fillMonitor(harness.store, harness.clock, 12);

        Map<String, Object> accepted = harness.service.start(UUID.randomUUID().toString(), cpuJson(35));
        assertEquals("STARTING", accepted.get("status"));
        assertEquals(Boolean.FALSE, accepted.get("engineCompleted"));
        assertTrue(accepted.get("executionId") instanceof String);

        Map<String, Object> cpu = harness.service.cpuStatus();
        assertEquals(35.0, ((Number) cpu.get("targetPct")).doubleValue(), 0.001);
        assertEquals(4, cpu.get("workerCount"));
        assertTrue(cpu.containsKey("duty"));
        assertTrue(cpu.containsKey("actualPct"));
        assertTrue(cpu.containsKey("limitedReasons"));

        harness.sessions.requestStop((String) accepted.get("executionId"), StopKind.USER);
        assertTrue(engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS));
    }

    @Test
    void memoryOverBudgetReturns422WithoutSideEffects() throws Exception {
        Harness harness = harness(true);
        engine = harness.engine;
        service = harness.service;
        harness.switchOn();
        fillMonitor(harness.store, harness.clock, 12);
        long heldBefore = harness.memory.heldBytes();
        ApiException exception = assertThrows(ApiException.class,
                () -> harness.service.start(UUID.randomUUID().toString(), """
                        {"resourceMode":"MEMORY","memoryMode":"EXTRA_BYTES","memoryTargetBytes":1073741824,"runMode":"CONTINUOUS"}
                        """));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.status());
        assertEquals(MemoryBudgetGate.BUDGET_EXCEEDED, exception.code());
        assertEquals(heldBefore, harness.memory.heldBytes());
        assertFalse(harness.sessions.active().isPresent());
    }

    @Test
    void memoryWithinBudgetStillUnavailableWithoutSideEffects() throws Exception {
        Harness harness = harness(true);
        engine = harness.engine;
        service = harness.service;
        harness.switchOn();
        fillAmpleMemoryMonitor(harness.store, harness.clock, 12);
        long heldBefore = harness.memory.heldBytes();
        ApiException exception = assertThrows(ApiException.class,
                () -> harness.service.start(UUID.randomUUID().toString(), """
                        {"resourceMode":"MEMORY","memoryMode":"EXTRA_BYTES","memoryTargetBytes":4194304,"runMode":"CONTINUOUS"}
                        """));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.status());
        assertEquals("CONTROL_UNAVAILABLE", exception.code());
        assertEquals(heldBefore, harness.memory.heldBytes());
        assertFalse(harness.sessions.active().isPresent());
    }

    @Test
    void memoryPreflightExposesBudgetWithoutSideEffect() throws Exception {
        Harness harness = harness(true);
        engine = harness.engine;
        service = harness.service;
        fillAmpleMemoryMonitor(harness.store, harness.clock, 12);
        Map<String, Object> result = harness.service.preflight("""
                {"resourceMode":"MEMORY","memoryMode":"EXTRA_BYTES","memoryTargetBytes":4194304,"runMode":"CONTINUOUS"}
                """);
        assertEquals(Boolean.FALSE, result.get("sideEffect"));
        assertEquals(Boolean.FALSE, result.get("ok"));
        assertTrue(result.get("budget") instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> budget = (Map<String, Object>) result.get("budget");
        assertTrue(((Number) budget.get("additionalBytes")).longValue() >= 4L * 1024 * 1024);
        assertFalse(harness.sessions.active().isPresent());
        assertEquals(0, harness.memory.heldBytes());
    }

    @Test
    void incapableEnvironmentRejectsStart() throws Exception {
        Harness harness = harness(false);
        engine = harness.engine;
        service = harness.service;
        harness.switchOn();
        fillMonitor(harness.store, harness.clock, 12);
        ApiException exception = assertThrows(ApiException.class,
                () -> harness.service.start(UUID.randomUUID().toString(), cpuJson(30)));
        assertEquals("CONTROL_UNAVAILABLE", exception.code());
        assertFalse(harness.sessions.active().isPresent());
    }

    @Test
    void preflightHasNoSideEffect() throws Exception {
        Harness harness = harness(true);
        engine = harness.engine;
        service = harness.service;
        Map<String, Object> result = harness.service.preflight(cpuJson(40));
        assertEquals(Boolean.FALSE, result.get("sideEffect"));
        assertEquals("CPU", result.get("resourceMode"));
        assertFalse(harness.sessions.active().isPresent());
    }

    private static String cpuJson(double target) {
        return "{\"resourceMode\":\"CPU\",\"cpuTargetPct\":" + target + ",\"runMode\":\"CONTINUOUS\"}";
    }

    private static void fillMonitor(SnapshotStore store, MutableClock clock, int seconds) {
        long base = clock.millis();
        for (int i = 0; i < seconds; i++) {
            clock.setMillis(base + i * 1000L);
            store.add(sample(i + 1, clock.millis(), 8.0, false));
        }
    }

    private static void fillAmpleMemoryMonitor(SnapshotStore store, MutableClock clock, int seconds) {
        long base = clock.millis();
        for (int i = 0; i < seconds; i++) {
            clock.setMillis(base + i * 1000L);
            store.add(sample(i + 1, clock.millis(), 8.0, true));
        }
    }

    private static MetricSnapshot sample(long sequence, long observedAt, double busy, boolean ampleHeap) {
        long heapMax = ampleHeap ? (1L << 30) : (4L << 20);
        long heapUsed = ampleHeap ? (200L << 20) : (1L << 20);
        long heapCommitted = ampleHeap ? (400L << 20) : (2L << 20);
        return new MetricSnapshot(
                sequence,
                "boot",
                observedAt,
                0,
                Quality.VALID,
                List.of(),
                true,
                new MetricSnapshot.HostView("h", "Linux", "1", "1", "cpu", 4, "17", 1L, 1L),
                new MetricSnapshot.CpuView(busy, 1D, 1D, 90D, 0D, 0D, 0.1, 0.1, 0.1),
                new MetricSnapshot.MemoryView(8L << 30, 4L << 30, 4L << 30, 1L << 30, 0L, 0L),
                new MetricSnapshot.JvmView(heapUsed, heapCommitted, heapMax, 1L << 20, 1L, 1L, 10, 1L << 20, 1D),
                List.of(),
                List.of(),
                new MetricSnapshot.NetworkView(0D, 0D, 0L, 0L, List.of()));
    }

    private Harness harness(boolean capable) throws Exception {
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        ResourceLoadManager sessions = new ResourceLoadManager();
        SnapshotStore store = new SnapshotStore();
        StorageHealth storage = new StorageHealth();
        LatchStore latchStore = new MemoryLatchStore();
        LatchHealthWindow health = new LatchHealthWindow();
        ControlSwitch controlSwitch = new ControlSwitch(sessions, latchStore, health, storage, clock, ControlAudit.NONE);
        SafetyLoop safety = new FixedSafetyLoop(store, controlSwitch, health, storage, clock);
        EnvironmentReport report = capable
                ? new EnvironmentReport("h", "Linux", "1", "1", "cpu", 4, "17", 8L << 30, "none", true, List.of(), 1L << 30, 0.5)
                : new EnvironmentReport("h", "Windows", "10", "10", "cpu", 4, "17", 8L << 30, "unsupported", false,
                        List.of("当前系统不是 Linux，负载控制只允许在无额外配额的 Linux 虚拟机内启用"), 1L << 30, 0.5);
        ControlAvailability availability = new ControlAvailability(() -> report, storage);
        DataSource dataSource = memoryDb();
        ExecutionStartService starts = new ExecutionStartService(dataSource, new DataSourceTransactionManager(dataSource),
                clock, controlSwitch);
        CpuEngine created = new CpuEngine(sessions, () -> {
            MetricSnapshot latest = store.latest();
            return latest == null ? null : latest.cpu().overallBusyPct();
        }, safety::current, (nanos, permit, allowed) -> 1L);
        FakeMemoryAllocator memory = new FakeMemoryAllocator();
        MemorySampleSource samples = () -> {
            MetricSnapshot latest = store.latest();
            if (latest == null || latest.memory() == null || latest.jvm() == null) {
                return null;
            }
            return new MemorySample(
                    latest.memory().totalBytes(),
                    latest.memory().availableBytes(),
                    latest.jvm().heapMaxBytes(),
                    latest.jvm().heapUsedBytes(),
                    Math.max(0, clock.millis() - latest.observedAt()));
        };
        MemoryBudgetGate budgetGate = new MemoryBudgetGate(memory, samples, com.szsn.srm.common.Units.DEFAULT_TASK_CAP);
        ChunkedMemoryGrower grower = new ChunkedMemoryGrower(
                memory, budgetGate, samples, () -> false, System::nanoTime, nanos -> {
                }, held -> {
                });
        MemoryEngine memoryEngine = new MemoryEngine(sessions, memory, budgetGate, samples, grower, safety::current, clock::millis);
        CpuControlService createdService = new CpuControlService(availability, controlSwitch, sessions, starts, created,
                memoryEngine, budgetGate, store, safety, storage, clock);
        return new Harness(createdService, created, sessions, store, clock, controlSwitch, memory);
    }

    private static DataSource memoryDb() throws Exception {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.h2.Driver.class);
        dataSource.setUrl("jdbc:h2:mem:cpu-control-" + UUID.randomUUID() + ";MODE=REGULAR;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        new DatabaseMigrator(dataSource).migrate();
        return dataSource;
    }

    private record Harness(CpuControlService service, CpuEngine engine, ResourceLoadManager sessions, SnapshotStore store,
                           MutableClock clock, ControlSwitch controlSwitch, FakeMemoryAllocator memory) {
        void switchOn() {
            controlSwitch.setEnabled(true, Operator.system());
        }
    }

    private static final class MemoryLatchStore implements LatchStore {
        private StoredLatch latch;

        @Override
        public Optional<StoredLatch> load() {
            return Optional.ofNullable(latch);
        }

        @Override
        public void save(StoredLatch value) {
            latch = value;
        }
    }

    private static final class FixedSafetyLoop extends SafetyLoop {
        private final SnapshotStore store;
        private final Clock clock;

        private FixedSafetyLoop(SnapshotStore store, ControlSwitch controlSwitch, LatchHealthWindow health,
                                StorageHealth storage, Clock clock) {
            super(store, controlSwitch, health, storage, clock);
            this.store = store;
            this.clock = clock;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public SafetySignal current() {
            MetricSnapshot latest = store.latest();
            long now = clock.millis();
            long age = latest == null ? Long.MAX_VALUE : Math.max(0, now - latest.observedAt());
            return new SafetySignal(
                    now,
                    latest == null ? null : latest.sequence(),
                    age,
                    latest == null ? Quality.UNAVAILABLE : latest.quality(),
                    true,
                    null,
                    false,
                    false,
                    false,
                    false,
                    false,
                    List.of());
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(long epochMs) {
            this.instant = new AtomicReference<>(Instant.ofEpochMilli(epochMs));
        }

        void setMillis(long epochMs) {
            instant.set(Instant.ofEpochMilli(epochMs));
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
