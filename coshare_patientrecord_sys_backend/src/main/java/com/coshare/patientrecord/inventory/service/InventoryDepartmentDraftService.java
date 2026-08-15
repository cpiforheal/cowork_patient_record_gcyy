package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.InventoryAccessService;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ComparisonOperator;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.PatternFormatting;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Stores editable daily accounting sheets without producing any inventory movement. */
@Service
@Profile("mysql")
public class InventoryDepartmentDraftService {

    private static final Map<String, String> DEPARTMENTS = departments();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryAccessService inventoryAccess;
    private final InventoryRepository repository;
    private final InventoryQuotaGovernanceService quotaGovernanceService;

    public InventoryDepartmentDraftService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        InventoryAccessService inventoryAccess,
        InventoryRepository repository,
        InventoryQuotaGovernanceService quotaGovernanceService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.inventoryAccess = inventoryAccess;
        this.repository = repository;
        this.quotaGovernanceService = quotaGovernanceService;
    }

    @Transactional(readOnly = true)
    public ObjectNode read(String departmentKey, LocalDate businessDate, SessionUser user) {
        String departmentName = requireDepartment(departmentKey, user);
        ObjectNode result = jdbcTemplate.query(
            "SELECT id, department_key, department_name, business_date, template_version, revision, operator_name, operator_username, raw_json, updated_at "
                + "FROM inventory_department_daily_drafts WHERE department_key = ? AND business_date = ?",
            rowSet -> rowSet.next() ? row(rowSet) : empty(departmentKey, departmentName, businessDate),
            departmentKey,
            businessDate
        );
        result.put("exists", result.has("id"));
        if (!result.path("exists").asBoolean(false)) applyQuotaTemplate(result, departmentKey, businessDate);
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode summary(LocalDate businessDate, SessionUser user) {
        if (!inventoryAccess.canViewAllDepartments(user)) {
            return JsonNodeFactory.instance.objectNode().set("list", JsonNodeFactory.instance.arrayNode());
        }
        ArrayNode rows = jdbcTemplate.query(
                "SELECT id, department_key, department_name, business_date, template_version, revision, operator_name, operator_username, raw_json, updated_at "
                + "FROM inventory_department_daily_drafts WHERE business_date = ? ORDER BY department_name",
            resultSet -> {
                ArrayNode list = JsonNodeFactory.instance.arrayNode();
                while (resultSet.next()) list.add(row(resultSet));
                return list;
            },
            businessDate
        );
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("businessDate", businessDate.toString());
        result.set("list", rows);
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode adminDailyRollup(LocalDate businessDate, SessionUser user) {
        return adminDailyRollup(businessDate, businessDate, user);
    }

    @Transactional(readOnly = true)
    public ObjectNode adminDailyRollup(LocalDate from, LocalDate to, SessionUser user) {
        requireAdministrator(user);
        validateDateRange(from, to);
        List<ObjectNode> drafts = jdbcTemplate.query(
            "SELECT id, department_key, department_name, business_date, template_version, revision, operator_name, operator_username, raw_json, updated_at "
                + "FROM inventory_department_daily_drafts WHERE business_date BETWEEN ? AND ? ORDER BY business_date, department_name",
            (resultSet, rowNum) -> row(resultSet), from, to
        );
        Map<String, InventoryQuotaGovernanceService.ReviewRecord> reviews = quotaGovernanceService.reviews(from, to);
        Map<String, ObjectNode> draftsByDayDepartment = new LinkedHashMap<>();
        Map<String, List<ObjectNode>> draftsByDepartment = new LinkedHashMap<>();
        for (ObjectNode draft : drafts) {
            String departmentKey = text(draft, "departmentKey");
            String businessDate = text(draft, "businessDate");
            if (!DEPARTMENTS.containsKey(departmentKey) || businessDate.isBlank()) continue;
            draftsByDayDepartment.put(businessDate + "\u0000" + departmentKey, draft);
            draftsByDepartment.computeIfAbsent(departmentKey, ignored -> new ArrayList<>()).add(draft);
        }

        Map<String, AdminDailyRollupLine> summary = new LinkedHashMap<>();
        ArrayNode details = JsonNodeFactory.instance.arrayNode();
        for (ObjectNode draft : drafts) appendAdminDailyRollup(draft, summary, details, reviews);

        ArrayNode departmentDays = JsonNodeFactory.instance.arrayNode();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (Map.Entry<String, String> department : DEPARTMENTS.entrySet()) {
                ObjectNode draft = draftsByDayDepartment.get(date + "\u0000" + department.getKey());
                ObjectNode row = departmentDays.addObject();
                row.put("businessDate", date.toString());
                row.put("departmentKey", department.getKey());
                row.put("departmentName", department.getValue());
                if (draft == null) {
                    row.put("status", "MISSING");
                    row.putNull("businessVolume");
                    row.put("riskCount", 0);
                    row.put("unverifiedCount", 0);
                    row.put("attentionCount", 0);
                    row.put("abnormalCount", 0);
                    row.put("specialPendingNoteCount", 0);
                    continue;
                }
                int businessVolume = businessVolume(draft);
                int unverified = 0;
                int attention = 0;
                int abnormal = 0;
                int specialPending = 0;
                for (JsonNode detail : details) {
                    if (!date.toString().equals(textStatic(detail, "businessDate")) || !department.getKey().equals(textStatic(detail, "departmentKey"))) continue;
                    String risk = textStatic(detail, "riskLevel");
                    if ("UNVERIFIED".equals(risk)) unverified++;
                    else if ("ATTENTION".equals(risk)) attention++;
                    else if ("ABNORMAL".equals(risk) || "HISTORICAL_UNFROZEN".equals(risk)) abnormal++;
                    else if ("SPECIAL_PENDING_NOTE".equals(risk)) specialPending++;
                }
                row.put("status", abnormal + specialPending > 0 ? "ABNORMAL" : unverified + attention > 0 ? "ATTENTION" : businessVolume == 0 ? "ZERO_VOLUME" : "SUBMITTED");
                row.put("businessVolume", businessVolume);
                row.put("riskCount", unverified + attention + abnormal + specialPending);
                row.put("unverifiedCount", unverified);
                row.put("attentionCount", attention);
                row.put("abnormalCount", abnormal);
                row.put("specialPendingNoteCount", specialPending);
                row.put("operator", text(draft, "operator"));
                row.put("updatedAt", text(draft, "updatedAt"));
            }
        }

        ArrayNode departments = JsonNodeFactory.instance.arrayNode();
        ArrayNode missingDepartments = JsonNodeFactory.instance.arrayNode();
        int savedDepartmentCount = 0;
        for (Map.Entry<String, String> department : DEPARTMENTS.entrySet()) {
            List<ObjectNode> departmentDrafts = draftsByDepartment.getOrDefault(department.getKey(), List.of());
            ObjectNode row = departments.addObject();
            row.put("departmentKey", department.getKey());
            row.put("departmentName", department.getValue());
            row.put("submittedDayCount", departmentDrafts.size());
            if (departmentDrafts.isEmpty()) {
                row.put("status", "MISSING");
                row.putNull("businessVolume");
                row.put("operator", "");
                row.put("operatorUsername", "");
                row.put("updatedAt", "");
                missingDepartments.add(department.getValue());
                continue;
            }
            ObjectNode latestDraft = departmentDrafts.get(departmentDrafts.size() - 1);
            row.put("status", "SUBMITTED");
            row.put("businessVolume", departmentDrafts.stream().mapToInt(InventoryDepartmentDraftService::businessVolume).sum());
            row.put("operator", text(latestDraft, "operator"));
            row.put("operatorUsername", text(latestDraft, "operatorUsername"));
            row.put("updatedAt", text(latestDraft, "updatedAt"));
            savedDepartmentCount++;
        }

        ArrayNode summaryRows = JsonNodeFactory.instance.arrayNode();
        summary.values().stream()
            .sorted((left, right) -> Double.compare(right.actualAmount(), left.actualAmount()))
            .forEach(line -> summaryRows.add(summaryRow(line)));

        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("businessDate", from.equals(to) ? from.toString() : "");
        result.put("periodStart", from.toString());
        result.put("periodEnd", to.toString());
        result.put("departmentCount", DEPARTMENTS.size());
        result.put("savedDepartmentCount", savedDepartmentCount);
        result.put("savedDraftCount", drafts.size());
        result.set("missingDepartments", missingDepartments);
        result.set("departments", departments);
        result.set("departmentDays", departmentDays);
        result.set("summary", summaryRows);
        result.set("details", details);
        result.set("dashboard", buildDashboard(from, to, departmentDays, details, summaryRows));
        return result;
    }

    @Transactional(readOnly = true)
    public byte[] exportAdminDailyRollup(LocalDate businessDate, SessionUser user) {
        return exportAdminDailyRollup(businessDate, businessDate, user);
    }

    @Transactional(readOnly = true)
    public byte[] exportAdminDailyRollup(LocalDate from, LocalDate to, SessionUser user) {
        ObjectNode report = adminDailyRollup(from, to, user);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { "导出类型", "管理员 12 科室理论与实际耗材核查报表" });
        rows.add(new String[] { "查询起始日期", report.path("periodStart").asText() });
        rows.add(new String[] { "查询结束日期", report.path("periodEnd").asText() });
        rows.add(new String[] { "纳入科室", report.path("departmentCount").asText() });
        rows.add(new String[] { "已填报科室", report.path("savedDepartmentCount").asText() });
        rows.add(new String[] { "未填报科室", joinText(report.path("missingDepartments")) });
        rows.add(new String[] { "导出时间", OffsetDateTime.now(ZoneId.systemDefault()).toString() });
        rows.add(new String[] {});
        rows.add(new String[] { "理论与实际汇总" });
        rows.add(new String[] { "耗材", "单位", "理论使用量", "实际使用量", "管理主口径", "特殊行数", "未核验行数", "关注行数", "异常行数", "覆盖科室数", "覆盖科室" });
        report.path("summary").forEach(line -> rows.add(new String[] {
            text(line, "materialName"), text(line, "unit"), quantityText(line.path("theoreticalQuantity").asDouble()), quantityNodeText(line, "actualQuantity"),
            quantityText(line.path("mainQuantity").asDouble()), line.path("specialLineCount").asText(), line.path("unverifiedCount").asText(),
            line.path("attentionCount").asText(), line.path("abnormalCount").asText(), line.path("departmentCount").asText(), joinText(line.path("departments"))
        }));
        rows.add(new String[] {});
        rows.add(new String[] { "异常与未核验" });
        rows.add(new String[] { "业务日期", "科室", "耗材", "单位", "理论使用量", "实际使用量", "差额", "偏差率", "风险等级", "特殊耗材", "特殊说明", "复核状态", "复核备注" });
        report.path("details").forEach(line -> {
            String risk = text(line, "riskLevel");
            if (!"NORMAL".equals(risk) && !"SPECIAL".equals(risk)) rows.add(comparisonCsvRow(line));
        });
        rows.add(new String[] {});
        rows.add(new String[] { "逐日科室明细" });
        rows.add(new String[] { "业务日期", "科室", "业务量/患者数", "耗材", "单位", "定额版本", "每人次定额", "固定调整", "理论使用量", "实际使用量", "实际状态", "差额", "偏差率", "风险等级", "特殊耗材", "特殊说明", "复核状态", "复核备注", "复核人", "复核时间", "填报人", "更新时间" });
        report.path("details").forEach(line -> rows.add(detailedCsvRow(line)));
        return csv(rows);
    }

    @Transactional(readOnly = true)
    public byte[] exportAdminDailyRollupXlsx(LocalDate businessDate, SessionUser user) {
        return exportAdminDailyRollupXlsx(businessDate, businessDate, user);
    }

    @Transactional(readOnly = true)
    public byte[] exportAdminDailyRollupXlsx(LocalDate from, LocalDate to, SessionUser user) {
        ObjectNode report = adminDailyRollup(from, to, user);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle titleStyle = workbook.createCellStyle();
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);
            titleStyle.setFont(titleFont);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            DataFormat dataFormat = workbook.createDataFormat();
            CellStyle percentageStyle = workbook.createCellStyle();
            percentageStyle.setDataFormat(dataFormat.getFormat("0.0%"));
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(dataFormat.getFormat("yyyy-mm-dd"));
            CellStyle dateTimeStyle = workbook.createCellStyle();
            dateTimeStyle.setDataFormat(dataFormat.getFormat("yyyy-mm-dd hh:mm"));
            CellStyle amountStyle = workbook.createCellStyle();
            amountStyle.setDataFormat(dataFormat.getFormat("#,##0.00"));
            CellStyle totalStyle = workbook.createCellStyle();
            Font totalFont = workbook.createFont();
            totalFont.setBold(true);
            totalStyle.setFont(totalFont);
            totalStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            totalStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle totalPercentageStyle = workbook.createCellStyle();
            totalPercentageStyle.cloneStyleFrom(totalStyle);
            totalPercentageStyle.setDataFormat(dataFormat.getFormat("0.0%"));
            CellStyle totalAmountStyle = workbook.createCellStyle();
            totalAmountStyle.cloneStyleFrom(totalStyle);
            totalAmountStyle.setDataFormat(dataFormat.getFormat("#,##0.00"));

            ObjectNode dashboard = (ObjectNode) report.path("dashboard");
            // P3：Sheet 顺序按阅读动线重排 —— 总览 → 科室汇总 → 异常核查 → 耗材汇总 → 明细 → 口径
            Sheet overview = workbook.createSheet("领导总览");
            int row = 0;
            row = writeXlsxRow(overview, row, titleStyle, "管理员耗材数据驾驶舱");
            row = writeXlsxRow(overview, row, null, "查询起止日期", report.path("periodStart").asText() + " 至 " + report.path("periodEnd").asText());
            row++;
            row = writeXlsxRow(overview, row, headerStyle, "日报完成率", "已填报科室日", "应填报科室日", "未填报科室日", "待处理风险行", "未核验耗材行", "关注风险行", "异常风险行", "特殊待说明行", "已核价实际金额", "核价覆盖率");
            int pendingRiskRows = dashboard.path("unverifiedCount").asInt() + dashboard.path("attentionCount").asInt()
                + dashboard.path("abnormalCount").asInt() + dashboard.path("specialPendingNoteCount").asInt();
            row = writeDataXlsxRow(overview, row, new CellStyle[] { percentageStyle, null, null, null, null, null, null, null, null, amountStyle, percentageStyle },
                percentageValue(dashboard.path("completionRate").asDouble()), dashboard.path("submittedDepartmentDays").asInt(),
                dashboard.path("expectedDepartmentDays").asInt(), dashboard.path("missingDepartmentDays").asInt(), pendingRiskRows,
                dashboard.path("unverifiedCount").asInt(), dashboard.path("attentionCount").asInt(), dashboard.path("abnormalCount").asInt(),
                dashboard.path("specialPendingNoteCount").asInt(), nullableNumber(dashboard, "actualAmount"), nullablePercentage(dashboard, "pricingCoverageRate"));
            row += 2;
            row = writeXlsxRow(overview, row, headerStyle, "日期", "已提交科室", "应提交科室", "完成率", "理论金额", "实际金额", "风险总行数", "未核验行", "关注行", "异常行", "特殊待说明行");
            for (JsonNode day : dashboard.path("dailyTrend")) row = writeDataXlsxRow(overview, row,
                new CellStyle[] { dateStyle, null, null, percentageStyle, amountStyle, amountStyle, null, null, null, null, null },
                excelDate(text(day, "businessDate")), day.path("submittedDepartmentDays").asInt(), day.path("expectedDepartmentDays").asInt(),
                percentageValue(day.path("completionRate").asDouble()), nullableNumber(day, "theoreticalAmount"), nullableNumber(day, "actualAmount"),
                day.path("unverifiedCount").asInt() + day.path("attentionCount").asInt() + day.path("abnormalCount").asInt() + day.path("specialPendingNoteCount").asInt(),
                day.path("unverifiedCount").asInt(), day.path("attentionCount").asInt(), day.path("abnormalCount").asInt(), day.path("specialPendingNoteCount").asInt());
            setColumnWidths(overview, 14, 13, 13, 13, 16, 16, 14, 12, 12, 12, 15);
            overview.createFreezePane(0, 4);
            overview.setAutoFilter(new CellRangeAddress(3, Math.max(3, row - 1), 0, 10));

            // P2：科室汇总改为单次聚合，消除按科室反复扫描 departmentDays 的重复计算
            int expectedDays = Math.max(1, dashboard.path("dailyTrend").size());
            Map<String, DepartmentRollup> rollups = new LinkedHashMap<>();
            for (JsonNode department : report.path("departments")) rollups.put(text(department, "departmentKey"),
                new DepartmentRollup(text(department, "departmentName"), textOrNull(text(department, "operator")), text(department, "updatedAt")));
            for (JsonNode day : report.path("departmentDays")) {
                DepartmentRollup rollup = rollups.get(text(day, "departmentKey"));
                if (rollup == null) continue;
                if (!"MISSING".equals(text(day, "status"))) rollup.submittedDays++;
                if (!day.path("businessVolume").isNull()) rollup.businessVolume += day.path("businessVolume").asDouble();
                rollup.riskCount += day.path("riskCount").asInt();
                rollup.unverifiedCount += day.path("unverifiedCount").asInt();
                rollup.attentionCount += day.path("attentionCount").asInt();
                rollup.abnormalCount += day.path("abnormalCount").asInt();
                rollup.specialPendingNoteCount += day.path("specialPendingNoteCount").asInt();
            }
            List<DepartmentRollup> orderedRollups = new ArrayList<>(rollups.values());
            orderedRollups.sort(java.util.Comparator.comparingDouble(value -> value.completionRate(expectedDays)));

            Sheet departmentSheet = workbook.createSheet("科室汇总");
            row = 0;
            row = writeXlsxRow(departmentSheet, row, titleStyle, "12 科室填报与风险汇总（按完成率升序，待关注科室在前）");
            row = writeXlsxRow(departmentSheet, row, null, "查询起止日期", report.path("periodStart").asText() + " 至 " + report.path("periodEnd").asText());
            row++;
            row = writeXlsxRow(departmentSheet, row, headerStyle, "科室", "状态", "已填报天数", "应填报天数", "完成率", "业务量合计", "风险条目", "未核验", "关注", "异常", "特殊待说明", "最后填报人", "最后更新时间");
            int departmentFirstRow = row;
            int totalSubmittedDays = 0;
            int totalExpectedDays = 0;
            double totalBusinessVolume = 0;
            int totalRiskRows = 0;
            int totalUnverifiedRows = 0;
            int totalAttentionRows = 0;
            int totalAbnormalRows = 0;
            int totalSpecialRows = 0;
            for (DepartmentRollup rollup : orderedRollups) {
                row = writeDataXlsxRow(departmentSheet, row, new CellStyle[] { null, null, null, null, percentageStyle, null, null, null, null, null, null, null, dateTimeStyle },
                    rollup.departmentName, rollup.status(expectedDays), rollup.submittedDays, expectedDays,
                    percentageValue(rollup.completionRate(expectedDays)), rollup.businessVolume, rollup.riskCount, rollup.unverifiedCount,
                    rollup.attentionCount, rollup.abnormalCount, rollup.specialPendingNoteCount, rollup.operator, excelDateTime(rollup.updatedAt));
                totalSubmittedDays += rollup.submittedDays;
                totalExpectedDays += expectedDays;
                totalBusinessVolume += rollup.businessVolume;
                totalRiskRows += rollup.riskCount;
                totalUnverifiedRows += rollup.unverifiedCount;
                totalAttentionRows += rollup.attentionCount;
                totalAbnormalRows += rollup.abnormalCount;
                totalSpecialRows += rollup.specialPendingNoteCount;
            }
            int departmentLastRow = row - 1;
            row = writeDataXlsxRow(departmentSheet, row, new CellStyle[] { totalStyle, totalStyle, totalStyle, totalStyle, totalPercentageStyle, totalStyle, totalStyle, totalStyle, totalStyle, totalStyle, totalStyle, totalStyle, totalStyle },
                "合计", null, totalSubmittedDays, totalExpectedDays, percentageValue(totalExpectedDays == 0 ? 0 : (double) totalSubmittedDays / totalExpectedDays),
                totalBusinessVolume, totalRiskRows, totalUnverifiedRows, totalAttentionRows, totalAbnormalRows, totalSpecialRows, null, null);
            setColumnWidths(departmentSheet, 20, 14, 14, 14, 12, 14, 12, 12, 12, 12, 15, 18, 24);
            departmentSheet.createFreezePane(0, 4);
            departmentSheet.setAutoFilter(new CellRangeAddress(3, Math.max(3, departmentLastRow), 0, 12));
            addCompletionRateConditionalFormat(departmentSheet, 4, departmentFirstRow, departmentLastRow);

            // P2：异常核查先按优先级+日期排序，让 P1 异常始终在最前
            List<JsonNode> exceptionLines = new ArrayList<>();
            for (JsonNode line : report.path("details")) {
                String risk = text(line, "riskLevel");
                if (!"NORMAL".equals(risk) && !"SPECIAL".equals(risk)) exceptionLines.add(line);
            }
            exceptionLines.sort(java.util.Comparator
                .comparingInt((JsonNode line) -> exceptionPriority(text(line, "riskLevel")))
                .thenComparing(line -> text(line, "businessDate")));

            Sheet exceptionSheet = workbook.createSheet("异常核查");
            row = 0;
            row = writeXlsxRow(exceptionSheet, row, titleStyle, "异常、未核验与待说明明细（按优先级排序，P1 异常最前）");
            row = writeXlsxRow(exceptionSheet, row, null, "查询起止日期", report.path("periodStart").asText() + " 至 " + report.path("periodEnd").asText());
            row++;
            row = writeXlsxRow(exceptionSheet, row, headerStyle, "优先级", "业务日期", "科室", "耗材", "单位", "实际状态", "理论使用量", "实际使用量", "差额", "偏差率", "风险等级", "特殊说明", "复核状态", "复核备注", "建议动作");
            int exceptionFirstRow = row;
            for (JsonNode line : exceptionLines) {
                String risk = text(line, "riskLevel");
                row = writeDataXlsxRow(exceptionSheet, row, new CellStyle[] { null, dateStyle, null, null, null, null, null, null, null, percentageStyle, null, null, null, null, null },
                    exceptionPriority(risk), excelDate(text(line, "businessDate")), text(line, "departmentName"), text(line, "materialName"), text(line, "unit"),
                    actualStatusLabel(text(line, "actualStatus")), nullableNumber(line, "theoreticalQuantity"), nullableNumber(line, "actualQuantity"), nullableNumber(line, "difference"),
                    nullablePercentage(line, "deviationRate"), riskLabel(risk), textOrNull(text(line, "specialDailyNote")), reviewLabel(text(line, "reviewStatus")),
                    textOrNull(text(line, "reviewNote")), exceptionAction(risk));
            }
            int exceptionLastRow = row - 1;
            setColumnWidths(exceptionSheet, 10, 14, 16, 22, 10, 14, 15, 15, 12, 12, 18, 30, 14, 30, 18);
            exceptionSheet.createFreezePane(0, 4);
            exceptionSheet.setAutoFilter(new CellRangeAddress(3, Math.max(3, exceptionLastRow), 0, 14));
            addDeviationConditionalFormat(exceptionSheet, 9, exceptionFirstRow, exceptionLastRow);
            addReviewStatusValidation(exceptionSheet, 12, exceptionFirstRow, exceptionLastRow);

            // P3：耗材汇总降噪 —— 4 个风险列合并为“风险行数+风险构成”，覆盖科室折叠，末尾合计行
            Sheet summarySheet = workbook.createSheet("耗材汇总");
            row = 0;
            row = writeXlsxRow(summarySheet, row, titleStyle, "耗材理论、实际与金额汇总（按实际金额降序，仅保留有效行）");
            row = writeXlsxRow(summarySheet, row, null, "查询起止日期", report.path("periodStart").asText() + " 至 " + report.path("periodEnd").asText());
            row++;
            row = writeXlsxRow(summarySheet, row, headerStyle, "耗材", "单位", "理论使用量", "实际使用量", "理论金额", "实际金额", "金额偏差", "金额偏差率", "实际填报覆盖率", "核价覆盖率", "风险行数", "风险构成", "覆盖科室数", "覆盖科室");
            int summaryFirstRow = row;
            double totalTheoreticalQuantity = 0;
            double totalActualQuantity = 0;
            double totalTheoreticalAmount = 0;
            double totalActualAmount = 0;
            double totalAmountDifference = 0;
            int totalMaterialRiskRows = 0;
            for (JsonNode line : report.path("summary")) {
                boolean meaningful = line.path("theoreticalQuantity").asDouble() != 0 || line.path("reportedLineCount").asInt() > 0 || riskTotal(line) > 0;
                if (!meaningful) continue;
                row = writeDataXlsxRow(summarySheet, row, new CellStyle[] { null, null, null, null, amountStyle, amountStyle, amountStyle, percentageStyle, percentageStyle, percentageStyle, null, null, null, null },
                    text(line, "materialName"), text(line, "unit"),
                    line.path("theoreticalQuantity").asDouble(), nullableNumber(line, "actualQuantity"), nullableNumber(line, "theoreticalAmount"),
                    nullableNumber(line, "actualAmount"), nullableNumber(line, "amountDifference"), nullablePercentage(line, "amountDeviationRate"),
                    percentageValue(line.path("actualCoverageRate").asDouble()),
                    percentageValue(line.path("pricingCoverageRate").asDouble()), riskTotal(line), riskComposition(line),
                    line.path("departmentCount").asInt(), joinText(line.path("departments")));
                totalTheoreticalQuantity += line.path("theoreticalQuantity").asDouble();
                if (!line.path("actualQuantity").isNull()) totalActualQuantity += line.path("actualQuantity").asDouble();
                if (!line.path("theoreticalAmount").isNull()) totalTheoreticalAmount += line.path("theoreticalAmount").asDouble();
                if (!line.path("actualAmount").isNull()) totalActualAmount += line.path("actualAmount").asDouble();
                if (!line.path("amountDifference").isNull()) totalAmountDifference += line.path("amountDifference").asDouble();
                totalMaterialRiskRows += riskTotal(line);
            }
            int summaryLastRow = row - 1;
            row = writeDataXlsxRow(summarySheet, row, new CellStyle[] { totalStyle, totalStyle, totalStyle, totalStyle, totalAmountStyle, totalAmountStyle, totalAmountStyle, totalStyle, totalStyle, totalStyle, totalStyle, totalStyle, totalStyle, totalStyle },
                "合计", null, totalTheoreticalQuantity, totalActualQuantity, totalTheoreticalAmount, totalActualAmount, totalAmountDifference,
                null, null, null, totalMaterialRiskRows, null, null, null);
            setColumnWidths(summarySheet, 22, 10, 15, 15, 15, 15, 15, 15, 17, 15, 12, 24, 13, 36);
            summarySheet.createFreezePane(0, 4);
            summarySheet.setAutoFilter(new CellRangeAddress(3, Math.max(3, summaryLastRow), 0, 13));
            summarySheet.groupColumn(13, 13);
            summarySheet.setColumnGroupCollapsed(13, true);

            // P1：日期改 Excel 日期类型，空值统一为空白单元格
            Sheet progress = workbook.createSheet("日期科室明细");
            row = 0;
            row = writeXlsxRow(progress, row, titleStyle, "日期 × 12 科室填报明细");
            row = writeXlsxRow(progress, row, null, "查询起止日期", report.path("periodStart").asText() + " 至 " + report.path("periodEnd").asText());
            row++;
            row = writeXlsxRow(progress, row, headerStyle, "业务日期", "科室", "填报状态", "业务量/患者数", "风险条目", "待核验", "关注", "异常", "特殊待说明", "填报人", "更新时间");
            for (JsonNode day : report.path("departmentDays")) row = writeDataXlsxRow(progress, row,
                new CellStyle[] { dateStyle, null, null, null, null, null, null, null, null, null, dateTimeStyle },
                excelDate(text(day, "businessDate")), text(day, "departmentName"), departmentDayStatusLabel(text(day, "status")),
                day.path("businessVolume").isNull() ? null : day.path("businessVolume").asInt(), day.path("riskCount").asInt(),
                day.path("unverifiedCount").asInt(), day.path("attentionCount").asInt(), day.path("abnormalCount").asInt(),
                day.path("specialPendingNoteCount").asInt(), textOrNull(text(day, "operator")), excelDateTime(text(day, "updatedAt")));
            setColumnWidths(progress, 14, 16, 14, 16, 12, 12, 12, 12, 15, 16, 24);
            progress.createFreezePane(0, 4);
            progress.setAutoFilter(new CellRangeAddress(3, Math.max(3, row - 1), 0, 10));

            // P1/P4：审计明细日期时间类型 + 定额/复核追溯列分组折叠
            Sheet detailsSheet = workbook.createSheet("审计明细");
            row = 0;
            row = writeXlsxRow(detailsSheet, row, titleStyle, "逐日科室耗材审计明细（定额与复核列默认折叠）");
            row = writeXlsxRow(detailsSheet, row, null, "查询起止日期", report.path("periodStart").asText() + " 至 " + report.path("periodEnd").asText());
            row++;
            row = writeXlsxRow(detailsSheet, row, headerStyle, "业务日期", "科室", "耗材", "单位", "业务量/患者数", "理论使用量", "实际使用量", "单价", "理论金额", "实际金额", "实际状态", "差额", "偏差率", "风险等级", "特殊说明", "复核状态", "复核备注", "定额版本", "每人次定额", "固定调整", "复核人", "复核时间", "填报人", "更新时间");
            int detailFirstRow = row;
            for (JsonNode line : report.path("details")) row = writeDataXlsxRow(detailsSheet, row,
                new CellStyle[] { dateStyle, null, null, null, null, null, null, amountStyle, amountStyle, amountStyle, null, null, percentageStyle, null, null, null, null, null, null, null, null, dateTimeStyle, null, dateTimeStyle },
                excelDate(text(line, "businessDate")), text(line, "departmentName"), text(line, "materialName"), text(line, "unit"), line.path("volume").asInt(),
                nullableNumber(line, "theoreticalQuantity"), nullableNumber(line, "actualQuantity"), nullableNumber(line, "unitPrice"), nullableNumber(line, "theoreticalAmount"),
                nullableNumber(line, "actualAmount"), actualStatusLabel(text(line, "actualStatus")), nullableNumber(line, "difference"), nullablePercentage(line, "deviationRate"),
                riskLabel(text(line, "riskLevel")), textOrNull(text(line, "specialDailyNote")), reviewLabel(text(line, "reviewStatus")), textOrNull(text(line, "reviewNote")),
                textOrNull(text(line, "quotaVersionCode")), nullableNumber(line, "standardQuantity"), nullableNumber(line, "fixedAdjustment"), textOrNull(text(line, "reviewerName")),
                excelDateTime(text(line, "reviewedAt")), textOrNull(text(line, "operator")), excelDateTime(text(line, "updatedAt")));
            setColumnWidths(detailsSheet, 14, 16, 22, 10, 14, 15, 15, 12, 15, 15, 12, 12, 12, 18, 30, 14, 30, 16, 14, 12, 14, 24, 14, 24);
            detailsSheet.createFreezePane(0, 4);
            detailsSheet.setAutoFilter(new CellRangeAddress(3, Math.max(3, row - 1), 0, 23));
            addDeviationConditionalFormat(detailsSheet, 12, detailFirstRow, row - 1);
            detailsSheet.groupColumn(17, 21);
            detailsSheet.setColumnGroupCollapsed(17, true);
            hideEmptyColumns(detailsSheet, detailFirstRow, row, 14, 16);

            // P4：口径说明补充示例列，降低口径歧义
            Sheet glossary = workbook.createSheet("口径说明");
            row = 0;
            row = writeXlsxRow(glossary, row, titleStyle, "管理员 12 科室日报导出说明");
            row = writeXlsxRow(glossary, row, headerStyle, "术语", "说明", "示例");
            row = writeXlsxRow(glossary, row, null, "科室日", "一个科室在一个业务日期的填报单元；完成率 = 已提交科室日 / 应提交科室日", "12 科室 × 7 天 = 84 个应填报科室日；提交 72 个则完成率 85.7%");
            row = writeXlsxRow(glossary, row, null, "风险条目", "按耗材明细行统计，不等同于科室数、患者数或风险事件数", "同一天纱布 3 行未核验 → 未核验 = 3 行");
            row = writeXlsxRow(glossary, row, null, "未填报", "应填报但没有保存日报；不等同于明确填报为 0", "当日无保存记录的科室显示“未填报”");
            row = writeXlsxRow(glossary, row, null, "未核验", "实际使用量为空，不能按 0 参与金额或偏差计算", "实际使用量为空白，金额与偏差率留空");
            row = writeXlsxRow(glossary, row, null, "未核价", "存在数量但缺少单价，相关金额保留为空", "实际量 100 且单价空白 → 实际金额空白");
            row = writeXlsxRow(glossary, row, null, "0", "明确填报的零值，与空值、未填报、未核价不同", "实际使用量填 0 表示确认未使用");
            row = writeXlsxRow(glossary, row, null, "空白单元格", "数据不适用或尚未产生，不是 0", "未填报科室的业务量为空白而非 0");
            row = writeXlsxRow(glossary, row, null, "金额口径", "实际金额仅统计已填报且已核价的明细行", "10 行中 8 行已核价 → 金额仅含 8 行");
            setColumnWidths(glossary, 18, 60, 55);
            glossary.createFreezePane(0, 2);

            workbook.write(output);
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("生成管理员日报 XLSX 失败", exception);
        }
    }

    private static ObjectNode summaryRow(AdminDailyRollupLine line) {
        ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("materialName", line.materialName());
        row.put("unit", line.unit());
        row.put("theoreticalQuantity", line.theoreticalQuantity());
        if (line.reportedLineCount() == 0) row.putNull("actualQuantity"); else row.put("actualQuantity", line.actualQuantity());
        row.put("mainQuantity", line.mainQuantity());
        ArrayNode departments = row.putArray("departments");
        line.departments().stream().sorted().forEach(departments::add);
        row.put("departmentCount", line.departments().size());
        row.put("specialLineCount", line.specialLineCount());
        row.put("lineCount", line.lineCount());
        row.put("reportedLineCount", line.reportedLineCount());
        row.put("filledActualLineCount", line.reportedLineCount());
        row.put("unverifiedCount", line.unverifiedCount());
        row.put("attentionCount", line.attentionCount());
        row.put("abnormalCount", line.abnormalCount());
        row.put("specialPendingNoteCount", line.specialPendingNoteCount());
        row.put("pricedLineCount", line.pricedLineCount());
        row.put("pricedActualLineCount", line.pricedActualLineCount());
        row.put("unpricedLineCount", line.lineCount() - line.pricedLineCount());
        row.put("actualCoverageRate", ratio(line.reportedLineCount(), line.lineCount()));
        row.put("pricingCoverageRate", ratio(line.pricedActualLineCount(), line.reportedLineCount()));
        row.put("theoreticalPricingCoverageRate", ratio(line.pricedLineCount(), line.lineCount()));
        if (line.pricedLineCount() == 0) row.putNull("theoreticalAmount"); else row.put("theoreticalAmount", line.theoreticalAmount());
        if (line.pricedActualLineCount() == 0) row.putNull("actualAmount"); else row.put("actualAmount", line.actualAmount());
        if (line.pricedLineCount() == 0) row.putNull("mainAmount"); else row.put("mainAmount", line.mainAmount());
        if (line.amountDifferenceLineCount() == 0) row.putNull("amountDifference"); else row.put("amountDifference", line.amountDifference());
        if (line.amountDifferenceLineCount() == 0 || line.theoreticalAmount() == 0) row.putNull("amountDeviationRate");
        else row.put("amountDeviationRate", round(line.amountDifference() / line.theoreticalAmount(), 6));
        return row;
    }

    private static ObjectNode buildDashboard(LocalDate from, LocalDate to, ArrayNode departmentDays, ArrayNode details, ArrayNode summaryRows) {
        ObjectNode dashboard = JsonNodeFactory.instance.objectNode();
        int expectedDepartmentDays = departmentDays.size();
        int submittedDepartmentDays = 0;
        int missingDepartmentDays = 0;
        int zeroVolumeDepartmentDays = 0;
        for (JsonNode day : departmentDays) {
            String status = textStatic(day, "status");
            if ("MISSING".equals(status)) missingDepartmentDays++;
            else submittedDepartmentDays++;
            if ("ZERO_VOLUME".equals(status)) zeroVolumeDepartmentDays++;
        }

        int unverifiedCount = 0;
        int attentionCount = 0;
        int abnormalCount = 0;
        int specialPendingNoteCount = 0;
        int reportedLineCount = 0;
        int pricedActualLineCount = 0;
        double actualAmount = 0;
        for (JsonNode detail : details) {
            String risk = textStatic(detail, "riskLevel");
            if ("UNVERIFIED".equals(risk)) unverifiedCount++;
            else if ("ATTENTION".equals(risk)) attentionCount++;
            else if ("ABNORMAL".equals(risk) || "HISTORICAL_UNFROZEN".equals(risk)) abnormalCount++;
            else if ("SPECIAL_PENDING_NOTE".equals(risk)) specialPendingNoteCount++;
            if (!detail.path("actualQuantity").isNull() && !detail.path("actualQuantity").isMissingNode()) {
                reportedLineCount++;
                if (!detail.path("actualAmount").isNull() && !detail.path("actualAmount").isMissingNode()) {
                    pricedActualLineCount++;
                    actualAmount += detail.path("actualAmount").asDouble();
                }
            }
        }
        dashboard.put("periodStart", from.toString());
        dashboard.put("periodEnd", to.toString());
        dashboard.put("expectedDepartmentDays", expectedDepartmentDays);
        dashboard.put("submittedDepartmentDays", submittedDepartmentDays);
        dashboard.put("completionRate", ratio(submittedDepartmentDays, expectedDepartmentDays));
        dashboard.put("missingDepartmentDays", missingDepartmentDays);
        dashboard.put("zeroVolumeDepartmentDays", zeroVolumeDepartmentDays);
        dashboard.put("unverifiedCount", unverifiedCount);
        dashboard.put("attentionCount", attentionCount);
        dashboard.put("abnormalCount", abnormalCount);
        dashboard.put("specialPendingNoteCount", specialPendingNoteCount);
        dashboard.put("reportedLineCount", reportedLineCount);
        dashboard.put("pricedActualLineCount", pricedActualLineCount);
        dashboard.put("pricingCoverageRate", ratio(pricedActualLineCount, reportedLineCount));
        if (pricedActualLineCount == 0) dashboard.putNull("actualAmount"); else dashboard.put("actualAmount", round(actualAmount, 2));

        ArrayNode dailyTrend = dashboard.putArray("dailyTrend");
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            String dateText = date.toString();
            int expected = 0;
            int submitted = 0;
            int missing = 0;
            int zeroVolume = 0;
            for (JsonNode day : departmentDays) {
                if (!dateText.equals(textStatic(day, "businessDate"))) continue;
                expected++;
                if ("MISSING".equals(textStatic(day, "status"))) missing++; else submitted++;
                if ("ZERO_VOLUME".equals(textStatic(day, "status"))) zeroVolume++;
            }
            int dayUnverified = 0;
            int dayAttention = 0;
            int dayAbnormal = 0;
            int daySpecialPending = 0;
            int dayLineCount = 0;
            int dayPricedLineCount = 0;
            int dayReportedLineCount = 0;
            int dayPricedActualLineCount = 0;
            double theoreticalAmount = 0;
            double dayActualAmount = 0;
            for (JsonNode detail : details) {
                if (!dateText.equals(textStatic(detail, "businessDate"))) continue;
                dayLineCount++;
                String risk = textStatic(detail, "riskLevel");
                if ("UNVERIFIED".equals(risk)) dayUnverified++;
                else if ("ATTENTION".equals(risk)) dayAttention++;
                else if ("ABNORMAL".equals(risk) || "HISTORICAL_UNFROZEN".equals(risk)) dayAbnormal++;
                else if ("SPECIAL_PENDING_NOTE".equals(risk)) daySpecialPending++;
                if (!detail.path("theoreticalAmount").isNull() && !detail.path("theoreticalAmount").isMissingNode()) {
                    dayPricedLineCount++;
                    theoreticalAmount += detail.path("theoreticalAmount").asDouble();
                }
                if (!detail.path("actualQuantity").isNull() && !detail.path("actualQuantity").isMissingNode()) {
                    dayReportedLineCount++;
                    if (!detail.path("actualAmount").isNull() && !detail.path("actualAmount").isMissingNode()) {
                        dayPricedActualLineCount++;
                        dayActualAmount += detail.path("actualAmount").asDouble();
                    }
                }
            }
            ObjectNode day = dailyTrend.addObject();
            day.put("businessDate", dateText);
            day.put("expectedDepartmentDays", expected);
            day.put("submittedDepartmentDays", submitted);
            day.put("missingDepartmentDays", missing);
            day.put("zeroVolumeDepartmentDays", zeroVolume);
            day.put("completionRate", ratio(submitted, expected));
            day.put("lineCount", dayLineCount);
            day.put("reportedLineCount", dayReportedLineCount);
            day.put("pricedLineCount", dayPricedLineCount);
            day.put("pricedActualLineCount", dayPricedActualLineCount);
            day.put("unpricedLineCount", dayLineCount - dayPricedLineCount);
            day.put("unverifiedCount", dayUnverified);
            day.put("attentionCount", dayAttention);
            day.put("abnormalCount", dayAbnormal);
            day.put("specialPendingNoteCount", daySpecialPending);
            day.put("pricingCoverageRate", ratio(dayPricedActualLineCount, dayReportedLineCount));
            if (dayPricedLineCount == 0) day.putNull("theoreticalAmount"); else day.put("theoreticalAmount", round(theoreticalAmount, 2));
            if (dayPricedActualLineCount == 0) day.putNull("actualAmount"); else day.put("actualAmount", round(dayActualAmount, 2));
        }

        Map<String, ObjectNode> departmentRisk = new LinkedHashMap<>();
        for (Map.Entry<String, String> department : DEPARTMENTS.entrySet()) {
            ObjectNode row = JsonNodeFactory.instance.objectNode();
            row.put("departmentKey", department.getKey());
            row.put("departmentName", department.getValue());
            row.put("unverifiedCount", 0);
            row.put("attentionCount", 0);
            row.put("abnormalCount", 0);
            row.put("specialPendingNoteCount", 0);
            departmentRisk.put(department.getKey(), row);
        }
        for (JsonNode detail : details) {
            ObjectNode row = departmentRisk.get(textStatic(detail, "departmentKey"));
            if (row == null) continue;
            String risk = textStatic(detail, "riskLevel");
            if ("UNVERIFIED".equals(risk)) row.put("unverifiedCount", row.path("unverifiedCount").asInt() + 1);
            else if ("ATTENTION".equals(risk)) row.put("attentionCount", row.path("attentionCount").asInt() + 1);
            else if ("ABNORMAL".equals(risk) || "HISTORICAL_UNFROZEN".equals(risk)) row.put("abnormalCount", row.path("abnormalCount").asInt() + 1);
            else if ("SPECIAL_PENDING_NOTE".equals(risk)) row.put("specialPendingNoteCount", row.path("specialPendingNoteCount").asInt() + 1);
        }
        ArrayNode departmentRiskRows = dashboard.putArray("departmentRisk");
        departmentRisk.values().stream()
            .peek(row -> row.put("riskTotal", riskTotal(row)))
            .sorted((left, right) -> Integer.compare(riskTotal(right), riskTotal(left)))
            .forEach(departmentRiskRows::add);

        List<JsonNode> summaries = new ArrayList<>();
        summaryRows.forEach(summaries::add);
        ArrayNode materialAmountTop = dashboard.putArray("materialAmountTop");
        summaries.stream()
            .filter(row -> !row.path("actualAmount").isNull())
            .sorted((left, right) -> Double.compare(right.path("actualAmount").asDouble(), left.path("actualAmount").asDouble()))
            .limit(10)
            .forEach(row -> materialAmountTop.add(row.deepCopy()));
        ArrayNode materialDeviationTop = dashboard.putArray("materialDeviationTop");
        summaries.stream()
            .filter(row -> !row.path("amountDifference").isNull())
            .sorted((left, right) -> Double.compare(Math.abs(right.path("amountDifference").asDouble()), Math.abs(left.path("amountDifference").asDouble())))
            .limit(10)
            .forEach(row -> materialDeviationTop.add(row.deepCopy()));
        return dashboard;
    }

    private static int riskTotal(JsonNode row) {
        return row.path("unverifiedCount").asInt() + row.path("attentionCount").asInt()
            + row.path("abnormalCount").asInt() + row.path("specialPendingNoteCount").asInt();
    }

    private static double ratio(int numerator, int denominator) {
        return denominator <= 0 ? 0 : round((double) numerator / denominator, 6);
    }

    private static Double nullableNumber(JsonNode node, String field) {
        return node.path(field).isMissingNode() || node.path(field).isNull() ? null : node.path(field).asDouble();
    }

    private static Double nullablePercentage(JsonNode node, String field) {
        return node.path(field).isMissingNode() || node.path(field).isNull() ? null : node.path(field).asDouble();
    }

    private static Double percentageValue(double value) {
        return value;
    }

    private static String departmentDayStatusLabel(String value) {
        return switch (value) {
            case "MISSING" -> "未填报";
            case "ZERO_VOLUME" -> "零业务量";
            case "ATTENTION" -> "已提交·待关注";
            case "ABNORMAL" -> "已提交·有异常";
            default -> "已提交";
        };
    }

    private static String actualStatusLabel(String value) { return "UNVERIFIED".equals(value) ? "待核验" : "已填报"; }
    private static String reviewLabel(String value) { return switch (value) { case "EXPLAINED" -> "已说明"; case "REVIEWED" -> "已复核"; case "CLOSED" -> "已关闭"; default -> "待核查"; }; }
    private static String riskLabel(String value) { return switch (value) { case "UNVERIFIED" -> "未核验"; case "ATTENTION" -> "关注"; case "ABNORMAL" -> "异常"; case "SPECIAL_PENDING_NOTE" -> "特殊待说明"; case "SPECIAL" -> "特殊耗材"; case "HISTORICAL_UNFROZEN" -> "历史未冻结"; default -> "正常"; }; }

    private static void hideEmptyColumns(Sheet sheet, int firstDataRow, int rowCount, int... columns) {
        for (int column : columns) {
            boolean hasValue = false;
            for (int rowIndex = firstDataRow; rowIndex < rowCount; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                Cell cell = row == null ? null : row.getCell(column);
                if (cell != null && !cell.toString().isBlank()) { hasValue = true; break; }
            }
            if (!hasValue) sheet.setColumnHidden(column, true);
        }
    }

    private static String[] comparisonCsvRow(JsonNode line) {
        return new String[] {
            textStatic(line, "businessDate"), textStatic(line, "departmentName"), textStatic(line, "materialName"), textStatic(line, "unit"),
            quantityNodeText(line, "theoreticalQuantity"), quantityNodeText(line, "actualQuantity"), quantityNodeText(line, "difference"), percentageNodeText(line, "deviationRate"),
            textStatic(line, "riskLevel"), line.path("isSpecial").asBoolean(false) ? "是" : "否", textStatic(line, "specialDailyNote"), textStatic(line, "reviewStatus"), textStatic(line, "reviewNote")
        };
    }

    private static String[] detailedCsvRow(JsonNode line) {
        return new String[] {
            textStatic(line, "businessDate"), textStatic(line, "departmentName"), line.path("volume").asText("0"), textStatic(line, "materialName"), textStatic(line, "unit"),
            textStatic(line, "quotaVersionCode"), quantityNodeText(line, "standardQuantity"), quantityNodeText(line, "fixedAdjustment"), quantityNodeText(line, "theoreticalQuantity"),
            quantityNodeText(line, "actualQuantity"), textStatic(line, "actualStatus"), quantityNodeText(line, "difference"), percentageNodeText(line, "deviationRate"),
            textStatic(line, "riskLevel"), line.path("isSpecial").asBoolean(false) ? "是" : "否", textStatic(line, "specialDailyNote"), textStatic(line, "reviewStatus"),
            textStatic(line, "reviewNote"), textStatic(line, "reviewerName"), textStatic(line, "reviewedAt"), textStatic(line, "operator"), textStatic(line, "updatedAt")
        };
    }

    private static String textStatic(JsonNode node, String field) { return node == null ? "" : node.path(field).asText("").trim(); }
    private static String quantityNodeText(JsonNode node, String field) { return node.path(field).isMissingNode() || node.path(field).isNull() ? "" : quantityText(node.path(field).asDouble()); }
    private static String percentageNodeText(JsonNode node, String field) { return node.path(field).isMissingNode() || node.path(field).isNull() ? "" : quantityText(node.path(field).asDouble() * 100) + "%"; }

    private static int writeXlsxRow(Sheet sheet, int rowIndex, CellStyle style, Object... values) {
        Row row = sheet.createRow(rowIndex);
        for (int index = 0; index < values.length; index++) {
            Cell cell = row.createCell(index);
            if (style != null) cell.setCellStyle(style);
            setCellValue(cell, values[index]);
        }
        return rowIndex + 1;
    }

    private static int writeDataXlsxRow(Sheet sheet, int rowIndex, CellStyle[] colStyles, Object... values) {
        Row row = sheet.createRow(rowIndex);
        for (int index = 0; index < values.length; index++) {
            Cell cell = row.createCell(index);
            if (colStyles != null && index < colStyles.length && colStyles[index] != null) {
                cell.setCellStyle(colStyles[index]);
            }
            setCellValue(cell, values[index]);
        }
        return rowIndex + 1;
    }

    private static void setCellValue(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Date date) {
            cell.setCellValue(date);
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    private static Date excelDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Date.from(LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Date excelDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Date.from(LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant());
        } catch (Exception ignored) {
            return excelDate(value);
        }
    }

    private static String textOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static int exceptionPriority(String risk) {
        return "ABNORMAL".equals(risk) ? 1 : "UNVERIFIED".equals(risk) ? 2 : "SPECIAL_PENDING_NOTE".equals(risk) ? 3 : 4;
    }

    private static String exceptionAction(String risk) {
        return "UNVERIFIED".equals(risk) ? "补填实际量" : "SPECIAL_PENDING_NOTE".equals(risk) ? "补充特殊说明" : "ABNORMAL".equals(risk) ? "确认异常偏差" : "完成复核";
    }

    private static String riskComposition(JsonNode line) {
        List<String> parts = new ArrayList<>();
        if (line.path("unverifiedCount").asInt() > 0) parts.add("未核验" + line.path("unverifiedCount").asInt());
        if (line.path("attentionCount").asInt() > 0) parts.add("关注" + line.path("attentionCount").asInt());
        if (line.path("abnormalCount").asInt() > 0) parts.add("异常" + line.path("abnormalCount").asInt());
        if (line.path("specialPendingNoteCount").asInt() > 0) parts.add("待说明" + line.path("specialPendingNoteCount").asInt());
        return String.join("、", parts);
    }

    private static void addCompletionRateConditionalFormat(Sheet sheet, int column, int firstRow, int lastRow) {
        if (lastRow < firstRow) return;
        SheetConditionalFormatting formatting = sheet.getSheetConditionalFormatting();
        ConditionalFormattingRule incomplete = formatting.createConditionalFormattingRule(ComparisonOperator.LT, "1");
        PatternFormatting incompleteFill = incomplete.createPatternFormatting();
        incompleteFill.setFillBackgroundColor(IndexedColors.ROSE.getIndex());
        incompleteFill.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        incompleteFill.setFillPattern(PatternFormatting.SOLID_FOREGROUND);
        ConditionalFormattingRule complete = formatting.createConditionalFormattingRule(ComparisonOperator.GE, "1");
        PatternFormatting completeFill = complete.createPatternFormatting();
        completeFill.setFillBackgroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        completeFill.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        completeFill.setFillPattern(PatternFormatting.SOLID_FOREGROUND);
        formatting.addConditionalFormatting(new CellRangeAddress[] { new CellRangeAddress(firstRow, lastRow, column, column) },
            new ConditionalFormattingRule[] { incomplete, complete });
    }

    private static void addDeviationConditionalFormat(Sheet sheet, int column, int firstRow, int lastRow) {
        if (lastRow < firstRow) return;
        SheetConditionalFormatting formatting = sheet.getSheetConditionalFormatting();
        ConditionalFormattingRule over = formatting.createConditionalFormattingRule(ComparisonOperator.GT, "0.5");
        ConditionalFormattingRule under = formatting.createConditionalFormattingRule(ComparisonOperator.LT, "-0.5");
        for (ConditionalFormattingRule rule : new ConditionalFormattingRule[] { over, under }) {
            PatternFormatting fill = rule.createPatternFormatting();
            fill.setFillBackgroundColor(IndexedColors.ROSE.getIndex());
            fill.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            fill.setFillPattern(PatternFormatting.SOLID_FOREGROUND);
        }
        formatting.addConditionalFormatting(new CellRangeAddress[] { new CellRangeAddress(firstRow, lastRow, column, column) },
            new ConditionalFormattingRule[] { over, under });
    }

    private static void addReviewStatusValidation(Sheet sheet, int column, int firstRow, int lastRow) {
        if (lastRow < firstRow) return;
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createExplicitListConstraint(new String[] { "待核查", "已说明", "已复核", "已关闭" });
        DataValidation validation = helper.createValidation(constraint, new CellRangeAddressList(firstRow, lastRow, column, column));
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }

    /** 科室填报汇总的中间聚合体，供 XLSX 导出按完成率排序与生成合计行使用。 */
    private static final class DepartmentRollup {
        private final String departmentName;
        private final String operator;
        private final String updatedAt;
        private int submittedDays;
        private double businessVolume;
        private int riskCount;
        private int unverifiedCount;
        private int attentionCount;
        private int abnormalCount;
        private int specialPendingNoteCount;

        private DepartmentRollup(String departmentName, String operator, String updatedAt) {
            this.departmentName = departmentName;
            this.operator = operator;
            this.updatedAt = updatedAt;
        }

        private double completionRate(int expectedDays) {
            return expectedDays == 0 ? 0 : (double) submittedDays / expectedDays;
        }

        private String status(int expectedDays) {
            return submittedDays == expectedDays ? "已完成" : submittedDays == 0 ? "未填报" : "部分完成";
        }
    }

    private static void setColumnWidths(Sheet sheet, int... widths) {
        for (int index = 0; index < widths.length; index++) sheet.setColumnWidth(index, widths[index] * 256);
    }

    @Transactional
    public ObjectNode save(JsonNode payload, SessionUser user) {
        String departmentKey = text(payload, "departmentKey");
        String departmentName = requireDepartment(departmentKey, user);
        LocalDate businessDate = parseDate(text(payload, "businessDate"));
        JsonNode lines = payload == null ? null : payload.path("lines");
        if (!lines.isArray() || lines.size() > 400) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "耗材明细格式不正确");
        }
        int expectedRevision = Math.max(payload.path("revision").asInt(0), 0);
        DraftVersion current = jdbcTemplate.query(
            "SELECT id, revision, raw_json FROM inventory_department_daily_drafts WHERE department_key = ? AND business_date = ? FOR UPDATE",
            resultSet -> resultSet.next()
                ? new DraftVersion(resultSet.getString("id"), resultSet.getInt("revision"), text(readJson(resultSet.getString("raw_json")), "quotaVersionId"))
                : null,
            departmentKey,
            businessDate
        );
        InventoryQuotaGovernanceService.QuotaVersion quotaVersion = quotaGovernanceService.activeVersion(businessDate);
        if (current != null && !current.quotaVersionId().isBlank()
            && (quotaVersion == null || !current.quotaVersionId().equals(quotaVersion.id()))) {
            InventoryQuotaGovernanceService.QuotaVersion pinned = quotaGovernanceService.versionById(current.quotaVersionId());
            if (pinned != null) quotaVersion = pinned;
        }
        String templateVersion = quotaVersion == null ? "department-template-v1" : quotaVersion.versionCode();
        ObjectNode stored = JsonNodeFactory.instance.objectNode();
        stored.put("monthDays", Math.max(1, Math.min(payload.path("monthDays").asInt(30), 31)));
        stored.put("templateVersion", templateVersion);
        ObjectNode groupVolumes = canonicalGroupVolumes(payload.path("groupVolumes"));
        stored.set("groupVolumes", groupVolumes);
        if (quotaVersion == null) stored.put("frozenQuota", false);
        else {
            stored.put("frozenQuota", true);
            stored.put("quotaVersionId", quotaVersion.id());
            stored.put("quotaVersionCode", quotaVersion.versionCode());
            stored.put("quotaEffectiveDate", quotaVersion.effectiveDate().toString());
        }
        stored.set("lines", canonicalLines(lines, groupVolumes, departmentKey, quotaVersion));

        if (current == null) {
            if (expectedRevision != 0) throw staleDraft();
            jdbcTemplate.update(
                "INSERT INTO inventory_department_daily_drafts "
                    + "(id, department_key, department_name, business_date, template_version, revision, operator_name, operator_username, raw_json) "
                    + "VALUES (?, ?, ?, ?, ?, 1, ?, ?, CAST(? AS JSON))",
                "inv-department-draft-" + UUID.randomUUID(),
                departmentKey,
                departmentName,
                businessDate,
                templateVersion,
                user.name(),
                user.username(),
                json(stored)
            );
        } else {
            if (current.revision() != expectedRevision) throw staleDraft();
            jdbcTemplate.update(
                "UPDATE inventory_department_daily_drafts "
                    + "SET template_version = ?, revision = revision + 1, operator_name = ?, operator_username = ?, raw_json = CAST(? AS JSON) "
                    + "WHERE id = ?",
                templateVersion,
                user.name(),
                user.username(),
                json(stored),
                current.id()
            );
        }
        repository.log(user.name(), "保存科室耗材日草稿", "department_daily_draft", departmentName + " " + businessDate, "仅保存核算草稿，未生成库存流水");
        return read(departmentKey, businessDate, user);
    }

    @Transactional(readOnly = true)
    public byte[] export(String kind, JsonNode payload, SessionUser user) {
        if (!"details".equals(kind) && !"summary".equals(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的科室日核算导出类型");
        }
        String departmentKey = text(payload, "departmentKey");
        String departmentName = requireDepartment(departmentKey, user);
        LocalDate businessDate = parseDate(text(payload, "businessDate"));
        int monthDays = Math.max(1, Math.min(payload.path("monthDays").asInt(30), 31));
        Map<String, Integer> groupVolumes = new LinkedHashMap<>();
        JsonNode volumeNode = payload.path("groupVolumes");
        if (volumeNode.isObject()) {
            volumeNode.fields().forEachRemaining(entry -> groupVolumes.put(entry.getKey(), nonNegativeInteger(entry.getValue())));
        }
        List<DepartmentExportLine> lines = new ArrayList<>();
        JsonNode lineNode = payload.path("lines");
        if (lineNode.isArray()) {
            for (JsonNode line : lineNode) {
                String serviceGroup = text(line, "serviceGroup");
                int volume = line.hasNonNull("volumeOverride")
                    ? nonNegativeInteger(line.get("volumeOverride"))
                    : groupVolumes.getOrDefault(serviceGroup, 0);
                Double standard = nullableNonNegativeDouble(line.get("standardQuantity"));
                String unit = text(line, "unit");
                double reference = referenceQuantity(line, volume);
                Double actual = nullableNonNegativeDouble(line.get("actualQuantity"));
                Double weekly = actual == null ? null : round(actual * 7, 6);
                Double monthly = actual == null ? null : round(actual * monthDays, 6);
                Double unitPrice = nullableNonNegativeDouble(line.get("unitPrice"));
                Double amount = unitPrice == null || unit.isBlank() || monthly == null ? null : round(monthly * unitPrice, 2);
                String status = unit.isBlank() ? "待核定/无单位" : actual == null ? "未填报实际耗材" : "已填报实际耗材";
                lines.add(new DepartmentExportLine(
                    text(line, "serviceGroup"), text(line, "careType"), text(line, "materialName"), unit,
                    standard, volume, reference, actual, weekly, monthly, amount, status
                ));
            }
        }
        LocalDate weekStart = businessDate.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { "导出类型", "科室日核算" + ("details".equals(kind) ? "明细" : "汇总") });
        rows.add(new String[] { "科室编码", departmentKey });
        rows.add(new String[] { "科室名称", departmentName });
        rows.add(new String[] { "业务日期", businessDate.toString() });
        rows.add(new String[] { "自然周区间", weekStart + " 至 " + weekEnd });
        rows.add(new String[] { "门诊量", String.valueOf(volumeByCareType(groupVolumes, lines, "outpatient")) });
        rows.add(new String[] { "住院量", String.valueOf(volumeByCareType(groupVolumes, lines, "inpatient")) });
        rows.add(new String[] { "月天数", String.valueOf(monthDays) });
        rows.add(new String[] { "导出时间", OffsetDateTime.now(ZoneId.systemDefault()).toString() });
        rows.add(new String[] { "操作账号", user.username() });
        rows.add(new String[] { "责任人", user.name() });
        rows.add(new String[] { "草稿状态", payload.path("exists").asBoolean(false) && payload.path("revision").asInt(0) > 0 ? "已保存" : "未保存（当前编辑值）" });
        rows.add(new String[] { "计算版本", "department-daily-export-v1" });
        rows.add(new String[] {});
        if ("details".equals(kind)) {
            rows.add(new String[] { "服务项目/类型", "耗材", "单位", "每人次定额", "业务量/患者数", "参考试算（不计入统计）", "实际使用量", "按实际外推周量", "按实际外推月量", "金额", "状态" });
            lines.forEach(line -> rows.add(line.toCsv()));
        } else {
            rows.add(new String[] { "耗材", "单位", "实际使用量", "按实际外推周量", "按实际外推月量", "金额", "状态" });
            Map<String, DepartmentSummaryLine> summary = new LinkedHashMap<>();
            lines.forEach(line -> {
                if (!line.hasActualQuantity() || line.pending()) return;
                String key = line.materialName() + "\u0000" + line.unit();
                DepartmentSummaryLine current = summary.get(key);
                summary.put(key, current == null
                    ? new DepartmentSummaryLine(line.materialName(), line.unit(), line.actualQuantity(), line.weeklyQuantity(), line.monthlyQuantity(), line.amount())
                    : current.add(line));
            });
            summary.values().forEach(line -> rows.add(line.toCsv()));
            lines.stream().filter(DepartmentExportLine::pending).forEach(line -> rows.add(new String[] { line.materialName(), line.unit(), "", "", "", "", line.status() }));
        }
        return csv(rows);
    }

    private void appendAdminDailyRollup(
        ObjectNode draft,
        Map<String, AdminDailyRollupLine> summary,
        ArrayNode details,
        Map<String, InventoryQuotaGovernanceService.ReviewRecord> reviews
    ) {
        Map<String, Integer> groupVolumes = groupVolumes(draft.path("groupVolumes"));
        String departmentKey = text(draft, "departmentKey");
        String departmentName = text(draft, "departmentName");
        LocalDate businessDate = LocalDate.parse(text(draft, "businessDate"));
        boolean frozenQuota = draft.path("frozenQuota").asBoolean(false) && !text(draft, "quotaVersionId").isBlank();
        for (JsonNode line : draft.path("lines")) {
            String materialName = text(line, "materialName");
            String unit = text(line, "unit");
            if (materialName.isBlank() || unit.isBlank()) continue;
            int volume = line.hasNonNull("volumeOverride")
                ? nonNegativeInteger(line.get("volumeOverride"))
                : line.path("volume").asInt(groupVolumes.getOrDefault(text(line, "serviceGroup"), 0));
            Double standardQuantity = nullableNonNegativeDouble(line.get("standardQuantity"));
            double fixedAdjustment = finiteDouble(line.get("fixedAdjustment"), finiteDouble(line.get("manualAdjustment"), 0));
            double theoreticalQuantity = line.hasNonNull("referenceQuantity")
                ? Math.max(0, finiteDouble(line.get("referenceQuantity"), 0))
                : referenceQuantity(standardQuantity, volume, fixedAdjustment, line.path("isSupplemental").asBoolean(false));
            Double actualQuantity = nullableNonNegativeDouble(line.get("actualQuantity"));
            boolean special = line.path("isSpecial").asBoolean(false);
            String specialDailyNote = text(line, "specialDailyNote");
            String lineKey = text(line, "lineKey");
            if (lineKey.isBlank()) lineKey = text(line, "id");
            if (lineKey.isBlank()) lineKey = materialName + "\u0000" + unit + "\u0000" + line.path("sourceRow").asText("0");
            Double difference = actualQuantity == null ? null : round(actualQuantity - theoreticalQuantity, 6);
            Double deviationRate = actualQuantity == null || theoreticalQuantity == 0 ? null : round(difference / theoreticalQuantity, 6);
            String riskLevel = riskLevel(frozenQuota, special, theoreticalQuantity, actualQuantity, specialDailyNote);
            InventoryQuotaGovernanceService.ReviewRecord review = reviews.get(InventoryQuotaGovernanceService.reviewKey(businessDate, departmentKey, lineKey));

            Double unitPrice = nullableNonNegativeDouble(line.get("unitPrice"));
            double mainQuantity = special ? (actualQuantity == null ? 0 : actualQuantity) : theoreticalQuantity;
            Double theoreticalAmount = unitPrice == null ? null : round(theoreticalQuantity * unitPrice, 2);
            Double actualAmount = unitPrice == null || actualQuantity == null ? null : round(actualQuantity * unitPrice, 2);
            Double mainAmount = unitPrice == null || special && actualQuantity == null ? null : round(mainQuantity * unitPrice, 2);
            String summaryKey = materialName + "\u0000" + unit;
            AdminDailyRollupLine current = summary.get(summaryKey);
            AdminDailyRollupLine addition = new AdminDailyRollupLine(
                materialName, unit, theoreticalQuantity, actualQuantity == null ? 0 : actualQuantity, mainQuantity,
                Set.of(departmentKey), special ? 1 : 0, 1, actualQuantity == null ? 0 : 1, actualQuantity == null ? 1 : 0,
                "ATTENTION".equals(riskLevel) ? 1 : 0,
                "ABNORMAL".equals(riskLevel) || "HISTORICAL_UNFROZEN".equals(riskLevel) ? 1 : 0,
                "SPECIAL_PENDING_NOTE".equals(riskLevel) ? 1 : 0,
                unitPrice == null ? 0 : 1,
                theoreticalAmount == null ? 0 : theoreticalAmount,
                actualAmount == null ? 0 : actualAmount,
                mainAmount == null ? 0 : mainAmount,
                unitPrice == null || actualQuantity == null ? 0 : 1,
                unitPrice == null || actualQuantity == null ? 0 : 1,
                actualAmount == null ? 0 : actualAmount - (theoreticalAmount == null ? 0 : theoreticalAmount)
            );
            summary.put(summaryKey, current == null ? addition : current.add(addition));

            ObjectNode detail = details.addObject();
            detail.put("businessDate", businessDate.toString());
            detail.put("departmentKey", departmentKey);
            detail.put("departmentName", departmentName);
            detail.put("lineKey", lineKey);
            detail.put("materialName", materialName);
            detail.put("unit", unit);
            detail.put("serviceGroup", text(line, "serviceGroup"));
            detail.put("careType", text(line, "careType"));
            detail.put("measurementScope", text(line, "measurementScope"));
            detail.put("volume", volume);
            if (standardQuantity == null) detail.putNull("standardQuantity"); else detail.put("standardQuantity", standardQuantity);
            detail.put("fixedAdjustment", fixedAdjustment);
            detail.put("manualAdjustment", fixedAdjustment);
            detail.put("theoreticalQuantity", theoreticalQuantity);
            detail.put("referenceQuantity", theoreticalQuantity);
            if (actualQuantity == null) detail.putNull("actualQuantity"); else detail.put("actualQuantity", actualQuantity);
            detail.put("actualStatus", actualQuantity == null ? "UNVERIFIED" : "REPORTED");
            if (difference == null) detail.putNull("difference"); else detail.put("difference", difference);
            if (deviationRate == null) detail.putNull("deviationRate"); else detail.put("deviationRate", deviationRate);
            detail.put("riskLevel", riskLevel);
            detail.put("isSpecial", special);
            detail.put("specialAdminNote", text(line, "specialAdminNote"));
            detail.put("specialDailyNote", specialDailyNote);
            detail.put("mainQuantity", mainQuantity);
            if (unitPrice == null) detail.putNull("unitPrice"); else detail.put("unitPrice", unitPrice);
            if (theoreticalAmount == null) detail.putNull("theoreticalAmount"); else detail.put("theoreticalAmount", theoreticalAmount);
            if (actualAmount == null) detail.putNull("actualAmount"); else detail.put("actualAmount", actualAmount);
            if (mainAmount == null) detail.putNull("mainAmount"); else detail.put("mainAmount", mainAmount);
            detail.put("frozenQuota", frozenQuota);
            detail.put("quotaVersionCode", text(draft, "quotaVersionCode"));
            detail.put("isSupplemental", line.path("isSupplemental").asBoolean(false));
            detail.put("reviewStatus", review == null ? "PENDING" : review.reviewStatus());
            detail.put("reviewNote", review == null ? "" : review.reviewNote());
            detail.put("reviewerUsername", review == null ? "" : review.reviewerUsername());
            detail.put("reviewerName", review == null ? "" : review.reviewerName());
            detail.put("reviewedAt", review == null ? "" : review.reviewedAt());
            detail.put("operator", text(draft, "operator"));
            detail.put("operatorUsername", text(draft, "operatorUsername"));
            detail.put("updatedAt", text(draft, "updatedAt"));
        }
    }

    private static String riskLevel(boolean frozenQuota, boolean special, double theoreticalQuantity, Double actualQuantity, String specialDailyNote) {
        if (!frozenQuota) return "HISTORICAL_UNFROZEN";
        if (actualQuantity == null) return "UNVERIFIED";
        if (special) return actualQuantity > 0 && specialDailyNote.isBlank() ? "SPECIAL_PENDING_NOTE" : "SPECIAL";
        double difference = Math.abs(actualQuantity - theoreticalQuantity);
        if (theoreticalQuantity == 0) return actualQuantity > 0 ? "ABNORMAL" : "NORMAL";
        if (difference < 1) return "NORMAL";
        double rate = difference / theoreticalQuantity;
        if (rate >= 0.5) return "ABNORMAL";
        if (rate >= 0.2) return "ATTENTION";
        return "NORMAL";
    }

    private void requireAdministrator(SessionUser user) {
        if (!inventoryAccess.hasCapability(user, "inventory:role:manage")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅进销存管理员可查看全院 12 科室日报");
        }
    }

    private static String joinText(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            String text = value.asText("").trim();
            if (!text.isBlank()) result.add(text);
        });
        return String.join("、", result);
    }

    private ObjectNode row(ResultSet resultSet) throws SQLException {
        ObjectNode result = readJson(resultSet.getString("raw_json"));
        result.put("id", resultSet.getString("id"));
        result.put("departmentKey", resultSet.getString("department_key"));
        result.put("departmentName", resultSet.getString("department_name"));
        result.put("businessDate", resultSet.getDate("business_date").toLocalDate().toString());
        result.put("templateVersion", resultSet.getString("template_version"));
        result.put("revision", resultSet.getInt("revision"));
        result.put("operator", resultSet.getString("operator_name"));
        result.put("operatorUsername", resultSet.getString("operator_username"));
        result.put("updatedAt", resultSet.getTimestamp("updated_at").toLocalDateTime().toString());
        return result;
    }

    private ObjectNode empty(String departmentKey, String departmentName, LocalDate businessDate) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("departmentKey", departmentKey);
        result.put("departmentName", departmentName);
        result.put("businessDate", businessDate.toString());
        result.put("revision", 0);
        return result;
    }

    String requireDepartment(String departmentKey, SessionUser user) {
        String departmentName = DEPARTMENTS.get(departmentKey == null ? "" : departmentKey.trim());
        if (departmentName == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知科室核算模板");
        if (!inventoryAccess.canViewAllDepartments(user) && !departmentName.equals(user.department())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问其他科室的核算草稿");
        }
        return departmentName;
    }

    public static Map<String, String> departmentDirectory() { return DEPARTMENTS; }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "业务日期格式不正确");
        }
    }

    private String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("核算草稿序列化失败", error);
        }
    }

    private ObjectNode readJson(String value) {
        try {
            JsonNode parsed = objectMapper.readTree(value);
            return parsed != null && parsed.isObject() ? (ObjectNode) parsed : JsonNodeFactory.instance.objectNode();
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("核算草稿数据损坏", error);
        }
    }

    private ResponseStatusException staleDraft() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "草稿已被其他人更新，请刷新后再保存");
    }

    private static ObjectNode canonicalGroupVolumes(JsonNode source) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        if (!source.isObject()) return result;
        source.fields().forEachRemaining(entry -> result.put(entry.getKey(), nonNegativeInteger(entry.getValue())));
        return result;
    }

    private ArrayNode canonicalLines(JsonNode source, ObjectNode groupVolumes, String departmentKey, InventoryQuotaGovernanceService.QuotaVersion quotaVersion) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        if (!source.isArray()) return result;
        Map<String, Integer> volumes = groupVolumes(groupVolumes);
        Map<Integer, InventoryQuotaGovernanceService.QuotaRule> rules = new LinkedHashMap<>();
        Map<String, InventoryQuotaGovernanceService.SpecialRule> specialRules = quotaGovernanceService.specialRules(departmentKey);
        if (quotaVersion != null) {
            for (InventoryQuotaGovernanceService.QuotaRule rule : quotaGovernanceService.rules(quotaVersion.id(), departmentKey)) rules.put(rule.sourceRow(), rule);
        }
        int combinedVolume = combinedVolume(volumes, rules.values());
        for (JsonNode line : source) {
            if (!line.isObject()) continue;
            int sourceRow = nonNegativeInteger(line.get("sourceRow"));
            boolean supplemental = line.path("isSupplemental").asBoolean(false);
            InventoryQuotaGovernanceService.QuotaRule rule = rules.get(sourceRow);
            // 规则已被删除或停用时，既有行降级为补充行快照保留，不再阻断保存
            supplemental = supplemental || (quotaVersion != null && rule == null);
            ObjectNode canonical = result.addObject();
            String materialName = rule == null ? textValue(line, "materialName") : rule.materialName();
            String unit = rule == null ? textValue(line, "unit") : rule.unit();
            String serviceGroup = rule == null ? textValue(line, "serviceGroup") : rule.serviceGroup();
            String careType = rule == null ? textValue(line, "careType") : rule.careType();
            String measurementScope = rule == null ? textValue(line, "measurementScope") : rule.measurementScope();
            Double standardQuantity = supplemental ? null : rule == null ? nullableNonNegativeDouble(line.get("standardQuantity")) : rule.standardQuantity();
            double fixedAdjustment = supplemental ? 0 : rule == null ? finiteDouble(line.get("manualAdjustment"), 0) : rule.fixedAdjustment();
            String id = rule == null ? textValue(line, "id") : rule.id();
            String lineKey = rule == null ? textValue(line, "lineKey") : rule.id();
            if (lineKey.isBlank()) lineKey = id;
            canonical.put("id", id);
            canonical.put("lineKey", lineKey);
            canonical.put("sourceRow", sourceRow);
            canonical.put("serviceGroup", serviceGroup);
            canonical.put("careType", careType);
            canonical.put("measurementScope", measurementScope);
            canonical.put("materialName", materialName);
            canonical.put("unit", unit);
            canonical.put("isSupplemental", supplemental);
            if (standardQuantity == null) canonical.putNull("standardQuantity"); else canonical.put("standardQuantity", standardQuantity);
            Double unitPrice = nullableNonNegativeDouble(line.get("unitPrice"));
            if (unitPrice == null) canonical.putNull("unitPrice"); else canonical.put("unitPrice", unitPrice);
            if (line.hasNonNull("volumeOverride")) canonical.put("volumeOverride", nonNegativeInteger(line.get("volumeOverride")));
            int volume = line.hasNonNull("volumeOverride") ? nonNegativeInteger(line.get("volumeOverride")) : "COMBINED".equals(measurementScope) ? combinedVolume : volumes.getOrDefault(serviceGroup, 0);
            canonical.put("volume", volume);
            canonical.put("fixedAdjustment", fixedAdjustment);
            canonical.put("manualAdjustment", fixedAdjustment);
            Double actualQuantity = nullableNonNegativeDouble(line.get("actualQuantity"));
            if (actualQuantity == null) canonical.putNull("actualQuantity"); else canonical.put("actualQuantity", actualQuantity);
            InventoryQuotaGovernanceService.SpecialRule special = specialRules.get(InventoryQuotaGovernanceService.materialKey(materialName, unit));
            boolean isSpecial = special != null;
            String specialDailyNote = textValue(line, "specialDailyNote");
            if (isSpecial && actualQuantity != null && actualQuantity > 0 && specialDailyNote.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, materialName + " 为特殊耗材，实际使用量大于 0 时必须填写当日说明");
            }
            canonical.put("isSpecial", isSpecial);
            canonical.put("specialAdminNote", special == null ? "" : special.adminNote());
            canonical.put("specialDailyNote", specialDailyNote);
            double referenceQuantity = referenceQuantity(standardQuantity, volume, fixedAdjustment, supplemental);
            canonical.put("referenceQuantity", referenceQuantity);
            canonical.put("calculatedQuantity", referenceQuantity);
        }
        return result;
    }

    private void applyQuotaTemplate(ObjectNode result, String departmentKey, LocalDate businessDate) {
        applyQuotaTemplate(result, departmentKey, quotaGovernanceService.activeVersion(businessDate));
    }

    private void applyQuotaTemplate(ObjectNode result, String departmentKey, InventoryQuotaGovernanceService.QuotaVersion version) {
        if (version == null) {
            result.put("frozenQuota", false);
            return;
        }
        ObjectNode groupVolumes = result.putObject("groupVolumes");
        ArrayNode lines = result.putArray("lines");
        Map<String, InventoryQuotaGovernanceService.SpecialRule> specialRules = quotaGovernanceService.specialRules(departmentKey);
        for (InventoryQuotaGovernanceService.QuotaRule rule : quotaGovernanceService.rules(version.id(), departmentKey)) {
            ObjectNode line = lines.addObject();
            line.put("id", rule.id()); line.put("lineKey", rule.id()); line.put("sourceRow", rule.sourceRow());
            line.put("serviceGroup", rule.serviceGroup()); line.put("careType", rule.careType()); line.put("measurementScope", rule.measurementScope());
            line.put("materialName", rule.materialName()); line.put("unit", rule.unit());
            if (rule.standardQuantity() == null) line.putNull("standardQuantity"); else line.put("standardQuantity", rule.standardQuantity());
            line.put("fixedAdjustment", rule.fixedAdjustment()); line.put("manualAdjustment", rule.fixedAdjustment()); line.put("isSupplemental", false);
            line.putNull("actualQuantity"); line.put("volume", 0); line.put("referenceQuantity", 0); line.put("calculatedQuantity", 0);
            InventoryQuotaGovernanceService.SpecialRule special = specialRules.get(InventoryQuotaGovernanceService.materialKey(rule.materialName(), rule.unit()));
            line.put("isSpecial", special != null); line.put("specialAdminNote", special == null ? "" : special.adminNote()); line.put("specialDailyNote", "");
            groupVolumes.put(rule.serviceGroup(), 0);
        }
        result.put("quotaVersionId", version.id()); result.put("quotaVersionCode", version.versionCode()); result.put("quotaEffectiveDate", version.effectiveDate().toString()); result.put("frozenQuota", true); result.put("templateVersion", version.versionCode());
    }

    /** 方案B「当日即时应用」：仅对当日尚未填报的科室，用指定定额版本预播种草稿，不触碰任何既有草稿。 */
    @Transactional
    public ObjectNode applyVersionToday(String versionId, SessionUser user) {
        requireAdministrator(user);
        InventoryQuotaGovernanceService.QuotaVersion version = quotaGovernanceService.versionById(versionId);
        if (version == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "定额版本不存在");
        LocalDate today = LocalDate.now();
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("versionId", version.id());
        result.put("versionCode", version.versionCode());
        result.put("effectiveDate", version.effectiveDate().toString());
        ArrayNode seeded = result.putArray("seededDepartments");
        int skipped = 0;
        for (Map.Entry<String, String> department : DEPARTMENTS.entrySet()) {
            Boolean exists = jdbcTemplate.query(
                "SELECT 1 FROM inventory_department_daily_drafts WHERE department_key = ? AND business_date = ? LIMIT 1",
                ResultSet::next,
                department.getKey(),
                today
            );
            if (Boolean.TRUE.equals(exists)) { skipped++; continue; }
            ObjectNode stored = JsonNodeFactory.instance.objectNode();
            stored.put("monthDays", 30);
            applyQuotaTemplate(stored, department.getKey(), version);
            if (!stored.path("lines").isArray() || stored.path("lines").isEmpty()) { skipped++; continue; }
            try {
                jdbcTemplate.update(
                    "INSERT INTO inventory_department_daily_drafts "
                        + "(id, department_key, department_name, business_date, template_version, revision, operator_name, operator_username, raw_json) "
                        + "VALUES (?, ?, ?, ?, ?, 1, ?, ?, CAST(? AS JSON))",
                    "inv-department-draft-" + UUID.randomUUID(),
                    department.getKey(),
                    department.getValue(),
                    today,
                    version.versionCode(),
                    user.name(),
                    user.username(),
                    json(stored)
                );
                seeded.add(department.getValue());
            } catch (RuntimeException ignored) {
                skipped++;
            }
        }
        result.put("seededCount", seeded.size());
        result.put("skippedCount", skipped);
        if (!seeded.isEmpty()) {
            repository.log(user.name(), "定额控制台当日即时应用", "department_daily_draft", version.versionCode() + " " + today, "为 " + seeded.size() + " 个未填报科室预播种当日草稿");
        }
        return result;
    }

    private static int combinedVolume(Map<String, Integer> volumes, java.util.Collection<InventoryQuotaGovernanceService.QuotaRule> rules) {
        Set<String> groups = new LinkedHashSet<>();
        for (InventoryQuotaGovernanceService.QuotaRule rule : rules) {
            if ("outpatient".equals(rule.careType()) || "inpatient".equals(rule.careType())) groups.add(rule.serviceGroup());
        }
        return groups.stream().mapToInt(group -> volumes.getOrDefault(group, 0)).sum();
    }

    private static Map<String, Integer> groupVolumes(JsonNode source) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (source.isObject()) source.fields().forEachRemaining(entry -> result.put(entry.getKey(), nonNegativeInteger(entry.getValue())));
        return result;
    }

    private static int businessVolume(JsonNode draft) {
        return groupVolumes(draft.path("groupVolumes")).values().stream().mapToInt(Integer::intValue).sum();
    }

    private static double referenceQuantity(JsonNode line, int volume) {
        return referenceQuantity(
            nullableNonNegativeDouble(line.get("standardQuantity")),
            volume,
            finiteDouble(line.get("manualAdjustment"), 0),
            line.path("isSupplemental").asBoolean(false)
        );
    }

    private static double referenceQuantity(Double standardQuantity, int volume, double manualAdjustment, boolean supplemental) {
        return Math.max(0, round((supplemental || standardQuantity == null ? 0 : standardQuantity * volume) + manualAdjustment, 6));
    }

    private static String textValue(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }

    private static int nonNegativeInteger(JsonNode value) {
        if (value == null || !value.isNumber()) return 0;
        return Math.max(0, value.asInt(0));
    }

    private static double finiteDouble(JsonNode value, double fallback) {
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())) return fallback;
        return value.asDouble();
    }

    private static Double nullableNonNegativeDouble(JsonNode value) {
        if (value == null || value.isNull() || !value.isNumber() || !Double.isFinite(value.asDouble())) return null;
        return Math.max(0, value.asDouble());
    }

    private static int volumeByCareType(Map<String, Integer> groupVolumes, List<DepartmentExportLine> lines, String careType) {
        Map<String, Boolean> groups = new LinkedHashMap<>();
        lines.stream().filter(line -> careType.equals(line.careType())).forEach(line -> groups.put(line.serviceGroup(), Boolean.TRUE));
        return groups.keySet().stream().mapToInt(group -> groupVolumes.getOrDefault(group, 0)).sum();
    }

    private static double round(double value, int scale) {
        return java.math.BigDecimal.valueOf(value).setScale(scale, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private static void validateDateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询日期范围无效");
        }
    }

    private static String quantityText(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static byte[] csv(List<String[]> rows) {
        StringBuilder result = new StringBuilder("\uFEFF");
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) result.append(',');
                String value = row[i] == null ? "" : row[i];
                result.append('"').append(value.replace("\"", "\"\"")).append('"');
            }
            result.append("\r\n");
        }
        return result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private record AdminDailyRollupLine(
        String materialName,
        String unit,
        double theoreticalQuantity,
        double actualQuantity,
        double mainQuantity,
        Set<String> departments,
        int specialLineCount,
        int lineCount,
        int reportedLineCount,
        int unverifiedCount,
        int attentionCount,
        int abnormalCount,
        int specialPendingNoteCount,
        int pricedLineCount,
        double theoreticalAmount,
        double actualAmount,
        double mainAmount,
        int pricedActualLineCount,
        int amountDifferenceLineCount,
        double amountDifference
    ) {
        AdminDailyRollupLine add(AdminDailyRollupLine line) {
            Set<String> nextDepartments = new LinkedHashSet<>(departments);
            nextDepartments.addAll(line.departments());
            return new AdminDailyRollupLine(
                materialName, unit,
                round(theoreticalQuantity + line.theoreticalQuantity(), 6),
                round(actualQuantity + line.actualQuantity(), 6),
                round(mainQuantity + line.mainQuantity(), 6),
                Set.copyOf(nextDepartments), specialLineCount + line.specialLineCount(), lineCount + line.lineCount(),
                reportedLineCount + line.reportedLineCount(), unverifiedCount + line.unverifiedCount(),
                attentionCount + line.attentionCount(), abnormalCount + line.abnormalCount(),
                specialPendingNoteCount + line.specialPendingNoteCount(), pricedLineCount + line.pricedLineCount(),
                round(theoreticalAmount + line.theoreticalAmount(), 2), round(actualAmount + line.actualAmount(), 2),
                round(mainAmount + line.mainAmount(), 2), pricedActualLineCount + line.pricedActualLineCount(),
                amountDifferenceLineCount + line.amountDifferenceLineCount(), round(amountDifference + line.amountDifference(), 2)
            );
        }
    }

    private record DepartmentExportLine(String serviceGroup, String careType, String materialName, String unit, Double standardQuantity,
        int volume, double referenceQuantity, Double actualQuantity, Double weeklyQuantity, Double monthlyQuantity, Double amount, String status) {
        boolean pending() { return materialName.isBlank() || unit.isBlank(); }
        boolean hasActualQuantity() { return actualQuantity != null; }
        String[] toCsv() {
            return new String[] { serviceGroup + (careType.isBlank() ? "" : " / " + careType), materialName, unit, standardQuantity == null ? "" : quantityText(standardQuantity),
                String.valueOf(volume), quantityText(referenceQuantity), actualQuantity == null ? "" : quantityText(actualQuantity),
                weeklyQuantity == null ? "" : quantityText(weeklyQuantity), monthlyQuantity == null ? "" : quantityText(monthlyQuantity),
                amount == null ? "" : quantityText(amount), status };
        }
    }

    private record DepartmentSummaryLine(String materialName, String unit, double dailyQuantity, double weeklyQuantity, double monthlyQuantity, Double amount) {
        DepartmentSummaryLine add(DepartmentExportLine line) {
            Double nextAmount = amount == null || line.amount() == null ? null : round(amount + line.amount(), 2);
            return new DepartmentSummaryLine(materialName, unit, round(dailyQuantity + line.actualQuantity(), 6), round(weeklyQuantity + line.weeklyQuantity(), 6), round(monthlyQuantity + line.monthlyQuantity(), 6), nextAmount);
        }
        String[] toCsv() { return new String[] { materialName, unit, quantityText(dailyQuantity), quantityText(weeklyQuantity), quantityText(monthlyQuantity), amount == null ? "" : quantityText(amount), "仅统计已填实际耗材" }; }
    }

    private static Map<String, String> departments() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("physiotherapy", "理疗室");
        result.put("laboratory", "检验科");
        result.put("nursing", "护理部");
        result.put("tcm", "中医科");
        result.put("operating", "手术室");
        result.put("anesthesia", "麻醉室");
        result.put("endoscopy", "胃肠镜");
        result.put("inspection", "检查室");
        result.put("logistics", "后勤");
        result.put("western-pharmacy", "西药房");
        result.put("cashier", "收费室");
        result.put("tcm-pharmacy", "中药房");
        return Map.copyOf(result);
    }

    private record DraftVersion(String id, int revision, String quotaVersionId) {}
}
