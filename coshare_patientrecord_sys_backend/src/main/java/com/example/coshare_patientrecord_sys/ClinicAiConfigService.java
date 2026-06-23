package com.example.coshare_patientrecord_sys;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@Profile("mysql")
public class ClinicAiConfigService {

    private static final String CONFIG_ID = "default";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String defaultBaseUrl;
    private final String defaultApiKey;
    private final String defaultModel;
    private final byte[] encryptionKey;

    public ClinicAiConfigService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        @Value("${clinic.ai.base-url:}") String defaultBaseUrl,
        @Value("${clinic.ai.api-key:}") String defaultApiKey,
        @Value("${clinic.ai.model:gpt-5.5}") String defaultModel,
        @Value("${clinic.ai.config-secret:}") String configSecret
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.defaultBaseUrl = safe(defaultBaseUrl);
        this.defaultApiKey = safe(defaultApiKey);
        this.defaultModel = safe(defaultModel).isBlank() ? "gpt-5.5" : safe(defaultModel);
        this.encryptionKey = deriveEncryptionKey(configSecret);
    }

    @PostConstruct
    public void initializeSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_ai_config (
              config_id VARCHAR(32) PRIMARY KEY,
              base_url VARCHAR(1024),
              api_key_cipher TEXT,
              model VARCHAR(128),
              enabled BOOLEAN NOT NULL DEFAULT TRUE,
              updated_at VARCHAR(32),
              updated_by VARCHAR(100)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
    }

    public ObjectNode status() {
        StoredAiConfig stored = readStoredConfig();
        EffectiveAiConfig effective = resolveEffectiveConfig();
        ObjectNode status = objectMapper.createObjectNode();
        status.put("baseUrl", stored == null ? defaultBaseUrl : stored.baseUrl());
        status.put("model", stored == null ? defaultModel : stored.model());
        status.put("enabled", stored == null || stored.enabled());
        status.put("apiKeyConfigured", !effective.apiKey().isBlank());
        status.put("apiKeyMasked", maskKey(effective.apiKey()));
        status.put("usingRuntimeConfig", stored != null);
        status.put("updatedAt", stored == null ? "" : stored.updatedAt());
        status.put("updatedBy", stored == null ? "" : stored.updatedBy());
        return status;
    }

    public ObjectNode updateConfig(Map<String, Object> payload, AuthSessionService.SessionUser user) {
        StoredAiConfig current = readStoredConfig();
        String baseUrl = safe(payload.get("baseUrl"));
        String model = safe(payload.get("model")).isBlank() ? "gpt-5.5" : safe(payload.get("model"));
        boolean enabled = !payload.containsKey("enabled") || Boolean.parseBoolean(String.valueOf(payload.get("enabled")));
        String apiKey = safe(payload.get("apiKey"));
        boolean keepExisting = payload.containsKey("keepExistingApiKey")
            && Boolean.parseBoolean(String.valueOf(payload.get("keepExistingApiKey")));

        if (baseUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写 AI base_url");
        }
        if (model.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写 AI 模型名称");
        }

        String cipher = current == null ? "" : current.apiKeyCipher();
        if (!apiKey.isBlank()) {
            cipher = encrypt(apiKey);
        } else if (!keepExisting || cipher.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写 AI API Key，或选择保留现有 Key");
        }

        String updatedAt = LocalDateTime.now().format(TIME_FORMATTER);
        String updatedBy = user == null ? "" : user.name();
        jdbcTemplate.update("""
            INSERT INTO clinic_ai_config (config_id, base_url, api_key_cipher, model, enabled, updated_at, updated_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              base_url = VALUES(base_url),
              api_key_cipher = VALUES(api_key_cipher),
              model = VALUES(model),
              enabled = VALUES(enabled),
              updated_at = VALUES(updated_at),
              updated_by = VALUES(updated_by)
            """, CONFIG_ID, baseUrl, cipher, model, enabled, updatedAt, updatedBy);
        return status();
    }

    public EffectiveAiConfig resolveEffectiveConfig() {
        StoredAiConfig stored = readStoredConfig();
        if (stored == null) {
            return new EffectiveAiConfig(defaultBaseUrl, defaultApiKey, defaultModel, false, true);
        }
        if (!stored.enabled()) {
            return new EffectiveAiConfig(stored.baseUrl(), "", stored.model(), true, false);
        }
        return new EffectiveAiConfig(stored.baseUrl(), decrypt(stored.apiKeyCipher()), stored.model(), true, true);
    }

    private StoredAiConfig readStoredConfig() {
        List<StoredAiConfig> rows = jdbcTemplate.query(
            "SELECT base_url, api_key_cipher, model, enabled, updated_at, updated_by FROM clinic_ai_config WHERE config_id = ? LIMIT 1",
            (rs, rowNum) -> new StoredAiConfig(
                safe(rs.getString("base_url")),
                safe(rs.getString("api_key_cipher")),
                safe(rs.getString("model")).isBlank() ? "gpt-5.5" : safe(rs.getString("model")),
                rs.getBoolean("enabled"),
                safe(rs.getString("updated_at")),
                safe(rs.getString("updated_by"))
            ),
            CONFIG_ID
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private byte[] deriveEncryptionKey(String configuredSecret) {
        String secret = safe(configuredSecret);
        if (secret.isBlank()) {
            secret = safe(System.getenv("AI_CONFIG_SECRET"));
        }
        if (secret.isBlank()) {
            secret = safe(System.getProperty("user.name")) + "|" + safe(System.getProperty("user.dir")) + "|clinic-ai-config-v1";
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("AI配置密钥初始化失败", error);
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
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI API Key 加密失败");
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
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI API Key 解密失败，请重新保存配置");
        }
    }

    private static String maskKey(String key) {
        String value = safe(key);
        if (value.isBlank()) return "";
        if (value.length() <= 10) return "******";
        return value.substring(0, Math.min(6, value.length())) + "..." + value.substring(Math.max(0, value.length() - 4));
    }

    private static String safe(Object value) {
        return String.valueOf(value == null ? "" : value).trim();
    }

    private record StoredAiConfig(String baseUrl, String apiKeyCipher, String model, boolean enabled, String updatedAt, String updatedBy) {}

    public record EffectiveAiConfig(String baseUrl, String apiKey, String model, boolean runtimeConfig, boolean enabled) {}
}
