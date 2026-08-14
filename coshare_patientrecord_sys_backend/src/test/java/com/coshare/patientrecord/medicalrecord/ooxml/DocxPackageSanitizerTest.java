package com.coshare.patientrecord.medicalrecord.ooxml;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class DocxPackageSanitizerTest {

    private final DocxPackageSanitizer sanitizer = new DocxPackageSanitizer();

    @Test
    void acceptsMinimalValidDocxWithoutRewritingIt() throws Exception {
        byte[] source = packageBytes(baseEntries());

        DocxPackageSanitizer.Result result = sanitizer.inspectAndSanitize(source);

        assertThat(result.decision()).isEqualTo(DocxPackageSanitizer.Decision.ACCEPTED);
        assertThat(result.packageValidation().valid()).isTrue();
        assertThat(result.removedPartCount()).isZero();
        assertThat(result.sanitizedBytes()).isEqualTo(source);
    }

    @Test
    void removesMacroEmbeddedObjectAndExternalRelationshipThenRevalidates() throws Exception {
        Map<String, byte[]> entries = baseEntries();
        entries.put("word/vbaProject.bin", new byte[] {1, 2, 3});
        entries.put("word/embeddings/oleObject1.bin", new byte[] {4, 5, 6});
        entries.put("word/_rels/document.xml.rels", xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
              <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://example.invalid" TargetMode="External"/>
              <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/oleObject" Target="embeddings/oleObject1.bin"/>
              <Relationship Id="rId4" Type="http://schemas.microsoft.com/office/2006/relationships/vbaProject" Target="vbaProject.bin"/>
            </Relationships>
            """));
        entries.put(DocxPackageSanitizer.CONTENT_TYPES, xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
              <Override PartName="/word/vbaProject.bin" ContentType="application/vnd.ms-office.vbaProject"/>
              <Override PartName="/word/embeddings/oleObject1.bin" ContentType="application/vnd.openxmlformats-officedocument.oleObject"/>
            </Types>
            """));

        DocxPackageSanitizer.Result result = sanitizer.inspectAndSanitize(packageBytes(entries));

        assertThat(result.decision()).isEqualTo(DocxPackageSanitizer.Decision.SANITIZED);
        assertThat(result.packageValidation().valid()).isTrue();
        assertThat(result.removedPartCount()).isEqualTo(2);
        assertThat(result.removedRelationshipCount()).isEqualTo(3);
        assertThat(result.externalRelationshipCount()).isEqualTo(1);
        assertThat(entryNames(result.sanitizedBytes()))
            .doesNotContain("word/vbaProject.bin", "word/embeddings/oleObject1.bin");
        assertThat(entryText(result.sanitizedBytes(), "word/_rels/document.xml.rels"))
            .contains("styles.xml")
            .doesNotContain("example.invalid", "oleObject", "vbaProject");
        assertThat(entryText(result.sanitizedBytes(), DocxPackageSanitizer.CONTENT_TYPES))
            .doesNotContain("vbaProject", "oleObject1.bin");
    }

    @Test
    void rewritesPackageWhenOnlyDangerousContentTypeDeclarationIsRemoved() throws Exception {
        Map<String, byte[]> entries = baseEntries();
        entries.put(DocxPackageSanitizer.CONTENT_TYPES, xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
              <Override PartName="/word/orphan.bin" ContentType="application/vnd.ms-office.vbaProject"/>
            </Types>
            """));

        DocxPackageSanitizer.Result result = sanitizer.inspectAndSanitize(packageBytes(entries));

        assertThat(result.decision()).isEqualTo(DocxPackageSanitizer.Decision.SANITIZED);
        assertThat(result.removedPartCount()).isZero();
        assertThat(result.removedRelationshipCount()).isZero();
        assertThat(result.findings()).extracting(DocxPackageSanitizer.Finding::code).contains("CONTENT_TYPE_REMOVED");
        assertThat(entryText(result.sanitizedBytes(), DocxPackageSanitizer.CONTENT_TYPES))
            .doesNotContain("vbaProject", "orphan.bin");
    }

    @Test
    void rejectsDuplicateEntriesCaseInsensitively() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            writeEntry(zip, "word/document.xml", "a".getBytes(StandardCharsets.UTF_8));
            writeEntry(zip, "WORD/DOCUMENT.XML", "b".getBytes(StandardCharsets.UTF_8));
        }

        DocxPackageSanitizer.Result result = sanitizer.inspectAndSanitize(output.toByteArray());

        assertThat(result.decision()).isEqualTo(DocxPackageSanitizer.Decision.REJECTED);
        assertThat(result.findings()).extracting(DocxPackageSanitizer.Finding::code).contains("DUPLICATE_ENTRY");
    }

    @Test
    void rejectsTraversalEntry() throws Exception {
        Map<String, byte[]> entries = baseEntries();
        entries.put("../payload.bin", new byte[] {1});

        DocxPackageSanitizer.Result result = sanitizer.inspectAndSanitize(packageBytes(entries));

        assertThat(result.decision()).isEqualTo(DocxPackageSanitizer.Decision.REJECTED);
        assertThat(result.findings()).extracting(DocxPackageSanitizer.Finding::code).contains("UNSAFE_ENTRY_NAME");
    }

    @Test
    void acceptsRelationshipTargetThatNormalizesWithinPackageRoot() throws Exception {
        Map<String, byte[]> entries = baseEntries();
        entries.put("customXml/item1.xml", xml("<root/>"));
        entries.put("word/_rels/document.xml.rels", xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/customXml" Target="../customXml/item1.xml"/>
            </Relationships>
            """));

        DocxPackageSanitizer.Result result = sanitizer.inspectAndSanitize(packageBytes(entries));

        assertThat(result.decision()).isEqualTo(DocxPackageSanitizer.Decision.ACCEPTED);
        assertThat(result.removedRelationshipCount()).isZero();
    }

    @Test
    void removesRelationshipTargetThatEscapesPackageRoot() throws Exception {
        Map<String, byte[]> entries = baseEntries();
        entries.put("word/_rels/document.xml.rels", xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/customXml" Target="../../payload.xml"/>
            </Relationships>
            """));

        DocxPackageSanitizer.Result result = sanitizer.inspectAndSanitize(packageBytes(entries));

        assertThat(result.decision()).isEqualTo(DocxPackageSanitizer.Decision.SANITIZED);
        assertThat(result.removedRelationshipCount()).isOne();
        assertThat(entryText(result.sanitizedBytes(), "word/_rels/document.xml.rels"))
            .doesNotContain("payload.xml");
    }

    @Test
    void rejectsPackageThatExceedsEntryBudget() throws Exception {
        DocxPackageSanitizer limited = new DocxPackageSanitizer(
            new DocxPackageSanitizer.Limits(1024 * 1024, 2, 1024 * 1024, 1024 * 1024, 1000, 1024 * 1024)
        );

        DocxPackageSanitizer.Result result = limited.inspectAndSanitize(packageBytes(baseEntries()));

        assertThat(result.decision()).isEqualTo(DocxPackageSanitizer.Decision.REJECTED);
        assertThat(result.findings()).extracting(DocxPackageSanitizer.Finding::code).contains("ENTRY_COUNT_EXCEEDED");
    }

    @Test
    void rejectsMalformedPackageMissingRequiredParts() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DocxPackageSanitizer.DOCUMENT, xml("""
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body/></w:document>
            """));

        DocxPackageSanitizer.Result result = sanitizer.inspectAndSanitize(packageBytes(entries));

        assertThat(result.decision()).isEqualTo(DocxPackageSanitizer.Decision.REJECTED);
        assertThat(result.packageValidation().valid()).isFalse();
        assertThat(result.packageValidation().errors()).hasSize(2);
    }

    private Map<String, byte[]> baseEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DocxPackageSanitizer.CONTENT_TYPES, xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
            """));
        entries.put(DocxPackageSanitizer.ROOT_RELS, xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
            """));
        entries.put(DocxPackageSanitizer.DOCUMENT, xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>病历</w:t></w:r></w:p></w:body></w:document>
            """));
        return entries;
    }

    private byte[] xml(String value) {
        return value.stripLeading().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] packageBytes(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) writeEntry(zip, entry.getKey(), entry.getValue());
        }
        return output.toByteArray();
    }

    private void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private java.util.Set<String> entryNames(byte[] archive) throws Exception {
        java.util.Set<String> names = new java.util.HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) names.add(entry.getName());
        }
        return names;
    }

    private String entryText(byte[] archive, String name) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (name.equals(entry.getName())) return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return "";
    }
}
