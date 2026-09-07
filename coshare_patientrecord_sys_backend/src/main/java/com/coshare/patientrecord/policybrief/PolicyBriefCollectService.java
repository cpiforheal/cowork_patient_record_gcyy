package com.coshare.patientrecord.policybrief;

import com.coshare.patientrecord.ai.model.EffectiveAiConfig;
import com.coshare.patientrecord.ai.service.AiCallGuard;
import com.coshare.patientrecord.ai.service.ClinicAiConfigService;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 医政早报采集服务：每天早上定时抓取医疗政策 / 医保DIP / 肛肠学术资讯列表页，
 * 逐条抓正文后用系统自带 AI 通道（glm-5.3-flash）生成一句话摘要，落库供管理端「医政早报」页展示。
 *
 * <p>列表页解析为通用启发式（同域链接 + 标题长度 + 日期正则），单源失败只记日志；AI 摘要失败
 * 的条目置 FAILED（保留标题链接可读），不阻塞整体。
 */
@Service
@Profile("mysql")
public class PolicyBriefCollectService {

    private static final Logger LOG = LoggerFactory.getLogger(PolicyBriefCollectService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String AI_MODEL = "glm-5.3-flash";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final int DAILY_SUMMARY_CAP = 30;
    private static final int ARTICLE_TEXT_LIMIT = 4000;

    private final JdbcTemplate jdbcTemplate;
    private final ClinicAiConfigService aiConfigService;
    private final AiCallGuard aiCallGuard;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService collectExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "policy-brief-collector");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 最近一次采集运行状态（内存态，重启清零即可） */
    private volatile CollectRun lastRun = CollectRun.idle();

    public PolicyBriefCollectService(
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

    public record CollectRun(
        boolean running,
        String briefDate,
        int fetched,
        int fresh,
        int summarized,
        int failed,
        String message,
        String startedAt,
        String finishedAt
    ) {

        static CollectRun idle() {
            return new CollectRun(false, "", 0, 0, 0, 0, "尚未运行", "", "");
        }
    }

    // ---------- 触发入口 ----------

    /** 每天 7:30 自动采集（cron 可经 clinic.policy-brief.cron 覆盖）。 */
    @Scheduled(cron = "${clinic.policy-brief.cron:0 30 7 * * *}", zone = "${clinic.policy-brief.zone:Asia/Shanghai}")
    public void runScheduledCollect() {
        try {
            triggerCollect();
        } catch (RuntimeException error) {
            LOG.error("[policy-brief] 定时采集失败: {}", error.getMessage(), error);
        }
    }

    /** 手动触发（管理端「立即采集」）。已在运行时返回进行中提示，不重复排队。 */
    public synchronized Map<String, Object> triggerCollect() {
        if (running.get()) {
            return Map.of("started", false, "message", "采集任务正在进行中，请稍后刷新查看");
        }
        running.set(true);
        String now = LocalDateTime.now().format(TIME_FORMATTER);
        lastRun = new CollectRun(true, LocalDateTime.now().format(DATE_FORMATTER), 0, 0, 0, 0, "采集中…", now, "");
        collectExecutor.submit(() -> {
            try {
                executeCollect();
            } catch (RuntimeException error) {
                LOG.error("[policy-brief] 采集执行异常: {}", error.getMessage(), error);
                lastRun = new CollectRun(
                    false,
                    lastRun.briefDate(),
                    lastRun.fetched(),
                    lastRun.fresh(),
                    lastRun.summarized(),
                    lastRun.failed(),
                    "采集异常：" + error.getMessage(),
                    lastRun.startedAt(),
                    LocalDateTime.now().format(TIME_FORMATTER)
                );
            } finally {
                running.set(false);
            }
        });
        return Map.of("started", true, "message", "采集任务已启动，约 1-3 分钟后完成");
    }

    public CollectRun lastRun() {
        return lastRun;
    }

    // ---------- 采集主流程 ----------

    private void executeCollect() {
        String briefDate = LocalDateTime.now().format(DATE_FORMATTER);
        int fetched = 0;
        int fresh = 0;
        Set<String> seenHashes = new HashSet<>();
        for (PolicyBriefSources.SourceSpec source : PolicyBriefSources.all()) {
            List<ExtractedItem> items;
            try {
                items = extractListItems(source);
            } catch (IOException | RuntimeException error) {
                LOG.warn("[policy-brief] 源抓取失败 {}: {}", source.name(), error.getMessage());
                continue;
            }
            fetched += items.size();
            for (ExtractedItem item : items) {
                String hash = sha256(item.url() + "|" + item.title());
                if (!seenHashes.add(hash)) continue;
                if (existsByHash(hash)) continue;
                insertItem(briefDate, source, item, hash);
                fresh += 1;
            }
        }
        int[] summarized = {0};
        int[] failed = {0};
        summarizePending(briefDate, summarized, failed);
        lastRun = new CollectRun(
            false,
            briefDate,
            fetched,
            fresh,
            summarized[0],
            failed[0],
            "完成：抓取 " + fetched + " 条，新增 " + fresh + " 条，生成摘要 " + summarized[0] + " 条"
                + (failed[0] > 0 ? "，摘要失败 " + failed[0] + " 条" : ""),
            lastRun.startedAt(),
            LocalDateTime.now().format(TIME_FORMATTER)
        );
        LOG.info("[policy-brief] {} 采集完成: fetched={} fresh={} summarized={} failed={}", briefDate, fetched, fresh, summarized[0], failed[0]);
    }

    // ---------- 列表页启发式解析 ----------

    private record ExtractedItem(String title, String url, String publishedAt) {}

    private List<ExtractedItem> extractListItems(PolicyBriefSources.SourceSpec source) throws IOException {
        org.jsoup.Connection.Response response = Jsoup.connect(source.listUrl())
            .userAgent(USER_AGENT)
            .timeout(10_000)
            .followRedirects(true)
            .execute();
        String body = response.body();
        // RSS 源（如 Bing 搜索 RSS）：XML 解析 item/title/link/pubDate
        if (body.contains("<rss") || body.contains("<?xml")) {
            return extractRssItems(body, source);
        }
        Document document = response.parse();
        List<ExtractedItem> items = new ArrayList<>();
        Set<String> perSourceUrls = new HashSet<>();
        for (Element anchor : document.select("a[href]")) {
            if (items.size() >= source.maxItems()) break;
            String url = anchor.absUrl("href");
            String title = String.valueOf(anchor.attr("title")).isBlank() ? anchor.text() : anchor.attr("title");
            title = normalizeTitle(title);
            if (url.isBlank() || !url.startsWith("http") || title.length() < 10) continue;
            if (url.equals(source.listUrl()) || !perSourceUrls.add(url)) continue;
            if (title.matches("^(更多|详情|首页|上一页|下一页|末页|返回).*")) continue;
            if (isNoise(source, title, url)) continue;
            items.add(new ExtractedItem(title, url, extractDateNear(anchor)));
        }
        return items;
    }

    /** RSS/Atom 简版解析：取 item 的 title/link/pubDate，套用与直连源相同的关键词与去重规则。 */
    private List<ExtractedItem> extractRssItems(String xml, PolicyBriefSources.SourceSpec source) {
        Document document = Jsoup.parse(xml, source.listUrl(), org.jsoup.parser.Parser.xmlParser());
        List<ExtractedItem> items = new ArrayList<>();
        Set<String> perSourceUrls = new HashSet<>();
        for (Element item : document.select("item")) {
            if (items.size() >= source.maxItems()) break;
            String title = normalizeTitle(item.selectFirst("title") != null ? item.selectFirst("title").text() : "");
            String url = item.selectFirst("link") != null ? item.selectFirst("link").text().trim() : "";
            String pubDate = item.selectFirst("pubDate") != null ? item.selectFirst("pubDate").text().trim() : "";
            if (url.isBlank() || !url.startsWith("http") || title.length() < 10) continue;
            if (!perSourceUrls.add(url)) continue;
            if (isNoise(source, title, url)) continue;
            items.add(new ExtractedItem(title, url, normalizeRssDate(pubDate)));
        }
        return items;
    }

    /** 通用噪音过滤：UGC 域名黑名单 + 标题噪音词 + 必含词 + 任一关键词。 */
    private boolean isNoise(PolicyBriefSources.SourceSpec source, String title, String url) {
        String urlLower = url.toLowerCase();
        for (String domain : PolicyBriefSources.DOMAIN_BLACKLIST) {
            if (urlLower.contains(domain)) return true;
        }
        for (String noise : PolicyBriefSources.TITLE_NOISE_WORDS) {
            if (title.contains(noise)) return true;
        }
        for (String required : source.requireAll()) {
            if (!title.contains(required)) return true;
        }
        if (!source.keywords().isEmpty() && source.keywords().stream().noneMatch(title::contains)) return true;
        return false;
    }

    /** RFC-1123 日期（Bing RSS pubDate）→ yyyy-MM-dd HH:mm；解析失败原样截断保留。 */
    private String normalizeRssDate(String raw) {
        try {
            return java.time.LocalDateTime.ofInstant(
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(raw, java.time.Instant::from),
                java.time.ZoneId.systemDefault()
            ).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (RuntimeException error) {
            return trim(raw, 30);
        }
    }

    private String normalizeTitle(String raw) {
        return String.valueOf(raw == null ? "" : raw).replaceAll("\\s+", " ").trim();
    }

    /** 条目日期：优先锚点自身与父块文本中的 20xx 日期串，归一为 yyyy-MM-dd；解析不出留空（不阻塞入库）。 */
    private String extractDateNear(Element anchor) {
        for (Element scope : List.of(anchor, anchor.parent(), anchor.parent() != null ? anchor.parent().parent() : null)) {
            if (scope == null) continue;
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(20\\d{2})[-/年.]\\s?(\\d{1,2})[-/月.]\\s?(\\d{1,2})日?")
                .matcher(scope.text());
            if (matcher.find()) {
                return String.format("%s-%02d-%02d", matcher.group(1), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
            }
        }
        return "";
    }

    // ---------- AI 摘要 ----------

    private void summarizePending(String briefDate, int[] summarized, int[] failed) {
        EffectiveAiConfig aiConfig;
        try {
            aiConfig = aiConfigService.resolveEffectiveConfig();
        } catch (RuntimeException error) {
            LOG.warn("[policy-brief] AI 配置不可用，本次仅完成采集: {}", error.getMessage());
            return;
        }
        String apiKey = String.valueOf(aiConfig.apiKey() == null ? "" : aiConfig.apiKey()).trim();
        if (!aiConfig.enabled() || apiKey.isBlank() || aiConfig.baseUrl().isBlank()) {
            LOG.warn("[policy-brief] AI 未配置/未启用，本次仅完成采集");
            return;
        }
        String endpoint = normalizeChatCompletionsUrl(aiConfig.baseUrl());
        List<Map<String, Object>> pending = jdbcTemplate.queryForList(
            "SELECT id, title, url, source_name FROM policy_brief_item WHERE brief_date = ? AND status = 'PENDING' ORDER BY id LIMIT "
                + DAILY_SUMMARY_CAP,
            briefDate
        );
        for (Map<String, Object> row : pending) {
            String id = String.valueOf(row.get("id"));
            String title = String.valueOf(row.getOrDefault("title", ""));
            String url = String.valueOf(row.getOrDefault("url", ""));
            try {
                String articleText = fetchArticleText(url, title);
                String[] summaryResult = requestAiSummary(endpoint, apiKey, title, articleText);
                String aiCategory = summaryResult[1];
                boolean categoryValid = List.of(
                    PolicyBriefSources.CATEGORY_POLICY,
                    PolicyBriefSources.CATEGORY_DIP,
                    PolicyBriefSources.CATEGORY_ANORECTAL,
                    PolicyBriefSources.CATEGORY_GENERAL
                ).contains(aiCategory);
                if (categoryValid) {
                    jdbcTemplate.update(
                        "UPDATE policy_brief_item SET ai_summary = ?, ai_model = ?, category = ?, status = 'SUMMARIZED' WHERE id = ?",
                        summaryResult[0],
                        AI_MODEL,
                        aiCategory,
                        id
                    );
                } else {
                    jdbcTemplate.update(
                        "UPDATE policy_brief_item SET ai_summary = ?, ai_model = ?, status = 'SUMMARIZED' WHERE id = ?",
                        summaryResult[0],
                        AI_MODEL,
                        id
                    );
                }
                summarized[0] += 1;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                LOG.warn("[policy-brief] 摘要被中断 id={}", id);
                jdbcTemplate.update("UPDATE policy_brief_item SET status = 'FAILED' WHERE id = ?", id);
                failed[0] += 1;
            } catch (RuntimeException | IOException error) {
                LOG.warn("[policy-brief] 摘要失败 id={}: {}", id, error.getMessage());
                jdbcTemplate.update("UPDATE policy_brief_item SET status = 'FAILED' WHERE id = ?", id);
                failed[0] += 1;
            }
        }
    }

    private String fetchArticleText(String url, String fallbackTitle) {
        try {
            Document document = Jsoup.connect(url).userAgent(USER_AGENT).timeout(10_000).followRedirects(true).get();
            String text = document.body() == null ? "" : document.body().text();
            text = text.replaceAll("\\s+", " ").trim();
            if (text.length() > ARTICLE_TEXT_LIMIT) text = text.substring(0, ARTICLE_TEXT_LIMIT);
            return text.isBlank() ? "(正文抓取失败，仅基于标题判断)" : text;
        } catch (IOException | RuntimeException error) {
            // 正文被反爬拦截（如 403）时退回标题摘要，不让单条正文失败拖垮整条资讯
            return "(正文抓取失败，仅基于标题判断) 标题：" + fallbackTitle;
        }
    }

    private String[] requestAiSummary(String endpoint, String apiKey, String title, String articleText)
        throws IOException, InterruptedException {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", AI_MODEL);
        payload.put("temperature", 0.3);
        payload.put("max_tokens", 512);
        payload.put("stream", false);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", summarySystemPrompt());
        messages.addObject()
            .put(
                "role",
                "user"
            )
            .put("content", "标题：" + title + "\n\n正文：\n" + articleText);

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(60))
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
        return parseSummaryResult(content);
    }

    private String summarySystemPrompt() {
        return """
            你是医院信息科的医政资讯编辑，为院内管理者与医务人员整理每日医政资讯。
            基于给定的资讯标题与正文，输出：
            1. summary：1-2 句中文摘要（合计不超过 100 字），概括该文件/文章的核心内容与适用对象；不得编造正文没有的信息。
            2. category：从 POLICY（政策法规）、DIP（医保支付/DRG/DIP/集采）、ANORECTAL（肛肠及普外学术）、GENERAL（行业动态）中选择最贴切的一个。
            只返回 JSON 对象，不要 Markdown，不要代码块：{"summary":"...","category":"POLICY"}
            """;
    }

    private String[] parseSummaryResult(String content) throws IOException {
        String normalized = content.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        try {
            JsonNode parsed = objectMapper.readTree(normalized);
            String summary = parsed.path("summary").asText("").trim();
            String category = parsed.path("category").asText("").trim();
            if (summary.isBlank()) throw new IOException("摘要为空");
            return new String[] { summary, category };
        } catch (IOException error) {
            // 模型未按 JSON 返回时退化为整段文本当摘要，分类保持采集时给定值
            if (!normalized.isBlank()) return new String[] { normalized, "" };
            throw error;
        }
    }

    // ---------- 查询（供 Controller） ----------

    public Map<String, Object> latest() {
        List<Map<String, Object>> dateRows = jdbcTemplate.queryForList(
            "SELECT brief_date, COUNT(*) AS total FROM policy_brief_item GROUP BY brief_date ORDER BY brief_date DESC LIMIT 1"
        );
        if (dateRows.isEmpty()) {
            return Map.of("briefDate", "", "total", 0, "items", List.of(), "lastRun", lastRun);
        }
        String briefDate = String.valueOf(dateRows.get(0).get("brief_date"));
        return items(briefDate, "");
    }

    public Map<String, Object> items(String briefDate, String category) {
        String safeDate = String.valueOf(briefDate == null ? "" : briefDate).trim();
        String safeCategory = String.valueOf(category == null ? "" : category).trim();
        if (!safeDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期格式应为 yyyy-MM-dd");
        }
        StringBuilder sql = new StringBuilder(
            "SELECT id, brief_date, source_name, category, title, url, published_at, ai_summary, ai_model, status, created_at "
                + "FROM policy_brief_item WHERE brief_date = ?"
        );
        List<Object> args = new ArrayList<>();
        args.add(safeDate);
        if (!safeCategory.isBlank()) {
            sql.append(" AND category = ?");
            args.add(safeCategory);
        }
        // 发布时间 desc 优先（published_at 已归一为定长格式，字符串序即时间序），无发布时间的按入库时间沉底
        sql.append(" ORDER BY published_at DESC, created_at DESC, id DESC LIMIT 100");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("briefDate", row.get("brief_date"));
            item.put("sourceName", row.get("source_name"));
            item.put("category", row.get("category"));
            item.put("title", row.get("title"));
            item.put("url", row.get("url"));
            item.put("publishedAt", row.get("published_at"));
            item.put("aiSummary", row.get("ai_summary"));
            item.put("aiModel", row.get("ai_model"));
            item.put("status", row.get("status"));
            item.put("createdAt", row.get("created_at"));
            rows.add(item);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("briefDate", safeDate);
        result.put("total", rows.size());
        result.put("items", rows);
        result.put("lastRun", lastRun);
        return result;
    }

    // ---------- 基础工具 ----------

    private void insertItem(String briefDate, PolicyBriefSources.SourceSpec source, ExtractedItem item, String hash) {
        jdbcTemplate.update(
            "INSERT IGNORE INTO policy_brief_item (id, brief_date, source_name, category, title, url, published_at, ai_summary, ai_model, status, content_hash, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, 'PENDING', ?, ?)",
            UUID.randomUUID().toString(),
            briefDate,
            source.name(),
            source.category(),
            trim(item.title(), 500),
            trim(item.url(), 890),
            trim(item.publishedAt(), 30),
            hash,
            LocalDateTime.now().format(TIME_FORMATTER)
        );
    }

    private boolean existsByHash(String hash) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM policy_brief_item WHERE content_hash = ?",
            Integer.class,
            hash
        );
        return count != null && count > 0;
    }

    private String trim(String value, int maxLength) {
        String text = String.valueOf(value == null ? "" : value).trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception error) {
            // SHA-256 在 JVM 内必然存在；此处仅为编译器收窄受检异常
            throw new IllegalStateException(error);
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

    private static String normalizeChatCompletionsUrl(String rawUrl) {
        String url = String.valueOf(rawUrl == null ? "" : rawUrl).trim();
        if (url.isBlank()) return url;
        url = url.replaceAll("/+$", "");
        if (url.endsWith("/chat/completions")) return url;
        if (url.endsWith("/v1")) return url + "/chat/completions";
        return url + "/v1/chat/completions";
    }
}
