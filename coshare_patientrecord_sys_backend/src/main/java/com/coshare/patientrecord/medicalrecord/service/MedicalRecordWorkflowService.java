package com.coshare.patientrecord.medicalrecord.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.medicalrecord.dto.DownloadFile;
import com.coshare.patientrecord.medicalrecord.dto.InpatientAiGenerateRequest;
import com.coshare.patientrecord.medicalrecord.dto.MedicalRecordWorkflowSubmitRequest;
import com.coshare.patientrecord.medicalrecord.ooxml.DocxNodeMapper;
import com.coshare.patientrecord.medicalrecord.ooxml.DocxPackageSanitizer;
import com.coshare.patientrecord.medicalrecord.repository.MedicalRecordWorkflowRepository;
import com.coshare.patientrecord.medicalrecord.repository.MedicalRecordWorkflowRepository.Asset;
import com.coshare.patientrecord.medicalrecord.repository.MedicalRecordWorkflowRepository.Inspection;
import com.coshare.patientrecord.medicalrecord.repository.MedicalRecordWorkflowRepository.Task;
import com.coshare.patientrecord.medicalrecord.repository.MedicalRecordWorkflowRepository.TaskRow;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class MedicalRecordWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(MedicalRecordWorkflowService.class);

    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final long MAX_INPUT_BYTES = 10L * 1024 * 1024;
    private static final List<String> AUTHOR_ROLES = List.of("doctor");
    private static final String BUILTIN_TEMPLATE_RESOURCE = "medical-record-templates/inpatient-record-reference-v1.docx";
    private static final String BUILTIN_TEMPLATE_FILE_NAME = "inpatient-record-reference-v1.docx";
    private static final String BUILTIN_TEMPLATE_ID = "builtin-inpatient-v1";

    private final MedicalRecordWorkflowRepository repository;
    private final DocxPackageSanitizer sanitizer;
    private final DocxNodeMapper nodeMapper;
    private final ClinicMedicalRecordService medicalRecordService;
    private final MedicalRecordSourceBuilder sourceBuilder;
    private final ThreadPoolTaskExecutor executor;
    private final Path assetRoot;

    public MedicalRecordWorkflowService(
        MedicalRecordWorkflowRepository repository,
        DocxPackageSanitizer sanitizer,
        DocxNodeMapper nodeMapper,
        ClinicMedicalRecordService medicalRecordService,
        MedicalRecordSourceBuilder sourceBuilder,
        @Qualifier("aiDocumentTaskExecutor") ThreadPoolTaskExecutor executor,
        @Value("${clinic.medical-record-workflow-dir:${clinic.attachment-dir}/../medical-record-workflow}") String assetRoot
    ) {
        this.repository = repository;
        this.sanitizer = sanitizer;
        this.nodeMapper = nodeMapper;
        this.medicalRecordService = medicalRecordService;
        this.sourceBuilder = sourceBuilder;
        this.executor = executor;
        this.assetRoot = Path.of(assetRoot).toAbsolutePath().normalize();
    }

    @Transactional
    public Map<String, Object> inspectUpload(
        String patientId,
        String encounterId,
        MultipartFile file,
        SessionUser user
    ) {
        requireAuthor(user);
        String normalizedPatientId = safe(patientId);
        String normalizedEncounterId = safe(encounterId);
        String scopeId = scopeId(normalizedPatientId, normalizedEncounterId);
        assertScopeReadable(scopeId, normalizedPatientId, normalizedEncounterId, user);
        byte[] sourceBytes = uploadBytes(file);
        String fileName = sanitizeFileName(file.getOriginalFilename());
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".docx")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参考文档仅支持 DOCX 格式");
        }

        String sourceAssetId = "mrasset-" + UUID.randomUUID();
        Path sourcePath = writeAsset(scopeId, sourceAssetId, fileName, sourceBytes);
        DocxPackageSanitizer.Result inspection = sanitizer.inspectAndSanitize(sourceBytes);
        Asset sourceAsset = asset(
            sourceAssetId,
            scopeId,
            normalizedPatientId,
            normalizedEncounterId,
            "SOURCE",
            fileName,
            sourcePath,
            sourceBytes,
            "",
            inspection.packageValidation().valid(),
            Map.of("inspectionDecision", inspection.decision().name())
        );
        repository.insertAsset(sourceAsset, user);

        String sanitizedAssetId = "";
        if (inspection.decision() == DocxPackageSanitizer.Decision.SANITIZED) {
            byte[] sanitizedBytes = inspection.sanitizedBytes();
            sanitizedAssetId = "mrasset-" + UUID.randomUUID();
            Path sanitizedPath = writeAsset(scopeId, sanitizedAssetId, "sanitized-" + fileName, sanitizedBytes);
            repository.insertAsset(asset(
                sanitizedAssetId,
                scopeId,
                normalizedPatientId,
                normalizedEncounterId,
                "SANITIZED",
                "sanitized-" + fileName,
                sanitizedPath,
                sanitizedBytes,
                sourceAssetId,
                true,
                Map.of("sanitizerVersion", "docx-package-sanitizer-v1")
            ), user);
        }

        String reportId = "mrreport-" + UUID.randomUUID();
        repository.insertReport(reportId, sourceAssetId, sanitizedAssetId, inspection, user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", reportId);
        result.put("sourceAssetId", sourceAssetId);
        result.put("sanitizedAssetId", sanitizedAssetId);
        result.put("scopeId", scopeId);
        result.put("decision", inspection.decision().name());
        result.put("highestRiskLevel", inspection.highestRisk().name());
        result.put("packageValidation", inspection.packageValidation());
        result.put("findings", inspection.findings());
        if (inspection.decision() != DocxPackageSanitizer.Decision.REJECTED) {
            byte[] effectiveBytes = inspection.decision() == DocxPackageSanitizer.Decision.SANITIZED
                ? inspection.sanitizedBytes()
                : sourceBytes;
            result.put("nodes", nodeMapper.catalog(effectiveBytes).nodes());
        } else {
            result.put("nodes", List.of());
        }
        result.put("canGenerate", inspection.decision() != DocxPackageSanitizer.Decision.REJECTED);
        result.put("effectiveAssetId", sanitizedAssetId.isBlank() ? sourceAssetId : sanitizedAssetId);
        return result;
    }

    @Transactional
    public Map<String, Object> inspectBuiltinTemplate(String patientId, String encounterId, SessionUser user) {
        requireAuthor(user);
        String normalizedPatientId = safe(patientId);
        String normalizedEncounterId = safe(encounterId);
        String scopeId = scopeId(normalizedPatientId, normalizedEncounterId);
        assertScopeReadable(scopeId, normalizedPatientId, normalizedEncounterId, user);
        byte[] sourceBytes = builtinTemplateBytes();
        DocxPackageSanitizer.Result inspection = sanitizer.inspectAndSanitize(sourceBytes);
        if (inspection.decision() == DocxPackageSanitizer.Decision.REJECTED) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "内置范本未通过安全检查，请联系管理员更换范本"
            );
        }
        String sourceAssetId = reusableBuiltinAssetId(scopeId, sha256(sourceBytes));
        if (sourceAssetId.isBlank()) {
            sourceAssetId = "mrasset-" + UUID.randomUUID();
            Path sourcePath = writeAsset(scopeId, sourceAssetId, BUILTIN_TEMPLATE_FILE_NAME, sourceBytes);
            repository.insertAsset(asset(
                sourceAssetId,
                scopeId,
                normalizedPatientId,
                normalizedEncounterId,
                "SOURCE",
                BUILTIN_TEMPLATE_FILE_NAME,
                sourcePath,
                sourceBytes,
                "",
                inspection.packageValidation().valid(),
                Map.of("templateId", BUILTIN_TEMPLATE_ID, "inspectionDecision", inspection.decision().name())
            ), user);
        }
        String sanitizedAssetId = "";
        if (inspection.decision() == DocxPackageSanitizer.Decision.SANITIZED) {
            byte[] sanitizedBytes = inspection.sanitizedBytes();
            sanitizedAssetId = "mrasset-" + UUID.randomUUID();
            Path sanitizedPath = writeAsset(
                scopeId, sanitizedAssetId, "sanitized-" + BUILTIN_TEMPLATE_FILE_NAME, sanitizedBytes
            );
            repository.insertAsset(asset(
                sanitizedAssetId,
                scopeId,
                normalizedPatientId,
                normalizedEncounterId,
                "SANITIZED",
                "sanitized-" + BUILTIN_TEMPLATE_FILE_NAME,
                sanitizedPath,
                sanitizedBytes,
                sourceAssetId,
                true,
                Map.of("templateId", BUILTIN_TEMPLATE_ID, "sanitizerVersion", "docx-package-sanitizer-v1")
            ), user);
        }
        String reportId = "mrreport-" + UUID.randomUUID();
        repository.insertReport(reportId, sourceAssetId, sanitizedAssetId, inspection, user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", reportId);
        result.put("templateId", BUILTIN_TEMPLATE_ID);
        result.put("sourceAssetId", sourceAssetId);
        result.put("sanitizedAssetId", sanitizedAssetId);
        result.put("scopeId", scopeId);
        result.put("decision", inspection.decision().name());
        result.put("highestRiskLevel", inspection.highestRisk().name());
        result.put("packageValidation", inspection.packageValidation());
        result.put("findings", inspection.findings());
        if (inspection.decision() != DocxPackageSanitizer.Decision.REJECTED) {
            byte[] effectiveBytes = inspection.decision() == DocxPackageSanitizer.Decision.SANITIZED
                ? inspection.sanitizedBytes()
                : sourceBytes;
            result.put("nodes", nodeMapper.catalog(effectiveBytes).nodes());
        } else {
            result.put("nodes", List.of());
        }
        result.put("canGenerate", inspection.decision() != DocxPackageSanitizer.Decision.REJECTED);
        result.put("effectiveAssetId", sanitizedAssetId.isBlank() ? sourceAssetId : sanitizedAssetId);
        return result;
    }

    public Map<String, Object> submit(MedicalRecordWorkflowSubmitRequest request, SessionUser user) {
        requireAuthor(user);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少请求参数");
        }
        String reportId = safe(request.reportId());
        String referenceAssetId = safe(request.referenceAssetId());
        if (reportId.isBlank() && referenceAssetId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少文档检查报告ID或参考资产ID");
        }
        if (!reportId.isBlank() && !referenceAssetId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档检查报告与参考资产只能二选一");
        }
        String sourceRecordId = safe(request.sourceRecordId());
        if (sourceRecordId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少基础目标病历版本ID");
        }
        String mode = mappingMode(request.mappingMode()).name();

        String scopeId;
        String patientId;
        String encounterId;
        String sourceAssetId;
        String sanitizedAssetId;
        String taskReportId;
        if (!reportId.isBlank()) {
            Inspection inspection = repository.loadInspection(reportId);
            assertScopeReadable(inspection.scopeId(), inspection.patientId(), inspection.encounterId(), user);
            if ("REJECTED".equals(inspection.decision())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "文档包检查未通过，不能提交生成");
            }
            scopeId = inspection.scopeId();
            patientId = inspection.patientId();
            encounterId = inspection.encounterId();
            sourceAssetId = inspection.sourceAssetId();
            sanitizedAssetId = inspection.sanitizedAssetId();
            taskReportId = inspection.reportId();
        } else {
            Asset reference = repository.loadAsset(referenceAssetId);
            if (!"OUTPUT".equals(reference.assetType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "精修参考必须是既往生成结果资产");
            }
            if (!reference.mediaTypeVerified() || !reference.packageVerified() || !DOCX_MIME.equals(reference.mimeType())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "参考资产未通过 DOCX 安全校验");
            }
            assertScopeReadable(reference.scopeId(), reference.patientId(), reference.encounterId(), user);
            readAssetBytes(reference);
            scopeId = reference.scopeId();
            patientId = reference.patientId();
            encounterId = reference.encounterId();
            sourceAssetId = reference.id();
            sanitizedAssetId = "";
            taskReportId = "";
        }
        String taskId = "mrtask-" + UUID.randomUUID();
        String preAiExportId = safe(request.preAiExportId());
        Object preAiExportSnapshot = null;
        if (!preAiExportId.isBlank()) {
            if (encounterId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "脱敏资料附件仅支持前置病例范围");
            }
            MedicalRecordWorkflowRepository.PreAiExportRef export = repository.findPreAiExport(preAiExportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "脱敏资料版本不存在"));
            if (!encounterId.equals(export.encounterId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "脱敏资料与当前病例不匹配");
            }
            if (!"GENERATED".equals(export.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "脱敏资料已因事实变更失效，请重新生成后再附加");
            }
            preAiExportSnapshot = export.maskedSnapshot();
        }
        List<String> targetNodeKeys = validateTargetNodeKeys(
            request.targetNodeKeys(),
            mode,
            sanitizedAssetId.isBlank() ? sourceAssetId : sanitizedAssetId
        );
        Map<String, Object> taskRequest = requestMap(
            patientId,
            encounterId,
            sourceRecordId,
            safe(request.prompt()),
            mode,
            targetNodeKeys,
            referenceAssetId,
            preAiExportId,
            preAiExportSnapshot,
            sanitizeConversationHistory(request.conversationHistory())
        );
        repository.insertTask(new Task(
            taskId,
            scopeId,
            patientId,
            encounterId,
            sourceRecordId,
            sourceAssetId,
            sanitizedAssetId,
            taskReportId,
            mode,
            safe(request.prompt()),
            taskRequest,
            1,
            ""
        ), user);
        enqueue(taskId, user);
        return status(taskId, user);
    }

    public Map<String, Object> retry(String taskId, SessionUser user) {
        requireAuthor(user);
        TaskRow current = repository.loadOwnedTask(safe(taskId), user);
        if (!"FAILED".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有失败的病历生成任务可以重试");
        }
        assertScopeReadable(current.scopeId(), current.patientId(), current.encounterId(), user);
        String retryId = "mrtask-" + UUID.randomUUID();
        repository.insertTask(new Task(
            retryId,
            current.scopeId(),
            current.patientId(),
            current.encounterId(),
            current.sourceRecordId(),
            current.sourceAssetId(),
            current.sanitizedAssetId(),
            current.reportId(),
            mappingMode(current.mappingMode()).name(),
            current.prompt(),
            current.request(),
            current.attemptCount() + 1,
            current.id()
        ), user);
        enqueue(retryId, user);
        return status(retryId, user);
    }

    public Map<String, Object> status(String taskId, SessionUser user) {
        TaskRow row = repository.loadOwnedTask(safe(taskId), user);
        assertScopeReadable(row.scopeId(), row.patientId(), row.encounterId(), user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", row.id());
        result.put("scopeId", row.scopeId());
        result.put("status", row.status());
        result.put("currentStage", row.currentStage());
        result.put("mappingMode", row.mappingMode());
        result.put("attemptCount", row.attemptCount());
        result.put("retryOfTaskId", row.retryOfTaskId());
        result.put("sourceAssetId", row.sourceAssetId());
        result.put("sanitizedAssetId", row.sanitizedAssetId());
        result.put("outputAssetId", row.outputAssetId());
        result.put("resultRecordId", row.resultRecordId());
        result.put("model", row.modelName());
        result.put("errorCode", row.errorCode());
        result.put("errorMessage", row.errorMessage());
        result.put("createdAt", row.createdAt());
        result.put("startedAt", row.startedAt());
        result.put("finishedAt", row.finishedAt());
        result.put("updatedAt", row.updatedAt());
        result.put("result", row.result());
        result.put("events", repository.events(row.id()));
        return result;
    }

    public Map<String, Object> mappings(String taskId, SessionUser user) {
        TaskRow row = repository.loadOwnedTask(safe(taskId), user);
        assertScopeReadable(row.scopeId(), row.patientId(), row.encounterId(), user);
        return Map.of(
            "taskId", row.id(),
            "status", row.status(),
            "mappingMode", row.mappingMode(),
            "mappings", repository.mappings(row.id())
        );
    }

    public DownloadFile downloadAsset(String assetId, SessionUser user) {
        Asset asset = repository.loadAsset(safe(assetId));
        assertScopeReadable(asset.scopeId(), asset.patientId(), asset.encounterId(), user);
        if (!asset.mediaTypeVerified() || !asset.packageVerified() || !DOCX_MIME.equals(asset.mimeType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "病历文档资产未通过 DOCX 安全校验");
        }
        readAssetBytes(asset);
        Path path = validatedAssetPath(asset);
        return new DownloadFile(new FileSystemResource(path), asset.originalFileName());
    }

    private void enqueue(String taskId, SessionUser user) {
        try {
            executor.execute(() -> run(taskId, user));
        } catch (RuntimeException error) {
            repository.failTask(taskId, "QUEUE_REJECTED", "QUEUE_FULL", "病历生成任务队列已满，请稍后重试", user);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "病历生成任务队列已满，请稍后重试");
        }
    }

    /** 巡检：RUNNING 超过 15 分钟的生成任务视为执行中断，转失败释放生成队列。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300_000L)
    public void failStuckGenerationTasks() {
        int failed = repository.failStuckGenerationTasks();
        if (failed > 0) log.warn("Stuck generation tasks failed by sweep: count={}", failed);
    }

    private void run(String taskId, SessionUser user) {
        if (!repository.claimTask(taskId)) return;
        String stage = "ASSET_LOADING";
        String recordId = "";
        Path generatedRecordPath = null;
        String outputAssetId = "";
        Path outputAssetPath = null;
        try {
            TaskRow task = repository.loadOwnedTask(taskId, user);
            Asset inputAsset = repository.loadAsset(
                task.sanitizedAssetId().isBlank() ? task.sourceAssetId() : task.sanitizedAssetId()
            );
            byte[] inputBytes = readAssetBytes(inputAsset);

            stage = "AI_GENERATION";
            repository.updateStage(taskId, stage, "正在依据已检查文档生成病历草稿", user);
            InpatientAiGenerateRequest request = new InpatientAiGenerateRequest(
                task.patientId(),
                task.encounterId(),
                task.sourceRecordId(),
                task.prompt(),
                task.request().get("preAiExport"),
                stringList(task.request().get("conversationHistory"))
            );
            java.util.function.Consumer<String> chapterSink = text ->
                repository.appendProgressEvent(taskId, text, user);
            Map<String, Object> generated = medicalRecordService.generateInpatientAi(
                request,
                inputAsset.originalFileName(),
                inputBytes,
                mappingMode(task.mappingMode()),
                stringList(task.request().get("targetNodeKeys")),
                user,
                chapterSink
            );
            Map<String, Object> record = mapValue(generated.get("record"));
            recordId = safe(record.get("id"));
            if (recordId.isBlank()) throw new IllegalStateException("生成结果缺少病历版本ID");
            String generatedFilePath = safe(record.get("filePath"));
            if (!generatedFilePath.isBlank()) {
                generatedRecordPath = Path.of(generatedFilePath).toAbsolutePath().normalize();
            }

            stage = "OUTPUT_ASSET";
            repository.updateStage(taskId, stage, "正在登记生成文档资产", user);
            DownloadFile generatedDownload = medicalRecordService.download(recordId, user);
            Path downloadablePath = generatedDownload.resource().getFile().toPath().toAbsolutePath().normalize();
            if (generatedRecordPath == null) generatedRecordPath = downloadablePath;
            byte[] outputBytes = Files.readAllBytes(downloadablePath);
            DocxPackageSanitizer.Result outputInspection = sanitizer.inspectAndSanitize(outputBytes);
            if (outputInspection.decision() != DocxPackageSanitizer.Decision.ACCEPTED) {
                throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "生成的 DOCX 未通过输出安全与结构校验"
                );
            }
            outputAssetId = "mrasset-" + UUID.randomUUID();
            outputAssetPath = writeAsset(task.scopeId(), outputAssetId, generatedDownload.fileName(), outputBytes);
            repository.insertAsset(asset(
                outputAssetId,
                task.scopeId(),
                task.patientId(),
                task.encounterId(),
                "OUTPUT",
                generatedDownload.fileName(),
                outputAssetPath,
                outputBytes,
                inputAsset.id(),
                outputInspection.packageValidation().valid(),
                Map.of(
                    "recordId", recordId,
                    "taskId", taskId,
                    "inspectionDecision", outputInspection.decision().name(),
                    "sanitizerVersion", "docx-package-sanitizer-v1"
                )
            ), user);

            stage = "NODE_MAPPING";
            repository.updateStage(taskId, stage, "正在计算文档节点差异", user);
            DocxNodeMapper.MappingPlan plan = nodeMapper.map(
                nodeMapper.catalog(inputBytes),
                nodeMapper.catalog(outputBytes),
                mappingMode(task.mappingMode())
            );
            repository.replaceMappings(taskId, plan);
            repository.linkVersionAsset(recordId, task.sourceAssetId(), taskId, "SOURCE", user);
            if (!task.sanitizedAssetId().isBlank()) {
                repository.linkVersionAsset(recordId, task.sanitizedAssetId(), taskId, "SANITIZED", user);
            }
            repository.linkVersionAsset(recordId, outputAssetId, taskId, "OUTPUT", user);

            Map<String, Object> taskResult = new LinkedHashMap<>(generated);
            taskResult.put("mappingSummary", Map.of(
                "sourceNodeCount", plan.sourceNodeCount(),
                "targetNodeCount", plan.targetNodeCount(),
                "mappedCount", plan.mappedCount(),
                "sourceUnmappedCount", plan.sourceUnmappedCount(),
                "targetUnmappedCount", plan.targetUnmappedCount()
            ));
            repository.completeTask(taskId, outputAssetId, recordId, safe(generated.get("model")), taskResult, user);
        } catch (Exception error) {
            compensateFailedRun(
                taskId,
                stage,
                outputAssetId,
                outputAssetPath,
                recordId,
                generatedRecordPath,
                error,
                user
            );
        }
    }

    private void compensateFailedRun(
        String taskId,
        String stage,
        String outputAssetId,
        Path outputAssetPath,
        String recordId,
        Path generatedRecordPath,
        Exception originalError,
        SessionUser user
    ) {
        String failureCode = errorCode(originalError);
        String failureMessage = errorMessage(originalError);
        boolean compensated = false;
        try {
            compensated = repository.compensateFailedRun(taskId, outputAssetId, recordId);
            if (compensated) {
                deleteCompensatedFile(outputAssetPath);
                deleteCompensatedFile(generatedRecordPath);
            }
        } catch (Exception compensationError) {
            failureCode = "COMPENSATION_FAILED";
            failureMessage = failureMessage
                + "；自动补偿失败："
                + errorMessage(compensationError);
        }
        if (!compensated && !"COMPENSATION_FAILED".equals(failureCode)) {
            failureCode = "COMPENSATION_SKIPPED";
            failureMessage = failureMessage + "；任务状态已变化，未执行自动补偿";
        }
        repository.failTask(taskId, stage, failureCode, failureMessage, user);
    }

    private void deleteCompensatedFile(Path path) throws IOException {
        if (path == null) return;
        Files.deleteIfExists(path);
    }

    private Asset asset(
        String id,
        String scopeId,
        String patientId,
        String encounterId,
        String type,
        String fileName,
        Path path,
        byte[] bytes,
        String parentId,
        boolean packageVerified,
        Map<String, Object> metadata
    ) {
        return new Asset(
            id,
            scopeId,
            patientId,
            encounterId,
            type,
            fileName,
            path.toString(),
            DOCX_MIME,
            bytes.length,
            sha256(bytes),
            parentId,
            true,
            packageVerified,
            metadata
        );
    }

    private byte[] uploadBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传待检查的 DOCX 文档");
        }
        if (file.getSize() > MAX_INPUT_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "参考文档不能超过 10 MB");
        }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传待检查的 DOCX 文档");
            return bytes;
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参考文档读取失败", error);
        }
    }

    private Path writeAsset(String scopeId, String assetId, String fileName, byte[] bytes) {
        Path temporary = null;
        try {
            Path directory = assetRoot.resolve(sanitizePathSegment(scopeId)).normalize();
            if (!directory.startsWith(assetRoot)) throw new IOException("invalid asset directory");
            Files.createDirectories(directory);
            Path target = directory.resolve(assetId + "-" + sanitizeFileName(fileName)).normalize();
            if (!target.startsWith(assetRoot)) throw new IOException("invalid asset path");
            temporary = directory.resolve("." + assetId + ".tmp").normalize();
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            registerRollbackCleanup(target);
            return target;
        } catch (IOException error) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The maintenance scanner can remove abandoned temporary files.
                }
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "病历文档资产写入失败", error);
        }
    }

    private void registerRollbackCleanup(Path path) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_ROLLED_BACK) return;
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The maintenance scanner can remove an asset left after a failed rollback cleanup.
                }
            }
        });
    }

    private byte[] readAssetBytes(Asset asset) {
        Path path = validatedAssetPath(asset);
        try {
            long size = Files.size(path);
            if (size != asset.fileSize() || size > MAX_INPUT_BYTES) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "病历文档资产大小校验失败");
            }
            byte[] bytes = Files.readAllBytes(path);
            if (!sha256(bytes).equalsIgnoreCase(asset.sha256())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "病历文档资产摘要校验失败");
            }
            return bytes;
        } catch (ResponseStatusException error) {
            throw error;
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "病历文档资产无法读取", error);
        }
    }

    private Path validatedAssetPath(Asset asset) {
        Path path = Path.of(asset.storagePath()).toAbsolutePath().normalize();
        if (!path.startsWith(assetRoot) || !Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "病历文档资产不存在");
        }
        return path;
    }

    private void assertScopeReadable(
        String scopeId,
        String patientId,
        String encounterId,
        SessionUser user
    ) {
        sourceBuilder.assertCanReadScope(scopeId, user);
        if (scopeId.startsWith("preai:")) {
            sourceBuilder.readEncounterSource(encounterId, user, false, "", "");
        } else {
            sourceBuilder.readPatientSource(patientId, user, false, "", "");
        }
    }

    private String scopeId(String patientId, String encounterId) {
        if (!encounterId.isBlank()) return "preai:" + encounterId;
        if (patientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者ID或前置病例ID");
        }
        return patientId;
    }

    private DocxNodeMapper.MappingMode mappingMode(String raw) {
        String value = safe(raw).toUpperCase(Locale.ROOT);
        if (value.isBlank()) return DocxNodeMapper.MappingMode.CONTROLLED;
        try {
            return DocxNodeMapper.MappingMode.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的节点映射模式");
        }
    }

    private List<String> validateTargetNodeKeys(
        List<String> rawKeys,
        String mappingMode,
        String effectiveAssetId
    ) {
        List<String> source = rawKeys == null ? List.of() : rawKeys;
        List<String> keys = source.stream()
            .map(MedicalRecordWorkflowService::safe)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
        if (keys.size() != source.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "受控目标节点键不能为空或重复");
        }
        if (!"CONTROLLED".equals(mappingMode) && !keys.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "旧顺序映射模式不支持指定目标节点");
        }
        if (keys.isEmpty()) return keys;

        Asset inputAsset = repository.loadAsset(effectiveAssetId);
        List<String> available = nodeMapper.catalog(readAssetBytes(inputAsset)).nodes().stream()
            .map(DocxNodeMapper.CatalogNode::nodeKey)
            .toList();
        if (!available.containsAll(keys)) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "受控目标节点不存在或文档已变化"
            );
        }
        return keys;
    }

    private Map<String, Object> requestMap(
        String patientId,
        String encounterId,
        String sourceRecordId,
        String prompt,
        String mappingMode,
        List<String> targetNodeKeys,
        String referenceAssetId,
        String preAiExportId,
        Object preAiExportSnapshot,
        List<String> conversationHistory
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patientId", patientId);
        result.put("encounterId", encounterId);
        result.put("sourceRecordId", sourceRecordId);
        result.put("prompt", prompt);
        result.put("mappingMode", mappingMode);
        result.put("targetNodeKeys", List.copyOf(targetNodeKeys));
        result.put("referenceAssetId", safe(referenceAssetId));
        result.put("preAiExportId", safe(preAiExportId));
        if (preAiExportSnapshot != null) result.put("preAiExport", preAiExportSnapshot);
        if (!conversationHistory.isEmpty()) result.put("conversationHistory", conversationHistory);
        return result;
    }

    /**
     * 会话记忆：仅保留医生各轮的额外备注（固定口径每轮都在 prompt 里，无需重复），
     * 最多回看 6 轮、单条截断 300 字，避免历史无限膨胀。
     */
    private List<String> sanitizeConversationHistory(List<String> conversationHistory) {
        if (conversationHistory == null || conversationHistory.isEmpty()) return List.of();
        List<String> items = conversationHistory.stream()
            .map(item -> safe(item))
            .filter(item -> !item.isBlank())
            .map(item -> item.length() > 300 ? item.substring(0, 300) : item)
            .toList();
        return items.size() > 6 ? items.subList(items.size() - 6, items.size()) : List.copyOf(items);
    }

    private String reusableBuiltinAssetId(String scopeId, String digest) {
        return repository.findActiveSourceAsset(scopeId, digest)
            .filter(asset -> {
                try {
                    validatedAssetPath(asset);
                    return true;
                } catch (RuntimeException ignored) {
                    return false;
                }
            })
            .map(Asset::id)
            .orElse("");
    }

    private byte[] builtinTemplateBytes() {
        try (java.io.InputStream input = getClass().getClassLoader().getResourceAsStream(BUILTIN_TEMPLATE_RESOURCE)) {
            if (input == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "内置住院病历范本缺失");
            }
            byte[] bytes = input.readAllBytes();
            if (bytes.length == 0 || bytes.length > MAX_INPUT_BYTES) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "内置住院病历范本大小异常");
            }
            return bytes;
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "内置住院病历范本读取失败", error);
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> raw)) return List.of();
        return raw.stream().map(String::valueOf).toList();
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private void requireAuthor(SessionUser user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
        if (!AUTHOR_ROLES.contains(safe(user.role()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无权生成目标病历");
        }
    }

    private String errorCode(Exception error) {
        if (error instanceof ResponseStatusException status) {
            return "HTTP_" + status.getStatusCode().value();
        }
        return error.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }

    private String errorMessage(Exception error) {
        if (error instanceof ResponseStatusException status && status.getReason() != null) {
            return status.getReason();
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? "病历生成失败" : message;
    }

    private String sanitizeFileName(String value) {
        String sanitized = safe(value).replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_");
        return sanitized.isBlank() ? "medical-record.docx" : sanitized;
    }

    private String sanitizePathSegment(String value) {
        String sanitized = safe(value).replaceAll("[^A-Za-z0-9._-]+", "_");
        return sanitized.isBlank() ? "scope" : sanitized;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    private static String safe(Object value) {
        return String.valueOf(value == null ? "" : value).trim();
    }
}
