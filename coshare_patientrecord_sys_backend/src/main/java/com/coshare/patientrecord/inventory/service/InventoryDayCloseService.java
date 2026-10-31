package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.InventoryAccessService;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Daily close for both entry modes: clinical departments close patient documents,
 * non-patient departments close their department daily report. Also serves the
 * combined usage summary and the day-level export.
 */
@Service
@Profile("mysql")
public class InventoryDayCloseService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryAccessService inventoryAccess;
    private final InventoryPatientConsumptionService patientConsumptionService;
    private final InventoryDepartmentDailyReportService dailyReportService;
    private final InventoryRepository repository;

    public InventoryDayCloseService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        InventoryAccessService inventoryAccess,
        InventoryPatientConsumptionService patientConsumptionService,
        InventoryDepartmentDailyReportService dailyReportService,
        InventoryRepository repository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.inventoryAccess = inventoryAccess;
        this.patientConsumptionService = patientConsumptionService;
        this.dailyReportService = dailyReportService;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ObjectNode usage(LocalDate businessDate, String departmentKey, String itemId, SessionUser user) {
        LocalDate date = businessDate == null ? LocalDate.now() : businessDate;
        String requested = departmentKey == null ? "" : departmentKey.trim();
        List<Department> scope = resolveScope(requested, date, user, true);
        String filterKey = requested.isBlank() ? "" : scope.get(0).key();

        ObjectNode result = objectMapper.createObjectNode();
        result.put("businessDate", date.toString());
        result.put("departmentKey", filterKey);
        result.put("mode", requested.isBlank() ? "ALL" : scope.get(0).entryMode());

        String item = itemId == null ? "" : itemId.trim();
        if (!item.isBlank()) {
            result.set("patients", itemPatients(date, filterKey, item));
            return result;
        }

        result.set("items", itemSummary(date, filterKey));
        result.set("totals", totals(date, scope, filterKey));
        result.set("closure", closureFor(scope, requested, date));
        result.set("missingPatients", missingPatientsFor(scope, date));
        result.set("missingReports", missingReportsFor(scope, date));
        return result;
    }

    private ArrayNode itemSummary(LocalDate date, String departmentKey) {
        return jdbcTemplate.query(
            """
            SELECT item_id, MAX(item_name) item_name, MAX(unit) unit, SUM(quantity) quantity,
                   COUNT(DISTINCT patient_id) patient_count,
                   SUM(CASE WHEN review_status = 'APPROVED' THEN quantity ELSE 0 END) approved_quantity,
                   SUM(CASE WHEN review_status IN ('PENDING', 'SUBMITTED') THEN quantity ELSE 0 END) pending_quantity
            FROM (
              SELECT l.item_id,
                     COALESCE(l.item_name_snapshot, i.name) item_name,
                     COALESCE(l.unit_snapshot, i.unit) unit,
                     l.actual_quantity quantity, l.review_status, si.patient_id
              FROM inventory_patient_consumption_lines l
              JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
              JOIN inventory_service_instances si ON si.id = d.service_instance_id
              LEFT JOIN inventory_items i ON i.id = l.item_id
              WHERE si.business_date = ? AND (? = '' OR si.department_key = ?)
                AND l.review_status <> 'REJECTED'
              UNION ALL
              SELECT rl.item_id,
                     COALESCE(rl.item_name_snapshot, i.name) item_name,
                     COALESCE(rl.unit_snapshot, i.unit) unit,
                     rl.actual_quantity quantity, rl.review_status, NULL patient_id
              FROM inventory_department_daily_report_lines rl
              JOIN inventory_department_daily_reports r ON r.id = rl.report_id
              LEFT JOIN inventory_items i ON i.id = rl.item_id
              WHERE r.business_date = ? AND (? = '' OR r.department_key = ?)
                AND rl.review_status <> 'REJECTED' AND rl.actual_quantity > 0
            ) usage_lines
            GROUP BY item_id
            ORDER BY quantity DESC, item_name
            """,
            resultSet -> {
                ArrayNode list = objectMapper.createArrayNode();
                while (resultSet.next()) {
                    ObjectNode row = list.addObject();
                    row.put("itemId", resultSet.getString("item_id"));
                    row.put("itemName", resultSet.getString("item_name"));
                    row.put("unit", resultSet.getString("unit"));
                    row.put("quantity", resultSet.getBigDecimal("quantity"));
                    row.put("patientCount", resultSet.getInt("patient_count"));
                    row.put("approvedQuantity", resultSet.getBigDecimal("approved_quantity"));
                    row.put("pendingQuantity", resultSet.getBigDecimal("pending_quantity"));
                }
                return list;
            },
            date, departmentKey, departmentKey, date, departmentKey, departmentKey
        );
    }

    private ArrayNode itemPatients(LocalDate date, String departmentKey, String itemId) {
        return jdbcTemplate.query(
            """
            SELECT patient_name, visit_no, department_name, service_name, quantity, review_status, attention_flag, source_type
            FROM (
              SELECT si.patient_name, si.visit_no, si.department_name, si.service_name,
                     l.actual_quantity quantity, l.review_status, si.attention_flag, 'PATIENT' source_type
              FROM inventory_patient_consumption_lines l
              JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
              JOIN inventory_service_instances si ON si.id = d.service_instance_id
              WHERE si.business_date = ? AND (? = '' OR si.department_key = ?)
                AND l.item_id = ? AND l.review_status <> 'REJECTED'
              UNION ALL
              SELECT r.department_name, '', r.department_name, '科室日报',
                     rl.actual_quantity, rl.review_status, 0, 'DAILY'
              FROM inventory_department_daily_report_lines rl
              JOIN inventory_department_daily_reports r ON r.id = rl.report_id
              WHERE r.business_date = ? AND (? = '' OR r.department_key = ?)
                AND rl.item_id = ? AND rl.review_status <> 'REJECTED' AND rl.actual_quantity > 0
            ) usage_rows
            ORDER BY department_name, patient_name
            """,
            resultSet -> {
                ArrayNode list = objectMapper.createArrayNode();
                while (resultSet.next()) {
                    ObjectNode row = list.addObject();
                    row.put("patientName", resultSet.getString("patient_name"));
                    row.put("visitNo", resultSet.getString("visit_no"));
                    row.put("departmentName", resultSet.getString("department_name"));
                    row.put("serviceName", resultSet.getString("service_name"));
                    row.put("quantity", resultSet.getBigDecimal("quantity"));
                    row.put("reviewStatus", resultSet.getString("review_status"));
                    row.put("attentionFlag", resultSet.getBoolean("attention_flag"));
                    row.put("sourceType", resultSet.getString("source_type"));
                }
                return list;
            },
            date, departmentKey, departmentKey, itemId, date, departmentKey, departmentKey, itemId
        );
    }

    private ObjectNode totals(LocalDate date, List<Department> scope, String departmentKey) {
        ObjectNode totals = objectMapper.createObjectNode();
        int patientTotal = 0, entered = 0, draft = 0, returned = 0, missing = 0, noConsumption = 0;
        int reportTotal = 0, reportSubmitted = 0, reportMissing = 0;
        for (Department department : scope) {
            if ("DAILY".equals(department.entryMode())) {
                ReportSummary report = reportSummary(department, date);
                if (report == null) {
                    reportMissing++;
                    continue;
                }
                reportTotal++;
                if (SetLike.CLOSED.contains(report.status())) reportSubmitted++;
                continue;
            }
            DepartmentStats stats = patientStats(department.key(), date);
            patientTotal += stats.total();
            entered += stats.entered();
            draft += stats.draft();
            returned += stats.returned();
            missing += stats.missing();
            noConsumption += stats.noConsumption();
        }
        totals.put("patientTotal", patientTotal);
        totals.put("entered", entered);
        totals.put("draft", draft);
        totals.put("returned", returned);
        totals.put("missingPatientCount", missing);
        totals.put("noConsumption", noConsumption);
        totals.put("reportTotal", reportTotal);
        totals.put("reportSubmitted", reportSubmitted);
        totals.put("reportMissing", reportMissing);
        totals.put("missingCount", missing + reportMissing);
        Integer distinctPatients = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(DISTINCT si.patient_id)
            FROM inventory_patient_consumption_lines l
            JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
            JOIN inventory_service_instances si ON si.id = d.service_instance_id
            WHERE si.business_date = ? AND (? = '' OR si.department_key = ?)
              AND l.review_status <> 'REJECTED'
            """,
            Integer.class,
            date, departmentKey, departmentKey
        );
        List<BigDecimal> quantities = jdbcTemplate.query(
            """
            SELECT SUM(quantity) quantity FROM (
              SELECT l.actual_quantity quantity
              FROM inventory_patient_consumption_lines l
              JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
              JOIN inventory_service_instances si ON si.id = d.service_instance_id
              WHERE si.business_date = ? AND (? = '' OR si.department_key = ?)
                AND l.review_status <> 'REJECTED'
              UNION ALL
              SELECT rl.actual_quantity
              FROM inventory_department_daily_report_lines rl
              JOIN inventory_department_daily_reports r ON r.id = rl.report_id
              WHERE r.business_date = ? AND (? = '' OR r.department_key = ?)
                AND rl.review_status <> 'REJECTED' AND rl.actual_quantity > 0
            ) usage_lines
            """,
            (resultSet, rowNum) -> resultSet.getBigDecimal("quantity"),
            date, departmentKey, departmentKey, date, departmentKey, departmentKey
        );
        Integer itemCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(DISTINCT item_id) FROM (
              SELECT l.item_id
              FROM inventory_patient_consumption_lines l
              JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
              JOIN inventory_service_instances si ON si.id = d.service_instance_id
              WHERE si.business_date = ? AND (? = '' OR si.department_key = ?)
                AND l.review_status <> 'REJECTED'
              UNION ALL
              SELECT rl.item_id
              FROM inventory_department_daily_report_lines rl
              JOIN inventory_department_daily_reports r ON r.id = rl.report_id
              WHERE r.business_date = ? AND (? = '' OR r.department_key = ?)
                AND rl.review_status <> 'REJECTED' AND rl.actual_quantity > 0
            ) usage_lines
            """,
            Integer.class,
            date, departmentKey, departmentKey, date, departmentKey, departmentKey
        );
        totals.put("patientCount", distinctPatients == null ? 0 : distinctPatients);
        totals.put("itemCount", itemCount == null ? 0 : itemCount);
        totals.put("totalQuantity", quantities.isEmpty() || quantities.get(0) == null ? BigDecimal.ZERO : quantities.get(0));
        return totals;
    }

    private ObjectNode closureFor(List<Department> scope, String requested, LocalDate date) {
        ObjectNode result = objectMapper.createObjectNode();
        if (!requested.isBlank()) {
            Department department = scope.get(0);
            ObjectNode row = closureRow(department.id(), date);
            if (row == null) {
                result.put("status", "OPEN");
            } else {
                result.setAll(row);
                if (date.isBefore(LocalDate.now()) && SetLike.CLOSED.contains(row.path("status").asText())) {
                    result.put("status", "LOCKED");
                }
            }
            result.put("locked", date.isBefore(LocalDate.now()) && SetLike.CLOSED.contains(result.path("status").asText()));
            result.put("closedDepartments", SetLike.CLOSED.contains(result.path("status").asText()) ? 1 : 0);
            result.put("totalDepartments", 1);
            return result;
        }
        int total = 0, closed = 0;
        String submittedBy = "";
        String submittedAt = "";
        for (Department department : scope) {
            if (!department.hasData()) continue;
            total++;
            ObjectNode row = closureRow(department.id(), date);
            if (row != null && SetLike.CLOSED.contains(row.path("status").asText())) {
                closed++;
                if (submittedBy.isBlank()) submittedBy = row.path("submittedBy").asText("");
                if (submittedAt.isBlank()) submittedAt = row.path("submittedAt").asText("");
            }
        }
        String status;
        if (total == 0 || closed == 0) status = "OPEN";
        else if (closed == total) status = date.isBefore(LocalDate.now()) ? "LOCKED" : "SUBMITTED";
        else status = "PARTIAL";
        result.put("status", status);
        result.put("submittedBy", submittedBy);
        result.put("submittedAt", submittedAt);
        result.put("locked", "LOCKED".equals(status));
        result.put("closedDepartments", closed);
        result.put("totalDepartments", total);
        return result;
    }

    private ObjectNode closureRow(String departmentId, LocalDate date) {
        return jdbcTemplate.query(
            "SELECT * FROM inventory_department_day_closures WHERE department_id = ? AND business_date = ?",
            resultSet -> {
                if (!resultSet.next()) return null;
                ObjectNode row = objectMapper.createObjectNode();
                row.put("status", resultSet.getString("status"));
                row.put("submittedBy", resultSet.getString("submitted_by"));
                Timestamp submittedAt = resultSet.getTimestamp("submitted_at");
                row.put("submittedAt", submittedAt == null ? "" : submittedAt.toLocalDateTime().toString());
                Timestamp lockedAt = resultSet.getTimestamp("locked_at");
                row.put("lockedAt", lockedAt == null ? "" : lockedAt.toLocalDateTime().toString());
                return row;
            },
            departmentId, date
        );
    }

    private ArrayNode missingPatientsFor(List<Department> scope, LocalDate date) {
        ArrayNode list = objectMapper.createArrayNode();
        for (Department department : scope) {
            if ("DAILY".equals(department.entryMode())) continue;
            list.addAll(missingPatients(department, date));
        }
        return list;
    }

    private ArrayNode missingPatients(Department department, LocalDate date) {
        return jdbcTemplate.query(
            """
            SELECT si.id service_instance_id, si.patient_name, si.visit_no, si.service_name,
                   d.id document_id, d.status document_status, COALESCE(d.no_consumption, 0) no_consumption,
                   (SELECT COUNT(*) FROM inventory_patient_consumption_lines l WHERE l.document_id = d.id) line_count
            FROM inventory_service_instances si
            LEFT JOIN inventory_patient_consumption_documents d ON d.service_instance_id = si.id
            WHERE si.department_key = ? AND si.business_date = ?
            ORDER BY si.service_started_at, si.patient_name
            """,
            resultSet -> {
                ArrayNode rows = objectMapper.createArrayNode();
                while (resultSet.next()) {
                    String documentId = resultSet.getString("document_id");
                    String status = resultSet.getString("document_status");
                    boolean noConsumption = resultSet.getBoolean("no_consumption");
                    int lineCount = resultSet.getInt("line_count");
                    boolean emptyDraft = "DRAFT".equals(status) && !noConsumption && lineCount == 0;
                    if (documentId != null && !emptyDraft) continue;
                    ObjectNode row = rows.addObject();
                    row.put("departmentKey", department.key());
                    row.put("departmentName", department.name());
                    row.put("serviceInstanceId", resultSet.getString("service_instance_id"));
                    row.put("patientName", resultSet.getString("patient_name"));
                    row.put("visitNo", resultSet.getString("visit_no"));
                    row.put("serviceName", resultSet.getString("service_name"));
                    row.put("reason", documentId == null ? "尚未录入耗材" : "草稿为空且未标记未使用");
                }
                return rows;
            },
            department.key(), date
        );
    }

    private ObjectNode fixedReportIssue(Department department, LocalDate date) {
        Integer fixedRules = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM inventory_quota_rules r
            JOIN inventory_quota_versions v ON v.id = r.version_id AND v.status = 'ACTIVE'
            WHERE r.department_key = ? AND r.enabled = 1 AND r.binding_type = 'FIXED_DAILY'
            """,
            Integer.class, department.key()
        );
        if (fixedRules == null || fixedRules == 0) return null;
        List<String[]> rows = jdbcTemplate.query(
            "SELECT id, status FROM inventory_department_daily_reports WHERE department_id = ? AND business_date = ?",
            (resultSet, rowNum) -> new String[] { resultSet.getString("id"), resultSet.getString("status") },
            department.id(), date
        );
        if (rows.isEmpty()) {
            return reportIssue(department, "", "MISSING", "固定耗材尚未确认");
        }
        String status = rows.get(0)[1];
        if (SetLike.CLOSED.contains(status)) return null;
        return reportIssue(department, rows.get(0)[0], status, "固定耗材未提交审核");
    }

    private ArrayNode missingReportsFor(List<Department> scope, LocalDate date) {
        ArrayNode list = objectMapper.createArrayNode();
        for (Department department : scope) {
            if ("PATIENT".equals(department.entryMode())) {
                ObjectNode fixed = fixedReportIssue(department, date);
                if (fixed != null) list.add(fixed);
                continue;
            }
            ReportSummary report = reportSummary(department, date);
            if (report != null && !"DRAFT".equals(report.status())) continue;
            ObjectNode row = list.addObject();
            row.put("departmentKey", department.key());
            row.put("departmentName", department.name());
            row.put("reportId", report == null ? "" : report.id());
            row.put("status", report == null ? "MISSING" : report.status());
            row.put("reason", report == null ? "尚未创建科室日报" : "科室日报仍为草稿");
        }
        return list;
    }

    private DepartmentStats patientStats(String departmentKey, LocalDate date) {
        return jdbcTemplate.query(
            """
            SELECT COUNT(*) total,
                   SUM(CASE WHEN d.id IS NOT NULL AND d.status IN ('SUBMITTED', 'PARTIALLY_APPROVED', 'APPROVED') THEN 1 ELSE 0 END) entered,
                   SUM(CASE WHEN d.status = 'DRAFT' THEN 1 ELSE 0 END) draft,
                   SUM(CASE WHEN d.status = 'RETURNED' THEN 1 ELSE 0 END) returned,
                   SUM(CASE WHEN d.id IS NULL OR (d.status = 'DRAFT' AND COALESCE(d.no_consumption, 0) = 0
                        AND (SELECT COUNT(*) FROM inventory_patient_consumption_lines l WHERE l.document_id = d.id) = 0)
                       THEN 1 ELSE 0 END) missing,
                   SUM(CASE WHEN COALESCE(d.no_consumption, 0) = 1 THEN 1 ELSE 0 END) no_consumption
            FROM inventory_service_instances si
            LEFT JOIN inventory_patient_consumption_documents d ON d.service_instance_id = si.id
            WHERE si.department_key = ? AND si.business_date = ?
            """,
            resultSet -> {
                if (!resultSet.next()) return new DepartmentStats(0, 0, 0, 0, 0, 0);
                return new DepartmentStats(
                    resultSet.getInt("total"), resultSet.getInt("entered"), resultSet.getInt("draft"),
                    resultSet.getInt("returned"), resultSet.getInt("missing"), resultSet.getInt("no_consumption")
                );
            },
            departmentKey, date
        );
    }

    private ReportSummary reportSummary(Department department, LocalDate date) {
        return jdbcTemplate.query(
            """
            SELECT r.id, r.status, r.department_name,
                   (SELECT COUNT(*) FROM inventory_department_daily_report_lines l
                    WHERE l.report_id = r.id AND l.actual_quantity > 0) positive_lines
            FROM inventory_department_daily_reports r
            WHERE r.department_key = ? AND r.business_date = ?
            """,
            resultSet -> {
                if (!resultSet.next()) return null;
                return new ReportSummary(
                    resultSet.getString("id"), resultSet.getString("status"),
                    resultSet.getString("department_name"), resultSet.getInt("positive_lines")
                );
            },
            department.key(), date
        );
    }

    @Transactional
    public ObjectNode close(JsonNode payload, SessionUser user) {
        LocalDate date = parseDate(payload == null ? "" : payload.path("businessDate").asText(""));
        String requested = payload == null ? "" : payload.path("departmentKey").asText("").trim();
        List<Department> scope = resolveScope(requested, date, user, true);
        ArrayNode missingPatients = objectMapper.createArrayNode();
        ArrayNode failedPatients = objectMapper.createArrayNode();
        ArrayNode missingReports = objectMapper.createArrayNode();
        ArrayNode closedDepartments = objectMapper.createArrayNode();
        int submittedDocuments = 0;
        int submittedReports = 0;
        int closed = 0;

        for (Department department : scope) {
            if (requested.isBlank() && !department.hasData()) continue;
            if ("DAILY".equals(department.entryMode())) {
                ReportSummary report = reportSummary(department, date);
                if (report == null) {
                    missingReports.add(reportIssue(department, "", "MISSING", "尚未创建科室日报"));
                    continue;
                }
                if ("DRAFT".equals(report.status())) {
                    if (report.positiveLines() == 0) {
                        missingReports.add(reportIssue(department, report.id(), report.status(), "日报没有实际使用量"));
                        continue;
                    }
                    dailyReportService.submit(report.id(), user);
                    submittedReports++;
                    closed++;
                    closedDepartments.add(department.key());
                } else if (SetLike.CLOSED.contains(report.status())) {
                    closed++;
                    closedDepartments.add(department.key());
                } else {
                    missingReports.add(reportIssue(department, report.id(), report.status(), "日报状态为 " + report.status()));
                }
                continue;
            }
            ObjectNode fixedIssue = fixedReportIssue(department, date);
            if (fixedIssue != null) {
                missingReports.add(fixedIssue);
                continue;
            }
            ObjectNode batch = patientConsumptionService.submitBatch(department.key(), date, user);
            submittedDocuments += batch.path("submitted").asInt(0);
            JsonNode missing = batch.path("missing");
            JsonNode failed = batch.path("failed");
            for (JsonNode row : missing) missingPatients.add(withDepartment(row, department));
            for (JsonNode row : failed) failedPatients.add(withDepartment(row, department));
            if (missing.isEmpty() && failed.isEmpty()) {
                upsertClosure(department, date, "PATIENT", user);
                closed++;
                closedDepartments.add(department.key());
            }
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("businessDate", date.toString());
        result.put("departmentKey", requested);
        result.put("totalDepartments", scope.size());
        result.put("closedDepartments", closed);
        result.put("submittedDocuments", submittedDocuments);
        result.put("submittedReports", submittedReports);
        boolean blocked = !missingPatients.isEmpty() || !failedPatients.isEmpty() || !missingReports.isEmpty();
        result.put("blocked", blocked);
        result.set("missingPatients", missingPatients);
        result.set("failedPatients", failedPatients);
        result.set("missingReports", missingReports);
        result.set("closed", closedDepartments);
        result.put("message", blocked ? "仍有未完成项，暂时无法完成日结" : "日结已完成");
        repository.log(user.name(), "提交日结", "inventory_day_closure", requested.isBlank() ? "all" : requested + " " + date,
            "完成 " + closed + " 个科室，患者单据 " + submittedDocuments + "，日报 " + submittedReports
                + "，缺失 " + (missingPatients.size() + missingReports.size()));
        return result;
    }

    @Transactional(readOnly = true)
    public byte[] export(LocalDate businessDate, String departmentKey, String format, SessionUser user) {
        LocalDate date = businessDate == null ? LocalDate.now() : businessDate;
        String requested = departmentKey == null ? "" : departmentKey.trim();
        List<Department> scope = resolveScope(requested, date, user, true);
        String filterKey = requested.isBlank() ? "" : scope.get(0).key();
        ArrayNode rows = exportRows(date, filterKey);
        if ("xlsx".equalsIgnoreCase(format)) return xlsx(date, rows);
        return csv(date, rows);
    }

    private ArrayNode exportRows(LocalDate date, String departmentKey) {
        return jdbcTemplate.query(
            """
            SELECT department_name, patient_name, visit_no, service_name, item_name, unit, quantity,
                   source_label, review_status
            FROM (
              SELECT si.department_name, si.patient_name, si.visit_no, si.service_name,
                     COALESCE(l.item_name_snapshot, i.name) item_name,
                     COALESCE(l.unit_snapshot, i.unit) unit,
                     l.actual_quantity quantity, '患者' source_label, l.review_status
              FROM inventory_patient_consumption_lines l
              JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
              JOIN inventory_service_instances si ON si.id = d.service_instance_id
              LEFT JOIN inventory_items i ON i.id = l.item_id
              WHERE si.business_date = ? AND (? = '' OR si.department_key = ?)
              UNION ALL
              SELECT r.department_name, r.department_name, '', '科室日报',
                     COALESCE(rl.item_name_snapshot, i.name),
                     COALESCE(rl.unit_snapshot, i.unit),
                     rl.actual_quantity, rl.source, rl.review_status
              FROM inventory_department_daily_report_lines rl
              JOIN inventory_department_daily_reports r ON r.id = rl.report_id
              LEFT JOIN inventory_items i ON i.id = rl.item_id
              WHERE r.business_date = ? AND (? = '' OR r.department_key = ?)
                AND rl.actual_quantity > 0
            ) export_lines
            ORDER BY department_name, patient_name, item_name
            """,
            resultSet -> {
                ArrayNode list = objectMapper.createArrayNode();
                while (resultSet.next()) {
                    ObjectNode row = list.addObject();
                    row.put("departmentName", resultSet.getString("department_name"));
                    row.put("patientName", resultSet.getString("patient_name"));
                    row.put("visitNo", resultSet.getString("visit_no"));
                    row.put("serviceName", resultSet.getString("service_name"));
                    row.put("itemName", resultSet.getString("item_name"));
                    row.put("unit", resultSet.getString("unit"));
                    row.put("quantity", resultSet.getBigDecimal("quantity"));
                    row.put("sourceLabel", resultSet.getString("source_label"));
                    row.put("reviewStatus", resultSet.getString("review_status"));
                }
                return list;
            },
            date, departmentKey, departmentKey, date, departmentKey, departmentKey
        );
    }

    private byte[] csv(LocalDate date, ArrayNode rows) {
        StringBuilder result = new StringBuilder("\uFEFF");
        result.append("业务日期,科室,患者/日报,就诊号,服务,耗材,单位,数量,来源,审核状态\n");
        for (JsonNode row : rows) {
            result.append(date).append(',')
                .append(csvCell(row.path("departmentName").asText())).append(',')
                .append(csvCell(row.path("patientName").asText())).append(',')
                .append(csvCell(row.path("visitNo").asText())).append(',')
                .append(csvCell(row.path("serviceName").asText())).append(',')
                .append(csvCell(row.path("itemName").asText())).append(',')
                .append(csvCell(row.path("unit").asText())).append(',')
                .append(row.path("quantity").asText("")).append(',')
                .append(csvCell(row.path("sourceLabel").asText())).append(',')
                .append(csvCell(row.path("reviewStatus").asText())).append('\n');
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] xlsx(LocalDate date, ArrayNode rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("耗材日结");
            Row header = sheet.createRow(0);
            String[] columns = { "业务日期", "科室", "患者/日报", "就诊号", "服务", "耗材", "单位", "数量", "来源", "审核状态" };
            for (int index = 0; index < columns.length; index++) header.createCell(index).setCellValue(columns[index]);
            int rowIndex = 1;
            for (JsonNode row : rows) {
                Row sheetRow = sheet.createRow(rowIndex++);
                sheetRow.createCell(0).setCellValue(date.toString());
                sheetRow.createCell(1).setCellValue(row.path("departmentName").asText());
                sheetRow.createCell(2).setCellValue(row.path("patientName").asText());
                sheetRow.createCell(3).setCellValue(row.path("visitNo").asText());
                sheetRow.createCell(4).setCellValue(row.path("serviceName").asText());
                sheetRow.createCell(5).setCellValue(row.path("itemName").asText());
                sheetRow.createCell(6).setCellValue(row.path("unit").asText());
                sheetRow.createCell(7).setCellValue(row.path("quantity").asText(""));
                sheetRow.createCell(8).setCellValue(row.path("sourceLabel").asText());
                sheetRow.createCell(9).setCellValue(row.path("reviewStatus").asText());
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("耗材日结导出失败", error);
        }
    }

    private List<Department> resolveScope(String requested, LocalDate date, SessionUser user, boolean dataOnly) {
        if (!requested.isBlank()) {
            Department department = requireDepartment(requested, user);
            boolean hasData = hasData(department, date);
            return List.of(department.withData(hasData));
        }
        List<Department> departments = new ArrayList<>();
        if (!inventoryAccess.canViewAllDepartments(user)) {
            Department own = ownDepartment(user);
            if (own == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号未绑定科室");
            }
            departments.add(own.withData(hasData(own, date)));
            return departments;
        }
        jdbcTemplate.query(
            "SELECT id, department_key, name, entry_mode FROM inventory_departments WHERE status = 'ACTIVE' ORDER BY display_order",
            resultSet -> {
                Department department = departmentRow(resultSet);
                if (!dataOnly || hasData(department, date)) departments.add(department.withData(true));
            }
        );
        return departments;
    }

    private boolean hasData(Department department, LocalDate date) {
        if ("DAILY".equals(department.entryMode())) {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_department_daily_reports WHERE department_id = ? AND business_date = ?",
                Integer.class,
                department.id(), date
            );
            return count != null && count > 0;
        }
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM inventory_service_instances WHERE department_key = ? AND business_date = ?",
            Integer.class,
            department.key(), date
        );
        return count != null && count > 0;
    }

    private Department requireDepartment(String key, SessionUser user) {
        List<Department> rows = jdbcTemplate.query(
            "SELECT id, department_key, name, entry_mode FROM inventory_departments WHERE department_key = ? LIMIT 1",
            (resultSet, rowNum) -> departmentRow(resultSet),
            key
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知科室：" + key);
        Department department = rows.get(0);
        if (!inventoryAccess.canViewAllDepartments(user) && !sameDepartment(user.department(), department.name())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问其他科室的日结");
        }
        return department;
    }

    private Department ownDepartment(SessionUser user) {
        String name = user.department() == null ? "" : user.department().trim();
        if (name.isBlank()) return null;
        List<Department> rows = jdbcTemplate.query(
            """
            SELECT id, department_key, name, entry_mode FROM inventory_departments
            WHERE status = 'ACTIVE' AND (name = ? OR (? = '后勤保洁' AND name = '后勤') OR (? = '后勤' AND name = '后勤保洁'))
            LIMIT 1
            """,
            (resultSet, rowNum) -> departmentRow(resultSet),
            name, name, name
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Department departmentRow(ResultSet resultSet) throws SQLException {
        return new Department(
            resultSet.getString("id"), resultSet.getString("department_key"),
            resultSet.getString("name"), resultSet.getString("entry_mode"), false
        );
    }

    private void upsertClosure(Department department, LocalDate date, String entryMode, SessionUser user) {
        jdbcTemplate.update(
            """
            INSERT INTO inventory_department_day_closures
              (id, department_id, department_key, department_name, business_date, entry_mode, status, submitted_by, submitted_at)
            VALUES (?, ?, ?, ?, ?, ?, 'SUBMITTED', ?, CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE status = VALUES(status), submitted_by = VALUES(submitted_by),
              submitted_at = VALUES(submitted_at)
            """,
            "day-closure-" + UUID.randomUUID(), department.id(), department.key(), department.name(), date, entryMode, user.name()
        );
    }

    private ObjectNode reportIssue(Department department, String reportId, String status, String reason) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("departmentKey", department.key());
        row.put("departmentName", department.name());
        row.put("reportId", reportId);
        row.put("status", status);
        row.put("reason", reason);
        return row;
    }

    private ObjectNode withDepartment(JsonNode source, Department department) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("departmentKey", department.key());
        row.put("departmentName", department.name());
        row.put("patientName", source.path("patientName").asText(""));
        row.put("reason", source.path("reason").asText(""));
        row.put("serviceInstanceId", source.path("serviceInstanceId").asText(""));
        row.put("visitNo", source.path("visitNo").asText(""));
        return row;
    }

    private static String csvCell(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.isBlank() ? LocalDate.now().toString() : value);
        } catch (Exception ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "业务日期格式不正确");
        }
    }

    private static boolean sameDepartment(String userDepartment, String canonicalName) {
        if (userDepartment == null) return false;
        String value = userDepartment.trim();
        return value.equals(canonicalName)
            || ("后勤保洁".equals(value) && "后勤".equals(canonicalName))
            || ("后勤".equals(value) && "后勤保洁".equals(canonicalName));
    }

    private record Department(String id, String key, String name, String entryMode, boolean hasData) {
        Department withData(boolean value) {
            return new Department(id, key, name, entryMode, value);
        }
    }

    private record DepartmentStats(int total, int entered, int draft, int returned, int missing, int noConsumption) {}

    private record ReportSummary(String id, String status, String departmentName, int positiveLines) {}

    private static final class SetLike {
        static final java.util.Set<String> CLOSED = java.util.Set.of("SUBMITTED", "PARTIALLY_APPROVED", "APPROVED", "LOCKED");

        private SetLike() {}
    }
}
