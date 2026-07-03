package com.coshare.patientrecord.clinic.service.policy;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ClinicVisibilityPolicy {

    private final ObjectMapper objectMapper;

    public ClinicVisibilityPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean isClinicAdmin(SessionUser user) {
        return user != null && ("admin".equals(user.role()) || "quality".equals(user.role()));
    }

    public ObjectNode filterDbForUser(ObjectNode db, SessionUser user) {
        if (isClinicAdmin(user)) {
            return db;
        }

        ObjectNode records = db.path("records").isObject() ? (ObjectNode) db.path("records") : objectMapper.createObjectNode();
        ObjectNode documents = db.path("documents").isObject() ? (ObjectNode) db.path("documents") : objectMapper.createObjectNode();
        db.set("archive", objectMapper.createObjectNode());
        db.set("patients", filterPatients(db.path("patients"), records, documents));
        db.set("accounts", hideVisibleAccountPasswords(currentAccountOnly(db.path("accounts"), user)));
        db.set("auditLogs", objectMapper.createArrayNode());
        return db;
    }

    public ObjectNode filterWritePayload(ObjectNode payload, SessionUser user, JsonNode templateFieldRules) {
        if (isClinicAdmin(user)) {
            return payload;
        }

        removeAdminSections(payload);
        Set<String> allowedFields = allowedFieldKeys(templateFieldRules, user.role());
        filterWritableRecords(payload.path("records"), allowedFields);
        filterWritableDocuments(payload.path("documents"), user);
        return payload;
    }

    public boolean canReadDocument(JsonNode document, SessionUser user) {
        if (document == null || !document.isObject()) return false;
        String department = text(document, "department");
        String uploaderRole = text(document, "uploaderRole", text(document, "role"));
        if (!department.isBlank() && department.equals(user.department())) return true;
        return !uploaderRole.isBlank() && uploaderRole.equals(user.role());
    }

    private void removeAdminSections(ObjectNode payload) {
        payload.remove(List.of("accounts", "roles", "departments", "dictionaries", "templateFieldRules"));
    }

    private void filterWritableRecords(JsonNode records, Set<String> allowedFields) {
        if (records == null || !records.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> patientRecords = records.fields();
        while (patientRecords.hasNext()) {
            Map.Entry<String, JsonNode> entry = patientRecords.next();
            if (!entry.getValue().isObject()) {
                patientRecords.remove();
                continue;
            }
            ObjectNode patientRecord = (ObjectNode) entry.getValue();
            Iterator<Map.Entry<String, JsonNode>> fields = patientRecord.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!allowedFields.contains(field.getKey())) {
                    fields.remove();
                }
            }
            if (patientRecord.isEmpty()) {
                patientRecords.remove();
            }
        }
    }

    private void filterWritableDocuments(JsonNode documents, SessionUser user) {
        if (documents == null || !documents.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> patientDocuments = documents.fields();
        while (patientDocuments.hasNext()) {
            Map.Entry<String, JsonNode> entry = patientDocuments.next();
            if (!entry.getValue().isArray()) {
                patientDocuments.remove();
                continue;
            }
            ArrayNode filtered = objectMapper.createArrayNode();
            for (JsonNode document : entry.getValue()) {
                if (canReadDocument(document, user)) {
                    filtered.add(document);
                }
            }
            if (filtered.isEmpty()) {
                patientDocuments.remove();
            } else {
                ((ObjectNode) documents).set(entry.getKey(), filtered);
            }
        }
    }

    private Set<String> allowedFieldKeys(JsonNode templateFieldRules, String role) {
        Set<String> allowedFields = new HashSet<>();
        if (templateFieldRules != null && templateFieldRules.isArray()) {
            for (JsonNode rule : templateFieldRules) {
                if (!rule.path("enabled").asBoolean(true)) continue;
                JsonNode editors = rule.path("editors");
                if (editors.isArray()) {
                    for (JsonNode editor : editors) {
                        if (role.equals(editor.asText())) {
                            String fieldKey = text(rule, "fieldKey");
                            if (!fieldKey.isBlank()) allowedFields.add(fieldKey);
                            break;
                        }
                    }
                }
            }
        }
        return allowedFields;
    }

    private ArrayNode filterPatients(JsonNode patients, JsonNode records, JsonNode documents) {
        Set<String> visiblePatientIds = new HashSet<>();
        collectVisiblePatientIds(records, visiblePatientIds);
        collectVisiblePatientIds(documents, visiblePatientIds);

        ArrayNode filtered = objectMapper.createArrayNode();
        if (patients == null || !patients.isArray() || visiblePatientIds.isEmpty()) return filtered;
        for (JsonNode patient : patients) {
            if (visiblePatientIds.contains(text(patient, "id"))) {
                filtered.add(patient);
            }
        }
        return filtered;
    }

    private void collectVisiblePatientIds(JsonNode rowsByPatient, Set<String> patientIds) {
        if (rowsByPatient == null || !rowsByPatient.isObject()) return;
        rowsByPatient.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && !value.isNull() && !value.isMissingNode() && value.size() > 0) {
                patientIds.add(entry.getKey());
            }
        });
    }

    private ArrayNode currentAccountOnly(JsonNode accounts, SessionUser user) {
        ArrayNode filtered = objectMapper.createArrayNode();
        if (accounts == null || !accounts.isArray()) return filtered;
        for (JsonNode account : accounts) {
            if (user.id().equals(text(account, "id")) || user.username().equalsIgnoreCase(text(account, "username"))) {
                filtered.add(account);
            }
        }
        return filtered;
    }

    private ArrayNode hideVisibleAccountPasswords(JsonNode accounts) {
        ArrayNode filtered = objectMapper.createArrayNode();
        if (accounts == null || !accounts.isArray()) return filtered;
        for (JsonNode account : accounts) {
            ObjectNode visible = account != null && account.isObject() ? (ObjectNode) account.deepCopy() : objectMapper.createObjectNode();
            visible.remove(List.of("password", "passwordHash", "currentPassword"));
            filtered.add(visible);
        }
        return filtered;
    }

    private static String text(JsonNode node, String key) {
        return text(node, key, "");
    }

    private static String text(JsonNode node, String key, String fallback) {
        if (node == null) return fallback;
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText();
    }
}
