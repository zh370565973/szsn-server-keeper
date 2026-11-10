package com.szsn.srm.resource;

import com.szsn.srm.common.ApiBody;
import com.szsn.srm.common.Bodies;
import com.szsn.srm.system.ControlAvailability;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/resource")
public class ResourceStatusController {
    private final ControlAvailability availability;
    private final ControlSwitch controlSwitch;
    private final CpuControlService cpuControl;

    public ResourceStatusController(ControlAvailability availability, ControlSwitch controlSwitch,
                                    CpuControlService cpuControl) {
        this.availability = availability;
        this.controlSwitch = controlSwitch;
        this.cpuControl = cpuControl;
    }

    @GetMapping("/status")
    public ApiBody<Map<String, Object>> status() {
        ControlState state = controlSwitch.state();
        Map<String, Object> body = new LinkedHashMap<>(state.toMap());
        List<String> reasons = new ArrayList<>(availability.reasons());
        if (state.emergencyLatched()) {
            reasons.add("急停已锁存");
        }
        if ("OFF".equals(state.controlSwitch())) {
            reasons.add("控制开关已关闭");
        }
        boolean capable = availability.controlCapable();
        body.put("controlCapable", capable);
        body.put("controlDisabledReasons", List.copyOf(reasons));
        body.put("budget", null);
        body.put("loadEngine", "CPU");
        body.put("cpu", cpuControl.cpuStatus());
        Map<String, Object> active = enrichActive(body.get("activeExecution"), cpuControl.cpuStatus());
        body.put("activeExecution", active);
        return Bodies.ok(body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> enrichActive(Object raw, Map<String, Object> cpu) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        Map<String, Object> active = new LinkedHashMap<>((Map<String, Object>) map);
        active.put("targetPct", cpu.get("targetPct"));
        active.put("actualPct", cpu.get("actualPct"));
        active.put("duty", cpu.get("duty"));
        active.put("workerCount", cpu.get("workerCount"));
        active.put("limitedReasons", cpu.get("limitedReasons"));
        active.put("configVersion", cpu.get("configVersion"));
        return active;
    }
}
