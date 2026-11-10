package com.szsn.srm.system;

import com.szsn.srm.common.SrmProperties;
import com.szsn.srm.monitor.CollectorLoop;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ControlAvailability {
    private final CollectorLoop collectorLoop;
    private final StorageHealth storageHealth;

    public ControlAvailability(CollectorLoop collectorLoop, StorageHealth storageHealth) {
        this.collectorLoop = collectorLoop;
        this.storageHealth = storageHealth;
    }

    public List<String> reasons() {
        List<String> reasons = new ArrayList<>();
        reasons.add("M1 未安装负载引擎");
        EnvironmentReport report = collectorLoop.environment();
        reasons.addAll(report.controlDisabledReasons());
        if (!storageHealth.healthy() && storageHealth.fault() != null) {
            reasons.add(storageHealth.fault());
        }
        return List.copyOf(reasons);
    }

    public EnvironmentReport environment() {
        return collectorLoop.environment();
    }
}
