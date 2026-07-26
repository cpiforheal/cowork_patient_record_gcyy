package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.inventory.repository.InventoryWeeklyRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class InventoryWeeklyExportService {

    private static final String[] HEADERS = {
        "物资", "类型", "单位", "实际患者数", "计划患者数", "每患者标准", "换算系数", "患者量来源",
        "期初", "调入", "实际耗用", "冲销", "退库", "报损", "盘点调整", "期末", "可用",
        "预估量", "预估-实际", "安全库存", "建议补领", "最终调整", "调整-建议", "调整原因",
        "确认状态", "审计来源"
    };

    private final InventoryWeeklyRepository weekly;

    public InventoryWeeklyExportService(InventoryWeeklyRepository weekly) {
        this.weekly = weekly;
    }

    @Transactional
    public ExportFile export(String snapshotId, String format, SessionUser user) {
        return export(weekly.snapshot(snapshotId), format, user);
    }

    @Transactional
    public ExportFile export(ObjectNode snapshot, String format, SessionUser user) {
        String normalized = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        byte[] body = switch (normalized) {
            case "xlsx" -> xlsx(snapshot, user);
            case "pdf" -> pdf(snapshot, user);
            case "docx" -> docx(snapshot, user);
            default -> throw new IllegalArgumentException("仅支持 XLSX/PDF/DOCX 导出");
        };
        String snapshotId = snapshot.path("id").asText();
        String fileName = "inventory-weekly-" + snapshot.path("weekNo").asText() + "-r" + snapshot.path("revision").asInt() + "." + normalized;
        String contentHash = sha256(body);
        String exportId = weekly.recordExport(snapshotId, normalized.toUpperCase(Locale.ROOT), snapshot.withArray("lines").size(),
            fileName, contentHash, body.length, user.name(), user.role(), snapshot.path("departmentId").asText());
        ObjectNode detail = weekly.mapper().createObjectNode();
        detail.put("format", normalized.toUpperCase(Locale.ROOT));
        detail.put("fileName", fileName);
        detail.put("contentHash", contentHash);
        weekly.audit(snapshotId, null, exportId, "SNAPSHOT_EXPORTED", user.name(), user.role(), snapshot.path("departmentId").asText(), detail);
        return new ExportFile(body, fileName, mediaType(normalized));
    }

    byte[] xlsx(ObjectNode snapshot, SessionUser user) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("周度库存快照");
            int rowIndex = 0;
            row(sheet, rowIndex++, "周次", snapshot.path("weekNo").asText(), "科室", snapshot.path("departmentName").asText(), "版本", "R" + snapshot.path("revision").asInt());
            row(sheet, rowIndex++, "标准版本", String.valueOf(snapshot.path("standardVersion").asInt()), "状态", statusLabel(snapshot), "来源截止", snapshot.path("sourceCutoffAt").asText());
            row(sheet, rowIndex++, "导出人", user.name(), "导出时间", exportedAt(), "筛选条件", "周次=" + snapshot.path("weekNo").asText() + "；科室=" + snapshot.path("departmentName").asText());
            row(sheet, rowIndex++, "确认人", snapshot.path("confirmedBy").asText(""), "确认时间", snapshot.path("confirmedAt").asText(""), "文件状态", statusLabel(snapshot));
            Row header = sheet.createRow(rowIndex++);
            for (int i = 0; i < HEADERS.length; i++) header.createCell(i).setCellValue(HEADERS[i]);
            for (JsonNode line : snapshot.withArray("lines")) {
                Row value = sheet.createRow(rowIndex++);
                String[] cells = values(snapshot, line);
                for (int i = 0; i < cells.length; i++) {
                    String field = fieldAt(i);
                    if (!field.isBlank()) value.createCell(i).setCellValue(number(line, field));
                    else value.createCell(i).setCellValue(cells[i]);
                }
            }
            row(sheet, rowIndex + 1, "制表", "", "科室负责人", "", "复核", "", "日期", "");
            for (int i = 0; i < HEADERS.length; i++) sheet.setColumnWidth(i, Math.min(i == 0 || i == 25 ? 6800 : 3600, 12000));
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("周度 XLSX 生成失败", error);
        }
    }

    byte[] docx(ObjectNode snapshot, SessionUser user) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFParagraph title = document.createParagraph();
            title.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER);
            var titleRun = title.createRun();
            titleRun.setBold(true);
            titleRun.setFontSize(16);
            titleRun.setText(snapshot.path("departmentName").asText() + " 周度库存快照");
            XWPFParagraph meta = document.createParagraph();
            meta.createRun().setText("周次：" + snapshot.path("weekNo").asText() + "    Revision：R" + snapshot.path("revision").asInt()
                + "    状态：" + statusLabel(snapshot) + "    来源截止：" + snapshot.path("sourceCutoffAt").asText());
            XWPFParagraph exportMeta = document.createParagraph();
            exportMeta.createRun().setText("导出人：" + user.name() + "    导出时间：" + exportedAt()
                + "    筛选条件：周次=" + snapshot.path("weekNo").asText() + "；科室=" + snapshot.path("departmentName").asText()
                + "    确认人：" + snapshot.path("confirmedBy").asText("") + "    确认时间：" + snapshot.path("confirmedAt").asText(""));
            XWPFTable table = document.createTable(snapshot.withArray("lines").size() + 1, HEADERS.length);
            for (int i = 0; i < HEADERS.length; i++) table.getRow(0).getCell(i).setText(HEADERS[i]);
            int rowIndex = 1;
            for (JsonNode line : snapshot.withArray("lines")) {
                String[] values = values(snapshot, line);
                for (int i = 0; i < values.length; i++) table.getRow(rowIndex).getCell(i).setText(values[i]);
                rowIndex++;
            }
            XWPFParagraph sign = document.createParagraph();
            sign.createRun().setText("制表：____________    科室负责人：____________    复核：____________    日期：____________");
            document.write(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("周度 DOCX 生成失败", error);
        }
    }

    byte[] pdf(ObjectNode snapshot, SessionUser user) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A3.rotate(), 18, 18, 24, 24);
            PdfWriter.getInstance(document, output);
            document.open();
            Font titleFont = new Font(chineseBaseFont(), 14, Font.BOLD);
            Font body = new Font(chineseBaseFont(), 7, Font.NORMAL);
            Paragraph title = new Paragraph(snapshot.path("departmentName").asText() + " 周度库存快照", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            document.add(title);
            document.add(new Paragraph("周次：" + snapshot.path("weekNo").asText() + "  Revision：R" + snapshot.path("revision").asInt()
                + "  状态：" + statusLabel(snapshot) + "  来源截止：" + snapshot.path("sourceCutoffAt").asText(), body));
            document.add(new Paragraph("导出人：" + user.name() + "  导出时间：" + exportedAt()
                + "  筛选条件：周次=" + snapshot.path("weekNo").asText() + "；科室=" + snapshot.path("departmentName").asText()
                + "  确认人：" + snapshot.path("confirmedBy").asText("") + "  确认时间：" + snapshot.path("confirmedAt").asText(""), body));
            PdfPTable table = new PdfPTable(HEADERS.length);
            table.setWidthPercentage(100);
            for (String header : HEADERS) cell(table, body, header);
            for (JsonNode line : snapshot.withArray("lines")) for (String value : values(snapshot, line)) cell(table, body, value);
            document.add(table);
            document.add(new Paragraph("制表：____________    科室负责人：____________    复核：____________    日期：____________", body));
            document.close();
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("周度 PDF 生成失败", error);
        }
    }

    private static String[] values(ObjectNode snapshot, JsonNode line) {
        return new String[] {
            line.path("itemName").asText(), careTypeLabel(line), line.path("itemUnit").asText(),
            textNumber(line, "sourceSummary.actualPatientVolume"), textNumber(line, "sourceSummary.plannedPatientVolume"),
            textNumber(line, "sourceSummary.perPatientStandardQuantity"), textNumber(line, "sourceSummary.conversionFactor"),
            patientVolumeSource(line), textNumber(line, "openingQuantity"), textNumber(line, "transferInQuantity"),
            textNumber(line, "actualConsumedQuantity"), textNumber(line, "reversalQuantity"), textNumber(line, "returnedQuantity"),
            textNumber(line, "scrappedQuantity"), textNumber(line, "countAdjustmentQuantity"), textNumber(line, "closingQuantity"),
            textNumber(line, "availableQuantity"), textNumber(line, "expectedQuantity"), textNumber(line, "expectedActualVariance"),
            textNumber(line, "safetyStockQuantity"), textNumber(line, "suggestedQuantity"), textNumber(line, "adjustedQuantity"),
            textNumber(line, "adjustmentVariance"), line.path("adjustmentReason").asText(""), statusLabel(snapshot), auditSource(line)
        };
    }

    private static String fieldAt(int index) {
        return new String[] {"", "", "", "sourceSummary.actualPatientVolume", "sourceSummary.plannedPatientVolume",
            "sourceSummary.perPatientStandardQuantity", "sourceSummary.conversionFactor", "", "openingQuantity",
            "transferInQuantity", "actualConsumedQuantity", "reversalQuantity", "returnedQuantity", "scrappedQuantity",
            "countAdjustmentQuantity", "closingQuantity", "availableQuantity", "expectedQuantity", "expectedActualVariance",
            "safetyStockQuantity", "suggestedQuantity", "adjustedQuantity", "adjustmentVariance", "", "", ""}[index];
    }

    private static void row(Sheet sheet, int index, String... values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
    }

    private static void cell(PdfPTable table, Font font, String value) {
        PdfPCell cell = new PdfPCell(new Phrase(value == null ? "" : value, font));
        cell.setPadding(2);
        table.addCell(cell);
    }

    private static double number(JsonNode node, String field) {
        if ("actualConsumedQuantity".equals(field)) return number(node, "consumedQuantity") - number(node, "reversalQuantity");
        if (field.startsWith("sourceSummary.")) return node.path("sourceSummary").path(field.substring("sourceSummary.".length())).asDouble(0);
        return node.path(field).asDouble(0);
    }

    private static String textNumber(JsonNode node, String field) {
        if ("actualConsumedQuantity".equals(field)) {
            return BigDecimal.valueOf(number(node, field)).stripTrailingZeros().toPlainString();
        }
        JsonNode value = field.startsWith("sourceSummary.")
            ? node.path("sourceSummary").path(field.substring("sourceSummary.".length()))
            : node.path(field);
        return value.isNumber() ? value.decimalValue().stripTrailingZeros().toPlainString() : value.asText("0");
    }

    private static String careTypeLabel(JsonNode line) {
        String value = line.path("careType").asText(line.path("sourceSummary").path("careType").asText("outpatient"));
        return "inpatient".equalsIgnoreCase(value) || "住院".equals(value) ? "住院" : "门诊";
    }

    private static String patientVolumeSource(JsonNode line) {
        return "pre_ai_encounters".equals(line.path("sourceSummary").path("patientVolumeSource").asText())
            ? "患者登记"
            : "计划患者数";
    }

    private static String auditSource(JsonNode line) {
        JsonNode source = line.path("sourceSummary");
        String flag = source.path("varianceFlag").asText("").isBlank() ? "" : "；异常：" + source.path("varianceFlag").asText();
        return patientVolumeSource(line) + "；标准=" + textNumber(line, "sourceSummary.perPatientStandardQuantity")
            + "；换算=" + textNumber(line, "sourceSummary.conversionFactor")
            + "；扣减流水=" + textNumber(line, "sourceSummary.consumptionEventVolume") + flag;
    }

    private static String statusLabel(ObjectNode snapshot) {
        return "CONFIRMED".equals(snapshot.path("status").asText()) ? "正式版/已确认" : "草稿/待确认";
    }

    private static String exportedAt() {
        return LocalDateTime.now().toString();
    }

    private static BaseFont chineseBaseFont() throws Exception {
        Path simsun = Path.of("C:/Windows/Fonts/simsun.ttc");
        if (Files.exists(simsun)) return BaseFont.createFont(simsun + ",0", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        throw new IllegalStateException("未找到中文字体 C:/Windows/Fonts/simsun.ttc");
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder builder = new StringBuilder();
            for (byte b : digest) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (Exception error) {
            throw new IllegalStateException("导出摘要计算失败", error);
        }
    }

    private static String mediaType(String format) {
        return switch (format) {
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> "application/pdf";
        };
    }

    public record ExportFile(byte[] body, String filename, String mediaType) {}
}
