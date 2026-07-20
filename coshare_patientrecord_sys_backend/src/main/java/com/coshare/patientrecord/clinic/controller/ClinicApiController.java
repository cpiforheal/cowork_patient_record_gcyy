package com.coshare.patientrecord.clinic.controller;

import com.coshare.patientrecord.ai.service.ClinicAiAssistantLogService;
import com.coshare.patientrecord.ai.service.ClinicAiAssistantService;
import com.coshare.patientrecord.ai.service.ClinicAiConfigService;
import com.coshare.patientrecord.ai.service.ClinicAiDocumentService;
import com.coshare.patientrecord.ai.service.ClinicAiSummaryService;
import com.coshare.patientrecord.ai.service.ClinicDoubaoTtsService;
import com.coshare.patientrecord.ai.service.AiDocumentTaskService;
import com.coshare.patientrecord.ai.dto.AiAssistantRequest;
import com.coshare.patientrecord.ai.dto.AiDocumentRequest;
import com.coshare.patientrecord.ai.dto.AiSummaryRequest;
import com.coshare.patientrecord.ai.dto.TtsConfigTestRequest;
import com.coshare.patientrecord.ai.dto.TtsSpeakRequest;
import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.backup.service.ClinicBackupService;
import com.coshare.patientrecord.clinic.service.ClinicDatabaseService;
import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.file.dto.ClinicFileUploadRequest;
import com.coshare.patientrecord.file.model.ClinicStoredFile;
import com.coshare.patientrecord.file.service.ClinicFileService;
import com.coshare.patientrecord.medicalrecord.dto.FinalizeRequest;
import com.coshare.patientrecord.medicalrecord.dto.GenerateRequest;
import com.coshare.patientrecord.medicalrecord.dto.InpatientAiGenerateRequest;
import com.coshare.patientrecord.medicalrecord.dto.VoidRequest;
import com.coshare.patientrecord.medicalrecord.dto.WorkspaceSaveRequest;
import com.coshare.patientrecord.medicalrecord.service.ClinicMedicalRecordService;
import com.coshare.patientrecord.security.AuthPermission;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("mysql")
public class ClinicApiController {

    private static final String FILE_ROUTE_PREFIX = "/clinic-api/files/";
    private static final Set<String> ADMIN_CLINIC_SECTIONS = Set.of(
        "accounts",
        "roles",
        "departments",
        "dictionaries",
        "templateFieldRules"
    );

    private final ClinicDatabaseService databaseService;
    private final ClinicFileService fileService;
    private final ClinicBackupService backupService;
    private final ClinicAiSummaryService aiSummaryService;
    private final ClinicAiAssistantService aiAssistantService;
    private final ClinicAiAssistantLogService aiAssistantLogService;
    private final ClinicAiConfigService aiConfigService;
    private final ClinicDoubaoTtsService doubaoTtsService;
    private final ClinicMedicalRecordService medicalRecordService;
    private final ClinicAiDocumentService aiDocumentService;
    private final AiDocumentTaskService aiDocumentTaskService;
    private final ObjectMapper objectMapper;

    public ClinicApiController(
        ClinicDatabaseService databaseService,
        ClinicFileService fileService,
        ClinicBackupService backupService,
        ClinicAiSummaryService aiSummaryService,
        ClinicAiAssistantService aiAssistantService,
        ClinicAiAssistantLogService aiAssistantLogService,
        ClinicAiConfigService aiConfigService,
        ClinicDoubaoTtsService doubaoTtsService,
        ClinicMedicalRecordService medicalRecordService,
        ClinicAiDocumentService aiDocumentService,
        AiDocumentTaskService aiDocumentTaskService,
        ObjectMapper objectMapper
    ) {
        this.databaseService = databaseService;
        this.fileService = fileService;
        this.backupService = backupService;
        this.aiSummaryService = aiSummaryService;
        this.aiAssistantService = aiAssistantService;
        this.aiAssistantLogService = aiAssistantLogService;
        this.aiConfigService = aiConfigService;
        this.doubaoTtsService = doubaoTtsService;
        this.medicalRecordService = medicalRecordService;
        this.aiDocumentService = aiDocumentService;
        this.aiDocumentTaskService = aiDocumentTaskService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/clinic-api/db")
    public ApiResult<Map<String, Object>> readDb() {
        Map<String, Object> db = objectMapper.convertValue(
            databaseService.readDbForUser(AuthPermission.currentUserOrThrow()),
            new TypeReference<Map<String, Object>>() {}
        );
        return ApiResult.success(db);
    }

    @PutMapping("/clinic-api/db")
    public ApiResult<Map<String, String>> writeDb(@RequestBody Map<String, Object> payload) {
        requireClinicAdmin();
        String revision = databaseService.writeDb(objectMapper.valueToTree(payload));
        return ApiResult.of(200, "saved", Map.of("_revision", revision));
    }

    @PostMapping("/clinic-api/db/merge")
    public ApiResult<Map<String, Object>> mergeDb(@RequestBody Map<String, Object> payload) {
        SessionUser user = AuthPermission.currentUserOrThrow();
        requireClinicWriter(payload);
        ObjectNode sanitizedPayload = databaseService.prepareWritePayload(objectMapper.valueToTree(payload), user);
        ObjectNode result = databaseService.mergeDb(sanitizedPayload);
        result.set("db", databaseService.readDbForUser(user));
        Map<String, Object> data = objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
        return ApiResult.of(200, "merged", data);
    }

    @PostMapping("/clinic-api/db/patch")
    public ApiResult<Map<String, Object>> patchDb(@RequestBody Map<String, Object> payload) {
        SessionUser user = AuthPermission.currentUserOrThrow();
        requireClinicWriter(payload);
        ObjectNode sanitizedPayload = databaseService.prepareWritePayload(objectMapper.valueToTree(payload), user);
        ObjectNode result = databaseService.patchDb(sanitizedPayload);
        result.set("db", databaseService.readDbForUser(user));
        Map<String, Object> data = objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
        return ApiResult.of(200, "patched", data);
    }

    @GetMapping("/clinic-api/maintenance/status")
    public ApiResult<Map<String, Object>> maintenanceStatus() throws IOException {
        requireClinicAdmin();
        ObjectNode status = databaseService.maintenanceStatus(fileService.inspectStorage(databaseService.referencedStoragePaths()));
        Map<String, Object> data = objectMapper.convertValue(status, new TypeReference<Map<String, Object>>() {});
        return ApiResult.success(data);
    }

    @GetMapping("/clinic-api/maintenance/status/summary")
    public ApiResult<Map<String, Object>> maintenanceStatusSummary() throws IOException {
        requireClinicAdmin();
        ObjectNode status = databaseService.maintenanceStatus(fileService.summarizeStorage(databaseService.referencedStoragePaths()));
        Map<String, Object> data = objectMapper.convertValue(status, new TypeReference<Map<String, Object>>() {});
        return ApiResult.success(data);
    }

    @PostMapping("/clinic-api/maintenance/snapshot")
    public ApiResult<Map<String, Object>> createSnapshot() {
        requireClinicAdmin();
        ObjectNode result = databaseService.createSnapshot();
        Map<String, Object> data = objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
        return ApiResult.of(200, "snapshot saved", data);
    }

    @GetMapping("/clinic-api/maintenance/backup/status")
    public ApiResult<Map<String, Object>> backupStatus() {
        requireClinicAdmin();
        ObjectNode result = backupService.status();
        Map<String, Object> data = objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
        return ApiResult.success(data);
    }

    @PutMapping("/clinic-api/maintenance/backup/config")
    public ApiResult<Map<String, Object>> updateBackupConfig(@RequestBody Map<String, Object> payload) {
        requireClinicAdmin();
        String backupDir = String.valueOf(payload.getOrDefault("backupDir", ""));
        boolean enabled = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("enabled", "true")));
        ObjectNode result = backupService.updateConfig(backupDir, enabled);
        Map<String, Object> data = objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
        return ApiResult.of(200, "backup config saved", data);
    }

    @PostMapping("/clinic-api/maintenance/backup/choose-dir")
    public ApiResult<Map<String, Object>> chooseBackupDirectory(@RequestBody(required = false) Map<String, Object> payload) {
        requireClinicAdmin();
        String initialDir = payload == null ? "" : String.valueOf(payload.getOrDefault("initialDir", ""));
        ObjectNode result = backupService.chooseBackupDirectory(initialDir);
        Map<String, Object> data = objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
        return ApiResult.of(200, "backup directory selected", data);
    }

    @PostMapping("/clinic-api/maintenance/backup/run")
    public ApiResult<Map<String, Object>> runBackup() {
        requireClinicAdmin();
        ObjectNode result = backupService.runManualBackup();
        Map<String, Object> data = objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
        return ApiResult.of(200, "backup finished", data);
    }

    @GetMapping("/clinic-api/ai/config")
    public ApiResult<Map<String, Object>> aiConfigStatus() {
        requireClinicAdmin();
        Map<String, Object> data = objectMapper.convertValue(aiConfigService.status(), new TypeReference<Map<String, Object>>() {});
        return ApiResult.success(data);
    }

    @PutMapping("/clinic-api/ai/config")
    public ApiResult<Map<String, Object>> updateAiConfig(@RequestBody Map<String, Object> payload) {
        SessionUser user = AuthPermission.currentUserOrThrow();
        requireClinicAdmin();
        ObjectNode result = aiConfigService.updateConfig(payload, user);
        Map<String, Object> data = objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
        return ApiResult.of(200, "AI config saved", data);
    }

    @GetMapping("/clinic-api/ai/doubao/config")
    public ApiResult<Map<String, Object>> doubaoAiConfigStatus() {
        requireClinicAdmin();
        Map<String, Object> data = objectMapper.convertValue(aiConfigService.doubaoStatus(), new TypeReference<Map<String, Object>>() {});
        return ApiResult.success(data);
    }

    @PutMapping("/clinic-api/ai/doubao/config")
    public ApiResult<Map<String, Object>> updateDoubaoAiConfig(@RequestBody Map<String, Object> payload) {
        SessionUser user = AuthPermission.currentUserOrThrow();
        requireClinicAdmin();
        ObjectNode result = aiConfigService.updateDoubaoConfig(payload, user);
        Map<String, Object> data = objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
        return ApiResult.of(200, "doubao assistant config saved", data);
    }

    @PostMapping("/clinic-api/ai/doubao/models")
    public ApiResult<Map<String, Object>> detectDoubaoAiModels(@RequestBody Map<String, Object> payload) {
        requireClinicAdmin();
        ObjectNode result = aiConfigService.detectDoubaoModels(payload);
        Map<String, Object> data = objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
        return ApiResult.of(200, "doubao models detected", data);
    }

    @GetMapping("/clinic-api/ai/doubao/tts/config")
    public ApiResult<Map<String, Object>> doubaoTtsConfigStatus() {
        requireClinicAdmin();
        Map<String, Object> data = objectMapper.convertValue(aiConfigService.doubaoTtsStatus(), new TypeReference<Map<String, Object>>() {});
        return ApiResult.success(data);
    }

    @PutMapping("/clinic-api/ai/doubao/tts/config")
    public ApiResult<Map<String, Object>> updateDoubaoTtsConfig(@RequestBody Map<String, Object> payload) {
        SessionUser user = AuthPermission.currentUserOrThrow();
        requireClinicAdmin();
        ObjectNode result = aiConfigService.updateDoubaoTtsConfig(payload, user);
        Map<String, Object> data = objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
        return ApiResult.of(200, "doubao tts config saved", data);
    }

    @PostMapping("/clinic-api/ai/doubao/tts/speak")
    public ApiResult<Map<String, Object>> speakDoubaoTts(@RequestBody TtsSpeakRequest request) {
        Map<String, Object> data = doubaoTtsService.speak(request, AuthPermission.currentUserOrThrow());
        return ApiResult.of(200, "doubao tts generated", data);
    }

    @PostMapping("/clinic-api/ai/doubao/tts/test")
    public ApiResult<Map<String, Object>> testDoubaoTts(@RequestBody TtsConfigTestRequest request) {
        requireClinicAdmin();
        Map<String, Object> data = doubaoTtsService.test(request);
        return ApiResult.of(200, "doubao tts tested", data);
    }

    @GetMapping("/clinic-api/patients/duplicates")
    public ApiResult<Object> duplicatePatients() {
        requireClinicAdmin();
        ArrayNode duplicates = databaseService.findDuplicatePatients();
        Object data = objectMapper.convertValue(duplicates, Object.class);
        return ApiResult.success(data);
    }

    @GetMapping("/clinic-api/patients/timeline")
    public ApiResult<Object> patientTimeline(@org.springframework.web.bind.annotation.RequestParam String patientId) {
        ArrayNode timeline = databaseService.patientTimeline(patientId, AuthPermission.currentUserOrThrow());
        Object data = objectMapper.convertValue(timeline, Object.class);
        return ApiResult.success(data);
    }

    @PostMapping("/clinic-api/ai/record-summary")
    public ApiResult<Map<String, Object>> generateRecordAiSummary(@RequestBody AiSummaryRequest request) {
        Map<String, Object> data = aiSummaryService.generateRecordSummary(request, AuthPermission.currentUserOrThrow());
        return ApiResult.of(200, "AI summary generated", data);
    }

    @PostMapping("/clinic-api/ai/assistant")
    public ApiResult<Map<String, Object>> askAiAssistant(@RequestBody AiAssistantRequest request) {
        Map<String, Object> data = aiAssistantService.ask(request, AuthPermission.currentUserOrThrow());
        return ApiResult.of(200, "doubao assistant generated", data);
    }

    @PostMapping("/clinic-api/ai/assistant/{assistantType}")
    public ApiResult<Map<String, Object>> askAiAssistantByType(
        @PathVariable String assistantType,
        @RequestBody(required = false) AiAssistantRequest request
    ) {
        AiAssistantRequest normalized = new AiAssistantRequest(
            assistantType,
            request == null ? "" : request.prompt(),
            request == null || request.messages() == null ? List.of() : request.messages(),
            request == null ? "" : request.patientId(),
            request == null || request.context() == null ? Map.of() : request.context(),
            request == null || request.attachmentIds() == null ? List.of() : request.attachmentIds(),
            request == null || request.attachments() == null ? List.of() : request.attachments()
        );
        Map<String, Object> data = aiAssistantService.ask(normalized, AuthPermission.currentUserOrThrow());
        return ApiResult.of(200, "doubao assistant generated", data);
    }

    @GetMapping("/clinic-api/ai/assistant/logs")
    public ApiResult<Map<String, Object>> aiAssistantLogs(@RequestParam Map<String, String> params) {
        return ApiResult.success(aiAssistantLogService.logs(params));
    }

    @GetMapping("/clinic-api/ai/assistant/analytics")
    public ApiResult<Map<String, Object>> aiAssistantAnalytics(@RequestParam Map<String, String> params) {
        return ApiResult.success(aiAssistantLogService.analytics(params));
    }

    @GetMapping("/clinic-api/ai/assistant/templates")
    public ApiResult<Map<String, Object>> aiAssistantTemplates() {
        return ApiResult.success(aiAssistantLogService.templates());
    }

    @PostMapping("/clinic-api/ai/assistant/logs/{id}/template-candidate")
    public ApiResult<Map<String, Object>> markAiAssistantTemplateCandidate(
        @PathVariable String id,
        @RequestBody Map<String, Object> payload
    ) {
        Map<String, Object> data = aiAssistantLogService.markTemplateCandidate(id, payload, AuthPermission.currentUserOrThrow());
        return ApiResult.of(200, "AI template candidate saved", data);
    }

    @GetMapping("/clinic-api/ai-document/templates")
    public ApiResult<Map<String, Object>> aiDocumentTemplates() {
        return ApiResult.success(aiDocumentService.templates(AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/clinic-api/ai-document/preview")
    public ApiResult<Map<String, Object>> previewAiDocument(@RequestBody AiDocumentRequest request) {
        return ApiResult.success(aiDocumentService.preview(request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/clinic-api/ai-document/generate")
    public ApiResult<Map<String, Object>> generateAiDocument(@RequestBody AiDocumentRequest request) {
        return ApiResult.of(202, "AI 文稿任务已提交", aiDocumentTaskService.submit(request, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/clinic-api/ai-document/tasks/{id}")
    public ApiResult<Map<String, Object>> aiDocumentTask(@PathVariable String id) {
        return ApiResult.success(aiDocumentTaskService.status(id, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/clinic-api/ai-document/tasks/{id}/retry")
    public ApiResult<Map<String, Object>> retryAiDocumentTask(@PathVariable String id) {
        return ApiResult.of(202, "AI 文稿任务已重新提交", aiDocumentTaskService.retry(id, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/clinic-api/ai-document/download")
    public ResponseEntity<FileSystemResource> downloadAiDocument(@RequestParam String id) {
        com.coshare.patientrecord.ai.dto.DownloadFile download = aiDocumentService.download(id, AuthPermission.currentUserOrThrow());
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .cacheControl(CacheControl.noStore().cachePrivate())
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                    .filename(download.fileName(), StandardCharsets.UTF_8)
                    .build()
                    .toString()
            )
            .body(download.resource());
    }

    @GetMapping("/clinic-api/medical-record/templates")
    public ApiResult<Map<String, Object>> medicalRecordTemplates() {
        return ApiResult.success(medicalRecordService.templateStatus(AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/clinic-api/medical-record/versions")
    public ApiResult<Map<String, Object>> medicalRecordVersions(
        @RequestParam(required = false, defaultValue = "") String patientId,
        @RequestParam(required = false, defaultValue = "") String encounterId,
        @RequestParam(required = false, defaultValue = "0") int limit
    ) {
        return ApiResult.success(medicalRecordService.versions(patientId, encounterId, AuthPermission.currentUserOrThrow(), limit));
    }

    @PostMapping("/clinic-api/medical-record/precheck")
    public ApiResult<Map<String, Object>> precheckMedicalRecord(@RequestBody GenerateRequest request) {
        return ApiResult.success(medicalRecordService.precheck(request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/clinic-api/medical-record/workspace")
    public ApiResult<Map<String, Object>> saveMedicalRecordWorkspace(@RequestBody WorkspaceSaveRequest request) {
        return ApiResult.of(200, "\u76ee\u6807\u75c5\u5386\u586b\u5199\u5df2\u4fdd\u5b58", medicalRecordService.saveWorkspace(request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/clinic-api/medical-record/generate")
    public ApiResult<Map<String, Object>> generateMedicalRecord(@RequestBody GenerateRequest request) {
        return ApiResult.of(200, "\u76ee\u6807\u75c5\u5386\u5df2\u751f\u6210", medicalRecordService.generate(request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping(
        value = "/clinic-api/medical-record/generate-inpatient-ai",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ApiResult<Map<String, Object>> generateInpatientAiMedicalRecord(
        @RequestParam(required = false, defaultValue = "") String patientId,
        @RequestParam(required = false, defaultValue = "") String encounterId,
        @RequestParam String sourceRecordId,
        @RequestParam(required = false, defaultValue = "") String prompt,
        @RequestParam("referenceDocument") MultipartFile referenceDocument
    ) {
        InpatientAiGenerateRequest request = new InpatientAiGenerateRequest(
            patientId,
            encounterId,
            sourceRecordId,
            prompt
        );
        return ApiResult.of(
            200,
            "AI 住院病历草稿已生成",
            medicalRecordService.generateInpatientAi(
                request,
                referenceDocument,
                AuthPermission.currentUserOrThrow()
            )
        );
    }

    @PostMapping("/clinic-api/medical-record/finalize")
    public ApiResult<Map<String, Object>> finalizeMedicalRecord(@RequestBody FinalizeRequest request) {
        return ApiResult.of(200, "\u76ee\u6807\u75c5\u5386\u5df2\u5b9a\u7a3f", medicalRecordService.finalizeRecord(request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/clinic-api/medical-record/void")
    public ApiResult<Map<String, Object>> voidMedicalRecord(@RequestBody VoidRequest request) {
        return ApiResult.of(200, "\u76ee\u6807\u75c5\u5386\u7248\u672c\u5df2\u4f5c\u5e9f", medicalRecordService.voidRecord(request, AuthPermission.currentUserOrThrow()));
    }

    @DeleteMapping("/clinic-api/medical-record/{id}")
    public ApiResult<Map<String, Object>> deleteMedicalRecord(@PathVariable String id) {
        return ApiResult.of(
            200,
            "目标病历历史版本及对应文件已删除",
            medicalRecordService.deleteRecord(id, AuthPermission.currentUserOrThrow())
        );
    }

    @GetMapping("/clinic-api/medical-record/download")
    public ResponseEntity<FileSystemResource> downloadMedicalRecord(@RequestParam String id) {
        com.coshare.patientrecord.medicalrecord.dto.DownloadFile download = medicalRecordService.download(id, AuthPermission.currentUserOrThrow());
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .cacheControl(CacheControl.noStore().cachePrivate())
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                    .filename(download.fileName(), StandardCharsets.UTF_8)
                    .build()
                    .toString()
            )
            .body(download.resource());
    }

    @PostMapping("/clinic-api/files")
    public ApiResult<ClinicStoredFile> storeFile(@Valid @RequestBody ClinicFileUploadRequest request) throws IOException {
        requireClinicContributor();
        return ApiResult.of(200, "stored", fileService.store(request, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/clinic-api/files/**")
    public ResponseEntity<FileSystemResource> downloadFile(jakarta.servlet.http.HttpServletRequest request) {
        SessionUser user = AuthPermission.currentUserOrThrow();
        String storagePath = extractStoragePath(request);
        if (!databaseService.canReadStoragePath(storagePath, user)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "File not found");
        }
        FileSystemResource file = fileService.load(storagePath);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .cacheControl(CacheControl.noStore().cachePrivate())
            .header(HttpHeaders.PRAGMA, "no-cache")
            .body(file);
    }

    private String extractStoragePath(jakarta.servlet.http.HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        String prefix = contextPath + FILE_ROUTE_PREFIX;
        int index = requestUri.indexOf(prefix);
        if (index < 0) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid file path");
        }
        return requestUri.substring(index + prefix.length());
    }

    private void requireClinicWriter(Map<String, Object> payload) {
        if (containsAdminClinicSections(payload)) {
            requireClinicAdmin();
            return;
        }
        requireClinicContributor();
    }

    private boolean containsAdminClinicSections(Map<String, Object> payload) {
        JsonNode payloadNode = objectMapper.valueToTree(payload);
        return containsAdminClinicSections(payloadNode);
    }

    private boolean containsAdminClinicSections(JsonNode payload) {
        if (payload == null || !payload.isObject() || payload.isMissingNode() || payload.isNull()) return false;
        for (String section : ADMIN_CLINIC_SECTIONS) {
            if (payload.has(section)) return true;
        }
        return containsAdminClinicSections((JsonNode) payload.path("db"))
            || containsAdminClinicSections((JsonNode) payload.path("patch"))
            || containsAdminClinicSections((JsonNode) payload.path("data"))
            || containsAdminClinicSections((JsonNode) payload.path("payload"));
    }

    private void requireClinicAdmin() {
        AuthPermission.requireAnyRole("\u5f53\u524d\u8d26\u53f7\u65e0\u75c5\u5386\u7cfb\u7edf\u7ba1\u7406\u6743\u9650", "admin");
    }

    private void requireClinicContributor() {
        AuthPermission.requireAnyRole(
            "\u5f53\u524d\u8d26\u53f7\u65e0\u75c5\u5386\u5199\u5165\u6743\u9650",
            "admin",
            "quality",
            "frontdesk",
            "reception",
            "doctor",
            "nurse",
            "nursing",
            "lab",
            "ecg",
            "ultrasound",
            "inspection",
            "tcm"
        );
    }
}
