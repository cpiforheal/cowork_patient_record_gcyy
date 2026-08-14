package com.coshare.patientrecord.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.InventoryAccessService;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class InventoryDepartmentDraftServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private InventoryAccessService inventoryAccess;
    @Mock private InventoryRepository repository;
    @Mock private InventoryQuotaGovernanceService quotaGovernanceService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InventoryDepartmentDraftService service;
    private SessionUser administrator;

    @BeforeEach
    void setUp() {
        service = new InventoryDepartmentDraftService(jdbcTemplate, objectMapper, inventoryAccess, repository, quotaGovernanceService);
        administrator = new SessionUser(
            "inventory-portal-admin", "inventory-admin", "Inventory Administrator", "admin", "Management", "", "", false,
            Instant.now().plusSeconds(3600)
        );
        lenient().when(inventoryAccess.hasCapability(administrator, "inventory:role:manage")).thenReturn(true);
        lenient().when(quotaGovernanceService.reviews(any(LocalDate.class), any(LocalDate.class))).thenReturn(Map.of());
    }

    @Test
    void rollupKeepsTheoreticalAndActualQuantitiesSeparateAndMarksMissingActualAsUnverified() {
        LocalDate date = LocalDate.of(2026, 8, 12);
        ObjectNode draft = draft("physiotherapy", "理疗室", date);
        addLine(draft, "固定耗材", 10, 4d, false, "");
        addLine(draft, "未核验耗材", 6, null, false, "");
        doReturn(List.of(draft)).when(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(date), eq(date));

        ObjectNode report = service.adminDailyRollup(date, administrator);

        assertThat(report.path("details")).hasSize(2);
        assertThat(detail(report, "固定耗材").path("theoreticalQuantity").asDouble()).isEqualTo(10d);
        assertThat(detail(report, "固定耗材").path("actualQuantity").asDouble()).isEqualTo(4d);
        assertThat(detail(report, "固定耗材").path("mainQuantity").asDouble()).isEqualTo(10d);
        assertThat(detail(report, "固定耗材").path("riskLevel").asText()).isEqualTo("ABNORMAL");
        assertThat(detail(report, "未核验耗材").path("actualQuantity").isNull()).isTrue();
        assertThat(detail(report, "未核验耗材").path("actualStatus").asText()).isEqualTo("UNVERIFIED");
        assertThat(summary(report, "固定耗材").path("theoreticalQuantity").asDouble()).isEqualTo(10d);
        assertThat(summary(report, "固定耗材").path("actualQuantity").asDouble()).isEqualTo(4d);
        assertThat(summary(report, "未核验耗材").path("unverifiedCount").asInt()).isEqualTo(1);
    }

    @Test
    void specialMaterialUsesActualQuantityAsManagementMainQuantityAndPreservesExplicitZero() {
        LocalDate date = LocalDate.of(2026, 8, 12);
        ObjectNode draft = draft("tcm", "中医科", date);
        addLine(draft, "特殊耗材", 8, 3d, true, "临时特殊使用");
        addLine(draft, "明确为零", 5, 0d, false, "");
        doReturn(List.of(draft)).when(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(date), eq(date));

        ObjectNode report = service.adminDailyRollup(date, administrator);

        assertThat(detail(report, "特殊耗材").path("isSpecial").asBoolean()).isTrue();
        assertThat(detail(report, "特殊耗材").path("mainQuantity").asDouble()).isEqualTo(3d);
        assertThat(detail(report, "特殊耗材").path("riskLevel").asText()).isEqualTo("SPECIAL");
        assertThat(detail(report, "明确为零").path("actualStatus").asText()).isEqualTo("REPORTED");
        assertThat(detail(report, "明确为零").path("actualQuantity").asDouble()).isZero();
        assertThat(summary(report, "特殊耗材").path("mainQuantity").asDouble()).isEqualTo(3d);
    }

    @Test
    void rollupBuildsEveryDateAndDepartmentStatusWithoutTurningMissingIntoZero() {
        LocalDate from = LocalDate.of(2026, 8, 11);
        LocalDate to = LocalDate.of(2026, 8, 12);
        ObjectNode draft = draft("tcm", "中医科", from);
        draft.putObject("groupVolumes").put("门诊", 0);
        addLine(draft, "零业务量耗材", 0, 0d, false, "");
        doReturn(List.of(draft)).when(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(from), eq(to));

        ObjectNode report = service.adminDailyRollup(from, to, administrator);

        assertThat(report.withArray("departmentDays")).hasSize(24);
        ObjectNode zeroVolume = departmentDay(report, from, "tcm");
        ObjectNode missing = departmentDay(report, to, "tcm");
        assertThat(zeroVolume.path("status").asText()).isEqualTo("ZERO_VOLUME");
        assertThat(zeroVolume.path("businessVolume").asInt()).isZero();
        assertThat(missing.path("status").asText()).isEqualTo("MISSING");
        assertThat(missing.path("businessVolume").isNull()).isTrue();
        assertThat(report.path("dashboard").path("expectedDepartmentDays").asInt()).isEqualTo(24);
        assertThat(report.path("dashboard").path("missingDepartmentDays").asInt()).isEqualTo(23);
    }

    @Test
    void missingUnitPriceLeavesAmountsBlankInsteadOfTreatingThemAsZero() {
        LocalDate date = LocalDate.of(2026, 8, 12);
        ObjectNode draft = draft("physiotherapy", "理疗室", date);
        addLine(draft, "未核价耗材", 10, 8d, false, "");
        doReturn(List.of(draft)).when(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(date), eq(date));

        ObjectNode report = service.adminDailyRollup(date, administrator);

        assertThat(detail(report, "未核价耗材").path("theoreticalAmount").isNull()).isTrue();
        assertThat(detail(report, "未核价耗材").path("actualAmount").isNull()).isTrue();
        assertThat(summary(report, "未核价耗材").path("theoreticalAmount").isNull()).isTrue();
        assertThat(summary(report, "未核价耗材").path("actualAmount").isNull()).isTrue();
        assertThat(summary(report, "未核价耗材").path("pricingCoverageRate").asDouble()).isZero();
    }

    @Test
    void sameMaterialNameWithDifferentUnitsRemainsSeparateInSummary() {
        LocalDate date = LocalDate.of(2026, 8, 12);
        ObjectNode draft = draft("physiotherapy", "理疗室", date);
        addLine(draft, "同名耗材", 2, 2d, false, "", "支", 5d);
        addLine(draft, "同名耗材", 3, 3d, false, "", "盒", 10d);
        doReturn(List.of(draft)).when(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(date), eq(date));

        ObjectNode report = service.adminDailyRollup(date, administrator);

        assertThat(summary(report, "同名耗材", "支").path("actualAmount").asDouble()).isEqualTo(10d);
        assertThat(summary(report, "同名耗材", "盒").path("actualAmount").asDouble()).isEqualTo(30d);
        int sameNameCount = 0;
        for (JsonNode row : report.withArray("summary")) if ("同名耗材".equals(row.path("materialName").asText())) sameNameCount++;
        assertThat(sameNameCount).isEqualTo(2);
    }

    @Test
    void exportsContainFiveManagementSheetsAndUnverifiedLines() throws Exception {
        LocalDate date = LocalDate.of(2026, 8, 12);
        ObjectNode draft = draft("physiotherapy", "理疗室", date);
        addLine(draft, "已填报耗材", 10, 8d, false, "");
        addLine(draft, "未填报耗材", 5, null, false, "");
        addLine(draft, "explicit-zero", 2, 0d, false, "");
        doReturn(List.of(draft)).when(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(date), eq(date));

        String csv = new String(service.exportAdminDailyRollup(date, administrator), StandardCharsets.UTF_8);
        assertThat(csv).contains("已填报耗材", "未填报耗材", "UNVERIFIED");

        byte[] xlsx = service.exportAdminDailyRollupXlsx(date, administrator);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(5);
            assertThat(workbook.getSheetName(0)).isEqualTo("领导总览");
            assertThat(workbook.getSheetName(1)).isEqualTo("填报进度");
            assertThat(workbook.getSheetName(2)).isEqualTo("耗材汇总");
            assertThat(workbook.getSheetName(3)).isEqualTo("异常核查");
            assertThat(workbook.getSheetName(4)).isEqualTo("审计明细");
            assertThat(workbookText(workbook)).contains("已填报耗材", "未填报耗材", "待核验");
            assertThat(workbook.getSheetAt(1).getPaneInformation().isFreezePane()).isTrue();
            assertThat(workbook.getSheetAt(1).getCTWorksheet().isSetAutoFilter()).isTrue();
            var audit = workbook.getSheetAt(4);
            assertThat(rowForMaterial(audit, "explicit-zero").getCell(6).getNumericCellValue()).isZero();
            assertThat(rowForMaterial(audit, "explicit-zero").getCell(9).getStringCellValue()).isEmpty();
        }
    }

    private ObjectNode draft(String departmentKey, String departmentName, LocalDate date) {
        ObjectNode draft = objectMapper.createObjectNode();
        draft.put("departmentKey", departmentKey);
        draft.put("departmentName", departmentName);
        draft.put("businessDate", date.toString());
        draft.put("operator", departmentName + "填报员");
        draft.put("operatorUsername", "inv-" + departmentKey);
        draft.put("updatedAt", date + "T17:00:00");
        draft.put("frozenQuota", true);
        draft.put("quotaVersionId", "quota-v1");
        draft.put("quotaVersionCode", "quota-v1");
        draft.putObject("groupVolumes").put("门诊", 10);
        draft.putArray("lines");
        return draft;
    }

    private void addLine(ObjectNode draft, String materialName, double theoreticalQuantity, Double actualQuantity, boolean special, String specialDailyNote) {
        addLine(draft, materialName, theoreticalQuantity, actualQuantity, special, specialDailyNote, "支", null);
    }

    private void addLine(ObjectNode draft, String materialName, double theoreticalQuantity, Double actualQuantity, boolean special, String specialDailyNote,
        String unit, Double unitPrice) {
        ArrayNode lines = (ArrayNode) draft.path("lines");
        ObjectNode line = lines.addObject();
        line.put("lineKey", materialName + "-key-" + lines.size());
        line.put("sourceRow", lines.size());
        line.put("serviceGroup", "门诊");
        line.put("materialName", materialName);
        line.put("unit", unit);
        line.put("standardQuantity", 1);
        line.put("fixedAdjustment", 0);
        line.put("referenceQuantity", theoreticalQuantity);
        line.put("isSpecial", special);
        line.put("specialDailyNote", specialDailyNote);
        if (unitPrice == null) line.putNull("unitPrice"); else line.put("unitPrice", unitPrice);
        if (actualQuantity == null) line.putNull("actualQuantity"); else line.put("actualQuantity", actualQuantity);
    }

    private static ObjectNode detail(ObjectNode report, String materialName) {
        return find(report.withArray("details"), materialName);
    }

    private static ObjectNode summary(ObjectNode report, String materialName) {
        return find(report.withArray("summary"), materialName);
    }

    private static ObjectNode summary(ObjectNode report, String materialName, String unit) {
        for (JsonNode row : report.withArray("summary")) {
            if (materialName.equals(row.path("materialName").asText()) && unit.equals(row.path("unit").asText())) return (ObjectNode) row;
        }
        throw new AssertionError("missing material/unit: " + materialName + "/" + unit);
    }

    private static ObjectNode departmentDay(ObjectNode report, LocalDate businessDate, String departmentKey) {
        for (JsonNode row : report.withArray("departmentDays")) {
            if (businessDate.toString().equals(row.path("businessDate").asText()) && departmentKey.equals(row.path("departmentKey").asText())) return (ObjectNode) row;
        }
        throw new AssertionError("missing department day: " + businessDate + "/" + departmentKey);
    }

    private static org.apache.poi.ss.usermodel.Row rowForMaterial(org.apache.poi.ss.usermodel.Sheet sheet, String materialName) {
        for (org.apache.poi.ss.usermodel.Row row : sheet) {
            if (row.getCell(2) != null && materialName.equals(row.getCell(2).getStringCellValue())) return row;
        }
        throw new AssertionError("missing material in sheet: " + materialName);
    }

    private static ObjectNode find(ArrayNode rows, String materialName) {
        for (var row : rows) if (materialName.equals(row.path("materialName").asText())) return (ObjectNode) row;
        throw new AssertionError("missing material: " + materialName);
    }

    private static String workbookText(XSSFWorkbook workbook) {
        StringBuilder text = new StringBuilder();
        workbook.forEach(sheet -> sheet.forEach(row -> row.forEach(cell -> text.append(cell).append('\n'))));
        return text.toString();
    }
}
