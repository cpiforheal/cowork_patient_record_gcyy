package com.coshare.patientrecord.labportal;

import com.coshare.patientrecord.common.api.ApiResult;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
@RequestMapping("/lab-api")
public class LabPortalApiController {

    private static final String SESSION_COOKIE = "lab_portal_session";

    private final LabPortalService service;

    public LabPortalApiController(LabPortalService service) {
        this.service = service;
    }

    @PostMapping("/ingest")
    public ApiResult<Map<String, Object>> ingest(
        @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
        @RequestHeader(value = "X-Signature", required = false) String signature,
        @RequestBody String body
    ) {
        service.ingest(timestamp, signature, body);
        return ApiResult.success(Map.of("accepted", true));
    }

    @PostMapping("/lookup")
    public ApiResult<Map<String, Object>> lookup(
        @RequestBody LookupRequest request,
        @RequestHeader(value = "User-Agent", required = false) String userAgent,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        LabPortalService.LookupResult result = service.lookup(
            request == null ? "" : request.name(),
            request == null ? "ID" : request.mode(),
            request == null ? "" : request.lastDigits(),
            remoteIp(httpRequest),
            userAgent
        );
        if (result.sessionToken() != null) {
            ResponseCookie cookie = ResponseCookie.from(SESSION_COOKIE, result.sessionToken())
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofMinutes(30))
                .build();
            httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return ApiResult.success(result.data());
    }

    @GetMapping("/reports")
    public ApiResult<Map<String, Object>> reports(
        @CookieValue(value = SESSION_COOKIE, required = false) String session
    ) {
        return ApiResult.success(service.reports(session));
    }

    @GetMapping("/reports/{reportId}")
    public ApiResult<Map<String, Object>> report(
        @CookieValue(value = SESSION_COOKIE, required = false) String session,
        @PathVariable String reportId,
        @RequestHeader(value = "User-Agent", required = false) String userAgent,
        HttpServletRequest httpRequest
    ) {
        return ApiResult.success(service.report(session, reportId, remoteIp(httpRequest), userAgent));
    }

    @PostMapping("/logout")
    public ApiResult<Map<String, Object>> logout(
        @CookieValue(value = SESSION_COOKIE, required = false) String session,
        HttpServletResponse httpResponse
    ) {
        service.logout(session);
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(SESSION_COOKIE, "")
            .httpOnly(true).sameSite("Lax").path("/").maxAge(0).build().toString());
        return ApiResult.success(Map.of("loggedOut", true));
    }

    private String remoteIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    public record LookupRequest(String name, String mode, String lastDigits) {}
}
