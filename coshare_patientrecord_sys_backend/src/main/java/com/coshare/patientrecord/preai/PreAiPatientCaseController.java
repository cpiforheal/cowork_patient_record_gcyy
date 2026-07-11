package com.coshare.patientrecord.preai;

import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
@RequestMapping("/clinic-api/pre-ai/patients")
public class PreAiPatientCaseController {

    private final PreAiEncounterService service;

    public PreAiPatientCaseController(PreAiEncounterService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> list() {
        return ApiResult.success(service.listPatientCases(AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/{patientCaseId}/encounters")
    public ApiResult<Map<String, Object>> createFollowUp(
        @PathVariable String patientCaseId,
        @RequestBody(required = false) PreAiEncounterService.FollowUpEncounterCreateRequest request
    ) {
        return ApiResult.of(200, "复诊子病历已创建", service.createFollowUp(patientCaseId, request, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/{patientCaseId}/inspection-timeline")
    public ApiResult<Map<String, Object>> inspectionTimeline(@PathVariable String patientCaseId) {
        return ApiResult.success(service.inspectionTimeline(patientCaseId, AuthPermission.currentUserOrThrow()));
    }
}


