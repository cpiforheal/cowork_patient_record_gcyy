package com.example.coshare_patientrecord_sys;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class ClinicDoubaoTtsService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_TEXT_LENGTH = 2200;

    private final ClinicAiConfigService aiConfigService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ClinicDoubaoTtsService(ClinicAiConfigService aiConfigService, ObjectMapper objectMapper) {
        this.aiConfigService = aiConfigService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Map<String, Object> speak(TtsSpeakRequest request, AuthSessionService.SessionUser user) {
        AuthSessionService.SessionUser currentUser = user == null ? AuthPermission.currentUserOrThrow() : user;
        requireAllowedRole(currentUser);
        String text = trimText(request == null ? "" : request.text(), MAX_TEXT_LENGTH);
        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "朗读文本不能为空");
        }

        ClinicAiConfigService.EffectiveTtsConfig config = aiConfigService.resolveDoubaoTtsConfig();
        if (!config.enabled() || safe(config.baseUrl()).isBlank() || safe(config.apiKey()).isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "豆包语音朗读未启用或未配置 Base URL / API Key");
        }

        TtsRuntimeConfig runtimeConfig = new TtsRuntimeConfig(
            config.baseUrl(),
            config.apiKey(),
            safe(config.model()).isBlank() ? "doubao-tts" : safe(config.model()),
            config.resourceId(),
            config.voiceType(),
            config.speedRatio()
        );
        return speakWithConfig(text, request == null ? "" : request.voiceType(), request == null ? null : request.speedRatio(), runtimeConfig);
    }

    public Map<String, Object> test(TtsConfigTestRequest request) {
        String text = trimText(request == null ? "" : request.text(), 300);
        if (text.isBlank()) text = "豆包语音朗读配置检测成功。";
        String apiKey = safe(request == null ? "" : request.apiKey());
        if (apiKey.isBlank() && request != null && Boolean.TRUE.equals(request.keepExistingApiKey())) {
            apiKey = aiConfigService.resolveDoubaoTtsConfig().apiKey();
        }
        if (safe(request == null ? "" : request.baseUrl()).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先填写豆包语音朗读 Base URL");
        }
        if (apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先填写 API Key，或勾选保留现有 Key");
        }
        TtsRuntimeConfig runtimeConfig = new TtsRuntimeConfig(
            request.baseUrl(),
            apiKey,
            safe(request.model()).isBlank() ? "doubao-tts" : safe(request.model()),
            request.resourceId(),
            request.voiceType(),
            normalizeSpeedRatio(request.speedRatio(), 1.0)
        );
        return speakWithConfig(text, request.voiceType(), request.speedRatio(), runtimeConfig);
    }

    private Map<String, Object> speakWithConfig(String text, String overrideVoiceType, Double overrideSpeedRatio, TtsRuntimeConfig config) {
        String model = safe(config.model()).isBlank() ? "doubao-tts" : safe(config.model());
        String resourceId = safe(config.resourceId());
        String voiceType = safe(overrideVoiceType);
        if (voiceType.isBlank()) voiceType = safe(config.voiceType());
        double speedRatio = normalizeSpeedRatio(overrideSpeedRatio, config.speedRatio());
        try {
            String ttsUrl = normalizeTtsUrl(config.baseUrl());
            ObjectNode payload = buildPayload(ttsUrl, text, model, resourceId, voiceType, speedRatio);
            ApiKeyParts apiKeyParts = parseApiKey(config.apiKey());

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(ttsUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", authorizationHeader(ttsUrl, apiKeyParts.accessKey()))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json, audio/mpeg, audio/wav, audio/*")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8));
            if (!apiKeyParts.appId().isBlank()) {
                builder.header("X-Api-App-Id", apiKeyParts.appId());
            }
            if (!apiKeyParts.accessKey().isBlank()) {
                builder.header("X-Api-Key", apiKeyParts.accessKey());
                builder.header("X-Api-Access-Key", apiKeyParts.accessKey());
            }
            if (!resourceId.isBlank()) {
                builder.header("X-Api-Resource-Id", resourceId);
                builder.header("X-Resource-Id", resourceId);
            }

            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, buildUpstreamErrorMessage(response.statusCode(), response.body()));
            }

            TtsAudio audio = extractAudio(response);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("audioBase64", audio.audioBase64());
            result.put("mimeType", audio.mimeType());
            result.put("generatedAt", LocalDateTime.now().format(TIME_FORMATTER));
            result.put("voiceType", voiceType);
            result.put("model", model);
            result.put("textLength", text.length());
            return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
        } catch (ResponseStatusException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "豆包语音朗读 Base URL 格式不正确");
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "豆包语音朗读暂时不可用：" + safe(error.getMessage()));
        }
    }

    private TtsAudio extractAudio(HttpResponse<byte[]> response) throws Exception {
        String contentType = response.headers().firstValue("Content-Type").orElse("audio/mpeg");
        byte[] body = response.body() == null ? new byte[0] : response.body();
        if (body.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "豆包语音朗读返回了空音频");
        }
        if (contentType.toLowerCase().startsWith("audio/")) {
            return new TtsAudio(Base64.getEncoder().encodeToString(body), contentType.split(";")[0]);
        }

        String bodyText = new String(body, StandardCharsets.UTF_8);
        if (contentType.toLowerCase().contains("text/event-stream") || bodyText.stripLeading().startsWith("event:")) {
            return extractSseAudio(bodyText);
        }

        JsonNode root = objectMapper.readTree(bodyText);
        return extractJsonAudio(root, "audio/mpeg");
    }

    private TtsAudio extractSseAudio(String bodyText) throws Exception {
        List<byte[]> audioChunks = new ArrayList<>();
        String mimeType = "audio/mpeg";
        StringBuilder dataBuffer = new StringBuilder();
        for (String rawLine : bodyText.split("\\R")) {
            String line = rawLine.trim();
            if (line.isBlank()) {
                collectSseDataAudio(dataBuffer.toString(), audioChunks);
                dataBuffer.setLength(0);
                continue;
            }
            if (line.startsWith("data:")) {
                dataBuffer.append(line.substring("data:".length()).trim());
            }
        }
        collectSseDataAudio(dataBuffer.toString(), audioChunks);
        if (audioChunks.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "豆包语音朗读 SSE 未返回可播放音频");
        }
        return new TtsAudio(encodeAudioChunks(audioChunks), mimeType);
    }

    private void collectSseDataAudio(String dataText, List<byte[]> audioChunks) throws Exception {
        String value = safe(dataText);
        if (value.isBlank() || "[DONE]".equalsIgnoreCase(value)) return;
        if (!value.startsWith("{")) return;
        JsonNode root = objectMapper.readTree(value);
        String code = safe(root.path("code").asText(""));
        if (!code.isBlank() && !List.of("0", "20000000").contains(code)) {
            String message = safe(root.path("message").asText(root.path("msg").asText("")));
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "豆包语音朗读上游返回业务错误：" + code + (message.isBlank() ? "" : "，" + message)
            );
        }
        String audioBase64 = extractAudioText(root);
        if (!audioBase64.isBlank()) {
            audioChunks.add(Base64.getDecoder().decode(stripDataUrl(audioBase64).audioBase64()));
        }
    }

    private TtsAudio extractJsonAudio(JsonNode root, String fallbackMimeType) {
        String audioBase64 = extractAudioText(root);
        if (audioBase64.startsWith("data:")) {
            return stripDataUrl(audioBase64);
        }
        if (audioBase64.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "豆包语音朗读未返回可播放音频");
        }
        String mimeType = safe(root.path("mimeType").asText(root.path("mime_type").asText(fallbackMimeType)));
        return new TtsAudio(audioBase64, mimeType.isBlank() ? "audio/mpeg" : mimeType);
    }

    private String extractAudioText(JsonNode root) {
        JsonNode audioNode = firstTextNode(
            root.path("audio"),
            root.path("audio_base64"),
            root.path("audioBase64"),
            root.path("audio_data"),
            root.path("audioData"),
            root.path("payload"),
            root.path("data"),
            root.path("data").path("audio"),
            root.path("data").path("audio_base64"),
            root.path("data").path("audioBase64"),
            root.path("data").path("audio_data"),
            root.path("data").path("audioData"),
            root.path("data").path("payload"),
            root.path("result").path("audio"),
            root.path("result").path("audio_base64"),
            root.path("result").path("audioBase64"),
            root.path("result").path("audio_data"),
            root.path("result").path("audioData"),
            root.path("result").path("payload")
        );
        return safe(audioNode == null ? "" : audioNode.asText());
    }

    private ObjectNode buildPayload(String ttsUrl, String text, String model, String resourceId, String voiceType, double speedRatio) {
        ObjectNode payload = objectMapper.createObjectNode();
        if (ttsUrl.contains("/api/v3/tts/")) {
            payload.putObject("user").put("uid", "clinic_tts_" + UUID.randomUUID());
            ObjectNode reqParams = payload.putObject("req_params");
            reqParams.put("text", text);
            reqParams.put("speaker", voiceType);
            ObjectNode audioParams = reqParams.putObject("audio_params");
            audioParams.put("format", "mp3");
            audioParams.put("sample_rate", 24000);
            audioParams.put("speech_rate", Math.round((speedRatio - 1.0) * 100));
            if (!model.isBlank()) reqParams.put("model", model);
            if (!resourceId.isBlank()) reqParams.put("resource_id", resourceId);
            return payload;
        }

        payload.put("model", model);
        payload.put("input", text);
        payload.put("text", text);
        payload.put("voice", voiceType);
        payload.put("voice_type", voiceType);
        payload.put("speed", speedRatio);
        payload.put("speed_ratio", speedRatio);
        payload.put("format", "mp3");
        payload.put("response_format", "mp3");
        if (!resourceId.isBlank()) {
            payload.put("resource_id", resourceId);
            payload.put("resourceId", resourceId);
        }
        return payload;
    }

    private String encodeAudioChunks(List<byte[]> audioChunks) {
        int totalLength = audioChunks.stream().mapToInt(chunk -> chunk.length).sum();
        byte[] merged = new byte[totalLength];
        int offset = 0;
        for (byte[] chunk : audioChunks) {
            System.arraycopy(chunk, 0, merged, offset, chunk.length);
            offset += chunk.length;
        }
        return Base64.getEncoder().encodeToString(merged);
    }

    private ApiKeyParts parseApiKey(String apiKey) {
        String value = safe(apiKey);
        if (value.regionMatches(true, 0, "Bearer;", 0, 7)) {
            value = value.substring(7).trim();
        }
        if (value.contains("|")) {
            String[] parts = value.split("\\|", 2);
            return new ApiKeyParts(safe(parts[0]), safe(parts.length > 1 ? parts[1] : ""));
        }
        return new ApiKeyParts("", value);
    }

    private String authorizationHeader(String ttsUrl, String accessKey) {
        if (ttsUrl.contains("openspeech.bytedance.com/api/v3/tts/")) {
            return "Bearer;" + accessKey;
        }
        return "Bearer " + accessKey;
    }

    private TtsAudio stripDataUrl(String audioBase64) {
        if (audioBase64.startsWith("data:")) {
            int commaIndex = audioBase64.indexOf(',');
            if (commaIndex > 0) {
                String mimeType = audioBase64.substring(5, audioBase64.indexOf(';') > 5 ? audioBase64.indexOf(';') : commaIndex);
                return new TtsAudio(audioBase64.substring(commaIndex + 1), mimeType);
            }
        }
        return new TtsAudio(audioBase64, "audio/mpeg");
    }

    private JsonNode firstTextNode(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isTextual() && !safe(node.asText()).isBlank()) return node;
        }
        return null;
    }

    private String buildUpstreamErrorMessage(int statusCode, byte[] body) {
        String bodyText = trimText(new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8), 300);
        if (statusCode == 401 || statusCode == 403) {
            return "豆包语音朗读鉴权失败，请检查 TTS API Key、资源 ID 和服务开通状态";
        }
        if (statusCode == 404) {
            return "豆包语音朗读接口不存在，请在 AI 接口配置中填写完整的 TTS Base URL";
        }
        if (statusCode == 429) {
            return "豆包语音朗读调用过于频繁或额度不足，请稍后重试";
        }
        return "豆包语音朗读上游返回 HTTP " + statusCode + (bodyText.isBlank() ? "" : "：" + bodyText);
    }

    private void requireAllowedRole(AuthSessionService.SessionUser user) {
        String role = safe(user == null ? "" : user.role());
        if (
            !role.equals("admin")
                && !role.equals("doctor")
                && !role.equals("quality")
                && !role.equals("nurse")
                && !role.equals("nursing")
                && !role.equals("frontdesk")
                && !role.equals("reception")
        ) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无权使用豆包语音朗读");
        }
    }

    private static String normalizeTtsUrl(String baseUrl) {
        return safe(baseUrl).replaceAll("/+$", "");
    }

    private static String trimText(String text, int maxLength) {
        String value = safe(text);
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private static double normalizeSpeedRatio(Object value, double fallback) {
        try {
            double number = value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(safe(value));
            if (Double.isNaN(number) || Double.isInfinite(number)) return fallback;
            return Math.max(0.5, Math.min(2.0, number));
        } catch (Exception error) {
            return Math.max(0.5, Math.min(2.0, fallback <= 0 ? 1.0 : fallback));
        }
    }

    private static String safe(Object value) {
        return String.valueOf(value == null ? "" : value).trim();
    }

    public record TtsSpeakRequest(String text, String voiceType, Double speedRatio) {}

    public record TtsConfigTestRequest(
        String baseUrl,
        String apiKey,
        Boolean keepExistingApiKey,
        String model,
        String resourceId,
        String voiceType,
        Double speedRatio,
        String text
    ) {}

    private record TtsAudio(String audioBase64, String mimeType) {}

    private record ApiKeyParts(String appId, String accessKey) {}

    private record TtsRuntimeConfig(String baseUrl, String apiKey, String model, String resourceId, String voiceType, double speedRatio) {}
}
