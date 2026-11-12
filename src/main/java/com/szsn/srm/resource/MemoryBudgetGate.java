package com.szsn.srm.resource;

import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.Units;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/**
 * 分配器与三重预算的接入点。固定容量（EXTRA_BYTES）超过当前可新增预算时返回 422，
 * 且不改变持有量。一次性 {@link #growTo} 供准入测试；生产增长走 {@link ChunkedMemoryGrower}。
 */
public final class MemoryBudgetGate {
    public static final String BUDGET_EXCEEDED = "MEMORY_BUDGET_EXCEEDED";

    private final MemoryAllocator allocator;
    private final MemorySampleSource samples;
    private final long taskCapBytes;

    public MemoryBudgetGate(MemoryAllocator allocator, MemorySampleSource samples, long taskCapBytes) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.samples = Objects.requireNonNull(samples, "samples");
        if (taskCapBytes <= 0) {
            throw new IllegalArgumentException("任务上限必须为正");
        }
        this.taskCapBytes = taskCapBytes;
    }

    public static MemoryBudgetGate withDefaults(MemoryAllocator allocator, MemorySampleSource samples) {
        return new MemoryBudgetGate(allocator, samples, Units.DEFAULT_TASK_CAP);
    }

    public long heldBytes() {
        return allocator.heldBytes();
    }

    public long taskCapBytes() {
        return taskCapBytes;
    }

    public MemoryBudget.Result requireFixedCapacity(long requestedBytes, long heapMaxBytes, long heapUsedBytes) {
        if (requestedBytes < 1) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "memoryTargetBytes 不合法");
        }
        MemoryBudget.Result budget = budgetFor(heapMaxBytes, heapUsedBytes);
        long allowedTotal = Math.addExact(allocator.heldBytes(), budget.additionalBytes());
        if (requestedBytes > allowedTotal) {
            throw exceeded(requestedBytes, allocator.heldBytes(), budget);
        }
        return budget;
    }

    public Map<String, Object> budgetView(long heapMaxBytes, long heapUsedBytes) {
        MemoryBudget.Result budget = budgetFor(heapMaxBytes, heapUsedBytes);
        long held = allocator.heldBytes();
        long additional = budget.additionalBytes();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("additionalBytes", additional);
        view.put("systemReserveBytes", budget.systemReserveBytes());
        view.put("heapReserveBytes", budget.heapReserveBytes());
        view.put("loadCapBytes", budget.loadCapBytes());
        view.put("heldBytes", held);
        view.put("maxAllowedBytes", held + additional);
        view.put("taskCapBytes", taskCapBytes);
        view.put("blockBytes", Units.BLOCK);
        view.put("limitingFactors", budget.limitingFactors());
        return view;
    }

    private MemoryBudget.Result budgetFor(long heapMaxBytes, long heapUsedBytes) {
        MemorySample sample = requireSample();
        MemorySample adjusted = new MemorySample(
                sample.memTotalBytes(),
                sample.memAvailableBytes(),
                heapMaxBytes,
                heapUsedBytes,
                0L);
        return MemoryBudget.compute(budgetInput(adjusted));
    }

    /**
     * 用当前样本与持有量计算三重预算。样本关键字段缺失时 additionalBytes=0。
     */
    public MemoryBudget.Result currentBudget() {
        return MemoryBudget.compute(budgetInput(requireSample()));
    }

    /**
     * 固定容量目标准入：新目标不得超过 {@code held + B}。超限抛 422，分配器不变。
     */
    public MemoryBudget.Result requireFixedCapacity(long requestedBytes) {
        if (requestedBytes < 1) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "memoryTargetBytes 不合法");
        }
        MemoryBudget.Result budget = currentBudget();
        long allowedTotal = Math.addExact(allocator.heldBytes(), budget.additionalBytes());
        if (requestedBytes > allowedTotal) {
            throw exceeded(requestedBytes, allocator.heldBytes(), budget);
        }
        return budget;
    }

    /**
     * 在预算内把假持有量一次调到目标。超预算抛 422 且不改持有量；减少目标只减计数。
     * 分块限速增长请用 {@link ChunkedMemoryGrower}。
     */
    public long growTo(long targetBytes) {
        requireFixedCapacity(targetBytes);
        long held = allocator.heldBytes();
        if (targetBytes == held) {
            return held;
        }
        if (targetBytes < held) {
            return allocator.release(held - targetBytes);
        }
        long need = targetBytes - held;
        MemoryBudget.Result budget = currentBudget();
        if (need > budget.additionalBytes()) {
            throw exceeded(targetBytes, held, budget);
        }
        return allocator.grow(need);
    }

    public Map<String, Object> budgetView() {
        MemoryBudget.Result budget = currentBudget();
        long held = allocator.heldBytes();
        long additional = budget.additionalBytes();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("additionalBytes", additional);
        view.put("systemReserveBytes", budget.systemReserveBytes());
        view.put("heapReserveBytes", budget.heapReserveBytes());
        view.put("loadCapBytes", budget.loadCapBytes());
        view.put("heldBytes", held);
        // 可输入的固定容量最大值（已按 4MiB 对齐），超限须禁止提交，不能静默改小
        view.put("maxAllowedBytes", held + additional);
        view.put("taskCapBytes", taskCapBytes);
        view.put("blockBytes", Units.BLOCK);
        view.put("limitingFactors", budget.limitingFactors());
        return view;
    }

    private MemoryBudget.Input budgetInput(MemorySample sample) {
        long memTotal = sample.memTotalBytes() == null ? -1L : sample.memTotalBytes();
        long memAvailable = sample.memAvailableBytes() == null ? -1L : sample.memAvailableBytes();
        long heapMax = sample.heapMaxBytes() == null ? 0L : sample.heapMaxBytes();
        long heapUsed = sample.heapUsedBytes() == null ? -1L : sample.heapUsedBytes();
        if (memTotal < 0 || memAvailable < 0 || heapUsed < 0) {
            return new MemoryBudget.Input(0, 0, 0, 0, allocator.heldBytes(), taskCapBytes);
        }
        return new MemoryBudget.Input(
                memTotal,
                memAvailable,
                heapMax,
                heapUsed,
                allocator.heldBytes(),
                taskCapBytes);
    }

    private MemorySample requireSample() {
        MemorySample sample = samples.sample();
        if (sample == null) {
            return new MemorySample(null, null, null, null);
        }
        return sample;
    }

    private static ApiException exceeded(long requestedBytes, long heldBytes, MemoryBudget.Result budget) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requestedBytes", requestedBytes);
        details.put("allowedAdditionalBytes", budget.additionalBytes());
        details.put("maxAllowedBytes", heldBytes + budget.additionalBytes());
        details.put("limitingFactors", List.copyOf(budget.limitingFactors()));
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, BUDGET_EXCEEDED,
                "固定内存目标超过当前预算", details);
    }
}
