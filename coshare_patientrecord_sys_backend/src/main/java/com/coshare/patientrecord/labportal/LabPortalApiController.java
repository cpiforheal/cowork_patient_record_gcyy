package com.coshare.patientrecord.labportal;

import com.coshare.patientrecord.common.api.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.context.annotation.Profile;
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

    private final LabPortalService service;

    public LabPortalApiController(LabPortalService service) {
        this.service = service;
    }

    @PostMapping("/ingest")
    public ApiResult<Map<String, Object>> ingest(
        @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
        @RequestHeader(value = "X-Signature", required = false) String signature,
        @RequestBody String body,
        HttpServletRequest request
    ) {
        service.ingest(timestamp, signature, body, remoteIp(request));
        return ApiResult.success(Map.of("accepted", true));
    }

    @GetMapping("/reports/{token}")
    public ApiResult<Map<String, Object>> report(
        @PathVariable String token,
        @RequestHeader(value = "User-Agent", required = false) String userAgent,
        HttpServletRequest request
    ) {
        return ApiResult.success(service.view(token, remoteIp(request), userAgent));
    }

    private String remoteIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
