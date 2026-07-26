package com.coshare.patientrecord.inventory.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("mysql")
public class InventoryWeeklyRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public InventoryWeeklyRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public ArrayNode standards() {
        return queryJson("""
            SELECT s.id, s.standard_code standardCode, s.version, s.name, s.status,
                   s.effective_week effectiveWeek, s.expires_week expiresWeek,
                   s.hospital_timezone hospitalTimezone, s.policy_json policy,
                   s.published_by publishedBy, s.published_by_role publishedByRole,
                   s.published_at publishedAt, s.created_by createdBy,
                   s.created_by_role createdByRole, s.created_at createdAt, s.updated_at updatedAt,
                   (SELECT COUNT(*) FROM inventory_weekly_standard_lines l WHERE l.standard_id = s.id) lineCount
            FROM inventory_weekly_standards s
            ORDER BY s.standard_code, s.version DESC
            """);
    }

    public ObjectNode standard(String id) {
        ObjectNode result = one("""
            SELECT id, standard_code standardCode, version, name, status,
                   effective_week effectiveWeek, expires_week expiresWeek,
                   hospital_timezone hospitalTimezone, policy_json policy,
                   published_by publishedBy, published_by_role publishedByRole,
                   published_at publishedAt, created_by createdBy,
                   created_by_role createdByRole, created_at createdAt, updated_at updatedAt
            FROM inventory_weekly_standards WHERE id = ?
            """, id);
        if (result == null) throw new IllegalArgumentException("周度标准版本不存在");
        result.set("lines", queryJson("""
            SELECT id, standard_id standardId, department_id departmentId,
                   department_name_snapshot departmentName, care_type careType, item_id itemId,
                   item_name_snapshot itemName, item_unit_snapshot itemUnit,
                   expected_quantity expectedQuantity, safety_stock_quantity safetyStockQuantity,
                   calculation_policy calculationPolicy, line_policy_json linePolicy, status, created_at createdAt
            FROM inventory_weekly_standard_lines WHERE standard_id = ? ORDER BY department_name_snapshot, care_type, item_name_snapshot
            """, id));
        parseJson(result, "policy");
        for (JsonNode line : result.withArray("lines")) parseJson((ObjectNode) line, "linePolicy");
        return result;
    }

    public int nextVersion(String standardCode) {
        Integer value = jdbc.queryForObject(
            "SELECT COALESCE(MAX(version), 0) + 1 FROM inventory_weekly_standards WHERE standard_code = ?",
            Integer.class,
            standardCode
        );
        return value == null ? 1 : value;
    }

    public void insertStandard(
        String id, String code, int version, String name, String effectiveWeek, String expiresWeek,
        String timezone, JsonNode policy, String actor, String role
    ) {
        jdbc.update("""
            INSERT INTO inventory_weekly_standards
              (id, standard_code, version, name, status, effective_week, expires_week,
               hospital_timezone, policy_json, created_by, created_by_role)
            VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?)
            """, id, code, version, name, effectiveWeek, blankToNull(expiresWeek), timezone, json(policy), actor, role);
    }

    public void updateStandard(
        String id, String name, String effectiveWeek, String expiresWeek, String timezone, JsonNode policy
    ) {
        int updated = jdbc.update("""
            UPDATE inventory_weekly_standards
            SET name = ?, effective_week = ?, expires_week = ?, hospital_timezone = ?, policy_json = ?
            WHERE id = ? AND status = 'DRAFT'
            """, name, effectiveWeek, blankToNull(expiresWeek), timezone, json(policy), id);
        if (updated != 1) throw new IllegalStateException("仅草稿标准可修改");
        jdbc.update("DELETE FROM inventory_weekly_standard_lines WHERE standard_id = ?", id);
    }

    public void insertStandardLine(
        String id, String standardId, String departmentId, String departmentName, String itemId,
        String itemName, String itemUnit, String careType, BigDecimal expected, BigDecimal safety,
        String calculationPolicy, JsonNode linePolicy
    ) {
        jdbc.update("""
            INSERT INTO inventory_weekly_standard_lines
              (id, standard_id, department_id, department_name_snapshot, care_type, item_id,
               item_name_snapshot, item_unit_snapshot, expected_quantity, safety_stock_quantity,
               calculation_policy, line_policy_json, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
            """, id, standardId, departmentId, departmentName, careType, itemId, itemName, itemUnit,
            expected, safety, calculationPolicy, json(linePolicy));
    }

    public void publish(String id, String actor, String role) {
        ObjectNode standard = standard(id);
        if (!"DRAFT".equals(standard.path("status").asText())) throw new IllegalStateException("仅草稿标准可发布");
        jdbc.update("""
            UPDATE inventory_weekly_standards
            SET status = 'RETIRED'
            WHERE standard_code = ? AND status = 'PUBLISHED' AND id <> ?
            """, standard.path("standardCode").asText(), id);
        jdbc.update("""
            UPDATE inventory_weekly_standards
            SET status = 'PUBLISHED', published_by = ?, published_by_role = ?, published_at = CURRENT_TIMESTAMP(3)
            WHERE id = ? AND status = 'DRAFT'
            """, actor, role, id);
    }

    public void deleteDraft(String id) {
        jdbc.update("DELETE FROM inventory_weekly_standard_lines WHERE standard_id = ? AND EXISTS (SELECT 1 FROM inventory_weekly_standards s WHERE s.id = ? AND s.status = 'DRAFT')", id, id);
        int deleted = jdbc.update("DELETE FROM inventory_weekly_standards WHERE id = ? AND status = 'DRAFT'", id);
        if (deleted != 1) throw new IllegalStateException("仅草稿标准可删除");
    }

    public ObjectNode activeStandard(String weekNo, String departmentId) {
        ObjectNode result = one("""
            SELECT s.id, s.standard_code standardCode, s.version, s.name, s.status,
                   s.effective_week effectiveWeek, s.expires_week expiresWeek,
                   s.hospital_timezone hospitalTimezone, s.policy_json policy
            FROM inventory_weekly_standards s
            WHERE s.status = 'PUBLISHED' AND s.effective_week <= ?
              AND (s.expires_week IS NULL OR s.expires_week >= ?)
              AND EXISTS (SELECT 1 FROM inventory_weekly_standard_lines l WHERE l.standard_id = s.id AND l.department_id = ? AND l.status = 'ACTIVE')
            ORDER BY s.effective_week DESC, s.version DESC LIMIT 1
            """, weekNo, weekNo, departmentId);
        if (result == null) throw new IllegalStateException("当前科室和周次没有已发布的标准清单");
        parseJson(result, "policy");
        return result;
    }

    public List<StandardLine> standardLines(String standardId, String departmentId) {
        return jdbc.query("""
            SELECT l.id, l.care_type, l.item_id, l.item_name_snapshot, l.item_unit_snapshot,
                   l.expected_quantity, l.safety_stock_quantity, l.calculation_policy,
                   l.line_policy_json, i.enabled
            FROM inventory_weekly_standard_lines l
            JOIN inventory_items i ON i.id = l.item_id
            WHERE l.standard_id = ? AND l.department_id = ? AND l.status = 'ACTIVE'
            ORDER BY l.care_type, l.item_name_snapshot
            """, this::mapStandardLine, standardId, departmentId);
    }

    public FlowSummary flow(String departmentId, String itemId, String careType, LocalDate from, LocalDate to, LocalDateTime cutoff) {
        String locationId = "loc-dept-" + InventoryLedgerRepository.shaKey(departmentId);
        ObjectNode row = one("""
            SELECT
              COALESCE(SUM(CASE WHEN m.occurred_at < ? THEN
                CASE WHEN m.to_location_id = ? THEN m.quantity WHEN m.from_location_id = ? THEN -m.quantity ELSE 0 END
                ELSE 0 END), 0) openingQuantity,
              COALESCE(SUM(CASE WHEN m.occurred_at >= ? AND m.occurred_at < ? AND m.movement_type = 'INBOUND' THEN m.quantity ELSE 0 END), 0) inboundQuantity,
              COALESCE(SUM(CASE WHEN m.occurred_at >= ? AND m.occurred_at < ? AND m.movement_type = 'TRANSFER_TO_DEPARTMENT' THEN m.quantity ELSE 0 END), 0) transferInQuantity,
              COALESCE(SUM(CASE WHEN m.occurred_at >= ? AND m.occurred_at < ? AND m.movement_type = 'RETURN_TO_CENTRAL' THEN m.quantity ELSE 0 END), 0) transferOutQuantity,
              COALESCE(SUM(CASE WHEN m.occurred_at >= ? AND m.occurred_at < ? AND m.movement_type = 'CONSUMPTION' THEN m.quantity ELSE 0 END), 0) consumedQuantity,
              COALESCE(SUM(CASE WHEN m.occurred_at >= ? AND m.occurred_at < ? AND m.movement_type = 'CONSUMPTION_REVERSAL' THEN m.quantity ELSE 0 END), 0) reversalQuantity,
              COALESCE(SUM(CASE WHEN m.occurred_at >= ? AND m.occurred_at < ? AND m.movement_type = 'RETURN_TO_CENTRAL' THEN m.quantity ELSE 0 END), 0) returnedQuantity,
              COALESCE(SUM(CASE WHEN m.occurred_at >= ? AND m.occurred_at < ? AND m.movement_type = 'SCRAP' THEN m.quantity ELSE 0 END), 0) scrappedQuantity,
              COALESCE(SUM(CASE WHEN m.occurred_at >= ? AND m.occurred_at < ? AND m.movement_type LIKE 'COUNT_ADJUSTMENT_%%'
                THEN CASE WHEN m.movement_type = 'COUNT_ADJUSTMENT_IN' THEN m.quantity ELSE -m.quantity END ELSE 0 END), 0) countAdjustmentQuantity,
              COUNT(m.id) movementCount
            FROM inventory_ledger_movements m
            WHERE m.department_id = ? AND m.item_id = ? AND m.occurred_at < ?
            """, from.atStartOfDay(), locationId, locationId,
            from.atStartOfDay(), cutoff, from.atStartOfDay(), cutoff,
            from.atStartOfDay(), cutoff, from.atStartOfDay(), cutoff,
            from.atStartOfDay(), cutoff, from.atStartOfDay(), cutoff,
            from.atStartOfDay(), cutoff, from.atStartOfDay(), cutoff,
            departmentId, itemId, cutoff);
        BigDecimal closing = decimal(oneValue("""
            SELECT COALESCE(SUM(quantity), 0) FROM inventory_batch_balances WHERE location_id = ? AND item_id = ?
            """, locationId, itemId));
        BigDecimal reserved = decimal(oneValue("""
            SELECT COALESCE(SUM(reserved_quantity), 0) FROM inventory_batch_balances WHERE location_id = ? AND item_id = ?
            """, locationId, itemId));
        String normalizedCareType = careType == null || careType.isBlank() ? "" : careType.trim().toLowerCase();
        Integer registeredVolume = normalizedCareType.isBlank()
            ? jdbc.queryForObject("""
                SELECT COUNT(DISTINCT e.id)
                FROM pre_ai_encounters e
                WHERE e.owning_department_id = ?
                  AND COALESCE(STR_TO_DATE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.patient_json, '$.visitDate')), ''), '%Y-%m-%d'),
                               STR_TO_DATE(LEFT(e.created_at, 10), '%Y-%m-%d')) BETWEEN ? AND ?
                  AND e.status <> 'VOID'
                """, Integer.class, departmentId, from, to)
            : jdbc.queryForObject("""
                SELECT COUNT(DISTINCT e.id)
                FROM pre_ai_encounters e
                WHERE e.owning_department_id = ?
                  AND COALESCE(STR_TO_DATE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.patient_json, '$.visitDate')), ''), '%Y-%m-%d'),
                               STR_TO_DATE(LEFT(e.created_at, 10), '%Y-%m-%d')) BETWEEN ? AND ?
                  AND LOWER(e.route) = ?
                  AND e.status <> 'VOID'
                """, Integer.class, departmentId, from, to, normalizedCareType);
        Integer consumptionEventVolume = normalizedCareType.isBlank()
            ? jdbc.queryForObject("""
                SELECT COUNT(DISTINCT encounter_id) FROM inventory_consumption_events
                WHERE department_id = ? AND visit_date >= ? AND visit_date <= ? AND status = 'succeeded'
                """, Integer.class, departmentId, from, to)
            : jdbc.queryForObject("""
                SELECT COUNT(DISTINCT encounter_id) FROM inventory_consumption_events
                WHERE department_id = ? AND visit_date >= ? AND visit_date <= ? AND status = 'succeeded'
                  AND LOWER(route) = ?
                """, Integer.class, departmentId, from, to, normalizedCareType);
        return new FlowSummary(
            decimal(row.path("openingQuantity")), decimal(row.path("inboundQuantity")),
            decimal(row.path("transferInQuantity")), decimal(row.path("transferOutQuantity")),
            decimal(row.path("consumedQuantity")), decimal(row.path("reversalQuantity")),
            decimal(row.path("returnedQuantity")), decimal(row.path("scrappedQuantity")),
            decimal(row.path("countAdjustmentQuantity")), closing, reserved,
            row.path("movementCount").asInt(),
            registeredVolume == null ? 0 : registeredVolume,
            consumptionEventVolume == null ? 0 : consumptionEventVolume
        );
    }

    public int nextRevision(String weekNo, String departmentId) {
        Integer revision = jdbc.queryForObject(
            "SELECT COALESCE(MAX(revision), 0) + 1 FROM inventory_weekly_snapshots WHERE week_no = ? AND department_id = ?",
            Integer.class, weekNo, departmentId
        );
        return revision == null ? 1 : revision;
    }

    public int latestRevision(String weekNo, String departmentId) {
        Integer revision = jdbc.queryForObject(
            "SELECT COALESCE(MAX(revision), 0) FROM inventory_weekly_snapshots WHERE week_no = ? AND department_id = ?",
            Integer.class, weekNo, departmentId
        );
        return revision == null ? 0 : revision;
    }

    public void insertSnapshot(
        String id, String weekNo, String departmentId, String departmentName, String standardId,
        int standardVersion, int revision, String previousId, String rootId, String status,
        LocalDateTime cutoff, String timezone, String digest, int lineCount,
        BigDecimal totalExpected, BigDecimal totalActual, BigDecimal totalAdjusted,
        String revisionReason, String actor, String role
    ) {
        jdbc.update("""
            INSERT INTO inventory_weekly_snapshots
              (id, week_no, department_id, department_name_snapshot, standard_id, standard_version,
               revision, previous_snapshot_id, root_snapshot_id, status, source_cutoff_at,
               hospital_timezone, calculation_version, source_digest, line_count,
               total_expected_quantity, total_actual_consumed_quantity, total_adjusted_quantity,
               revision_reason, created_by, created_by_role)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'weekly-v1', ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, weekNo, departmentId, departmentName, standardId, standardVersion, revision,
            blankToNull(previousId), blankToNull(rootId), status, cutoff, timezone, digest, lineCount,
            totalExpected, totalActual, totalAdjusted, blankToNull(revisionReason), actor, role);
    }

    public void insertSnapshotLine(String snapshotId, SnapshotLine line) {
        jdbc.update("""
            INSERT INTO inventory_weekly_snapshot_lines
              (id, snapshot_id, standard_line_id, care_type, item_id, item_name_snapshot, item_unit_snapshot,
               opening_quantity, inbound_quantity, transfer_in_quantity, transfer_out_quantity,
               consumed_quantity, reversal_quantity, returned_quantity, scrapped_quantity,
               count_adjustment_quantity, closing_quantity, reserved_quantity, available_quantity,
               expected_quantity, expected_actual_variance, safety_stock_quantity, suggested_quantity,
               adjusted_quantity, adjustment_variance, adjustment_reason, source_summary_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, line.id(), snapshotId, line.standardLineId(), line.careType(), line.itemId(), line.itemName(), line.itemUnit(),
            line.flow().opening(), line.flow().inbound(), line.flow().transferIn(), line.flow().transferOut(),
            line.flow().consumed(), line.flow().reversal(), line.flow().returned(), line.flow().scrapped(),
            line.flow().countAdjustment(), line.flow().closing(), line.flow().reserved(), line.flow().available(),
            line.expected(), line.expectedActualVariance(), line.safety(), line.suggested(), line.adjusted(),
            line.adjustmentVariance(), blankToNull(line.adjustmentReason()), json(line.sourceSummary()));
    }

    public void copySnapshotLines(String sourceSnapshotId, String targetSnapshotId) {
        jdbc.update("""
            INSERT INTO inventory_weekly_snapshot_lines
              (id, snapshot_id, standard_line_id, care_type, item_id, item_name_snapshot, item_unit_snapshot,
               opening_quantity, inbound_quantity, transfer_in_quantity, transfer_out_quantity,
               consumed_quantity, reversal_quantity, returned_quantity, scrapped_quantity,
               count_adjustment_quantity, closing_quantity, reserved_quantity, available_quantity,
               expected_quantity, expected_actual_variance, safety_stock_quantity, suggested_quantity,
               adjusted_quantity, adjustment_variance, adjustment_reason, source_summary_json)
            SELECT UUID(), ?, standard_line_id, care_type, item_id, item_name_snapshot, item_unit_snapshot,
                   opening_quantity, inbound_quantity, transfer_in_quantity, transfer_out_quantity,
                   consumed_quantity, reversal_quantity, returned_quantity, scrapped_quantity,
                   count_adjustment_quantity, closing_quantity, reserved_quantity, available_quantity,
                   expected_quantity, expected_actual_variance, safety_stock_quantity, suggested_quantity,
                   adjusted_quantity, adjustment_variance, adjustment_reason, source_summary_json
            FROM inventory_weekly_snapshot_lines WHERE snapshot_id = ?
            """, targetSnapshotId, sourceSnapshotId);
    }

    public void updateSnapshotLineAdjustment(
        String snapshotId, String itemId, String careType, BigDecimal adjustedQuantity, String reason
    ) {
        int updated = jdbc.update("""
            UPDATE inventory_weekly_snapshot_lines
            SET adjusted_quantity = ?, adjustment_variance = ? - suggested_quantity,
                adjustment_reason = ?
            WHERE snapshot_id = ? AND item_id = ? AND care_type = ?
            """, adjustedQuantity, adjustedQuantity, blankToNull(reason), snapshotId, itemId, careType);
        if (updated != 1) throw new IllegalArgumentException("更正行物资不在原快照中：" + itemId + "/" + careType);
    }

    public ObjectNode snapshot(String id) {
        ObjectNode result = one("""
            SELECT id, week_no weekNo, department_id departmentId, department_name_snapshot departmentName,
                   standard_id standardId, standard_version standardVersion, revision,
                   previous_snapshot_id previousSnapshotId, root_snapshot_id rootSnapshotId, status,
                   source_cutoff_at sourceCutoffAt, hospital_timezone hospitalTimezone,
                   calculation_version calculationVersion, source_digest sourceDigest, line_count lineCount,
                   total_expected_quantity totalExpectedQuantity,
                   total_actual_consumed_quantity totalActualConsumedQuantity,
                   total_adjusted_quantity totalAdjustedQuantity, revision_reason revisionReason,
                   confirmation_note confirmationNote, confirmed_by confirmedBy,
                   confirmed_by_role confirmedByRole, confirmed_at confirmedAt,
                   created_by createdBy, created_by_role createdByRole, created_at createdAt
            FROM inventory_weekly_snapshots WHERE id = ?
            """, id);
        if (result == null) throw new IllegalArgumentException("周度快照不存在");
        result.set("lines", queryJson("""
            SELECT id, snapshot_id snapshotId, standard_line_id standardLineId, care_type careType, item_id itemId,
                   item_name_snapshot itemName, item_unit_snapshot itemUnit,
                   opening_quantity openingQuantity, inbound_quantity inboundQuantity,
                   transfer_in_quantity transferInQuantity, transfer_out_quantity transferOutQuantity,
                   consumed_quantity consumedQuantity, reversal_quantity reversalQuantity,
                   returned_quantity returnedQuantity, scrapped_quantity scrappedQuantity,
                   count_adjustment_quantity countAdjustmentQuantity, closing_quantity closingQuantity,
                   reserved_quantity reservedQuantity, available_quantity availableQuantity,
                   expected_quantity expectedQuantity, expected_actual_variance expectedActualVariance,
                   safety_stock_quantity safetyStockQuantity, suggested_quantity suggestedQuantity,
                   adjusted_quantity adjustedQuantity, adjustment_variance adjustmentVariance,
                   adjustment_reason adjustmentReason, source_summary_json sourceSummary
            FROM inventory_weekly_snapshot_lines WHERE snapshot_id = ? ORDER BY care_type, item_name_snapshot
            """, id));
        for (JsonNode line : result.withArray("lines")) parseJson((ObjectNode) line, "sourceSummary");
        return result;
    }

    public ArrayNode snapshots(String weekNo, String departmentId) {
        StringBuilder sql = new StringBuilder("""
            SELECT id, week_no weekNo, department_id departmentId, department_name_snapshot departmentName,
                   standard_id standardId, standard_version standardVersion, revision, status,
                   source_cutoff_at sourceCutoffAt, line_count lineCount,
                   total_expected_quantity totalExpectedQuantity,
                   total_actual_consumed_quantity totalActualConsumedQuantity,
                   total_adjusted_quantity totalAdjustedQuantity, confirmed_by confirmedBy,
                   confirmed_at confirmedAt, created_at createdAt
            FROM inventory_weekly_snapshots WHERE 1 = 1
            """);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (weekNo != null && !weekNo.isBlank()) { sql.append(" AND week_no = ?"); args.add(weekNo); }
        if (departmentId != null && !departmentId.isBlank()) { sql.append(" AND department_id = ?"); args.add(departmentId); }
        sql.append(" ORDER BY week_no DESC, department_name_snapshot, revision DESC");
        return queryJson(sql.toString(), args.toArray());
    }

    public void confirmSnapshot(String id, String note, String actor, String role) {
        int updated = jdbc.update("""
            UPDATE inventory_weekly_snapshots
            SET status = 'CONFIRMED', confirmation_note = ?, confirmed_by = ?,
                confirmed_by_role = ?, confirmed_at = CURRENT_TIMESTAMP(3)
            WHERE id = ? AND status = 'DRAFT'
            """, blankToNull(note), actor, role, id);
        if (updated != 1) throw new IllegalStateException("仅草稿快照可确认");
    }

    public ObjectNode command(String idempotencyKey) {
        ObjectNode value = one("""
            SELECT id, idempotency_key idempotencyKey, command_type commandType,
                   week_no weekNo, department_id departmentId, expected_revision expectedRevision,
                   snapshot_id snapshotId, status, request_hash requestHash,
                   request_json requestJson, response_json responseJson,
                   error_code errorCode, error_message errorMessage
            FROM inventory_weekly_commands WHERE idempotency_key = ?
            """, idempotencyKey);
        if (value != null) { parseJson(value, "requestJson"); parseJson(value, "responseJson"); }
        return value;
    }

    public String beginCommand(
        String id, String key, String type, String weekNo, String departmentId,
        Integer expectedRevision, String requestHash, JsonNode request, String actor, String role
    ) {
        jdbc.update("""
            INSERT INTO inventory_weekly_commands
              (id, idempotency_key, command_type, week_no, department_id, expected_revision,
               status, request_hash, request_json, requested_by, requested_by_role)
            VALUES (?, ?, ?, ?, ?, ?, 'PROCESSING', ?, ?, ?, ?)
            """, id, key, type, weekNo, departmentId, expectedRevision, requestHash, json(request), actor, role);
        return id;
    }

    public void completeCommand(String id, String snapshotId, JsonNode response) {
        jdbc.update("""
            UPDATE inventory_weekly_commands SET snapshot_id = ?, status = 'COMPLETED',
                response_json = ?, completed_at = CURRENT_TIMESTAMP(3) WHERE id = ?
            """, blankToNull(snapshotId), json(response), id);
    }

    public void audit(
        String snapshotId, String commandId, String exportId, String action,
        String actor, String role, String departmentId, JsonNode detail
    ) {
        jdbc.update("""
            INSERT INTO inventory_weekly_audit_events
              (id, snapshot_id, command_id, export_id, action_code, actor_name, actor_role, department_id, detail_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, "weekly-audit-" + java.util.UUID.randomUUID(), blankToNull(snapshotId), blankToNull(commandId),
            blankToNull(exportId), action, actor, role, blankToNull(departmentId), json(detail));
    }

    public String recordExport(
        String snapshotId, String format, int rowCount, String fileName, String contentHash,
        long fileSize, String actor, String role, String departmentId
    ) {
        String id = "weekly-export-" + java.util.UUID.randomUUID();
        jdbc.update("""
            INSERT INTO inventory_weekly_exports
              (id, snapshot_id, export_format, filter_json, row_count, file_name, storage_path,
               content_hash, file_size, requested_by, requested_by_role, requested_department_id)
            VALUES (?, ?, ?, JSON_OBJECT(), ?, ?, NULL, ?, ?, ?, ?, ?)
            """, id, snapshotId, format, rowCount, fileName, contentHash, fileSize, actor, role, blankToNull(departmentId));
        return id;
    }

    public ArrayNode auditEvents(String snapshotId) {
        return queryJson("""
            SELECT id, snapshot_id snapshotId, command_id commandId, export_id exportId,
                   action_code actionCode, actor_name actorName, actor_role actorRole,
                   department_id departmentId, detail_json detail, occurred_at occurredAt
            FROM inventory_weekly_audit_events WHERE snapshot_id = ? ORDER BY occurred_at DESC
            """, snapshotId);
    }

    public JdbcTemplate jdbc() { return jdbc; }
    public ObjectMapper mapper() { return mapper; }

    private StandardLine mapStandardLine(ResultSet rs, int rowNum) throws SQLException {
        return new StandardLine(
            rs.getString("id"), rs.getString("care_type"), rs.getString("item_id"), rs.getString("item_name_snapshot"),
            rs.getString("item_unit_snapshot"), scale(rs.getBigDecimal("expected_quantity")),
            scale(rs.getBigDecimal("safety_stock_quantity")), rs.getString("calculation_policy"),
            readJson(rs.getString("line_policy_json")), rs.getBoolean("enabled")
        );
    }

    private ArrayNode queryJson(String sql, Object... args) {
        return jdbc.query(sql, rs -> {
            ArrayNode rows = mapper.createArrayNode();
            java.sql.ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                ObjectNode row = rows.addObject();
                for (int index = 1; index <= meta.getColumnCount(); index++) {
                    String key = meta.getColumnLabel(index);
                    Object value = rs.getObject(index);
                    if (value == null) row.putNull(key);
                    else if (value instanceof BigDecimal number) row.put(key, number);
                    else if (value instanceof Number number) row.put(key, number.doubleValue());
                    else if (value instanceof Boolean bool) row.put(key, bool);
                    else row.put(key, value.toString());
                }
            }
            return rows;
        }, args);
    }

    private ObjectNode one(String sql, Object... args) {
        ArrayNode rows = queryJson(sql, args);
        return rows.isEmpty() ? null : (ObjectNode) rows.get(0);
    }

    private Object oneValue(String sql, Object... args) {
        return jdbc.queryForObject(sql, Object.class, args);
    }

    private void parseJson(ObjectNode node, String field) {
        if (node == null || !node.path(field).isTextual()) return;
        node.set(field, readJson(node.path(field).asText()));
    }

    private JsonNode readJson(String value) {
        try { return mapper.readTree(value == null || value.isBlank() ? "{}" : value); }
        catch (Exception error) { throw new IllegalStateException("周度库存 JSON 读取失败", error); }
    }

    private String json(JsonNode value) {
        try { return mapper.writeValueAsString(value == null ? mapper.createObjectNode() : value); }
        catch (Exception error) { throw new IllegalArgumentException("周度库存 JSON 序列化失败", error); }
    }

    private static BigDecimal decimal(JsonNode value) {
        return value == null || value.isNull() ? scale(BigDecimal.ZERO) : scale(value.decimalValue());
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? scale(BigDecimal.ZERO) : scale(new BigDecimal(value.toString()));
    }

    private static BigDecimal scale(BigDecimal value) {
        return InventoryLedgerRepository.scale(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record StandardLine(
        String id, String careType, String itemId, String itemName, String itemUnit, BigDecimal expected,
        BigDecimal safety, String calculationPolicy, JsonNode linePolicy, boolean enabled
    ) {}

    public record FlowSummary(
        BigDecimal opening, BigDecimal inbound, BigDecimal transferIn, BigDecimal transferOut,
        BigDecimal consumed, BigDecimal reversal, BigDecimal returned, BigDecimal scrapped,
        BigDecimal countAdjustment, BigDecimal closing, BigDecimal reserved,
        int movementCount, int actualBusinessVolume, int consumptionEventVolume
    ) {
        public BigDecimal available() { return scale(closing.subtract(reserved)); }
        public BigDecimal actualConsumed() { return scale(consumed.subtract(reversal)); }
    }

    public record SnapshotLine(
        String id, String standardLineId, String careType, String itemId, String itemName, String itemUnit,
        FlowSummary flow, BigDecimal expected, BigDecimal expectedActualVariance,
        BigDecimal safety, BigDecimal suggested, BigDecimal adjusted,
        BigDecimal adjustmentVariance, String adjustmentReason, JsonNode sourceSummary
    ) {}
}
