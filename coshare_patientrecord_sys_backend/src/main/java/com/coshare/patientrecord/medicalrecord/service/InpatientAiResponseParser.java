package com.coshare.patientrecord.medicalrecord.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Parses and strictly validates the structured content returned by the inpatient-record AI.
 */
@Component
public class InpatientAiResponseParser {

    private static final int MAX_PARAGRAPH_LENGTH = 12000;

    /**
     * 模型常把“面向系统的说明”写进病历正文（如“当前复核事实为门诊就诊”“未提供住院及手术计划”
     * “本段待医生补充”、把签名行并入正文末尾）。这些不是病历语言，入盘前统一剔除。
     */
    private static final Pattern[] META_COMMENTARY_PATTERNS = {
        // 括号内的生成说明：（当前复核事实为…）（未提供…）
        Pattern.compile("（[^（）]{0,60}(?:当前复核事实|复核事实|未提供|无相关)[^（）]{0,60}）"),
        // “复核事实”只会出现在面向系统的说明里，整句剔除到下一个标点
        Pattern.compile("(?:当前|目前)?复核事实[^。，；,;）)]{0,40}[。，；,;）)]?"),
        // “未提供/无相关 + 事实/依据/计划/记录/处方”说明从句；不含这些落点的临床叙述（如“未提供过敏史”）不受影响
        Pattern.compile("(?:当前|目前)?(?:未提供|无相关)[^。，；,;）)]{0,16}(?:事实|依据|计划|记录|处方)[。，；,;）)]?"),
        // “本段待医生补充”收敛为占位符本身
        Pattern.compile("(?:本段|该段)[。；，,;]?\\s*待医生补充[。；，,;]?"),
        // 并入正文末尾的签名行（仅吞并其前的空白，不动句子自身的结尾标点）
        Pattern.compile("\\s*(?:副?主?任?医师|手术医师|上级医师)?签(?:名|字)[:：]\\s*$")
    };

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
        if (isNumberedParagraphArray(paragraphs)) {
            return parseNumberedParagraphs(paragraphs, expectedCount);
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

    private boolean isNumberedParagraphArray(JsonNode paragraphs) {
        return paragraphs.size() > 0 && paragraphs.path(0).isObject() && paragraphs.path(0).has("n");
    }

    /**
     * 编号回填契约：模型按 {"n":段落号,"text":...} 返回，允许存在缺口——
     * 未覆盖的段落以“待医生补充”占位而不是整体失败，避免逐段错位写入。
     */
    private ArrayNode parseNumberedParagraphs(JsonNode paragraphs, int expectedCount) {
        String[] slots = new String[expectedCount];
        boolean[] filled = new boolean[expectedCount];
        for (JsonNode item : paragraphs) {
            int index = item.path("n").asInt(0);
            if (index < 1 || index > expectedCount) {
                throw badGateway("模型返回了超出范围的段落编号：" + index);
            }
            if (filled[index - 1]) {
                throw badGateway("模型返回了重复的段落编号：" + index);
            }
            if (!item.path("text").isTextual()) {
                throw badGateway("模型返回格式错误：编号段落的 text 必须为文本");
            }
            slots[index - 1] = boundedParagraph(item.path("text").asText(""));
            filled[index - 1] = true;
        }
        int covered = 0;
        for (boolean flag : filled) if (flag) covered++;
        if (expectedCount > 0 && covered * 2 < expectedCount) {
            throw badGateway(
                "模型仅覆盖 " + covered + "/" + expectedCount + " 段（低于一半），内容不可用，请重试"
            );
        }
        ArrayNode accepted = objectMapper.createArrayNode();
        for (int i = 0; i < expectedCount; i++) {
            accepted.add(filled[i] ? slots[i] : "待医生补充");
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
        value = stripMetaCommentary(value);
        return value.isBlank() ? "待医生补充" : value;
    }

    private String stripMetaCommentary(String value) {
        String result = value;
        for (Pattern pattern : META_COMMENTARY_PATTERNS) {
            result = pattern.matcher(result).replaceAll("");
        }
        return result.strip();
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
