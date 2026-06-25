package com.example.coshare_patientrecord_sys;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class ClinicAiAssistantLogService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_LOGS = 500;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private volatile boolean schemaReady = false;

    public ClinicAiAssistantLogService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initializeSchema() {
        ensureSchema();
    }

    private synchronized void ensureSchema() {
        if (schemaReady) return;
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_ai_assistant_logs (
              id VARCHAR(128) PRIMARY KEY,
              created_at VARCHAR(32),
              assistant_type VARCHAR(32),
              status VARCHAR(32),
              operator_id VARCHAR(64),
              operator_name VARCHAR(100),
              operator_role VARCHAR(64),
              operator_department VARCHAR(100),
              page_source VARCHAR(100),
              page_path VARCHAR(255),
              model VARCHAR(128),
              latency_ms BIGINT DEFAULT 0,
              intent_category VARCHAR(64),
              prompt_preview VARCHAR(512),
              error_message VARCHAR(512),
              template_candidate BOOLEAN DEFAULT FALSE,
              raw_json LONGTEXT,
              INDEX idx_clinic_ai_logs_created (created_at),
              INDEX idx_clinic_ai_logs_type (assistant_type),
              INDEX idx_clinic_ai_logs_status (status),
              INDEX idx_clinic_ai_logs_operator (operator_role, operator_department)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_ai_prompt_templates (
              id VARCHAR(128) PRIMARY KEY,
              created_at VARCHAR(32),
              assistant_type VARCHAR(32),
              title VARCHAR(160),
              role_scope VARCHAR(64),
              source_log_id VARCHAR(128),
              raw_json LONGTEXT,
              INDEX idx_clinic_ai_templates_type (assistant_type),
              INDEX idx_clinic_ai_templates_created (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        ensureColumn("clinic_ai_assistant_logs", "created_at", "VARCHAR(32)");
        ensureColumn("clinic_ai_assistant_logs", "assistant_type", "VARCHAR(32)");
        ensureColumn("clinic_ai_assistant_logs", "status", "VARCHAR(32)");
        ensureColumn("clinic_ai_assistant_logs", "operator_id", "VARCHAR(64)");
        ensureColumn("clinic_ai_assistant_logs", "operator_name", "VARCHAR(100)");
        ensureColumn("clinic_ai_assistant_logs", "operator_role", "VARCHAR(64)");
        ensureColumn("clinic_ai_assistant_logs", "operator_department", "VARCHAR(100)");
        ensureColumn("clinic_ai_assistant_logs", "page_source", "VARCHAR(100)");
        ensureColumn("clinic_ai_assistant_logs", "page_path", "VARCHAR(255)");
        ensureColumn("clinic_ai_assistant_logs", "model", "VARCHAR(128)");
        ensureColumn("clinic_ai_assistant_logs", "latency_ms", "BIGINT DEFAULT 0");
        ensureColumn("clinic_ai_assistant_logs", "intent_category", "VARCHAR(64)");
        ensureColumn("clinic_ai_assistant_logs", "prompt_preview", "VARCHAR(512)");
        ensureColumn("clinic_ai_assistant_logs", "error_message", "VARCHAR(512)");
        ensureColumn("clinic_ai_assistant_logs", "template_candidate", "BOOLEAN DEFAULT FALSE");
        ensureColumn("clinic_ai_assistant_logs", "raw_json", "LONGTEXT");
        ensureColumn("clinic_ai_prompt_templates", "created_at", "VARCHAR(32)");
        ensureColumn("clinic_ai_prompt_templates", "assistant_type", "VARCHAR(32)");
        ensureColumn("clinic_ai_prompt_templates", "title", "VARCHAR(160)");
        ensureColumn("clinic_ai_prompt_templates", "role_scope", "VARCHAR(64)");
        ensureColumn("clinic_ai_prompt_templates", "source_log_id", "VARCHAR(128)");
        ensureColumn("clinic_ai_prompt_templates", "raw_json", "LONGTEXT");
        schemaReady = true;
    }

    private void ensureColumn(String table, String column, String definition) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (DataAccessException ignored) {
            // Existing pilot databases may already have this column.
        }
    }

    public void record(AssistantLogDraft draft) {
        try {
            ensureSchema();
            ObjectNode log = objectMapper.createObjectNode();
            String id = "ai-log-" + UUID.randomUUID();
            String now = LocalDateTime.now().format(TIME_FORMATTER);
            AuthSessionService.SessionUser user = draft.user();
            Map<String, Object> context = draft.context() == null ? Map.of() : draft.context();
            String assistantType = safe(draft.assistantType()).isBlank() ? "public" : safe(draft.assistantType());
            boolean patientLike = isPatientSensitive(assistantType, draft.patientId(), context);
            String prompt = safe(draft.prompt());
            String answer = safe(draft.answer());
            String sanitizedPrompt = patientLike ? desensitize(prompt) : prompt;
            String sanitizedAnswer = patientLike ? desensitize(answer) : answer;
            String intent = classifyIntent(prompt + " " + safe(context));

            log.put("id", id);
            log.put("createdAt", now);
            log.put("assistantType", assistantType);
            log.put("status", draft.success() ? "success" : "failed");
            log.put("operatorId", user == null ? "" : safe(user.id()));
            log.put("operatorName", user == null ? "" : safe(user.name()));
            log.put("operatorRole", user == null ? "" : safe(user.role()));
            log.put("operatorDepartment", user == null ? "" : safe(user.department()));
            log.put("pageSource", text(context, "source"));
            log.put("pagePath", text(context, "path"));
            log.put("pageTitle", text(context, "pageTitle"));
            log.put("model", safe(draft.model()));
            log.put("latencyMs", Math.max(0, draft.latencyMs()));
            log.put("intentCategory", intent);
            log.put("prompt", trimText(sanitizedPrompt, 2400));
            log.put("promptPreview", trimText(sanitizedPrompt, 180));
            log.put("answer", trimText(sanitizedAnswer, 3000));
            log.put("answerPreview", trimText(sanitizedAnswer, 220));
            log.put("systemPromptSummary", trimText(safe(draft.systemPromptSummary()), 1200));
            log.put("contextSummary", summarizeContext(context, draft.patientId(), draft.attachmentCount()));
            log.put("patientContextIncluded", !safe(draft.patientId()).isBlank());
            log.put("patientId", patientLike ? maskPatientId(draft.patientId()) : safe(draft.patientId()));
            log.put("attachmentCount", Math.max(0, draft.attachmentCount()));
            log.put("imageCount", Math.max(0, draft.imageCount()));
            log.put("errorMessage", trimText(safe(draft.errorMessage()), 500));
            log.put("sensitive", patientLike);
            log.put("templateCandidate", false);
            log.set("knowledgeSources", objectMapper.valueToTree(draft.knowledgeSources() == null ? List.of() : draft.knowledgeSources()));

            jdbcTemplate.update(
                """
                INSERT INTO clinic_ai_assistant_logs (
                  id, created_at, assistant_type, status, operator_id, operator_name, operator_role,
                  operator_department, page_source, page_path, model, latency_ms, intent_category,
                  prompt_preview, error_message, template_candidate, raw_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                now,
                assistantType,
                draft.success() ? "success" : "failed",
                log.path("operatorId").asText(""),
                log.path("operatorName").asText(""),
                log.path("operatorRole").asText(""),
                log.path("operatorDepartment").asText(""),
                log.path("pageSource").asText(""),
                log.path("pagePath").asText(""),
                safe(draft.model()),
                Math.max(0, draft.latencyMs()),
                intent,
                log.path("promptPreview").asText(""),
                log.path("errorMessage").asText(""),
                false,
                toJson(log)
            );
        } catch (Exception ignored) {
            // AI logging is observational. It must never block the assistant response.
        }
    }

    public Map<String, Object> logs(Map<String, String> params) {
        AuthPermission.requireAnyRole("当前账号无 AI 使用分析权限", "admin");
        try {
        Map<String, String> query = params == null ? Map.of() : params;
        List<ObjectNode> filtered = filterLogs(queryLogs(), query);
        int pageNum = parseInt(query.get("pageNum"), 1);
        int pageSize = Math.min(parseInt(query.get("pageSize"), 20), 100);
        int from = Math.max(0, (pageNum - 1) * pageSize);
        int to = Math.min(filtered.size(), from + pageSize);
        List<ObjectNode> page = from >= filtered.size() ? List.of() : filtered.subList(from, to);
        return Map.of("list", toPlainList(page), "total", filtered.size());
        } catch (DataAccessException error) {
            return Map.of("list", List.of(), "total", 0, "warning", "AI assistant logs are temporarily unavailable");
        }
    }

    public Map<String, Object> analytics(Map<String, String> params) {
        AuthPermission.requireAnyRole("当前账号无 AI 使用分析权限", "admin");
        try {
        List<ObjectNode> logs = filterLogs(queryLogs(), params == null ? Map.of() : params);
        LocalDate today = LocalDate.now();
        long todayCalls = logs.stream().filter(log -> safe(log.path("createdAt").asText()).startsWith(today.toString())).count();
        long failed = logs.stream().filter(log -> "failed".equals(log.path("status").asText())).count();
        long latencyTotal = logs.stream().mapToLong(log -> log.path("latencyMs").asLong(0)).sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCalls", logs.size());
        result.put("todayCalls", todayCalls);
        result.put("failedCalls", failed);
        result.put("failureRate", logs.isEmpty() ? 0 : Math.round(failed * 1000.0 / logs.size()) / 10.0);
        result.put("averageLatencyMs", logs.isEmpty() ? 0 : Math.round(latencyTotal * 1.0 / logs.size()));
        result.put("assistantTypeBuckets", toPlainList(bucket(logs, "assistantType", 8)));
        result.put("roleBuckets", toPlainList(bucket(logs, "operatorRole", 8)));
        result.put("departmentBuckets", toPlainList(bucket(logs, "operatorDepartment", 8)));
        result.put("intentBuckets", toPlainList(bucket(logs, "intentCategory", 10)));
        result.put("pageBuckets", toPlainList(bucket(logs, "pageTitle", 8)));
        result.put("modelErrorBuckets", toPlainList(modelErrors(logs)));
        result.put("frequentPrompts", toPlainList(frequentPrompts(logs)));
        result.put("knowledgeMisses", toPlainList(knowledgeMisses(logs)));
        return result;
        } catch (DataAccessException error) {
            Map<String, Object> result = emptyAnalytics();
            result.put("warning", "AI assistant analytics are temporarily unavailable");
            return result;
        }
    }

    public Map<String, Object> templates() {
        AuthPermission.currentUserOrThrow();
        try {
        ensureSchema();
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT raw_json FROM clinic_ai_prompt_templates ORDER BY created_at DESC, id DESC LIMIT 200",
            (resultSet, rowNum) -> readJson(resultSet.getString("raw_json"))
        ).stream().filter(node -> !node.isEmpty()).toList();
        return Map.of("list", toPlainList(rows), "total", rows.size());
        } catch (DataAccessException error) {
            return Map.of("list", List.of(), "total", 0, "warning", "AI prompt templates are temporarily unavailable");
        }
    }

    public Map<String, Object> markTemplateCandidate(String logId, Map<String, Object> payload, AuthSessionService.SessionUser user) {
        AuthPermission.requireAnyRole("当前账号无 AI 模板维护权限", "admin");
        ObjectNode log = findLog(logId);
        if (log == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI 日志不存在");
        }
        String now = LocalDateTime.now().format(TIME_FORMATTER);
        String templateId = "ai-template-" + UUID.randomUUID();
        ObjectNode template = objectMapper.createObjectNode();
        template.put("id", templateId);
        template.put("sourceLogId", logId);
        template.put("createdAt", now);
        template.put("createdBy", user == null ? "" : safe(user.name()));
        template.put("assistantType", safe(value(payload, "assistantType", log.path("assistantType").asText("public"))));
        template.put("title", trimText(safe(value(payload, "title", log.path("promptPreview").asText("模板候选"))), 120));
        template.put("roleScope", trimText(safe(value(payload, "roleScope", log.path("operatorRole").asText("all"))), 80));
        template.put("recommendedPrompt", trimText(safe(value(payload, "recommendedPrompt", log.path("prompt").asText(""))), 1200));
        template.put("contextNote", trimText(safe(value(payload, "contextNote", log.path("contextSummary").asText(""))), 600));
        template.put("status", "candidate");

        jdbcTemplate.update(
            "INSERT INTO clinic_ai_prompt_templates (id, created_at, assistant_type, title, role_scope, source_log_id, raw_json) VALUES (?, ?, ?, ?, ?, ?, ?)",
            templateId,
            now,
            template.path("assistantType").asText("public"),
            template.path("title").asText(""),
            template.path("roleScope").asText(""),
            logId,
            toJson(template)
        );
        ObjectNode updatedLog = log.deepCopy();
        updatedLog.put("templateCandidate", true);
        jdbcTemplate.update(
            "UPDATE clinic_ai_assistant_logs SET template_candidate = TRUE, raw_json = ? WHERE id = ?",
            toJson(updatedLog),
            logId
        );
        return toPlainMap(template);
    }

    private List<ObjectNode> queryLogs() {
        ensureSchema();
        return jdbcTemplate.query(
            "SELECT raw_json FROM clinic_ai_assistant_logs ORDER BY created_at DESC, id DESC LIMIT ?",
            (resultSet, rowNum) -> readJson(resultSet.getString("raw_json")),
            MAX_LOGS
        ).stream().filter(node -> !node.isEmpty()).toList();
    }

    private ObjectNode findLog(String id) {
        ensureSchema();
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT raw_json FROM clinic_ai_assistant_logs WHERE id = ? LIMIT 1",
            (resultSet, rowNum) -> readJson(resultSet.getString("raw_json")),
            safe(id)
        ).stream().filter(node -> !node.isEmpty()).toList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<ObjectNode> filterLogs(List<ObjectNode> logs, Map<String, String> params) {
        String assistantType = safe(params.get("assistantType"));
        String status = safe(params.get("status"));
        String role = safe(params.get("role"));
        String department = safe(params.get("department"));
        String intent = safe(params.get("intentCategory"));
        String keyword = safe(params.get("keyword")).toLowerCase(Locale.ROOT);
        String dateFrom = safe(params.get("dateFrom"));
        String dateTo = safe(params.get("dateTo"));
        return logs.stream()
            .filter(log -> assistantType.isBlank() || assistantType.equals(log.path("assistantType").asText("")))
            .filter(log -> status.isBlank() || status.equals(log.path("status").asText("")))
            .filter(log -> role.isBlank() || role.equals(log.path("operatorRole").asText("")))
            .filter(log -> department.isBlank() || department.equals(log.path("operatorDepartment").asText("")))
            .filter(log -> intent.isBlank() || intent.equals(log.path("intentCategory").asText("")))
            .filter(log -> dateFrom.isBlank() || log.path("createdAt").asText("").compareTo(dateFrom) >= 0)
            .filter(log -> dateTo.isBlank() || log.path("createdAt").asText("").compareTo(dateTo + " 23:59:59") <= 0)
            .filter(log -> keyword.isBlank() || safe(log).toLowerCase(Locale.ROOT).contains(keyword))
            .toList();
    }

    private ArrayNode bucket(List<ObjectNode> logs, String field, int limit) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ObjectNode log : logs) {
            String key = safe(log.path(field).asText(""));
            if (key.isBlank()) key = "未标记";
            counts.put(key, counts.getOrDefault(key, 0L) + 1);
        }
        ArrayNode result = objectMapper.createArrayNode();
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(limit)
            .forEach(entry -> {
                ObjectNode item = result.addObject();
                item.put("label", entry.getKey());
                item.put("count", entry.getValue());
            });
        return result;
    }

    private ArrayNode modelErrors(List<ObjectNode> logs) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ObjectNode log : logs) {
            if (!"failed".equals(log.path("status").asText())) continue;
            String key = safe(log.path("model").asText("未配置模型")) + " · " + trimText(log.path("errorMessage").asText("调用失败"), 80);
            counts.put(key, counts.getOrDefault(key, 0L) + 1);
        }
        ArrayNode result = objectMapper.createArrayNode();
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(8)
            .forEach(entry -> {
                ObjectNode item = result.addObject();
                item.put("label", entry.getKey());
                item.put("count", entry.getValue());
            });
        return result;
    }

    private ArrayNode frequentPrompts(List<ObjectNode> logs) {
        Map<String, PromptGroup> groups = new LinkedHashMap<>();
        for (ObjectNode log : logs) {
            String prompt = safe(log.path("promptPreview").asText());
            if (prompt.isBlank()) continue;
            String key = normalizePromptKey(prompt);
            PromptGroup group = groups.computeIfAbsent(key, ignored -> new PromptGroup(prompt, log.path("assistantType").asText("public")));
            group.count++;
            if (group.sourceLogId.isBlank()) {
                group.sourceLogId = log.path("id").asText("");
            }
        }
        ArrayNode result = objectMapper.createArrayNode();
        groups.values().stream()
            .sorted(Comparator.comparingInt((PromptGroup group) -> group.count).reversed())
            .limit(10)
            .forEach(group -> {
                ObjectNode item = result.addObject();
                item.put("prompt", group.prompt);
                item.put("assistantType", group.assistantType);
                item.put("count", group.count);
                item.put("sourceLogId", group.sourceLogId);
            });
        return result;
    }

    private ArrayNode knowledgeMisses(List<ObjectNode> logs) {
        ArrayNode result = objectMapper.createArrayNode();
        logs.stream()
            .filter(log -> safe(log.path("answer").asText()).contains("知识库未覆盖"))
            .limit(10)
            .forEach(log -> {
                ObjectNode item = result.addObject();
                item.put("id", log.path("id").asText(""));
                item.put("createdAt", log.path("createdAt").asText(""));
                item.put("prompt", log.path("promptPreview").asText(""));
                item.put("assistantType", log.path("assistantType").asText(""));
            });
        return result;
    }

    private Map<String, Object> emptyAnalytics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCalls", 0);
        result.put("todayCalls", 0);
        result.put("failedCalls", 0);
        result.put("failureRate", 0);
        result.put("averageLatencyMs", 0);
        result.put("assistantTypeBuckets", List.of());
        result.put("roleBuckets", List.of());
        result.put("departmentBuckets", List.of());
        result.put("intentBuckets", List.of());
        result.put("pageBuckets", List.of());
        result.put("modelErrorBuckets", List.of());
        result.put("frequentPrompts", List.of());
        result.put("knowledgeMisses", List.of());
        return result;
    }

    private boolean isPatientSensitive(String assistantType, String patientId, Map<String, Object> context) {
        String haystack = (assistantType + " " + safe(patientId) + " " + safe(context)).toLowerCase(Locale.ROOT);
        return "patient".equals(assistantType)
            || "quality".equals(assistantType)
            || !safe(patientId).isBlank()
            || haystack.contains("patient")
            || haystack.contains("患者")
            || haystack.contains("病历")
            || haystack.contains("档案");
    }

    private String classifyIntent(String text) {
        String value = safe(text).toLowerCase(Locale.ROOT);
        if (containsAny(value, "权限", "账号", "角色", "菜单", "登录", "permission", "role")) return "permission";
        if (containsAny(value, "质控", "审核", "退回", "归档", "缺项", "冲突", "quality", "audit")) return "quality";
        if (containsAny(value, "患者", "病历", "档案", "问诊", "诊断", "patient", "record")) return "patient";
        if (containsAny(value, "库存", "进销存", "申领", "发放", "签收", "盘点", "inventory")) return "inventory";
        if (containsAny(value, "备份", "恢复", "附件", "路径", "backup", "file")) return "backup";
        if (containsAny(value, "模型", "api", "key", "401", "豆包", "ai")) return "ai_config";
        if (containsAny(value, "流程", "怎么操作", "哪里点", "步骤", "workflow")) return "workflow";
        return "other";
    }

    private boolean containsAny(String value, String... words) {
        for (String word : words) {
            if (value.contains(word.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String summarizeContext(Map<String, Object> context, String patientId, int attachmentCount) {
        List<String> parts = new ArrayList<>();
        String source = text(context, "source");
        String pageTitle = text(context, "pageTitle");
        String path = text(context, "path");
        if (!source.isBlank()) parts.add("来源：" + source);
        if (!pageTitle.isBlank()) parts.add("页面：" + pageTitle);
        if (!path.isBlank()) parts.add("路径：" + path);
        if (!safe(patientId).isBlank()) parts.add("包含患者上下文");
        if (attachmentCount > 0) parts.add("上传材料：" + attachmentCount + " 个");
        return parts.isEmpty() ? "仅系统知识库" : String.join("；", parts);
    }

    private String desensitize(String value) {
        String text = safe(value);
        text = text.replaceAll("1[3-9]\\d{9}", "1**********");
        text = text.replaceAll("\\b\\d{15}(\\d{2}[0-9Xx])?\\b", "******************");
        text = text.replaceAll("(visitNo|就诊号|门诊号|住院号)[：:\\s]*[A-Za-z0-9_-]+", "$1：**");
        return text;
    }

    private String maskPatientId(String patientId) {
        String value = safe(patientId);
        if (value.length() <= 4) return value.isBlank() ? "" : "***";
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }

    private String normalizePromptKey(String prompt) {
        return safe(prompt).replaceAll("\\d+", "#").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
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

    private Map<String, Object> toPlainMap(ObjectNode node) {
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    private List<Map<String, Object>> toPlainList(Iterable<? extends JsonNode> nodes) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (nodes == null) return result;
        for (JsonNode node : nodes) {
            if (node != null && node.isObject()) {
                result.add(objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {}));
            }
        }
        return result;
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            return "{}";
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(safe(value));
        } catch (Exception error) {
            return fallback;
        }
    }

    private static String text(Map<String, Object> map, String key) {
        return safe(map == null ? "" : map.get(key));
    }

    private static Object value(Map<String, Object> map, String key, Object fallback) {
        if (map == null || !map.containsKey(key)) return fallback;
        Object value = map.get(key);
        return value == null ? fallback : value;
    }

    private static String trimText(String value, int maxLength) {
        String text = safe(value);
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    private static String safe(Object value) {
        return String.valueOf(value == null ? "" : value).trim();
    }

    public record AssistantLogDraft(
        String assistantType,
        String prompt,
        Map<String, Object> context,
        String patientId,
        int attachmentCount,
        int imageCount,
        List<String> knowledgeSources,
        String model,
        String answer,
        String errorMessage,
        String systemPromptSummary,
        long latencyMs,
        boolean success,
        AuthSessionService.SessionUser user
    ) {}

    private static final class PromptGroup {
        private final String prompt;
        private final String assistantType;
        private int count;
        private String sourceLogId = "";

        private PromptGroup(String prompt, String assistantType) {
            this.prompt = prompt;
            this.assistantType = assistantType;
        }
    }
}
