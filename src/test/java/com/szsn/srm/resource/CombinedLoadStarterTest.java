package com.szsn.srm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.Units;
import com.szsn.srm.db.DatabaseMigrator;
import com.szsn.srm.monitor.Quality;
import com.szsn.srm.safety.SafetySignal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * 联合控制失败回收：任一引擎失败时两侧都停，并写 FAILED；不静默退化为单项。
 */
class CombinedLoadStarterTest {
    private CpuEngine cpu;
    private MemoryEngine memory;
    private ResourceLoadManager sessions;
    private FakeMemoryAllocator allocator;

    @AfterEach
    void tearDown() throws Exception {
        if (cpu != null) {
            cpu.awaitStop(CpuEngine.STOP_TIMEOUT_MS);
        }
        if (memory != null) {
            memory.detach();
        }
        if (sessions != null) {
            sessions.emergencyStop();
            sessions.active().ifPresent(view -> {
                try {
                    sessions.confirmWorkerExit(new WorkerPermit(
                            view.executionId(), view.issuedGeneration(), new AtomicLong(view.issuedGeneration()),
                            new AtomicBoolean(true)));
                } catch (RuntimeException ignored) {
                    // 尽力释放
                }
            });
        }
    }

    @Test
    void combinedStartRunsBothEngines() {
        Harness harness = harness(ample());
        SessionClaim claim = sessions.claim("combined-ok");
        harness.starter.start(claim, 30, 2, MemoryEngine.MODE_EXTRA_BYTES, 8L * Units.MIB, null, false);

        assertEquals("combined-ok", cpu.view().executionId());
        assertEquals("combined-ok", memory.view().executionId());
        assertEquals(ExecutionStatus.RUNNING, sessions.active().orElseThrow().status());

        memory.controlTick();
        assertEquals(8L * Units.MIB, allocator.heldBytes());
        assertFalse(cpu.view().executionId() == null || memory.view().executionId() == null);

        sessions.requestStop("combined-ok", StopKind.USER);
        assertDoesStopBoth();
    }

    @Test
    void memoryStartFailureStopsCpuAndDoesNotLeaveSingleEngine() {
        Harness harness = harness(ample());
        WorkerPermit ghost = new WorkerPermit("ghost", 1L, new AtomicLong(1L), new AtomicBoolean(false));
        memory.startExtraBytes(new SessionClaim("ghost", 1L, ghost), 4L * Units.MIB, false);
        assertEquals("ghost", memory.view().executionId());

        SessionClaim claim = sessions.claim("combined-mem-fail");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> harness.starter.start(claim, 25, 2, MemoryEngine.MODE_EXTRA_BYTES, 4L * Units.MIB, null, false));
        assertTrue(thrown.getMessage().contains("内存引擎已在运行"));

        assertNull(cpu.view().executionId());
        assertEquals(0L, allocator.heldBytes());
        assertTrue(sessions.active().isEmpty()
                || sessions.lastFinished().map(v -> v.status() == ExecutionStatus.FAILED).orElse(false)
                || sessions.active().map(v -> v.status().terminal() || v.status() == ExecutionStatus.STOPPING).orElse(false));
        // 不得静默只跑 CPU
        assertNull(cpu.view().executionId());
        memory.detach();
        assertTrue(sessions.active().isEmpty() || sessions.active().orElseThrow().heldBytes() == 0);
    }

    @Test
    void cpuStartFailureClearsMemorySideAndReleasesSlot() {
        Harness harness = harness(ample());
        SessionClaim claim = sessions.claim("combined-cpu-fail");
        sessions.emergencyStop();

        assertThrows(SessionRejectedException.class,
                () -> harness.starter.start(claim, 20, 2, MemoryEngine.MODE_EXTRA_BYTES, 4L * Units.MIB, null, false));

        assertNull(cpu.view().executionId());
        assertNull(memory.view().executionId());
        assertEquals(0L, allocator.heldBytes());
        assertTrue(sessions.active().isEmpty()
                || sessions.lastFinished().isPresent());
    }

    @Test
    void overBudgetRejectsBeforeEitherEngineStarts() {
        Harness harness = harness(ample());
        SessionClaim claim = sessions.claim("combined-budget");
        long before = allocator.heldBytes();
        ApiException denied = assertThrows(ApiException.class,
                () -> harness.starter.start(
                        claim, 20, 2, MemoryEngine.MODE_EXTRA_BYTES, Units.DEFAULT_TASK_CAP + Units.BLOCK, null, false));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, denied.status());
        assertEquals(MemoryBudgetGate.BUDGET_EXCEEDED, denied.code());
        assertEquals(before, allocator.heldBytes());
        assertNull(cpu.view().executionId());
        assertNull(memory.view().executionId());
    }

    @Test
    void launchFailurePersistsFailedAndLeavesNoRunningEngine() throws Exception {
        Harness harness = harness(ample());
        DataSource dataSource = memoryDb();
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        ExecutionStartService starts = new ExecutionStartService(
                dataSource, new DataSourceTransactionManager(dataSource), clock, StartGate.OPEN);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        WorkerPermit ghost = new WorkerPermit("ghost2", 1L, new AtomicLong(1L), new AtomicBoolean(false));
        memory.startExtraBytes(new SessionClaim("ghost2", 1L, ghost), 4L * Units.MIB, false);

        String key = UUID.randomUUID().toString();
        String config = """
                {"name":"联合失败","resourceMode":"COMBINED","cpuTargetPct":30,\
                "memoryMode":"EXTRA_BYTES","memoryTargetBytes":4194304,"runMode":"CONTINUOUS"}
                """;
        assertThrows(IllegalStateException.class, () -> starts.start(key, config, committed -> {
            SessionClaim claim = sessions.claim(committed.executionId());
            harness.starter.start(claim, 30, 2, MemoryEngine.MODE_EXTRA_BYTES, 4L * Units.MIB, null, false);
        }));

        assertEquals(ExecutionStatus.FAILED.name(), jdbc.queryForObject(
                "SELECT STATUS FROM TASK_EXECUTION WHERE IDEMPOTENCY_KEY = ?", String.class, key));
        assertEquals("START_FAILED", jdbc.queryForObject(
                "SELECT REASON FROM TASK_EXECUTION WHERE IDEMPOTENCY_KEY = ?", String.class, key));
        assertNull(cpu.view().executionId());
        memory.detach();
        assertEquals(0L, allocator.heldBytes());
    }

    @Test
    void systemPercentCombinedAlsoRecoversOnMemoryConflict() {
        Harness harness = harness(ample());
        WorkerPermit ghost = new WorkerPermit("ghost3", 1L, new AtomicLong(1L), new AtomicBoolean(false));
        memory.startSystemPercent(new SessionClaim("ghost3", 1L, ghost), 40, false);

        SessionClaim claim = sessions.claim("combined-pct-fail");
        assertThrows(IllegalStateException.class,
                () -> harness.starter.start(claim, 35, 2, MemoryEngine.MODE_SYSTEM_PERCENT, null, 40D, false));
        assertNull(cpu.view().executionId());
        memory.detach();
        assertEquals(0L, allocator.heldBytes());
    }

    private void assertDoesStopBoth() {
        try {
            assertTrue(cpu.awaitStop(CpuEngine.STOP_TIMEOUT_MS));
            assertTrue(memory.stopAndClear(MemoryEngine.CLEAR_TIMEOUT_MS, false));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
        assertNull(cpu.view().executionId());
        assertNull(memory.view().executionId());
        assertEquals(0L, allocator.heldBytes());
    }

    private Harness harness(MemorySample sample) {
        sessions = new ResourceLoadManager();
        allocator = new FakeMemoryAllocator();
        AtomicReference<MemorySample> sampleRef = new AtomicReference<>(sample);
        AtomicReference<SafetySignal> safety = new AtomicReference<>(healthy());
        AtomicReference<Double> cpuSample = new AtomicReference<>(10D);
        MemoryBudgetGate gate = new MemoryBudgetGate(allocator, sampleRef::get, Units.DEFAULT_TASK_CAP);
        ChunkedMemoryGrower grower = new ChunkedMemoryGrower(
                allocator, gate, sampleRef::get, () -> false, System::nanoTime, nanos -> {
                }, held -> {
                });
        memory = new MemoryEngine(sessions, allocator, gate, sampleRef::get, grower, safety::get);
        cpu = new CpuEngine(sessions, cpuSample::get, safety::get, (nanos, permit, allowed) -> 1L);
        CombinedLoadStarter starter = new CombinedLoadStarter(sessions, cpu, memory, gate);
        return new Harness(starter, sampleRef, safety, cpuSample);
    }

    private static MemorySample ample() {
        return new MemorySample(8L * Units.GIB, 6L * Units.GIB, 2L * Units.GIB, 200L * Units.MIB, 0L);
    }

    private static SafetySignal healthy() {
        return new SafetySignal(
                0L, 1L, 0L, Quality.VALID, true, null,
                false, false, false, false, false, List.of());
    }

    private static DataSource memoryDb() throws Exception {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.h2.Driver.class);
        dataSource.setUrl("jdbc:h2:mem:combined-" + UUID.randomUUID() + ";MODE=REGULAR;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        new DatabaseMigrator(dataSource).migrate();
        return dataSource;
    }

    private record Harness(
            CombinedLoadStarter starter,
            AtomicReference<MemorySample> sample,
            AtomicReference<SafetySignal> safety,
            AtomicReference<Double> cpuSample) {
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(long epochMs) {
            this.instant = new AtomicReference<>(Instant.ofEpochMilli(epochMs));
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
