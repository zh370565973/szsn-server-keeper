package com.szsn.srm.system;

import com.szsn.srm.common.ApiBody;
import com.szsn.srm.common.Bodies;
import com.szsn.srm.common.SrmProperties;
import com.szsn.srm.common.Units;
import com.szsn.srm.history.RetentionPolicy;
import com.szsn.srm.resource.ControlState;
import com.szsn.srm.resource.ControlSwitch;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SystemController {
    private final ControlAvailability availability;
    private final StorageHealth storageHealth;
    private final SrmProperties properties;
    private final Environment environment;
    private final ControlSwitch controlSwitch;

    public SystemController(ControlAvailability availability, StorageHealth storageHealth, SrmProperties properties,
                            Environment environment, ControlSwitch controlSwitch) {
        this.availability = availability;
        this.storageHealth = storageHealth;
        this.properties = properties;
        this.environment = environment;
        this.controlSwitch = controlSwitch;
    }

    @GetMapping("/system/info")
    public ApiBody<Map<String, Object>> info() {
        EnvironmentReport report = availability.environment();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hostname", report.hostname());
        body.put("osName", report.osName());
        body.put("osVersion", report.osVersion());
        body.put("kernel", report.kernel());
        body.put("cpuModel", report.cpuModel());
        body.put("logicalCpus", report.logicalCpus());
        body.put("jdkVersion", report.jdkVersion());
        body.put("memTotalBytes", report.memTotalBytes());
        body.put("cgroupVersion", report.cgroupVersion());
        body.put("controlCapable", availability.controlCapable());
        body.put("controlDisabledReasons", availability.reasons());
        body.put("dataDiskFreeBytes", report.dataDiskFreeBytes());
        body.put("dataDiskFreeRatio", report.dataDiskFreeRatio());
        body.put("storageHealthy", storageHealth.healthy());
        body.put("storageFault", storageHealth.fault());
        body.put("phase", "M3");
        body.put("loadEngine", "CPU_MEMORY");
        body.put("acceptance", "开发验证通过，目标环境待验收");
        return Bodies.ok(body);
    }

    @GetMapping("/settings")
    public ApiBody<Map<String, Object>> settings() {
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("cpuTargetDefaultPct", 30);
        thresholds.put("cpuTargetMinPct", 1);
        thresholds.put("cpuTargetMaxPct", 60);
        thresholds.put("cpuHardMaxPct", 70);
        thresholds.put("cpuDeadbandPp", 3);
        thresholds.put("memoryBlockBytes", Units.BLOCK);
        thresholds.put("memoryGrowthBytesPerSec", Units.MEMORY_GROWTH_BYTES_PER_SEC);
        thresholds.put("memoryTaskCapBytes", Units.DEFAULT_TASK_CAP);
        thresholds.put("systemMemoryTargetDefaultPct", 50);
        thresholds.put("systemReserve", "max(总内存×20%, 1GiB)");
        thresholds.put("heapLoadHardCap", "Xmx×50%");
        thresholds.put("jvmReserve", "max(Xmx×25%, 256MiB)");
        thresholds.put("guardBytes", Units.GUARD);
        thresholds.put("historyBucketSeconds", 10);
        thresholds.put("historyRetentionDays", RetentionPolicy.METRIC_RETENTION_MS / 86_400_000L);
        thresholds.put("executionRetentionDays", RetentionPolicy.EXECUTION_RETENTION_MS / 86_400_000L);
        thresholds.put("executionRecordCap", RetentionPolicy.EXECUTION_CAP);
        thresholds.put("operationLogRetentionDays", RetentionPolicy.LOG_RETENTION_MS / 86_400_000L);
        thresholds.put("operationLogCap", RetentionPolicy.LOG_CAP);
        thresholds.put("retentionBatchRows", RetentionPolicy.BATCH_LIMIT);
        thresholds.put("maxSseConnections", 5);
        ControlState state = controlSwitch.state();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timezone", properties.getTimezone());
        body.put("listen", environment.getProperty("server.address", "127.0.0.1") + ":" + environment.getProperty("server.port", "8080"));
        body.put("controlSwitch", state.controlSwitch());
        body.put("controlSwitchPolicy", "每次服务启动均为关闭，需要人工开启。打开开关不会解除急停锁存。");
        body.put("emergencyLatched", state.emergencyLatched());
        body.put("latchReason", state.latchReason());
        body.put("healthyForMs", state.healthyForMs());
        body.put("latchResetReady", state.latchResetReady());
        body.put("thresholdsReadOnly", true);
        body.put("thresholds", thresholds);
        body.put("deploy", properties.locations());
        body.put("remoteCidrConfigured", properties.getRemote().getCidr() != null && !properties.getRemote().getCidr().isBlank());
        return Bodies.ok(body);
    }
}
