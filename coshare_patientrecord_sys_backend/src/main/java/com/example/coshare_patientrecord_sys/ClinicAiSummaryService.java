package com.example.coshare_patientrecord_sys;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class ClinicAiSummaryService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DISCLAIMER = "本输出仅供院内辅助阅读，不替代医生判断和 HIS 官方病历质控。";

    private final ClinicDatabaseService databaseService;
    private final ClinicAiConfigService aiConfigService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ClinicAiSummaryService(
        ClinicDatabaseService databaseService,
        ClinicAiConfigService aiConfigService,
        ObjectMapper objectMapper
    ) {
        this.databaseService = databaseService;
        this.aiConfigService = aiConfigService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Map<String, Object> generateRecordSummary(AiSummaryRequest request, AuthSessionService.SessionUser user) {
        requireAllowedRole();
        ClinicAiConfigService.EffectiveAiConfig aiConfig = aiConfigService.resolveEffectiveConfig();
        String baseUrl = normalizeChatCompletionsUrl(aiConfig.baseUrl());
        String apiKey = String.valueOf(aiConfig.apiKey() == null ? "" : aiConfig.apiKey()).trim();
        String model = String.valueOf(aiConfig.model() == null ? "" : aiConfig.model()).trim();
        if (!aiConfig.enabled() || apiKey.isBlank() || baseUrl.isBlank() || model.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI服务未配置，请在系统设置中配置 AI base_url、api_key 和模型");
        }
        String patientId = request == null ? "" : String.valueOf(request.patientId() == null ? "" : request.patientId()).trim();
        if (patientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者ID");
        }

        ObjectNode db = databaseService.readDbForUser(user);
        JsonNode patient = findPatient(db.path("patients"), patientId);
        if (patient == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "患者不存在或当前账号无权查看");
        }

        String prompt = buildPrompt(patient, db.path("records").path(patientId), db.path("documents").path(patientId), db.path("archive").path(patientId));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.put("temperature", 0.45);
        payload.put("max_tokens", 1200);
        payload.put("stream", false);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt());
        messages.addObject().put("role", "user").put("content", prompt);

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, buildUpstreamErrorMessage(response.statusCode(), response.body()));
            }
            String content = extractContent(response.body());
            return normalizeAiOutput(content, model);
        } catch (ResponseStatusException error) {
            throw error;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI服务暂时不可用，请稍后重试");
        }
    }

    private void requireAllowedRole() {
        AuthPermission.requireAnyRole("当前账号无权生成AI总结", "admin", "doctor", "quality", "nurse");
    }

    private static String normalizeChatCompletionsUrl(String rawUrl) {
        String url = String.valueOf(rawUrl == null ? "" : rawUrl).trim();
        if (url.isBlank()) return "";
        url = url.replaceAll("/+$", "");
        if (url.endsWith("/chat/completions")) return url;
        if (url.endsWith("/v1")) return url + "/chat/completions";
        return url + "/v1/chat/completions";
    }

    private String systemPrompt() {
        return """
            你是院内患者健康管理档案的AI辅助助手，服务对象是医生、护士、前台和院内管理人员。
            你的目标不是机械复述字段，而是帮助团队快速理解这个患者目前的治疗状态、沟通重点、随访重点和容易忽略的信息。

            工作边界：
            1. 只能基于提供的档案字段、附件索引和归档状态给出辅助阅读意见，不得编造检查结果、诊断、手术方式、随访结论或质控结论。
            2. 不做自动诊断，不做DIP分组判断，不替代医生判断和HIS官方病历质控。
            3. 可以做“建议核实、建议关注、建议随访追问”的延伸提醒，但必须让人看得出这是辅助建议，不是结论。
            4. 对缺失信息不要只写“未见记录”，要说明这项缺失可能影响后续沟通、复查或档案完整性。
            5. 语气要像一位稳妥、有经验、有人情味的院内助手：清楚、温和、具体，不夸张，不制造焦虑。

            输出要求：
            只返回JSON对象，不要Markdown，不要代码块。
            字段必须包含：
            summary, patientPortrait, clinicalSummary, managementSummary, followupSummary,
            priorityFocus, overlookedInsights, missingItems, riskHints, communicationTips,
            nextFollowupSuggestions, doctorTips, disclaimer。
            summary/patientPortrait/clinicalSummary/managementSummary/followupSummary 返回字符串，每项不超过90个汉字。
            priorityFocus、overlookedInsights、missingItems、riskHints、communicationTips、nextFollowupSuggestions、doctorTips 返回字符串数组，每组2到5条，每条不超过60个汉字。
            如果依据不足，请写“建议补充/建议核实”，不要猜测。
            """;
    }

    private String buildPrompt(JsonNode patient, JsonNode record, JsonNode documents, JsonNode archive) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("patient", patient);
        payload.set("recordFields", compactRecord(record));
        payload.set("attachments", compactDocuments(documents));
        payload.set("archive", archive == null || archive.isMissingNode() ? objectMapper.createObjectNode() : archive);
        payload.put("instruction", "请生成一份院内辅助阅读意见：既要概括患者档案，也要指出值得关注但容易被忽略的沟通、随访、复查和档案完整性问题。控制篇幅，避免模板化套话。");
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (IOException error) {
            return payload.toString();
        }
    }

    private ObjectNode compactRecord(JsonNode record) {
        ObjectNode compact = objectMapper.createObjectNode();
        if (record == null || !record.isObject()) return compact;
        Iterator<Map.Entry<String, JsonNode>> fields = record.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String value = entry.getValue().asText("");
            if (!value.isBlank()) {
                compact.put(entry.getKey(), value.length() > 900 ? value.substring(0, 900) + "..." : value);
            }
        }
        return compact;
    }

    private ArrayNode compactDocuments(JsonNode documents) {
        ArrayNode compact = objectMapper.createArrayNode();
        if (documents == null || !documents.isArray()) return compact;
        for (JsonNode document : documents) {
            ObjectNode item = compact.addObject();
            item.put("fileName", document.path("fileName").asText(""));
            item.put("fieldLabel", document.path("fieldLabel").asText(""));
            item.put("department", document.path("department").asText(""));
            item.put("uploadedAt", document.path("uploadedAt").asText(""));
            item.put("status", document.path("status").asText(""));
        }
        return compact;
    }

    private JsonNode findPatient(JsonNode patients, String patientId) {
        if (patients == null || !patients.isArray()) return null;
        for (JsonNode patient : patients) {
            if (patientId.equals(patient.path("id").asText())) return patient;
        }
        return null;
    }

    private String extractContent(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText("").isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI服务返回为空");
        }
        return content.asText();
    }

    private String buildUpstreamErrorMessage(int statusCode, String responseBody) {
        String message = "";
        try {
            JsonNode root = objectMapper.readTree(String.valueOf(responseBody == null ? "" : responseBody));
            message = root.path("error").path("message").asText("");
        } catch (IOException ignored) {
            message = "";
        }
        if (message.isBlank()) {
            message = "请检查 base_url、模型名称或接口权限";
        }
        return "AI服务调用失败（上游状态 " + statusCode + "）：" + message;
    }

    private Map<String, Object> normalizeAiOutput(String content, String model) throws IOException {
        JsonNode parsed = parseJsonContent(content);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("summary", textOrFallback(parsed, "summary"));
        result.put("patientPortrait", textOrFallback(parsed, "patientPortrait"));
        result.put("clinicalSummary", textOrFallback(parsed, "clinicalSummary"));
        result.put("managementSummary", textOrFallback(parsed, "managementSummary"));
        result.put("followupSummary", textOrFallback(parsed, "followupSummary"));
        result.set("priorityFocus", arrayOrText(parsed, "priorityFocus"));
        result.set("overlookedInsights", arrayOrText(parsed, "overlookedInsights"));
        result.set("missingItems", arrayOrText(parsed, "missingItems"));
        result.set("riskHints", arrayOrText(parsed, "riskHints"));
        result.set("communicationTips", arrayOrText(parsed, "communicationTips"));
        result.set("nextFollowupSuggestions", arrayOrText(parsed, "nextFollowupSuggestions"));
        result.set("doctorTips", arrayOrText(parsed, "doctorTips"));
        result.put("disclaimer", parsed.path("disclaimer").asText(DISCLAIMER));
        result.put("generatedAt", LocalDateTime.now().format(TIME_FORMATTER));
        result.put("model", model);
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    private JsonNode parseJsonContent(String content) throws IOException {
        String normalized = content.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        try {
            return objectMapper.readTree(normalized);
        } catch (IOException error) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("summary", normalized);
            return fallback;
        }
    }

    private String textOrFallback(JsonNode node, String key) {
        String value = node.path(key).asText("");
        return value.isBlank() ? "待补充" : value;
    }

    private ArrayNode arrayOrText(JsonNode node, String key) {
        ArrayNode values = objectMapper.createArrayNode();
        JsonNode raw = node.path(key);
        if (raw.isArray()) {
            for (JsonNode item : raw) {
                if (!item.asText("").isBlank() && values.size() < 5) values.add(trimText(item.asText(), 90));
            }
        } else if (!raw.asText("").isBlank()) {
            values.add(trimText(raw.asText(), 90));
        }
        if (values.isEmpty()) values.add("未见明显记录");
        return values;
    }

    private String trimText(String value, int maxLength) {
        String text = String.valueOf(value == null ? "" : value).trim();
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    public record AiSummaryRequest(String patientId, String mode) {}
}
