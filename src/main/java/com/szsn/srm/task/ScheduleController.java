package com.szsn.srm.task;

import com.szsn.srm.common.ApiBody;
import com.szsn.srm.common.Bodies;
import com.szsn.srm.common.PageQuery;
import com.szsn.srm.common.PageResult;
import com.szsn.srm.common.RequestIds;
import com.szsn.srm.resource.Operator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {
    private final ScheduleService schedules;

    public ScheduleController(ScheduleService schedules) {
        this.schedules = schedules;
    }

    @GetMapping
    public ApiBody<PageResult<ScheduleView>> list(@RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer pageSize) {
        return Bodies.ok(schedules.list(PageQuery.page(page), PageQuery.pageSize(pageSize)));
    }

    @PostMapping
    public ApiBody<ScheduleView> create(@RequestBody(required = false) String body, HttpServletRequest request) {
        return Bodies.ok(schedules.create(body, operator(request)));
    }

    @GetMapping("/{id}")
    public ApiBody<ScheduleView> get(@PathVariable String id) {
        return Bodies.ok(schedules.get(id));
    }

    @PutMapping("/{id}")
    public ApiBody<ScheduleView> update(@PathVariable String id, @RequestBody(required = false) String body,
                                        HttpServletRequest request) {
        return Bodies.ok(schedules.update(id, body, operator(request)));
    }

    @DeleteMapping("/{id}")
    public ApiBody<Map<String, Object>> delete(@PathVariable String id, HttpServletRequest request) {
        return Bodies.ok(schedules.delete(id, operator(request)));
    }

    @PutMapping("/{id}/enabled")
    public ApiBody<ScheduleView> setEnabled(@PathVariable String id, @RequestBody(required = false) String body,
                                            HttpServletRequest request) {
        return Bodies.ok(schedules.setEnabled(id, body, operator(request)));
    }

    private static Operator operator(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String name = authentication == null || authentication.getName() == null ? "admin" : authentication.getName();
        return new Operator(name, request.getRemoteAddr(), RequestIds.current());
    }
}
