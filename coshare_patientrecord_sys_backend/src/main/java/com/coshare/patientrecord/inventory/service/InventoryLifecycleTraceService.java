package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository;
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
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class InventoryLifecycleTraceService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryLedgerRepository ledgerRepository;

    public InventoryLifecycleTraceService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        InventoryLedgerRepository ledgerRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.ledgerRepository = ledgerRepository;
    }

    public ArrayNode subjects(String keyword, LocalDate from, LocalDate to, String department, String type, String operator) {
        String term = keyword == null ? "" : keyword.trim();
        ArrayNode result = objectMapper.createArrayNode();
        if (term.isBlank()) {
            List<ObjectNode> items = this.queryList(
                """
                SELECT i.id item_id, i.name, i.spec, i.unit,
                       COALESCE((SELECT SUM(b.quantity - b.reserved_quantity) FROM inventory_batch_balances b
                                 WHERE b.item_id = i.id AND b.location_id = 'loc-central'), 0) balance,
                       (SELECT MAX(m.occurred_at) FROM inventory_ledger_movements m WHERE m.item_id = i.id) latest_at
                FROM inventory_items i WHERE i.enabled = 1
                ORDER BY latest_at DESC, i.name LIMIT 20
                """,
                (rs, rowNum) -> {
                    ObjectNode row = objectMapper.createObjectNode();
                    row.put("kind", "ITEM");
                    row.put("id", rs.getString("item_id"));
                    row.put("itemId", rs.getString("item_id"));
                    row.put("label", rs.getString("name"));
                    row.put("spec", nz(rs.getString("spec")));
                    row.put("unit", nz(rs.getString("unit")));
                    row.put("balance", scale(rs.getBigDecimal("balance")));
                    row.put("latestAt", rs.getTimestamp("latest_at") == null ? "" : rs.getTimestamp("latest_at").toLocalDateTime().toString());
                    row.put("pendingCount", 0);
                    row.put("note", "中心库可发");
                    return row;
                }
            );
            items.forEach(result::add);
            return result;
        }
        String like = "%" + term + "%";
        this.queryList(
            """
            SELECT i.id item_id, i.name, i.spec, i.unit,
                   COALESCE((SELECT SUM(b.quantity - b.reserved_quantity) FROM inventory_batch_balances b
                             WHERE b.item_id = i.id AND b.location_id = 'loc-central'), 0) balance,
                   (SELECT MAX(m.occurred_at) FROM inventory_ledger_movements m WHERE m.item_id = i.id) latest_at
            FROM inventory_items i
            WHERE i.enabled = 1 AND (i.name LIKE ? OR i.spec LIKE ?)
            ORDER BY i.name LIMIT 10
            """,
            (rs, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("kind", "ITEM");
                row.put("id", rs.getString("item_id"));
                row.put("itemId", rs.getString("item_id"));
                row.put("label", rs.getString("name"));
                row.put("spec", nz(rs.getString("spec")));
                row.put("unit", nz(rs.getString("unit")));
                row.put("balance", scale(rs.getBigDecimal("balance")));
                row.put("latestAt", rs.getTimestamp("latest_at") == null ? "" : rs.getTimestamp("latest_at").toLocalDateTime().toString());
                row.put("pendingCount", 0);
                row.put("note", "物资");
                return row;
            },
            like, like
        ).forEach(result::add);
        this.queryList(
            """
            SELECT b.id batch_id, b.item_id, b.batch_no, b.expiry_date, i.name, i.spec, i.unit,
                   COALESCE((SELECT SUM(x.quantity - x.reserved_quantity) FROM inventory_batch_balances x
                             WHERE x.batch_id = b.id), 0) balance
            FROM inventory_batches b JOIN inventory_items i ON i.id = b.item_id
            WHERE b.batch_no LIKE ? ORDER BY b.updated_at DESC LIMIT 10
            """,
            (rs, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("kind", "BATCH");
                row.put("id", rs.getString("batch_id"));
                row.put("batchId", rs.getString("batch_id"));
                row.put("itemId", rs.getString("item_id"));
                row.put("label", rs.getString("name") + " · " + rs.getString("batch_no"));
                row.put("spec", nz(rs.getString("spec")));
                row.put("unit", nz(rs.getString("unit")));
                row.put("balance", scale(rs.getBigDecimal("balance")));
                row.put("latestAt", "");
                row.put("pendingCount", 0);
                row.put("note", "批号 " + nz(rs.getString("batch_no")));
                return row;
            },
            like
        ).forEach(result::add);
        this.queryList(
            """
            SELECT o.id, o.order_no, o.supplier, o.purchase_date,
                   (SELECT MIN(il.batch_id) FROM inventory_inbound_lines il WHERE il.order_id = o.id) batch_id,
                   (SELECT MIN(il.item_id) FROM inventory_inbound_lines il WHERE il.order_id = o.id) item_id
            FROM inventory_inbound_orders o WHERE o.order_no LIKE ? ORDER BY o.created_at DESC LIMIT 10
            """,
            (rs, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("kind", "INBOUND");
                row.put("id", rs.getString("id"));
                row.put("orderNo", rs.getString("order_no"));
                row.put("itemId", nz(rs.getString("item_id")));
                row.put("batchId", nz(rs.getString("batch_id")));
                row.put("label", "入库单 " + rs.getString("order_no"));
                row.put("spec", "");
                row.put("unit", "");
                row.put("balance", 0);
                row.put("latestAt", rs.getDate("purchase_date") == null ? "" : rs.getDate("purchase_date").toString());
                row.put("pendingCount", 0);
                row.put("note", nz(rs.getString("supplier")));
                return row;
            },
            like
        ).forEach(result::add);
        this.queryList(
            """
            SELECT r.id, r.department, r.status, r.created_at,
                   JSON_UNQUOTE(JSON_EXTRACT(r.raw_json, '$.lines[0].itemId')) item_id
            FROM inventory_requests r
            WHERE r.id LIKE ? OR JSON_UNQUOTE(JSON_EXTRACT(r.raw_json, '$.itemSummary')) LIKE ?
            ORDER BY r.created_at DESC LIMIT 10
            """,
            (rs, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("kind", "REQUEST");
                row.put("id", rs.getString("id"));
                row.put("itemId", nz(rs.getString("item_id")));
                row.put("batchId", "");
                row.put("label", "申领单 " + rs.getString("id"));
                row.put("spec", "");
                row.put("unit", "");
                row.put("balance", 0);
                row.put("latestAt", nz(rs.getString("created_at")));
                row.put("pendingCount", 0);
                row.put("note", nz(rs.getString("department")) + " · " + nz(rs.getString("status")));
                return row;
            },
            like, like
        ).forEach(result::add);
        this.queryList(
            """
            SELECT si.id, si.patient_name, si.visit_no, si.department_name, si.service_started_at
            FROM inventory_service_instances si
            WHERE si.patient_name LIKE ? OR si.visit_no LIKE ?
            ORDER BY si.service_started_at DESC LIMIT 10
            """,
            (rs, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("kind", "PATIENT");
                row.put("id", rs.getString("id"));
                row.put("itemId", "");
                row.put("batchId", "");
                row.put("label", rs.getString("patient_name"));
                row.put("spec", "");
                row.put("unit", "");
                row.put("balance", 0);
                row.put("latestAt", rs.getTimestamp("service_started_at") == null
                    ? "" : rs.getTimestamp("service_started_at").toLocalDateTime().toString());
                row.put("pendingCount", 0);
                row.put("note", nz(rs.getString("visit_no")) + " · " + nz(rs.getString("department_name")));
                row.put("patientKeyword", rs.getString("patient_name"));
                return row;
            },
            like, like
        ).forEach(result::add);
        return result;
    }

    public ObjectNode lifecycle(
        String itemId, String batchId, String orderNo, String requestId, String patientKeyword,
        LocalDate from, LocalDate to, String department, String type, String operator, boolean abnormalOnly,
        int page, int size
    ) {
        String resolvedItemId = nz(itemId);
        String resolvedBatchId = nz(batchId);
        if (!resolvedBatchId.isBlank() && resolvedItemId.isBlank()) {
            List<String> items = jdbcTemplate.query(
                "SELECT item_id FROM inventory_batches WHERE id = ? LIMIT 1",
                (rs, rowNum) -> rs.getString(1), resolvedBatchId
            );
            if (!items.isEmpty()) resolvedItemId = items.get(0);
        }
        if (!nz(orderNo).isBlank()) {
            List<String> items = jdbcTemplate.query(
                """
                SELECT il.item_id FROM inventory_inbound_lines il
                JOIN inventory_inbound_orders o ON o.id = il.order_id
                WHERE o.order_no = ? LIMIT 1
                """,
                (rs, rowNum) -> rs.getString(1), orderNo.trim()
            );
            if (!items.isEmpty()) resolvedItemId = items.get(0);
        }
        if (!nz(requestId).isBlank()) {
            List<String> items = jdbcTemplate.query(
                """
                SELECT JSON_UNQUOTE(JSON_EXTRACT(raw_json, '$.lines[0].itemId'))
                FROM inventory_requests WHERE id = ? LIMIT 1
                """,
                (rs, rowNum) -> rs.getString(1), requestId.trim()
            );
            if (!items.isEmpty() && items.get(0) != null) resolvedItemId = items.get(0);
        }
        Set<String> patientInstanceIds = new LinkedHashSet<>();
        if (!nz(patientKeyword).isBlank()) {
            String like = "%" + patientKeyword.trim() + "%";
            patientInstanceIds.addAll(jdbcTemplate.query(
                """
                SELECT id FROM inventory_service_instances
                WHERE patient_name LIKE ? OR visit_no LIKE ? LIMIT 50
                """,
                (rs, rowNum) -> rs.getString(1), like, like
            ));
        }
        if (resolvedItemId.isBlank() && resolvedBatchId.isBlank() && patientInstanceIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择物资、批次、单号或患者");
        }
        List<ObjectNode> events = new ArrayList<>();
        if (!resolvedItemId.isBlank() || !resolvedBatchId.isBlank()) {
            loadInboundEvents(events, resolvedItemId, resolvedBatchId, orderNo);
            loadMovementEvents(events, resolvedItemId, resolvedBatchId);
            loadConsumptionEvents(events, resolvedItemId, resolvedBatchId, null);
            loadPendingTasks(events, resolvedItemId, resolvedBatchId);
        } else {
            loadConsumptionEvents(events, "", "", patientInstanceIds);
        }
        filterEvents(events, from, to, department, type, operator, abnormalOnly);
        events.sort(Comparator.comparing((ObjectNode event) -> event.path("occurredAt").asText("")).reversed());
        int safeSize = Math.max(1, Math.min(size <= 0 ? 200 : size, 500));
        int safePage = Math.max(1, page);
        int start = Math.min(events.size(), (safePage - 1) * safeSize);
        int end = Math.min(events.size(), start + safeSize);
        ArrayNode pageEvents = objectMapper.createArrayNode();
        for (int index = start; index < end; index += 1) pageEvents.add(events.get(index));
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("itemId", resolvedItemId);
        summary.put("batchId", resolvedBatchId);
        summary.put("itemName", itemName(resolvedItemId));
        summary.put("spec", itemSpec(resolvedItemId));
        summary.put("unit", itemUnit(resolvedItemId));
        summary.put("inboundTotal", sumInbound(resolvedItemId, resolvedBatchId));
        summary.put("usedTotal", sumUsed(resolvedItemId, resolvedBatchId));
        summary.put("returnedTotal", sumMovement(resolvedItemId, resolvedBatchId, "RETURN_TO_CENTRAL"));
        summary.put("scrappedTotal", sumMovement(resolvedItemId, resolvedBatchId, "SCRAP"));
        summary.put("pendingDeductionCount", pendingCount(resolvedItemId, resolvedBatchId));
        summary.put("centralAvailable", available("loc-central", resolvedItemId, resolvedBatchId));
        summary.put("departmentAvailable", departmentAvailable(resolvedItemId, resolvedBatchId));
        result.set("summary", summary);
        result.set("events", pageEvents);
        result.put("total", events.size());
        result.put("page", safePage);
        result.put("size", safeSize);
        return result;
    }

    private void loadInboundEvents(List<ObjectNode> events, String itemId, String batchId, String orderNo) {
        StringBuilder sql = new StringBuilder("""
            SELECT il.id line_id, il.item_id, il.batch_id, il.batch_no, il.quantity, il.remark,
                   o.id order_id, o.order_no, o.supplier, o.created_by, o.purchase_date, o.created_at,
                   i.name item_name, i.unit
            FROM inventory_inbound_lines il
            JOIN inventory_inbound_orders o ON o.id = il.order_id AND o.status = 'ACTIVE'
            JOIN inventory_items i ON i.id = il.item_id
            WHERE 1 = 1
            """);
        List<Object> args = new ArrayList<>();
        if (!itemId.isBlank()) { sql.append(" AND il.item_id = ?"); args.add(itemId); }
        if (!batchId.isBlank()) { sql.append(" AND il.batch_id = ?"); args.add(batchId); }
        String safeOrderNo = nz(orderNo);
        if (!safeOrderNo.isBlank()) { sql.append(" AND o.order_no = ?"); args.add(safeOrderNo.trim()); }
        sql.append(" ORDER BY o.created_at DESC LIMIT 200");
        this.queryList(sql.toString(), (rs, rowNum) -> {
            ObjectNode event = baseEvent("INBOUND", "入库", rs.getString("created_at"), rs.getBigDecimal("quantity").abs());
            event.put("eventKey", "INBOUND:" + rs.getString("line_id"));
            event.put("quantity", scale(rs.getBigDecimal("quantity")));
            event.put("unit", nz(rs.getString("unit")));
            event.put("itemName", nz(rs.getString("item_name")));
            event.put("batchNo", nz(rs.getString("batch_no")));
            event.put("department", "中心库");
            event.put("operator", nz(rs.getString("created_by")));
            event.put("documentType", "INBOUND");
            event.put("documentId", rs.getString("order_id"));
            event.put("documentNo", nz(rs.getString("order_no")));
            event.put("note", "供应商：" + nz(rs.getString("supplier")) + (nz(rs.getString("remark")).isBlank() ? "" : " · " + rs.getString("remark")));
            event.put("status", "DONE");
            return event;
        }, args.toArray()).forEach(events::add);
    }

    private void loadMovementEvents(List<ObjectNode> events, String itemId, String batchId) {
        StringBuilder sql = new StringBuilder("""
            SELECT m.*, i.name item_name, i.unit, b.batch_no, l.department_name_snapshot
            FROM inventory_ledger_movements m
            LEFT JOIN inventory_items i ON i.id = m.item_id
            LEFT JOIN inventory_batches b ON b.id = m.batch_id
            LEFT JOIN inventory_locations l ON l.id = m.to_location_id
            WHERE m.movement_type NOT IN ('INBOUND', 'OPENING_BALANCE')
            """);
        List<Object> args = new ArrayList<>();
        if (!itemId.isBlank()) { sql.append(" AND m.item_id = ?"); args.add(itemId); }
        if (!batchId.isBlank()) { sql.append(" AND m.batch_id = ?"); args.add(batchId); }
        sql.append(" ORDER BY m.occurred_at DESC LIMIT 300");
        this.queryList(sql.toString(), (rs, rowNum) -> {
            String movementType = rs.getString("movement_type");
            String label = movementLabel(movementType);
            BigDecimal quantity = rs.getBigDecimal("quantity");
            ObjectNode event = baseEvent(eventType(movementType), label, rs.getTimestamp("occurred_at") == null
                ? "" : rs.getTimestamp("occurred_at").toLocalDateTime().toString(), signedQuantity(movementType, quantity).abs());
            event.put("eventKey", "MOVEMENT:" + rs.getString("id"));
            event.put("quantity", scale(signedQuantity(movementType, quantity)));
            event.put("unit", nz(rs.getString("unit")));
            event.put("itemName", nz(rs.getString("item_name")));
            event.put("batchNo", nz(rs.getString("batch_no")));
            event.put("department", nz(rs.getString("department_name_snapshot")));
            event.put("operator", nz(rs.getString("operator_name")));
            event.put("documentType", nz(rs.getString("related_type")));
            event.put("documentId", nz(rs.getString("related_id")));
            event.put("documentNo", nz(rs.getString("related_id")));
            event.put("note", nz(rs.getString("reason")));
            event.put("status", isAbnormalMovement(movementType) ? "WARNING" : "DONE");
            return event;
        }, args.toArray()).forEach(events::add);
    }

    private void loadConsumptionEvents(
        List<ObjectNode> events, String itemId, String batchId, Set<String> patientInstanceIds
    ) {
        StringBuilder sql = new StringBuilder("""
            SELECT e.id event_id, e.source_document_id, e.service_instance_id, e.patient_id, e.department_id,
                   e.status, e.operator_name, e.created_at, e.event_kind,
                   d.item_id, d.batch_id, d.quantity, i.name item_name, i.unit,
                   si.patient_name, si.visit_no, si.department_name
            FROM inventory_consumption_events e
            JOIN inventory_consumption_details d ON d.event_id = e.id
            LEFT JOIN inventory_items i ON i.id = d.item_id
            LEFT JOIN inventory_service_instances si ON si.id = e.service_instance_id
            WHERE e.status = 'succeeded'
            """);
        List<Object> args = new ArrayList<>();
        if (!itemId.isBlank()) { sql.append(" AND d.item_id = ?"); args.add(itemId); }
        if (!batchId.isBlank()) { sql.append(" AND d.batch_id = ?"); args.add(batchId); }
        if (patientInstanceIds != null && !patientInstanceIds.isEmpty()) {
            sql.append(" AND e.service_instance_id IN (");
            sql.append(String.join(",", patientInstanceIds.stream().map(id -> "?").toList()));
            sql.append(")");
            args.addAll(patientInstanceIds);
        }
        sql.append(" ORDER BY e.created_at DESC LIMIT 300");
        this.queryList(sql.toString(), (rs, rowNum) -> {
            String patientName = nz(rs.getString("patient_name"));
            ObjectNode event = baseEvent("CONSUMPTION", patientName.isBlank() ? "科室使用" : "患者使用",
                nz(rs.getString("created_at")), rs.getBigDecimal("quantity").abs());
            event.put("eventKey", "CONSUMPTION:" + rs.getString("event_id") + ":" + rs.getString("item_id"));
            event.put("quantity", scale(rs.getBigDecimal("quantity").negate()));
            event.put("unit", nz(rs.getString("unit")));
            event.put("itemName", nz(rs.getString("item_name")));
            event.put("department", nz(rs.getString("department_name")));
            event.put("operator", nz(rs.getString("operator_name")));
            event.put("documentType", "PATIENT");
            event.put("documentId", nz(rs.getString("source_document_id")));
            event.put("documentNo", nz(rs.getString("source_document_id")));
            event.put("note", patientName.isBlank() ? "科室消耗" : "患者 " + patientName + " · " + nz(rs.getString("visit_no")));
            event.put("status", "DONE");
            if (!patientName.isBlank()) {
                ObjectNode patient = objectMapper.createObjectNode();
                patient.put("name", patientName);
                patient.put("visitNo", nz(rs.getString("visit_no")));
                event.set("patient", patient);
            }
            return event;
        }, args.toArray()).forEach(events::add);
    }

    private void loadPendingTasks(List<ObjectNode> events, String itemId, String batchId) {
        StringBuilder sql = new StringBuilder("""
            SELECT t.id, t.item_id, t.message, t.status, t.created_at, t.resolved_at,
                   i.name item_name, i.unit, t.line_id
            FROM inventory_exception_tasks t
            LEFT JOIN inventory_items i ON i.id = t.item_id
            WHERE t.exception_type = 'REVIEW_DEDUCTION_PENDING'
            """);
        List<Object> args = new ArrayList<>();
        if (!itemId.isBlank()) { sql.append(" AND t.item_id = ?"); args.add(itemId); }
        sql.append(" ORDER BY t.created_at DESC LIMIT 100");
        this.queryList(sql.toString(), (rs, rowNum) -> {
            ObjectNode event = baseEvent("PENDING_DEDUCTION", "待补扣", nz(rs.getString("created_at")),
                BigDecimal.ZERO.abs());
            event.put("eventKey", "PENDING:" + rs.getString("id"));
            event.put("quantity", 0);
            event.put("unit", nz(rs.getString("unit")));
            event.put("itemName", nz(rs.getString("item_name")));
            event.put("batchNo", "");
            event.put("department", "");
            event.put("operator", "");
            event.put("documentType", "REVIEW");
            event.put("documentId", nz(rs.getString("line_id")));
            event.put("documentNo", nz(rs.getString("line_id")));
            event.put("note", nz(rs.getString("message")));
            event.put("status", "RESOLVED".equalsIgnoreCase(nz(rs.getString("status"))) ? "DONE" : "PENDING");
            event.put("occurredAt", "RESOLVED".equalsIgnoreCase(nz(rs.getString("status")))
                ? nz(rs.getString("resolved_at")) : nz(rs.getString("created_at")));
            return event;
        }, args.toArray()).forEach(events::add);
    }

    private void filterEvents(
        List<ObjectNode> events, LocalDate from, LocalDate to, String department, String type,
        String operator, boolean abnormalOnly
    ) {
        events.removeIf(event -> {
            String occurred = event.path("occurredAt").asText("");
            String day = occurred.length() >= 10 ? occurred.substring(0, 10) : "";
            if (from != null && !day.isBlank() && day.compareTo(from.toString()) < 0) return true;
            if (to != null && !day.isBlank() && day.compareTo(to.toString()) > 0) return true;
            if (!nz(department).isBlank() && !event.path("department").asText("").contains(department.trim())) return true;
            if (!nz(type).isBlank() && !event.path("eventType").asText("").equalsIgnoreCase(type.trim())) return true;
            if (!nz(operator).isBlank() && !event.path("operator").asText("").contains(operator.trim())) return true;
            return abnormalOnly && "DONE".equals(event.path("status").asText());
        });
    }

    private ObjectNode baseEvent(String eventType, String label, String occurredAt, BigDecimal quantity) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("eventId", "trace-" + Math.abs((eventType + occurredAt + quantity).hashCode()));
        event.put("eventType", eventType);
        event.put("eventLabel", label);
        event.put("occurredAt", occurredAt);
        event.put("quantity", scale(quantity));
        event.put("status", "DONE");
        return event;
    }

    private BigDecimal sumInbound(String itemId, String batchId) {
        if (itemId.isBlank()) return BigDecimal.ZERO;
        List<BigDecimal> values = jdbcTemplate.query(
            "SELECT COALESCE(SUM(il.quantity),0) FROM inventory_inbound_lines il JOIN inventory_inbound_orders o ON o.id=il.order_id "
                + "WHERE o.status='ACTIVE' AND il.item_id=?" + (batchId.isBlank() ? "" : " AND il.batch_id=?"),
            (rs, rowNum) -> rs.getBigDecimal(1), batchId.isBlank() ? new Object[]{itemId} : new Object[]{itemId, batchId}
        );
        return scale(values.isEmpty() ? BigDecimal.ZERO : values.get(0));
    }

    private BigDecimal sumUsed(String itemId, String batchId) {
        if (itemId.isBlank()) return BigDecimal.ZERO;
        List<BigDecimal> values = jdbcTemplate.query(
            "SELECT COALESCE(SUM(d.quantity),0) FROM inventory_consumption_events e JOIN inventory_consumption_details d ON d.event_id=e.id "
                + "WHERE e.status='succeeded' AND d.item_id=?" + (batchId.isBlank() ? "" : " AND d.batch_id=?"),
            (rs, rowNum) -> rs.getBigDecimal(1), batchId.isBlank() ? new Object[]{itemId} : new Object[]{itemId, batchId}
        );
        return scale(values.isEmpty() ? BigDecimal.ZERO : values.get(0));
    }

    private BigDecimal sumMovement(String itemId, String batchId, String type) {
        if (itemId.isBlank()) return BigDecimal.ZERO;
        List<BigDecimal> values = jdbcTemplate.query(
            "SELECT COALESCE(SUM(quantity),0) FROM inventory_ledger_movements WHERE movement_type=? AND item_id=?"
                + (batchId.isBlank() ? "" : " AND batch_id=?"),
            (rs, rowNum) -> rs.getBigDecimal(1), batchId.isBlank() ? new Object[]{type, itemId} : new Object[]{type, itemId, batchId}
        );
        return scale(values.isEmpty() ? BigDecimal.ZERO : values.get(0));
    }

    private BigDecimal available(String locationId, String itemId, String batchId) {
        if (itemId.isBlank()) return BigDecimal.ZERO;
        List<BigDecimal> values = jdbcTemplate.query(
            "SELECT COALESCE(SUM(quantity-reserved_quantity),0) FROM inventory_batch_balances WHERE location_id=? AND item_id=?"
                + (batchId.isBlank() ? "" : " AND batch_id=?"),
            (rs, rowNum) -> rs.getBigDecimal(1), batchId.isBlank() ? new Object[]{locationId, itemId} : new Object[]{locationId, itemId, batchId}
        );
        return scale(values.isEmpty() ? BigDecimal.ZERO : values.get(0));
    }

    private BigDecimal departmentAvailable(String itemId, String batchId) {
        if (itemId.isBlank()) return BigDecimal.ZERO;
        List<BigDecimal> values = jdbcTemplate.query(
            "SELECT COALESCE(SUM(b.quantity-b.reserved_quantity),0) FROM inventory_batch_balances b "
                + "JOIN inventory_locations l ON l.id=b.location_id WHERE l.location_type='DEPARTMENT' AND b.item_id=?"
                + (batchId.isBlank() ? "" : " AND b.batch_id=?"),
            (rs, rowNum) -> rs.getBigDecimal(1), batchId.isBlank() ? new Object[]{itemId} : new Object[]{itemId, batchId}
        );
        return scale(values.isEmpty() ? BigDecimal.ZERO : values.get(0));
    }

    private int pendingCount(String itemId, String batchId) {
        if (itemId.isBlank()) return 0;
        List<Integer> values = jdbcTemplate.query(
            "SELECT COUNT(*) FROM inventory_exception_tasks WHERE exception_type='REVIEW_DEDUCTION_PENDING' "
                + "AND status='OPEN' AND item_id=?" + (batchId.isBlank() ? "" : " AND line_id IN (SELECT id FROM inventory_patient_consumption_lines WHERE batch_id=?)"),
            (rs, rowNum) -> rs.getInt(1), batchId.isBlank() ? new Object[]{itemId} : new Object[]{itemId, batchId}
        );
        return values.isEmpty() ? 0 : values.get(0);
    }

    private String itemName(String itemId) {
        if (itemId.isBlank()) return "";
        List<String> values = jdbcTemplate.query("SELECT name FROM inventory_items WHERE id=?", (rs, n) -> rs.getString(1), itemId);
        return values.isEmpty() ? itemId : values.get(0);
    }

    private String itemSpec(String itemId) {
        if (itemId.isBlank()) return "";
        List<String> values = jdbcTemplate.query("SELECT COALESCE(spec,'') FROM inventory_items WHERE id=?", (rs, n) -> rs.getString(1), itemId);
        return values.isEmpty() ? "" : values.get(0);
    }

    private String itemUnit(String itemId) {
        if (itemId.isBlank()) return "";
        List<String> values = jdbcTemplate.query("SELECT COALESCE(unit,'') FROM inventory_items WHERE id=?", (rs, n) -> rs.getString(1), itemId);
        return values.isEmpty() ? "" : values.get(0);
    }

    private List<ObjectNode> queryList(String sql, RowMapper<ObjectNode> mapper, Object... args) {
        return jdbcTemplate.query(sql, mapper, args);
    }


    public byte[] exportXlsx(ObjectNode lifecycle) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("全链路追溯");
            ObjectNode summary = (ObjectNode) lifecycle.path("summary");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("内部追溯 · " + summary.path("itemName").asText(""));
            String[] headers = { "时间", "类型", "单据号", "物资", "批次", "数量", "单位", "科室", "经办人", "状态", "备注" };
            Row header = sheet.createRow(2);
            for (int index = 0; index < headers.length; index += 1) header.createCell(index).setCellValue(headers[index]);
            int rowIndex = 3;
            for (var node : lifecycle.path("events")) {
                ObjectNode event = (ObjectNode) node;
                Row row = sheet.createRow(rowIndex++);
                String[] values = {
                    event.path("occurredAt").asText(""), event.path("eventLabel").asText(""),
                    event.path("documentNo").asText(""), summary.path("itemName").asText(""),
                    event.path("batchNo").asText(""), event.path("quantity").asText(""),
                    event.path("unit").asText(""), event.path("department").asText(""),
                    event.path("operator").asText(""), event.path("status").asText(""),
                    event.path("note").asText("")
                };
                for (int index = 0; index < values.length; index += 1) row.createCell(index).setCellValue(values[index]);
            }
            for (int index = 0; index < headers.length; index += 1) sheet.setColumnWidth(index, 4200);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("追溯 XLSX 导出失败", error);
        }
    }

    public byte[] exportPdf(ObjectNode lifecycle) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ObjectNode summary = (ObjectNode) lifecycle.path("summary");
            Document document = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
            PdfWriter.getInstance(document, output);
            document.open();
            Font titleFont = new Font(chineseBaseFont(), 16, Font.BOLD);
            Font bodyFont = new Font(chineseBaseFont(), 9);
            document.add(new Paragraph("内部追溯 · " + summary.path("itemName").asText("") + " 全链路摘要", titleFont));
            document.add(new Paragraph("进 " + summary.path("inboundTotal").asText("0") + " · 用 "
                + summary.path("usedTotal").asText("0") + " · 中心存 " + summary.path("centralAvailable").asText("0")
                + " · 科室存 " + summary.path("departmentAvailable").asText("0")
                + " · 待补扣 " + summary.path("pendingDeductionCount").asText("0"), bodyFont));
            PdfPTable table = new PdfPTable(6);
            table.setWidthPercentage(100);
            for (String text : new String[] { "时间", "类型", "单据", "数量", "科室", "经办人" }) {
                PdfPCell cell = new PdfPCell(new Paragraph(text, bodyFont));
                cell.setPadding(4);
                table.addCell(cell);
            }
            int count = 0;
            for (var node : lifecycle.path("events")) {
                if (count++ >= 20) break;
                ObjectNode event = (ObjectNode) node;
                for (String text : new String[] {
                    event.path("occurredAt").asText(""), event.path("eventLabel").asText(""),
                    event.path("documentNo").asText(""), event.path("quantity").asText(""),
                    event.path("department").asText(""), event.path("operator").asText("")
                }) {
                    PdfPCell cell = new PdfPCell(new Paragraph(text, bodyFont));
                    cell.setPadding(3);
                    table.addCell(cell);
                }
            }
            document.add(table);
            document.close();
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("追溯 PDF 导出失败", error);
        }
    }

    private static BaseFont chineseBaseFont() throws Exception {
        Path simsun = Path.of("C:/Windows/Fonts/simsun.ttc");
        if (Files.exists(simsun)) return BaseFont.createFont(simsun.toString() + ",0", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        throw new IllegalStateException("未找到中文字体，无法生成 PDF");
    }
    private static String movementLabel(String type) {
        return switch (type) {
            case "TRANSFER_TO_TRANSIT" -> "仓库发放";
            case "TRANSFER_TO_DEPARTMENT" -> "科室签收";
            case "RETURN_TO_CENTRAL" -> "科室退回";
            case "CONSUMPTION" -> "使用消耗";
            case "CONSUMPTION_REVERSAL" -> "消耗冲销";
            case "SCRAP" -> "报废";
            case "COUNT_ADJUSTMENT_IN" -> "盘盈";
            case "COUNT_ADJUSTMENT_OUT" -> "盘亏";
            case "INBOUND_REVERSAL" -> "入库红冲";
            default -> type;
        };
    }

    private static String eventType(String type) {
        return switch (type) {
            case "TRANSFER_TO_TRANSIT" -> "TRANSFER";
            case "TRANSFER_TO_DEPARTMENT" -> "RECEIVE";
            case "RETURN_TO_CENTRAL" -> "RETURN";
            case "SCRAP" -> "SCRAP";
            case "COUNT_ADJUSTMENT_IN", "COUNT_ADJUSTMENT_OUT" -> "COUNT";
            case "INBOUND_REVERSAL", "CONSUMPTION_REVERSAL" -> "REVERSAL";
            case "CONSUMPTION" -> "CONSUMPTION";
            default -> type;
        };
    }

    private static BigDecimal signedQuantity(String type, BigDecimal quantity) {
        return switch (type) {
            case "TRANSFER_TO_TRANSIT", "RETURN_TO_CENTRAL", "SCRAP", "COUNT_ADJUSTMENT_OUT",
                 "INBOUND_REVERSAL", "CONSUMPTION" -> quantity.negate();
            default -> quantity;
        };
    }

    private static boolean isAbnormalMovement(String type) {
        return Set.of("RETURN_TO_CENTRAL", "SCRAP", "COUNT_ADJUSTMENT_OUT", "INBOUND_REVERSAL",
            "CONSUMPTION_REVERSAL").contains(type);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
