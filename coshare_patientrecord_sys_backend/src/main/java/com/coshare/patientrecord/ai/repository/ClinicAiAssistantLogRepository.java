package com.coshare.patientrecord.ai.repository;

import com.coshare.patientrecord.ai.entity.AiAssistantLog;
import com.coshare.patientrecord.ai.entity.AiPromptTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class ClinicAiAssistantLogRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ClinicAiAssistantLogRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void saveLog(
        String id,
        String createdAt,
        String assistantType,
        String status,
        String operatorId,
        String operatorName,
        String operatorRole,
        String operatorDepartment,
        String pageSource,
        String pagePath,
        String model,
        long latencyMs,
        String intentCategory,
        String promptPreview,
        String errorMessage,
        boolean templateCandidate,
        ObjectNode rawJson
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO clinic_ai_assistant_logs (
              id, created_at, assistant_type, status, operator_id, operator_name, operator_role,
              operator_department, page_source, page_path, model, latency_ms, intent_category,
              prompt_preview, error_message, template_candidate, raw_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            id,
            createdAt,
            assistantType,
            status,
            operatorId,
            operatorName,
            operatorRole,
            operatorDepartment,
            pageSource,
            pagePath,
            model,
            latencyMs,
            intentCategory,
            promptPreview,
            errorMessage,
            templateCandidate,
            toJson(rawJson)
        );
    }

    public List<ObjectNode> queryLogs(int limit) {
        return jdbcTemplate.query(
            """
                SELECT id, created_at, assistant_type, status, operator_id, operator_name,
                       operator_role, operator_department, page_source, page_path, model,
                       latency_ms, intent_category, prompt_preview, error_message,
                       template_candidate, raw_json
                FROM clinic_ai_assistant_logs
                ORDER BY created_at DESC, id DESC LIMIT ?
                """,
            (resultSet, rowNum) -> readJson(toLogEntity(resultSet).rawJson()),
            limit
        ).stream().filter(node -> !node.isEmpty()).toList();
    }

    public ObjectNode findLog(String id) {
        List<ObjectNode> rows = jdbcTemplate.query(
            """
                SELECT id, created_at, assistant_type, status, operator_id, operator_name,
                       operator_role, operator_department, page_source, page_path, model,
                       latency_ms, intent_category, prompt_preview, error_message,
                       template_candidate, raw_json
                FROM clinic_ai_assistant_logs WHERE id = ? LIMIT 1
                """,
            (resultSet, rowNum) -> readJson(toLogEntity(resultSet).rawJson()),
            safe(id)
        ).stream().filter(node -> !node.isEmpty()).toList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ObjectNode> queryTemplates() {
        return jdbcTemplate.query(
            """
                SELECT id, created_at, assistant_type, title, role_scope, source_log_id, raw_json
                FROM clinic_ai_prompt_templates
                ORDER BY created_at DESC, id DESC LIMIT 200
                """,
            (resultSet, rowNum) -> readJson(toTemplateEntity(resultSet).rawJson())
        ).stream().filter(node -> !node.isEmpty()).toList();
    }

    public void saveTemplate(
        String id,
        String createdAt,
        String assistantType,
        String title,
        String roleScope,
        String sourceLogId,
        ObjectNode rawJson
    ) {
        jdbcTemplate.update(
            "INSERT INTO clinic_ai_prompt_templates (id, created_at, assistant_type, title, role_scope, source_log_id, raw_json) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id,
            createdAt,
            assistantType,
            title,
            roleScope,
            sourceLogId,
            toJson(rawJson)
        );
    }

    public void markTemplateCandidate(String logId, ObjectNode rawJson) {
        jdbcTemplate.update(
            "UPDATE clinic_ai_assistant_logs SET template_candidate = TRUE, raw_json = ? WHERE id = ?",
            toJson(rawJson),
            logId
        );
    }

    private ObjectNode readJson(String rawJson) {
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            if (node != null && node.isObject()) {
                return (ObjectNode) node;
            }
            return objectMapper.createObjectNode();
        } catch (Exception error) {
            return objectMapper.createObjectNode();
        }
    }

    private AiAssistantLog toLogEntity(ResultSet resultSet) throws java.sql.SQLException {
        return new AiAssistantLog(
            resultSet.getString("id"),
            resultSet.getString("created_at"),
            resultSet.getString("assistant_type"),
            resultSet.getString("status"),
            resultSet.getString("operator_id"),
            resultSet.getString("operator_name"),
            resultSet.getString("operator_role"),
            resultSet.getString("operator_department"),
            resultSet.getString("page_source"),
            resultSet.getString("page_path"),
            resultSet.getString("model"),
            resultSet.getLong("latency_ms"),
            resultSet.getString("intent_category"),
            resultSet.getString("prompt_preview"),
            resultSet.getString("error_message"),
            resultSet.getBoolean("template_candidate"),
            resultSet.getString("raw_json")
        );
    }

    private AiPromptTemplate toTemplateEntity(ResultSet resultSet) throws java.sql.SQLException {
        return new AiPromptTemplate(
            resultSet.getString("id"),
            resultSet.getString("created_at"),
            resultSet.getString("assistant_type"),
            resultSet.getString("title"),
            resultSet.getString("role_scope"),
            resultSet.getString("source_log_id"),
            resultSet.getString("raw_json")
        );
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            return "{}";
        }
    }

    private static String safe(Object value) {
        return String.valueOf(value == null ? "" : value).trim();
    }
}
