package com.szsn.srm.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.szsn.srm.common.HttpApiWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

public final class JsonAuthHandlers {
    private JsonAuthHandlers() {
    }

    public static AuthenticationEntryPoint entryPoint(ObjectMapper objectMapper) {
        return (request, response, exception) -> write(objectMapper, request, response, HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED", "未登录", exception);
    }

    public static AccessDeniedHandler denied(ObjectMapper objectMapper) {
        return (request, response, exception) -> write(objectMapper, request, response, HttpStatus.FORBIDDEN,
                "FORBIDDEN", "没有权限或 CSRF 校验失败", exception);
    }

    private static void write(ObjectMapper objectMapper, HttpServletRequest request, HttpServletResponse response,
                              HttpStatus status, String code, String message, Exception exception) throws IOException {
        if (exception instanceof AuthenticationException || exception instanceof AccessDeniedException) {
            HttpApiWriter.write(objectMapper, request, response, status, code, message);
        }
    }
}
