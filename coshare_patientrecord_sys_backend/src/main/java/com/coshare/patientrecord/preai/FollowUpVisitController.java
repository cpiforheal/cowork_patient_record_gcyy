package com.coshare.patientrecord.preai;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("mysql")
public class FollowUpVisitController {

    private final FollowUpVisitService followUpVisitService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public FollowUpVisitController(FollowUpVisitService followUpVisitService,
                                   com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.followUpVisitService = followUpVisitService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/clinic-api/follow-up/visits")
    public ApiResult<Map<String, Object>> list(@RequestParam String patientCaseId) {
        return ApiResult.success(objectMapper.convertValue(
            followUpVisitService.list(safe(patientCaseId), currentUser()),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
        ));
    }

    @PostMapping("/clinic-api/follow-up/visits")
    public ApiResult<Map<String, Object>> create(@RequestBody CreateRequest request) {
        SessionUser user = currentUser();
        return ApiResult.of(200, "复诊记录已创建", followUpVisitService.create(
            safe(request.patientCaseId()), objectMapper.valueToTree(request), user));
    }

    @PostMapping("/clinic-api/follow-up/visits/{id}/images")
    public ApiResult<Map<String, Object>> addImage(@PathVariable String id, @RequestBody ImageRequest request) {
        SessionUser user = currentUser();
        return ApiResult.of(200, "复诊图片已上传", followUpVisitService.addImage(
            safe(id), objectMapper.valueToTree(request), user));
    }

    @DeleteMapping("/clinic-api/follow-up/visits/{id}/images/{imageId}")
    public ApiResult<Map<String, Object>> removeImage(@PathVariable String id, @PathVariable String imageId) {
        SessionUser user = currentUser();
        return ApiResult.of(200, "复诊图片已删除", followUpVisitService.removeImage(safe(id), safe(imageId), user));
    }

    @GetMapping("/clinic-api/follow-up/visits/images/{imageId}/file")
    public ResponseEntity<FileSystemResource> imageFile(@PathVariable String imageId) {
        SessionUser user = currentUser();
        Map<String, String> image = followUpVisitService.imageContent(safe(imageId), user);
        FileSystemResource resource = new FileSystemResource(image.get("storagePath"));
        if (!resource.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "复诊图片文件不存在");
        String mimeType = image.get("mimeType") == null || image.get("mimeType").isBlank()
            ? MediaType.APPLICATION_OCTET_STREAM_VALUE
            : image.get("mimeType");
        String fileName = new String(image.get("fileName").getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mimeType))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(fileName).build().toString())
            .body(resource);
    }

    private SessionUser currentUser() {
        return AuthPermission.currentUserOrThrow();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record CreateRequest(
        String patientCaseId,
        String encounterId,
        String reason,
        String conditionNote,
        String nextReviewDate,
        java.util.List<Map<String, Object>> images
    ) {}

    public record ImageRequest(String fileName, String dataUrl) {}
}
