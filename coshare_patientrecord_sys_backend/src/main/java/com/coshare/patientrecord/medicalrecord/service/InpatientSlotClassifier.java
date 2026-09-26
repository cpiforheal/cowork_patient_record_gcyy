package com.coshare.patientrecord.medicalrecord.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 识别参考文档中需要沿用范本原文的段落，分两类：
 * 一是结构性段落（纯标题、签名行），避免标题错位或签名行被并入正文；
 * 二是医生固定的标准医嘱段（如“中医特色治疗”），内容为科室统一治疗方案、
 * 不含患者个体事实，由系统照抄范本，不交给模型改写。
 */
public final class InpatientSlotClassifier {

    private static final Pattern SIGNATURE = Pattern.compile("^(?:副?主?任?医师|手术医师|上级医师)?签(?:名|字)[:：]$");
    private static final List<String> HEADER_SUFFIXES = List.of(
        "记录", "检查", "结果", "诊断", "小结", "准备", "调护", "计划", "依据", "医嘱"
    );
    private static final int MAX_HEADER_LENGTH = 15;
    /** 医生固定医嘱段的段首标识（比对前先去掉“4、”这类序号）。 */
    private static final List<String> PINNED_BODY_PREFIXES = List.of("中医特色治疗");
    private static final Pattern LEADING_ORDINAL = Pattern.compile(
        "^(?:[0-9０-９]+\\s*[、.．)）]|[（(]\\s*[0-9０-９]+\\s*[)）])\\s*"
    );

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
     * 判定某段落是否为医生固定医嘱段：去掉段首序号后以固定标识开头。这类段落
     * 描述科室统一治疗方案，与患者个体无关，须整段沿用范本原文。
     */
    public static boolean isPinnedBody(String paragraphText) {
        String text = paragraphText == null ? "" : paragraphText.trim();
        if (text.isEmpty()) return false;
        String withoutOrdinal = LEADING_ORDINAL.matcher(text).replaceFirst("");
        for (String prefix : PINNED_BODY_PREFIXES) {
            if (withoutOrdinal.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 需要沿用范本原文的段落：结构段或医生固定医嘱段。 */
    public static boolean isVerbatimSlot(String paragraphText) {
        return isStructural(paragraphText) || isPinnedBody(paragraphText);
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
            if (isVerbatimSlot(referenceParagraphs.get(index))) {
                result.set(index, referenceParagraphs.get(index));
            }
        }
        return result;
    }
}
