package com.coshare.patientrecord.clinic.service;

import com.coshare.patientrecord.ai.model.EffectiveAiConfig;
import com.coshare.patientrecord.ai.service.AiCallGuard;
import com.coshare.patientrecord.ai.service.ClinicAiConfigService;
import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.security.AuthPermission;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 首页数据看板「AI 汇总分析」：对指定时间窗（近 7/14/30 天）内的患者登记主诉做流式汇总分析，
 * 输出患者意向与潜在发病热门、健康宣教建议、宣传内容建议。SSE 同步直写（复用化验单识别流式方案），
 * 分析结果仅作为看板暂存参考，不进入病历与导出文档。
 */
@Service
@Profile("mysql")
public class HomeTrendInsightService {

    private static final Logger log = LoggerFactory.getLogger(HomeTrendInsightService.class);
    private static final String AI_MODEL = "glm-5.3-flash";
    private static final int MAX_COMPLAINTS = 200;
    private static final int COMPLAINT_MAX_LENGTH = 200;

    private final ClinicAiConfigService aiConfigService;
    private final AiCallGuard aiCallGuard;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HomeTrendInsightService(
        ClinicAiConfigService aiConfigService,
        AiCallGuard aiCallGuard,
        ObjectMapper objectMapper
    ) {
        this.aiConfigService = aiConfigService;
        this.aiCallGuard = aiCallGuard;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public record TrendInsightRequest(int days, int total, List<String> complaints) {}

    public void streamInsight(TrendInsightRequest request, SessionUser user, HttpServletResponse response)
        throws IOException {
        // 守卫阶段：异常在任何字节写出前抛出，前端仍能收到标准 JSON 错误
        AuthPermission.requireAnyRole("仅管理员可使用AI汇总分析", "admin");
        List<String> complaints = sanitizeComplaints(request);
        if (complaints.isEmpty()) throw badRequest("当前时间窗内没有可分析的患者主诉");
        EffectiveAiConfig config = aiConfigService.resolveEffectiveConfig();
        String apiKey = String.valueOf(config.apiKey() == null ? "" : config.apiKey()).trim();
        if (!config.enabled() || apiKey.isBlank() || config.baseUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务未配置，请联系管理员");
        }
        int days = request == null ? 30 : Math.max(1, Math.min(request.days(), 90));
        int total = request == null ? 0 : Math.max(0, request.total());
        String endpoint = normalizeChatCompletionsUrl(config.baseUrl());
        ObjectNode payload = buildPayload(endpoint, apiKey, days, total, complaints);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        OutputStream sink = response.getOutputStream();
        StringBuilder sse = new StringBuilder();
        Runnable flush = () -> {
            try {
                sink.write(sse.toString().getBytes(StandardCharsets.UTF_8));
                sink.flush();
            } catch (IOException ignored) {
                // 客户端断开时忽略写失败，循环会在下一次读/写中自然退出
            }
            sse.setLength(0);
        };
        java.util.function.Consumer<ObjectNode> send = event -> {
            try {
                sse.append("data: ").append(objectMapper.writeValueAsString(event)).append("\n\n");
            } catch (JsonProcessingException ignored) {
                // 事件序列化失败则跳过该事件
            }
            flush.run();
        };
        long startedAt = System.currentTimeMillis();
        try {
            ObjectNode status = objectMapper.createObjectNode();
            status.put("type", "status");
            status.put("message", "已接收 " + complaints.size() + " 条主诉，正在连接分析模型…");
            send.accept(status);
            StringBuilder contentBuilder = new StringBuilder();
            try (InputStream upstream = callChatStream(endpoint, apiKey, payload)) {
                ObjectNode connected = objectMapper.createObjectNode();
                connected.put("type", "status");
                connected.put("message", "已连接模型，正在输出…");
                send.accept(connected);
                BufferedReader reader = new BufferedReader(new InputStreamReader(upstream, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) continue;
                    if ("[DONE]".equals(data)) break;
                    JsonNode chunk = null;
                    try {
                        chunk = objectMapper.readTree(data);
                    } catch (JsonProcessingException ignored) {
                        // 忽略无法解析的心跳/杂项行
                    }
                    if (chunk == null) continue;
                    String delta = chunk.path("choices").path(0).path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        contentBuilder.append(delta);
                        ObjectNode deltaEvent = objectMapper.createObjectNode();
                        deltaEvent.put("type", "delta");
                        deltaEvent.put("text", delta);
                        send.accept(deltaEvent);
                    }
                }
            }
            String content = contentBuilder.toString();
            if (content.isBlank()) throw badRequest("AI 分析未返回内容，请重试");
            ObjectNode done = objectMapper.createObjectNode();
            done.put("type", "done");
            done.put("chars", content.length());
            send.accept(done);
            log.info("Home trend insight finished: days={}, complaints={}, chars={}, costMs={}, operator={}",
                days, complaints.size(), content.length(), System.currentTimeMillis() - startedAt,
                user == null ? "" : user.name());
        } catch (ResponseStatusException error) {
            sendError(send, error.getReason() == null ? "AI 分析失败" : error.getReason());
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            sendError(send, "AI 分析连接中断，请重试");
        }
    }

    private List<String> sanitizeComplaints(TrendInsightRequest request) {
        List<String> result = new ArrayList<>();
        if (request == null || request.complaints() == null) return result;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String raw : request.complaints()) {
            String text = String.valueOf(raw == null ? "" : raw).replaceAll("\\s+", " ").trim();
            if (text.isBlank() || "—".equals(text)) continue;
            if (text.length() > COMPLAINT_MAX_LENGTH) text = text.substring(0, COMPLAINT_MAX_LENGTH);
            if (seen.add(text)) result.add(text);
            if (result.size() >= MAX_COMPLAINTS) break;
        }
        return result;
    }

    private ObjectNode buildPayload(String endpoint, String apiKey, int days, int total, List<String> complaints) {
        StringBuilder listing = new StringBuilder();
        for (int i = 0; i < complaints.size(); i++) {
            listing.append(i + 1).append(". ").append(complaints.get(i)).append("\n");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", AI_MODEL);
        payload.put("temperature", 0.5);
        payload.put("max_tokens", 1500);
        payload.put("stream", true);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt());
        messages.addObject()
            .put(
                "role",
                "user"
            )
            .put(
                "content",
                "时间窗：近 "
                    + days
                    + " 天 · 来访患者 "
                    + total
                    + " 人 · 有效主诉 "
                    + complaints.size()
                    + " 条\n主诉记录：\n"
                    + listing
            );
        return payload;
    }

    private String systemPrompt() {
        return """
            你是肛肠专科医院的患者运营与健康宣教助手。基于给定时间窗内的患者登记主诉原始记录做汇总分析，输出三部分：
            一、患者意向与潜在发病热门：归纳主诉反映的就诊动机与症状热点（如便血、疼痛、肿物脱出、潮湿溢脓等），
               指出潜在高发病种趋势（痔/肛裂/肛瘘/肛周脓肿等），按热度排序，人数用"约 N 条主诉提及"口径估算。
            二、健康宣教建议：针对上述热点给出 3-5 条具体宣教主题与要点（贴近门诊场景、可落地）。
            三、宣传内容建议：给出 3-5 条可直接用于院内宣教栏/公众号/短视频的宣传选题创意（短标题+一句话说明）。
            要求：仅基于给定主诉信息归纳，不得编造未提及的症状或结论；分条输出、每条前用短标题；语言精炼、直接可用。
            """;
    }

    private InputStream callChatStream(String endpoint, String apiKey, ObjectNode payload)
        throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(120))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
            .build();
        HttpResponse<InputStream> response = aiCallGuard.execute(
            () -> httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 分析服务调用失败（上游状态 " + response.statusCode() + "）");
        }
        return response.body();
    }

    private void sendError(java.util.function.Consumer<ObjectNode> send, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("type", "error");
        error.put("message", message);
        send.accept(error);
    }

    private static String normalizeChatCompletionsUrl(String rawUrl) {
        String url = String.valueOf(rawUrl == null ? "" : rawUrl).trim();
        if (url.isBlank()) return url;
        url = url.replaceAll("/+$", "");
        if (url.endsWith("/chat/completions")) return url;
        if (url.endsWith("/v1")) return url + "/chat/completions";
        return url + "/v1/chat/completions";
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
