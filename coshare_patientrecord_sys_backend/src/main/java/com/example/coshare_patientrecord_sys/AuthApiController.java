package com.example.coshare_patientrecord_sys;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
public class AuthApiController {

    private final AuthSessionService authSessionService;

    public AuthApiController(AuthSessionService authSessionService) {
        this.authSessionService = authSessionService;
    }

    @PostMapping("/auth/login")
    public ApiResult<AuthSessionService.LoginResult> login(
        @RequestBody AuthSessionService.LoginRequest request,
        HttpServletRequest servletRequest
    ) {
        return ApiResult.success(authSessionService.login(request, clientIp(servletRequest)));
    }

    @GetMapping("/auth/options")
    public ApiResult<AuthSessionService.LoginOptions> options() {
        return ApiResult.success(authSessionService.loginOptions());
    }

    @GetMapping("/auth/options/accounts")
    public ApiResult<AuthSessionService.LoginAccountOptions> accounts(@RequestParam String department) {
        return ApiResult.success(authSessionService.loginAccounts(department));
    }

    @PostMapping("/auth/logout")
    public ApiResult<Map<String, Boolean>> logout(HttpServletRequest request) {
        authSessionService.logout(AuthTokenFilter.extractToken(request));
        return ApiResult.success(Map.of("ok", true));
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
