package com.coshare.patientrecord.medicalrecord.service;

import com.coshare.patientrecord.medicalrecord.model.TargetField;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("mysql")
public class MedicalRecordTemplateRenderer {

    private final ObjectMapper objectMapper;
    private static final Pattern UNBOUND_PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{[^}]+}");
    private static final Pattern TABLE_ROW_PATTERN = Pattern.compile("<w:tr[\\s\\S]*?</w:tr>");
    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile("<w:p[\\s\\S]*?</w:p>");
    private static final List<String> GENERATION_SCOPE_STOP_MARKERS = List.of(
        "十三、",
        "十三.",
        "十三．",
        "13、",
        "13.",
        "13．",
        "查房时序",
        "自动生成文书范围",
        "质控校验",
        "首次病程记录"
    );

    public MedicalRecordTemplateRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] renderTemplate(String templateResource, Map<String, String> replacements) {
        return renderTemplate(templateResource, replacements, true);
    }

    public byte[] renderCompleteTemplate(String templateResource, Map<String, String> replacements) {
        return renderTemplate(templateResource, replacements, false);
    }

    private byte[] renderTemplate(String templateResource, Map<String, String> replacements, boolean trimGenerationScope) {
        try (InputStream inputStream = templateInputStream(templateResource);
             ZipInputStream zipInputStream = new ZipInputStream(inputStream, StandardCharsets.UTF_8);
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zipOutputStream = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                ZipEntry next = new ZipEntry(entry.getName());
                zipOutputStream.putNextEntry(next);
                byte[] bytes = zipInputStream.readAllBytes();
                if (entry.getName().matches("word/(document|header\\d*|footer\\d*)\\.xml")) {
                    String xml = new String(bytes, StandardCharsets.UTF_8);
                    if (trimGenerationScope && "word/document.xml".equals(entry.getName())) {
                        xml = trimDocumentXmlToGenerationScope(xml);
                    }
                    xml = applyReplacements(xml, replacements);
                    xml = UNBOUND_PLACEHOLDER_PATTERN.matcher(xml).replaceAll("");
                    if ("word/document.xml".equals(entry.getName())) {
                        xml = cleanGeneratedDocumentXml(xml);
                    }
                    bytes = xml.getBytes(StandardCharsets.UTF_8);
                }
                zipOutputStream.write(bytes);
                zipOutputStream.closeEntry();
                zipInputStream.closeEntry();
            }
            zipOutputStream.finish();
            return output.toByteArray();
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "医生目标病历模板生成失败：" + error.getMessage(), error);
        }
    }

    public boolean templateAvailable(String templateResource) {
        return getClass().getClassLoader().getResource(templateResource) != null;
    }

    public ArrayNode unboundTemplateFields(String templateResource, List<TargetField> targetFields) {
        ArrayNode rows = objectMapper.createArrayNode();
        Set<String> placeholders = templatePlaceholderKeys(templateResource);
        for (TargetField field : targetFields) {
            if ("formOnly".equals(field.targetUse())) continue;
            if (!placeholders.contains(field.key())) {
                rows.add(field.section() + " - " + field.label() + "（" + field.key() + "）");
            }
        }
        return rows;
    }

    private String applyReplacements(String xml, Map<String, String> replacements) {
        String result = xml;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String key = entry.getKey();
            String value = xmlEscape(entry.getValue());
            result = result.replace("${" + key + "}", value);
        }
        return result;
    }

    private InputStream templateInputStream(String templateResource) throws IOException {
        InputStream stream = getClass().getClassLoader().getResourceAsStream(templateResource);
        if (stream == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "未找到医生目标病历模板：" + templateResource);
        }
        return stream;
    }

    public Set<String> templatePlaceholderKeys(String templateResource) {
        return readTemplatePlaceholderKeys(templateResource, true);
    }

    public Set<String> completeTemplatePlaceholderKeys(String templateResource) {
        return readTemplatePlaceholderKeys(templateResource, false);
    }

    public String referenceDocumentText(InputStream inputStream, int maxCharacters) {
        if (inputStream == null || maxCharacters <= 0) return "";
        try (ZipInputStream zipInputStream = new ZipInputStream(inputStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (!"word/document.xml".equals(entry.getName())) {
                    zipInputStream.closeEntry();
                    continue;
                }
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                Document document = factory.newDocumentBuilder().parse(zipInputStream);
                StringBuilder result = new StringBuilder();
                NodeList paragraphs = document.getElementsByTagNameNS(
                    "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
                    "p"
                );
                for (int index = 0; index < paragraphs.getLength(); index++) {
                    String paragraph = visibleParagraphText(paragraphs.item(index)).trim();
                    if (paragraph.isBlank()) continue;
                    if (result.length() > 0) result.append('\n');
                    int remaining = maxCharacters - result.length();
                    if (remaining <= 0) break;
                    result.append(paragraph, 0, Math.min(paragraph.length(), remaining));
                }
                return result.toString();
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传文件不是有效的 DOCX 文档");
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "上传的住院病历参考文档无法读取：" + error.getMessage(),
                error
            );
        }
    }

    private String visibleParagraphText(Node paragraph) {
        StringBuilder result = new StringBuilder();
        appendTextNodes(paragraph, result);
        return result.toString().replaceAll("\\s+", " ");
    }

    private void appendTextNodes(Node node, StringBuilder result) {
        if (node.getNodeType() == Node.TEXT_NODE) {
            result.append(node.getNodeValue());
            return;
        }
        NodeList children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            appendTextNodes(children.item(index), result);
        }
    }

    private Set<String> readTemplatePlaceholderKeys(String templateResource, boolean trimGenerationScope) {
        Set<String> keys = new HashSet<>();
        if (!templateAvailable(templateResource)) return keys;
        try (InputStream inputStream = templateInputStream(templateResource);
             ZipInputStream zipInputStream = new ZipInputStream(inputStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            Pattern placeholder = Pattern.compile("\\$\\{([^}]+)}");
            while ((entry = zipInputStream.getNextEntry()) != null) {
                byte[] bytes = zipInputStream.readAllBytes();
                if (entry.getName().matches("word/(document|header\\d*|footer\\d*)\\.xml")) {
                    String xml = new String(bytes, StandardCharsets.UTF_8);
                    if (trimGenerationScope && "word/document.xml".equals(entry.getName())) {
                        xml = trimDocumentXmlToGenerationScope(xml);
                    }
                    var matcher = placeholder.matcher(xml);
                    while (matcher.find()) keys.add(matcher.group(1));
                }
                zipInputStream.closeEntry();
            }
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "医生目标病历模板无法读取：" + error.getMessage(), error);
        }
        return keys;
    }

    private String trimDocumentXmlToGenerationScope(String xml) {
        var matcher = PARAGRAPH_PATTERN.matcher(xml);
        while (matcher.find()) {
            String paragraph = matcher.group();
            if (GENERATION_SCOPE_STOP_MARKERS.stream().noneMatch(paragraph::contains)) continue;
            int end = xml.indexOf("<w:sectPr", matcher.end());
            if (end < 0) end = xml.indexOf("</w:body>", matcher.end());
            if (end < 0) end = xml.length();
            return xml.substring(0, matcher.start()) + xml.substring(end);
        }
        return xml;
    }

    private String cleanGeneratedDocumentXml(String xml) {
        String withoutEmptyRows = removeEmptyBlocks(xml, TABLE_ROW_PATTERN);
        return removeEmptyBlocks(withoutEmptyRows, PARAGRAPH_PATTERN);
    }

    private String removeEmptyBlocks(String xml, Pattern blockPattern) {
        var matcher = blockPattern.matcher(xml);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String block = matcher.group();
            matcher.appendReplacement(buffer, shouldRemoveGeneratedBlock(block) ? "" : java.util.regex.Matcher.quoteReplacement(block));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private boolean shouldRemoveGeneratedBlock(String block) {
        if (block.contains("<w:sectPr") || block.contains("<w:drawing") || block.contains("<w:pict") || block.contains("<w:object")) {
            return false;
        }
        String visible = block
            .replaceAll("<[^>]+>", "")
            .replace("&nbsp;", "")
            .replace("&#160;", "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim();
        return visible.isBlank();
    }

    private String xmlEscape(String value) {
        return String.valueOf(value == null ? "" : value).trim()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
