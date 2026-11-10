package com.szsn.srm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.Units;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * M3 验收：AT-05 / AT-06 / AT-07 / AT-08。
 * 使用假分配器与可注入时钟，不分配真实 {@code byte[]}，不占满开发机 CPU/内存。
 * 有限容量真实分配仅在获准环境执行（目标环境待验收）。
 */
class M3AcceptanceTest {
    private static final long TARGET_256_MIB = 256L * Units.MIB;

    private CpuEngine cpuEngine;
    private MemoryEngine memoryEngine;
    private ResourceLoadManager sessions;
    private CpuControlService controlService;

    @AfterEach
    void tearDown() throws Exception {
        if (controlService != null) {
            controlService.destroy();
            controlService = null;
        }
        if (sessions != null) {
            sessions.emergencyStop();
        }
        if (cpuEngine != null) {
            cpuEngine.awaitStop(CpuEngine.STOP_TIMEOUT_MS);
            cpuEngine = null;
        }
        if (memoryEngine != null) {
            memoryEngine.detach();
            memoryEngine = null;
        }
    }

    @Test
    void at05BudgetAllows256MibAtRateAndPageSeparatesHeldHeapRss() throws Exception {
        FakeMemoryAllocator allocator = new FakeMemoryAllocator();
        AtomicReference<MemorySample> sample = new AtomicReference<>(ample(16L * Units.GIB, 14L * Units.GIB));
        MemoryBudgetGate gate = new MemoryBudgetGate(allocator, sample::get, Units.DEFAULT_TASK_CAP);
        AtomicLong now = new AtomicLong();
        List<Long> sleeps = new ArrayList<>();
        ChunkedMemoryGrower grower = new ChunkedMemoryGrower(
                allocator,
                gate,
                sample::get,
                () -> false,
                now::get,
                nanos -> {
                    sleeps.add(nanos);
                    now.addAndGet(nanos);
                },
                held -> {
                });
        ResourceLoadManager rateSessions = new ResourceLoadManager();
        SessionClaim claim = rateSessions.claim("at05");
        ChunkedMemoryGrower.GrowResult grown = grower.growToward(TARGET_256_MIB, claim.permit());

        assertEquals(ChunkedMemoryGrower.StopReason.COMPLETED, grown.reason());
        assertEquals(TARGET_256_MIB, grown.heldBytes());
        assertEquals(TARGET_256_MIB, allocator.heldBytes());
        long chunks = TARGET_256_MIB / Units.BLOCK;
        assertEquals(chunks - 1, sleeps.size());
        for (long sleep : sleeps) {
            assertEquals(ChunkedMemoryGrower.CHUNK_INTERVAL_NANOS, sleep);
        }
        // 块间限速：每块间隔对应 32MiB/s；首块立即分配，不计入间隔
        assertEquals(Units.BLOCK * 1_000_000_000L / ChunkedMemoryGrower.CHUNK_INTERVAL_NANOS,
                ChunkedMemoryGrower.RATE_BYTES_PER_SEC);
        rateSessions.emergencyStop();

        ControlHarness harness = controlHarness(true);
        cpuEngine = harness.cpu;
        memoryEngine = harness.memory;
        controlService = harness.service;
        sessions = harness.sessions;
        fillAmpleMonitor(harness.store, harness.clock, 12);
        Map<String, Object> view = harness.service.memoryLoadView();
        assertTrue(view.containsKey("heldBytes"));
        assertTrue(view.containsKey("heapUsedBytes"));
        assertTrue(view.containsKey("rssBytes"));
        assertNotNull(view.get("heapUsedBytes"));
        assertNotNull(view.get("rssBytes"));
        // 三项口径独立：堆与 RSS 来自快照，负载持有来自分配器，不得互相冒充
        assertNotEquals(view.get("heapUsedBytes"), view.get("rssBytes"));
        assertEquals(0L, ((Number) view.get("heldBytes")).longValue());
    }

    @Test
    void at06RejectsNegativeOverflowMutexAndOverBudgetWithoutSideEffects() throws Exception {
        // 负值 / 溢出 / 互斥字段
        assertInvalid("""
                {"resourceMode":"MEMORY","memoryMode":"EXTRA_BYTES","memoryTargetBytes":-1,"runMode":"CONTINUOUS"}
                """);
        assertInvalid("""
                {"resourceMode":"MEMORY","memoryMode":"EXTRA_BYTES",\
                "memoryTargetBytes":99999999999999999999,"runMode":"CONTINUOUS"}
                """);
        assertInvalid("""
                {"resourceMode":"MEMORY","memoryMode":"EXTRA_BYTES",\
                "memoryTargetBytes":4194304,"memoryTargetPct":40,"runMode":"CONTINUOUS"}
                """);
        assertInvalid("""
                {"resourceMode":"MEMORY","memoryMode":"SYSTEM_PERCENT",\
                "memoryTargetPct":40,"memoryTargetBytes":4194304,"runMode":"CONTINUOUS"}
                """);
        assertInvalid("""
                {"resourceMode":"CPU","cpuTargetPct":30,"memoryMode":"EXTRA_BYTES",\
                "memoryTargetBytes":4194304,"runMode":"CONTINUOUS"}
                """);
        assertInvalid("""
                {"resourceMode":"MEMORY","memoryMode":"EXTRA_BYTES","memoryTargetBytes":4194304,\
                "cpuTargetPct":30,"runMode":"CONTINUOUS"}
                """);
        assertInvalid("""
                {"resourceMode":"MEMORY","memoryMode":"SYSTEM_PERCENT","memoryTargetPct":120,\
                "runMode":"CONTINUOUS"}
                """);
        assertInvalid("""
                {"resourceMode":"MEMORY","memoryMode":"EXTRA_BYTES","memoryTargetBytes":4194304,\
                "runMode":"CONTINUOUS","durationSeconds":60}
                """);

        ControlHarness harness = controlHarness(true);
        cpuEngine = harness.cpu;
        memoryEngine = harness.memory;
        controlService = harness.service;
        sessions = harness.sessions;
        harness.switchOn();
        fillAmpleMonitor(harness.store, harness.clock, 12);
        long heldBefore = harness.allocator.heldBytes();

        ApiException overBudget = assertThrows(ApiException.class,
                () -> harness.service.start(UUID.randomUUID().toString(), """
                        {"resourceMode":"COMBINED","cpuTargetPct":30,"memoryMode":"EXTRA_BYTES",\
                        "memoryTargetBytes":1073741824,"runMode":"CONTINUOUS"}
                        """));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, overBudget.status());
        assertEquals(MemoryBudgetGate.BUDGET_EXCEEDED, overBudget.code());
        assertEquals(heldBefore, harness.allocator.heldBytes());
        assertFalse(harness.sessions.active().isPresent());
        assertNull(harness.cpu.view().executionId());
        assertNull(harness.memory.view().executionId());
    }

    @Test
    void at07UnreachableSystemPercentStopsAtBudgetAndNeverBreaksHardCap() {
        sessions = new ResourceLoadManager();
        FakeMemoryAllocator allocator = new FakeMemoryAllocator();
        // used≈10%，目标 50% 需约 3.2GiB；堆预算把可新增压到远低于缺口
        long total = 8L * Units.GIB;
        long available = total * 90 / 100;
        long heapMax = 512L * Units.MIB;
        long heapUsed = 200L * Units.MIB;
        AtomicReference<MemorySample> sample =
                new AtomicReference<>(new MemorySample(total, available, heapMax, heapUsed, 0L));
        MemoryBudgetGate gate = new MemoryBudgetGate(allocator, sample::get, Units.DEFAULT_TASK_CAP);
        AtomicReference<SafetySignal> safety = new AtomicReference<>(healthy());
        AtomicLong clock = new AtomicLong(2_000_000L);
        ChunkedMemoryGrower grower = new ChunkedMemoryGrower(
                allocator,
                gate,
                sample::get,
                () -> safety.get().forbidMemoryGrowth(),
                () -> clock.get() * 1_000_000L,
                nanos -> clock.addAndGet(Math.max(1L, nanos / 1_000_000L)),
                held -> {
                });
        memoryEngine = new MemoryEngine(sessions, allocator, gate, sample::get, grower, safety::get, clock::get);

        SessionClaim claim = sessions.claim("at07");
        memoryEngine.startSystemPercent(claim, 50);
        long hardCap = Units.DEFAULT_TASK_CAP;
        long peak = 0L;
        for (int i = 0; i < 40; i++) {
            MemoryEngineView view = memoryEngine.controlTick();
            long held = allocator.heldBytes();
            peak = Math.max(peak, held);
            assertTrue(held <= hardCap, "held=" + held);
            if (i == 0) {
                assertTrue(view.targetLimited() || view.limitedReasons().contains(SystemPercentPlanner.TARGET_LIMITED));
            }
            assertTrue(view.limitedReasons().contains(SystemPercentPlanner.TARGET_LIMITED)
                    || held == 0
                    || held < 3L * Units.GIB);
            clock.addAndGet(2_000L);
        }
        assertTrue(peak <= hardCap);
        assertTrue(peak < 3L * Units.GIB);
        MemoryEngineView last = memoryEngine.view();
        assertTrue(last.limitedReasons().contains(SystemPercentPlanner.TARGET_LIMITED)
                || last.targetLimited());
    }

    @Test
    void at08PauseClearsLoadKeepsSlotResumeReChecksAndNoInstantFullLoad() throws Exception {
        ControlHarness harness = controlHarness(true);
        cpuEngine = harness.cpu;
        memoryEngine = harness.memory;
        controlService = harness.service;
        sessions = harness.sessions;
        harness.switchOn();
        fillAmpleMonitor(harness.store, harness.clock, 12);

        Map<String, Object> started = harness.service.start(UUID.randomUUID().toString(), """
                {"resourceMode":"COMBINED","cpuTargetPct":40,"memoryMode":"EXTRA_BYTES",\
                "memoryTargetBytes":16777216,"runMode":"CONTINUOUS"}
                """);
        String executionId = (String) started.get("executionId");
        assertNotNull(executionId);

        harness.memory.controlTick();
        harness.cpu.controlTick();
        assertEquals(16L * Units.MIB, harness.allocator.heldBytes());
        assertTrue(harness.cpu.view().duty() > 0);

        Map<String, Object> paused = harness.service.pause(executionId);
        assertEquals("PAUSED", paused.get("status"));
        assertEquals(0L, harness.allocator.heldBytes());
        assertEquals(0D, harness.cpu.view().duty(), 1e-12);
        assertEquals(ExecutionStatus.PAUSED, harness.sessions.active().orElseThrow().status());

        harness.cpu.controlTick();
        harness.memory.controlTick();
        assertEquals(0D, harness.cpu.view().duty(), 1e-12);
        assertEquals(0L, harness.allocator.heldBytes());

        harness.availability.deny();
        ApiException denied = assertThrows(ApiException.class, () -> harness.service.resume(executionId));
        assertEquals("CONTROL_UNAVAILABLE", denied.code());
        assertEquals(ExecutionStatus.PAUSED, harness.sessions.active().orElseThrow().status());
        assertEquals(0L, harness.allocator.heldBytes());

        harness.availability.clearDeny();
        Map<String, Object> resumed = harness.service.resume(executionId);
        assertEquals("RUNNING", resumed.get("status"));
        assertEquals(0D, harness.cpu.view().duty(), 1e-12);
        assertEquals(0L, harness.allocator.heldBytes());
        double firstDuty = harness.cpu.controlTick().duty();
        assertTrue(firstDuty > 0 && firstDuty <= 0.05, "duty=" + firstDuty);
        harness.memory.controlTick();
        assertEquals(16L * Units.MIB, harness.allocator.heldBytes());

        harness.service.stop(executionId);
        assertTrue(harness.cpu.awaitStop(CpuEngine.STOP_TIMEOUT_MS));
        harness.memory.stopAndClear(MemoryEngine.CLEAR_TIMEOUT_MS, false);
    }

    @Test
    void at08PauseDoesNotExtendFiniteDeadline() throws Exception {
        ControlHarness harness = controlHarness(true);
        cpuEngine = harness.cpu;
        memoryEngine = harness.memory;
        controlService = harness.service;
        sessions = harness.sessions;
        harness.switchOn();
        fillAmpleMonitor(harness.store, harness.clock, 12);

        long durationSeconds = 2L;
        long wallStart = System.nanoTime();
        Map<String, Object> started = harness.service.start(UUID.randomUUID().toString(), """
                {"resourceMode":"COMBINED","cpuTargetPct":30,"memoryMode":"EXTRA_BYTES",\
                "memoryTargetBytes":4194304,"runMode":"FINITE","durationSeconds":%d}
                """.formatted(durationSeconds));
        String executionId = (String) started.get("executionId");
        harness.service.pause(executionId);
        assertEquals(ExecutionStatus.PAUSED, harness.sessions.active().orElseThrow().status());
        assertEquals(0L, harness.allocator.heldBytes());

        long waitMs = durationSeconds * 1000L + 1_200L
                - (System.nanoTime() - wallStart) / 1_000_000L;
        if (waitMs > 0) {
            Thread.sleep(Math.min(waitMs, 5_000L));
        }
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline && harness.sessions.active().isPresent()) {
            Thread.sleep(50L);
        }
        assertTrue(harness.sessions.active().isEmpty(), "暂停不得延长到期点");
        SessionView finished = harness.sessions.lastFinished().orElseThrow();
        assertEquals(executionId, finished.executionId());
        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertEquals(0L, harness.allocator.heldBytes());
    }

    private static void assertInvalid(String json) {
        ApiException exception = assertThrows(ApiException.class,
                () -> StartConfig.prepare(UUID.randomUUID().toString(), json));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.status());
        assertEquals("TARGET_INVALID", exception.code());
    }

    private ControlHarness controlHarness(boolean capable) throws Exception {
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        ResourceLoadManager manager = new ResourceLoadManager();
        SnapshotStore store = new SnapshotStore();
        StorageHealth storage = new StorageHealth();
        LatchStore latchStore = new MemoryLatchStore();
        LatchHealthWindow health = new LatchHealthWindow();
        ControlSwitch controlSwitch = new ControlSwitch(manager, latchStore, health, storage, clock, ControlAudit.NONE);
        SafetyLoop safety = new FixedSafetyLoop(store, controlSwitch, health, storage, clock);
        ToggleAvailability availability = new ToggleAvailability(capable, storage);
        DataSource dataSource = memoryDb();
        ExecutionStartService starts = new ExecutionStartService(dataSource, new DataSourceTransactionManager(dataSource),
                clock, controlSwitch);
        CpuEngine cpu = new CpuEngine(manager, () -> {
            MetricSnapshot latest = store.latest();
            return latest == null ? null : latest.cpu().overallBusyPct();
        }, safety::current, (nanos, permit, allowed) -> 1L);
        FakeMemoryAllocator allocator = new FakeMemoryAllocator();
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
        MemoryBudgetGate gate = new MemoryBudgetGate(allocator, samples, Units.DEFAULT_TASK_CAP);
        AtomicLong nano = new AtomicLong();
        ChunkedMemoryGrower grower = new ChunkedMemoryGrower(
                allocator,
                gate,
                samples,
                () -> false,
                nano::get,
                nanos -> nano.addAndGet(nanos),
                held -> {
                });
        MemoryEngine memory = new MemoryEngine(manager, allocator, gate, samples, grower, safety::current, clock::millis);
        CpuControlService service = new CpuControlService(availability, controlSwitch, manager, starts, cpu,
                memory, gate, store, safety, storage, clock);
        return new ControlHarness(service, cpu, memory, manager, store, clock, controlSwitch, allocator, availability);
    }

    private static void fillAmpleMonitor(SnapshotStore store, MutableClock clock, int seconds) {
        long base = clock.millis();
        for (int i = 0; i < seconds; i++) {
            clock.setMillis(base + i * 1000L);
            store.add(sample(i + 1, clock.millis()));
        }
    }

    private static MetricSnapshot sample(long sequence, long observedAt) {
        long heapUsed = 200L * Units.MIB;
        long heapCommitted = 400L * Units.MIB;
        long heapMax = Units.GIB;
        long rss = 512L * Units.MIB;
        return new MetricSnapshot(
                sequence,
                "boot",
                observedAt,
                0,
                Quality.VALID,
                List.of(),
                true,
                new MetricSnapshot.HostView("h", "Linux", "1", "1", "cpu", 4, "17", 1L, 1L),
                new MetricSnapshot.CpuView(8D, 1D, 1D, 90D, 0D, 0D, 0.1, 0.1, 0.1),
                new MetricSnapshot.MemoryView(16L * Units.GIB, 12L * Units.GIB, 12L * Units.GIB, Units.GIB, 0L, 0L),
                new MetricSnapshot.JvmView(heapUsed, heapCommitted, heapMax, Units.MIB, 1L, 1L, 10, rss, 1D),
                List.of(),
                List.of(),
                new MetricSnapshot.NetworkView(0D, 0D, 0L, 0L, List.of()));
    }

    private static MemorySample ample(long total, long available) {
        return new MemorySample(total, available, 2L * Units.GIB, 200L * Units.MIB, 0L);
    }

    private static SafetySignal healthy() {
        return new SafetySignal(
                0L, 1L, 0L, Quality.VALID, true, null,
                false, false, false, false, false, List.of());
    }

    private static DataSource memoryDb() throws Exception {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.h2.Driver.class);
        dataSource.setUrl("jdbc:h2:mem:m3-accept-" + UUID.randomUUID() + ";MODE=REGULAR;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        new DatabaseMigrator(dataSource).migrate();
        return dataSource;
    }

    private record ControlHarness(
            CpuControlService service,
            CpuEngine cpu,
            MemoryEngine memory,
            ResourceLoadManager sessions,
            SnapshotStore store,
            MutableClock clock,
            ControlSwitch controlSwitch,
            FakeMemoryAllocator allocator,
            ToggleAvailability availability) {
        void switchOn() {
            controlSwitch.setEnabled(true, Operator.system());
        }
    }

    private static final class ToggleAvailability extends ControlAvailability {
        private final AtomicReference<Boolean> deny = new AtomicReference<>(false);

        private ToggleAvailability(boolean capable, StorageHealth storage) {
            super(() -> capable
                            ? new EnvironmentReport("h", "Linux", "1", "1", "cpu", 4, "17", 8L << 30, "none", true,
                            List.of(), 1L << 30, 0.5)
                            : new EnvironmentReport("h", "Windows", "10", "10", "cpu", 4, "17", 8L << 30, "unsupported",
                            false, List.of("当前系统不是 Linux"), 1L << 30, 0.5),
                    storage);
        }

        void deny() {
            deny.set(true);
        }

        void clearDeny() {
            deny.set(false);
        }

        @Override
        public List<String> reasons() {
            if (Boolean.TRUE.equals(deny.get())) {
                return List.of("恢复预检失败");
            }
            return super.reasons();
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
