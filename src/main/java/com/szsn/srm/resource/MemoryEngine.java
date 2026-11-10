package com.szsn.srm.resource;

import com.szsn.srm.common.ApiException;
import com.szsn.srm.safety.SafetySignal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;

/**
 * 内存负载引擎：EXTRA_BYTES 固定持有目标；SYSTEM_PERCENT 每拍按系统占用调节。
 * 使用假分配器与 {@link ChunkedMemoryGrower}，不分配真实 {@code byte[]}，不打开 HTTP 启动入口。
 * 暂停/停止清引用留给后续任务。
 */
public final class MemoryEngine {
    public static final long CONTROL_PERIOD_MS = 2_000L;
    public static final long REGROW_COOLDOWN_MS = 10_000L;
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
     */
    public MemoryEngineView startExtraBytes(SessionClaim claim, long targetBytes) {
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
            sessions.markRunning(claim.executionId());
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
        requirePercent(targetPct);
        requireClaim(claim);
        State created = State.systemPercent(claim, targetPct);
        if (!state.compareAndSet(null, created)) {
            throw new IllegalStateException("内存引擎已在运行");
        }
        try {
            sessions.markRunning(claim.executionId());
        } catch (RuntimeException exception) {
            state.compareAndSet(created, null);
            throw exception;
        }
        return created.view(allocator.heldBytes(), null, null, false);
    }

    /**
     * 推进一拍。EXTRA_BYTES 向目标增长；SYSTEM_PERCENT 按规划增减。
     */
    public MemoryEngineView controlTick() {
        State current = state.get();
        if (current == null) {
            return idleView();
        }
        WorkerPermit permit = current.claim.permit();
        if (!permit.valid()) {
            state.compareAndSet(current, null);
            return idleView();
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

    /**
     * 测试或后续停止路径：使当前状态失效。不在此任务清 heldBytes。
     */
    public void detach() {
        State current = state.getAndSet(null);
        if (current != null) {
            current.claim.permit().acknowledgeExit();
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
        if (held == current.targetBytes) {
            current.setLimited(List.of(), false, false);
            return current.view(held, systemUsed, systemPct, false);
        }
        ChunkedMemoryGrower.GrowResult result = grower.growToward(current.targetBytes, permit);
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
        if (!permit.valid()) {
            state.compareAndSet(current, null);
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
                current.targetPct,
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
            if (after < before) {
                current.lastReleaseAtMs = clock.millis();
            }
            current.setLimited(decision.reasons(), true, false);
            return current.view(after, systemUsed, systemPct, cooldownActive(current));
        }

        if (decision.action() == SystemPercentPlanner.Action.GROW) {
            long targetHeld = allocator.heldBytes() + decision.amountBytes();
            ChunkedMemoryGrower.GrowResult result = grower.growToward(targetHeld, permit);
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
                state.compareAndSet(current, null);
            }
            return current.view(allocator.heldBytes(), systemUsed, systemPct, cooldownActive(current));
        }

        current.setLimited(decision.reasons(), decision.systemAboveTarget(), decision.targetLimited());
        return current.view(allocator.heldBytes(), systemUsed, systemPct, cooling);
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

    private static MemoryEngineView idleView() {
        return new MemoryEngineView(
                null, false, null, null, null, 0L, null, null, false, false, false, List.of());
    }

    private static final class State {
        private final SessionClaim claim;
        private final String mode;
        private final long targetBytes;
        private final double targetPct;
        private volatile Long lastReleaseAtMs;
        private volatile List<String> limitedReasons = List.of();
        private volatile boolean systemAboveTarget;
        private volatile boolean targetLimited;

        private State(SessionClaim claim, String mode, long targetBytes, double targetPct) {
            this.claim = claim;
            this.mode = mode;
            this.targetBytes = targetBytes;
            this.targetPct = targetPct;
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
                    mode,
                    MODE_EXTRA_BYTES.equals(mode) ? targetBytes : null,
                    MODE_SYSTEM_PERCENT.equals(mode) ? targetPct : null,
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
