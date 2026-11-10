package com.szsn.srm.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class RequestIds {
    public static final String ATTRIBUTE = "srm.requestId";

    private RequestIds() {
    }

    public static String current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            Object value = servlet.getRequest().getAttribute(ATTRIBUTE);
            if (value instanceof String id && !id.isBlank()) {
                return id;
            }
        }
        return UUID.randomUUID().toString();
    }

    public static String resolve(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        String created = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, created);
        return created;
    }
}
