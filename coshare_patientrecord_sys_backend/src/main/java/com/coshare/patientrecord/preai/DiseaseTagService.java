package com.coshare.patientrecord.preai;

import com.coshare.patientrecord.ai.model.EffectiveAiConfig;
import com.coshare.patientrecord.ai.service.AiCallGuard;
import com.coshare.patientrecord.ai.service.ClinicAiConfigService;
import com.coshare.patientrecord.auth.dto.SessionUser;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * AI 病种归类服务：对没有套用病种模板的患者，用系统自带 AI 通道（glm-5.3-flash）批量阅读登记主诉，
 * 归类到预置病种（混合痔/内痔/肛裂/肛瘘/肛周脓肿）并按患者缓存标签。
 *
 * <p>设计要点：批量打包（每次 20 位患者）+ 单轮上限 200 人；标签按患者缓存（夜间增量 + 管理员手动触发），
 * 统计读取永远查标签表，不在看板加载时实时调 AI。
 */
@Service
@Profile("mysql")
public class DiseaseTagService {

    private static final Logger LOG = LoggerFactory.getLogger(DiseaseTagService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String AI_MODEL = "glm-5.3-flash";
    private static final int BATCH_SIZE = 20;
    private static final int RUN_LIMIT = 200;
    private static final Set<String> PRESET_DISEASES = Set.of("混合痔", "内痔", "肛裂", "肛瘘", "肛周脓肿");
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private final JdbcTemplate jdbcTemplate;
    private final ClinicAiConfigService aiConfigService;
    private final AiCallGuard aiCallGuard;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService tagExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "disease-tag-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String lastRunMessage = "尚未运行";

    public DiseaseTagService(
        JdbcTemplate jdbcTemplate,
        ClinicAiConfigService aiConfigService,
        AiCallGuard aiCallGuard,
        ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.aiConfigService = aiConfigService;
        this.aiCallGuard = aiCallGuard;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 夜间增量归类：只处理尚无标签的患者主档案。 */
    @Scheduled(cron = "${clinic.disease-tag.cron:0 40 3 * * *}", zone = "${clinic.disease-tag.zone:Asia/Shanghai}")
    public void runScheduled() {
        try {
            triggerRun(null);
        } catch (RuntimeException error) {
            LOG.error("[disease-tag] 定时归类失败: {}", error.getMessage(), error);
        }
    }

    /** 手动触发（仅管理员）；已在运行时返回进行中提示。 */
    public synchronized Map<String, Object> triggerRun(SessionUser user) {
        if (running.get()) {
            return Map.of("started", false, "message", "AI 归类任务正在进行中，请稍后刷新查看");
        }
        running.set(true);
        String operator = user == null ? "system-schedule" : user.name();
        lastRunMessage = "归类中…";
        tagExecutor.submit(() -> {
            try {
                executeBatch(operator);
            } catch (RuntimeException error) {
                LOG.error("[disease-tag] 归类执行异常: {}", error.getMessage(), error);
                lastRunMessage = "归类异常：" + error.getMessage();
            } finally {
                running.set(false);
            }
        });
        return Map.of("started", true, "message", "AI 归类任务已启动，约 1-2 分钟后完成");
    }

    public String lastRunMessage() {
        return lastRunMessage;
    }

    private void executeBatch(String operator) {
        EffectiveAiConfig aiConfig = aiConfigService.resolveEffectiveConfig();
        String apiKey = String.valueOf(aiConfig.apiKey() == null ? "" : aiConfig.apiKey()).trim();
        if (!aiConfig.enabled() || apiKey.isBlank() || aiConfig.baseUrl().isBlank()) {
            lastRunMessage = "AI 未配置，无法归类";
            LOG.warn("[disease-tag] AI 未配置/未启用，跳过归类");
            return;
        }
        String endpoint = normalizeChatCompletionsUrl(aiConfig.baseUrl());
        List<Map<String, Object>> pending = jdbcTemplate.queryForList(
            "SELECT c.id, c.patient_json FROM pre_ai_patient_cases c "
                + "LEFT JOIN pre_ai_disease_tags t ON t.patient_case_id = c.id "
                + "WHERE t.patient_case_id IS NULL ORDER BY c.updated_at DESC LIMIT " + RUN_LIMIT
        );
        if (pending.isEmpty()) {
            lastRunMessage = "没有待归类的患者";
            return;
        }
        int tagged = 0;
        int untagged = 0;
        for (int start = 0; start < pending.size(); start += BATCH_SIZE) {
            List<Map<String, Object>> batch = pending.subList(start, Math.min(start + BATCH_SIZE, pending.size()));
            StringBuilder listing = new StringBuilder();
            List<String> batchIds = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                Map<String, Object> row = batch.get(i);
                String id = String.valueOf(row.get("id"));
                String complaint = complaintText(String.valueOf(row.getOrDefault("patient_json", "")));
                batchIds.add(id);
                listing.append(i + 1).append(". ").append(complaint.isBlank() ? "（无主诉记录）" : trim(complaint, 200)).append("\n");
            }
            try {
                Map<Integer, List<String>> result = requestClassification(endpoint, apiKey, listing.toString());
                for (int i = 0; i < batchIds.size(); i++) {
                    List<String> diseases = result.getOrDefault(i + 1, List.of());
                    upsertTags(batchIds.get(i), diseases);
                    if (diseases.isEmpty()) untagged += 1;
                    else tagged += 1;
                }
            } catch (IOException | InterruptedException | RuntimeException error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.warn("[disease-tag] 批次归类失败（{} 位患者跳过）: {}", batchIds.size(), error.getMessage());
            }
        }
        lastRunMessage = "完成：待归类 " + pending.size() + " 人，成功归类 " + tagged + " 人，无病种线索 " + untagged + " 人";
        LOG.info("[disease-tag] 归类完成: pending={} tagged={} untagged={} operator={}", pending.size(), tagged, untagged, operator);
    }

    /** 从 patient_json 提取归类输入文本：登记主诉优先，登记症状兜底。 */
    private String complaintText(String patientJsonRaw) {
        try {
            JsonNode patient = objectMapper.readTree(patientJsonRaw);
            String complaint = patient.path("registrationChiefComplaint").asText("").trim();
            if (complaint.isBlank()) complaint = patient.path("registrationSymptoms").asText("").trim();
            return complaint;
        } catch (IOException error) {
            return "";
        }
    }

    private Map<Integer, List<String>> requestClassification(String endpoint, String apiKey, String listing)
        throws IOException, InterruptedException {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", AI_MODEL);
        payload.put("temperature", 0.2);
        payload.put("max_tokens", 1200);
        payload.put("stream", false);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject()
            .put(
                "role",
                "system"
            )
            .put(
                "content",
                """
                你是肛肠专科病案归类助手。把每位患者的主诉归入预置病种（可多选）：混合痔、内痔、肛裂、肛瘘、肛周脓肿。
                判定依据示例：便血/肛门疼痛伴便血多提示肛裂或痔；肿物脱出/肛周肿块多提示痔；肛周潮湿/溢脓/分泌物伴反复发作多提示肛瘘或肛周脓肿；
                "皮赘/擦不净"提示痔或肛裂；远期复查、体检等无病种线索的返回空数组。
                只返回 JSON 对象，不要 Markdown，不要解释：
                {"items":[{"id":1,"diseases":["混合痔"]}]}
                """
            );
        messages.addObject().put("role", "user").put("content", "患者主诉列表：\n" + listing);

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(90))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = aiCallGuard.execute(
            () -> httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("上游状态 " + response.statusCode());
        }
        String content = extractContent(response.body());
        String normalized = content.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        Map<Integer, List<String>> result = new LinkedHashMap<>();
        JsonNode items = objectMapper.readTree(normalized).path("items");
        if (items.isArray()) {
            for (JsonNode item : items) {
                int id = item.path("id").asInt(0);
                if (id <= 0) continue;
                List<String> diseases = new ArrayList<>();
                JsonNode diseasesNode = item.path("diseases");
                if (diseasesNode.isArray()) {
                    for (JsonNode disease : diseasesNode) {
                        String name = disease.asText("").trim();
                        if (PRESET_DISEASES.contains(name)) diseases.add(name);
                    }
                }
                result.put(id, diseases);
            }
        }
        return result;
    }

    private void upsertTags(String patientCaseId, List<String> diseases) {
        try {
            ArrayNode tags = objectMapper.createArrayNode();
            diseases.forEach(tags::add);
            jdbcTemplate.update(
                "INSERT INTO pre_ai_disease_tags (patient_case_id, tags_json, ai_model, tagged_at) VALUES (?, CAST(? AS JSON), ?, ?) "
                    + "ON DUPLICATE KEY UPDATE tags_json = VALUES(tags_json), ai_model = VALUES(ai_model), tagged_at = VALUES(tagged_at)",
                patientCaseId,
                objectMapper.writeValueAsString(tags),
                AI_MODEL,
                LocalDateTime.now().format(TIME_FORMATTER)
            );
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "标签序列化失败");
        }
    }

    private String extractContent(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(String.valueOf(responseBody == null ? "" : responseBody));
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText("").isBlank()) {
            throw new IOException("AI 返回为空");
        }
        return content.asText();
    }

    private static String trim(String value, int maxLength) {
        String text = String.valueOf(value == null ? "" : value).trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static String normalizeChatCompletionsUrl(String rawUrl) {
        String url = String.valueOf(rawUrl == null ? "" : rawUrl).trim();
        if (url.isBlank()) return url;
        url = url.replaceAll("/+$", "");
        if (url.endsWith("/chat/completions")) return url;
        if (url.endsWith("/v1")) return url + "/chat/completions";
        return url + "/v1/chat/completions";
    }
}
