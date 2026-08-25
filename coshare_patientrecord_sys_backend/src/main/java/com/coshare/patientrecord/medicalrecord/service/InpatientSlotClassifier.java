package com.coshare.patientrecord.medicalrecord.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 识别参考文档中的结构性段落（纯标题、签名行）。这类段落不承载患者事实，
 * 由系统直接沿用范本原文，不依赖模型改写，避免标题错位或签名行被并入正文。
 */
public final class InpatientSlotClassifier {

    private static final Pattern SIGNATURE = Pattern.compile("^(?:副?主?任?医师|手术医师|上级医师)?签(?:名|字)[:：]$");
    private static final List<String> HEADER_SUFFIXES = List.of(
        "记录", "检查", "结果", "诊断", "小结", "准备", "调护", "计划", "依据", "医嘱"
    );
    private static final int MAX_HEADER_LENGTH = 15;

    private InpatientSlotClassifier() {
    }

    /**
     * 判定某参考段落是否为结构段。要求：签名行；或无 ASCII 数字、长度不超过 15、
     * 去掉结尾冒号后以章节后缀（记录/检查/结果/诊断等）结尾。含日期、数值或
     * 正文内容的短行不在此列，仍交由模型生成。
     */
    public static boolean isStructural(String paragraphText) {
        String text = paragraphText == null ? "" : paragraphText.trim();
        if (text.isEmpty()) return false;
        if (SIGNATURE.matcher(text).matches()) return true;
        if (text.length() > MAX_HEADER_LENGTH) return false;
        if (text.chars().anyMatch(character -> character >= '0' && character <= '9')) return false;
        String withoutTrailingColon = text.replaceAll("[:：]$", "");
        for (String suffix : HEADER_SUFFIXES) {
            if (withoutTrailingColon.endsWith(suffix)) return true;
        }
        return false;
    }

    /**
     * 将生成结果中对应参考结构段的位置替换回范本原文，返回新列表。
     * 仅在 LEGACY_ORDINAL（逐段对齐）模式下使用；两个列表长度不一致时说明
     * 对齐已破坏（后续渲染会整体拒绝），此时不做任何替换。
     */
    public static List<String> pinStructuralSlots(List<String> referenceParagraphs, List<String> generatedParagraphs) {
        List<String> result = new ArrayList<>(generatedParagraphs);
        if (referenceParagraphs.size() != result.size()) return result;
        for (int index = 0; index < result.size(); index++) {
            if (isStructural(referenceParagraphs.get(index))) {
                result.set(index, referenceParagraphs.get(index));
            }
        }
        return result;
    }
}
