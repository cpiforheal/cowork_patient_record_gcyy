package com.coshare.patientrecord.ai.repository;

import com.coshare.patientrecord.ai.entity.GeneratedAiDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("mysql")
public class ClinicAiDocumentRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ClinicAiDocumentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void saveGeneratedDocument(
        String id,
        String title,
        String docType,
        String fileName,
        String filePath,
        String contentHash,
        String operator,
        String operatorRole,
        String generatedAt,
        ObjectNode rawJson
    ) {
        jdbcTemplate.update("""
            INSERT INTO clinic_generated_ai_documents (
              id, title, doc_type, file_name, file_path, content_hash,
              operator, operator_role, generated_at, raw_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            id,
            title,
            docType,
            fileName,
            filePath,
            contentHash,
            operator,
            operatorRole,
            generatedAt,
            toJson(rawJson)
        );
    }

    public ObjectNode loadDocument(String id) {
        List<GeneratedAiDocument> rows = jdbcTemplate.query(
            """
                SELECT id, title, doc_type, file_name, file_path, content_hash,
                       operator, operator_role, generated_at, raw_json
                FROM clinic_generated_ai_documents WHERE id = ? LIMIT 1
                """,
            (rs, rowNum) -> toEntity(rs),
            id
        );
        return rows.isEmpty() ? null : readJson(rows.get(0).rawJson());
    }

    private GeneratedAiDocument toEntity(ResultSet resultSet) throws java.sql.SQLException {
        return new GeneratedAiDocument(
            resultSet.getString("id"),
            resultSet.getString("title"),
            resultSet.getString("doc_type"),
            resultSet.getString("file_name"),
            resultSet.getString("file_path"),
            resultSet.getString("content_hash"),
            resultSet.getString("operator"),
            resultSet.getString("operator_role"),
            resultSet.getString("generated_at"),
            resultSet.getString("raw_json")
        );
    }

    private ObjectNode readJson(String json) {
        try {
            return (ObjectNode) objectMapper.readTree(json);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文稿记录解析失败", error);
        }
    }

    private String toJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文稿记录序列化失败", error);
        }
    }
}
