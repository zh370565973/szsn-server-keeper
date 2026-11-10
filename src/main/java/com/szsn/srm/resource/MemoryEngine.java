package com.szsn.srm.resource;

import com.szsn.srm.common.ApiException;
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
 * 内存负载引擎：EXTRA_BYTES 固定持有目标；SYSTEM_PERCENT 每拍按系统占用调节。
 * 使用假分配器与 {@link ChunkedMemoryGrower}，不分配真实 {@code byte[]}。
 * 暂停与停止在正常调度下于 {@link #CLEAR_TIMEOUT_MS} 内清除负载引用并使 heldBytes=0。
 */
public final class MemoryEngine {
    public static final long CONTROL_PERIOD_MS = 2_000L;
    public static final long REGROW_COOLDOWN_MS = 10_000L;
    public static final long CLEAR_TIMEOUT_MS = 2_000L;
    public static final String MODE_EXTRA_BYTES = "EXTRA_BYTES";
    public static final String MODE_SYSTEM_PERCENT = "SYSTEM_PERCENT";

    private final ResourceLoadManager sessions;
    private final MemoryAllocator allocator;
    private final MemoryBudgetGate budget;
    private final MemorySampleSource samples;
    private final ChunkedMemoryGrower grower;
    private final Supplier<SafetySignal> safety;
    private final MilliClock clock;
    private final AtomicReference<State> state = new AtomicReference<>();

    public MemoryEngine(
            ResourceLoadManager sessions,
            MemoryAllocator allocator,
            MemoryBudgetGate budget,
            MemorySampleSource samples,
            ChunkedMemoryGrower grower,
            Supplier<SafetySignal> safety) {
        this(sessions, allocator, budget, samples, grower, safety, System::currentTimeMillis);
    }

    public MemoryEngine(
            ResourceLoadManager sessions,
            MemoryAllocator allocator,
            MemoryBudgetGate budget,
            MemorySampleSource samples,
            ChunkedMemoryGrower grower,
            Supplier<SafetySignal> safety,
            MilliClock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.samples = Objects.requireNonNull(samples, "samples");
        this.grower = Objects.requireNonNull(grower, "grower");
        this.safety = Objects.requireNonNull(safety, "safety");
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /**
     * 启动 EXTRA_BYTES。新目标须通过固定容量预算门闸（超限 422 且不分配）。
     * 单独内存会话时推进状态机；联合控制由 CPU 侧持有会话时传 {@code manageSession=false}。
     * 单测默认不启自动控制，生产路径传 {@code autoControl=true}。
     */
    public MemoryEngineView startExtraBytes(SessionClaim claim, long targetBytes) {
        return startExtraBytes(claim, targetBytes, true, false);
    }

    public MemoryEngineView startExtraBytes(SessionClaim claim, long targetBytes, boolean manageSession) {
        return startExtraBytes(claim, targetBytes, manageSession, false);
    }

    public MemoryEngineView startExtraBytes(
            SessionClaim claim, long targetBytes, boolean manageSession, boolean autoControl) {
        ChunkedMemoryGrower.requireAligned(targetBytes);
        if (targetBytes < ChunkedMemoryGrower.CHUNK_BYTES) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "memoryTargetBytes 不合法");
        }
        requireClaim(claim);
        budget.requireFixedCapacity(targetBytes);
        State created = State.extraBytes(claim, targetBytes);
        if (!state.compareAndSet(null, created)) {
            throw new IllegalStateException("内存引擎已在运行");
        }
        try {
            if (manageSession) {
                sessions.markRunning(claim.executionId());
            }
            reportHeld(claim.executionId(), allocator.heldBytes());
            if (autoControl) {
                startControl(created);
            }
        } catch (RuntimeException exception) {
            state.compareAndSet(created, null);
            throw exception;
        }
        return created.view(allocator.heldBytes(), null, null, false);
    }

    /**
     * 启动 SYSTEM_PERCENT。允许在预算受限下启动，运行中显示 TARGET_LIMITED。
     */
    public MemoryEngineView startSystemPercent(SessionClaim claim, double targetPct) {
        return startSystemPercent(claim, targetPct, true, false);
    }

    public MemoryEngineView startSystemPercent(SessionClaim claim, double targetPct, boolean manageSession) {
        return startSystemPercent(claim, targetPct, manageSession, false);
    }

    public MemoryEngineView startSystemPercent(
            SessionClaim claim, double targetPct, boolean manageSession, boolean autoControl) {
        requirePercent(targetPct);
        requireClaim(claim);
        State created = State.systemPercent(claim, targetPct);
        if (!state.compareAndSet(null, created)) {
            throw new IllegalStateException("内存引擎已在运行");
        }
        try {
            if (manageSession) {
                sessions.markRunning(claim.executionId());
            }
            reportHeld(claim.executionId(), allocator.heldBytes());
            if (autoControl) {
                startControl(created);
            }
        } catch (RuntimeException exception) {
            state.compareAndSet(created, null);
            throw exception;
        }
        return created.view(allocator.heldBytes(), null, null, false);
    }

    /**
     * 运行中或暂停时改目标。先抬版本再应用；版本冲突返回 409。
     * EXTRA_BYTES 增大目标须通过预算门闸，超限 422 且不改版本、不静默改小。
     */
    public MemoryEngineView updateTarget(long expectedVersion, Long memoryTargetBytes, Double memoryTargetPct) {
        State current = requireActive();
        long from = current.configVersion.get();
        if (from != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "配置版本不匹配",
                    java.util.Map.of("expectedVersion", expectedVersion, "actualVersion", from));
        }
        if (MODE_EXTRA_BYTES.equals(current.mode)) {
            if (memoryTargetBytes == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "memoryTargetBytes 不合法");
            }
            ChunkedMemoryGrower.requireAligned(memoryTargetBytes);
            if (memoryTargetBytes < ChunkedMemoryGrower.CHUNK_BYTES) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "memoryTargetBytes 不合法");
            }
            if (memoryTargetBytes > current.targetBytes.get()) {
                budget.requireFixedCapacity(memoryTargetBytes);
            }
            if (!current.configVersion.compareAndSet(from, from + 1)) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "配置版本不匹配",
                        java.util.Map.of("expectedVersion", expectedVersion, "actualVersion", current.configVersion.get()));
            }
            current.targetBytes.set(memoryTargetBytes);
        } else if (MODE_SYSTEM_PERCENT.equals(current.mode)) {
            if (memoryTargetPct == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "memoryTargetPct 不合法");
            }
            requirePercent(memoryTargetPct);
            if (!current.configVersion.compareAndSet(from, from + 1)) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "配置版本不匹配",
                        java.util.Map.of("expectedVersion", expectedVersion, "actualVersion", current.configVersion.get()));
            }
            current.targetPct.set(memoryTargetPct);
        } else {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "memoryMode 不合法");
        }
        MemorySample sample = samples.sample();
        return current.view(allocator.heldBytes(), systemUsedBytes(sample), systemUsedPct(sample), cooldownActive(current));
    }

    /**
     * RUNNING → PAUSING → PAUSED。清除全部负载引用，heldBytes 归零，保留槽位与许可。
     * 联合控制下由编排层推进会话时传 {@code manageSession=false}。
     */
    public MemoryEngineView pause() {
        return pause(true);
    }

    public MemoryEngineView pause(boolean manageSession) {
        State current = requireActive();
        String executionId = current.claim.executionId();
        if (manageSession) {
            sessions.beginPause(executionId);
        }
        current.paused.set(true);
        clearHeld(executionId);
        if (manageSession) {
            sessions.completePause(executionId);
        }
        return current.view(allocator.heldBytes(), systemUsedBytes(samples.sample()), systemUsedPct(samples.sample()), false);
    }

    /**
     * 仅清除负载引用并标记内部暂停，不推进会话状态机。供 CPU/联合编排在已迁状态后调用。
     */
    public void clearLoadReferences() {
        State current = state.get();
        if (current != null) {
            current.paused.set(true);
            clearHeld(current.claim.executionId());
            return;
        }
        allocator.clear();
    }

    /**
     * 恢复须重新预检。失败保持 PAUSED；通过后从 heldBytes=0 再渐进增长。
     * 联合控制下会话已由 CPU 侧恢复时传 {@code manageSession=false}（仍执行 gate）。
     */
    public MemoryEngineView resume(ResumeGate gate) {
        return resume(gate, true);
    }

    public MemoryEngineView resume(ResumeGate gate, boolean manageSession) {
        State current = requireActive();
        String executionId = current.claim.executionId();
        ResumeGate check = gate == null ? ResumeGate.OPEN : gate;
        try {
            check.admit();
        } catch (RuntimeException exception) {
            if (manageSession) {
                sessions.rejectResume(executionId);
            }
            throw exception;
        }
        if (manageSession) {
            sessions.acceptResume(executionId);
        }
        current.paused.set(false);
        current.lastReleaseAtMs = null;
        if (manageSession) {
            sessions.markRunning(executionId);
        }
        reportHeld(executionId, allocator.heldBytes());
        return current.view(allocator.heldBytes(), systemUsedBytes(samples.sample()), systemUsedPct(samples.sample()), false);
    }

    /**
     * 停止路径：清除引用；单独内存会话时确认 Worker 退出许可。
     * 联合控制下由 CPU 侧确认退出时传 {@code confirmExit=false}，避免槽位在 CPU Worker 仍运行时被提前释放。
     * 正常调度下应在 {@link #CLEAR_TIMEOUT_MS} 内 heldBytes=0。
     *
     * @return true 表示时限内 heldBytes 已归零
     */
    public boolean stopAndClear(long timeoutMs) throws InterruptedException {
        return stopAndClear(timeoutMs, true);
    }

    public boolean stopAndClear(long timeoutMs, boolean confirmExit) throws InterruptedException {
        State current = state.get();
        String executionId = current == null ? null : current.claim.executionId();
        if (current != null) {
            current.paused.set(true);
            wake(current);
        }
        clearHeld(executionId);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
        if (current != null && current.control != null) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                current.control.join(TimeUnit.NANOSECONDS.toMillis(remaining), (int) (remaining % 1_000_000L));
            }
        }
        while (allocator.heldBytes() != 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(5)));
            clearHeld(executionId);
        }
        boolean zero = allocator.heldBytes() == 0;
        if (current != null && state.compareAndSet(current, null)) {
            if (confirmExit) {
                current.claim.permit().acknowledgeExit();
                sessions.confirmWorkerExit(current.claim.permit());
            }
        } else if (current == null && zero) {
            // 无活动状态时仍保证分配器为空
        }
        return zero;
    }

    /**
     * 推进一拍。EXTRA_BYTES 向目标增长；SYSTEM_PERCENT 按规划增减。暂停中不增长。
     */
    public MemoryEngineView controlTick() {
        State current = state.get();
        if (current == null) {
            return idleView();
        }
        WorkerPermit permit = current.claim.permit();
        if (!permit.valid()) {
            // 仅清引用；状态留给 stopAndClear/detach 释放，避免 MEMORY-only 槽位泄漏
            clearHeld(current.claim.executionId());
            MemorySample sample = samples.sample();
            return current.view(allocator.heldBytes(), systemUsedBytes(sample), systemUsedPct(sample), false);
        }
        if (current.paused.get()) {
            MemorySample sample = samples.sample();
            return current.view(allocator.heldBytes(), systemUsedBytes(sample), systemUsedPct(sample), false);
        }

        SafetySignal signal = safety.get();
        boolean forbidGrowth = signal != null && signal.forbidMemoryGrowth();
        MemorySample sample = samples.sample();
        Long systemUsed = systemUsedBytes(sample);
        Double systemPct = systemUsedPct(sample);

        if (MODE_EXTRA_BYTES.equals(current.mode)) {
            return tickExtraBytes(current, permit, forbidGrowth, systemUsed, systemPct);
        }
        return tickSystemPercent(current, permit, forbidGrowth, sample, systemUsed, systemPct);
    }

    public MemoryEngineView view() {
        State current = state.get();
        if (current == null) {
            return idleView();
        }
        MemorySample sample = samples.sample();
        return current.view(
                allocator.heldBytes(),
                systemUsedBytes(sample),
                systemUsedPct(sample),
                cooldownActive(current));
    }

    public long heldBytes() {
        return allocator.heldBytes();
    }

    /**
     * 使当前状态失效并清除负载引用。
     */
    public void detach() {
        State current = state.getAndSet(null);
        if (current != null) {
            clearHeld(current.claim.executionId());
            current.claim.permit().acknowledgeExit();
        } else {
            allocator.clear();
        }
    }

    private MemoryEngineView tickExtraBytes(
            State current,
            WorkerPermit permit,
            boolean forbidGrowth,
            Long systemUsed,
            Double systemPct) {
        List<String> reasons = new ArrayList<>();
        if (forbidGrowth) {
            reasons.add("FORBID_MEMORY_GROWTH");
            current.setLimited(reasons, false, false);
            return current.view(allocator.heldBytes(), systemUsed, systemPct, false);
        }
        long held = allocator.heldBytes();
        long target = current.targetBytes.get();
        if (held == target) {
            current.setLimited(List.of(), false, false);
            reportHeld(current.claim.executionId(), held);
            return current.view(held, systemUsed, systemPct, false);
        }
        ChunkedMemoryGrower.GrowResult result = grower.growToward(target, permit);
        reportHeld(current.claim.executionId(), result.heldBytes());
        if (result.reason() == ChunkedMemoryGrower.StopReason.BUDGET_EXHAUSTED) {
            reasons.add(SystemPercentPlanner.TARGET_LIMITED);
            if (result.detail() != null) {
                reasons.add(result.detail());
            }
            current.setLimited(reasons, false, true);
        } else if (result.reason() != ChunkedMemoryGrower.StopReason.COMPLETED) {
            if (result.detail() != null) {
                reasons.add(result.detail());
            }
            current.setLimited(reasons, false, false);
        } else {
            current.setLimited(List.of(), false, false);
        }
        return current.view(allocator.heldBytes(), systemUsed, systemPct, false);
    }

    private MemoryEngineView tickSystemPercent(
            State current,
            WorkerPermit permit,
            boolean forbidGrowth,
            MemorySample sample,
            Long systemUsed,
            Double systemPct) {
        if (sample == null
                || sample.memTotalBytes() == null
                || sample.memAvailableBytes() == null
                || !ChunkedMemoryGrower.freshEnough(sample)) {
            List<String> reasons = new ArrayList<>();
            reasons.add(sample == null || !ChunkedMemoryGrower.freshEnough(sample)
                    ? "METRIC_STALE_3S"
                    : SystemPercentPlanner.SAMPLE_INCOMPLETE);
            current.setLimited(reasons, false, false);
            return current.view(allocator.heldBytes(), systemUsed, systemPct, cooldownActive(current));
        }

        MemoryBudget.Result budgetNow = budget.currentBudget();
        boolean cooling = cooldownActive(current);
        SystemPercentPlanner.Decision decision = SystemPercentPlanner.decide(
                current.targetPct.get(),
                sample.memTotalBytes(),
                sample.memAvailableBytes(),
                allocator.heldBytes(),
                budgetNow.additionalBytes(),
                cooling);

        if (decision.action() == SystemPercentPlanner.Action.GROW && forbidGrowth) {
            List<String> reasons = new ArrayList<>(decision.reasons());
            reasons.add("FORBID_MEMORY_GROWTH");
            current.setLimited(reasons, decision.systemAboveTarget(), decision.targetLimited());
            return current.view(allocator.heldBytes(), systemUsed, systemPct, cooling);
        }

        if (decision.action() == SystemPercentPlanner.Action.RELEASE) {
            long before = allocator.heldBytes();
            long after = allocator.release(decision.amountBytes());
            reportHeld(current.claim.executionId(), after);
            if (after < before) {
                current.lastReleaseAtMs = clock.millis();
            }
            current.setLimited(decision.reasons(), true, false);
            return current.view(after, systemUsed, systemPct, cooldownActive(current));
        }

        if (decision.action() == SystemPercentPlanner.Action.GROW) {
            long targetHeld = allocator.heldBytes() + decision.amountBytes();
            ChunkedMemoryGrower.GrowResult result = grower.growToward(targetHeld, permit);
            reportHeld(current.claim.executionId(), result.heldBytes());
            boolean limited = decision.targetLimited()
                    || result.reason() == ChunkedMemoryGrower.StopReason.BUDGET_EXHAUSTED;
            List<String> reasons = new ArrayList<>(decision.reasons());
            if (result.reason() == ChunkedMemoryGrower.StopReason.BUDGET_EXHAUSTED
                    && !reasons.contains(SystemPercentPlanner.TARGET_LIMITED)) {
                reasons.add(SystemPercentPlanner.TARGET_LIMITED);
            }
            if (result.detail() != null
                    && result.reason() != ChunkedMemoryGrower.StopReason.COMPLETED
                    && !reasons.contains(result.detail())) {
                reasons.add(result.detail());
            }
            current.setLimited(reasons, false, limited);
            if (!permit.valid()) {
                clearHeld(current.claim.executionId());
                state.compareAndSet(current, null);
            }
            return current.view(allocator.heldBytes(), systemUsed, systemPct, cooldownActive(current));
        }

        current.setLimited(decision.reasons(), decision.systemAboveTarget(), decision.targetLimited());
        return current.view(allocator.heldBytes(), systemUsed, systemPct, cooling);
    }

    private void clearHeld(String executionId) {
        allocator.clear();
        if (executionId != null) {
            reportHeld(executionId, 0L);
        }
    }

    private void reportHeld(String executionId, long heldBytes) {
        try {
            sessions.observeHeldBytes(executionId, heldBytes);
        } catch (SessionRejectedException ignored) {
            // 会话已终态或不匹配时忽略上报
        } catch (RuntimeException ignored) {
            // 停止路径不能因上报失败留下引用
        }
    }

    private State requireActive() {
        State current = state.get();
        if (current == null) {
            throw new SessionRejectedException(SessionRejectedException.Reason.NOT_ACTIVE, "内存引擎没有活动会话");
        }
        if (!current.claim.permit().valid()) {
            throw new SessionRejectedException(SessionRejectedException.Reason.EMERGENCY_PREEMPTED, "许可已失效");
        }
        return current;
    }

    private boolean cooldownActive(State current) {
        if (current.lastReleaseAtMs == null) {
            return false;
        }
        return clock.millis() - current.lastReleaseAtMs < REGROW_COOLDOWN_MS;
    }

    private static void requireClaim(SessionClaim claim) {
        if (claim == null || claim.permit() == null) {
            throw new IllegalArgumentException("会话许可不能为空");
        }
        if (!claim.permit().valid()) {
            throw new SessionRejectedException(SessionRejectedException.Reason.EMERGENCY_PREEMPTED, "许可已失效");
        }
    }

    private static void requirePercent(double targetPct) {
        if (Double.isNaN(targetPct) || targetPct < 0 || targetPct > 100) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "memoryTargetPct 不合法");
        }
    }

    private static Long systemUsedBytes(MemorySample sample) {
        if (sample == null || sample.memTotalBytes() == null || sample.memAvailableBytes() == null) {
            return null;
        }
        return sample.memTotalBytes() - sample.memAvailableBytes();
    }

    private static Double systemUsedPct(MemorySample sample) {
        Long used = systemUsedBytes(sample);
        if (used == null || sample.memTotalBytes() == null || sample.memTotalBytes() <= 0) {
            return null;
        }
        return used * 100.0 / sample.memTotalBytes();
    }

    private void startControl(State created) {
        Thread control = namedFactory("srm-mem-ctrl").newThread(() -> controlLoop(created));
        created.control = control;
        control.start();
    }

    private void controlLoop(State current) {
        try {
            while (current.claim.permit().valid() && !Thread.currentThread().isInterrupted()) {
                if (state.get() != current) {
                    break;
                }
                controlTick();
                if (!park(current, CONTROL_PERIOD_MS)) {
                    break;
                }
            }
        } finally {
            clearHeld(current.claim.executionId());
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
                synchronized (current.wake) {
                    if (!current.claim.permit().valid()) {
                        return false;
                    }
                    long waitMs = TimeUnit.NANOSECONDS.toMillis(remaining);
                    int waitNs = (int) (remaining % 1_000_000L);
                    if (waitMs > 0 || waitNs > 0) {
                        current.wake.wait(Math.min(waitMs, 50L), waitNs);
                    }
                }
            }
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void wake(State current) {
        synchronized (current.wake) {
            current.wake.notifyAll();
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

    private static MemoryEngineView idleView() {
        return new MemoryEngineView(
                null, false, false, null, null, null, 0L, 0L, null, null, false, false, false, List.of());
    }

    private static final class State {
        private final SessionClaim claim;
        private final String mode;
        private final AtomicLong targetBytes;
        private final AtomicReference<Double> targetPct;
        private final AtomicLong configVersion = new AtomicLong(1);
        private final AtomicBoolean paused = new AtomicBoolean();
        private final Object wake = new Object();
        private volatile Thread control;
        private volatile Long lastReleaseAtMs;
        private volatile List<String> limitedReasons = List.of();
        private volatile boolean systemAboveTarget;
        private volatile boolean targetLimited;

        private State(SessionClaim claim, String mode, long targetBytes, double targetPct) {
            this.claim = claim;
            this.mode = mode;
            this.targetBytes = new AtomicLong(targetBytes);
            this.targetPct = new AtomicReference<>(targetPct);
        }

        static State extraBytes(SessionClaim claim, long targetBytes) {
            return new State(claim, MODE_EXTRA_BYTES, targetBytes, 0D);
        }

        static State systemPercent(SessionClaim claim, double targetPct) {
            return new State(claim, MODE_SYSTEM_PERCENT, 0L, targetPct);
        }

        void setLimited(List<String> reasons, boolean above, boolean limited) {
            this.limitedReasons = List.copyOf(reasons);
            this.systemAboveTarget = above;
            this.targetLimited = limited;
        }

        MemoryEngineView view(long held, Long systemUsed, Double systemPct, boolean cooldown) {
            return new MemoryEngineView(
                    claim.executionId(),
                    true,
                    paused.get(),
                    mode,
                    MODE_EXTRA_BYTES.equals(mode) ? targetBytes.get() : null,
                    MODE_SYSTEM_PERCENT.equals(mode) ? targetPct.get() : null,
                    configVersion.get(),
                    held,
                    systemUsed,
                    systemPct,
                    systemAboveTarget,
                    targetLimited,
                    cooldown,
                    limitedReasons);
        }
    }

    @FunctionalInterface
    public interface MilliClock {
        long millis();
    }
}
