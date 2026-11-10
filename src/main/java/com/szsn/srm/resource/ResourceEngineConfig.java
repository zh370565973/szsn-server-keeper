package com.szsn.srm.resource;

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
}
