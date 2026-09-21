package com.coshare.patientrecord.preai;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 病种模板库（热更新）：接诊下拉数据源 + 管理页 CRUD/转正/归档。 */
@RestController
@Profile("mysql")
public class ClinicDiseaseTemplateController {

    private final ClinicDiseaseTemplateService service;
    private final ObjectMapper objectMapper;

    public ClinicDiseaseTemplateController(ClinicDiseaseTemplateService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/clinic-api/disease-templates")
    public ApiResult<List<Map<String, Object>>> listActive(@RequestParam(required = false) String status) {
        AuthPermission.currentUserOrThrow();
        return ApiResult.success(objectMapper.convertValue(
            "CANDIDATE".equalsIgnoreCase(status) || "ARCHIVED".equalsIgnoreCase(status)
                ? service.listManage().get("templates")
                : service.listActive(),
            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}
        ));
    }

    @GetMapping("/clinic-api/disease-templates/manage")
    public ApiResult<Map<String, Object>> manage() {
        return ApiResult.success(objectMapper.convertValue(
            service.listManage(),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
        ));
    }

    @PostMapping("/clinic-api/disease-templates")
    public ApiResult<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return ApiResult.of(200, "病种模板已创建", objectMapper.convertValue(
            service.create(objectMapper.valueToTree(body), AuthPermission.currentUserOrThrow()),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
        ));
    }

    @PutMapping("/clinic-api/disease-templates/{id}")
    public ApiResult<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResult.of(200, "病种模板已更新", objectMapper.convertValue(
            service.update(safe(id), objectMapper.valueToTree(body), AuthPermission.currentUserOrThrow()),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
        ));
    }

    @PostMapping("/clinic-api/disease-templates/{id}/{action}")
    public ApiResult<Map<String, Object>> changeStatus(@PathVariable String id, @PathVariable String action) {
        return ApiResult.of(200, "操作完成", objectMapper.convertValue(
            service.changeStatus(safe(id), safe(action), AuthPermission.currentUserOrThrow()),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
        ));
    }

    private SessionUser currentUser() {
        return AuthPermission.currentUserOrThrow();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
