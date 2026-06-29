package com.example.coshare_patientrecord_sys;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class ClinicAiDocumentService {

    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_CONTENT_LENGTH = 60_000;
    private static final List<DocTemplate> TEMPLATES = List.of(
        new DocTemplate("general-outline", "通用文稿", "按用户粘贴内容整理成正式院内文稿"),
        new DocTemplate("meeting-minutes", "会议纪要", "整理为会议纪要或会议决议文稿"),
        new DocTemplate("work-plan", "工作计划", "整理为目标、任务和推进计划"),
        new DocTemplate("rectification", "整改方案", "整理为问题、措施和闭环要求")
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ClinicAiConfigService aiConfigService;
    private final HttpClient httpClient;
    private final Path generatedDir;

    public ClinicAiDocumentService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        ClinicAiConfigService aiConfigService,
        @Value("${clinic.generated-ai-document-dir:${clinic.attachment-dir}/../generated-ai-documents}") String generatedDir
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.aiConfigService = aiConfigService;
        this.generatedDir = Path.of(generatedDir).toAbsolutePath().normalize();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @PostConstruct
    public void initializeSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_generated_ai_documents (
              id VARCHAR(128) PRIMARY KEY,
              title VARCHAR(255) NOT NULL,
              doc_type VARCHAR(64) NOT NULL,
              file_name VARCHAR(255) NOT NULL,
              file_path VARCHAR(1000) NOT NULL,
              content_hash VARCHAR(128),
              operator VARCHAR(100),
              operator_role VARCHAR(64),
              generated_at VARCHAR(32),
              raw_json JSON NOT NULL,
              INDEX idx_ai_documents_generated_at (generated_at),
              INDEX idx_ai_documents_operator (operator)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
    }

    public Map<String, Object> templates(AuthSessionService.SessionUser user) {
        requireAllowedRole(user);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode rows = result.putArray("templates");
        for (DocTemplate template : TEMPLATES) {
            ObjectNode row = rows.addObject();
            row.put("id", template.id());
            row.put("name", template.name());
            row.put("description", template.description());
        }
        result.put("defaultTemplateId", TEMPLATES.get(0).id());
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    public Map<String, Object> preview(AiDocumentRequest request, AuthSessionService.SessionUser user) {
        requireAllowedRole(user);
        DraftInput input = validateInput(request);
        ObjectNode result = previewNode(input.title(), input.docType(), List.of("预检通过。点击 AI生成DOCX 后，系统会把粘贴内容交给 AI 整理为最终成稿。"));
        result.put("aiRequired", true);
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    @Transactional
    public Map<String, Object> generate(AiDocumentRequest request, AuthSessionService.SessionUser user) {
        requireAllowedRole(user);
        DraftInput input = validateInput(request);
        ClinicAiConfigService.EffectiveAiConfig config = aiConfigService.resolveEffectiveConfig();
        if (!config.enabled() || safe(config.apiKey()).isBlank() || safe(config.baseUrl()).isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI文稿生成未配置，请先在AI接口配置中填写 base_url、api_key 和模型");
        }

        String generatedText = generateFinalText(input, config);
        List<String> paragraphs = splitParagraphs(generatedText);
        byte[] bytes = renderDocx(input.title(), paragraphs, user, config.model());
        String id = "aidoc-" + UUID.randomUUID();
        String hash = sha256(bytes);
        String fileName = sanitizeFileName(input.title()) + ".docx";
        Path filePath = writeGeneratedFile(id, fileName, bytes);
        String generatedAt = now();

        ObjectNode document = objectMapper.createObjectNode();
        document.put("id", id);
        document.put("title", input.title());
        document.put("docType", input.docType());
        document.put("templateName", templateName(input.docType()));
        document.put("fileName", fileName);
        document.put("filePath", filePath.toString());
        document.put("contentHash", hash);
        document.put("operator", user.name());
        document.put("operatorRole", user.role());
        document.put("generatedAt", generatedAt);
        document.put("model", config.model());
        document.put("downloadUrl", "/clinic-api/ai-document/download?id=" + id);
        document.put("content", String.join("\n", paragraphs));
        document.set("preview", paragraphsToBlocks(paragraphs));

        jdbcTemplate.update("""
            INSERT INTO clinic_generated_ai_documents (
              id, title, doc_type, file_name, file_path, content_hash,
              operator, operator_role, generated_at, raw_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            id,
            input.title(),
            input.docType(),
            fileName,
            filePath.toString(),
            hash,
            user.name(),
            user.role(),
            generatedAt,
            toJson(document)
        );

        ObjectNode result = objectMapper.createObjectNode();
        result.set("document", document);
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    public DownloadFile download(String id, AuthSessionService.SessionUser user) {
        requireAllowedRole(user);
        ObjectNode row = loadDocument(safe(id));
        Path target = Path.of(text(row, "filePath")).toAbsolutePath().normalize();
        if (!target.startsWith(generatedDir) || !Files.isRegularFile(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文稿文件不存在，请重新生成");
        }
        return new DownloadFile(new FileSystemResource(target), text(row, "fileName", "AI文稿.docx"));
    }

    private String generateFinalText(DraftInput input, ClinicAiConfigService.EffectiveAiConfig config) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", safe(config.model()).isBlank() ? "gpt-5.5" : safe(config.model()));
        payload.put("temperature", 0.45);
        payload.put("max_tokens", 4000);
        payload.put("stream", false);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", fixedPrompt());
        messages.addObject().put("role", "user").put("content", userPrompt(input));

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(normalizeChatCompletionsUrl(config.baseUrl())))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + safe(config.apiKey()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, buildUpstreamErrorMessage(response.statusCode(), response.body()));
            }
            String content = extractContent(response.body());
            if (content.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI返回内容为空");
            return stripCodeFence(content);
        } catch (ResponseStatusException error) {
            throw error;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI文稿生成调用失败，请检查 AI 服务地址、模型、Key、额度或网络连通性：" + safe(error.getMessage()));
        }
    }

    private String fixedPrompt() {
        return """
            你是院内办公文稿成稿助手。
            你的任务很简单：像网页端 AI 对话一样，阅读用户粘贴的材料和要求，直接整理出一份最终可下载为 Word 的中文成稿。

            输出规则：
            1. 只输出最终文稿正文，不解释过程，不输出 JSON，不输出代码块，不输出 Markdown 标记。
            2. 允许你自行组织标题、层级、段落、条目和必要的表格化文字，让文稿看起来像一份可以直接交付的正式文件。
            3. 保留用户核心意思，可以润色、归纳、排序、补足承接语和纲领性表达，但不能改变事实方向。
            4. 不编造用户没有提供的事实、日期、金额、人名、科室或结论；确实缺失但文档必须出现的内容写“待补充”。
            5. 文风正式、清楚、稳妥，适合院内汇报、会议纪要、制度文件、工作计划、整改方案或领导讲话提纲。
            6. 不要出现“根据您提供的信息”“以下是整理后的文稿”等对话式开场，直接从文档标题或正文开始。
            """;
    }

    private String userPrompt(DraftInput input) {
        return """
            文档标题：%s
            文档类型：%s

            用户粘贴的材料和要求：
            %s
            """.formatted(input.title(), templateName(input.docType()), input.content());
    }

    private ObjectNode previewNode(String title, String docType, List<String> paragraphs) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("title", title);
        result.put("docType", docType);
        result.put("templateName", templateName(docType));
        result.put("paragraphCount", paragraphs.size());
        result.put("headingCount", 0);
        result.put("listCount", 0);
        result.put("tableCount", 0);
        result.put("content", String.join("\n", paragraphs));
        result.set("blocks", paragraphsToBlocks(paragraphs));
        return result;
    }

    private ArrayNode paragraphsToBlocks(List<String> paragraphs) {
        ArrayNode blocks = objectMapper.createArrayNode();
        for (String paragraph : paragraphs.stream().limit(200).toList()) {
            ObjectNode block = blocks.addObject();
            block.put("type", "paragraph");
            block.put("text", paragraph);
            block.put("level", 0);
            block.putArray("rows");
        }
        return blocks;
    }

    private List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String raw : safe(text).replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = raw.trim();
            if (!line.isBlank()) paragraphs.add(line);
        }
        if (paragraphs.isEmpty()) paragraphs.add(safe(text));
        return paragraphs;
    }

    private byte[] renderDocx(String title, List<String> paragraphs, AuthSessionService.SessionUser user, String model) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            addEntry(zip, "[Content_Types].xml", contentTypesXml());
            addEntry(zip, "_rels/.rels", relsXml());
            addEntry(zip, "docProps/core.xml", coreXml(title, user, model));
            addEntry(zip, "docProps/app.xml", appXml());
            addEntry(zip, "word/_rels/document.xml.rels", documentRelsXml());
            addEntry(zip, "word/styles.xml", stylesXml());
            addEntry(zip, "word/document.xml", documentXml(title, paragraphs));
            zip.finish();
            return output.toByteArray();
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "DOCX文件生成失败：" + error.getMessage(), error);
        }
    }

    private String documentXml(String title, List<String> paragraphs) {
        StringBuilder body = new StringBuilder();
        body.append(paragraph(title, "Title"));
        for (String item : paragraphs) body.append(paragraph(item, "Normal"));
        body.append("""
            <w:sectPr>
              <w:pgSz w:w="11906" w:h="16838"/>
              <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/>
            </w:sectPr>
            """);
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
            """ + body + """
              </w:body>
            </w:document>
            """;
    }

    private String paragraph(String text, String style) {
        return "<w:p><w:pPr><w:pStyle w:val=\"" + style + "\"/></w:pPr><w:r><w:t xml:space=\"preserve\">" +
            xmlEscape(text) + "</w:t></w:r></w:p>";
    }

    private DraftInput validateInput(AiDocumentRequest request) {
        String title = normalizeTitle(request == null ? "" : request.title());
        String docType = normalizeDocType(request == null ? "" : request.docType());
        String content = safe(request == null ? "" : request.content());
        if (content.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先粘贴需要AI整理的文稿内容");
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文稿内容过长，请控制在 " + MAX_CONTENT_LENGTH + " 字以内");
        }
        return new DraftInput(title, docType, content);
    }

    private String extractContent(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText("").isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI服务返回为空");
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
        if (message.isBlank()) message = "请检查 AI 配置、模型名称、API Key、额度或上游服务状态";
        return "AI文稿生成失败（上游状态 " + statusCode + "）：" + message;
    }

    private static String normalizeChatCompletionsUrl(String rawUrl) {
        String url = safe(rawUrl);
        if (url.isBlank()) return "";
        url = url.replaceAll("/+$", "");
        if (url.endsWith("/chat/completions")) return url;
        if (url.endsWith("/v1")) return url + "/chat/completions";
        return url + "/v1/chat/completions";
    }

    private String stripCodeFence(String content) {
        String value = safe(content);
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:\\w+)?", "").replaceFirst("```$", "").trim();
        }
        return value;
    }

    private void requireAllowedRole(AuthSessionService.SessionUser user) {
        String role = safe(user == null ? "" : user.role());
        if (!List.of(
            "admin",
            "manager",
            "quality",
            "doctor",
            "nurse",
            "nursing",
            "frontdesk",
            "reception",
            "inspection",
            "lab",
            "ecg",
            "ultrasound"
        ).contains(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无权使用AI文稿生成工具");
        }
    }

    private String normalizeTitle(String title) {
        String value = safe(title).replaceAll("[*_`~#]", "").trim();
        if (value.isBlank()) value = "AI文稿-" + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        if (value.length() > MAX_TITLE_LENGTH) value = value.substring(0, MAX_TITLE_LENGTH);
        if (value.matches(".*[\\\\/:*?\"<>|\\r\\n].*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能包含文件名非法字符");
        }
        return value;
    }

    private String normalizeDocType(String docType) {
        String value = safe(docType);
        return TEMPLATES.stream().anyMatch(item -> item.id().equals(value)) ? value : TEMPLATES.get(0).id();
    }

    private String templateName(String docType) {
        return TEMPLATES.stream().filter(item -> item.id().equals(docType)).findFirst().map(DocTemplate::name).orElse(TEMPLATES.get(0).name());
    }

    private Path writeGeneratedFile(String id, String fileName, byte[] bytes) {
        try {
            Files.createDirectories(generatedDir);
            Path target = generatedDir.resolve(id + "-" + sanitizeFileName(fileName)).normalize();
            if (!target.startsWith(generatedDir)) throw new IOException("invalid target file");
            Files.write(target, bytes);
            return target;
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI文稿文件写入失败：" + error.getMessage(), error);
        }
    }

    private ObjectNode loadDocument(String id) {
        if (safe(id).isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少文稿ID");
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT raw_json FROM clinic_generated_ai_documents WHERE id = ? LIMIT 1",
            (rs, rowNum) -> readJson(rs.getString("raw_json")),
            id
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文稿不存在");
        return rows.get(0);
    }

    private ObjectNode readJson(String json) {
        try {
            return (ObjectNode) objectMapper.readTree(json);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文稿记录解析失败", error);
        }
    }

    private String toJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文稿记录序列化失败", error);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文稿哈希计算失败", error);
        }
    }

    private String text(ObjectNode node, String key) {
        return text(node, key, "");
    }

    private String text(ObjectNode node, String key, String fallback) {
        String value = node == null ? "" : node.path(key).asText("");
        return value.isBlank() ? fallback : value;
    }

    private String sanitizeFileName(String value) {
        String sanitized = safe(value).replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_");
        return sanitized.isBlank() ? "AI文稿" : sanitized;
    }

    private String xmlEscape(String value) {
        return safe(value)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    private static String safe(String value) {
        return String.valueOf(value == null ? "" : value).trim();
    }

    private String now() {
        return OffsetDateTime.now().format(DISPLAY_TIME);
    }

    private String w3cTime() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private String stylesXml() {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:rPr><w:rFonts w:ascii="Microsoft YaHei" w:eastAsia="Microsoft YaHei"/><w:sz w:val="22"/></w:rPr><w:pPr><w:spacing w:after="120" w:line="360" w:lineRule="auto"/></w:pPr></w:style>
              <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:rPr><w:rFonts w:ascii="Microsoft YaHei" w:eastAsia="Microsoft YaHei"/><w:b/><w:sz w:val="36"/></w:rPr><w:pPr><w:jc w:val="center"/><w:spacing w:after="240"/></w:pPr></w:style>
            </w:styles>
            """;
    }

    private String contentTypesXml() {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
              <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
              <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
              <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
            </Types>
            """;
    }

    private String relsXml() {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
              <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
              <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
            </Relationships>
            """;
    }

    private String documentRelsXml() {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
            </Relationships>
            """;
    }

    private String coreXml(String title, AuthSessionService.SessionUser user, String model) {
        String created = w3cTime();
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <dc:title>%s</dc:title>
              <dc:creator>%s</dc:creator>
              <dc:description>Generated by AI document tool, model: %s</dc:description>
              <cp:lastModifiedBy>%s</cp:lastModifiedBy>
              <dcterms:created xsi:type="dcterms:W3CDTF">%s</dcterms:created>
              <dcterms:modified xsi:type="dcterms:W3CDTF">%s</dcterms:modified>
            </cp:coreProperties>
            """.formatted(xmlEscape(title), xmlEscape(user.name()), xmlEscape(model), xmlEscape(user.name()), created, created);
    }

    private String appXml() {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
              <Application>cowork patient record</Application>
            </Properties>
            """;
    }

    private void addEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    public record AiDocumentRequest(String title, String docType, String content) {}

    public record DownloadFile(FileSystemResource resource, String fileName) {}

    private record DocTemplate(String id, String name, String description) {}

    private record DraftInput(String title, String docType, String content) {}
}
