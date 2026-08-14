package com.coshare.patientrecord.medicalrecord.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Parses and strictly validates the structured content returned by the inpatient-record AI.
 */
@Component
public class InpatientAiResponseParser {

    private static final int MAX_PARAGRAPH_LENGTH = 12000;

    private final ObjectMapper objectMapper;

    public InpatientAiResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedGeneration parse(
        String rawContent,
        int expectedParagraphCount,
        List<String> controlledNodeKeys
    ) throws IOException {
        List<String> expectedNodeKeys = controlledNodeKeys == null
            ? List.of()
            : List.copyOf(controlledNodeKeys);
        if (expectedNodeKeys.isEmpty()) {
            return new ParsedGeneration(
                parseLegacyParagraphs(rawContent, expectedParagraphCount),
                Map.of()
            );
        }
        return parseControlledNodes(rawContent, expectedNodeKeys);
    }

    private ParsedGeneration parseControlledNodes(
        String rawContent,
        List<String> controlledNodeKeys
    ) throws IOException {
        JsonNode root = objectMapper.readTree(stripCodeFence(rawContent));
        JsonNode nodes = root.path("nodes");
        if (!nodes.isArray()) {
            throw badGateway("模型返回格式错误：缺少 nodes 数组");
        }

        Map<String, String> replacements = new LinkedHashMap<>();
        for (JsonNode node : nodes) {
            String key = safe(node.path("key").asText(""));
            if (!controlledNodeKeys.contains(key) || replacements.containsKey(key)) {
                throw badGateway("模型返回了未知或重复的受控节点键");
            }
            if (!node.path("text").isTextual()) {
                throw badGateway("模型返回格式错误：节点 text 必须为文本");
            }
            replacements.put(key, boundedParagraph(node.path("text").asText("")));
        }

        if (replacements.size() != controlledNodeKeys.size()
            || !replacements.keySet().containsAll(controlledNodeKeys)) {
            throw badGateway("模型返回的受控节点键不完整");
        }

        ArrayNode paragraphs = objectMapper.createArrayNode();
        controlledNodeKeys.forEach(key -> paragraphs.add(replacements.get(key)));
        return new ParsedGeneration(paragraphs, replacements);
    }

    private ArrayNode parseLegacyParagraphs(String rawContent, int expectedCount) throws IOException {
        JsonNode paragraphs = objectMapper.readTree(stripCodeFence(rawContent)).path("paragraphs");
        if (!paragraphs.isArray()) {
            throw badGateway("模型返回格式错误：缺少 paragraphs 数组");
        }
        if (paragraphs.size() != expectedCount) {
            throw badGateway(
                "模型返回段落数与参考文档不一致：期望 " + expectedCount
                    + " 段，实际 " + paragraphs.size() + " 段"
            );
        }

        ArrayNode accepted = objectMapper.createArrayNode();
        for (JsonNode paragraph : paragraphs) {
            if (!paragraph.isTextual()) {
                throw badGateway("模型返回格式错误：paragraphs 只能包含文本");
            }
            accepted.add(boundedParagraph(paragraph.asText("")));
        }
        return accepted;
    }

    private String stripCodeFence(String rawContent) {
        String content = safe(rawContent);
        if (content.startsWith("```")) {
            content = content
                .replaceFirst("^```(?:json)?\\s*", "")
                .replaceFirst("\\s*```$", "")
                .trim();
        }
        return content;
    }

    private String boundedParagraph(String raw) {
        String value = safe(raw);
        if (value.length() > MAX_PARAGRAPH_LENGTH) {
            value = value.substring(0, MAX_PARAGRAPH_LENGTH);
        }
        return value.isBlank() ? "待医生补充" : value;
    }

    private ResponseStatusException badGateway(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    private static String safe(Object value) {
        return String.valueOf(value == null ? "" : value).trim();
    }

    public record ParsedGeneration(
        ArrayNode paragraphs,
        Map<String, String> nodeReplacements
    ) {
        public ParsedGeneration {
            nodeReplacements = Map.copyOf(nodeReplacements);
        }
    }
}
