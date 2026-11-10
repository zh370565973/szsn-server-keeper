package com.szsn.srm.resource;

import com.szsn.srm.common.ApiBody;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.system.ControlAvailability;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class UnavailableControlController {
    private final ControlAvailability availability;

    public UnavailableControlController(ControlAvailability availability) {
        this.availability = availability;
    }

    @PostMapping("/resource/preflight")
    public ApiBody<Void> preflight() {
        return reject();
    }

    @PostMapping("/executions")
    public ApiBody<Void> start() {
        return reject();
    }

    @PatchMapping("/executions/{id}/targets")
    public ApiBody<Void> targets(@PathVariable String id) {
        return reject();
    }

    @PostMapping("/executions/{id}/pause")
    public ApiBody<Void> pause(@PathVariable String id) {
        return reject();
    }

    @PostMapping("/executions/{id}/resume")
    public ApiBody<Void> resume(@PathVariable String id) {
        return reject();
    }

    @PostMapping("/executions/{id}/stop")
    public ApiBody<Void> stop(@PathVariable String id) {
        return reject();
    }

    @PostMapping("/tasks")
    public ApiBody<Void> createTask() {
        return reject();
    }

    @PutMapping("/tasks/{id}")
    public ApiBody<Void> updateTask(@PathVariable String id) {
        return reject();
    }

    @DeleteMapping("/tasks/{id}")
    public ApiBody<Void> deleteTask(@PathVariable String id) {
        return reject();
    }

    @PostMapping("/tasks/{id}/run")
    public ApiBody<Void> runTask(@PathVariable String id) {
        return reject();
    }

    @PostMapping("/schedules")
    public ApiBody<Void> createSchedule() {
        return reject();
    }

    @PutMapping("/schedules/{id}")
    public ApiBody<Void> updateSchedule(@PathVariable String id) {
        return reject();
    }

    @DeleteMapping("/schedules/{id}")
    public ApiBody<Void> deleteSchedule(@PathVariable String id) {
        return reject();
    }

    @PutMapping("/schedules/{id}/enabled")
    public ApiBody<Void> enableSchedule(@PathVariable String id) {
        return reject();
    }

    private ApiBody<Void> reject() {
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CONTROL_UNAVAILABLE", "负载控制不可用",
                Map.of("reasons", availability.reasons()));
    }
}
