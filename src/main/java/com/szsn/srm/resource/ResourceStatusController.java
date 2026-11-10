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

    public ResourceStatusController(ControlAvailability availability, ControlSwitch controlSwitch) {
        this.availability = availability;
        this.controlSwitch = controlSwitch;
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
        body.put("controlCapable", false);
        body.put("controlDisabledReasons", List.copyOf(reasons));
        body.put("budget", null);
        body.put("loadEngine", "NOT_INSTALLED");
        return Bodies.ok(body);
    }
}
