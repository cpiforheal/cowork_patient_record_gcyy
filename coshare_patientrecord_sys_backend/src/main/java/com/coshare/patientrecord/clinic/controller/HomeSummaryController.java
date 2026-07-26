package com.coshare.patientrecord.clinic.controller;

import com.coshare.patientrecord.clinic.service.HomeSummaryService;
import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
public class HomeSummaryController {

    private final HomeSummaryService homeSummaryService;

    public HomeSummaryController(HomeSummaryService homeSummaryService) {
        this.homeSummaryService = homeSummaryService;
    }

    @GetMapping("/clinic-api/home/summary")
    public ApiResult<Map<String, Object>> summary() {
        AuthPermission.currentUserOrThrow();
        return ApiResult.success(homeSummaryService.summary());
    }
}
