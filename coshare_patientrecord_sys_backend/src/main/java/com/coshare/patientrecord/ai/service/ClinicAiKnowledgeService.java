package com.coshare.patientrecord.ai.service;

import com.coshare.patientrecord.ai.model.KnowledgeItem;
import com.coshare.patientrecord.ai.model.KnowledgeSelection;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class ClinicAiKnowledgeService {

    private static final String KNOWLEDGE_PATH = "ai-knowledge/clinic-system-knowledge.json";
    private static final int MAX_SELECTED = 8;
    private static final int MIN_SELECTED = 3;

    private final ObjectMapper objectMapper;
    private final List<KnowledgeItem> items;

    public ClinicAiKnowledgeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.items = loadItems();
    }

    public KnowledgeSelection select(String assistantType, String prompt, String role, Map<String, Object> context) {
        Set<String> signals = new LinkedHashSet<>();
        addSignal(signals, assistantType);
        addSignal(signals, role);
        addContextSignals(signals, context);

        String question = safe(prompt).toLowerCase(Locale.ROOT);
        List<ScoredKnowledge> scored = new ArrayList<>();
        for (KnowledgeItem item : items) {
            int score = scoreItem(item, signals, question);
            if (score > 0) scored.add(new ScoredKnowledge(item, score));
        }

        List<KnowledgeItem> selected = scored.stream()
            .sorted(Comparator.comparingInt(ScoredKnowledge::score).reversed().thenComparing(item -> item.item().id()))
            .limit(MAX_SELECTED)
            .map(ScoredKnowledge::item)
            .toList();

        if (selected.size() < MIN_SELECTED) {
            LinkedHashSet<KnowledgeItem> merged = new LinkedHashSet<>(selected);
            items.stream()
                .filter(item -> item.tags().contains("system") || item.tags().contains("ai") || item.tags().contains("public"))
                .limit(MIN_SELECTED)
                .forEach(merged::add);
            selected = merged.stream().limit(MAX_SELECTED).toList();
        }

        return new KnowledgeSelection(selected, selected.stream().map(KnowledgeItem::title).toList());
    }

    private int scoreItem(KnowledgeItem item, Set<String> signals, String question) {
        int score = 0;
        for (String tag : safeTags(item)) {
            if (signals.contains(tag)) score += 8;
        }
        String haystack = (safe(item.title()) + " " + safe(item.content()) + " " + String.join(" ", safeTags(item))).toLowerCase(Locale.ROOT);
        for (String keyword : keywords(question)) {
            if (haystack.contains(keyword)) score += 3;
        }
        if (safeTags(item).contains("public")) score += 1;
        return score;
    }

    private List<String> keywords(String question) {
        List<String> result = new ArrayList<>();
        Map<String, List<String>> dictionary = Map.ofEntries(
            Map.entry("frontdesk", List.of("前台", "登记", "建档", "分诊", "重复患者")),
            Map.entry("reception", List.of("接诊", "问诊", "主诉", "现病史", "症状")),
            Map.entry("inspection", List.of("检查室", "指检", "肛门镜", "摄像头", "图片", "影像", "初检")),
            Map.entry("doctor", List.of("医生", "诊断", "分流", "住院", "门诊", "手术", "中医", "治疗")),
            Map.entry("nurse", List.of("护士", "护理", "宣教", "院前", "出院")),
            Map.entry("quality", List.of("质控", "审核", "退回", "归档", "缺项", "冲突")),
            Map.entry("inventory", List.of("库存", "进销存", "申领", "发放", "签收", "低库存", "临期", "盘点", "报废")),
            Map.entry("backup", List.of("备份", "恢复", "路径", "数据库", "附件")),
            Map.entry("permission", List.of("权限", "角色", "账号", "科室", "能不能", "看不到")),
            Map.entry("attachment", List.of("附件", "上传", "图片", "报告", "重试")),
            Map.entry("ai", List.of("ai", "助手", "豆包", "生成", "模型"))
        );
        dictionary.forEach((key, words) -> {
            if (words.stream().anyMatch(question::contains)) result.add(key);
        });
        for (String token : question.split("[\\s,，。；;、]+")) {
            String value = safe(token).toLowerCase(Locale.ROOT);
            if (value.length() >= 2 && result.size() < 24) result.add(value);
        }
        return result;
    }

    private void addContextSignals(Set<String> signals, Map<String, Object> context) {
        if (context == null || context.isEmpty()) return;
        String text = safe(context).toLowerCase(Locale.ROOT);
        Map<String, List<String>> rules = Map.ofEntries(
            Map.entry("patient", List.of("patient", "患者", "病历", "档案", "detail")),
            Map.entry("quality", List.of("quality", "audit", "质控", "审核", "review")),
            Map.entry("leader", List.of("leader", "manager", "管理", "首页", "home")),
            Map.entry("inventory", List.of("inventory", "库存", "进销存")),
            Map.entry("backup", List.of("backup", "备份")),
            Map.entry("permission", List.of("permission", "权限", "role", "账号"))
        );
        rules.forEach((signal, words) -> {
            if (words.stream().anyMatch(text::contains)) signals.add(signal);
        });
    }

    private void addSignal(Set<String> signals, String value) {
        String signal = safe(value).toLowerCase(Locale.ROOT);
        if (!signal.isBlank()) signals.add(signal);
    }

    private List<KnowledgeItem> loadItems() {
        ClassPathResource resource = new ClassPathResource(KNOWLEDGE_PATH);
        try (InputStream input = resource.getInputStream()) {
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return objectMapper.readValue(json, new TypeReference<List<KnowledgeItem>>() {});
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load AI knowledge base: " + KNOWLEDGE_PATH, error);
        }
    }

    private static List<String> safeTags(KnowledgeItem item) {
        return item == null || item.tags() == null ? List.of() : item.tags();
    }

    private static String safe(Object value) {
        return String.valueOf(value == null ? "" : value).trim();
    }

    private record ScoredKnowledge(KnowledgeItem item, int score) {}
}
