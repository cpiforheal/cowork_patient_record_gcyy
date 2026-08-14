package com.coshare.patientrecord.medicalrecord.ooxml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Builds deterministic paragraph-level node catalogs and controlled source-to-target mappings.
 * Callers must pass a package accepted or produced by {@link DocxPackageSanitizer}.
 */
@Component
public class DocxNodeMapper {

    static final String DOCUMENT_PART = "word/document.xml";
    static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int MAX_DOCUMENT_XML_BYTES = 16 * 1024 * 1024;
    private static final int MAX_NODE_KEY_CHARACTERS = 255;
    private static final int MAX_LOCATOR_CHARACTERS = 1000;
    private static final int MAX_PREVIEW_CHARACTERS = 500;

    public Catalog catalog(byte[] docxBytes) {
        byte[] documentXml = readDocumentXml(docxBytes);
        try {
            Document document = parseXml(documentXml);
            NodeList paragraphs = document.getElementsByTagNameNS(WORD_NS, "p");
            List<CatalogNode> nodes = new ArrayList<>();
            Map<String, Integer> keyOccurrences = new HashMap<>();
            for (int index = 0; index < paragraphs.getLength(); index++) {
                Element paragraph = (Element) paragraphs.item(index);
                String text = visibleText(paragraph);
                if (text.isBlank()) continue;

                Locator locator = preferredLocator(paragraph);
                String contentHash = sha256(text);
                String baseKey = nodeKey(locator, contentHash);
                int occurrence = keyOccurrences.merge(baseKey.toLowerCase(Locale.ROOT), 1, Integer::sum);
                String nodeKey = boundedStableValue(
                    occurrence == 1 ? baseKey : baseKey + "#" + occurrence,
                    MAX_NODE_KEY_CHARACTERS
                );
                nodes.add(new CatalogNode(
                    nodes.size() + 1,
                    nodeKey,
                    locator.type(),
                    locator.value(),
                    locator.structuralPath(),
                    contentHash,
                    preview(text),
                    index + 1
                ));
            }
            return new Catalog(DOCUMENT_PART, sha256(documentXml), List.copyOf(nodes));
        } catch (Exception error) {
            throw new IllegalArgumentException("DOCX 主文档节点无法安全解析：" + error.getMessage(), error);
        }
    }

    public MappingPlan map(byte[] sourceDocx, byte[] targetDocx, MappingMode mode) {
        return map(catalog(sourceDocx), catalog(targetDocx), mode);
    }

    public MappingPlan map(Catalog source, Catalog target, MappingMode mode) {
        Objects.requireNonNull(source, "source catalog is required");
        Objects.requireNonNull(target, "target catalog is required");
        Objects.requireNonNull(mode, "mapping mode is required");
        return mode == MappingMode.LEGACY_ORDINAL
            ? legacyOrdinalPlan(source, target)
            : controlledPlan(source, target);
    }

    private MappingPlan controlledPlan(Catalog source, Catalog target) {
        Map<String, List<CatalogNode>> bySemanticLocator = indexBySemanticLocator(target.nodes());
        Map<String, List<CatalogNode>> byStructure = indexByStructure(target.nodes());
        Map<String, List<CatalogNode>> byHash = indexByHash(target.nodes());
        List<Mapping> mappings = new ArrayList<>();
        java.util.Set<String> claimedTargetKeys = new java.util.HashSet<>();

        for (CatalogNode sourceNode : source.nodes()) {
            Match match = uniqueUnclaimed(bySemanticLocator.get(semanticLocatorKey(sourceNode)), claimedTargetKeys,
                MatchBasis.SEMANTIC_LOCATOR, 1.0000);
            if (match == null) {
                match = uniqueUnclaimed(byStructure.get(structureKey(sourceNode)), claimedTargetKeys,
                    MatchBasis.STRUCTURAL_PATH, 0.9000);
            }
            if (match == null) {
                match = uniqueUnclaimed(byHash.get(sourceNode.contentHash()), claimedTargetKeys,
                    MatchBasis.CONTENT_HASH, 0.7500);
            }
            if (match == null) {
                mappings.add(unmapped(sourceNode, MappingMode.CONTROLLED));
                continue;
            }
            claimedTargetKeys.add(match.target().nodeKey());
            mappings.add(mapped(sourceNode, match.target(), MappingMode.CONTROLLED, match.basis(), match.confidence()));
        }
        appendUnclaimedTargets(target, claimedTargetKeys, mappings, MappingMode.CONTROLLED);
        return plan(MappingMode.CONTROLLED, source, target, mappings);
    }

    private MappingPlan legacyOrdinalPlan(Catalog source, Catalog target) {
        List<Mapping> mappings = new ArrayList<>();
        int paired = Math.min(source.nodes().size(), target.nodes().size());
        for (int index = 0; index < paired; index++) {
            mappings.add(mapped(source.nodes().get(index), target.nodes().get(index), MappingMode.LEGACY_ORDINAL,
                MatchBasis.ORDINAL, 0.5000));
        }
        for (int index = paired; index < source.nodes().size(); index++) {
            mappings.add(unmapped(source.nodes().get(index), MappingMode.LEGACY_ORDINAL));
        }
        java.util.Set<String> claimed = new java.util.HashSet<>();
        for (int index = 0; index < paired; index++) claimed.add(target.nodes().get(index).nodeKey());
        appendUnclaimedTargets(target, claimed, mappings, MappingMode.LEGACY_ORDINAL);
        return plan(MappingMode.LEGACY_ORDINAL, source, target, mappings);
    }

    private MappingPlan plan(MappingMode mode, Catalog source, Catalog target, List<Mapping> mappings) {
        List<Mapping> sequenced = new ArrayList<>(mappings.size());
        for (int index = 0; index < mappings.size(); index++) {
            Mapping value = mappings.get(index);
            sequenced.add(new Mapping(index + 1, value.sourceNodeKey(), value.targetNodeKey(),
                value.sourceLocatorType(), value.sourceLocator(), value.targetLocatorType(), value.targetLocator(),
                value.sourceContentHash(), value.targetContentHash(), value.mappingMode(), value.status(),
                value.matchBasis(), value.confidence(), value.beforePreview(), value.afterPreview()));
        }
        long mapped = sequenced.stream().filter(value -> value.status() == MappingStatus.MAPPED).count();
        long sourceUnmapped = sequenced.stream().filter(value -> value.status() == MappingStatus.SOURCE_UNMAPPED).count();
        long targetUnmapped = sequenced.stream().filter(value -> value.status() == MappingStatus.TARGET_UNMAPPED).count();
        return new MappingPlan(mode, source.documentHash(), target.documentHash(), source.nodes().size(),
            target.nodes().size(), (int) mapped, (int) sourceUnmapped, (int) targetUnmapped, List.copyOf(sequenced));
    }

    private Map<String, List<CatalogNode>> indexBySemanticLocator(List<CatalogNode> nodes) {
        Map<String, List<CatalogNode>> result = new LinkedHashMap<>();
        for (CatalogNode node : nodes) {
            String key = semanticLocatorKey(node);
            if (key != null) result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(node);
        }
        return result;
    }

    private Map<String, List<CatalogNode>> indexByStructure(List<CatalogNode> nodes) {
        Map<String, List<CatalogNode>> result = new LinkedHashMap<>();
        for (CatalogNode node : nodes) {
            result.computeIfAbsent(structureKey(node), ignored -> new ArrayList<>()).add(node);
        }
        return result;
    }

    private Map<String, List<CatalogNode>> indexByHash(List<CatalogNode> nodes) {
        Map<String, List<CatalogNode>> result = new LinkedHashMap<>();
        for (CatalogNode node : nodes) {
            result.computeIfAbsent(node.contentHash(), ignored -> new ArrayList<>()).add(node);
        }
        return result;
    }

    private Match uniqueUnclaimed(List<CatalogNode> candidates, java.util.Set<String> claimed,
                                  MatchBasis basis, double confidence) {
        if (candidates == null) return null;
        List<CatalogNode> available = candidates.stream()
            .filter(value -> !claimed.contains(value.nodeKey()))
            .toList();
        return available.size() == 1 ? new Match(available.get(0), basis, confidence) : null;
    }

    private String semanticLocatorKey(CatalogNode node) {
        if (node.locatorType() != LocatorType.CONTENT_CONTROL && node.locatorType() != LocatorType.BOOKMARK) return null;
        return node.locatorType().name() + ":" + node.locator().toLowerCase(Locale.ROOT);
    }

    private String structureKey(CatalogNode node) {
        return node.structuralPath().toLowerCase(Locale.ROOT);
    }

    private Mapping mapped(CatalogNode source, CatalogNode target, MappingMode mode,
                           MatchBasis basis, double confidence) {
        return new Mapping(source.sequenceNo(), source.nodeKey(), target.nodeKey(), source.locatorType(),
            source.locator(), target.locatorType(), target.locator(), source.contentHash(), target.contentHash(),
            mode, MappingStatus.MAPPED, basis, confidence, source.preview(), target.preview());
    }

    private Mapping unmapped(CatalogNode source, MappingMode mode) {
        return new Mapping(source.sequenceNo(), source.nodeKey(), null, source.locatorType(), source.locator(),
            null, null, source.contentHash(), null, mode, MappingStatus.SOURCE_UNMAPPED, MatchBasis.NONE,
            null, source.preview(), null);
    }

    private void appendUnclaimedTargets(Catalog target, java.util.Set<String> claimed, List<Mapping> mappings,
                                        MappingMode mode) {
        for (CatalogNode targetNode : target.nodes()) {
            if (claimed.contains(targetNode.nodeKey())) continue;
            mappings.add(new Mapping(mappings.size() + 1, null, targetNode.nodeKey(), null, null,
                targetNode.locatorType(), targetNode.locator(), null, targetNode.contentHash(), mode,
                MappingStatus.TARGET_UNMAPPED, MatchBasis.NONE, null, null, targetNode.preview()));
        }
    }

    private Locator preferredLocator(Element paragraph) {
        String path = boundedStableValue(structuralPath(paragraph), MAX_LOCATOR_CHARACTERS);
        Element contentControl = nearestAncestor(paragraph, "sdt");
        if (contentControl != null) {
            Element properties = firstDescendant(contentControl, "sdtPr");
            String tag = wordAttribute(firstDescendant(properties, "tag"), "val");
            String alias = wordAttribute(firstDescendant(properties, "alias"), "val");
            String value = firstNonBlank(tag, alias);
            if (value != null) {
                return new Locator(
                    LocatorType.CONTENT_CONTROL,
                    boundedStableValue(value, MAX_LOCATOR_CHARACTERS),
                    path
                );
            }
        }
        NodeList bookmarks = paragraph.getElementsByTagNameNS(WORD_NS, "bookmarkStart");
        for (int index = 0; index < bookmarks.getLength(); index++) {
            String name = wordAttribute((Element) bookmarks.item(index), "name");
            if (name != null && !name.isBlank() && !name.startsWith("_")) {
                return new Locator(
                    LocatorType.BOOKMARK,
                    boundedStableValue(name, MAX_LOCATOR_CHARACTERS),
                    path
                );
            }
        }
        return new Locator(LocatorType.STRUCTURAL_PATH, path, path);
    }

    private Element nearestAncestor(Element element, String localName) {
        Node current = element;
        while (current != null && current.getNodeType() == Node.ELEMENT_NODE) {
            Element currentElement = (Element) current;
            if (WORD_NS.equals(currentElement.getNamespaceURI()) && localName.equals(currentElement.getLocalName())) {
                return currentElement;
            }
            current = current.getParentNode();
        }
        return null;
    }

    private Element firstDescendant(Element parent, String localName) {
        if (parent == null) return null;
        NodeList nodes = parent.getElementsByTagNameNS(WORD_NS, localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private String wordAttribute(Element element, String localName) {
        if (element == null) return null;
        String value = element.getAttributeNS(WORD_NS, localName);
        if (value == null || value.isBlank()) value = element.getAttribute("w:" + localName);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String structuralPath(Element element) {
        List<String> segments = new ArrayList<>();
        Node current = element;
        while (current instanceof Element currentElement) {
            String localName = currentElement.getLocalName();
            if (WORD_NS.equals(currentElement.getNamespaceURI()) && localName != null) {
                int position = siblingPosition(currentElement);
                segments.add(0, localName + "[" + position + "]");
                if ("document".equals(localName)) break;
            }
            current = current.getParentNode();
        }
        return "/" + String.join("/", segments);
    }

    private int siblingPosition(Element element) {
        int position = 1;
        Node sibling = element.getPreviousSibling();
        while (sibling != null) {
            if (sibling instanceof Element siblingElement
                && Objects.equals(element.getNamespaceURI(), siblingElement.getNamespaceURI())
                && Objects.equals(element.getLocalName(), siblingElement.getLocalName())) position++;
            sibling = sibling.getPreviousSibling();
        }
        return position;
    }

    private String visibleText(Element paragraph) {
        StringBuilder result = new StringBuilder();
        appendVisibleText(paragraph, result);
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

    private String nodeKey(Locator locator, String contentHash) {
        String stable = switch (locator.type()) {
            case CONTENT_CONTROL -> "cc:" + locator.value();
            case BOOKMARK -> "bm:" + locator.value();
            case STRUCTURAL_PATH -> "path:" + locator.value() + ":" + contentHash.substring(0, 16);
        };
        return boundedStableValue(stable, MAX_NODE_KEY_CHARACTERS);
    }

    private String boundedStableValue(String value, int maxCharacters) {
        if (value.length() <= maxCharacters) return value;
        String hashSuffix = ":sha256:" + sha256(value);
        return value.substring(0, maxCharacters - hashSuffix.length()) + hashSuffix;
    }

    private String preview(String value) {
        return value.length() <= MAX_PREVIEW_CHARACTERS ? value : value.substring(0, MAX_PREVIEW_CHARACTERS) + "…";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }

    private byte[] readDocumentXml(byte[] docxBytes) {
        if (docxBytes == null || docxBytes.length == 0) throw new IllegalArgumentException("DOCX 内容为空");
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docxBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!DOCUMENT_PART.equals(entry.getName())) continue;
                byte[] bytes = zip.readNBytes(MAX_DOCUMENT_XML_BYTES + 1);
                if (bytes.length > MAX_DOCUMENT_XML_BYTES) throw new IllegalArgumentException("DOCX 主文档 XML 超过节点解析限制");
                return bytes;
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("DOCX 包无法读取：" + error.getMessage(), error);
        }
        throw new IllegalArgumentException("DOCX 缺少主文档部件");
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

    private String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte current : digest) output.append(String.format("%02x", current));
            return output.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    public enum LocatorType { CONTENT_CONTROL, BOOKMARK, STRUCTURAL_PATH }
    public enum MappingMode { CONTROLLED, LEGACY_ORDINAL }
    public enum MappingStatus { MAPPED, SOURCE_UNMAPPED, TARGET_UNMAPPED }
    public enum MatchBasis { SEMANTIC_LOCATOR, STRUCTURAL_PATH, CONTENT_HASH, ORDINAL, NONE }

    public record Catalog(String partName, String documentHash, List<CatalogNode> nodes) {
        public Catalog { nodes = List.copyOf(nodes); }
    }

    public record CatalogNode(int sequenceNo, String nodeKey, LocatorType locatorType, String locator,
                              String structuralPath, String contentHash, String preview, int legacyOrdinal) {}

    public record Mapping(int sequenceNo, String sourceNodeKey, String targetNodeKey,
                          LocatorType sourceLocatorType, String sourceLocator,
                          LocatorType targetLocatorType, String targetLocator,
                          String sourceContentHash, String targetContentHash,
                          MappingMode mappingMode, MappingStatus status, MatchBasis matchBasis,
                          Double confidence, String beforePreview, String afterPreview) {}

    public record MappingPlan(MappingMode mode, String sourceDocumentHash, String targetDocumentHash,
                              int sourceNodeCount, int targetNodeCount, int mappedCount,
                              int sourceUnmappedCount, int targetUnmappedCount, List<Mapping> mappings) {
        public MappingPlan { mappings = List.copyOf(mappings); }
    }

    private record Locator(LocatorType type, String value, String structuralPath) {}
    private record Match(CatalogNode target, MatchBasis basis, double confidence) {}
}
