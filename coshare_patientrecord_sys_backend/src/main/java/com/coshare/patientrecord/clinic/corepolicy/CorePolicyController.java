package com.coshare.patientrecord.clinic.corepolicy;

import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
public class CorePolicyController {
    private final CorePolicyService service;

    public CorePolicyController(CorePolicyService service) {
        this.service = service;
    }

    @GetMapping("/clinic-api/core-policy/today")
    public ApiResult<Map<String, Object>> today() {
        return ApiResult.success(service.today(AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/clinic-api/core-policy/check-in")
    public ApiResult<Map<String, Object>> checkIn() {
        return ApiResult.success(service.checkIn(AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/clinic-api/core-policy/policies")
    public ApiResult<List<Map<String, Object>>> policies() {
        return ApiResult.success(service.policies(AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/clinic-api/core-policy/quiz/current")
    public ApiResult<Map<String, Object>> quizCurrent() {
        return ApiResult.success(service.quizCurrent(AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/clinic-api/core-policy/quiz/submit")
    public ApiResult<Map<String, Object>> quizSubmit(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResult.success(service.quizSubmit(AuthPermission.currentUserOrThrow(), body));
    }

    @GetMapping("/clinic-api/core-policy/quiz/stats")
    public ApiResult<Map<String, Object>> quizStats() {
        return ApiResult.success(service.quizStats(AuthPermission.currentUserOrThrow()));
    }
}
