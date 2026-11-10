package com.szsn.srm.resource;

import com.szsn.srm.common.ApiBody;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.Bodies;
import com.szsn.srm.common.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/resource")
public class ControlSwitchController {
    private final ControlSwitch controlSwitch;

    public ControlSwitchController(ControlSwitch controlSwitch) {
        this.controlSwitch = controlSwitch;
    }

    @PostMapping("/emergency-stop")
    public ApiBody<Map<String, Object>> emergencyStop(HttpServletRequest request) {
        return Bodies.ok(controlSwitch.emergencyStop(operator(request)).toMap());
    }

    @PostMapping("/reset-latch")
    public ApiBody<Map<String, Object>> resetLatch(HttpServletRequest request) {
        return Bodies.ok(controlSwitch.resetLatch(operator(request)).toMap());
    }

    @PutMapping("/control-switch")
    public ApiBody<Map<String, Object>> controlSwitch(@RequestBody(required = false) SwitchCommand command,
                                                      HttpServletRequest request) {
        if (command == null || command.enabled() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "enabled 必须是布尔值");
        }
        return Bodies.ok(controlSwitch.setEnabled(command.enabled(), operator(request)).toMap());
    }

    private static Operator operator(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String name = authentication == null || authentication.getName() == null ? "admin" : authentication.getName();
        return new Operator(name, request.getRemoteAddr(), RequestIds.current());
    }

    public record SwitchCommand(Boolean enabled) {
    }
}
