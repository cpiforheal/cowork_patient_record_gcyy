package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Period reports and planning-only allocations derived from saved department daily drafts. */
@Service
@Profile("mysql")
public class InventoryDepartmentPeriodService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryDepartmentDraftService drafts;
    private final InventoryRepository repository;

    public InventoryDepartmentPeriodService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                            InventoryDepartmentDraftService drafts, InventoryRepository repository) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.drafts = drafts;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ObjectNode report(String departmentKey, String periodType, LocalDate anchorDate, SessionUser user) {
        String departmentName = drafts.requireDepartment(departmentKey, user);
        Period period = period(periodType, anchorDate);
        List<AuditLine> audit = readAudit(departmentKey, period.start(), period.end());
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("departmentKey", departmentKey);
        result.put("departmentName", departmentName);
        result.put("periodType", period.type());
        result.put("periodStart", period.start().toString());
        result.put("periodEnd", period.end().toString());
        result.put("savedDraftCount", audit.stream().map(AuditLine::draftId).distinct().count());
        ObjectNode volumes = businessVolumes(audit);
        result.put("outpatientVolume", volumes.path("outpatient").asInt());
        result.put("inpatientVolume", volumes.path("inpatient").asInt());
        result.set("summary", summary(audit));
        result.set("dailyAudit", auditArray(audit));
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode allocation(String departmentKey, String monthText, LocalDate throughDate, SessionUser user) {
        String departmentName = drafts.requireDepartment(departmentKey, user);
        YearMonth month = parseMonth(monthText);
        LocalDate end = throughDate == null ? month.atEndOfMonth() : throughDate;
        if (end.isBefore(month.atDay(1))) end = month.atDay(1);
        if (end.isAfter(month.atEndOfMonth())) end = month.atEndOfMonth();
        LocalDate effectiveEnd = end;
        PlanHeader header = jdbcTemplate.query(
            "SELECT id, revision, operator_name, operator_username, updated_at FROM inventory_department_allocation_plans WHERE department_key = ? AND business_month = ?",
            rows -> rows.next() ? new PlanHeader(rows.getString(1), rows.getInt(2), rows.getString(3), rows.getString(4), rows.getTimestamp(5).toLocalDateTime().toString()) : null,
            departmentKey, month.atDay(1)
        );
        Map<String, Usage> usage = usage(readAudit(departmentKey, month.atDay(1), effectiveEnd));
        YearMonth previousMonth = month.minusMonths(1);
        Map<String, Usage> previousUsage = usage(readAudit(departmentKey, previousMonth.atDay(1), previousMonth.atEndOfMonth()));
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("departmentKey", departmentKey);
        result.put("departmentName", departmentName);
        result.put("month", month.toString());
        result.put("throughDate", effectiveEnd.toString());
        result.put("exists", header != null);
        result.put("revision", header == null ? 0 : header.revision());
        if (header != null) {
            result.put("operator", header.operator());
            result.put("operatorUsername", header.username());
            result.put("updatedAt", header.updatedAt());
        }
        ArrayNode lines = jdbcTemplate.query(
            "SELECT material_name, unit, allocated_quantity, source_type, count_reference, manual_adjustment, warning_threshold FROM inventory_department_allocation_plan_lines WHERE plan_id = ? ORDER BY material_name, unit",
            (org.springframework.jdbc.core.ResultSetExtractor<ArrayNode>) rows -> allocationLines(rows, usage, previousUsage, effectiveEnd, month),
            header == null ? "" : header.id()
        );
        result.set("lines", lines);
        result.set("usage", usageArray(usage));
        result.set("previousUsage", usageArray(previousUsage));
        return result;
    }

    @Transactional
    public ObjectNode saveAllocation(JsonNode payload, SessionUser user) {
        String departmentKey = text(payload, "departmentKey");
        String departmentName = drafts.requireDepartment(departmentKey, user);
        YearMonth month = parseMonth(text(payload, "month"));
        JsonNode submitted = payload.path("lines");
        if (!submitted.isArray() || submitted.size() > 400) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Allocation lines are invalid");
        int expectedRevision = Math.max(0, payload.path("revision").asInt(0));
        PlanHeader current = jdbcTemplate.query(
            "SELECT id, revision, operator_name, operator_username, updated_at FROM inventory_department_allocation_plans WHERE department_key = ? AND business_month = ? FOR UPDATE",
            rows -> rows.next() ? new PlanHeader(rows.getString(1), rows.getInt(2), rows.getString(3), rows.getString(4), rows.getTimestamp(5).toLocalDateTime().toString()) : null,
            departmentKey, month.atDay(1)
        );
        String planId;
        if (current == null) {
            if (expectedRevision != 0) throw stale();
            planId = "inv-allocation-plan-" + UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO inventory_department_allocation_plans (id, department_key, department_name, business_month, revision, operator_name, operator_username) VALUES (?, ?, ?, ?, 1, ?, ?)",
                planId, departmentKey, departmentName, month.atDay(1), user.name(), user.username());
        } else {
            if (current.revision() != expectedRevision) throw stale();
            planId = current.id();
            jdbcTemplate.update("UPDATE inventory_department_allocation_plans SET revision = revision + 1, operator_name = ?, operator_username = ? WHERE id = ?", user.name(), user.username(), planId);
            jdbcTemplate.update("DELETE FROM inventory_department_allocation_plan_lines WHERE plan_id = ?", planId);
        }
        for (JsonNode line : submitted) {
            String material = text(line, "materialName");
            String unit = text(line, "unit");
            if (material.isBlank() || unit.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Material and unit are required");
            double allocated = number(line, "allocatedQuantity", 0);
            double adjustment = number(line, "manualAdjustment", 0);
            Double warning = nullableNumber(line.get("warningThreshold"));
            if (allocated < 0 || allocated + adjustment < 0 || (warning != null && warning < 0)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Allocation quantities cannot be negative");
            }
            jdbcTemplate.update("INSERT INTO inventory_department_allocation_plan_lines (id, plan_id, material_name, unit, allocated_quantity, source_type, count_reference, manual_adjustment, warning_threshold) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "inv-allocation-line-" + UUID.randomUUID(), planId, material, unit, allocated, source(line), text(line, "countReference"), adjustment, warning);
        }
        repository.log(user.name(), "Save department allocation plan", "department_allocation_plan", departmentName + " " + month, "Planning only; no inventory ledger movement was created");
        LocalDate throughDate = null;
        String throughDateText = text(payload, "throughDate");
        if (!throughDateText.isBlank()) {
            try { throughDate = LocalDate.parse(throughDateText); }
            catch (Exception error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "throughDate must be YYYY-MM-DD"); }
        }
        return allocation(departmentKey, month.toString(), throughDate, user);
    }

    @Transactional(readOnly = true)
    public ExportFile export(String departmentKey, String periodType, LocalDate anchorDate, String format, SessionUser user) {
        ObjectNode report = report(departmentKey, periodType, anchorDate, user);
        String safe = report.path("periodStart").asText();
        if ("xlsx".equalsIgnoreCase(format)) return new ExportFile(xlsx(report), "department-" + periodType + "-" + safe + ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        if ("csv".equalsIgnoreCase(format)) return new ExportFile(csvZip(report), "department-" + periodType + "-" + safe + ".zip", "application/zip");
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only xlsx or csv is supported");
    }

    private List<AuditLine> readAudit(String departmentKey, LocalDate from, LocalDate to) {
        return jdbcTemplate.query(
            "SELECT id, business_date, revision, template_version, operator_name, operator_username, updated_at, raw_json FROM inventory_department_daily_drafts WHERE department_key = ? AND business_date BETWEEN ? AND ? ORDER BY business_date, updated_at, id",
            rows -> {
                List<AuditLine> result = new ArrayList<>();
                while (rows.next()) result.addAll(parseDraft(rows));
                return result;
            }, departmentKey, from, to
        );
    }

    private List<AuditLine> parseDraft(ResultSet rows) throws java.sql.SQLException {
        String id = rows.getString("id"); LocalDate date = rows.getDate("business_date").toLocalDate();
        JsonNode draft;
        try {
            draft = objectMapper.readTree(rows.getString("raw_json"));
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read saved department draft", error);
        }
        JsonNode groups = draft.path("groupVolumes");
        List<AuditLine> parsed = new ArrayList<>();
        for (JsonNode line : draft.path("lines")) {
            String unit = text(line, "unit");
            Double standard = nullableNumber(line.get("standardQuantity"));
            int volume = line.hasNonNull("volumeOverride") ? Math.max(0, line.path("volumeOverride").asInt()) : Math.max(0, groups.path(text(line, "serviceGroup")).asInt());
            double manualAdjustment = number(line, "manualAdjustment", 0);
            double referenceQuantity = Math.max(0, round((line.path("isSupplemental").asBoolean(false) || standard == null ? 0 : standard * volume) + manualAdjustment, 6));
            Double actualQuantity = nullableNonNegativeNumber(line.get("actualQuantity"));
            boolean pending = text(line, "materialName").isBlank() || unit.isBlank();
            parsed.add(new AuditLine(id, date, rows.getInt("revision"), rows.getString("template_version"), rows.getString("operator_name"), rows.getString("operator_username"), rows.getTimestamp("updated_at").toLocalDateTime().toString(), text(line, "serviceGroup"), text(line, "careType"), text(line, "materialName"), unit, standard, volume, manualAdjustment, referenceQuantity, actualQuantity, pending));
        }
        return parsed.isEmpty() ? List.of() : parsed;
    }

    private ArrayNode summary(List<AuditLine> audit) { return usageArray(usage(audit)); }
    private Map<String, Usage> usage(List<AuditLine> audit) {
        Map<String, Usage> result = new LinkedHashMap<>();
        for (AuditLine line : audit) if (!line.pending() && line.actualQuantity() != null) {
            String key = line.material() + "\u0000" + line.unit();
            Usage old = result.get(key);
            result.put(key, old == null ? Usage.of(line) : old.add(line));
        }
        return result;
    }
    private ObjectNode businessVolumes(List<AuditLine> audit) {
        Map<String, Integer> volumes = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AuditLine line : audit) {
            String type = line.careType();
            if (!"outpatient".equals(type) && !"inpatient".equals(type)) continue;
            if (seen.add(line.draftId() + "\u0000" + type + "\u0000" + line.serviceGroup())) {
                volumes.merge(type, line.volume(), Integer::sum);
            }
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("outpatient", volumes.getOrDefault("outpatient", 0));
        result.put("inpatient", volumes.getOrDefault("inpatient", 0));
        return result;
    }
    private ArrayNode usageArray(Map<String, Usage> usage) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        usage.values().forEach(row -> { ObjectNode item = result.addObject(); item.put("materialName", row.material()); item.put("unit", row.unit()); item.put("quantity", round(row.quantity(), 6)); item.put("activeDays", row.activeDays()); });
        return result;
    }
    private ArrayNode auditArray(List<AuditLine> audit) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        audit.forEach(line -> { ObjectNode row = result.addObject(); row.put("draftId", line.draftId()); row.put("businessDate", line.date().toString()); row.put("revision", line.revision()); row.put("templateVersion", line.templateVersion()); row.put("operator", line.operator()); row.put("operatorUsername", line.username()); row.put("updatedAt", line.updatedAt()); row.put("serviceGroup", line.serviceGroup()); row.put("careType", line.careType()); row.put("materialName", line.material()); row.put("unit", line.unit()); if (line.standard() != null) row.put("standardQuantity", line.standard()); row.put("businessVolume", line.volume()); row.put("manualAdjustment", line.manualAdjustment()); row.put("referenceQuantity", line.referenceQuantity()); if (line.actualQuantity() == null) row.putNull("actualQuantity"); else row.put("actualQuantity", line.actualQuantity()); row.put("dailyQuantity", line.actualQuantity() == null ? 0 : line.actualQuantity()); row.put("status", line.pending() ? "PENDING_MATERIAL_OR_UNIT" : line.actualQuantity() == null ? "ACTUAL_NOT_REPORTED" : "SAVED"); });
        return result;
    }
    private ArrayNode allocationLines(ResultSet rows, Map<String, Usage> usage, Map<String, Usage> previousUsage, LocalDate through, YearMonth month) throws java.sql.SQLException {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        while (rows.next()) {
            String material = rows.getString(1), unit = rows.getString(2); Usage used = usage.get(material + "\u0000" + unit);
            double allocated = rows.getDouble(3), adjustment = rows.getDouble(6), monthUsed = used == null ? 0 : used.quantity();
            Usage previous = previousUsage.get(material + "\u0000" + unit);
            int elapsedDays = Math.max(1, through.getDayOfMonth());
            double suggestedWarning = round(monthUsed / elapsedDays * 3, 6);
            Number configured = (Number) rows.getObject(7);
            Double configuredWarning = configured == null ? null : configured.doubleValue();
            ObjectNode row = result.addObject(); row.put("materialName", material); row.put("unit", unit); row.put("allocatedQuantity", allocated); row.put("manualAdjustment", adjustment); row.put("effectiveAllocatedQuantity", round(allocated + adjustment, 6)); row.put("sourceType", rows.getString(4)); row.put("countReference", rows.getString(5)); if (configuredWarning != null) row.put("warningThreshold", configuredWarning); row.put("suggestedWarningThreshold", suggestedWarning); row.put("previousMonthSuggestedQuantity", previous == null ? 0 : round(previous.quantity(), 6)); row.put("monthUsedQuantity", monthUsed); row.put("monthRemainingQuantity", round(allocated + adjustment - monthUsed, 6)); row.put("status", configuredWarning == null && allocated == 0 && adjustment == 0 ? "PENDING" : (allocated + adjustment - monthUsed <= (configuredWarning == null ? suggestedWarning : configuredWarning) ? "WARNING" : "NORMAL"));
        }
        return result;
    }
    private byte[] xlsx(ObjectNode report) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeSheet(workbook, "汇总", report, false); writeSheet(workbook, "每日审计", report, true); workbook.write(out); return out.toByteArray();
        } catch (Exception error) { throw new IllegalStateException("Cannot create XLSX report", error); }
    }
    private void writeSheet(XSSFWorkbook workbook, String name, ObjectNode report, boolean audit) {
        var sheet = workbook.createSheet(name); int rowIndex = 0; Row meta = sheet.createRow(rowIndex++); meta.createCell(0).setCellValue(report.path("departmentName").asText()); meta.createCell(1).setCellValue(report.path("periodStart").asText() + " ~ " + report.path("periodEnd").asText()); meta.createCell(2).setCellValue("门诊量 " + report.path("outpatientVolume").asInt() + "；住院量 " + report.path("inpatientVolume").asInt());
        String[] heads = audit ? new String[] {"业务日期","耗材","单位","服务项目","业务量/患者数","每人次定额","手工调整","参考试算（不计入统计）","实际使用量","操作账号","责任人","revision","计算版本","保存状态"} : new String[] {"耗材","单位","周期用量","有使用日期数"}; Row head = sheet.createRow(rowIndex++); for (int i=0;i<heads.length;i++) head.createCell(i).setCellValue(heads[i]);
        for (JsonNode line : audit ? report.path("dailyAudit") : report.path("summary")) { Row row = sheet.createRow(rowIndex++); if (audit) { String[] values = {line.path("businessDate").asText(),line.path("materialName").asText(),line.path("unit").asText(),line.path("serviceGroup").asText(),line.path("businessVolume").asText(),line.path("standardQuantity").isMissingNode()?"":line.path("standardQuantity").asText(),line.path("manualAdjustment").asText(),line.path("referenceQuantity").asText(),line.path("actualQuantity").isNull()?"":line.path("actualQuantity").asText(),line.path("operatorUsername").asText(),line.path("operator").asText(),line.path("revision").asText(),line.path("templateVersion").asText(),line.path("status").asText()}; for(int i=0;i<values.length;i++)row.createCell(i).setCellValue(values[i]); } else { row.createCell(0).setCellValue(line.path("materialName").asText()); row.createCell(1).setCellValue(line.path("unit").asText()); row.createCell(2).setCellValue(line.path("quantity").asDouble()); row.createCell(3).setCellValue(line.path("activeDays").asInt()); } }
        for(int i=0;i<heads.length;i++) sheet.autoSizeColumn(i);
    }
    private byte[] csvZip(ObjectNode report) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) { zip.putNextEntry(new ZipEntry("summary.csv")); zip.write(csv(report.path("summary"), false)); zip.closeEntry(); zip.putNextEntry(new ZipEntry("daily-audit.csv")); zip.write(csv(report.path("dailyAudit"), true)); zip.closeEntry(); zip.finish(); return out.toByteArray(); } catch (Exception error) { throw new IllegalStateException("Cannot create CSV archive", error); }
    }
    private byte[] csv(JsonNode rows, boolean audit) { StringBuilder text = new StringBuilder("\uFEFF"); String[] heads = audit ? new String[] {"业务日期","耗材","单位","服务项目","业务量/患者数","每人次定额","手工调整","参考试算（不计入统计）","实际使用量","操作账号","责任人","revision","计算版本","保存状态"} : new String[] {"耗材","单位","周期用量","有使用日期数"}; text.append(String.join(",", heads)).append("\r\n"); for (JsonNode row: rows) { String[] values = audit ? new String[]{row.path("businessDate").asText(),row.path("materialName").asText(),row.path("unit").asText(),row.path("serviceGroup").asText(),row.path("businessVolume").asText(),row.path("standardQuantity").isMissingNode()?"":row.path("standardQuantity").asText(),row.path("manualAdjustment").asText(),row.path("referenceQuantity").asText(),row.path("actualQuantity").isNull()?"":row.path("actualQuantity").asText(),row.path("operatorUsername").asText(),row.path("operator").asText(),row.path("revision").asText(),row.path("templateVersion").asText(),row.path("status").asText()} : new String[]{row.path("materialName").asText(),row.path("unit").asText(),row.path("quantity").asText(),row.path("activeDays").asText()}; for(int i=0;i<values.length;i++){if(i>0)text.append(',');text.append('"').append(values[i].replace("\"","\"\"")).append('"');} text.append("\r\n"); } return text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    private Period period(String type, LocalDate anchor) { LocalDate date = anchor == null ? LocalDate.now() : anchor; if ("week".equalsIgnoreCase(type)) { LocalDate start = date.with(DayOfWeek.MONDAY); return new Period("week", start, start.plusDays(6)); } if ("month".equalsIgnoreCase(type)) { YearMonth month=YearMonth.from(date); return new Period("month",month.atDay(1),month.atEndOfMonth()); } throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Period must be week or month"); }
    private YearMonth parseMonth(String value) { try { return YearMonth.parse(value); } catch(Exception error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Month must be YYYY-MM"); } }
    private static String text(JsonNode node,String field){return node==null?"":node.path(field).asText("").trim();}
    private static double number(JsonNode node,String field,double fallback){Double value=nullableNumber(node.get(field)); return value==null?fallback:value;}
    private static Double nullableNumber(JsonNode value){return value==null||value.isNull()||!value.isNumber()||!Double.isFinite(value.asDouble())?null:value.asDouble();}
    private static Double nullableNonNegativeNumber(JsonNode value){Double parsed=nullableNumber(value); return parsed==null?null:Math.max(0,parsed);}
    private static String source(JsonNode line){String value=text(line,"sourceType"); return List.of("COUNT","MANUAL","PREVIOUS_MONTH").contains(value.toUpperCase())?value.toUpperCase():"MANUAL";}
    private static double round(double value,int scale){return BigDecimal.valueOf(value).setScale(scale,RoundingMode.HALF_UP).doubleValue();}
    private static ResponseStatusException stale(){return new ResponseStatusException(HttpStatus.CONFLICT,"Allocation plan was changed by another user; refresh and retry");}
    public record ExportFile(byte[] body, String filename, String mediaType) {}
    private record Period(String type,LocalDate start,LocalDate end){}
    private record PlanHeader(String id,int revision,String operator,String username,String updatedAt){}
    private record Usage(String material, String unit, double quantity, Set<LocalDate> dates) {
        static Usage of(AuditLine line) { return new Usage(line.material(), line.unit(), line.actualQuantity(), new LinkedHashSet<>(List.of(line.date()))); }
        Usage add(AuditLine line) { Set<LocalDate> next = new LinkedHashSet<>(dates); next.add(line.date()); return new Usage(material, unit, round(quantity + line.actualQuantity(), 6), next); }
        int activeDays() { return dates.size(); }
    }
    private record AuditLine(String draftId,LocalDate date,int revision,String templateVersion,String operator,String username,String updatedAt,String serviceGroup,String careType,String material,String unit,Double standard,int volume,double manualAdjustment,double referenceQuantity,Double actualQuantity,boolean pending){}
}
