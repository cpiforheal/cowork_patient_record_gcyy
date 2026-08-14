package com.coshare.patientrecord.medicalrecord.ooxml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
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
 * Performs bounded, dependency-free inspection and sanitization of uploaded DOCX packages.
 * The sanitizer never expands an entry without first applying per-entry and package budgets.
 */
@Component
public class DocxPackageSanitizer {

    static final String CONTENT_TYPES = "[Content_Types].xml";
    static final String ROOT_RELS = "_rels/.rels";
    static final String DOCUMENT = "word/document.xml";
    static final String REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    static final String CONTENT_TYPE_NS = "http://schemas.openxmlformats.org/package/2006/content-types";

    private static final Set<String> DANGEROUS_CONTENT_TYPES = Set.of(
        "application/vnd.ms-office.vbaproject",
        "application/vnd.ms-office.activeX+xml".toLowerCase(Locale.ROOT),
        "application/vnd.openxmlformats-officedocument.oleobject",
        "application/vnd.ms-office.activex",
        "application/vnd.ms-package.oleobject"
    );
    private static final Set<String> ALLOWED_RELATIONSHIP_NAMES = Set.of(
        "officeDocument", "styles", "stylesWithEffects", "numbering", "settings", "theme",
        "fontTable", "webSettings", "header", "footer", "image", "hyperlink", "footnotes",
        "endnotes", "comments", "commentsExtended", "commentsIds", "people", "glossaryDocument",
        "customXml", "customXmlProps", "core-properties", "extended-properties", "custom-properties",
        "thumbnail"
    );

    private final Limits limits;

    public DocxPackageSanitizer() {
        this(Limits.defaults());
    }

    DocxPackageSanitizer(Limits limits) {
        this.limits = limits;
    }

    public Result inspectAndSanitize(byte[] source) {
        if (source == null || source.length == 0) {
            return rejected("EMPTY_PACKAGE", "DOCX 文件为空", 0, 0, 0, List.of());
        }
        if (source.length > limits.maxArchiveBytes()) {
            return rejected("ARCHIVE_TOO_LARGE", "DOCX 压缩包超过允许大小", 0, source.length, 0, List.of());
        }

        ReadResult read;
        try {
            read = readBounded(source);
        } catch (BudgetException error) {
            return rejected(error.code, error.getMessage(), error.entryCount, source.length, error.totalBytes, error.findings);
        } catch (IOException error) {
            return rejected("INVALID_ZIP", "DOCX ZIP 包无法读取：" + error.getMessage(), 0, source.length, 0, List.of());
        }

        List<Finding> findings = new ArrayList<>(read.findings());
        PackageValidation originalValidation = validatePackage(read.entries(), findings);
        if (!originalValidation.valid()) {
            return new Result(Decision.REJECTED, RiskLevel.CRITICAL, read.entryCount(), source.length,
                read.totalBytes(), 0, 0, countExternal(findings), findings, originalValidation, null);
        }

        Map<String, byte[]> sanitized = new LinkedHashMap<>(read.entries());
        Set<String> removedParts = removeDangerousParts(sanitized, findings);
        int removedRelationships;
        int removedContentTypes;
        try {
            removedRelationships = sanitizeRelationships(sanitized, removedParts, findings);
            removedContentTypes = sanitizeContentTypes(sanitized, removedParts, findings);
        } catch (Exception error) {
            findings.add(new Finding("SANITIZATION_FAILED", RiskLevel.CRITICAL, "", error.getMessage()));
            return new Result(Decision.REJECTED, RiskLevel.CRITICAL, read.entryCount(), source.length,
                read.totalBytes(), removedParts.size(), 0, countExternal(findings), findings,
                new PackageValidation(false, List.of("净化处理失败")), null);
        }

        PackageValidation sanitizedValidation = validatePackage(sanitized, findings);
        if (!sanitizedValidation.valid()) {
            findings.add(new Finding("POST_SANITIZATION_INVALID", RiskLevel.CRITICAL, "", "净化后 OOXML 包完整性校验失败"));
            return new Result(Decision.REJECTED, RiskLevel.CRITICAL, read.entryCount(), source.length,
                read.totalBytes(), removedParts.size(), removedRelationships, countExternal(findings),
                findings, sanitizedValidation, null);
        }

        boolean changed = !removedParts.isEmpty() || removedRelationships > 0 || removedContentTypes > 0;
        byte[] output;
        try {
            output = changed ? writePackage(sanitized) : source.clone();
        } catch (IOException error) {
            findings.add(new Finding("SANITIZED_PACKAGE_WRITE_FAILED", RiskLevel.CRITICAL, "", error.getMessage()));
            return new Result(Decision.REJECTED, RiskLevel.CRITICAL, read.entryCount(), source.length,
                read.totalBytes(), removedParts.size(), removedRelationships, countExternal(findings),
                findings, sanitizedValidation, null);
        }
        RiskLevel risk = highestRisk(findings);
        return new Result(changed ? Decision.SANITIZED : Decision.ACCEPTED, risk, read.entryCount(),
            source.length, read.totalBytes(), removedParts.size(), removedRelationships,
            countExternal(findings), List.copyOf(findings), sanitizedValidation, output);
    }

    private ReadResult readBounded(byte[] source) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        List<Finding> findings = new ArrayList<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(source), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                count++;
                if (count > limits.maxEntries()) {
                    throw new BudgetException("ENTRY_COUNT_EXCEEDED", "DOCX ZIP 条目数超过限制", count, total, findings);
                }
                String name = canonicalEntryName(entry.getName());
                if (name == null) {
                    findings.add(new Finding("UNSAFE_ENTRY_NAME", RiskLevel.CRITICAL, entry.getName(), "ZIP 条目路径不安全"));
                    throw new BudgetException("UNSAFE_ENTRY_NAME", "DOCX 包含不安全路径", count, total, findings);
                }
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                String key = name.toLowerCase(Locale.ROOT);
                if (entries.keySet().stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(key::equals)) {
                    findings.add(new Finding("DUPLICATE_ENTRY", RiskLevel.CRITICAL, name, "ZIP 包含重复条目"));
                    throw new BudgetException("DUPLICATE_ENTRY", "DOCX ZIP 包含重复条目", count, total, findings);
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                long entryBytes = 0;
                while ((read = zip.read(buffer)) != -1) {
                    entryBytes += read;
                    total += read;
                    if (entryBytes > limits.maxEntryBytes()) {
                        throw new BudgetException("ENTRY_TOO_LARGE", "DOCX ZIP 单条目超过限制", count, total, findings);
                    }
                    if (total > limits.maxUncompressedBytes()) {
                        throw new BudgetException("PACKAGE_EXPANSION_EXCEEDED", "DOCX ZIP 总解压量超过限制", count, total, findings);
                    }
                    output.write(buffer, 0, read);
                }
                entries.put(name, output.toByteArray());
                zip.closeEntry();
            }
        } catch (ZipException error) {
            throw new IOException(error.getMessage(), error);
        }
        if (count == 0) throw new IOException("ZIP 包不含条目");
        double ratio = total / (double) Math.max(source.length, 1);
        if (ratio > limits.maxCompressionRatio()) {
            findings.add(new Finding("COMPRESSION_RATIO_EXCEEDED", RiskLevel.CRITICAL, "", "ZIP 压缩比超过限制"));
            throw new BudgetException("COMPRESSION_RATIO_EXCEEDED", "DOCX ZIP 压缩比超过限制", count, total, findings);
        }
        return new ReadResult(Collections.unmodifiableMap(entries), List.copyOf(findings), count, total);
    }

    private PackageValidation validatePackage(Map<String, byte[]> entries, List<Finding> findings) {
        List<String> errors = new ArrayList<>();
        for (String required : List.of(CONTENT_TYPES, ROOT_RELS, DOCUMENT)) {
            if (!entries.containsKey(required)) errors.add("缺少必要部件：" + required);
        }
        if (!errors.isEmpty()) {
            findings.add(new Finding("MISSING_REQUIRED_PART", RiskLevel.CRITICAL, "", String.join("；", errors)));
            return new PackageValidation(false, List.copyOf(errors));
        }
        try {
            Document contentTypes = parseXml(entries.get(CONTENT_TYPES));
            boolean hasDocumentType = false;
            NodeList overrides = contentTypes.getElementsByTagNameNS(CONTENT_TYPE_NS, "Override");
            for (int i = 0; i < overrides.getLength(); i++) {
                Element element = (Element) overrides.item(i);
                if ("/word/document.xml".equals(element.getAttribute("PartName"))
                    && element.getAttribute("ContentType").contains("wordprocessingml.document.main+xml")) {
                    hasDocumentType = true;
                }
            }
            if (!hasDocumentType) errors.add("主文档内容类型声明缺失");

            Document rootRels = parseXml(entries.get(ROOT_RELS));
            boolean hasOfficeDocument = false;
            NodeList relationships = rootRels.getElementsByTagNameNS(REL_NS, "Relationship");
            for (int i = 0; i < relationships.getLength(); i++) {
                Element element = (Element) relationships.item(i);
                if ("officeDocument".equals(relationshipName(element.getAttribute("Type")))
                    && "word/document.xml".equals(canonicalRelationshipTarget("", element.getAttribute("Target")))) {
                    hasOfficeDocument = true;
                }
            }
            if (!hasOfficeDocument) errors.add("根关系未指向 word/document.xml");
            parseXml(entries.get(DOCUMENT));
        } catch (Exception error) {
            errors.add("OOXML XML 无法安全解析：" + error.getMessage());
        }
        if (!errors.isEmpty()) findings.add(new Finding("INVALID_PACKAGE_GRAPH", RiskLevel.CRITICAL, "", String.join("；", errors)));
        return new PackageValidation(errors.isEmpty(), List.copyOf(errors));
    }

    private Set<String> removeDangerousParts(Map<String, byte[]> entries, List<Finding> findings) {
        Set<String> removed = new HashSet<>();
        List<String> names = new ArrayList<>(entries.keySet());
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("word/vbaproject.bin") || lower.startsWith("word/activex/")
                || lower.startsWith("word/embeddings/") || lower.startsWith("customui/")
                || lower.startsWith("word/externallinks/")) {
                entries.remove(name);
                removed.add(name);
                findings.add(new Finding("DANGEROUS_PART_REMOVED", RiskLevel.HIGH, name, "已移除宏、ActiveX、嵌入对象或外部链接部件"));
            }
        }
        return removed;
    }

    private int sanitizeRelationships(Map<String, byte[]> entries, Set<String> removedParts, List<Finding> findings) throws Exception {
        int removed = 0;
        for (String name : new ArrayList<>(entries.keySet())) {
            if (!name.endsWith(".rels")) continue;
            Document document = parseXml(entries.get(name));
            NodeList nodes = document.getElementsByTagNameNS(REL_NS, "Relationship");
            List<Element> relationships = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) relationships.add((Element) nodes.item(i));
            String sourceDirectory = relationshipSourceDirectory(name);
            for (Element relationship : relationships) {
                String typeName = relationshipName(relationship.getAttribute("Type"));
                String targetMode = relationship.getAttribute("TargetMode");
                String target = relationship.getAttribute("Target");
                String resolved = canonicalRelationshipTarget(sourceDirectory, target);
                boolean external = "External".equalsIgnoreCase(targetMode);
                boolean unsafeType = !ALLOWED_RELATIONSHIP_NAMES.contains(typeName);
                boolean removedTarget = resolved == null || removedParts.stream().anyMatch(value -> value.equalsIgnoreCase(resolved));
                boolean dangerousType = Set.of("oleObject", "package", "control", "attachedTemplate", "externalLink", "vbaProject").contains(typeName);
                if (external || unsafeType || removedTarget || dangerousType) {
                    relationship.getParentNode().removeChild(relationship);
                    removed++;
                    String code = external ? "EXTERNAL_RELATIONSHIP_REMOVED" : "UNSAFE_RELATIONSHIP_REMOVED";
                    findings.add(new Finding(code, RiskLevel.HIGH, name, "已移除关系：" + typeName + " -> " + target));
                }
            }
            entries.put(name, writeXml(document));
        }
        return removed;
    }

    private int sanitizeContentTypes(Map<String, byte[]> entries, Set<String> removedParts, List<Finding> findings) throws Exception {
        Document document = parseXml(entries.get(CONTENT_TYPES));
        int removed = 0;
        NodeList children = document.getDocumentElement().getChildNodes();
        List<Element> declarations = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element) declarations.add(element);
        }
        for (Element declaration : declarations) {
            String contentType = declaration.getAttribute("ContentType").toLowerCase(Locale.ROOT);
            String partName = declaration.getAttribute("PartName");
            String normalizedPart = partName.startsWith("/") ? partName.substring(1) : partName;
            boolean removedPart = removedParts.stream().anyMatch(value -> value.equalsIgnoreCase(normalizedPart));
            boolean dangerous = DANGEROUS_CONTENT_TYPES.contains(contentType)
                || contentType.contains("macroenabled") || contentType.contains("activex") || contentType.contains("oleobject");
            if (removedPart || dangerous) {
                declaration.getParentNode().removeChild(declaration);
                removed++;
                findings.add(new Finding("CONTENT_TYPE_REMOVED", RiskLevel.HIGH, partName, "已移除危险内容类型声明"));
            }
        }
        entries.put(CONTENT_TYPES, writeXml(document));
        return removed;
    }

    private Document parseXml(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length > limits.maxXmlBytes()) throw new IOException("XML 部件超过限制");
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

    private byte[] writeXml(Document document) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }

    private byte[] writePackage(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private String canonicalEntryName(String raw) {
        if (raw == null || raw.isBlank() || hasControlCharacter(raw) || raw.contains("\\")
            || raw.startsWith("/") || raw.matches("^[A-Za-z]:.*")) return null;
        String[] segments = raw.split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty() && index == segments.length - 1) continue;
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return null;
        }
        return raw;
    }

    private String canonicalRelationshipTarget(String sourceDirectory, String target) {
        if (target == null || target.isBlank() || hasControlCharacter(target) || target.contains("\\")
            || target.startsWith("/") || target.contains("?") || target.contains("#")
            || target.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) return null;

        Deque<String> segments = new ArrayDeque<>();
        if (sourceDirectory != null && !sourceDirectory.isEmpty()) {
            for (String segment : sourceDirectory.split("/")) {
                if (!segment.isEmpty()) segments.addLast(segment);
            }
        }
        for (String segment : target.split("/", -1)) {
            if (segment.isEmpty()) return null;
            if (".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (segments.isEmpty()) return null;
                segments.removeLast();
                continue;
            }
            segments.addLast(segment);
        }
        return segments.isEmpty() ? null : String.join("/", segments);
    }

    private boolean hasControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private String relationshipSourceDirectory(String relationshipPart) {
        if (ROOT_RELS.equals(relationshipPart)) return "";
        int relsMarker = relationshipPart.lastIndexOf("/_rels/");
        if (relsMarker < 0 || !relationshipPart.endsWith(".rels")) return "";
        return relationshipPart.substring(0, relsMarker + 1);
    }

    private String relationshipName(String type) {
        int slash = type == null ? -1 : Math.max(type.lastIndexOf('/'), type.lastIndexOf('#'));
        return slash >= 0 ? type.substring(slash + 1) : String.valueOf(type);
    }

    private int countExternal(List<Finding> findings) {
        return (int) findings.stream().filter(value -> "EXTERNAL_RELATIONSHIP_REMOVED".equals(value.code())).count();
    }

    private RiskLevel highestRisk(List<Finding> findings) {
        return findings.stream().map(Finding::risk).max(Enum::compareTo).orElse(RiskLevel.NONE);
    }

    private Result rejected(String code, String message, int entries, long compressed, long uncompressed, List<Finding> existing) {
        List<Finding> findings = new ArrayList<>(existing);
        findings.add(new Finding(code, RiskLevel.CRITICAL, "", message));
        return new Result(Decision.REJECTED, RiskLevel.CRITICAL, entries, compressed, uncompressed,
            0, 0, countExternal(findings), List.copyOf(findings), new PackageValidation(false, List.of(message)), null);
    }

    public enum Decision { ACCEPTED, SANITIZED, REJECTED }
    public enum RiskLevel { NONE, LOW, MEDIUM, HIGH, CRITICAL }

    public record Limits(long maxArchiveBytes, int maxEntries, long maxEntryBytes,
                         long maxUncompressedBytes, double maxCompressionRatio, long maxXmlBytes) {
        public Limits {
            if (maxArchiveBytes <= 0 || maxEntries <= 0 || maxEntryBytes <= 0 || maxUncompressedBytes <= 0
                || maxCompressionRatio <= 0 || maxXmlBytes <= 0) throw new IllegalArgumentException("OOXML budgets must be positive");
        }
        public static Limits defaults() {
            return new Limits(25L * 1024 * 1024, 2048, 32L * 1024 * 1024,
                128L * 1024 * 1024, 100.0, 16L * 1024 * 1024);
        }
    }

    public record Finding(String code, RiskLevel risk, String partName, String message) {}
    public record PackageValidation(boolean valid, List<String> errors) {}
    public record Result(Decision decision, RiskLevel highestRisk, int zipEntryCount, long compressedSize,
                         long uncompressedSize, int removedPartCount, int removedRelationshipCount,
                         int externalRelationshipCount, List<Finding> findings,
                         PackageValidation packageValidation, byte[] sanitizedBytes) {
        public Result {
            findings = List.copyOf(findings);
            sanitizedBytes = sanitizedBytes == null ? null : sanitizedBytes.clone();
        }
        @Override public byte[] sanitizedBytes() { return sanitizedBytes == null ? null : sanitizedBytes.clone(); }
    }

    private record ReadResult(Map<String, byte[]> entries, List<Finding> findings, int entryCount, long totalBytes) {}

    private static final class BudgetException extends IOException {
        private final String code;
        private final int entryCount;
        private final long totalBytes;
        private final List<Finding> findings;
        private BudgetException(String code, String message, int entryCount, long totalBytes, List<Finding> findings) {
            super(message);
            this.code = code;
            this.entryCount = entryCount;
            this.totalBytes = totalBytes;
            this.findings = List.copyOf(findings);
        }
    }
}
