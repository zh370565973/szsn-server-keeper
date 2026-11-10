package com.szsn.srm.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;

public final class HttpApiWriter {
    private HttpApiWriter() {
    }

    public static void write(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
                             HttpStatus status, String code, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        ApiBody<Void> body = ApiBody.fail(new ApiError(code, message, null), RequestIds.resolve(request), System.currentTimeMillis());
        mapper.writeValue(response.getOutputStream(), body);
    }
}
