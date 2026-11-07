package com.coshare.patientrecord.exam;

import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("mysql")
@RequestMapping("/clinic-api/exam-admin")
public class ExamAdminController {

    private final ExamService service;
    private final ObjectMapper objectMapper;

    public ExamAdminController(ExamService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/paper")
    public ApiResult<Map<String, Object>> paper() {
        return ApiResult.success(service.adminPaper(AuthPermission.currentUserOrThrow()));
    }

    @PutMapping("/paper/status")
    public ApiResult<Map<String, Object>> paperStatus(@RequestBody Map<String, Object> payload) {
        return ApiResult.of(200, "考试状态已更新",
            service.updatePaperStatus(toJson(payload), AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/questions")
    public ApiResult<Map<String, Object>> questions() {
        return ApiResult.success(service.adminQuestions(AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/roster")
    public ApiResult<Map<String, Object>> roster() {
        return ApiResult.success(service.roster(AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/roster")
    public ApiResult<Map<String, Object>> addRoster(@RequestBody Map<String, Object> payload) {
        return ApiResult.of(200, "花名册已更新",
            service.addRoster(toJson(payload), AuthPermission.currentUserOrThrow()));
    }

    @DeleteMapping("/roster/{id}")
    public ApiResult<Map<String, Object>> deleteRoster(@PathVariable String id) {
        return ApiResult.of(200, "已删除",
            service.deleteRoster(id, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/roster/import")
    public ApiResult<Map<String, Object>> importRoster(@RequestParam("file") MultipartFile file) {
        return ApiResult.of(200, "花名册导入完成",
            service.importRoster(file, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/roster/import-accounts")
    public ApiResult<Map<String, Object>> importAccounts() {
        return ApiResult.of(200, "账号名单导入完成",
            service.importAccounts(AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/attempts")
    public ApiResult<Map<String, Object>> attempts(
        @RequestParam(required = false) String department,
        @RequestParam(required = false) String status
    ) {
        return ApiResult.success(service.attempts(department, status, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/attempts/export")
    public ResponseEntity<byte[]> exportAttempts() {
        byte[] bytes = service.exportAttempts(AuthPermission.currentUserOrThrow());
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=exam-attempts.xlsx")
            .contentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }

    @GetMapping("/attempts/{id}")
    public ApiResult<Map<String, Object>> attemptDetail(@PathVariable String id) {
        return ApiResult.success(service.attemptDetail(id, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/attempts/{id}/void")
    public ApiResult<Map<String, Object>> voidAttempt(
        @PathVariable String id,
        @RequestBody Map<String, Object> payload
    ) {
        return ApiResult.of(200, "答卷已作废，可重新答题",
            service.voidAttempt(id, toJson(payload), AuthPermission.currentUserOrThrow()));
    }

    private JsonNode toJson(Map<String, Object> payload) {
        return objectMapper.valueToTree(payload == null ? Map.of() : payload);
    }
}