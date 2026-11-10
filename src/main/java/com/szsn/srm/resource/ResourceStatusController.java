package com.szsn.srm.resource;

import com.szsn.srm.common.ApiBody;
import com.szsn.srm.common.Bodies;
import com.szsn.srm.system.ControlAvailability;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/resource")
public class ResourceStatusController {
    private final ControlAvailability availability;

    public ResourceStatusController(ControlAvailability availability) {
        this.availability = availability;
    }

    @GetMapping("/status")
    public ApiBody<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("controlSwitch", "OFF");
        body.put("emergencyLatched", false);
        body.put("latchReason", null);
        body.put("activeExecution", null);
        body.put("controlCapable", false);
        body.put("controlDisabledReasons", availability.reasons());
        body.put("budget", null);
        body.put("loadEngine", "NOT_INSTALLED");
        return Bodies.ok(body);
    }
}
