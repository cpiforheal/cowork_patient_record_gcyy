package com.coshare.patientrecord.preai;

import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("mysql")
@RequestMapping("/clinic-api/pre-ai/encounters")
public class PreAiEncounterController {

    private final PreAiEncounterService service;

    public PreAiEncounterController(PreAiEncounterService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> list() {
        return ApiResult.success(service.list(AuthPermission.currentUserOrThrow()));
    }

    @PostMapping
    public ApiResult<Map<String, Object>> create(@RequestBody PreAiEncounterService.CreateEncounterRequest request) {
        return ApiResult.of(200, "前置病历就诊已创建", service.create(request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/register-and-issue")
    public ApiResult<Map<String, Object>> registerAndIssue(@RequestBody PreAiEncounterService.RegisterAndIssueRequest request) {
        return ApiResult.of(200, "就诊登记和发号已完成", service.registerAndIssue(request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/{encounterId}/register-and-issue")
    public ApiResult<Map<String, Object>> registerExistingAndIssue(
        @PathVariable String encounterId,
        @RequestBody PreAiEncounterService.ExistingRegisterAndIssueRequest request
    ) {
        return ApiResult.of(200, "复诊补登记和发号已完成",
            service.registerExistingAndIssue(encounterId, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/imports/{patientId}")
    public ApiResult<Map<String, Object>> importLegacy(@PathVariable String patientId) {
        return ApiResult.of(200, "旧患者资料已导入或复用现有前置病历", service.importLegacy(patientId, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/{encounterId}")
    public ApiResult<Map<String, Object>> workspace(
        @PathVariable String encounterId,
        @RequestParam(defaultValue = "false") boolean readOnly,
        @RequestParam(defaultValue = "") String patientCaseId
    ) {
        return ApiResult.success(service.getWorkspace(encounterId, readOnly, patientCaseId, AuthPermission.currentUserOrThrow()));
    }

    @PutMapping("/{encounterId}/visit-meta")
    public ApiResult<Map<String, Object>> updateVisitMeta(
        @PathVariable String encounterId,
        @RequestBody PreAiEncounterService.VisitMetaRequest request
    ) {
        return ApiResult.of(200, "来访及交费参考信息已保存", service.updateVisitMeta(encounterId, request, AuthPermission.currentUserOrThrow()));
    }

    @PutMapping("/{encounterId}/duty-assignments")
    public ApiResult<Map<String, Object>> saveDutyAssignments(
        @PathVariable String encounterId,
        @RequestBody PreAiEncounterService.DutyAssignmentsRequest request
    ) {
        return ApiResult.of(200, "病例岗位安排已保存", service.saveDutyAssignments(encounterId, request, AuthPermission.currentUserOrThrow()));
    }

    @PutMapping("/{encounterId}/stages/{stageCode}")
    public ApiResult<Map<String, Object>> saveStage(
        @PathVariable String encounterId,
        @PathVariable String stageCode,
        @RequestBody PreAiEncounterService.StageSaveRequest request
    ) {
        return ApiResult.of(200, "阶段草稿已保存", service.saveStage(encounterId, stageCode, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/{encounterId}/stages/{stageCode}/complete")
    public ApiResult<Map<String, Object>> completeStage(
        @PathVariable String encounterId,
        @PathVariable String stageCode,
        @RequestBody(required = false) PreAiEncounterService.StageSaveRequest request
    ) {
        return ApiResult.of(200, "阶段已完成并交接", service.completeStage(encounterId, stageCode, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/{encounterId}/stages/{stageCode}/return")
    public ApiResult<Map<String, Object>> returnStage(
        @PathVariable String encounterId,
        @PathVariable String stageCode,
        @RequestBody PreAiEncounterService.ReturnStageRequest request
    ) {
        return ApiResult.of(200, "阶段已退回", service.returnStage(encounterId, stageCode, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/{encounterId}/auxiliary-tasks")
    public ApiResult<Map<String, Object>> createAuxiliaryTask(
        @PathVariable String encounterId,
        @RequestBody PreAiEncounterService.AuxiliaryTaskRequest request
    ) {
        return ApiResult.of(200, "辅助检查任务已创建", service.createAuxiliaryTask(encounterId, request, AuthPermission.currentUserOrThrow()));
    }

    @PutMapping("/{encounterId}/auxiliary-tasks/{taskId}")
    public ApiResult<Map<String, Object>> saveAuxiliaryTask(
        @PathVariable String encounterId,
        @PathVariable String taskId,
        @RequestBody PreAiEncounterService.AuxiliaryTaskSaveRequest request
    ) {
        return ApiResult.of(200, "辅助检查草稿已保存", service.saveAuxiliaryTask(encounterId, taskId, request, false, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/{encounterId}/auxiliary-tasks/{taskId}/complete")
    public ApiResult<Map<String, Object>> completeAuxiliaryTask(
        @PathVariable String encounterId,
        @PathVariable String taskId,
        @RequestBody PreAiEncounterService.AuxiliaryTaskSaveRequest request
    ) {
        return ApiResult.of(200, "辅助检查任务已完成", service.saveAuxiliaryTask(encounterId, taskId, request, true, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/{encounterId}/auxiliary-tasks/{taskId}/return")
    public ApiResult<Map<String, Object>> returnAuxiliaryTask(
        @PathVariable String encounterId,
        @PathVariable String taskId,
        @RequestBody PreAiEncounterService.ReturnStageRequest request
    ) {
        return ApiResult.of(200, "辅助检查任务已退回", service.returnAuxiliaryTask(encounterId, taskId, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/{encounterId}/lab-reports")
    public ApiResult<Map<String, Object>> saveLabReport(
        @PathVariable String encounterId,
        @RequestBody PreAiEncounterService.LabReportRequest request
    ) {
        return ApiResult.of(200, "检验报告已同步到前置病历", service.saveLabReport(encounterId, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/{encounterId}/lab/complete")
    public ApiResult<Map<String, Object>> completeLab(
        @PathVariable String encounterId,
        @RequestBody PreAiEncounterService.VersionRequest request
    ) {
        return ApiResult.of(200, "化验室已完成并交接", service.completeLab(encounterId, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping(path = "/{encounterId}/attachments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<Map<String, Object>> uploadAttachment(
        @PathVariable String encounterId,
        @RequestBody PreAiEncounterService.AttachmentUploadRequest request
    ) throws IOException {
        return ApiResult.of(200, "附件已上传", service.uploadAttachment(encounterId, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping(path = "/{encounterId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<Map<String, Object>> uploadAttachmentMultipart(
        @PathVariable String encounterId,
        @RequestPart("file") MultipartFile file,
        @RequestParam(defaultValue = "") String stageCode,
        @RequestParam(defaultValue = "") String taskId,
        @RequestParam(defaultValue = "") String description,
        @RequestParam(defaultValue = "") String capturedAt,
        @RequestParam(defaultValue = "") String batchId,
        @RequestParam(defaultValue = "") String batchName,
        @RequestParam(defaultValue = "") String relativePath,
        @RequestParam(required = false) Integer sequenceNo
    ) throws IOException {
        PreAiEncounterService.AttachmentUploadRequest metadata = new PreAiEncounterService.AttachmentUploadRequest(
            stageCode, taskId, file.getOriginalFilename(), null, description, capturedAt, batchId, batchName, relativePath, sequenceNo
        );
        return ApiResult.of(200, "附件已上传", service.uploadAttachment(encounterId, metadata, file, AuthPermission.currentUserOrThrow()));
    }

    @DeleteMapping("/{encounterId}/attachments/{attachmentId}")
    public ApiResult<Map<String, Object>> voidAttachment(@PathVariable String encounterId, @PathVariable String attachmentId) {
        return ApiResult.of(200, "附件引用已作废", service.voidAttachment(encounterId, attachmentId, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/{encounterId}/attachments/{attachmentId}/download")
    public ResponseEntity<FileSystemResource> downloadAttachment(@PathVariable String encounterId, @PathVariable String attachmentId) {
        PreAiEncounterService.AttachmentDownload download = service.downloadAttachment(encounterId, attachmentId, AuthPermission.currentUserOrThrow());
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(download.mimeType());
        } catch (Exception ignored) {
            try {
                String detected = Files.probeContentType(download.resource().getFile().toPath());
                mediaType = detected == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(detected);
            } catch (Exception error) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
            }
        }
        return ResponseEntity.ok()
            .contentType(mediaType)
            .cacheControl(CacheControl.noStore().cachePrivate())
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
            .body(download.resource());
    }

    @GetMapping("/{encounterId}/review")
    public ApiResult<Map<String, Object>> reviewPreview(@PathVariable String encounterId) {
        return ApiResult.success(service.reviewPreview(encounterId, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/{encounterId}/review/confirm")
    public ApiResult<Map<String, Object>> confirmReview(
        @PathVariable String encounterId,
        @RequestBody(required = false) PreAiEncounterService.ReviewConfirmRequest request
    ) {
        return ApiResult.of(200, "医生复核已确认", service.confirmReview(encounterId, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/{encounterId}/exports")
    public ApiResult<Map<String, Object>> generateExport(@PathVariable String encounterId) {
        return ApiResult.of(200, "脱敏前置资料 DOCX 已生成", service.generateExport(encounterId, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/{encounterId}/exports")
    public ApiResult<Map<String, Object>> exports(@PathVariable String encounterId) {
        return ApiResult.success(service.exports(encounterId, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/{encounterId}/exports/{exportId}/download")
    public ResponseEntity<FileSystemResource> downloadExport(@PathVariable String encounterId, @PathVariable String exportId) {
        PreAiEncounterService.ExportDownload download = service.downloadExport(encounterId, exportId, AuthPermission.currentUserOrThrow());
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .cacheControl(CacheControl.noStore().cachePrivate())
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
            .body(download.resource());
    }
}

