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
        "identityNumber", "idNumber", "idCard", "visitNo", "admissionNo", "medicalRecordNo", "bedNo"
    );
    private static final Map<String, List<String>> STAGE_FIELDS = Map.of(
        "INSPECTION", List.of("examinationDirection", "diseaseDirections", "examinationTypes", "lesionLocation", "clockPosition", "visualFindings", "digitalExamFindings", "anoscopyFindings", "otherFindings", "factualConclusion"),
        "RECEPTION", List.of(
            "chiefComplaint", "symptomDuration", "onsetTrigger", "symptomPattern", "symptomChanges", "aggravatingFactors",
            "bleedingFeatures", "painFeatures", "prolapseReduction", "associatedSymptoms", "recentAggravation",
            "previousTreatment", "generalCondition", "stoolFrequency", "stoolCharacteristics", "presentIllness",
            "pastHistory", "surgicalHistory", "traumaHistory", "transfusionHistory", "vaccinationHistory",
            "medicationHistory", "allergyHistory", "personalHistory", "maritalHistory", "familyHistory", "historySupplement",
            "reviewOpinion", "nextStepRecommendation", "dispositionSuggestion", "recommendedAuxiliaryExams"
        ),
        "TCM", List.of("tcmDisease", "primarySyndrome", "concurrentSyndrome", "inspection", "auscultationOlfaction", "inquiry", "palpation", "tongue", "pulse", "syndromeBasis", "treatmentPrinciple"),
        "DOCTOR", List.of("finalRoute", "primaryWesternDiagnosis", "secondaryWesternDiagnoses", "diagnosisBasis", "differentialDiagnoses", "treatmentPath", "treatmentPlan", "plannedOperationName", "plannedOperationSite", "plannedOperationPlan"),
        "SURGERY", List.of("actualOperationName", "operationDate", "operationStartTime", "operationEndTime", "operationSite", "anesthesiaMethod", "intraoperativeFindings", "procedurePerformed", "specimenPathology", "bloodLossDrainDressing", "complications", "postoperativeDestination", "postoperativeHandoff")
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
        putIfPresent(maskedPatient, "patientSource", text(patient, "patientSource"));
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
            for (String key : List.of("project", "sampledAt", "reportedAt", "result", "abnormalItems", "conclusion", "examinedAt", "findings", "modality", "bodyPart")) {
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
        for (String key : List.of("visitNo", "admissionNo", "medicalRecordNo", "bedNo")) {
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
        JsonNode stages = masked.path("stages");
        JsonNode reception = stages.path("RECEPTION");
        addTableSection(body, "病例信息", List.of(
            row("医院名称", "中医肛肠医院"),
            row("科室", "肛肠科"),
            row("病例标识", text(metadata, "caseToken")),
            row("就诊日期", text(metadata, "visitDate")),
            row("入院方式", text(metadata, "route")),
            row("治疗类别", text(metadata, "treatmentPath")),
            row("建议就诊分支", displayFieldValue("dispositionSuggestion", reception.path("dispositionSuggestion")))
        ));

        JsonNode patient = masked.path("patient");
        addTableSection(body, "一、基础信息", nodeRows(patient, List.of(
            "patientName", "gender", "age", "address", "phone", "contactName", "contactRelation", "contactPhone", "patientSource", "registrationNote"
        )));

        addTableSection(body, "二、主诉", nodeRows(reception, List.of("chiefComplaint", "symptomDuration")));
        addTableSection(body, "三、现病史", nodeRows(reception, List.of(
            "presentIllness", "onsetTrigger", "symptomPattern", "symptomChanges", "aggravatingFactors", "bleedingFeatures", "painFeatures",
            "prolapseReduction", "associatedSymptoms", "recentAggravation", "previousTreatment", "generalCondition", "stoolFrequency", "stoolCharacteristics"
        )));
        addTableSection(body, "四、既往史 / 个人史 / 婚育史 / 家族史", nodeRows(reception, List.of(
            "pastHistory", "surgicalHistory", "traumaHistory", "transfusionHistory", "vaccinationHistory", "medicationHistory", "allergyHistory",
            "personalHistory", "maritalHistory", "familyHistory", "historySupplement"
        )));

        JsonNode tcm = stages.path("TCM");
        addTableSection(body, "五、中医四诊", nodeRows(tcm, List.of(
            "inspection", "auscultationOlfaction", "inquiry", "palpation", "tongue", "pulse", "tcmDisease", "primarySyndrome", "syndromeBasis", "treatmentPrinciple"
        )));

        JsonNode inspection = stages.path("INSPECTION");
        addTableSection(body, "六、专科检查", nodeRows(inspection, List.of(
            "examinationDirection", "diseaseDirections", "examinationTypes", "lesionLocation", "clockPosition", "visualFindings", "digitalExamFindings",
            "anoscopyFindings", "otherFindings", "factualConclusion"
        )));

        List<DocumentRow> auxiliaryRows = new java.util.ArrayList<>();
        if (masked.has("auxiliaryTasks")) {
            for (JsonNode task : masked.path("auxiliaryTasks")) {
                String taskLabel = auxiliaryTaskLabel(text(task, "taskType")) + optionalSuffix(text(task, "title"));
                for (String key : List.of("project", "sampledAt", "reportedAt", "result", "abnormalItems", "conclusion", "examinedAt", "findings", "modality", "bodyPart")) {
                    String value = displayValue(task.path(key));
                    if (!value.isBlank()) auxiliaryRows.add(row(taskLabel + " / " + FIELD_LABELS.getOrDefault(key, key), value));
                }
            }
        }
        String recommendedExams = displayValue(reception.path("recommendedAuxiliaryExams"));
        if (!recommendedExams.isBlank()) auxiliaryRows.add(row("流程建议 / 建议辅助检查", recommendedExams));
        if (masked.has("labReports")) {
            for (JsonNode report : masked.path("labReports")) {
                String title = text(report, "templateName") + optionalSuffix(text(report, "reportDate"));
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
                    String result = value + unit + marker + (reference.isBlank() ? "" : "；参考范围：" + reference);
                    auxiliaryRows.add(row(title + " / " + label, result, !abnormal.isBlank() || "CRITICAL".equals(severity)));
                }
                if (!text(report, "remark").isBlank()) auxiliaryRows.add(row(title + " / 备注", text(report, "remark")));
            }
        }
        addTableSection(body, "七、辅助检查", auxiliaryRows);

        JsonNode doctor = stages.path("DOCTOR");
        List<DocumentRow> diagnoses = new java.util.ArrayList<>(nodeRows(tcm, List.of("tcmDisease", "primarySyndrome")));
        diagnoses.addAll(nodeRows(doctor, List.of("primaryWesternDiagnosis", "diagnosisBasis")));
        addTableSection(body, "八、中西医主诊断", diagnoses);
        addTableSection(body, "九、次诊断（已选择）", nodeRows(doctor, List.of("secondaryWesternDiagnoses", "differentialDiagnoses")));
        addTableSection(body, "十、合并病中医病名及证型", nodeRows(tcm, List.of("concurrentSyndrome")));

        JsonNode surgery = stages.path("SURGERY");
        List<DocumentRow> operations = new java.util.ArrayList<>(nodeRows(doctor, List.of(
            "plannedOperationName", "plannedOperationSite", "plannedOperationPlan"
        )));
        operations.addAll(nodeRows(surgery, List.of(
            "actualOperationName", "operationDate", "operationStartTime", "operationEndTime", "operationSite", "anesthesiaMethod", "intraoperativeFindings",
            "procedurePerformed", "specimenPathology", "bloodLossDrainDressing", "complications", "postoperativeDestination", "postoperativeHandoff"
        )));
        addTableSection(body, "十一、手术 / 操作信息", operations);
        addTableSection(body, "十二、DIP 病组与治疗路径", nodeRows(doctor, List.of(
            "finalRoute", "treatmentPath", "treatmentPlan"
        )));
        List<DocumentRow> reviewRows = new java.util.ArrayList<>(nodeRows(masked.path("review"), List.of("reviewedAt", "reviewerRole", "statement")));
        reviewRows.addAll(nodeRows(reception, List.of("reviewOpinion", "nextStepRecommendation")));
        addTableSection(body, "医生复核", reviewRows);

        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            + body
            + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>"
            + "</w:body></w:document>";
    }

    private List<DocumentRow> nodeRows(JsonNode node, List<String> keys) {
        if (node == null || !node.isObject()) return List.of();
        List<DocumentRow> rows = new java.util.ArrayList<>();
        for (String key : keys) {
            String value = displayFieldValue(key, node.path(key));
            if (!value.isBlank()) rows.add(row(FIELD_LABELS.getOrDefault(key, key), value));
        }
        return rows;
    }

    private String displayFieldValue(String key, JsonNode value) {
        String display = displayValue(value);
        return switch (key) {
            case "finalRoute", "dispositionSuggestion" -> routeLabel(display);
            case "treatmentPath" -> treatmentPathLabel(display);
            case "examinationTypes" -> java.util.stream.StreamSupport.stream(value.spliterator(), false)
                .map(JsonNode::asText)
                .map(item -> switch (item) {
                    case "VISUAL" -> "视诊";
                    case "DIGITAL" -> "指诊";
                    case "ANOSCOPY" -> "肛门镜";
                    case "OTHER" -> "其他";
                    default -> item;
                })
                .filter(item -> !item.isBlank())
                .reduce((left, right) -> left + "、" + right)
                .orElse("");
            default -> display;
        };
    }

    private void addTableSection(StringBuilder body, String title, List<DocumentRow> rows) {
        List<DocumentRow> present = rows.stream().filter(row -> row != null && !safe(row.value()).isBlank()).toList();
        if (present.isEmpty()) return;
        body.append(paragraph(title, "Heading1"));
        body.append("<w:tbl><w:tblPr><w:tblW w:w=\"9072\" w:type=\"dxa\"/><w:tblLayout w:type=\"fixed\"/>"
            + "<w:tblBorders><w:top w:val=\"single\" w:sz=\"6\" w:color=\"808080\"/><w:left w:val=\"single\" w:sz=\"6\" w:color=\"808080\"/>"
            + "<w:bottom w:val=\"single\" w:sz=\"6\" w:color=\"808080\"/><w:right w:val=\"single\" w:sz=\"6\" w:color=\"808080\"/>"
            + "<w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"B7B7B7\"/><w:insideV w:val=\"single\" w:sz=\"4\" w:color=\"B7B7B7\"/></w:tblBorders></w:tblPr>"
            + "<w:tblGrid><w:gridCol w:w=\"2100\"/><w:gridCol w:w=\"6972\"/></w:tblGrid>");
        body.append(tableRow("项目", "内容", true, false));
        present.forEach(row -> body.append(tableRow(row.label(), row.value(), false, row.abnormal())));
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

    private DocumentRow row(String label, String value) {
        return row(label, value, false);
    }

    private DocumentRow row(String label, String value, boolean abnormal) {
        return new DocumentRow(label, safe(value), abnormal);
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

    private String displayValue(JsonNode value) {
        if (value == null || value.isNull()) return "";
        if (value.isArray()) {
            return java.util.stream.StreamSupport.stream(value.spliterator(), false)
                .map(item -> item.isTextual() ? item.asText() : item.toString())
                .filter(item -> !item.isBlank())
                .reduce((left, right) -> left + "、" + right)
                .orElse("");
        }
        if (value.isObject()) return value.toString();
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
            + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:rPr><w:rFonts w:ascii=\"SimSun\" w:hAnsi=\"SimSun\" w:eastAsia=\"宋体\"/><w:sz w:val=\"21\"/></w:rPr><w:pPr><w:spacing w:after=\"80\" w:line=\"320\" w:lineRule=\"auto\"/></w:pPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:jc w:val=\"center\"/><w:spacing w:after=\"120\"/></w:pPr><w:rPr><w:b/><w:rFonts w:eastAsia=\"黑体\"/><w:sz w:val=\"32\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Subtitle\"><w:name w:val=\"Subtitle\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:jc w:val=\"center\"/><w:spacing w:after=\"180\"/></w:pPr><w:rPr><w:color w:val=\"595959\"/><w:sz w:val=\"18\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:keepNext/><w:spacing w:before=\"220\" w:after=\"80\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:rFonts w:eastAsia=\"黑体\"/><w:sz w:val=\"25\"/><w:color w:val=\"000000\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"TableText\"><w:name w:val=\"Table Text\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"0\" w:line=\"300\" w:lineRule=\"auto\"/></w:pPr><w:rPr><w:sz w:val=\"20\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"TableHeader\"><w:name w:val=\"Table Header\"/><w:basedOn w:val=\"TableText\"/><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:rPr><w:b/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Abnormal\"><w:name w:val=\"Abnormal\"/><w:basedOn w:val=\"TableText\"/><w:rPr><w:b/><w:color w:val=\"C00000\"/></w:rPr></w:style>"
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
            default -> "辅助检查";
        };
    }

    private static Map<String, String> buildFieldLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        String[][] values = {
            {"patientName", "姓名"}, {"gender", "性别"}, {"age", "年龄"}, {"phone", "联系电话"}, {"address", "地址"},
            {"contactName", "联系人"}, {"contactRelation", "联系人关系"}, {"contactPhone", "联系人电话"}, {"patientSource", "患者来源"}, {"registrationNote", "登记备注"},
            {"examinationDirection", "检查方向"}, {"diseaseDirections", "病种方向"}, {"examinationTypes", "已完成检查"}, {"lesionLocation", "病变位置"}, {"clockPosition", "钟点位"}, {"visualFindings", "外观所见"},
            {"digitalExamFindings", "指检所见"}, {"anoscopyFindings", "镜下/肛门镜所见"}, {"otherFindings", "其他客观表现"}, {"factualConclusion", "检查事实结论"},
            {"chiefComplaint", "主诉症状"}, {"symptomDuration", "主要症状病程"}, {"onsetTrigger", "起病诱因"}, {"symptomPattern", "症状发作方式"}, {"symptomChanges", "症状变化"},
            {"aggravatingFactors", "加重诱因"}, {"bleedingFeatures", "便血特征"}, {"painFeatures", "疼痛特征"}, {"prolapseReduction", "脱出与回纳"}, {"associatedSymptoms", "伴随症状"},
            {"recentAggravation", "近期加重情况"}, {"previousTreatment", "既往相关治疗"}, {"generalCondition", "一般情况"}, {"stoolFrequency", "大便频次"}, {"stoolCharacteristics", "大便性状"},
            {"presentIllness", "现病史最终文本"}, {"pastHistory", "慢性病及重要既往史"}, {"surgicalHistory", "手术史"}, {"traumaHistory", "外伤史"}, {"transfusionHistory", "输血史"},
            {"vaccinationHistory", "预防接种史"}, {"medicationHistory", "用药史"}, {"allergyHistory", "过敏史"}, {"personalHistory", "个人史"}, {"maritalHistory", "婚育史"},
            {"familyHistory", "家族史"}, {"historySupplement", "病史补充原文"}, {"reviewOpinion", "检查材料回看意见"}, {"nextStepRecommendation", "下一步处置建议"},
            {"dispositionSuggestion", "建议就诊分支"}, {"recommendedAuxiliaryExams", "建议辅助检查"},
            {"tcmDisease", "中医病名"}, {"primarySyndrome", "主证"}, {"concurrentSyndrome", "兼证"}, {"inspection", "望诊"}, {"auscultationOlfaction", "闻诊"}, {"inquiry", "问诊"}, {"palpation", "切诊"},
            {"tongue", "舌象"}, {"pulse", "脉象"}, {"syndromeBasis", "辨证依据"}, {"treatmentPrinciple", "治法治则"},
            {"finalRoute", "最终就诊分支"}, {"primaryWesternDiagnosis", "西医主诊断"}, {"secondaryWesternDiagnoses", "西医次诊断"}, {"diagnosisBasis", "诊断依据"}, {"differentialDiagnoses", "待排/鉴别诊断"},
            {"treatmentPath", "治疗方式"}, {"treatmentPlan", "治疗方案"}, {"plannedOperationName", "拟行手术"}, {"plannedOperationSite", "拟手术部位"}, {"plannedOperationPlan", "手术计划"},
            {"actualOperationName", "实际手术名称"}, {"operationDate", "手术日期"}, {"operationStartTime", "开始时间"}, {"operationEndTime", "结束时间"}, {"operationSite", "手术部位"},
            {"anesthesiaMethod", "麻醉方式"}, {"intraoperativeFindings", "术中所见"}, {"procedurePerformed", "实际实施步骤"}, {"specimenPathology", "标本/病理送检"}, {"bloodLossDrainDressing", "出血、引流及敷料"},
            {"complications", "异常或并发症"}, {"postoperativeDestination", "术后去向"}, {"postoperativeHandoff", "术后交接"},
            {"taskType", "检查类型"}, {"title", "任务名称"}, {"project", "项目"}, {"sampledAt", "采样时间"}, {"reportedAt", "报告时间"}, {"result", "结果"}, {"abnormalItems", "异常项"},
            {"conclusion", "结论"}, {"examinedAt", "检查时间"}, {"findings", "主要表现"}, {"modality", "检查方式"}, {"bodyPart", "检查部位"},
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

    private record DocumentRow(String label, String value, boolean abnormal) {}
}


