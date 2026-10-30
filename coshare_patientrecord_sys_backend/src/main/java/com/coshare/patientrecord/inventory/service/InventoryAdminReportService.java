package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Frozen weekly/monthly admin report snapshots and exports. */
@Service
@Profile("mysql")
public class InventoryAdminReportService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryAdminReviewService reviewService;
    private final InventoryRepository repository;

    public InventoryAdminReportService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        InventoryAdminReviewService reviewService,
        InventoryRepository repository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.reviewService = reviewService;
        this.repository = repository;
    }

    @Transactional
    public ObjectNode generate(JsonNode payload, SessionUser user) {
        String type = text(payload, "reportType").toUpperCase(Locale.ROOT);
        if (!List.of("WEEK", "MONTH").contains(type)) {
            throw badRequest("报表类型只能是 WEEK 或 MONTH");
        }
        LocalDate periodEnd = date(payload, "periodEnd");
        LocalDate periodStart = date(payload, "periodStart");
        if (periodStart == null || periodEnd == null) {
            LocalDate anchor = periodEnd == null ? LocalDate.now() : periodEnd;
            if ("WEEK".equals(type)) {
                periodStart = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                periodEnd = periodStart.plusDays(6);
            } else {
                periodStart = anchor.withDayOfMonth(1);
                periodEnd = anchor.withDayOfMonth(anchor.lengthOfMonth());
            }
        }
        if (periodStart.isAfter(periodEnd)) throw badRequest("报表开始日期不能晚于结束日期");
        long days = ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
        LocalDate previousEnd = periodStart.minusDays(1);
        LocalDate previousStart = previousEnd.minusDays(days - 1);
        ObjectNode summary = summary(periodStart, periodEnd, previousStart, previousEnd);
        ArrayNode departments = departments(periodStart, periodEnd);
        ArrayNode items = items(periodStart, periodEnd);
        ObjectNode anomalyBundle = reviewService.anomalies(periodEnd, (int) Math.min(days, 31), "", "");
        ObjectNode trendBundle = reviewService.trends(periodStart, periodEnd, "");
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("reportType", type);
        snapshot.put("periodStart", periodStart.toString());
        snapshot.put("periodEnd", periodEnd.toString());
        snapshot.put("generatedAt", LocalDateTime.now().toString());
        snapshot.put("generatedBy", user.name());
        snapshot.put("frozen", true);
        snapshot.set("summary", summary);
        snapshot.set("departments", departments);
        snapshot.set("items", items);
        snapshot.set("anomalySummary", anomalyBundle.path("summary"));
        ArrayNode anomalyItems = snapshot.putArray("anomalies");
        int index = 0;
        for (JsonNode item : anomalyBundle.path("items")) {
            if (index++ >= 200) break;
            anomalyItems.add(item);
        }
        snapshot.set("trends", trendBundle);
        String id = "admin-report-" + UUID.randomUUID();
        String payloadJson = json(snapshot);
        jdbcTemplate.update(
            """
            INSERT INTO inventory_admin_report_snapshots
              (id, report_type, period_start, period_end, status, payload_json, generated_by, data_revision)
            VALUES (?, ?, ?, ?, 'READY', ?, ?, ?)
            """,
            id, type, periodStart, periodEnd, payloadJson, user.name(), LocalDateTime.now().toString()
        );
        repository.log(user.name(), "生成管理报表快照", "inventory_admin_report_snapshot", id,
            type + " " + periodStart + " ~ " + periodEnd);
        ObjectNode result = snapshot.deepCopy();
        result.put("id", id);
        result.put("status", "READY");
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode list() {
        ArrayNode items = jdbcTemplate.query(
            "SELECT id, report_type, period_start, period_end, status, generated_by, generated_at FROM inventory_admin_report_snapshots ORDER BY generated_at DESC",
            resultSet -> {
                ArrayNode rows = objectMapper.createArrayNode();
                while (resultSet.next()) {
                    ObjectNode row = rows.addObject();
                    row.put("id", resultSet.getString("id"));
                    row.put("reportType", resultSet.getString("report_type"));
                    row.put("periodStart", resultSet.getDate("period_start").toLocalDate().toString());
                    row.put("periodEnd", resultSet.getDate("period_end").toLocalDate().toString());
                    row.put("status", resultSet.getString("status"));
                    row.put("generatedBy", resultSet.getString("generated_by"));
                    Timestamp generatedAt = resultSet.getTimestamp("generated_at");
                    row.put("generatedAt", generatedAt == null ? "" : generatedAt.toLocalDateTime().toString());
                }
                return rows;
            }
        );
        ObjectNode result = objectMapper.createObjectNode();
        result.set("items", items);
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode detail(String id) {
        return load(id);
    }

    @Transactional(readOnly = true)
    public byte[] export(String id, String format) {
        ObjectNode snapshot = load(id);
        if ("xlsx".equalsIgnoreCase(format)) return xlsx(snapshot);
        if ("pdf".equalsIgnoreCase(format)) return pdf(snapshot);
        return csv(snapshot);
    }

    private ObjectNode load(String id) {
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT * FROM inventory_admin_report_snapshots WHERE id = ?",
            (resultSet, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("id", resultSet.getString("id"));
                row.put("reportType", resultSet.getString("report_type"));
                row.put("periodStart", resultSet.getDate("period_start").toLocalDate().toString());
                row.put("periodEnd", resultSet.getDate("period_end").toLocalDate().toString());
                row.put("status", resultSet.getString("status"));
                row.put("generatedBy", resultSet.getString("generated_by"));
                Timestamp generatedAt = resultSet.getTimestamp("generated_at");
                row.put("generatedAt", generatedAt == null ? "" : generatedAt.toLocalDateTime().toString());
                return row;
            },
            id
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报表快照不存在");
        ObjectNode root = rows.get(0);
        String payload = jdbcTemplate.queryForObject(
            "SELECT payload_json FROM inventory_admin_report_snapshots WHERE id = ?", String.class, id);
        try {
            JsonNode payloadNode = objectMapper.readTree(payload);
            if (payloadNode instanceof ObjectNode object) {
                object.fields().forEachRemaining(entry -> root.set(entry.getKey(), entry.getValue()));
            }
        } catch (Exception error) {
            throw new IllegalStateException("报表快照解析失败", error);
        }
        return root;
    }

    private ObjectNode summary(LocalDate start, LocalDate end, LocalDate previousStart, LocalDate previousEnd) {
        BigDecimal patientQuantity = decimal(
            """
            SELECT COALESCE(SUM(l.actual_quantity), 0)
            FROM inventory_patient_consumption_lines l
            JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
            JOIN inventory_service_instances si ON si.id = d.service_instance_id
            WHERE si.business_date BETWEEN ? AND ? AND l.review_status <> 'REJECTED'
            """, start, end);
        BigDecimal dailyQuantity = decimal(
            """
            SELECT COALESCE(SUM(rl.actual_quantity), 0)
            FROM inventory_department_daily_report_lines rl
            JOIN inventory_department_daily_reports r ON r.id = rl.report_id
            WHERE r.business_date BETWEEN ? AND ? AND rl.review_status <> 'REJECTED' AND rl.actual_quantity > 0
            """, start, end);
        Integer patients = integer(
            """
            SELECT COUNT(DISTINCT si.patient_id)
            FROM inventory_patient_consumption_documents d
            JOIN inventory_service_instances si ON si.id = d.service_instance_id
            WHERE si.business_date BETWEEN ? AND ?
            """, start, end);
        Integer reportDays = integer(
            """
            SELECT COUNT(*)
            FROM inventory_department_daily_reports
            WHERE business_date BETWEEN ? AND ? AND status IN ('SUBMITTED', 'PARTIALLY_APPROVED', 'APPROVED')
            """, start, end);
        Integer items = integer(
            """
            SELECT COUNT(DISTINCT item_id) FROM (
              SELECT l.item_id FROM inventory_patient_consumption_lines l
              JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
              JOIN inventory_service_instances si ON si.id = d.service_instance_id
              WHERE si.business_date BETWEEN ? AND ? AND l.review_status <> 'REJECTED'
              UNION
              SELECT rl.item_id FROM inventory_department_daily_report_lines rl
              JOIN inventory_department_daily_reports r ON r.id = rl.report_id
              WHERE r.business_date BETWEEN ? AND ? AND rl.review_status <> 'REJECTED' AND rl.actual_quantity > 0
            ) items
            """, start, end, start, end);
        BigDecimal previousQuantity = decimal(
            """
            SELECT COALESCE(SUM(quantity), 0) FROM (
              SELECT l.actual_quantity quantity FROM inventory_patient_consumption_lines l
              JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
              JOIN inventory_service_instances si ON si.id = d.service_instance_id
              WHERE si.business_date BETWEEN ? AND ? AND l.review_status <> 'REJECTED'
              UNION ALL
              SELECT rl.actual_quantity FROM inventory_department_daily_report_lines rl
              JOIN inventory_department_daily_reports r ON r.id = rl.report_id
              WHERE r.business_date BETWEEN ? AND ? AND rl.review_status <> 'REJECTED' AND rl.actual_quantity > 0
            ) quantities
            """, previousStart, previousEnd, previousStart, previousEnd);
        BigDecimal currentQuantity = patientQuantity.add(dailyQuantity);
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("patients", patients == null ? 0 : patients);
        summary.put("reportDays", reportDays == null ? 0 : reportDays);
        summary.put("itemCount", items == null ? 0 : items);
        summary.put("totalQuantity", currentQuantity);
        summary.put("previousQuantity", previousQuantity);
        summary.put("deltaQuantity", currentQuantity.subtract(previousQuantity));
        summary.put("changeRate", previousQuantity.signum() == 0 ? null
            : currentQuantity.subtract(previousQuantity).divide(previousQuantity, 4, RoundingMode.HALF_UP));
        return summary;
    }

    private ArrayNode departments(LocalDate start, LocalDate end) {
        return jdbcTemplate.query(
            """
            SELECT department_key, department_name,
                   SUM(quantity) quantity,
                   COUNT(DISTINCT patient_id) patients
            FROM (
              SELECT si.department_key, si.department_name, l.actual_quantity quantity, si.patient_id
              FROM inventory_patient_consumption_lines l
              JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
              JOIN inventory_service_instances si ON si.id = d.service_instance_id
              WHERE si.business_date BETWEEN ? AND ? AND l.review_status <> 'REJECTED'
              UNION ALL
              SELECT r.department_key, r.department_name, rl.actual_quantity, NULL
              FROM inventory_department_daily_report_lines rl
              JOIN inventory_department_daily_reports r ON r.id = rl.report_id
              WHERE r.business_date BETWEEN ? AND ? AND rl.review_status <> 'REJECTED' AND rl.actual_quantity > 0
            ) dept_rows
            GROUP BY department_key, department_name
            ORDER BY quantity DESC
            """,
            resultSet -> {
                ArrayNode list = objectMapper.createArrayNode();
                while (resultSet.next()) {
                    ObjectNode row = list.addObject();
                    row.put("departmentKey", resultSet.getString("department_key"));
                    row.put("departmentName", resultSet.getString("department_name"));
                    row.put("quantity", resultSet.getBigDecimal("quantity"));
                    row.put("patients", resultSet.getInt("patients"));
                }
                return list;
            },
            start, end, start, end
        );
    }

    private ArrayNode items(LocalDate start, LocalDate end) {
        return jdbcTemplate.query(
            """
            SELECT item_id, MAX(item_name) item_name, MAX(unit) unit, SUM(quantity) quantity
            FROM (
              SELECT l.item_id, COALESCE(l.item_name_snapshot, i.name) item_name,
                     COALESCE(l.unit_snapshot, i.unit) unit, l.actual_quantity quantity
              FROM inventory_patient_consumption_lines l
              JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
              JOIN inventory_service_instances si ON si.id = d.service_instance_id
              LEFT JOIN inventory_items i ON i.id = l.item_id
              WHERE si.business_date BETWEEN ? AND ? AND l.review_status <> 'REJECTED'
              UNION ALL
              SELECT rl.item_id, COALESCE(rl.item_name_snapshot, i.name),
                     COALESCE(rl.unit_snapshot, i.unit), rl.actual_quantity
              FROM inventory_department_daily_report_lines rl
              JOIN inventory_department_daily_reports r ON r.id = rl.report_id
              LEFT JOIN inventory_items i ON i.id = rl.item_id
              WHERE r.business_date BETWEEN ? AND ? AND rl.review_status <> 'REJECTED' AND rl.actual_quantity > 0
            ) line_rows
            GROUP BY item_id
            ORDER BY quantity DESC
            LIMIT 200
            """,
            resultSet -> {
                ArrayNode list = objectMapper.createArrayNode();
                while (resultSet.next()) {
                    ObjectNode row = list.addObject();
                    row.put("itemId", resultSet.getString("item_id"));
                    row.put("itemName", resultSet.getString("item_name"));
                    row.put("unit", resultSet.getString("unit"));
                    row.put("quantity", resultSet.getBigDecimal("quantity"));
                }
                return list;
            },
            start, end, start, end
        );
    }

    private byte[] csv(ObjectNode snapshot) {
        StringBuilder out = new StringBuilder("\uFEFF");
        out.append("报表周期,").append(snapshot.path("periodStart").asText()).append(" 至 ")
            .append(snapshot.path("periodEnd").asText()).append('\n');
        out.append("生成时间,").append(snapshot.path("generatedAt").asText()).append('\n');
        out.append("患者数,").append(snapshot.path("summary").path("patients").asText()).append('\n');
        out.append("填报天数,").append(snapshot.path("summary").path("reportDays").asText()).append('\n');
        out.append("耗材种类,").append(snapshot.path("summary").path("itemCount").asText()).append('\n');
        out.append("使用总量,").append(snapshot.path("summary").path("totalQuantity").asText()).append("\n\n");
        out.append("科室,使用量,患者数\n");
        for (JsonNode row : snapshot.path("departments")) {
            out.append(csvCell(row.path("departmentName").asText())).append(',')
                .append(row.path("quantity").asText()).append(',')
                .append(row.path("patients").asText()).append('\n');
        }
        out.append("\n耗材,单位,使用量\n");
        for (JsonNode row : snapshot.path("items")) {
            out.append(csvCell(row.path("itemName").asText())).append(',')
                .append(csvCell(row.path("unit").asText())).append(',')
                .append(row.path("quantity").asText()).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] xlsx(ObjectNode snapshot) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet summary = workbook.createSheet("总览");
            summary.createRow(0).createCell(0).setCellValue("报表周期");
            summary.getRow(0).createCell(1).setCellValue(snapshot.path("periodStart").asText() + " ~ " + snapshot.path("periodEnd").asText());
            summary.createRow(1).createCell(0).setCellValue("生成时间");
            summary.getRow(1).createCell(1).setCellValue(snapshot.path("generatedAt").asText());
            summary.createRow(2).createCell(0).setCellValue("患者数");
            summary.getRow(2).createCell(1).setCellValue(snapshot.path("summary").path("patients").asInt());
            summary.createRow(3).createCell(0).setCellValue("填报天数");
            summary.getRow(3).createCell(1).setCellValue(snapshot.path("summary").path("reportDays").asInt());
            summary.createRow(4).createCell(0).setCellValue("耗材种类");
            summary.getRow(4).createCell(1).setCellValue(snapshot.path("summary").path("itemCount").asInt());
            summary.createRow(5).createCell(0).setCellValue("使用总量");
            summary.getRow(5).createCell(1).setCellValue(snapshot.path("summary").path("totalQuantity").asText());

            Sheet deptSheet = workbook.createSheet("科室");
            Row deptHeader = deptSheet.createRow(0);
            deptHeader.createCell(0).setCellValue("科室");
            deptHeader.createCell(1).setCellValue("使用量");
            deptHeader.createCell(2).setCellValue("患者数");
            int deptRow = 1;
            for (JsonNode row : snapshot.path("departments")) {
                Row sheetRow = deptSheet.createRow(deptRow++);
                sheetRow.createCell(0).setCellValue(row.path("departmentName").asText());
                sheetRow.createCell(1).setCellValue(row.path("quantity").asDouble());
                sheetRow.createCell(2).setCellValue(row.path("patients").asInt());
            }

            Sheet itemSheet = workbook.createSheet("耗材");
            Row itemHeader = itemSheet.createRow(0);
            itemHeader.createCell(0).setCellValue("耗材");
            itemHeader.createCell(1).setCellValue("单位");
            itemHeader.createCell(2).setCellValue("使用量");
            int itemRow = 1;
            for (JsonNode row : snapshot.path("items")) {
                Row sheetRow = itemSheet.createRow(itemRow++);
                sheetRow.createCell(0).setCellValue(row.path("itemName").asText());
                sheetRow.createCell(1).setCellValue(row.path("unit").asText());
                sheetRow.createCell(2).setCellValue(row.path("quantity").asDouble());
            }

            Sheet anomalySheet = workbook.createSheet("异常");
            Row anomalyHeader = anomalySheet.createRow(0);
            anomalyHeader.createCell(0).setCellValue("严重度");
            anomalyHeader.createCell(1).setCellValue("科室");
            anomalyHeader.createCell(2).setCellValue("业务日");
            anomalyHeader.createCell(3).setCellValue("患者/日报");
            anomalyHeader.createCell(4).setCellValue("原因");
            int anomalyRow = 1;
            for (JsonNode row : snapshot.path("anomalies")) {
                Row sheetRow = anomalySheet.createRow(anomalyRow++);
                sheetRow.createCell(0).setCellValue(row.path("severity").asText());
                sheetRow.createCell(1).setCellValue(row.path("departmentName").asText());
                sheetRow.createCell(2).setCellValue(row.path("businessDate").asText());
                sheetRow.createCell(3).setCellValue(row.path("patientName").asText());
                sheetRow.createCell(4).setCellValue(row.path("reason").asText());
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("报表 XLSX 导出失败", error);
        }
    }

    private byte[] pdf(ObjectNode snapshot) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
            PdfWriter.getInstance(document, output);
            document.open();
            Font titleFont = new Font(chineseBaseFont(), 16, Font.BOLD);
            Font bodyFont = new Font(chineseBaseFont(), 9);
            document.add(new Paragraph("耗材管理报表", titleFont));
            document.add(new Paragraph(snapshot.path("periodStart").asText() + " ~ " + snapshot.path("periodEnd").asText()
                + " · 冻结生成 " + snapshot.path("generatedAt").asText(), bodyFont));
            document.add(new Paragraph("患者 " + snapshot.path("summary").path("patients").asInt()
                + " 人 · 填报 " + snapshot.path("summary").path("reportDays").asInt()
                + " 天 · 耗材 " + snapshot.path("summary").path("itemCount").asInt()
                + " 种 · 总量 " + snapshot.path("summary").path("totalQuantity").asText(), bodyFont));
            document.add(new Paragraph(" ", bodyFont));
            PdfPTable deptTable = table(bodyFont, "科室", "使用量", "患者数");
            for (JsonNode row : snapshot.path("departments")) {
                addRow(deptTable, bodyFont, row.path("departmentName").asText(),
                    row.path("quantity").asText(), row.path("patients").asText());
            }
            document.add(deptTable);
            document.add(new Paragraph(" ", bodyFont));
            PdfPTable itemTable = table(bodyFont, "耗材", "单位", "使用量");
            int count = 0;
            for (JsonNode row : snapshot.path("items")) {
                if (count++ >= 40) break;
                addRow(itemTable, bodyFont, row.path("itemName").asText(), row.path("unit").asText(),
                    row.path("quantity").asText());
            }
            document.add(itemTable);
            document.close();
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("报表 PDF 导出失败", error);
        }
    }

    private static BaseFont chineseBaseFont() throws Exception {
        Path simsun = Path.of("C:/Windows/Fonts/simsun.ttc");
        if (Files.exists(simsun)) {
            return BaseFont.createFont(simsun.toString() + ",0", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        }
        throw new IllegalStateException("未找到可嵌入的中文字体 C:/Windows/Fonts/simsun.ttc，无法安全生成中文 PDF");
    }

    private static PdfPTable table(Font font, String... headers) {
        PdfPTable table = new PdfPTable(headers.length);
        table.setWidthPercentage(100);
        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Paragraph(header, font));
            cell.setPadding(4);
            table.addCell(cell);
        }
        return table;
    }

    private static void addRow(PdfPTable table, Font font, String... values) {
        for (String value : values) {
            PdfPCell cell = new PdfPCell(new Paragraph(value == null ? "" : value, font));
            cell.setPadding(3);
            table.addCell(cell);
        }
    }

    private BigDecimal decimal(String sql, Object... args) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }

    private Integer integer(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalStateException("报表快照序列化失败", error);
        }
    }

    private static LocalDate date(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            throw badRequest(field + " 日期格式不正确");
        }
    }

    private static String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }

    private static String csvCell(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
