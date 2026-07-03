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
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("mysql")
public class MedicalRecordTemplateRenderer {

    private final ObjectMapper objectMapper;

    public MedicalRecordTemplateRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] renderTemplate(String templateResource, Map<String, String> replacements) {
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
                    xml = applyReplacements(xml, replacements);
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

    private Set<String> templatePlaceholderKeys(String templateResource) {
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

    private String xmlEscape(String value) {
        return String.valueOf(value == null ? "" : value).trim()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
