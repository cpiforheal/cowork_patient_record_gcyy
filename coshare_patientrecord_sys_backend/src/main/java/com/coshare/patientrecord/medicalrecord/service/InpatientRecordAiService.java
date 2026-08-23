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
import java.util.List;
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
    private static final int MAX_UPSTREAM_ERROR_LENGTH = 500;

    private final ClinicAiConfigService aiConfigService;
    private final AiCallGuard aiCallGuard;
    private final ObjectMapper objectMapper;
    private final InpatientAiResponseParser responseParser;
    private final HttpClient httpClient;

    public InpatientRecordAiService(
        ClinicAiConfigService aiConfigService,
        AiCallGuard aiCallGuard,
        ObjectMapper objectMapper,
        InpatientAiResponseParser responseParser
    ) {
        this.aiConfigService = aiConfigService;
        this.aiCallGuard = aiCallGuard;
        this.objectMapper = objectMapper;
        this.responseParser = responseParser;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public AiGeneration generate(
        String prompt,
        String referenceDocumentText,
        String referenceSourceLabel,
        ObjectNode sourceSnapshot,
        ObjectNode preAiFacts,
        JsonNode maskedPreAiExport,
        Map<String, String> currentValues,
        int referenceParagraphCount,
        List<String> controlledNodeKeys
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
            payload.put("max_tokens", 16000);
            payload.put("stream", false);
            payload.putObject("response_format").put("type", "json_object");
            ArrayNode messages = payload.putArray("messages");
            List<String> expectedNodeKeys = controlledNodeKeys == null ? List.of() : List.copyOf(controlledNodeKeys);
            messages.addObject().put("role", "system").put(
                "content",
                systemPrompt(referenceParagraphCount, expectedNodeKeys)
            );
            messages.addObject().put(
                "role",
                "user"
            ).put(
                "content",
                buildUserContent(
                    normalizedPrompt,
                    sourceSnapshot,
                    preAiFacts,
                    maskedPreAiExport,
                    currentValues,
                    referenceDocumentText,
                    referenceSourceLabel
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
            InpatientAiResponseParser.ParsedGeneration parsed = responseParser.parse(
                extractContent(response.body()),
                referenceParagraphCount,
                expectedNodeKeys
            );
            return new AiGeneration(parsed.paragraphs(), parsed.nodeReplacements(), model);
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

    private String systemPrompt(int referenceParagraphCount, List<String> controlledNodeKeys) throws IOException {
        String outputContract = controlledNodeKeys.isEmpty()
            ? "输出必须是单个 JSON 对象，格式只能为 {\"paragraphs\":[\"第一段\", \"第二段\"]}；paragraphs 必须恰好包含 "
                + referenceParagraphCount + " 个字符串，并与参考文档非空段落逐一对应。"
            : "输出必须是单个 JSON 对象，格式只能为 {\"nodes\":[{\"key\":\"节点键\",\"text\":\"节点正文\"}]}。"
                + "nodes 必须逐一包含以下全部受控节点键，每个键恰好出现一次，不得新增、遗漏或改写键："
                + objectMapper.writeValueAsString(controlledNodeKeys);
        return """
            你是院内住院病历文档生成引擎。任务方式与网页端“提示词 + 参考文档”一致：根据医生提示词、当前患者资料和已复核事实，重写上传参考 DOCX 的正文，生成一份新住院病历。
            必须严格沿用参考文档的结构、标题、段落数量、段落顺序、查房时序和医学书写风格。参考文档只提供格式、结构和写法，严禁把参考患者的姓名、诊断、日期、检查结果、处方等事实复制给当前患者。
            参考文档中出现的姓名、日期、时间、住院号、床号、检查数值、诊断与方剂全部是范本示例：必须逐项替换为当前患者的真实信息；当前患者缺少的日期（查房、手术、出院等）按其入院日期与诊疗经过推算，严禁沿用范本日期。
            若提供了 reviewedMaskedExport（医生复核后的脱敏前置资料），生成前逐节比对它与参考文档结构：参考文档的每个章节都必须用当前患者的对应事实填充，两处不一致时一律以 reviewedMaskedExport 为准。
            当前参考文档共有 %d 个非空正文节点。%s 禁止 Markdown、解释、代码围栏或其他键。
            只能依据当前患者资料、已复核事实和医生提示词撰写，不得虚构检查数值、日期、手术事实、身份信息或诊断。缺少事实时写“待医生补充”。已复核事实优先级高于当前模板值。
            若提供了 reviewedMaskedExport（医生复核后的脱敏前置资料），它是该患者事实的权威口径：生成内容必须与其一致，冲突时以它为准并在相应段落按医生提示词处理。
            中医辨证、治法和方剂必须以主病、主证、兼证及四诊为依据，理法方药一致；方剂只能作为医生复核用参考，并明确标注“参考”。
            每个输出值只放对应节点正文，不要自行合并或拆分节点。系统只会按受控节点键写回上传 DOCX 的原位置，以保留原文档样式、表格、书签和排版。
            """.formatted(referenceParagraphCount, outputContract);
    }

    private String buildUserContent(
        String prompt,
        ObjectNode sourceSnapshot,
        ObjectNode preAiFacts,
        JsonNode maskedPreAiExport,
        Map<String, String> currentValues,
        String referenceDocumentText,
        String referenceSourceLabel
    ) throws IOException {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("doctorSupplement", prompt);
        context.put("referenceDocument", referenceDocumentText);
        if (safe(referenceSourceLabel).length() > 0) {
            context.put("referenceDocumentSource", referenceSourceLabel);
        }
        context.set("patientAndRecord", sourceSnapshot);
        context.set("reviewedPreAiFacts", preAiFacts == null ? objectMapper.createObjectNode() : preAiFacts);
        if (maskedPreAiExport != null && maskedPreAiExport.isObject()) {
            context.set("reviewedMaskedExport", maskedPreAiExport);
        }
        context.set("currentTemplateValues", objectMapper.valueToTree(currentValues));
        return objectMapper.writeValueAsString(context);
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

    public record AiGeneration(ArrayNode paragraphs, Map<String, String> nodeReplacements, String model) {
        public AiGeneration {
            nodeReplacements = Map.copyOf(nodeReplacements);
        }
    }
}
