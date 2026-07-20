package com.coshare.patientrecord.medicalrecord.service;

import com.coshare.patientrecord.ai.model.EffectiveAiConfig;
import com.coshare.patientrecord.ai.service.AiCallGuard;
import com.coshare.patientrecord.ai.service.ClinicAiConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class InpatientRecordAiService {

    private static final Logger log = LoggerFactory.getLogger(InpatientRecordAiService.class);
    private static final int MAX_PROMPT_LENGTH = 4000;
    private static final int MAX_FIELD_LENGTH = 12000;
    private static final int MAX_UPSTREAM_ERROR_LENGTH = 500;

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
        String referenceDocumentText,
        ObjectNode sourceSnapshot,
        ObjectNode preAiFacts,
        Map<String, String> currentValues,
        Set<String> allowedFields
    ) {
        String normalizedPrompt = safe(prompt);
        if (normalizedPrompt.length() > MAX_PROMPT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI 提示词不能超过 " + MAX_PROMPT_LENGTH + " 个字符");
        }

        EffectiveAiConfig config = aiConfigService.resolveEffectiveConfig();
        String baseUrl = normalizeChatCompletionsUrl(config.baseUrl());
        String apiKey = normalizeApiKey(config.apiKey());
        String model = safe(config.model());
        if (!config.enabled() || baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "病历 AI 未启用，或 GPT 兼容 Base URL、API Key、Model 尚未完整配置"
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
            messages.addObject().put(
                "role",
                "user"
            ).put(
                "content",
                buildUserContent(
                    normalizedPrompt,
                    sourceSnapshot,
                    preAiFacts,
                    currentValues,
                    referenceDocumentText
                )
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
                String upstreamMessage = upstreamErrorMessage(response.body());
                log.warn(
                    "GPT-compatible inpatient generation rejected by upstream: status={}, model={}, endpoint={}, detail={}",
                    response.statusCode(),
                    model,
                    safeEndpoint(baseUrl),
                    upstreamMessage
                );
                HttpStatus status = response.statusCode() == 401 || response.statusCode() == 403
                    ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.BAD_GATEWAY;
                String hint = response.statusCode() == 401 || response.statusCode() == 403
                    ? "API Key 无效或无权访问当前模型，请由管理员检查病历 AI 配置"
                    : "上游返回 HTTP " + response.statusCode() + (upstreamMessage.isBlank() ? "" : "：" + upstreamMessage);
                throw new ResponseStatusException(status, "GPT 兼容病历生成失败：" + hint);
            }
            ObjectNode fields = parseAndValidateFields(extractContent(response.body()), allowedFields);
            if (fields.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型未返回可用于病历模板的字段");
            }
            return new AiGeneration(fields, model);
        } catch (ResponseStatusException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GPT 兼容病历生成已中断，请稍后重试", error);
        } catch (HttpTimeoutException error) {
            log.warn("GPT-compatible inpatient generation timed out: endpoint={}", safeEndpoint(baseUrl), error);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "GPT 兼容病历生成超时，请检查模型服务网络后重试", error);
        } catch (ConnectException error) {
            log.warn("GPT-compatible inpatient generation connection failed: endpoint={}", safeEndpoint(baseUrl), error);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "无法连接 GPT 兼容模型服务，请检查 Base URL 和网络", error);
        } catch (IOException error) {
            log.warn("GPT-compatible inpatient generation I/O or response parsing failed: endpoint={}", safeEndpoint(baseUrl), error);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GPT 兼容模型响应无法解析：" + safeErrorMessage(error), error);
        } catch (IllegalArgumentException error) {
            log.warn("GPT-compatible inpatient generation request is invalid: endpoint={}", safeEndpoint(baseUrl), error);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GPT 兼容 Base URL 或请求参数格式不正确", error);
        }
    }

    private String systemPrompt(Set<String> allowedFields) {
        return """
            你是院内住院病历结构化生成引擎。只能依据用户提供的患者资料和已复核前置事实撰写，不得虚构检查数值、日期、手术事实、身份信息或诊断。
            固定业务要求：参照医生本次显式上传的 DOCX 参考文档组织内容，学习其标题、段落顺序、查房时序和医学书写风格；你只生成动态字段的纯文本值，最终排版由系统模板完成。参考文档只用于格式和风格，不得把其中患者的事实写入当前病历。
            输出必须是单个 JSON 对象，禁止 Markdown、解释文字和代码围栏。JSON 的键只能来自下列模板字段：
            %s
            缺少事实时保留现有字段值或写“待医生补充”，不得猜测。已复核前置事实优先级高于当前字段值和医生补充说明。
            中医辨证、治法和方剂必须以主病、主证、兼证及四诊为依据，理法方药一致；方剂仅作为医生复核用参考，并在字段文本中明确“参考”。
            attendingRoundsJson、postOpRoundsJson 等时序字段请按原资料中的日期顺序组织为可直接放入 Word 段落的中文文本，不要输出嵌套 JSON。
            """.formatted(String.join(", ", allowedFields));
    }

    private String buildUserContent(
        String prompt,
        ObjectNode sourceSnapshot,
        ObjectNode preAiFacts,
        Map<String, String> currentValues,
        String referenceDocumentText
    ) throws IOException {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("doctorSupplement", prompt);
        context.put("referenceDocument", referenceDocumentText);
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
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型返回格式错误：需要 JSON 对象");
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
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型返回了空内容");
        }
        return content.asText();
    }

    private String upstreamErrorMessage(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(safe(responseBody));
            String message = safe(root.path("error").path("message").asText(root.path("message").asText("")));
            return truncate(message);
        } catch (Exception ignored) {
            return truncate(safe(responseBody).replaceAll("\\s+", " "));
        }
    }

    private String truncate(String value) {
        String text = safe(value);
        return text.length() <= MAX_UPSTREAM_ERROR_LENGTH ? text : text.substring(0, MAX_UPSTREAM_ERROR_LENGTH) + "…";
    }

    private String safeEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            return uri.getScheme() + "://" + uri.getAuthority() + safe(uri.getPath());
        } catch (Exception ignored) {
            return "invalid-endpoint";
        }
    }

    private String safeErrorMessage(Exception error) {
        String message = safe(error == null ? "" : error.getMessage());
        return message.isBlank() ? "响应格式错误" : truncate(message);
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
