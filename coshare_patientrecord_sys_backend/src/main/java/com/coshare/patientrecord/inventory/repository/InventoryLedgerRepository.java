package com.coshare.patientrecord.inventory.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("mysql")
public class InventoryLedgerRepository {

    public static final String CENTRAL_LOCATION = "loc-central";
    public static final String TRANSIT_LOCATION = "loc-in-transit";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public InventoryLedgerRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public String resolveDepartmentId(String departmentId, String departmentName) {
        if (departmentId != null && !departmentId.isBlank()) {
            List<String> found = jdbcTemplate.query(
                "SELECT id FROM clinic_departments WHERE id = ? LIMIT 1",
                (rs, rowNum) -> rs.getString(1),
                departmentId
            );
            if (!found.isEmpty()) return found.get(0);
        }
        if (departmentName == null || departmentName.isBlank()) {
            throw new IllegalArgumentException("科室标识不能为空");
        }
        List<String> found = jdbcTemplate.query(
            "SELECT id FROM clinic_departments WHERE name = ? ORDER BY id LIMIT 1",
            (rs, rowNum) -> rs.getString(1),
            departmentName
        );
        if (found.isEmpty()) throw new IllegalArgumentException("未找到对应科室：" + departmentName);
        return found.get(0);
    }

    public String departmentName(String departmentId) {
        List<String> names = jdbcTemplate.query(
            "SELECT name FROM clinic_departments WHERE id = ? LIMIT 1",
            (rs, rowNum) -> rs.getString(1),
            departmentId
        );
        return names.isEmpty() ? departmentId : names.get(0);
    }

    public String departmentLocation(String departmentId) {
        String name = departmentName(departmentId);
        String id = "loc-dept-" + shaKey(departmentId);
        jdbcTemplate.update(
            """
            INSERT INTO inventory_locations
              (id, location_type, department_id, department_name_snapshot, name, status, opening_confirmed)
            VALUES (?, 'DEPARTMENT', ?, ?, ?, 'ACTIVE', FALSE)
            ON DUPLICATE KEY UPDATE department_name_snapshot = VALUES(department_name_snapshot),
              name = VALUES(name), status = VALUES(status)
            """,
            id,
            departmentId,
            name,
            name + "科室库"
        );
        return jdbcTemplate.queryForObject(
            "SELECT id FROM inventory_locations WHERE location_type = 'DEPARTMENT' AND department_id = ?",
            String.class,
            departmentId
        );
    }

    public boolean isOpeningConfirmed(String departmentId) {
        Boolean value = jdbcTemplate.queryForObject(
            "SELECT opening_confirmed FROM inventory_locations WHERE id = ?",
            Boolean.class,
            departmentLocation(departmentId)
        );
        return Boolean.TRUE.equals(value);
    }

    public void ensureBalance(String locationId, String itemId, String batchId) {
        String id = balanceId(locationId, batchId);
        jdbcTemplate.update(
            """
            INSERT INTO inventory_batch_balances
              (id, location_id, item_id, batch_id, quantity, reserved_quantity, version)
            VALUES (?, ?, ?, ?, 0, 0, 0)
            ON DUPLICATE KEY UPDATE item_id = VALUES(item_id)
            """,
            id,
            locationId,
            itemId,
            batchId
        );
    }

    public Balance lockBalance(String locationId, String itemId, String batchId) {
        ensureBalance(locationId, itemId, batchId);
        return jdbcTemplate.queryForObject(
            """
            SELECT b.id, b.location_id, b.item_id, b.batch_id, b.quantity, b.reserved_quantity,
                   COALESCE(ib.batch_no, '') batch_no, COALESCE(ib.expiry_date, '') expiry_date
            FROM inventory_batch_balances b
            LEFT JOIN inventory_batches ib ON ib.id = b.batch_id
            WHERE b.location_id = ? AND b.item_id = ? AND b.batch_id = ?
            FOR UPDATE
            """,
            this::mapBalance,
            locationId,
            itemId,
            batchId
        );
    }

    public List<Balance> lockAvailableBalances(String locationId, String itemId) {
        return jdbcTemplate.query(
            """
            SELECT b.id, b.location_id, b.item_id, b.batch_id, b.quantity, b.reserved_quantity,
                   COALESCE(ib.batch_no, '') batch_no, COALESCE(ib.expiry_date, '') expiry_date
            FROM inventory_batch_balances b
            JOIN inventory_batches ib ON ib.id = b.batch_id
            WHERE b.location_id = ? AND b.item_id = ? AND b.quantity > 0
            ORDER BY CASE WHEN ib.expiry_date IS NULL OR ib.expiry_date = '' THEN 1 ELSE 0 END,
                     ib.expiry_date, ib.updated_at, ib.id
            FOR UPDATE
            """,
            this::mapBalance,
            locationId,
            itemId
        );
    }

    public void changeBalance(String balanceId, BigDecimal quantityDelta, BigDecimal reservedDelta) {
        int updated = jdbcTemplate.update(
            """
            UPDATE inventory_batch_balances
            SET quantity = quantity + ?, reserved_quantity = reserved_quantity + ?, version = version + 1
            WHERE id = ? AND quantity + ? >= 0 AND reserved_quantity + ? >= 0
              AND reserved_quantity + ? <= quantity + ?
            """,
            scale(quantityDelta),
            scale(reservedDelta),
            balanceId,
            scale(quantityDelta),
            scale(reservedDelta),
            scale(reservedDelta),
            scale(quantityDelta)
        );
        if (updated != 1) throw new IllegalStateException("库存余额已变化，请重试");
    }

    public void syncLegacyCentralBatch(String batchId) {
        jdbcTemplate.update(
            """
            UPDATE inventory_batches b
            JOIN inventory_batch_balances bal ON bal.batch_id = b.id AND bal.location_id = 'loc-central'
            SET b.quantity = bal.quantity,
                b.raw_json = JSON_SET(b.raw_json, '$.quantity', bal.quantity)
            WHERE b.id = ?
            """,
            batchId
        );
    }

    public void movement(
        String type,
        String itemId,
        String batchId,
        String fromLocation,
        String toLocation,
        BigDecimal quantity,
        String departmentId,
        String relatedType,
        String relatedId,
        String consumptionEventId,
        String reversalOfMovementId,
        String operator,
        String reason
    ) {
        String id = "ledger-" + UUID.randomUUID();
        String department = departmentId == null || departmentId.isBlank() ? "" : departmentName(departmentId);
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("id", id);
        raw.put("type", type);
        raw.put("itemId", itemId);
        raw.put("batchId", batchId == null ? "" : batchId);
        raw.put("fromLocationId", fromLocation == null ? "" : fromLocation);
        raw.put("toLocationId", toLocation == null ? "" : toLocation);
        raw.put("quantity", scale(quantity));
        raw.put("departmentId", departmentId == null ? "" : departmentId);
        raw.put("department", department);
        raw.put("relatedType", relatedType == null ? "" : relatedType);
        raw.put("relatedId", relatedId == null ? "" : relatedId);
        raw.put("operator", operator == null ? "system" : operator);
        raw.put("reason", reason == null ? "" : reason);
        raw.put("occurredAt", LocalDateTime.now().toString());
        jdbcTemplate.update(
            """
            INSERT INTO inventory_ledger_movements
              (id, item_id, batch_id, from_location_id, to_location_id, movement_type, quantity,
               department_id, department_name_snapshot, related_type, related_id, consumption_event_id,
               reversal_of_movement_id, operator_name, reason, occurred_at, raw_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3), ?)
            """,
            id,
            itemId,
            blankToNull(batchId),
            blankToNull(fromLocation),
            blankToNull(toLocation),
            type,
            scale(quantity).abs(),
            blankToNull(departmentId),
            department,
            blankToNull(relatedType),
            blankToNull(relatedId),
            blankToNull(consumptionEventId),
            blankToNull(reversalOfMovementId),
            operator == null ? "system" : operator,
            reason == null ? "" : reason,
            json(raw)
        );
    }

    public ObjectNode confirmOpening(String departmentId, JsonNode payload, String operator) {
        String locationId = departmentLocation(departmentId);
        Boolean confirmed = jdbcTemplate.queryForObject(
            "SELECT opening_confirmed FROM inventory_locations WHERE id = ? FOR UPDATE",
            Boolean.class,
            locationId
        );
        if (Boolean.TRUE.equals(confirmed)) return openingState(departmentId);
        Map<String, BigDecimal> overrides = new java.util.HashMap<>();
        JsonNode lines = payload == null ? null : payload.path("lines");
        if (lines != null && lines.isArray()) {
            for (JsonNode line : lines) {
                String batchId = line.path("batchId").asText("");
                if (!batchId.isBlank()) overrides.put(batchId, decimal(line.path("quantity").asText("0")));
            }
        }
        List<OpeningSuggestion> suggestions = jdbcTemplate.query(
            """
            SELECT item_id, batch_id, suggested_quantity
            FROM inventory_opening_suggestions
            WHERE department_id = ? AND status = 'PENDING'
            ORDER BY item_id, batch_id FOR UPDATE
            """,
            (rs, rowNum) -> new OpeningSuggestion(
                rs.getString("item_id"),
                rs.getString("batch_id"),
                rs.getBigDecimal("suggested_quantity")
            ),
            departmentId
        );
        for (OpeningSuggestion suggestion : suggestions) {
            BigDecimal confirmedQuantity = scale(overrides.getOrDefault(suggestion.batchId(), suggestion.quantity()));
            if (confirmedQuantity.signum() < 0) throw new IllegalArgumentException("科室期初数量不能为负数");
            Balance balance = lockBalance(locationId, suggestion.itemId(), suggestion.batchId());
            if (confirmedQuantity.signum() > 0) {
                changeBalance(balance.id(), confirmedQuantity, BigDecimal.ZERO);
                movement(
                    "OPENING_BALANCE", suggestion.itemId(), suggestion.batchId(), null, locationId,
                    confirmedQuantity, departmentId, "OPENING_CONFIRMATION", departmentId, null, null,
                    operator, "科室期初盘点确认"
                );
            }
            jdbcTemplate.update(
                """
                UPDATE inventory_opening_suggestions
                SET confirmed_quantity = ?, status = 'CONFIRMED', confirmed_by = ?, confirmed_at = CURRENT_TIMESTAMP(3)
                WHERE department_id = ? AND batch_id = ?
                """,
                confirmedQuantity,
                operator,
                departmentId,
                suggestion.batchId()
            );
        }
        jdbcTemplate.update(
            """
            UPDATE inventory_locations
            SET opening_confirmed = TRUE, opening_confirmed_by = ?, opening_confirmed_at = CURRENT_TIMESTAMP(3)
            WHERE id = ?
            """,
            operator,
            locationId
        );
        return openingState(departmentId);
    }

    public ObjectNode openingState(String departmentId) {
        String locationId = departmentLocation(departmentId);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("departmentId", departmentId);
        result.put("department", departmentName(departmentId));
        Boolean confirmed = jdbcTemplate.queryForObject(
            "SELECT opening_confirmed FROM inventory_locations WHERE id = ?",
            Boolean.class,
            locationId
        );
        result.put("confirmed", Boolean.TRUE.equals(confirmed));
        result.set("suggestions", queryJson(
            """
            SELECT s.department_id departmentId, s.department_name_snapshot department,
                   s.item_id itemId, i.name itemName, s.batch_id batchId,
                   b.batch_no batchNo, s.suggested_quantity suggestedQuantity,
                   s.confirmed_quantity confirmedQuantity, s.status
            FROM inventory_opening_suggestions s
            JOIN inventory_items i ON i.id = s.item_id
            LEFT JOIN inventory_batches b ON b.id = s.batch_id
            WHERE s.department_id = ? ORDER BY i.name, b.expiry_date, b.batch_no
            """,
            departmentId
        ));
        return result;
    }

    public ArrayNode readBalances(String departmentId, String itemId) {
        return readBalances(departmentId, itemId, false);
    }

    public ArrayNode readWeeklySuggestions(String departmentId) {
        String locationId = departmentLocation(departmentId);
        return queryJson(
            """
            SELECT CONCAT(DATE_FORMAT(CURRENT_DATE, '%x-W%v'), ':', ?, ':', i.id) id,
                   ? departmentId, ? departmentName, i.id itemId, i.name itemName, i.unit,
                   COALESCE(consumption.actual_quantity, 0) actualConsumption,
                   COALESCE(balance.available_quantity, 0) availableQuantity,
                   COALESCE(i.safety_stock, 0) safetyQuantity,
                   GREATEST(
                     COALESCE(consumption.actual_quantity, 0) + COALESCE(i.safety_stock, 0)
                       - COALESCE(balance.available_quantity, 0),
                     0
                   ) suggestedQuantity,
                   CASE
                     WHEN COALESCE(balance.available_quantity, 0) < COALESCE(i.safety_stock, 0)
                       THEN '当前结存低于安全库存'
                     WHEN COALESCE(consumption.actual_quantity, 0) > 0
                       THEN '按本周实际耗用、当前结存和安全库存计算'
                     ELSE '本周暂无实际耗用'
                   END reason
            FROM inventory_items i
            LEFT JOIN (
              SELECT item_id,
                     SUM(CASE WHEN movement_type = 'CONSUMPTION' THEN quantity
                              WHEN movement_type = 'CONSUMPTION_REVERSAL' THEN -quantity ELSE 0 END) actual_quantity
              FROM inventory_ledger_movements
              WHERE department_id = ?
                AND DATE_FORMAT(occurred_at, '%x-W%v') = DATE_FORMAT(CURRENT_DATE, '%x-W%v')
              GROUP BY item_id
            ) consumption ON consumption.item_id = i.id
            LEFT JOIN (
              SELECT item_id, SUM(quantity - reserved_quantity) available_quantity
              FROM inventory_batch_balances
              WHERE location_id = ?
              GROUP BY item_id
            ) balance ON balance.item_id = i.id
            WHERE i.enabled = TRUE
            ORDER BY suggestedQuantity DESC, actualConsumption DESC, i.name
            """,
            departmentId,
            departmentId,
            departmentName(departmentId),
            departmentId,
            locationId
        );
    }

    public ArrayNode readWorkbenchBalances(String departmentId, String itemId) {
        return readBalances(departmentId, itemId, true);
    }

    private ArrayNode readBalances(String departmentId, String itemId, boolean includeCentral) {
        StringBuilder sql = new StringBuilder("""
            SELECT l.id locationId, l.name locationName, l.location_type locationType, l.department_id departmentId,
                   l.department_name_snapshot department, l.opening_confirmed openingConfirmed,
                   b.item_id itemId, i.name itemName, i.unit, b.batch_id batchId,
                   ib.batch_no batchNo, ib.expiry_date expiryDate,
                   b.quantity, b.reserved_quantity reservedQuantity,
                   (b.quantity - b.reserved_quantity) availableQuantity, b.version
            FROM inventory_batch_balances b
            JOIN inventory_locations l ON l.id = b.location_id
            JOIN inventory_items i ON i.id = b.item_id
            LEFT JOIN inventory_batches ib ON ib.id = b.batch_id
            WHERE 1 = 1
            """);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (departmentId != null && !departmentId.isBlank()) {
            sql.append(includeCentral ? " AND (l.department_id = ? OR l.location_type = 'CENTRAL')" : " AND l.department_id = ?");
            args.add(departmentId);
        }
        if (itemId != null && !itemId.isBlank()) {
            sql.append(" AND b.item_id = ?");
            args.add(itemId);
        }
        sql.append(" ORDER BY l.location_type, l.department_name_snapshot, i.name, ib.expiry_date, ib.batch_no");
        return queryJson(sql.toString(), args.toArray());
    }

    public ArrayNode readExceptions(String departmentId, String status) {
        StringBuilder sql = new StringBuilder("""
            SELECT e.id, e.command_id commandId, e.exception_type exceptionType, e.severity, e.status,
                   e.department_id departmentId, e.department_name_snapshot department,
                   e.encounter_id encounterId, e.trigger_stage triggerStage,
                   e.item_id itemId, i.name itemName, e.message,
                   e.retry_count retryCount, e.created_at createdAt, e.resolved_at resolvedAt
            FROM inventory_exception_tasks e
            LEFT JOIN inventory_items i ON i.id = e.item_id
            WHERE 1 = 1
            """);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (departmentId != null && !departmentId.isBlank()) {
            sql.append(" AND e.department_id = ?");
            args.add(departmentId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND e.status = ?");
            args.add(status.toUpperCase());
        }
        sql.append(" ORDER BY CASE e.severity WHEN 'HIGH' THEN 0 ELSE 1 END, e.created_at DESC");
        return queryJson(sql.toString(), args.toArray());
    }

    public ArrayNode readConsumptions(String departmentId, LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder("""
            SELECT e.id, e.command_id commandId, e.encounter_id encounterId, e.case_token caseToken,
                   e.department_id departmentId, e.department department,
                   e.trigger_stage triggerStage, e.completion_version completionVersion,
                   e.event_kind eventKind, e.status, e.visit_date visitDate,
                   e.package_id packageId, e.reversal_of_event_id reversalOfEventId,
                   d.item_id itemId, i.name itemName, i.unit, d.batch_id batchId,
                   b.batch_no batchNo, b.expiry_date expiryDate, d.quantity,
                   e.created_at createdAt
            FROM inventory_consumption_events e
            JOIN inventory_consumption_details d ON d.event_id = e.id
            JOIN inventory_items i ON i.id = d.item_id
            LEFT JOIN inventory_batches b ON b.id = d.batch_id
            WHERE e.status = 'succeeded'
            """);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (departmentId != null && !departmentId.isBlank()) {
            sql.append(" AND e.department_id = ?");
            args.add(departmentId);
        }
        if (from != null) {
            sql.append(" AND e.visit_date >= ?");
            args.add(from);
        }
        if (to != null) {
            sql.append(" AND e.visit_date <= ?");
            args.add(to);
        }
        sql.append(" ORDER BY e.visit_date DESC, e.created_at DESC, i.name");
        return queryJson(sql.toString(), args.toArray());
    }

    public ArrayNode queryJson(String sql, Object... args) {
        return jdbcTemplate.query(
            sql,
            rs -> {
                ArrayNode rows = objectMapper.createArrayNode();
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
            },
            args
        );
    }

    public JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }

    public static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal decimal(String value) {
        try {
            return scale(new BigDecimal(value));
        } catch (Exception ignored) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
    }

    public static String shaKey(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    public static String balanceId(String locationId, String batchId) {
        return "bal-" + shaKey(locationId + "|" + batchId);
    }

    private Balance mapBalance(ResultSet rs, int rowNum) throws SQLException {
        return new Balance(
            rs.getString("id"),
            rs.getString("location_id"),
            rs.getString("item_id"),
            rs.getString("batch_id"),
            scale(rs.getBigDecimal("quantity")),
            scale(rs.getBigDecimal("reserved_quantity")),
            rs.getString("batch_no"),
            rs.getString("expiry_date")
        );
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("库存流水序列化失败", error);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record Balance(
        String id,
        String locationId,
        String itemId,
        String batchId,
        BigDecimal quantity,
        BigDecimal reserved,
        String batchNo,
        String expiryDate
    ) {
        public BigDecimal available() {
            return quantity.subtract(reserved);
        }
    }

    private record OpeningSuggestion(String itemId, String batchId, BigDecimal quantity) {}
}
