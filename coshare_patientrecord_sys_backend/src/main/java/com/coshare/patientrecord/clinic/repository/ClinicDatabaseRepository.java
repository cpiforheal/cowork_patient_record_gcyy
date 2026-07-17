package com.coshare.patientrecord.clinic.repository;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.clinic.service.builder.ClinicTimelineBuilder;
import com.coshare.patientrecord.clinic.service.inspection.ClinicMaintenanceInspector;
import com.coshare.patientrecord.clinic.service.policy.ClinicVisibilityPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("mysql")
public class ClinicDatabaseRepository {

    private final ObjectMapper objectMapper;
    private final ClinicDbReader dbReader;
    private final ClinicDbWriter dbWriter;
    private final ClinicTimelineBuilder timelineBuilder;
    private final ClinicMaintenanceInspector maintenanceInspector;
    private final ClinicVisibilityPolicy visibilityPolicy;
    private final ClinicDbMerger dbMerger;

    public ClinicDatabaseRepository(
        ObjectMapper objectMapper,
        ClinicDbReader dbReader,
        ClinicDbWriter dbWriter,
        ClinicTimelineBuilder timelineBuilder,
        ClinicMaintenanceInspector maintenanceInspector,
        ClinicVisibilityPolicy visibilityPolicy,
        ClinicDbMerger dbMerger
    ) {
        this.objectMapper = objectMapper;
        this.dbReader = dbReader;
        this.dbWriter = dbWriter;
        this.timelineBuilder = timelineBuilder;
        this.maintenanceInspector = maintenanceInspector;
        this.visibilityPolicy = visibilityPolicy;
        this.dbMerger = dbMerger;
    }

    public ObjectNode readDb() {
        return dbReader.readDb(dbWriter.currentRevision());
    }

    public ObjectNode readDbForUser(SessionUser user) {
        return visibilityPolicy.filterDbForUser(readDb(), user);
    }

    public ObjectNode prepareWritePayload(JsonNode payload, SessionUser user) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("clinic db write payload must be an object");
        }

        ObjectNode writable = ((ObjectNode) payload).deepCopy();
        stampAuditLogs(writable.path("auditLogs"), user);
        stampDocuments(writable.path("documents"), user, visibilityPolicy.isClinicAdmin(user));

        return visibilityPolicy.filterWritePayload(writable, user, dbReader.readTemplateFieldRules());
    }

    @Transactional
    public ObjectNode patchDb(JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            throw new IllegalArgumentException("clinic db patch payload must be an object");
        }
        ObjectNode db = readDb();
        dbMerger.mergePatch(db, patch);
        String revision = dbWriter.writeDb(db);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("_revision", revision);
        result.set("db", readDb());
        return result;
    }

    @Transactional
    public ObjectNode mergeDb(JsonNode incomingDb) {
        if (incomingDb == null || !incomingDb.isObject()) {
            throw new IllegalArgumentException("clinic db merge payload must be an object");
        }

        ObjectNode db = readDb();
        dbMerger.mergePatch(db, incomingDb);
        dbWriter.upsertMergedDb(db, incomingDb);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("_revision", dbWriter.updateRevision());
        result.set("db", readDb());
        return result;
    }

    public ObjectNode maintenanceStatus(Map<String, Object> fileStatus) {
        return maintenanceInspector.maintenanceStatus(dbWriter.currentRevision(), fileStatus);
    }

    @Transactional
    public ObjectNode createSnapshot() {
        ObjectNode db = readDb();
        dbWriter.saveSnapshot(db);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("savedAt", maintenanceInspector.latestSnapshotAt());
        result.put("snapshotCount", maintenanceInspector.snapshotCount());
        result.put("revision", dbWriter.currentRevision());
        return result;
    }

    public ArrayNode findDuplicatePatients() {
        return maintenanceInspector.findDuplicatePatients(readDb());
    }

    public List<String> referencedStoragePaths() {
        return maintenanceInspector.referencedStoragePaths();
    }

    public boolean canReadStoragePath(String storagePath, SessionUser user) {
        if (visibilityPolicy.isClinicAdmin(user)) return true;
        JsonNode document = dbReader.readDocumentByStoragePath(storagePath);
        if (document == null) return false;
        return visibilityPolicy.canReadStoredFile(document, user);
    }

    public ArrayNode patientTimeline(String patientId, SessionUser user) {
        if (patientId == null || patientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "patientId is required");
        }
        ObjectNode db = readDbForUser(user);
        JsonNode patient = timelineBuilder.findPatientById(db.path("patients"), patientId);
        if (patient == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found");
        }

        return timelineBuilder.build(patientId, db, patient);
    }

    @Transactional
    public String writeDb(JsonNode db) {
        return dbWriter.writeDb(db);
    }

    private void stampAuditLogs(JsonNode auditLogs, SessionUser user) {
        if (auditLogs == null || !auditLogs.isArray()) return;
        for (JsonNode auditLog : auditLogs) {
            if (!auditLog.isObject()) continue;
            ObjectNode row = (ObjectNode) auditLog;
            row.put("operator", user.name());
            row.put("role", user.roleLabel());
            row.put("operatorId", user.id());
            row.put("operatorUsername", user.username());
            row.put("operatorDepartment", user.department());
        }
    }

    private void stampDocuments(JsonNode documents, SessionUser user, boolean allowOriginalDepartment) {
        if (documents == null || !documents.isObject()) return;
        documents.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isArray()) return;
            for (JsonNode document : entry.getValue()) {
                if (!document.isObject()) continue;
                ObjectNode row = (ObjectNode) document;
                row.put("uploader", user.name());
                row.put("uploaderRole", user.role());
                row.put("operator", user.name());
                row.put("operatorRole", user.role());
                row.put("operatorId", user.id());
                row.put("operatorUsername", user.username());
                if (!allowOriginalDepartment || text(row, "department").isBlank()) {
                    row.put("department", user.department());
                }
            }
        });
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }
}
