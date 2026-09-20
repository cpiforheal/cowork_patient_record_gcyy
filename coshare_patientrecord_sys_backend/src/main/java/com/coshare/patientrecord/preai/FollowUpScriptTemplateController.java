package com.coshare.patientrecord.preai;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 随访话术模板库（热更新）：话术弹窗数据源 + 管理页 CRUD/归档/启用。 */
@RestController
@Profile("mysql")
public class FollowUpScriptTemplateController {

    private final FollowUpScriptTemplateService service;

    public FollowUpScriptTemplateController(FollowUpScriptTemplateService service) {
        this.service = service;
    }

    @GetMapping("/clinic-api/follow-up-script-templates")
    public ApiResult<List<Map<String, Object>>> listActive() {
        AuthPermission.currentUserOrThrow();
        return ApiResult.success(service.listActive());
    }

    @GetMapping("/clinic-api/follow-up-script-templates/manage")
    public ApiResult<List<Map<String, Object>>> manage() {
        AuthPermission.currentUserOrThrow();
        return ApiResult.success(service.listManage());
    }

    @PostMapping("/clinic-api/follow-up-script-templates")
    public ApiResult<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return ApiResult.of(200, "话术模板已创建", service.create(body, AuthPermission.currentUserOrThrow()));
    }

    @PutMapping("/clinic-api/follow-up-script-templates/{id}")
    public ApiResult<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResult.of(200, "话术模板已更新", service.update(safe(id), body, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/clinic-api/follow-up-script-templates/{id}/{action}")
    public ApiResult<Map<String, Object>> changeStatus(@PathVariable String id, @PathVariable String action) {
        return ApiResult.of(200, "操作完成", service.changeStatus(safe(id), safe(action), AuthPermission.currentUserOrThrow()));
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
