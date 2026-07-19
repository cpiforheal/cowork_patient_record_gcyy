package com.coshare.patientrecord.medicalrecord.service;

import com.coshare.patientrecord.ai.model.EffectiveAiConfig;
import com.coshare.patientrecord.ai.service.AiCallGuard;
import com.coshare.patientrecord.ai.service.ClinicAiConfigService;
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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class InpatientRecordAiService {

    private static final int MAX_PROMPT_LENGTH = 4000;
    private static final int MAX_FIELD_LENGTH = 12000;

    private final ClinicAiConfigService aiConfigService;
    private final AiCallGuard aiCallGuard;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public InpatientRecordAiService(
        ClinicAiConfigService aiConfigService,
        AiCallGuard aiCallGuard,
        ObjectMapper objectMapper
    ) {
        this.aiConfigService = aiConfigService;
        this.aiCallGuard = aiCallGuard;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public AiGeneration generate(
        String prompt,
        ObjectNode sourceSnapshot,
        ObjectNode preAiFacts,
        Map<String, String> currentValues,
        Set<String> allowedFields
    ) {
        String normalizedPrompt = safe(prompt);
        if (normalizedPrompt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI 提示词不能为空");
        }
        if (normalizedPrompt.length() > MAX_PROMPT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI 提示词不能超过 " + MAX_PROMPT_LENGTH + " 个字符");
        }

        EffectiveAiConfig config = aiConfigService.resolveDoubaoConfig();
        String baseUrl = normalizeChatCompletionsUrl(config.baseUrl());
        String apiKey = normalizeApiKey(config.apiKey());
        String model = safe(config.model());
        if (!config.enabled() || baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "豆包住院病历生成未启用，或 Base URL、API Key、Model 尚未完整配置"
            );
        }

        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", model);
            payload.put("temperature", 0.2);
            payload.put("max_tokens", 10000);
            payload.put("stream", false);
            payload.putObject("response_format").put("type", "json_object");
            ArrayNode messages = payload.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt(allowedFields));
            messages.addObject().put("role", "user").put(
                "content",
                buildUserContent(normalizedPrompt, sourceSnapshot, preAiFacts, currentValues)
            );

            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = aiCallGuard.execute(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "豆包住院病历生成失败：上游返回 HTTP " + response.statusCode()
                );
            }
            ObjectNode fields = parseAndValidateFields(extractContent(response.body()), allowedFields);
            if (fields.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "豆包未返回可用于病历模板的字段");
            }
            return new AiGeneration(fields, model);
        } catch (ResponseStatusException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "豆包住院病历生成已中断");
        } catch (IOException | IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "豆包住院病历生成暂时不可用，请稍后重试");
        }
    }

    private String systemPrompt(Set<String> allowedFields) {
        return """
            你是院内住院病历结构化生成引擎。只能依据用户提供的患者资料和已复核前置事实撰写，不得虚构检查数值、日期、手术事实、身份信息或诊断。
            输出必须是单个 JSON 对象，禁止 Markdown、解释文字和代码围栏。JSON 的键只能来自下列模板字段：
            %s
            保持原有周xx病历模板的固定格式、标题、段落和查房时序；你只负责生成这些动态字段的纯文本值。缺少事实时保留现有字段值或写“待医生补充”，不得猜测。
            中医辨证、治法和方剂必须以主病、主证、兼证及四诊为依据，理法方药一致；方剂仅作为医生复核用参考，并在字段文本中明确“参考”。
            attendingRoundsJson、postOpRoundsJson 等时序字段请按原资料中的日期顺序组织为可直接放入 Word 段落的中文文本，不要输出嵌套 JSON。
            """.formatted(String.join(", ", allowedFields));
    }

    private String buildUserContent(
        String prompt,
        ObjectNode sourceSnapshot,
        ObjectNode preAiFacts,
        Map<String, String> currentValues
    ) throws IOException {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("doctorPrompt", prompt);
        context.set("patientAndRecord", sourceSnapshot);
        context.set("reviewedPreAiFacts", preAiFacts == null ? objectMapper.createObjectNode() : preAiFacts);
        context.set("currentTemplateValues", objectMapper.valueToTree(currentValues));
        return objectMapper.writeValueAsString(context);
    }

    private ObjectNode parseAndValidateFields(String rawContent, Set<String> allowedFields) throws IOException {
        String content = safe(rawContent);
        if (content.startsWith("```")) {
            content = content.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        JsonNode parsed = objectMapper.readTree(content);
        JsonNode candidate = parsed.has("fields") && parsed.path("fields").isObject() ? parsed.path("fields") : parsed;
        if (!candidate.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "豆包返回格式错误：需要 JSON 对象");
        }
        ObjectNode accepted = objectMapper.createObjectNode();
        candidate.fields().forEachRemaining(entry -> {
            if (!allowedFields.contains(entry.getKey()) || !entry.getValue().isValueNode()) return;
            String value = safe(entry.getValue().asText(""));
            if (value.length() > MAX_FIELD_LENGTH) value = value.substring(0, MAX_FIELD_LENGTH);
            if (!value.isBlank()) accepted.put(entry.getKey(), value);
        });
        return accepted;
    }

    private String extractContent(String responseBody) throws IOException {
        JsonNode content = objectMapper.readTree(responseBody)
            .path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.asText("").isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "豆包返回了空内容");
        }
        return content.asText();
    }

    private String normalizeChatCompletionsUrl(String rawUrl) {
        String value = safe(rawUrl).replaceAll("/+$", "");
        if (value.isBlank() || value.endsWith("/chat/completions")) return value;
        return value + "/chat/completions";
    }

    private String normalizeApiKey(Object value) {
        String result = safe(value);
        if (result.regionMatches(true, 0, "Bearer ", 0, 7)) result = result.substring(7).trim();
        return result.replace("，", "").replace(",", "").replaceAll("\\s+", "");
    }

    private static String safe(Object value) {
        return String.valueOf(value == null ? "" : value).trim();
    }

    public record AiGeneration(ObjectNode fields, String model) {}
}
