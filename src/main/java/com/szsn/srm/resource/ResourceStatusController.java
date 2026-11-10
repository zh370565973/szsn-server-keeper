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
        body.put("budget", cpuControl.memoryBudgetView());
        Map<String, Object> memory = cpuControl.memoryLoadView();
        body.put("memory", memory);
        body.put("loadEngine", "CPU_MEMORY");
        Map<String, Object> cpu = cpuControl.cpuStatus();
        body.put("cpu", cpu);
        Map<String, Object> active = enrichActive(body.get("activeExecution"), cpu, memory);
        body.put("activeExecution", active);
        return Bodies.ok(body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> enrichActive(Object raw, Map<String, Object> cpu, Map<String, Object> memory) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        Map<String, Object> active = new LinkedHashMap<>((Map<String, Object>) map);
        active.put("targetPct", cpu.get("targetPct"));
        active.put("actualPct", cpu.get("actualPct"));
        active.put("duty", cpu.get("duty"));
        active.put("workerCount", cpu.get("workerCount"));
        Object cpuLimited = cpu.get("limitedReasons");
        Object memLimited = memory.get("limitedReasons");
        List<String> limited = new ArrayList<>();
        if (cpuLimited instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    limited.add(String.valueOf(item));
                }
            }
        }
        if (memLimited instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    limited.add(String.valueOf(item));
                }
            }
        }
        active.put("limitedReasons", List.copyOf(limited));
        Object cpuVersion = cpu.get("configVersion");
        Object memVersion = memory.get("configVersion");
        active.put("configVersion", cpuVersion != null ? cpuVersion : memVersion);
        active.put("memoryMode", memory.get("memoryMode"));
        active.put("memoryTargetBytes", memory.get("memoryTargetBytes"));
        active.put("memoryTargetPct", memory.get("memoryTargetPct"));
        return active;
    }
}
