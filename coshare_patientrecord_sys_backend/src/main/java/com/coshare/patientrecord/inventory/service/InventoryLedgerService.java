package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository;
import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository.Balance;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class InventoryLedgerService {

    private final InventoryLedgerRepository ledger;
    private final InventoryRepository legacy;

    public InventoryLedgerService(InventoryLedgerRepository ledger, InventoryRepository legacy) {
        this.ledger = ledger;
        this.legacy = legacy;
    }

    @Transactional
    public void recordInbound(String itemId, String batchId, BigDecimal quantity, SessionUser user, String reason) {
        Balance central = ledger.lockBalance(InventoryLedgerRepository.CENTRAL_LOCATION, itemId, batchId);
        ledger.changeBalance(central.id(), quantity, BigDecimal.ZERO);
        ledger.syncLegacyCentralBatch(batchId);
        ledger.movement(
            "INBOUND", itemId, batchId, null, InventoryLedgerRepository.CENTRAL_LOCATION, quantity,
            null, "INBOUND", batchId, null, null, user.name(), reason
        );
    }

    @Transactional
    public ObjectNode reserveRequest(ObjectNode request, SessionUser user) {
        String requestId = legacy.text(request, "id");
        Transfer existing = transferByRequest(requestId, "OUTBOUND", true);
        if (existing != null) return transferJson(existing);
        String departmentId = ledger.resolveDepartmentId(
            legacy.text(request, "departmentId"),
            legacy.text(request, "department")
        );
        String departmentName = ledger.departmentName(departmentId);
        String departmentLocation = ledger.departmentLocation(departmentId);
        request.put("departmentId", departmentId);
        request.put("department", departmentName);
        String transferId = "transfer-" + UUID.randomUUID();
        ledger.jdbc().update(
            """
            INSERT INTO inventory_transfers
              (id, request_id, transfer_type, status, from_location_id, to_location_id,
               department_id, department_name_snapshot, reason, created_by, approved_by, approved_at)
            VALUES (?, ?, 'OUTBOUND', 'RESERVED', ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3))
            """,
            transferId,
            requestId,
            InventoryLedgerRepository.CENTRAL_LOCATION,
            departmentLocation,
            departmentId,
            departmentName,
            legacy.text(request, "reason"),
            legacy.text(request, "applicant", user.name()),
            user.name()
        );
        for (JsonNode lineNode : legacy.requestLines(request)) {
            ObjectNode line = legacy.object(lineNode);
            String lineId = legacy.text(line, "id");
            String itemId = legacy.text(line, "itemId");
            BigDecimal required = legacy.quantity(line, "quantity");
            BigDecimal remaining = required;
            for (Balance balance : ledger.lockAvailableBalances(InventoryLedgerRepository.CENTRAL_LOCATION, itemId)) {
                if (remaining.signum() <= 0) break;
                BigDecimal allocated = remaining.min(balance.available());
                if (allocated.signum() <= 0) continue;
                ledger.changeBalance(balance.id(), BigDecimal.ZERO, allocated);
                ledger.jdbc().update(
                    """
                    INSERT INTO inventory_transfer_lines
                      (id, transfer_id, request_line_id, item_id, batch_id, requested_quantity, reserved_quantity)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    "transfer-line-" + UUID.randomUUID(),
                    transferId,
                    lineId,
                    itemId,
                    balance.batchId(),
                    required,
                    allocated
                );
                remaining = remaining.subtract(allocated);
            }
            if (remaining.signum() > 0) {
                throw new IllegalArgumentException("可用库存不足，无法锁定：" + legacy.itemLabel(itemId));
            }
        }
        return transferJson(new Transfer(transferId, requestId, "OUTBOUND", "RESERVED", departmentId, departmentName));
    }

    @Transactional
    public BigDecimal issueRequest(ObjectNode request, JsonNode payload, SessionUser user) {
        String requestId = legacy.text(request, "id");
        Transfer transfer = transferByRequest(requestId, "OUTBOUND", true);
        if (transfer == null) throw new IllegalArgumentException("申领单未完成库存锁定，请重新审核");
        if ("RECEIVED".equals(transfer.status())) return BigDecimal.ZERO;
        Map<String, BigDecimal> desiredByLine = desiredIssueQuantities(request, payload);
        Map<String, ObjectNode> requestLines = new HashMap<>();
        for (JsonNode lineNode : legacy.requestLines(request)) {
            ObjectNode line = legacy.object(lineNode);
            requestLines.put(legacy.text(line, "id"), line);
        }
        List<TransferAllocation> allocations = ledger.jdbc().query(
            """
            SELECT tl.id, tl.request_line_id, tl.item_id, tl.batch_id, tl.reserved_quantity,
                   tl.moved_quantity, tl.received_quantity,
                   COALESCE(b.batch_no, '') batch_no, COALESCE(b.expiry_date, '') expiry_date
            FROM inventory_transfer_lines tl
            LEFT JOIN inventory_batches b ON b.id = tl.batch_id
            WHERE tl.transfer_id = ?
            ORDER BY tl.request_line_id,
              CASE WHEN b.expiry_date IS NULL OR b.expiry_date = '' THEN 1 ELSE 0 END,
              b.expiry_date, b.updated_at, b.id
            FOR UPDATE
            """,
            (rs, rowNum) -> new TransferAllocation(
                rs.getString("id"), rs.getString("request_line_id"), rs.getString("item_id"), rs.getString("batch_id"),
                rs.getBigDecimal("reserved_quantity"), rs.getBigDecimal("moved_quantity"), rs.getBigDecimal("received_quantity"),
                rs.getString("batch_no"), rs.getString("expiry_date")
            ),
            transfer.id()
        );
        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> issuedNowByLine = new HashMap<>();
        for (TransferAllocation allocation : allocations) {
            BigDecimal desired = desiredByLine.getOrDefault(allocation.requestLineId(), BigDecimal.ZERO)
                .subtract(issuedNowByLine.getOrDefault(allocation.requestLineId(), BigDecimal.ZERO));
            if (desired.signum() <= 0) continue;
            BigDecimal unissuedAllocation = allocation.reserved().subtract(allocation.moved());
            BigDecimal movedNow = desired.min(unissuedAllocation);
            if (movedNow.signum() <= 0) continue;
            Balance central = ledger.lockBalance(
                InventoryLedgerRepository.CENTRAL_LOCATION,
                allocation.itemId(),
                allocation.batchId()
            );
            Balance transit = ledger.lockBalance(
                InventoryLedgerRepository.TRANSIT_LOCATION,
                allocation.itemId(),
                allocation.batchId()
            );
            ledger.changeBalance(central.id(), movedNow.negate(), movedNow.negate());
            ledger.changeBalance(transit.id(), movedNow, BigDecimal.ZERO);
            ledger.syncLegacyCentralBatch(allocation.batchId());
            ledger.jdbc().update(
                "UPDATE inventory_transfer_lines SET moved_quantity = moved_quantity + ? WHERE id = ?",
                movedNow,
                allocation.id()
            );
            ledger.movement(
                "TRANSFER_TO_TRANSIT", allocation.itemId(), allocation.batchId(),
                InventoryLedgerRepository.CENTRAL_LOCATION, InventoryLedgerRepository.TRANSIT_LOCATION,
                movedNow, transfer.departmentId(), "TRANSFER", transfer.id(), null, null,
                user.name(), "申领单配发转入在途"
            );
            ObjectNode line = requestLines.get(allocation.requestLineId());
            if (line != null) addBatchAllocation(line, allocation, movedNow, user.name());
            issuedNowByLine.merge(allocation.requestLineId(), movedNow, BigDecimal::add);
            total = total.add(movedNow);
        }
        for (Map.Entry<String, BigDecimal> entry : desiredByLine.entrySet()) {
            BigDecimal moved = issuedNowByLine.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            if (moved.compareTo(entry.getValue()) < 0) {
                throw new IllegalStateException("已锁定库存不足或已发放，请刷新后重试");
            }
        }
        String status = hasUnmovedReservation(transfer.id()) ? "PARTIALLY_IN_TRANSIT" : "IN_TRANSIT";
        ledger.jdbc().update(
            """
            UPDATE inventory_transfers
            SET status = ?, issued_by = ?, issued_at = COALESCE(issued_at, CURRENT_TIMESTAMP(3))
            WHERE id = ?
            """,
            status,
            user.name(),
            transfer.id()
        );
        return total;
    }

    @Transactional
    public BigDecimal receiveRequest(ObjectNode request, SessionUser user) {
        Transfer transfer = transferByRequest(legacy.text(request, "id"), "OUTBOUND", true);
        if (transfer == null) throw new IllegalArgumentException("未找到对应调拨单");
        if ("RECEIVED".equals(transfer.status())) return BigDecimal.ZERO;
        if (!List.of("IN_TRANSIT", "PARTIALLY_IN_TRANSIT").contains(transfer.status())) {
            throw new IllegalArgumentException("只能签收已配发物资");
        }
        if (hasUnmovedReservation(transfer.id())) throw new IllegalArgumentException("申领单尚未全部配发，不能签收");
        String departmentLocation = ledger.departmentLocation(transfer.departmentId());
        List<TransferAllocation> allocations = transferAllocationsForUpdate(transfer.id());
        BigDecimal total = BigDecimal.ZERO;
        for (TransferAllocation allocation : allocations) {
            BigDecimal pending = allocation.moved().subtract(allocation.received());
            if (pending.signum() <= 0) continue;
            Balance transit = ledger.lockBalance(
                InventoryLedgerRepository.TRANSIT_LOCATION,
                allocation.itemId(),
                allocation.batchId()
            );
            Balance department = ledger.lockBalance(departmentLocation, allocation.itemId(), allocation.batchId());
            ledger.changeBalance(transit.id(), pending.negate(), BigDecimal.ZERO);
            ledger.changeBalance(department.id(), pending, BigDecimal.ZERO);
            ledger.jdbc().update(
                "UPDATE inventory_transfer_lines SET received_quantity = received_quantity + ? WHERE id = ?",
                pending,
                allocation.id()
            );
            ledger.movement(
                "TRANSFER_TO_DEPARTMENT", allocation.itemId(), allocation.batchId(),
                InventoryLedgerRepository.TRANSIT_LOCATION, departmentLocation, pending,
                transfer.departmentId(), "TRANSFER", transfer.id(), null, null, user.name(), "科室签收入库"
            );
            total = total.add(pending);
        }
        ledger.jdbc().update(
            """
            UPDATE inventory_transfers
            SET status = 'RECEIVED', received_by = ?, received_at = CURRENT_TIMESTAMP(3)
            WHERE id = ?
            """,
            user.name(),
            transfer.id()
        );
        return total;
    }

    @Transactional
    public void releaseReservation(String requestId, SessionUser user, String reason) {
        Transfer transfer = transferByRequest(requestId, "OUTBOUND", true);
        if (transfer == null || List.of("CANCELLED", "VOID").contains(transfer.status())) return;
        List<TransferAllocation> allocations = transferAllocationsForUpdate(transfer.id());
        for (TransferAllocation allocation : allocations) {
            if (allocation.moved().signum() > 0) {
                throw new IllegalArgumentException("已配发的申领单不能直接作废，请先办理退库");
            }
            if (allocation.reserved().signum() <= 0) continue;
            Balance central = ledger.lockBalance(
                InventoryLedgerRepository.CENTRAL_LOCATION,
                allocation.itemId(),
                allocation.batchId()
            );
            ledger.changeBalance(central.id(), BigDecimal.ZERO, allocation.reserved().negate());
            ledger.jdbc().update(
                "UPDATE inventory_transfer_lines SET reserved_quantity = 0 WHERE id = ?",
                allocation.id()
            );
        }
        ledger.jdbc().update(
            "UPDATE inventory_transfers SET status = 'VOID', reason = ? WHERE id = ?",
            reason,
            transfer.id()
        );
    }

    @Transactional
    public ObjectNode returnToCentral(JsonNode payload, SessionUser user) {
        String departmentId = ledger.resolveDepartmentId(
            legacy.text(payload, "departmentId"),
            legacy.text(payload, "department", user.department())
        );
        String itemId = legacy.text(payload, "itemId");
        BigDecimal requested = legacy.quantity(payload, "quantity");
        if (itemId.isBlank() || requested.signum() <= 0) throw new IllegalArgumentException("请选择物资并填写正数退库数量");
        String clientRequestId = legacy.text(payload, "clientRequestId");
        if (!clientRequestId.isBlank()) {
            List<String> existing = ledger.jdbc().query(
                "SELECT id FROM inventory_transfers WHERE client_request_id = ?",
                (rs, rowNum) -> rs.getString(1),
                clientRequestId
            );
            if (!existing.isEmpty()) return transferByIdJson(existing.get(0));
        }
        String departmentLocation = ledger.departmentLocation(departmentId);
        String transferId = "transfer-" + UUID.randomUUID();
        ledger.jdbc().update(
            """
            INSERT INTO inventory_transfers
              (id, transfer_type, status, from_location_id, to_location_id, department_id,
               department_name_snapshot, client_request_id, reason, created_by, approved_by,
               issued_by, received_by, approved_at, issued_at, received_at)
            VALUES (?, 'RETURN', 'RECEIVED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            """,
            transferId, departmentLocation, InventoryLedgerRepository.CENTRAL_LOCATION,
            departmentId, ledger.departmentName(departmentId), clientRequestId.isBlank() ? null : clientRequestId,
            legacy.text(payload, "reason"), user.name(), user.name(), user.name(), user.name()
        );
        BigDecimal remaining = requested;
        String selectedBatch = legacy.text(payload, "batchId");
        List<Balance> balances = selectedBatch.isBlank()
            ? ledger.lockAvailableBalances(departmentLocation, itemId)
            : List.of(ledger.lockBalance(departmentLocation, itemId, selectedBatch));
        for (Balance source : balances) {
            if (remaining.signum() <= 0) break;
            BigDecimal moved = remaining.min(source.available());
            if (moved.signum() <= 0) continue;
            Balance central = ledger.lockBalance(InventoryLedgerRepository.CENTRAL_LOCATION, itemId, source.batchId());
            ledger.changeBalance(source.id(), moved.negate(), BigDecimal.ZERO);
            ledger.changeBalance(central.id(), moved, BigDecimal.ZERO);
            ledger.syncLegacyCentralBatch(source.batchId());
            ledger.jdbc().update(
                """
                INSERT INTO inventory_transfer_lines
                  (id, transfer_id, item_id, batch_id, requested_quantity, reserved_quantity, moved_quantity, received_quantity)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?)
                """,
                "transfer-line-" + UUID.randomUUID(), transferId, itemId, source.batchId(), requested, moved, moved
            );
            ledger.movement(
                "RETURN_TO_CENTRAL", itemId, source.batchId(), departmentLocation,
                InventoryLedgerRepository.CENTRAL_LOCATION, moved, departmentId, "TRANSFER", transferId,
                null, null, user.name(), legacy.text(payload, "reason", "科室退库")
            );
            remaining = remaining.subtract(moved);
        }
        if (remaining.signum() > 0) throw new IllegalArgumentException("科室可用库存不足，无法退库");
        return transferByIdJson(transferId);
    }

    @Transactional
    public ObjectNode confirmOpening(JsonNode payload, SessionUser user) {
        String departmentId = ledger.resolveDepartmentId(
            legacy.text(payload, "departmentId"),
            legacy.text(payload, "department", user.department())
        );
        return ledger.confirmOpening(departmentId, payload, user.name());
    }

    @Transactional
    public void scrap(JsonNode payload, SessionUser user) {
        String itemId = legacy.text(payload, "itemId");
        BigDecimal requested = legacy.quantity(payload, "quantity");
        if (itemId.isBlank() || requested.signum() <= 0) throw new IllegalArgumentException("请选择物资并填写正数报损数量");
        LocationScope scope = locationScope(payload, user);
        BigDecimal remaining = requested;
        String selectedBatch = legacy.text(payload, "batchId");
        List<Balance> balances = selectedBatch.isBlank()
            ? ledger.lockAvailableBalances(scope.locationId(), itemId)
            : List.of(ledger.lockBalance(scope.locationId(), itemId, selectedBatch));
        for (Balance balance : balances) {
            if (remaining.signum() <= 0) break;
            BigDecimal quantity = remaining.min(balance.available());
            if (quantity.signum() <= 0) continue;
            ledger.changeBalance(balance.id(), quantity.negate(), BigDecimal.ZERO);
            if (InventoryLedgerRepository.CENTRAL_LOCATION.equals(scope.locationId())) {
                ledger.syncLegacyCentralBatch(balance.batchId());
            }
            ledger.movement(
                "SCRAP", itemId, balance.batchId(), scope.locationId(), null, quantity,
                scope.departmentId(), "SCRAP", "scrap-" + UUID.randomUUID(), null, null,
                user.name(), legacy.text(payload, "reason", "库存报损")
            );
            remaining = remaining.subtract(quantity);
        }
        if (remaining.signum() > 0) throw new IllegalArgumentException("所属库位可用库存不足，无法报损");
    }

    @Transactional
    public CountResult count(JsonNode payload, SessionUser user) {
        String itemId = legacy.text(payload, "itemId");
        String batchId = legacy.text(payload, "batchId");
        if (itemId.isBlank() || batchId.isBlank()) throw new IllegalArgumentException("盘点必须选择物资和批次");
        LocationScope scope = locationScope(payload, user);
        Balance balance = ledger.lockBalance(scope.locationId(), itemId, batchId);
        BigDecimal book = balance.quantity();
        BigDecimal actual = legacy.quantity(payload, "actualQuantity");
        if (actual.signum() < 0) throw new IllegalArgumentException("实盘数量不能小于 0");
        BigDecimal difference = actual.subtract(book);
        if (difference.signum() != 0) {
            ledger.changeBalance(balance.id(), difference, BigDecimal.ZERO);
            if (InventoryLedgerRepository.CENTRAL_LOCATION.equals(scope.locationId())) {
                ledger.syncLegacyCentralBatch(batchId);
            }
            ledger.movement(
                difference.signum() > 0 ? "COUNT_ADJUSTMENT_IN" : "COUNT_ADJUSTMENT_OUT",
                itemId, batchId, difference.signum() > 0 ? null : scope.locationId(),
                difference.signum() > 0 ? scope.locationId() : null, difference.abs(),
                scope.departmentId(), "COUNT", "count-" + UUID.randomUUID(), null, null,
                user.name(), legacy.text(payload, "reason", "库存盘点")
            );
        }
        return new CountResult(scope.locationId(), scope.departmentId(), batchId, book, actual, difference);
    }

    @Transactional(readOnly = true)
    public void populateWeeklySuggestion(ObjectNode row, SessionUser user) {
        String departmentId = ledger.resolveDepartmentId(
            legacy.text(row, "departmentId"),
            legacy.text(row, "department", user.department())
        );
        String itemId = legacy.text(row, "itemId");
        String weekNo = legacy.text(row, "weekNo");
        if (weekNo.isBlank() || itemId.isBlank()) return;
        String locationId = ledger.departmentLocation(departmentId);
        BigDecimal consumed = ledger.jdbc().queryForObject(
            """
            SELECT COALESCE(SUM(CASE WHEN movement_type = 'CONSUMPTION' THEN quantity
                                    WHEN movement_type = 'CONSUMPTION_REVERSAL' THEN -quantity ELSE 0 END), 0)
            FROM inventory_ledger_movements
            WHERE department_id = ? AND item_id = ? AND DATE_FORMAT(occurred_at, '%x-W%v') = ?
            """,
            BigDecimal.class,
            departmentId,
            itemId,
            weekNo
        );
        BigDecimal remaining = ledger.jdbc().queryForObject(
            "SELECT COALESCE(SUM(quantity), 0) FROM inventory_batch_balances WHERE location_id = ? AND item_id = ?",
            BigDecimal.class,
            locationId,
            itemId
        );
        BigDecimal safety = ledger.jdbc().queryForObject(
            "SELECT COALESCE(safety_stock, 0) FROM inventory_items WHERE id = ?",
            BigDecimal.class,
            itemId
        );
        consumed = consumed == null ? BigDecimal.ZERO : consumed;
        remaining = remaining == null ? BigDecimal.ZERO : remaining;
        safety = safety == null ? BigDecimal.ZERO : safety;
        BigDecimal suggested = consumed.add(safety).subtract(remaining).max(BigDecimal.ZERO);
        row.put("departmentId", departmentId);
        row.put("department", ledger.departmentName(departmentId));
        legacy.putQuantity(row, "consumedQuantity", consumed);
        legacy.putQuantity(row, "actualConsumedQuantity", consumed);
        legacy.putQuantity(row, "remainingQuantity", remaining);
        legacy.putQuantity(row, "suggestedQuantity", suggested);
        legacy.putQuantity(row, "safetyStock", safety);
        if (!row.hasNonNull("adjustedQuantity")) legacy.putQuantity(row, "adjustedQuantity", suggested);
        legacy.putQuantity(row, "nextWeekQuantity", legacy.quantity(row, "adjustedQuantity", suggested));
        row.put("sourceType", "LEDGER");
    }

    private LocationScope locationScope(JsonNode payload, SessionUser user) {
        String requestedLocationId = legacy.text(payload, "locationId");
        String requestedLocationType = legacy.text(payload, "locationType");
        if (InventoryLedgerRepository.CENTRAL_LOCATION.equals(requestedLocationId)
            || "CENTRAL".equalsIgnoreCase(requestedLocationType)) {
            return new LocationScope(InventoryLedgerRepository.CENTRAL_LOCATION, null);
        }
        String departmentId = legacy.text(payload, "departmentId");
        String department = legacy.text(payload, "department");
        if (departmentId.isBlank() && department.isBlank()) {
            return new LocationScope(InventoryLedgerRepository.CENTRAL_LOCATION, null);
        }
        String resolved = ledger.resolveDepartmentId(departmentId, department.isBlank() ? user.department() : department);
        return new LocationScope(ledger.departmentLocation(resolved), resolved);
    }

    private Map<String, BigDecimal> desiredIssueQuantities(ObjectNode request, JsonNode payload) {
        Map<String, BigDecimal> requested = new HashMap<>();
        JsonNode payloadLines = payload.path("lines");
        for (JsonNode lineNode : legacy.requestLines(request)) {
            ObjectNode line = legacy.object(lineNode);
            BigDecimal remaining = legacy.quantity(line, "quantity").subtract(legacy.quantity(line, "issuedQuantity"));
            if (remaining.signum() <= 0) continue;
            BigDecimal desired = remaining;
            if (payloadLines.isArray() && !payloadLines.isEmpty()) {
                desired = BigDecimal.ZERO;
                for (JsonNode payloadLine : payloadLines) {
                    if (legacy.text(line, "id").equals(legacy.text(payloadLine, "id"))
                        || legacy.text(line, "itemId").equals(legacy.text(payloadLine, "itemId"))) {
                        desired = legacy.quantity(payloadLine, "issuedQuantity", remaining);
                        break;
                    }
                }
            }
            if (desired.signum() > 0) requested.put(legacy.text(line, "id"), desired.min(remaining));
        }
        return requested;
    }

    private void addBatchAllocation(ObjectNode line, TransferAllocation allocation, BigDecimal quantity, String operator) {
        ArrayNode batchAllocations = line.withArray("batchAllocations");
        ObjectNode row = batchAllocations.addObject();
        row.put("batchId", allocation.batchId());
        row.put("batchNo", allocation.batchNo());
        row.put("expiryDate", allocation.expiryDate());
        row.put("quantity", quantity);
        row.put("issuedAt", legacy.now());
        row.put("issuer", operator);
        BigDecimal issued = legacy.quantity(line, "issuedQuantity").add(quantity);
        legacy.putQuantity(line, "issuedQuantity", issued);
        line.put("status", issued.compareTo(legacy.quantity(line, "quantity")) >= 0 ? "issued" : "partially_issued");
    }

    private boolean hasUnmovedReservation(String transferId) {
        Integer count = ledger.jdbc().queryForObject(
            "SELECT COUNT(*) FROM inventory_transfer_lines WHERE transfer_id = ? AND moved_quantity < reserved_quantity",
            Integer.class,
            transferId
        );
        return count != null && count > 0;
    }

    private Transfer transferByRequest(String requestId, String transferType, boolean forUpdate) {
        List<Transfer> rows = ledger.jdbc().query(
            """
            SELECT id, request_id, transfer_type, status, department_id, department_name_snapshot
            FROM inventory_transfers WHERE request_id = ? AND transfer_type = ?
            """ + (forUpdate ? " FOR UPDATE" : ""),
            (rs, rowNum) -> new Transfer(
                rs.getString("id"), rs.getString("request_id"), rs.getString("transfer_type"),
                rs.getString("status"), rs.getString("department_id"), rs.getString("department_name_snapshot")
            ),
            requestId,
            transferType
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<TransferAllocation> transferAllocationsForUpdate(String transferId) {
        return ledger.jdbc().query(
            """
            SELECT tl.id, tl.request_line_id, tl.item_id, tl.batch_id, tl.reserved_quantity,
                   tl.moved_quantity, tl.received_quantity,
                   COALESCE(b.batch_no, '') batch_no, COALESCE(b.expiry_date, '') expiry_date
            FROM inventory_transfer_lines tl
            LEFT JOIN inventory_batches b ON b.id = tl.batch_id
            WHERE tl.transfer_id = ? ORDER BY tl.id FOR UPDATE
            """,
            (rs, rowNum) -> new TransferAllocation(
                rs.getString("id"), rs.getString("request_line_id"), rs.getString("item_id"), rs.getString("batch_id"),
                rs.getBigDecimal("reserved_quantity"), rs.getBigDecimal("moved_quantity"), rs.getBigDecimal("received_quantity"),
                rs.getString("batch_no"), rs.getString("expiry_date")
            ),
            transferId
        );
    }

    private ObjectNode transferByIdJson(String transferId) {
        ArrayNode rows = ledger.queryJson(
            """
            SELECT id, request_id requestId, transfer_type transferType, status,
                   from_location_id fromLocationId, to_location_id toLocationId,
                   department_id departmentId, department_name_snapshot department,
                   client_request_id clientRequestId, created_at createdAt,
                   issued_at issuedAt, received_at receivedAt
            FROM inventory_transfers WHERE id = ?
            """,
            transferId
        );
        return rows.isEmpty() ? ledger.mapper().createObjectNode() : (ObjectNode) rows.get(0);
    }

    private ObjectNode transferJson(Transfer transfer) {
        ObjectNode row = ledger.mapper().createObjectNode();
        row.put("id", transfer.id());
        row.put("requestId", transfer.requestId());
        row.put("transferType", transfer.type());
        row.put("status", transfer.status());
        row.put("departmentId", transfer.departmentId());
        row.put("department", transfer.department());
        return row;
    }

    private record Transfer(
        String id,
        String requestId,
        String type,
        String status,
        String departmentId,
        String department
    ) {}

    private record TransferAllocation(
        String id,
        String requestLineId,
        String itemId,
        String batchId,
        BigDecimal reserved,
        BigDecimal moved,
        BigDecimal received,
        String batchNo,
        String expiryDate
    ) {}

    public record CountResult(
        String locationId,
        String departmentId,
        String batchId,
        BigDecimal bookQuantity,
        BigDecimal actualQuantity,
        BigDecimal difference
    ) {}

    private record LocationScope(String locationId, String departmentId) {}
}
