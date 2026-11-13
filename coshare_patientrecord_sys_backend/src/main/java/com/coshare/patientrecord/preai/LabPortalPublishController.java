package com.coshare.patientrecord.preai;

import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
@RequestMapping("/clinic-api/lab-portal")
public class LabPortalPublishController {
    private final LabReportPublishService service;
    public LabPortalPublishController(LabReportPublishService service) { this.service = service; }

    @GetMapping("/encounters/{encounterId}/status")
    public ApiResult<Map<String, Object>> status(@PathVariable String encounterId) {
        return ApiResult.success(service.status(encounterId, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/encounters/{encounterId}/revoke")
    public ApiResult<Map<String, Object>> revoke(@PathVariable String encounterId) {
        return ApiResult.of(200, "已撤回患者可见报告", service.revoke(encounterId, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/encounters/{encounterId}/resync")
    public ApiResult<Map<String, Object>> resync(@PathVariable String encounterId) {
        return ApiResult.of(200, "已重新同步", service.syncEncounter(encounterId, AuthPermission.currentUserOrThrow()));
    }
}
