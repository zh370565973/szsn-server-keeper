package com.szsn.srm.resource;

import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.Units;
import com.szsn.srm.safety.SafetySignal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;

/**
 * CPU 负载引擎。复用 {@link CpuDutyCalculator}；Worker 数不超过逻辑核心数；
 * 停止后在 2 秒内确认退出。暂停只停止计算并保留槽位；恢复须重新预检。
 * 可注入采集与燃烧器；本类不分配内存，也不打开 HTTP 启动入口。
 */
public final class CpuEngine {
    public static final long SLICE_MS = 100L;
    public static final long CHECK_MS = 5L;
    public static final long CONTROL_PERIOD_MS = 2_000L;
    public static final long STOP_TIMEOUT_MS = 2_000L;

    private final ResourceLoadManager sessions;
    private final CpuSampleSource samples;
    private final Supplier<SafetySignal> safety;
    private final CpuBurner burner;
    private final TargetChangeJournal targetChanges;
    private final Object wake = new Object();
    private final AtomicReference<State> state = new AtomicReference<>();

    public CpuEngine(ResourceLoadManager sessions, CpuSampleSource samples, Supplier<SafetySignal> safety,
                     CpuBurner burner) {
        this(sessions, samples, safety, burner, TargetChangeJournal.NONE);
    }

    public CpuEngine(ResourceLoadManager sessions, CpuSampleSource samples, Supplier<SafetySignal> safety,
                     CpuBurner burner, TargetChangeJournal targetChanges) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.samples = Objects.requireNonNull(samples, "samples");
        this.safety = Objects.requireNonNull(safety, "safety");
        this.burner = Objects.requireNonNull(burner, "burner");
        this.targetChanges = targetChanges == null ? TargetChangeJournal.NONE : targetChanges;
    }

    /**
     * 默认 Worker 数 = 可用逻辑核心数；显式请求时也不得超过该上限。
     */
    public static int cappedWorkerCount(int requested, int availableLogicalCpus) {
        if (availableLogicalCpus < 1) {
            throw new IllegalArgumentException("逻辑核心数必须至少为 1");
        }
        if (requested < 1) {
            return availableLogicalCpus;
        }
        return Math.min(requested, availableLogicalCpus);
    }

    /**
     * 启动固定数量的 Worker 与 2 秒控制循环。{@code workerCount} 必须已经过 {@link #cappedWorkerCount}。
     */
    public CpuEngineView start(SessionClaim claim, double targetPct, int workerCount) {
        return start(claim, targetPct, workerCount, true);
    }

    /**
     * @param autoControl false 时只起 Worker，由测试调用 {@link #controlTick()} 推进调节
     */
    public CpuEngineView start(SessionClaim claim, double targetPct, int workerCount, boolean autoControl) {
        if (claim == null || claim.permit() == null) {
            throw new IllegalArgumentException("会话许可不能为空");
        }
        requireTarget(targetPct);
        if (workerCount < 1) {
            throw new IllegalArgumentException("Worker 数必须至少为 1");
        }
        if (!claim.permit().valid()) {
            throw new SessionRejectedException(SessionRejectedException.Reason.EMERGENCY_PREEMPTED, "许可已失效");
        }
        State created = new State(claim, targetPct, workerCount);
        if (!state.compareAndSet(null, created)) {
            throw new IllegalStateException("CPU 引擎已在运行");
        }
        try {
            sessions.markRunning(claim.executionId());
            ThreadFactory workers = namedFactory("srm-cpu-w");
            for (int i = 0; i < created.workerCount; i++) {
                final int index = i;
                Thread thread = workers.newThread(() -> workerLoop(created));
                created.workers[index] = thread;
                thread.start();
            }
            if (autoControl) {
                Thread control = namedFactory("srm-cpu-ctrl").newThread(() -> controlLoop(created));
                created.control = control;
                control.start();
            }
        } catch (RuntimeException exception) {
            state.compareAndSet(created, null);
            created.duty.set(0D);
            wakeAll();
            throw exception;
        }
        return created.view();
    }

    /**
     * RUNNING → PAUSING → PAUSED。duty 归零，Worker 停止计算但保留槽位与许可。
     */
    public CpuEngineView pause() {
        State current = requireActive();
        String executionId = current.claim.executionId();
        sessions.beginPause(executionId);
        current.paused.set(true);
        current.duty.set(0D);
        wakeAll();
        sessions.completePause(executionId);
        return current.view();
    }

    /**
     * 恢复须重新预检。失败保持 PAUSED；通过后 STARTING → RUNNING，duty 从 0 渐进。
     */
    public CpuEngineView resume(ResumeGate gate) {
        State current = requireActive();
        String executionId = current.claim.executionId();
        ResumeGate check = gate == null ? ResumeGate.OPEN : gate;
        try {
            check.admit();
        } catch (RuntimeException exception) {
            sessions.rejectResume(executionId);
            throw exception;
        }
        sessions.acceptResume(executionId);
        current.calculator.resetDuty();
        current.duty.set(0D);
        current.externalLoadHigh.set(false);
        current.safetyZeroed.set(false);
        current.paused.set(false);
        sessions.markRunning(executionId);
        wakeAll();
        return current.view();
    }

    /**
     * 运行中或暂停时改目标。先记录变更再应用；记录失败不改版本，不谎报成功。
     * 暂停时只更新配置，恢复后按新目标从 0 渐进。
     */
    public CpuEngineView updateTarget(long expectedVersion, double targetPct) {
        requireTarget(targetPct);
        State current = requireActive();
        if (!current.claim.permit().valid()) {
            throw new SessionRejectedException(SessionRejectedException.Reason.EMERGENCY_PREEMPTED, "许可已失效");
        }
        long from = current.configVersion.get();
        if (from != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_MISMATCH", "目标版本不匹配",
                    java.util.Map.of("expectedVersion", expectedVersion, "actualVersion", from));
        }
        long to = from + 1;
        targetChanges.record(current.claim.executionId(), from, to, targetPct);
        if (!current.configVersion.compareAndSet(from, to)) {
            sessions.requestStop(current.claim.executionId(), StopKind.FAILURE);
            wakeAll();
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_MISMATCH", "目标版本不匹配",
                    java.util.Map.of("expectedVersion", expectedVersion, "actualVersion", current.configVersion.get()));
        }
        current.targetPct.set(targetPct);
        wakeAll();
        return current.view();
    }

    /**
     * 供测试同步推进一次控制周期，不依赖真实 2 秒等待。暂停中调用只保持 duty=0。
     */
    public CpuEngineView controlTick() {
        State current = requireActive();
        applyControl(current);
        return current.view();
    }

    public CpuEngineView view() {
        State current = state.get();
        if (current == null) {
            return idleView();
        }
        return current.view();
    }

    /**
     * 等待当前会话 Worker 全部退出并确认；超时则标记 {@code stopTimeout}。
     *
     * @return true 表示在时限内确认退出
     */
    public boolean awaitStop(long timeoutMs) throws InterruptedException {
        State current = state.get();
        if (current == null) {
            return true;
        }
        wakeAll();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
        boolean joined = true;
        for (Thread worker : current.workers) {
            if (worker == null) {
                continue;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                joined = false;
                break;
            }
            worker.join(TimeUnit.NANOSECONDS.toMillis(remaining), (int) (remaining % 1_000_000L));
            if (worker.isAlive()) {
                joined = false;
                break;
            }
        }
        Thread control = current.control;
        if (control != null) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                control.join(TimeUnit.NANOSECONDS.toMillis(remaining), (int) (remaining % 1_000_000L));
            }
            if (control.isAlive()) {
                joined = false;
            }
        }
        if (!joined) {
            current.stopTimeout.set(true);
            return false;
        }
        confirmExit(current);
        return true;
    }

    private void controlLoop(State current) {
        try {
            while (current.claim.permit().valid() && !Thread.currentThread().isInterrupted()) {
                applyControl(current);
                if (!park(current, CONTROL_PERIOD_MS)) {
                    break;
                }
            }
        } finally {
            current.duty.set(0D);
            wakeAll();
        }
    }

    private void applyControl(State current) {
        if (!current.claim.permit().valid()) {
            current.duty.set(0D);
            wakeAll();
            return;
        }
        if (current.paused.get()) {
            current.duty.set(0D);
            wakeAll();
            return;
        }
        Double raw = samples.overallBusyPct();
        SafetySignal signal = safety.get();
        boolean safetyZero = signal != null && signal.zeroDuty();
        boolean forbidGrowth = signal != null && signal.forbidCpuGrowth();
        List<String> reasons = new ArrayList<>();
        if (signal != null && signal.reasons() != null) {
            for (String reason : signal.reasons()) {
                if (reason != null && !reason.isBlank()) {
                    reasons.add(reason);
                }
            }
        }
        if (raw == null || Double.isNaN(raw) || Double.isInfinite(raw)) {
            current.actual.set(null);
            CpuDutyCalculator.DutyStep missing = current.calculator.step(0, current.targetPct.get(), true, true);
            current.duty.set(0D);
            current.ewma.set(missing.ewma());
            current.externalLoadHigh.set(false);
            current.safetyZeroed.set(true);
            reasons.add("CPU_SAMPLE_MISSING");
            current.limitedReasons.set(List.copyOf(reasons));
            wakeAll();
            return;
        }
        current.actual.set(raw);
        CpuDutyCalculator.DutyStep step = current.calculator.step(raw, current.targetPct.get(), safetyZero, forbidGrowth);
        current.duty.set(step.duty());
        current.ewma.set(step.ewma());
        current.externalLoadHigh.set(step.externalLoadHigh());
        current.safetyZeroed.set(step.safetyZeroed());
        if (step.externalLoadHigh()) {
            reasons.add("EXTERNAL_LOAD_HIGH");
        }
        if (step.safetyZeroed()) {
            reasons.add("SAFETY_ZERO");
        }
        current.limitedReasons.set(List.copyOf(reasons));
        wakeAll();
    }

    private void workerLoop(State current) {
        WorkerPermit permit = current.claim.permit();
        try {
            while (permit.valid() && !Thread.currentThread().isInterrupted()) {
                if (current.paused.get()) {
                    if (!park(current, SLICE_MS)) {
                        break;
                    }
                    continue;
                }
                double duty = Units.clampDouble(current.duty.get(), 0, 1);
                long workMs = Math.round(SLICE_MS * duty);
                if (workMs < 0) {
                    workMs = 0;
                }
                if (workMs > SLICE_MS) {
                    workMs = SLICE_MS;
                }
                long waitMs = SLICE_MS - workMs;
                if (workMs > 0) {
                    long value = burner.burn(
                            TimeUnit.MILLISECONDS.toNanos(workMs),
                            permit,
                            () -> !current.paused.get());
                    if (value != 0L) {
                        current.checksum.addAndGet(value);
                    }
                }
                if (waitMs > 0 && permit.valid() && !current.paused.get()) {
                    park(current, waitMs);
                }
            }
        } finally {
            if (current.workersDone.incrementAndGet() >= current.workerCount) {
                confirmExit(current);
            }
        }
    }

    private boolean park(State current, long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        try {
            while (current.claim.permit().valid()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return true;
                }
                long slice = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(CHECK_MS));
                synchronized (wake) {
                    if (!current.claim.permit().valid()) {
                        return false;
                    }
                    long waitMs = TimeUnit.NANOSECONDS.toMillis(slice);
                    int waitNs = (int) (slice % 1_000_000L);
                    if (waitMs > 0 || waitNs > 0) {
                        wake.wait(waitMs, waitNs);
                    }
                }
            }
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void wakeAll() {
        synchronized (wake) {
            wake.notifyAll();
        }
    }

    private void confirmExit(State current) {
        if (current.exitConfirmed.compareAndSet(false, true)) {
            sessions.confirmWorkerExit(current.claim.permit());
            state.compareAndSet(current, null);
        }
    }

    private State requireActive() {
        State current = state.get();
        if (current == null) {
            throw new IllegalStateException("CPU 引擎未运行");
        }
        return current;
    }

    private static void requireTarget(double targetPct) {
        if (Double.isNaN(targetPct) || Double.isInfinite(targetPct) || targetPct < 0 || targetPct > 100) {
            throw new IllegalArgumentException("CPU 目标不合法");
        }
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicLong sequence = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static CpuEngineView idleView() {
        return new CpuEngineView(null, false, false, 0, 0, null, 0, 0, 0, false, false, false, 0, List.of());
    }

    private static final class State {
        private final SessionClaim claim;
        private final AtomicReference<Double> targetPct;
        private final AtomicLong configVersion = new AtomicLong(1);
        private final int workerCount;
        private final Thread[] workers;
        private final CpuDutyCalculator calculator = new CpuDutyCalculator();
        private final AtomicReference<Double> duty = new AtomicReference<>(0D);
        private final AtomicReference<Double> actual = new AtomicReference<>();
        private final AtomicReference<Double> ewma = new AtomicReference<>(0D);
        private final AtomicBoolean paused = new AtomicBoolean();
        private final AtomicBoolean externalLoadHigh = new AtomicBoolean();
        private final AtomicBoolean safetyZeroed = new AtomicBoolean();
        private final AtomicBoolean stopTimeout = new AtomicBoolean();
        private final AtomicBoolean exitConfirmed = new AtomicBoolean();
        private final AtomicLong workersDone = new AtomicLong();
        private final AtomicLong checksum = new AtomicLong();
        private final AtomicReference<List<String>> limitedReasons = new AtomicReference<>(List.of());
        private volatile Thread control;

        private State(SessionClaim claim, double targetPct, int workerCount) {
            this.claim = claim;
            this.targetPct = new AtomicReference<>(targetPct);
            this.workerCount = workerCount;
            this.workers = new Thread[workerCount];
        }

        private CpuEngineView view() {
            return new CpuEngineView(
                    claim.executionId(),
                    !exitConfirmed.get(),
                    paused.get(),
                    targetPct.get(),
                    configVersion.get(),
                    actual.get(),
                    duty.get(),
                    ewma.get(),
                    workerCount,
                    externalLoadHigh.get(),
                    safetyZeroed.get(),
                    stopTimeout.get(),
                    checksum.get(),
                    limitedReasons.get());
        }
    }
}
