package com.coshare.patientrecord.medicalrecord.ooxml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Applies explicit paragraph-node updates to an accepted DOCX package while retaining the
 * surrounding OOXML structure, paragraph properties, runs and run properties.
 */
@Component
public class DocxControlledEditor {

    private static final String DOCUMENT_PART = DocxNodeMapper.DOCUMENT_PART;
    private static final String WORD_NS = DocxNodeMapper.WORD_NS;
    private static final String XML_NS = XMLConstants.XML_NS_URI;
    private static final int MAX_DOCUMENT_XML_BYTES = 16 * 1024 * 1024;

    private final DocxNodeMapper nodeMapper;

    public DocxControlledEditor(DocxNodeMapper nodeMapper) {
        this.nodeMapper = nodeMapper;
    }

    /**
     * Replaces only explicitly addressed catalog nodes. No ordinal or content-similarity fallback is used.
     */
    public EditResult edit(byte[] sourceDocx, Map<String, String> replacementsByNodeKey) {
        Objects.requireNonNull(sourceDocx, "source DOCX is required");
        Objects.requireNonNull(replacementsByNodeKey, "node replacements are required");
        if (replacementsByNodeKey.isEmpty()) {
            throw new IllegalArgumentException("受控节点替换内容不能为空");
        }

        DocxNodeMapper.Catalog catalog = nodeMapper.catalog(sourceDocx);
        Map<String, DocxNodeMapper.CatalogNode> requested = validateRequests(catalog, replacementsByNodeKey);

        try (ZipInputStream input = new ZipInputStream(
                 new ByteArrayInputStream(sourceDocx), StandardCharsets.UTF_8
             );
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zipOutput = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            boolean editedDocument = false;
            int updatedCount = 0;
            while ((entry = input.getNextEntry()) != null) {
                boolean documentPart = DOCUMENT_PART.equals(entry.getName());
                byte[] bytes = documentPart
                    ? input.readNBytes(MAX_DOCUMENT_XML_BYTES + 1)
                    : input.readAllBytes();
                if (documentPart) {
                    if (bytes.length > MAX_DOCUMENT_XML_BYTES) {
                        throw new IllegalArgumentException("DOCX 主文档 XML 超过受控编辑限制");
                    }
                    Document document = parseXml(bytes);
                    NodeList paragraphs = document.getElementsByTagNameNS(WORD_NS, "p");
                    List<Element> visibleParagraphs = visibleParagraphs(paragraphs);
                    if (visibleParagraphs.size() != catalog.nodes().size()) {
                        throw new IllegalArgumentException("DOCX 节点目录与编辑文档不一致");
                    }
                    for (int index = 0; index < catalog.nodes().size(); index++) {
                        DocxNodeMapper.CatalogNode node = catalog.nodes().get(index);
                        if (!requested.containsKey(node.nodeKey())) continue;
                        replaceTextPreservingRuns(
                            visibleParagraphs.get(index),
                            replacementsByNodeKey.get(node.nodeKey())
                        );
                        updatedCount++;
                    }
                    bytes = serialize(document);
                    editedDocument = true;
                }
                ZipEntry copied = new ZipEntry(entry.getName());
                copied.setComment(entry.getComment());
                if (entry.getExtra() != null) copied.setExtra(entry.getExtra());
                if (entry.getTime() >= 0) copied.setTime(entry.getTime());
                zipOutput.putNextEntry(copied);
                zipOutput.write(bytes);
                zipOutput.closeEntry();
                input.closeEntry();
            }
            if (!editedDocument) throw new IllegalArgumentException("DOCX 缺少主文档部件");
            zipOutput.finish();
            return new EditResult(output.toByteArray(), catalog.documentHash(), updatedCount,
                List.copyOf(replacementsByNodeKey.keySet()));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("DOCX 受控节点编辑失败：" + error.getMessage(), error);
        }
    }

    private Map<String, DocxNodeMapper.CatalogNode> validateRequests(
        DocxNodeMapper.Catalog catalog,
        Map<String, String> replacements
    ) {
        Map<String, DocxNodeMapper.CatalogNode> available = new LinkedHashMap<>();
        for (DocxNodeMapper.CatalogNode node : catalog.nodes()) available.put(node.nodeKey(), node);
        Map<String, DocxNodeMapper.CatalogNode> requested = new LinkedHashMap<>();
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            if (replacement.getKey() == null || replacement.getKey().isBlank()) {
                throw new IllegalArgumentException("受控节点键不能为空");
            }
            DocxNodeMapper.CatalogNode node = available.get(replacement.getKey());
            if (node == null) {
                throw new IllegalArgumentException("受控节点不存在或文档已变化：" + replacement.getKey());
            }
            if (replacement.getValue() == null) {
                throw new IllegalArgumentException("受控节点替换值不能为 null：" + replacement.getKey());
            }
            requested.put(replacement.getKey(), node);
        }
        return requested;
    }

    private List<Element> visibleParagraphs(NodeList paragraphs) {
        List<Element> result = new ArrayList<>();
        for (int index = 0; index < paragraphs.getLength(); index++) {
            Element paragraph = (Element) paragraphs.item(index);
            if (!visibleText(paragraph).isBlank()) result.add(paragraph);
        }
        return result;
    }

    private void replaceTextPreservingRuns(Element paragraph, String replacement) {
        List<Element> textNodes = descendants(paragraph, "t");
        if (textNodes.isEmpty()) {
            throw new IllegalArgumentException("受控节点没有可安全更新的文本 run");
        }
        int consumed = 0;
        for (int index = 0; index < textNodes.size(); index++) {
            Element textNode = textNodes.get(index);
            String original = textNode.getTextContent();
            int remaining = replacement.length() - consumed;
            int length = index == textNodes.size() - 1
                ? Math.max(remaining, 0)
                : Math.min(Math.max(remaining, 0), original.length());
            String value = length == 0 ? "" : replacement.substring(consumed, consumed + length);
            consumed += length;
            textNode.setTextContent(value);
            applySpacePreservation(textNode, value);
        }
    }

    private void applySpacePreservation(Element textNode, String value) {
        boolean preserve = !value.isEmpty()
            && (Character.isWhitespace(value.charAt(0)) || Character.isWhitespace(value.charAt(value.length() - 1)));
        if (preserve) textNode.setAttributeNS(XML_NS, "xml:space", "preserve");
        else textNode.removeAttributeNS(XML_NS, "space");
    }

    private List<Element> descendants(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS(WORD_NS, localName);
        List<Element> result = new ArrayList<>(nodes.getLength());
        for (int index = 0; index < nodes.getLength(); index++) result.add((Element) nodes.item(index));
        return result;
    }

    private String visibleText(Node node) {
        StringBuilder result = new StringBuilder();
        appendVisibleText(node, result);
        return result.toString().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private void appendVisibleText(Node node, StringBuilder result) {
        if (node instanceof Element element && WORD_NS.equals(element.getNamespaceURI())) {
            if ("t".equals(element.getLocalName()) || "instrText".equals(element.getLocalName())) {
                result.append(element.getTextContent());
                return;
            }
            if ("tab".equals(element.getLocalName())) result.append('\t');
            if ("br".equals(element.getLocalName()) || "cr".equals(element.getLocalName())) result.append('\n');
        }
        NodeList children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) appendVisibleText(children.item(index), result);
    }

    private Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private byte[] serialize(Document document) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }

    public record EditResult(byte[] docxBytes, String sourceDocumentHash, int updatedNodeCount,
                             List<String> updatedNodeKeys) {
        public EditResult {
            docxBytes = docxBytes.clone();
            updatedNodeKeys = List.copyOf(updatedNodeKeys);
        }

        @Override
        public byte[] docxBytes() {
            return docxBytes.clone();
        }
    }
}
