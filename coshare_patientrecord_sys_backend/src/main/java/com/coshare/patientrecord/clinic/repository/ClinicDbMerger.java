package com.coshare.patientrecord.clinic.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ClinicDbMerger {

    void mergePatch(ObjectNode db, JsonNode patch) {
        mergeArrayById(db.withArray("patients"), patch.path("patients"), "id");
        mergeRecordProperties((ObjectNode) db.path("records"), patch.path("records"));
        mergeObjectProperties((ObjectNode) db.path("archive"), patch.path("archive"));
        mergeDocuments((ObjectNode) db.path("documents"), patch.path("documents"));
        mergeArrayById(db.withArray("accounts"), patch.path("accounts"), "id");
        mergeArrayById(db.withArray("roles"), patch.path("roles"), "id");
        mergeArrayById(db.withArray("departments"), patch.path("departments"), "id");
        mergeArrayById(db.withArray("dictionaries"), patch.path("dictionaries"), "id");
        mergeArrayById(db.withArray("templateFieldRules"), patch.path("templateFieldRules"), "id");
        mergeAuditLogs(db.withArray("auditLogs"), patch.path("auditLogs"));
    }

    private void mergeRecordProperties(ObjectNode target, JsonNode values) {
        if (!values.isObject()) return;
        values.fields().forEachRemaining(entry -> {
            ObjectNode patientRecord = target.has(entry.getKey()) && target.get(entry.getKey()).isObject()
                ? (ObjectNode) target.get(entry.getKey())
                : target.putObject(entry.getKey());
            if (entry.getValue() != null && entry.getValue().isObject()) {
                entry.getValue().fields().forEachRemaining(field -> patientRecord.set(field.getKey(), field.getValue()));
            }
        });
    }

    private void mergeObjectProperties(ObjectNode target, JsonNode values) {
        if (!values.isObject()) return;
        values.fields().forEachRemaining(entry -> target.set(entry.getKey(), entry.getValue()));
    }

    private void mergeDocuments(ObjectNode target, JsonNode values) {
        if (!values.isObject()) return;
        values.fields().forEachRemaining(entry -> {
            ArrayNode documents = target.has(entry.getKey()) && target.get(entry.getKey()).isArray()
                ? (ArrayNode) target.get(entry.getKey())
                : target.putArray(entry.getKey());
            mergeArrayById(documents, entry.getValue(), "key");
        });
    }

    private void mergeAuditLogs(ArrayNode target, JsonNode values) {
        if (!values.isArray()) return;
        mergeArrayById(target, values, "id");
    }

    private void mergeArrayById(ArrayNode target, JsonNode values, String idKey) {
        if (!values.isArray()) return;
        Map<String, Integer> indexById = new LinkedHashMap<>();
        for (int index = 0; index < target.size(); index++) {
            indexById.put(text(target.get(index), idKey), index);
        }
        for (JsonNode value : values) {
            String id = text(value, idKey);
            if (!id.isBlank() && indexById.containsKey(id)) {
                target.set(indexById.get(id), value);
            } else {
                target.insert(0, value);
            }
        }
    }

    private static String text(JsonNode node, String key) {
        if (node == null) return "";
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }
}
