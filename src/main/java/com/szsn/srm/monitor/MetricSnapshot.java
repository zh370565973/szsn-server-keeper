package com.szsn.srm.monitor;

import java.util.List;

public record MetricSnapshot(
        long sequence,
        String bootId,
        long observedAt,
        long ageMs,
        Quality quality,
        List<String> missingFields,
        boolean criticalComplete,
        HostView host,
        CpuView cpu,
        MemoryView memory,
        JvmView jvm,
        List<MountView> mounts,
        List<DiskView> disks,
        NetworkView network) {

    public MetricSnapshot freshen(long now) {
        long age = Math.max(0, now - observedAt);
        boolean optionalMissing = missingFields != null && !missingFields.isEmpty();
        Quality assessed = SnapshotQuality.assess(criticalComplete, optionalMissing, age);
        return new MetricSnapshot(sequence, bootId, observedAt, age, assessed, missingFields, criticalComplete,
                host, cpu, memory, jvm, mounts, disks, network);
    }

    public record HostView(
            String hostname,
            String osName,
            String osVersion,
            String kernel,
            String cpuModel,
            Integer logicalCpus,
            String jdkVersion,
            Long systemUptimeSeconds,
            Long processUptimeMs) {
    }

    public record CpuView(
            Double overallBusyPct,
            Double userPct,
            Double systemPct,
            Double idlePct,
            Double iowaitPct,
            Double stealPct,
            Double load1,
            Double load5,
            Double load15) {
    }

    public record MemoryView(
            Long totalBytes,
            Long availableBytes,
            Long usedBytes,
            Long cacheBytes,
            Long swapTotalBytes,
            Long swapUsedBytes) {
    }

    public record JvmView(
            Long heapUsedBytes,
            Long heapCommittedBytes,
            Long heapMaxBytes,
            Long nonHeapUsedBytes,
            Long gcCount,
            Long gcTimeMs,
            Integer threadCount,
            Long rssBytes,
            Double processCpuPct) {
    }

    public record MountView(String mount, Long totalBytes, Long usedBytes, Double usedPct) {
    }

    public record DiskView(String name, Double readBytesPerSec, Double writeBytesPerSec) {
    }

    public record NicView(
            String name,
            String status,
            Double rxBytesPerSec,
            Double txBytesPerSec,
            Long rxBytes,
            Long txBytes) {
    }

    public record NetworkView(
            Double rxBytesPerSec,
            Double txBytesPerSec,
            Long rxBytes,
            Long txBytes,
            List<NicView> interfaces) {
    }
}
