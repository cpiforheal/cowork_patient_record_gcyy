package com.coshare.patientrecord.clinic.controller;

import com.coshare.patientrecord.clinic.service.PatientAnalysisQuery;
import com.coshare.patientrecord.clinic.service.PatientAnalysisService;
import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
public class PatientAnalysisController {
    private final PatientAnalysisService service;

    public PatientAnalysisController(PatientAnalysisService service) {
        this.service = service;
    }

    @GetMapping("/clinic-api/ops/analysis")
    public ApiResult<Map<String, Object>> analysis(@RequestParam MultiValueMap<String, String> params) {
        return ApiResult.success(service.analysis(PatientAnalysisQuery.parse(params), AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/clinic-api/ops/analysis/facets")
    public ApiResult<Map<String, Object>> facets(@RequestParam MultiValueMap<String, String> params) {
        return ApiResult.success(service.facets(PatientAnalysisQuery.parse(params), AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/clinic-api/ops/analysis/details")
    public ApiResult<Map<String, Object>> details(@RequestParam MultiValueMap<String, String> params) {
        return ApiResult.success(service.details(PatientAnalysisQuery.parse(params), AuthPermission.currentUserOrThrow()));
    }
}
