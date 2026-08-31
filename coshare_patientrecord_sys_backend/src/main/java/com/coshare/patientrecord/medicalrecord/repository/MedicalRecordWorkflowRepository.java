package com.coshare.patientrecord.medicalrecord.repository;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.medicalrecord.ooxml.DocxNodeMapper;
import com.coshare.patientrecord.medicalrecord.ooxml.DocxPackageSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("mysql")
public class MedicalRecordWorkflowRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MedicalRecordWorkflowRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insertAsset(Asset asset, SessionUser user) {
        jdbcTemplate.update("""
            INSERT INTO clinic_medical_record_document_assets (
              id, scope_id, patient_id, encounter_id, asset_type, status, original_file_name,
              storage_path, mime_type, file_size, sha256, parent_asset_id, media_type_verified,
              package_verified, metadata_json, created_by, created_by_role
            ) VALUES (?, ?, NULLIF(?, ''), NULLIF(?, ''), ?, 'ACTIVE', ?, ?, ?, ?, ?, NULLIF(?, ''), ?, ?, CAST(? AS JSON), ?, ?)
            """,
            asset.id(), asset.scopeId(), asset.patientId(), asset.encounterId(), asset.assetType(),
            asset.originalFileName(), asset.storagePath(), asset.mimeType(), asset.fileSize(), asset.sha256(),
            asset.parentAssetId(), asset.mediaTypeVerified(), asset.packageVerified(), toJson(asset.metadata()),
            user.name(), user.role()
        );
    }

    public Asset loadAsset(String id) {
        List<Asset> rows = jdbcTemplate.query("""
            SELECT id, scope_id, patient_id, encounter_id, asset_type, original_file_name, storage_path,
                   mime_type, file_size, sha256, parent_asset_id, media_type_verified, package_verified,
                   metadata_json
            FROM clinic_medical_record_document_assets
            WHERE id = ? AND status = 'ACTIVE'
            LIMIT 1
            """, (rs, rowNum) -> readAsset(rs), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "病历文档资产不存在");
        return rows.get(0);
    }

    public java.util.Optional<Asset> findActiveSourceAsset(String scopeId, String sha256) {
        List<Asset> rows = jdbcTemplate.query("""
            SELECT id, scope_id, patient_id, encounter_id, asset_type, original_file_name, storage_path,
                   mime_type, file_size, sha256, parent_asset_id, media_type_verified, package_verified,
                   metadata_json
            FROM clinic_medical_record_document_assets
            WHERE scope_id = ? AND sha256 = ? AND asset_type = 'SOURCE' AND status = 'ACTIVE'
            ORDER BY created_at DESC
            LIMIT 1
            """, (rs, rowNum) -> readAsset(rs), scopeId, sha256);
        return rows.stream().findFirst();
    }

    public record PreAiExportRef(String id, String encounterId, String status, Object maskedSnapshot) {}

    public java.util.Optional<PreAiExportRef> findPreAiExport(String id) {
        List<PreAiExportRef> rows = jdbcTemplate.query("""
            SELECT id, encounter_id, status, masked_snapshot
            FROM pre_ai_exports
            WHERE id = ?
            LIMIT 1
            """, (rs, rowNum) -> new PreAiExportRef(
                rs.getString("id"),
                safe(rs.getString("encounter_id")),
                rs.getString("status"),
                readJson(rs.getString("masked_snapshot"))
            ), id);
        return rows.stream().findFirst();
    }

    public void insertReport(
        String id,
        String sourceAssetId,
        String sanitizedAssetId,
        DocxPackageSanitizer.Result result,
        SessionUser user
    ) {
        String failureCode = result.decision() == DocxPackageSanitizer.Decision.REJECTED
            && !result.findings().isEmpty() ? result.findings().get(result.findings().size() - 1).code() : "";
        String failureMessage = result.decision() == DocxPackageSanitizer.Decision.REJECTED
            && !result.findings().isEmpty() ? result.findings().get(result.findings().size() - 1).message() : "";
        long duplicateEntries = result.findings().stream()
            .filter(value -> "DUPLICATE_ENTRY".equals(value.code()))
            .count();
        jdbcTemplate.update("""
            INSERT INTO clinic_medical_record_sanitization_reports (
              id, source_asset_id, sanitized_asset_id, inspection_status, decision, highest_risk_level,
              sanitizer_version, zip_entry_count, compressed_size, uncompressed_size, removed_part_count,
              removed_relationship_count, external_relationship_count, duplicate_entry_count, findings_json,
              package_validation_json, failure_stage, failure_code, failure_message, inspected_by, inspected_by_role
            ) VALUES (?, ?, NULLIF(?, ''), ?, ?, ?, 'docx-package-sanitizer-v1', ?, ?, ?, ?, ?, ?, ?,
                      CAST(? AS JSON), CAST(? AS JSON), NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''), ?, ?)
            """,
            id, sourceAssetId, sanitizedAssetId,
            result.decision() == DocxPackageSanitizer.Decision.REJECTED ? "FAILED" : "COMPLETED",
            result.decision().name(), result.highestRisk().name(), result.zipEntryCount(), result.compressedSize(),
            result.uncompressedSize(), result.removedPartCount(), result.removedRelationshipCount(),
            result.externalRelationshipCount(), duplicateEntries, toJson(result.findings()),
            toJson(result.packageValidation()), result.decision() == DocxPackageSanitizer.Decision.REJECTED ? "PACKAGE_INSPECTION" : "",
            failureCode, truncate(failureMessage, 1000), user.name(), user.role()
        );
    }

    public Inspection loadInspection(String reportId) {
        List<Inspection> rows = jdbcTemplate.query("""
            SELECT r.id, r.source_asset_id, r.sanitized_asset_id, r.decision, r.highest_risk_level,
                   r.findings_json, r.package_validation_json, a.scope_id, a.patient_id, a.encounter_id
            FROM clinic_medical_record_sanitization_reports r
            JOIN clinic_medical_record_document_assets a ON a.id = r.source_asset_id
            WHERE r.id = ?
            LIMIT 1
            """, (rs, rowNum) -> new Inspection(
                rs.getString("id"), rs.getString("source_asset_id"), safe(rs.getString("sanitized_asset_id")),
                rs.getString("decision"), rs.getString("highest_risk_level"),
                readJson(rs.getString("findings_json")), readJson(rs.getString("package_validation_json")),
                rs.getString("scope_id"), safe(rs.getString("patient_id")), safe(rs.getString("encounter_id"))
            ), reportId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "病历文档检查报告不存在");
        return rows.get(0);
    }

    @Transactional
    public void insertTask(Task task, SessionUser user) {
        jdbcTemplate.update("""
            INSERT INTO clinic_medical_record_generation_tasks (
              id, scope_id, patient_id, encounter_id, source_record_id, source_asset_id, sanitized_asset_id,
              sanitization_report_id, status, current_stage, mapping_mode, prompt_text, request_json,
              attempt_count, retry_of_task_id, created_by, created_by_role
            ) VALUES (?, ?, NULLIF(?, ''), NULLIF(?, ''), ?, ?, NULLIF(?, ''), NULLIF(?, ''), 'PENDING', 'QUEUED', ?, ?,
                      CAST(? AS JSON), ?, NULLIF(?, ''), ?, ?)
            """,
            task.id(), task.scopeId(), task.patientId(), task.encounterId(), task.sourceRecordId(),
            task.sourceAssetId(), task.sanitizedAssetId(), task.reportId(), task.mappingMode(), task.prompt(),
            toJson(task.request()), task.attemptCount(), task.retryOfTaskId(), user.name(), user.role()
        );
        appendEvent(task.id(), "TASK_CREATED", "QUEUED", null, "PENDING", "生成任务已创建", Map.of(), user);
    }

    /** 章节进度事件：检查室收束流式反馈（按章节追加，消息截断至 1000 字）。 */
    public void appendProgressEvent(String taskId, String message, SessionUser user) {
        appendEvent(taskId, "CHAPTER_PROGRESS", "AI_GENERATION", "RUNNING", "RUNNING", message, Map.of(), user);
    }

    public boolean claimTask(String id) {
        int updated = jdbcTemplate.update("""
            UPDATE clinic_medical_record_generation_tasks
            SET status = 'RUNNING', current_stage = 'ASSET_LOADING', started_at = CURRENT_TIMESTAMP(3)
            WHERE id = ? AND status = 'PENDING'
            """, id);
        return updated == 1;
    }

    public void updateStage(String id, String stage, String message, SessionUser user) {
        jdbcTemplate.update("""
            UPDATE clinic_medical_record_generation_tasks
            SET current_stage = ?
            WHERE id = ? AND status = 'RUNNING'
            """, stage, id);
        appendEvent(id, "STAGE_CHANGED", stage, "RUNNING", "RUNNING", message, Map.of(), user);
    }

    @Transactional
    public void completeTask(
        String id,
        String outputAssetId,
        String resultRecordId,
        String modelName,
        Map<String, Object> result,
        SessionUser user
    ) {
        int updated = jdbcTemplate.update("""
            UPDATE clinic_medical_record_generation_tasks
            SET output_asset_id = ?, result_record_id = ?, status = 'SUCCEEDED', current_stage = 'COMPLETED',
                model_name = ?, result_json = CAST(? AS JSON), error_code = NULL, error_message = NULL,
                finished_at = CURRENT_TIMESTAMP(3)
            WHERE id = ? AND status = 'RUNNING'
            """, outputAssetId, resultRecordId, modelName, toJson(result), id);
        if (updated != 1) {
            throw new IllegalStateException("病历生成任务完成状态更新冲突");
        }
        appendEvent(id, "TASK_SUCCEEDED", "COMPLETED", "RUNNING", "SUCCEEDED", "病历草稿生成完成", Map.of(), user);
    }

    public void failTask(String id, String stage, String code, String message, SessionUser user) {
        jdbcTemplate.update("""
            UPDATE clinic_medical_record_generation_tasks
            SET status = 'FAILED', current_stage = ?, error_code = ?, error_message = ?, finished_at = CURRENT_TIMESTAMP(3)
            WHERE id = ? AND status IN ('PENDING', 'RUNNING')
            """, stage, truncate(code, 64), truncate(message, 1000), id);
        appendEvent(id, "TASK_FAILED", stage, null, "FAILED", truncate(message, 1000), Map.of("errorCode", code), user);
    }

    public TaskRow loadOwnedTask(String id, SessionUser user) {
        List<TaskRow> rows = jdbcTemplate.query("""
            SELECT id, scope_id, patient_id, encounter_id, source_record_id, source_asset_id,
                   sanitized_asset_id, sanitization_report_id, output_asset_id, result_record_id,
                   status, current_stage, mapping_mode, prompt_text, model_name, request_json, result_json,
                   attempt_count, retry_of_task_id, error_code, error_message, created_by, created_by_role,
                   created_at, started_at, finished_at, updated_at
            FROM clinic_medical_record_generation_tasks
            WHERE id = ?
            LIMIT 1
            """, (rs, rowNum) -> readTask(rs), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "病历生成任务不存在");
        TaskRow row = rows.get(0);
        if (user == null || (!"admin".equals(user.role()) && !user.name().equals(row.createdBy()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "病历生成任务不存在");
        }
        return row;
    }

    public List<Map<String, Object>> events(String taskId) {
        return jdbcTemplate.query("""
            SELECT sequence_no, event_type, stage, from_status, to_status, message, detail_json,
                   operator_name, operator_role, occurred_at
            FROM clinic_medical_record_task_events
            WHERE task_id = ?
            ORDER BY sequence_no
            """, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sequenceNo", rs.getInt("sequence_no"));
                row.put("eventType", rs.getString("event_type"));
                row.put("stage", rs.getString("stage"));
                row.put("fromStatus", safe(rs.getString("from_status")));
                row.put("toStatus", safe(rs.getString("to_status")));
                row.put("message", safe(rs.getString("message")));
                row.put("detail", readMap(rs.getString("detail_json")));
                row.put("operatorName", safe(rs.getString("operator_name")));
                row.put("operatorRole", safe(rs.getString("operator_role")));
                row.put("occurredAt", String.valueOf(rs.getTimestamp("occurred_at")));
                return row;
            }, taskId);
    }

    @Transactional
    public void replaceMappings(String taskId, DocxNodeMapper.MappingPlan plan) {
        jdbcTemplate.update("DELETE FROM clinic_medical_record_node_mappings WHERE task_id = ?", taskId);
        for (DocxNodeMapper.Mapping mapping : plan.mappings()) {
            jdbcTemplate.update("""
                INSERT INTO clinic_medical_record_node_mappings (
                  id, task_id, sequence_no, source_node_key, target_node_key, source_locator_type,
                  source_locator, target_locator_type, target_locator, source_content_hash, target_content_hash,
                  mapping_mode, mapping_status, confidence, before_preview, after_preview, metadata_json
                ) VALUES (?, ?, ?, ?, NULLIF(?, ''), ?, ?, NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''),
                          NULLIF(?, ''), ?, ?, ?, ?, ?, CAST(? AS JSON))
                """,
                "mrmap-" + UUID.randomUUID(), taskId, mapping.sequenceNo(), mapping.sourceNodeKey(),
                safe(mapping.targetNodeKey()), mapping.sourceLocatorType().name(), mapping.sourceLocator(),
                mapping.targetLocatorType() == null ? "" : mapping.targetLocatorType().name(),
                safe(mapping.targetLocator()), safe(mapping.sourceContentHash()), safe(mapping.targetContentHash()),
                mapping.mappingMode().name(), mapping.status().name(), mapping.confidence(), mapping.beforePreview(),
                mapping.afterPreview(), toJson(Map.of(
                    "matchBasis", mapping.matchBasis() == null ? "" : mapping.matchBasis().name()
                ))
            );
        }
    }

    public List<Map<String, Object>> mappings(String taskId) {
        return jdbcTemplate.query("""
            SELECT sequence_no, source_node_key, target_node_key, source_locator_type, source_locator,
                   target_locator_type, target_locator, source_content_hash, target_content_hash,
                   mapping_mode, mapping_status, confidence, before_preview, after_preview, metadata_json
            FROM clinic_medical_record_node_mappings
            WHERE task_id = ?
            ORDER BY sequence_no
            """, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sequenceNo", rs.getInt("sequence_no"));
                row.put("sourceNodeKey", rs.getString("source_node_key"));
                row.put("targetNodeKey", safe(rs.getString("target_node_key")));
                row.put("sourceLocatorType", rs.getString("source_locator_type"));
                row.put("sourceLocator", rs.getString("source_locator"));
                row.put("targetLocatorType", safe(rs.getString("target_locator_type")));
                row.put("targetLocator", safe(rs.getString("target_locator")));
                row.put("sourceContentHash", safe(rs.getString("source_content_hash")));
                row.put("targetContentHash", safe(rs.getString("target_content_hash")));
                row.put("mappingMode", rs.getString("mapping_mode"));
                row.put("status", rs.getString("mapping_status"));
                row.put("confidence", rs.getBigDecimal("confidence"));
                row.put("beforePreview", safe(rs.getString("before_preview")));
                row.put("afterPreview", safe(rs.getString("after_preview")));
                row.put("metadata", readMap(rs.getString("metadata_json")));
                return row;
            }, taskId);
    }

    public void linkVersionAsset(String recordId, String assetId, String taskId, String role, SessionUser user) {
        jdbcTemplate.update("""
            INSERT INTO clinic_medical_record_version_assets (
              id, record_id, asset_id, task_id, asset_role, linked_by
            ) VALUES (?, ?, ?, ?, ?, ?)
            """, "mrva-" + UUID.randomUUID(), recordId, assetId, taskId, role, user.name());
    }

    /**
     * Removes only resources created by one failed generation attempt. Input assets and the
     * sanitization report are deliberately retained because they predate the attempt and are
     * also required by retries. The dependency order matches the V13 RESTRICT foreign keys.
     */
    @Transactional
    public boolean compensateFailedRun(String taskId, String outputAssetId, String recordId) {
        List<String> statuses = jdbcTemplate.query(
            "SELECT status FROM clinic_medical_record_generation_tasks WHERE id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getString("status"),
            taskId
        );
        if (statuses.size() != 1 || !"RUNNING".equals(statuses.get(0))) return false;

        jdbcTemplate.update("DELETE FROM clinic_medical_record_version_assets WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM clinic_medical_record_node_mappings WHERE task_id = ?", taskId);
        jdbcTemplate.update("""
            UPDATE clinic_medical_record_generation_tasks
            SET output_asset_id = NULL,
                result_record_id = NULL,
                model_name = NULL,
                result_json = NULL
            WHERE id = ?
            """, taskId);
        if (!safe(outputAssetId).isBlank()) {
            jdbcTemplate.update("""
                DELETE FROM clinic_medical_record_document_assets
                WHERE id = ? AND asset_type = 'OUTPUT'
                """, outputAssetId);
        }
        if (!safe(recordId).isBlank()) {
            jdbcTemplate.update("""
                DELETE FROM clinic_generated_medical_records
                WHERE id = ? AND status = 'draft'
                """, recordId);
        }
        return true;
    }

    private void appendEvent(
        String taskId,
        String eventType,
        String stage,
        String fromStatus,
        String toStatus,
        String message,
        Map<String, Object> detail,
        SessionUser user
    ) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                jdbcTemplate.update("""
                    INSERT INTO clinic_medical_record_task_events (
                      id, task_id, sequence_no, event_type, stage, from_status, to_status, message,
                      detail_json, operator_name, operator_role
                    )
                    SELECT ?, ?, COALESCE(MAX(sequence_no), 0) + 1, ?, ?, NULLIF(?, ''), NULLIF(?, ''), ?,
                           CAST(? AS JSON), ?, ?
                    FROM clinic_medical_record_task_events
                    WHERE task_id = ?
                    """,
                    "mrevt-" + UUID.randomUUID(), taskId, eventType, stage, safe(fromStatus), safe(toStatus),
                    truncate(message, 1000), toJson(detail), user == null ? "system" : user.name(),
                    user == null ? "system" : user.role(), taskId
                );
                return;
            } catch (DuplicateKeyException error) {
                if (attempt == 2) throw error;
            }
        }
    }

    private Asset readAsset(ResultSet rs) throws SQLException {
        return new Asset(
            rs.getString("id"), rs.getString("scope_id"), safe(rs.getString("patient_id")),
            safe(rs.getString("encounter_id")), rs.getString("asset_type"), rs.getString("original_file_name"),
            rs.getString("storage_path"), rs.getString("mime_type"), rs.getLong("file_size"),
            rs.getString("sha256"), safe(rs.getString("parent_asset_id")), rs.getBoolean("media_type_verified"),
            rs.getBoolean("package_verified"), readMap(rs.getString("metadata_json"))
        );
    }

    private TaskRow readTask(ResultSet rs) throws SQLException {
        return new TaskRow(
            rs.getString("id"), rs.getString("scope_id"), safe(rs.getString("patient_id")),
            safe(rs.getString("encounter_id")), rs.getString("source_record_id"), rs.getString("source_asset_id"),
            safe(rs.getString("sanitized_asset_id")), rs.getString("sanitization_report_id"),
            safe(rs.getString("output_asset_id")), safe(rs.getString("result_record_id")), rs.getString("status"),
            rs.getString("current_stage"), rs.getString("mapping_mode"), safe(rs.getString("prompt_text")),
            safe(rs.getString("model_name")), readMap(rs.getString("request_json")), readMap(rs.getString("result_json")),
            rs.getInt("attempt_count"), safe(rs.getString("retry_of_task_id")), safe(rs.getString("error_code")),
            safe(rs.getString("error_message")), rs.getString("created_by"), rs.getString("created_by_role"),
            String.valueOf(rs.getTimestamp("created_at")), timestamp(rs, "started_at"), timestamp(rs, "finished_at"),
            String.valueOf(rs.getTimestamp("updated_at"))
        );
    }

    private String timestamp(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? "" : value.toString();
    }

    private Map<String, Object> readMap(String json) {
        Object value = readJson(json);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "病历工作流数据损坏", error);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to serialize medical record workflow data", error);
        }
    }

    private String truncate(String value, int maxLength) {
        String safe = safe(value);
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record Asset(
        String id,
        String scopeId,
        String patientId,
        String encounterId,
        String assetType,
        String originalFileName,
        String storagePath,
        String mimeType,
        long fileSize,
        String sha256,
        String parentAssetId,
        boolean mediaTypeVerified,
        boolean packageVerified,
        Map<String, Object> metadata
    ) {}

    public record Inspection(
        String reportId,
        String sourceAssetId,
        String sanitizedAssetId,
        String decision,
        String highestRiskLevel,
        Object findings,
        Object packageValidation,
        String scopeId,
        String patientId,
        String encounterId
    ) {}

    public record Task(
        String id,
        String scopeId,
        String patientId,
        String encounterId,
        String sourceRecordId,
        String sourceAssetId,
        String sanitizedAssetId,
        String reportId,
        String mappingMode,
        String prompt,
        Map<String, Object> request,
        int attemptCount,
        String retryOfTaskId
    ) {}

    public record TaskRow(
        String id,
        String scopeId,
        String patientId,
        String encounterId,
        String sourceRecordId,
        String sourceAssetId,
        String sanitizedAssetId,
        String reportId,
        String outputAssetId,
        String resultRecordId,
        String status,
        String currentStage,
        String mappingMode,
        String prompt,
        String modelName,
        Map<String, Object> request,
        Map<String, Object> result,
        int attemptCount,
        String retryOfTaskId,
        String errorCode,
        String errorMessage,
        String createdBy,
        String createdByRole,
        String createdAt,
        String startedAt,
        String finishedAt,
        String updatedAt
    ) {}
}
