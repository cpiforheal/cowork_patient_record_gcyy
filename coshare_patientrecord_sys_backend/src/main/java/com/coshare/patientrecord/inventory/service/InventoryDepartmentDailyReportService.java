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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

/** Department-level daily consumable report for departments without patient flow. */
@Service
@Profile("mysql")
public class InventoryDepartmentDailyReportService {

    private static final Map<String, Department> DEPARTMENTS = departments();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryAccessService inventoryAccess;
    private final InventoryRepository repository;

    public InventoryDepartmentDailyReportService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        InventoryAccessService inventoryAccess,
        InventoryRepository repository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.inventoryAccess = inventoryAccess;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ObjectNode read(String departmentKey, LocalDate businessDate, SessionUser user) {
        Department department = requireDepartment(departmentKey, user);
        LocalDate date = businessDate == null ? LocalDate.now() : businessDate;
        Report report = loadReport(department.id(), date);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("departmentKey", department.key());
        result.put("departmentId", department.id());
        result.put("departmentName", department.name());
        result.put("businessDate", date.toString());
        result.put("entryMode", department.entryMode());
        result.set("closure", closureJson(department.id(), date));
        if (report == null) {
            result.put("id", "");
            result.put("status", "DRAFT");
            result.put("revision", 0);
            result.put("note", "");
            result.set("lines", templateLines(department.key()));
        } else {
            result.put("id", report.id());
            result.put("status", report.status());
            result.put("revision", report.revision());
            result.put("note", report.note());
            result.put("submittedBy", report.submittedBy());
            result.put("submittedAt", report.submittedAt() == null ? "" : report.submittedAt().toString());
            result.set("lines", loadLines(report.id()));
        }
        result.put("locked", isLocked(date, report, result.path("closure")));
        return result;
    }

    @Transactional
    public ObjectNode save(JsonNode payload, SessionUser user) {
        Department department = requireDepartment(text(payload, "departmentKey"), user);
        LocalDate date = parseDate(text(payload, "businessDate"));
        Report current = loadReport(department.id(), date);
        ensureEditable(department, date, current);
        int expectedRevision = Math.max(payload.path("revision").asInt(0), 0);
        if (current != null && current.revision() != expectedRevision) throw stale();
        JsonNode linesNode = payload.path("lines");
        if (!linesNode.isArray() || linesNode.size() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "科室日报明细格式不正确");
        }
        List<Line> lines = new ArrayList<>();
        for (JsonNode node : linesNode) {
            String itemId = text(node, "itemId");
            BigDecimal actual = decimal(node, "actualQuantity");
            if (itemId.isBlank() || actual == null || actual.signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "耗材和数量不能为空");
            }
            Item item = requireItem(itemId);
            lines.add(new Line(
                item.id(),
                firstNonBlank(text(node, "itemName"), item.name()),
                firstNonBlank(text(node, "unit"), item.unit()),
                decimal(node, "presetQuantity"),
                actual,
                firstNonBlank(text(node, "source"), "TEMPLATE").toUpperCase(Locale.ROOT),
                text(node, "exceptionReason")
            ));
        }
        String note = text(payload, "note");
        String reportId = current == null ? "department-daily-report-" + UUID.randomUUID() : current.id();
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("departmentKey", department.key());
        raw.put("businessDate", date.toString());
        raw.put("note", note);
        if (current == null) {
            jdbcTemplate.update(
                """
                INSERT INTO inventory_department_daily_reports
                  (id, department_id, department_key, department_name, business_date, status, revision, note, raw_json)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', 1, ?, CAST(? AS JSON))
                """,
                reportId, department.id(), department.key(), department.name(), date, note, json(raw)
            );
        } else {
            jdbcTemplate.update(
                """
                UPDATE inventory_department_daily_reports
                SET revision = revision + 1, note = ?, status = 'DRAFT', raw_json = CAST(? AS JSON)
                WHERE id = ? AND revision = ?
                """,
                note, json(raw), reportId, expectedRevision
            );
        }
        jdbcTemplate.update("DELETE FROM inventory_department_daily_report_lines WHERE report_id = ?", reportId);
        int lineNo = 0;
        for (Line line : lines) {
            ObjectNode lineRaw = objectMapper.createObjectNode();
            lineRaw.put("itemId", line.itemId());
            lineRaw.put("actualQuantity", line.actualQuantity());
            lineRaw.put("source", line.source());
            jdbcTemplate.update(
                """
                INSERT INTO inventory_department_daily_report_lines
                  (id, report_id, line_no, item_id, item_name_snapshot, unit_snapshot, preset_quantity,
                   actual_quantity, source, exception_reason, review_status, raw_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', CAST(? AS JSON))
                """,
                "department-daily-line-" + UUID.randomUUID(), reportId, ++lineNo, line.itemId(), line.itemName(),
                line.unit(), line.presetQuantity(), line.actualQuantity(), line.source(), blankToNull(line.exceptionReason()),
                json(lineRaw)
            );
        }
        repository.log(user.name(), "保存科室日报", "department_daily_report", department.name() + " " + date,
            "保存 " + lines.size() + " 行耗材，未扣减库存");
        return read(department.key(), date, user);
    }

    @Transactional
    public ObjectNode submit(String reportId, SessionUser user) {
        Report report = loadReportById(reportId);
        if (report == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "科室日报不存在");
        Department department = requireDepartment(report.departmentKey(), user);
        ensureEditable(department, report.businessDate(), report);
        Integer positiveLines = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM inventory_department_daily_report_lines WHERE report_id = ? AND actual_quantity > 0",
            Integer.class,
            reportId
        );
        if (positiveLines == null || positiveLines == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "科室日报至少需要一行实际使用量");
        }
        jdbcTemplate.update(
            """
            UPDATE inventory_department_daily_reports
            SET status = 'SUBMITTED', submitted_by = ?, submitted_at = CURRENT_TIMESTAMP(3)
            WHERE id = ?
            """,
            user.name(), reportId
        );
        upsertApprovalBatch(report);
        upsertClosure(department, report.businessDate(), "SUBMITTED", user);
        repository.log(user.name(), "提交科室日报", "department_daily_report", department.name() + " " + report.businessDate(),
            "已提交库管审核，未扣减库存");
        return read(department.key(), report.businessDate(), user);
    }

    @Transactional(readOnly = true)
    public ObjectNode calendar(String departmentKey, String month, SessionUser user) {
        Department department = requireDepartment(departmentKey, user);
        LocalDate first;
        try {
            String value = month == null || month.isBlank()
                ? LocalDate.now().withDayOfMonth(1).toString()
                : month.trim();
            if (value.length() == 7) value = value + "-01";
            first = LocalDate.parse(value).withDayOfMonth(1);
        } catch (Exception ignored) {
            first = LocalDate.now().withDayOfMonth(1);
        }
        LocalDate last = first.withDayOfMonth(first.lengthOfMonth());
        ArrayNode days = jdbcTemplate.query(
            """
            SELECT r.id, r.business_date, r.status, r.submitted_at, r.submitted_by,
                   (SELECT COUNT(*) FROM inventory_department_daily_report_lines l
                    WHERE l.report_id = r.id AND l.actual_quantity > 0) line_count
            FROM inventory_department_daily_reports r
            WHERE r.department_key = ? AND r.business_date BETWEEN ? AND ?
            ORDER BY r.business_date
            """,
            resultSet -> {
                ArrayNode list = objectMapper.createArrayNode();
                while (resultSet.next()) {
                    ObjectNode row = list.addObject();
                    row.put("date", resultSet.getDate("business_date").toLocalDate().toString());
                    row.put("reportId", resultSet.getString("id"));
                    row.put("status", resultSet.getString("status"));
                    row.put("lineCount", resultSet.getInt("line_count"));
                    Timestamp submittedAt = resultSet.getTimestamp("submitted_at");
                    row.put("submittedAt", submittedAt == null ? "" : submittedAt.toLocalDateTime().toString());
                    row.put("submittedBy", resultSet.getString("submitted_by"));
                }
                return list;
            },
            department.key(), first, last
        );
        Map<String, ObjectNode> dayMap = new LinkedHashMap<>();
        for (JsonNode day : days) {
            if (day instanceof ObjectNode row) dayMap.put(row.path("date").asText(), row);
        }
        if ("PATIENT".equals(department.entryMode())) {
            jdbcTemplate.query(
                """
                SELECT si.business_date,
                       COUNT(*) patient_total,
                       COUNT(DISTINCT CASE WHEN d.id IS NOT NULL THEN si.patient_id END) patient_count,
                       SUM(CASE WHEN d.status IN ('SUBMITTED', 'PARTIALLY_APPROVED', 'APPROVED') THEN 1 ELSE 0 END) entered,
                       SUM(CASE WHEN d.status = 'DRAFT' THEN 1 ELSE 0 END) draft,
                       SUM(CASE WHEN d.status = 'RETURNED' THEN 1 ELSE 0 END) returned,
                       SUM(CASE WHEN d.id IS NULL OR (d.status = 'DRAFT' AND COALESCE(d.no_consumption, 0) = 0
                            AND (SELECT COUNT(*) FROM inventory_patient_consumption_lines l WHERE l.document_id = d.id) = 0)
                           THEN 1 ELSE 0 END) missing,
                       SUM(CASE WHEN COALESCE(d.no_consumption, 0) = 1 THEN 1 ELSE 0 END) no_consumption
                FROM inventory_service_instances si
                LEFT JOIN inventory_patient_consumption_documents d ON d.service_instance_id = si.id
                WHERE si.department_key = ? AND si.business_date BETWEEN ? AND ?
                GROUP BY si.business_date
                """,
                resultSet -> {
                    while (resultSet.next()) {
                        String date = resultSet.getDate("business_date").toLocalDate().toString();
                        ObjectNode row = dayMap.get(date);
                        if (row == null) {
                            row = objectMapper.createObjectNode();
                            row.put("date", date);
                            row.put("reportId", "");
                            row.put("status", "");
                            row.put("lineCount", 0);
                            row.put("submittedAt", "");
                            row.put("submittedBy", "");
                            dayMap.put(date, row);
                        }
                        row.put("patientTotal", resultSet.getInt("patient_total"));
                        row.put("patientCount", resultSet.getInt("patient_count"));
                        row.put("entered", resultSet.getInt("entered"));
                        row.put("draft", resultSet.getInt("draft"));
                        row.put("returned", resultSet.getInt("returned"));
                        row.put("missing", resultSet.getInt("missing"));
                        row.put("noConsumption", resultSet.getInt("no_consumption"));
                    }
                },
                department.key(), first, last
            );
        }
        ArrayNode calendarDays = objectMapper.createArrayNode();
        for (ObjectNode row : new java.util.TreeMap<>(dayMap).values()) {
            if (!row.has("patientTotal")) row.put("patientTotal", 0);
            if (!row.has("patientCount")) row.put("patientCount", 0);
            if (!row.has("entered")) row.put("entered", 0);
            if (!row.has("draft")) row.put("draft", 0);
            if (!row.has("returned")) row.put("returned", 0);
            if (!row.has("missing")) row.put("missing", 0);
            if (!row.has("noConsumption")) row.put("noConsumption", 0);
            calendarDays.add(row);
        }
        days = calendarDays;
        ObjectNode result = objectMapper.createObjectNode();
        result.put("departmentKey", department.key());
        result.put("departmentName", department.name());
        result.put("entryMode", department.entryMode());
        result.put("month", first.toString());
        result.put("today", LocalDate.now().toString());
        result.set("days", days);
        return result;
    }

    @Transactional(readOnly = true)
    public byte[] export(String reportId, String format, SessionUser user) {
        Report report = loadReportById(reportId);
        if (report == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "科室日报不存在");
        requireDepartment(report.departmentKey(), user);
        ArrayNode lines = loadLines(report.id());
        if ("xlsx".equalsIgnoreCase(format)) return xlsx(report, lines);
        return csv(report, lines);
    }

    private ArrayNode templateLines(String departmentKey) {
        ArrayNode lines = jdbcTemplate.query(
            """
            SELECT r.item_id, i.name item_name, i.unit item_unit, r.standard_quantity, r.binding_type
            FROM inventory_quota_rules r
            JOIN inventory_quota_versions v ON v.id = r.version_id AND v.status = 'ACTIVE'
            JOIN inventory_items i ON i.id = r.item_id AND i.enabled = 1
            WHERE r.department_key = ? AND r.enabled = 1
            ORDER BY r.service_group, r.source_row
            """,
            resultSet -> {
                ArrayNode list = objectMapper.createArrayNode();
                Map<String, ObjectNode> seen = new LinkedHashMap<>();
                while (resultSet.next()) {
                    String itemId = resultSet.getString("item_id");
                    if (seen.containsKey(itemId)) continue;
                    ObjectNode row = objectMapper.createObjectNode();
                    BigDecimal preset = resultSet.getBigDecimal("standard_quantity");
                    String binding = resultSet.getString("binding_type");
                    row.put("itemId", itemId);
                    row.put("itemName", resultSet.getString("item_name"));
                    row.put("unit", resultSet.getString("item_unit"));
                    row.put("presetQuantity", preset);
                    row.put("actualQuantity", "FIXED_DAILY".equalsIgnoreCase(binding) && preset != null ? preset : BigDecimal.ZERO);
                    row.put("source", "TEMPLATE");
                    row.put("reviewStatus", "PENDING");
                    seen.put(itemId, row);
                    list.add(row);
                }
                return list;
            },
            departmentKey
        );
        return lines;
    }

    private ObjectNode loadReportNode(Report report) {
        return objectMapper.createObjectNode();
    }

    private ArrayNode loadLines(String reportId) {
        return jdbcTemplate.query(
            """
            SELECT l.*, i.name item_name, i.unit item_unit
            FROM inventory_department_daily_report_lines l
            LEFT JOIN inventory_items i ON i.id = l.item_id
            WHERE l.report_id = ?
            ORDER BY l.line_no
            """,
            resultSet -> {
                ArrayNode list = objectMapper.createArrayNode();
                while (resultSet.next()) {
                    ObjectNode row = list.addObject();
                    row.put("lineId", resultSet.getString("id"));
                    row.put("itemId", resultSet.getString("item_id"));
                    row.put("itemName", firstNonBlank(resultSet.getString("item_name_snapshot"), resultSet.getString("item_name")));
                    row.put("unit", firstNonBlank(resultSet.getString("unit_snapshot"), resultSet.getString("item_unit")));
                    row.put("presetQuantity", resultSet.getBigDecimal("preset_quantity"));
                    row.put("actualQuantity", resultSet.getBigDecimal("actual_quantity"));
                    row.put("source", resultSet.getString("source"));
                    row.put("exceptionReason", resultSet.getString("exception_reason"));
                    row.put("reviewStatus", resultSet.getString("review_status"));
                    row.put("reviewNote", resultSet.getString("review_note"));
                    row.put("consumptionEventId", resultSet.getString("consumption_event_id"));
                }
                return list;
            },
            reportId
        );
    }

    private ObjectNode closureJson(String departmentId, LocalDate date) {
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT * FROM inventory_department_day_closures WHERE department_id = ? AND business_date = ?",
            (resultSet, rowNum) -> closureRow(resultSet),
            departmentId, date
        );
        ObjectNode result = rows.isEmpty() ? objectMapper.createObjectNode() : rows.get(0);
        if (date.isBefore(LocalDate.now()) && ("SUBMITTED".equals(result.path("status").asText()) || "APPROVED".equals(result.path("status").asText()))) {
            result.put("status", "LOCKED");
        }
        return result;
    }

    private ObjectNode closureRow(ResultSet resultSet) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("status", resultSet.getString("status"));
        row.put("submittedBy", resultSet.getString("submitted_by"));
        Timestamp submittedAt = resultSet.getTimestamp("submitted_at");
        row.put("submittedAt", submittedAt == null ? "" : submittedAt.toLocalDateTime().toString());
        Timestamp lockedAt = resultSet.getTimestamp("locked_at");
        row.put("lockedAt", lockedAt == null ? "" : lockedAt.toLocalDateTime().toString());
        return row;
    }

    private boolean isLocked(LocalDate date, Report report, JsonNode closure) {
        return date.isBefore(LocalDate.now())
            && ("SUBMITTED".equals(closure.path("status").asText()) || "APPROVED".equals(closure.path("status").asText())
                || "LOCKED".equals(closure.path("status").asText()));
    }

    private void ensureEditable(Department department, LocalDate date, Report report) {
        if (date.isBefore(LocalDate.now()) && report != null && "SUBMITTED".equals(report.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "科室日报已提交且已过业务日，不能直接修改；请发起更正");
        }
    }

    private void upsertApprovalBatch(Report report) {
        Integer lineCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM inventory_department_daily_report_lines WHERE report_id = ?",
            Integer.class,
            report.id()
        );
        jdbcTemplate.update(
            """
            INSERT INTO inventory_consumption_approval_batches
              (id, department_id, department_key, department_name, batch_type, business_date, status,
               document_count, line_count, approved_line_count, rejected_line_count)
            VALUES (?, ?, ?, ?, 'DAILY', ?, 'PENDING', 1, ?, 0, 0)
            ON DUPLICATE KEY UPDATE batch_type = 'DAILY', status = 'PENDING', document_count = 1,
              line_count = VALUES(line_count), approved_line_count = 0, rejected_line_count = 0,
              reviewed_by = NULL, reviewed_at = NULL
            """,
            "daily-approval-batch-" + UUID.randomUUID(), report.departmentId(), report.departmentKey(),
            report.departmentName(), report.businessDate(), lineCount == null ? 0 : lineCount
        );
    }

    private void upsertClosure(Department department, LocalDate date, String status, SessionUser user) {
        jdbcTemplate.update(
            """
            INSERT INTO inventory_department_day_closures
              (id, department_id, department_key, department_name, business_date, entry_mode, status, submitted_by, submitted_at)
            VALUES (?, ?, ?, ?, ?, 'DAILY', ?, ?, CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE status = VALUES(status), submitted_by = VALUES(submitted_by),
              submitted_at = VALUES(submitted_at)
            """,
            "day-closure-" + UUID.randomUUID(), department.id(), department.key(), department.name(), date, status, user.name()
        );
    }

    private Report loadReport(String departmentId, LocalDate date) {
        return jdbcTemplate.query(
            "SELECT * FROM inventory_department_daily_reports WHERE department_id = ? AND business_date = ?",
            resultSet -> resultSet.next() ? reportRow(resultSet) : null,
            departmentId, date
        );
    }

    private Report loadReportById(String reportId) {
        return jdbcTemplate.query(
            "SELECT * FROM inventory_department_daily_reports WHERE id = ?",
            resultSet -> resultSet.next() ? reportRow(resultSet) : null,
            reportId
        );
    }

    private Report reportRow(ResultSet resultSet) throws SQLException {
        Timestamp submittedAt = resultSet.getTimestamp("submitted_at");
        return new Report(
            resultSet.getString("id"), resultSet.getString("department_id"), resultSet.getString("department_key"),
            resultSet.getString("department_name"), resultSet.getDate("business_date").toLocalDate(),
            resultSet.getString("status"), resultSet.getInt("revision"), resultSet.getString("note"),
            resultSet.getString("submitted_by"), submittedAt == null ? null : submittedAt.toLocalDateTime()
        );
    }

    private Department requireDepartment(String key, SessionUser user) {
        Department department = DEPARTMENTS.get(key == null ? "" : key.trim());
        if (department == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知科室");
        if (!inventoryAccess.canViewAllDepartments(user) && !sameDepartment(user.department(), department.name())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问其他科室的日报");
        }
        String id = jdbcTemplate.query(
            "SELECT id FROM inventory_departments WHERE department_key = ? AND status = 'ACTIVE' LIMIT 1",
            resultSet -> resultSet.next() ? resultSet.getString(1) : null,
            department.key()
        );
        if (id == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "科室目录未配置：" + department.key());
        String entryMode = jdbcTemplate.query(
            "SELECT entry_mode FROM inventory_departments WHERE id = ?",
            resultSet -> resultSet.next() ? resultSet.getString(1) : "PATIENT",
            id
        );
        return new Department(department.key(), id, department.name(), entryMode);
    }

    private Item requireItem(String itemId) {
        List<Item> items = jdbcTemplate.query(
            "SELECT id, name, unit FROM inventory_items WHERE id = ? AND enabled = 1",
            (resultSet, rowNum) -> new Item(resultSet.getString("id"), resultSet.getString("name"), resultSet.getString("unit")),
            itemId
        );
        if (items.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "耗材不存在或已停用");
        return items.get(0);
    }

    private byte[] csv(Report report, ArrayNode lines) {
        StringBuilder result = new StringBuilder("\uFEFF");
        result.append("科室,业务日期,耗材,单位,模板数量,实际数量,来源,异常原因,审核状态\n");
        for (JsonNode line : lines) {
            result.append(csvCell(report.departmentName())).append(',')
                .append(report.businessDate()).append(',')
                .append(csvCell(line.path("itemName").asText())).append(',')
                .append(csvCell(line.path("unit").asText())).append(',')
                .append(line.path("presetQuantity").asText("")).append(',')
                .append(line.path("actualQuantity").asText("")).append(',')
                .append(csvCell(line.path("source").asText())).append(',')
                .append(csvCell(line.path("exceptionReason").asText())).append(',')
                .append(csvCell(line.path("reviewStatus").asText())).append('\n');
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] xlsx(Report report, ArrayNode lines) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("科室日报");
            Row header = sheet.createRow(0);
            String[] columns = { "科室", "业务日期", "耗材", "单位", "模板数量", "实际数量", "来源", "异常原因", "审核状态" };
            for (int index = 0; index < columns.length; index++) header.createCell(index).setCellValue(columns[index]);
            int rowIndex = 1;
            for (JsonNode line : lines) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(report.departmentName());
                row.createCell(1).setCellValue(report.businessDate().toString());
                row.createCell(2).setCellValue(line.path("itemName").asText());
                row.createCell(3).setCellValue(line.path("unit").asText());
                row.createCell(4).setCellValue(line.path("presetQuantity").asText(""));
                row.createCell(5).setCellValue(line.path("actualQuantity").asText(""));
                row.createCell(6).setCellValue(line.path("source").asText());
                row.createCell(7).setCellValue(line.path("exceptionReason").asText());
                row.createCell(8).setCellValue(line.path("reviewStatus").asText());
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("科室日报导出失败", error);
        }
    }

    private static String csvCell(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "业务日期格式不正确");
        }
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isNull() || value.asText().isBlank()) return null;
        try {
            return value.decimalValue();
        } catch (Exception ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 必须为数字");
        }
    }

    private static String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean sameDepartment(String userDepartment, String canonicalName) {
        if (userDepartment == null) return false;
        String value = userDepartment.trim();
        return value.equals(canonicalName)
            || ("后勤保洁".equals(value) && "后勤".equals(canonicalName))
            || ("后勤".equals(value) && "后勤保洁".equals(canonicalName));
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("科室日报序列化失败", error);
        }
    }

    private static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "科室日报已被其他人更新，请刷新后重试");
    }

    private static Map<String, Department> departments() {
        Map<String, Department> result = new LinkedHashMap<>();
        result.put("physiotherapy", new Department("physiotherapy", "", "理疗室", "PATIENT"));
        result.put("laboratory", new Department("laboratory", "", "检验科", "PATIENT"));
        result.put("nursing", new Department("nursing", "", "护理部", "PATIENT"));
        result.put("tcm", new Department("tcm", "", "中医科", "PATIENT"));
        result.put("operating", new Department("operating", "", "手术室", "PATIENT"));
        result.put("anesthesia", new Department("anesthesia", "", "麻醉室", "PATIENT"));
        result.put("endoscopy", new Department("endoscopy", "", "胃肠镜", "PATIENT"));
        result.put("inspection", new Department("inspection", "", "检查室", "PATIENT"));
        result.put("logistics", new Department("logistics", "", "后勤", "DAILY"));
        result.put("western-pharmacy", new Department("western-pharmacy", "", "西药房", "DAILY"));
        result.put("cashier", new Department("cashier", "", "收费室", "DAILY"));
        result.put("tcm-pharmacy", new Department("tcm-pharmacy", "", "中药房", "DAILY"));
        return Map.copyOf(result);
    }

    private record Department(String key, String id, String name, String entryMode) {}
    private record Item(String id, String name, String unit) {}
    private record Line(String itemId, String itemName, String unit, BigDecimal presetQuantity, BigDecimal actualQuantity, String source, String exceptionReason) {}
    private record Report(String id, String departmentId, String departmentKey, String departmentName, LocalDate businessDate,
                          String status, int revision, String note, String submittedBy, LocalDateTime submittedAt) {}
}
