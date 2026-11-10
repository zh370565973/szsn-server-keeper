package com.szsn.srm.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiBody<Void>> api(ApiException exception) {
        return body(exception.status(), exception.code(), exception.getMessage(), exception.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiBody<Void>> invalid(MethodArgumentNotValidException exception) {
        Map<String, Object> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error -> details.put(error.getField(), error.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求字段不合法", details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiBody<Void>> malformed(Exception exception) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求格式不合法", Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiBody<Void>> unexpected(Exception exception, HttpServletRequest request) {
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误", Map.of());
    }

    private ResponseEntity<ApiBody<Void>> body(HttpStatus status, String code, String message, Map<String, Object> details) {
        ApiError error = new ApiError(code, message, details.isEmpty() ? null : details);
        ApiBody<Void> payload = ApiBody.fail(error, RequestIds.current(), System.currentTimeMillis());
        return ResponseEntity.status(status).body(payload);
    }
}
