package com.coshare.patientrecord.medicalrecord.ooxml;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class DocxNodeMapperTest {

    private final DocxNodeMapper mapper = new DocxNodeMapper();

    @Test
    void catalogsParagraphsWithContentControlBookmarkAndStructuralPathPriority() throws Exception {
        DocxNodeMapper.Catalog catalog = mapper.catalog(docx("""
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:sdt>
                  <w:sdtPr><w:alias w:val="别名"/><w:tag w:val="chief-complaint"/></w:sdtPr>
                  <w:sdtContent><w:p><w:r><w:t>主诉内容</w:t></w:r></w:p></w:sdtContent>
                </w:sdt>
                <w:p><w:bookmarkStart w:id="1" w:name="present_illness"/><w:r><w:t>现病史</w:t></w:r></w:p>
                <w:p><w:r><w:t>一般段落</w:t></w:r></w:p>
                <w:p><w:r><w:t>   </w:t></w:r></w:p>
              </w:body>
            </w:document>
            """));

        assertThat(catalog.partName()).isEqualTo("word/document.xml");
        assertThat(catalog.documentHash()).hasSize(64);
        assertThat(catalog.nodes()).hasSize(3);
        assertThat(catalog.nodes()).extracting(DocxNodeMapper.CatalogNode::locatorType)
            .containsExactly(
                DocxNodeMapper.LocatorType.CONTENT_CONTROL,
                DocxNodeMapper.LocatorType.BOOKMARK,
                DocxNodeMapper.LocatorType.STRUCTURAL_PATH
            );
        assertThat(catalog.nodes().get(0).locator()).isEqualTo("chief-complaint");
        assertThat(catalog.nodes().get(0).nodeKey()).isEqualTo("cc:chief-complaint");
        assertThat(catalog.nodes().get(1).locator()).isEqualTo("present_illness");
        assertThat(catalog.nodes().get(1).nodeKey()).isEqualTo("bm:present_illness");
        assertThat(catalog.nodes().get(2).locator()).startsWith("/document[1]/body[1]/p[2]");
        assertThat(catalog.nodes()).extracting(DocxNodeMapper.CatalogNode::legacyOrdinal)
            .containsExactly(1, 2, 3);
    }

    @Test
    void controlledMappingUsesSemanticLocatorBeforeChangedContentAndStructure() throws Exception {
        byte[] source = docx("""
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
              <w:sdt><w:sdtPr><w:tag w:val="diagnosis"/></w:sdtPr><w:sdtContent>
                <w:p><w:r><w:t>旧诊断</w:t></w:r></w:p>
              </w:sdtContent></w:sdt>
            </w:body></w:document>
            """);
        byte[] target = docx("""
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
              <w:p><w:r><w:t>新增标题</w:t></w:r></w:p>
              <w:sdt><w:sdtPr><w:tag w:val="diagnosis"/></w:sdtPr><w:sdtContent>
                <w:p><w:r><w:t>新诊断</w:t></w:r></w:p>
              </w:sdtContent></w:sdt>
            </w:body></w:document>
            """);

        DocxNodeMapper.MappingPlan plan = mapper.map(source, target, DocxNodeMapper.MappingMode.CONTROLLED);

        assertThat(plan.mappedCount()).isOne();
        assertThat(plan.targetUnmappedCount()).isOne();
        assertThat(plan.mappings().get(0).matchBasis()).isEqualTo(DocxNodeMapper.MatchBasis.SEMANTIC_LOCATOR);
        assertThat(plan.mappings().get(0).confidence()).isEqualTo(1.0);
        assertThat(plan.mappings().get(0).beforePreview()).isEqualTo("旧诊断");
        assertThat(plan.mappings().get(0).afterPreview()).isEqualTo("新诊断");
    }

    @Test
    void controlledMappingFallsBackToStructuralPathThenUniqueContentHash() {
        DocxNodeMapper.Catalog source = catalog(
            node(1, "source-structure", "/document[1]/body[1]/p[1]", "hash-a", "原内容"),
            node(2, "source-hash", "/document[1]/body[1]/p[9]", "hash-b", "可移动内容")
        );
        DocxNodeMapper.Catalog target = catalog(
            node(1, "target-structure", "/document[1]/body[1]/p[1]", "changed", "改写内容"),
            node(2, "target-hash", "/document[1]/body[1]/p[2]", "hash-b", "可移动内容")
        );

        DocxNodeMapper.MappingPlan plan = mapper.map(source, target, DocxNodeMapper.MappingMode.CONTROLLED);

        assertThat(plan.mappedCount()).isEqualTo(2);
        assertThat(plan.mappings()).extracting(DocxNodeMapper.Mapping::matchBasis)
            .containsExactly(DocxNodeMapper.MatchBasis.STRUCTURAL_PATH, DocxNodeMapper.MatchBasis.CONTENT_HASH);
        assertThat(plan.mappings()).extracting(DocxNodeMapper.Mapping::confidence)
            .containsExactly(0.9, 0.75);
    }

    @Test
    void controlledMappingDoesNotGuessWhenContentHashIsAmbiguous() {
        DocxNodeMapper.Catalog source = catalog(
            node(1, "source", "/document[1]/body[1]/p[9]", "same-hash", "重复内容")
        );
        DocxNodeMapper.Catalog target = catalog(
            node(1, "target-a", "/document[1]/body[1]/p[1]", "same-hash", "重复内容"),
            node(2, "target-b", "/document[1]/body[1]/p[2]", "same-hash", "重复内容")
        );

        DocxNodeMapper.MappingPlan plan = mapper.map(source, target, DocxNodeMapper.MappingMode.CONTROLLED);

        assertThat(plan.mappedCount()).isZero();
        assertThat(plan.sourceUnmappedCount()).isOne();
        assertThat(plan.targetUnmappedCount()).isEqualTo(2);
        assertThat(plan.mappings().get(0).status()).isEqualTo(DocxNodeMapper.MappingStatus.SOURCE_UNMAPPED);
    }

    @Test
    void boundsLongLocatorsAndDuplicateNodeKeysToV13ColumnLengths() throws Exception {
        String longTag = "section-" + "x".repeat(1200);
        DocxNodeMapper.Catalog catalog = mapper.catalog(docx("""
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
              <w:sdt><w:sdtPr><w:tag w:val="%s"/></w:sdtPr><w:sdtContent>
                <w:p><w:r><w:t>第一段</w:t></w:r></w:p>
                <w:p><w:r><w:t>第二段</w:t></w:r></w:p>
              </w:sdtContent></w:sdt>
            </w:body></w:document>
            """.formatted(longTag)));

        assertThat(catalog.nodes()).hasSize(2);
        assertThat(catalog.nodes()).allSatisfy(node -> {
            assertThat(node.locator()).hasSizeLessThanOrEqualTo(1000).contains(":sha256:");
            assertThat(node.structuralPath()).hasSizeLessThanOrEqualTo(1000);
            assertThat(node.nodeKey()).hasSizeLessThanOrEqualTo(255);
        });
        assertThat(catalog.nodes().get(0).nodeKey()).isNotEqualTo(catalog.nodes().get(1).nodeKey());
    }

    @Test
    void resequencesMappingsForV13TaskSequenceConstraint() {
        DocxNodeMapper.Catalog source = catalog(
            node(10, "source-a", "/source/a", "hash-a", "源一"),
            node(30, "source-b", "/source/b", "hash-b", "源二")
        );
        DocxNodeMapper.Catalog target = catalog(
            node(7, "target-a", "/target/a", "hash-a", "目标一"),
            node(8, "target-extra", "/target/extra", "hash-extra", "额外目标")
        );

        DocxNodeMapper.MappingPlan plan = mapper.map(source, target, DocxNodeMapper.MappingMode.CONTROLLED);

        assertThat(plan.mappings()).extracting(DocxNodeMapper.Mapping::sequenceNo)
            .containsExactly(1, 2, 3);
        assertThat(plan.mappings()).extracting(DocxNodeMapper.Mapping::status)
            .containsExactly(
                DocxNodeMapper.MappingStatus.MAPPED,
                DocxNodeMapper.MappingStatus.SOURCE_UNMAPPED,
                DocxNodeMapper.MappingStatus.TARGET_UNMAPPED
            );
    }

    @Test
    void legacyOrdinalModePairsByVisibleParagraphOrderOnlyWhenExplicitlySelected() {
        DocxNodeMapper.Catalog source = catalog(
            node(1, "source-a", "/source/a", "source-hash-a", "源一"),
            node(2, "source-b", "/source/b", "source-hash-b", "源二")
        );
        DocxNodeMapper.Catalog target = catalog(
            node(1, "target-x", "/target/x", "target-hash-x", "目标一")
        );

        DocxNodeMapper.MappingPlan plan = mapper.map(source, target, DocxNodeMapper.MappingMode.LEGACY_ORDINAL);

        assertThat(plan.mode()).isEqualTo(DocxNodeMapper.MappingMode.LEGACY_ORDINAL);
        assertThat(plan.mappedCount()).isOne();
        assertThat(plan.sourceUnmappedCount()).isOne();
        assertThat(plan.mappings().get(0).matchBasis()).isEqualTo(DocxNodeMapper.MatchBasis.ORDINAL);
        assertThat(plan.mappings().get(0).confidence()).isEqualTo(0.5);
        assertThat(plan.mappings().get(0).sourceNodeKey()).isEqualTo("source-a");
        assertThat(plan.mappings().get(0).targetNodeKey()).isEqualTo("target-x");
    }

    private DocxNodeMapper.Catalog catalog(DocxNodeMapper.CatalogNode... nodes) {
        return new DocxNodeMapper.Catalog("word/document.xml", "document-hash", List.of(nodes));
    }

    private DocxNodeMapper.CatalogNode node(int sequence, String key, String path, String hash, String preview) {
        return new DocxNodeMapper.CatalogNode(sequence, key, DocxNodeMapper.LocatorType.STRUCTURAL_PATH,
            path, path, hash, preview, sequence);
    }

    private byte[] docx(String documentXml) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.stripLeading().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
