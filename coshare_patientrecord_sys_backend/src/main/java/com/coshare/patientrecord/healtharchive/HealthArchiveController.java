package com.coshare.patientrecord.healtharchive;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.medicalrecord.dto.DownloadFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("mysql")
public class HealthArchiveController {

    private static final String DOCX_MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final HealthArchiveService healthArchiveService;
    private final ObjectMapper objectMapper;

    public HealthArchiveController(HealthArchiveService healthArchiveService, ObjectMapper objectMapper) {
        this.healthArchiveService = healthArchiveService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/clinic-api/health-archive")
    public ApiResult<Map<String, Object>> load(@RequestParam String encounterId) {
        SessionUser user = currentUser();
        return ApiResult.success(objectMapper.convertValue(
            healthArchiveService.load(safe(encounterId), user),
            new TypeReference<Map<String, Object>>() {}
        ));
    }

    @PutMapping("/clinic-api/health-archive")
    public ApiResult<Map<String, Object>> save(@RequestBody SaveRequest request) {
        SessionUser user = currentUser();
        return ApiResult.of(200, "健康管理档案草稿已保存", objectMapper.convertValue(
            healthArchiveService.save(safe(request.encounterId()),
                objectMapper.valueToTree(request.form()), safe(request.sourceRecordId()), user),
            new TypeReference<Map<String, Object>>() {}
        ));
    }

    @PostMapping("/clinic-api/health-archive/complete")
    public ApiResult<Map<String, Object>> complete(@RequestBody CompleteRequest request) {
        SessionUser user = currentUser();
        return ApiResult.of(200, "健康管理档案合并文档已生成", objectMapper.convertValue(
            healthArchiveService.complete(safe(request.encounterId()),
                objectMapper.valueToTree(request.form()), safe(request.sourceRecordId()), user),
            new TypeReference<Map<String, Object>>() {}
        ));
    }

    @GetMapping("/clinic-api/health-archive/documents/{id}/download")
    public ResponseEntity<org.springframework.core.io.FileSystemResource> download(@PathVariable String id) {
        SessionUser user = currentUser();
        DownloadFile download = healthArchiveService.downloadDocument(safe(id), user);
        String fileName = new String(download.fileName().getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(DOCX_MIME))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
            .body(download.resource());
    }

    private SessionUser currentUser() {
        return com.coshare.patientrecord.security.AuthPermission.currentUserOrThrow();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record SaveRequest(String encounterId, String sourceRecordId, Map<String, Object> form) {}
    public record CompleteRequest(String encounterId, String sourceRecordId, Map<String, Object> form) {}
}
