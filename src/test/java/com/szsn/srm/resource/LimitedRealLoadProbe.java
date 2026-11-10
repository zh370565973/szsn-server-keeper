package com.szsn.srm.resource;

import com.szsn.srm.common.Units;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 不进入默认测试集。手动运行：有上限的真实内存占用，以及短时 CPU 燃烧。
 * 不调用 System.gc()。
 */
public final class LimitedRealLoadProbe {
    /** 硬上限 64MiB，低于 AT-05 的 256MiB，避免占满虚机。 */
    static final long MEMORY_CAP = 64L * Units.MIB;

    private LimitedRealLoadProbe() {
    }

    public static void main(String[] args) throws Exception {
        long available = memAvailable();
        System.out.println("memAvailableKiB=" + available);
        if (available > 0 && available < (MEMORY_CAP / 1024) * 4) {
            throw new IllegalStateException("可用内存不足以做 64MiB 上限探测");
        }
        System.out.println("baselineCpu=" + cpuLine());
        long rssBefore = vmRssKb();
        long heapBefore = heapUsed();
        ByteArrayMemoryAllocator allocator = new ByteArrayMemoryAllocator();
        MemorySample sample = new MemorySample(16L * Units.GIB, 8L * Units.GIB, Units.GIB, 200L * Units.MIB, 0L);
        MemoryBudgetGate gate = new MemoryBudgetGate(allocator, () -> sample, Units.DEFAULT_TASK_CAP);
        ChunkedMemoryGrower grower = new ChunkedMemoryGrower(allocator, gate, () -> sample);
        ResourceLoadManager sessions = new ResourceLoadManager();
        SessionClaim claim = sessions.claim("probe-mem");
        long started = System.nanoTime();
        ChunkedMemoryGrower.GrowResult grown;
        try {
            grown = grower.growToward(MEMORY_CAP, claim.permit());
        } catch (RuntimeException exception) {
            allocator.clear();
            throw exception;
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        System.out.println("growReason=" + grown.reason() + " held=" + allocator.heldBytes()
                + " chunks=" + allocator.retainedChunks() + " sample=" + allocator.payloadSample()
                + " elapsedMs=" + elapsedMs);
        System.out.println("heapAtPeak=" + heapUsed() + " rssAtPeakKb=" + vmRssKb()
                + " heapBefore=" + heapBefore + " rssBeforeKb=" + rssBefore);
        if (allocator.heldBytes() != MEMORY_CAP || allocator.payloadSample() == 0) {
            allocator.clear();
            throw new IllegalStateException("真实分配未达到上限或页面未被触碰");
        }
        allocator.clear();
        System.out.println("afterClear held=" + allocator.heldBytes()
                + " chunks=" + allocator.retainedChunks()
                + " heap=" + heapUsed()
                + " rssKb=" + vmRssKb()
                + " note=RSS 不作为释放成功条件，且未调用 System.gc");

        long cpuBefore = processTicks();
        long wall = System.nanoTime();
        ChecksumCpuBurner burner = new ChecksumCpuBurner();
        WorkerPermit permit = new ResourceLoadManager().claim("probe-cpu").permit();
        long checksum = burner.burn(1_500_000_000L, permit, () -> true);
        long cpuAfter = processTicks();
        long burnMs = (System.nanoTime() - wall) / 1_000_000L;
        System.out.println("burnMs=" + burnMs + " checksumNonZero=" + (checksum != 0)
                + " processTickDelta=" + (cpuAfter - cpuBefore));
        if (checksum == 0 || cpuAfter <= cpuBefore) {
            throw new IllegalStateException("生产燃烧器没有产生可观察的 CPU 占用");
        }
        System.out.println("cpuAfter=" + cpuLine());
        System.out.println("PROBE_OK capBytes=" + MEMORY_CAP);
    }

    private static long heapUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long vmRssKb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]);
                }
            }
        } catch (Exception ignored) {
            return -1L;
        }
        return -1L;
    }

    private static long memAvailable() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    return Long.parseLong(line.trim().split("\\s+")[1]);
                }
            }
        } catch (Exception ignored) {
            return -1L;
        }
        return -1L;
    }

    private static String cpuLine() {
        try {
            List<String> lines = Files.readAllLines(Path.of("/proc/stat"));
            return lines.isEmpty() ? "" : lines.get(0);
        } catch (Exception exception) {
            return exception.getMessage();
        }
    }

    private static long processTicks() {
        try {
            String stat = Files.readString(Path.of("/proc/self/stat"));
            int close = stat.lastIndexOf(')');
            String[] parts = stat.substring(close + 2).split(" ");
            return Long.parseLong(parts[11]) + Long.parseLong(parts[12]);
        } catch (Exception exception) {
            return -1L;
        }
    }
}
