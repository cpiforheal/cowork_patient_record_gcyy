package com.coshare.patientrecord.medicalrecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class MedicalRecordTemplateRendererTest {

    private final MedicalRecordTemplateRenderer renderer = new MedicalRecordTemplateRenderer(new ObjectMapper());

    @Test
    void extractsParagraphsFromUploadedDocx() throws Exception {
        byte[] docx = docxWithDocumentXml("""
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>入院记录</w:t></w:r></w:p>
                <w:p><w:r><w:t>主诉：</w:t></w:r><w:r><w:t>反复咳嗽三天</w:t></w:r></w:p>
              </w:body>
            </w:document>
            """);

        String result = renderer.referenceDocumentText(new ByteArrayInputStream(docx), 1000);

        assertThat(result).isEqualTo("入院记录\n主诉：反复咳嗽三天");
    }

    @Test
    void rejectsDocumentTextOverConfiguredLimitWithoutPartialParagraphs() throws Exception {
        byte[] docx = docxWithDocumentXml("""
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:p><w:r><w:t>1234567890</w:t></w:r></w:p></w:body>
            </w:document>
            """);

        assertThatThrownBy(() -> renderer.referenceDocumentText(new ByteArrayInputStream(docx), 6))
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                assertThat(error.getReason()).isEqualTo("参考文档正文超过 6 字，请精简后重新上传");
            });
    }

    @Test
    void rejectsArchiveWithoutWordDocumentXml() throws Exception {
        byte[] invalidDocx = zipEntry("content.txt", "not a docx");

        assertThatThrownBy(() -> renderer.referenceDocumentText(new ByteArrayInputStream(invalidDocx), 1000))
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getReason()).isEqualTo("上传文件不是有效的 DOCX 文档");
            });
    }

    @Test
    void rewritesVisibleParagraphTextAndKeepsParagraphFormatting() throws Exception {
        byte[] docx = docxWithDocumentXml("""
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>旧标题</w:t></w:r></w:p>
                <w:p><w:r><w:t>旧正文</w:t></w:r><w:r><w:t>旧内容</w:t></w:r></w:p>
              </w:body>
            </w:document>
            """);

        byte[] rewritten = renderer.rewriteReferenceDocument(docx, List.of("新标题", "新正文内容"));
        String xml = documentXml(rewritten);

        assertThat(xml).contains("<w:jc w:val=\"center\"/>", "<w:b/>", "<w:t>新标题</w:t>", "<w:t>新正文内容</w:t>");
        assertThat(xml).doesNotContain("旧标题", "旧正文", "旧内容");
        assertThat(renderer.referenceDocumentParagraphs(new ByteArrayInputStream(rewritten), 1000))
            .containsExactly("新标题", "新正文内容");
    }

    private String documentXml(byte[] docx) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }

    private byte[] docxWithDocumentXml(String xml) throws Exception {
        return zipEntry("word/document.xml", xml);
    }

    private byte[] zipEntry(String name, String content) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
