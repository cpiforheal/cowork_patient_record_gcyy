package com.coshare.patientrecord.preai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PreAiPrivacyService {

    private static final Pattern MOBILE_PATTERN = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)\\d{6}(?:19|20)\\d{2}\\d{7}[0-9Xx](?![0-9Xx])");
    private static final Pattern COUNTY_ADDRESS = Pattern.compile("([^省市]{1,12}(?:县|区).{0,16}?(?:乡|镇|街道|村))");
    private static final Pattern ADDRESS_BOUNDARY = Pattern.compile("^(.+?(?:县|区|市).{0,16}?(?:乡|镇|街道|村))");
    private static final Set<String> IDENTITY_KEYS = Set.of(
        "identityNumber", "idNumber", "idCard", "visitNo", "admissionNo", "medicalRecordNo", "inpatientNo", "bedNo"
    );
    private static final Map<String, List<String>> STAGE_FIELDS = Map.of(
        "INSPECTION", List.of(
            "examinationDirection", "diseaseDirections", "examinationTypes", "lesionLocation", "clockPosition",
            "lesionSize", "lesionExtent", "lesionDepth", "visualFindings", "digitalExamFindings",
            "anoscopyFindings", "otherFindings", "factualConclusion"
        ),
        "RECEPTION", List.of(
            "chiefComplaint", "symptomDuration", "chiefComplaintText", "onsetTrigger", "symptomPattern", "symptomChanges", "aggravatingFactors",
            "bleedingFeatures", "painFeatures", "prolapseReduction", "associatedSymptoms", "recentAggravation",
            "previousTreatment", "generalCondition", "stoolFrequency", "stoolCharacteristics", "presentIllness",
            "chronicDiseaseItems", "surgicalHistoryItems", "pastHistory", "surgicalHistory", "traumaHistory", "transfusionHistory", "vaccinationHistory",
            "medicationHistory", "allergyHistory", "personalHistory", "maritalHistory", "familyHistory", "historySupplement",
            "reviewOpinion", "nextStepRecommendation", "dispositionSuggestion", "recommendedAuxiliaryExams", "specialCircumstances"
        ),
        "TCM", List.of(
            "tcmDisease", "primarySyndrome", "concurrentSyndrome", "inspection", "auscultationOlfaction", "inquiry",
            "palpation", "tongue", "pulse", "syndromeBasis", "treatmentPrinciple", "comorbidTcmItems"
        ),
        "DOCTOR", List.of(
            "finalRoute", "primaryWesternDiagnosis", "secondaryWesternDiagnoses", "secondaryDiagnosisItems",
            "diagnosisEvidence", "diagnosisBasis", "differentialDiagnoses", "treatmentPath", "treatmentMeasures",
            "medicationDirections", "examPlans", "surgeryArrangements", "observationFocus", "treatmentPlan",
            "admissionSeverity", "treatmentCategory", "plannedPrimaryOperation", "plannedSecondaryOperations",
            "operationIndications", "plannedOperationName", "plannedOperationSite", "plannedOperationPlan",
            "recommendedAnesthesia", "operationGrade", "specialOperationPlan"
        ),
        "SURGERY", List.of(
            "actualPrimaryOperation", "actualSecondaryOperations", "actualOperationName", "operationDate",
            "operationStartTime", "operationEndTime", "operationSite", "anesthesiaMethod", "intraoperativeFindings",
            "procedurePerformed", "specimenPathology", "bloodLossMeasurement", "drainageOptions", "dressingOptions",
            "bloodLossDrainDressing", "complications", "postoperativeDestination", "postoperativeHandoff",
            "physicianConfirmedBy", "physicianConfirmedAt"
        )
    );
    private static final Map<String, String> FIELD_LABELS = buildFieldLabels();

    private final ObjectMapper objectMapper;

    public PreAiPrivacyService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode maskWorkspace(ObjectNode workspace) {
        ObjectNode result = objectMapper.createObjectNode();
        JsonNode encounter = workspace.path("encounter");
        String caseToken = text(encounter, "caseToken");
        JsonNode patient = encounter.path("patient");
        String rawPatientName = text(patient, "patientName");
        String rawContactName = text(patient, "contactName");
        String rawAddress = text(patient, "address");

        ObjectNode metadata = result.putObject("metadata");
        metadata.put("caseToken", caseToken);
        metadata.put("visitDate", text(patient, "visitDate"));
        metadata.put("route", routeLabel(text(encounter, "route")));
        metadata.put("treatmentPath", treatmentPathLabel(text(encounter, "treatmentPath")));
        metadata.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        ObjectNode maskedPatient = result.putObject("patient");
        putIfPresent(maskedPatient, "patientName", maskName(rawPatientName));
        putIfPresent(maskedPatient, "gender", text(patient, "gender"));
        putIfPresent(maskedPatient, "age", patientAge(patient));
        putIfPresent(maskedPatient, "phone", maskPhone(text(patient, "phone")));
        putIfPresent(maskedPatient, "address", coarseAddress(rawAddress));
        putIfPresent(maskedPatient, "contactName", maskName(rawContactName));
        putIfPresent(maskedPatient, "contactRelation", text(patient, "contactRelation"));
        putIfPresent(maskedPatient, "contactPhone", maskPhone(text(patient, "contactPhone")));
        putIfPresent(maskedPatient, "nationality", text(patient, "nationality"));
        putIfPresent(maskedPatient, "nativePlace", text(patient, "nativePlace"));
        putIfPresent(maskedPatient, "birthplace", text(patient, "birthplace"));
        putIfPresent(maskedPatient, "maritalStatus", text(patient, "maritalStatus"));
        putIfPresent(maskedPatient, "admissionMethod", text(patient, "admissionMethod"));
        putIfPresent(maskedPatient, "insuranceType", text(patient, "insuranceType"));
        putIfPresent(maskedPatient, "paymentMethod", text(patient, "paymentMethod"));
        putIfPresent(maskedPatient, "patientSource", text(patient, "patientSource"));
        putIfPresent(maskedPatient, "ward", text(patient, "ward"));
        putIfPresent(maskedPatient, "admissionCount", text(patient, "admissionCount"));
        putIfPresent(maskedPatient, "registrationNote", maskClinicalText(text(patient, "registrationNote"), patient, caseToken));

        ObjectNode stages = result.putObject("stages");
        for (JsonNode stage : workspace.path("stages")) {
            String stageCode = text(stage, "stageCode");
            if (!"COMPLETED".equals(text(stage, "status")) && !"SKIPPED".equals(text(stage, "status"))) continue;
            List<String> allowlist = STAGE_FIELDS.get(stageCode);
            if (allowlist == null || "SKIPPED".equals(text(stage, "status"))) continue;
            ObjectNode data = copyAllowed(stage.path("data"), allowlist, patient, caseToken);
            if (!data.isEmpty()) stages.set(stageCode, data);
        }

        ArrayNode auxiliaryTasks = result.putArray("auxiliaryTasks");
        for (JsonNode task : workspace.path("auxiliaryTasks")) {
            if (!"COMPLETED".equals(text(task, "status"))) continue;
            if ("LAB".equals(text(task, "taskType"))) continue;
            ObjectNode row = auxiliaryTasks.addObject();
            row.put("taskType", text(task, "taskType"));
            putIfPresent(row, "title", maskClinicalText(text(task, "title"), patient, caseToken));
            JsonNode data = task.path("data");
            for (String key : List.of(
                "project", "sampledAt", "reportedAt", "result", "abnormalItems", "conclusion", "examinedAt",
                "findings", "modality", "bodyPart", "rawReport", "measuredAt", "systolicBp", "diastolicBp",
                "temperature", "pulse", "respiration", "nursingConditions", "note", "status", "scope",
                "lesionLocation", "lesionCount", "lesionSize", "lesionMorphology", "biopsyPerformed",
                "resectionPerformed", "pathologySubmitted", "abnormalDescription"
            )) {
                JsonNode value = data.path(key);
                if (!isEmpty(value)) row.set(key, maskNode(value, patient, caseToken));
            }
        }
        if (auxiliaryTasks.isEmpty()) result.remove("auxiliaryTasks");

        boolean labCompleted = false;
        for (JsonNode task : workspace.path("auxiliaryTasks")) {
            if ("LAB".equals(text(task, "taskType")) && "COMPLETED".equals(text(task, "status"))) {
                labCompleted = true;
                break;
            }
        }
        if (labCompleted) {
            ArrayNode labReports = result.putArray("labReports");
            for (JsonNode report : workspace.path("labReports")) {
                ObjectNode row = labReports.addObject();
                putIfPresent(row, "templateName", maskClinicalText(text(report, "templateName"), patient, caseToken));
                putIfPresent(row, "reportDate", text(report, "reportDate"));
                putIfPresent(row, "remark", maskClinicalText(text(report, "remark"), patient, caseToken));
                ArrayNode metrics = row.putArray("metrics");
                for (JsonNode metric : report.path("metrics")) {
                    if (text(metric, "value").isBlank()) continue;
                    ObjectNode clean = metrics.addObject();
                    for (String key : List.of("name", "shortName", "value", "unit", "reference")) {
                        String value = text(metric, key);
                        if (!value.isBlank()) clean.put(key, maskClinicalText(value, patient, caseToken));
                    }
                    String abnormal = labAbnormalLabel(clean);
                    if (!abnormal.isBlank()) clean.put("abnormal", abnormal);
                    boolean critical = metric.path("critical").asBoolean(false) || "CRITICAL".equalsIgnoreCase(text(metric, "severity"));
                    clean.put("severity", critical ? "CRITICAL" : abnormal.isBlank() ? "NORMAL" : "ABNORMAL");
                }
                if (metrics.isEmpty()) labReports.remove(labReports.size() - 1);
            }
            if (labReports.isEmpty()) result.remove("labReports");
        }

        ObjectNode review = result.putObject("review");
        putIfPresent(review, "reviewedAt", text(encounter, "reviewedAt"));
        review.put("reviewerRole", "医生");
        review.put("statement", "以上内容由各岗位录入并经医生复核，仅作为外部病历生成前的事实资料。\n");
        return result;
    }

    public byte[] renderDocx(ObjectNode maskedWorkspace, ObjectNode rawWorkspace) {
        String documentXml = buildDocumentXml(maskedWorkspace);
        assertNoLeak(documentXml, rawWorkspace);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            addEntry(zip, "[Content_Types].xml", contentTypesXml());
            addEntry(zip, "_rels/.rels", relationshipsXml());
            addEntry(zip, "docProps/core.xml", corePropertiesXml());
            addEntry(zip, "docProps/app.xml", appPropertiesXml());
            addEntry(zip, "word/document.xml", documentXml);
            addEntry(zip, "word/styles.xml", stylesXml());
            addEntry(zip, "word/settings.xml", settingsXml());
            addEntry(zip, "word/_rels/document.xml.rels", documentRelationshipsXml());
            zip.finish();
            return output.toByteArray();
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "脱敏 DOCX 生成失败", error);
        }
    }

    String maskName(String value) {
        String name = safe(value);
        if (name.isBlank()) return "";
        if (name.length() >= 2 && Set.of("欧阳", "司马", "上官", "诸葛", "东方", "皇甫", "尉迟", "公孙").contains(name.substring(0, 2))) {
            return name.substring(0, 2) + "xx";
        }
        return name.substring(0, 1) + "xx";
    }

    String maskPhone(String value) {
        String phone = safe(value).replaceAll("\\s+", "");
        Matcher matcher = MOBILE_PATTERN.matcher(phone);
        if (matcher.matches()) return phone.substring(0, 3) + "****" + phone.substring(7);
        if (phone.length() >= 7) return phone.substring(0, Math.min(3, phone.length())) + "****" + phone.substring(phone.length() - Math.min(4, phone.length()));
        return phone.isBlank() ? "" : "已脱敏";
    }

    String coarseAddress(String value) {
        String address = safe(value).replaceAll("\\s+", "");
        if (address.isBlank()) return "";
        Matcher countyMatcher = COUNTY_ADDRESS.matcher(address);
        if (countyMatcher.find()) return countyMatcher.group(1);
        Matcher matcher = ADDRESS_BOUNDARY.matcher(address);
        if (matcher.find()) return matcher.group(1);
        for (String boundary : List.of("村", "镇", "乡", "街道", "县", "区", "市")) {
            int index = address.indexOf(boundary);
            if (index >= 0) return address.substring(0, index + boundary.length());
        }
        return "已脱敏";
    }

    private ObjectNode copyAllowed(JsonNode source, List<String> keys, JsonNode patient, String caseToken) {
        ObjectNode result = objectMapper.createObjectNode();
        for (String key : keys) {
            JsonNode value = source.path(key);
            if (isEmpty(value) || IDENTITY_KEYS.contains(key)) continue;
            result.set(key, maskNode(value, patient, caseToken));
        }
        return result;
    }

    private JsonNode maskNode(JsonNode value, JsonNode patient, String caseToken) {
        if (value == null || value.isNull() || value.isMissingNode()) return value;
        if (value.isTextual()) return objectMapper.getNodeFactory().textNode(maskClinicalText(value.asText(), patient, caseToken));
        if (value.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            for (JsonNode item : value) {
                JsonNode masked = maskNode(item, patient, caseToken);
                if (!isEmpty(masked)) array.add(masked);
            }
            return array;
        }
        if (value.isObject()) {
            ObjectNode object = objectMapper.createObjectNode();
            value.fields().forEachRemaining(entry -> {
                if (IDENTITY_KEYS.contains(entry.getKey()) || isEmpty(entry.getValue())) return;
                object.set(entry.getKey(), maskNode(entry.getValue(), patient, caseToken));
            });
            return object;
        }
        return value.deepCopy();
    }

    private String maskClinicalText(String value, JsonNode patient, String caseToken) {
        String result = safe(value);
        if (result.isBlank()) return "";
        String patientName = text(patient, "patientName");
        String contactName = text(patient, "contactName");
        String address = text(patient, "address");
        if (!patientName.isBlank()) result = result.replace(patientName, maskName(patientName));
        if (!contactName.isBlank()) result = result.replace(contactName, maskName(contactName));
        if (!address.isBlank()) result = result.replace(address, coarseAddress(address));
        for (String key : List.of("visitNo", "admissionNo", "medicalRecordNo", "inpatientNo", "bedNo")) {
            String identifier = text(patient, key);
            if (!identifier.isBlank()) result = result.replace(identifier, caseToken);
        }
        result = MOBILE_PATTERN.matcher(result).replaceAll(match -> {
            String phone = match.group(1);
            return phone.substring(0, 3) + "****" + phone.substring(7);
        });
        result = ID_CARD_PATTERN.matcher(result).replaceAll("[身份证号已删除]");
        return result;
    }

    private String buildDocumentXml(ObjectNode masked) {
        StringBuilder body = new StringBuilder();
        body.append(paragraph("中医肛肠医院住院病历自动生成表", "Title"));
        body.append(paragraph("前置事实版（仅输出已填写、已选择内容）", "Subtitle"));

        JsonNode metadata = masked.path("metadata");
        JsonNode patient = masked.path("patient");
        JsonNode stages = masked.path("stages");
        JsonNode reception = stages.path("RECEPTION");
        JsonNode inspection = stages.path("INSPECTION");
        JsonNode tcm = stages.path("TCM");
        JsonNode doctor = stages.path("DOCTOR");
        JsonNode surgery = stages.path("SURGERY");

        addSection(body, "一、基础及住院信息", mergeLines(List.of(
            line("病例标识", text(metadata, "caseToken")),
            line("就诊日期", text(metadata, "visitDate")),
            line("就诊分支", text(metadata, "route")),
            line("治疗路径", text(metadata, "treatmentPath")),
            line("血型", findLabValue(masked, false)),
            line("Rh血型", findLabValue(masked, true)),
            line("过敏史", displayValue(reception.path("allergyHistory")))
        ), nodeLines(patient)));

        addSection(body, "二、主诉", List.of(line("主诉", firstPresent(text(reception, "chiefComplaintText"), displayValue(reception.path("chiefComplaint"))))));
        addSection(body, "三、现病史", List.of(line("现病史", text(reception, "presentIllness"))));
        addSection(body, "四、既往史、个人史、婚育史及家族史", nodeLines(reception, List.of(
            "chronicDiseaseItems", "surgicalHistoryItems", "pastHistory", "surgicalHistory", "traumaHistory",
            "transfusionHistory", "vaccinationHistory", "medicationHistory", "allergyHistory", "personalHistory",
            "maritalHistory", "familyHistory", "historySupplement", "specialCircumstances"
        )));
        addSection(body, "五、中医四诊", nodeLines(tcm, List.of(
            "inspection", "auscultationOlfaction", "inquiry", "palpation", "tongue", "pulse", "tcmDisease",
            "primarySyndrome", "concurrentSyndrome", "syndromeBasis", "treatmentPrinciple"
        )));
        addSection(body, "六、专科检查", nodeLines(inspection, List.of(
            "examinationDirection", "diseaseDirections", "examinationTypes", "lesionLocation", "clockPosition",
            "lesionSize", "lesionExtent", "lesionDepth", "visualFindings", "digitalExamFindings",
            "anoscopyFindings", "otherFindings", "factualConclusion"
        )));

        body.append(auxiliaryDocumentXml(masked));

        List<String> mainDiagnosisLines = new java.util.ArrayList<>();
        mainDiagnosisLines.add(line("中医主诊断", tcmDiagnosis(tcm)));
        mainDiagnosisLines.add(line("西医主诊断", text(doctor, "primaryWesternDiagnosis")));
        mainDiagnosisLines.add(line("全身合并症", diagnosisNames(doctor, "COMORBIDITY")));
        mainDiagnosisLines.addAll(nodeLines(doctor, List.of("diagnosisEvidence", "diagnosisBasis", "differentialDiagnoses")));
        addSection(body, "八、中西医主诊断及合并症", mainDiagnosisLines);

        String localDiagnoses = diagnosisNames(doctor, "LOCAL");
        if (localDiagnoses.isBlank()) localDiagnoses = displayValue(doctor.path("secondaryWesternDiagnoses"));
        addSection(body, "九、西医次诊断", List.of(line("局部次诊断", localDiagnoses)));
        addSection(body, "十、合并病中医病名及证型", comorbidTcmLines(tcm.path("comorbidTcmItems")));

        List<String> operationLines = new java.util.ArrayList<>();
        operationLines.addAll(nodeLines(doctor, List.of(
            "treatmentPath", "treatmentMeasures", "medicationDirections", "examPlans", "surgeryArrangements",
            "observationFocus", "treatmentPlan", "plannedPrimaryOperation", "plannedSecondaryOperations",
            "operationIndications", "plannedOperationSite", "recommendedAnesthesia", "operationGrade", "specialOperationPlan"
        )));
        operationLines.addAll(nodeLines(surgery, List.of(
            "actualPrimaryOperation", "actualSecondaryOperations", "operationDate", "operationStartTime",
            "operationEndTime", "operationSite", "anesthesiaMethod", "intraoperativeFindings", "procedurePerformed",
            "specimenPathology", "bloodLossMeasurement", "drainageOptions", "dressingOptions", "complications",
            "postoperativeDestination", "postoperativeHandoff", "physicianConfirmedBy", "physicianConfirmedAt"
        )));
        addSection(body, "十一、治疗及手术/操作信息", operationLines);
        addSection(body, "十二、DIP分组提示（非阻断）", dipHintLines(doctor, surgery, masked.path("auxiliaryTasks")));

        addSection(body, "医生复核信息", nodeLines(masked.path("review")));

        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            + body
            + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>"
            + "</w:body></w:document>";
    }

    private String auxiliaryDocumentXml(ObjectNode masked) {
        StringBuilder body = new StringBuilder();
        if (masked.has("auxiliaryTasks")) {
            body.append(paragraph("七、辅助检查", "Heading1"));
            int index = 1;
            for (JsonNode task : masked.path("auxiliaryTasks")) {
                body.append(paragraph(index++ + ". " + auxiliaryTaskLabel(text(task, "taskType")) + optionalSuffix(text(task, "title")), "Heading2"));
                nodeLines(task).stream().filter(line -> !line.startsWith("检查类型：") && !line.startsWith("任务名称：")).forEach(line -> body.append(paragraph(line, "Normal")));
            }
        }
        if (masked.has("labReports")) {
            if (!masked.has("auxiliaryTasks")) body.append(paragraph("七、辅助检查", "Heading1"));
            body.append(paragraph("化验室检验报告", "Heading2"));
            int index = 1;
            for (JsonNode report : masked.path("labReports")) {
                String title = index++ + ". " + text(report, "templateName") + optionalSuffix(text(report, "reportDate"));
                body.append(paragraph(title, "Heading2"));
                for (JsonNode metric : report.path("metrics")) {
                    String name = text(metric, "name");
                    String shortName = text(metric, "shortName");
                    String value = text(metric, "value");
                    String unit = text(metric, "unit");
                    String reference = text(metric, "reference");
                    String label = name + (shortName.isBlank() ? "" : "（" + shortName + "）");
                    String abnormal = text(metric, "abnormal");
                    String severity = text(metric, "severity");
                    String marker = "CRITICAL".equals(severity) ? "【危急值·" + (abnormal.isBlank() ? "异常" : abnormal) + "】"
                        : abnormal.isBlank() ? "" : "【异常·" + abnormal + "】";
                    String line = label + "：" + value + unit + marker + (reference.isBlank() ? "" : "；参考范围：" + reference);
                    body.append(paragraph(line, abnormal.isBlank() ? "Normal" : "Abnormal"));
                }
                if (!text(report, "remark").isBlank()) body.append(paragraph("备注：" + text(report, "remark"), "Normal"));
            }
        }
        return body.toString();
    }

    private List<String> nodeLines(JsonNode node) {
        if (node == null || !node.isObject()) return List.of();
        List<String> keys = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return nodeLines(node, keys);
    }

    private List<String> nodeLines(JsonNode node, List<String> keys) {
        Map<String, String> lines = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return List.of();
        for (String key : keys) {
            JsonNode valueNode = node.path(key);
            if (isEmpty(valueNode)) continue;
            String label = FIELD_LABELS.getOrDefault(key, key);
            String value = displayValue(key, valueNode);
            if (!value.isBlank()) lines.put(label, value);
        }
        return lines.entrySet().stream().map(entry -> line(entry.getKey(), entry.getValue())).toList();
    }

    private List<String> mergeLines(List<String> first, List<String> second) {
        List<String> result = new java.util.ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private String firstPresent(String... values) {
        for (String value : values) if (!safe(value).isBlank()) return safe(value);
        return "";
    }

    private String tcmDiagnosis(JsonNode tcm) {
        String disease = text(tcm, "tcmDisease");
        String syndrome = text(tcm, "primarySyndrome");
        if (disease.isBlank()) return "";
        return disease + (syndrome.isBlank() ? "" : "（" + syndrome + "）");
    }

    private String diagnosisNames(JsonNode doctor, String category) {
        JsonNode items = doctor.path("secondaryDiagnosisItems");
        if (!items.isArray()) return "";
        List<String> names = new java.util.ArrayList<>();
        for (JsonNode item : items) {
            if (!category.equalsIgnoreCase(text(item, "category"))) continue;
            String name = text(item, "name");
            if (!name.isBlank()) names.add(name);
        }
        return String.join("、", names);
    }

    private List<String> comorbidTcmLines(JsonNode items) {
        if (!items.isArray()) return List.of();
        List<String> lines = new java.util.ArrayList<>();
        int index = 1;
        for (JsonNode item : items) {
            String western = text(item, "westernComorbidity");
            if (western.isBlank()) continue;
            boolean included = item.path("includedInTcm").asBoolean(false);
            if (!included) {
                lines.add(index++ + ". " + western + "：未纳入中医辨证");
                continue;
            }
            String tcmDisease = text(item, "tcmDisease");
            String syndrome = text(item, "syndrome");
            String note = text(item, "note");
            if (tcmDisease.isBlank() || syndrome.isBlank()) continue;
            String detail = western + " → " + tcmDisease
                + (syndrome.isBlank() ? "" : "（" + syndrome + "）")
                + (note.isBlank() ? "" : "；" + note);
            lines.add(index++ + ". " + detail);
        }
        return lines;
    }

    private String findLabValue(ObjectNode masked, boolean rh) {
        for (JsonNode report : masked.path("labReports")) {
            for (JsonNode metric : report.path("metrics")) {
                String name = (text(metric, "name") + " " + text(metric, "shortName"))
                    .replaceAll("\\s+", "")
                    .toLowerCase(java.util.Locale.ROOT);
                boolean rhMetric = name.contains("rh") || name.contains("rhd");
                boolean bloodTypeMetric = name.contains("abo") || name.contains("血型");
                boolean matches = rh ? rhMetric : bloodTypeMetric && !rhMetric;
                if (matches && !text(metric, "value").isBlank()) return text(metric, "value");
            }
        }
        return "未查";
    }

    private List<String> dipHintLines(JsonNode doctor, JsonNode surgery, JsonNode auxiliaryTasks) {
        String diagnosis = text(doctor, "primaryWesternDiagnosis");
        String primaryOperation = firstPresent(text(surgery, "actualPrimaryOperation"), text(doctor, "plannedPrimaryOperation"));
        boolean colonoscopyCompleted = false;
        boolean colonoscopyResection = false;
        for (JsonNode task : auxiliaryTasks) {
            if (!"COLONOSCOPY".equals(text(task, "taskType"))) continue;
            colonoscopyCompleted = "COMPLETED".equals(text(task, "status"));
            colonoscopyResection = text(task, "resectionPerformed").contains("切除");
        }
        List<String> lines = new java.util.ArrayList<>();
        if (!diagnosis.isBlank()) lines.add(line("主诊断", diagnosis));
        if (!primaryOperation.isBlank()) lines.add(line("主术式", primaryOperation));
        if (colonoscopyCompleted) lines.add(line("肠镜事实", colonoscopyResection ? "已完成并实施切除" : "已完成检查"));
        String group = "";
        if (colonoscopyResection && (primaryOperation.contains("息肉") || diagnosis.contains("息肉"))) {
            group = "建议关注结直肠息肉手术组（肠镜下切除）";
        } else if (!primaryOperation.isBlank() && colonoscopyCompleted) {
            group = "建议关注肛肠手术与肠镜联合组";
        } else if (!primaryOperation.isBlank()) {
            group = "建议按主诊断与实际主术式核对纯肛肠手术组";
        }
        if (!group.isBlank()) lines.add(line("分组提示", group));
        if (!lines.isEmpty()) lines.add("提示性质：仅供复核，不阻断事实包生成，最终以正式编码及DIP规则为准");
        return lines;
    }

    private void addSection(StringBuilder body, String title, List<String> lines) {
        List<String> present = lines.stream().filter(line -> line != null && !line.isBlank() && !line.endsWith("：")).toList();
        if (present.isEmpty()) return;
        body.append(paragraph(title, "Heading1"));
        body.append("<w:tbl><w:tblPr><w:tblW w:w=\"9072\" w:type=\"dxa\"/><w:tblLayout w:type=\"fixed\"/>"
            + "<w:tblBorders><w:top w:val=\"single\" w:sz=\"6\" w:color=\"808080\"/><w:left w:val=\"single\" w:sz=\"6\" w:color=\"808080\"/>"
            + "<w:bottom w:val=\"single\" w:sz=\"6\" w:color=\"808080\"/><w:right w:val=\"single\" w:sz=\"6\" w:color=\"808080\"/>"
            + "<w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"B7B7B7\"/><w:insideV w:val=\"single\" w:sz=\"4\" w:color=\"B7B7B7\"/></w:tblBorders></w:tblPr>"
            + "<w:tblGrid><w:gridCol w:w=\"2100\"/><w:gridCol w:w=\"6972\"/></w:tblGrid>");
        body.append(tableRow("项目", "内容", true, false));
        for (String line : present) {
            int separator = line.indexOf('：');
            String label = separator < 0 ? "说明" : line.substring(0, separator);
            String value = separator < 0 ? line : line.substring(separator + 1);
            body.append(tableRow(label, value, false, line.contains("【异常") || line.contains("【危急值")));
        }
        body.append("</w:tbl>");
    }

    private String tableRow(String label, String value, boolean header, boolean abnormal) {
        return "<w:tr>" + tableCell(label, 2100, header, false) + tableCell(value, 6972, header, abnormal) + "</w:tr>";
    }

    private String tableCell(String value, int width, boolean header, boolean abnormal) {
        String shading = header ? "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"E7E6E6\"/>" : "";
        String style = header ? "TableHeader" : abnormal ? "Abnormal" : "TableText";
        return "<w:tc><w:tcPr><w:tcW w:w=\"" + width + "\" w:type=\"dxa\"/>" + shading
            + "<w:tcMar><w:top w:w=\"80\" w:type=\"dxa\"/><w:left w:w=\"100\" w:type=\"dxa\"/><w:bottom w:w=\"80\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/></w:tcMar>"
            + "<w:vAlign w:val=\"center\"/></w:tcPr>" + paragraph(value, style) + "</w:tc>";
    }

    private String paragraph(String value, String style) {
        String text = safe(value).replace("\n", "；");
        return "<w:p><w:pPr><w:pStyle w:val=\"" + style + "\"/></w:pPr><w:r><w:t xml:space=\"preserve\">" + xml(text) + "</w:t></w:r></w:p>";
    }

    String labAbnormalLabel(JsonNode metric) {
        String value = text(metric, "value");
        String reference = text(metric, "reference");
        if (value.isBlank() || reference.isBlank() || "未查".equals(value)) return "";
        String normalized = reference.replaceAll("\\s+", "").replace('～', '-').replace('—', '-').replace('–', '-');
        try {
            double numeric = Double.parseDouble(value.replace(",", ""));
            Matcher range = Pattern.compile("^(-?\\d+(?:\\.\\d+)?)-(-?\\d+(?:\\.\\d+)?)$").matcher(normalized);
            if (range.matches()) {
                double min = Double.parseDouble(range.group(1));
                double max = Double.parseDouble(range.group(2));
                return numeric < min ? "偏低" : numeric > max ? "偏高" : "";
            }
            Matcher upper = Pattern.compile("^(≤|<=|<)(-?\\d+(?:\\.\\d+)?)$").matcher(normalized);
            if (upper.matches()) {
                double max = Double.parseDouble(upper.group(2));
                boolean abnormal = "<".equals(upper.group(1)) ? numeric >= max : numeric > max;
                return abnormal ? "偏高" : "";
            }
            Matcher lower = Pattern.compile("^(≥|>=|>)(-?\\d+(?:\\.\\d+)?)$").matcher(normalized);
            if (lower.matches()) {
                double min = Double.parseDouble(lower.group(2));
                boolean abnormal = ">".equals(lower.group(1)) ? numeric <= min : numeric < min;
                return abnormal ? "偏低" : "";
            }
        } catch (NumberFormatException ignored) {
            // 定性指标在下方判断。
        }
        if (Set.of("阴性", "-", "正常").contains(reference)) {
            return Set.of("阴性", "-", "正常").contains(value) ? "" : "异常";
        }
        return "";
    }

    private String displayValue(String key, JsonNode value) {
        if (value != null && value.isTextual()) {
            String raw = value.asText("");
            if ("finalRoute".equals(key)) return routeLabel(raw);
            if ("treatmentPath".equals(key)) return treatmentPathLabel(raw);
            if ("examinationTypes".equals(key)) return examinationTypeLabel(raw);
            if ("status".equals(key)) return switch (raw) {
                case "NOT_DONE" -> "未查";
                case "COMPLETED" -> "已查";
                case "REFUSED" -> "患者拒绝";
                case "DEFERRED" -> "暂缓";
                default -> raw;
            };
        }
        if ("examinationTypes".equals(key) && value != null && value.isArray()) {
            return java.util.stream.StreamSupport.stream(value.spliterator(), false)
                .map(JsonNode::asText)
                .map(this::examinationTypeLabel)
                .filter(item -> !item.isBlank())
                .reduce((left, right) -> left + "、" + right)
                .orElse("");
        }
        return displayValue(value);
    }

    private String examinationTypeLabel(String value) {
        return switch (safe(value)) {
            case "VISUAL" -> "视诊";
            case "DIGITAL" -> "指诊";
            case "ANOSCOPY" -> "肛门镜";
            case "OTHER" -> "其他";
            default -> safe(value);
        };
    }

    private String displayValue(JsonNode value) {
        if (value == null || value.isNull()) return "";
        if (value.isArray()) {
            return java.util.stream.StreamSupport.stream(value.spliterator(), false)
                .map(this::displayValue)
                .filter(item -> !item.isBlank())
                .reduce((left, right) -> left + "、" + right)
                .orElse("");
        }
        if (value.isObject()) {
            if (value.has("value") || value.has("unit") || value.has("status")) {
                String measurement = text(value, "value") + text(value, "unit");
                String status = text(value, "status");
                return measurement + (status.isBlank() || "正常".equals(status) ? "" : "（" + status + "）");
            }
            List<String> parts = new java.util.ArrayList<>();
            value.fields().forEachRemaining(entry -> {
                if (isEmpty(entry.getValue())) return;
                String label = FIELD_LABELS.getOrDefault(entry.getKey(), entry.getKey());
                String content = displayValue(entry.getKey(), entry.getValue());
                if (!content.isBlank()) parts.add(label + "：" + content);
            });
            return String.join("；", parts);
        }
        if (value.isBoolean()) return value.asBoolean() ? "是" : "否";
        return value.asText("");
    }

    private void assertNoLeak(String documentXml, ObjectNode rawWorkspace) {
        JsonNode patient = rawWorkspace.path("encounter").path("patient");
        Map<String, String> sensitive = new LinkedHashMap<>();
        for (String key : List.of("identityNumber", "idNumber", "idCard", "phone", "contactPhone", "visitNo", "admissionNo", "medicalRecordNo", "bedNo", "address", "patientName", "contactName")) {
            String value = text(patient, key);
            if (!value.isBlank()) sensitive.put(key, value);
        }
        for (Map.Entry<String, String> entry : sensitive.entrySet()) {
            String value = entry.getValue();
            if ("address".equals(entry.getKey()) && value.equals(coarseAddress(value))) continue;
            int minimum = Set.of("patientName", "contactName").contains(entry.getKey()) ? 2 : 5;
            if (value.length() >= minimum && documentXml.contains(xml(value))) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "脱敏泄漏检查未通过：" + entry.getKey());
            }
        }
        if (ID_CARD_PATTERN.matcher(documentXml).find()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "脱敏泄漏检查发现身份证号");
        }
        Matcher phoneMatcher = MOBILE_PATTERN.matcher(documentXml);
        if (phoneMatcher.find()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "脱敏泄漏检查发现完整手机号");
        }
    }

    private void addEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String contentTypesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
            + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
            + "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>"
            + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
            + "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>"
            + "</Types>";
    }

    private String relationshipsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
            + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>"
            + "</Relationships>";
    }

    private String documentRelationshipsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings\" Target=\"settings.xml\"/>"
            + "</Relationships>";
    }

    private String stylesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:rPr><w:rFonts w:eastAsia=\"宋体\"/><w:sz w:val=\"22\"/></w:rPr><w:pPr><w:spacing w:after=\"120\" w:line=\"360\" w:lineRule=\"auto\"/></w:pPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:jc w:val=\"center\"/><w:spacing w:after=\"280\"/></w:pPr><w:rPr><w:b/><w:rFonts w:eastAsia=\"黑体\"/><w:sz w:val=\"36\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Subtitle\"><w:name w:val=\"Subtitle\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:jc w:val=\"center\"/><w:spacing w:after=\"300\"/></w:pPr><w:rPr><w:color w:val=\"666666\"/><w:sz w:val=\"19\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:keepNext/><w:spacing w:before=\"260\" w:after=\"140\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:rFonts w:eastAsia=\"黑体\"/><w:sz w:val=\"28\"/><w:color w:val=\"1F4E78\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:keepNext/><w:spacing w:before=\"180\" w:after=\"100\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"24\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Abnormal\"><w:name w:val=\"Abnormal\"/><w:basedOn w:val=\"Normal\"/><w:rPr><w:b/><w:color w:val=\"C00000\"/></w:rPr><w:pPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"FDE9E7\"/></w:pPr></w:style>"
            + "</w:styles>";
    }

    private String settingsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:settings xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:updateFields w:val=\"true\"/></w:settings>";
    }

    private String corePropertiesXml() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
            + "<dc:title>脱敏前置病历资料</dc:title><dc:creator>病历协同系统</dc:creator><cp:lastModifiedBy>病历协同系统</cp:lastModifiedBy>"
            + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:created></cp:coreProperties>";
    }

    private String appPropertiesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\"><Application>病历协同系统</Application></Properties>";
    }

    private String patientAge(JsonNode patient) {
        String age = text(patient, "age");
        if (!age.isBlank()) return age.matches(".*[岁月天]$") ? age : age + "岁";
        String birthDate = text(patient, "birthDate");
        if (birthDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                int years = java.time.Period.between(java.time.LocalDate.parse(birthDate), java.time.LocalDate.now()).getYears();
                if (years >= 0) return years + "岁";
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private String routeLabel(String value) {
        return switch (safe(value)) {
            case "OUTPATIENT" -> "门诊";
            case "INPATIENT" -> "住院";
            default -> "";
        };
    }

    private String treatmentPathLabel(String value) {
        return switch (safe(value)) {
            case "CONSERVATIVE" -> "保守治疗";
            case "SURGICAL" -> "手术治疗";
            default -> "";
        };
    }

    private String auxiliaryTaskLabel(String value) {
        return switch (safe(value)) {
            case "LAB" -> "检验";
            case "ECG" -> "心电";
            case "IMAGING" -> "影像";
            case "VITAL_SIGNS" -> "生命体征";
            case "COLONOSCOPY" -> "肠镜";
            default -> "辅助检查";
        };
    }

    private static Map<String, String> buildFieldLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        String[][] values = {
            {"patientName", "姓名"}, {"gender", "性别"}, {"age", "年龄"}, {"phone", "联系电话"}, {"address", "地址"},
            {"contactName", "联系人"}, {"contactRelation", "联系人关系"}, {"contactPhone", "联系人电话"}, {"patientSource", "患者来源"}, {"registrationNote", "登记备注"},
            {"nationality", "民族"}, {"nativePlace", "籍贯"}, {"birthplace", "出生地"}, {"maritalStatus", "婚姻状态"}, {"admissionMethod", "入院方式"},
            {"insuranceType", "参保险种"}, {"paymentMethod", "付费方式"}, {"ward", "病区"}, {"admissionCount", "第几次入院"},
            {"examinationDirection", "检查方向"}, {"diseaseDirections", "病种方向"}, {"examinationTypes", "已完成检查"}, {"lesionLocation", "病变位置"}, {"clockPosition", "钟点位"},
            {"lesionSize", "病灶大小"}, {"lesionExtent", "病灶范围"}, {"lesionDepth", "病灶深度"}, {"visualFindings", "外观所见"},
            {"digitalExamFindings", "指检所见"}, {"anoscopyFindings", "镜下/肛门镜所见"}, {"otherFindings", "其他客观表现"}, {"factualConclusion", "检查事实结论"},
            {"chiefComplaint", "主诉症状"}, {"symptomDuration", "主要症状病程"}, {"chiefComplaintText", "主诉"}, {"onsetTrigger", "起病诱因"}, {"symptomPattern", "症状发作方式"}, {"symptomChanges", "症状变化"},
            {"aggravatingFactors", "加重诱因"}, {"bleedingFeatures", "便血特征"}, {"painFeatures", "疼痛特征"}, {"prolapseReduction", "脱出与回纳"}, {"associatedSymptoms", "伴随症状"},
            {"recentAggravation", "近期加重情况"}, {"previousTreatment", "既往相关治疗"}, {"generalCondition", "一般情况"}, {"stoolFrequency", "大便频次"}, {"stoolCharacteristics", "大便性状"},
            {"presentIllness", "现病史"}, {"chronicDiseaseItems", "慢性病史明细"}, {"surgicalHistoryItems", "手术史明细"}, {"disease", "疾病"}, {"course", "病程"},
            {"duration", "病程"}, {"treatment", "治疗情况"}, {"control", "控制情况"}, {"year", "年份"}, {"operationName", "手术名称"}, {"site", "部位"}, {"result", "结果"}, {"outcome", "结果"},
            {"pastHistory", "慢性病及重要既往史"}, {"surgicalHistory", "手术史"}, {"traumaHistory", "外伤史"}, {"transfusionHistory", "输血史"},
            {"vaccinationHistory", "预防接种史"}, {"medicationHistory", "用药史"}, {"allergyHistory", "过敏史"}, {"personalHistory", "个人史"}, {"maritalHistory", "婚育史"},
            {"familyHistory", "家族史"}, {"historySupplement", "病史补充"}, {"specialCircumstances", "其他特殊情况"}, {"reviewOpinion", "检查材料回看意见"}, {"nextStepRecommendation", "下一步处置建议"},
            {"dispositionSuggestion", "建议就诊分支"}, {"recommendedAuxiliaryExams", "建议辅助检查"},
            {"tcmDisease", "中医病名"}, {"primarySyndrome", "主证"}, {"concurrentSyndrome", "兼证"}, {"inspection", "望诊"}, {"auscultationOlfaction", "闻诊"}, {"inquiry", "问诊"}, {"palpation", "切诊"},
            {"tongue", "舌象"}, {"pulse", "脉象"}, {"syndromeBasis", "辨证依据"}, {"treatmentPrinciple", "治法治则"},
            {"comorbidTcmItems", "合并病中医辨证明细"}, {"westernComorbidity", "西医合并症"}, {"includedInTcm", "是否纳入中医辨证"}, {"syndrome", "证型"}, {"note", "补充说明"},
            {"finalRoute", "最终就诊分支"}, {"primaryWesternDiagnosis", "西医主诊断"}, {"secondaryWesternDiagnoses", "西医次诊断"}, {"secondaryDiagnosisItems", "西医次诊断与合并症"},
            {"name", "诊断名称"}, {"category", "分类"}, {"diagnosisEvidence", "诊断依据来源"}, {"diagnosisBasis", "诊断依据"}, {"differentialDiagnoses", "待排/鉴别诊断"},
            {"treatmentPath", "治疗方式"}, {"treatmentMeasures", "主要措施"}, {"medicationDirections", "用药方向"}, {"examPlans", "检查安排"},
            {"surgeryArrangements", "手术安排"}, {"observationFocus", "观察重点"}, {"treatmentPlan", "治疗方案"}, {"admissionSeverity", "入院病情"}, {"treatmentCategory", "治疗类别"},
            {"plannedPrimaryOperation", "拟行主术式"}, {"plannedSecondaryOperations", "拟行次术式/附加操作"}, {"operationIndications", "手术指征"},
            {"plannedOperationName", "拟行手术"}, {"plannedOperationSite", "拟手术部位"}, {"plannedOperationPlan", "手术计划"}, {"recommendedAnesthesia", "麻醉建议"},
            {"operationGrade", "手术等级"}, {"specialOperationPlan", "特殊计划"},
            {"actualPrimaryOperation", "实际主术式"}, {"actualSecondaryOperations", "实际次术式/附加操作"}, {"actualOperationName", "实际手术名称"},
            {"operationDate", "手术日期"}, {"operationStartTime", "开始时间"}, {"operationEndTime", "结束时间"}, {"operationSite", "手术部位"},
            {"anesthesiaMethod", "麻醉方式"}, {"intraoperativeFindings", "术中所见"}, {"procedurePerformed", "实际实施步骤"}, {"specimenPathology", "标本/病理送检"}, {"bloodLossDrainDressing", "出血、引流及敷料"},
            {"bloodLossMeasurement", "术中出血量"}, {"drainageOptions", "引流"}, {"dressingOptions", "敷料"}, {"complications", "异常或并发症"},
            {"postoperativeDestination", "术后去向"}, {"postoperativeHandoff", "术后交接"}, {"physicianConfirmedBy", "手术确认医生"}, {"physicianConfirmedAt", "确认时间"},
            {"taskType", "检查类型"}, {"title", "任务名称"}, {"project", "项目"}, {"sampledAt", "采样时间"}, {"reportedAt", "报告时间"}, {"result", "结果"}, {"abnormalItems", "异常项"},
            {"conclusion", "结论"}, {"examinedAt", "检查时间"}, {"findings", "主要表现"}, {"modality", "检查方式"}, {"bodyPart", "检查部位"}, {"rawReport", "原始报告"},
            {"measuredAt", "测量时间"}, {"systolicBp", "收缩压"}, {"diastolicBp", "舒张压"}, {"temperature", "体温"}, {"pulse", "脉搏"}, {"respiration", "呼吸"},
            {"nursingConditions", "特殊护理情况"}, {"status", "肠镜状态"}, {"scope", "检查范围"}, {"lesionCount", "病变数量"}, {"lesionMorphology", "病变形态"},
            {"biopsyPerformed", "活检"}, {"resectionPerformed", "切除"}, {"pathologySubmitted", "病理送检"}, {"abnormalDescription", "异常特殊描述"},
            {"reviewedAt", "复核时间"}, {"reviewerRole", "复核岗位"}, {"statement", "说明"}
        };
        for (String[] value : values) labels.put(value[0], value[1]);
        return Map.copyOf(labels);
    }

    private boolean isEmpty(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return true;
        if (value.isTextual()) return value.asText().isBlank();
        if (value.isArray() || value.isObject()) return value.isEmpty();
        return false;
    }

    private void putIfPresent(ObjectNode node, String key, String value) {
        if (value != null && !value.isBlank()) node.put(key, value);
    }

    private String line(String label, String value) {
        return safe(value).isBlank() ? "" : label + "：" + value;
    }

    private String optionalSuffix(String value) {
        return safe(value).isBlank() ? "" : "｜" + value;
    }

    private String text(JsonNode node, String key) {
        if (node == null) return "";
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    private String xml(String value) {
        return safe(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}


