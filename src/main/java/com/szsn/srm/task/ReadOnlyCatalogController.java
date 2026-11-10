package com.szsn.srm.task;

import com.szsn.srm.auth.OperationLogRepository;
import com.szsn.srm.common.ApiBody;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.Bodies;
import com.szsn.srm.common.PageQuery;
import com.szsn.srm.common.PageResult;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ReadOnlyCatalogController {
    private final OperationLogRepository operationLog;

    public ReadOnlyCatalogController(OperationLogRepository operationLog) {
        this.operationLog = operationLog;
    }

    @GetMapping("/tasks")
    public ApiBody<PageResult<Map<String, Object>>> tasks(@RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer pageSize) {
        return Bodies.ok(PageResult.empty(PageQuery.page(page), PageQuery.pageSize(pageSize)));
    }

    @GetMapping("/tasks/{id}")
    public ApiBody<Void> task(@PathVariable String id) {
        throw missing("任务模板");
    }

    @GetMapping("/schedules")
    public ApiBody<PageResult<Map<String, Object>>> schedules(@RequestParam(required = false) Integer page,
                                                              @RequestParam(required = false) Integer pageSize) {
        return Bodies.ok(PageResult.empty(PageQuery.page(page), PageQuery.pageSize(pageSize)));
    }

    @GetMapping("/schedules/{id}")
    public ApiBody<Void> schedule(@PathVariable String id) {
        throw missing("定时计划");
    }

    @GetMapping("/executions")
    public ApiBody<PageResult<Map<String, Object>>> executions(@RequestParam(required = false) Integer page,
                                                               @RequestParam(required = false) Integer pageSize) {
        return Bodies.ok(PageResult.empty(PageQuery.page(page), PageQuery.pageSize(pageSize)));
    }

    @GetMapping("/executions/{id}")
    public ApiBody<Void> execution(@PathVariable String id) {
        throw missing("执行记录");
    }

    @GetMapping("/logs")
    public ApiBody<PageResult<OperationLogRepository.LogRow>> logs(@RequestParam(required = false) Integer page,
                                                                   @RequestParam(required = false) Integer pageSize) {
        return Bodies.ok(operationLog.page(PageQuery.page(page), PageQuery.pageSize(pageSize)));
    }

    private static ApiException missing(String name) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", name + "不存在");
    }
}
