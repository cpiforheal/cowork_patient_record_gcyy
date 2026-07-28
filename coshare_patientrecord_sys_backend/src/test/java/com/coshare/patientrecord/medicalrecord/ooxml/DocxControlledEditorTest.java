package com.coshare.patientrecord.medicalrecord.ooxml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class DocxControlledEditorTest {

    private final DocxNodeMapper mapper = new DocxNodeMapper();
    private final DocxControlledEditor editor = new DocxControlledEditor(mapper);

    @Test
    void editsExplicitContentControlAndBookmarkNodesWithoutOrdinalFallback() throws Exception {
        byte[] source = docx(documentXml("""
            <w:sdt><w:sdtPr><w:tag w:val="diagnosis"/></w:sdtPr><w:sdtContent>
              <w:p><w:r><w:t>旧诊断</w:t></w:r></w:p>
            </w:sdtContent></w:sdt>
            <w:p><w:bookmarkStart w:id="1" w:name="treatment"/><w:r><w:t>旧方案</w:t></w:r></w:p>
            <w:p><w:r><w:t>保持不变</w:t></w:r></w:p>
            """));
        DocxNodeMapper.Catalog catalog = mapper.catalog(source);
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("cc:diagnosis", "新诊断");
        replacements.put("bm:treatment", "新方案");

        DocxControlledEditor.EditResult result = editor.edit(source, replacements);
        String xml = documentXml(result.docxBytes());

        assertThat(result.updatedNodeCount()).isEqualTo(2);
        assertThat(result.updatedNodeKeys()).containsExactly("cc:diagnosis", "bm:treatment");
        assertThat(xml).contains("新诊断", "新方案", "保持不变");
        assertThat(xml).doesNotContain("旧诊断", "旧方案");
        assertThat(mapper.catalog(result.docxBytes()).nodes()).hasSameSizeAs(catalog.nodes());
    }

    @Test
    void preservesRunPropertiesAndDistributesTextAcrossExistingTextRuns() throws Exception {
        byte[] source = docx(documentXml("""
            <w:p>
              <w:pPr><w:jc w:val="center"/></w:pPr>
              <w:r><w:rPr><w:b/></w:rPr><w:t>甲乙</w:t></w:r>
              <w:r><w:rPr><w:color w:val="FF0000"/></w:rPr><w:t>丙丁</w:t></w:r>
            </w:p>
            """));
        String nodeKey = mapper.catalog(source).nodes().get(0).nodeKey();

        byte[] edited = editor.edit(source, Map.of(nodeKey, "新的诊断内容")).docxBytes();
        Document document = parse(documentXml(edited));
        NodeList texts = document.getElementsByTagNameNS(DocxNodeMapper.WORD_NS, "t");

        assertThat(texts.getLength()).isEqualTo(2);
        assertThat(texts.item(0).getTextContent()).isEqualTo("新的");
        assertThat(texts.item(1).getTextContent()).isEqualTo("诊断内容");
        assertThat(document.getElementsByTagNameNS(DocxNodeMapper.WORD_NS, "pPr").getLength()).isEqualTo(1);
        assertThat(document.getElementsByTagNameNS(DocxNodeMapper.WORD_NS, "b").getLength()).isEqualTo(1);
        assertThat(document.getElementsByTagNameNS(DocxNodeMapper.WORD_NS, "color").getLength()).isEqualTo(1);
    }

    @Test
    void preservesTableBookmarkBreakTabAndDrawingNodes() throws Exception {
        byte[] source = docx(documentXml("""
            <w:tbl><w:tr><w:tc><w:p>
              <w:bookmarkStart w:id="7" w:name="table_field"/>
              <w:r><w:t>旧值</w:t><w:tab/><w:br/><w:drawing><w:inline/></w:drawing></w:r>
              <w:bookmarkEnd w:id="7"/>
            </w:p></w:tc></w:tr></w:tbl>
            """));

        byte[] edited = editor.edit(source, Map.of("bm:table_field", "新值")).docxBytes();
        Document document = parse(documentXml(edited));

        assertThat(document.getElementsByTagNameNS(DocxNodeMapper.WORD_NS, "tbl").getLength()).isEqualTo(1);
        assertThat(document.getElementsByTagNameNS(DocxNodeMapper.WORD_NS, "bookmarkStart").getLength()).isEqualTo(1);
        assertThat(document.getElementsByTagNameNS(DocxNodeMapper.WORD_NS, "bookmarkEnd").getLength()).isEqualTo(1);
        assertThat(document.getElementsByTagNameNS(DocxNodeMapper.WORD_NS, "tab").getLength()).isEqualTo(1);
        assertThat(document.getElementsByTagNameNS(DocxNodeMapper.WORD_NS, "br").getLength()).isEqualTo(1);
        assertThat(document.getElementsByTagNameNS(DocxNodeMapper.WORD_NS, "drawing").getLength()).isEqualTo(1);
        assertThat(document.getElementsByTagNameNS(DocxNodeMapper.WORD_NS, "t").item(0).getTextContent())
            .isEqualTo("新值");
    }

    @Test
    void preservesLeadingAndTrailingSpacesWithXmlSpace() throws Exception {
        byte[] source = docx(documentXml("<w:p><w:r><w:t>旧值</w:t></w:r></w:p>"));
        String nodeKey = mapper.catalog(source).nodes().get(0).nodeKey();

        Document document = parse(documentXml(editor.edit(source, Map.of(nodeKey, " 新值 ")).docxBytes()));
        Element text = (Element) document.getElementsByTagNameNS(DocxNodeMapper.WORD_NS, "t").item(0);

        assertThat(text.getTextContent()).isEqualTo(" 新值 ");
        assertThat(text.getAttributeNS(XMLConstants.XML_NS_URI, "space")).isEqualTo("preserve");
    }

    @Test
    void rejectsUnknownNodeInsteadOfGuessingByOrdinal() throws Exception {
        byte[] source = docx(documentXml("<w:p><w:r><w:t>原内容</w:t></w:r></w:p>"));

        assertThatThrownBy(() -> editor.edit(source, Map.of("cc:not-present", "新内容")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("节点不存在");
    }

    @Test
    void editedRealPackageRemainsAcceptedAndPreservesUntargetedParts() throws Exception {
        byte[] source = validPackage(documentXml("""
            <w:sdt><w:sdtPr><w:tag w:val="diagnosis"/></w:sdtPr><w:sdtContent>
              <w:p><w:r><w:t>旧诊断</w:t></w:r></w:p>
            </w:sdtContent></w:sdt>
            """));
        byte[] originalStyles = entry(source, "word/styles.xml");
        byte[] originalHeader = entry(source, "word/header1.xml");
        byte[] originalMedia = entry(source, "word/media/image1.png");

        byte[] edited = editor.edit(source, Map.of("cc:diagnosis", "新诊断")).docxBytes();
        DocxPackageSanitizer.Result inspection = new DocxPackageSanitizer().inspectAndSanitize(edited);

        assertThat(inspection.decision()).isEqualTo(DocxPackageSanitizer.Decision.ACCEPTED);
        assertThat(inspection.packageValidation().valid()).isTrue();
        assertThat(entry(edited, "word/styles.xml")).isEqualTo(originalStyles);
        assertThat(entry(edited, "word/header1.xml")).isEqualTo(originalHeader);
        assertThat(entry(edited, "word/media/image1.png")).isEqualTo(originalMedia);
    }

    @Test
    void doesNotModifyOtherZipParts() throws Exception {
        byte[] source = docx(documentXml("<w:p><w:r><w:t>原内容</w:t></w:r></w:p>"));
        String nodeKey = mapper.catalog(source).nodes().get(0).nodeKey();

        byte[] edited = editor.edit(source, Map.of(nodeKey, "新内容")).docxBytes();

        assertThat(entry(edited, "word/styles.xml"))
            .isEqualTo("<styles>opaque-formatting-part</styles>".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void copiesNonDocumentPartLargerThanDocumentXmlLimitWithoutTruncation() throws Exception {
        byte[] largePart = new byte[16 * 1024 * 1024 + 257];
        for (int index = 0; index < largePart.length; index++) {
            largePart[index] = (byte) (index % 251);
        }
        byte[] source = docx(
            documentXml("<w:p><w:r><w:t>原内容</w:t></w:r></w:p>"),
            largePart
        );
        String nodeKey = mapper.catalog(source).nodes().get(0).nodeKey();

        byte[] edited = editor.edit(source, Map.of(nodeKey, "新内容")).docxBytes();

        assertThat(entry(edited, "word/styles.xml")).isEqualTo(largePart);
    }

    private String documentXml(String body) {
        return """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>%s</w:body>
            </w:document>
            """.formatted(body).stripLeading();
    }

    private byte[] docx(String documentXml) throws Exception {
        return docx(
            documentXml,
            "<styles>opaque-formatting-part</styles>".getBytes(StandardCharsets.UTF_8)
        );
    }

    private byte[] docx(String documentXml, byte[] stylesPart) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            writeEntry(zip, "word/document.xml", documentXml.getBytes(StandardCharsets.UTF_8));
            writeEntry(zip, "word/styles.xml", stylesPart);
        }
        return output.toByteArray();
    }

    private byte[] validPackage(String documentXml) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            writeEntry(zip, "[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Default Extension="png" ContentType="image/png"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                  <Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>
                </Types>
                """.stripLeading().getBytes(StandardCharsets.UTF_8));
            writeEntry(zip, "_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
                """.stripLeading().getBytes(StandardCharsets.UTF_8));
            writeEntry(zip, "word/document.xml", documentXml.getBytes(StandardCharsets.UTF_8));
            writeEntry(zip, "word/styles.xml", "<styles>formatting</styles>".getBytes(StandardCharsets.UTF_8));
            writeEntry(zip, "word/header1.xml", "<header>保留页眉</header>".getBytes(StandardCharsets.UTF_8));
            writeEntry(zip, "word/media/image1.png", new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 1, 2, 3});
        }
        return output.toByteArray();
    }

    private String documentXml(byte[] docx) throws Exception {
        return new String(entry(docx, "word/document.xml"), StandardCharsets.UTF_8);
    }

    private byte[] entry(byte[] docx, String name) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (name.equals(entry.getName())) return zip.readAllBytes();
            }
        }
        throw new AssertionError("missing ZIP entry: " + name);
    }

    private void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))
        );
    }
}
