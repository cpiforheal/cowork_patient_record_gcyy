package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class InventoryDepartmentReportService {

    private final InventoryLedgerRepository ledger;

    public InventoryDepartmentReportService(InventoryLedgerRepository ledger) {
        this.ledger = ledger;
    }

    public ObjectNode query(
        LocalDate from, LocalDate to, List<String> departmentIds, String itemId, String category, String triggerStage
    ) {
        if (from == null || to == null || to.isBefore(from)) throw new IllegalArgumentException("报表日期范围不正确");
        ObjectNode result = ledger.mapper().createObjectNode();
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("triggerStage", triggerStage == null ? "" : triggerStage);
        result.set("summary", summary(from, to, departmentIds, itemId, category, triggerStage));
        result.set("details", details(from, to, departmentIds, itemId, category, triggerStage));
        return result;
    }

    public byte[] exportXlsx(ObjectNode report) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writeSheet(workbook.createSheet("全院汇总"), report.withArray("summary"));
            Set<String> usedNames = new HashSet<>();
            for (JsonNode summary : report.withArray("summary")) {
                String departmentId = summary.path("departmentId").asText("");
                String department = summary.path("department").asText("未归属科室");
                if (usedNames.contains(departmentId)) continue;
                usedNames.add(departmentId);
                ArrayNode details = ledger.mapper().createArrayNode();
                for (JsonNode detail : report.withArray("details")) {
                    if (departmentId.equals(detail.path("departmentId").asText(""))) details.add(detail);
                }
                writeSheet(workbook.createSheet(uniqueSheetName(workbook, department)), details);
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("XLSX 报表生成失败", error);
        }
    }

    public byte[] exportPdf(ObjectNode report) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 24, 24, 28, 28);
            PdfWriter.getInstance(document, output);
            document.open();
            Font title = new Font(chineseBaseFont(), 14, Font.BOLD);
            Font body = new Font(chineseBaseFont(), 8, Font.NORMAL);
            Set<String> completed = new HashSet<>();
            for (JsonNode row : report.withArray("summary")) {
                String departmentId = row.path("departmentId").asText("");
                if (!completed.add(departmentId)) continue;
                if (completed.size() > 1) document.newPage();
                Paragraph heading = new Paragraph(
                    row.path("department").asText("未归属科室") + " 科室耗材使用明细",
                    title
                );
                heading.setAlignment(Element.ALIGN_CENTER);
                document.add(heading);
                document.add(new Paragraph(report.path("from").asText() + " 至 " + report.path("to").asText(), body));
                PdfPTable summaryTable = table(body,
                    "物资", "单位", "期初", "调入", "退库", "实际耗用", "冲销", "报损", "调整", "期末");
                for (JsonNode value : report.withArray("summary")) {
                    if (!departmentId.equals(value.path("departmentId").asText(""))) continue;
                    addRow(summaryTable, body,
                        value.path("itemName").asText(""), value.path("unit").asText(""),
                        number(value, "openingQuantity"), number(value, "transferInQuantity"),
                        number(value, "returnQuantity"), number(value, "consumedQuantity"),
                        number(value, "reversalQuantity"), number(value, "scrapQuantity"),
                        number(value, "adjustmentQuantity"), number(value, "closingQuantity"));
                }
                document.add(summaryTable);
                document.add(new Paragraph("耗用追溯明细", title));
                PdfPTable detailTable = table(body,
                    "日期", "就诊流水", "患者", "执行环节", "套餐版本", "物资", "批次", "数量", "来源");
                for (JsonNode value : report.withArray("details")) {
                    if (!departmentId.equals(value.path("departmentId").asText(""))) continue;
                    addRow(detailTable, body,
                        value.path("visitDate").asText(""), value.path("encounterId").asText(""),
                        value.path("patientMasked").asText(""), value.path("triggerStage").asText(""),
                        value.path("packageVersion").asText(""), value.path("itemName").asText(""),
                        value.path("batchNo").asText(""), number(value, "quantity"),
                        value.path("source").asText("自动耗用"));
                }
                document.add(detailTable);
                document.add(new Paragraph("科室确认：____________    仓库确认：____________    日期：____________", body));
            }
            document.close();
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("PDF 报表生成失败", error);
        }
    }

    public void audit(SessionUser user, String action, LocalDate from, LocalDate to, List<String> departmentIds) {
        List<String> safeDepartments = departmentIds == null ? List.of() : departmentIds;
        String departmentScope = safeDepartments.isEmpty() ? "ALL" : String.join(",", safeDepartments);
        String id = "audit-" + java.util.UUID.randomUUID();
        ObjectNode raw = ledger.mapper().createObjectNode();
        raw.put("id", id);
        raw.put("operator", user.name());
        raw.put("action", action);
        raw.put("range", from + "~" + to);
        raw.put("departmentIds", departmentScope);
        ledger.jdbc().update(
            """
            INSERT INTO inventory_audit_logs
              (id, operator_name, action, target_type, target_label, detail, created_at, raw_json)
            VALUES (?, ?, ?, 'department_usage_report', ?, ?, ?, ?)
            """,
            id, user.name(), action, departmentScope, from + "~" + to,
            java.time.LocalDateTime.now().toString(), raw.toString()
        );
    }

    private ArrayNode summary(
        LocalDate from, LocalDate to, List<String> departmentIds, String itemId, String category, String triggerStage
    ) {
        SqlFilter filter = filter(departmentIds, itemId, category, "m");
        String normalizedStage = triggerStage == null || triggerStage.isBlank() ? null : triggerStage.trim().toUpperCase();
        String stageCondition = normalizedStage == null ? "" : " AND c.trigger_stage = ?";
        String sql = """
            SELECT m.department_id departmentId, MAX(m.department_name_snapshot) department,
                   m.item_id itemId, MAX(i.name) itemName, MAX(i.unit) unit,
                   COALESCE(SUM(CASE WHEN m.occurred_at < ?
                       THEN CASE WHEN m.to_location_id = l.id THEN m.quantity WHEN m.from_location_id = l.id THEN -m.quantity ELSE 0 END
                       ELSE 0 END), 0) openingQuantity,
                   COALESCE(SUM(CASE WHEN m.occurred_at >= ? AND m.occurred_at < ? AND m.movement_type = 'TRANSFER_TO_DEPARTMENT' THEN m.quantity ELSE 0 END), 0) transferInQuantity,
                   COALESCE(SUM(CASE WHEN m.occurred_at >= ? AND m.occurred_at < ? AND m.movement_type = 'RETURN_TO_CENTRAL' THEN m.quantity ELSE 0 END), 0) returnQuantity,
                   COALESCE(SUM(CASE WHEN m.occurred_at >= ? AND m.occurred_at < ? AND m.movement_type = 'CONSUMPTION' %s THEN m.quantity ELSE 0 END), 0) consumedQuantity,
                   COALESCE(SUM(CASE WHEN m.occurred_at >= ? AND m.occurred_at < ? AND m.movement_type = 'CONSUMPTION_REVERSAL' %s THEN m.quantity ELSE 0 END), 0) reversalQuantity,
                   COALESCE(SUM(CASE WHEN m.occurred_at >= ? AND m.occurred_at < ? AND m.movement_type = 'SCRAP' THEN m.quantity ELSE 0 END), 0) scrapQuantity,
                   COALESCE(SUM(CASE WHEN m.occurred_at >= ? AND m.occurred_at < ? AND m.movement_type LIKE 'COUNT_ADJUSTMENT_%%'
                       THEN CASE WHEN m.movement_type = 'COUNT_ADJUSTMENT_IN' THEN m.quantity ELSE -m.quantity END ELSE 0 END), 0) adjustmentQuantity,
                   COALESCE(SUM(CASE WHEN m.occurred_at < ?
                       THEN CASE WHEN m.to_location_id = l.id THEN m.quantity WHEN m.from_location_id = l.id THEN -m.quantity ELSE 0 END
                       ELSE 0 END), 0) closingQuantity
            FROM inventory_ledger_movements m
            JOIN inventory_items i ON i.id = m.item_id
            JOIN inventory_locations l ON l.department_id = m.department_id AND l.location_type = 'DEPARTMENT'
            LEFT JOIN inventory_stage_consumption_commands c ON c.id = m.related_id AND m.related_type = 'STAGE_COMMAND'
            WHERE m.department_id IS NOT NULL AND m.occurred_at < ?
            """.formatted(stageCondition, stageCondition) + filter.sql()
            + " GROUP BY m.department_id, m.item_id ORDER BY department, itemName";
        List<Object> expanded = new ArrayList<>();
        expanded.add(from);
        expanded.add(from); expanded.add(to.plusDays(1));
        expanded.add(from); expanded.add(to.plusDays(1));
        expanded.add(from); expanded.add(to.plusDays(1));
        if (normalizedStage != null) expanded.add(normalizedStage);
        expanded.add(from); expanded.add(to.plusDays(1));
        if (normalizedStage != null) expanded.add(normalizedStage);
        expanded.add(from); expanded.add(to.plusDays(1));
        expanded.add(from); expanded.add(to.plusDays(1));
        expanded.add(to.plusDays(1));
        expanded.add(to.plusDays(1));
        expanded.addAll(filter.args());
        return ledger.queryJson(sql, expanded.toArray());
    }

    private ArrayNode details(
        LocalDate from, LocalDate to, List<String> departmentIds, String itemId, String category, String triggerStage
    ) {
        SqlFilter filter = filter(departmentIds, itemId, category, "e");
        List<Object> args = new ArrayList<>();
        args.add(from);
        args.add(to);
        if (triggerStage != null && !triggerStage.isBlank()) args.add(triggerStage.trim().toUpperCase());
        args.addAll(filter.args());
        return ledger.queryJson(
            """
            SELECT e.department_id departmentId, e.department department, e.visit_date visitDate,
                   e.encounter_id encounterId,
                   CASE WHEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(pe.patient_json, '$.name')), '') = '' THEN '患者**'
                        ELSE CONCAT(LEFT(JSON_UNQUOTE(JSON_EXTRACT(pe.patient_json, '$.name')), 1), '**') END patientMasked,
                   e.trigger_stage triggerStage, p.version_no packageVersion,
                   d.item_id itemId, i.name itemName, i.unit, d.batch_id batchId,
                   COALESCE(b.batch_no, '') batchNo, d.quantity,
                   CASE e.event_kind WHEN 'REVERSAL' THEN '耗用冲销' ELSE '套餐自动耗用' END source
            FROM inventory_consumption_events e
            JOIN inventory_consumption_details d ON d.event_id = e.id
            JOIN inventory_items i ON i.id = d.item_id
            LEFT JOIN inventory_batches b ON b.id = d.batch_id
            LEFT JOIN inventory_packages p ON p.id = e.package_id
            LEFT JOIN pre_ai_encounters pe ON pe.id = e.encounter_id
            WHERE e.status = 'succeeded' AND e.visit_date >= ? AND e.visit_date <= ?
            """ + (triggerStage == null || triggerStage.isBlank() ? "" : " AND e.trigger_stage = ?")
            + filter.sql() + " ORDER BY e.department, e.visit_date, e.encounter_id, i.name",
            args.toArray()
        );
    }

    private SqlFilter filter(List<String> departmentIds, String itemId, String category, String alias) {
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (departmentIds != null && !departmentIds.isEmpty()) {
            sql.append(" AND ").append(alias).append(".department_id IN (");
            for (int index = 0; index < departmentIds.size(); index++) {
                if (index > 0) sql.append(',');
                sql.append('?');
                args.add(departmentIds.get(index));
            }
            sql.append(')');
        }
        if (itemId != null && !itemId.isBlank()) {
            if ("e".equals(alias)) sql.append(" AND d.item_id = ?");
            else sql.append(" AND m.item_id = ?");
            args.add(itemId);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND i.category = ?");
            args.add(category);
        }
        return new SqlFilter(sql.toString(), args);
    }

    private void writeSheet(Sheet sheet, ArrayNode rows) {
        if (rows.isEmpty()) {
            sheet.createRow(0).createCell(0).setCellValue("无数据");
            return;
        }
        List<String> headers = new ArrayList<>();
        rows.get(0).fieldNames().forEachRemaining(headers::add);
        Row header = sheet.createRow(0);
        for (int index = 0; index < headers.size(); index++) header.createCell(index).setCellValue(headers.get(index));
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row row = sheet.createRow(rowIndex + 1);
            JsonNode source = rows.get(rowIndex);
            for (int column = 0; column < headers.size(); column++) {
                JsonNode value = source.path(headers.get(column));
                Cell cell = row.createCell(column);
                if (value.isNumber()) cell.setCellValue(value.asDouble());
                else cell.setCellValue(value.isNull() ? "" : value.asText());
            }
        }
        for (int index = 0; index < Math.min(headers.size(), 15); index++) sheet.autoSizeColumn(index);
    }

    private static PdfPTable table(Font font, String... headers) {
        PdfPTable table = new PdfPTable(headers.length);
        table.setWidthPercentage(100);
        table.setSpacingBefore(8);
        table.setSpacingAfter(8);
        addRow(table, font, headers);
        return table;
    }

    private static void addRow(PdfPTable table, Font font, String... values) {
        for (String value : values) {
            PdfPCell cell = new PdfPCell(new Phrase(value == null ? "" : value, font));
            cell.setPadding(3);
            table.addCell(cell);
        }
    }

    private static BaseFont chineseBaseFont() throws Exception {
        Path simsun = Path.of("C:/Windows/Fonts/simsun.ttc");
        if (Files.exists(simsun)) {
            return BaseFont.createFont(simsun.toString() + ",0", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        }
        throw new IllegalStateException("未找到可嵌入的中文字体 C:/Windows/Fonts/simsun.ttc，无法安全生成中文 PDF");
    }

    private static String uniqueSheetName(XSSFWorkbook workbook, String value) {
        String base = value.replaceAll("[\\\\/?*\\[\\]:]", "_");
        if (base.length() > 28) base = base.substring(0, 28);
        String candidate = base.isBlank() ? "科室" : base;
        int suffix = 1;
        while (workbook.getSheet(candidate) != null) candidate = base + "-" + suffix++;
        return candidate;
    }

    private static String number(JsonNode row, String name) {
        return row.path(name).isNumber() ? row.path(name).decimalValue().stripTrailingZeros().toPlainString() : row.path(name).asText("0");
    }

    private record SqlFilter(String sql, List<Object> args) {}
}
