package com.coshare.patientrecord.ai.service;

import com.coshare.patientrecord.ai.dto.AiDocumentRequest;
import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class AiDocumentTaskService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ClinicAiDocumentService documentService;
    private final ThreadPoolTaskExecutor executor;

    public AiDocumentTaskService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        ClinicAiDocumentService documentService,
        @Qualifier("aiDocumentTaskExecutor") ThreadPoolTaskExecutor executor
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.documentService = documentService;
        this.executor = executor;
    }

    public Map<String, Object> submit(AiDocumentRequest request, SessionUser user) {
        documentService.preview(request, user);
        String id = "aitask-" + UUID.randomUUID();
        String now = now();
        jdbcTemplate.update(
            """
            INSERT INTO clinic_ai_document_tasks (
              id, status, request_json, attempt, created_by, created_by_role, created_at, updated_at
            ) VALUES (?, 'PENDING', CAST(? AS JSON), 1, ?, ?, ?, ?)
            """,
            id,
            toJson(request),
            user.name(),
            user.role(),
            now,
            now
        );
        enqueue(id, request, user);
        return status(id, user);
    }

    public Map<String, Object> retry(String id, SessionUser user) {
        Map<String, Object> current = loadOwnedTask(id, user);
        if (!"FAILED".equals(current.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有失败的 AI 任务可以重试");
        }
        AiDocumentRequest request = readRequest(String.valueOf(current.get("requestJson")));
        int updated = jdbcTemplate.update(
            """
            UPDATE clinic_ai_document_tasks
            SET status = 'PENDING', error_message = NULL, result_json = NULL,
                started_at = NULL, finished_at = NULL, attempt = attempt + 1, updated_at = ?
            WHERE id = ? AND status = 'FAILED'
            """,
            now(),
            id
        );
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "任务状态已变化，请刷新后重试");
        enqueue(id, request, user);
        return status(id, user);
    }

    public Map<String, Object> status(String id, SessionUser user) {
        Map<String, Object> row = loadOwnedTask(id, user);
        row.remove("requestJson");
        Object resultJson = row.remove("resultJson");
        if (resultJson != null && !String.valueOf(resultJson).isBlank()) {
            try {
                row.put("result", objectMapper.readValue(String.valueOf(resultJson), new TypeReference<Map<String, Object>>() {}));
            } catch (JsonProcessingException error) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 任务结果损坏");
            }
        }
        return row;
    }

    public Map<String, Object> metrics() {
        Integer failed = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM clinic_ai_document_tasks WHERE status = 'FAILED'",
            Integer.class
        );
        return Map.of(
            "active", executor.getActiveCount(),
            "queued", executor.getThreadPoolExecutor().getQueue().size(),
            "failed", failed == null ? 0 : failed
        );
    }

    private void enqueue(String id, AiDocumentRequest request, SessionUser user) {
        try {
            executor.execute(() -> run(id, request, user));
        } catch (RuntimeException error) {
            jdbcTemplate.update(
                "UPDATE clinic_ai_document_tasks SET status = 'FAILED', error_message = ?, finished_at = ?, updated_at = ? WHERE id = ?",
                "AI 任务队列已满，请稍后重试",
                now(),
                now(),
                id
            );
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 任务队列已满，请稍后重试");
        }
    }

    private void run(String id, AiDocumentRequest request, SessionUser user) {
        String startedAt = now();
        int claimed = jdbcTemplate.update(
            "UPDATE clinic_ai_document_tasks SET status = 'RUNNING', started_at = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'",
            startedAt,
            startedAt,
            id
        );
        if (claimed != 1) return;
        try {
            Map<String, Object> result = documentService.generate(request, user);
            String finishedAt = now();
            jdbcTemplate.update(
                "UPDATE clinic_ai_document_tasks SET status = 'SUCCEEDED', result_json = CAST(? AS JSON), error_message = NULL, finished_at = ?, updated_at = ? WHERE id = ? AND status = 'RUNNING'",
                toJson(result),
                finishedAt,
                finishedAt,
                id
            );
        } catch (Exception error) {
            String finishedAt = now();
            jdbcTemplate.update(
                "UPDATE clinic_ai_document_tasks SET status = 'FAILED', error_message = ?, finished_at = ?, updated_at = ? WHERE id = ? AND status = 'RUNNING'",
                errorMessage(error),
                finishedAt,
                finishedAt,
                id
            );
        }
    }

    private Map<String, Object> loadOwnedTask(String id, SessionUser user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        List<Map<String, Object>> rows = jdbcTemplate.query(
            """
            SELECT id, status, request_json, result_json, error_message, attempt,
                   created_by, created_by_role, created_at, started_at, finished_at, updated_at
            FROM clinic_ai_document_tasks WHERE id = ? LIMIT 1
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("taskId", rs.getString("id"));
                row.put("status", rs.getString("status"));
                row.put("requestJson", rs.getString("request_json"));
                row.put("resultJson", rs.getString("result_json"));
                row.put("errorMessage", rs.getString("error_message"));
                row.put("attempt", rs.getInt("attempt"));
                row.put("createdBy", rs.getString("created_by"));
                row.put("createdByRole", rs.getString("created_by_role"));
                row.put("createdAt", rs.getString("created_at"));
                row.put("startedAt", rs.getString("started_at"));
                row.put("finishedAt", rs.getString("finished_at"));
                row.put("updatedAt", rs.getString("updated_at"));
                return row;
            },
            id
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI 任务不存在");
        Map<String, Object> row = rows.get(0);
        if (!"admin".equals(user.role()) && !user.name().equals(row.get("createdBy"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI 任务不存在");
        }
        return row;
    }

    private AiDocumentRequest readRequest(String json) {
        try {
            return objectMapper.readValue(json, AiDocumentRequest.class);
        } catch (JsonProcessingException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 任务请求损坏");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialize AI task", error);
        }
    }

    private String errorMessage(Exception error) {
        String message = error instanceof ResponseStatusException response && response.getReason() != null
            ? response.getReason()
            : error.getMessage();
        if (message == null || message.isBlank()) message = "AI 文稿生成失败";
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
