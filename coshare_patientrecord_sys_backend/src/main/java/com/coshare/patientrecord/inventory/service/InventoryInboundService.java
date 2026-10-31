package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.InventoryAccessService;
import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository.Balance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class InventoryInboundService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryRepository repository;
    private final InventoryLedgerRepository ledgerRepository;
    private final InventoryLedgerService ledgerService;
    private final InventoryAccessService accessService;

    public InventoryInboundService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        InventoryRepository repository,
        InventoryLedgerRepository ledgerRepository,
        InventoryLedgerService ledgerService,
        InventoryAccessService accessService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.ledgerRepository = ledgerRepository;
        this.ledgerService = ledgerService;
        this.accessService = accessService;
    }

    public ArrayNode supplierSuggestions() {
        return jdbcTemplate.query(
            "SELECT DISTINCT supplier FROM inventory_inbound_orders WHERE supplier IS NOT NULL AND supplier <> '' ORDER BY supplier LIMIT 50",
            (ResultSetExtractor<ArrayNode>) resultSet -> {
                ArrayNode rows = objectMapper.createArrayNode();
                while (resultSet.next()) rows.add(resultSet.getString(1));
                return rows;
            }
        );
    }

    public ObjectNode listOrders(LocalDate from, LocalDate to, String supplier, String status) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        StringBuilder sql = new StringBuilder("""
            SELECT o.*, COUNT(l.id) line_count, COALESCE(SUM(l.quantity), 0) total_quantity
            FROM inventory_inbound_orders o
            LEFT JOIN inventory_inbound_lines l ON l.order_id = o.id
            WHERE o.purchase_date BETWEEN ? AND ?
            """);
        List<Object> params = new ArrayList<>();
        params.add(start);
        params.add(end);
        if (supplier != null && !supplier.isBlank()) {
            sql.append(" AND o.supplier LIKE ?");
            params.add("%" + supplier.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND o.status = ?");
            params.add(status.trim().toUpperCase());
        }
        sql.append(" GROUP BY o.id ORDER BY o.purchase_date DESC, o.created_at DESC LIMIT 500");
        ArrayNode rows = jdbcTemplate.query(sql.toString(), (ResultSetExtractor<ArrayNode>) resultSet -> {
            ArrayNode list = objectMapper.createArrayNode();
            while (resultSet.next()) {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("id", resultSet.getString("id"));
                row.put("orderNo", resultSet.getString("order_no"));
                row.put("supplier", nz(resultSet.getString("supplier")));
                row.put("purchaseDate", resultSet.getDate("purchase_date").toLocalDate().toString());
                row.put("supplierDocumentNo", nz(resultSet.getString("supplier_document_no")));
                row.put("status", resultSet.getString("status"));
                row.put("note", nz(resultSet.getString("note")));
                row.put("createdBy", resultSet.getString("created_by"));
                row.put("createdAt", String.valueOf(resultSet.getTimestamp("created_at").toLocalDateTime()));
                row.put("lineCount", resultSet.getInt("line_count"));
                row.put("totalQuantity", resultSet.getBigDecimal("total_quantity"));
                list.add(row);
            }
            return list;
        }, params.toArray());
        ObjectNode result = objectMapper.createObjectNode();
        result.put("from", start.toString());
        result.put("to", end.toString());
        result.set("list", rows);
        return result;
    }

    public ObjectNode getOrder(String orderId) {
        List<ObjectNode> orders = jdbcTemplate.query(
            "SELECT * FROM inventory_inbound_orders WHERE id = ?",
            (resultSet, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("id", resultSet.getString("id"));
                row.put("orderNo", resultSet.getString("order_no"));
                row.put("supplier", nz(resultSet.getString("supplier")));
                row.put("purchaseDate", resultSet.getDate("purchase_date").toLocalDate().toString());
                row.put("supplierDocumentNo", nz(resultSet.getString("supplier_document_no")));
                row.put("status", resultSet.getString("status"));
                row.put("note", nz(resultSet.getString("note")));
                row.put("createdBy", resultSet.getString("created_by"));
                row.put("createdAt", String.valueOf(resultSet.getTimestamp("created_at").toLocalDateTime()));
                row.put("reversedBy", nz(resultSet.getString("reversed_by")));
                row.put("reverseReason", nz(resultSet.getString("reverse_reason")));
                row.put("reversedAt", String.valueOf(resultSet.getTimestamp("reversed_at")));
                return row;
            },
            orderId
        );
        if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "入库单不存在");
        ArrayNode lines = queryArray(
            """
            SELECT l.*, i.name item_name, i.unit item_unit
            FROM inventory_inbound_lines l
            LEFT JOIN inventory_items i ON i.id = l.item_id
            WHERE l.order_id = ?
            ORDER BY l.created_at, l.id
            """,
            (resultSet, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("id", resultSet.getString("id"));
                row.put("itemId", resultSet.getString("item_id"));
                row.put("itemName", nz(resultSet.getString("item_name")));
                row.put("unit", nz(resultSet.getString("item_unit")));
                row.put("batchId", resultSet.getString("batch_id"));
                row.put("batchNo", nz(resultSet.getString("batch_no")));
                row.put("expiryDate", nz(resultSet.getString("expiry_date")));
                row.put("issueQuantity", resultSet.getBigDecimal("issue_quantity"));
                row.put("conversionFactor", resultSet.getBigDecimal("conversion_factor"));
                row.put("quantity", resultSet.getBigDecimal("quantity"));
                row.put("remark", nz(resultSet.getString("remark")));
                return row;
            },
            orderId
        );
        ObjectNode order = orders.get(0);
        order.set("lines", lines);
        return order;
    }

    @Transactional
    public ObjectNode createOrder(JsonNode payload, SessionUser user) {
        requireWarehouse(user);
        JsonNode lines = payload == null ? null : payload.path("lines");
        if (lines == null || !lines.isArray() || lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少添加入库物资");
        }
        String purchaseDateText = text(payload, "purchaseDate");
        LocalDate purchaseDate = purchaseDateText.isBlank() ? LocalDate.now() : LocalDate.parse(purchaseDateText);
        String supplier = text(payload, "supplier");
        ArrayNode mergedLines = mergeInboundLines(lines);
        String orderId = "inbound-" + UUID.randomUUID();
        String orderNo = nextOrderNo(purchaseDate);
        jdbcTemplate.update(
            """
            INSERT INTO inventory_inbound_orders
              (id, order_no, supplier, purchase_date, supplier_document_no, status, note, created_by)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
            """,
            orderId, orderNo, blankToNull(supplier), purchaseDate,
            blankToNull(text(payload, "supplierDocumentNo")), blankToNull(text(payload, "note")), user.name()
        );
        for (JsonNode lineNode : mergedLines) {
            String itemId = text(lineNode, "itemId");
            if (itemId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入库行缺少物资");
            BigDecimal issueQuantity = decimal(lineNode, "issueQuantity", "quantity");
            if (issueQuantity.signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入库数量必须大于 0");
            BigDecimal factor = decimal(lineNode, "conversionFactor", "factor");
            if (factor.signum() <= 0) factor = BigDecimal.ONE;
            BigDecimal quantity = issueQuantity.multiply(factor).setScale(2, RoundingMode.HALF_UP);
            ObjectNode item = repository.loadItem(itemId);
            boolean batchRequired = item.path("batchRequired").asBoolean(false);
            boolean expiryRequired = item.path("expiryRequired").asBoolean(false);
            String batchNo = text(lineNode, "batchNo");
            String expiryDate = text(lineNode, "expiryDate");
            if (batchRequired && batchNo.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, item.path("name").asText() + " 必须填写批号");
            }
            if (expiryRequired && expiryDate.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, item.path("name").asText() + " 必须填写有效期");
            }
            String location = text(lineNode, "location");
            if (location.isBlank()) location = repository.itemLocation(itemId);
            ObjectNode batch = repository.reusableInboundBatch(itemId, batchNo, expiryDate, batchRequired);
            String batchId = batch.path("id").asText();
            repository.putQuantity(batch, "quantity", repository.quantity(batch, "quantity").add(quantity));
            batch.put("batchNo", batchNo.isBlank() ? "常备库存" : batchNo);
            batch.put("expiryDate", expiryDate);
            batch.put("location", location);
            batch.put("source", supplier);
            if (batch.path("createdAt").asText("").isBlank()) {
                batch.put("createdAt", LocalDateTime.now().toString());
            }
            repository.upsertBatch(batch);
            ledgerService.recordInbound(itemId, batchId, quantity, user, supplier.isBlank() ? orderNo : supplier + " / " + orderNo);
            jdbcTemplate.update(
                """
                INSERT INTO inventory_inbound_lines
                  (id, order_id, item_id, batch_id, batch_no, expiry_date, issue_quantity, conversion_factor, quantity, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "inbound-line-" + UUID.randomUUID(), orderId, itemId, batchId,
                blankToNull(batchNo), blankToNull(expiryDate), issueQuantity, factor, quantity,
                blankToNull(text(lineNode, "remark"))
            );
        }
        repository.log(user.name(), "新建入库单", "inbound", orderNo,
            "入库 " + lines.size() + " 行，供应商：" + (supplier.isBlank() ? "未填" : supplier));
        return getOrder(orderId);
    }

    @Transactional
    public ObjectNode reverseOrder(String orderId, String reason, SessionUser user) {
        requireWarehouse(user);
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "红冲必须填写原因");
        }
        List<ObjectNode> orders = jdbcTemplate.query(
            "SELECT * FROM inventory_inbound_orders WHERE id = ? FOR UPDATE",
            (resultSet, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("status", resultSet.getString("status"));
                row.put("orderNo", resultSet.getString("order_no"));
                return row;
            },
            orderId
        );
        if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "入库单不存在");
        if (!"ACTIVE".equals(orders.get(0).path("status").asText())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该入库单已红冲");
        }
        ArrayNode lines = (ArrayNode) getOrder(orderId).path("lines");
        for (JsonNode line : lines) {
            String batchId = line.path("batchId").asText();
            Integer moved = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM inventory_ledger_movements
                WHERE batch_id = ? AND movement_type NOT IN ('INBOUND', 'INBOUND_REVERSAL', 'OPENING_BALANCE')
                """,
                Integer.class,
                batchId
            );
            if (moved != null && moved > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    line.path("itemName").asText() + " 批次已发生出库或使用，不能红冲；请走退回或盘点");
            }
            String itemId = line.path("itemId").asText();
            BigDecimal quantity = new BigDecimal(line.path("quantity").asText("0"));
            Balance central = ledgerRepository.lockBalance(InventoryLedgerRepository.CENTRAL_LOCATION, itemId, batchId);
            if (central.available().compareTo(quantity) < 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "中心库余额不足，不能红冲");
            }
            ledgerRepository.changeBalance(central.id(), quantity.negate(), BigDecimal.ZERO);
            ledgerRepository.syncLegacyCentralBatch(batchId);
            ledgerRepository.movement(
                "INBOUND_REVERSAL", itemId, batchId,
                InventoryLedgerRepository.CENTRAL_LOCATION, null, quantity,
                null, "INBOUND_ORDER", orderId, null, null, user.name(), reason
            );
        }
        jdbcTemplate.update(
            """
            UPDATE inventory_inbound_orders
            SET status = 'REVERSED', reversed_by = ?, reverse_reason = ?, reversed_at = CURRENT_TIMESTAMP(3)
            WHERE id = ?
            """,
            user.name(), reason, orderId
        );
        repository.log(user.name(), "红冲入库单", "inbound", orders.get(0).path("orderNo").asText(), reason);
        return getOrder(orderId);
    }

    private ArrayNode queryArray(String sql, RowMapper<ObjectNode> mapper, Object... args) {
        ArrayNode result = objectMapper.createArrayNode();
        jdbcTemplate.query(sql, mapper, args).forEach(result::add);
        return result;
    }

    private ArrayNode mergeInboundLines(JsonNode lines) {
        Map<String, ObjectNode> merged = new LinkedHashMap<>();
        for (JsonNode lineNode : lines) {
            ObjectNode line = lineNode.deepCopy();
            String itemId = text(line, "itemId");
            if (itemId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入库行缺少物资");
            BigDecimal quantity = decimal(line, "issueQuantity", "quantity");
            if (quantity.signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入库数量必须大于 0");
            BigDecimal factor = decimal(line, "conversionFactor", "factor");
            if (factor.signum() <= 0) factor = BigDecimal.ONE;
            line.put("issueQuantity", quantity);
            line.put("conversionFactor", factor);
            String key = itemId + "|" + text(line, "batchNo").trim().toLowerCase() + "|" + text(line, "expiryDate").trim();
            ObjectNode existing = merged.get(key);
            if (existing == null) {
                line.put("mergeCount", 1);
                merged.put(key, line);
                continue;
            }
            if (existing.path("conversionFactor").decimalValue().compareTo(factor) != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同一物资的换算系数不一致，请检查入库行");
            }
            existing.put("issueQuantity", existing.path("issueQuantity").decimalValue().add(quantity));
            existing.put("mergeCount", existing.path("mergeCount").asInt(1) + 1);
            String remark = text(line, "remark");
            if (!remark.isBlank()) {
                String old = text(existing, "remark");
                if (!old.contains(remark)) existing.put("remark", old.isBlank() ? remark : old + "；" + remark);
            }
        }
        ArrayNode result = objectMapper.createArrayNode();
        merged.values().forEach(result::add);
        return result;
    }

    private String nextOrderNo(LocalDate date) {
        String prefix = "RK-" + date.toString().replace("-", "") + "-";
        String last = jdbcTemplate.query(
            "SELECT order_no FROM inventory_inbound_orders WHERE order_no LIKE ? ORDER BY order_no DESC LIMIT 1",
            resultSet -> resultSet.next() ? resultSet.getString(1) : null,
            prefix + "%"
        );
        int sequence = 1;
        if (last != null) {
            try {
                sequence = Integer.parseInt(last.substring(prefix.length())) + 1;
            } catch (NumberFormatException ignored) {
                sequence = 1;
            }
        }
        return prefix + String.format("%03d", sequence);
    }

    private void requireWarehouse(SessionUser user) {
        if (user == null || !accessService.hasCapability(user, "inventory:issue")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅库管可以入库或红冲");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() ? "" : value.asText().trim();
    }

    private static BigDecimal decimal(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            value = node == null ? null : node.path(fallback);
        }
        if (value == null || value.isMissingNode() || value.isNull() || value.asText().isBlank()) return BigDecimal.ONE;
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException error) {
            return BigDecimal.ONE;
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
