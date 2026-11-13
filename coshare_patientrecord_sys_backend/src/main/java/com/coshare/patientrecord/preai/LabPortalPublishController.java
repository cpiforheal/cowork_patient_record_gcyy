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

    public LabPortalPublishController(LabReportPublishService service) {
        this.service = service;
    }

    @PostMapping("/encounters/{encounterId}/reports/{reportId}/publish")
    public ApiResult<Map<String, Object>> publish(
        @PathVariable String encounterId,
        @PathVariable String reportId
    ) {
        return ApiResult.of(200, "已发布给患者", service.publish(encounterId, reportId, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/publications/{publicationId}/revoke")
    public ApiResult<Map<String, Object>> revoke(@PathVariable String publicationId) {
        return ApiResult.of(200, "已撤销患者访问", service.revoke(publicationId, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/encounters/{encounterId}/publications")
    public ApiResult<Map<String, Object>> publications(@PathVariable String encounterId) {
        return ApiResult.success(service.publications(encounterId, AuthPermission.currentUserOrThrow()));
    }
}
