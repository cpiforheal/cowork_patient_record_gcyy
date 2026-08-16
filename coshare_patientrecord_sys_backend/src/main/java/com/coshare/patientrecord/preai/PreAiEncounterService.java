package com.coshare.patientrecord.preai;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.AuthNavigationService;
import com.coshare.patientrecord.auth.service.RoleCatalog;
import com.coshare.patientrecord.common.exception.VersionConflictException;
import com.coshare.patientrecord.clinic.service.ClinicDatabaseService;
import com.coshare.patientrecord.clinicqueue.ClinicQueueService;
import com.coshare.patientrecord.file.dto.ClinicFileUploadRequest;
import com.coshare.patientrecord.file.model.ClinicStoredFile;
import com.coshare.patientrecord.file.service.ClinicFileService;
import com.coshare.patientrecord.inventory.service.InventoryStageConsumptionService;

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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

@Service
@Profile("mysql")
public class PreAiEncounterService {

    private static final Logger log = LoggerFactory.getLogger(PreAiEncounterService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> STAGE_ORDER = List.of("REGISTRATION", "INSPECTION", "RECEPTION", "TCM", "DOCTOR", "SURGERY", "REVIEW");
    private static final Set<String> INVENTORY_CONSUMPTION_STAGES = Set.of("INSPECTION", "TCM", "DOCTOR", "SURGERY");
    private static final Set<String> READ_ROLES = Set.of("admin", "frontdesk", "inspection", "reception", "tcm", "doctor", "nurse", "lab", "ecg", "ultrasound", "quality");
    private static final Set<String> FULL_OPERATION_ROLES = Set.of(
        "admin", "inspection", "reception", "tcm", "doctor", "nurse", "lab", "ecg", "ultrasound"
    );
    private static final Set<String> DUTY_CODES = Set.of(
        "FRONT_DESK", "RECEPTION_DOCTOR", "TCM_DOCTOR", "INSPECTION_DOCTOR", "LAB_STAFF",
        "BASIC_NURSING", "ATTENDING_DOCTOR", "SURGEON", "OPERATING_ROOM_NURSE", "FINAL_REVIEW_DOCTOR"
    );
    private static final Map<String, Set<String>> STAGE_DUTIES = Map.of(
        "REGISTRATION", Set.of("FRONT_DESK"),
        "INSPECTION", Set.of("INSPECTION_DOCTOR", "RECEPTION_DOCTOR", "ATTENDING_DOCTOR"),
        "RECEPTION", Set.of("RECEPTION_DOCTOR", "INSPECTION_DOCTOR", "ATTENDING_DOCTOR"),
        "TCM", Set.of("TCM_DOCTOR"),
        "DOCTOR", Set.of("ATTENDING_DOCTOR"),
        "SURGERY", Set.of("SURGEON", "OPERATING_ROOM_NURSE"),
        "REVIEW", Set.of("FINAL_REVIEW_DOCTOR", "ATTENDING_DOCTOR")
    );
    private static final Map<String, Set<String>> DUTY_ROLES = Map.ofEntries(
        Map.entry("FRONT_DESK", Set.of("frontdesk")),
        Map.entry("RECEPTION_DOCTOR", Set.of("reception", "doctor")),
        Map.entry("TCM_DOCTOR", Set.of("tcm")),
        Map.entry("INSPECTION_DOCTOR", Set.of("inspection")),
        Map.entry("LAB_STAFF", Set.of("lab")),
        Map.entry("BASIC_NURSING", Set.of("nurse")),
        Map.entry("ATTENDING_DOCTOR", Set.of("doctor")),
        Map.entry("SURGEON", Set.of("doctor")),
        Map.entry("OPERATING_ROOM_NURSE", Set.of("nurse")),
        Map.entry("FINAL_REVIEW_DOCTOR", Set.of("doctor", "quality"))
    );
    private static final Map<String, Set<String>> ALLOWED_FIELDS = Map.of(
        "REGISTRATION", Set.of(
            "patientName", "gender", "birthDate", "age", "phone", "identityType", "identityNumber", "address",
            "contactName", "contactRelation", "contactPhone", "visitDate", "visitPurpose", "patientSource", "registrationNote",
            "registrationChiefComplaint", "visitProblem", "visitExpectation", "registrationPastHistory", "registrationIllnessHistory", "registrationPersonalHistory", "registrationCurrentIllness",
            "visitNo", "admissionNo", "medicalRecordNo", "inpatientNo", "ward", "bedNo", "admissionCount",
            "nationality", "nativePlace", "birthplace", "maritalStatus", "admissionMethod", "insuranceType", "paymentMethod",
            "owningDepartmentId", "inventoryCareType", "careSituationDescription",
            "clinicalTemplateIds", "clinicalTemplateDiseases", "clinicalTemplateVersion", "clinicalTemplateAppliedAt", "clinicalTemplateSlots"
        ),
        "INSPECTION", Set.of(
            "examinationDirection", "diseaseDirections", "examinationTypes", "lesionLocation", "clockPosition",
            "lesionSize", "lesionExtent", "lesionDepth", "visualFindings", "digitalExamFindings", "anoscopyFindings",
            "otherFindings", "preliminaryDiagnosis", "preliminaryDiagnosisNote", "factualConclusion", "factualConclusionOverride", "factualConclusionSourceHash", "factualConclusionConfirmed",
            "inspectionSpecialDescription", "inspectionNarrative", "nextReviewAt", "nextReviewNote",
            "clinicalTemplateIds", "clinicalTemplateDiseases", "clinicalTemplateVersion", "clinicalTemplateAppliedAt", "clinicalTemplateSlots"
        ),
        "RECEPTION", Set.of(
            "chiefComplaint", "symptomDuration", "onsetTrigger", "symptomPattern", "symptomChanges", "aggravatingFactors",
            "bleedingFeatures", "painFeatures", "prolapseReduction", "associatedSymptoms", "recentAggravation",
            "previousTreatment", "generalCondition", "stoolFrequency", "stoolCharacteristics", "chiefComplaintText", "presentIllness",
            "presentIllnessOverride", "presentIllnessSourceHash", "presentIllnessConfirmed", "chronicDiseaseItems", "surgicalHistoryItems",
            "clinicalTemplateIds", "clinicalTemplateDiseases", "clinicalTemplateVersion", "clinicalTemplateAppliedAt", "clinicalTemplateSlots",
            "pastHistory", "surgicalHistory", "traumaHistory", "transfusionHistory", "vaccinationHistory",
            "medicationHistory", "allergyHistory", "personalHistory", "maritalHistory", "familyHistory", "historySupplement",
            "reviewOpinion", "nextStepRecommendation", "dispositionSuggestion", "dispositionSupplement", "recommendedAuxiliaryExams", "specialCircumstances",
            "chiefComplaintSupplement", "receptionSpecialDescription", "physicalExam", "physicalExamOverride",
            "physicalExamSourceHash", "physicalExamConfirmed"
        ),
        "TCM", Set.of(
            "tcmDisease", "primarySyndrome", "concurrentSyndrome", "inspection", "auscultationOlfaction", "inquiry",
            "palpation", "tongue", "pulse", "syndromeBasis", "syndromeBasisOverride", "syndromeBasisSourceHash",
            "syndromeBasisConfirmed", "treatmentPrinciple", "preoperativeAssessment", "consultationOpinion", "comorbidTcmItems"
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
    private static final Set<String> ADMISSION_PROFILE_FIELDS = Set.of(
        "contactName", "contactRelation", "contactPhone", "nativePlace", "birthplace", "maritalStatus",
        "insuranceType", "paymentMethod", "medicalRecordNo", "inpatientNo", "ward", "bedNo", "admissionCount",
        "admissionMethod"
    );
    private static final Map<String, String> AUX_OWNER_ROLES = Map.of(
        "LAB", "lab", "ECG", "ecg", "IMAGING", "ultrasound", "VITAL_SIGNS", "nursing", "COLONOSCOPY", "inspection"
    );
    private static final Map<String, Set<String>> AUX_DUTIES = Map.of(
        "LAB", Set.of("LAB_STAFF"),
        "VITAL_SIGNS", Set.of("BASIC_NURSING"),
        "COLONOSCOPY", Set.of("INSPECTION_DOCTOR"),
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
    private final InventoryStageConsumptionService inventoryStageConsumptionService;
    private final AuthNavigationService navigationService;
    private final Path generatedDir;

    public PreAiEncounterService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        ClinicDatabaseService clinicDatabaseService,
        ClinicFileService fileService,
        PreAiPrivacyService privacyService,
        ClinicQueueService clinicQueueService,
        InventoryStageConsumptionService inventoryStageConsumptionService,
        AuthNavigationService navigationService,
        @Value("${clinic.generated-pre-ai-dir:${clinic.attachment-dir}/../generated-pre-ai}") String generatedDir
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clinicDatabaseService = clinicDatabaseService;
        this.fileService = fileService;
        this.privacyService = privacyService;
        this.clinicQueueService = clinicQueueService;
        this.inventoryStageConsumptionService = inventoryStageConsumptionService;
        this.navigationService = navigationService;
        this.generatedDir = Path.of(generatedDir).toAbsolutePath().normalize();
    }

    @Transactional
    public Map<String, Object> create(CreateEncounterRequest request, SessionUser user) {
        requireEncounterCreator(user);
        ObjectNode patient = sanitizeStageData("REGISTRATION", request == null ? null : request.patient());
        validateStage("REGISTRATION", patient, null);
        String sourcePatientId = createClinicPatientArchive(patient);
        String patientCaseId = createPatientCase(patient, sourcePatientId);
        return toMap(createEncounterInternal(patient, sourcePatientId, patientCaseId, 1, "", objectMapper.createObjectNode(), objectMapper.createObjectNode(), user));
    }

    @Transactional
    public Map<String, Object> registerAndIssue(RegisterAndIssueRequest request, SessionUser user) {
        requireEncounterCreator(user);
        String clientRequestId = validateRegistrationRequestId(request == null ? "" : request.clientRequestId());

        List<String> existing = jdbcTemplate.query(
            "SELECT id FROM pre_ai_encounters WHERE registration_request_id = ? LIMIT 1",
            (rs, rowNum) -> rs.getString("id"), clientRequestId
        );
        if (!existing.isEmpty()) return registerAndIssueResult(existing.get(0), user);

        ObjectNode patient = sanitizeStageData("REGISTRATION", request.patient());
        validateStage("REGISTRATION", patient, null);
        String sourcePatientId = createClinicPatientArchive(patient);
        String patientCaseId = createPatientCase(patient, sourcePatientId);
        ObjectNode created;
        try {
            created = createEncounterInternal(
                patient, sourcePatientId, patientCaseId, 1, "", objectMapper.createObjectNode(), objectMapper.createObjectNode(), user, clientRequestId
            );
        } catch (DuplicateKeyException duplicate) {
            List<String> concurrent = jdbcTemplate.query(
                "SELECT id FROM pre_ai_encounters WHERE registration_request_id = ? LIMIT 1",
                (rs, rowNum) -> rs.getString("id"), clientRequestId
            );
            if (concurrent.isEmpty()) throw duplicate;
            jdbcTemplate.update("DELETE FROM pre_ai_patient_cases WHERE id = ? AND NOT EXISTS (SELECT 1 FROM pre_ai_encounters WHERE patient_case_id = ?)", patientCaseId, patientCaseId);
            jdbcTemplate.update("DELETE FROM clinic_patients WHERE id = ? AND NOT EXISTS (SELECT 1 FROM pre_ai_patient_cases WHERE source_patient_id = ?)", sourcePatientId, sourcePatientId);
            return registerAndIssueResult(concurrent.get(0), user);
        }

        String encounterId = text(created.path("encounter"), "id");
        updateStageVersioned(encounterId, "REGISTRATION", "COMPLETED", patient, "", user, now(), 0);
        applyRegistrationPurpose(encounterId, patient, user);
        audit(encounterId, "registration.complete-and-issue", "REGISTRATION", user, "就诊登记完成并生成检查接诊号码");
        refreshProgress(encounterId);
        return registerAndIssueResult(encounterId, user);
    }

    @Transactional
    public Map<String, Object> createFollowUpAndIssue(String patientCaseId, FollowUpRegisterAndIssueRequest request, SessionUser user) {
        requireEncounterCreator(user);
        String clientRequestId = validateRegistrationRequestId(request == null ? "" : request.clientRequestId());
        List<ObjectNode> existing = jdbcTemplate.query(
            "SELECT * FROM pre_ai_encounters WHERE registration_request_id = ? LIMIT 1",
            (rs, rowNum) -> readEncounter(rs), clientRequestId
        );
        if (!existing.isEmpty()) {
            if (!safe(patientCaseId).equals(text(existing.get(0), "patientCaseId"))) {
                throw conflict("登记请求标识已用于其他患者");
            }
            return registerAndIssueResult(text(existing.get(0), "id"), user);
        }

        lockPatientCase(patientCaseId);
        ObjectNode patientCase = loadPatientCase(patientCaseId);
        ObjectNode patient = safeObject(patientCase.path("patient"));
        String visitDate = safe(request == null ? "" : request.visitDate());
        patient.put("visitDate", visitDate.isBlank() ? LocalDate.now().toString() : visitDate);
        if (text(patient, "inventoryCareType").isBlank()) patient.put("inventoryCareType", "outpatient");
        if (text(patient, "visitPurpose").isBlank()) patient.put("visitPurpose", "GENERAL");
        validateStage("REGISTRATION", patient, null);
        List<ObjectNode> previous = jdbcTemplate.query(
            "SELECT * FROM pre_ai_encounters WHERE patient_case_id = ? ORDER BY visit_no DESC, created_at DESC LIMIT 1",
            (rs, rowNum) -> readEncounter(rs), patientCaseId
        );
        if (!previous.isEmpty()) requireEncounterAccess(text(previous.get(0), "id"), user);
        int visitNo = previous.isEmpty() ? 1 : previous.get(0).path("visitNo").asInt(0) + 1;
        String previousEncounterId = previous.isEmpty() ? "" : text(previous.get(0), "id");
        ObjectNode visitMeta = sanitizeVisitMeta(request == null ? null : request.visitMeta());
        ObjectNode created;
        try {
            created = createEncounterInternal(
                patient,
                text(patientCase, "sourcePatientId"),
                patientCaseId,
                visitNo,
                previousEncounterId,
                objectMapper.createObjectNode(),
                visitMeta,
                user,
                clientRequestId
            );
        } catch (DuplicateKeyException duplicate) {
            List<String> concurrent = jdbcTemplate.query(
                "SELECT id FROM pre_ai_encounters WHERE registration_request_id = ? LIMIT 1",
                (rs, rowNum) -> rs.getString("id"), clientRequestId
            );
            if (concurrent.isEmpty()) throw duplicate;
            return registerAndIssueResult(concurrent.get(0), user);
        }
        String encounterId = text(created.path("encounter"), "id");
        updateStageVersioned(encounterId, "REGISTRATION", "COMPLETED", patient, "", user, now(), 0);
        applyRegistrationPurpose(encounterId, patient, user);
        jdbcTemplate.update("UPDATE pre_ai_patient_cases SET patient_json = CAST(? AS JSON), updated_at = ? WHERE id = ?", toJson(patient), now(), patientCaseId);
        audit(encounterId, "encounter.followup.register-and-issue", "REGISTRATION", user,
            "创建第 " + visitNo + " 次来访、完成登记并生成检查接诊号码");
        refreshProgress(encounterId);
        return registerAndIssueResult(encounterId, user);
    }

    @Transactional
    public Map<String, Object> registerExistingAndIssue(String encounterId, ExistingRegisterAndIssueRequest request, SessionUser user) {
        requireEncounterCreator(user);
        requireEncounterAccess(encounterId, user);
        String clientRequestId = validateRegistrationRequestId(request == null ? "" : request.clientRequestId());
        ObjectNode encounter = loadEncounter(encounterId);
        if ("CANCELLED".equals(text(encounter, "status"))) throw conflict("就诊记录已取消，无法发号");

        List<ObjectNode> requestOwner = jdbcTemplate.query(
            "SELECT * FROM pre_ai_encounters WHERE registration_request_id = ? LIMIT 1",
            (rs, rowNum) -> readEncounter(rs), clientRequestId
        );
        if (!requestOwner.isEmpty() && !encounterId.equals(text(requestOwner.get(0), "id"))) {
            throw conflict("登记请求标识已用于其他就诊记录");
        }

        ObjectNode registration = loadStage(encounterId, "REGISTRATION");
        if (!"COMPLETED".equals(text(registration, "status"))) {
            if (!Set.of("DRAFT", "RETURNED").contains(text(registration, "status"))) {
                throw conflict("当前登记状态不允许补登记发号");
            }
            ObjectNode patient = sanitizeStageData(
                "REGISTRATION",
                request == null || request.patient() == null
                    ? objectMapper.convertValue(encounter.path("patient"), new TypeReference<Map<String, Object>>() {})
                    : request.patient()
            );
            validateStage("REGISTRATION", patient, null);
            int changed = jdbcTemplate.update("""
                UPDATE pre_ai_encounters
                SET patient_json = CAST(? AS JSON), registration_request_id = NULLIF(?, ''), updated_at = ?
                WHERE id = ? AND (registration_request_id IS NULL OR registration_request_id = '' OR registration_request_id = ?)
                """, toJson(patient), clientRequestId, now(), encounterId, clientRequestId);
            if (changed != 1) throw conflict("该复诊正在由其他终端补登记，请刷新后重试");
            updateStageVersioned(encounterId, "REGISTRATION", "COMPLETED", patient, "", user, now(),
                request == null ? null : request.expectedVersion());
            applyRegistrationPurpose(encounterId, patient, user);
            jdbcTemplate.update("UPDATE pre_ai_patient_cases SET patient_json = CAST(? AS JSON), updated_at = ? WHERE id = ?",
                toJson(patient), now(), text(encounter, "patientCaseId"));
            audit(encounterId, "registration.recover-and-issue", "REGISTRATION", user, "补全存量复诊登记并生成检查接诊号码");
            refreshProgress(encounterId);
        }
        return registerAndIssueResult(encounterId, user);
    }

    private Map<String, Object> registerAndIssueResult(String encounterId, SessionUser user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("encounterWorkspace", toMap(workspace(encounterId, user)));
        result.put("queueWorkspace", clinicQueueService.issue(new ClinicQueueService.IssueRequest(encounterId, ""), user));
        return result;
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
            if (!canAccessEncounter(text(row, "id"), user)) return;
            rows.add(encounterSummary(row));
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
            if (encounters.isEmpty()) {
                patientCase.put("visitCount", 0);
                patientCase.put("legacyProgressFallback", true);
                rows.add(patientCase);
                return;
            }
            encounters.removeIf(encounter -> !canAccessEncounter(text(encounter, "id"), user));
            if (encounters.isEmpty()) return;
            patientCase.put("visitCount", encounters.size());
            if (!encounters.isEmpty()) patientCase.set("latestEncounter", encounterSummary(encounters.get(0)));
            rows.add(patientCase);
        });
        return Map.of("list", objectMapper.convertValue(rows, new TypeReference<List<Map<String, Object>>>() {}));
    }

    public Map<String, Object> encounterHistory(String patientCaseId, SessionUser user) {
        requireReadRole(user);
        loadPatientCase(patientCaseId);
        ArrayNode rows = objectMapper.createArrayNode();
        jdbcTemplate.query(
            "SELECT * FROM pre_ai_encounters WHERE patient_case_id = ? ORDER BY visit_no DESC, created_at DESC, id DESC",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                ObjectNode encounter = readEncounter(rs);
                String encounterId = text(encounter, "id");
                if (!canAccessEncounter(encounterId, user)) return;
                ObjectNode summary = encounterSummary(encounter);
                summary.put("visitType", encounter.path("visitNo").asInt(1) <= 1 ? "INITIAL" : "FOLLOW_UP");
                summary.put("previousEncounterId", text(encounter, "followUpOfEncounterId"));
                ObjectNode statuses = stageStatusMap(encounterId);
                ArrayNode completedStages = summary.putArray("completedStages");
                statuses.fields().forEachRemaining(entry -> {
                    if ("COMPLETED".equals(entry.getValue().asText())) completedStages.add(entry.getKey());
                });
                summary.put("completedStageCount", completedStages.size());
                JsonNode visitMeta = encounter.path("visitMeta");
                summary.put("visitReason", text(visitMeta, "visitReason"));
                summary.put("description", text(visitMeta, "description"));
                rows.add(summary);
            },
            patientCaseId
        );
        return Map.of(
            "patientCaseId", patientCaseId,
            "encounters", objectMapper.convertValue(rows, new TypeReference<List<Map<String, Object>>>() {})
        );
    }

    @Transactional
    public Map<String, Object> createFollowUp(String patientCaseId, FollowUpEncounterCreateRequest request, SessionUser user) {
        requireEncounterCreator(user);
        ObjectNode patientCase = loadPatientCase(patientCaseId);
        ObjectNode patient = safeObject(patientCase.path("patient"));
        String visitDate = safe(request == null ? "" : request.visitDate());
        if (visitDate.isBlank()) visitDate = now();
        patient.put("visitDate", visitDate);
        if (text(patient, "inventoryCareType").isBlank()) patient.put("inventoryCareType", "outpatient");
        validateStage("REGISTRATION", patient, null);
        List<ObjectNode> previous = jdbcTemplate.query(
            "SELECT * FROM pre_ai_encounters WHERE patient_case_id = ? ORDER BY visit_no DESC, created_at DESC LIMIT 1",
            (rs, rowNum) -> readEncounter(rs), patientCaseId
        );
        if (!previous.isEmpty()) requireEncounterAccess(text(previous.get(0), "id"), user);
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

    private String validateRegistrationRequestId(String value) {
        String clientRequestId = safe(value);
        if (clientRequestId.isBlank()) throw badRequest("缺少登记请求标识");
        if (clientRequestId.length() > 64) throw badRequest("登记请求标识不能超过 64 个字符");
        return clientRequestId;
    }

    private void lockPatientCase(String patientCaseId) {
        List<String> rows = jdbcTemplate.query(
            "SELECT id FROM pre_ai_patient_cases WHERE id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getString("id"), safe(patientCaseId)
        );
        if (rows.isEmpty()) throw notFound("患者主档案不存在");
    }

    @Transactional
    public Map<String, Object> updateVisitMeta(String encounterId, VisitMetaRequest request, SessionUser user) {
        requireRole(user, "frontdesk");
        requireEncounterAccess(encounterId, user);
        requireActiveEncounter(loadEncounter(encounterId));
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
            if (!canAccessEncounter(encounterId, user)) continue;
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

    public Map<String, Object> getWorkspace(String encounterId, boolean readOnly, String patientCaseId, SessionUser user) {
        ObjectNode result = workspace(encounterId, user);
        if (readOnly) {
            String actualPatientCaseId = text(result.path("encounter"), "patientCaseId");
            if (safe(patientCaseId).isBlank() || !actualPatientCaseId.equals(safe(patientCaseId))) {
                throw forbidden("历史病历不属于当前患者主档案");
            }
            result.put("readOnly", true);
        }
        return toMap(result);
    }

    public Map<String, Object> getWorkspace(String encounterId, SessionUser user) {
        return getWorkspace(encounterId, false, "", user);
    }

    /** Returns enabled, department-authorized staff that may be assigned to a case duty. */
    public Map<String, Object> listDutyUserOptions(SessionUser user) {
        requireDutyAssignmentManager(user);
        List<Map<String, Object>> users = jdbcTemplate.query(
            """
            SELECT a.id, a.username, a.role,
                   COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(a.raw_json, '$.name')), ''), a.username) display_name,
                   GROUP_CONCAT(DISTINCT d.name ORDER BY d.name SEPARATOR '、') department
            FROM clinic_accounts a
            JOIN clinic_account_departments ad ON ad.account_id = a.id AND ad.status = 'ACTIVE'
            JOIN clinic_departments d ON d.id = ad.department_id AND d.status = 'ACTIVE'
            WHERE a.status = '启用'
            GROUP BY a.id, a.username, a.role, a.raw_json
            ORDER BY a.role, a.username, a.id
            """,
            (rs, rowNum) -> Map.<String, Object>of(
                "id", rs.getString("id"),
                "name", rs.getString("display_name"),
                "username", rs.getString("username"),
                "role", RoleCatalog.canonicalize(rs.getString("role")),
                "department", rs.getString("department")
            )
        ).stream().filter(account -> DUTY_ROLES.values().stream().anyMatch(roles -> roles.contains(account.get("role")))).toList();
        return Map.of("list", users);
    }

    @Transactional
    public Map<String, Object> saveDutyAssignments(String encounterId, DutyAssignmentsRequest request, SessionUser user) {
        requireDutyAssignmentManager(user);
        requireEncounterAccess(encounterId, user);
        requireActiveEncounter(loadEncounter(encounterId));
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
                String responsibleUserId = text(source, "responsibleUserId");
                if (!responsibleUserId.isBlank()) {
                    DutyAccount account = requireDutyAccount(responsibleUserId, dutyCode);
                    clean.put("responsibleUserId", account.id());
                    clean.put("responsibleUserName", account.name());
                }
                ArrayNode participantIds = clean.putArray("participantUserIds");
                ArrayNode participantNames = clean.putArray("participantUserNames");
                Set<String> participantSeen = new LinkedHashSet<>();
                for (JsonNode participant : source.path("participantUserIds")) {
                    String participantId = safe(participant.asText());
                    if (participantId.isBlank() || !participantSeen.add(participantId)) continue;
                    DutyAccount account = requireDutyAccount(participantId, dutyCode);
                    participantIds.add(account.id());
                    participantNames.add(account.name());
                }
            }
        }
        jdbcTemplate.update("UPDATE pre_ai_encounters SET duty_assignments_json = CAST(? AS JSON), updated_at = ? WHERE id = ?",
            toJson(assignments), now(), encounterId);
        invalidateReview(encounterId, user, "病例岗位安排发生修改");
        audit(encounterId, "duty.assignments.save", "REGISTRATION", user, "更新病例级一人多岗安排");
        return toMap(workspace(encounterId, user));
    }

    private DutyAccount requireDutyAccount(String accountId, String dutyCode) {
        Set<String> requiredRoles = DUTY_ROLES.get(dutyCode);
        List<DutyAccount> rows = jdbcTemplate.query(
            """
            SELECT a.id, COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(a.raw_json, '$.name')), ''), a.username) display_name,
                   a.role
            FROM clinic_accounts a
            WHERE a.id = ? AND a.status = '启用'
              AND EXISTS (
                SELECT 1
                FROM clinic_account_departments ad
                JOIN clinic_departments d ON d.id = ad.department_id AND d.status = 'ACTIVE'
                WHERE ad.account_id = a.id AND ad.status = 'ACTIVE'
              )
            LIMIT 1
            """,
            (rs, rowNum) -> new DutyAccount(rs.getString("id"), rs.getString("display_name"), rs.getString("role")),
            accountId
        );
        if (rows.isEmpty() || requiredRoles == null || !requiredRoles.contains(RoleCatalog.canonicalize(rows.get(0).role()))) {
            throw badRequest("岗位分配失败：账号未启用、岗位不匹配或未获科室授权（" + dutyCode + "）");
        }
        return rows.get(0);
    }

    @Transactional
    public Map<String, Object> saveStage(String encounterId, String stageCode, StageSaveRequest request, SessionUser user) {
        requireEncounterAccess(encounterId, user);
        String stage = normalizeStage(stageCode);
        ObjectNode encounter = loadEncounter(encounterId);
        requireStageEditor(encounter, stage, user);
        ObjectNode current = loadStage(encounterId, stage);
        if ("COMPLETED".equals(text(current, "status"))) {
            throw conflict("该阶段已完成，需进入纠错模式并填写原因后才能修改");
        }
        ObjectNode data = sanitizeStageData(stage, request == null ? null : request.data());
        if ("SURGERY".equals(stage)) data.remove(List.of("physicianConfirmed", "physicianConfirmedBy", "physicianConfirmedAt"));
        if ("REGISTRATION".equals(stage)) {
            syncRegistrationCareType(encounterId, data, encounter);
            jdbcTemplate.update("UPDATE pre_ai_encounters SET patient_json = ?, updated_at = ? WHERE id = ?", toJson(data), now(), encounterId);
            String patientCaseId = text(encounter, "patientCaseId");
            if (!patientCaseId.isBlank()) {
                jdbcTemplate.update("UPDATE pre_ai_patient_cases SET patient_json = CAST(? AS JSON), updated_at = ? WHERE id = ?", toJson(data), now(), patientCaseId);
            }
        }
        if ("DOCTOR".equals(stage)) syncEncounterBranch(encounterId, data, encounter, user);
        updateStageVersioned(encounterId, stage, text(current, "status", "DRAFT"), data, "", user, "", request == null ? null : request.expectedVersion());
        syncDiagnoses(encounterId, stage, data);
        invalidateReview(encounterId, user, "阶段内容发生修改");
        audit(encounterId, "stage.save", stage, user, "保存阶段草稿");
        refreshProgress(encounterId);
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> correctOwningDepartment(String encounterId, DepartmentCorrectionRequest request, SessionUser user) {
        requireRole(user, "admin");
        requireEncounterAccess(encounterId, user);
        ObjectNode encounter = loadEncounter(encounterId);
        requireActiveEncounter(encounter);
        String departmentId = safe(request == null ? null : request.departmentId());
        String reason = safe(request == null ? null : request.reason());
        if (departmentId.isBlank()) throw badRequest("请选择归属科室");
        if (reason.isBlank()) throw badRequest("请填写归属修正原因");
        List<DepartmentIdentity> departments = jdbcTemplate.query(
            "SELECT id, name FROM clinic_departments WHERE id = ? AND status = 'ACTIVE'",
            (rs, rowNum) -> new DepartmentIdentity(rs.getString("id"), rs.getString("name")),
            departmentId
        );
        if (departments.isEmpty()) throw badRequest("目标科室不存在或已停用");
        DepartmentIdentity department = departments.get(0);
        String previousId = text(encounter, "owningDepartmentId");
        String previousName = text(encounter, "owningDepartmentNameSnapshot");
        if (previousId.equals(department.id())) return toMap(workspace(encounterId, user));
        Integer activeConsumptionCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM inventory_consumption_events e
            WHERE e.encounter_id = ? AND e.status = 'succeeded' AND e.event_kind = 'CONSUMPTION'
              AND NOT EXISTS (
                SELECT 1 FROM inventory_consumption_events r
                WHERE r.reversal_of_event_id = e.id AND r.status = 'succeeded' AND r.event_kind = 'REVERSAL'
              )
            """,
            Integer.class,
            encounterId
        );
        if (activeConsumptionCount != null && activeConsumptionCount > 0) {
            throw conflict("该病历仍有未冲销的患者耗用，必须先退回相关阶段完成冲销，再修正归属科室");
        }
        jdbcTemplate.update(
            "UPDATE pre_ai_encounters SET owning_department_id = ?, owning_department_name_snapshot = ?, updated_at = ? WHERE id = ?",
            department.id(), department.name(), now(), encounterId
        );
        jdbcTemplate.update(
            "UPDATE pre_ai_care_encounters SET owning_department_id = ? WHERE clinical_encounter_id = ?",
            department.id(), encounterId
        );
        invalidateReview(encounterId, user, "病历归属科室发生修正：" + reason);
        auditDepartmentCorrection(encounterId, user, reason, previousId, previousName, department);
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> updateEncounterGrant(String encounterId, EncounterGrantRequest request, SessionUser user) {
        requireRole(user, "admin");
        requireEncounterAccess(encounterId, user);
        requireActiveEncounter(loadEncounter(encounterId));
        String accountId = safe(request == null ? null : request.accountId());
        String status = safe(request == null ? null : request.status()).toUpperCase(Locale.ROOT);
        String reason = safe(request == null ? null : request.reason());
        if (accountId.isBlank()) throw badRequest("请选择跨科协作账号");
        if (!Set.of("ACTIVE", "INACTIVE").contains(status)) throw badRequest("授权状态只能为 ACTIVE 或 INACTIVE");
        if (reason.isBlank()) throw badRequest("请填写跨科授权变更原因");
        Integer accountCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM clinic_accounts a
            WHERE a.id = ?
              AND EXISTS (
                SELECT 1 FROM clinic_account_departments ad
                WHERE ad.account_id = a.id AND ad.status = 'ACTIVE'
              )
            """,
            Integer.class,
            accountId
        );
        if (accountCount == null || accountCount == 0) throw badRequest("目标账号不存在、已停用或未关联有效科室");
        List<String> previous = jdbcTemplate.query(
            "SELECT status FROM pre_ai_encounter_department_grants WHERE account_id = ? AND encounter_id = ?",
            (rs, rowNum) -> rs.getString("status"), accountId, encounterId
        );
        String previousStatus = previous.isEmpty() ? "NONE" : previous.get(0);
        jdbcTemplate.update(
            """
            INSERT INTO pre_ai_encounter_department_grants (
              account_id, encounter_id, status, granted_by, reason, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE status = VALUES(status), granted_by = VALUES(granted_by),
              reason = VALUES(reason), updated_at = CURRENT_TIMESTAMP(6)
            """,
            accountId, encounterId, status, user.id(), reason
        );
        ObjectNode before = objectMapper.createObjectNode();
        before.put("accountId", accountId);
        before.put("status", previousStatus);
        ObjectNode after = objectMapper.createObjectNode();
        after.put("accountId", accountId);
        after.put("status", status);
        jdbcTemplate.update(
            """
            INSERT INTO pre_ai_audit_logs (
              id, encounter_id, action, stage_code, operator, operator_role, operator_id, operator_username, operator_department, detail,
              reason, before_json, after_json, created_at
            ) VALUES (?, ?, 'encounter.department-grant.update', NULL, ?, ?, ?, ?, ?, '管理员变更病历跨科协作授权', ?, CAST(? AS JSON), CAST(? AS JSON), ?)
            """,
            "preaudit-" + UUID.randomUUID(), encounterId, user.name(), user.role(), user.id(), user.username(), user.department(),
            reason, toJson(before), toJson(after), now()
        );
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> correctStage(String encounterId, String stageCode, StageCorrectionRequest request, SessionUser user) {
        requireEncounterAccess(encounterId, user);
        String stage = normalizeStage(stageCode);
        ObjectNode encounter = loadEncounter(encounterId);
        requireActiveEncounter(encounter);
        if (user == null || !navigationService.canCorrectStage(user.role(), stage)) {
            throw forbidden("当前岗位无权纠错" + stageLabel(stage));
        }
        String reason = safe(request == null ? "" : request.reason());
        if (reason.isBlank()) throw badRequest("纠错原因不能为空");
        ObjectNode current = loadStage(encounterId, stage);
        if (!Set.of("COMPLETED", "SKIPPED").contains(text(current, "status"))) {
            throw conflict("只有已完成或已跳过的阶段可以进入纠错模式");
        }
        ObjectNode before = safeObject(current.path("data")).deepCopy();
        ObjectNode data = sanitizeStageData(stage, request == null ? null : request.data());
        if ("SURGERY".equals(stage)) data.remove(List.of("physicianConfirmed", "physicianConfirmedBy", "physicianConfirmedAt"));
        validateStage(stage, data, encounter);
        if ("REGISTRATION".equals(stage)) {
            syncRegistrationCareType(encounterId, data, encounter);
            jdbcTemplate.update("UPDATE pre_ai_encounters SET patient_json = ?, updated_at = ? WHERE id = ?", toJson(data), now(), encounterId);
            String patientCaseId = text(encounter, "patientCaseId");
            if (!patientCaseId.isBlank()) {
                jdbcTemplate.update("UPDATE pre_ai_patient_cases SET patient_json = CAST(? AS JSON), updated_at = ? WHERE id = ?", toJson(data), now(), patientCaseId);
            }
        }
        if ("DOCTOR".equals(stage)) syncEncounterBranch(encounterId, data, encounter, user);
        String correctedStatus = "SURGERY".equals(stage) ? "PENDING_CONFIRMATION" : "COMPLETED";
        if ("SURGERY".equals(stage)) data.remove(List.of("physicianConfirmed", "physicianConfirmedBy", "physicianConfirmedAt"));
        updateStageVersioned(encounterId, stage, correctedStatus, data, reason, user, now(), request == null ? null : request.expectedVersion());
        if ("DOCTOR".equals(stage)) syncAdmissionProfile(encounterId, text(data, "finalRoute"), user);
        syncDiagnoses(encounterId, stage, data);
        long correctedVersion = loadStage(encounterId, stage).path("version").asLong();
        enqueueInventoryReversal(encounter, stage, correctedVersion, user, reason);
        markDownstreamForReconfirmation(encounterId, stage, user, "上游" + stageLabel(stage) + "发生纠错：" + reason);
        if (!"SURGERY".equals(stage)) enqueueInventoryConsumption(loadEncounter(encounterId), stage, correctedVersion, user);
        invalidateReview(encounterId, user, "已完成阶段纠错：" + reason);
        auditCorrection(encounterId, stage, user, reason, before, data);
        refreshProgress(encounterId);
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> completeStage(String encounterId, String stageCode, StageSaveRequest request, SessionUser user) {
        requireEncounterAccess(encounterId, user);
        String stage = normalizeStage(stageCode);
        if ("REVIEW".equals(stage)) throw badRequest("复核阶段请使用确认复核接口");
        ObjectNode encounter = loadEncounter(encounterId);
        requireStageEditor(encounter, stage, user);
        assertPreviousStages(encounterId, stage, encounter);
        ObjectNode current = loadStage(encounterId, stage);
        if ("COMPLETED".equals(text(current, "status"))) {
            throw conflict("该阶段已经完成；如需变更，请使用纠错模式并填写原因");
        }
        ObjectNode data = request != null && request.data() != null
            ? sanitizeStageData(stage, request.data())
            : safeObject(current.path("data"));
        if ("SURGERY".equals(stage)) data.remove(List.of("physicianConfirmed", "physicianConfirmedBy", "physicianConfirmedAt"));
        validateStage(stage, data, encounter);
        if ("REGISTRATION".equals(stage)) {
            syncRegistrationCareType(encounterId, data, encounter);
            jdbcTemplate.update("UPDATE pre_ai_encounters SET patient_json = ?, updated_at = ? WHERE id = ?", toJson(data), now(), encounterId);
            String patientCaseId = text(encounter, "patientCaseId");
            if (!patientCaseId.isBlank()) {
                jdbcTemplate.update("UPDATE pre_ai_patient_cases SET patient_json = CAST(? AS JSON), updated_at = ? WHERE id = ?", toJson(data), now(), patientCaseId);
            }
        }
        if ("DOCTOR".equals(stage)) syncEncounterBranch(encounterId, data, encounter, user);
        String completedStatus = "SURGERY".equals(stage) ? "PENDING_CONFIRMATION" : "COMPLETED";
        if ("SURGERY".equals(stage)) data.remove(List.of("physicianConfirmed", "physicianConfirmedBy", "physicianConfirmedAt"));
        updateStageVersioned(encounterId, stage, completedStatus, data, "", user, now(), request == null ? null : request.expectedVersion());
        if ("REGISTRATION".equals(stage)) applyRegistrationPurpose(encounterId, data, user);
        if ("DOCTOR".equals(stage)) syncAdmissionProfile(encounterId, text(data, "finalRoute"), user);
        syncDiagnoses(encounterId, stage, data);
        if (!"SURGERY".equals(stage)) {
            enqueueInventoryConsumption(loadEncounter(encounterId), stage, loadStage(encounterId, stage).path("version").asLong(), user);
        }
        Map<String, Object> queueHandoff = Set.of("INSPECTION", "RECEPTION").contains(stage)
            ? clinicQueueService.onClinicalStageCompleted(encounterId, stage, user)
            : Map.of();
        if ("DOCTOR".equals(stage)) applySurgeryBranch(encounterId, data, user);
        invalidateReview(encounterId, user, "阶段重新完成");
        audit(encounterId, "stage.complete", stage, user,
            "SURGERY".equals(stage) ? "手术护理事实已提交，等待手术医生确认" : "完成阶段并交接下一岗位");
        refreshProgress(encounterId);
        Map<String, Object> result = toMap(workspace(encounterId, user));
        if (!queueHandoff.isEmpty()) result.put("queueHandoff", queueHandoff);
        return result;
    }

    @Transactional
    public Map<String, Object> terminateReception(String encounterId, EncounterTerminationRequest request, SessionUser user) {
        requireEncounterAccess(encounterId, user);
        ObjectNode encounter = loadEncounter(encounterId);
        requireStageEditor(encounter, "RECEPTION", user);
        if ("CANCELLED".equals(text(encounter, "status"))) throw conflict("该病历已办理离院，不能重复终止");
        String reason = safe(request == null ? "" : request.reason());
        if (reason.isBlank()) throw badRequest("请填写患者离院原因");
        ObjectNode current = loadStage(encounterId, "RECEPTION");
        if ("COMPLETED".equals(text(current, "status"))) throw conflict("接诊已完成，不能再按未治疗离院终止");
        if (request != null && request.data() != null) {
            ObjectNode data = sanitizeStageData("RECEPTION", request.data());
            updateStageVersioned(encounterId, "RECEPTION", text(current, "status", "DRAFT"), data,
                "患者离院未治疗：" + reason, user, "", request.expectedVersion());
        }
        clinicQueueService.leaveEncounterFromReception(encounterId, reason, user);
        jdbcTemplate.update("UPDATE pre_ai_care_encounters SET status = 'CANCELLED', ended_at = CURRENT_TIMESTAMP(3) WHERE clinical_encounter_id = ? AND status = 'ACTIVE'", encounterId);
        jdbcTemplate.update("UPDATE pre_ai_encounters SET status = 'CANCELLED', current_stage = 'RECEPTION', updated_at = ? WHERE id = ?", now(), encounterId);
        audit(encounterId, "encounter.terminate", "RECEPTION", user, "患者离院未治疗：" + reason);
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> confirmSurgery(String encounterId, VersionRequest request, SessionUser user) {
        requireEncounterAccess(encounterId, user);
        ObjectNode encounter = loadEncounter(encounterId);
        requireActiveEncounter(encounter);
        if (user == null || !navigationService.hasCapability(user, "preai:surgery:confirm")) {
            throw forbidden("当前岗位无权执行手术医生确认");
        }
        if (hasConfiguredDuty(encounter, Set.of("SURGEON", "ATTENDING_DOCTOR"))
            && !hasAssignedDuty(encounter, user, Set.of("SURGEON", "ATTENDING_DOCTOR"))) {
            throw forbidden("本病例已指定手术医生，仅被指定医生可以确认");
        }
        ObjectNode current = loadStage(encounterId, "SURGERY");
        if (!"PENDING_CONFIRMATION".equals(text(current, "status"))) {
            throw conflict("请先由手术护理岗位完成事实登记并提交");
        }
        ObjectNode data = safeObject(current.path("data"));
        normalizeSurgeryConfirmation(encounter, data, user);
        validateStage("SURGERY", data, encounter);
        updateStageVersioned(encounterId, "SURGERY", "COMPLETED", data, "", user, now(),
            request == null ? null : request.expectedVersion());
        long completionVersion = loadStage(encounterId, "SURGERY").path("version").asLong();
        enqueueInventoryConsumption(loadEncounter(encounterId), "SURGERY", completionVersion, user);
        invalidateReview(encounterId, user, "手术医生完成独立确认");
        audit(encounterId, "surgery.physician.confirm", "SURGERY", user, "手术医生核对护理事实并完成医学确认");
        refreshProgress(encounterId);
        return toMap(workspace(encounterId, user));
    }

    public Map<String, Object> responsibilityTimeline(String encounterId, int offset, int limit, SessionUser user) {
        requireEncounterAccess(encounterId, user);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        Integer total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pre_ai_audit_logs WHERE encounter_id = ?", Integer.class, encounterId
        );
        ArrayNode events = objectMapper.createArrayNode();
        jdbcTemplate.query(
            "SELECT * FROM pre_ai_audit_logs WHERE encounter_id = ? ORDER BY timeline_sequence ASC LIMIT ? OFFSET ?",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> events.add(readAudit(rs)),
            encounterId, safeLimit, safeOffset
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("encounterId", encounterId);
        result.put("total", total == null ? 0 : total);
        result.put("events", objectMapper.convertValue(events, new TypeReference<List<Map<String, Object>>>() {}));
        return result;
    }

    public Map<String, Object> admissionProfile(String encounterId, SessionUser user) {
        requireReadRole(user);
        requireEncounterAccess(encounterId, user);
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode profile = findAdmissionProfile(encounterId);
        if (profile == null) result.putNull("profile");
        else result.set("profile", profile);
        return toMap(result);
    }

    @Transactional
    public Map<String, Object> saveAdmissionProfile(String encounterId, AdmissionProfileSaveRequest request, SessionUser user) {
        requireEncounterAccess(encounterId, user);
        requireAdmissionEditor(user);
        ObjectNode encounter = loadEncounter(encounterId);
        requireActiveEncounter(encounter);
        if (!"INPATIENT".equals(text(encounter, "route"))) throw conflict("仅医生确认住院后可填写住院补录资料");
        ObjectNode profile = findAdmissionProfile(encounterId);
        if (profile == null || "CANCELLED".equals(text(profile, "status"))) throw conflict("当前没有可填写的住院补录任务");
        Integer expectedVersion = request == null ? null : request.expectedVersion();
        if (expectedVersion != null && expectedVersion != profile.path("version").asInt()) throw conflict("住院补录资料已被其他终端更新，请刷新后重试");
        ObjectNode data = sanitizeAdmissionProfile(request == null ? null : request.data());
        boolean complete = request != null && request.complete();
        String status = complete ? "COMPLETED" : "PENDING";
        String timestamp = now();
        jdbcTemplate.update(
            "UPDATE pre_ai_admission_profiles SET status = ?, data_json = CAST(? AS JSON), version = version + 1, updated_at = ?, updated_by = ?, completed_at = ?, completed_by = ? WHERE encounter_id = ?",
            status, toJson(data), timestamp, user.name(), complete ? timestamp : null, complete ? user.name() : "", encounterId
        );
        audit(encounterId, complete ? "admission-profile.complete" : "admission-profile.save", "ADMISSION", user,
            complete ? "护士完成住院资料补录" : "护士保存住院资料补录草稿");
        return toMap(workspace(encounterId, user));
    }

    @Transactional
    public Map<String, Object> returnStage(String encounterId, String stageCode, ReturnStageRequest request, SessionUser user) {
        requireEncounterAccess(encounterId, user);
        ObjectNode encounter = loadEncounter(encounterId);
        requireActiveEncounter(encounter);
        requireReviewer(encounter, user);
        String stage = normalizeStage(stageCode);
        if ("REVIEW".equals(stage)) throw badRequest("不能退回复核阶段");
        String reason = safe(request == null ? "" : request.reason());
        if (reason.isBlank()) throw badRequest("退回原因不能为空");
        ObjectNode current = loadStage(encounterId, stage);
        if (!Set.of("COMPLETED", "SKIPPED").contains(text(current, "status"))) throw conflict("只有已完成或已跳过的阶段可以退回");
        updateStageVersioned(encounterId, stage, "RETURNED", safeObject(current.path("data")), reason, user, "", request == null ? null : request.expectedVersion());
        enqueueInventoryReversal(encounter, stage, loadStage(encounterId, stage).path("version").asLong(), user, reason);
        markDownstreamForReconfirmation(encounterId, stage, user, "上游" + stageLabel(stage) + "已退回：" + reason);
        invalidateReview(encounterId, user, "医生退回阶段：" + reason);
        audit(encounterId, "stage.return", stage, user, reason);
        refreshProgress(encounterId);
        return toMap(workspace(encounterId, user));
    }

    private void markDownstreamForReconfirmation(String encounterId, String changedStage, SessionUser user, String reason) {
        int changedIndex = STAGE_ORDER.indexOf(changedStage);
        if (changedIndex < 0) return;
        ObjectNode encounter = loadEncounter(encounterId);
        for (int index = changedIndex + 1; index < STAGE_ORDER.size(); index++) {
            String downstreamStage = STAGE_ORDER.get(index);
            if ("REVIEW".equals(downstreamStage)) continue;
            ObjectNode downstream = loadStage(encounterId, downstreamStage);
            if (!Set.of("COMPLETED", "PENDING_CONFIRMATION").contains(text(downstream, "status"))) continue;
            int currentVersion = downstream.path("version").asInt();
            updateStageVersioned(
                encounterId,
                downstreamStage,
                "RETURNED",
                safeObject(downstream.path("data")),
                reason,
                user,
                "",
                currentVersion
            );
            long returnedVersion = loadStage(encounterId, downstreamStage).path("version").asLong();
            enqueueInventoryReversal(encounter, downstreamStage, returnedVersion, user, reason);
            audit(encounterId, "stage.reconfirmation.required", downstreamStage, user, reason);
        }
    }

    @Transactional
    public Map<String, Object> createAuxiliaryTask(String encounterId, AuxiliaryTaskRequest request, SessionUser user) {
        requireEncounterAccess(encounterId, user);
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
        requireEncounterAccess(encounterId, user);
        ObjectNode encounter = loadEncounter(encounterId);
        ObjectNode task = loadAuxiliaryTask(encounterId, taskId);
        requireAuxEditor(encounter, task, user);
        if ("COMPLETED".equals(text(task, "status")) && !"doctor".equals(user.role())) {
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
        requireEncounterAccess(encounterId, user);
        ObjectNode encounter = loadEncounter(encounterId);
        requireActiveEncounter(encounter);
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
        requireEncounterAccess(encounterId, user);
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
        requireEncounterAccess(encounterId, user);
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
        requireEncounterAccess(encounterId, user);
        ObjectNode encounter = loadEncounter(encounterId);
        requireAuxTaskEditor(encounter, "LAB", user);
        ObjectNode task = ensureLabTask(encounterId, user.name());
        if ("COMPLETED".equals(text(task, "status")) && !"doctor".equals(user.role())) {
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
        requireEncounterAccess(encounterId, user);
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
        requireEncounterAccess(encounterId, user);
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
        requireEncounterAccess(encounterId, user);
        ObjectNode attachment = loadAttachment(encounterId, attachmentId);
        if (!"ACTIVE".equals(text(attachment, "status"))) throw notFound("附件不存在");
        return new AttachmentDownload(fileService.load(text(attachment, "storagePath")), text(attachment, "fileName", "attachment"), text(attachment, "mimeType", "application/octet-stream"));
    }

    public Map<String, Object> reviewPreview(String encounterId, SessionUser user) {
        requireEncounterAccess(encounterId, user);
        ObjectNode encounter = loadEncounter(encounterId);
        requireReviewer(encounter, user);
        ObjectNode workspace = workspace(encounterId, user);
        ArrayNode blockers = reviewBlockers(workspace);
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode maskedPreview = privacyService.maskWorkspace(workspace);
        ObjectNode documentView = privacyService.buildDocumentView(maskedPreview);
        result.set("workspace", workspace);
        result.set("maskedPreview", maskedPreview);
        result.set("blockers", blockers);
        result.set("labSummary", labReviewSummary(maskedPreview));
        result.put("templateVersion", privacyService.templateVersion());
        result.put("effectiveFieldCount", documentView.path("effectiveFieldCount").asInt());
        result.set("documentSections", documentView.path("sections"));
        result.put("ready", blockers.isEmpty());
        return toMap(result);
    }

    @Transactional
    public Map<String, Object> confirmReview(String encounterId, ReviewConfirmRequest request, SessionUser user) {
        requireEncounterAccess(encounterId, user);
        ObjectNode encounter = loadEncounter(encounterId);
        requireActiveEncounter(encounter);
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
            UPDATE pre_ai_encounters
            SET status = 'REVIEWED', current_stage = 'REVIEW', reviewed_at = ?, reviewed_by = ?, reviewed_by_role = ?,
                reviewed_facts_revision = facts_revision, updated_at = ?
            WHERE id = ?
            """, now(), user.name(), user.role(), now(), encounterId);
        String auditDetail = criticalCount > 0 ? "医生确认全部前置事实，并已阅 " + criticalCount + " 项危急值" : "医生确认全部前置事实";
        audit(encounterId, "review.confirm", "REVIEW", user, auditDetail);
        return toMap(workspace(encounterId, user));
    }

    private void enqueueInventoryConsumption(ObjectNode encounter, String stage, long completionVersion, SessionUser user) {
        if (!INVENTORY_CONSUMPTION_STAGES.contains(stage)) return;
        inventoryStageConsumptionService.enqueueStageCompletion(
            text(encounter, "id"),
            stage,
            completionVersion,
            text(encounter, "owningDepartmentId"),
            text(encounter, "caseToken"),
            text(encounter, "inventoryCareType", "outpatient"),
            parseVisitDate(text(encounter.path("patient"), "visitDate")),
            user.name()
        );
    }

    private void enqueueInventoryReversal(
        ObjectNode encounter,
        String stage,
        long completionVersion,
        SessionUser user,
        String reason
    ) {
        if (!INVENTORY_CONSUMPTION_STAGES.contains(stage)) return;
        inventoryStageConsumptionService.enqueueStageReversal(
            text(encounter, "id"),
            stage,
            completionVersion,
            text(encounter, "owningDepartmentId"),
            user.name(),
            reason
        );
    }

    private LocalDate parseVisitDate(String value) {
        String normalized = safe(value);
        if (normalized.length() >= 10) normalized = normalized.substring(0, 10);
        try {
            return normalized.isBlank() ? LocalDate.now() : LocalDate.parse(normalized);
        } catch (RuntimeException ignored) {
            return LocalDate.now();
        }
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
        requireEncounterAccess(encounterId, user);
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
                  id, encounter_id, version, status, template_version, case_token, file_name, file_path, source_snapshot, masked_snapshot,
                  generated_by, generated_by_role, generated_at
                ) VALUES (?, ?, ?, 'GENERATED', ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?, ?)
                """, exportId, encounterId, version, privacyService.templateVersion(), caseToken, fileName, target.toString(), toJson(workspace), toJson(masked), user.name(), user.role(), now());

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
        requireEncounterAccess(encounterId, user);
        loadEncounter(encounterId);
        List<Map<String, Object>> rows = jdbcTemplate.query("SELECT * FROM pre_ai_exports WHERE encounter_id = ? ORDER BY version DESC", (rs, rowNum) -> toMap(readExport(rs)), encounterId);
        return Map.of("versions", rows);
    }

    public ExportDownload downloadExport(String encounterId, String exportId, SessionUser user) {
        requireEncounterAccess(encounterId, user);
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
        return createEncounterInternal(patient, sourcePatientId, patientCaseId, visitNo, followUpOfEncounterId, legacyReference, visitMeta, user, "");
    }

    private ObjectNode createEncounterInternal(
        ObjectNode patient,
        String sourcePatientId,
        String patientCaseId,
        int visitNo,
        String followUpOfEncounterId,
        ObjectNode legacyReference,
        ObjectNode visitMeta,
        SessionUser user,
        String registrationRequestId
    ) {
        String id = "preai-" + UUID.randomUUID();
        String caseToken = nextCaseToken();
        String timestamp = now();
        DepartmentIdentity owningDepartment = resolveOwningDepartment(user, text(patient, "owningDepartmentId"));
        String inventoryCareType = normalizeInventoryCareType(text(patient, "inventoryCareType", "outpatient"));
        String careSituationTags = careSituationTags(inventoryCareType, text(patient, "careSituationDescription"));
        patient.put("inventoryCareType", inventoryCareType);
        patient.put("careSituationTags", careSituationTags);
        jdbcTemplate.update("""
            INSERT INTO pre_ai_encounters (
              id, source_patient_id, patient_case_id, owning_department_id, owning_department_name_snapshot,
              visit_no, follow_up_of_encounter_id, case_token, route, inventory_care_type, care_situation_tags, care_type_locked_at, treatment_path,
              status, current_stage, patient_json, visit_meta_json, legacy_reference_json, duty_assignments_json, reviewed_at, reviewed_by,
              reviewed_by_role, registration_request_id, created_at, updated_at, created_by, created_by_role
            ) VALUES (?, ?, ?, NULLIF(?, ''), NULLIF(?, ''), ?, ?, ?, '', ?, NULLIF(?, ''), ?, '', 'IN_PROGRESS', 'REGISTRATION', CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), JSON_ARRAY(), '', '', '', NULLIF(?, ''), ?, ?, ?, ?)
            """, id, sourcePatientId, patientCaseId, owningDepartment.id(), owningDepartment.name(), visitNo, followUpOfEncounterId, caseToken,
            inventoryCareType, careSituationTags, timestamp, toJson(patient), toJson(visitMeta),
            toJson(legacyReference), safe(registrationRequestId), timestamp, timestamp, user.name(), user.role());
        jdbcTemplate.update(
            """
            INSERT INTO pre_ai_care_encounters (
              id, clinical_encounter_id, source_care_encounter_id, care_type, owning_department_id,
              case_token, visit_date, status, started_at, created_by
            ) VALUES (?, ?, NULL, ?, ?, ?, ?, 'ACTIVE', ?, ?)
            """,
            "care-" + UUID.randomUUID(), id, inventoryCareType, owningDepartment.id(), caseToken,
            parseVisitDate(text(patient, "visitDate")), timestamp, user.name()
        );
        for (String stage : STAGE_ORDER) {
            ObjectNode data = "REGISTRATION".equals(stage) ? patient : objectMapper.createObjectNode();
            upsertStage(id, stage, "DRAFT", 0, data, "", user, "");
        }
        ensureLabTask(id, user.name());
        audit(id, "encounter.create", "REGISTRATION", user, sourcePatientId.isBlank() ? "创建前置病历就诊" : "从旧患者创建前置病历就诊");
        return workspace(id, user);
    }

    private DepartmentIdentity resolveOwningDepartment(SessionUser user, String requestedDepartmentId) {
        if (user == null) throw forbidden("登录已失效，无法确定病历归属科室");
        String requested = safe(requestedDepartmentId);
        List<DepartmentIdentity> rows = jdbcTemplate.query(
            """
            SELECT d.id, d.name
            FROM clinic_account_departments ad
            JOIN clinic_departments d ON d.id = ad.department_id
            WHERE ad.account_id = ? AND ad.status = 'ACTIVE' AND d.status = 'ACTIVE'
              AND d.id <> 'dept-unassigned'
              AND (? = '' OR d.id = ?)
            ORDER BY CASE WHEN d.id = ? THEN 0 WHEN ad.is_primary = TRUE THEN 1 ELSE 2 END, d.name, d.id
            LIMIT 1
            """,
            (rs, rowNum) -> new DepartmentIdentity(rs.getString("id"), rs.getString("name")),
            user.id(), requested, requested, safe(user.activeDepartmentId())
        );
        if (!rows.isEmpty()) return rows.get(0);
        if (!requested.isBlank()) throw forbidden("所选科室不在当前账号授权范围内");
        throw forbidden("当前账号未配置可用的活动科室");
    }

    private String createClinicPatientArchive(ObjectNode patient) {
        String id = "patient-" + UUID.randomUUID();
        String timestamp = now();
        String patientName = text(patient, "patientName", "未命名患者");
        String visitNo = firstNonBlank(
            text(patient, "visitNo"),
            text(patient, "inpatientNo"),
            text(patient, "admissionNo"),
            text(patient, "medicalRecordNo")
        );
        ObjectNode archive = objectMapper.createObjectNode();
        archive.put("id", id);
        archive.put("name", patientName);
        archive.put("visitNo", visitNo);
        archive.put("visitDate", text(patient, "visitDate"));
        archive.put("visitType", "前置病历");
        archive.put("doctor", "");
        archive.put("currentStage", "前置病历登记");
        archive.put("completedCount", 1);
        archive.put("progressPercent", 0);
        archive.put("status", "前置病历流转中");
        archive.put("riskType", "info");
        archive.put("createdAt", timestamp);
        archive.put("updatedAt", timestamp);
        archive.put("encounterCount", 1);
        archive.put("sourceType", "preai");
        archive.put("phone", text(patient, "phone"));
        archive.put("gender", text(patient, "gender"));
        archive.put("identityType", text(patient, "identityType"));
        archive.put("identityNumber", text(patient, "identityNumber"));
        archive.set("encounterHistory", objectMapper.createArrayNode());
        jdbcTemplate.update("""
            INSERT INTO clinic_patients (
              id, name, visit_no, visit_date, visit_type, doctor, current_stage, completed_count,
              progress_percent, status, risk_type, created_at, updated_at, encounter_count,
              encounter_history_json, raw_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON))
            """,
            id, patientName, visitNo, text(patient, "visitDate"), "前置病历", "", "前置病历登记", 1,
            0, "前置病历流转中", "info", timestamp, timestamp, 1,
            toJson(archive.path("encounterHistory")), toJson(archive)
        );
        return id;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!safe(value).isBlank()) return safe(value);
        }
        return "";
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
        requireEncounterAccess(encounterId, user);
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode encounter = loadEncounter(encounterId);
        result.set("encounter", encounter);
        result.set("dutyAssignments", encounter.path("dutyAssignments").deepCopy());
        ObjectNode admissionProfile = findAdmissionProfile(encounterId);
        if (admissionProfile == null) result.putNull("admissionProfile");
        else result.set("admissionProfile", admissionProfile);
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
        boolean surgeon = user != null && "doctor".equals(user.role())
            && (!hasConfiguredDuty(encounter, Set.of("SURGEON", "ATTENDING_DOCTOR"))
                || hasAssignedDuty(encounter, user, Set.of("SURGEON", "ATTENDING_DOCTOR")));
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
                required(data, missing, "visitPurpose", "来院目的");
                required(data, missing, "registrationChiefComplaint", "登记主诉");
                required(data, missing, "inventoryCareType", "耗材统计口径（门诊/住院）");
                if (!text(data, "inventoryCareType").isBlank()) normalizeInventoryCareType(text(data, "inventoryCareType"));
                if (!text(data, "visitPurpose").isBlank()) normalizeEnum(text(data, "visitPurpose"), Set.of("GENERAL", "ENDOSCOPY_DIRECT"), "来院目的");
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
                required(data, missing, "preoperativeAssessment", "术前评估");
                required(data, missing, "consultationOpinion", "会诊意见");
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
            case "RECEPTION" -> Map.of(
                "presentIllnessOverride", List.of("presentIllnessConfirmed", "现病史"),
                "physicalExamOverride", List.of("physicalExamConfirmed", "体格检查")
            );
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
        ObjectNode encounter = safeObject(workspace.path("encounter"));
        Map<String, String> statuses = new LinkedHashMap<>();
        for (JsonNode stage : workspace.path("stages")) statuses.put(text(stage, "stageCode"), text(stage, "status"));
        for (String stage : effectiveStageOrder(encounter)) {
            if ("REVIEW".equals(stage)) continue;
            if (!"COMPLETED".equals(statuses.get(stage))) blockers.add(stageLabel(stage) + "未完成");
        }
        for (JsonNode task : workspace.path("auxiliaryTasks")) {
            if (task.path("requiredBeforeExport").asBoolean(false) && !"COMPLETED".equals(text(task, "status"))) {
                blockers.add("必需辅助检查未完成：" + auxiliaryLabel(text(task, "taskType")) + optionalTitle(text(task, "title")));
            }
        }
        return blockers;
    }

    private void assertPreviousStages(String encounterId, String stage, ObjectNode encounter) {
        List<String> stages = effectiveStageOrder(encounter);
        int index = stages.indexOf(stage);
        if (index <= 0) return;
        for (int i = 0; i < index; i++) {
            String previous = stages.get(i);
            if ("REVIEW".equals(previous)) continue;
            String status = text(loadStage(encounterId, previous), "status");
            if (!Set.of("COMPLETED", "SKIPPED").contains(status)) throw conflict(stageLabel(previous) + "尚未完成");
        }
    }

    private void syncEncounterBranch(String encounterId, ObjectNode data, ObjectNode encounter, SessionUser user) {
        String route = normalizeEnum(text(data, "finalRoute"), Set.of("OUTPATIENT", "INPATIENT"), "最终就诊分支");
        String path = normalizeEnum(text(data, "treatmentPath"), Set.of("CONSERVATIVE", "SURGICAL"), "治疗方式");
        String suggested = text(loadStage(encounterId, "RECEPTION").path("data"), "dispositionSuggestion");
        String existing = text(encounter, "route");
        if (!suggested.isBlank() && !route.equals(suggested) && text(data, "routeOverrideReason").isBlank()) {
            throw badRequest("医生更改接诊室建议分支时必须填写更正原因");
        }
        if ("INPATIENT".equals(route) && "outpatient".equals(text(encounter, "inventoryCareType"))) {
            transitionToInpatientCare(encounterId, encounter, user);
        } else if ("OUTPATIENT".equals(route) && "inpatient".equals(text(encounter, "inventoryCareType"))) {
            throw conflict("该就诊已进入住院耗材口径，不能直接改回门诊；请先完成住院阶段冲销并由管理员处理");
        }
        jdbcTemplate.update("UPDATE pre_ai_encounters SET route = ?, treatment_path = ?, updated_at = ? WHERE id = ?", route, path, now(), encounterId);
        if (!existing.isBlank() && !existing.equals(route)) {
            // Detailed reason remains in the doctor-stage snapshot and audit trail.
        }
    }

    private void applyRegistrationPurpose(String encounterId, ObjectNode patient, SessionUser user) {
        if (!"ENDOSCOPY_DIRECT".equals(text(patient, "visitPurpose"))) return;
        ObjectNode inspection = loadStage(encounterId, "INSPECTION");
        if (!Set.of("DRAFT", "RETURNED").contains(text(inspection, "status"))) return;
        upsertStage(
            encounterId,
            "INSPECTION",
            "SKIPPED",
            inspection.path("version").asInt(0) + 1,
            objectMapper.createObjectNode(),
            "登记选择胃肠镜检查/咨询，直达接诊室",
            user,
            now()
        );
        audit(encounterId, "registration.endoscopy-direct", "INSPECTION", user, "检查室阶段已跳过，患者直接进入接诊室");
    }

    private void syncAdmissionProfile(String encounterId, String route, SessionUser user) {
        String timestamp = now();
        if ("INPATIENT".equals(route)) {
            jdbcTemplate.update(
                "INSERT INTO pre_ai_admission_profiles (encounter_id, status, data_json, version, created_at, created_by, updated_at, updated_by, completed_at, completed_by) VALUES (?, 'PENDING', JSON_OBJECT(), 0, ?, ?, ?, ?, NULL, '') ON DUPLICATE KEY UPDATE status = IF(status = 'CANCELLED', 'PENDING', status), updated_at = VALUES(updated_at), updated_by = VALUES(updated_by)",
                encounterId, timestamp, user.name(), timestamp, user.name()
            );
            audit(encounterId, "admission-profile.open", "ADMISSION", user, "医生确认住院，已创建或恢复护士住院资料补录任务");
            return;
        }
        int changed = jdbcTemplate.update(
            "UPDATE pre_ai_admission_profiles SET status = 'CANCELLED', updated_at = ?, updated_by = ? WHERE encounter_id = ? AND status <> 'CANCELLED'",
            timestamp, user.name(), encounterId
        );
        if (changed > 0) audit(encounterId, "admission-profile.close", "ADMISSION", user, "医生改为门诊，住院资料补录任务已关闭并保留记录");
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
        for (String stage : effectiveStageOrder(encounter)) {
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

    private ObjectNode findAdmissionProfile(String encounterId) {
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT * FROM pre_ai_admission_profiles WHERE encounter_id = ? LIMIT 1",
            (rs, rowNum) -> readAdmissionProfile(rs),
            encounterId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ObjectNode sanitizeAdmissionProfile(Map<String, Object> values) {
        return sanitizeObject(values, ADMISSION_PROFILE_FIELDS);
    }

    private void requireAdmissionEditor(SessionUser user) {
        if (user == null || !Set.of("admin", "nurse", "nursing").contains(user.role())) {
            throw forbidden("仅护士或管理员可填写住院补录资料");
        }
    }

    private void invalidateReview(String encounterId, SessionUser user, String reason) {
        ObjectNode encounter = loadEncounter(encounterId);
        String timestamp = now();
        jdbcTemplate.update(
            "UPDATE pre_ai_encounters SET facts_revision = facts_revision + 1, updated_at = ? WHERE id = ?",
            timestamp,
            encounterId
        );
        jdbcTemplate.update(
            "UPDATE pre_ai_exports SET status = 'INVALIDATED' WHERE encounter_id = ? AND status <> 'INVALIDATED'",
            encounterId
        );
        jdbcTemplate.update(
            """
            UPDATE clinic_generated_medical_records
            SET validity_status = 'STALE', invalidated_at = ?, invalidated_reason = ?,
                raw_json = JSON_SET(raw_json, '$.validityStatus', 'STALE', '$.invalidatedAt', ?, '$.invalidatedReason', ?)
            WHERE source_encounter_id = ? AND validity_status = 'CURRENT' AND status <> 'voided'
            """,
            timestamp, reason, timestamp, reason, encounterId
        );
        if (!Set.of("REVIEWED", "EXPORTED").contains(text(encounter, "status"))) return;
        ObjectNode review = loadStage(encounterId, "REVIEW");
        upsertStage(encounterId, "REVIEW", "RETURNED", review.path("version").asInt(0) + 1, safeObject(review.path("data")), reason, user, "");
        jdbcTemplate.update("UPDATE pre_ai_encounters SET status = 'IN_PROGRESS', reviewed_at = '', reviewed_by = '', reviewed_by_role = '', reviewed_facts_revision = NULL, updated_at = ? WHERE id = ?", timestamp, encounterId);
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
              encounter_id, stage_code, status, version, data_json, returned_reason, requires_reconfirmation,
              submitted_by, submitted_by_role, completed_at, updated_at
            ) VALUES (?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE status = VALUES(status), version = VALUES(version), data_json = VALUES(data_json),
              returned_reason = VALUES(returned_reason), requires_reconfirmation = VALUES(requires_reconfirmation),
              submitted_by = VALUES(submitted_by), submitted_by_role = VALUES(submitted_by_role),
              completed_at = VALUES(completed_at), updated_at = VALUES(updated_at)
            """, encounterId, stage, status, version, toJson(data), reason, "RETURNED".equals(status),
            user.name(), user.role(), completedAt, now());
    }

    private void updateStageVersioned(String encounterId, String stage, String status, ObjectNode data, String reason,
                                      SessionUser user, String completedAt, Integer expectedVersion) {
        int expected = requireExpectedVersion(expectedVersion, "阶段记录");
        recordStageHistory(encounterId, stage, user, "BEFORE_" + status, reason);
        int changed = jdbcTemplate.update("""
            UPDATE pre_ai_stage_submissions
            SET status = ?, version = version + 1, data_json = CAST(? AS JSON), returned_reason = ?,
                requires_reconfirmation = ?, submitted_by = ?, submitted_by_role = ?, completed_at = ?, updated_at = ?
            WHERE encounter_id = ? AND stage_code = ? AND version = ?
            """, status, toJson(data), reason, "RETURNED".equals(status), user.name(), user.role(), completedAt, now(), encounterId, stage, expected);
        if (changed != 1) throwVersionConflict("阶段记录", stage, expectedVersion, loadStage(encounterId, stage));
    }

    private void recordStageHistory(String encounterId, String stage, SessionUser user, String action, String reason) {
        jdbcTemplate.update(
            """
            INSERT IGNORE INTO pre_ai_stage_revision_history (
              id, encounter_id, stage_code, version, status, data_json, returned_reason,
              requires_reconfirmation, changed_by, changed_by_role, change_action, change_reason
            )
            SELECT ?, encounter_id, stage_code, version, status, data_json, returned_reason,
                   requires_reconfirmation, ?, ?, ?, ?
            FROM pre_ai_stage_submissions WHERE encounter_id = ? AND stage_code = ?
            """,
            "stage-history-" + UUID.randomUUID(), user == null ? "system" : user.name(),
            user == null ? "system" : user.role(), action, safe(reason), encounterId, stage
        );
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
        row.put("owningDepartmentId", safe(rs.getString("owning_department_id")));
        row.put("owningDepartmentName", safe(rs.getString("owning_department_name_snapshot")));
        row.put("visitNo", rs.getInt("visit_no"));
        row.put("followUpOfEncounterId", safe(rs.getString("follow_up_of_encounter_id")));
        row.put("careTransitionFromEncounterId", safe(rs.getString("care_transition_from_encounter_id")));
        row.put("caseToken", rs.getString("case_token"));
        row.put("route", safe(rs.getString("route")));
        row.put("inventoryCareType", safe(rs.getString("inventory_care_type")));
        row.put("careSituationTags", safe(rs.getString("care_situation_tags")));
        row.put("careTypeLockedAt", safe(rs.getString("care_type_locked_at")));
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
        row.put("factsRevision", rs.getLong("facts_revision"));
        Object reviewedFactsRevision = rs.getObject("reviewed_facts_revision");
        if (reviewedFactsRevision != null) row.put("reviewedFactsRevision", rs.getLong("reviewed_facts_revision"));
        row.put("registrationRequestId", safe(rs.getString("registration_request_id")));
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
        enrichEncounterWorkflow(summary);
        return summary;
    }

    private void enrichEncounterWorkflow(ObjectNode summary) {
        ObjectNode rawStatuses = safeObject(summary.path("stageStatuses"));
        ObjectNode effectiveStatuses = rawStatuses.deepCopy();
        ArrayNode skippedStages = summary.putArray("skippedStages");
        List<String> stages = effectiveStageOrder(summary);
        Set<String> effectiveStages = new LinkedHashSet<>(stages);
        for (String stage : STAGE_ORDER) {
            if (!effectiveStages.contains(stage)) {
                effectiveStatuses.put(stage, "SKIPPED");
                skippedStages.add(stage);
            } else if ("SKIPPED".equals(text(rawStatuses, stage))) {
                skippedStages.add(stage);
            }
        }
        String current = "REVIEW";
        for (String stage : stages) {
            if (!Set.of("COMPLETED", "SKIPPED").contains(text(effectiveStatuses, stage, "DRAFT"))) {
                current = stage;
                break;
            }
        }
        summary.put("normalizedCareType", normalizedCareType(summary));
        summary.put("effectiveCurrentStage", current);
        summary.put("nextOwner", stageLabel(current));
        summary.set("effectiveStageStatuses", effectiveStatuses);
    }

    private ObjectNode readStage(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("encounterId", rs.getString("encounter_id"));
        row.put("stageCode", rs.getString("stage_code"));
        row.put("status", rs.getString("status"));
        row.put("version", rs.getInt("version"));
        row.set("data", readObject(rs.getString("data_json")));
        row.put("returnedReason", safe(rs.getString("returned_reason")));
        row.put("requiresReconfirmation", rs.getBoolean("requires_reconfirmation"));
        row.put("submittedBy", safe(rs.getString("submitted_by")));
        row.put("submittedByRole", safe(rs.getString("submitted_by_role")));
        row.put("completedAt", safe(rs.getString("completed_at")));
        row.put("updatedAt", rs.getString("updated_at"));
        return row;
    }

    private ObjectNode readAdmissionProfile(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("encounterId", rs.getString("encounter_id"));
        row.put("status", rs.getString("status"));
        row.set("data", readObject(rs.getString("data_json")));
        row.put("version", rs.getInt("version"));
        row.put("createdAt", rs.getString("created_at"));
        row.put("createdBy", safe(rs.getString("created_by")));
        row.put("updatedAt", rs.getString("updated_at"));
        row.put("updatedBy", safe(rs.getString("updated_by")));
        row.put("completedAt", safe(rs.getString("completed_at")));
        row.put("completedBy", safe(rs.getString("completed_by")));
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
        row.put("timelineSequence", rs.getLong("timeline_sequence"));
        row.put("action", rs.getString("action"));
        row.put("stageCode", safe(rs.getString("stage_code")));
        row.put("operator", safe(rs.getString("operator")));
        row.put("operatorRole", safe(rs.getString("operator_role")));
        row.put("operatorId", safe(rs.getString("operator_id")));
        row.put("operatorUsername", safe(rs.getString("operator_username")));
        row.put("operatorDepartment", safe(rs.getString("operator_department")));
        row.put("detail", safe(rs.getString("detail")));
        row.put("reason", safe(rs.getString("reason")));
        String beforeJson = rs.getString("before_json");
        String afterJson = rs.getString("after_json");
        if (beforeJson != null && !beforeJson.isBlank()) row.set("before", readObject(beforeJson));
        if (afterJson != null && !afterJson.isBlank()) row.set("after", readObject(afterJson));
        row.put("createdAt", rs.getString("created_at"));
        row.put("occurredAt", rs.getString("created_at"));
        row.put("submittedAt", rs.getString("created_at"));
        return row;
    }

    private ObjectNode readExport(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("encounterId", rs.getString("encounter_id"));
        row.put("version", rs.getInt("version"));
        row.put("status", rs.getString("status"));
        String templateVersion = safe(rs.getString("template_version"));
        row.put("templateVersion", templateVersion.isBlank() ? "legacy-pre-ai-export-v1" : templateVersion);
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
        jdbcTemplate.update("INSERT INTO pre_ai_audit_logs (id, encounter_id, action, stage_code, operator, operator_role, operator_id, operator_username, operator_department, detail, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "preaudit-" + UUID.randomUUID(), encounterId, action, safe(stage), user.name(), user.role(), user.id(), user.username(), user.department(), safe(detail), now());
    }

    private void auditCorrection(String encounterId, String stage, SessionUser user, String reason, ObjectNode before, ObjectNode after) {
        jdbcTemplate.update("""
            INSERT INTO pre_ai_audit_logs (
              id, encounter_id, action, stage_code, operator, operator_role, operator_id, operator_username, operator_department, detail,
              reason, before_json, after_json, created_at
            ) VALUES (?, ?, 'stage.correct', ?, ?, ?, ?, ?, ?, '已完成阶段纠错', ?, CAST(? AS JSON), CAST(? AS JSON), ?)
            """,
            "preaudit-" + UUID.randomUUID(), encounterId, stage, user.name(), user.role(), user.id(), user.username(), user.department(),
            reason, toJson(before), toJson(after), now()
        );
    }

    private void auditDepartmentCorrection(
        String encounterId,
        SessionUser user,
        String reason,
        String previousId,
        String previousName,
        DepartmentIdentity department
    ) {
        ObjectNode before = objectMapper.createObjectNode();
        before.put("owningDepartmentId", previousId);
        before.put("owningDepartmentNameSnapshot", previousName);
        ObjectNode after = objectMapper.createObjectNode();
        after.put("owningDepartmentId", department.id());
        after.put("owningDepartmentNameSnapshot", department.name());
        jdbcTemplate.update(
            """
            INSERT INTO pre_ai_audit_logs (
              id, encounter_id, action, stage_code, operator, operator_role, operator_id, operator_username, operator_department, detail,
              reason, before_json, after_json, created_at
            ) VALUES (?, ?, 'encounter.department.correct', NULL, ?, ?, ?, ?, ?, '管理员修正病历归属科室', ?, CAST(? AS JSON), CAST(? AS JSON), ?)
            """,
            "preaudit-" + UUID.randomUUID(), encounterId, user.name(), user.role(), user.id(), user.username(), user.department(),
            reason, toJson(before), toJson(after), now()
        );
    }

    private void requireReadRole(SessionUser user) {
        if (user != null && READ_ROLES.contains(RoleCatalog.canonicalize(user.role()))) return;
        if (user == null || !READ_ROLES.contains(user.role())) throw forbidden("当前账号无权查看前置病历");
    }

    private void requireEncounterCreator(SessionUser user) {
        if (user == null || !navigationService.hasCapability(user, "preai:encounter:create")) {
            throw forbidden("当前岗位无权新建病历");
        }
    }

    private void requireDutyAssignmentManager(SessionUser user) {
        if (user == null || !Set.of("admin", "doctor").contains(RoleCatalog.canonicalize(user.role()))) {
            throw forbidden("仅管理员和医师可以维护病例岗位安排");
        }
    }

    private void syncRegistrationCareType(String encounterId, ObjectNode data, ObjectNode encounter) {
        String requested = normalizeInventoryCareType(text(data, "inventoryCareType", text(encounter, "inventoryCareType", "outpatient")));
        String existing = text(encounter, "inventoryCareType", "outpatient");
        String tags = careSituationTags(requested, text(data, "careSituationDescription"));
        data.put("inventoryCareType", requested);
        data.put("careSituationTags", tags);
        if (requested.equals(existing)) {
            jdbcTemplate.update("UPDATE pre_ai_encounters SET care_situation_tags = NULLIF(?, ''), updated_at = ? WHERE id = ?", tags, now(), encounterId);
            return;
        }
        Integer eventCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM inventory_consumption_events WHERE encounter_id = ? AND status = 'succeeded'",
            Integer.class,
            encounterId
        );
        if (eventCount != null && eventCount > 0) {
            throw conflict("该就诊已经产生实际耗用，登记口径不能直接修改；请通过退回冲销或门诊转住院处理");
        }
        jdbcTemplate.update(
            "UPDATE pre_ai_encounters SET inventory_care_type = ?, care_situation_tags = NULLIF(?, ''), care_type_locked_at = ?, updated_at = ? WHERE id = ?",
            requested, tags, now(), now(), encounterId
        );
        jdbcTemplate.update(
            "UPDATE pre_ai_care_encounters SET care_type = ?, visit_date = ? WHERE clinical_encounter_id = ? AND care_type = ?",
            requested, parseVisitDate(text(data, "visitDate")), encounterId, existing
        );
    }

    private String careSituationTags(String inventoryCareType, String description) {
        List<String> tags = new ArrayList<>();
        tags.add("inpatient".equals(inventoryCareType) ? "住院" : "门诊");
        if (safe(description).contains("低保")) tags.add("低保");
        return String.join(",", tags);
    }

    private void transitionToInpatientCare(String encounterId, ObjectNode encounter, SessionUser user) {
        String outpatientCareId = jdbcTemplate.query(
            "SELECT id FROM pre_ai_care_encounters WHERE clinical_encounter_id = ? AND care_type = 'outpatient' ORDER BY started_at DESC LIMIT 1",
            (rs, rowNum) -> rs.getString("id"),
            encounterId
        ).stream().findFirst().orElse(null);
        jdbcTemplate.update(
            "UPDATE pre_ai_care_encounters SET status = 'COMPLETED', ended_at = CURRENT_TIMESTAMP(3) WHERE id = ? AND status = 'ACTIVE'",
            outpatientCareId
        );
        jdbcTemplate.update(
            """
            INSERT INTO pre_ai_care_encounters (
              id, clinical_encounter_id, source_care_encounter_id, care_type, owning_department_id,
              case_token, visit_date, status, started_at, created_by
            ) VALUES (?, ?, ?, 'inpatient', ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP(3), ?)
            ON DUPLICATE KEY UPDATE status = 'ACTIVE', ended_at = NULL
            """,
            "care-" + UUID.randomUUID(), encounterId, outpatientCareId, text(encounter, "owningDepartmentId"),
            text(encounter, "caseToken"), parseVisitDate(text(encounter.path("patient"), "visitDate")), text(encounter, "createdBy")
        );
        jdbcTemplate.update(
            "UPDATE pre_ai_encounters SET inventory_care_type = 'inpatient', care_type_locked_at = ?, updated_at = ? WHERE id = ?",
            now(), now(), encounterId
        );
        audit(encounterId, "encounter.care-type.transition", "DOCTOR", user,
            "门诊耗材就诊已结束并创建关联住院耗材就诊；既往门诊耗用保持不变");
    }

    private void requireEncounterAccess(String encounterId, SessionUser user) {
        requireReadRole(user);
        String role = RoleCatalog.canonicalize(user.role());
        if (isTcmRole(user) && isOutpatientEncounter(loadEncounter(encounterId))) {
            throw forbidden("门诊患者跳过中医环节，当前岗位不可查看该病历");
        }
        if ("quality".equals(role) || hasFullPreAiOperationAccess(user)) return;
        if (!canAccessEncounter(encounterId, user)) {
            throw forbidden("当前账号不属于病历归属科室，且未获得该病历的跨科授权");
        }
    }

    private boolean canAccessEncounter(String encounterId, SessionUser user) {
        if (user == null || safe(encounterId).isBlank()) return false;
        String role = RoleCatalog.canonicalize(user.role());
        ObjectNode encounter = loadEncounter(encounterId);
        if (isTcmRole(user) && isOutpatientEncounter(encounter)) return false;
        if ("quality".equals(role) || hasFullPreAiOperationAccess(user)) return true;
        if (!READ_ROLES.contains(role)) return false;

        if (!safe(user.activeDepartmentId()).isBlank()
            && safe(user.activeDepartmentId()).equals(text(encounter, "owningDepartmentId"))) {
            return true;
        }
        Integer grantCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pre_ai_encounter_department_grants WHERE encounter_id = ? AND account_id = ? AND status = 'ACTIVE'",
            Integer.class,
            safe(encounterId),
            safe(user.id())
        );
        return (grantCount != null && grantCount > 0)
            || canAccessCurrentWorkflowStage(encounter, user)
            || hasAssignedDuty(encounter, user, DUTY_CODES)
            || hasAccessibleQueueTask(encounterId, user)
            || hasAccessibleAuxiliaryTask(encounterId, user);
    }

    private boolean canAccessCurrentWorkflowStage(ObjectNode encounter, SessionUser user) {
        String currentStage = "REVIEW";
        ObjectNode statuses = stageStatusMap(text(encounter, "id"));
        for (String stage : effectiveStageOrder(encounter)) {
            if (!Set.of("COMPLETED", "SKIPPED").contains(text(statuses, stage, "DRAFT"))) {
                currentStage = stage;
                break;
            }
        }
        return !currentStage.isBlank() && navigationService.canEditStage(user.role(), currentStage);
    }

    private boolean hasAccessibleQueueTask(String encounterId, SessionUser user) {
        List<String> stages = jdbcTemplate.query(
            """
            SELECT DISTINCT t.stage_code
            FROM clinic_queue_tickets q
            JOIN clinic_queue_tasks t ON t.ticket_id = q.id
            WHERE q.encounter_id = ?
              AND t.status NOT IN ('CANCELLED', 'INACTIVE')
            """,
            (rs, rowNum) -> rs.getString("stage_code"),
            safe(encounterId)
        );
        return stages.stream().anyMatch(stage -> navigationService.canEditStage(user.role(), stage));
    }

    private boolean hasAccessibleAuxiliaryTask(String encounterId, SessionUser user) {
        List<String> taskTypes = jdbcTemplate.query(
            """
            SELECT DISTINCT task_type
            FROM pre_ai_auxiliary_tasks
            WHERE encounter_id = ?
              AND status IN ('DRAFT', 'RETURNED')
            """,
            (rs, rowNum) -> rs.getString("task_type"),
            safe(encounterId)
        );
        return taskTypes.stream().anyMatch(taskType -> navigationService.canEditAuxiliary(user.role(), taskType));
    }

    private void requireActiveEncounter(ObjectNode encounter) {
        if ("CANCELLED".equals(text(encounter, "status"))) {
            throw conflict("该病历已办理离院，仅可查看历史记录");
        }
    }

    private void requireStageEditor(ObjectNode encounter, String stage, SessionUser user) {
        requireActiveEncounter(encounter);
        if ("TCM".equals(stage) && isOutpatientEncounter(encounter)) {
            throw conflict("门诊患者跳过中医环节，不能维护中医阶段");
        }
        boolean policyAllowed = user != null && navigationService.canEditStage(user.role(), stage);
        if (!policyAllowed) throw forbidden("当前岗位无权维护" + stageLabel(stage));
        if (isReceptionInspectionCoverage(stage, user)) return;
        if (hasFullPreAiOperationAccess(user)) return;
        Set<String> duties = STAGE_DUTIES.getOrDefault(stage, Set.of());
        if (hasConfiguredDuty(encounter, duties) && !hasAssignedDuty(encounter, user, duties)) {
            throw forbidden("本病例已指定" + stageLabel(stage) + "责任人，当前账号不在责任范围内");
        }
    }

    private boolean isReceptionInspectionCoverage(String stage, SessionUser user) {
        if (!Set.of("INSPECTION", "RECEPTION").contains(stage) || user == null) return false;
        return Set.of("inspection", "reception").contains(RoleCatalog.canonicalize(user.role()));
    }

    private boolean hasConfiguredDuty(ObjectNode encounter, Set<String> dutyCodes) {
        if (encounter == null || dutyCodes == null || dutyCodes.isEmpty()) return false;
        for (JsonNode assignment : encounter.path("dutyAssignments")) {
            if (!dutyCodes.contains(text(assignment, "dutyCode"))) continue;
            if (!text(assignment, "responsibleUserId").isBlank()
                || !text(assignment, "responsibleUserName").isBlank()
                || (assignment.path("participantUserIds").isArray() && !assignment.path("participantUserIds").isEmpty())
                || (assignment.path("participantUserNames").isArray() && !assignment.path("participantUserNames").isEmpty())) {
                return true;
            }
        }
        return false;
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
        requireActiveEncounter(encounter);
        boolean baseRole = user != null && navigationService.canCreateAuxiliary(user.role(), taskType);
        if (!baseRole) throw forbidden("当前岗位无权创建该辅助检查任务");
    }

    private void requireAuxTaskEditor(ObjectNode encounter, String taskType, SessionUser user) {
        requireActiveEncounter(encounter);
        boolean baseRole = user != null && navigationService.canEditAuxiliary(user.role(), taskType);
        if (!baseRole) throw forbidden("当前岗位无权维护该辅助检查任务");
        if (hasFullPreAiOperationAccess(user)) return;
        Set<String> duties = AUX_DUTIES.getOrDefault(taskType, Set.of());
        if (hasConfiguredDuty(encounter, duties) && !hasAssignedDuty(encounter, user, duties)) {
            throw forbidden("本病例已指定辅助任务责任人，当前账号不在责任范围内");
        }
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
        boolean baseRole = user != null && navigationService.canEditStage(user.role(), "REVIEW");
        if (!baseRole) throw forbidden("当前岗位无权执行医生复核操作");
        if (hasFullPreAiOperationAccess(user)) return;
        Set<String> duties = STAGE_DUTIES.get("REVIEW");
        if (hasConfiguredDuty(encounter, duties) && !hasAssignedDuty(encounter, user, duties)) {
            throw forbidden("本病例已指定最终复核医生，当前账号无权代为复核");
        }
    }

    private void requireRole(SessionUser user, String... roles) {
        if (user != null) {
            Set<String> allowedRoles = new LinkedHashSet<>();
            for (String role : roles) allowedRoles.add(RoleCatalog.canonicalize(role));
            String userRole = RoleCatalog.canonicalize(user.role());
            if (allowedRoles.contains(userRole)) return;
            if (hasFullPreAiOperationAccess(user) && !allowedRoles.equals(Set.of("admin"))) return;
        }
        if (user == null || !Set.of(roles).contains(user.role())) throw forbidden("当前岗位无权执行此操作");
    }

    private boolean hasFullPreAiOperationAccess(SessionUser user) {
        return user != null && FULL_OPERATION_ROLES.contains(RoleCatalog.canonicalize(user.role()));
    }

    private String normalizeStage(String value) {
        String stage = safe(value).toUpperCase(Locale.ROOT);
        if (!STAGE_ORDER.contains(stage)) throw badRequest("不支持的流程阶段");
        return stage;
    }

    private String normalizeInventoryCareType(String value) {
        String normalized = safe(value).toLowerCase(Locale.ROOT);
        if (Set.of("outpatient", "门诊", "门诊医保", "out").contains(normalized)) return "outpatient";
        if (Set.of("inpatient", "住院", "in").contains(normalized)) return "inpatient";
        throw badRequest("耗材统计口径只能选择门诊或住院");
    }

    private List<String> effectiveStageOrder(ObjectNode encounter) {
        List<String> stages = new ArrayList<>(STAGE_ORDER);
        if (isOutpatientEncounter(encounter)) stages.remove("TCM");
        if (!("inpatient".equals(normalizedCareType(encounter)) && "SURGICAL".equalsIgnoreCase(text(encounter, "treatmentPath")))) {
            stages.remove("SURGERY");
        }
        return stages;
    }

    private boolean isOutpatientEncounter(ObjectNode encounter) {
        return "outpatient".equals(normalizedCareType(encounter));
    }

    private String normalizedCareType(ObjectNode encounter) {
        String value = text(encounter, "inventoryCareType").toLowerCase(Locale.ROOT);
        if (Set.of("outpatient", "门诊", "门诊医保", "out").contains(value)) return "outpatient";
        if (Set.of("inpatient", "住院", "in").contains(value)) return "inpatient";
        String route = text(encounter, "route").toLowerCase(Locale.ROOT);
        if (Set.of("outpatient", "门诊", "门诊医保", "out").contains(route)) return "outpatient";
        // Unknown legacy values must remain visible to the TCM work queue.
        return "inpatient";
    }

    private boolean isTcmRole(SessionUser user) {
        return user != null && "tcm".equals(RoleCatalog.canonicalize(user.role()));
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
    public record RegisterAndIssueRequest(Map<String, Object> patient, String clientRequestId) {}
    public record DutyAssignmentsRequest(List<Map<String, Object>> dutyAssignments) {}
    public record StageSaveRequest(Map<String, Object> data, Integer expectedVersion) {}
    public record EncounterTerminationRequest(Map<String, Object> data, Integer expectedVersion, String reason) {}
    public record StageCorrectionRequest(Map<String, Object> data, Integer expectedVersion, String reason) {}
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
    public record FollowUpRegisterAndIssueRequest(String visitDate, Map<String, Object> visitMeta, String clientRequestId) {}
    public record ExistingRegisterAndIssueRequest(Map<String, Object> patient, String clientRequestId, Integer expectedVersion) {}
    public record AdmissionProfileSaveRequest(Map<String, Object> data, Integer expectedVersion, boolean complete) {}
    public record VisitMetaRequest(Map<String, Object> visitMeta) {}
    public record DepartmentCorrectionRequest(String departmentId, String reason) {}
    public record EncounterGrantRequest(String accountId, String status, String reason) {}
    private record DepartmentIdentity(String id, String name) {}
    private record DutyAccount(String id, String name, String role) {}
    public record AttachmentDownload(FileSystemResource resource, String fileName, String mimeType) {}
    public record ExportDownload(FileSystemResource resource, String fileName) {}
}

