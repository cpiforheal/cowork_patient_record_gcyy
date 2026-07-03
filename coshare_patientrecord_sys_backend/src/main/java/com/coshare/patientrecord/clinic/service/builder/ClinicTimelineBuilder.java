package com.coshare.patientrecord.clinic.service.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ClinicTimelineBuilder {

    private final ObjectMapper objectMapper;

    public ClinicTimelineBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode findPatientById(JsonNode patients, String patientId) {
        if (patients == null || !patients.isArray()) return null;
        for (JsonNode patient : patients) {
            if (patientId.equals(text(patient, "id"))) return patient;
        }
        return null;
    }

    public ArrayNode build(String patientId, ObjectNode db, JsonNode patient) {
        List<ObjectNode> events = new ArrayList<>();
        addPatientTimelineEvents(events, patient);
        addArchiveTimelineEvents(events, patientId, db.path("archive").path(patientId));
        addFollowupTimelineEvents(events, patientId, db.path("records").path(patientId).path("followupRecordsJson"));
        addDocumentTimelineEvents(events, patientId, db.path("documents").path(patientId));
        addAuditTimelineEvents(events, db.path("auditLogs"), patientId);

        events.sort((left, right) -> text(right, "time").compareTo(text(left, "time")));
        ArrayNode timeline = objectMapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (ObjectNode event : events) {
            String key = text(event, "source") + ":" + text(event, "sourceId") + ":" + text(event, "time") + ":" + text(event, "title");
            if (seen.add(key)) {
                timeline.add(event);
            }
        }
        return timeline;
    }

    private void addPatientTimelineEvents(List<ObjectNode> events, JsonNode patient) {
        String patientId = text(patient, "id");
        addTimelineEvent(events, text(patient, "createdAt"), "patient-created-" + patientId, "patient", "patient", patientId,
            "创建患者档案", "登记患者基础就诊信息", "success", text(patient, "doctor"), text(patient, "visitNo"));
        JsonNode encounters = patient.path("encounterHistory");
        if (encounters.isArray()) {
            for (JsonNode encounter : encounters) {
                addTimelineEvent(events, text(encounter, "createdAt", text(encounter, "visitDate")), "encounter-" + text(encounter, "id"),
                    "encounter", "patient", text(encounter, "id"), "就诊记录", text(encounter, "visitType") + "：" + text(encounter, "visitNo"),
                    "primary", text(encounter, "doctor"), text(encounter, "visitDate"));
            }
        }
        addTimelineEvent(events, text(patient, "updatedAt"), "patient-updated-" + patientId, "patient", "patient", patientId,
            "档案状态更新", text(patient, "status", text(patient, "currentStage")), "info", "", text(patient, "currentStage"));
    }

    private void addArchiveTimelineEvents(List<ObjectNode> events, String patientId, JsonNode archive) {
        if (archive == null || !archive.isObject()) return;
        String version = text(archive, "version");
        if (version.isBlank()) return;
        addTimelineEvent(events, text(archive, "generatedAt"), "archive-" + patientId + "-" + version, "archive", "archive", patientId,
            archive.path("submitted").asBoolean(false) ? "提交档案审核" : "档案版本记录",
            "版本：" + version, archive.path("submitted").asBoolean(false) ? "warning" : "info", "", version);
    }

    private void addFollowupTimelineEvents(List<ObjectNode> events, String patientId, JsonNode followupJson) {
        String raw = followupJson == null || followupJson.isMissingNode() || followupJson.isNull() ? "" : followupJson.asText();
        if (raw.isBlank()) return;
        try {
            JsonNode records = objectMapper.readTree(raw);
            if (!records.isArray()) return;
            int index = 0;
            for (JsonNode record : records) {
                index += 1;
                String date = text(record, "date", text(record, "nextDate"));
                String type = text(record, "type", "复查随访");
                String detail = "恢复：" + text(record, "recovery", "待记录")
                    + "；异常：" + text(record, "abnormal", "无")
                    + "；建议：" + text(record, "advice", "待记录");
                addTimelineEvent(events, date, "followup-" + patientId + "-" + text(record, "id", String.valueOf(index)), "followup",
                    "field", "followupRecordsJson", type, detail, "success", "", text(record, "onTime"));
            }
        } catch (Exception ignored) {
            addTimelineEvent(events, "", "followup-parse-" + patientId, "followup", "field", "followupRecordsJson",
                "复查随访记录", "复查随访 JSON 暂无法解析，请回到编辑页检查字段内容", "warning", "", "");
        }
    }

    private void addDocumentTimelineEvents(List<ObjectNode> events, String patientId, JsonNode documents) {
        if (documents == null || !documents.isArray()) return;
        for (JsonNode document : documents) {
            String status = text(document, "status");
            String action = "voided".equals(status) ? "附件已作废" : "新增检查检验附件";
            String detail = text(document, "fileName") + "，关联：" + text(document, "fieldLabel") + "，科室：" + text(document, "department");
            addTimelineEvent(events, text(document, "uploadedAt"), "document-upload-" + text(document, "key"), "document", "document",
                text(document, "key"), action, detail, "voided".equals(status) ? "danger" : "primary", text(document, "uploader"), text(document, "fieldLabel"));
            if ("voided".equals(status) && !text(document, "voidedAt").isBlank()) {
                addTimelineEvent(events, text(document, "voidedAt"), "document-void-" + text(document, "key"), "document", "document",
                    text(document, "key"), "作废附件", text(document, "voidReason", "未填写作废原因"), "danger", text(document, "voidedBy"), text(document, "fileName"));
            }
            if (!text(document, "restoredAt").isBlank()) {
                addTimelineEvent(events, text(document, "restoredAt"), "document-restore-" + text(document, "key"), "document", "document",
                    text(document, "key"), "恢复附件", text(document, "fileName"), "success", text(document, "restoredBy"), text(document, "fieldLabel"));
            }
        }
    }

    private void addAuditTimelineEvents(List<ObjectNode> events, JsonNode auditLogs, String patientId) {
        if (auditLogs == null || !auditLogs.isArray()) return;
        for (JsonNode log : auditLogs) {
            if (!patientId.equals(text(log, "patientId"))) continue;
            addTimelineEvent(events, text(log, "time"), text(log, "id"), "audit", text(log, "module", "audit"), text(log, "targetKey"),
                text(log, "action", "操作记录"), text(log, "detail"), "denied".equals(text(log, "result")) ? "danger" : "info",
                text(log, "operator"), text(log, "targetLabel"));
        }
    }

    private void addTimelineEvent(
        List<ObjectNode> events,
        String time,
        String id,
        String source,
        String module,
        String sourceId,
        String title,
        String detail,
        String level,
        String operator,
        String targetLabel
    ) {
        if (title == null || title.isBlank()) return;
        ObjectNode event = objectMapper.createObjectNode();
        event.put("id", id == null || id.isBlank() ? UUID.randomUUID().toString() : id);
        event.put("time", normalizeTimelineTime(time));
        event.put("source", source == null || source.isBlank() ? "system" : source);
        event.put("module", module == null || module.isBlank() ? source : module);
        event.put("sourceId", sourceId == null ? "" : sourceId);
        event.put("title", title);
        event.put("detail", detail == null ? "" : detail);
        event.put("level", level == null || level.isBlank() ? "info" : level);
        event.put("operator", operator == null ? "" : operator);
        event.put("targetLabel", targetLabel == null ? "" : targetLabel);
        events.add(event);
    }

    private String normalizeTimelineTime(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) return "0000-00-00 00:00:00";
        if (normalized.length() == 10 && normalized.charAt(4) == '-') return normalized + " 00:00:00";
        return normalized;
    }

    private String text(JsonNode node, String key) {
        return text(node, key, "");
    }

    private String text(JsonNode node, String key, String fallback) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText();
    }
}
