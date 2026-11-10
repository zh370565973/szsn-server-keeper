package com.szsn.srm.monitor;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSFileStore;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

@Component
public class OshiMetricSampler {
    private final SystemInfo systemInfo;
    private final String startupError;
    private ProcStatMath.Counters previousCpu;
    private boolean cpuBaselineReady;
    private final Map<String, long[]> previousDisk = new HashMap<>();
    private final Map<String, long[]> previousNic = new HashMap<>();
    private long previousDiskAt;
    private long previousNicAt;

    public OshiMetricSampler() {
        SystemInfo created = null;
        String error = null;
        try {
            created = new SystemInfo();
        } catch (Throwable throwable) {
            error = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }
        this.systemInfo = created;
        this.startupError = error;
    }

    public String startupError() {
        return startupError;
    }

    public SystemInfo systemInfo() {
        return systemInfo;
    }

    public MetricSnapshot sample(long sequence, String bootId) {
        long now = System.currentTimeMillis();
        List<String> missing = new ArrayList<>();
        if (startupError != null) {
            missing.add("oshi");
        }
        MetricSnapshot.HostView host = readHost(missing);
        MetricSnapshot.CpuView cpu = readCpu(missing);
        MetricSnapshot.MemoryView memory = readMemory(missing);
        MetricSnapshot.JvmView jvm = readJvm(missing);
        List<MetricSnapshot.MountView> mounts = readMounts(missing);
        List<MetricSnapshot.DiskView> disks = readDisks(now, missing);
        MetricSnapshot.NetworkView network = readNetwork(now, missing);
        boolean critical = memory.totalBytes() != null && memory.availableBytes() != null;
        if (!critical) {
            if (memory.totalBytes() == null) {
                missing.add("memory.totalBytes");
            }
            if (memory.availableBytes() == null) {
                missing.add("memory.availableBytes");
            }
        }
        boolean optionalMissing = !missing.isEmpty();
        Quality quality = SnapshotQuality.assess(critical, optionalMissing, 0);
        return new MetricSnapshot(sequence, bootId, now, 0, quality, List.copyOf(missing), critical,
                host, cpu, memory, jvm, mounts, disks, network);
    }

    private MetricSnapshot.HostView readHost(List<String> missing) {
        String hostname = null;
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String kernel = osVersion;
        String cpuModel = null;
        Integer logical = null;
        Long uptime = null;
        if (systemInfo != null) {
            try {
                OperatingSystem os = systemInfo.getOperatingSystem();
                hostname = os.getNetworkParams().getHostName();
                osName = os.getFamily();
                osVersion = os.getVersionInfo().getVersion();
                kernel = os.getVersionInfo().getBuildNumber();
                if (kernel == null || kernel.isBlank()) {
                    kernel = osVersion;
                }
                uptime = os.getSystemUptime();
                CentralProcessor processor = systemInfo.getHardware().getProcessor();
                cpuModel = processor.getProcessorIdentifier().getName();
                logical = processor.getLogicalProcessorCount();
            } catch (Throwable throwable) {
                missing.add("host");
            }
        }
        if (Files.isRegularFile(Path.of("/proc/sys/kernel/osrelease"))) {
            try {
                kernel = Files.readString(Path.of("/proc/sys/kernel/osrelease")).trim();
            } catch (IOException ignored) {
                missing.add("kernel");
            }
        }
        if (hostname == null || hostname.isBlank()) {
            missing.add("hostname");
            hostname = null;
        }
        return new MetricSnapshot.HostView(
                hostname,
                osName,
                osVersion,
                kernel,
                cpuModel,
                logical,
                System.getProperty("java.version"),
                uptime,
                ManagementFactory.getRuntimeMXBean().getUptime());
    }

    private MetricSnapshot.CpuView readCpu(List<String> missing) {
        ProcStatMath.Counters current = readProcCounters();
        if (current == null && systemInfo != null) {
            current = readOshiCounters();
        }
        ProcStatMath.Percents percents = null;
        if (current != null && cpuBaselineReady) {
            percents = ProcStatMath.between(previousCpu, current);
            if (percents == null) {
                missing.add("cpu.countersReset");
            }
        } else if (current != null) {
            missing.add("cpu.warmup");
        } else {
            missing.add("cpu");
        }
        if (current != null) {
            previousCpu = current;
            cpuBaselineReady = true;
        }
        Double load1 = null;
        Double load5 = null;
        Double load15 = null;
        if (systemInfo != null) {
            try {
                double[] load = systemInfo.getHardware().getProcessor().getSystemLoadAverage(3);
                load1 = positiveLoad(load, 0);
                load5 = positiveLoad(load, 1);
                load15 = positiveLoad(load, 2);
            } catch (Throwable throwable) {
                missing.add("cpu.load");
            }
        }
        if (load1 == null) {
            missing.add("cpu.load1");
        }
        if (percents == null) {
            return new MetricSnapshot.CpuView(null, null, null, null, null, null, load1, load5, load15);
        }
        return new MetricSnapshot.CpuView(
                percents.overallBusyPct(),
                percents.userPct(),
                percents.systemPct(),
                percents.idlePct(),
                percents.iowaitPct(),
                percents.stealPct(),
                load1, load5, load15);
    }

    private ProcStatMath.Counters readProcCounters() {
        Path stat = Path.of("/proc/stat");
        if (!Files.isRegularFile(stat)) {
            return null;
        }
        try {
            return ProcStatMath.parseAggregate(Files.readString(stat));
        } catch (IOException exception) {
            return null;
        }
    }

    private ProcStatMath.Counters readOshiCounters() {
        try {
            long[] ticks = systemInfo.getHardware().getProcessor().getSystemCpuLoadTicks();
            return new ProcStatMath.Counters(
                    tick(ticks, CentralProcessor.TickType.USER),
                    tick(ticks, CentralProcessor.TickType.NICE),
                    tick(ticks, CentralProcessor.TickType.SYSTEM),
                    tick(ticks, CentralProcessor.TickType.IDLE),
                    tick(ticks, CentralProcessor.TickType.IOWAIT),
                    tick(ticks, CentralProcessor.TickType.IRQ),
                    tick(ticks, CentralProcessor.TickType.SOFTIRQ),
                    tick(ticks, CentralProcessor.TickType.STEAL));
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static long tick(long[] ticks, CentralProcessor.TickType type) {
        int index = type.ordinal();
        if (index < 0 || index >= ticks.length) {
            return 0;
        }
        return ticks[index];
    }

    private MetricSnapshot.MemoryView readMemory(List<String> missing) {
        if (Files.isRegularFile(Path.of("/proc/meminfo"))) {
            try {
                MeminfoParser.MemoryReading reading = MeminfoParser.parse(Files.readString(Path.of("/proc/meminfo")));
                return toMemory(reading.totalBytes(), reading.availableBytes(), reading.cacheBytes(),
                        reading.swapTotalBytes(), reading.swapUsedBytes(), missing);
            } catch (IOException exception) {
                missing.add("memory.meminfo");
            }
        }
        if (systemInfo == null) {
            return emptyMemory();
        }
        try {
            GlobalMemory memory = systemInfo.getHardware().getMemory();
            Long total = positive(memory.getTotal());
            Long available = nonNegative(memory.getAvailable());
            Long swapTotal = nonNegative(memory.getVirtualMemory().getSwapTotal());
            Long swapUsed = nonNegative(memory.getVirtualMemory().getSwapUsed());
            return toMemory(total, available, null, swapTotal, swapUsed, missing);
        } catch (Throwable throwable) {
            missing.add("memory");
            return emptyMemory();
        }
    }

    private MetricSnapshot.MemoryView toMemory(Long total, Long available, Long cache, Long swapTotal, Long swapUsed, List<String> missing) {
        Long used = null;
        if (total != null && available != null) {
            if (available > total) {
                missing.add("memory.available");
                available = null;
            } else {
                used = total - available;
            }
        }
        if (cache == null) {
            missing.add("memory.cacheBytes");
        }
        return new MetricSnapshot.MemoryView(total, available, used, cache, swapTotal, swapUsed);
    }

    private static MetricSnapshot.MemoryView emptyMemory() {
        return new MetricSnapshot.MemoryView(null, null, null, null, null, null);
    }

    private MetricSnapshot.JvmView readJvm(List<String> missing) {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        long gcCount = 0;
        long gcTime = 0;
        boolean gcKnown = false;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getCollectionCount() >= 0) {
                gcCount += bean.getCollectionCount();
                gcKnown = true;
            }
            if (bean.getCollectionTime() >= 0) {
                gcTime += bean.getCollectionTime();
            }
        }
        Long rss = null;
        if (systemInfo != null) {
            try {
                OperatingSystem os = systemInfo.getOperatingSystem();
                OSProcess process = os.getProcess(os.getProcessId());
                if (process != null && process.getResidentMemory() > 0) {
                    rss = process.getResidentMemory();
                }
            } catch (Throwable throwable) {
                missing.add("jvm.rss");
            }
        }
        if (rss == null) {
            missing.add("jvm.rssBytes");
        }
        Double processCpu = null;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean sun) {
            double load = sun.getProcessCpuLoad();
            if (load >= 0) {
                processCpu = load * 100.0;
            }
        }
        if (processCpu == null) {
            missing.add("jvm.processCpuPct");
        }
        long heapMax = heap.getMax();
        return new MetricSnapshot.JvmView(
                heap.getUsed(),
                heap.getCommitted(),
                heapMax > 0 ? heapMax : null,
                nonHeap.getUsed(),
                gcKnown ? gcCount : null,
                gcKnown ? gcTime : null,
                ManagementFactory.getThreadMXBean().getThreadCount(),
                rss,
                processCpu);
    }

    private List<MetricSnapshot.MountView> readMounts(List<String> missing) {
        if (systemInfo == null) {
            missing.add("disk.mounts");
            return List.of();
        }
        try {
            List<MetricSnapshot.MountView> mounts = new ArrayList<>();
            for (OSFileStore store : systemInfo.getOperatingSystem().getFileSystem().getFileStores()) {
                long total = store.getTotalSpace();
                if (total <= 0) {
                    continue;
                }
                long usable = store.getUsableSpace();
                long used = Math.max(0, total - usable);
                mounts.add(new MetricSnapshot.MountView(store.getMount(), total, used, used * 100.0 / total));
            }
            return List.copyOf(mounts);
        } catch (Throwable throwable) {
            missing.add("disk.mounts");
            return List.of();
        }
    }

    private List<MetricSnapshot.DiskView> readDisks(long now, List<String> missing) {
        if (systemInfo == null) {
            missing.add("disk.devices");
            return List.of();
        }
        try {
            List<MetricSnapshot.DiskView> disks = new ArrayList<>();
            long elapsed = previousDiskAt == 0 ? 0 : now - previousDiskAt;
            for (HWDiskStore disk : systemInfo.getHardware().getDiskStores()) {
                disk.updateAttributes();
                long read = disk.getReadBytes();
                long write = disk.getWriteBytes();
                long[] previous = previousDisk.get(disk.getName());
                Double readRate = rate(previous == null ? null : previous[0], read, elapsed);
                Double writeRate = rate(previous == null ? null : previous[1], write, elapsed);
                previousDisk.put(disk.getName(), new long[] {read, write});
                disks.add(new MetricSnapshot.DiskView(disk.getName(), readRate, writeRate));
            }
            previousDiskAt = now;
            return List.copyOf(disks);
        } catch (Throwable throwable) {
            missing.add("disk.devices");
            return List.of();
        }
    }

    private MetricSnapshot.NetworkView readNetwork(long now, List<String> missing) {
        if (systemInfo == null) {
            missing.add("network");
            return new MetricSnapshot.NetworkView(null, null, null, null, List.of());
        }
        try {
            HardwareAbstractionLayer hardware = systemInfo.getHardware();
            List<MetricSnapshot.NicView> nics = new ArrayList<>();
            long elapsed = previousNicAt == 0 ? 0 : now - previousNicAt;
            Double rxRate = 0D;
            Double txRate = 0D;
            long rx = 0;
            long tx = 0;
            boolean rateReady = true;
            boolean any = false;
            for (NetworkIF nic : hardware.getNetworkIFs()) {
                if (!included(nic.getName())) {
                    continue;
                }
                nic.updateAttributes();
                any = true;
                long[] previous = previousNic.get(nic.getName());
                Double nicRx = rate(previous == null ? null : previous[0], nic.getBytesRecv(), elapsed);
                Double nicTx = rate(previous == null ? null : previous[1], nic.getBytesSent(), elapsed);
                previousNic.put(nic.getName(), new long[] {nic.getBytesRecv(), nic.getBytesSent()});
                if (nicRx == null || nicTx == null) {
                    rateReady = false;
                } else {
                    rxRate += nicRx;
                    txRate += nicTx;
                }
                rx += nic.getBytesRecv();
                tx += nic.getBytesSent();
                String status = nic.getIfOperStatus() == null ? null : nic.getIfOperStatus().name();
                nics.add(new MetricSnapshot.NicView(nic.getName(), status, nicRx, nicTx, nic.getBytesRecv(), nic.getBytesSent()));
            }
            previousNicAt = now;
            if (!any) {
                missing.add("network.interfaces");
                return new MetricSnapshot.NetworkView(null, null, null, null, List.of());
            }
            return new MetricSnapshot.NetworkView(rateReady ? rxRate : null, rateReady ? txRate : null, rx, tx, List.copyOf(nics));
        } catch (Throwable throwable) {
            missing.add("network");
            return new MetricSnapshot.NetworkView(null, null, null, null, List.of());
        }
    }

    static boolean included(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.equals("lo") || normalized.matches("lo\\d+") || normalized.contains("loopback")) {
            return false;
        }
        return !(normalized.startsWith("docker")
                || normalized.startsWith("veth")
                || normalized.startsWith("br-")
                || normalized.startsWith("virbr")
                || normalized.startsWith("cni")
                || normalized.startsWith("flannel"));
    }

    private static Double rate(Long previous, long current, long elapsedMs) {
        if (previous == null || elapsedMs <= 0) {
            return null;
        }
        if (current < previous) {
            return null;
        }
        return (current - previous) * 1000.0 / elapsedMs;
    }

    private static Double positiveLoad(double[] load, int index) {
        if (load == null || index >= load.length || load[index] < 0) {
            return null;
        }
        return load[index];
    }

    private static Long positive(long value) {
        return value > 0 ? value : null;
    }

    private static Long nonNegative(long value) {
        return value >= 0 ? value : null;
    }
}
