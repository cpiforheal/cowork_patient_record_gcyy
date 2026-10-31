package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Admin-facing anomaly review, quality checks, matrix and threshold management. */
@Service
@Profile("mysql")
public class InventoryAdminReviewService {

    private static final Set<String> COMPLETED_DOCUMENT_STATUSES =
        Set.of("SUBMITTED", "PARTIALLY_APPROVED", "APPROVED");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryRepository repository;

    public InventoryAdminReviewService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        InventoryRepository repository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ObjectNode anomalies(LocalDate anchor, int rangeDays, String type, String departmentKey) {
        LocalDate end = anchor == null ? LocalDate.now() : anchor;
        int range = Math.max(1, Math.min(rangeDays, 7));
        LocalDate start = end.minusDays(range - 1L);
        String deptFilter = departmentKey == null ? "" : departmentKey.trim();
        String typeFilter = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        Map<String, LocalDate> expectedFrom = expectedFromByDepartment();
        List<DepartmentInfo> departments = departments();
        List<Anomaly> items = new ArrayList<>();
        Map<String, List<InstanceRow>> instanceGroups = new LinkedHashMap<>();
        for (InstanceRow row : loadInstances(start, end, deptFilter)) {
            instanceGroups.computeIfAbsent(row.departmentKey() + "|" + row.businessDate(), key -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<String, List<InstanceRow>> entry : instanceGroups.entrySet()) {
            List<InstanceRow> rows = entry.getValue();
            InstanceRow first = rows.get(0);
            LocalDate expected = expectedFrom.getOrDefault(first.departmentKey(), LocalDate.now());
            if (first.businessDate().isBefore(expected)) continue;
            ThresholdBook thresholds = thresholdBook(first.departmentKey());
            List<InstanceRow> completedDocs = new ArrayList<>();
            Map<String, List<InstanceRow>> centralized = new LinkedHashMap<>();
            int noConsumption = 0;
            for (InstanceRow row : rows) {
                if (row.documentId() == null || row.documentId().isBlank()) {
                    items.add(patientAnomaly("MISSING", "BLOCKING", row, "尚未录入耗材", null, null));
                } else {
                    String status = blank(row.documentStatus());
                    int lineCount = row.lineCount();
                    if ("DRAFT".equals(status) && lineCount == 0 && !row.noConsumption()) {
                        items.add(patientAnomaly("EMPTY_DRAFT", "BLOCKING", row, "草稿为空且未标记未使用", null, null));
                    }
                    if ("RETURNED".equals(status)) {
                        items.add(patientAnomaly("RETURNED", "BLOCKING", row, "已驳回，需科室修改后重提", null, null));
                    }
                    if (COMPLETED_DOCUMENT_STATUSES.contains(status) || row.noConsumption()) completedDocs.add(row);
                    if (row.noConsumption()) noConsumption++;
                    if (row.submittedAt() != null) {
                        LocalDate lateBoundary = row.businessDate().plusDays(1);
                        if (row.submittedAt().isAfter(lateBoundary.atStartOfDay())) {
                            items.add(patientAnomaly("LATE", "TIMELINESS", row,
                                "提交时间晚于业务日次日 0 点", row.submittedAt().toString(), lateBoundary + " 00:00"));
                        }
                        String operator = blank(row.operatorUsername()).isBlank() ? row.operatorName() : row.operatorUsername();
                        if (!operator.isBlank()) {
                            long window = row.submittedAt().toEpochSecond(java.time.ZoneOffset.UTC)
                                / Math.max(60, thresholds.centralizedWindowMinutes() * 60L);
                            centralized.computeIfAbsent(operator + "|" + window, key -> new ArrayList<>()).add(row);
                        }
                    }
                }
            }
            if (completedDocs.size() >= thresholds.noConsumptionMinDocs() && !completedDocs.isEmpty()) {
                double ratio = noConsumption * 1.0 / completedDocs.size();
                if (ratio >= thresholds.noConsumptionRatio()) {
                    items.add(groupAnomaly("NO_CONSUMPTION_RATIO", "ANOMALY", first.departmentKey(), first.departmentName(),
                        first.businessDate(), "未使用比例偏高", completedDocs,
                        String.format(Locale.ROOT, "%.0f%%", ratio * 100),
                        String.format(Locale.ROOT, "≥%.0f%%", thresholds.noConsumptionRatio() * 100)));
                }
            }
            for (List<InstanceRow> group : centralized.values()) {
                if (group.size() >= thresholds.centralizedMinDocs()) {
                    items.add(groupAnomaly("CENTRALIZED", "TIMELINESS", first.departmentKey(), first.departmentName(),
                        first.businessDate(), "短时间集中补录", group,
                        group.size() + " 人", "≥" + thresholds.centralizedMinDocs() + " 人/" + thresholds.centralizedWindowMinutes() + " 分钟"));
                }
            }
        }
        if (!deptFilter.isBlank()) {
            DepartmentInfo selected = departments.stream().filter(d -> d.key().equals(deptFilter)).findFirst().orElse(null);
            if (selected != null && "PATIENT".equals(selected.entryMode())) {
                Set<String> claimed = new HashSet<>();
                for (List<InstanceRow> rows : instanceGroups.values()) {
                    if (rows.isEmpty() || !selected.key().equals(rows.get(0).departmentKey())) continue;
                    for (InstanceRow row : rows) claimed.add(row.businessDate() + "|" + row.patientId());
                }
                jdbcTemplate.query(
                    """
                    SELECT DATE(e.created_at) business_date,
                           COALESCE(NULLIF(e.source_patient_id, ''), e.patient_case_id, e.id) patient_id,
                           MAX(COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.patient_json, '$.patientName')), ''),
                                    NULLIF(JSON_UNQUOTE(JSON_EXTRACT(c.patient_json, '$.name')), ''), '未命名患者')) patient_name,
                           MAX(CAST(e.visit_no AS CHAR)) visit_no
                    FROM pre_ai_encounters e
                    LEFT JOIN pre_ai_patient_cases c ON c.id = e.patient_case_id
                    WHERE e.status <> 'CANCELLED' AND DATE(e.created_at) BETWEEN ? AND ?
                      AND NOT EXISTS (
                        SELECT 1 FROM inventory_patient_day_statuses st
                        WHERE st.department_key = ? AND st.business_date = DATE(e.created_at)
                          AND st.patient_id = COALESCE(NULLIF(e.source_patient_id, ''), e.patient_case_id, e.id)
                          AND st.status = 'NOT_ARRIVED'
                      )
                    GROUP BY DATE(e.created_at), COALESCE(NULLIF(e.source_patient_id, ''), e.patient_case_id, e.id)
                    ORDER BY business_date, patient_name
                    """,
                    resultSet -> {
                        while (resultSet.next()) {
                            String date = resultSet.getDate("business_date").toLocalDate().toString();
                            String patientId = resultSet.getString("patient_id");
                            if (claimed.contains(date + "|" + patientId)) continue;
                            LocalDate businessDate = LocalDate.parse(date);
                            if (businessDate.isBefore(expectedFrom.getOrDefault(selected.key(), LocalDate.now()))) continue;
                            InstanceRow row = new InstanceRow(selected.id(), selected.key(), selected.name(),
                                businessDate, "", patientId, resultSet.getString("patient_name"),
                                resultSet.getString("visit_no"), "", "NOT_STARTED", null, "", "", false, 0);
                            items.add(patientAnomaly("MISSING", "BLOCKING", row, "已登记但尚未录入耗材", null, null));
                        }
                    },
                    start, end, selected.key()
                );
            }
        }
        for (DepartmentInfo department : departments) {
            if (!"DAILY".equals(department.entryMode())) continue;
            if (!deptFilter.isBlank() && !deptFilter.equals(department.key())) continue;
            LocalDate expected = expectedFrom.getOrDefault(department.key(), LocalDate.now());
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                if (date.isBefore(expected)) continue;
                DailyReportRow report = dailyReport(department.key(), date);
                if (report == null) {
                    items.add(reportAnomaly("REPORT_MISSING", "BLOCKING", department, date, null,
                        "科室日报尚未创建", null, null));
                    continue;
                }
                if ("DRAFT".equals(report.status())) {
                    items.add(reportAnomaly("REPORT_DRAFT", "BLOCKING", department, date, report,
                        "科室日报仍是草稿", null, null));
                }
                if ("RETURNED".equals(report.status())) {
                    items.add(reportAnomaly("REPORT_RETURNED", "BLOCKING", department, date, report,
                        "科室日报已驳回", null, null));
                }
                if (report.submittedAt() != null && report.submittedAt().isAfter(date.plusDays(1).atStartOfDay())) {
                    items.add(reportAnomaly("LATE", "TIMELINESS", department, date, report,
                        "提交时间晚于业务日次日 0 点", report.submittedAt().toString(), date.plusDays(1) + " 00:00"));
                }
            }
        }
        List<Anomaly> filtered = items.stream()
            .filter(item -> typeFilter.isBlank() || item.severity().equals(typeFilter) || item.type().equals(typeFilter))
            .sorted(Comparator
                .comparingInt((Anomaly item) -> severityRank(item.severity()))
                .thenComparing(Anomaly::departmentName)
                .thenComparing(Anomaly::businessDate, Comparator.reverseOrder())
                .thenComparing(item -> blank(item.patientName())))
            .toList();
        ArrayNode rows = objectMapper.createArrayNode();
        int blocking = 0, anomalyCount = 0, timeliness = 0;
        Map<String, Integer> byType = new HashMap<>();
        for (int index = 0; index < filtered.size(); index++) {
            Anomaly item = filtered.get(index);
            ObjectNode row = anomalyNode(item, index);
            rows.add(row);
            switch (item.severity()) {
                case "BLOCKING" -> blocking++;
                case "ANOMALY" -> anomalyCount++;
                default -> timeliness++;
            }
            byType.merge(item.type(), 1, Integer::sum);
        }
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("total", filtered.size());
        summary.put("blocking", blocking);
        summary.put("anomaly", anomalyCount);
        summary.put("timeliness", timeliness);
        summary.set("byType", objectMapper.valueToTree(byType));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("date", end.toString());
        result.put("range", range);
        result.put("from", start.toString());
        result.put("to", end.toString());
        result.set("summary", summary);
        result.set("items", rows);
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode dataQuality() {
        int openingUnconfirmed = count(
            "SELECT COUNT(*) FROM inventory_locations WHERE location_type = 'DEPARTMENT' AND status = 'ACTIVE' AND opening_confirmed = 0"
        );
        List<RuleMapping> rules = jdbcTemplate.query(
            """
            SELECT r.department_key, r.department_name, r.material_name, i.name item_name
            FROM inventory_quota_rules r
            JOIN inventory_quota_versions v ON v.id = r.version_id AND v.status = 'ACTIVE'
            LEFT JOIN inventory_items i ON i.id = r.item_id
            WHERE r.enabled = 1
            """,
            (resultSet, rowNum) -> new RuleMapping(
                resultSet.getString("department_key"), resultSet.getString("department_name"),
                resultSet.getString("material_name"), resultSet.getString("item_name")
            )
        );
        int unmapped = 0;
        int conflicts = 0;
        for (RuleMapping rule : rules) {
            if (rule.itemName() == null || rule.itemName().isBlank()) {
                unmapped++;
            } else if (!normalizeMaterial(rule.materialName()).equals(normalizeMaterial(rule.itemName()))) {
                conflicts++;
            }
        }
        int commonMissing = commonMissingDepartments();
        ArrayNode items = objectMapper.createArrayNode();
        items.add(qualityItem("OPENING_STOCK", "期初库存未确认", openingUnconfirmed, "DANGER",
            "科室期初未确认，审核扣库会被阻断", "/inventory-system/stock"));
        items.add(qualityItem("MAPPING", "映射冲突/未映射", conflicts + unmapped, conflicts > 0 ? "DANGER" : "WARNING",
            "冲突 " + conflicts + " 条，未映射 " + unmapped + " 条", "/inventory-system/department-materials"));
        items.add(qualityItem("COMMON_ITEMS", "常用项为 0 的科室", commonMissing, "WARNING",
            "科室没有常用项，快捷区为空", "/inventory-system/packages"));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("ready", openingUnconfirmed == 0 && conflicts == 0 && unmapped == 0 && commonMissing == 0);
        result.set("items", items);
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode matrix(String departmentKey, LocalDate from, LocalDate to, String metric) {
        DepartmentInfo department = department(departmentKey);
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(13) : from;
        if (start.isAfter(end)) throw new HttpStatusException("开始日期不能晚于结束日期");
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > 62) throw new HttpStatusException("矩阵最多查看 62 天");
        String mode = "PER_PATIENT".equalsIgnoreCase(metric) ? "PER_PATIENT" : "QUANTITY";
        ArrayNode dates = objectMapper.createArrayNode();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) dates.add(date.toString());
        List<MatrixCell> cells = jdbcTemplate.query(
            """
            SELECT item_id, MAX(item_name) item_name, MAX(unit) unit, business_date, SUM(quantity) quantity
            FROM (
              SELECT l.item_id, COALESCE(l.item_name_snapshot, i.name) item_name,
                     COALESCE(l.unit_snapshot, i.unit) unit, si.business_date, l.actual_quantity quantity
              FROM inventory_patient_consumption_lines l
              JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
              JOIN inventory_service_instances si ON si.id = d.service_instance_id
              LEFT JOIN inventory_items i ON i.id = l.item_id
              WHERE si.business_date BETWEEN ? AND ? AND si.department_key = ? AND l.review_status <> 'REJECTED'
              UNION ALL
              SELECT rl.item_id, COALESCE(rl.item_name_snapshot, i.name),
                     COALESCE(rl.unit_snapshot, i.unit), r.business_date, rl.actual_quantity
              FROM inventory_department_daily_report_lines rl
              JOIN inventory_department_daily_reports r ON r.id = rl.report_id
              LEFT JOIN inventory_items i ON i.id = rl.item_id
              WHERE r.business_date BETWEEN ? AND ? AND r.department_key = ?
                AND rl.review_status <> 'REJECTED' AND rl.actual_quantity > 0
            ) line_rows
            GROUP BY item_id, business_date
            ORDER BY item_name
            """,
            (resultSet, rowNum) -> new MatrixCell(
                resultSet.getString("item_id"), resultSet.getString("item_name"), resultSet.getString("unit"),
                resultSet.getDate("business_date").toLocalDate(), resultSet.getBigDecimal("quantity")
            ),
            start, end, department.key(), start, end, department.key()
        );
        Map<String, ObjectNode> itemMap = new LinkedHashMap<>();
        for (MatrixCell cell : cells) {
            ObjectNode row = itemMap.computeIfAbsent(cell.itemId(), key -> {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("itemId", cell.itemId());
                node.put("itemName", cell.itemName());
                node.put("unit", cell.unit());
                node.set("cells", objectMapper.createObjectNode());
                return node;
            });
            ((ObjectNode) row.path("cells")).put(cell.date().toString(), cell.quantity());
        }
        Map<String, BigDecimal> patientCounts = patientCountsByDate(department.key(), start, end);
        Map<String, BigDecimal> reportDays = reportDaysByDate(department.key(), start, end);
        ArrayNode rows = objectMapper.createArrayNode();
        for (ObjectNode row : itemMap.values()) {
            ObjectNode normalized = objectMapper.createObjectNode();
            normalized.put("itemId", row.path("itemId").asText());
            normalized.put("itemName", row.path("itemName").asText());
            normalized.put("unit", row.path("unit").asText());
            ObjectNode sourceCells = (ObjectNode) row.path("cells");
            ObjectNode displayCells = normalized.putObject("cells");
            sourceCells.fields().forEachRemaining(entry -> {
                BigDecimal quantity = entry.getValue().decimalValue();
                if ("PER_PATIENT".equals(mode)) {
                    BigDecimal denominator = "PATIENT".equals(department.entryMode())
                        ? patientCounts.getOrDefault(entry.getKey(), BigDecimal.ZERO)
                        : reportDays.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                    displayCells.put(entry.getKey(), denominator.signum() == 0 ? null : quantity.divide(denominator, 2, RoundingMode.HALF_UP));
                } else {
                    displayCells.put(entry.getKey(), quantity);
                }
            });
            rows.add(normalized);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("departmentKey", department.key());
        result.put("departmentName", department.name());
        result.put("entryMode", department.entryMode());
        result.put("metric", mode);
        result.put("from", start.toString());
        result.put("to", end.toString());
        result.set("dates", dates);
        result.set("patientCounts", objectMapper.valueToTree(patientCounts));
        result.set("reportDays", objectMapper.valueToTree(reportDays));
        result.set("items", rows);
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode trends(LocalDate periodStart, LocalDate periodEnd, String departmentKey) {
        LocalDate end = periodEnd == null ? LocalDate.now() : periodEnd;
        LocalDate start = periodStart == null ? end.minusDays(6) : periodStart;
        if (start.isAfter(end)) throw new HttpStatusException("开始日期不能晚于结束日期");
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        LocalDate previousEnd = start.minusDays(1);
        LocalDate previousStart = previousEnd.minusDays(days - 1);
        String dept = departmentKey == null ? "" : departmentKey.trim();
        ThresholdBook thresholds = thresholdBook(dept);
        Map<String, BigDecimal> current = itemQuantities(start, end, dept);
        Map<String, BigDecimal> previous = itemQuantities(previousStart, previousEnd, dept);
        Map<String, String[]> names = itemNames(current.keySet());
        Map<String, BigDecimal> currentPatients = patientCounts(start, end, dept);
        Map<String, BigDecimal> previousPatients = patientCounts(previousStart, previousEnd, dept);
        ArrayNode items = objectMapper.createArrayNode();
        ArrayNode anomalies = objectMapper.createArrayNode();
        Set<String> keys = new HashSet<>(current.keySet());
        keys.addAll(previous.keySet());
        for (String itemId : keys) {
            BigDecimal now = current.getOrDefault(itemId, BigDecimal.ZERO);
            BigDecimal before = previous.getOrDefault(itemId, BigDecimal.ZERO);
            BigDecimal delta = now.subtract(before);
            double ratio = before.signum() == 0
                ? (now.signum() == 0 ? 0 : 999)
                : delta.doubleValue() / before.doubleValue();
            String[] item = names.getOrDefault(itemId, new String[] { itemId, "" });
            ObjectNode row = items.addObject();
            row.put("itemId", itemId);
            row.put("itemName", item[0]);
            row.put("unit", item[1]);
            row.put("currentQuantity", now);
            row.put("previousQuantity", before);
            row.put("delta", delta);
            row.put("ratio", ratio == 999 ? null : BigDecimal.valueOf(ratio).setScale(4, RoundingMode.HALF_UP));
            row.put("currentPerPatient", normalize(now, total(currentPatients)));
            row.put("previousPerPatient", normalize(before, total(previousPatients)));
            if (delta.compareTo(BigDecimal.valueOf(thresholds.trendMinDelta())) >= 0
                && (before.signum() == 0 || ratio >= thresholds.trendRatio())) {
                ObjectNode anomaly = anomalies.addObject();
                anomaly.put("type", "TREND");
                anomaly.put("itemId", itemId);
                anomaly.put("itemName", item[0]);
                anomaly.put("currentQuantity", now);
                anomaly.put("previousQuantity", before);
                anomaly.put("delta", delta);
                anomaly.put("ratio", ratio == 999 ? null : BigDecimal.valueOf(ratio).setScale(4, RoundingMode.HALF_UP));
                anomaly.put("reason", "周环比超过 " + Math.round(thresholds.trendRatio() * 100) + "% 且增量 ≥" + thresholds.trendMinDelta());
            }
        }
        if (dept.isBlank()) {
            ArrayNode cross = crossDepartmentDeviations(start, end, thresholds);
            cross.forEach(anomalies::add);
        }
        ArrayNode preset = presetDeviations(start, end, dept, thresholds);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("from", start.toString());
        result.put("to", end.toString());
        result.put("previousFrom", previousStart.toString());
        result.put("previousTo", previousEnd.toString());
        result.set("items", items);
        result.set("anomalies", anomalies);
        result.set("presetDeviations", preset);
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode thresholds() {
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT scope, department_key, item_id, threshold_key, threshold_value, updated_by, updated_at FROM inventory_review_thresholds ORDER BY scope, department_key, threshold_key",
            (resultSet, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("scope", resultSet.getString("scope"));
                row.put("departmentKey", resultSet.getString("department_key"));
                row.put("itemId", resultSet.getString("item_id"));
                row.put("key", resultSet.getString("threshold_key"));
                row.put("value", resultSet.getBigDecimal("threshold_value"));
                row.put("updatedBy", resultSet.getString("updated_by"));
                Timestamp updatedAt = resultSet.getTimestamp("updated_at");
                row.put("updatedAt", updatedAt == null ? "" : updatedAt.toLocalDateTime().toString());
                return row;
            }
        );
        ArrayNode items = objectMapper.createArrayNode();
        rows.forEach(items::add);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("items", items);
        result.set("departments", objectMapper.valueToTree(departments()));
        return result;
    }

    @Transactional
    public ObjectNode saveThresholds(JsonNode payload, SessionUser user) {
        JsonNode items = payload == null ? null : payload.path("items");
        if (items == null || !items.isArray()) {
            throw new HttpStatusException("阈值数据格式不正确");
        }
        for (JsonNode item : items) {
            String scope = text(item, "scope").toUpperCase(Locale.ROOT);
            if (!Set.of("GLOBAL", "DEPARTMENT", "ITEM").contains(scope)) {
                throw new HttpStatusException("阈值范围不正确");
            }
            String departmentKey = text(item, "departmentKey");
            String itemId = text(item, "itemId");
            String key = text(item, "key");
            BigDecimal value = item.path("value").decimalValue();
            if (key.isBlank() || value == null) throw new HttpStatusException("阈值键和值不能为空");
            jdbcTemplate.update(
                """
                INSERT INTO inventory_review_thresholds
                  (id, scope, department_key, item_id, threshold_key, threshold_value, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE threshold_value = VALUES(threshold_value), updated_by = VALUES(updated_by)
                """,
                "review-threshold-" + UUID.randomUUID(), scope, departmentKey == null ? "" : departmentKey,
                itemId == null ? "" : itemId, key, value, user.name()
            );
        }
        repository.log(user.name(), "更新管理审查阈值", "inventory_review_threshold", "thresholds",
            "更新 " + items.size() + " 项阈值");
        return thresholds();
    }

    // ---- helpers ---------------------------------------------------------

    private List<InstanceRow> loadInstances(LocalDate start, LocalDate end, String deptFilter) {
        return jdbcTemplate.query(
            """
            SELECT si.department_id, si.department_key, si.department_name, si.business_date,
                   si.id service_instance_id, si.patient_id, si.patient_name, si.visit_no,
                   d.id document_id, d.status document_status, d.submitted_at,
                   d.operator_username, d.operator_name, d.no_consumption,
                   COALESCE((SELECT COUNT(*) FROM inventory_patient_consumption_lines l WHERE l.document_id = d.id), 0) line_count
            FROM inventory_service_instances si
            LEFT JOIN inventory_patient_consumption_documents d ON d.service_instance_id = si.id
            WHERE si.business_date BETWEEN ? AND ? AND (? = '' OR si.department_key = ?)
              AND NOT EXISTS (
                SELECT 1 FROM inventory_patient_day_statuses st
                WHERE st.department_key = si.department_key AND st.business_date = si.business_date
                  AND st.patient_id = si.patient_id AND st.status = 'NOT_ARRIVED'
              )
            ORDER BY si.department_key, si.business_date, si.patient_name
            """,
            (resultSet, rowNum) -> new InstanceRow(
                resultSet.getString("department_id"), resultSet.getString("department_key"),
                resultSet.getString("department_name"), resultSet.getDate("business_date").toLocalDate(),
                resultSet.getString("service_instance_id"), resultSet.getString("patient_id"),
                resultSet.getString("patient_name"), resultSet.getString("visit_no"),
                resultSet.getString("document_id"), resultSet.getString("document_status"),
                resultSet.getTimestamp("submitted_at") == null ? null : resultSet.getTimestamp("submitted_at").toLocalDateTime(),
                resultSet.getString("operator_username"), resultSet.getString("operator_name"),
                resultSet.getBoolean("no_consumption"), resultSet.getInt("line_count")
            ),
            start, end, deptFilter, deptFilter
        );
    }

    private DailyReportRow dailyReport(String departmentKey, LocalDate date) {
        return jdbcTemplate.query(
            "SELECT * FROM inventory_department_daily_reports WHERE department_key = ? AND business_date = ?",
            resultSet -> {
                if (!resultSet.next()) return null;
                Timestamp submittedAt = resultSet.getTimestamp("submitted_at");
                return new DailyReportRow(resultSet.getString("id"), resultSet.getString("department_key"),
                    resultSet.getString("department_name"), resultSet.getDate("business_date").toLocalDate(),
                    resultSet.getString("status"), submittedAt == null ? null : submittedAt.toLocalDateTime());
            },
            departmentKey, date
        );
    }

    private Anomaly patientAnomaly(String type, String severity, InstanceRow row, String reason, String current, String threshold) {
        return new Anomaly(UUID.randomUUID().toString(), type, severity, row.departmentKey(), row.departmentName(),
            row.businessDate(), row.patientId(), row.patientName(), row.visitNo(), row.serviceInstanceId(),
            row.documentId(), "", row.documentStatus(), reason, current, threshold, row.submittedAt(),
            row.operatorName(), List.of(row));
    }

    private Anomaly groupAnomaly(String type, String severity, String departmentKey, String departmentName,
                                 LocalDate date, String reason, List<InstanceRow> rows, String current, String threshold) {
        String name = rows.size() == 1 ? rows.get(0).patientName() : rows.size() + " 位患者";
        return new Anomaly(UUID.randomUUID().toString(), type, severity, departmentKey, departmentName, date,
            "", name, "", "", "", "", "", reason, current, threshold,
            rows.stream().map(InstanceRow::submittedAt).filter(java.util.Objects::nonNull).findFirst().orElse(null),
            rows.isEmpty() ? "" : blank(rows.get(0).operatorName()), rows);
    }

    private Anomaly reportAnomaly(String type, String severity, DepartmentInfo department, LocalDate date,
                                  DailyReportRow report, String reason, String current, String threshold) {
        return new Anomaly(UUID.randomUUID().toString(), type, severity, department.key(), department.name(), date,
            "", "科室日报", "", "", "", report == null ? "" : report.id(), report == null ? "" : report.status(),
            reason, current, threshold, report == null ? null : report.submittedAt(), "", List.of());
    }

    private ObjectNode anomalyNode(Anomaly item, int index) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", item.id());
        row.put("type", item.type());
        row.put("severity", item.severity());
        row.put("departmentKey", item.departmentKey());
        row.put("departmentName", item.departmentName());
        row.put("businessDate", item.businessDate().toString());
        row.put("patientId", item.patientId());
        row.put("patientName", item.patientName());
        row.put("visitNo", item.visitNo());
        row.put("serviceInstanceId", item.serviceInstanceId());
        row.put("documentId", item.documentId());
        row.put("reportId", item.reportId());
        row.put("status", item.status());
        row.put("reason", item.reason());
        row.put("currentValue", item.currentValue());
        row.put("thresholdValue", item.thresholdValue());
        row.put("submittedAt", item.submittedAt() == null ? "" : item.submittedAt().toString());
        row.put("operatorName", item.operatorName());
        ArrayNode patients = row.putArray("patients");
        for (InstanceRow patient : item.patients()) {
            ObjectNode patientNode = patients.addObject();
            patientNode.put("serviceInstanceId", patient.serviceInstanceId());
            patientNode.put("patientName", patient.patientName());
            patientNode.put("visitNo", patient.visitNo());
            patientNode.put("status", blank(patient.documentStatus()).isBlank() ? "NOT_STARTED" : patient.documentStatus());
            patientNode.put("submittedAt", patient.submittedAt() == null ? "" : patient.submittedAt().toString());
        }
        return row;
    }

    private Map<String, BigDecimal> itemQuantities(LocalDate start, LocalDate end, String dept) {
        Map<String, BigDecimal> result = new HashMap<>();
        jdbcTemplate.query(
            """
            SELECT item_id, SUM(quantity) quantity FROM (
              SELECT l.item_id, l.actual_quantity quantity
              FROM inventory_patient_consumption_lines l
              JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
              JOIN inventory_service_instances si ON si.id = d.service_instance_id
              WHERE si.business_date BETWEEN ? AND ? AND (? = '' OR si.department_key = ?)
                AND l.review_status <> 'REJECTED'
              UNION ALL
              SELECT rl.item_id, rl.actual_quantity
              FROM inventory_department_daily_report_lines rl
              JOIN inventory_department_daily_reports r ON r.id = rl.report_id
              WHERE r.business_date BETWEEN ? AND ? AND (? = '' OR r.department_key = ?)
                AND rl.review_status <> 'REJECTED' AND rl.actual_quantity > 0
            ) line_rows GROUP BY item_id
            """,
            resultSet -> {
                while (resultSet.next()) result.put(resultSet.getString("item_id"), resultSet.getBigDecimal("quantity"));
            },
            start, end, dept, dept, start, end, dept, dept
        );
        return result;
    }

    private Map<String, String[]> itemNames(Set<String> ids) {
        if (ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        Map<String, String[]> result = new HashMap<>();
        jdbcTemplate.query(
            "SELECT id, name, unit FROM inventory_items WHERE id IN (" + placeholders + ")",
            resultSet -> {
                while (resultSet.next()) result.put(resultSet.getString("id"),
                    new String[] { resultSet.getString("name"), resultSet.getString("unit") });
            },
            ids.toArray()
        );
        return result;
    }

    private Map<String, BigDecimal> patientCounts(LocalDate start, LocalDate end, String dept) {
        Map<String, BigDecimal> result = new HashMap<>();
        jdbcTemplate.query(
            """
            SELECT business_date, COUNT(DISTINCT patient_id) c
            FROM inventory_service_instances
            WHERE business_date BETWEEN ? AND ? AND (? = '' OR department_key = ?)
            GROUP BY business_date
            """,
            resultSet -> {
                while (resultSet.next()) result.put(resultSet.getDate("business_date").toLocalDate().toString(),
                    BigDecimal.valueOf(resultSet.getInt("c")));
            },
            start, end, dept, dept
        );
        return result;
    }

    private Map<String, BigDecimal> patientCountsByDate(String departmentKey, LocalDate start, LocalDate end) {
        Map<String, BigDecimal> result = new HashMap<>();
        jdbcTemplate.query(
            "SELECT business_date, COUNT(DISTINCT patient_id) c FROM inventory_service_instances WHERE department_key = ? AND business_date BETWEEN ? AND ? GROUP BY business_date",
            resultSet -> {
                while (resultSet.next()) result.put(resultSet.getDate("business_date").toLocalDate().toString(),
                    BigDecimal.valueOf(resultSet.getInt("c")));
            },
            departmentKey, start, end
        );
        return result;
    }

    private Map<String, BigDecimal> reportDaysByDate(String departmentKey, LocalDate start, LocalDate end) {
        Map<String, BigDecimal> result = new HashMap<>();
        jdbcTemplate.query(
            "SELECT business_date, COUNT(*) c FROM inventory_department_daily_reports WHERE department_key = ? AND business_date BETWEEN ? AND ? AND status IN ('SUBMITTED','PARTIALLY_APPROVED','APPROVED') GROUP BY business_date",
            resultSet -> {
                while (resultSet.next()) result.put(resultSet.getDate("business_date").toLocalDate().toString(),
                    BigDecimal.valueOf(resultSet.getInt("c")));
            },
            departmentKey, start, end
        );
        return result;
    }

    private ArrayNode crossDepartmentDeviations(LocalDate start, LocalDate end, ThresholdBook thresholds) {
        List<CrossDeptRow> rows = jdbcTemplate.query(
            """
            SELECT si.department_key, si.department_name, l.item_id,
                   MAX(COALESCE(l.item_name_snapshot, i.name)) item_name,
                   SUM(l.actual_quantity) quantity,
                   COUNT(DISTINCT si.patient_id) patients
            FROM inventory_patient_consumption_lines l
            JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
            JOIN inventory_service_instances si ON si.id = d.service_instance_id
            LEFT JOIN inventory_items i ON i.id = l.item_id
            WHERE si.business_date BETWEEN ? AND ? AND l.review_status <> 'REJECTED'
            GROUP BY si.department_key, si.department_name, l.item_id
            """,
            (resultSet, rowNum) -> new CrossDeptRow(
                resultSet.getString("department_key"), resultSet.getString("department_name"),
                resultSet.getString("item_id"), resultSet.getString("item_name"),
                resultSet.getBigDecimal("quantity"), resultSet.getInt("patients")
            ),
            start, end
        );
        Map<String, List<CrossDeptRow>> byItem = new LinkedHashMap<>();
        for (CrossDeptRow row : rows) {
            if (row.patients() >= thresholds.crossDeptMinBase()) {
                byItem.computeIfAbsent(row.itemId(), key -> new ArrayList<>()).add(row);
            }
        }
        ArrayNode anomalies = objectMapper.createArrayNode();
        for (List<CrossDeptRow> itemRows : byItem.values()) {
            if (itemRows.size() < 2) continue;
            List<BigDecimal> values = itemRows.stream()
                .map(row -> row.quantity().divide(BigDecimal.valueOf(row.patients()), 4, RoundingMode.HALF_UP))
                .sorted()
                .toList();
            BigDecimal median = values.size() % 2 == 0
                ? values.get(values.size() / 2 - 1).add(values.get(values.size() / 2)).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP)
                : values.get(values.size() / 2);
            if (median.signum() <= 0) continue;
            for (CrossDeptRow row : itemRows) {
                BigDecimal perPatient = row.quantity().divide(BigDecimal.valueOf(row.patients()), 4, RoundingMode.HALF_UP);
                if (perPatient.doubleValue() < median.doubleValue() * thresholds.crossDeptMultiple()) continue;
                ObjectNode anomaly = anomalies.addObject();
                anomaly.put("type", "CROSS_DEPT");
                anomaly.put("departmentKey", row.departmentKey());
                anomaly.put("departmentName", row.departmentName());
                anomaly.put("itemId", row.itemId());
                anomaly.put("itemName", row.itemName());
                anomaly.put("currentPerPatient", perPatient);
                anomaly.put("medianPerPatient", median);
                anomaly.put("patients", row.patients());
                anomaly.put("reason", "科室人均用量超过科室中位数的 " + thresholds.crossDeptMultiple() + " 倍");
            }
        }
        return anomalies;
    }

    private ArrayNode presetDeviations(LocalDate start, LocalDate end, String dept, ThresholdBook thresholds) {
        return jdbcTemplate.query(
            """
            SELECT d.department_key, d.department_name,
                   COUNT(DISTINCT d.id) docs,
                   COUNT(l.id) line_count,
                   SUM(CASE WHEN l.source = 'MANUAL'
                         OR (l.preset_quantity IS NOT NULL AND l.preset_quantity > 0
                             AND ABS(l.actual_quantity - l.preset_quantity) / l.preset_quantity >= ?)
                       THEN 1 ELSE 0 END) deviation_lines
            FROM inventory_patient_consumption_documents d
            JOIN inventory_patient_consumption_lines l ON l.document_id = d.id
            JOIN inventory_service_instances si ON si.id = d.service_instance_id
            WHERE si.business_date BETWEEN ? AND ? AND (? = '' OR d.department_key = ?)
            GROUP BY d.department_key, d.department_name
            """,
            resultSet -> {
                ArrayNode list = objectMapper.createArrayNode();
                while (resultSet.next()) {
                    int docs = resultSet.getInt("docs");
                    int lines = resultSet.getInt("line_count");
                    int deviations = resultSet.getInt("deviation_lines");
                    if (docs < thresholds.presetMinDocs() || lines == 0) continue;
                    double ratio = deviations * 1.0 / lines;
                    if (ratio < thresholds.presetDeviationRatio()) continue;
                    ObjectNode row = list.addObject();
                    row.put("type", "PRESET_DEVIATION");
                    row.put("departmentKey", resultSet.getString("department_key"));
                    row.put("departmentName", resultSet.getString("department_name"));
                    row.put("docs", docs);
                    row.put("deviationLines", deviations);
                    row.put("lines", lines);
                    row.put("ratio", BigDecimal.valueOf(ratio).setScale(4, RoundingMode.HALF_UP));
                    row.put("reason", "手工追加/数量偏离预置的比例偏高");
                }
                return list;
            },
            thresholds.presetDeviationRatio(), start, end, dept, dept
        );
    }

    private int commonMissingDepartments() {
        Integer value = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM (
              SELECT r.department_key
              FROM inventory_quota_rules r
              JOIN inventory_quota_versions v ON v.id = r.version_id AND v.status = 'ACTIVE'
              WHERE r.enabled = 1
              GROUP BY r.department_key
              HAVING COALESCE(SUM(r.common_item), 0) = 0
            ) missing
            """,
            Integer.class
        );
        return value == null ? 0 : value;
    }

    private ObjectNode qualityItem(String key, String label, int count, String severity, String detail, String route) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("key", key);
        row.put("label", label);
        row.put("count", count);
        row.put("severity", count == 0 ? "OK" : severity);
        row.put("detail", detail);
        row.put("route", route);
        return row;
    }

    private Map<String, LocalDate> expectedFromByDepartment() {
        Map<String, LocalDate> result = new HashMap<>();
        jdbcTemplate.query(
            "SELECT department_key, MIN(business_date) earliest FROM inventory_service_instances GROUP BY department_key",
            resultSet -> {
                while (resultSet.next()) result.put(resultSet.getString("department_key"),
                    resultSet.getDate("earliest").toLocalDate());
            }
        );
        jdbcTemplate.query(
            "SELECT department_key, MIN(business_date) earliest FROM inventory_department_daily_reports GROUP BY department_key",
            resultSet -> {
                while (resultSet.next()) result.putIfAbsent(resultSet.getString("department_key"),
                    resultSet.getDate("earliest").toLocalDate());
            }
        );
        return result;
    }

    private List<DepartmentInfo> departments() {
        return jdbcTemplate.query(
            "SELECT id, department_key, name, entry_mode FROM inventory_departments WHERE status = 'ACTIVE' ORDER BY display_order",
            (resultSet, rowNum) -> new DepartmentInfo(
                resultSet.getString("id"), resultSet.getString("department_key"),
                resultSet.getString("name"), resultSet.getString("entry_mode")
            )
        );
    }

    private DepartmentInfo department(String key) {
        String target = key == null ? "" : key.trim();
        if (target.isBlank()) {
            return departments().stream().filter(d -> "PATIENT".equals(d.entryMode())).findFirst()
                .orElseThrow(() -> new HttpStatusException("没有可用科室"));
        }
        return departments().stream().filter(d -> d.key().equals(target)).findFirst()
            .orElseThrow(() -> new HttpStatusException("未知科室：" + target));
    }

    private ThresholdBook thresholdBook(String departmentKey) {
        String dept = departmentKey == null ? "" : departmentKey;
        List<ThresholdRow> rows = jdbcTemplate.query(
            "SELECT scope, department_key, item_id, threshold_key, threshold_value FROM inventory_review_thresholds",
            (resultSet, rowNum) -> new ThresholdRow(
                resultSet.getString("scope"), resultSet.getString("department_key"),
                resultSet.getString("item_id"), resultSet.getString("threshold_key"),
                resultSet.getBigDecimal("threshold_value")
            )
        );
        BigDecimal ratio = value(rows, dept, "", "NO_CONSUMPTION_RATIO", new BigDecimal("0.5"));
        int minDocs = value(rows, dept, "", "NO_CONSUMPTION_MIN_DOCS", new BigDecimal("5")).intValue();
        double trendRatio = value(rows, dept, "", "TREND_WOW_RATIO", new BigDecimal("0.3")).doubleValue();
        double minDelta = value(rows, dept, "", "TREND_MIN_DELTA", new BigDecimal("5")).doubleValue();
        double crossMultiple = value(rows, dept, "", "CROSS_DEPT_MULTIPLE", new BigDecimal("2")).doubleValue();
        int crossBase = value(rows, dept, "", "CROSS_DEPT_MIN_BASE", new BigDecimal("5")).intValue();
        double presetRatio = value(rows, dept, "", "PRESET_DEVIATION_RATIO", new BigDecimal("0.3")).doubleValue();
        int presetDocs = value(rows, dept, "", "PRESET_DEVIATION_MIN_DOCS", new BigDecimal("5")).intValue();
        int lateHour = value(rows, dept, "", "LATE_HOUR", BigDecimal.ZERO).intValue();
        int window = value(rows, dept, "", "CENTRALIZED_WINDOW_MINUTES", new BigDecimal("30")).intValue();
        int centralizedMin = value(rows, dept, "", "CENTRALIZED_MIN_DOCS", new BigDecimal("5")).intValue();
        return new ThresholdBook(ratio.doubleValue(), minDocs, trendRatio, minDelta, crossMultiple,
            crossBase, presetRatio, presetDocs, lateHour, window, centralizedMin);
    }

    private BigDecimal value(List<ThresholdRow> rows, String dept, String item, String key, BigDecimal fallback) {
        BigDecimal itemValue = null, deptValue = null, global = null;
        String targetItem = item == null ? "" : item;
        for (ThresholdRow row : rows) {
            if (!key.equals(row.key())) continue;
            if ("ITEM".equals(row.scope()) && !targetItem.isBlank() && targetItem.equals(row.itemId())) itemValue = row.value();
            else if ("DEPARTMENT".equals(row.scope()) && dept != null && !dept.isBlank() && dept.equals(row.departmentKey())) deptValue = row.value();
            else if ("GLOBAL".equals(row.scope())) global = row.value();
        }
        if (itemValue != null) return itemValue;
        if (deptValue != null) return deptValue;
        return global == null ? fallback : global;
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "BLOCKING" -> 0;
            case "ANOMALY" -> 1;
            default -> 2;
        };
    }

    private static String normalizeMaterial(String value) {
        return value == null ? "" : value.replaceAll("[（(].*?[）)]", "").replaceAll("\\s", "").trim();
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }

    private static BigDecimal total(Map<String, BigDecimal> values) {
        return values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal normalize(BigDecimal value, BigDecimal denominator) {
        return denominator.signum() == 0 ? null : value.divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private static final class HttpStatusException extends ResponseStatusException {
        HttpStatusException(String message) {
            super(HttpStatus.BAD_REQUEST, message);
        }
    }

    private record DepartmentInfo(String id, String key, String name, String entryMode) {}

    private record InstanceRow(
        String departmentId, String departmentKey, String departmentName, LocalDate businessDate,
        String serviceInstanceId, String patientId, String patientName, String visitNo,
        String documentId, String documentStatus, LocalDateTime submittedAt,
        String operatorUsername, String operatorName, boolean noConsumption, int lineCount
    ) {}

    private record DailyReportRow(String id, String departmentKey, String departmentName, LocalDate businessDate,
                                  String status, LocalDateTime submittedAt) {}

    private record Anomaly(
        String id, String type, String severity, String departmentKey, String departmentName, LocalDate businessDate,
        String patientId, String patientName, String visitNo, String serviceInstanceId, String documentId,
        String reportId, String status, String reason, String currentValue, String thresholdValue,
        LocalDateTime submittedAt, String operatorName, List<InstanceRow> patients
    ) {}

    private record MatrixCell(String itemId, String itemName, String unit, LocalDate date, BigDecimal quantity) {}

    private record CrossDeptRow(String departmentKey, String departmentName, String itemId, String itemName,
                                BigDecimal quantity, int patients) {}

    private record ThresholdRow(String scope, String departmentKey, String itemId, String key, BigDecimal value) {}

    private record RuleMapping(String departmentKey, String departmentName, String materialName, String itemName) {}

    private record ThresholdBook(
        double noConsumptionRatio, int noConsumptionMinDocs, double trendRatio, double trendMinDelta,
        double crossDeptMultiple, int crossDeptMinBase, double presetDeviationRatio, int presetMinDocs,
        int lateHour, int centralizedWindowMinutes, int centralizedMinDocs
    ) {}
}
