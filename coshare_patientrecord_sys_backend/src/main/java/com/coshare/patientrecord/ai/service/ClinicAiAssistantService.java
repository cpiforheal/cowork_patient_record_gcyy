package com.coshare.patientrecord.ai.service;

import com.coshare.patientrecord.ai.dto.AiAssistantAttachment;
import com.coshare.patientrecord.ai.dto.AiAssistantMessage;
import com.coshare.patientrecord.ai.dto.AiAssistantRequest;
import com.coshare.patientrecord.ai.model.AssistantLogDraft;
import com.coshare.patientrecord.ai.model.EffectiveAiConfig;
import com.coshare.patientrecord.ai.model.KnowledgeItem;
import com.coshare.patientrecord.ai.model.KnowledgeSelection;
import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.clinic.service.ClinicDatabaseService;
import com.coshare.patientrecord.common.privacy.SensitiveDataMasker;
import com.coshare.patientrecord.security.AuthPermission;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class ClinicAiAssistantService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DEFAULT_MODEL = "doubao-seed-1-6";
    private static final String DISCLAIMER =
        "仅供院内辅助参考，不替代医生诊断、处方、医嘱、质控结论、归档审核或正式病历。";

    private final ClinicDatabaseService databaseService;
    private final ClinicAiConfigService aiConfigService;
    private final ClinicAiKnowledgeService knowledgeService;
    private final ClinicAiAssistantLogService logService;
    private final ObjectMapper objectMapper;
    private final SensitiveDataMasker sensitiveDataMasker;
    private final AiCallGuard aiCallGuard;
    private final HttpClient httpClient;

    public ClinicAiAssistantService(
        ClinicDatabaseService databaseService,
        ClinicAiConfigService aiConfigService,
        ClinicAiKnowledgeService knowledgeService,
        ClinicAiAssistantLogService logService,
        ObjectMapper objectMapper,
        SensitiveDataMasker sensitiveDataMasker,
        AiCallGuard aiCallGuard
    ) {
        this.databaseService = databaseService;
        this.aiConfigService = aiConfigService;
        this.knowledgeService = knowledgeService;
        this.logService = logService;
        this.objectMapper = objectMapper;
        this.sensitiveDataMasker = sensitiveDataMasker;
        this.aiCallGuard = aiCallGuard;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Map<String, Object> ask(AiAssistantRequest request, SessionUser user) {
        AiAssistantRequest currentRequest = request == null
            ? new AiAssistantRequest("public", "", List.of(), "", Map.of(), List.of(), List.of())
            : request;
        SessionUser currentUser = user == null ? AuthPermission.currentUserOrThrow() : user;
        String assistantType = normalizeAssistantType(currentRequest.assistantType());
        String prompt = safe(currentRequest.prompt());
        long startedAt = System.nanoTime();
        KnowledgeSelection knowledge = new KnowledgeSelection(List.of(), List.of());
        String model = "";

        try {
            if (prompt.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入要咨询豆包助手的问题。");
            }
            requireAllowedRole(assistantType, currentUser);

            EffectiveAiConfig aiConfig = aiConfigService.resolveDoubaoConfig();
            String baseUrl = normalizeChatCompletionsUrl(aiConfig.baseUrl());
            String apiKey = normalizeApiKey(aiConfig.apiKey());
            model = safe(aiConfig.model()).isBlank() ? DEFAULT_MODEL : safe(aiConfig.model());
            if (!aiConfig.enabled() || apiKey.isBlank() || baseUrl.isBlank()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "豆包助手未启用或未配置 Base URL、API Key、Model。");
            }

            knowledge = knowledgeService.select(
                assistantType,
                prompt,
                currentUser.role(),
                currentRequest.context() == null ? Map.of() : currentRequest.context()
            );

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", model);
            payload.put("temperature", 0.35);
            payload.put("max_tokens", 1400);
            payload.put("stream", false);
            ArrayNode messages = payload.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt(assistantType));
            appendConversationMessages(messages, currentRequest, shouldMaskRequest(assistantType, currentRequest));
            appendCurrentUserMessage(messages, currentRequest, currentUser, assistantType, knowledge);

            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = aiCallGuard.execute(
                () -> httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, buildUpstreamErrorMessage(response.statusCode(), response.body()));
            }

            String answer = extractContent(response.body());
            logAssistantCall(currentRequest, currentUser, assistantType, knowledge, model, answer, "", startedAt, true);

            ObjectNode result = objectMapper.createObjectNode();
            result.put("answer", answer);
            result.put("assistantType", assistantType);
            result.put("model", model);
            result.put("generatedAt", LocalDateTime.now().format(TIME_FORMATTER));
            result.put("disclaimer", DISCLAIMER);
            result.set("knowledgeSources", objectMapper.valueToTree(knowledge.titles()));
            return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
        } catch (ResponseStatusException error) {
            logAssistantCall(currentRequest, currentUser, assistantType, knowledge, model, "", safe(error.getReason()), startedAt, false);
            throw error;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            String message = "豆包助手暂时不可用，请稍后重试。";
            logAssistantCall(currentRequest, currentUser, assistantType, knowledge, model, "", message, startedAt, false);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
        }
    }

    private void logAssistantCall(
        AiAssistantRequest request,
        SessionUser user,
        String assistantType,
        KnowledgeSelection knowledge,
        String model,
        String answer,
        String errorMessage,
        long startedAt,
        boolean success
    ) {
        List<AiAssistantAttachment> attachments = request == null || request.attachments() == null ? List.of() : request.attachments();
        long latencyMs = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
        logService.record(
            new AssistantLogDraft(
                assistantType,
                request == null ? "" : request.prompt(),
                request == null || request.context() == null ? Map.of() : request.context(),
                request == null ? "" : request.patientId(),
                attachments.size(),
                safeImageAttachments(attachments).size(),
                knowledge == null ? List.of() : knowledge.titles(),
                model,
                answer,
                errorMessage,
                systemPrompt(assistantType),
                latencyMs,
                success,
                user
            )
        );
    }

    private void appendConversationMessages(ArrayNode messages, AiAssistantRequest request, boolean maskSensitive) {
        List<AiAssistantMessage> history = request == null || request.messages() == null ? List.of() : request.messages();
        history.stream()
            .skip(Math.max(0, history.size() - 8))
            .filter(item -> item != null && List.of("user", "assistant").contains(safe(item.role())))
            .filter(item -> !safe(item.content()).isBlank())
            .forEach(item -> {
                String content = maskSensitive ? sensitiveDataMasker.maskText(item.content()) : item.content();
                messages.addObject().put("role", safe(item.role())).put("content", trimText(content, 1200));
            });
    }

    private void appendCurrentUserMessage(
        ArrayNode messages,
        AiAssistantRequest request,
        SessionUser user,
        String assistantType,
        KnowledgeSelection knowledge
    ) {
        String prompt = buildUserPrompt(request, user, assistantType, knowledge);
        List<AiAssistantAttachment> imageAttachments = safeImageAttachments(request == null ? null : request.attachments());
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        if (imageAttachments.isEmpty()) {
            message.put("content", prompt);
            return;
        }

        ArrayNode content = message.putArray("content");
        content.addObject().put("type", "text").put("text", prompt);
        for (AiAssistantAttachment attachment : imageAttachments) {
            ObjectNode image = content.addObject();
            image.put("type", "image_url");
            image.putObject("image_url").put("url", attachment.dataUrl());
        }
    }

    private String buildUserPrompt(
        AiAssistantRequest request,
        SessionUser user,
        String assistantType,
        KnowledgeSelection knowledge
    ) {
        ObjectNode payload = objectMapper.createObjectNode();
        boolean maskSensitive = shouldMaskRequest(assistantType, request);
        payload.put("assistantType", assistantType);
        payload.put("question", maskSensitive ? sensitiveDataMasker.maskText(request.prompt()) : safe(request.prompt()));
        ObjectNode operator = payload.putObject("operator");
        operator.put("name", safe(user.name()));
        operator.put("role", safe(user.role()));
        operator.put("department", safe(user.department()));
        JsonNode userContext = request.context() == null ? objectMapper.createObjectNode() : objectMapper.valueToTree(request.context());
        payload.set("userContext", maskSensitive ? sensitiveDataMasker.maskJson(userContext) : userContext);
        payload.set("attachmentIds", objectMapper.valueToTree(request.attachmentIds() == null ? List.of() : request.attachmentIds()));
        payload.set("uploadedMaterials", compactUploadedMaterials(request.attachments(), maskSensitive));
        payload.set("knowledgeBase", compactKnowledge(knowledge));
        if (maskSensitive) payload.put("desensitizationPolicyVersion", sensitiveDataMasker.policyVersion());

        String patientId = safe(request.patientId());
        if (!patientId.isBlank()) {
            ObjectNode db = databaseService.readDbForUser(user);
            JsonNode patient = findPatient(db.path("patients"), patientId);
            if (patient == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到患者，或当前账号无权访问该患者。");
            }
            payload.set("patient", sensitiveDataMasker.maskJson(patient));
            payload.set("recordFields", compactRecord(db.path("records").path(patientId)));
            payload.set("attachments", compactDocuments(db.path("documents").path(patientId)));
            payload.set("archive", sensitiveDataMasker.maskJson(db.path("archive").path(patientId)));
        }

        payload.put(
            "instruction",
            "请用中文回答。回答系统菜单、权限、流程、制度等系统内问题时，必须优先依据 knowledgeBase 和当前页面上下文；如果知识库没有覆盖，必须明确说明“当前系统知识库未覆盖”，不得编造系统规则，但可以继续给出明确标注为“通用建议”的推断和下一步核验建议。非系统类通用问题、写作沟通、资料整理和日常协作问题可以直接基于你的通用能力回答，不要求命中知识库。涉及实时新闻、最新政策、价格、法规更新或外部检索的问题，要说明当前未接入实时联网检索，只能给出基于已有知识的初步判断，并建议核验最新官方来源。病历、质控、管理类回答必须区分“系统规则”“当前患者/页面数据”“AI 建议”。只提供说明、清单、草稿和操作建议，不替代诊断、处方、医嘱、正式病历、质控结论或归档审核。"
        );
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (IOException error) {
            return payload.toString();
        }
    }

    private ArrayNode compactKnowledge(KnowledgeSelection knowledge) {
        ArrayNode result = objectMapper.createArrayNode();
        if (knowledge == null || knowledge.items() == null) return result;
        for (KnowledgeItem item : knowledge.items()) {
            ObjectNode node = result.addObject();
            node.put("id", safe(item.id()));
            node.put("title", safe(item.title()));
            node.set("tags", objectMapper.valueToTree(item.tags() == null ? List.of() : item.tags()));
            node.put("content", trimText(item.content(), 900));
        }
        return result;
    }

    private ArrayNode compactUploadedMaterials(List<AiAssistantAttachment> attachments, boolean maskSensitive) {
        ArrayNode compact = objectMapper.createArrayNode();
        if (attachments == null) return compact;
        attachments.stream().limit(8).forEach(attachment -> {
            ObjectNode item = compact.addObject();
            String name = maskSensitive ? sensitiveDataMasker.maskFieldValue("fileName", attachment.name()) : safe(attachment.name());
            item.put("name", trimText(name, 160));
            item.put("type", safe(attachment.type()));
            item.put("size", attachment.size() == null ? 0 : attachment.size());
            item.put("source", safe(attachment.source()));
            item.put("hasImageData", isValidImageDataUrl(attachment.dataUrl()));
        });
        return compact;
    }

    private List<AiAssistantAttachment> safeImageAttachments(List<AiAssistantAttachment> attachments) {
        if (attachments == null) return List.of();
        return attachments.stream()
            .filter(item -> item != null && isValidImageDataUrl(item.dataUrl()))
            .filter(item -> safe(item.dataUrl()).length() <= 6_000_000)
            .limit(4)
            .toList();
    }

    private boolean isValidImageDataUrl(String dataUrl) {
        String value = safe(dataUrl);
        return value.startsWith("data:image/") && value.contains(";base64,");
    }

    private void requireAllowedRole(String assistantType, SessionUser user) {
        String role = safe(user.role());
        if ("leader".equals(assistantType) && !List.of("admin", "manager", "quality").contains(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号不能使用管理助手。");
        }
        if ("quality".equals(assistantType) && !List.of("admin", "quality", "doctor").contains(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号不能使用质控助手。");
        }
        if (
            "patient".equals(assistantType)
                && !List.of("admin", "doctor", "quality", "nurse", "nursing", "reception", "lab", "ecg", "ultrasound", "inspection").contains(role)
        ) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号不能使用患者助手。");
        }
    }

    private String systemPrompt(String assistantType) {
        return switch (assistantType) {
            case "patient" -> """
                你是院内患者档案助手。
                你帮助医生、护士、检查室和质控人员阅读、整理患者健康档案。
                你可以总结患者资料、缺失项、附件摘要、宣教草稿和沟通文本。
                你不能替代诊断、处方、手术决策或正式病历。
                必须优先使用用户消息中的 knowledgeBase 回答系统规则，并区分系统规则、当前患者数据和 AI 建议。
                始终用中文回答，并给出清晰下一步。
                """;
            case "quality" -> """
                你是院内质控助手。
                你帮助质控人员发现缺项、逻辑冲突、附件异常，并起草退回原因。
                你只提供质控辅助和草稿建议，不能直接判定档案通过归档审核。
                必须优先使用用户消息中的 knowledgeBase 回答审核与流程规则，并区分系统规则、当前档案数据和 AI 建议。
                始终用中文回答，并按风险优先级排序。
                """;
            case "leader" -> """
                你是院内管理助手。
                你帮助管理人员汇总今日风险、待办、备份状态、流程阻塞和科室异常。
                不提供医疗诊断。
                关注谁负责、卡在哪里、建议动作是什么。
                必须优先使用用户消息中的 knowledgeBase 回答系统流程、病历协同、备份和权限规则。
                始终用中文回答，简洁可执行。
                """;
            default -> """
                你是院内公共 AI 助手。
                你帮助同事理解系统使用、医院流程、数据组织、日常协作问题，也可以回答一般知识、写作沟通和资料整理类问题。
                你不替代医生判断、处方、诊断、正式病历或最终质控结论。
                涉及医疗风险时，请提示用户找责任医生或质控人员确认。
                回答系统规则、菜单、权限和院内流程时必须优先使用用户消息中的 knowledgeBase；知识库未覆盖的系统规则要明确说明未覆盖，不能编造。
                对知识库未覆盖的系统问题，可以在说明边界后给出“通用建议”和核验路径。
                对非系统类通用问题，可以直接回答，不要求知识库命中。
                对实时新闻、最新政策、价格、法规更新或外部检索问题，要说明当前未接入实时联网检索，并建议核验最新官方来源。
                始终用中文回答，温和、清晰、可执行。
                """;
        };
    }

    private static String normalizeAssistantType(String rawType) {
        String type = safe(rawType);
        if (List.of("patient", "quality", "leader").contains(type)) return type;
        return "public";
    }

    private boolean shouldMaskRequest(String assistantType, AiAssistantRequest request) {
        if (request != null && !safe(request.patientId()).isBlank()) return true;
        return List.of("patient", "quality").contains(assistantType);
    }

    private static String normalizeChatCompletionsUrl(String rawUrl) {
        String url = safe(rawUrl);
        if (url.isBlank()) return "";
        url = url.replaceAll("/+$", "");
        if (url.endsWith("/chat/completions")) return url;
        if (url.endsWith("/v1")) return url + "/chat/completions";
        if (url.endsWith("/v3") || url.endsWith("/api/v3")) return url + "/chat/completions";
        return url + "/v1/chat/completions";
    }

    private JsonNode findPatient(JsonNode patients, String patientId) {
        if (patients == null || !patients.isArray()) return null;
        for (JsonNode patient : patients) {
            if (patientId.equals(patient.path("id").asText())) return patient;
        }
        return null;
    }

    private ObjectNode compactRecord(JsonNode record) {
        ObjectNode compact = objectMapper.createObjectNode();
        if (record == null || !record.isObject()) return compact;
        Iterator<Map.Entry<String, JsonNode>> fields = record.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String value = entry.getValue().asText("");
            if (!value.isBlank()) compact.put(entry.getKey(), trimText(sensitiveDataMasker.maskFieldValue(entry.getKey(), value), 800));
        }
        return compact;
    }

    private ArrayNode compactDocuments(JsonNode documents) {
        ArrayNode compact = objectMapper.createArrayNode();
        if (documents == null || !documents.isArray()) return compact;
        for (JsonNode document : documents) {
            ObjectNode item = compact.addObject();
            item.put("id", document.path("id").asText(document.path("key").asText("")));
            item.put("fileName", sensitiveDataMasker.maskFieldValue("fileName", document.path("fileName").asText("")));
            item.put("fieldLabel", document.path("fieldLabel").asText(""));
            item.put("department", document.path("department").asText(""));
            item.put("uploadedAt", document.path("uploadedAt").asText(""));
            item.put("status", document.path("status").asText(""));
        }
        return compact;
    }

    private String extractContent(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText("").isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "豆包助手返回了空内容。");
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
        if (statusCode == 401) {
            message = message.isBlank()
                ? "认证失败。请检查豆包 API Key 是否完整有效，是否误带中文逗号、空格或 Bearer 前缀，以及该 Key 是否有当前模型接入点权限。"
                : message;
        } else if (statusCode == 404) {
            message = message.isBlank()
                ? "未找到接口或模型接入点。火山方舟 Base URL 通常填写 https://ark.cn-beijing.volces.com/api/v3，Model 需要填写控制台提供的模型或推理接入点 ID。"
                : message;
        } else if (message.isBlank()) {
            message = "请检查豆包 Base URL、模型接入点 ID 或 API 权限。";
        }
        return "豆包助手调用失败。上游状态 " + statusCode + "：" + message;
    }

    private static String normalizeApiKey(Object value) {
        String key = safe(value);
        if (key.regionMatches(true, 0, "Bearer ", 0, 7)) {
            key = key.substring(7).trim();
        }
        while (key.endsWith(",") || key.endsWith("，") || key.endsWith(";") || key.endsWith("；") || key.endsWith("。")) {
            key = key.substring(0, key.length() - 1).trim();
        }
        return key;
    }

    private static String trimText(String value, int maxLength) {
        String text = safe(value);
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    private static String safe(Object value) {
        return String.valueOf(value == null ? "" : value).trim();
    }

}
