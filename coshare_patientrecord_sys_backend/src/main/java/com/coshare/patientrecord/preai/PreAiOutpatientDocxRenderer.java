package com.coshare.patientrecord.preai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 门诊病历 DOCX 渲染器。
 * 输入为门诊病历汇总（summary，由 PreAiEncounterService 组装并持久化快照），
 * 输出为按门诊病历表式排版（基本信息 + 主诉/现病史等段落）的 DOCX 字节。
 * 采用与脱敏导出一致的手写 OOXML + zip 打包方式，无额外依赖。
 */
@Component
public class PreAiOutpatientDocxRenderer {

    public static final String TEMPLATE_VERSION = "outpatient-record-v1";

    private final ObjectMapper objectMapper;

    public PreAiOutpatientDocxRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 汇总结构：basic 为键值对行，sections 为段落型小节，前端预览与 DOCX 渲染共用同一份 JSON。 */
    public ObjectNode emptySummary() {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("templateVersion", TEMPLATE_VERSION);
        summary.putArray("basic");
        summary.putArray("sections");
        return summary;
    }

    public ObjectNode addBasic(ObjectNode summary, String label, String value) {
        if (value == null || value.isBlank()) return summary;
        ObjectNode row = summary.withArray("basic").addObject();
        row.put("label", label);
        row.put("value", value.trim());
        return summary;
    }

    public ObjectNode addSection(ObjectNode summary, String code, String title, List<String> paragraphs) {
        List<String> cleaned = new ArrayList<>();
        if (paragraphs != null) {
            for (String item : paragraphs) {
                if (item != null && !item.isBlank()) cleaned.add(item.trim());
            }
        }
        ObjectNode section = summary.withArray("sections").addObject();
        section.put("code", code);
        section.put("title", title);
        section.put("empty", cleaned.isEmpty());
        com.fasterxml.jackson.databind.node.ArrayNode nodes = section.putArray("paragraphs");
        cleaned.forEach(nodes::add);
        return summary;
    }

    public byte[] render(ObjectNode summary) {
        String documentXml = buildDocumentXml(summary);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            addEntry(zip, "[Content_Types].xml", contentTypesXml());
            addEntry(zip, "_rels/.rels", relationshipsXml());
            addEntry(zip, "docProps/core.xml", corePropertiesXml());
            addEntry(zip, "docProps/app.xml", appPropertiesXml());
            addEntry(zip, "word/document.xml", documentXml);
            addEntry(zip, "word/styles.xml", stylesXml());
            addEntry(zip, "word/settings.xml", settingsXml());
            addEntry(zip, "word/_rels/document.xml.rels", documentRelationshipsXml());
            zip.finish();
            return output.toByteArray();
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "门诊病历 DOCX 生成失败", error);
        }
    }

    private String buildDocumentXml(ObjectNode summary) {
        StringBuilder body = new StringBuilder();
        body.append(paragraph("门诊病历", "Title"));
        StringBuilder meta = new StringBuilder();
        JsonNode basic = summary.path("basic");
        for (int index = 0; index < basic.size(); index++) {
            if (index > 0) meta.append("　|　");
            meta.append(basic.path(index).path("label").asText()).append("：").append(basic.path(index).path("value").asText());
        }
        body.append(paragraph(meta.toString(), "Meta"));
        for (JsonNode section : summary.path("sections")) {
            body.append(paragraph(text(section, "title"), "Heading1"));
            if (section.path("empty").asBoolean(false)) {
                body.append(paragraph("（空）", "EmptyMark"));
                continue;
            }
            for (JsonNode item : section.path("paragraphs")) {
                body.append(paragraph(item.asText(), "BodyText"));
            }
        }
        body.append(paragraph("医生签名：＿＿＿＿＿＿＿＿　　日期：＿＿＿＿＿＿＿＿", "SignLine"));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            + body
            + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1300\" w:right=\"1300\" w:bottom=\"1300\" w:left=\"1300\"/></w:sectPr>"
            + "</w:body></w:document>";
    }

    private String paragraph(String value, String style) {
        String content = value == null ? "" : value;
        return "<w:p><w:pPr><w:pStyle w:val=\"" + style + "\"/></w:pPr><w:r><w:t xml:space=\"preserve\">"
            + xml(content) + "</w:t></w:r></w:p>";
    }

    private String xml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.path(key);
        return value == null || value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    private void addEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String contentTypesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
            + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
            + "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>"
            + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
            + "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>"
            + "</Types>";
    }

    private String relationshipsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
            + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>"
            + "</Relationships>";
    }

    private String documentRelationshipsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings\" Target=\"settings.xml\"/>"
            + "</Relationships>";
    }

    private String stylesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:rPr><w:rFonts w:ascii=\"SimSun\" w:hAnsi=\"SimSun\" w:eastAsia=\"宋体\"/><w:sz w:val=\"21\"/></w:rPr><w:pPr><w:spacing w:after=\"80\" w:line=\"300\" w:lineRule=\"auto\"/></w:pPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:jc w:val=\"center\"/><w:spacing w:after=\"120\"/></w:pPr><w:rPr><w:b/><w:rFonts w:eastAsia=\"黑体\"/><w:sz w:val=\"36\"/><w:color w:val=\"000000\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Meta\"><w:name w:val=\"Meta\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"160\"/></w:pPr><w:rPr><w:sz w:val=\"20\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"70\"/><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:color=\"999999\"/></w:pBdr></w:pPr><w:rPr><w:b/><w:rFonts w:eastAsia=\"黑体\"/><w:sz w:val=\"24\"/><w:color w:val=\"000000\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"BodyText\"><w:name w:val=\"Body Text\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:ind w:firstLine=\"420\"/><w:spacing w:after=\"90\"/></w:pPr><w:rPr><w:sz w:val=\"22\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"EmptyMark\"><w:name w:val=\"Empty Mark\"/><w:basedOn w:val=\"Normal\"/><w:rPr><w:sz w:val=\"20\"/><w:color w:val=\"AAAAAA\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"SignLine\"><w:name w:val=\"Sign Line\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:before=\"500\"/></w:pPr><w:rPr><w:sz w:val=\"22\"/></w:rPr></w:style>"
            + "</w:styles>";
    }

    private String settingsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:settings xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"></w:settings>";
    }

    private String corePropertiesXml() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
            + "<dc:title>门诊病历</dc:title><dc:creator>病历协同系统</dc:creator><cp:lastModifiedBy>病历协同系统</cp:lastModifiedBy>"
            + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:created></cp:coreProperties>";
    }

    private String appPropertiesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\"><Application>病历协同系统</Application></Properties>";
    }
}
