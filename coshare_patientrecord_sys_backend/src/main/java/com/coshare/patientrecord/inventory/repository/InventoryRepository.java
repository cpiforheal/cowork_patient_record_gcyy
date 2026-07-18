package com.coshare.patientrecord.inventory.repository;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.inventory.service.builder.InventorySummaryBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class InventoryRepository {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventorySummaryBuilder summaryBuilder;

    public InventoryRepository(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        InventorySummaryBuilder summaryBuilder
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.summaryBuilder = summaryBuilder;
    }

    public ObjectNode readDb() {
        ObjectNode db = objectMapper.createObjectNode();
        db.set("items", readArray("inventory_items", "raw_json", "updated_at DESC, name ASC"));
        db.set("batches", readArray("inventory_batches", "raw_json", "updated_at DESC"));
        db.set("requests", normalizeStoredRequests(readArray("inventory_requests", "raw_json", "created_at DESC, id DESC")));
        db.set("weeklyConsumptions", readArray("inventory_weekly_consumption", "raw_json", "week_no DESC, department ASC"));
        db.set("counts", readArray("inventory_counts", "raw_json", "counted_at DESC, id DESC"));
        db.set("movements", readArray("inventory_movements", "raw_json", "created_at DESC, id DESC"));
        db.set("auditLogs", readArray("inventory_audit_logs", "raw_json", "created_at DESC, id DESC"));
        db.set("summary", summaryBuilder.build(db));
        return db;
    }

    public ObjectNode readDbForUser(SessionUser user) {
        ObjectNode db = readDb();
        if (isInventoryManager(user)) {
            return db;
        }
        String department = user.department();
        db.set("requests", filterByDepartment(db.path("requests"), department));
        db.set("weeklyConsumptions", filterByDepartment(db.path("weeklyConsumptions"), department));
        db.set("movements", filterByDepartment(db.path("movements"), department));
        db.set("batches", objectMapper.createArrayNode());
        db.set("counts", objectMapper.createArrayNode());
        db.set("auditLogs", objectMapper.createArrayNode());
        db.set("summary", summaryBuilder.build(db));
        return db;
    }

    private ArrayNode filterByDepartment(JsonNode rows, String department) {
        ArrayNode filtered = objectMapper.createArrayNode();
        if (rows == null || !rows.isArray() || department == null || department.isBlank()) {
            return filtered;
        }
        for (JsonNode row : rows) {
            if (department.equals(text(row, "department"))) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    public void upsertItem(ObjectNode item) {
        jdbcTemplate.update("""
            INSERT INTO inventory_items (
              id, name, category, spec, unit, location, low_stock_threshold,
              is_sensitive, batch_required, expiry_required, enabled, raw_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE name = VALUES(name), category = VALUES(category), spec = VALUES(spec),
              unit = VALUES(unit), location = VALUES(location), low_stock_threshold = VALUES(low_stock_threshold),
              is_sensitive = VALUES(is_sensitive), batch_required = VALUES(batch_required), expiry_required = VALUES(expiry_required),
              enabled = VALUES(enabled), raw_json = VALUES(raw_json)
            """,
            text(item, "id"), text(item, "name"), text(item, "category"), text(item, "spec"), text(item, "unit"),
            text(item, "location"), quantity(item, "lowStockThreshold"), item.path("sensitive").asBoolean(false),
            item.path("batchRequired").asBoolean(false), item.path("expiryRequired").asBoolean(false),
            item.path("enabled").asBoolean(true), toJson(item)
        );
    }

    public void upsertBatch(ObjectNode batch) {
        jdbcTemplate.update("""
            INSERT INTO inventory_batches (id, item_id, batch_no, expiry_date, quantity, location, raw_json)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE batch_no = VALUES(batch_no), expiry_date = VALUES(expiry_date),
              quantity = VALUES(quantity), location = VALUES(location), raw_json = VALUES(raw_json)
            """,
            text(batch, "id"), text(batch, "itemId"), text(batch, "batchNo"), text(batch, "expiryDate"),
            quantity(batch, "quantity"), text(batch, "location"), toJson(batch)
        );
    }

    public void upsertRequest(ObjectNode request) {
        ArrayNode lines = requestLines(request);
        applyRequestPrimaryFields(request, lines);
        jdbcTemplate.update("""
            INSERT INTO inventory_requests (
              id, department, applicant, owner, reason, expected_use_week, status,
              created_at, approved_at, issued_at, received_at, raw_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE department = VALUES(department), applicant = VALUES(applicant), owner = VALUES(owner),
              reason = VALUES(reason), expected_use_week = VALUES(expected_use_week), status = VALUES(status),
              approved_at = VALUES(approved_at), issued_at = VALUES(issued_at), received_at = VALUES(received_at),
              raw_json = VALUES(raw_json)
            """,
            text(request, "id"), text(request, "department"), text(request, "applicant"), text(request, "owner"),
            text(request, "reason"), text(request, "expectedUseWeek"), text(request, "status"),
            text(request, "createdAt"), text(request, "approvedAt"), text(request, "issuedAt"),
            text(request, "receivedAt"), toJson(request)
        );
        jdbcTemplate.update("DELETE FROM inventory_request_lines WHERE request_id = ?", text(request, "id"));
        for (JsonNode lineNode : lines) {
            ObjectNode line = object(lineNode);
            jdbcTemplate.update("""
                INSERT INTO inventory_request_lines (
                  id, request_id, item_id, requested_quantity, issued_quantity, line_status, raw_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                text(line, "id"), text(request, "id"), text(line, "itemId"), quantity(line, "quantity"),
                quantity(line, "issuedQuantity"), text(line, "status"), toJson(line)
            );
        }
    }

    public ObjectNode loadRequest(String id) {
        if (id.isBlank()) throw new IllegalArgumentException("申领单不存在");
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT raw_json FROM inventory_requests WHERE id = ? FOR UPDATE",
            (resultSet, rowNumber) -> readObject(resultSet, "raw_json"),
            id
        );
        if (rows.isEmpty()) throw new IllegalArgumentException("申领单不存在");
        ObjectNode request = rows.get(0);
        request.set("lines", normalizeRequestLines(request, text(request, "status", "pending")));
        return request;
    }

    public ObjectNode chooseBatch(String itemId, String batchId) {
        if (itemId.isBlank()) throw new IllegalArgumentException("请选择物资");
        List<ObjectNode> rows;
        if (!batchId.isBlank()) {
            rows = jdbcTemplate.query(
                "SELECT raw_json FROM inventory_batches WHERE id = ? AND item_id = ? FOR UPDATE",
                (resultSet, rowNumber) -> readObject(resultSet, "raw_json"),
                batchId,
                itemId
            );
        } else {
            rows = jdbcTemplate.query(
                "SELECT raw_json FROM inventory_batches WHERE item_id = ? AND quantity > 0 ORDER BY expiry_date ASC, updated_at ASC LIMIT 1 FOR UPDATE",
                (resultSet, rowNumber) -> readObject(resultSet, "raw_json"),
                itemId
            );
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("未找到可用库存批次");
        return rows.get(0);
    }

    public List<ObjectNode> chooseBatchesForIssue(String itemId, String batchId) {
        if (itemId.isBlank()) throw new IllegalArgumentException("请选择物资");
        if (!batchId.isBlank()) {
            return jdbcTemplate.query(
                "SELECT raw_json FROM inventory_batches WHERE id = ? AND item_id = ? AND quantity > 0 FOR UPDATE",
                (resultSet, rowNumber) -> readObject(resultSet, "raw_json"),
                batchId,
                itemId
            );
        }
        return jdbcTemplate.query(
            """
            SELECT raw_json FROM inventory_batches
            WHERE item_id = ? AND quantity > 0
            ORDER BY
              CASE WHEN expiry_date IS NULL OR expiry_date = '' THEN 1 ELSE 0 END ASC,
              expiry_date ASC,
              updated_at ASC
            FOR UPDATE
            """,
            (resultSet, rowNumber) -> readObject(resultSet, "raw_json"),
            itemId
        );
    }

    public BigDecimal issueLine(ObjectNode line, ObjectNode request, BigDecimal requestedIssue, String batchId, SessionUser user) {
        BigDecimal remainingToIssue = requestedIssue;
        BigDecimal issued = BigDecimal.ZERO;
        List<ObjectNode> batches = chooseBatchesForIssue(text(line, "itemId"), batchId);
        for (ObjectNode batch : batches) {
            if (remainingToIssue.signum() <= 0) break;
            BigDecimal available = quantity(batch, "quantity");
            if (available.signum() <= 0) continue;
            BigDecimal taken = available.min(remainingToIssue);
            putQuantity(batch, "quantity", available.subtract(taken));
            upsertBatch(batch);
            remainingToIssue = remainingToIssue.subtract(taken);
            issued = issued.add(taken);
            ArrayNode allocations = line.withArray("batchAllocations");
            ObjectNode allocation = objectMapper.createObjectNode();
            allocation.put("batchId", text(batch, "id"));
            allocation.put("batchNo", text(batch, "batchNo"));
            allocation.put("expiryDate", text(batch, "expiryDate"));
            putQuantity(allocation, "quantity", taken);
            allocation.put("issuedAt", now());
            allocation.put("issuer", user.name());
            allocations.add(allocation);
            movement("issue", text(line, "itemId"), text(batch, "id"), taken.negate(), text(request, "department"), user.name(), text(request, "reason"), text(request, "id"));
        }
        if (issued.signum() > 0) {
            putQuantity(line, "issuedQuantity", quantity(line, "issuedQuantity").add(issued));
            BigDecimal remaining = quantity(line, "quantity").subtract(quantity(line, "issuedQuantity"));
            line.put("status", remaining.signum() > 0 ? "partially_issued" : "issued");
        }
        return issued;
    }

    public BigDecimal requestedIssueQuantity(JsonNode payload, JsonNode issueLines, ObjectNode line, BigDecimal remaining) {
        if (issueLines != null && issueLines.isArray()) {
            for (JsonNode issueLine : issueLines) {
                if (text(line, "id").equals(text(issueLine, "id")) || text(line, "itemId").equals(text(issueLine, "itemId"))) {
                    BigDecimal value = quantity(issueLine, "issuedQuantity", remaining);
                    return value.signum() > 0 ? value : BigDecimal.ZERO;
                }
            }
            return BigDecimal.ZERO;
        }
        if (!text(payload, "lineId").isBlank() && !text(payload, "lineId").equals(text(line, "id"))) {
            return BigDecimal.ZERO;
        }
        if (!text(payload, "itemId").isBlank() && !text(payload, "itemId").equals(text(line, "itemId"))) {
            return BigDecimal.ZERO;
        }
        return quantity(payload, "issuedQuantity", remaining);
    }

    public ObjectNode loadItem(String itemId) {
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT raw_json FROM inventory_items WHERE id = ?",
            (resultSet, rowNumber) -> readObject(resultSet, "raw_json"),
            itemId
        );
        if (rows.isEmpty()) throw new IllegalArgumentException("物资档案不存在");
        return rows.get(0);
    }

    public ObjectNode reusableInboundBatch(String itemId, String batchNo, String expiryDate, boolean batchRequired) {
        boolean standaloneBatch = batchRequired || !batchNo.isBlank() || !expiryDate.isBlank();
        if (standaloneBatch) {
            ObjectNode batch = objectMapper.createObjectNode();
            batch.put("id", "batch-" + UUID.randomUUID());
            batch.put("itemId", itemId);
            putQuantity(batch, "quantity", BigDecimal.ZERO);
            return batch;
        }
        String stockBatchId = "stock-" + itemId;
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT raw_json FROM inventory_batches WHERE id = ? FOR UPDATE",
            (resultSet, rowNumber) -> readObject(resultSet, "raw_json"),
            stockBatchId
        );
        if (!rows.isEmpty()) return rows.get(0);
        ObjectNode batch = objectMapper.createObjectNode();
        batch.put("id", stockBatchId);
        batch.put("itemId", itemId);
        batch.put("batchNo", "常备库存");
        batch.put("expiryDate", "");
        putQuantity(batch, "quantity", BigDecimal.ZERO);
        return batch;
    }

    private ArrayNode normalizeStoredRequests(ArrayNode rows) {
        ArrayNode normalized = objectMapper.createArrayNode();
        for (JsonNode row : rows) {
            ObjectNode request = object(row).deepCopy();
            request.set("lines", normalizeRequestLines(request, text(request, "status", "pending")));
            applyRequestPrimaryFields(request, requestLines(request));
            normalized.add(request);
        }
        return normalized;
    }

    public ArrayNode normalizeRequestLines(ObjectNode request, String defaultStatus) {
        ArrayNode normalized = objectMapper.createArrayNode();
        JsonNode lines = request.path("lines");
        if (lines.isArray() && lines.size() > 0) {
            for (JsonNode lineNode : lines) {
                ObjectNode line = object(lineNode).deepCopy();
                normalizeRequestLine(line, defaultStatus);
                normalized.add(line);
            }
        } else {
            ObjectNode line = objectMapper.createObjectNode();
            line.put("id", "line-" + UUID.randomUUID());
            line.put("itemId", text(request, "itemId"));
            putQuantity(line, "quantity", quantity(request, "quantity"));
            putQuantity(line, "issuedQuantity", quantity(request, "issuedQuantity"));
            line.put("status", lineStatusFromRequestStatus(defaultStatus));
            if (!text(request, "batchId").isBlank()) line.put("batchId", text(request, "batchId"));
            normalizeRequestLine(line, defaultStatus);
            normalized.add(line);
        }
        if (normalized.isEmpty()) throw new IllegalArgumentException("请至少添加一项申领物资");
        return normalized;
    }

    private void normalizeRequestLine(ObjectNode line, String defaultStatus) {
        if (text(line, "id").isBlank()) line.put("id", "line-" + UUID.randomUUID());
        if (text(line, "itemId").isBlank()) throw new IllegalArgumentException("请选择申领物资");
        if (quantity(line, "quantity").signum() <= 0) throw new IllegalArgumentException("申领数量必须大于 0");
        if (!line.has("issuedQuantity")) putQuantity(line, "issuedQuantity", BigDecimal.ZERO);
        if (text(line, "status").isBlank()) line.put("status", lineStatusFromRequestStatus(defaultStatus));
    }

    public ArrayNode requestLines(ObjectNode request) {
        JsonNode lines = request.path("lines");
        if (lines.isArray()) return (ArrayNode) lines;
        ArrayNode normalized = normalizeRequestLines(request, text(request, "status", "pending"));
        request.set("lines", normalized);
        return normalized;
    }

    private String lineStatusFromRequestStatus(String status) {
        return switch (status) {
            case "received" -> "received";
            case "issued" -> "issued";
            case "partially_issued" -> "partially_issued";
            case "rejected" -> "rejected";
            case "cancelled" -> "cancelled";
            case "void" -> "void";
            case "approved" -> "approved";
            default -> "pending";
        };
    }

    public void updateLineStatuses(ObjectNode request, String status) {
        for (JsonNode lineNode : requestLines(request)) {
            object(lineNode).put("status", status);
        }
    }

    public void applyRequestStatusFromLines(ObjectNode request, ArrayNode lines) {
        boolean anyIssued = false;
        boolean allIssued = true;
        for (JsonNode lineNode : lines) {
            ObjectNode line = object(lineNode);
            BigDecimal requested = quantity(line, "quantity");
            BigDecimal issued = quantity(line, "issuedQuantity");
            if (issued.signum() > 0) anyIssued = true;
            if (issued.compareTo(requested) < 0) allIssued = false;
        }
        request.put("status", allIssued ? "issued" : anyIssued ? "partially_issued" : "approved");
    }

    public BigDecimal totalIssuedQuantity(ArrayNode lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode line : lines) total = total.add(quantity(line, "issuedQuantity"));
        return total;
    }

    public BigDecimal totalRequestedQuantity(ArrayNode lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode line : lines) total = total.add(quantity(line, "quantity"));
        return total;
    }

    public void applyRequestPrimaryFields(ObjectNode request, ArrayNode lines) {
        ObjectNode firstLine = object(lines.get(0));
        request.put("itemId", text(firstLine, "itemId"));
        putQuantity(request, "quantity", totalRequestedQuantity(lines));
        putQuantity(request, "issuedQuantity", totalIssuedQuantity(lines));
        request.put("itemCount", lines.size());
        request.put("itemSummary", requestLineSummary(lines));
    }

    public String requestLineSummary(ArrayNode lines) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < lines.size() && i < 3; i += 1) {
            ObjectNode line = object(lines.get(i));
            names.add(itemLabel(text(line, "itemId")) + " " + quantity(line, "quantity") + itemUnit(text(line, "itemId")));
        }
        if (lines.size() > 3) names.add("等 " + lines.size() + " 项");
        return String.join("、", names);
    }

    public String weeklyConsumptionId(ObjectNode row) {
        String existingId = text(row, "id");
        if (!existingId.isBlank()) return existingId;
        List<String> ids = jdbcTemplate.query(
            """
            SELECT id FROM inventory_weekly_consumption
            WHERE week_no = ? AND department = ? AND item_id = ?
            LIMIT 1
            """,
            (resultSet, rowNumber) -> resultSet.getString("id"),
            text(row, "weekNo"),
            text(row, "department"),
            text(row, "itemId")
        );
        return ids.isEmpty() ? "week-" + UUID.randomUUID() : ids.get(0);
    }

    public void saveWeeklyConsumption(ObjectNode row) {
        jdbcTemplate.update("""
            INSERT INTO inventory_weekly_consumption (
              id, week_no, department, item_id, consumed_quantity, remaining_quantity, next_week_quantity,
              owner, abnormal_reason, confirmed_at, raw_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE week_no = VALUES(week_no), department = VALUES(department), item_id = VALUES(item_id),
              consumed_quantity = VALUES(consumed_quantity), remaining_quantity = VALUES(remaining_quantity),
              next_week_quantity = VALUES(next_week_quantity), owner = VALUES(owner), abnormal_reason = VALUES(abnormal_reason),
              confirmed_at = VALUES(confirmed_at), raw_json = VALUES(raw_json)
            """,
            text(row, "id"), text(row, "weekNo"), text(row, "department"), text(row, "itemId"),
            quantity(row, "consumedQuantity"), quantity(row, "remainingQuantity"), quantity(row, "nextWeekQuantity"),
            text(row, "owner"), text(row, "abnormalReason"), text(row, "confirmedAt"), toJson(row)
        );
    }

    public void saveInventoryCount(
        ObjectNode row,
        String itemId,
        ObjectNode batch,
        BigDecimal bookQuantity,
        BigDecimal actualQuantity,
        BigDecimal difference
    ) {
        jdbcTemplate.update("""
            INSERT INTO inventory_counts (
              id, item_id, batch_id, book_quantity, actual_quantity, difference_quantity,
              operator_name, reason, counted_at, raw_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            text(row, "id"), itemId, text(batch, "id"), bookQuantity, actualQuantity, difference,
            text(row, "operator"), text(row, "reason"), text(row, "countedAt"), toJson(row)
        );
    }

    public ObjectNode chooseBatchForPositiveAdjustment(String itemId, String batchId, String source) {
        try {
            return chooseBatch(itemId, batchId);
        } catch (IllegalArgumentException error) {
            if (!batchId.isBlank()) throw error;
            ObjectNode batch = objectMapper.createObjectNode();
            batch.put("id", "batch-" + UUID.randomUUID());
            batch.put("itemId", itemId);
            batch.put("batchNo", source + "-" + LocalDate.now());
            batch.put("expiryDate", "");
            putQuantity(batch, "quantity", BigDecimal.ZERO);
            batch.put("location", itemLocation(itemId));
            batch.put("source", source);
            batch.put("createdAt", now());
            upsertBatch(batch);
            return batch;
        }
    }

    public void assertStatus(ObjectNode request, List<String> expectedStatuses, String message) {
        if (!expectedStatuses.contains(text(request, "status"))) {
            throw new IllegalArgumentException(message);
        }
    }

    public void applyOperator(ObjectNode row, SessionUser user) {
        row.put("operator", user.name());
        row.put("operatorId", user.id());
        row.put("operatorRole", user.role());
    }

    public void applyUserDepartment(ObjectNode row, SessionUser user) {
        row.put("department", user.department());
    }

    public boolean isInventoryManager(SessionUser user) {
        return "admin".equals(user.role()) || "quality".equals(user.role());
    }

    public boolean sameDepartment(SessionUser user, JsonNode row) {
        String department = user.department();
        return department != null && department.equals(text(row, "department"));
    }

    public void movement(String type, String itemId, String batchId, BigDecimal quantity, String department, String operator, String reason, String relatedId) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", "mov-" + UUID.randomUUID());
        row.put("itemId", itemId);
        row.put("batchId", batchId);
        row.put("type", type);
        putQuantity(row, "quantity", quantity);
        row.put("department", department);
        row.put("operator", operator);
        row.put("reason", reason);
        row.put("relatedId", relatedId);
        row.put("createdAt", now());
        jdbcTemplate.update("""
            INSERT INTO inventory_movements (
              id, item_id, batch_id, movement_type, quantity, department, operator_name, reason, related_id, created_at, raw_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            text(row, "id"), itemId, batchId, type, quantity, department, operator, reason, relatedId, text(row, "createdAt"), toJson(row)
        );
    }

    public void log(String operator, String action, String targetType, String targetLabel, String detail) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", "inv-audit-" + UUID.randomUUID());
        row.put("operator", operator == null || operator.isBlank() ? "系统" : operator);
        row.put("action", action);
        row.put("targetType", targetType);
        row.put("targetLabel", targetLabel);
        row.put("detail", detail);
        row.put("createdAt", now());
        jdbcTemplate.update("""
            INSERT INTO inventory_audit_logs (id, operator_name, action, target_type, target_label, detail, created_at, raw_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            text(row, "id"), text(row, "operator"), action, targetType, targetLabel, detail, text(row, "createdAt"), toJson(row)
        );
    }

    private ArrayNode readArray(String table, String jsonColumn, String orderBy) {
        ResultSetExtractor<ArrayNode> extractor = resultSet -> {
            ArrayNode rows = objectMapper.createArrayNode();
            while (resultSet.next()) {
                rows.add(readJson(resultSet, jsonColumn));
            }
            return rows;
        };
        return jdbcTemplate.query("SELECT " + jsonColumn + " FROM " + table + " ORDER BY " + orderBy, extractor);
    }

    private ObjectNode readObject(ResultSet resultSet, String column) throws SQLException {
        JsonNode node = readJson(resultSet, column);
        return node.isObject() ? (ObjectNode) node.deepCopy() : objectMapper.createObjectNode();
    }

    private JsonNode readJson(ResultSet resultSet, String column) throws SQLException {
        try {
            String rawJson = resultSet.getString(column);
            if (rawJson == null || rawJson.isBlank()) return objectMapper.createObjectNode();
            JsonNode node = objectMapper.readTree(rawJson);
            return node == null || node.isNull() ? objectMapper.createObjectNode() : node;
        } catch (Exception error) {
            return objectMapper.createObjectNode();
        }
    }

    public ObjectNode object(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("请求内容必须是对象");
        return (ObjectNode) node;
    }

    public String itemLabel(String itemId) {
        if (itemId == null || itemId.isBlank()) return "";
        List<String> names = jdbcTemplate.query(
            "SELECT name FROM inventory_items WHERE id = ?",
            (resultSet, rowNumber) -> resultSet.getString("name"),
            itemId
        );
        return names.isEmpty() ? itemId : names.get(0);
    }

    public String itemUnit(String itemId) {
        if (itemId == null || itemId.isBlank()) return "";
        List<String> units = jdbcTemplate.query(
            "SELECT unit FROM inventory_items WHERE id = ?",
            (resultSet, rowNumber) -> resultSet.getString("unit"),
            itemId
        );
        return units.isEmpty() ? "" : units.get(0);
    }

    public String itemLocation(String itemId) {
        if (itemId == null || itemId.isBlank()) return "";
        List<String> locations = jdbcTemplate.query(
            "SELECT location FROM inventory_items WHERE id = ?",
            (resultSet, rowNumber) -> resultSet.getString("location"),
            itemId
        );
        return locations.isEmpty() ? "" : locations.get(0);
    }

    public String now() {
        return LocalDateTime.now().format(DATE_TIME_FORMATTER);
    }

    public String text(JsonNode node, String key) {
        return text(node, key, "");
    }

    public String text(JsonNode node, String key, String fallback) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText();
    }

    private double number(JsonNode node, String key) {
        return number(node, key, 0);
    }

    private double number(JsonNode node, String key, double fallback) {
        JsonNode value = node.path(key);
        if (value.isNumber()) return value.asDouble();
        if (value.isTextual()) {
            try {
                return Double.parseDouble(value.asText());
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public BigDecimal quantity(JsonNode node, String key) {
        return quantity(node, key, BigDecimal.ZERO);
    }

    public BigDecimal quantity(JsonNode node, String key, BigDecimal fallback) {
        JsonNode value = node.path(key);
        if (value.isNumber() || value.isTextual()) {
            try {
                return new BigDecimal(value.asText()).setScale(2, RoundingMode.HALF_UP);
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public void putQuantity(ObjectNode node, String key, BigDecimal value) {
        node.set(key, DecimalNode.valueOf(value.setScale(2, RoundingMode.HALF_UP)));
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalArgumentException("Failed to serialize JSON", error);
        }
    }

    public Map<String, Object> asMap(ObjectNode db) {
        return objectMapper.convertValue(db, new TypeReference<Map<String, Object>>() {});
    }
}
