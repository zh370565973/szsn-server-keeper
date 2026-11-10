package com.szsn.srm.resource;

import com.szsn.srm.common.ApiBody;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.Bodies;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CPU 控制写接口。生命周期变更返回 202，前端通过状态/SSE 确认，不假定引擎已调到目标。
 */
@RestController
@RequestMapping("/api/v1")
public class CpuControlController {
    private final CpuControlService control;

    public CpuControlController(CpuControlService control) {
        this.control = control;
    }

    @PostMapping("/resource/preflight")
    public ApiBody<Map<String, Object>> preflight(@RequestBody(required = false) String body) {
        return Bodies.ok(control.preflight(body == null ? "{}" : body));
    }

    @PostMapping("/executions")
    public ResponseEntity<ApiBody<Map<String, Object>>> start(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) String body) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "幂等键不能为空");
        }
        Map<String, Object> accepted = control.start(idempotencyKey, body == null ? "{}" : body);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Bodies.ok(accepted));
    }

    @PatchMapping("/executions/{id}/targets")
    public ResponseEntity<ApiBody<Map<String, Object>>> targets(@PathVariable("id") String id,
                                                                @RequestBody(required = false) String body) {
        Map<String, Object> accepted = control.updateTargets(id, body == null ? "{}" : body);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Bodies.ok(accepted));
    }

    @PostMapping("/executions/{id}/pause")
    public ResponseEntity<ApiBody<Map<String, Object>>> pause(@PathVariable("id") String id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Bodies.ok(control.pause(id)));
    }

    @PostMapping("/executions/{id}/resume")
    public ResponseEntity<ApiBody<Map<String, Object>>> resume(@PathVariable("id") String id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Bodies.ok(control.resume(id)));
    }

    @PostMapping("/executions/{id}/stop")
    public ResponseEntity<ApiBody<Map<String, Object>>> stop(@PathVariable("id") String id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Bodies.ok(control.stop(id)));
    }
}
