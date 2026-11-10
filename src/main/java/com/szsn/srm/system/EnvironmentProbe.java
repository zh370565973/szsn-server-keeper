package com.szsn.srm.system;

import com.szsn.srm.common.Units;
import com.szsn.srm.monitor.OshiMetricSampler;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import oshi.hardware.CentralProcessor;
import oshi.software.os.OperatingSystem;

@Component
public class EnvironmentProbe {
    private final OshiMetricSampler sampler;

    public EnvironmentProbe(OshiMetricSampler sampler) {
        this.sampler = sampler;
    }

    public EnvironmentReport probe(Path dataDir) {
        List<String> reasons = new ArrayList<>();
        String osName = System.getProperty("os.name", "");
        boolean linux = osName.toLowerCase(Locale.ROOT).contains("linux");
        String hostname = null;
        String osVersion = System.getProperty("os.version");
        String kernel = osVersion;
        String cpuModel = null;
        int logical = Runtime.getRuntime().availableProcessors();
        Long memTotal = null;
        String cgroup = "none";
        if (sampler.startupError() != null) {
            reasons.add("本机信息库加载失败：" + sampler.startupError());
        }
        if (sampler.systemInfo() != null) {
            try {
                OperatingSystem operatingSystem = sampler.systemInfo().getOperatingSystem();
                hostname = operatingSystem.getNetworkParams().getHostName();
                osName = operatingSystem.getFamily();
                osVersion = operatingSystem.getVersionInfo().getVersion();
                kernel = operatingSystem.getVersionInfo().getBuildNumber();
                CentralProcessor processor = sampler.systemInfo().getHardware().getProcessor();
                cpuModel = processor.getProcessorIdentifier().getName();
                logical = processor.getLogicalProcessorCount();
                long total = sampler.systemInfo().getHardware().getMemory().getTotal();
                memTotal = total > 0 ? total : null;
            } catch (Throwable throwable) {
                reasons.add("读取主机信息失败");
            }
        }
        if (!linux) {
            cgroup = "unsupported";
            reasons.add("当前系统不是 Linux，负载控制只允许在无额外配额的 Linux 虚拟机内启用");
        } else {
            cgroup = diagnoseLinux(reasons, logical, memTotal == null ? 0 : memTotal);
        }
        DiskSpace disk = disk(dataDir);
        if (disk.low()) {
            reasons.add("数据盘剩余空间不足");
        }
        return new EnvironmentReport(
                hostname,
                osName,
                osVersion,
                kernel,
                cpuModel,
                logical,
                System.getProperty("java.version"),
                memTotal,
                cgroup,
                reasons.isEmpty(),
                List.copyOf(reasons),
                disk.freeBytes(),
                disk.freeRatio());
    }

    private String diagnoseLinux(List<String> reasons, int logical, long memTotal) {
        int available = Runtime.getRuntime().availableProcessors();
        if (logical > 0 && available > 0 && available < logical) {
            reasons.add("CPU 可用逻辑核心少于整机，存在 affinity 或 cpuset 限制");
        }
        boolean v2 = Files.isRegularFile(Path.of("/sys/fs/cgroup/cgroup.controllers"));
        boolean v1 = Files.isDirectory(Path.of("/sys/fs/cgroup/memory"));
        String version;
        if (v2) {
            version = "v2";
        } else if (v1) {
            version = "v1";
        } else if (!Files.isRegularFile(Path.of("/proc/self/cgroup"))) {
            reasons.add("无法确定 cgroup 版本");
            return "unknown";
        } else {
            reasons.add("无法确定 cgroup 版本");
            return "unknown";
        }
        String memoryMax = readFirst(
                Path.of("/sys/fs/cgroup/memory.max"),
                Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"));
        if (memoryMax == null) {
            reasons.add("无法确定 cgroup 内存限制");
        } else if (!"max".equalsIgnoreCase(memoryMax) && memTotal > 0) {
            try {
                long limit = Long.parseLong(memoryMax);
                if (limit > 0 && limit < Long.MAX_VALUE / 2 && limit < memTotal * 98 / 100) {
                    reasons.add("检测到相对虚拟机的额外内存限制");
                }
            } catch (NumberFormatException exception) {
                reasons.add("无法解析 cgroup 内存限制");
            }
        }
        String cpus = readFirst(
                Path.of("/sys/fs/cgroup/cpuset.cpus.effective"),
                Path.of("/sys/fs/cgroup/cpuset/cpuset.cpus"));
        if (cpus == null) {
            reasons.add("无法确定 cpuset");
        } else {
            int count = countCpus(cpus);
            if (count > 0 && logical > 0 && count < logical) {
                reasons.add("cpuset 未覆盖全部逻辑核心");
            }
        }
        return version;
    }

    private static String readFirst(Path... paths) {
        for (Path path : paths) {
            if (Files.isRegularFile(path)) {
                try {
                    String text = Files.readString(path).trim();
                    if (!text.isEmpty()) {
                        return text;
                    }
                } catch (IOException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    static int countCpus(String spec) {
        if (spec == null || spec.isBlank() || spec.contains("\n")) {
            return 0;
        }
        int count = 0;
        for (String part : spec.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int dash = token.indexOf('-');
            try {
                if (dash < 0) {
                    Integer.parseInt(token);
                    count++;
                } else {
                    int start = Integer.parseInt(token.substring(0, dash));
                    int end = Integer.parseInt(token.substring(dash + 1));
                    if (end >= start) {
                        count += end - start + 1;
                    }
                }
            } catch (NumberFormatException exception) {
                return 0;
            }
        }
        return count;
    }

    private static DiskSpace disk(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            FileStore store = Files.getFileStore(dataDir);
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            Double ratio = total > 0 ? free * 1.0 / total : null;
            boolean low = free < Units.DISK_FLOOR || (ratio != null && ratio < 0.05);
            return new DiskSpace(free, ratio, low);
        } catch (IOException exception) {
            return new DiskSpace(null, null, true);
        }
    }

    private record DiskSpace(Long freeBytes, Double freeRatio, boolean low) {
    }
}
