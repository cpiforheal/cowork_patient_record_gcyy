package com.coshare.patientrecord.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class InventoryOpsHealthService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public InventoryOpsHealthService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ObjectNode health() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("openingUnconfirmed", count("SELECT COUNT(*) FROM inventory_locations WHERE location_type='DEPARTMENT' AND status='ACTIVE' AND opening_confirmed=0"));
        result.put("mappingConflicts", count("""
            SELECT COUNT(*) FROM inventory_quota_rules r
            JOIN inventory_quota_versions v ON v.id = r.version_id AND v.status='ACTIVE'
            LEFT JOIN inventory_items i ON i.id = r.item_id
            WHERE r.enabled=1 AND (i.id IS NULL OR TRIM(SUBSTRING_INDEX(REPLACE(r.material_name,'(','（'),'（',1)) <> i.name)
            """));
        result.put("missingBatchOrExpiry", count("""
            SELECT COUNT(*) FROM inventory_items i
            WHERE i.enabled=1 AND (i.batch_required=1 OR i.expiry_required=1)
              AND NOT EXISTS (
                SELECT 1 FROM inventory_batches b
                WHERE b.item_id=i.id AND b.quantity > 0
                  AND (i.batch_required=0 OR COALESCE(b.batch_no,'') <> '')
                  AND (i.expiry_required=0 OR COALESCE(b.expiry_date,'') <> '')
              )
            """));
        result.put("lowStock", count("""
            SELECT COUNT(*) FROM (
              SELECT i.id, COALESCE(i.low_stock_threshold,0) threshold,
                (SELECT COALESCE(SUM(quantity-reserved_quantity),0) FROM inventory_batch_balances b
                 WHERE b.item_id=i.id AND b.location_id='loc-central') available
              FROM inventory_items i WHERE i.enabled=1 AND COALESCE(i.low_stock_threshold,0) > 0
            ) x WHERE available < threshold
            """));
        result.put("expiringBatches", count("""
            SELECT COUNT(*) FROM inventory_batch_balances b
            JOIN inventory_batches ib ON ib.id=b.batch_id
            WHERE (b.quantity-b.reserved_quantity) > 0 AND ib.expiry_date IS NOT NULL AND ib.expiry_date <> ''
              AND ib.expiry_date <= ?
            """, LocalDate.now().plusDays(30).toString()));
        result.put("pendingDeduction", count("""
            SELECT COUNT(*) FROM inventory_exception_tasks
            WHERE exception_type='REVIEW_DEDUCTION_PENDING' AND status='OPEN'
            """));
        result.put("openExceptions", count("SELECT COUNT(*) FROM inventory_exception_tasks WHERE status='OPEN'"));
        result.put("negativeStock", count("SELECT COUNT(*) FROM inventory_batch_balances WHERE quantity < 0"));
        return result;
    }

    public ArrayNode exceptions(String type, String department, LocalDate from, LocalDate to, String status) {
        List<ObjectNode> rows = new ArrayList<>();
        String normalizedType = type == null ? "" : type.trim().toUpperCase();
        if (normalizedType.isBlank() || normalizedType.equals("PENDING_DEDUCTION")) {
            StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.created_at occurred_at, t.department_name_snapshot, t.item_id,
                       i.name item_name, i.spec, i.unit, t.status, t.message, t.line_id,
                       'REVIEW' document_type, t.source_id document_id, t.source_id document_no
                FROM inventory_exception_tasks t
                LEFT JOIN inventory_items i ON i.id = t.item_id
                WHERE t.exception_type='REVIEW_DEDUCTION_PENDING'
                """);
            List<Object> args = new ArrayList<>();
            appendFilters(sql, args, department, from, to, status, "t.department_name_snapshot", "t.created_at", "t.status");
            sql.append(" ORDER BY t.created_at DESC LIMIT 200");
            rows.addAll(this.queryList(sql.toString(), (rs, rowNum) -> exceptionRow(
                "PENDING_DEDUCTION", "待补扣", rs.getString("occurred_at"), rs.getString("department_name_snapshot"),
                rs.getString("item_id"), rs.getString("item_name"), rs.getString("spec"), rs.getString("unit"),
                BigDecimal.ZERO, rs.getString("document_type"), rs.getString("document_id"), rs.getString("document_no"),
                rs.getString("message"), rs.getString("status"), "/inventory-system/patient-review"
            ), args.toArray()));
        }
        if (normalizedType.isBlank() || normalizedType.equals("NEGATIVE_STOCK")) {
            StringBuilder sql = new StringBuilder("""
                SELECT b.item_id, i.name item_name, i.spec, i.unit, b.quantity, b.location_id,
                       l.department_name_snapshot, b.updated_at occurred_at
                FROM inventory_batch_balances b
                JOIN inventory_items i ON i.id = b.item_id
                LEFT JOIN inventory_locations l ON l.id = b.location_id
                WHERE b.quantity < 0
                """);
            List<Object> args = new ArrayList<>();
            appendFilters(sql, args, department, from, to, status, "l.department_name_snapshot", "b.updated_at", null);
            sql.append(" ORDER BY b.updated_at DESC LIMIT 200");
            rows.addAll(this.queryList(sql.toString(), (rs, rowNum) -> exceptionRow(
                "NEGATIVE_STOCK", "负库存", String.valueOf(rs.getTimestamp("occurred_at")),
                rs.getString("department_name_snapshot"), rs.getString("item_id"), rs.getString("item_name"),
                rs.getString("spec"), rs.getString("unit"), rs.getBigDecimal("quantity"), "STOCK",
                rs.getString("item_id"), "", "库存数量为负，请盘点或补录入库", "OPEN",
                "/inventory-system/warehouse"
            ), args.toArray()));
        }
        if (normalizedType.isBlank() || normalizedType.equals("INBOUND_REVERSAL")) {
            StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.item_id, m.quantity, m.occurred_at, m.operator_name, m.reason,
                       i.name item_name, i.spec, i.unit, b.batch_no
                FROM inventory_ledger_movements m
                LEFT JOIN inventory_items i ON i.id = m.item_id
                LEFT JOIN inventory_batches b ON b.id = m.batch_id
                WHERE m.movement_type='INBOUND_REVERSAL'
                """);
            List<Object> args = new ArrayList<>();
            appendFilters(sql, args, department, from, to, status, null, "m.occurred_at", null);
            sql.append(" ORDER BY m.occurred_at DESC LIMIT 200");
            rows.addAll(this.queryList(sql.toString(), (rs, rowNum) -> exceptionRow(
                "INBOUND_REVERSAL", "入库红冲", String.valueOf(rs.getTimestamp("occurred_at")), "",
                rs.getString("item_id"), rs.getString("item_name"), rs.getString("spec"), rs.getString("unit"),
                rs.getBigDecimal("quantity"), "INBOUND", rs.getString("id"), nz(rs.getString("batch_no")),
                nz(rs.getString("reason")), "DONE", "/inventory-system/warehouse"
            ), args.toArray()));
        }
        rows.sort((a, b) -> b.path("occurredAt").asText("").compareTo(a.path("occurredAt").asText("")));
        ArrayNode result = objectMapper.createArrayNode();
        rows.forEach(result::add);
        return result;
    }

    private void appendFilters(
        StringBuilder sql, List<Object> args, String department, LocalDate from, LocalDate to, String status,
        String departmentColumn, String timeColumn, String statusColumn
    ) {
        if (department != null && !department.isBlank() && departmentColumn != null) {
            sql.append(" AND ").append(departmentColumn).append(" LIKE ?");
            args.add("%" + department.trim() + "%");
        }
        if (from != null && timeColumn != null) {
            sql.append(" AND ").append(timeColumn).append(" >= ?");
            args.add(from.atStartOfDay().toString());
        }
        if (to != null && timeColumn != null) {
            sql.append(" AND ").append(timeColumn).append(" < ?");
            args.add(to.plusDays(1).atStartOfDay().toString());
        }
        if (status != null && !status.isBlank() && statusColumn != null) {
            sql.append(" AND ").append(statusColumn).append(" = ?");
            args.add(status.trim().toUpperCase());
        }
    }

    private ObjectNode exceptionRow(
        String type, String typeLabel, String occurredAt, String department, String itemId, String itemName,
        String spec, String unit, BigDecimal quantity, String documentType, String documentId,
        String documentNo, String reason, String status, String actionPath
    ) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("type", type);
        row.put("typeLabel", typeLabel);
        row.put("occurredAt", nz(occurredAt));
        row.put("department", nz(department));
        row.put("itemId", nz(itemId));
        row.put("itemName", nz(itemName));
        row.put("spec", nz(spec));
        row.put("unit", nz(unit));
        row.put("quantity", quantity == null ? BigDecimal.ZERO : quantity.setScale(2, RoundingMode.HALF_UP));
        row.put("documentType", nz(documentType));
        row.put("documentId", nz(documentId));
        row.put("documentNo", nz(documentNo));
        row.put("reason", nz(reason));
        row.put("status", nz(status));
        row.put("actionPath", nz(actionPath));
        return row;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private List<ObjectNode> queryList(String sql, RowMapper<ObjectNode> mapper, Object... args) {
        return jdbcTemplate.query(sql, mapper, args);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
