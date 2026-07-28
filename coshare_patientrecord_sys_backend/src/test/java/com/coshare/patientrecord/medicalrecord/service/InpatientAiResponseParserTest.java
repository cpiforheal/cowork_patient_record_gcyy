package com.coshare.patientrecord.medicalrecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class InpatientAiResponseParserTest {

    private final InpatientAiResponseParser parser = new InpatientAiResponseParser(new ObjectMapper());

    @Test
    void parsesControlledNodesInExpectedKeyOrder() throws Exception {
        InpatientAiResponseParser.ParsedGeneration result = parser.parse(
            "{\"nodes\":[{\"key\":\"bm:second\",\"text\":\"方案\"},{\"key\":\"cc:first\",\"text\":\"诊断\"}]}",
            2,
            List.of("cc:first", "bm:second")
        );

        assertThat(result.nodeReplacements())
            .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("cc:first", "诊断", "bm:second", "方案"));
        assertThat(result.paragraphs()).extracting(node -> node.asText())
            .containsExactly("诊断", "方案");
    }

    @Test
    void acceptsJsonCodeFenceAndNormalizesBlankText() throws Exception {
        InpatientAiResponseParser.ParsedGeneration result = parser.parse(
            "```json\n{\"nodes\":[{\"key\":\"cc:first\",\"text\":\"   \"}]}\n```",
            1,
            List.of("cc:first")
        );

        assertThat(result.nodeReplacements()).containsEntry("cc:first", "待医生补充");
        assertThat(result.paragraphs().get(0).asText()).isEqualTo("待医生补充");
    }

    @Test
    void rejectsUnknownControlledNodeKey() {
        assertBadGateway(
            "{\"nodes\":[{\"key\":\"cc:unknown\",\"text\":\"内容\"}]}",
            List.of("cc:first"),
            "未知或重复"
        );
    }

    @Test
    void rejectsDuplicateControlledNodeKey() {
        assertBadGateway(
            "{\"nodes\":[{\"key\":\"cc:first\",\"text\":\"甲\"},{\"key\":\"cc:first\",\"text\":\"乙\"}]}",
            List.of("cc:first"),
            "未知或重复"
        );
    }

    @Test
    void rejectsMissingControlledNodeKey() {
        assertBadGateway(
            "{\"nodes\":[{\"key\":\"cc:first\",\"text\":\"甲\"}]}",
            List.of("cc:first", "bm:second"),
            "不完整"
        );
    }

    @Test
    void rejectsNonTextControlledNodeValue() {
        assertBadGateway(
            "{\"nodes\":[{\"key\":\"cc:first\",\"text\":42}]}",
            List.of("cc:first"),
            "text 必须为文本"
        );
    }

    @Test
    void retainsLegacyParagraphContract() throws Exception {
        InpatientAiResponseParser.ParsedGeneration result = parser.parse(
            "{\"paragraphs\":[\"第一段\",\"第二段\"]}",
            2,
            List.of()
        );

        assertThat(result.nodeReplacements()).isEmpty();
        assertThat(result.paragraphs()).extracting(node -> node.asText())
            .containsExactly("第一段", "第二段");
    }

    private void assertBadGateway(String content, List<String> keys, String message) {
        assertThatThrownBy(() -> parser.parse(content, keys.size(), keys))
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(error.getReason()).contains(message);
            });
    }
}
