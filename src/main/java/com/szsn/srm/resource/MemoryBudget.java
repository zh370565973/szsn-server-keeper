package com.szsn.srm.resource;

import com.szsn.srm.common.Units;
import java.util.ArrayList;
import java.util.List;

public final class MemoryBudget {
    private MemoryBudget() {
    }

    public record Input(
            long memTotalBytes,
            long memAvailableBytes,
            long heapMaxBytes,
            long heapUsedBytes,
            long heldBytes,
            long taskCapBytes) {
    }

    public record Result(
            long additionalBytes,
            long systemReserveBytes,
            long heapReserveBytes,
            long loadCapBytes,
            List<String> limitingFactors) {
    }

    public static long systemReserve(long memTotalBytes) {
        if (memTotalBytes < 0) {
            throw new IllegalArgumentException("内存总量不能为负");
        }
        return Math.max(memTotalBytes * 20 / 100, Units.GIB);
    }

    public static long heapReserve(long heapMaxBytes) {
        if (heapMaxBytes <= 0) {
            return 256L * Units.MIB;
        }
        return Math.max(heapMaxBytes / 4, 256L * Units.MIB);
    }

    public static Result compute(Input input) {
        if (input.memTotalBytes() < 0 || input.memAvailableBytes() < 0 || input.heapUsedBytes() < 0 || input.heldBytes() < 0) {
            throw new IllegalArgumentException("容量不能为负");
        }
        if (input.taskCapBytes() <= 0) {
            throw new IllegalArgumentException("任务上限必须为正");
        }
        long systemReserve = systemReserve(input.memTotalBytes());
        long heapReserve = heapReserve(input.heapMaxBytes());
        List<String> factors = new ArrayList<>();
        if (input.heapMaxBytes() <= 0) {
            factors.add("HEAP_MAX_UNAVAILABLE");
            return new Result(0, systemReserve, heapReserve, 0, List.copyOf(factors));
        }
        long loadCap = Math.min(input.taskCapBytes(), input.heapMaxBytes() / 2);
        long systemRoom = input.memAvailableBytes() - systemReserve - Units.GUARD;
        long heapRoom = input.heapMaxBytes() - input.heapUsedBytes() - heapReserve - Units.GUARD;
        long taskRoom = loadCap - input.heldBytes();
        long raw = Math.min(systemRoom, Math.min(heapRoom, taskRoom));
        if (systemRoom <= heapRoom && systemRoom <= taskRoom) {
            factors.add("SYSTEM_AVAILABLE");
        }
        if (heapRoom <= systemRoom && heapRoom <= taskRoom) {
            factors.add("HEAP_AVAILABLE");
        }
        if (taskRoom <= systemRoom && taskRoom <= heapRoom) {
            factors.add("TASK_CAP");
        }
        long aligned = alignDown(Math.max(0, raw));
        if (raw > 0 && aligned == 0) {
            factors.add("ALIGNMENT");
        }
        if (raw <= 0 && factors.isEmpty()) {
            factors.add("NO_BUDGET");
        }
        return new Result(aligned, systemReserve, heapReserve, loadCap, List.copyOf(factors));
    }

    public static long alignDown(long bytes) {
        if (bytes <= 0) {
            return 0;
        }
        return bytes - Math.floorMod(bytes, Units.BLOCK);
    }
}
