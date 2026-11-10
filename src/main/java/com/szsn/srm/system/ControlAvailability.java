package com.szsn.srm.system;

import com.szsn.srm.common.ApiException;
import com.szsn.srm.monitor.CollectorLoop;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ControlAvailability {
    public static final String MEMORY_ENGINE_REASON = "内存单独控制尚未开放";
    public static final String COMBINED_ENGINE_REASON = "联合控制需要内存引擎，当前仅开放 CPU";

    private final Supplier<EnvironmentReport> environment;
    private final StorageHealth storageHealth;

    @Autowired
    public ControlAvailability(CollectorLoop collectorLoop, StorageHealth storageHealth) {
        this(collectorLoop::environment, storageHealth);
    }

    public ControlAvailability(Supplier<EnvironmentReport> environment, StorageHealth storageHealth) {
        this.environment = environment;
        this.storageHealth = storageHealth;
    }

    /**
     * 环境是否允许开启 CPU 负载控制。不含开关/锁存；那些由控制开关单独表达。
     */
    public boolean controlCapable() {
        EnvironmentReport report = environment();
        return report.controlCapable() && storageHealth.healthy();
    }

    public List<String> reasons() {
        List<String> reasons = new ArrayList<>();
        EnvironmentReport report = environment();
        reasons.addAll(report.controlDisabledReasons());
        if (!storageHealth.healthy() && storageHealth.fault() != null) {
            reasons.add(storageHealth.fault());
        }
        return List.copyOf(reasons);
    }

    public EnvironmentReport environment() {
        return environment.get();
    }

    public void rejectMemoryOrCombined() {
        throw unavailable(List.of(MEMORY_ENGINE_REASON, COMBINED_ENGINE_REASON));
    }

    public void rejectMemoryOnly() {
        throw unavailable(List.of(MEMORY_ENGINE_REASON));
    }

    public static ApiException unavailable(List<String> reasons) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CONTROL_UNAVAILABLE", "负载控制不可用",
                Map.of("reasons", reasons));
    }
}
