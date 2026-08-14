package com.coshare.patientrecord.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class InventoryDepartmentPeriodServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void periodUsageUsesOnlyExplicitActualQuantityWhileBusinessVolumeRemainsAvailable() throws Exception {
        InventoryDepartmentPeriodService service = new InventoryDepartmentPeriodService(
            mock(JdbcTemplate.class), objectMapper, mock(InventoryDepartmentDraftService.class), mock(InventoryRepository.class)
        );
        ResultSet rows = mock(ResultSet.class);
        ObjectNode draft = objectMapper.createObjectNode();
        draft.putObject("groupVolumes").put("门诊", 50);
        ArrayNode lines = draft.putArray("lines");
        lines.addObject().put("serviceGroup", "门诊").put("careType", "outpatient").put("materialName", "TRIAL_ONLY")
            .put("unit", "piece").put("standardQuantity", 2).put("manualAdjustment", 1).putNull("actualQuantity");
        lines.addObject().put("serviceGroup", "门诊").put("careType", "outpatient").put("materialName", "ACTUAL_ONLY")
            .put("unit", "piece").put("standardQuantity", 2).put("manualAdjustment", 1).put("actualQuantity", 3);
        when(rows.getString("id")).thenReturn("draft-1");
        when(rows.getDate("business_date")).thenReturn(java.sql.Date.valueOf(LocalDate.of(2026, 8, 12)));
        when(rows.getString("raw_json")).thenReturn(objectMapper.writeValueAsString(draft));
        when(rows.getInt("revision")).thenReturn(1);
        when(rows.getString("template_version")).thenReturn("v1");
        when(rows.getString("operator_name")).thenReturn("operator");
        when(rows.getString("operator_username")).thenReturn("inv-physiotherapy");
        when(rows.getTimestamp("updated_at")).thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 8, 12, 12, 0)));

        List<?> audit = ReflectionTestUtils.invokeMethod(service, "parseDraft", rows);
        ArrayNode summary = ReflectionTestUtils.invokeMethod(service, "summary", audit);
        ObjectNode volumes = ReflectionTestUtils.invokeMethod(service, "businessVolumes", audit);
        ArrayNode dailyAudit = ReflectionTestUtils.invokeMethod(service, "auditArray", audit);

        assertThat(summary).isNotNull().hasSize(1);
        assertThat(summary.get(0).path("materialName").asText()).isEqualTo("ACTUAL_ONLY");
        assertThat(summary.get(0).path("quantity").asDouble()).isEqualTo(3d);
        assertThat(volumes).isNotNull();
        assertThat(volumes.path("outpatient").asInt()).isEqualTo(50);
        JsonNode trial = "TRIAL_ONLY".equals(dailyAudit.get(0).path("materialName").asText()) ? dailyAudit.get(0) : dailyAudit.get(1);
        JsonNode actual = "ACTUAL_ONLY".equals(dailyAudit.get(0).path("materialName").asText()) ? dailyAudit.get(0) : dailyAudit.get(1);
        assertThat(trial.path("referenceQuantity").asDouble()).isEqualTo(101d);
        assertThat(trial.path("actualQuantity").isNull()).isTrue();
        assertThat(actual.path("referenceQuantity").asDouble()).isEqualTo(101d);
        assertThat(actual.path("actualQuantity").asDouble()).isEqualTo(3d);
        assertThat(actual.path("dailyQuantity").asDouble()).isEqualTo(3d);
    }
}
