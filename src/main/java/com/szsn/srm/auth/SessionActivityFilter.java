package com.szsn.srm.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.szsn.srm.common.HttpApiWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionActivityFilter extends OncePerRequestFilter {
    public static final String LAST_ACTIVE = "SRM_LAST_ACTIVE";
    public static final Duration IDLE = Duration.ofMinutes(30);

    private final Clock clock;
    private final ObjectMapper objectMapper;

    public SessionActivityFilter(Clock clock, ObjectMapper objectMapper) {
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (isStream(request) || !authenticated()) {
            filterChain.doFilter(request, response);
            return;
        }
        HttpSession session = request.getSession(false);
        if (session != null && expired(session)) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            HttpApiWriter.write(objectMapper, request, response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "登录已过期");
            return;
        }
        if (session != null) {
            session.setAttribute(LAST_ACTIVE, clock.millis());
        }
        filterChain.doFilter(request, response);
    }

    public boolean expired(HttpSession session) {
        Object value = session.getAttribute(LAST_ACTIVE);
        return value instanceof Long last && clock.millis() - last > IDLE.toMillis();
    }

    private static boolean isStream(HttpServletRequest request) {
        return request.getRequestURI() != null && request.getRequestURI().endsWith("/monitor/stream");
    }

    private static boolean authenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
