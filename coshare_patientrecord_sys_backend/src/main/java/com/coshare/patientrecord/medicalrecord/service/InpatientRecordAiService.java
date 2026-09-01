package com.coshare.patientrecord.medicalrecord.service;

import com.coshare.patientrecord.ai.model.EffectiveAiConfig;
import com.coshare.patientrecord.ai.service.AiCallGuard;
import com.coshare.patientrecord.ai.service.ClinicAiConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
    private static final int RELAY_SLICE_COUNT = 4;
    private static final int RELAY_MAX_TOKENS = 10000;
    private static final int RELAY_OUTLINE_MAX_TOKENS = 4096;

    private final ClinicAiConfigService aiConfigService;
    private final AiCallGuard aiCallGuard;
    private final ObjectMapper objectMapper;
    private final InpatientAiResponseParser responseParser;
    private final HttpClient httpClient;
    private final ExecutorService relayExecutor;
    private final Semaphore relayGenerationLock = new Semaphore(1, true);

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
        AtomicInteger threadIndex = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "inpatient-record-relay-" + threadIndex.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        this.relayExecutor = Executors.newFixedThreadPool(RELAY_SLICE_COUNT, threadFactory);
    }

    @PreDestroy
    void shutdownRelayExecutor() {
        relayExecutor.shutdownNow();
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
        List<String> controlledNodeKeys,
        List<String> conversationHistory,
        String modelOverride,
        java.util.function.Consumer<String> chapterProgress
    ) {
        String normalizedPrompt = safe(prompt);
        if (normalizedPrompt.length() > MAX_PROMPT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI 提示词不能超过 " + MAX_PROMPT_LENGTH + " 个字符");
        }

        boolean legacyOrdinalMode = controlledNodeKeys == null || controlledNodeKeys.isEmpty();
        EffectiveAiConfig config = aiConfigService.resolveEffectiveConfig();
        boolean directEligible = legacyOrdinalMode
            && config.enabled()
            && !normalizeApiKey(config.apiKey()).isBlank()
            && !safe(config.baseUrl()).isBlank()
            && !safe(config.model()).isBlank();
        String effectiveModel = safe(modelOverride);
        if (effectiveModel.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型名称不能超过 100 个字符");
        }
        if (directEligible) {
            return generateViaChapteredRelay(
                config,
                effectiveModel,
                normalizedPrompt,
                referenceDocumentText,
                referenceSourceLabel,
                sourceSnapshot,
                preAiFacts,
                maskedPreAiExport,
                currentValues,
                referenceParagraphCount,
                conversationHistory,
                chapterProgress
            );
        }

        EffectiveAiConfig difyConfig = aiConfigService.resolveDifyConfig();
        boolean difyEligible = legacyOrdinalMode
            && difyConfig.enabled()
            && !normalizeApiKey(difyConfig.apiKey()).isBlank()
            && !safe(difyConfig.baseUrl()).isBlank();
        if (difyEligible) {
            return generateViaDifyWorkflow(
                difyConfig,
                normalizedPrompt,
                referenceDocumentText,
                referenceSourceLabel,
                sourceSnapshot,
                preAiFacts,
                maskedPreAiExport,
                currentValues,
                referenceParagraphCount,
                conversationHistory,
                chapterProgress
            );
        }
        String baseUrl = normalizeChatCompletionsUrl(config.baseUrl());
        String apiKey = normalizeApiKey(config.apiKey());
        String model = safe(modelOverride).isBlank() ? safe(config.model()) : safe(modelOverride);
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
                systemPrompt(referenceParagraphCount, expectedNodeKeys) + conversationHistoryBlock(conversationHistory)
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

    private AiGeneration generateViaChapteredRelay(
        EffectiveAiConfig config,
        String modelOverride,
        String prompt,
        String referenceDocumentText,
        String referenceSourceLabel,
        ObjectNode sourceSnapshot,
        ObjectNode preAiFacts,
        JsonNode maskedPreAiExport,
        Map<String, String> currentValues,
        int referenceParagraphCount,
        List<String> conversationHistory,
        Consumer<String> chapterProgress
    ) {
        String endpoint = normalizeChatCompletionsUrl(config.baseUrl());
        String apiKey = normalizeApiKey(config.apiKey());
        String model = safe(modelOverride).isBlank() ? safe(config.model()) : safe(modelOverride);
        if (!config.enabled() || endpoint.isBlank() || apiKey.isBlank() || model.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "病历 AI 未启用，或 GPT 兼容 Base URL、API Key、Model 尚未完整配置"
            );
        }

        boolean lockAcquired = false;
        long startedAt = System.nanoTime();
        try {
            relayGenerationLock.acquire();
            lockAcquired = true;

            List<NumberedParagraph> referenceParagraphs = parseNumberedReference(referenceDocumentText);
            if (referenceParagraphs.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "参考范本内容为空，无法生成病历");
            }
            int expectedCount = referenceParagraphCount > 0 ? referenceParagraphCount : referenceParagraphs.size();
            if (referenceParagraphs.size() > expectedCount) {
                referenceParagraphs = referenceParagraphs.subList(0, expectedCount);
            }
            if (referenceParagraphs.size() != expectedCount) {
                log.warn(
                    "Chaptered relay reference paragraph count mismatch: parsed={}, expected={}",
                    referenceParagraphs.size(),
                    expectedCount
                );
            }

            String factsContext = buildRelayFactsContext(
                prompt,
                sourceSnapshot,
                preAiFacts,
                maskedPreAiExport,
                currentValues,
                conversationHistory
            );
            log.info(
                "Chaptered relay generation started: endpoint={}, model={}, factsContextChars={}, referenceChars={}, expectedCount={}",
                safeEndpoint(endpoint),
                model,
                factsContext.length(),
                safe(referenceDocumentText).length(),
                expectedCount
            );
            String outline = generateRelayOutline(
                endpoint,
                apiKey,
                model,
                prompt,
                factsContext,
                referenceSourceLabel,
                expectedCount
            );
            List<RelaySlice> slices = splitRelaySlices(referenceParagraphs);
            Map<Integer, String> merged = Collections.synchronizedMap(new TreeMap<>());
            List<String> failures = Collections.synchronizedList(new ArrayList<>());
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (RelaySlice slice : slices) {
                CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return rewriteRelaySliceWithRetry(endpoint, apiKey, model, factsContext, outline, referenceSourceLabel, expectedCount, slice);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(error);
                    } catch (IOException error) {
                        throw new CompletionException(error);
                    }
                }, relayExecutor).thenAccept(result -> {
                    synchronized (merged) {
                        for (NumberedParagraph item : result.items()) {
                            merged.put(item.n(), item.text());
                        }
                    }
                    emitRelayProgress(result, chapterProgress);
                }).exceptionally(error -> {
                    failures.add(slice.label() + "：" + safeThrowableMessage(unwrapCompletion(error)));
                    return null;
                });
                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            int covered = merged.size();
            if (expectedCount > 0 && covered * 2 < expectedCount) {
                throw new IOException("模型仅覆盖 " + covered + "/" + expectedCount + " 段（低于一半），内容不可用，请重试" + summarizeFailures(failures));
            }
            if (!failures.isEmpty()) {
                log.warn("Chaptered relay generation completed with partial slice failures: endpoint={}, failures={}", safeEndpoint(endpoint), failures);
            }

            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode paragraphs = result.putArray("paragraphs");
            synchronized (merged) {
                for (Map.Entry<Integer, String> entry : merged.entrySet()) {
                    paragraphs.addObject().put("n", entry.getKey()).put("text", entry.getValue());
                }
            }
            InpatientAiResponseParser.ParsedGeneration parsed = responseParser.parse(
                objectMapper.writeValueAsString(result),
                expectedCount,
                List.of()
            );
            log.info(
                "Chaptered relay inpatient generation succeeded: endpoint={}, model={}, elapsedMs={}, covered={}/{}, historyRounds={}",
                safeEndpoint(endpoint),
                model,
                (System.nanoTime() - startedAt) / 1_000_000,
                covered,
                expectedCount,
                conversationHistory == null ? 0 : conversationHistory.size()
            );
            return new AiGeneration(parsed.paragraphs(), parsed.nodeReplacements(), "chaptered-relay/" + model);
        } catch (ResponseStatusException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "直连病历生成已中断，请稍后重试", error);
        } catch (HttpTimeoutException error) {
            log.warn("Chaptered relay inpatient generation timed out: endpoint={}", safeEndpoint(endpoint), error);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "直连病历生成超时，请检查模型服务网络后重试", error);
        } catch (ConnectException error) {
            log.warn("Chaptered relay inpatient generation connection failed: endpoint={}", safeEndpoint(endpoint), error);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "无法连接直连模型服务，请检查 Base URL 和网络", error);
        } catch (IOException error) {
            log.warn("Chaptered relay inpatient generation failed: endpoint={}, detail={}", safeEndpoint(endpoint), safeErrorMessage(error));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "直连病历生成失败：" + safeErrorMessage(error), error);
        } finally {
            if (lockAcquired) relayGenerationLock.release();
        }
    }

    private String generateRelayOutline(
        String endpoint,
        String apiKey,
        String model,
        String prompt,
        String factsContext,
        String referenceSourceLabel,
        int expectedCount
    ) throws IOException, InterruptedException {
        // 定纲只做写作口径规划，不携带范本全文：长上下文会使 deepseek-v4-flash 等推理模型退化
        // （实测 46k+ prompt tokens 时输出近乎为空且上游照常计费）；范本结构由各分片自行携带。
        String userContent = """
            【固定上下文：医生提示词、患者档案、已复核事实、脱敏前置资料、会话记忆】
            %s

            【参考范本来源】
            %s

            【范本规模】
            参考范本共 %d 个非空正文段落，后续将按 4 个连续分片并行改写后合并；总纲无需逐段规划，只需统一病例写作口径。
            """.formatted(factsContext, safe(referenceSourceLabel), expectedCount);
        String raw = chatCompletion(endpoint, apiKey, model, relayOutlineSystemPrompt(expectedCount), userContent, RELAY_OUTLINE_MAX_TOKENS);
        return extractRelayOutline(raw);
    }

    private SliceResult rewriteRelaySliceWithRetry(
        String endpoint,
        String apiKey,
        String model,
        String factsContext,
        String outline,
        String referenceSourceLabel,
        int expectedCount,
        RelaySlice slice
    ) throws IOException, InterruptedException {
        IOException lastIo = null;
        ResponseStatusException lastStatus = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return rewriteRelaySlice(endpoint, apiKey, model, factsContext, outline, referenceSourceLabel, expectedCount, slice);
            } catch (ResponseStatusException error) {
                lastStatus = error;
            } catch (IOException error) {
                lastIo = error;
            }
            if (attempt < 2) Thread.sleep(700L * attempt);
        }
        if (lastStatus != null) throw lastStatus;
        throw lastIo == null ? new IOException("分片生成失败") : lastIo;
    }

    private SliceResult rewriteRelaySlice(
        String endpoint,
        String apiKey,
        String model,
        String factsContext,
        String outline,
        String referenceSourceLabel,
        int expectedCount,
        RelaySlice slice
    ) throws IOException, InterruptedException {
        String userContent = """
            【生成总纲】
            %s

            【固定上下文：医生提示词、患者资料、脱敏前置资料、会话记忆】
            %s

            【参考范本来源】
            %s

            【本次分片：%s】
            %s
            """.formatted(safe(outline), factsContext, safe(referenceSourceLabel), slice.label(), slice.referenceText());
        String raw = chatCompletion(endpoint, apiKey, model, relaySliceSystemPrompt(slice, expectedCount), userContent, RELAY_MAX_TOKENS);
        List<NumberedParagraph> items = parseRelaySliceResponse(raw, slice.startN(), slice.endN());
        if (items.isEmpty()) {
            throw new IOException(slice.label() + " 未返回可用段落");
        }
        return new SliceResult(slice, items);
    }

    private String chatCompletion(
        String endpoint,
        String apiKey,
        String model,
        String systemContent,
        String userContent,
        int maxTokens
    ) throws IOException, InterruptedException {
        int attemptBudget = Math.max(maxTokens, 1024);
        for (int attempt = 1; attempt <= 2; attempt++) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", model);
            payload.put("temperature", 0.2);
            payload.put("max_tokens", attemptBudget);
            payload.put("stream", false);
            payload.putObject("response_format").put("type", "json_object");
            ArrayNode messages = payload.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemContent);
            messages.addObject().put("role", "user").put("content", userContent);

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = aiCallGuard.execute(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("上游状态 " + response.statusCode() + "：" + truncate(upstreamErrorMessage(response.body())));
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isTextual() && !content.asText("").isBlank()) {
                return content.asText();
            }
            String finishReason = root.path("choices").path(0).path("finish_reason").asText("unknown");
            int completionTokens = root.path("usage").path("completion_tokens").asInt(0);
            int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
            log.warn(
                "Chaptered relay upstream returned empty content: endpoint={}, model={}, attempt={}/2, budgetTokens={}, "
                    + "finishReason={}, completionTokens={}, promptTokens={}",
                safeEndpoint(endpoint),
                model,
                attempt,
                attemptBudget,
                finishReason,
                completionTokens,
                promptTokens
            );
            if (attempt == 1) {
                attemptBudget = Math.min(attemptBudget * 2, 32768);
                continue;
            }
            throw new IOException(
                "模型返回了空内容（finish_reason=" + finishReason + "，completionTokens=" + completionTokens
                    + "，promptTokens=" + promptTokens + "）；已自动提高输出上限重试仍失败，请稍后重试或联系管理员"
            );
        }
        throw new IOException("模型返回了空内容");
    }

    private String buildRelayFactsContext(
        String prompt,
        ObjectNode sourceSnapshot,
        ObjectNode preAiFacts,
        JsonNode maskedPreAiExport,
        Map<String, String> currentValues,
        List<String> conversationHistory
    ) throws IOException {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("doctorSupplement", safe(prompt));
        // 只携带患者身份档案（真实姓名/性别/年龄等表头信息），不携带全量合并病历：
        // 实测直连中转在 46k+ prompt tokens 时输出严重退化甚至返回空内容，临床事实由
        // reviewedPreAiFacts 与 reviewedMaskedExport（权威口径）承担。
        ObjectNode patientProfile = sourceSnapshot != null && sourceSnapshot.path("patient").isObject()
            ? (ObjectNode) sourceSnapshot.path("patient").deepCopy()
            : objectMapper.createObjectNode();
        context.set("patientProfile", patientProfile);
        context.set("reviewedPreAiFacts", preAiFacts == null ? objectMapper.createObjectNode() : preAiFacts);
        if (maskedPreAiExport != null && !maskedPreAiExport.isMissingNode() && !maskedPreAiExport.isNull()) {
            context.set("reviewedMaskedExport", maskedPreAiExport);
        }
        context.set("currentTemplateValues", objectMapper.valueToTree(currentValues == null ? Map.of() : currentValues));
        String history = joinConversationHistory(conversationHistory);
        if (!history.isBlank()) context.put("conversationHistory", history);
        return objectMapper.writeValueAsString(context);
    }

    private List<NumberedParagraph> parseNumberedReference(String referenceDocumentText) {
        List<NumberedParagraph> paragraphs = new ArrayList<>();
        int index = 1;
        for (String line : safe(referenceDocumentText).split("\\n", -1)) {
            String text = safe(line);
            if (text.isBlank()) continue;
            paragraphs.add(new NumberedParagraph(index++, text));
        }
        return paragraphs;
    }

    private List<RelaySlice> splitRelaySlices(List<NumberedParagraph> paragraphs) {
        if (paragraphs.isEmpty()) return List.of();
        List<RelaySlice> slices = new ArrayList<>();
        int total = paragraphs.size();
        for (int i = 0; i < RELAY_SLICE_COUNT; i++) {
            int from = i * total / RELAY_SLICE_COUNT;
            int to = (i + 1) * total / RELAY_SLICE_COUNT;
            if (from >= to) continue;
            List<NumberedParagraph> items = paragraphs.subList(from, to);
            NumberedParagraph first = items.get(0);
            NumberedParagraph last = items.get(items.size() - 1);
            slices.add(new RelaySlice(i + 1, first.n(), last.n(), numberedText(items)));
        }
        return slices;
    }

    private String numberedText(List<NumberedParagraph> paragraphs) {
        StringBuilder result = new StringBuilder();
        for (NumberedParagraph paragraph : paragraphs) {
            if (result.length() > 0) result.append('\n');
            result.append("【第").append(paragraph.n()).append("段】").append(paragraph.text());
        }
        return result.toString();
    }

    private List<NumberedParagraph> parseRelaySliceResponse(String rawContent, int startN, int endN) throws IOException {
        JsonNode root = objectMapper.readTree(stripCodeFence(rawContent));
        JsonNode items = root.path("items");
        if (!items.isArray()) items = root.path("paragraphs");
        if (!items.isArray()) {
            throw new IOException("分片模型返回格式错误：缺少 items 数组");
        }
        Map<Integer, String> accepted = new LinkedHashMap<>();
        for (JsonNode item : items) {
            int n = item.path("n").asInt(0);
            if (n < startN || n > endN) {
                throw new IOException("分片模型返回了超出范围的段落编号：" + n + "，期望 " + startN + "-" + endN);
            }
            if (accepted.containsKey(n)) {
                throw new IOException("分片模型返回了重复的段落编号：" + n);
            }
            if (!item.path("text").isTextual()) {
                throw new IOException("分片模型返回格式错误：text 必须为文本");
            }
            String text = safe(item.path("text").asText(""));
            accepted.put(n, text.isBlank() ? "待医生补充" : text);
        }
        List<NumberedParagraph> result = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : accepted.entrySet()) {
            result.add(new NumberedParagraph(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private void emitRelayProgress(SliceResult result, Consumer<String> chapterProgress) {
        if (chapterProgress == null) return;
        StringBuilder body = new StringBuilder();
        for (NumberedParagraph item : result.items()) {
            if (body.length() > 0) body.append('\n');
            body.append("【第").append(item.n()).append("段】").append(item.text());
        }
        String text = body.toString().strip();
        if (text.isBlank()) return;
        emitProgressChunks("【" + result.slice().label() + " 已完成】\n", text, chapterProgress);
    }

    private void emitProgressChunks(String firstHeader, String text, Consumer<String> chapterProgress) {
        int chunkSize = 850;
        for (int start = 0; start < text.length(); start += chunkSize) {
            int end = Math.min(text.length(), start + chunkSize);
            String header = start == 0 ? firstHeader : firstHeader.replace(" 已完成", " 续");
            chapterProgress.accept(header + text.substring(start, end));
        }
    }

    private String relayOutlineSystemPrompt(int expectedCount) {
        return """
            你是院内住院病历生成定纲助手。请基于医生提示词、当前患者档案、已复核事实与脱敏前置资料，整理后续分片改写共用的病例写作总纲。
            范本全文不随本请求下发：总纲只负责统一写作口径（主诊断、关键病史、查体/辅助检查事实、诊疗经过、中医辨证和方剂参考），章节结构与段落编号由各分片按范本自行对齐。
            缺事实时写待医生补充，不得虚构。
            输出必须是单个 JSON 对象，格式只能为 {"outline":"供后续分片改写使用的中文总纲"}，总纲控制在 600 字以内，禁止 Markdown、解释、代码围栏或其他键。
            """.formatted(expectedCount);
    }

    private String relaySliceSystemPrompt(RelaySlice slice, int expectedCount) {
        return """
            你是院内住院病历章节改写引擎。任务是按周xx参考范本的结构和写法，仅改写本次给定编号范围内的段落。
            当前参考文档共有 %d 个非空正文段落，本次只允许输出第 %d 到第 %d 段。输出编号 n 必须对应参考范本中的【第N段】，不得越界、不得重复、不得合并相邻段落。
            参考范本只提供格式、结构、查房时序和语言风格；范本中的患者事实全部是示例，必须替换为当前患者事实。reviewedMaskedExport 是当前患者事实的权威口径，冲突时以它为准。
            纯标题段和签名行可以不输出，系统会沿用范本；含日期、姓名、数值、诊断、病程和治疗内容的短行必须输出。缺少事实时该项只写“待医生补充”。
            严禁虚构：症状细节、体征、诊断、次诊断、术式、麻醉方式、检查与化验数值只能来自当前患者资料与已复核事实；系统未登记的诊断、术式或麻醉方式一律写“待医生补充”，不得按常见做法补写。
            上下文中已提供的检查结果（心电图结论、化验指标、四测/生命体征数值等）必须如实写入对应段落，不得仍写“待医生补充”。
            时间线必须先后一致：全部记录按 入院 → 术前准备/术前小结 → 手术 → 术后首次病程 → 逐日查房 → 出院 的顺序排列，日期从患者入院日期与诊疗经过推算，严禁出现手术早于术前记录、术后记录早于手术等矛盾。
            医师姓名若以脱敏形式（如“桂xx”“罗x”）出现，正文中一律写作“xxx”，不得把脱敏名当作真实姓名写入。
            中医辨证、治法和方剂必须以主病、主证、兼证及四诊为依据，理法方药一致；方剂只能作为医生复核用参考，并明确标注“参考”。
            输出必须是单个 JSON 对象，格式只能为 {"items":[{"n":%d,"text":"第%d段改写后的完整正文"}]}。禁止 Markdown、解释、代码围栏或其他键。
            """.formatted(expectedCount, slice.startN(), slice.endN(), slice.startN(), slice.startN());
    }

    private String extractRelayOutline(String rawContent) {
        try {
            String outline = objectMapper.readTree(stripCodeFence(rawContent)).path("outline").asText("");
            return safe(outline).isBlank() ? safe(rawContent) : safe(outline);
        } catch (Exception ignored) {
            return safe(rawContent);
        }
    }

    private String stripCodeFence(String rawContent) {
        String content = safe(rawContent);
        if (content.startsWith("```")) {
            content = content
                .replaceFirst("^```(?:json)?\\s*", "")
                .replaceFirst("\\s*```$", "")
                .trim();
        }
        return content;
    }

    private String summarizeFailures(List<String> failures) {
        if (failures == null || failures.isEmpty()) return "";
        int count = Math.min(3, failures.size());
        return "；失败分片：" + truncate(String.join("；", failures.subList(0, count)));
    }

    private Throwable unwrapCompletion(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String safeThrowableMessage(Throwable error) {
        String message = error instanceof ResponseStatusException statusError
            ? statusError.getReason()
            : error == null ? "" : error.getMessage();
        return safe(message).isBlank() ? "生成失败" : truncate(message);
    }

    private AiGeneration generateViaDifyWorkflow(
        EffectiveAiConfig difyConfig,
        String prompt,
        String referenceDocumentText,
        String referenceSourceLabel,
        ObjectNode sourceSnapshot,
        ObjectNode preAiFacts,
        JsonNode maskedPreAiExport,
        Map<String, String> currentValues,
        int referenceParagraphCount,
        List<String> conversationHistory,
        java.util.function.Consumer<String> chapterProgress
    ) {
        long startedAt = System.nanoTime();
        try {
            String paragraphsJson = aiCallGuard.execute(() ->
                runDifyWorkflow(difyConfig, prompt, referenceDocumentText, referenceSourceLabel, sourceSnapshot, preAiFacts, maskedPreAiExport, currentValues, conversationHistory, chapterProgress));
            InpatientAiResponseParser.ParsedGeneration parsed = responseParser.parse(paragraphsJson, referenceParagraphCount, List.of());
            log.info(
                "Dify inpatient generation succeeded: endpoint={}, elapsedMs={}, historyRounds={}",
                safeEndpoint(difyConfig.baseUrl()),
                (System.nanoTime() - startedAt) / 1_000_000,
                conversationHistory == null ? 0 : conversationHistory.size()
            );
            return new AiGeneration(parsed.paragraphs(), parsed.nodeReplacements(), "dify-workflow");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Dify 病历生成已中断，请稍后重试", error);
        } catch (HttpTimeoutException error) {
            log.warn("Dify inpatient generation timed out: endpoint={}", safeEndpoint(difyConfig.baseUrl()), error);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Dify 病历生成超时，请稍后重试", error);
        } catch (ConnectException error) {
            log.warn("Dify inpatient generation connection failed: endpoint={}", safeEndpoint(difyConfig.baseUrl()), error);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "无法连接 Dify 工作流服务，请检查 Base URL 和网络", error);
        } catch (IOException error) {
            log.warn("Dify inpatient generation failed: endpoint={}, detail={}", safeEndpoint(difyConfig.baseUrl()), safeErrorMessage(error));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Dify 病历生成失败：" + safeErrorMessage(error), error);
        }
    }

    /**
     * 以流式模式调用 Dify 工作流并消费 SSE 事件，返回工作流 end 节点的 paragraphs_json。
     * 上游非 2xx / workflow failed / 覆盖度硬失败统一抛 IOException，交给 AiCallGuard 计入熔断统计。
     */
    private String runDifyWorkflow(
        EffectiveAiConfig difyConfig,
        String prompt,
        String referenceDocumentText,
        String referenceSourceLabel,
        ObjectNode sourceSnapshot,
        ObjectNode preAiFacts,
        JsonNode maskedPreAiExport,
        Map<String, String> currentValues,
        List<String> conversationHistory,
        java.util.function.Consumer<String> chapterProgress
    ) throws IOException, InterruptedException {
        String endpoint = normalizeDifyWorkflowUrl(difyConfig.baseUrl());
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("response_mode", "streaming");
        payload.put("user", "clinic-inpatient-engine");
        ObjectNode inputs = payload.putObject("inputs");
        inputs.put("doctorSupplement", safe(prompt));
        inputs.put("referenceDocument", numberReferenceParagraphs(referenceDocumentText));
        inputs.put("referenceSourceLabel", safe(referenceSourceLabel));
        inputs.put("patientAndRecord", sourceSnapshot == null ? "{}" : sourceSnapshot.toString());
        inputs.put("reviewedPreAiFacts", preAiFacts == null ? "{}" : preAiFacts.toString());
        inputs.put("reviewedMaskedExport", maskedPreAiExport == null ? "" : maskedPreAiExport.toString());
        inputs.put("currentTemplateValues", objectMapper.writeValueAsString(currentValues == null ? Map.of() : currentValues));
        inputs.put("conversationHistory", joinConversationHistory(conversationHistory));

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(120))
            .header("Authorization", "Bearer " + normalizeApiKey(difyConfig.apiKey()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
            .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("上游状态 " + response.statusCode() + "：" + truncate(upstreamErrorMessage(body)));
        }

        Instant deadline = Instant.now().plus(Duration.ofMinutes(10));
        // 看门狗：SSE 读在网络停滞时会永久阻塞（行间超时检查无法触发），超时强制断流使阻塞读抛出、任务转失败
        java.util.concurrent.atomic.AtomicBoolean watchdogFired = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.Timer watchdog = new java.util.Timer("dify-sse-watchdog", true);
        watchdog.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                watchdogFired.set(true);
                try {
                    response.body().close();
                } catch (IOException ignored) {
                }
            }
        }, java.time.Duration.ofMinutes(10).toMillis());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Instant.now().isAfter(deadline)) {
                    throw new IOException("Dify 工作流执行超过 10 分钟未完成");
                }
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) continue;
                JsonNode event;
                try {
                    event = objectMapper.readTree(trimmed.substring(5).trim());
                } catch (Exception malformed) {
                    continue;
                }
                String type = event.path("event").asText("");
                if ("node_finished".equals(type)) {
                    JsonNode nodeData = event.path("data");
                    log.debug(
                        "Dify workflow node finished: title={}, status={}, elapsedMs={}",
                        nodeData.path("title").asText(""),
                        nodeData.path("status").asText(""),
                        Math.round(nodeData.path("elapsed_time").asDouble(0) * 1000)
                    );
                    emitChapterProgress(nodeData, chapterProgress);
                } else if ("workflow_finished".equals(type)) {
                    return extractDifyOutputs(event.path("data"));
                } else if ("error".equals(type)) {
                    throw new IOException(truncate(event.path("message").asText("Dify 工作流执行错误")));
                }
            }
        } catch (IOException error) {
            if (watchdogFired.get()) {
                throw new IOException("Dify 工作流执行超过 10 分钟未完成（连接已超时中断）");
            }
            throw error;
        }
        watchdog.cancel();
        throw new IOException("Dify 工作流流式响应在 workflow_finished 前结束");
    }

    private String extractDifyOutputs(JsonNode workflowData) throws IOException {
        String status = workflowData.path("status").asText("");
        if (!"succeeded".equalsIgnoreCase(status)) {
            throw new IOException("Dify 工作流执行失败：" + workflowData.path("error").asText(status));
        }
        JsonNode outputs = workflowData.path("outputs");
        String errorCode = outputs.path("error_code").asText("");
        if ("EMPTY_REFERENCE".equals(errorCode)) {
            throw new IOException("参考范本内容为空，无法生成病历");
        }
        if ("PARAGRAPH_COVERAGE_INCOMPLETE".equals(errorCode)) {
            throw new IOException("模型段落覆盖率过低（" + outputs.path("covered_n").asText("?") + "/" + outputs.path("total_n").asText("?") + "），请重试");
        }
        if ("PARTIAL_COVERAGE".equals(errorCode)) {
            log.warn("Dify workflow partial coverage: missing_n={}", outputs.path("missing_n").asText(""));
        }
        String paragraphsJson = outputs.path("paragraphs_json").asText("");
        if (paragraphsJson.isBlank()) {
            throw new IOException("Dify 工作流输出缺少 paragraphs_json");
        }
        return paragraphsJson;
    }

    /** 分片改写节点完成：抽取章节文本追加进进度回调（供任务事件透出，前端按章渲染）。 */
    private void emitChapterProgress(JsonNode nodeData, java.util.function.Consumer<String> chapterProgress) {
        if (chapterProgress == null) return;
        if (!"succeeded".equalsIgnoreCase(nodeData.path("status").asText(""))) return;
        String title = nodeData.path("title").asText("章节");
        JsonNode outputs = nodeData.path("outputs");
        String raw = outputs.path("text").asText("");
        if (raw.isBlank()) {
            for (JsonNode value : outputs) {
                if (value.isTextual() && !value.asText("").isBlank()) {
                    raw = value.asText();
                    break;
                }
            }
        }
        if (raw.isBlank()) return;
        String readable = extractChapterText(raw);
        if (readable.isBlank()) return;
        chapterProgress.accept("【" + title + " 已完成】\n" + readable);
    }

    private String extractChapterText(String raw) {
        try {
            JsonNode items = objectMapper.readTree(raw).path("items");
            if (!items.isArray()) return "";
            StringBuilder text = new StringBuilder();
            for (JsonNode item : items) {
                String paragraph = item.path("text").asText("").trim();
                if (!paragraph.isBlank()) text.append(paragraph).append("\n");
            }
            return text.toString().strip();
        } catch (Exception error) {
            return "";
        }
    }

    private String normalizeDifyWorkflowUrl(String rawUrl) {
        String value = safe(rawUrl).replaceAll("/+$", "");
        if (value.isBlank() || value.endsWith("/workflows/run")) return value;
        return value + "/workflows/run";
    }

    /**
     * 会话记忆：把医生早前各轮的额外备注拼成"【第N轮】…"文本块。
     * Dify 路径作为独立输入变量下发（旧工作流会忽略该变量）；legacy 路径折入系统提示词。
     */
    private String joinConversationHistory(List<String> conversationHistory) {
        if (conversationHistory == null || conversationHistory.isEmpty()) return "";
        List<String> items = conversationHistory.stream()
            .map(InpatientRecordAiService::safe)
            .filter(item -> !item.isBlank())
            .map(item -> item.length() > 300 ? item.substring(0, 300) : item)
            .toList();
        if (items.size() > 6) items = items.subList(items.size() - 6, items.size());
        StringBuilder block = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (block.length() > 0) block.append("\n");
            block.append("【第").append(i + 1).append("轮】").append(items.get(i));
        }
        return block.toString();
    }

    private String conversationHistoryBlock(List<String> conversationHistory) {
        String joined = joinConversationHistory(conversationHistory);
        if (joined.isBlank()) return "";
        return "\n\n【既往对话要求】（医生早前各轮的特殊强调，除本轮明确推翻外必须继续遵守；与本轮要求冲突时以本轮为准）\n" + joined;
    }

    private String systemPrompt(int referenceParagraphCount, List<String> controlledNodeKeys) throws IOException {
        String outputContract = controlledNodeKeys.isEmpty()
            ? "输出必须是单个 JSON 对象，格式只能为 {\"paragraphs\":[{\"n\":1,\"text\":\"第1段改写后的完整文字\"},{\"n\":2,\"text\":\"第2段改写后的完整文字\"}]}。"
                + "数组每项的 n 对应参考文档中的段落编号【第N段】，必须覆盖 1 到 " + referenceParagraphCount
                + " 的编号；每个编号至多出现一次，未覆盖的段落由系统占位。text 为该段改写后的完整文字。"
                + "纯标题段（如“体格检查”“辅助检查结果”“出院记录”）和签名行（如“医师签名：”“医师签字：”）无需输出，系统会自动沿用范本原文；"
                + "含日期、姓名、数值的短行（如查房记录标题行、生命体征行）不属于此类，仍必须输出。"
            : "输出必须是单个 JSON 对象，格式只能为 {\"nodes\":[{\"key\":\"节点键\",\"text\":\"节点正文\"}]}。"
                + "nodes 必须逐一包含以下全部受控节点键，每个键恰好出现一次，不得新增、遗漏或改写键："
                + objectMapper.writeValueAsString(controlledNodeKeys);
        return """
            你是院内住院病历文档生成引擎。任务方式与网页端“提示词 + 参考文档”一致：根据医生提示词、当前患者资料和已复核事实，重写上传参考 DOCX 的正文，生成一份新住院病历。
            必须严格沿用参考文档的结构、标题、段落数量、段落顺序、查房时序和医学书写风格。参考文档只提供格式、结构和写法，严禁把参考患者的姓名、诊断、日期、检查结果、处方等事实复制给当前患者。
            参考文档正文已按【第N段】逐段编号：输出的每一段必须是对参考中同编号段落的改写，一段不落、一序不乱；无需改动的段落原样输出该段文字。
            参考文档中出现的姓名、日期、时间、住院号、床号、检查数值、诊断与方剂全部是范本示例：必须逐项替换为当前患者的真实信息；当前患者缺少的日期（查房、手术、出院等）按其入院日期与诊疗经过推算，严禁沿用范本日期。
            若提供了 reviewedMaskedExport（医生复核后的脱敏前置资料），生成前逐节比对它与参考文档结构：参考文档的每个章节都必须用当前患者的对应事实填充，两处不一致时一律以 reviewedMaskedExport 为准。
            当前参考文档共有 %d 个非空正文节点。%s 禁止 Markdown、解释、代码围栏或其他键。
            只能依据当前患者资料、已复核事实和医生提示词撰写，不得虚构检查数值、日期、手术事实、身份信息或诊断。缺少事实时写“待医生补充”。已复核事实优先级高于当前模板值。
            病历正文只能使用病历书写语言：禁止出现“当前复核事实”“未提供……事实”“本段待医生补充”等面向系统的说明文字，也不要用括号解释缺项原因；某项缺少事实时该项只写“待医生补充”四个字。
            每段必须独立成段输出，严禁把相邻段落（尤其是签名行）并入上一段或在段内重复签名行。
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
        context.put("referenceDocument", numberReferenceParagraphs(referenceDocumentText));
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

    private String numberReferenceParagraphs(String referenceDocumentText) {
        String text = safe(referenceDocumentText);
        if (text.isEmpty()) return text;
        StringBuilder numbered = new StringBuilder(text.length() + text.length() / 16);
        int index = 1;
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) continue;
            if (numbered.length() > 0) numbered.append('\n');
            numbered.append("【第").append(index++).append("段】").append(line);
        }
        return numbered.toString();
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

    private record NumberedParagraph(int n, String text) {}

    private record RelaySlice(int index, int startN, int endN, String referenceText) {
        String label() {
            return "第" + index + "分片（第" + startN + "-" + endN + "段）";
        }
    }

    private record SliceResult(RelaySlice slice, List<NumberedParagraph> items) {
        SliceResult {
            items = List.copyOf(items);
        }
    }

    public record AiGeneration(ArrayNode paragraphs, Map<String, String> nodeReplacements, String model) {
        public AiGeneration {
            nodeReplacements = Map.copyOf(nodeReplacements);
        }
    }
}
