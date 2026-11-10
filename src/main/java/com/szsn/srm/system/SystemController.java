package com.szsn.srm.system;

import com.szsn.srm.common.ApiBody;
import com.szsn.srm.common.Bodies;
import com.szsn.srm.common.SrmProperties;
import com.szsn.srm.common.Units;
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
        body.put("phase", "M2");
        body.put("loadEngine", "CPU");
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
        thresholds.put("memoryGrowthBytesPerSec", 32L * Units.MIB);
        thresholds.put("memoryTaskCapBytes", Units.DEFAULT_TASK_CAP);
        thresholds.put("systemMemoryTargetDefaultPct", 50);
        thresholds.put("systemReserve", "max(总内存×20%, 1GiB)");
        thresholds.put("heapLoadHardCap", "Xmx×50%");
        thresholds.put("jvmReserve", "max(Xmx×25%, 256MiB)");
        thresholds.put("guardBytes", Units.GUARD);
        thresholds.put("historyBucketSeconds", 10);
        thresholds.put("historyRetentionDays", 7);
        thresholds.put("executionRetentionDays", 90);
        thresholds.put("operationLogRetentionDays", 30);
        thresholds.put("maxSseConnections", 5);
        Map<String, Object> deploy = new LinkedHashMap<>();
        deploy.put("jar", "/opt/server-resource-manager");
        deploy.put("config", "/etc/server-resource-manager");
        deploy.put("data", "/var/lib/server-resource-manager");
        deploy.put("log", "/var/log/server-resource-manager");
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
        body.put("deploy", deploy);
        body.put("remoteCidrConfigured", properties.getRemote().getCidr() != null && !properties.getRemote().getCidr().isBlank());
        return Bodies.ok(body);
    }
}
