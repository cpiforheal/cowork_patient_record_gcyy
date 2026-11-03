package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.InventoryAccessService;
import com.coshare.patientrecord.file.model.ClinicStoredFile;
import com.coshare.patientrecord.file.service.ClinicFileService;
import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository;
import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository.Balance;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 定额消耗模式：院方核定“科室 + 耗材”每人次定额，科室只报接诊人数。
 * 定额部分提交日报时直接扣库；超量只能作为事实上报，最终补扣量由管理员审核确定。
 */
@Service
@Profile("mysql")
public class InventoryQuotaConsumptionService {

    private static final String MODE_LEGACY = "LEGACY";
    private static final String MODE_QUOTA = "QUOTA";
    private static final Set<String> MODES = Set.of(MODE_LEGACY, MODE_QUOTA);
    private static final Set<String> PER_PERSON = Set.of("PER_PERSON");
    private static final Set<String> DAILY_FIXED = Set.of("FIXED_DAILY", "ON_DEMAND", "EQUIPMENT");
    private static final BigDecimal THREE = BigDecimal.valueOf(3);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryLedgerRepository ledger;
    private final InventoryAccessService inventoryAccess;
    private final InventoryRepository repository;
    private final ClinicFileService fileService;

    public InventoryQuotaConsumptionService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        InventoryLedgerRepository ledger,
        InventoryAccessService inventoryAccess,
        InventoryRepository repository,
        ClinicFileService fileService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.ledger = ledger;
        this.inventoryAccess = inventoryAccess;
        this.repository = repository;
        this.fileService = fileService;
    }

    @Transactional(readOnly = true)
    public ObjectNode modeState() {
        LocalDate today = LocalDate.now();
        String mode = setting("inventory_quota_mode", MODE_LEGACY);
        LocalDate effective = settingDate("inventory_quota_mode_effective_date");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("mode", mode);
        result.put("effectiveDate", effective == null ? "" : effective.toString());
        result.put("active", isQuotaMode(today));
        result.put("confirmedBy", setting("inventory_quota_mode_confirmed_by", ""));
        result.put("changedAt", setting("inventory_quota_mode_changed_at", ""));
        ObjectNode completeness = completeness(null, null);
        result.set("completeness", completeness);
        result.put("readyToSwitch", completeness.path("total").asInt(0) == 0);
        return result;
    }

    @Transactional
    public ObjectNode switchMode(JsonNode payload, SessionUser user) {
        String mode = text(payload, "mode").toUpperCase(Locale.ROOT);
        if (!MODES.contains(mode)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模式只能为 LEGACY 或 QUOTA");
        LocalDate effective = parseDate(text(payload, "effectiveDate"));
        if (effective == null) effective = LocalDate.now().plusDays(1);
        if (MODE_QUOTA.equals(mode)) {
            if (effective.isBefore(LocalDate.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "生效日不能早于今天");
            ObjectNode completeness = completeness(effective, null);
            if (completeness.path("total").asInt(0) > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "仍有 " + completeness.path("total").asInt() + " 项耗材未核定定额，请先在待核定清单补齐");
            }
        }
        upsertSetting("inventory_quota_mode", mode, user.name());
        upsertSetting("inventory_quota_mode_effective_date", effective.toString(), user.name());
        upsertSetting("inventory_quota_mode_confirmed_by", user.name(), user.name());
        upsertSetting("inventory_quota_mode_changed_at", LocalDateTime.now().toString(), user.name());
        if (MODE_QUOTA.equals(mode)) {
            LocalDate cutoff = effective.isBefore(LocalDate.now()) ? effective : LocalDate.now();
            jdbcTemplate.update("UPDATE inventory_department_daily_drafts SET frozen = 1 WHERE business_date < ?", cutoff);
        }
        repository.log(user.name(), MODE_QUOTA.equals(mode) ? "启用定额消耗模式" : "回退实耗模式",
            "inventory_quota_mode", effective.toString(), "定额模式变更；历史数据不重算");
        return modeState();
    }

    public boolean isQuotaMode(LocalDate date) {
        String mode = setting("inventory_quota_mode", MODE_LEGACY);
        LocalDate effective = settingDate("inventory_quota_mode_effective_date");
        return MODE_QUOTA.equals(mode) && effective != null && (date == null || !date.isBefore(effective));
    }

    @Transactional(readOnly = true)
    public ObjectNode completeness(LocalDate date, String departmentKey) {
        LocalDate day = date == null ? LocalDate.now() : date;
        LocalDate since = day.minusDays(90);
        String activeVersion = activeVersionId(day);
        List<Department> departments = departments(departmentKey);
        ArrayNode items = objectMapper.createArrayNode();
        for (Department department : departments) {
            if (activeVersion.isBlank()) {
                items.add(missingDepartment(department, "未配置生效定额版本"));
                continue;
            }
            List<MissingItem> missing = jdbcTemplate.query(
                """
                SELECT x.item_id, i.name item_name, i.unit item_unit
                FROM (
                  SELECT l.item_id
                  FROM inventory_patient_consumption_lines l
                  JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
                  WHERE d.department_key = ? AND d.business_date >= ?
                  UNION
                  SELECT l.item_id
                  FROM inventory_department_daily_report_lines l
                  JOIN inventory_department_daily_reports r ON r.id = l.report_id
                  WHERE r.department_key = ? AND r.business_date >= ?
                  UNION
                  SELECT de.item_id
                  FROM inventory_consumption_details de
                  JOIN inventory_consumption_events e ON e.id = de.event_id
                  WHERE e.department_id = ? AND e.visit_date >= ? AND e.status = 'succeeded'
                  UNION
                  SELECT b.item_id FROM inventory_batch_balances b
                  WHERE b.location_id = ? AND b.quantity > 0
                ) x
                JOIN inventory_items i ON i.id = x.item_id AND i.enabled = 1
                LEFT JOIN inventory_quota_rules r
                  ON r.version_id = ? AND r.department_key = ? AND r.item_id = x.item_id AND r.enabled = 1
                WHERE r.id IS NULL OR r.standard_quantity IS NULL OR r.standard_quantity <= 0
                ORDER BY i.name
                """,
                (rs, rowNum) -> new MissingItem(rs.getString("item_id"), rs.getString("item_name"), rs.getString("item_unit")),
                department.key(), since, department.key(), since, department.id(), since.toString(),
                departmentLocationReadOnly(department.id()), activeVersion, department.key()
            );
            for (MissingItem item : missing) {
                ObjectNode row = items.addObject();
                row.put("departmentKey", department.key());
                row.put("departmentName", department.name());
                row.put("itemId", item.itemId());
                row.put("itemName", item.itemName());
                row.put("unit", item.unit());
                row.put("reason", "近90天使用或在库，但未核定每人次定额");
            }
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("date", day.toString());
        result.put("total", items.size());
        result.set("items", items);
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode tolerances(String departmentKey) {
        String versionId = activeVersionId(LocalDate.now());
        ArrayNode rows = objectMapper.createArrayNode();
        if (!versionId.isBlank()) {
            StringBuilder sql = new StringBuilder("""
                SELECT r.id, r.department_key, r.department_name, r.item_id, r.material_name, r.unit,
                       r.standard_quantity, r.binding_type, r.tolerance_mode, r.tolerance_value
                FROM inventory_quota_rules r
                WHERE r.version_id = ? AND r.enabled = 1
                """);
            List<Object> args = new ArrayList<>();
            args.add(versionId);
            if (departmentKey != null && !departmentKey.isBlank()) {
                sql.append(" AND r.department_key = ?");
                args.add(departmentKey);
            }
            sql.append(" ORDER BY r.department_name, r.source_row, r.id");
            jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
                ObjectNode row = rows.addObject();
                row.put("ruleId", rs.getString("id"));
                row.put("departmentKey", rs.getString("department_key"));
                row.put("departmentName", safe(rs.getString("department_name")));
                row.put("itemId", safe(rs.getString("item_id")));
                row.put("itemName", safe(rs.getString("material_name")));
                row.put("unit", safe(rs.getString("unit")));
                row.put("standardQuantity", rs.getBigDecimal("standard_quantity"));
                row.put("bindingType", safe(rs.getString("binding_type")));
                row.put("toleranceMode", safe(rs.getString("tolerance_mode")));
                row.put("toleranceValue", rs.getBigDecimal("tolerance_value"));
                return row;
            }, args.toArray());
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("list", rows);
        return result;
    }

    @Transactional
    public ObjectNode updateTolerance(JsonNode payload, SessionUser user) {
        String ruleId = text(payload, "ruleId");
        if (ruleId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少定额规则");
        String mode = text(payload, "toleranceMode").toUpperCase(Locale.ROOT);
        if (!Set.of("NONE", "PERCENT", "ABSOLUTE").contains(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "免审方式只能为 NONE/PERCENT/ABSOLUTE");
        }
        BigDecimal value = decimal(payload, "toleranceValue");
        if (value.signum() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "免审值不能为负数");
        if ("NONE".equals(mode)) value = BigDecimal.ZERO;
        int updated = jdbcTemplate.update(
            "UPDATE inventory_quota_rules SET tolerance_mode = ?, tolerance_value = ? WHERE id = ?",
            mode, value, ruleId
        );
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "定额规则不存在");
        repository.log(user.name(), "设置免审上浮", "inventory_quota_rules", ruleId,
            mode + " " + plain(value));
        return tolerances("");
    }

    @Transactional(readOnly = true)
    public ObjectNode read(String departmentKey, LocalDate businessDate, SessionUser user) {
        Department department = requireDepartment(departmentKey, user);
        LocalDate date = businessDate == null ? LocalDate.now() : businessDate;
        if (!isQuotaMode(date)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前业务日未启用定额模式");
        }
        ReportRow report = loadReport(department.id(), date);
        Headcount headcount = report == null ? autoHeadcount(department, date) : headcount(report);
        ArrayNode lines = report == null ? computeLines(department, date, headcount) : loadLines(report.id());
        ObjectNode result = objectMapper.createObjectNode();
        result.put("departmentKey", department.key());
        result.put("departmentId", department.id());
        result.put("departmentName", department.name());
        result.put("businessDate", date.toString());
        result.put("entryMode", department.entryMode());
        result.put("reportMode", "QUOTA");
        result.put("id", report == null ? "" : report.id());
        result.put("status", report == null ? "DRAFT" : report.status());
        result.put("revision", report == null ? 0 : report.revision());
        result.put("note", report == null ? "" : report.note());
        result.put("zeroUsage", false);
        result.put("locked", report != null && locked(report, date));
        result.set("headcount", headcountJson(headcount));
        result.set("lines", lines);
        result.set("closure", closureJson(department.id(), date));
        if (report != null) {
            result.put("quotaComputedTotal", report.quotaComputedTotal());
            result.put("quotaDeductedTotal", report.quotaDeductedTotal());
            result.put("exceptionTotal", report.exceptionTotal());
            result.put("pendingExceptions", countExceptions(report.id(), "PENDING"));
        } else {
            result.put("quotaComputedTotal", sum(lines, "quotaQuantity"));
            result.put("quotaDeductedTotal", 0);
            result.put("exceptionTotal", 0);
            result.put("pendingExceptions", 0);
        }
        return result;
    }

    @Transactional
    public ObjectNode save(JsonNode payload, SessionUser user) {
        Department department = requireDepartment(text(payload, "departmentKey"), user);
        LocalDate date = parseDate(text(payload, "businessDate"));
        if (date == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "业务日不能为空");
        if (!isQuotaMode(date)) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前业务日未启用定额模式");
        ReportRow current = loadReport(department.id(), date);
        if (current != null && locked(current, date)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该业务日已锁定，不能直接修改");
        }
        if (current != null && !"DRAFT".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "日报已提交，人数或备注变更请使用人数修正入口");
        }
        Headcount headcount = resolveHeadcount(department, date, payload);
        if ("DAILY".equals(department.entryMode()) && headcount.source().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写本科室今日服务人次");
        }
        ObjectNode result = persistReport(department, date, current, headcount, text(payload, "note"), user);
        repository.log(user.name(), "保存定额日报", "department_daily_report", department.name() + " " + date,
            "人数 " + headcount.total() + "；应耗 " + result.path("quotaComputedTotal").asText());
        return result;
    }

    @Transactional
    public ObjectNode submit(String reportId, SessionUser user) {
        ReportRow report = loadReportById(reportId);
        if (report == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "科室日报不存在");
        Department department = requireDepartment(report.departmentKey(), user);
        if (locked(report, report.businessDate())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该业务日已锁定，不能重复提交");
        }
        if (!isQuotaMode(report.businessDate())) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前业务日未启用定额模式");
        ObjectNode missing = completeness(report.businessDate(), department.key());
        if (missing.path("total").asInt(0) > 0) {
            String names = String.join("、", firstNames(missing.path("items"), 5));
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仍有未核定定额的耗材：" + names);
        }
        List<LineRow> lines = loadLineRows(report.id());
        BigDecimal computed = BigDecimal.ZERO;
        BigDecimal deducted = BigDecimal.ZERO;
        for (LineRow line : lines) {
            BigDecimal quota = scale(line.quotaQuantity());
            computed = computed.add(quota);
            String eventId = null;
            BigDecimal lineDeducted = BigDecimal.ZERO;
            if (quota.signum() > 0) {
                eventId = deduct(report, line, quota, "QUOTA_DAILY_LINE", "定额日报扣减", null, report.revision());
                if (eventId != null) {
                    lineDeducted = quota;
                    deducted = deducted.add(quota);
                }
            }
            jdbcTemplate.update(
                """
                UPDATE inventory_department_daily_report_lines
                SET actual_quantity = ?, quota_deducted_quantity = ?, quota_event_id = ?,
                    extra_quantity = 0, exception_status = 'NONE', exception_approved_quantity = 0
                WHERE id = ?
                """,
                quota, lineDeducted, eventId, line.id()
            );
        }
        jdbcTemplate.update(
            """
            UPDATE inventory_department_daily_reports
            SET status = 'APPROVED', quota_computed_total = ?, quota_deducted_total = ?, exception_total = 0,
                submitted_by = ?, submitted_at = CURRENT_TIMESTAMP(3)
            WHERE id = ?
            """,
            computed, deducted, user.name(), report.id()
        );
        upsertClosure(department, report.businessDate(), "APPROVED", user);
        repository.log(user.name(), "提交定额日报", "department_daily_report", department.name() + " " + report.businessDate(),
            "应耗 " + plain(computed) + "，已扣 " + plain(deducted));
        return read(department.key(), report.businessDate(), user);
    }

    @Transactional
    public ObjectNode correctHeadcount(String reportId, JsonNode payload, SessionUser user) {
        ReportRow report = loadReportById(reportId);
        if (report == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "科室日报不存在");
        Department department = requireDepartment(report.departmentKey(), user);
        if (locked(report, report.businessDate()) && !"admin".equals(canonicalRole(user))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "日结后仅管理员可解锁修正人数");
        }
        String reason = text(payload, "reason");
        if (reason.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "修正人数必须填写原因");
        Headcount before = headcount(report);
        Headcount after = resolveHeadcount(department, report.businessDate(), payload);
        List<LineRow> oldLines = loadLineRows(report.id());
        Map<String, LineRow> oldByItem = new LinkedHashMap<>();
        for (LineRow line : oldLines) oldByItem.put(line.itemId(), line);
        ArrayNode nextLines = computeLines(department, report.businessDate(), after);
        BigDecimal computed = BigDecimal.ZERO;
        BigDecimal deducted = BigDecimal.ZERO;
        for (JsonNode node : nextLines) {
            String itemId = text(node, "itemId");
            LineRow old = oldByItem.get(itemId);
            BigDecimal quota = decimal(node, "quotaQuantity");
            computed = computed.add(quota);
            BigDecimal already = old == null ? BigDecimal.ZERO : scale(old.quotaDeductedQuantity());
            BigDecimal approvedExtra = old == null ? BigDecimal.ZERO : scale(old.exceptionApprovedQuantity());
            BigDecimal delta = quota.subtract(old == null ? BigDecimal.ZERO : scale(old.quotaQuantity()));
            BigDecimal lineDeducted = already;
            String eventId = old == null ? null : old.quotaEventId();
            if (delta.signum() > 0) {
                String extraEvent = old == null ? null : deduct(report, old, delta, "QUOTA_HEADCOUNT_DELTA", "人数修正补扣", null, report.revision() + 1L);
                if (extraEvent != null) lineDeducted = already.add(delta);
            } else if (delta.signum() < 0 && old != null && old.quotaEventId() != null) {
                reverseQuota(report, old, delta.abs(), user, "人数修正冲回", report.revision() + 1L);
                lineDeducted = already.add(delta);
                if (lineDeducted.signum() < 0) lineDeducted = BigDecimal.ZERO;
            }
            deducted = deducted.add(lineDeducted);
            if (old == null) {
                insertLine(report.id(), nextLines.size(), node, quota, lineDeducted, eventId, approvedExtra);
            } else {
                jdbcTemplate.update(
                    """
                    UPDATE inventory_department_daily_report_lines
                    SET preset_quantity = ?, actual_quantity = ?, quota_quantity = ?, quota_deducted_quantity = ?,
                        extra_quantity = ?, tolerance_mode = ?, tolerance_value = ?, tolerance_quantity = ?,
                        exception_approved_quantity = ?, quota_event_id = ?, headcount_basis = ?
                    WHERE id = ?
                    """,
                    quota, quota.add(approvedExtra), quota, lineDeducted, decimal(node, "extraQuantity"),
                    text(node, "toleranceMode"), decimal(node, "toleranceValue"), decimal(node, "toleranceQuantity"),
                    approvedExtra, firstNonBlank(eventId, old.quotaEventId()), text(node, "headcountBasis"), old.id()
                );
            }
        }
        String beforeJson = json(headcountJson(before));
        String afterJson = json(headcountJson(after));
        jdbcTemplate.update(
            """
            INSERT INTO inventory_quota_headcount_corrections
              (id, report_id, department_id, department_key, business_date, before_json, after_json, reason, corrected_by)
            VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?)
            """,
            "quota-headcount-" + UUID.randomUUID(), report.id(), department.id(), department.key(),
            report.businessDate(), beforeJson, afterJson, reason, user.name()
        );
        jdbcTemplate.update(
            """
            UPDATE inventory_department_daily_reports
            SET headcount_outpatient = ?, headcount_inpatient = ?, headcount_manual = ?,
                headcount_source = ?, headcount_reason = ?, quota_computed_total = ?, quota_deducted_total = ?,
                revision = revision + 1
            WHERE id = ?
            """,
            after.outpatient(), after.inpatient(), after.manual(), after.source(), reason, computed, deducted, report.id()
        );
        repository.log(user.name(), "修正定额日报人数", "department_daily_report", department.name() + " " + report.businessDate(),
            "原因：" + reason + "；人数 " + before.total() + " → " + after.total());
        return read(department.key(), report.businessDate(), user);
    }

    @Transactional
    public ObjectNode reportException(JsonNode payload, SessionUser user) {
        Department department = requireDepartment(text(payload, "departmentKey"), user);
        LocalDate date = parseDate(text(payload, "businessDate"));
        if (date == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "业务日不能为空");
        ReportRow report = loadReport(department.id(), date);
        if (report == null || "DRAFT".equals(report.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先保存并提交当日定额日报，再上报例外");
        }
        if (locked(report, date)) throw new ResponseStatusException(HttpStatus.CONFLICT, "该业务日已锁定，不能追加例外");
        String itemId = text(payload, "itemId");
        LineRow line = loadLineRows(report.id()).stream().filter(row -> row.itemId().equals(itemId)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "该耗材不在当日定额明细中"));
        String reason = text(payload, "reason");
        if (reason.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写例外原因");
        BigDecimal reported = decimal(payload, "reportedActualQuantity");
        if (reported.compareTo(scale(line.quotaQuantity())) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上报实际用量必须大于当日定额");
        }
        BigDecimal extra = scale(reported.subtract(scale(line.quotaQuantity())));
        Integer revision = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(revision), 0) + 1 FROM inventory_quota_exceptions WHERE report_id = ? AND item_id = ?",
            Integer.class, report.id(), itemId
        );
        String id = "quota-exception-" + UUID.randomUUID();
        jdbcTemplate.update(
            """
            INSERT INTO inventory_quota_exceptions
              (id, report_id, line_id, department_id, department_key, department_name, business_date,
               item_id, item_name_snapshot, unit_snapshot, quota_quantity, actual_quantity, extra_quantity,
               tolerance_quantity, status, reason, revision, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
            """,
            id, report.id(), line.id(), department.id(), department.key(), department.name(), date,
            itemId, line.itemName(), line.unit(), line.quotaQuantity(), reported, extra,
            line.toleranceQuantity(), reason, revision, user.name()
        );
        jdbcTemplate.update(
            """
            UPDATE inventory_department_daily_report_lines
            SET exception_status = 'PENDING', extra_quantity = ?, linked_patient_id = NULLIF(?, ''),
                linked_encounter_id = NULLIF(?, '')
            WHERE id = ?
            """,
            extra, text(payload, "patientId"), text(payload, "encounterId"), line.id()
        );
        repository.log(user.name(), "上报定额例外", "quota_exception", department.name() + " " + date,
            line.itemName() + " 实际上报 " + plain(reported) + "，超量 " + plain(extra));
        return exceptionDetail(id, user);
    }

    @Transactional
    public ObjectNode decideException(String exceptionId, JsonNode payload, SessionUser user) {
        ExceptionRow exception = lockException(exceptionId);
        if (!"PENDING".equals(exception.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该例外已处理，不能重复审核");
        }
        Department department = requireDepartment(exception.departmentKey(), user);
        String decision = text(payload, "status").toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVED", "REJECTED").contains(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审核结果只能为通过或驳回");
        }
        BigDecimal approved = "REJECTED".equals(decision) ? BigDecimal.ZERO : decimal(payload, "approvedExtraQuantity");
        if (approved.signum() < 0 || approved.compareTo(exception.extraQuantity()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通过的补扣量不能超过上报超量");
        }
        ReportRow report = loadReportById(exception.reportId());
        LineRow line = loadLineRow(exception.lineId());
        String eventId = null;
        BigDecimal lineDeducted = line == null ? BigDecimal.ZERO : scale(line.exceptionApprovedQuantity());
        String exceptionStatus;
        if (approved.signum() > 0) {
            eventId = deduct(report, line, approved, "QUOTA_EXCEPTION", "定额例外审核通过补扣", exception.id(), report.revision());
            if (eventId != null) lineDeducted = approved;
            exceptionStatus = approved.compareTo(exception.extraQuantity()) < 0 ? "PARTIALLY_APPROVED" : "APPROVED";
        } else {
            exceptionStatus = "REJECTED";
        }
        jdbcTemplate.update(
            """
            UPDATE inventory_quota_exceptions
            SET status = ?, approved_quantity = ?, review_note = ?, reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP(3)
            WHERE id = ?
            """,
            exceptionStatus, approved, text(payload, "reviewNote"), user.name(), exceptionId
        );
        if (line != null) {
            BigDecimal quota = scale(line.quotaQuantity());
            jdbcTemplate.update(
                """
                UPDATE inventory_department_daily_report_lines
                SET actual_quantity = ?, exception_status = ?, exception_approved_quantity = ?,
                    exception_event_id = ?
                WHERE id = ?
                """,
                quota.add(approved), exceptionStatus, approved, eventId, line.id()
            );
        }
        BigDecimal total = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(approved_quantity), 0) FROM inventory_quota_exceptions WHERE report_id = ?",
            BigDecimal.class, exception.reportId()
        );
        jdbcTemplate.update("UPDATE inventory_department_daily_reports SET exception_total = ? WHERE id = ?", total, exception.reportId());
        repository.log(user.name(), "审核定额例外", "quota_exception", department.name() + " " + exception.businessDate(),
            exceptionStatus + "，补扣 " + plain(approved));
        return exceptionDetail(exceptionId, user);
    }

    @Transactional
    public ObjectNode uploadPhoto(String exceptionId, MultipartFile file, SessionUser user) throws IOException {
        ExceptionRow exception = lockException(exceptionId);
        requireDepartment(exception.departmentKey(), user);
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM inventory_quota_exception_photos WHERE exception_id = ?", Integer.class, exceptionId
        );
        if (count != null && count >= 3) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每条例外最多上传 3 张照片");
        ClinicStoredFile stored = fileService.store(file, "quota-exception-" + exceptionId);
        String id = "quota-exception-photo-" + UUID.randomUUID();
        jdbcTemplate.update(
            """
            INSERT INTO inventory_quota_exception_photos
              (id, exception_id, storage_path, content_type, size_bytes, original_name, uploaded_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            id, exceptionId, stored.storagePath(), stored.mimeType(), stored.size(), stored.fileName(), user.name()
        );
        return photoNode(id, stored.storagePath(), stored.mimeType(), stored.size(), stored.fileName(), user.name());
    }

    @Transactional(readOnly = true)
    public PhotoFile photo(String photoId, SessionUser user) {
        PhotoRow row = jdbcTemplate.query(
            """
            SELECT p.*, e.department_key
            FROM inventory_quota_exception_photos p
            JOIN inventory_quota_exceptions e ON e.id = p.exception_id
            WHERE p.id = ?
            """,
            rs -> rs.next() ? new PhotoRow(rs.getString("id"), rs.getString("exception_id"), rs.getString("storage_path"),
                rs.getString("content_type"), rs.getString("original_name"), rs.getString("department_key")) : null,
            photoId
        );
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "照片不存在");
        requireDepartment(row.departmentKey(), user);
        return new PhotoFile(photoId, row.contentType(), row.originalName(), fileService.load(row.storagePath()));
    }

    @Transactional(readOnly = true)
    public ObjectNode exceptions(String status, LocalDate date, String departmentKey, SessionUser user) {
        Department department = departmentKey == null || departmentKey.isBlank() ? null : requireDepartment(departmentKey, user);
        StringBuilder sql = new StringBuilder("""
            SELECT e.*, (SELECT COUNT(*) FROM inventory_quota_exception_photos p WHERE p.exception_id = e.id) photo_count
            FROM inventory_quota_exceptions e
            WHERE 1 = 1
            """);
        List<Object> args = new ArrayList<>();
        if (department != null) {
            sql.append(" AND e.department_key = ?");
            args.add(department.key());
        } else if (!inventoryAccess.canViewAllDepartments(user)) {
            sql.append(" AND e.department_key = ?");
            args.add(departmentKeyForUser(user));
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND e.status = ?");
            args.add(status.toUpperCase(Locale.ROOT));
        }
        if (date != null) {
            sql.append(" AND e.business_date = ?");
            args.add(date);
        }
        sql.append(" ORDER BY e.created_at DESC LIMIT 300");
        List<ObjectNode> list = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            ObjectNode row = objectMapper.createObjectNode();
            row.put("id", rs.getString("id"));
            row.put("reportId", rs.getString("report_id"));
            row.put("lineId", safe(rs.getString("line_id")));
            row.put("departmentKey", rs.getString("department_key"));
            row.put("departmentName", safe(rs.getString("department_name")));
            row.put("businessDate", rs.getDate("business_date") == null ? "" : rs.getDate("business_date").toString());
            row.put("itemId", rs.getString("item_id"));
            row.put("itemName", safe(rs.getString("item_name_snapshot")));
            row.put("unit", safe(rs.getString("unit_snapshot")));
            row.put("quotaQuantity", rs.getBigDecimal("quota_quantity"));
            row.put("reportedActualQuantity", rs.getBigDecimal("actual_quantity"));
            row.put("extraQuantity", rs.getBigDecimal("extra_quantity"));
            row.put("toleranceQuantity", rs.getBigDecimal("tolerance_quantity"));
            row.put("status", rs.getString("status"));
            row.put("approvedQuantity", rs.getBigDecimal("approved_quantity"));
            row.put("reason", safe(rs.getString("reason")));
            row.put("reviewNote", safe(rs.getString("review_note")));
            row.put("reviewedBy", safe(rs.getString("reviewed_by")));
            row.put("reviewedAt", safe(rs.getString("reviewed_at")));
            row.put("photoCount", rs.getInt("photo_count"));
            row.put("createdAt", safe(rs.getString("created_at")));
            return row;
        }, args.toArray());
        ObjectNode result = objectMapper.createObjectNode();
        result.set("list", objectMapper.valueToTree(list));
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode exceptionDetail(String exceptionId, SessionUser user) {
        ExceptionRow exception = lockExceptionRead(exceptionId);
        requireDepartment(exception.departmentKey(), user);
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", exception.id());
        row.put("reportId", exception.reportId());
        row.put("lineId", exception.lineId());
        row.put("departmentKey", exception.departmentKey());
        row.put("departmentName", exception.departmentName());
        row.put("businessDate", exception.businessDate().toString());
        row.put("itemId", exception.itemId());
        row.put("itemName", exception.itemName());
        row.put("unit", exception.unit());
        row.put("quotaQuantity", exception.quotaQuantity());
        row.put("reportedActualQuantity", exception.actualQuantity());
        row.put("extraQuantity", exception.extraQuantity());
        row.put("toleranceQuantity", exception.toleranceQuantity());
        row.put("status", exception.status());
        row.put("approvedQuantity", exception.approvedQuantity());
        row.put("reason", exception.reason());
        row.put("reviewNote", exception.reviewNote());
        row.put("reviewedBy", exception.reviewedBy());
        row.put("reviewedAt", exception.reviewedAt());
        row.set("photos", photos(exceptionId));
        return row;
    }

    @Transactional(readOnly = true)
    public ObjectNode patientEvidence(String departmentKey, LocalDate businessDate, SessionUser user) {
        Department department = requireDepartment(departmentKey, user);
        LocalDate date = businessDate == null ? LocalDate.now() : businessDate;
        ObjectNode report = read(departmentKey, date, user);
        ArrayNode patients = objectMapper.valueToTree(jdbcTemplate.query(
            """
            SELECT patient_id, MAX(patient_name) patient_name, COUNT(DISTINCT encounter_id) visit_count,
                   MAX(care_type) care_type
            FROM inventory_service_instances
            WHERE department_key = ? AND business_date = ?
            GROUP BY patient_id
            ORDER BY patient_name
            """,
            (rs, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("patientId", rs.getString("patient_id"));
                row.put("patientName", safe(rs.getString("patient_name")));
                row.put("visitCount", rs.getInt("visit_count"));
                row.put("careType", safe(rs.getString("care_type")));
                return row;
            },
            department.key(), date
        ));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("departmentKey", department.key());
        result.put("departmentName", department.name());
        result.put("businessDate", date.toString());
        result.set("headcount", report.path("headcount"));
        result.set("lines", report.path("lines"));
        result.set("patients", patients);
        result.set("exceptions", exceptions("", date, department.key(), user).path("list"));
        return result;
    }

    // ---------- report ----------

    private ObjectNode persistReport(Department department, LocalDate date, ReportRow current, Headcount headcount,
                                     String note, SessionUser user) {
        String reportId = current == null ? "department-daily-report-" + UUID.randomUUID() : current.id();
        int revision = current == null ? 1 : current.revision() + 1;
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("departmentKey", department.key());
        raw.put("businessDate", date.toString());
        raw.put("reportMode", "QUOTA");
        raw.put("note", note == null ? "" : note);
        if (current == null) {
            jdbcTemplate.update(
                """
                INSERT INTO inventory_department_daily_reports
                  (id, department_id, department_key, department_name, business_date, report_mode, status, revision, note,
                   headcount_outpatient, headcount_inpatient, headcount_manual, headcount_source, raw_json)
                VALUES (?, ?, ?, ?, ?, 'QUOTA', 'DRAFT', ?, ?, ?, ?, ?, ?, CAST(? AS JSON))
                """,
                reportId, department.id(), department.key(), department.name(), date, revision, note == null ? "" : note,
                headcount.outpatient(), headcount.inpatient(), headcount.manual(), headcount.source(), json(raw)
            );
        } else {
            jdbcTemplate.update(
                """
                UPDATE inventory_department_daily_reports
                SET revision = ?, note = ?, status = 'DRAFT', headcount_outpatient = ?, headcount_inpatient = ?,
                    headcount_manual = ?, headcount_source = ?, raw_json = CAST(? AS JSON)
                WHERE id = ?
                """,
                revision, note == null ? "" : note, headcount.outpatient(), headcount.inpatient(),
                headcount.manual(), headcount.source(), json(raw), reportId
            );
            jdbcTemplate.update("DELETE FROM inventory_department_daily_report_lines WHERE report_id = ?", reportId);
        }
        ArrayNode lines = computeLines(department, date, headcount);
        int lineNo = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode node : lines) {
            BigDecimal quota = decimal(node, "quotaQuantity");
            total = total.add(quota);
            insertLine(reportId, ++lineNo, node, quota, BigDecimal.ZERO, null, BigDecimal.ZERO);
        }
        jdbcTemplate.update(
            "UPDATE inventory_department_daily_reports SET quota_computed_total = ? WHERE id = ?", total, reportId
        );
        return read(department.key(), date, user);
    }

    private void insertLine(String reportId, int lineNo, JsonNode node, BigDecimal quota, BigDecimal deducted,
                            String eventId, BigDecimal approvedExtra) {
        ObjectNode raw = objectMapper.createObjectNode();
        raw.setAll((ObjectNode) node.deepCopy());
        jdbcTemplate.update(
            """
            INSERT INTO inventory_department_daily_report_lines
              (id, report_id, line_no, item_id, item_name_snapshot, unit_snapshot, preset_quantity, actual_quantity,
               quota_quantity, quota_deducted_quantity, extra_quantity, tolerance_mode, tolerance_value,
               tolerance_quantity, exception_status, exception_approved_quantity, quota_event_id, source,
               review_status, raw_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NONE', ?, ?, 'TEMPLATE', 'APPROVED', CAST(? AS JSON))
            """,
            "department-daily-line-" + UUID.randomUUID(), reportId, lineNo, text(node, "itemId"),
            text(node, "itemName"), text(node, "unit"), quota, quota.add(approvedExtra), quota, deducted,
            decimal(node, "extraQuantity"), text(node, "toleranceMode"), decimal(node, "toleranceValue"),
            decimal(node, "toleranceQuantity"), approvedExtra, eventId, json(raw)
        );
    }

    private ArrayNode computeLines(Department department, LocalDate date, Headcount headcount) {
        String versionId = activeVersionId(date);
        Map<String, ObjectNode> byItem = new LinkedHashMap<>();
        List<QuotaRuleRow> rules = jdbcTemplate.query(
            """
            SELECT r.*, COALESCE(i.name, r.material_name) item_name, COALESCE(i.unit, r.unit) item_unit
            FROM inventory_quota_rules r
            LEFT JOIN inventory_items i ON i.id = r.item_id
            WHERE r.version_id = ? AND r.department_key = ? AND r.enabled = 1 AND r.item_id IS NOT NULL
            ORDER BY r.source_row, r.id
            """,
            (rs, rowNum) -> new QuotaRuleRow(
                rs.getString("id"), rs.getString("item_id"), rs.getString("item_name"), rs.getString("item_unit"),
                rs.getString("binding_type"), rs.getString("care_type"), rs.getString("measurement_scope"),
                rs.getBigDecimal("standard_quantity"), rs.getBigDecimal("fixed_adjustment"),
                rs.getString("tolerance_mode"), rs.getBigDecimal("tolerance_value")
            ),
            versionId.isBlank() ? "" : versionId, department.key()
        );
        for (QuotaRuleRow rule : rules) {
            BigDecimal standard = scale(rule.standardQuantity());
            BigDecimal adjustment = scale(rule.fixedAdjustment());
            BigDecimal volume;
            String basis;
            if (PER_PERSON.contains(rule.bindingType())) {
                String scope = rule.measurementScope() == null ? "" : rule.measurementScope().toUpperCase(Locale.ROOT);
                if ("OUTPATIENT".equals(scope) || "outpatient".equalsIgnoreCase(rule.careType())) {
                    volume = BigDecimal.valueOf(headcount.outpatient());
                    basis = "OUTPATIENT";
                } else if ("INPATIENT".equals(scope) || "inpatient".equalsIgnoreCase(rule.careType())) {
                    volume = BigDecimal.valueOf(headcount.inpatient());
                    basis = "INPATIENT";
                } else if ("COMBINED".equals(scope)) {
                    volume = BigDecimal.valueOf(headcount.total());
                    basis = "COMBINED";
                } else {
                    volume = BigDecimal.valueOf(headcount.manual());
                    basis = "MANUAL";
                }
            } else {
                volume = BigDecimal.ONE;
                basis = "DAILY";
            }
            BigDecimal quota = scale(standard.multiply(volume).add(adjustment));
            if (quota.signum() < 0) quota = BigDecimal.ZERO;
            BigDecimal tolerance = tolerance(rule, quota);
            ObjectNode row = byItem.get(rule.itemId());
            if (row == null) {
                row = objectMapper.createObjectNode();
                row.put("itemId", rule.itemId());
                row.put("itemName", rule.itemName());
                row.put("unit", rule.itemUnit());
                row.put("bindingType", rule.bindingType());
                row.put("quotaQuantity", BigDecimal.ZERO);
                row.put("extraQuantity", BigDecimal.ZERO);
                row.put("toleranceMode", rule.toleranceMode());
                row.put("toleranceValue", scale(rule.toleranceValue()));
                row.put("toleranceQuantity", BigDecimal.ZERO);
                row.put("headcountBasis", basis);
                row.put("exceptionStatus", "NONE");
                byItem.put(rule.itemId(), row);
            }
            row.put("quotaQuantity", scale(row.path("quotaQuantity").decimalValue().add(quota)));
            if (tolerance.compareTo(row.path("toleranceQuantity").decimalValue()) > 0) {
                row.put("toleranceQuantity", tolerance);
                row.put("toleranceMode", rule.toleranceMode());
                row.put("toleranceValue", scale(rule.toleranceValue()));
            }
        }
        ArrayNode lines = objectMapper.createArrayNode();
        byItem.values().forEach(row -> {
            BigDecimal quota = row.path("quotaQuantity").decimalValue();
            row.put("presetQuantity", quota);
            row.put("actualQuantity", quota);
            row.put("source", "TEMPLATE");
            row.put("reviewStatus", "APPROVED");
            lines.add(row);
        });
        return lines;
    }

    private BigDecimal tolerance(QuotaRuleRow rule, BigDecimal quota) {
        String mode = rule.toleranceMode() == null ? "NONE" : rule.toleranceMode().toUpperCase(Locale.ROOT);
        BigDecimal value = scale(rule.toleranceValue());
        if ("PERCENT".equals(mode)) return scale(quota.multiply(value).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        if ("ABSOLUTE".equals(mode)) return value.max(BigDecimal.ZERO);
        return BigDecimal.ZERO;
    }

    private Headcount resolveHeadcount(Department department, LocalDate date, JsonNode payload) {
        boolean override = payload.path("headcountOverride").asBoolean(false);
        if ("DAILY".equals(department.entryMode())) {
            int manual = payload.path("headcountManual").asInt(0);
            return new Headcount(0, 0, Math.max(0, manual), "MANUAL");
        }
        Headcount auto = autoHeadcount(department, date);
        if (!override) return auto;
        String reason = text(payload, "headcountReason");
        if (reason.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "修正人数必须填写原因");
        int outpatient = Math.max(0, payload.path("headcountOutpatient").asInt(auto.outpatient()));
        int inpatient = Math.max(0, payload.path("headcountInpatient").asInt(auto.inpatient()));
        return new Headcount(outpatient, inpatient, 0, "MIXED");
    }

    private Headcount autoHeadcount(Department department, LocalDate date) {
        if ("DAILY".equals(department.entryMode())) return new Headcount(0, 0, 0, "");
        String clinicDepartmentId = safe(department.clinicDepartmentId());
        int outpatient = 0;
        int inpatient = 0;
        List<String> careTypes = jdbcTemplate.query(
            """
            SELECT x.care_type, COUNT(*) total
            FROM (
              SELECT e.id, e.inventory_care_type care_type
              FROM pre_ai_encounters e
              WHERE e.status <> 'CANCELLED' AND DATE(e.created_at) = ?
                AND (e.owning_department_id = ? OR e.owning_department_name_snapshot = ?)
              UNION
              SELECT si.encounter_id, si.care_type
              FROM inventory_service_instances si
              WHERE si.department_key = ? AND si.business_date = ?
                AND NOT EXISTS (
                  SELECT 1 FROM pre_ai_encounters e2
                  WHERE e2.id = si.encounter_id AND DATE(e2.created_at) = ? AND e2.status <> 'CANCELLED'
                    AND (e2.owning_department_id = ? OR e2.owning_department_name_snapshot = ?)
                )
            ) x
            GROUP BY x.care_type
            """,
            (rs, rowNum) -> safe(rs.getString("care_type")) + ":" + rs.getInt("total"),
            date, clinicDepartmentId, department.name(), department.key(), date, date, clinicDepartmentId, department.name()
        );
        for (String value : careTypes) {
            int split = value.lastIndexOf(':');
            if (split <= 0) continue;
            String careType = value.substring(0, split);
            int count = Integer.parseInt(value.substring(split + 1));
            if ("inpatient".equalsIgnoreCase(careType)) inpatient += count;
            else outpatient += count;
        }
        return new Headcount(outpatient, inpatient, 0, outpatient + inpatient == 0 ? "" : "AUTO");
    }

    private ObjectNode headcountJson(Headcount headcount) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("outpatient", headcount.outpatient());
        node.put("inpatient", headcount.inpatient());
        node.put("manual", headcount.manual());
        node.put("total", headcount.total());
        node.put("source", headcount.source());
        return node;
    }

    private BigDecimal sum(ArrayNode lines, String field) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode line : lines) total = total.add(decimal(line, field));
        return total;
    }

    // ---------- deduction ----------

    private String deduct(ReportRow report, LineRow line, BigDecimal quantity, String relatedType, String reason,
                          String exceptionId, long revisionToken) {
        if (line == null || quantity.signum() <= 0) return null;
        String idempotency = "quota:" + report.id() + ":" + revisionToken + ":" + line.id() + ":"
            + relatedType + ":" + safe(exceptionId);
        List<String> existing = jdbcTemplate.query(
            "SELECT id FROM inventory_consumption_events WHERE idempotency_key = ? LIMIT 1",
            (rs, rowNum) -> rs.getString(1), idempotency
        );
        if (!existing.isEmpty()) return existing.get(0);
        String location = ledger.departmentLocation(report.departmentId());
        List<Balance> balances = ledger.lockAvailableBalances(location, line.itemId());
        BigDecimal available = balances.stream().map(Balance::available).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!ledger.isOpeningConfirmed(report.departmentId()) && available.signum() <= 0) {
            createPendingTask(report, line, quantity, "科室期初库存尚未盘点确认", relatedType, exceptionId);
            return null;
        }
        if (available.compareTo(quantity) < 0) {
            createPendingTask(report, line, quantity, "当前可用 " + plain(available), relatedType, exceptionId);
            return null;
        }
        BigDecimal remaining = quantity;
        List<Allocation> allocations = new ArrayList<>();
        for (Balance balance : balances) {
            if (remaining.signum() <= 0) break;
            BigDecimal take = remaining.min(balance.available());
            if (take.signum() > 0) {
                allocations.add(new Allocation(balance, take));
                remaining = remaining.subtract(take);
            }
        }
        String eventId = "quota-consumption-" + UUID.randomUUID();
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("sourceType", relatedType);
        raw.put("sourceDocumentId", report.id());
        raw.put("sourceLineId", line.id());
        raw.put("itemId", line.itemId());
        raw.put("quantity", quantity);
        jdbcTemplate.update(
            """
            INSERT INTO inventory_consumption_events
              (id, command_id, source_document_id, source_line_id, correction_document_id, service_instance_id,
               patient_id, idempotency_key, encounter_id, case_token, route, care_type, care_encounter_id,
               department, department_id, visit_date, package_id, package_version, status, event_kind,
               trigger_stage, completion_version, operator_name, created_at, raw_json)
            VALUES (?, NULL, ?, ?, NULL, NULL, NULL, ?, NULL, NULL, 'QUOTA', 'outpatient', NULL,
                    ?, ?, ?, NULL, NULL, 'succeeded', 'CONSUMPTION', 'QUOTA', NULL, ?, ?, CAST(? AS JSON))
            """,
            eventId, report.id(), line.id(), idempotency, report.departmentName(), report.departmentId(),
            report.businessDate().toString(), "system", LocalDateTime.now().toString(), json(raw)
        );
        for (Allocation allocation : allocations) {
            ledger.changeBalance(allocation.balance().id(), allocation.quantity().negate(), BigDecimal.ZERO);
            jdbcTemplate.update(
                """
                INSERT INTO inventory_consumption_details
                  (id, event_id, item_id, batch_id, location_id, quantity, detail_kind, raw_json)
                VALUES (?, ?, ?, ?, ?, ?, 'CONSUMPTION', JSON_OBJECT('source', ?))
                """,
                "quota-consumption-detail-" + UUID.randomUUID(), eventId, line.itemId(),
                allocation.balance().batchId(), location, allocation.quantity(), relatedType
            );
            ledger.movement("CONSUMPTION", line.itemId(), allocation.balance().batchId(), location, null,
                allocation.quantity(), report.departmentId(), relatedType, line.id(), eventId, null,
                "system", reason);
        }
        return eventId;
    }

    private void reverseQuota(ReportRow report, LineRow line, BigDecimal quantity, SessionUser user, String reason,
                              long revisionToken) {
        if (line.quotaEventId() == null || quantity.signum() <= 0) return;
        String idempotency = "quota-reversal:" + report.id() + ":" + revisionToken + ":" + line.id();
        List<String> existing = jdbcTemplate.query(
            "SELECT id FROM inventory_consumption_events WHERE idempotency_key = ? LIMIT 1",
            (rs, rowNum) -> rs.getString(1), idempotency
        );
        if (!existing.isEmpty()) return;
        List<Allocation> details = jdbcTemplate.query(
            """
            SELECT d.*, b.id balance_id
            FROM inventory_consumption_details d
            JOIN inventory_batch_balances b ON b.location_id = d.location_id AND b.batch_id = d.batch_id
            WHERE d.event_id = ? ORDER BY d.id
            """,
            (rs, rowNum) -> new Allocation(
                new Balance(rs.getString("balance_id"), rs.getString("location_id"), rs.getString("item_id"),
                    rs.getString("batch_id"), rs.getBigDecimal("quantity"), BigDecimal.ZERO, "", ""),
                rs.getBigDecimal("quantity")
            ),
            line.quotaEventId()
        );
        BigDecimal remaining = quantity;
        String eventId = "quota-reversal-" + UUID.randomUUID();
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("sourceType", "QUOTA_HEADCOUNT_CORRECTION");
        raw.put("reversalOfEventId", line.quotaEventId());
        raw.put("quantity", quantity);
        jdbcTemplate.update(
            """
            INSERT INTO inventory_consumption_events
              (id, source_document_id, source_line_id, department, department_id, visit_date, idempotency_key,
               status, event_kind, reversal_of_event_id, operator_name, created_at, raw_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'succeeded', 'REVERSAL', ?, ?, ?, CAST(? AS JSON))
            """,
            eventId, report.id(), line.id(), report.departmentName(), report.departmentId(),
            report.businessDate().toString(), idempotency, line.quotaEventId(), user.name(), LocalDateTime.now().toString(), json(raw)
        );
        for (Allocation detail : details) {
            if (remaining.signum() <= 0) break;
            BigDecimal back = remaining.min(detail.quantity());
            if (back.signum() <= 0) continue;
            ledger.changeBalance(detail.balance().id(), back, BigDecimal.ZERO);
            ledger.movement("REVERSAL", line.itemId(), detail.balance().batchId(), detail.balance().locationId(),
                null, back, report.departmentId(), "QUOTA_HEADCOUNT_CORRECTION", line.id(), eventId,
                line.quotaEventId(), user.name(), reason);
            remaining = remaining.subtract(back);
        }
    }

    private void createPendingTask(ReportRow report, LineRow line, BigDecimal quantity, String reason,
                                   String sourceType, String exceptionId) {
        String message = line.itemName() + " 定额扣减待补扣：" + reason + "（数量 " + plain(quantity) + "）";
        jdbcTemplate.update(
            """
            INSERT INTO inventory_exception_tasks
              (id, command_id, exception_type, severity, status, department_id, department_name_snapshot,
               encounter_id, trigger_stage, item_id, message, source_type, source_id, line_id)
            VALUES (?, NULL, 'REVIEW_DEDUCTION_PENDING', 'HIGH', 'OPEN', ?, ?, NULL, 'QUOTA', ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE status = 'OPEN', message = VALUES(message), resolved_by = NULL,
              resolution_note = NULL, resolved_at = NULL, updated_at = CURRENT_TIMESTAMP(3)
            """,
            "inventory-exception-" + UUID.randomUUID(), report.departmentId(), report.departmentName(),
            line.itemId(), message, sourceType, firstNonBlank(exceptionId, report.id()), line.id()
        );
    }

    private void upsertClosure(Department department, LocalDate date, String status, SessionUser user) {
        jdbcTemplate.update(
            """
            INSERT INTO inventory_department_day_closures
              (id, department_id, department_key, department_name, business_date, entry_mode, status, submitted_by, submitted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE status = VALUES(status), submitted_by = VALUES(submitted_by),
              submitted_at = VALUES(submitted_at)
            """,
            "day-closure-" + UUID.randomUUID(), department.id(), department.key(), department.name(), date,
            department.entryMode(), status, user.name()
        );
    }

    // ---------- loading/helpers ----------

    private ReportRow loadReport(String departmentId, LocalDate date) {
        return jdbcTemplate.query(
            "SELECT * FROM inventory_department_daily_reports WHERE department_id = ? AND business_date = ?",
            rs -> rs.next() ? reportRow(rs) : null,
            departmentId, date
        );
    }

    private ReportRow loadReportById(String reportId) {
        return jdbcTemplate.query(
            "SELECT * FROM inventory_department_daily_reports WHERE id = ?",
            rs -> rs.next() ? reportRow(rs) : null,
            reportId
        );
    }

    private ReportRow reportRow(ResultSet rs) throws SQLException {
        return new ReportRow(
            rs.getString("id"), rs.getString("department_id"), rs.getString("department_key"),
            rs.getString("department_name"), rs.getDate("business_date").toLocalDate(), rs.getString("report_mode"),
            rs.getString("status"), rs.getInt("revision"), safe(rs.getString("note")),
            rs.getInt("headcount_outpatient"), rs.getInt("headcount_inpatient"), rs.getInt("headcount_manual"),
            safe(rs.getString("headcount_source")), rs.getBigDecimal("quota_computed_total"),
            rs.getBigDecimal("quota_deducted_total"), rs.getBigDecimal("exception_total")
        );
    }

    private ArrayNode loadLines(String reportId) {
        ArrayNode rows = objectMapper.createArrayNode();
        for (LineRow line : loadLineRows(reportId)) {
            ObjectNode row = rows.addObject();
            row.put("lineId", line.id());
            row.put("itemId", line.itemId());
            row.put("itemName", line.itemName());
            row.put("unit", line.unit());
            row.put("presetQuantity", line.quotaQuantity());
            row.put("quotaQuantity", line.quotaQuantity());
            row.put("actualQuantity", line.actualQuantity());
            row.put("quotaDeductedQuantity", line.quotaDeductedQuantity());
            row.put("extraQuantity", line.extraQuantity());
            row.put("toleranceMode", line.toleranceMode());
            row.put("toleranceValue", line.toleranceValue());
            row.put("toleranceQuantity", line.toleranceQuantity());
            row.put("exceptionStatus", line.exceptionStatus());
            row.put("exceptionApprovedQuantity", line.exceptionApprovedQuantity());
            row.put("quotaEventId", line.quotaEventId());
            row.put("exceptionEventId", line.exceptionEventId());
            row.put("headcountBasis", line.headcountBasis());
            row.put("source", "TEMPLATE");
            row.put("reviewStatus", "APPROVED");
        }
        return rows;
    }

    private List<LineRow> loadLineRows(String reportId) {
        return jdbcTemplate.query(
            "SELECT * FROM inventory_department_daily_report_lines WHERE report_id = ? ORDER BY line_no",
            (rs, rowNum) -> new LineRow(
                rs.getString("id"), rs.getString("item_id"), rs.getString("item_name_snapshot"), rs.getString("unit_snapshot"),
                rs.getBigDecimal("quota_quantity"), rs.getBigDecimal("actual_quantity"), rs.getBigDecimal("quota_deducted_quantity"),
                rs.getBigDecimal("extra_quantity"), rs.getString("tolerance_mode"), rs.getBigDecimal("tolerance_value"),
                rs.getBigDecimal("tolerance_quantity"), rs.getString("exception_status"),
                rs.getBigDecimal("exception_approved_quantity"), rs.getString("quota_event_id"),
                rs.getString("exception_event_id"), rs.getString("headcount_basis")
            ),
            reportId
        );
    }

    private LineRow loadLineRow(String lineId) {
        if (lineId == null || lineId.isBlank()) return null;
        List<LineRow> rows = jdbcTemplate.query(
            "SELECT * FROM inventory_department_daily_report_lines WHERE id = ?",
            (rs, rowNum) -> new LineRow(
                rs.getString("id"), rs.getString("item_id"), rs.getString("item_name_snapshot"), rs.getString("unit_snapshot"),
                rs.getBigDecimal("quota_quantity"), rs.getBigDecimal("actual_quantity"), rs.getBigDecimal("quota_deducted_quantity"),
                rs.getBigDecimal("extra_quantity"), rs.getString("tolerance_mode"), rs.getBigDecimal("tolerance_value"),
                rs.getBigDecimal("tolerance_quantity"), rs.getString("exception_status"),
                rs.getBigDecimal("exception_approved_quantity"), rs.getString("quota_event_id"),
                rs.getString("exception_event_id"), rs.getString("headcount_basis")
            ),
            lineId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ExceptionRow lockException(String exceptionId) {
        List<ExceptionRow> rows = jdbcTemplate.query(
            "SELECT * FROM inventory_quota_exceptions WHERE id = ? FOR UPDATE",
            (rs, rowNum) -> exceptionRow(rs), exceptionId
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "例外记录不存在");
        return rows.get(0);
    }

    private ExceptionRow lockExceptionRead(String exceptionId) {
        List<ExceptionRow> rows = jdbcTemplate.query(
            "SELECT * FROM inventory_quota_exceptions WHERE id = ?",
            (rs, rowNum) -> exceptionRow(rs), exceptionId
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "例外记录不存在");
        return rows.get(0);
    }

    private ExceptionRow exceptionRow(ResultSet rs) throws SQLException {
        return new ExceptionRow(
            rs.getString("id"), rs.getString("report_id"), rs.getString("line_id"), rs.getString("department_key"),
            rs.getString("department_name"), rs.getDate("business_date").toLocalDate(), rs.getString("item_id"),
            rs.getString("item_name_snapshot"), rs.getString("unit_snapshot"), rs.getBigDecimal("quota_quantity"),
            rs.getBigDecimal("actual_quantity"), rs.getBigDecimal("extra_quantity"), rs.getBigDecimal("tolerance_quantity"),
            rs.getString("status"), rs.getBigDecimal("approved_quantity"), safe(rs.getString("reason")),
            safe(rs.getString("review_note")), safe(rs.getString("reviewed_by")), safe(rs.getString("reviewed_at")),
            rs.getString("department_id")
        );
    }

    private ArrayNode photos(String exceptionId) {
        return objectMapper.valueToTree(jdbcTemplate.query(
            "SELECT * FROM inventory_quota_exception_photos WHERE exception_id = ? ORDER BY created_at",
            (rs, rowNum) -> photoNode(rs.getString("id"), rs.getString("storage_path"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("original_name"), rs.getString("uploaded_by")),
            exceptionId
        ));
    }

    private ObjectNode photoNode(String id, String path, String contentType, long size, String name, String uploadedBy) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", id);
        row.put("contentType", contentType);
        row.put("size", size);
        row.put("originalName", name);
        row.put("uploadedBy", uploadedBy);
        row.put("url", "/inventory-api/quota-exceptions/photos/" + id);
        return row;
    }

    private int countExceptions(String reportId, String status) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM inventory_quota_exceptions WHERE report_id = ? AND status = ?",
            Integer.class, reportId, status
        );
        return count == null ? 0 : count;
    }

    private ObjectNode closureJson(String departmentId, LocalDate date) {
        return jdbcTemplate.query(
            "SELECT * FROM inventory_department_day_closures WHERE department_id = ? AND business_date = ?",
            rs -> {
                ObjectNode node = objectMapper.createObjectNode();
                if (!rs.next()) return node;
                node.put("status", rs.getString("status"));
                node.put("submittedBy", safe(rs.getString("submitted_by")));
                node.put("submittedAt", rs.getTimestamp("submitted_at") == null ? "" : rs.getTimestamp("submitted_at").toLocalDateTime().toString());
                return node;
            },
            departmentId, date
        );
    }

    private boolean locked(ReportRow report, LocalDate date) {
        return date.isBefore(LocalDate.now()) && !"DRAFT".equals(report.status());
    }

    private Department requireDepartment(String departmentKey, SessionUser user) {
        List<Department> rows = jdbcTemplate.query(
            "SELECT id, department_key, name, clinic_department_id, entry_mode FROM inventory_departments WHERE department_key = ? AND status = 'ACTIVE'",
            (rs, rowNum) -> new Department(rs.getString("department_key"), rs.getString("id"), rs.getString("name"),
                rs.getString("clinic_department_id"), rs.getString("entry_mode")),
            departmentKey == null ? "" : departmentKey.trim()
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知科室");
        Department department = rows.get(0);
        if (!inventoryAccess.canViewAllDepartments(user) && !sameDepartment(user.department(), department.name())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问其他科室");
        }
        return department;
    }

    private List<Department> departments(String departmentKey) {
        String sql = "SELECT id, department_key, name, clinic_department_id, entry_mode FROM inventory_departments WHERE status = 'ACTIVE'";
        Object[] args = {};
        if (departmentKey != null && !departmentKey.isBlank()) {
            sql += " AND department_key = ?";
            args = new Object[]{departmentKey};
        }
        sql += " ORDER BY display_order, name";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new Department(
            rs.getString("department_key"), rs.getString("id"), rs.getString("name"),
            rs.getString("clinic_department_id"), rs.getString("entry_mode")
        ), args);
    }

    private String departmentKeyForUser(SessionUser user) {
        List<String> keys = jdbcTemplate.query(
            "SELECT department_key FROM inventory_departments WHERE name = ? AND status = 'ACTIVE' LIMIT 1",
            (rs, rowNum) -> rs.getString(1), user.department()
        );
        return keys.isEmpty() ? "" : keys.get(0);
    }

    private String departmentLocationReadOnly(String departmentId) {
        List<String> locations = jdbcTemplate.query(
            "SELECT id FROM inventory_locations WHERE department_id = ? AND status = 'ACTIVE' LIMIT 1",
            (rs, rowNum) -> rs.getString(1), departmentId
        );
        return locations.isEmpty() ? "" : locations.get(0);
    }

    private String activeVersionId(LocalDate date) {
        List<String> ids = jdbcTemplate.query(
            "SELECT id FROM inventory_quota_versions WHERE status = 'ACTIVE' AND effective_date <= ? ORDER BY effective_date DESC LIMIT 1",
            (rs, rowNum) -> rs.getString(1), date
        );
        return ids.isEmpty() ? "" : ids.get(0);
    }

    private ObjectNode missingDepartment(Department department, String reason) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("departmentKey", department.key());
        row.put("departmentName", department.name());
        row.put("itemId", "");
        row.put("itemName", "");
        row.put("unit", "");
        row.put("reason", reason);
        return row;
    }

    private String setting(String key, String fallback) {
        List<String> values = jdbcTemplate.query(
            "SELECT setting_value FROM inventory_runtime_settings WHERE setting_key = ?",
            (rs, rowNum) -> rs.getString(1), key
        );
        return values.isEmpty() || values.get(0) == null ? fallback : values.get(0);
    }

    private LocalDate settingDate(String key) {
        String value = setting(key, "");
        if (value.isBlank()) return null;
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void upsertSetting(String key, String value, String user) {
        jdbcTemplate.update(
            """
            INSERT INTO inventory_runtime_settings (setting_key, setting_value, updated_by)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_by = VALUES(updated_by)
            """,
            key, value == null ? "" : value, user
        );
    }

    private String canonicalRole(SessionUser user) {
        return user == null || user.role() == null ? "" : user.role().toLowerCase(Locale.ROOT);
    }

    private static boolean sameDepartment(String userDepartment, String canonicalName) {
        if (userDepartment == null) return false;
        String value = userDepartment.trim();
        return value.equals(canonicalName)
            || (value.equals("后勤保洁") && canonicalName.equals("后勤"))
            || (value.equals("后勤") && canonicalName.equals("后勤保洁"));
    }

    private static List<String> firstNames(JsonNode items, int limit) {
        List<String> names = new ArrayList<>();
        if (items.isArray()) {
            for (JsonNode item : items) {
                if (names.size() >= limit) break;
                names.add(text(item, "departmentName") + "/" + text(item, "itemName"));
            }
        }
        return names;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.asText("0"));
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(3, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal value) {
        return scale(value).stripTrailingZeros().toPlainString();
    }

    private static String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        try {
            return LocalDate.parse(normalized.length() > 10 ? normalized.substring(0, 10) : normalized);
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期格式不正确");
        }
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalStateException("JSON 序列化失败", error);
        }
    }

    private Headcount headcount(ReportRow report) {
        return new Headcount(report.headcountOutpatient(), report.headcountInpatient(), report.headcountManual(),
            report.headcountSource());
    }

    private record Department(String key, String id, String name, String clinicDepartmentId, String entryMode) {}
    private record Headcount(int outpatient, int inpatient, int manual, String source) {
        int total() { return outpatient + inpatient + manual; }
    }
    private record MissingItem(String itemId, String itemName, String unit) {}
    private record QuotaRuleRow(String id, String itemId, String itemName, String itemUnit, String bindingType,
                                String careType, String measurementScope, BigDecimal standardQuantity,
                                BigDecimal fixedAdjustment, String toleranceMode, BigDecimal toleranceValue) {}
    private record ReportRow(String id, String departmentId, String departmentKey, String departmentName, LocalDate businessDate,
                             String reportMode, String status, int revision, String note, int headcountOutpatient,
                             int headcountInpatient, int headcountManual, String headcountSource,
                             BigDecimal quotaComputedTotal, BigDecimal quotaDeductedTotal, BigDecimal exceptionTotal) {}
    private record LineRow(String id, String itemId, String itemName, String unit, BigDecimal quotaQuantity,
                           BigDecimal actualQuantity, BigDecimal quotaDeductedQuantity, BigDecimal extraQuantity,
                           String toleranceMode, BigDecimal toleranceValue, BigDecimal toleranceQuantity,
                           String exceptionStatus, BigDecimal exceptionApprovedQuantity, String quotaEventId,
                           String exceptionEventId, String headcountBasis) {}
    private record ExceptionRow(String id, String reportId, String lineId, String departmentKey, String departmentName,
                                LocalDate businessDate, String itemId, String itemName, String unit,
                                BigDecimal quotaQuantity, BigDecimal actualQuantity, BigDecimal extraQuantity,
                                BigDecimal toleranceQuantity, String status, BigDecimal approvedQuantity,
                                String reason, String reviewNote, String reviewedBy, String reviewedAt,
                                String departmentId) {}
    private record PhotoRow(String id, String exceptionId, String storagePath, String contentType, String originalName,
                            String departmentKey) {}
    private record Allocation(Balance balance, BigDecimal quantity) {}
    public record PhotoFile(String id, String contentType, String originalName, FileSystemResource resource) {}
}
