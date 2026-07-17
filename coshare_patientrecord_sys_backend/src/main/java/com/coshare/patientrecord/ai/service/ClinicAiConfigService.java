package com.coshare.patientrecord.ai.service;

import com.coshare.patientrecord.ai.entity.StoredAiConfig;
import com.coshare.patientrecord.ai.model.EffectiveAiConfig;
import com.coshare.patientrecord.ai.model.EffectiveTtsConfig;
import com.coshare.patientrecord.ai.repository.ClinicAiConfigRepository;
import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@Profile("mysql")
public class ClinicAiConfigService {

    private static final String CONFIG_ID = "default";
    private static final String DOUBAO_CONFIG_ID = "doubao_assistant";
    private static final String DOUBAO_TTS_CONFIG_ID = "doubao_tts";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final ClinicAiConfigRepository configRepository;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String defaultBaseUrl;
    private final String defaultApiKey;
    private final String defaultModel;
    private final String doubaoDefaultBaseUrl;
    private final String doubaoDefaultApiKey;
    private final String doubaoDefaultModel;
    private final String doubaoTtsDefaultBaseUrl;
    private final String doubaoTtsDefaultApiKey;
    private final String doubaoTtsDefaultModel;
    private final String doubaoTtsDefaultResourceId;
    private final String doubaoTtsDefaultVoiceType;
    private final double doubaoTtsDefaultSpeedRatio;
    private final byte[] encryptionKey;

    public ClinicAiConfigService(
        ClinicAiConfigRepository configRepository,
        ObjectMapper objectMapper,
        @Value("${clinic.ai.base-url:}") String defaultBaseUrl,
        @Value("${clinic.ai.api-key:}") String defaultApiKey,
        @Value("${clinic.ai.model:}") String defaultModel,
        @Value("${clinic.ai.doubao.base-url:}") String doubaoDefaultBaseUrl,
        @Value("${clinic.ai.doubao.api-key:}") String doubaoDefaultApiKey,
        @Value("${clinic.ai.doubao.model:}") String doubaoDefaultModel,
        @Value("${clinic.ai.doubao.tts.base-url:}") String doubaoTtsDefaultBaseUrl,
        @Value("${clinic.ai.doubao.tts.api-key:}") String doubaoTtsDefaultApiKey,
        @Value("${clinic.ai.doubao.tts.model:}") String doubaoTtsDefaultModel,
        @Value("${clinic.ai.doubao.tts.resource-id:}") String doubaoTtsDefaultResourceId,
        @Value("${clinic.ai.doubao.tts.voice-type:}") String doubaoTtsDefaultVoiceType,
        @Value("${clinic.ai.doubao.tts.speed-ratio:1.0}") double doubaoTtsDefaultSpeedRatio,
        @Value("${clinic.ai.config-secret:}") String configSecret,
        @Value("${spring.profiles.active:}") String activeProfiles
    ) {
        this.configRepository = configRepository;
        this.objectMapper = objectMapper;
        this.defaultBaseUrl = normalizeBaseUrl(defaultBaseUrl);
        this.defaultApiKey = normalizeApiKey(defaultApiKey);
        this.defaultModel = safe(defaultModel);
        this.doubaoDefaultBaseUrl = normalizeBaseUrl(firstNonBlank(doubaoDefaultBaseUrl, System.getenv("CLINIC_AI_DOUBAO_BASE_URL")));
        this.doubaoDefaultApiKey = normalizeApiKey(firstNonBlank(doubaoDefaultApiKey, System.getenv("CLINIC_AI_DOUBAO_API_KEY")));
        this.doubaoDefaultModel = safe(firstNonBlank(doubaoDefaultModel, System.getenv("CLINIC_AI_DOUBAO_MODEL")));
        this.doubaoTtsDefaultBaseUrl = normalizeBaseUrl(firstNonBlank(doubaoTtsDefaultBaseUrl, System.getenv("CLINIC_AI_DOUBAO_TTS_BASE_URL")));
        this.doubaoTtsDefaultApiKey = normalizeApiKey(firstNonBlank(doubaoTtsDefaultApiKey, System.getenv("CLINIC_AI_DOUBAO_TTS_API_KEY")));
        this.doubaoTtsDefaultModel = safe(firstNonBlank(doubaoTtsDefaultModel, System.getenv("CLINIC_AI_DOUBAO_TTS_MODEL")));
        this.doubaoTtsDefaultResourceId = safe(firstNonBlank(doubaoTtsDefaultResourceId, System.getenv("CLINIC_AI_DOUBAO_TTS_RESOURCE_ID")));
        this.doubaoTtsDefaultVoiceType = safe(firstNonBlank(doubaoTtsDefaultVoiceType, System.getenv("CLINIC_AI_DOUBAO_TTS_VOICE_TYPE")));
        this.doubaoTtsDefaultSpeedRatio = normalizeSpeedRatio(doubaoTtsDefaultSpeedRatio);
        this.encryptionKey = deriveEncryptionKey(configSecret, activeProfiles);
    }

    public ObjectNode status() {
        return statusFor(CONFIG_ID);
    }

    public ObjectNode doubaoStatus() {
        return statusFor(DOUBAO_CONFIG_ID);
    }

    public ObjectNode doubaoTtsStatus() {
        return statusFor(DOUBAO_TTS_CONFIG_ID);
    }

    public ObjectNode statusFor(String configId) {
        AiDefaults defaults = defaultsFor(configId);
        StoredAiConfig stored = readStoredConfig(configId, defaults.model());
        EffectiveAiConfig effective = resolveEffectiveConfig(configId);
        ObjectNode status = objectMapper.createObjectNode();
        status.put("baseUrl", stored == null ? defaults.baseUrl() : stored.baseUrl());
        status.put("model", stored == null ? defaults.model() : stored.model());
        status.put("resourceId", stored == null ? defaults.resourceId() : stored.resourceId());
        status.put("voiceType", stored == null ? defaults.voiceType() : stored.voiceType());
        status.put("speedRatio", stored == null ? defaults.speedRatio() : stored.speedRatio());
        status.put("enabled", stored == null || stored.enabled());
        status.put("apiKeyConfigured", !effective.apiKey().isBlank());
        status.put("apiKeyMasked", maskKey(effective.apiKey()));
        status.put("usingRuntimeConfig", stored != null);
        status.put("updatedAt", stored == null ? "" : stored.updatedAt());
        status.put("updatedBy", stored == null ? "" : stored.updatedBy());
        return status;
    }

    public ObjectNode updateConfig(Map<String, Object> payload, SessionUser user) {
        return updateConfigFor(CONFIG_ID, payload, user);
    }

    public ObjectNode updateDoubaoConfig(Map<String, Object> payload, SessionUser user) {
        return updateConfigFor(DOUBAO_CONFIG_ID, payload, user);
    }

    public ObjectNode updateDoubaoTtsConfig(Map<String, Object> payload, SessionUser user) {
        return updateConfigFor(DOUBAO_TTS_CONFIG_ID, payload, user);
    }

    public ObjectNode detectDoubaoModels(Map<String, Object> payload) {
        String baseUrl = normalizeBaseUrl(payload == null ? "" : payload.get("baseUrl"));
        if (baseUrl.isBlank()) {
            baseUrl = resolveEffectiveConfig(DOUBAO_CONFIG_ID).baseUrl();
        }
        if (baseUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先填写豆包 Base URL");
        }

        String apiKey = normalizeApiKey(payload == null ? "" : payload.get("apiKey"));
        boolean keepExisting = payload != null
            && payload.containsKey("keepExistingApiKey")
            && Boolean.parseBoolean(String.valueOf(payload.get("keepExistingApiKey")));
        if (apiKey.isBlank() && keepExisting) {
            apiKey = resolveDoubaoApiKeyForDetection();
        }
        if (apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先填写 API Key，或勾选保留现有 Key 后再检测模型");
        }

        String modelsUrl = normalizeModelsUrl(baseUrl);
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(modelsUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "模型检测鉴权失败：请确认 API Key 有效、未带中文逗号或多余空格，并确认火山方舟账号已开通该服务"
                );
            }
            if (response.statusCode() == 404) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "模型检测接口不存在：当前 Base URL 推导出的 /models 不可用，请确认是否填写为 https://ark.cn-beijing.volces.com/api/v3"
                );
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型检测失败：上游返回 HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            ArrayNode models = objectMapper.createArrayNode();
            if (data.isArray()) {
                for (JsonNode item : data) {
                    String id = safe(item.path("id").asText(""));
                    if (id.isBlank()) continue;
                    ObjectNode model = objectMapper.createObjectNode();
                    model.put("id", id);
                    model.put("name", safe(item.path("name").asText(id)));
                    model.put("ownedBy", safe(item.path("owned_by").asText(item.path("ownedBy").asText(""))));
                    models.add(model);
                }
            }
            ObjectNode result = objectMapper.createObjectNode();
            result.set("models", models);
            result.put("checkedAt", LocalDateTime.now().format(TIME_FORMATTER));
            if (models.isEmpty()) {
                result.put("warning", "已连通模型接口，但没有返回可选模型；可继续手动填写火山方舟控制台中的推理接入点 ID");
            }
            return result;
        } catch (ResponseStatusException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Base URL 格式不正确，请检查后再检测");
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型检测失败：" + safe(error.getMessage()));
        }
    }

    public ObjectNode updateConfigFor(String configId, Map<String, Object> payload, SessionUser user) {
        AiDefaults defaults = defaultsFor(configId);
        StoredAiConfig current = readStoredConfig(configId, defaults.model());
        String baseUrl = normalizeBaseUrl(payload.get("baseUrl"));
        String model = safe(payload.get("model")).isBlank() ? defaults.model() : safe(payload.get("model"));
        String resourceId = safe(payload.get("resourceId")).isBlank() ? defaults.resourceId() : safe(payload.get("resourceId"));
        String voiceType = safe(payload.get("voiceType")).isBlank() ? defaults.voiceType() : safe(payload.get("voiceType"));
        double speedRatio = normalizeSpeedRatio(payload.containsKey("speedRatio") ? payload.get("speedRatio") : defaults.speedRatio());
        boolean enabled = !payload.containsKey("enabled") || Boolean.parseBoolean(String.valueOf(payload.get("enabled")));
        String apiKey = normalizeApiKey(payload.get("apiKey"));
        boolean keepExisting = payload.containsKey("keepExistingApiKey")
            && Boolean.parseBoolean(String.valueOf(payload.get("keepExistingApiKey")));

        if (baseUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI base_url is required");
        }
        if (model.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI model is required");
        }

        String cipher = current == null ? "" : current.apiKeyCipher();
        if (!apiKey.isBlank()) {
            cipher = encrypt(apiKey);
        } else if (!keepExisting || cipher.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI API Key is required, or keep the existing key");
        }

        String updatedAt = LocalDateTime.now().format(TIME_FORMATTER);
        String updatedBy = user == null ? "" : user.name();
        configRepository.saveConfig(configId, baseUrl, cipher, model, enabled, updatedAt, updatedBy, resourceId, voiceType, speedRatio);
        return statusFor(configId);
    }

    public EffectiveAiConfig resolveEffectiveConfig() {
        return resolveEffectiveConfig(CONFIG_ID);
    }

    public EffectiveAiConfig resolveDoubaoConfig() {
        return resolveEffectiveConfig(DOUBAO_CONFIG_ID);
    }

    public EffectiveTtsConfig resolveDoubaoTtsConfig() {
        AiDefaults defaults = defaultsFor(DOUBAO_TTS_CONFIG_ID);
        StoredAiConfig stored = readStoredConfig(DOUBAO_TTS_CONFIG_ID, defaults.model());
        if (stored == null) {
            return new EffectiveTtsConfig(
                defaults.baseUrl(),
                defaults.apiKey(),
                defaults.model(),
                defaults.resourceId(),
                defaults.voiceType(),
                defaults.speedRatio(),
                false,
                true
            );
        }
        if (!stored.enabled()) {
            return new EffectiveTtsConfig(
                stored.baseUrl(),
                "",
                stored.model(),
                stored.resourceId(),
                stored.voiceType(),
                stored.speedRatio(),
                true,
                false
            );
        }
        return new EffectiveTtsConfig(
            stored.baseUrl(),
            normalizeApiKey(decrypt(stored.apiKeyCipher())),
            stored.model(),
            stored.resourceId(),
            stored.voiceType(),
            stored.speedRatio(),
            true,
            true
        );
    }

    public EffectiveAiConfig resolveEffectiveConfig(String configId) {
        AiDefaults defaults = defaultsFor(configId);
        StoredAiConfig stored = readStoredConfig(configId, defaults.model());
        if (stored == null) {
            return new EffectiveAiConfig(defaults.baseUrl(), defaults.apiKey(), defaults.model(), false, true);
        }
        if (!stored.enabled()) {
            return new EffectiveAiConfig(stored.baseUrl(), "", stored.model(), true, false);
        }
        return new EffectiveAiConfig(stored.baseUrl(), normalizeApiKey(decrypt(stored.apiKeyCipher())), stored.model(), true, true);
    }

    private StoredAiConfig readStoredConfig(String configId, String fallbackModel) {
        return configRepository.readStoredConfig(configId, fallbackModel);
    }

    private AiDefaults defaultsFor(String configId) {
        if (DOUBAO_CONFIG_ID.equals(configId)) {
            String model = doubaoDefaultModel.isBlank() ? "doubao-seed-1-6" : doubaoDefaultModel;
            return new AiDefaults(doubaoDefaultBaseUrl, doubaoDefaultApiKey, model, "", "", 1.0);
        }
        if (DOUBAO_TTS_CONFIG_ID.equals(configId)) {
            String model = doubaoTtsDefaultModel.isBlank() ? "doubao-tts" : doubaoTtsDefaultModel;
            return new AiDefaults(
                doubaoTtsDefaultBaseUrl,
                doubaoTtsDefaultApiKey,
                model,
                doubaoTtsDefaultResourceId,
                doubaoTtsDefaultVoiceType,
                doubaoTtsDefaultSpeedRatio
            );
        }
        return new AiDefaults(defaultBaseUrl, defaultApiKey, defaultModel, "", "", 1.0);
    }

    private String resolveDoubaoApiKeyForDetection() {
        AiDefaults defaults = defaultsFor(DOUBAO_CONFIG_ID);
        StoredAiConfig stored = readStoredConfig(DOUBAO_CONFIG_ID, defaults.model());
        if (stored != null && !safe(stored.apiKeyCipher()).isBlank()) {
            return normalizeApiKey(decrypt(stored.apiKeyCipher()));
        }
        return normalizeApiKey(defaults.apiKey());
    }

    private byte[] deriveEncryptionKey(String configuredSecret, String activeProfiles) {
        String secret = safe(configuredSecret);
        if (secret.isBlank()) {
            secret = safe(System.getenv("AI_CONFIG_SECRET"));
        }
        boolean production = java.util.Arrays.stream(safe(activeProfiles).toLowerCase(java.util.Locale.ROOT).split("[,;\\s]+"))
            .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
        if (secret.isBlank() && production) {
            throw new IllegalStateException("Production startup requires AI_CONFIG_SECRET");
        }
        if (secret.isBlank()) {
            byte[] temporarySecret = new byte[32];
            secureRandom.nextBytes(temporarySecret);
            return temporarySecret;
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("Failed to initialize AI config secret", error);
        }
    }

    private String encrypt(String plainText) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to encrypt AI API Key");
        }
    }

    private String decrypt(String cipherText) {
        if (safe(cipherText).isBlank()) return "";
        try {
            byte[] raw = Base64.getDecoder().decode(cipherText);
            ByteBuffer buffer = ByteBuffer.wrap(raw);
            byte[] iv = new byte[GCM_IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to decrypt AI API Key, please save the config again");
        }
    }

    private static String maskKey(String key) {
        String value = safe(key);
        if (value.isBlank()) return "";
        if (value.length() <= 10) return "******";
        return value.substring(0, Math.min(6, value.length())) + "..." + value.substring(Math.max(0, value.length() - 4));
    }

    private static String normalizeBaseUrl(Object value) {
        return stripWrappingQuotes(safe(value)).replaceAll("/+$", "");
    }

    private static String normalizeModelsUrl(String baseUrl) {
        String url = normalizeBaseUrl(baseUrl);
        if (url.isBlank()) return "";
        if (url.endsWith("/models")) return url;
        if (url.endsWith("/chat/completions")) {
            return url.substring(0, url.length() - "/chat/completions".length()) + "/models";
        }
        if (url.endsWith("/v1") || url.endsWith("/v3") || url.endsWith("/api/v3")) {
            return url + "/models";
        }
        return url + "/v1/models";
    }

    private static String normalizeApiKey(Object value) {
        String key = stripWrappingQuotes(safe(value));
        if (key.regionMatches(true, 0, "Bearer ", 0, 7)) {
            key = key.substring(7).trim();
        }
        while (
            key.endsWith(",")
                || key.endsWith("，")
                || key.endsWith(";")
                || key.endsWith("；")
                || key.endsWith("。")
        ) {
            key = key.substring(0, key.length() - 1).trim();
        }
        return stripWrappingQuotes(key);
    }

    private static String stripWrappingQuotes(String value) {
        String text = safe(value);
        while (
            text.length() >= 2
                && (
                    (text.startsWith("\"") && text.endsWith("\""))
                        || (text.startsWith("'") && text.endsWith("'"))
                        || (text.startsWith("“") && text.endsWith("”"))
                )
        ) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private static String safe(Object value) {
        return String.valueOf(value == null ? "" : value).trim();
    }

    private static String firstNonBlank(Object first, Object fallback) {
        String firstValue = safe(first);
        return firstValue.isBlank() ? safe(fallback) : firstValue;
    }

    private static double normalizeSpeedRatio(Object value) {
        try {
            double number = value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(safe(value));
            if (Double.isNaN(number) || Double.isInfinite(number)) return 1.0;
            return Math.max(0.5, Math.min(2.0, number));
        } catch (Exception error) {
            return 1.0;
        }
    }

    private record AiDefaults(String baseUrl, String apiKey, String model, String resourceId, String voiceType, double speedRatio) {}

}
