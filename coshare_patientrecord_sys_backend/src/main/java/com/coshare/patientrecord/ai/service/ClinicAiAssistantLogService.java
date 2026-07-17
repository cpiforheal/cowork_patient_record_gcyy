package com.coshare.patientrecord.ai.service;

import com.coshare.patientrecord.ai.model.AssistantLogDraft;
import com.coshare.patientrecord.ai.repository.ClinicAiAssistantLogRepository;
import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.common.privacy.SensitiveDataMasker;
import com.coshare.patientrecord.security.AuthPermission;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class ClinicAiAssistantLogService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_LOGS = 500;

    private final ClinicAiAssistantLogRepository logRepository;
    private final ObjectMapper objectMapper;
    private final SensitiveDataMasker sensitiveDataMasker;

    public ClinicAiAssistantLogService(
        ClinicAiAssistantLogRepository logRepository,
        ObjectMapper objectMapper,
        SensitiveDataMasker sensitiveDataMasker
    ) {
        this.logRepository = logRepository;
        this.objectMapper = objectMapper;
        this.sensitiveDataMasker = sensitiveDataMasker;
    }

    private boolean tryEnsureSchema() {
        return true;
    }

    public void record(AssistantLogDraft draft) {
        try {
            if (!tryEnsureSchema()) return;
            ObjectNode log = objectMapper.createObjectNode();
            String id = "ai-log-" + UUID.randomUUID();
            String now = LocalDateTime.now().format(TIME_FORMATTER);
            SessionUser user = draft.user();
            Map<String, Object> context = draft.context() == null ? Map.of() : draft.context();
            String assistantType = safe(draft.assistantType()).isBlank() ? "public" : safe(draft.assistantType());
            boolean patientLike = isPatientSensitive(assistantType, draft.patientId(), context);
            String prompt = safe(draft.prompt());
            String answer = safe(draft.answer());
            String sanitizedPrompt = patientLike ? sensitiveDataMasker.maskText(prompt) : prompt;
            String sanitizedAnswer = patientLike ? sensitiveDataMasker.maskText(answer) : answer;
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
            log.put("patientId", patientLike ? sensitiveDataMasker.maskPatientId(draft.patientId()) : safe(draft.patientId()));
            log.put("attachmentCount", Math.max(0, draft.attachmentCount()));
            log.put("imageCount", Math.max(0, draft.imageCount()));
            log.put("errorMessage", trimText(safe(draft.errorMessage()), 500));
            log.put("sensitive", patientLike);
            if (patientLike) log.put("desensitizationPolicyVersion", sensitiveDataMasker.policyVersion());
            log.put("templateCandidate", false);
            log.set("knowledgeSources", objectMapper.valueToTree(draft.knowledgeSources() == null ? List.of() : draft.knowledgeSources()));

            logRepository.saveLog(
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
                log
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
        } catch (Exception error) {
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
        } catch (Exception error) {
            Map<String, Object> result = emptyAnalytics();
            result.put("warning", "AI assistant analytics are temporarily unavailable");
            return result;
        }
    }

    public Map<String, Object> templates() {
        AuthPermission.currentUserOrThrow();
        try {
        if (!tryEnsureSchema()) {
            return Map.of("list", List.of(), "total", 0, "warning", "AI prompt templates are temporarily unavailable");
        }
        List<ObjectNode> rows = logRepository.queryTemplates();
        return Map.of("list", toPlainList(rows), "total", rows.size());
        } catch (Exception error) {
            return Map.of("list", List.of(), "total", 0, "warning", "AI prompt templates are temporarily unavailable");
        }
    }

    public Map<String, Object> markTemplateCandidate(String logId, Map<String, Object> payload, SessionUser user) {
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

        logRepository.saveTemplate(
            templateId,
            now,
            template.path("assistantType").asText("public"),
            template.path("title").asText(""),
            template.path("roleScope").asText(""),
            logId,
            template
        );
        ObjectNode updatedLog = log.deepCopy();
        updatedLog.put("templateCandidate", true);
        logRepository.markTemplateCandidate(logId, updatedLog);
        return toPlainMap(template);
    }

    private List<ObjectNode> queryLogs() {
        if (!tryEnsureSchema()) return List.of();
        return logRepository.queryLogs(MAX_LOGS);
    }

    private ObjectNode findLog(String id) {
        return logRepository.findLog(id);
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
        return parts.isEmpty() ? "知识库优先" : String.join("；", parts);
    }

    private String normalizePromptKey(String prompt) {
        return safe(prompt).replaceAll("\\d+", "#").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
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
