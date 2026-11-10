package com.szsn.srm.auth;

import com.szsn.srm.common.ApiBody;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.Bodies;
import com.szsn.srm.common.RequestIds;
import com.szsn.srm.monitor.SseHub;
import com.szsn.srm.system.StorageHealth;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AdminCredentialStore credentials;
    private final LoginRateLimiter rateLimiter;
    private final OperationLogRepository operationLog;
    private final StorageHealth storageHealth;
    private final SseHub sseHub;
    private final Clock clock;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(AdminCredentialStore credentials, LoginRateLimiter rateLimiter,
                          OperationLogRepository operationLog, StorageHealth storageHealth, SseHub sseHub, Clock clock) {
        this.credentials = credentials;
        this.rateLimiter = rateLimiter;
        this.operationLog = operationLog;
        this.storageHealth = storageHealth;
        this.sseHub = sseHub;
        this.clock = clock;
    }

    @GetMapping("/csrf")
    public ApiBody<Map<String, String>> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "CSRF 令牌不可用");
        }
        token.getToken();
        return Bodies.ok(Map.of("headerName", token.getHeaderName(), "token", token.getToken()));
    }

    @PostMapping("/login")
    public ApiBody<Map<String, String>> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                                              HttpServletResponse response) {
        String remote = request.getRemoteAddr();
        String limitKey = LoginRateLimiter.key(body.username(), remote);
        if (rateLimiter.coolingDown(limitKey)) {
            audit(body.username(), remote, "FAILURE", "COOLDOWN");
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_COOLDOWN", "登录失败次数过多，请 5 分钟后再试");
        }
        if (!credentials.initialized()) {
            audit(body.username(), remote, "FAILURE", "NOT_INITIALIZED");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_NOT_INITIALIZED", "管理员口令尚未初始化");
        }
        if (!credentials.username().equals(body.username()) || !credentials.matches(body.password())) {
            rateLimiter.recordFailure(limitKey);
            audit(body.username(), remote, "FAILURE", "BAD_CREDENTIALS");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "用户名或口令错误");
        }
        rateLimiter.recordSuccess(limitKey);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                credentials.username(), null, java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        request.changeSessionId();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(SessionActivityFilter.LAST_ACTIVE, clock.millis());
        }
        audit(credentials.username(), remote, "SUCCESS", null);
        return Bodies.ok(Map.of("username", credentials.username()));
    }

    @PostMapping("/logout")
    public ApiBody<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            sseHub.closeSession(session.getId());
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return Bodies.ok(null);
    }

    @GetMapping("/me")
    public ApiBody<Map<String, String>> me(org.springframework.security.core.Authentication authentication) {
        return Bodies.ok(Map.of("username", authentication.getName()));
    }

    private void audit(String actor, String remote, String result, String reason) {
        try {
            operationLog.insert(actor, remote, "LOGIN", result, reason, RequestIds.current());
        } catch (RuntimeException exception) {
            storageHealth.markFault("操作日志写入失败");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "操作日志写入失败");
        }
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 200) String password) {
    }
}
