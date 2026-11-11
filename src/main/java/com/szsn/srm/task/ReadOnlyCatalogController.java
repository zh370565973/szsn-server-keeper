package com.szsn.srm.task;

import com.szsn.srm.auth.OperationLogRepository;
import com.szsn.srm.common.ApiBody;
import com.szsn.srm.common.Bodies;
import com.szsn.srm.common.PageQuery;
import com.szsn.srm.common.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ReadOnlyCatalogController {
    private final OperationLogRepository operationLog;
    private final ExecutionQueryService executions;

    public ReadOnlyCatalogController(OperationLogRepository operationLog, ExecutionQueryService executions) {
        this.operationLog = operationLog;
        this.executions = executions;
    }

    @GetMapping("/executions")
    public ApiBody<PageResult<ExecutionQueryService.ExecutionSummary>> executions(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        return Bodies.ok(executions.list(PageQuery.page(page), PageQuery.pageSize(pageSize), status, taskId, from, to));
    }

    @GetMapping("/executions/{id}")
    public ApiBody<ExecutionQueryService.ExecutionDetail> execution(@PathVariable String id) {
        return Bodies.ok(executions.get(id));
    }

    @GetMapping("/logs")
    public ApiBody<PageResult<OperationLogRepository.LogRow>> logs(@RequestParam(required = false) Integer page,
                                                                   @RequestParam(required = false) Integer pageSize) {
        return Bodies.ok(operationLog.page(PageQuery.page(page), PageQuery.pageSize(pageSize)));
    }
}
