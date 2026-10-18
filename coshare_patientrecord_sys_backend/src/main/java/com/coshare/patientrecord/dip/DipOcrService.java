package com.coshare.patientrecord.dip;

import com.coshare.patientrecord.ai.model.EffectiveAiConfig;
import com.coshare.patientrecord.ai.service.ClinicAiConfigService;
import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * HIS 住院费用截图识别：只提取 DIP 预警需要的最小字段（住院号、姓名、未结算费用、押金余额），
 * 不在本系统保存 HIS 费用明细，也不保存原始截图。
 *
 * 识别结果仅作为预览返回，费用写入仍走 DipWaterlineService.saveFee 的鉴权与校验。
 */
@Service
@Profile("mysql")
public class DipOcrService {

    private static final Logger log = LoggerFactory.getLogger(DipOcrService.class);
    private static final String VISION_MODEL = "glm-5.3-flash";
    private static final int MAX_TOKENS = 2048;
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private final DipWaterlineService waterlineService;
    private final ClinicAiConfigService aiConfigService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public DipOcrService(
        DipWaterlineService waterlineService,
        ClinicAiConfigService aiConfigService,
        ObjectMapper objectMapper
    ) {
        this.waterlineService = waterlineService;
        this.aiConfigService = aiConfigService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> parse(MultipartFile file, SessionUser user) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先选择 HIS 费用截图");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "截图不能超过 8MB");
        }
        String mime = safe(file.getContentType()).toLowerCase(Locale.ROOT);
        if (!mime.startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 PNG / JPG 截图");
        }

        EffectiveAiConfig config = aiConfigService.resolveEffectiveConfig();
        if (!config.enabled() || safe(config.baseUrl()).isBlank() || safe(config.apiKey()).isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI 识别服务未配置，请联系管理员");
        }

        String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(file.getBytes());
        String content = callVision(config, buildPrompt(), dataUrl);
        List<Map<String, Object>> recognized = extractItems(content);
        List<Map<String, Object>> patients = authorizedPatients(user);

        List<Map<String, Object>> items = new ArrayList<>();
        int matched = 0;
        for (Map<String, Object> row : recognized) {
            Map<String, Object> item = matchPatient(row, patients);
            if ("MATCHED".equals(item.get("matchStatus"))) matched++;
            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("recognized", items.size());
        result.put("matched", matched);
        result.put("pending", items.size() - matched);
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> authorizedPatients(SessionUser user) {
        Object patients = waterlineService.board(user).get("patients");
        return patients instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** 优先住院号精确匹配；其次姓名唯一匹配；其余交给医生确认，不在全院范围模糊搜索。 */
    private Map<String, Object> matchPatient(Map<String, Object> ocr, List<Map<String, Object>> patients) {
        String admissionNo = digits(safe((String) ocr.get("admissionNo")));
        String name = safe((String) ocr.get("name")).replace(" ", "");

        Map<String, Object> hit = null;
        String status = "UNMATCHED";
        String reason = "未在当前可见患者中找到匹配";

        if (!admissionNo.isBlank()) {
            for (Map<String, Object> patient : patients) {
                if (admissionNo.equals(digits(safe((String) patient.get("admissionNo"))))) {
                    hit = patient;
                    status = "MATCHED";
                    reason = "住院号匹配";
                    break;
                }
            }
        }
        if (hit == null && !name.isBlank()) {
            List<Map<String, Object>> byName = patients.stream()
                .filter(patient -> name.equals(safe((String) patient.get("patientName")).replace(" ", "")))
                .toList();
            if (byName.size() == 1) {
                hit = byName.get(0);
                status = "MATCHED";
                reason = "姓名唯一匹配";
            } else if (byName.size() > 1) {
                status = "AMBIGUOUS";
                reason = "同名 " + byName.size() + " 人，请手动选择";
            }
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("ocrAdmissionNo", safe((String) ocr.get("admissionNo")));
        item.put("ocrName", safe((String) ocr.get("name")));
        item.put("unsettledFee", ocr.get("unsettledFee"));
        item.put("depositBalance", ocr.get("depositBalance"));
        item.put("recordedAt", safe((String) ocr.get("recordedAt")));
        item.put("confidence", ocr.get("confidence"));
        item.put("matchStatus", status);
        item.put("matchReason", reason);

        if (hit == null) {
            item.put("patientId", "");
            item.put("patientName", "");
            item.put("waterlineRatio", null);
            return item;
        }

        item.put("patientId", hit.get("patientId"));
        item.put("patientName", hit.get("patientName"));
        item.put("diagnosis", hit.get("diagnosis"));
        item.put("groupName", hit.get("groupName"));
        item.put("previousFee", hit.get("unsettledFee"));

        Object estimated = hit.get("estimatedFee");
        BigDecimal fee = (BigDecimal) ocr.get("unsettledFee");
        if (estimated instanceof BigDecimal estimatedFee && estimatedFee.signum() > 0 && fee != null) {
            item.put("waterlineRatio", fee.multiply(BigDecimal.valueOf(100)).divide(estimatedFee, 2, RoundingMode.HALF_UP));
        } else {
            item.put("waterlineRatio", null);
        }
        return item;
    }

    private List<Map<String, Object>> extractItems(String content) {
        int start = safe(content).indexOf('{');
        int end = safe(content).lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "识别结果无法解析，请重试或手动录入");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(safe(content).substring(start, end + 1));
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "识别结果无法解析，请重试或手动录入");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode node : root.path("items")) {
            BigDecimal fee = money(node.path("unsettledFee").asText(""));
            if (fee == null) continue; // 没有金额的行对预警无意义
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("admissionNo", node.path("admissionNo").asText(""));
            row.put("name", node.path("name").asText(""));
            row.put("unsettledFee", fee);
            row.put("depositBalance", money(node.path("depositBalance").asText("")));
            row.put("recordedAt", node.path("recordedAt").asText(""));
            row.put("confidence", node.path("confidence").asDouble(0));
            rows.add(row);
        }
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "截图中未识别到可用费用，请确认截取范围或手动录入");
        }
        return rows;
    }

    private static BigDecimal money(String raw) {
        String value = safe(raw).replace("¥", "").replace(",", "").replace(" ", "").replace("元", "");
        if (value.isBlank()) return null;
        String negative = value.startsWith("-") ? "-" : "";
        String body = negative.isEmpty() ? value : value.substring(1);
        if (!body.matches("\\d+(\\.\\d+)?")) return null;
        try {
            return new BigDecimal(negative + body).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String digits(String raw) {
        return raw == null ? "" : raw.replaceAll("[^0-9]", "");
    }

    private String callVision(EffectiveAiConfig config, String prompt, String dataUrl) {
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", Map.of("url", dataUrl));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(textPart, imagePart));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", VISION_MODEL);
        payload.put("messages", List.of(message));
        payload.put("temperature", 0);
        payload.put("max_tokens", MAX_TOKENS);

        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "识别请求构造失败");
        }

        String endpoint = config.baseUrl().replaceAll("/+$", "") + "/chat/completions";
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.warn("DIP OCR upstream returned HTTP {}: attempt={}", response.statusCode(), attempt);
                    if (attempt == 2) throw upstreamError(response.statusCode());
                    continue;
                }
                JsonNode root = objectMapper.readTree(response.body());
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                if (!content.isBlank()) return content;
                log.warn("DIP OCR upstream returned empty content: attempt={}", attempt);
            } catch (IOException error) {
                log.warn("DIP OCR upstream call failed: attempt={}, error={}", attempt, error.getMessage());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "识别被中断，请重试");
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "识别未返回内容，请重试或手动录入");
    }

    private static ResponseStatusException upstreamError(int status) {
        String message = switch (status) {
            case 400 -> "识别模型拒绝了该图片请求，请换用支持的视觉模型后重试";
            case 401, 403 -> "识别服务鉴权失败，请联系管理员检查 AI 配置";
            case 429 -> "识别请求过于频繁，请稍等几秒后重试";
            default -> "识别服务暂时不可用（HTTP " + status + "），请稍后重试或手动录入";
        };
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String buildPrompt() {
        return """
            你是医院 HIS 住院患者费用列表的录入助手。请从截图中逐行提取患者费用信息。
            规则：
            1. 只提取下面四个字段，其他内容全部忽略（不要输出药品、检查、收费项目、科室、性别、年龄等）。
            2. 住院号保持图片原样数字；看不清就不要猜，留空字符串。
            3. 未结算费用只取数字，不要带货币符号或千分位。
            4. 押金余额可为负数；图片没有该列则留空字符串。
            5. 每行只输出一条，不要合并不同患者。
            6. 识别不到未结算费用的行不要输出。
            7. confidence 为 0 到 1 的小数，表示这一行整体识别的把握。
            仅输出一个 JSON 对象，不要输出任何其他文字：
            {"items":[{"admissionNo":"0020224991","name":"黄金发","unsettledFee":"4350.65","depositBalance":"","recordedAt":"","confidence":0.92}]}
            """;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
