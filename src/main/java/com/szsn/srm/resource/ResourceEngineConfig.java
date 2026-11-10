package com.szsn.srm.resource;

import com.szsn.srm.common.Units;
import com.szsn.srm.monitor.MetricSnapshot;
import com.szsn.srm.monitor.SnapshotStore;
import com.szsn.srm.safety.SafetyLoop;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResourceEngineConfig {
    @Bean
    CpuBurner cpuBurner() {
        return new ChecksumCpuBurner();
    }

    @Bean
    CpuEngine cpuEngine(ResourceLoadManager sessions, SnapshotStore store, SafetyLoop safetyLoop, CpuBurner burner) {
        CpuSampleSource samples = () -> {
            MetricSnapshot snapshot = store.latest();
            if (snapshot == null || snapshot.cpu() == null) {
                return null;
            }
            return snapshot.cpu().overallBusyPct();
        };
        return new CpuEngine(sessions, samples, safetyLoop::current, burner);
    }

    @Bean
    MemoryAllocator memoryAllocator() {
        return new FakeMemoryAllocator();
    }

    @Bean
    MemoryBudgetGate memoryBudgetGate(MemoryAllocator allocator, SnapshotStore store) {
        MemorySampleSource samples = () -> {
            MetricSnapshot snapshot = store.latest();
            if (snapshot == null) {
                return null;
            }
            MetricSnapshot.MemoryView memory = snapshot.memory();
            MetricSnapshot.JvmView jvm = snapshot.jvm();
            return new MemorySample(
                    memory == null ? null : memory.totalBytes(),
                    memory == null ? null : memory.availableBytes(),
                    jvm == null ? null : jvm.heapMaxBytes(),
                    jvm == null ? null : jvm.heapUsedBytes());
        };
        return new MemoryBudgetGate(allocator, samples, Units.DEFAULT_TASK_CAP);
    }
}
