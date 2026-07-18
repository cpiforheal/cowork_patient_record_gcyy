package com.coshare.patientrecord.preai;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.common.exception.VersionConflictException;
import com.coshare.patientrecord.clinic.service.ClinicDatabaseService;
import com.coshare.patientrecord.clinicqueue.ClinicQueueService;
import com.coshare.patientrecord.file.dto.ClinicFileUploadRequest;
import com.coshare.patientrecord.file.model.ClinicStoredFile;
import com.coshare.patientrecord.file.service.ClinicFileService;
import com.coshare.patientrecord.inventory.service.InventoryPackageService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

@Service
@Profile("mysql")
public class PreAiEncounterService {

    private static final Logger log = LoggerFactory.getLogger(PreAiEncounterService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> STAGE_ORDER = List.of("REGISTRATION", "INSPECTION", "RECEPTION", "TCM", "DOCTOR", "SURGERY", "REVIEW");
    private static final Set<String> READ_ROLES = Set.of("admin", "frontdesk", "inspection", "reception", "tcm", "doctor", "nurse", "nursing", "lab", "ecg", "ultrasound", "quality", "manager");
    private static final Map<String, Set<String>> STAGE_EDITORS = Map.of(
        "REGISTRATION", Set.of("admin", "frontdesk"),
        "INSPECTION", Set.of("admin", "inspection"),
        "RECEPTION", Set.of("admin", "reception", "doctor"),
        "TCM", Set.of("admin", "tcm"),
        "DOCTOR", Set.of("admin", "doctor"),
        "SURGERY", Set.of("admin", "nurse", "nursing"),
        "REVIEW", Set.of("admin", "doctor")
    );
    private static final Set<String> DUTY_CODES = Set.of(
        "FRONT_DESK", "RECEPTION_DOCTOR", "TCM_DOCTOR", "INSPECTION_DOCTOR", "LAB_STAFF",
        "BASIC_NURSING", "ATTENDING_DOCTOR", "SURGEON", "OPERATING_ROOM_NURSE", "FINAL_REVIEW_DOCTOR"
    );
    private static final Map<String, Set<String>> STAGE_DUTIES = Map.of(
        "REGISTRATION", Set.of("FRONT_DESK"),
        "INSPECTION", Set.of("INSPECTION_DOCTOR"),
        "RECEPTION", Set.of("RECEPTION_DOCTOR", "ATTENDING_DOCTOR"),
        "TCM", Set.of("TCM_DOCTOR"),
        "DOCTOR", Set.of("ATTENDING_DOCTOR"),
        "SURGERY", Set.of("SURGEON", "OPERATING_ROOM_NURSE"),
        "REVIEW", Set.of("FINAL_REVIEW_DOCTOR", "ATTENDING_DOCTOR")
    );
    private static final Map<String, Set<String>> ALLOWED_FIELDS = Map.of(
        "REGISTRATION", Set.of(
            "patientName", "gender", "birthDate", "age", "phone", "identityType", "identityNumber", "address",
            "contactName", "contactRelation", "contactPhone", "visitDate", "patientSource", "registrationNote",
            "visitNo", "admissionNo", "medicalRecordNo", "inpatientNo", "ward", "bedNo", "admissionCount",
            "nationality", "nativePlace", "birthplace", "maritalStatus", "admissionMethod", "insuranceType", "paymentMethod"
        ),
        "INSPECTION", Set.of(
            "examinationDirection", "diseaseDirections", "examinationTypes", "lesionLocation", "clockPosition",
            "lesionSize", "lesionExtent", "lesionDepth", "visualFindings", "digitalExamFindings", "anoscopyFindings",
            "otherFindings", "factualConclusion", "factualConclusionOverride", "factualConclusionSourceHash", "factualConclusionConfirmed"
        ),
        "RECEPTION", Set.of(
            "chiefComplaint", "symptomDuration", "onsetTrigger", "symptomPattern", "symptomChanges", "aggravatingFactors",
            "bleedingFeatures", "painFeatures", "prolapseReduction", "associatedSymptoms", "recentAggravation",
            "previousTreatment", "generalCondition", "stoolFrequency", "stoolCharacteristics", "chiefComplaintText", "presentIllness",
            "presentIllnessOverride", "presentIllnessSourceHash", "presentIllnessConfirmed", "chronicDiseaseItems", "surgicalHistoryItems",
            "pastHistory", "surgicalHistory", "traumaHistory", "transfusionHistory", "vaccinationHistory",
            "medicationHistory", "allergyHistory", "personalHistory", "maritalHistory", "familyHistory", "historySupplement",
            "reviewOpinion", "nextStepRecommendation", "dispositionSuggestion", "recommendedAuxiliaryExams", "specialCircumstances"
        ),
        "TCM", Set.of(
            "tcmDisease", "primarySyndrome", "concurrentSyndrome", "inspection", "auscultationOlfaction", "inquiry",
            "palpation", "tongue", "pulse", "syndromeBasis", "syndromeBasisOverride", "syndromeBasisSourceHash",
            "syndromeBasisConfirmed", "treatmentPrinciple", "comorbidTcmItems"
        ),
        "DOCTOR", Set.of(
            "finalRoute", "primaryWesternDiagnosis", "secondaryWesternDiagnoses", "secondaryDiagnosisItems",
            "diagnosisEvidence", "diagnosisBasis", "diagnosisBasisOverride", "diagnosisBasisSourceHash", "diagnosisBasisConfirmed",
            "differentialDiagnoses", "treatmentPath", "treatmentMeasures", "medicationDirections", "examPlans",
            "surgeryArrangements", "observationFocus", "treatmentPlan", "treatmentPlanOverride", "treatmentPlanSourceHash",
            "treatmentPlanConfirmed", "admissionSeverity", "treatmentCategory", "plannedPrimaryOperation",
            "plannedSecondaryOperations", "operationIndications", "plannedOperationName", "plannedOperationSite",
            "plannedOperationPlan", "recommendedAnesthesia", "operationGrade", "specialOperationPlan",
            "requiredAuxiliaryTaskIds", "routeOverrideReason"
        ),
        "SURGERY", Set.of(
            "actualPrimaryOperation", "actualSecondaryOperations", "actualOperationName", "operationDate", "operationStartTime",
            "operationEndTime", "operationSite", "anesthesiaMethod", "intraoperativeFindingOptions", "intraoperativeFindings",
            "intraoperativeFindingsOverride", "intraoperativeFindingsSourceHash", "intraoperativeFindingsConfirmed",
            "procedureStepOptions", "procedurePerformed", "procedurePerformedOverride", "procedurePerformedSourceHash",
            "procedurePerformedConfirmed",
            "specimenPathology", "bloodLossDrainDressing", "bloodLossMeasurement", "drainageOptions", "dressingOptions",
            "complications", "postoperativeDestination", "postoperativeHandoffOptions", "postoperativeHandoff",
            "postoperativeHandoffOverride", "postoperativeHandoffSourceHash", "postoperativeHandoffConfirmed",
            "physicianConfirmed", "physicianConfirmedBy", "physicianConfirmedAt"
        ),
        "REVIEW", Set.of("reviewStatement")
    );
    private static final Map<String, String> AUX_OWNER_ROLES = Map.of(
        "LAB", "lab", "ECG", "ecg", "IMAGING", "ultrasound", "VITAL_SIGNS", "nursing", "COLONOSCOPY", "doctor"
    );
    private static final Map<String, Set<String>> AUX_DUTIES = Map.of(
        "LAB", Set.of("LAB_STAFF"),
        "VITAL_SIGNS", Set.of("BASIC_NURSING"),
        "COLONOSCOPY", Set.of("INSPECTION_DOCTOR", "ATTENDING_DOCTOR"),
        "ECG", Set.of(),
        "IMAGING", Set.of()
    );
    private static final Map<String, Set<String>> AUX_FIELDS = Map.of(
        "LAB", Set.of("project", "sampledAt", "reportedAt", "result", "abnormalItems", "conclusion", "rawReport"),
        "ECG", Set.of("examinedAt", "findings", "conclusion", "rawReport"),
        "IMAGING", Set.of("modality", "bodyPart", "examinedAt", "findings", "conclusion", "rawReport"),
        "VITAL_SIGNS", Set.of("measuredAt", "systolicBp", "diastolicBp", "temperature", "pulse", "respiration", "nursingConditions", "note"),
        "COLONOSCOPY", Set.of(
            "status", "examinedAt", "scope", "findings", "lesionLocation", "lesionCount", "lesionSize", "lesionMorphology",
            "biopsyPerformed", "resectionPerformed", "pathologySubmitted", "conclusion", "abnormalDescription"
        )
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ClinicDatabaseService clinicDatabaseService;
    private final ClinicFileService fileService;
    private final PreAiPrivacyService privacyService;
    private final ClinicQueueService clinicQueueService;
    private final InventoryPackageService inventoryPackageService;
    private final Path generatedDir;

    public PreAiEncounterService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        ClinicDatabaseService clinicDatabaseService,
        ClinicFileService fileService,
        PreAiPrivacyService privacyService,
        ClinicQueueService clinicQueueService,
        InventoryPackageService inventoryPackageService,
        @Value("${clinic.generated-pre-ai-dir:${clinic.attachment-dir}/../generated-pre-ai}") String generatedDir
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clinicDatabaseService = clinicDatabaseService;
        this.fileService = fileService;
        this.privacyService = privacyService;
        this.clinicQueueService = clinicQueueService;
        this.inventoryPackageService = inventoryPackageService;
        this.generatedDir = Path.of(generatedDir).toAbsolutePath().normalize();
    }

    @Transactional
    public Map<String, Object> create(CreateEncounterRequest request, SessionUser user) {
        requireRole(user, "admin", "frontdesk");
        ObjectNode patient = sanitizeStageData("REGISTRATION", request == null ? null : request.patient());
        validateStage("REGISTRATION", patient, null);
        String patientCaseId = createPatientCase(patient, "");
        return toMap(createEncounterInternal(patient, "", patientCaseId, 1, "", objectMapper.createObjectNode(), objectMapper.createObjectNode(), user));
    }

    @Transactional
    public Map<String, Object> importLegacy(String patientId, SessionUser user) {
        requireRole(user, "admin", "frontdesk", "doctor");
        String sourcePatientId = safe(patientId);
        if (sourcePatientId.isBlank()) throw badRequest("缺少旧患者 ID");
        List<String> existing = jdbcTemplate.query(
            "SELECT id FROM pre_ai_encounters WHERE source_patient_id = ? ORDER BY created_at DESC LIMIT 1",
            (rs, rowNum) -> rs.getString("id"),
            sourcePatientId
        );
        if (!existing.isEmpty()) return toMap(workspace(existing.get(0), user));

        ObjectNode legacyDb = clinicDatabaseService.readDbForUser(user);
        JsonNode patientRow = findById(legacyDb.path("patients"), sourcePatientId);
        if (patientRow == null) throw notFound("旧患者不存在或当前账号无权查看");
        JsonNode record = legacyDb.path("records").path(sourcePatientId);
        ObjectNode registration = objectMapper.createObjectNode();
        copyFirst(registration, "patientName", record, "patientName", patientRow, "name");
        copyFirst(registration, "gender", record, "gender", null, null);
        copyFirst(registration, "age", record, "age", record, "patientAge");
        copyFirst(registration, "phone", patientRow, "phone", record, "phone");
        copyFirst(registration, "identityNumber", record, "identityNumber", record, "idCard");
        copyFirst(registration, "address", record, "address", patientRow, "address");
        copyFirst(registration, "contactName", record, "contactName", null, null);
        copyFirst(registration, "contactRelation", record, "contactRelation", null, null);
        copyFirst(registration, "contactPhone", record, "contactPhone", null, null);
        copyFirst(registration, "visitDate", patientRow, "visitDate", record, "admissionDate");
        copyFirst(registration, "patientSource", record, "sourceChannel", patientRow, "visitType");
        copyFirst(registration, "visitNo", patientRow, "visitNo", record, "visitNo");
        if (text(registration, "gender").isBlank()) registration.put("gender", "待核实");
        if (text(registration, "age").isBlank()) registration.put("age", "待核实");
        if (text(registration, "visitDate").isBlank()) registration.put("visitDate", LocalDate.now().toString());

        ObjectNode legacyReference = objectMapper.createObjectNode();
        legacyReference.put("sourcePatientId", sourcePatientId);
        legacyReference.put("sourceVisitNo", text(patientRow, "visitNo"));
        legacyReference.put("importedAt", now());
        String patientCaseId = createPatientCase(registration, sourcePatientId);
        ObjectNode created = createEncounterInternal(registration, sourcePatientId, patientCaseId, 1, "", legacyReference, objectMapper.createObjectNode(), user);
        String encounterId = text(created.path("encounter"), "id");

        importStageDraft(encounterId, "INSPECTION", mapped(record, Map.of(
            "specialExamFullText", "factualConclusion",
            "inspectionBriefNote", "otherFindings",
            "analVisual", "visualFindings",
            "digitalRectalExam", "digitalExamFindings",
            "anoscopy", "anoscopyFindings"
        )), user);
        importStageDraft(encounterId, "RECEPTION", mapped(record, Map.ofEntries(
            Map.entry("chiefComplaintText", "chiefComplaint"),
            Map.entry("presentIllnessText", "presentIllness"),
            Map.entry("pastHistory", "pastHistory"),
            Map.entry("operationHistory", "surgicalHistory"),
            Map.entry("traumaTransfusion", "transfusionHistory"),
            Map.entry("allergyHistory", "allergyHistory"),
            Map.entry("personalHistory", "personalHistory"),
            Map.entry("familyHistory", "familyHistory"),
            Map.entry("admissionReason", "nextStepRecommendation")
        )), user);
        importStageDraft(encounterId, "TCM", mapped(record, Map.of(
            "tcmDisease", "tcmDisease",
            "tcmSyndrome", "primarySyndrome",
            "tcmLook", "inspection",
            "tcmFourDiagnosisText", "inquiry",
            "tongue", "tongue",
            "pulseCondition", "pulse",
            "tcmSyndromeBasis", "syndromeBasis",
            "tcmTreatmentMethod", "treatmentPrinciple"
        )), user);
        importStageDraft(encounterId, "DOCTOR", mapped(record, Map.of(
            "westernDiagnosis", "primaryWesternDiagnosis",
            "westernDiagnosisSecondary", "secondaryWesternDiagnoses",
            "westernDiagnosisBasis", "diagnosisBasis",
            "treatmentPlan", "treatmentPlan",
            "operationName", "plannedOperationName"
        )), user);
        importLegacyAttachments(encounterId, legacyDb.path("documents").path(sourcePatientId), user);
        audit(encounterId, "legacy.import", "REGISTRATION", user, "从旧档案幂等导入可明确映射的字段和附件引用");
        return toMap(workspace(encounterId, user));
    }

    public Map<String, Object> list(SessionUser user) {
        requireReadRole(user);
        ArrayNode rows = objectMapper.createArrayNode();
        jdbcTemplate.query("SELECT * FROM pre_ai_encounters ORDER BY updated_at DESC, created_at DESC", rs -> {
            ObjectNode row = readEncounter(rs);
            JsonNode patient = row.path("patient");
            row.put("patientName", text(patient, "patientName"));
            row.put("gender", text(patient, "gender"));
            row.put("age", text(patient, "age"));
            row.put("visitDate", text(patient, "visitDate"));
            row.remove("patient");
            row.set("stageStatuses", stageStatusMap(text(row, "id")));
            rows.add(row);
        });
        return Map.of("list", objectMapper.convertValue(rows, new TypeReference<List<Map<String, Object>>>() {}));
    }

    public Map<String, Object> listPatientCases(SessionUser user) {
        requireReadRole(user);
        ArrayNode rows = objectMapper.createArrayNode();
        jdbcTemplate.query("SELECT * FROM pre_ai_patient_cases ORDER BY updated_at DESC, created_at DESC", rs -> {
            ObjectNode patientCase = readPatientCase(rs);
            String patientCaseId = text(patientCase, "id");
            List<ObjectNode> encounters = jdbcTemplate.query(
                "SELECT * FROM pre_ai_encounters WHERE patient_case_id = ? ORDER BY visit_no DESC, created_at DESC",
                (resultSet, rowNum) -> readEncounter(resultSet), patientCaseId
            );
            patientCase.put("visitCount", encounters.size());
            if (!encounters.isEmpty()) patientCase.set("latestEncounter", encounterSummary(encounters.get(0)));
            rows.add(patientCase);
        });
        return Map.of("list", objectMapper.convertValue(rows, new TypeReference<List<Map<String, Object>>>() {}));
    }

    @Transactional
    public Map<String, Object> createFollowUp(String patientCaseId, FollowUpEncounterCreateRequest request, SessionUser user) {
        requireRole(user, "admin", "frontdesk");
        ObjectNode patientCase = loadPatientCase(patientCaseId);
        ObjectNode patient = safeObject(patientCase.path("patient"));
        String visitDate = safe(request == null ? "" : request.visitDate());
        if (visitDate.isBlank()) visitDate = now();
        patient.put("visitDate", visitDate);
        validateStage("REGISTRATION", patient, null);
        List<ObjectNode> previous = jdbcTemplate.query(
            "SELECT * FROM pre_ai_encounters WHERE patient_case_id = ? ORDER BY visit_no DESC, created_at DESC LIMIT 1",
            (rs, rowNum) -> readEncounter(rs), patientCaseId
        );
        int visitNo = previous.isEmpty() ? 1 : previous.get(0).path("visitNo").asInt(0) + 1;
        String previousEncounterId = previous.isEmpty() ? "" : text(previous.get(0), "id");
        ObjectNode visitMeta = sanitizeVisitMeta(request == null ? null : request.visitMeta());
        ObjectNode workspace = createEncounterInternal(
            patient,
            text(patientCase, "sourcePatientId"),
            patientCaseId,
            visitNo,
            previousEncounterId,
            objectMapper.createObjectNode(),
            visitMeta,
            user
        );
        jdbcTemplate.update("UPDATE pre_ai_patient_cases SET patient_json = CAST(? AS JSON), updated_at = ? WHERE id = ?", toJson(patient), now(), patientCaseId);
        audit(text(workspace.path("encounter"), "id"), "encounter.followup.create", "REGISTRATION", user, "创建第 " + visitNo + " 次来访子病历");
        return toMap(workspace);
    }

    @Transactional
    public Map<String, Object> updateVisitMeta(String encounterId, VisitMetaRequest request, SessionUser user) {
        requireRole(user, "admin", "frontdesk");
        loadEncounter(encounterId);
        ObjectNode visitMeta = sanitizeVisitMeta(request == null ? null : request.visitMeta());
        jdbcTemplate.update("UPDATE pre_ai_encounters SET visit_meta_json = CAST(? AS JSON), updated_at = ? WHERE id = ?", toJson(visitMeta), now(), encounterId);
        audit(encounterId, "encounter.visit-meta.update", "REGISTRATION", user, "更新来访及交费参考信息");
        return toMap(workspace(encounterId, user));
    }

    public Map<String, Object> inspectionTimeline(String patientCaseId, SessionUser user) {
        requireRole(user, "admin", "inspection", "doctor");
        loadPatientCase(patientCaseId);
        ArrayNode nodes = objectMapper.createArrayNode();
        List<ObjectNode> encounters = jdbcTemplate.query(
            "SELECT * FROM pre_ai_encounters WHERE patient_case_id = ? ORDER BY visit_no, created_at, id",
            (rs, rowNum) -> readEncounter(rs), patientCaseId
        );
        for (ObjectNode encounter : encounters) {
            String encounterId = text(encounter, "id");
            ObjectNode node = nodes.addObject();
            node.put("encounterId", encounterId);
            node.put("caseToken", text(encounter, "caseToken"));
            node.put("visitNo", encounter.path("visitNo").asInt(1));
            node.put("visitDate", text(encounter.path("patient"), "visitDate"));
            node.put("route", text(encounter, "route"));
            node.put("status", text(encounter, "status"));
            node.set("visitMeta", safeObject(encounter.path("visitMeta")));
            ObjectNode inspection = loadStage(encounterId, "INSPECTION");
            node.put("inspectionStatus", text(inspection, "status"));
            node.set("inspection", safeObject(inspection.path("data")));
            ArrayNode attachments = node.putArray("attachments");
            jdbcTemplate.query("""
                SELECT * FROM pre_ai_attachments
                WHERE encounter_id = ? AND stage_code = 'INSPECTION' AND status = 'ACTIVE'
                ORDER BY created_at, sequence_no, id
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> attachments.add(readAttachment(rs)), encounterId);
        }
        return Map.of("patientCaseId", patientCaseId, "nodes", objectMapper.convertValue(nodes, new TypeReference<List<Map<String, Object>>>() {}));
    }

    public Map<String, Object> getWorkspace(String encounterId, SessionUser user) {
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> saveDutyAssignments(String encounterId, DutyAssignmentsRequest request, SessionUser user) {
        ObjectNode encounter = loadEncounter(encounterId);
        if (user == null || !(Set.of("admin", "frontdesk", "doctor").contains(user.role())
            || hasAssignedDuty(encounter, user, Set.of("FRONT_DESK", "ATTENDING_DOCTOR", "FINAL_REVIEW_DOCTOR")))) {
            throw forbidden("当前账号无权维护本病例岗位安排");
        }
        ArrayNode assignments = objectMapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        if (request != null && request.dutyAssignments() != null) {
            for (Map<String, Object> item : request.dutyAssignments()) {
                ObjectNode source = objectMapper.valueToTree(item == null ? Map.of() : item);
                String dutyCode = safe(text(source, "dutyCode")).toUpperCase(Locale.ROOT);
                if (!DUTY_CODES.contains(dutyCode)) throw badRequest("不支持的病例岗位：" + dutyCode);
                if (!seen.add(dutyCode)) throw badRequest("同一病例岗位只能配置一次：" + dutyCode);
                ObjectNode clean = assignments.addObject();
                clean.put("dutyCode", dutyCode);
                putTextIfPresent(clean, "responsibleUserId", text(source, "responsibleUserId"));
                putTextIfPresent(clean, "responsibleUserName", text(source, "responsibleUserName"));
                copyTextArray(source, clean, "participantUserIds");
                copyTextArray(source, clean, "participantUserNames");
            }
        }
        jdbcTemplate.update("UPDATE pre_ai_encounters SET duty_assignments_json = CAST(? AS JSON), updated_at = ? WHERE id = ?",
            toJson(assignments), now(), encounterId);
        invalidateReview(encounterId, user, "病例岗位安排发生修改");
        audit(encounterId, "duty.assignments.save", "REGISTRATION", user, "更新病例级一人多岗安排");
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> saveStage(String encounterId, String stageCode, StageSaveRequest request, SessionUser user) {
        String stage = normalizeStage(stageCode);
        ObjectNode encounter = loadEncounter(encounterId);
        requireStageEditor(encounter, stage, user);
        ObjectNode current = loadStage(encounterId, stage);
        if ("COMPLETED".equals(text(current, "status")) && !"admin".equals(user.role())) {
            throw conflict("该阶段已完成，需由医生退回后才能修改");
        }
        ObjectNode data = sanitizeStageData(stage, request == null ? null : request.data());
        if ("SURGERY".equals(stage)) normalizeSurgeryConfirmation(encounter, data, user);
        if ("REGISTRATION".equals(stage)) {
            jdbcTemplate.update("UPDATE pre_ai_encounters SET patient_json = ?, updated_at = ? WHERE id = ?", toJson(data), now(), encounterId);
            String patientCaseId = text(encounter, "patientCaseId");
            if (!patientCaseId.isBlank()) {
                jdbcTemplate.update("UPDATE pre_ai_patient_cases SET patient_json = CAST(? AS JSON), updated_at = ? WHERE id = ?", toJson(data), now(), patientCaseId);
            }
        }
        if ("DOCTOR".equals(stage)) syncEncounterBranch(encounterId, data, encounter);
        updateStageVersioned(encounterId, stage, text(current, "status", "DRAFT"), data, "", user, "", request == null ? null : request.expectedVersion());
        syncDiagnoses(encounterId, stage, data);
        invalidateReview(encounterId, user, "阶段内容发生修改");
        audit(encounterId, "stage.save", stage, user, "保存阶段草稿");
        refreshProgress(encounterId);
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> completeStage(String encounterId, String stageCode, StageSaveRequest request, SessionUser user) {
        String stage = normalizeStage(stageCode);
        if ("REVIEW".equals(stage)) throw badRequest("复核阶段请使用确认复核接口");
        ObjectNode encounter = loadEncounter(encounterId);
        requireStageEditor(encounter, stage, user);
        assertPreviousStages(encounterId, stage, encounter);
        ObjectNode current = loadStage(encounterId, stage);
        ObjectNode data = request != null && request.data() != null
            ? sanitizeStageData(stage, request.data())
            : safeObject(current.path("data"));
        if ("SURGERY".equals(stage)) normalizeSurgeryConfirmation(encounter, data, user);
        validateStage(stage, data, encounter);
        if ("REGISTRATION".equals(stage)) {
            jdbcTemplate.update("UPDATE pre_ai_encounters SET patient_json = ?, updated_at = ? WHERE id = ?", toJson(data), now(), encounterId);
            String patientCaseId = text(encounter, "patientCaseId");
            if (!patientCaseId.isBlank()) {
                jdbcTemplate.update("UPDATE pre_ai_patient_cases SET patient_json = CAST(? AS JSON), updated_at = ? WHERE id = ?", toJson(data), now(), patientCaseId);
            }
        }
        if ("DOCTOR".equals(stage)) syncEncounterBranch(encounterId, data, encounter);
        updateStageVersioned(encounterId, stage, "COMPLETED", data, "", user, now(), request == null ? null : request.expectedVersion());
        syncDiagnoses(encounterId, stage, data);
        if (Set.of("INSPECTION", "RECEPTION").contains(stage)) clinicQueueService.onClinicalStageCompleted(encounterId, stage, user);
        if ("DOCTOR".equals(stage)) applySurgeryBranch(encounterId, data, user);
        invalidateReview(encounterId, user, "阶段重新完成");
        audit(encounterId, "stage.complete", stage, user, "完成阶段并交接下一岗位");
        refreshProgress(encounterId);
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> returnStage(String encounterId, String stageCode, ReturnStageRequest request, SessionUser user) {
        ObjectNode encounter = loadEncounter(encounterId);
        requireReviewer(encounter, user);
        String stage = normalizeStage(stageCode);
        if ("REVIEW".equals(stage)) throw badRequest("不能退回复核阶段");
        String reason = safe(request == null ? "" : request.reason());
        if (reason.isBlank()) throw badRequest("退回原因不能为空");
        ObjectNode current = loadStage(encounterId, stage);
        if (!Set.of("COMPLETED", "SKIPPED").contains(text(current, "status"))) throw conflict("只有已完成或已跳过的阶段可以退回");
        updateStageVersioned(encounterId, stage, "RETURNED", safeObject(current.path("data")), reason, user, "", request == null ? null : request.expectedVersion());
        invalidateReview(encounterId, user, "医生退回阶段：" + reason);
        audit(encounterId, "stage.return", stage, user, reason);
        refreshProgress(encounterId);
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> createAuxiliaryTask(String encounterId, AuxiliaryTaskRequest request, SessionUser user) {
        ObjectNode encounter = loadEncounter(encounterId);
        String taskType = normalizeTaskType(request == null ? "" : request.taskType());
        requireAuxCreator(encounter, taskType, user);
        String id = "aux-" + UUID.randomUUID();
        String timestamp = now();
        jdbcTemplate.update("""
            INSERT INTO pre_ai_auxiliary_tasks (
              id, encounter_id, task_type, title, owner_role, required_before_export, status, data_json, version,
              completed_at, updated_at, updated_by, updated_by_role, created_at, created_by
            ) VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', CAST(? AS JSON), 0, '', ?, ?, ?, ?, ?)
            """,
            id, encounterId, taskType, safe(request.title()), AUX_OWNER_ROLES.get(taskType), request.requiredBeforeExport(), "{}",
            timestamp, user.name(), user.role(), timestamp, user.name()
        );
        invalidateReview(encounterId, user, "新增辅助检查任务");
        audit(encounterId, "aux.create", null, user, taskType + "：" + safe(request.title()));
        refreshProgress(encounterId);
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> saveAuxiliaryTask(String encounterId, String taskId, AuxiliaryTaskSaveRequest request, boolean complete, SessionUser user) {
        ObjectNode encounter = loadEncounter(encounterId);
        ObjectNode task = loadAuxiliaryTask(encounterId, taskId);
        requireAuxEditor(encounter, task, user);
        boolean assignedDoctor = hasAssignedDuty(encounter, user, Set.of("ATTENDING_DOCTOR", "FINAL_REVIEW_DOCTOR"));
        if ("COMPLETED".equals(text(task, "status")) && !Set.of("admin", "doctor").contains(user.role()) && !assignedDoctor) {
            throw conflict("辅助检查已完成，需医生退回后才能修改");
        }
        String taskType = text(task, "taskType");
        ObjectNode data = sanitizeObject(request == null ? null : request.data(), AUX_FIELDS.get(taskType));
        if (complete) validateAuxiliaryTask(taskType, data);
        String status = complete ? "COMPLETED" : "DRAFT";
        int changed = jdbcTemplate.update("""
            UPDATE pre_ai_auxiliary_tasks
            SET title = ?, required_before_export = ?, status = ?, data_json = CAST(? AS JSON), version = version + 1,
                completed_at = ?, updated_at = ?, updated_by = ?, updated_by_role = ?,
                completed_by = ?, completed_by_role = ?
            WHERE id = ? AND encounter_id = ? AND version = ?
            """,
            request == null ? text(task, "title") : safe(request.title()),
            request == null ? task.path("requiredBeforeExport").asBoolean(false) : request.requiredBeforeExport(),
            status, toJson(data), complete ? now() : "", now(), user.name(), user.role(),
            complete ? user.name() : "", complete ? user.role() : "", taskId, encounterId,
            requireExpectedVersion(request == null ? null : request.expectedVersion(), "辅助检查任务")
        );
        if (changed != 1) throwVersionConflict("辅助检查任务", taskId, request == null ? null : request.expectedVersion(), loadAuxiliaryTask(encounterId, taskId));
        invalidateReview(encounterId, user, "辅助检查任务发生修改");
        audit(encounterId, complete ? "aux.complete" : "aux.save", null, user, taskType + "：" + text(task, "title"));
        refreshProgress(encounterId);
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> returnAuxiliaryTask(String encounterId, String taskId, ReturnStageRequest request, SessionUser user) {
        ObjectNode encounter = loadEncounter(encounterId);
        requireReviewer(encounter, user);
        ObjectNode task = loadAuxiliaryTask(encounterId, taskId);
        String reason = safe(request == null ? "" : request.reason());
        if (reason.isBlank()) throw badRequest("退回原因不能为空");
        int changed = jdbcTemplate.update("""
            UPDATE pre_ai_auxiliary_tasks
            SET status = 'RETURNED', completed_at = '', completed_by = '', completed_by_role = '',
                version = version + 1, updated_at = ?, updated_by = ?, updated_by_role = ?
            WHERE id = ? AND encounter_id = ? AND version = ?
            """, now(), user.name(), user.role(), taskId, encounterId,
            requireExpectedVersion(request == null ? null : request.expectedVersion(), "辅助检查任务"));
        if (changed != 1) throwVersionConflict("辅助检查任务", taskId, request == null ? null : request.expectedVersion(), loadAuxiliaryTask(encounterId, taskId));
        invalidateReview(encounterId, user, "辅助检查退回：" + reason);
        audit(encounterId, "aux.return", null, user, text(task, "taskType") + "：" + reason);
        refreshProgress(encounterId);
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> uploadAttachment(String encounterId, AttachmentUploadRequest request, SessionUser user) throws IOException {
        log.warn("Deprecated Base64 attachment upload used for encounter {}; migrate the client to multipart", encounterId);
        ObjectNode encounter = loadEncounter(encounterId);
        String stage = request == null ? "" : safe(request.stageCode()).toUpperCase(Locale.ROOT);
        String taskId = request == null ? "" : safe(request.taskId());
        if (!taskId.isBlank()) {
            requireAuxEditor(encounter, loadAuxiliaryTask(encounterId, taskId), user);
        } else {
            requireStageEditor(encounter, normalizeStage(stage), user);
        }
        ClinicStoredFile stored = fileService.store(new ClinicFileUploadRequest(
            request.fileName(), request.contentDataUrl(), encounterId, user.department(), user.name(), user.role(), "pre-ai", "前置病历附件"
        ), user);
        String id = "preatt-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO pre_ai_attachments (
              id, encounter_id, stage_code, task_id, file_name, storage_path, mime_type, file_size, sha256,
              description, captured_at, uploader, uploader_role, batch_id, batch_name, relative_path, sequence_no, status, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
            """,
            id, encounterId, stage, taskId, stored.fileName(), stored.storagePath(), stored.mimeType(), stored.size(), stored.sha256(),
            safe(request.description()), safe(request.capturedAt()), user.name(), user.role(), safe(request.batchId()), safe(request.batchName()),
            safe(request.relativePath()), request.sequenceNo(), now()
        );
        audit(encounterId, "attachment.upload", stage, user, "上传本阶段附件");
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> uploadAttachment(
        String encounterId,
        AttachmentUploadRequest request,
        MultipartFile file,
        SessionUser user
    ) throws IOException {
        ObjectNode encounter = loadEncounter(encounterId);
        String stage = request == null ? "" : safe(request.stageCode()).toUpperCase(Locale.ROOT);
        String taskId = request == null ? "" : safe(request.taskId());
        if (!taskId.isBlank()) {
            requireAuxEditor(encounter, loadAuxiliaryTask(encounterId, taskId), user);
        } else {
            requireStageEditor(encounter, normalizeStage(stage), user);
        }
        ClinicStoredFile stored = fileService.store(file, encounterId);
        String id = "preatt-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO pre_ai_attachments (
              id, encounter_id, stage_code, task_id, file_name, storage_path, mime_type, file_size, sha256,
              description, captured_at, uploader, uploader_role, batch_id, batch_name, relative_path, sequence_no, status, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
            """,
            id, encounterId, stage, taskId, stored.fileName(), stored.storagePath(), stored.mimeType(), stored.size(), stored.sha256(),
            safe(request.description()), safe(request.capturedAt()), user.name(), user.role(), safe(request.batchId()), safe(request.batchName()),
            safe(request.relativePath()), request.sequenceNo(), now()
        );
        audit(encounterId, "attachment.upload", stage, user, "上传本阶段附件");
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> saveLabReport(String encounterId, LabReportRequest request, SessionUser user) {
        ObjectNode encounter = loadEncounter(encounterId);
        requireAuxTaskEditor(encounter, "LAB", user);
        ObjectNode task = ensureLabTask(encounterId, user.name());
        boolean assignedDoctor = hasAssignedDuty(encounter, user, Set.of("ATTENDING_DOCTOR", "FINAL_REVIEW_DOCTOR"));
        if ("COMPLETED".equals(text(task, "status")) && !Set.of("admin", "doctor").contains(user.role()) && !assignedDoctor) {
            throw conflict("化验室已完成交接，需医生退回后才能继续填写");
        }
        String templateId = safe(request == null ? "" : request.templateId());
        String templateName = safe(request == null ? "" : request.templateName());
        String reportDate = safe(request == null ? "" : request.reportDate());
        if (templateId.isBlank() || templateName.isBlank() || reportDate.isBlank()) throw badRequest("缺少检验报告模板或报告日期");
        ArrayNode metrics = objectMapper.createArrayNode();
        if (request != null && request.metrics() != null) {
            for (Map<String, Object> item : request.metrics()) {
                ObjectNode metric = objectMapper.valueToTree(item == null ? Map.of() : item);
                if (text(metric, "value").isBlank()) continue;
                ObjectNode clean = metrics.addObject();
                for (String key : List.of("key", "name", "shortName", "value", "unit", "reference", "severity")) {
                    String value = text(metric, key);
                    if (!value.isBlank()) clean.put(key, value);
                }
                if (metric.path("critical").asBoolean(false)) clean.put("critical", true);
            }
        }
        if (metrics.isEmpty()) throw badRequest("请至少填写一个检验指标");
        List<Integer> versions = jdbcTemplate.queryForList("""
            SELECT version FROM pre_ai_lab_reports
            WHERE encounter_id = ? AND template_id = ? AND report_date = ?
            ORDER BY version DESC LIMIT 1 FOR UPDATE
            """, Integer.class, encounterId, templateId, reportDate);
        int currentVersion = versions.isEmpty() ? 0 : versions.get(0);
        int expectedVersion = requireExpectedVersion(request == null ? null : request.expectedVersion(), "检验报告");
        if (currentVersion != expectedVersion) {
            throw new VersionConflictException("检验报告已被其他终端更新，请刷新后重新提交", Map.of(
                "entity", "检验报告", "encounterId", encounterId, "templateId", templateId,
                "reportDate", reportDate, "expectedVersion", expectedVersion, "currentVersion", currentVersion
            ));
        }
        int version = currentVersion + 1;
        jdbcTemplate.update("""
            UPDATE pre_ai_lab_reports SET status = 'SUPERSEDED'
            WHERE encounter_id = ? AND template_id = ? AND report_date = ? AND status = 'ACTIVE'
            """, encounterId, templateId, reportDate);
        String id = "prelab-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO pre_ai_lab_reports (
              id, encounter_id, template_id, template_name, report_date, remark, metrics_json, version,
              status, saved_by, saved_by_role, saved_at
            ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, 'ACTIVE', ?, ?, ?)
            """, id, encounterId, templateId, templateName, reportDate, safe(request.remark()), toJson(metrics),
            version, user.name(), user.role(), now());
        invalidateReview(encounterId, user, "化验报告发生修改");
        audit(encounterId, "lab.report.save", null, user, templateName + "（" + reportDate + "）");
        refreshProgress(encounterId);
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> completeLab(String encounterId, VersionRequest request, SessionUser user) {
        ObjectNode encounter = loadEncounter(encounterId);
        requireAuxTaskEditor(encounter, "LAB", user);
        ObjectNode task = ensureLabTask(encounterId, user.name());
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pre_ai_lab_reports WHERE encounter_id = ? AND status = 'ACTIVE'", Integer.class, encounterId
        );
        if (count == null || count == 0) throw badRequest("至少保存一份检验报告后才能完成交接");
        int changed = jdbcTemplate.update("""
            UPDATE pre_ai_auxiliary_tasks SET status = 'COMPLETED', required_before_export = TRUE,
                completed_at = ?, completed_by = ?, completed_by_role = ?, version = version + 1,
                updated_at = ?, updated_by = ?, updated_by_role = ? WHERE id = ? AND version = ?
            """, now(), user.name(), user.role(), now(), user.name(), user.role(), text(task, "id"),
            requireExpectedVersion(request == null ? null : request.expectedVersion(), "化验室任务"));
        if (changed != 1) throwVersionConflict("化验室任务", text(task, "id"), request == null ? null : request.expectedVersion(), loadAuxiliaryTask(encounterId, text(task, "id")));
        invalidateReview(encounterId, user, "化验室完成交接");
        audit(encounterId, "lab.complete", null, user, "化验报告已确认完成");
        refreshProgress(encounterId);
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> voidAttachment(String encounterId, String attachmentId, SessionUser user) {
        ObjectNode encounter = loadEncounter(encounterId);
        ObjectNode attachment = loadAttachment(encounterId, attachmentId);
        String taskId = text(attachment, "taskId");
        if (!taskId.isBlank()) requireAuxEditor(encounter, loadAuxiliaryTask(encounterId, taskId), user);
        else requireStageEditor(encounter, normalizeStage(text(attachment, "stageCode")), user);
        jdbcTemplate.update("UPDATE pre_ai_attachments SET status = 'VOIDED' WHERE id = ? AND encounter_id = ?", attachmentId, encounterId);
        audit(encounterId, "attachment.void", text(attachment, "stageCode"), user, "作废附件引用");
        return toMap(workspace(encounterId, user));
    }

    public AttachmentDownload downloadAttachment(String encounterId, String attachmentId, SessionUser user) {
        requireReadRole(user);
        ObjectNode attachment = loadAttachment(encounterId, attachmentId);
        if (!"ACTIVE".equals(text(attachment, "status"))) throw notFound("附件不存在");
        return new AttachmentDownload(fileService.load(text(attachment, "storagePath")), text(attachment, "fileName", "attachment"), text(attachment, "mimeType", "application/octet-stream"));
    }

    public Map<String, Object> reviewPreview(String encounterId, SessionUser user) {
        ObjectNode encounter = loadEncounter(encounterId);
        requireReviewer(encounter, user);
        ObjectNode workspace = workspace(encounterId, user);
        ArrayNode blockers = reviewBlockers(workspace);
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode maskedPreview = privacyService.maskWorkspace(workspace);
        result.set("workspace", workspace);
        result.set("maskedPreview", maskedPreview);
        result.set("blockers", blockers);
        result.set("labSummary", labReviewSummary(maskedPreview));
        result.put("ready", blockers.isEmpty());
        return toMap(result);
    }

    @Transactional
    public Map<String, Object> confirmReview(String encounterId, ReviewConfirmRequest request, SessionUser user) {
        ObjectNode encounter = loadEncounter(encounterId);
        requireReviewer(encounter, user);
        ObjectNode workspace = workspace(encounterId, user);
        ArrayNode blockers = reviewBlockers(workspace);
        if (!blockers.isEmpty()) throw badRequest("复核前仍有未完成内容：" + join(blockers));
        ObjectNode labSummary = labReviewSummary(privacyService.maskWorkspace(workspace));
        int criticalCount = labSummary.path("criticalCount").asInt(0);
        boolean criticalAcknowledged = request != null && request.criticalAcknowledged();
        if (criticalCount > 0 && !criticalAcknowledged) throw badRequest("存在危急值，医生必须显式确认已阅后才能完成复核");
        ObjectNode reviewData = objectMapper.createObjectNode();
        reviewData.put("reviewStatement", safe(request == null ? "" : request.statement()));
        reviewData.put("criticalCount", criticalCount);
        reviewData.put("criticalAcknowledged", criticalAcknowledged);
        if (criticalAcknowledged) reviewData.put("criticalAcknowledgedAt", now());
        ObjectNode current = loadStage(encounterId, "REVIEW");
        updateStageVersioned(encounterId, "REVIEW", "COMPLETED", reviewData, "", user, now(), request == null ? null : request.expectedVersion());
        jdbcTemplate.update("""
            UPDATE pre_ai_encounters SET status = 'REVIEWED', current_stage = 'REVIEW', reviewed_at = ?, reviewed_by = ?, reviewed_by_role = ?, updated_at = ? WHERE id = ?
            """, now(), user.name(), user.role(), now(), encounterId);
        String auditDetail = criticalCount > 0 ? "医生确认全部前置事实，并已阅 " + criticalCount + " 项危急值" : "医生确认全部前置事实";
        audit(encounterId, "review.confirm", "REVIEW", user, auditDetail);
        String caseToken = text(encounter, "caseToken");
        String route = text(encounter, "route");
        String visitDate = text(encounter.path("patient"), "visitDate");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    inventoryPackageService.consumeEncounter(encounterId, caseToken, route, user.department(), visitDate, user);
                } catch (Exception error) {
                    log.warn("Inventory consumption skipped encounterId={} department={}", encounterId, user.department(), error);
                }
            }
        });
        return toMap(workspace(encounterId, user));
    }

    private ObjectNode labReviewSummary(JsonNode maskedWorkspace) {
        ObjectNode summary = objectMapper.createObjectNode();
        ArrayNode abnormalMetrics = summary.putArray("abnormalMetrics");
        int criticalCount = 0;
        int abnormalCount = 0;
        for (JsonNode report : maskedWorkspace.path("labReports")) {
            for (JsonNode metric : report.path("metrics")) {
                String severity = text(metric, "severity");
                if ("NORMAL".equals(severity) || severity.isBlank()) continue;
                abnormalCount++;
                if ("CRITICAL".equals(severity)) criticalCount++;
                ObjectNode item = abnormalMetrics.addObject();
                item.put("reportName", text(report, "templateName"));
                item.put("reportDate", text(report, "reportDate"));
                for (String key : List.of("name", "shortName", "value", "unit", "reference", "abnormal", "severity")) {
                    String value = text(metric, key);
                    if (!value.isBlank()) item.put(key, value);
                }
            }
        }
        summary.put("abnormalCount", abnormalCount);
        summary.put("criticalCount", criticalCount);
        return summary;
    }

    @Transactional
    public Map<String, Object> generateExport(String encounterId, SessionUser user) {
        String requestId = UUID.randomUUID().toString();
        String phase = "authorize";
        Path temporary = null;
        Path target = null;
        try {
            phase = "load";
            ObjectNode encounter = loadEncounter(encounterId);
            requireReviewer(encounter, user);
            if (!Set.of("REVIEWED", "EXPORTED").contains(text(encounter, "status"))) throw conflict("请先完成医生复核");
            ObjectNode workspace = workspace(encounterId, user);
            ArrayNode blockers = reviewBlockers(workspace);
            if (!blockers.isEmpty()) throw badRequest("导出前仍有未完成内容：" + join(blockers));

            phase = "mask";
            ObjectNode masked = privacyService.maskWorkspace(workspace);
            phase = "render";
            byte[] bytes = privacyService.renderDocx(masked, workspace);

            phase = "version";
            Integer nextVersion = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM pre_ai_exports WHERE encounter_id = ?",
                Integer.class,
                encounterId
            );
            int version = nextVersion == null || nextVersion < 1 ? 1 : nextVersion;
            String caseToken = text(encounter, "caseToken");
            String fileName = caseToken + "_前置资料_v" + version + ".docx";
            String exportId = "preexp-" + UUID.randomUUID();
            Path exportDirectory = generatedDir.resolve(encounterId).normalize();
            target = exportDirectory.resolve(exportId + ".docx").normalize();
            temporary = exportDirectory.resolve("." + exportId + ".tmp").normalize();
            if (!target.startsWith(generatedDir) || !temporary.startsWith(generatedDir)) {
                throw new IllegalStateException("导出路径超出允许目录");
            }

            phase = "temporary-file";
            Files.createDirectories(exportDirectory);
            Files.write(temporary, bytes);

            phase = "database-version";
            jdbcTemplate.update("""
                INSERT INTO pre_ai_exports (
                  id, encounter_id, version, status, case_token, file_name, file_path, source_snapshot, masked_snapshot,
                  generated_by, generated_by_role, generated_at
                ) VALUES (?, ?, ?, 'GENERATED', ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?, ?)
                """, exportId, encounterId, version, caseToken, fileName, target.toString(), toJson(workspace), toJson(masked), user.name(), user.role(), now());

            phase = "file-move";
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;

            phase = "finalize";
            jdbcTemplate.update("UPDATE pre_ai_encounters SET status = 'EXPORTED', current_stage = 'REVIEW', updated_at = ? WHERE id = ?", now(), encounterId);
            audit(encounterId, "export.generate", "REVIEW", user, "生成脱敏 DOCX v" + version + "，请求 " + requestId);
            log.info("Pre-AI export completed requestId={} encounterId={} caseToken={} version={}", requestId, encounterId, caseToken, version);
            return Map.of("export", toMap(loadExport(exportId)), "workspace", toMap(workspace(encounterId, user)), "requestId", requestId);
        } catch (Exception error) {
            deleteQuietly(temporary);
            deleteQuietly(target);
            log.error("Pre-AI export failed requestId={} encounterId={} phase={}", requestId, encounterId, phase, error);
            if (error instanceof ResponseStatusException response && response.getStatusCode().is4xxClientError()) throw response;
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "脱敏 DOCX 生成失败，请求编号：" + requestId, error);
        }
    }

    public Map<String, Object> exports(String encounterId, SessionUser user) {
        requireReadRole(user);
        loadEncounter(encounterId);
        List<Map<String, Object>> rows = jdbcTemplate.query("SELECT * FROM pre_ai_exports WHERE encounter_id = ? ORDER BY version DESC", (rs, rowNum) -> toMap(readExport(rs)), encounterId);
        return Map.of("versions", rows);
    }

    public ExportDownload downloadExport(String encounterId, String exportId, SessionUser user) {
        ObjectNode encounter = loadEncounter(encounterId);
        requireReviewer(encounter, user);
        ObjectNode row = loadExport(exportId);
        if (!encounterId.equals(text(row, "encounterId"))) throw notFound("导出版本不存在");
        Path target = Path.of(text(row, "filePath")).toAbsolutePath().normalize();
        if (!target.startsWith(generatedDir) || !Files.isRegularFile(target)) throw notFound("导出文件不存在，请重新生成");
        return new ExportDownload(new FileSystemResource(target), text(row, "fileName", "前置资料.docx"));
    }

    private ObjectNode createEncounterInternal(
        ObjectNode patient,
        String sourcePatientId,
        String patientCaseId,
        int visitNo,
        String followUpOfEncounterId,
        ObjectNode legacyReference,
        ObjectNode visitMeta,
        SessionUser user
    ) {
        String id = "preai-" + UUID.randomUUID();
        String caseToken = nextCaseToken();
        String timestamp = now();
        jdbcTemplate.update("""
            INSERT INTO pre_ai_encounters (
              id, source_patient_id, patient_case_id, visit_no, follow_up_of_encounter_id, case_token, route, treatment_path,
              status, current_stage, patient_json, visit_meta_json, legacy_reference_json, duty_assignments_json, reviewed_at, reviewed_by,
              reviewed_by_role, created_at, updated_at, created_by, created_by_role
            ) VALUES (?, ?, ?, ?, ?, ?, '', '', 'IN_PROGRESS', 'REGISTRATION', CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), JSON_ARRAY(), '', '', '', ?, ?, ?, ?)
            """, id, sourcePatientId, patientCaseId, visitNo, followUpOfEncounterId, caseToken, toJson(patient), toJson(visitMeta),
            toJson(legacyReference), timestamp, timestamp, user.name(), user.role());
        for (String stage : STAGE_ORDER) {
            ObjectNode data = "REGISTRATION".equals(stage) ? patient : objectMapper.createObjectNode();
            upsertStage(id, stage, "DRAFT", 0, data, "", user, "");
        }
        ensureLabTask(id, user.name());
        audit(id, "encounter.create", "REGISTRATION", user, sourcePatientId.isBlank() ? "创建前置病历就诊" : "从旧患者创建前置病历就诊");
        return workspace(id, user);
    }

    private String createPatientCase(ObjectNode patient, String sourcePatientId) {
        if (!safe(sourcePatientId).isBlank()) {
            List<String> existing = jdbcTemplate.query(
                "SELECT id FROM pre_ai_patient_cases WHERE source_patient_id = ? ORDER BY created_at LIMIT 1",
                (rs, rowNum) -> rs.getString("id"), sourcePatientId
            );
            if (!existing.isEmpty()) return existing.get(0);
        }
        String id = "pcase-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO pre_ai_patient_cases (id, source_patient_id, patient_json, created_at, updated_at)
            VALUES (?, ?, CAST(? AS JSON), ?, ?)
            """, id, safe(sourcePatientId), toJson(patient), now(), now());
        return id;
    }

    private ObjectNode workspace(String encounterId, SessionUser user) {
        requireReadRole(user);
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode encounter = loadEncounter(encounterId);
        result.set("encounter", encounter);
        result.set("dutyAssignments", encounter.path("dutyAssignments").deepCopy());
        ArrayNode stages = result.putArray("stages");
        jdbcTemplate.query("SELECT * FROM pre_ai_stage_submissions WHERE encounter_id = ? ORDER BY FIELD(stage_code, 'REGISTRATION','INSPECTION','RECEPTION','TCM','DOCTOR','SURGERY','REVIEW')", (org.springframework.jdbc.core.RowCallbackHandler) rs -> stages.add(readStage(rs)), encounterId);
        ArrayNode auxiliaryTasks = result.putArray("auxiliaryTasks");
        jdbcTemplate.query("SELECT * FROM pre_ai_auxiliary_tasks WHERE encounter_id = ? ORDER BY created_at, id", (org.springframework.jdbc.core.RowCallbackHandler) rs -> auxiliaryTasks.add(readAuxiliaryTask(rs)), encounterId);
        ArrayNode labReports = result.putArray("labReports");
        jdbcTemplate.query("SELECT * FROM pre_ai_lab_reports WHERE encounter_id = ? AND status = 'ACTIVE' ORDER BY report_date, saved_at, id", (org.springframework.jdbc.core.RowCallbackHandler) rs -> labReports.add(readLabReport(rs)), encounterId);
        ArrayNode attachments = result.putArray("attachments");
        jdbcTemplate.query("SELECT * FROM pre_ai_attachments WHERE encounter_id = ? AND status = 'ACTIVE' ORDER BY created_at", (org.springframework.jdbc.core.RowCallbackHandler) rs -> attachments.add(readAttachment(rs)), encounterId);
        ArrayNode diagnoses = result.putArray("diagnoses");
        jdbcTemplate.query("SELECT * FROM pre_ai_diagnoses WHERE encounter_id = ? ORDER BY source_stage, sort_no, id", (org.springframework.jdbc.core.RowCallbackHandler) rs -> diagnoses.add(readDiagnosis(rs)), encounterId);
        ArrayNode audits = result.putArray("auditLogs");
        jdbcTemplate.query("SELECT * FROM pre_ai_audit_logs WHERE encounter_id = ? ORDER BY created_at DESC, id DESC LIMIT 100", (org.springframework.jdbc.core.RowCallbackHandler) rs -> audits.add(readAudit(rs)), encounterId);
        ArrayNode exports = result.putArray("exports");
        jdbcTemplate.query("SELECT * FROM pre_ai_exports WHERE encounter_id = ? ORDER BY version DESC", (org.springframework.jdbc.core.RowCallbackHandler) rs -> exports.add(readExport(rs)), encounterId);
        result.put("currentUserRole", user.role());
        return result;
    }

    private ObjectNode ensureLabTask(String encounterId, String creator) {
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT * FROM pre_ai_auxiliary_tasks WHERE encounter_id = ? AND task_type = 'LAB' ORDER BY created_at LIMIT 1",
            (rs, rowNum) -> readAuxiliaryTask(rs), encounterId
        );
        if (!rows.isEmpty()) return rows.get(0);
        String id = "aux-lab-" + UUID.randomUUID();
        String timestamp = now();
        jdbcTemplate.update("""
            INSERT INTO pre_ai_auxiliary_tasks (
              id, encounter_id, task_type, title, owner_role, required_before_export, status, data_json, version,
              completed_at, updated_at, updated_by, created_at, created_by
            ) VALUES (?, ?, 'LAB', '化验室检验报告', 'lab', TRUE, 'DRAFT', JSON_OBJECT(), 0, '', ?, ?, ?, ?)
            """, id, encounterId, timestamp, safe(creator), timestamp, safe(creator));
        return loadAuxiliaryTask(encounterId, id);
    }

    private void normalizeSurgeryConfirmation(ObjectNode encounter, ObjectNode data, SessionUser user) {
        if (!data.path("physicianConfirmed").asBoolean(false)) {
            data.remove(List.of("physicianConfirmedBy", "physicianConfirmedAt"));
            return;
        }
        boolean surgeon = user != null && (Set.of("admin", "doctor").contains(user.role())
            || hasAssignedDuty(encounter, user, Set.of("SURGEON")));
        if (!surgeon) throw forbidden("手术事实只能由手术医生确认");
        data.put("physicianConfirmed", true);
        data.put("physicianConfirmedBy", user.name());
        data.put("physicianConfirmedAt", now());
    }

    private void validateStage(String stage, ObjectNode data, ObjectNode encounter) {
        List<String> missing = new ArrayList<>();
        if ("TCM".equals(stage)) validateComorbidTcmItems(data.path("comorbidTcmItems"), missing);
        if ("RECEPTION".equals(stage)) {
            validateRepeatableRequired(data.path("chronicDiseaseItems"), "disease", "慢性病史", "疾病", missing);
            validateRepeatableRequired(data.path("surgicalHistoryItems"), "operationName", "手术史", "手术名称", missing);
        }
        if ("DOCTOR".equals(stage)) validateSecondaryDiagnosisItems(data.path("secondaryDiagnosisItems"), missing);
        validateTemplateConfirmations(stage, data, missing);
        switch (stage) {
            case "REGISTRATION" -> {
                required(data, missing, "patientName", "姓名");
                required(data, missing, "gender", "性别");
                if (text(data, "age").isBlank() && text(data, "birthDate").isBlank()) missing.add("年龄或出生日期");
                required(data, missing, "visitDate", "就诊时间");
            }
            case "INSPECTION" -> {
                required(data, missing, "examinationDirection", "检查方向");
                required(data, missing, "diseaseDirections", "病种方向");
                if (data.path("examinationTypes").isMissingNode() || data.path("examinationTypes").isEmpty()) missing.add("已完成检查类型");
                required(data, missing, "factualConclusion", "检查事实结论");
            }
            case "RECEPTION" -> {
                required(data, missing, "chiefComplaint", "主诉");
                required(data, missing, "presentIllness", "现病经过");
                required(data, missing, "dispositionSuggestion", "建议门诊或住院");
            }
            case "TCM" -> {
                required(data, missing, "tcmDisease", "中医病名");
                required(data, missing, "primarySyndrome", "主证");
                required(data, missing, "inspection", "望诊");
                required(data, missing, "inquiry", "问诊");
                required(data, missing, "tongue", "舌象");
                required(data, missing, "pulse", "脉象");
                required(data, missing, "treatmentPrinciple", "治法治则");
            }
            case "DOCTOR" -> {
                required(data, missing, "finalRoute", "最终门诊/住院分支");
                required(data, missing, "primaryWesternDiagnosis", "西医主诊断");
                required(data, missing, "treatmentPath", "治疗方式");
                required(data, missing, "treatmentPlan", "治疗方案");
                if ("OUTPATIENT".equals(text(data, "finalRoute")) && "SURGICAL".equals(text(data, "treatmentPath"))) {
                    throw badRequest("门诊分支本期不进入手术室，请选择保守治疗或改为住院");
                }
                if ("SURGICAL".equals(text(data, "treatmentPath"))
                    && text(data, "plannedPrimaryOperation").isBlank()
                    && text(data, "plannedOperationName").isBlank()) {
                    missing.add("拟行主术式");
                }
            }
            case "SURGERY" -> {
                if (encounter != null && !"SURGICAL".equals(text(encounter, "treatmentPath"))) throw badRequest("当前患者不属于手术治疗分支");
                if (text(data, "actualPrimaryOperation").isBlank() && text(data, "actualOperationName").isBlank()) {
                    missing.add("实际主术式");
                }
                required(data, missing, "operationDate", "手术日期");
                required(data, missing, "intraoperativeFindings", "术中所见");
                required(data, missing, "procedurePerformed", "实际实施步骤");
                required(data, missing, "postoperativeHandoff", "术后交接说明");
                if (!data.path("physicianConfirmed").asBoolean(false)) missing.add("手术医生确认");
            }
            default -> {
            }
        }
        if (!missing.isEmpty()) throw badRequest("请先补齐：" + String.join("、", missing));
    }

    private void validateComorbidTcmItems(JsonNode items, List<String> missing) {
        if (!items.isArray()) return;
        int index = 1;
        for (JsonNode item : items) {
            if (text(item, "westernComorbidity").isBlank()) missing.add("合并病辨证第" + index + "项西医合并症");
            if (!item.has("includedInTcm") || !item.path("includedInTcm").isBoolean()) {
                missing.add("合并病辨证第" + index + "项是否纳入中医辨证");
            }
            if (item.path("includedInTcm").asBoolean(false)) {
                if (text(item, "tcmDisease").isBlank()) missing.add("合并病辨证第" + index + "项中医病名");
                if (text(item, "syndrome").isBlank()) missing.add("合并病辨证第" + index + "项证型");
            }
            index++;
        }
    }

    private void validateRepeatableRequired(JsonNode items, String key, String groupLabel, String fieldLabel, List<String> missing) {
        if (!items.isArray()) return;
        int index = 1;
        for (JsonNode item : items) {
            if (!item.isObject() || text(item, key).isBlank()) missing.add(groupLabel + "第" + index + "项" + fieldLabel);
            index++;
        }
    }

    private void validateSecondaryDiagnosisItems(JsonNode items, List<String> missing) {
        if (!items.isArray()) return;
        int index = 1;
        for (JsonNode item : items) {
            if (text(item, "name").isBlank()) missing.add("西医次诊断第" + index + "项诊断名称");
            String category = text(item, "category");
            if (!Set.of("LOCAL", "COMORBIDITY").contains(category)) missing.add("西医次诊断第" + index + "项分类");
            index++;
        }
    }

    private void validateTemplateConfirmations(String stage, JsonNode data, List<String> missing) {
        Map<String, List<String>> confirmations = switch (stage) {
            case "INSPECTION" -> Map.of("factualConclusionOverride", List.of("factualConclusionConfirmed", "检查事实结论"));
            case "RECEPTION" -> Map.of("presentIllnessOverride", List.of("presentIllnessConfirmed", "现病史"));
            case "TCM" -> Map.of("syndromeBasisOverride", List.of("syndromeBasisConfirmed", "辨证依据"));
            case "DOCTOR" -> Map.of(
                "diagnosisBasisOverride", List.of("diagnosisBasisConfirmed", "诊断依据"),
                "treatmentPlanOverride", List.of("treatmentPlanConfirmed", "治疗方案")
            );
            case "SURGERY" -> Map.of(
                "intraoperativeFindingsOverride", List.of("intraoperativeFindingsConfirmed", "术中所见"),
                "procedurePerformedOverride", List.of("procedurePerformedConfirmed", "实际实施步骤"),
                "postoperativeHandoffOverride", List.of("postoperativeHandoffConfirmed", "术后交接")
            );
            default -> Map.of();
        };
        confirmations.forEach((overrideKey, confirmation) -> {
            if (!text(data, overrideKey).isBlank() && !data.path(confirmation.get(0)).asBoolean(false)) {
                missing.add(confirmation.get(1) + "手工修订需重新确认");
            }
        });
    }

    private ArrayNode reviewBlockers(ObjectNode workspace) {
        ArrayNode blockers = objectMapper.createArrayNode();
        JsonNode encounter = workspace.path("encounter");
        boolean surgeryRequired = "INPATIENT".equals(text(encounter, "route")) && "SURGICAL".equals(text(encounter, "treatmentPath"));
        Map<String, String> statuses = new LinkedHashMap<>();
        for (JsonNode stage : workspace.path("stages")) statuses.put(text(stage, "stageCode"), text(stage, "status"));
        for (String stage : List.of("REGISTRATION", "INSPECTION", "RECEPTION", "TCM", "DOCTOR")) {
            if (!"COMPLETED".equals(statuses.get(stage))) blockers.add(stageLabel(stage) + "未完成");
        }
        if (surgeryRequired && !"COMPLETED".equals(statuses.get("SURGERY"))) blockers.add("手术室登记未完成");
        for (JsonNode task : workspace.path("auxiliaryTasks")) {
            if (task.path("requiredBeforeExport").asBoolean(false) && !"COMPLETED".equals(text(task, "status"))) {
                blockers.add("必需辅助检查未完成：" + auxiliaryLabel(text(task, "taskType")) + optionalTitle(text(task, "title")));
            }
        }
        return blockers;
    }

    private void assertPreviousStages(String encounterId, String stage, ObjectNode encounter) {
        int index = STAGE_ORDER.indexOf(stage);
        if (index <= 0) return;
        for (int i = 0; i < index; i++) {
            String previous = STAGE_ORDER.get(i);
            if ("SURGERY".equals(previous) && !("INPATIENT".equals(text(encounter, "route")) && "SURGICAL".equals(text(encounter, "treatmentPath")))) continue;
            if ("REVIEW".equals(previous)) continue;
            String status = text(loadStage(encounterId, previous), "status");
            if (!Set.of("COMPLETED", "SKIPPED").contains(status)) throw conflict(stageLabel(previous) + "尚未完成");
        }
    }

    private void syncEncounterBranch(String encounterId, ObjectNode data, ObjectNode encounter) {
        String route = normalizeEnum(text(data, "finalRoute"), Set.of("OUTPATIENT", "INPATIENT"), "最终就诊分支");
        String path = normalizeEnum(text(data, "treatmentPath"), Set.of("CONSERVATIVE", "SURGICAL"), "治疗方式");
        String suggested = text(loadStage(encounterId, "RECEPTION").path("data"), "dispositionSuggestion");
        String existing = text(encounter, "route");
        if (!suggested.isBlank() && !route.equals(suggested) && text(data, "routeOverrideReason").isBlank()) {
            throw badRequest("医生更改接诊室建议分支时必须填写更正原因");
        }
        jdbcTemplate.update("UPDATE pre_ai_encounters SET route = ?, treatment_path = ?, updated_at = ? WHERE id = ?", route, path, now(), encounterId);
        if (!existing.isBlank() && !existing.equals(route)) {
            // Detailed reason remains in the doctor-stage snapshot and audit trail.
        }
    }

    private void applySurgeryBranch(String encounterId, ObjectNode doctorData, SessionUser user) {
        ObjectNode surgery = loadStage(encounterId, "SURGERY");
        boolean surgical = "INPATIENT".equals(text(doctorData, "finalRoute")) && "SURGICAL".equals(text(doctorData, "treatmentPath"));
        if (surgical && "SKIPPED".equals(text(surgery, "status"))) {
            upsertStage(encounterId, "SURGERY", "DRAFT", surgery.path("version").asInt(0) + 1, objectMapper.createObjectNode(), "", user, "");
        } else if (!surgical) {
            upsertStage(encounterId, "SURGERY", "SKIPPED", surgery.path("version").asInt(0) + 1, objectMapper.createObjectNode(), "非住院手术分支", user, now());
        }
    }

    private void refreshProgress(String encounterId) {
        ObjectNode encounter = loadEncounter(encounterId);
        Map<String, String> statuses = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT stage_code, status FROM pre_ai_stage_submissions WHERE encounter_id = ?", (org.springframework.jdbc.core.RowCallbackHandler) rs -> statuses.put(rs.getString("stage_code"), rs.getString("status")), encounterId);
        String current = "REVIEW";
        for (String stage : STAGE_ORDER) {
            if ("SURGERY".equals(stage) && !("INPATIENT".equals(text(encounter, "route")) && "SURGICAL".equals(text(encounter, "treatmentPath")))) continue;
            if (!Set.of("COMPLETED", "SKIPPED").contains(statuses.getOrDefault(stage, "DRAFT"))) {
                current = stage;
                break;
            }
        }
        boolean readyForReview = reviewBlockers(workspaceForSystem(encounterId)).isEmpty();
        String existingStatus = text(encounter, "status");
        String status = Set.of("REVIEWED", "EXPORTED", "CANCELLED").contains(existingStatus)
            ? existingStatus
            : readyForReview ? "PENDING_REVIEW" : "IN_PROGRESS";
        jdbcTemplate.update("UPDATE pre_ai_encounters SET current_stage = ?, status = ?, updated_at = ? WHERE id = ?", current, status, now(), encounterId);
    }

    private ObjectNode workspaceForSystem(String encounterId) {
        ObjectNode result = objectMapper.createObjectNode();
        result.set("encounter", loadEncounter(encounterId));
        ArrayNode stages = result.putArray("stages");
        jdbcTemplate.query("SELECT * FROM pre_ai_stage_submissions WHERE encounter_id = ?", (org.springframework.jdbc.core.RowCallbackHandler) rs -> stages.add(readStage(rs)), encounterId);
        ArrayNode tasks = result.putArray("auxiliaryTasks");
        jdbcTemplate.query("SELECT * FROM pre_ai_auxiliary_tasks WHERE encounter_id = ?", (org.springframework.jdbc.core.RowCallbackHandler) rs -> tasks.add(readAuxiliaryTask(rs)), encounterId);
        result.putArray("attachments");
        result.putArray("diagnoses");
        result.putArray("auditLogs");
        result.putArray("exports");
        return result;
    }

    private void invalidateReview(String encounterId, SessionUser user, String reason) {
        ObjectNode encounter = loadEncounter(encounterId);
        if (!Set.of("REVIEWED", "EXPORTED").contains(text(encounter, "status"))) return;
        ObjectNode review = loadStage(encounterId, "REVIEW");
        upsertStage(encounterId, "REVIEW", "RETURNED", review.path("version").asInt(0) + 1, safeObject(review.path("data")), reason, user, "");
        jdbcTemplate.update("UPDATE pre_ai_encounters SET status = 'IN_PROGRESS', reviewed_at = '', reviewed_by = '', reviewed_by_role = '', updated_at = ? WHERE id = ?", now(), encounterId);
        audit(encounterId, "review.invalidate", "REVIEW", user, reason);
    }

    private void syncDiagnoses(String encounterId, String stage, ObjectNode data) {
        if (!Set.of("TCM", "DOCTOR").contains(stage)) return;
        jdbcTemplate.update("DELETE FROM pre_ai_diagnoses WHERE encounter_id = ? AND source_stage = ?", encounterId, stage);
        if ("TCM".equals(stage)) {
            insertDiagnosis(encounterId, "TCM_DISEASE", text(data, "tcmDisease"), 0, stage);
            insertDiagnosis(encounterId, "PRIMARY_SYNDROME", text(data, "primarySyndrome"), 1, stage);
            insertDiagnosis(encounterId, "CONCURRENT_SYNDROME", display(data.path("concurrentSyndrome")), 2, stage);
        } else {
            insertDiagnosis(encounterId, "WESTERN_PRIMARY", text(data, "primaryWesternDiagnosis"), 0, stage);
            JsonNode structured = data.path("secondaryDiagnosisItems");
            if (structured.isArray() && !structured.isEmpty()) {
                int index = 0;
                for (JsonNode item : structured) {
                    String type = "COMORBIDITY".equals(text(item, "category")) ? "WESTERN_COMORBIDITY" : "WESTERN_SECONDARY";
                    insertDiagnosis(encounterId, type, text(item, "name"), index++, stage);
                }
                return;
            }
            JsonNode secondary = data.path("secondaryWesternDiagnoses");
            if (secondary.isArray()) {
                int index = 0;
                for (JsonNode value : secondary) insertDiagnosis(encounterId, "WESTERN_SECONDARY", value.asText(""), index++, stage);
            } else insertDiagnosis(encounterId, "WESTERN_SECONDARY", secondary.asText(""), 0, stage);
        }
    }

    private void insertDiagnosis(String encounterId, String type, String diagnosis, int sortNo, String stage) {
        String value = safe(diagnosis);
        if (value.isBlank()) return;
        jdbcTemplate.update("INSERT INTO pre_ai_diagnoses (id, encounter_id, diagnosis_type, diagnosis_text, sort_no, source_stage, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            "diag-" + UUID.randomUUID(), encounterId, type, value, sortNo, stage, now());
    }

    private void importStageDraft(String encounterId, String stage, ObjectNode data, SessionUser user) {
        ObjectNode sanitized = sanitizeStageData(stage, data);
        if (sanitized.isEmpty()) return;
        ObjectNode current = loadStage(encounterId, stage);
        upsertStage(encounterId, stage, "DRAFT", current.path("version").asInt(0) + 1, sanitized, "旧资料导入后待岗位核实", user, "");
        syncDiagnoses(encounterId, stage, sanitized);
    }

    private void importLegacyAttachments(String encounterId, JsonNode documents, SessionUser user) {
        if (!documents.isArray()) return;
        Map<String, String> taskByType = new LinkedHashMap<>();
        for (JsonNode document : documents) {
            String storagePath = text(document, "storagePath");
            if (storagePath.isBlank() || "voided".equalsIgnoreCase(text(document, "status"))) continue;
            String department = text(document, "department");
            String fieldKey = text(document, "fieldKey");
            String taskType = department.contains("化验") || fieldKey.toLowerCase(Locale.ROOT).contains("blood") ? "LAB"
                : department.contains("心电") || fieldKey.toLowerCase(Locale.ROOT).contains("ecg") ? "ECG"
                : department.contains("B超") || department.contains("放射") || fieldKey.toLowerCase(Locale.ROOT).contains("imaging") ? "IMAGING" : "";
            String taskId = "";
            String stage = "INSPECTION";
            if (!taskType.isBlank()) {
                taskId = taskByType.computeIfAbsent(taskType, type ->
                    "LAB".equals(type) ? text(ensureLabTask(encounterId, user.name()), "id") : createImportedAuxiliaryTask(encounterId, type, user)
                );
                stage = "";
            }
            jdbcTemplate.update("""
                INSERT INTO pre_ai_attachments (
                  id, encounter_id, stage_code, task_id, file_name, storage_path, mime_type, file_size, sha256,
                  description, captured_at, uploader, uploader_role, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                """, "preatt-" + UUID.randomUUID(), encounterId, stage, taskId, text(document, "fileName"), storagePath,
                text(document, "mimeType"), document.path("size").asLong(0), text(document, "sha256"), "旧资料附件引用（待岗位核实）",
                text(document, "uploadedAt"), user.name(), user.role(), now());
        }
    }

    private String createImportedAuxiliaryTask(String encounterId, String taskType, SessionUser user) {
        String id = "aux-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO pre_ai_auxiliary_tasks (
              id, encounter_id, task_type, title, owner_role, required_before_export, status, data_json, version,
              completed_at, updated_at, updated_by, updated_by_role, created_at, created_by
            ) VALUES (?, ?, ?, ?, ?, FALSE, 'DRAFT', CAST(? AS JSON), 0, '', ?, ?, ?, ?, ?)
            """, id, encounterId, taskType, "旧资料导入-" + auxiliaryLabel(taskType), AUX_OWNER_ROLES.get(taskType), "{}",
            now(), user.name(), user.role(), now(), user.name());
        return id;
    }

    private ObjectNode sanitizeStageData(String stage, Map<String, Object> values) {
        return sanitizeObject(values, ALLOWED_FIELDS.get(stage));
    }

    private ObjectNode sanitizeStageData(String stage, JsonNode values) {
        return sanitizeObject(values, ALLOWED_FIELDS.get(stage));
    }

    private ObjectNode sanitizeObject(Map<String, Object> values, Set<String> allowed) {
        JsonNode node = values == null ? null : objectMapper.valueToTree(values);
        return sanitizeObject(node, allowed);
    }

    private ObjectNode sanitizeObject(JsonNode values, Set<String> allowed) {
        ObjectNode result = objectMapper.createObjectNode();
        if (values == null || !values.isObject() || allowed == null) return result;
        for (String key : allowed) {
            JsonNode value = values.path(key);
            if (value.isMissingNode() || value.isNull() || (value.isTextual() && value.asText().isBlank()) || ((value.isArray() || value.isObject()) && value.isEmpty())) continue;
            if (value.isTextual()) result.put(key, value.asText().trim());
            else result.set(key, value.deepCopy());
        }
        if ("INSPECTION".equals(findStageByFields(allowed))) {
            JsonNode types = result.path("examinationTypes");
            if (!containsAny(types, "VISUAL", "外观检查")) result.remove("visualFindings");
            if (!containsAny(types, "DIGITAL", "指检")) result.remove("digitalExamFindings");
            if (!containsAny(types, "ANOSCOPY", "肛门镜", "镜下检查")) result.remove("anoscopyFindings");
        }
        return result;
    }

    private String findStageByFields(Set<String> allowed) {
        return allowed == ALLOWED_FIELDS.get("INSPECTION") ? "INSPECTION" : "";
    }

    private boolean containsAny(JsonNode array, String... candidates) {
        if (!array.isArray()) return false;
        for (JsonNode item : array) {
            String value = item.asText("");
            for (String candidate : candidates) if (value.contains(candidate)) return true;
        }
        return false;
    }

    private void validateAuxiliaryTask(String taskType, ObjectNode data) {
        List<String> missing = new ArrayList<>();
        if ("VITAL_SIGNS".equals(taskType)) {
            required(data, missing, "measuredAt", "测量时间");
            requiredMeasurement(data, missing, "systolicBp", "收缩压");
            requiredMeasurement(data, missing, "diastolicBp", "舒张压");
            requiredMeasurement(data, missing, "temperature", "体温");
            requiredMeasurement(data, missing, "pulse", "脉搏");
            requiredMeasurement(data, missing, "respiration", "呼吸");
            if (!missing.isEmpty()) throw badRequest("请先补齐：" + String.join("、", missing));
            return;
        }
        if ("COLONOSCOPY".equals(taskType)) {
            required(data, missing, "status", "肠镜状态");
            if ("COMPLETED".equals(text(data, "status"))) {
                required(data, missing, "examinedAt", "检查时间");
                required(data, missing, "scope", "检查范围");
                required(data, missing, "findings", "肠镜所见");
                required(data, missing, "conclusion", "肠镜结论");
            }
            if (!missing.isEmpty()) throw badRequest("请先补齐：" + String.join("、", missing));
            return;
        }
        switch (taskType) {
            case "LAB" -> {
                required(data, missing, "project", "检验项目");
                required(data, missing, "result", "检验结果");
                required(data, missing, "conclusion", "检验结论");
            }
            case "ECG" -> {
                required(data, missing, "examinedAt", "检查时间");
                required(data, missing, "findings", "主要表现");
                required(data, missing, "conclusion", "结论");
            }
            case "IMAGING" -> {
                required(data, missing, "modality", "检查类型");
                required(data, missing, "bodyPart", "检查部位");
                required(data, missing, "findings", "主要表现");
                required(data, missing, "conclusion", "结论");
            }
            default -> throw badRequest("不支持的辅助检查类型");
        }
        if (!missing.isEmpty()) throw badRequest("请先补齐：" + String.join("、", missing));
    }

    private void putTextIfPresent(ObjectNode target, String key, String value) {
        String clean = safe(value);
        if (!clean.isBlank()) target.put(key, clean);
    }

    private void copyTextArray(JsonNode source, ObjectNode target, String key) {
        JsonNode values = source.path(key);
        if (!values.isArray()) return;
        ArrayNode clean = target.putArray(key);
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode value : values) {
            String text = safe(value.asText(""));
            if (!text.isBlank() && unique.add(text)) clean.add(text);
        }
        if (clean.isEmpty()) target.remove(key);
    }

    private ObjectNode mapped(JsonNode source, Map<String, String> mapping) {
        ObjectNode result = objectMapper.createObjectNode();
        mapping.forEach((from, to) -> {
            JsonNode value = source.path(from);
            if (value.isMissingNode() || value.isNull()) return;
            if (value.isTextual() && value.asText().isBlank()) return;
            result.set(to, value.deepCopy());
        });
        return result;
    }

    private void copyFirst(ObjectNode target, String targetKey, JsonNode first, String firstKey, JsonNode second, String secondKey) {
        JsonNode value = first == null || firstKey == null ? null : first.path(firstKey);
        if (value == null || value.isMissingNode() || value.isNull() || value.asText("").isBlank()) value = second == null || secondKey == null ? null : second.path(secondKey);
        if (value != null && !value.isMissingNode() && !value.isNull() && !value.asText("").isBlank()) target.set(targetKey, value.deepCopy());
    }

    private void upsertStage(String encounterId, String stage, String status, int version, ObjectNode data, String reason, SessionUser user, String completedAt) {
        jdbcTemplate.update("""
            INSERT INTO pre_ai_stage_submissions (
              encounter_id, stage_code, status, version, data_json, returned_reason, submitted_by, submitted_by_role, completed_at, updated_at
            ) VALUES (?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE status = VALUES(status), version = VALUES(version), data_json = VALUES(data_json),
              returned_reason = VALUES(returned_reason), submitted_by = VALUES(submitted_by), submitted_by_role = VALUES(submitted_by_role),
              completed_at = VALUES(completed_at), updated_at = VALUES(updated_at)
            """, encounterId, stage, status, version, toJson(data), reason, user.name(), user.role(), completedAt, now());
    }

    private void updateStageVersioned(String encounterId, String stage, String status, ObjectNode data, String reason,
                                      SessionUser user, String completedAt, Integer expectedVersion) {
        int expected = requireExpectedVersion(expectedVersion, "阶段记录");
        int changed = jdbcTemplate.update("""
            UPDATE pre_ai_stage_submissions
            SET status = ?, version = version + 1, data_json = CAST(? AS JSON), returned_reason = ?,
                submitted_by = ?, submitted_by_role = ?, completed_at = ?, updated_at = ?
            WHERE encounter_id = ? AND stage_code = ? AND version = ?
            """, status, toJson(data), reason, user.name(), user.role(), completedAt, now(), encounterId, stage, expected);
        if (changed != 1) throwVersionConflict("阶段记录", stage, expectedVersion, loadStage(encounterId, stage));
    }

    private int requireExpectedVersion(Integer expectedVersion, String entity) {
        if (expectedVersion == null || expectedVersion < 0) throw badRequest(entity + "缺少有效的 expectedVersion");
        return expectedVersion;
    }

    private void throwVersionConflict(String entity, String id, Integer expectedVersion, ObjectNode current) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("entity", entity);
        summary.put("id", id);
        summary.put("expectedVersion", expectedVersion == null ? -1 : expectedVersion);
        summary.put("currentVersion", current.path("version").asInt(0));
        summary.put("status", text(current, "status"));
        summary.put("updatedAt", text(current, "updatedAt"));
        throw new VersionConflictException(entity + "已被其他终端更新，请刷新后重新提交", summary);
    }

    private ObjectNode loadEncounter(String encounterId) {
        List<ObjectNode> rows = jdbcTemplate.query("SELECT * FROM pre_ai_encounters WHERE id = ? LIMIT 1", (rs, rowNum) -> readEncounter(rs), safe(encounterId));
        if (rows.isEmpty()) throw notFound("前置病历就诊不存在");
        return rows.get(0);
    }

    private ObjectNode loadPatientCase(String patientCaseId) {
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT * FROM pre_ai_patient_cases WHERE id = ? LIMIT 1",
            (rs, rowNum) -> readPatientCase(rs),
            safe(patientCaseId)
        );
        if (rows.isEmpty()) throw notFound("患者主档案不存在");
        return rows.get(0);
    }

    private ObjectNode sanitizeVisitMeta(Map<String, Object> values) {
        ObjectNode result = objectMapper.createObjectNode();
        if (values == null || values.isEmpty()) return result;
        Set<String> allowed = Set.of(
            "visitReason", "description", "paymentStatus", "paymentAmount", "paymentItems", "paidAt", "paymentRemark"
        );
        values.forEach((key, value) -> {
            if (!allowed.contains(key) || value == null) return;
            JsonNode node = objectMapper.valueToTree(value);
            if (node.isTextual() && node.asText().isBlank()) return;
            result.set(key, node);
        });
        String paymentStatus = text(result, "paymentStatus");
        if (!paymentStatus.isBlank() && !Set.of("UNPAID", "PARTIAL", "PAID", "REFUNDED").contains(paymentStatus)) {
            throw badRequest("交费状态无效");
        }
        return result;
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupError) {
            log.warn("Failed to clean Pre-AI export file {}", path, cleanupError);
        }
    }

    private ObjectNode loadStage(String encounterId, String stage) {
        List<ObjectNode> rows = jdbcTemplate.query("SELECT * FROM pre_ai_stage_submissions WHERE encounter_id = ? AND stage_code = ? LIMIT 1", (rs, rowNum) -> readStage(rs), encounterId, stage);
        if (rows.isEmpty()) throw notFound("阶段记录不存在");
        return rows.get(0);
    }

    private ObjectNode loadAuxiliaryTask(String encounterId, String taskId) {
        List<ObjectNode> rows = jdbcTemplate.query("SELECT * FROM pre_ai_auxiliary_tasks WHERE encounter_id = ? AND id = ? LIMIT 1", (rs, rowNum) -> readAuxiliaryTask(rs), encounterId, taskId);
        if (rows.isEmpty()) throw notFound("辅助检查任务不存在");
        return rows.get(0);
    }

    private ObjectNode loadAttachment(String encounterId, String attachmentId) {
        List<ObjectNode> rows = jdbcTemplate.query("SELECT * FROM pre_ai_attachments WHERE encounter_id = ? AND id = ? LIMIT 1", (rs, rowNum) -> readAttachment(rs), encounterId, attachmentId);
        if (rows.isEmpty()) throw notFound("附件不存在");
        return rows.get(0);
    }

    private ObjectNode loadExport(String exportId) {
        List<ObjectNode> rows = jdbcTemplate.query("SELECT * FROM pre_ai_exports WHERE id = ? LIMIT 1", (rs, rowNum) -> readExport(rs), exportId);
        if (rows.isEmpty()) throw notFound("导出版本不存在");
        return rows.get(0);
    }

    private ObjectNode readEncounter(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("sourcePatientId", safe(rs.getString("source_patient_id")));
        row.put("patientCaseId", safe(rs.getString("patient_case_id")));
        row.put("visitNo", rs.getInt("visit_no"));
        row.put("followUpOfEncounterId", safe(rs.getString("follow_up_of_encounter_id")));
        row.put("caseToken", rs.getString("case_token"));
        row.put("route", safe(rs.getString("route")));
        row.put("treatmentPath", safe(rs.getString("treatment_path")));
        row.put("status", rs.getString("status"));
        row.put("currentStage", rs.getString("current_stage"));
        row.set("patient", readObject(rs.getString("patient_json")));
        row.set("visitMeta", readObject(rs.getString("visit_meta_json")));
        row.set("legacyReference", readObject(rs.getString("legacy_reference_json")));
        row.set("dutyAssignments", readArray(rs.getString("duty_assignments_json")));
        row.put("reviewedAt", safe(rs.getString("reviewed_at")));
        row.put("reviewedBy", safe(rs.getString("reviewed_by")));
        row.put("reviewedByRole", safe(rs.getString("reviewed_by_role")));
        row.put("createdAt", rs.getString("created_at"));
        row.put("updatedAt", rs.getString("updated_at"));
        row.put("createdBy", safe(rs.getString("created_by")));
        row.put("createdByRole", safe(rs.getString("created_by_role")));
        return row;
    }

    private ObjectNode readPatientCase(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("sourcePatientId", safe(rs.getString("source_patient_id")));
        ObjectNode patient = readObject(rs.getString("patient_json"));
        row.set("patient", patient);
        row.put("patientName", text(patient, "patientName"));
        row.put("gender", text(patient, "gender"));
        row.put("age", text(patient, "age"));
        row.put("createdAt", rs.getString("created_at"));
        row.put("updatedAt", rs.getString("updated_at"));
        return row;
    }

    private ObjectNode encounterSummary(ObjectNode encounter) {
        ObjectNode summary = encounter.deepCopy();
        JsonNode patient = summary.path("patient");
        summary.put("patientName", text(patient, "patientName"));
        summary.put("gender", text(patient, "gender"));
        summary.put("age", text(patient, "age"));
        summary.put("visitDate", text(patient, "visitDate"));
        summary.remove(List.of("patient", "visitMeta", "legacyReference"));
        summary.set("stageStatuses", stageStatusMap(text(summary, "id")));
        return summary;
    }

    private ObjectNode readStage(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("encounterId", rs.getString("encounter_id"));
        row.put("stageCode", rs.getString("stage_code"));
        row.put("status", rs.getString("status"));
        row.put("version", rs.getInt("version"));
        row.set("data", readObject(rs.getString("data_json")));
        row.put("returnedReason", safe(rs.getString("returned_reason")));
        row.put("submittedBy", safe(rs.getString("submitted_by")));
        row.put("submittedByRole", safe(rs.getString("submitted_by_role")));
        row.put("completedAt", safe(rs.getString("completed_at")));
        row.put("updatedAt", rs.getString("updated_at"));
        return row;
    }

    private ObjectNode readAuxiliaryTask(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("encounterId", rs.getString("encounter_id"));
        row.put("taskType", rs.getString("task_type"));
        row.put("title", safe(rs.getString("title")));
        row.put("ownerRole", rs.getString("owner_role"));
        row.put("requiredBeforeExport", rs.getBoolean("required_before_export"));
        row.put("status", rs.getString("status"));
        row.set("data", readObject(rs.getString("data_json")));
        row.put("version", rs.getInt("version"));
        row.put("completedAt", safe(rs.getString("completed_at")));
        row.put("updatedAt", rs.getString("updated_at"));
        row.put("updatedBy", safe(rs.getString("updated_by")));
        row.put("updatedByRole", safe(rs.getString("updated_by_role")));
        row.put("completedBy", safe(rs.getString("completed_by")));
        row.put("completedByRole", safe(rs.getString("completed_by_role")));
        row.put("createdAt", rs.getString("created_at"));
        row.put("createdBy", safe(rs.getString("created_by")));
        return row;
    }

    private ObjectNode readAttachment(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("encounterId", rs.getString("encounter_id"));
        row.put("stageCode", safe(rs.getString("stage_code")));
        row.put("taskId", safe(rs.getString("task_id")));
        row.put("fileName", safe(rs.getString("file_name")));
        row.put("storagePath", rs.getString("storage_path"));
        row.put("mimeType", safe(rs.getString("mime_type")));
        row.put("fileSize", rs.getLong("file_size"));
        row.put("sha256", safe(rs.getString("sha256")));
        row.put("description", safe(rs.getString("description")));
        row.put("capturedAt", safe(rs.getString("captured_at")));
        row.put("uploader", safe(rs.getString("uploader")));
        row.put("uploaderRole", safe(rs.getString("uploader_role")));
        row.put("batchId", safe(rs.getString("batch_id")));
        row.put("batchName", safe(rs.getString("batch_name")));
        row.put("relativePath", safe(rs.getString("relative_path")));
        row.put("sequenceNo", rs.getInt("sequence_no"));
        row.put("status", rs.getString("status"));
        row.put("createdAt", rs.getString("created_at"));
        row.put("downloadUrl", "/clinic-api/pre-ai/encounters/" + text(row, "encounterId") + "/attachments/" + text(row, "id") + "/download");
        return row;
    }

    private ObjectNode readLabReport(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("encounterId", rs.getString("encounter_id"));
        row.put("templateId", rs.getString("template_id"));
        row.put("templateName", rs.getString("template_name"));
        row.put("reportDate", rs.getString("report_date"));
        row.put("remark", safe(rs.getString("remark")));
        row.set("metrics", readArray(rs.getString("metrics_json")));
        row.put("version", rs.getInt("version"));
        row.put("status", rs.getString("status"));
        row.put("savedBy", safe(rs.getString("saved_by")));
        row.put("savedByRole", safe(rs.getString("saved_by_role")));
        row.put("savedAt", rs.getString("saved_at"));
        return row;
    }

    private ObjectNode readDiagnosis(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("diagnosisType", rs.getString("diagnosis_type"));
        row.put("diagnosisText", rs.getString("diagnosis_text"));
        row.put("sortNo", rs.getInt("sort_no"));
        row.put("sourceStage", rs.getString("source_stage"));
        row.put("updatedAt", rs.getString("updated_at"));
        return row;
    }

    private ObjectNode readAudit(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("action", rs.getString("action"));
        row.put("stageCode", safe(rs.getString("stage_code")));
        row.put("operator", safe(rs.getString("operator")));
        row.put("operatorRole", safe(rs.getString("operator_role")));
        row.put("detail", safe(rs.getString("detail")));
        row.put("createdAt", rs.getString("created_at"));
        return row;
    }

    private ObjectNode readExport(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("encounterId", rs.getString("encounter_id"));
        row.put("version", rs.getInt("version"));
        row.put("status", rs.getString("status"));
        row.put("caseToken", rs.getString("case_token"));
        row.put("fileName", rs.getString("file_name"));
        row.put("filePath", rs.getString("file_path"));
        row.put("generatedBy", safe(rs.getString("generated_by")));
        row.put("generatedByRole", safe(rs.getString("generated_by_role")));
        row.put("generatedAt", rs.getString("generated_at"));
        row.put("downloadUrl", exportDownloadUrl(text(row, "encounterId"), text(row, "id")));
        return row;
    }

    static String exportDownloadUrl(String encounterId, String exportId) {
        return "/pre-ai/encounters/" + encounterId + "/exports/" + exportId + "/download";
    }

    private ObjectNode stageStatusMap(String encounterId) {
        ObjectNode statuses = objectMapper.createObjectNode();
        jdbcTemplate.query("SELECT stage_code, status FROM pre_ai_stage_submissions WHERE encounter_id = ?", (org.springframework.jdbc.core.RowCallbackHandler) rs -> statuses.put(rs.getString("stage_code"), rs.getString("status")), encounterId);
        return statuses;
    }

    private void audit(String encounterId, String action, String stage, SessionUser user, String detail) {
        jdbcTemplate.update("INSERT INTO pre_ai_audit_logs (id, encounter_id, action, stage_code, operator, operator_role, detail, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            "preaudit-" + UUID.randomUUID(), encounterId, action, safe(stage), user.name(), user.role(), safe(detail), now());
    }

    private void requireReadRole(SessionUser user) {
        if (user == null || !READ_ROLES.contains(user.role())) throw forbidden("当前账号无权查看前置病历");
    }

    private void requireStageEditor(ObjectNode encounter, String stage, SessionUser user) {
        Set<String> configured = STAGE_EDITORS.get(stage);
        Set<String> allowed = configured == null ? null : new LinkedHashSet<>(configured);
        if (allowed != null && user != null && hasAssignedDuty(encounter, user, STAGE_DUTIES.getOrDefault(stage, Set.of()))) {
            allowed.add(user.role());
        }
        if (allowed == null || user == null || !allowed.contains(user.role())) throw forbidden("当前岗位无权维护" + stageLabel(stage));
    }

    private boolean hasAssignedDuty(ObjectNode encounter, SessionUser user, Set<String> dutyCodes) {
        if (encounter == null || user == null || dutyCodes == null || dutyCodes.isEmpty()) return false;
        String userId = safe(user.id());
        String userName = safe(user.name());
        String username = safe(user.username());
        for (JsonNode assignment : encounter.path("dutyAssignments")) {
            if (!dutyCodes.contains(text(assignment, "dutyCode"))) continue;
            String responsibleId = text(assignment, "responsibleUserId");
            String responsibleName = text(assignment, "responsibleUserName");
            if ((!userId.isBlank() && userId.equals(responsibleId))
                || (!userName.isBlank() && userName.equals(responsibleName))
                || (!username.isBlank() && username.equals(responsibleName))
                || containsText(assignment.path("participantUserIds"), userId)
                || containsText(assignment.path("participantUserNames"), userName)
                || containsText(assignment.path("participantUserNames"), username)) return true;
        }
        return false;
    }

    private boolean containsText(JsonNode values, String expected) {
        if (expected.isBlank() || !values.isArray()) return false;
        for (JsonNode value : values) if (expected.equals(value.asText(""))) return true;
        return false;
    }

    private void requireAuxCreator(ObjectNode encounter, String taskType, SessionUser user) {
        boolean baseRole = user != null && (Set.of("admin", "doctor", "reception").contains(user.role())
            || auxiliaryOwnerRoleMatches(taskType, user.role()));
        boolean assigned = hasAssignedDuty(encounter, user, Set.of("ATTENDING_DOCTOR", "RECEPTION_DOCTOR"))
            || hasAssignedDuty(encounter, user, AUX_DUTIES.getOrDefault(taskType, Set.of()));
        if (!baseRole && !assigned) throw forbidden("当前岗位无权创建该辅助检查任务");
    }

    private void requireAuxTaskEditor(ObjectNode encounter, String taskType, SessionUser user) {
        boolean baseRole = user != null && (Set.of("admin", "doctor").contains(user.role())
            || auxiliaryOwnerRoleMatches(taskType, user.role()));
        boolean assigned = hasAssignedDuty(encounter, user, Set.of("ATTENDING_DOCTOR"))
            || hasAssignedDuty(encounter, user, AUX_DUTIES.getOrDefault(taskType, Set.of()));
        if (!baseRole && !assigned) throw forbidden("当前岗位无权维护该辅助检查任务");
    }

    private boolean auxiliaryOwnerRoleMatches(String taskType, String role) {
        if ("VITAL_SIGNS".equals(taskType) && Set.of("nurse", "nursing").contains(role)) return true;
        return safe(role).equals(AUX_OWNER_ROLES.get(taskType));
    }

    private void requireAuxEditor(ObjectNode encounter, ObjectNode task, SessionUser user) {
        if (task == null) throw forbidden("当前岗位无权维护该辅助检查任务");
        try {
            requireAuxTaskEditor(encounter, text(task, "taskType"), user);
        } catch (ResponseStatusException error) {
            throw forbidden("当前岗位无权维护该辅助检查任务");
        }
    }

    private void requireReviewer(ObjectNode encounter, SessionUser user) {
        boolean baseRole = user != null && Set.of("admin", "doctor").contains(user.role());
        boolean assigned = hasAssignedDuty(encounter, user, Set.of("FINAL_REVIEW_DOCTOR", "ATTENDING_DOCTOR"));
        if (!baseRole && !assigned) throw forbidden("当前岗位无权执行医生复核操作");
    }

    private void requireRole(SessionUser user, String... roles) {
        if (user == null || !Set.of(roles).contains(user.role())) throw forbidden("当前岗位无权执行此操作");
    }

    private String normalizeStage(String value) {
        String stage = safe(value).toUpperCase(Locale.ROOT);
        if (!STAGE_ORDER.contains(stage)) throw badRequest("不支持的流程阶段");
        return stage;
    }

    private String normalizeTaskType(String value) {
        String type = safe(value).toUpperCase(Locale.ROOT);
        if (!AUX_OWNER_ROLES.containsKey(type)) throw badRequest("不支持的辅助检查类型");
        return type;
    }

    private String normalizeEnum(String value, Set<String> values, String label) {
        String normalized = safe(value).toUpperCase(Locale.ROOT);
        if (!values.contains(normalized)) throw badRequest(label + "取值不正确");
        return normalized;
    }

    private void required(JsonNode data, List<String> missing, String key, String label) {
        JsonNode value = data.path(key);
        if (value.isMissingNode() || value.isNull() || (value.isTextual() && value.asText().isBlank()) || ((value.isArray() || value.isObject()) && value.isEmpty())) missing.add(label);
    }

    private void requiredMeasurement(JsonNode data, List<String> missing, String key, String label) {
        JsonNode measurement = data.path(key);
        if (!measurement.isObject() || text(measurement, "value").isBlank()) missing.add(label);
    }

    private String nextCaseToken() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String token = "CASE-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pre_ai_encounters WHERE case_token = ?", Integer.class, token);
            if (count == null || count == 0) return token;
        }
        return "CASE-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private JsonNode findById(JsonNode rows, String id) {
        if (!rows.isArray()) return null;
        for (JsonNode row : rows) if (id.equals(text(row, "id"))) return row;
        return null;
    }

    private ObjectNode safeObject(JsonNode node) {
        return node != null && node.isObject() ? ((ObjectNode) node).deepCopy() : objectMapper.createObjectNode();
    }

    private ObjectNode readObject(String json) {
        if (json == null || json.isBlank()) return objectMapper.createObjectNode();
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
        } catch (Exception error) {
            return objectMapper.createObjectNode();
        }
    }

    private ArrayNode readArray(String json) {
        if (json == null || json.isBlank()) return objectMapper.createArrayNode();
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isArray() ? (ArrayNode) node : objectMapper.createArrayNode();
        } catch (Exception error) {
            return objectMapper.createArrayNode();
        }
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node == null ? objectMapper.createObjectNode() : node);
        } catch (Exception error) {
            throw new IllegalArgumentException("JSON 序列化失败", error);
        }
    }

    private Map<String, Object> toMap(JsonNode node) {
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    private String text(JsonNode node, String key) {
        return text(node, key, "");
    }

    private String text(JsonNode node, String key, String fallback) {
        if (node == null) return fallback;
        JsonNode value = node.path(key);
        String text = value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
        return text.isBlank() ? fallback : text;
    }

    private String display(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) if (!item.asText("").isBlank()) values.add(item.asText());
            return String.join("、", values);
        }
        return node.asText("");
    }

    private String join(ArrayNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return String.join("、", result);
    }

    private String stageLabel(String stage) {
        return switch (stage) {
            case "REGISTRATION" -> "前台登记";
            case "INSPECTION" -> "检查室";
            case "RECEPTION" -> "接诊室";
            case "TCM" -> "中医岗位";
            case "DOCTOR" -> "医生诊疗方案";
            case "SURGERY" -> "手术室登记";
            case "REVIEW" -> "医生复核";
            default -> stage;
        };
    }

    private String auxiliaryLabel(String type) {
        return switch (type) {
            case "LAB" -> "检验";
            case "ECG" -> "心电";
            case "IMAGING" -> "影像";
            case "VITAL_SIGNS" -> "生命体征";
            case "COLONOSCOPY" -> "肠镜";
            default -> type;
        };
    }

    private String optionalTitle(String value) {
        return safe(value).isBlank() ? "" : "（" + value + "）";
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record CreateEncounterRequest(Map<String, Object> patient) {}
    public record DutyAssignmentsRequest(List<Map<String, Object>> dutyAssignments) {}
    public record StageSaveRequest(Map<String, Object> data, Integer expectedVersion) {}
    public record ReturnStageRequest(String reason, Integer expectedVersion) {}
    public record AuxiliaryTaskRequest(String taskType, String title, boolean requiredBeforeExport) {}
    public record AuxiliaryTaskSaveRequest(String title, boolean requiredBeforeExport, Map<String, Object> data, Integer expectedVersion) {}
    public record AttachmentUploadRequest(
        String stageCode,
        String taskId,
        String fileName,
        String contentDataUrl,
        String description,
        String capturedAt,
        String batchId,
        String batchName,
        String relativePath,
        Integer sequenceNo
    ) {}
    public record LabReportRequest(
        String templateId,
        String templateName,
        String reportDate,
        String remark,
        List<Map<String, Object>> metrics,
        Integer expectedVersion
    ) {}
    public record ReviewConfirmRequest(String statement, boolean criticalAcknowledged, Integer expectedVersion) {}
    public record VersionRequest(Integer expectedVersion) {}
    public record FollowUpEncounterCreateRequest(String visitDate, Map<String, Object> visitMeta) {}
    public record VisitMetaRequest(Map<String, Object> visitMeta) {}
    public record AttachmentDownload(FileSystemResource resource, String fileName, String mimeType) {}
    public record ExportDownload(FileSystemResource resource, String fileName) {}
}

