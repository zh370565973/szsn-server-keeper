package com.szsn.srm.task;

import com.szsn.srm.common.ApiBody;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.Bodies;
import com.szsn.srm.common.PageQuery;
import com.szsn.srm.common.PageResult;
import com.szsn.srm.common.RequestIds;
import com.szsn.srm.resource.CpuControlService;
import com.szsn.srm.resource.Operator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskTemplateController {
    private final TaskTemplateService templates;
    private final CpuControlService control;

    public TaskTemplateController(TaskTemplateService templates, CpuControlService control) {
        this.templates = templates;
        this.control = control;
    }

    @GetMapping
    public ApiBody<PageResult<TaskTemplateView>> list(@RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer pageSize,
                                                      @RequestParam(required = false) String q) {
        return Bodies.ok(templates.list(PageQuery.page(page), PageQuery.pageSize(pageSize), q));
    }

    @PostMapping
    public ApiBody<TaskTemplateView> create(@RequestBody(required = false) String body, HttpServletRequest request) {
        return Bodies.ok(templates.create(body, operator(request)));
    }

    @GetMapping("/{id}")
    public ApiBody<TaskTemplateView> get(@PathVariable String id) {
        return Bodies.ok(templates.get(id));
    }

    @PutMapping("/{id}")
    public ApiBody<TaskTemplateView> update(@PathVariable String id, @RequestBody(required = false) String body,
                                            HttpServletRequest request) {
        return Bodies.ok(templates.update(id, body, operator(request)));
    }

    @DeleteMapping("/{id}")
    public ApiBody<Map<String, Object>> delete(@PathVariable String id, HttpServletRequest request) {
        return Bodies.ok(templates.delete(id, operator(request)));
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<ApiBody<Map<String, Object>>> run(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "幂等键不能为空");
        }
        String config = templates.runConfig(id);
        Map<String, Object> accepted = control.start(idempotencyKey, config);
        Object executionId = accepted.get("executionId");
        if (executionId instanceof String value) {
            templates.bindExecution(id, value);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Bodies.ok(accepted));
    }

    private static Operator operator(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String name = authentication == null || authentication.getName() == null ? "admin" : authentication.getName();
        return new Operator(name, request.getRemoteAddr(), RequestIds.current());
    }
}
