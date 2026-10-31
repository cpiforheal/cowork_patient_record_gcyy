package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.InventoryAccessService;
import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class InventoryStockOverviewService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryLedgerRepository ledgerRepository;
    private final InventoryAccessService accessService;

    public InventoryStockOverviewService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        InventoryLedgerRepository ledgerRepository,
        InventoryAccessService accessService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.ledgerRepository = ledgerRepository;
        this.accessService = accessService;
    }

    public ObjectNode warehouseStock(String keyword, String category, String filter) {
        String like = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String categoryFilter = category == null ? "" : category.trim();
        ArrayNode items = queryArray(
            """
            SELECT i.id, i.name, i.spec, i.category, i.unit, i.base_unit, i.issue_unit,
                   COALESCE(i.low_stock_threshold, 0) low_stock_threshold, i.location,
                   (SELECT COALESCE(SUM(b.quantity - b.reserved_quantity), 0)
                    FROM inventory_batch_balances b
                    WHERE b.item_id = i.id AND b.location_id = 'loc-central') central_quantity,
                   (SELECT COALESCE(SUM(b.quantity - b.reserved_quantity), 0)
                    FROM inventory_batch_balances b
                    JOIN inventory_locations l ON l.id = b.location_id
                    WHERE b.item_id = i.id AND l.location_type = 'DEPARTMENT') department_quantity,
                   (SELECT MAX(o.created_at)
                    FROM inventory_inbound_lines il
                    JOIN inventory_inbound_orders o ON o.id = il.order_id
                    WHERE il.item_id = i.id AND o.status = 'ACTIVE') latest_inbound_at
            FROM inventory_items i
            WHERE i.enabled = 1
            ORDER BY i.name
            """,
            (resultSet, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("itemId", resultSet.getString("id"));
                row.put("name", resultSet.getString("name"));
                row.put("spec", nz(resultSet.getString("spec")));
                row.put("category", nz(resultSet.getString("category")));
                row.put("unit", nz(resultSet.getString("unit")));
                row.put("baseUnit", nz(resultSet.getString("base_unit")));
                row.put("issueUnit", nz(resultSet.getString("issue_unit")));
                BigDecimal threshold = scale(resultSet.getBigDecimal("low_stock_threshold"));
                BigDecimal central = scale(resultSet.getBigDecimal("central_quantity"));
                row.put("warningLine", threshold);
                row.put("centralQuantity", central);
                row.put("departmentQuantity", scale(resultSet.getBigDecimal("department_quantity")));
                row.put("lowStock", threshold.signum() > 0 && central.compareTo(threshold) < 0);
                row.put("location", nz(resultSet.getString("location")));
                row.put("latestInboundAt", resultSet.getTimestamp("latest_inbound_at") == null
                    ? "" : resultSet.getTimestamp("latest_inbound_at").toLocalDateTime().toString());
                return row;
            }
        );
        ArrayNode filtered = objectMapper.createArrayNode();
        for (var node : items) {
            ObjectNode row = (ObjectNode) node;
            if (!like.isBlank()) {
                String haystack = (row.path("name").asText() + " " + row.path("spec").asText() + " " + row.path("category").asText())
                    .toLowerCase(Locale.ROOT);
                if (!haystack.contains(like)) continue;
            }
            if (!categoryFilter.isBlank() && !categoryFilter.equals(row.path("category").asText())) continue;
            if ("LOW".equalsIgnoreCase(filter) && !row.path("lowStock").asBoolean()) continue;
            filtered.add(row);
        }
        ArrayNode batches = queryArray(
            """
            SELECT b.batch_id, b.item_id, b.quantity - b.reserved_quantity quantity, b.location_id,
                   loc.location_type, loc.department_id, loc.department_name_snapshot,
                   ib.batch_no, ib.expiry_date, i.name item_name, i.unit,
                   (SELECT o.supplier FROM inventory_inbound_lines il
                    JOIN inventory_inbound_orders o ON o.id = il.order_id
                    WHERE il.batch_id = b.batch_id AND o.status = 'ACTIVE'
                    ORDER BY o.created_at DESC LIMIT 1) supplier,
                   (SELECT o.purchase_date FROM inventory_inbound_lines il
                    JOIN inventory_inbound_orders o ON o.id = il.order_id
                    WHERE il.batch_id = b.batch_id AND o.status = 'ACTIVE'
                    ORDER BY o.created_at DESC LIMIT 1) purchase_date,
                   (SELECT o.order_no FROM inventory_inbound_lines il
                    JOIN inventory_inbound_orders o ON o.id = il.order_id
                    WHERE il.batch_id = b.batch_id AND o.status = 'ACTIVE'
                    ORDER BY o.created_at DESC LIMIT 1) order_no
            FROM inventory_batch_balances b
            JOIN inventory_items i ON i.id = b.item_id
            LEFT JOIN inventory_batches ib ON ib.id = b.batch_id
            LEFT JOIN inventory_locations loc ON loc.id = b.location_id
            WHERE i.enabled = 1 AND (b.quantity - b.reserved_quantity) <> 0
            ORDER BY i.name, ib.expiry_date, b.batch_id
            """,
            (resultSet, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("batchId", resultSet.getString("batch_id"));
                row.put("itemId", resultSet.getString("item_id"));
                row.put("itemName", resultSet.getString("item_name"));
                row.put("unit", nz(resultSet.getString("unit")));
                row.put("batchNo", nz(resultSet.getString("batch_no")));
                row.put("expiryDate", nz(resultSet.getString("expiry_date")));
                row.put("quantity", scale(resultSet.getBigDecimal("quantity")));
                row.put("locationId", nz(resultSet.getString("location_id")));
                row.put("locationType", nz(resultSet.getString("location_type")));
                row.put("departmentId", nz(resultSet.getString("department_id")));
                row.put("departmentName", nz(resultSet.getString("department_name_snapshot")));
                row.put("supplier", nz(resultSet.getString("supplier")));
                row.put("purchaseDate", resultSet.getDate("purchase_date") == null
                    ? "" : resultSet.getDate("purchase_date").toLocalDate().toString());
                row.put("orderNo", nz(resultSet.getString("order_no")));
                row.put("expiring", isExpiring(row.path("expiryDate").asText()));
                return row;
            }
        );
        ObjectNode result = objectMapper.createObjectNode();
        result.set("items", filtered);
        result.set("batches", batches);
        result.put("today", LocalDate.now().toString());
        return result;
    }

    public ObjectNode alerts() {
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        ArrayNode centralLow = queryArray(
            """
            SELECT i.id, i.name, i.spec, i.unit, COALESCE(i.low_stock_threshold, 0) warning_line,
                   (SELECT COALESCE(SUM(b.quantity - b.reserved_quantity), 0)
                    FROM inventory_batch_balances b
                    WHERE b.item_id = i.id AND b.location_id = 'loc-central') central_quantity
            FROM inventory_items i
            WHERE i.enabled = 1 AND COALESCE(i.low_stock_threshold, 0) > 0
            HAVING central_quantity < warning_line
            ORDER BY (warning_line - central_quantity) DESC, i.name
            """,
            (resultSet, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("itemId", resultSet.getString("id"));
                row.put("itemName", resultSet.getString("name"));
                row.put("spec", nz(resultSet.getString("spec")));
                row.put("unit", nz(resultSet.getString("unit")));
                row.put("warningLine", scale(resultSet.getBigDecimal("warning_line")));
                row.put("quantity", scale(resultSet.getBigDecimal("central_quantity")));
                return row;
            }
        );
        ArrayNode departmentLow = queryArray(
            """
            SELECT l.department_id, l.department_name_snapshot, i.id item_id, i.name, i.spec, i.unit,
                   SUM(b.quantity - b.reserved_quantity) available,
                   (SELECT COALESCE(SUM(CASE WHEN m.movement_type = 'TRANSFER_TO_DEPARTMENT' THEN m.quantity
                                             WHEN m.movement_type = 'RETURN_TO_CENTRAL' THEN -m.quantity
                                             ELSE 0 END), 0)
                    FROM inventory_ledger_movements m
                    WHERE m.department_id = l.department_id AND m.item_id = i.id
                      AND m.occurred_at >= ?) month_inbound
            FROM inventory_batch_balances b
            JOIN inventory_locations l ON l.id = b.location_id AND l.location_type = 'DEPARTMENT'
            JOIN inventory_items i ON i.id = b.item_id
            WHERE i.enabled = 1
            GROUP BY l.department_id, l.department_name_snapshot, i.id, i.name, i.spec, i.unit
            HAVING month_inbound > 0 AND available < month_inbound * 0.1
            ORDER BY (month_inbound * 0.1 - available) DESC, l.department_name_snapshot, i.name
            """,
            (resultSet, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("departmentId", resultSet.getString("department_id"));
                row.put("departmentName", nz(resultSet.getString("department_name_snapshot")));
                row.put("itemId", resultSet.getString("item_id"));
                row.put("itemName", resultSet.getString("name"));
                row.put("spec", nz(resultSet.getString("spec")));
                row.put("unit", nz(resultSet.getString("unit")));
                row.put("available", scale(resultSet.getBigDecimal("available")));
                row.put("monthInbound", scale(resultSet.getBigDecimal("month_inbound")));
                row.put("warningLine", scale(resultSet.getBigDecimal("month_inbound").multiply(new BigDecimal("0.1"))));
                return row;
            },
            monthStart.atStartOfDay()
        );
        ArrayNode expiring = queryArray(
            """
            SELECT b.batch_id, b.item_id, i.name, i.unit, ib.batch_no, ib.expiry_date,
                   b.quantity - b.reserved_quantity quantity, l.location_type, l.department_name_snapshot
            FROM inventory_batch_balances b
            JOIN inventory_items i ON i.id = b.item_id
            JOIN inventory_batches ib ON ib.id = b.batch_id
            LEFT JOIN inventory_locations l ON l.id = b.location_id
            WHERE i.enabled = 1 AND (b.quantity - b.reserved_quantity) > 0
              AND ib.expiry_date IS NOT NULL AND ib.expiry_date <> ''
              AND ib.expiry_date <= ?
            ORDER BY ib.expiry_date, i.name
            """,
            (resultSet, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("batchId", resultSet.getString("batch_id"));
                row.put("itemId", resultSet.getString("item_id"));
                row.put("itemName", resultSet.getString("name"));
                row.put("unit", nz(resultSet.getString("unit")));
                row.put("batchNo", nz(resultSet.getString("batch_no")));
                row.put("expiryDate", nz(resultSet.getString("expiry_date")));
                row.put("quantity", scale(resultSet.getBigDecimal("quantity")));
                row.put("locationType", nz(resultSet.getString("location_type")));
                row.put("departmentName", nz(resultSet.getString("department_name_snapshot")));
                return row;
            },
            LocalDate.now().plusDays(30).toString()
        );
        ObjectNode result = objectMapper.createObjectNode();
        result.set("centralLow", centralLow);
        result.set("departmentLow", departmentLow);
        result.set("expiring", expiring);
        return result;
    }

    public ObjectNode departmentStock(SessionUser user, String requestedDepartmentId) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        boolean allDepartments = accessService.canViewAllDepartments(user);
        String departmentId;
        if (allDepartments) {
            if (requestedDepartmentId == null || requestedDepartmentId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择科室");
            }
            departmentId = ledgerRepository.resolveDepartmentId(requestedDepartmentId, requestedDepartmentId);
        } else {
            departmentId = ledgerRepository.resolveDepartmentId(user.activeDepartmentId(), user.department());
        }
        String locationId = ledgerRepository.departmentLocation(departmentId);
        String departmentName = ledgerRepository.departmentName(departmentId);
        String monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay().toString();
        ArrayNode items = queryArray(
            """
            SELECT i.id, i.name, i.spec, i.category, i.unit, i.base_unit, i.issue_unit,
                   (SELECT COALESCE(SUM(b.quantity - b.reserved_quantity), 0)
                    FROM inventory_batch_balances b WHERE b.item_id = i.id AND b.location_id = ?) dept_available,
                   (SELECT COALESCE(SUM(b.quantity - b.reserved_quantity), 0)
                    FROM inventory_batch_balances b WHERE b.item_id = i.id AND b.location_id = 'loc-central') central_available,
                   (SELECT COALESCE(SUM(CASE WHEN m.movement_type = 'TRANSFER_TO_DEPARTMENT' THEN m.quantity
                                             WHEN m.movement_type = 'RETURN_TO_CENTRAL' THEN -m.quantity
                                             ELSE 0 END), 0)
                    FROM inventory_ledger_movements m
                    WHERE m.department_id = ? AND m.item_id = i.id AND m.occurred_at >= ?) month_inbound,
                   (SELECT COALESCE(SUM(d.quantity), 0)
                    FROM inventory_consumption_details d
                    JOIN inventory_consumption_events e ON e.id = d.event_id
                    WHERE e.department_id = ? AND d.item_id = i.id
                      AND e.status = 'succeeded' AND e.created_at >= ?) month_used
            FROM inventory_items i
            WHERE i.enabled = 1
            ORDER BY i.name
            """,
            (resultSet, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                BigDecimal monthInbound = scale(resultSet.getBigDecimal("month_inbound"));
                BigDecimal available = scale(resultSet.getBigDecimal("dept_available"));
                BigDecimal warningLine = monthInbound.multiply(new BigDecimal("0.1")).setScale(2, RoundingMode.HALF_UP);
                boolean lowStock = monthInbound.signum() > 0 && available.compareTo(warningLine) < 0;
                row.put("itemId", resultSet.getString("id"));
                row.put("name", resultSet.getString("name"));
                row.put("spec", nz(resultSet.getString("spec")));
                row.put("category", nz(resultSet.getString("category")));
                row.put("unit", nz(resultSet.getString("unit")));
                row.put("baseUnit", nz(resultSet.getString("base_unit")));
                row.put("issueUnit", nz(resultSet.getString("issue_unit")));
                row.put("monthInbound", monthInbound);
                row.put("monthUsed", scale(resultSet.getBigDecimal("month_used")));
                row.put("deptAvailable", available);
                row.put("centralAvailable", scale(resultSet.getBigDecimal("central_available")));
                row.put("deptWarningLine", warningLine);
                row.put("lowStock", lowStock);
                row.put("suggestedQuantity", lowStock
                    ? monthInbound.subtract(available).max(BigDecimal.ONE).setScale(2, RoundingMode.CEILING)
                    : BigDecimal.ZERO);
                return row;
            },
            locationId, departmentId, monthStart, departmentId, monthStart
        );
        ObjectNode result = objectMapper.createObjectNode();
        result.put("departmentId", departmentId);
        result.put("departmentName", departmentName);
        result.put("month", LocalDate.now().withDayOfMonth(1).toString());
        result.set("items", items);
        return result;
    }

    private ArrayNode queryArray(String sql, RowMapper<ObjectNode> mapper, Object... args) {
        ArrayNode result = objectMapper.createArrayNode();
        jdbcTemplate.query(sql, mapper, args).forEach(result::add);
        return result;
    }

    private boolean isExpiring(String expiryDate) {
        if (expiryDate == null || expiryDate.isBlank()) return false;
        try {
            LocalDate expiry = LocalDate.parse(expiryDate);
            return !expiry.isAfter(LocalDate.now().plusDays(30));
        } catch (Exception error) {
            return false;
        }
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
