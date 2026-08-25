package com.coshare.patientrecord.medicalrecord.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class InpatientSlotClassifierTest {

    @Test
    void classifiesSignatureLinesAsStructural() {
        assertThat(InpatientSlotClassifier.isStructural("医师签名：")).isTrue();
        assertThat(InpatientSlotClassifier.isStructural("医师签名:")).isTrue();
        assertThat(InpatientSlotClassifier.isStructural("医师签字：")).isTrue();
        assertThat(InpatientSlotClassifier.isStructural("副主任医师签名：")).isTrue();
    }

    @Test
    void classifiesPureSectionTitlesAsStructural() {
        assertThat(InpatientSlotClassifier.isStructural("体格检查")).isTrue();
        assertThat(InpatientSlotClassifier.isStructural("辅助检查结果")).isTrue();
        assertThat(InpatientSlotClassifier.isStructural("首次病程记录")).isTrue();
        assertThat(InpatientSlotClassifier.isStructural("中医辨病辨证依据：")).isTrue();
        assertThat(InpatientSlotClassifier.isStructural("出院诊断： 同入院诊断")).isTrue();
    }

    @Test
    void keepsFactBearingLinesForModelGeneration() {
        assertThat(InpatientSlotClassifier.isStructural("2025-02-25 13:30 手术医师查房记录")).isFalse();
        assertThat(InpatientSlotClassifier.isStructural("主诉： 间断便血伴便时肿物脱出1年，加重1月余")).isFalse();
        assertThat(InpatientSlotClassifier.isStructural("T（℃）：36.3 P（次／分）：70")).isFalse();
        assertThat(InpatientSlotClassifier.isStructural("中医“四诊”观察结果描述：")).isFalse();
        assertThat(InpatientSlotClassifier.isStructural("2、直肠粘膜松弛")).isFalse();
        assertThat(InpatientSlotClassifier.isStructural("")).isFalse();
        assertThat(InpatientSlotClassifier.isStructural(null)).isFalse();
    }

    @Test
    void pinsStructuralSlotsToReferenceTextAndKeepsContentSlots() {
        List<String> reference = List.of(
            "医师签名：",
            "体格检查",
            "主诉：间断便血1年",
            "2、直肠粘膜松弛"
        );
        List<String> generated = List.of(
            "医师签名： 医师签名：",
            "体格检查体格检查",
            "主诉：便血3天",
            "2、待医生补充"
        );

        List<String> merged = InpatientSlotClassifier.pinStructuralSlots(reference, generated);

        assertThat(merged).containsExactly(
            "医师签名：",
            "体格检查",
            "主诉：便血3天",
            "2、待医生补充"
        );
    }

    @Test
    void toleratesShorterGeneratedList() {
        List<String> merged = InpatientSlotClassifier.pinStructuralSlots(
            List.of("医师签名：", "体格检查"),
            List.of("模型只给了一段")
        );

        assertThat(merged).containsExactly("模型只给了一段");
    }
}
