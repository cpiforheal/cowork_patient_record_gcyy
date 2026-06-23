package com.example.coshare_patientrecord_sys;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class InventoryDatabaseService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public InventoryDatabaseService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initializeSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS inventory_items (
              id VARCHAR(64) PRIMARY KEY,
              name VARCHAR(120) NOT NULL,
              category VARCHAR(80),
              spec VARCHAR(120),
              unit VARCHAR(32),
              location VARCHAR(120),
              low_stock_threshold DECIMAL(12,2) DEFAULT 0,
              is_sensitive BOOLEAN NOT NULL DEFAULT FALSE,
              batch_required BOOLEAN NOT NULL DEFAULT FALSE,
              expiry_required BOOLEAN NOT NULL DEFAULT FALSE,
              enabled BOOLEAN NOT NULL DEFAULT TRUE,
              raw_json JSON NOT NULL,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              INDEX idx_inventory_items_name (name),
              INDEX idx_inventory_items_category (category)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS inventory_batches (
              id VARCHAR(64) PRIMARY KEY,
              item_id VARCHAR(64) NOT NULL,
              batch_no VARCHAR(120),
              expiry_date VARCHAR(32),
              quantity DECIMAL(12,2) NOT NULL DEFAULT 0,
              location VARCHAR(120),
              raw_json JSON NOT NULL,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              INDEX idx_inventory_batches_item (item_id),
              INDEX idx_inventory_batches_expiry (expiry_date)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS inventory_requests (
              id VARCHAR(64) PRIMARY KEY,
              department VARCHAR(120) NOT NULL,
              applicant VARCHAR(120),
              owner VARCHAR(120),
              reason VARCHAR(500),
              expected_use_week VARCHAR(32),
              status VARCHAR(32),
              created_at VARCHAR(32),
              approved_at VARCHAR(32),
              issued_at VARCHAR(32),
              received_at VARCHAR(32),
              raw_json JSON NOT NULL,
              INDEX idx_inventory_requests_department (department),
              INDEX idx_inventory_requests_status (status),
              INDEX idx_inventory_requests_created (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS inventory_request_lines (
              id VARCHAR(64) PRIMARY KEY,
              request_id VARCHAR(64) NOT NULL,
              item_id VARCHAR(64) NOT NULL,
              requested_quantity DECIMAL(12,2) NOT NULL DEFAULT 0,
              issued_quantity DECIMAL(12,2) NOT NULL DEFAULT 0,
              line_status VARCHAR(32),
              raw_json JSON NOT NULL,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              INDEX idx_inventory_request_lines_request (request_id),
              INDEX idx_inventory_request_lines_item (item_id),
              INDEX idx_inventory_request_lines_status (line_status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS inventory_weekly_consumption (
              id VARCHAR(64) PRIMARY KEY,
              week_no VARCHAR(32),
              department VARCHAR(120),
              item_id VARCHAR(64),
              consumed_quantity DECIMAL(12,2) DEFAULT 0,
              remaining_quantity DECIMAL(12,2) DEFAULT 0,
              next_week_quantity DECIMAL(12,2) DEFAULT 0,
              owner VARCHAR(120),
              abnormal_reason VARCHAR(500),
              confirmed_at VARCHAR(32),
              raw_json JSON NOT NULL,
              INDEX idx_inventory_weekly_department (department),
              INDEX idx_inventory_weekly_item (item_id),
              INDEX idx_inventory_weekly_week (week_no),
              UNIQUE KEY uk_inventory_weekly_scope (week_no, department, item_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        ensureWeeklyConsumptionUniqueKey();
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS inventory_counts (
              id VARCHAR(64) PRIMARY KEY,
              item_id VARCHAR(64),
              batch_id VARCHAR(64),
              book_quantity DECIMAL(12,2) DEFAULT 0,
              actual_quantity DECIMAL(12,2) DEFAULT 0,
              difference_quantity DECIMAL(12,2) DEFAULT 0,
              operator_name VARCHAR(120),
              reason VARCHAR(500),
              counted_at VARCHAR(32),
              raw_json JSON NOT NULL,
              INDEX idx_inventory_counts_item (item_id),
              INDEX idx_inventory_counts_counted (counted_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS inventory_movements (
              id VARCHAR(64) PRIMARY KEY,
              item_id VARCHAR(64) NOT NULL,
              batch_id VARCHAR(64),
              movement_type VARCHAR(40) NOT NULL,
              quantity DECIMAL(12,2) NOT NULL,
              department VARCHAR(120),
              operator_name VARCHAR(120),
              reason VARCHAR(500),
              related_id VARCHAR(64),
              created_at VARCHAR(32),
              raw_json JSON NOT NULL,
              INDEX idx_inventory_movements_item (item_id),
              INDEX idx_inventory_movements_type (movement_type),
              INDEX idx_inventory_movements_created (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS inventory_audit_logs (
              id VARCHAR(64) PRIMARY KEY,
              operator_name VARCHAR(120),
              action VARCHAR(120),
              target_type VARCHAR(80),
              target_label VARCHAR(200),
              detail VARCHAR(800),
              created_at VARCHAR(32),
              raw_json JSON NOT NULL,
              INDEX idx_inventory_audit_created (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
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
        db.set("summary", buildSummary(db));
        return db;
    }

    public ObjectNode readDbForUser(AuthSessionService.SessionUser user) {
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
        db.set("summary", buildSummary(db));
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

    @Transactional
    public ObjectNode saveItem(JsonNode payload, AuthSessionService.SessionUser user) {
        ObjectNode item = object(payload).deepCopy();
        applyOperator(item, user);
        if (text(item, "id").isBlank()) item.put("id", "item-" + UUID.randomUUID());
        if (text(item, "name").isBlank()) throw new IllegalArgumentException("物资名称不能为空");
        item.put("enabled", item.path("enabled").asBoolean(true));
        upsertItem(item);
        log(text(item, "operator", "系统"), "维护物资档案", "item", text(item, "name"), "新增或更新物资基础信息");
        return readDbForUser(user);
    }

    @Transactional
    public ObjectNode inbound(JsonNode payload, AuthSessionService.SessionUser user) {
        ObjectNode row = object(payload).deepCopy();
        applyOperator(row, user);
        String itemId = text(row, "itemId");
        if (itemId.isBlank()) throw new IllegalArgumentException("请选择入库物资");
        BigDecimal quantity = quantity(row, "quantity");
        if (quantity.signum() <= 0) throw new IllegalArgumentException("入库数量必须大于 0");
        ObjectNode item = loadItem(itemId);
        boolean batchRequired = item.path("batchRequired").asBoolean(false);
        boolean expiryRequired = item.path("expiryRequired").asBoolean(false);
        String batchNo = text(row, "batchNo");
        String expiryDate = text(row, "expiryDate");
        if (batchRequired && batchNo.isBlank()) throw new IllegalArgumentException("该物资要求批号，入库时必须填写批号");
        if (expiryRequired && expiryDate.isBlank()) throw new IllegalArgumentException("该物资要求效期，入库时必须填写有效期");

        ObjectNode batch = reusableInboundBatch(itemId, batchNo, expiryDate, batchRequired);
        putQuantity(batch, "quantity", quantity(batch, "quantity").add(quantity));
        batch.put("batchNo", batchNo.isBlank() && text(batch, "id").startsWith("stock-") ? "常备库存" : batchNo);
        batch.put("expiryDate", expiryDate);
        batch.put("location", text(row, "location", itemLocation(itemId)));
        batch.put("source", text(row, "source"));
        if (text(batch, "createdAt").isBlank()) batch.put("createdAt", now());
        upsertBatch(batch);
        movement("inbound", itemId, text(batch, "id"), quantity, "", text(row, "operator"), text(row, "source"), text(batch, "id"));
        log(text(row, "operator"), "物资入库", "batch", itemLabel(itemId), "入库 " + quantity + " " + itemUnit(itemId));
        return readDbForUser(user);
    }

    @Transactional
    public ObjectNode createRequest(JsonNode payload, AuthSessionService.SessionUser user) {
        ObjectNode row = object(payload).deepCopy();
        applyOperator(row, user);
        applyUserDepartment(row, user);
        row.put("applicant", user.name());
        if (text(row, "department").isBlank()) throw new IllegalArgumentException("申领科室不能为空");
        if (text(row, "reason").isBlank()) throw new IllegalArgumentException("申请理由不能为空");
        row.put("id", "req-" + UUID.randomUUID());
        row.put("status", "pending");
        row.put("createdAt", now());
        ArrayNode lines = normalizeRequestLines(row, "pending");
        row.set("lines", lines);
        applyRequestPrimaryFields(row, lines);
        upsertRequest(row);
        log(text(row, "applicant", text(row, "operator")), "发起申领", "request", requestLineSummary(lines), text(row, "department") + " 申领 " + lines.size() + " 项物资");
        return readDbForUser(user);
    }

    @Transactional
    public ObjectNode approveRequest(JsonNode payload, AuthSessionService.SessionUser user) {
        ObjectNode request = loadRequest(text(payload, "id"));
        assertStatus(request, List.of("pending"), "只有待审核申领单可以审核");
        updateLineStatuses(request, "approved");
        request.put("status", "approved");
        request.put("owner", user.name());
        request.put("approvedAt", now());
        upsertRequest(request);
        log(user.name(), "负责人审核", "request", requestLineSummary(requestLines(request)), "申领单已审核通过");
        return readDbForUser(user);
    }

    @Transactional
    public ObjectNode issueRequest(JsonNode payload, AuthSessionService.SessionUser user) {
        ObjectNode request = loadRequest(text(payload, "id"));
        assertStatus(request, List.of("approved", "partially_issued"), "只有已审核或部分发放的申领单可以发放");
        ArrayNode lines = requestLines(request);
        JsonNode issueLines = payload.path("lines");
        BigDecimal totalIssuedNow = BigDecimal.ZERO;
        String batchId = text(payload, "batchId");
        for (JsonNode lineNode : lines) {
            ObjectNode line = object(lineNode);
            BigDecimal remaining = quantity(line, "quantity").subtract(quantity(line, "issuedQuantity"));
            if (remaining.signum() <= 0) continue;
            BigDecimal requestedIssue = requestedIssueQuantity(payload, issueLines, line, remaining);
            if (requestedIssue.signum() <= 0) continue;
            if (requestedIssue.compareTo(remaining) > 0) requestedIssue = remaining;
            BigDecimal issued = issueLine(line, request, requestedIssue, batchId, user);
            totalIssuedNow = totalIssuedNow.add(issued);
        }
        if (totalIssuedNow.signum() <= 0) throw new IllegalArgumentException("当前没有可发放库存，请先入库或调整发放数量");

        applyRequestStatusFromLines(request, lines);
        request.put("issuedAt", now());
        request.put("issuer", user.name());
        putQuantity(request, "issuedQuantity", totalIssuedQuantity(lines));
        upsertRequest(request);
        log(user.name(), "仓库发放", "request", requestLineSummary(lines), text(request, "department") + " 本次发放 " + totalIssuedNow + "，状态：" + text(request, "status"));
        return readDbForUser(user);
    }

    @Transactional
    public ObjectNode receiveRequest(JsonNode payload, AuthSessionService.SessionUser user) {
        ObjectNode request = loadRequest(text(payload, "id"));
        assertStatus(request, List.of("issued"), "只有已发放申领单可以签收");
        if (!sameDepartment(user, request) && !isInventoryManager(user)) {
            throw new IllegalArgumentException("只能签收本科室申领单");
        }
        updateLineStatuses(request, "received");
        request.put("status", "received");
        request.put("receivedAt", now());
        request.put("receiver", user.name());
        upsertRequest(request);
        log(user.name(), "领取确认", "request", requestLineSummary(requestLines(request)), "申领单已签收");
        return readDbForUser(user);
    }

    @Transactional
    public ObjectNode rejectRequest(JsonNode payload, AuthSessionService.SessionUser user) {
        ObjectNode request = loadRequest(text(payload, "id"));
        assertStatus(request, List.of("pending"), "只有待审核申领单可以驳回");
        String reason = text(payload, "reason");
        if (reason.isBlank()) throw new IllegalArgumentException("驳回必须填写原因");
        updateLineStatuses(request, "rejected");
        request.put("status", "rejected");
        request.put("rejectedAt", now());
        request.put("rejector", user.name());
        request.put("rejectReason", reason);
        upsertRequest(request);
        log(user.name(), "驳回申领", "request", requestLineSummary(requestLines(request)), reason);
        return readDbForUser(user);
    }

    @Transactional
    public ObjectNode cancelRequest(JsonNode payload, AuthSessionService.SessionUser user) {
        ObjectNode request = loadRequest(text(payload, "id"));
        assertStatus(request, List.of("pending"), "只有待审核申领单可以撤销");
        if (!sameDepartment(user, request) && !isInventoryManager(user)) {
            throw new IllegalArgumentException("只能撤销本科室申领单");
        }
        String reason = text(payload, "reason", "申请人撤销");
        updateLineStatuses(request, "cancelled");
        request.put("status", "cancelled");
        request.put("cancelledAt", now());
        request.put("cancelledBy", user.name());
        request.put("cancelReason", reason);
        upsertRequest(request);
        log(user.name(), "撤销申领", "request", requestLineSummary(requestLines(request)), reason);
        return readDbForUser(user);
    }

    @Transactional
    public ObjectNode voidRequest(JsonNode payload, AuthSessionService.SessionUser user) {
        ObjectNode request = loadRequest(text(payload, "id"));
        assertStatus(request, List.of("pending", "approved"), "只有待审核或待发放申领单可以作废");
        String reason = text(payload, "reason");
        if (reason.isBlank()) throw new IllegalArgumentException("作废必须填写原因");
        updateLineStatuses(request, "void");
        request.put("status", "void");
        request.put("voidedAt", now());
        request.put("voidedBy", user.name());
        request.put("voidReason", reason);
        upsertRequest(request);
        log(user.name(), "作废申领", "request", requestLineSummary(requestLines(request)), reason);
        return readDbForUser(user);
    }

    @Transactional
    public ObjectNode weeklyConsumption(JsonNode payload, AuthSessionService.SessionUser user) {
        ObjectNode row = object(payload).deepCopy();
        applyOperator(row, user);
        applyUserDepartment(row, user);
        row.put("owner", user.name());
        if (text(row, "weekNo").isBlank()) throw new IllegalArgumentException("请选择周次");
        if (text(row, "department").isBlank()) throw new IllegalArgumentException("请选择科室");
        if (text(row, "itemId").isBlank()) throw new IllegalArgumentException("请选择物资");
        row.put("id", weeklyConsumptionId(row));
        row.put("confirmedAt", now());
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
        log(text(row, "operator", text(row, "owner")), "周消耗确认", "weekly", itemLabel(text(row, "itemId")), text(row, "department") + " 已确认周消耗");
        return readDbForUser(user);
    }

    @Transactional
    public ObjectNode returnOrScrap(JsonNode payload, AuthSessionService.SessionUser user) {
        ObjectNode row = object(payload).deepCopy();
        applyOperator(row, user);
        applyUserDepartment(row, user);
        String type = text(row, "type");
        if (!List.of("return", "scrap").contains(type)) throw new IllegalArgumentException("请选择退回或报废");
        String itemId = text(row, "itemId");
        BigDecimal quantity = quantity(row, "quantity");
        if (itemId.isBlank() || quantity.signum() <= 0) throw new IllegalArgumentException("请选择物资并填写数量");
        if ("return".equals(type)) {
            ObjectNode batch = chooseBatchForPositiveAdjustment(itemId, text(row, "batchId"), "退回补录");
            putQuantity(batch, "quantity", quantity(batch, "quantity").add(quantity));
            upsertBatch(batch);
            movement("return", itemId, text(batch, "id"), quantity, text(row, "department"), text(row, "operator"), text(row, "reason"), "");
            log(text(row, "operator"), "物资退回", "movement", itemLabel(itemId), text(row, "department") + " 退回 " + quantity);
        } else {
            ObjectNode batch = chooseBatch(itemId, text(row, "batchId"));
            BigDecimal available = quantity(batch, "quantity");
            if (available.compareTo(quantity) < 0) throw new IllegalArgumentException("库存不足，无法报废");
            putQuantity(batch, "quantity", available.subtract(quantity));
            upsertBatch(batch);
            movement("scrap", itemId, text(batch, "id"), quantity.negate(), text(row, "department"), text(row, "operator"), text(row, "reason"), "");
            log(text(row, "operator"), "物资报废", "movement", itemLabel(itemId), "报废 " + quantity);
        }
        return readDbForUser(user);
    }

    @Transactional
    public ObjectNode inventoryCount(JsonNode payload, AuthSessionService.SessionUser user) {
        ObjectNode row = object(payload).deepCopy();
        applyOperator(row, user);
        String itemId = text(row, "itemId");
        if (itemId.isBlank()) throw new IllegalArgumentException("请选择物资");
        ObjectNode batch = chooseBatchForPositiveAdjustment(itemId, text(row, "batchId"), "盘点补录");
        BigDecimal bookQuantity = quantity(batch, "quantity");
        BigDecimal actualQuantity = quantity(row, "actualQuantity");
        BigDecimal difference = actualQuantity.subtract(bookQuantity);
        putQuantity(batch, "quantity", actualQuantity);
        upsertBatch(batch);

        row.put("id", "count-" + UUID.randomUUID());
        row.put("batchId", text(batch, "id"));
        putQuantity(row, "bookQuantity", bookQuantity);
        putQuantity(row, "actualQuantity", actualQuantity);
        putQuantity(row, "differenceQuantity", difference);
        row.put("countedAt", now());
        jdbcTemplate.update("""
            INSERT INTO inventory_counts (
              id, item_id, batch_id, book_quantity, actual_quantity, difference_quantity,
              operator_name, reason, counted_at, raw_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            text(row, "id"), itemId, text(batch, "id"), bookQuantity, actualQuantity, difference,
            text(row, "operator"), text(row, "reason"), text(row, "countedAt"), toJson(row)
        );
        movement("count", itemId, text(batch, "id"), difference, "", text(row, "operator"), text(row, "reason"), text(row, "id"));
        log(text(row, "operator"), "库存盘点", "count", itemLabel(itemId), "账面 " + bookQuantity + "，实盘 " + actualQuantity);
        return readDbForUser(user);
    }

    private ObjectNode buildSummary(ObjectNode db) {
        ObjectNode summary = objectMapper.createObjectNode();
        ArrayNode items = (ArrayNode) db.path("items");
        ArrayNode batches = (ArrayNode) db.path("batches");
        ArrayNode requests = (ArrayNode) db.path("requests");
        LocalDate today = LocalDate.now();
        int lowStock = 0;
        int expirySoon = 0;
        for (JsonNode item : items) {
            BigDecimal total = stockOf(text(item, "id"), batches);
            if (total.compareTo(quantity(item, "lowStockThreshold")) <= 0) lowStock += 1;
        }
        for (JsonNode batch : batches) {
            String expiryDate = text(batch, "expiryDate");
            if (!expiryDate.isBlank()) {
                try {
                    LocalDate expiry = LocalDate.parse(expiryDate);
                    if (!expiry.isBefore(today) && !expiry.isAfter(today.plusDays(30))) expirySoon += 1;
                } catch (Exception ignored) {
                    // Keep summary tolerant of legacy manual date text.
                }
            }
        }
        summary.put("itemCount", items.size());
        summary.put("batchCount", batches.size());
        summary.put("pendingRequestCount", countByStatus(requests, "pending"));
        summary.put("approvedRequestCount", countByStatus(requests, "approved"));
        summary.put("lowStockCount", lowStock);
        summary.put("expirySoonCount", expirySoon);
        summary.put("movementCount", db.path("movements").size());
        return summary;
    }

    private BigDecimal stockOf(String itemId, ArrayNode batches) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode batch : batches) {
            if (itemId.equals(text(batch, "itemId"))) total = total.add(quantity(batch, "quantity"));
        }
        return total;
    }

    private int countByStatus(ArrayNode rows, String status) {
        int count = 0;
        for (JsonNode row : rows) {
            if (status.equals(text(row, "status"))) count += 1;
        }
        return count;
    }

    private void upsertItem(ObjectNode item) {
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

    private void upsertBatch(ObjectNode batch) {
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

    private void upsertRequest(ObjectNode request) {
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

    private ObjectNode loadRequest(String id) {
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

    private ObjectNode chooseBatch(String itemId, String batchId) {
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

    private List<ObjectNode> chooseBatchesForIssue(String itemId, String batchId) {
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

    private BigDecimal issueLine(ObjectNode line, ObjectNode request, BigDecimal requestedIssue, String batchId, AuthSessionService.SessionUser user) {
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

    private BigDecimal requestedIssueQuantity(JsonNode payload, JsonNode issueLines, ObjectNode line, BigDecimal remaining) {
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

    private ObjectNode loadItem(String itemId) {
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT raw_json FROM inventory_items WHERE id = ?",
            (resultSet, rowNumber) -> readObject(resultSet, "raw_json"),
            itemId
        );
        if (rows.isEmpty()) throw new IllegalArgumentException("物资档案不存在");
        return rows.get(0);
    }

    private ObjectNode reusableInboundBatch(String itemId, String batchNo, String expiryDate, boolean batchRequired) {
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

    private ArrayNode normalizeRequestLines(ObjectNode request, String defaultStatus) {
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

    private ArrayNode requestLines(ObjectNode request) {
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

    private void updateLineStatuses(ObjectNode request, String status) {
        for (JsonNode lineNode : requestLines(request)) {
            object(lineNode).put("status", status);
        }
    }

    private void applyRequestStatusFromLines(ObjectNode request, ArrayNode lines) {
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

    private BigDecimal totalIssuedQuantity(ArrayNode lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode line : lines) total = total.add(quantity(line, "issuedQuantity"));
        return total;
    }

    private BigDecimal totalRequestedQuantity(ArrayNode lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode line : lines) total = total.add(quantity(line, "quantity"));
        return total;
    }

    private void applyRequestPrimaryFields(ObjectNode request, ArrayNode lines) {
        ObjectNode firstLine = object(lines.get(0));
        request.put("itemId", text(firstLine, "itemId"));
        putQuantity(request, "quantity", totalRequestedQuantity(lines));
        putQuantity(request, "issuedQuantity", totalIssuedQuantity(lines));
        request.put("itemCount", lines.size());
        request.put("itemSummary", requestLineSummary(lines));
    }

    private String requestLineSummary(ArrayNode lines) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < lines.size() && i < 3; i += 1) {
            ObjectNode line = object(lines.get(i));
            names.add(itemLabel(text(line, "itemId")) + " " + quantity(line, "quantity") + itemUnit(text(line, "itemId")));
        }
        if (lines.size() > 3) names.add("等 " + lines.size() + " 项");
        return String.join("、", names);
    }

    private String weeklyConsumptionId(ObjectNode row) {
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

    private void ensureWeeklyConsumptionUniqueKey() {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(1)
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'inventory_weekly_consumption'
              AND index_name = 'uk_inventory_weekly_scope'
            """,
            Integer.class
        );
        if (count != null && count > 0) return;

        jdbcTemplate.execute("""
            DELETE older
            FROM inventory_weekly_consumption older
            JOIN inventory_weekly_consumption newer
              ON older.week_no <=> newer.week_no
             AND older.department <=> newer.department
             AND older.item_id <=> newer.item_id
             AND older.id > newer.id
            """);
        jdbcTemplate.execute("""
            ALTER TABLE inventory_weekly_consumption
            ADD UNIQUE KEY uk_inventory_weekly_scope (week_no, department, item_id)
            """);
    }

    private ObjectNode chooseBatchForPositiveAdjustment(String itemId, String batchId, String source) {
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

    private void assertStatus(ObjectNode request, List<String> expectedStatuses, String message) {
        if (!expectedStatuses.contains(text(request, "status"))) {
            throw new IllegalArgumentException(message);
        }
    }

    private void applyOperator(ObjectNode row, AuthSessionService.SessionUser user) {
        row.put("operator", user.name());
        row.put("operatorId", user.id());
        row.put("operatorRole", user.role());
    }

    private void applyUserDepartment(ObjectNode row, AuthSessionService.SessionUser user) {
        row.put("department", user.department());
    }

    private boolean isInventoryManager(AuthSessionService.SessionUser user) {
        return "admin".equals(user.role()) || "quality".equals(user.role());
    }

    private boolean sameDepartment(AuthSessionService.SessionUser user, JsonNode row) {
        String department = user.department();
        return department != null && department.equals(text(row, "department"));
    }

    private void movement(String type, String itemId, String batchId, BigDecimal quantity, String department, String operator, String reason, String relatedId) {
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

    private void log(String operator, String action, String targetType, String targetLabel, String detail) {
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

    private ObjectNode object(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("请求内容必须是对象");
        return (ObjectNode) node;
    }

    private String itemLabel(String itemId) {
        if (itemId == null || itemId.isBlank()) return "";
        List<String> names = jdbcTemplate.query(
            "SELECT name FROM inventory_items WHERE id = ?",
            (resultSet, rowNumber) -> resultSet.getString("name"),
            itemId
        );
        return names.isEmpty() ? itemId : names.get(0);
    }

    private String itemUnit(String itemId) {
        if (itemId == null || itemId.isBlank()) return "";
        List<String> units = jdbcTemplate.query(
            "SELECT unit FROM inventory_items WHERE id = ?",
            (resultSet, rowNumber) -> resultSet.getString("unit"),
            itemId
        );
        return units.isEmpty() ? "" : units.get(0);
    }

    private String itemLocation(String itemId) {
        if (itemId == null || itemId.isBlank()) return "";
        List<String> locations = jdbcTemplate.query(
            "SELECT location FROM inventory_items WHERE id = ?",
            (resultSet, rowNumber) -> resultSet.getString("location"),
            itemId
        );
        return locations.isEmpty() ? "" : locations.get(0);
    }

    private String now() {
        return LocalDateTime.now().format(DATE_TIME_FORMATTER);
    }

    private String text(JsonNode node, String key) {
        return text(node, key, "");
    }

    private String text(JsonNode node, String key, String fallback) {
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

    private BigDecimal quantity(JsonNode node, String key) {
        return quantity(node, key, BigDecimal.ZERO);
    }

    private BigDecimal quantity(JsonNode node, String key, BigDecimal fallback) {
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

    private void putQuantity(ObjectNode node, String key, BigDecimal value) {
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
