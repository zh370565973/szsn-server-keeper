package com.szsn.srm.resource;

import com.szsn.srm.common.Units;
import com.szsn.srm.monitor.SnapshotQuality;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * 按 4 MiB 分块增长假持有量：每块分配前复核停止标志、最新预算和样本时效；
 * 增长速率不超过 32 MiB/s。不分配真实 {@code byte[]}，不实现 SYSTEM_PERCENT。
 */
public final class ChunkedMemoryGrower {
    public static final long CHUNK_BYTES = Units.BLOCK;
    public static final long RATE_BYTES_PER_SEC = Units.MEMORY_GROWTH_BYTES_PER_SEC;
    public static final long CHUNK_INTERVAL_NANOS =
            TimeUnit.SECONDS.toNanos(1) * CHUNK_BYTES / RATE_BYTES_PER_SEC;
    public static final long STALE_FORBID_MS = SnapshotQuality.STALE_AFTER_MS;

    private final MemoryAllocator allocator;
    private final MemoryBudgetGate budget;
    private final MemorySampleSource samples;
    private final BooleanSupplier forbidGrowth;
    private final NanoTimeSource nanos;
    private final NanosSleeper sleeper;
    private final LongConsumer heldObserver;

    public ChunkedMemoryGrower(MemoryAllocator allocator, MemoryBudgetGate budget, MemorySampleSource samples) {
        this(allocator, budget, samples, () -> false, System::nanoTime, LockSupport::parkNanos, held -> {
        });
    }

    public ChunkedMemoryGrower(
            MemoryAllocator allocator,
            MemoryBudgetGate budget,
            MemorySampleSource samples,
            BooleanSupplier forbidGrowth,
            NanoTimeSource nanos,
            NanosSleeper sleeper,
            LongConsumer heldObserver) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.samples = Objects.requireNonNull(samples, "samples");
        this.forbidGrowth = forbidGrowth == null ? () -> false : forbidGrowth;
        this.nanos = nanos == null ? System::nanoTime : nanos;
        this.sleeper = sleeper == null ? LockSupport::parkNanos : sleeper;
        this.heldObserver = heldObserver == null ? held -> {
        } : heldObserver;
    }

    /**
     * 把假持有量调到对齐后的目标。增大时逐块检查与限速；减少时立即释放多余块。
     */
    public GrowResult growToward(long targetBytes, WorkerPermit permit) {
        requireAligned(targetBytes);
        Objects.requireNonNull(permit, "permit");
        long held = allocator.heldBytes();
        if (targetBytes < held) {
            long next = allocator.release(held - targetBytes);
            heldObserver.accept(next);
            return new GrowResult(next, 0L, StopReason.COMPLETED, null);
        }
        if (targetBytes == held) {
            return new GrowResult(held, 0L, StopReason.COMPLETED, null);
        }

        long grown = 0L;
        long nextAllowedAt = nanos.nanoTime();
        while (allocator.heldBytes() < targetBytes) {
            if (!permit.valid()) {
                return result(grown, StopReason.STOPPED, "STOP_FLAG");
            }
            if (Thread.currentThread().isInterrupted()) {
                return result(grown, StopReason.INTERRUPTED, "INTERRUPTED");
            }
            if (forbidGrowth.getAsBoolean()) {
                return result(grown, StopReason.FORBIDDEN, "FORBID_MEMORY_GROWTH");
            }

            MemorySample sample = samples.sample();
            if (!freshEnough(sample)) {
                return result(grown, StopReason.METRIC_STALE, "METRIC_STALE_3S");
            }

            MemoryBudget.Result current = budget.currentBudget();
            if (current.additionalBytes() < CHUNK_BYTES) {
                return result(grown, StopReason.BUDGET_EXHAUSTED,
                        current.limitingFactors().isEmpty() ? "NO_BUDGET" : current.limitingFactors().get(0));
            }

            long now = nanos.nanoTime();
            if (now < nextAllowedAt) {
                try {
                    sleeper.sleepNanos(nextAllowedAt - now);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return result(grown, StopReason.INTERRUPTED, "INTERRUPTED");
                }
                if (!permit.valid()) {
                    return result(grown, StopReason.STOPPED, "STOP_FLAG");
                }
            }

            // 等待后再复核，避免限速窗口内预算或时效已变
            if (!permit.valid()) {
                return result(grown, StopReason.STOPPED, "STOP_FLAG");
            }
            if (forbidGrowth.getAsBoolean()) {
                return result(grown, StopReason.FORBIDDEN, "FORBID_MEMORY_GROWTH");
            }
            sample = samples.sample();
            if (!freshEnough(sample)) {
                return result(grown, StopReason.METRIC_STALE, "METRIC_STALE_3S");
            }
            current = budget.currentBudget();
            if (current.additionalBytes() < CHUNK_BYTES) {
                return result(grown, StopReason.BUDGET_EXHAUSTED,
                        current.limitingFactors().isEmpty() ? "NO_BUDGET" : current.limitingFactors().get(0));
            }
            if (allocator.heldBytes() + CHUNK_BYTES > targetBytes) {
                return result(grown, StopReason.COMPLETED, null);
            }

            long after = allocator.grow(CHUNK_BYTES);
            heldObserver.accept(after);
            grown += CHUNK_BYTES;
            nextAllowedAt = nanos.nanoTime() + CHUNK_INTERVAL_NANOS;
        }
        return result(grown, StopReason.COMPLETED, null);
    }

    public static void requireAligned(long bytes) {
        if (bytes < 0 || bytes % CHUNK_BYTES != 0) {
            throw new IllegalArgumentException("内存目标必须是 4 MiB 的非负整数倍");
        }
    }

    public static boolean freshEnough(MemorySample sample) {
        if (sample == null || sample.ageMs() == null) {
            return false;
        }
        if (sample.memTotalBytes() == null
                || sample.memAvailableBytes() == null
                || sample.heapUsedBytes() == null) {
            return false;
        }
        return sample.ageMs() <= STALE_FORBID_MS;
    }

    private GrowResult result(long grown, StopReason reason, String detail) {
        return new GrowResult(allocator.heldBytes(), grown, reason, detail);
    }

    public enum StopReason {
        COMPLETED,
        STOPPED,
        BUDGET_EXHAUSTED,
        METRIC_STALE,
        FORBIDDEN,
        INTERRUPTED
    }

    public record GrowResult(long heldBytes, long grownBytes, StopReason reason, String detail) {
    }

    @FunctionalInterface
    public interface NanoTimeSource {
        long nanoTime();
    }

    @FunctionalInterface
    public interface NanosSleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }
}
