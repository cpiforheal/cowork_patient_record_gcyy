package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository;
import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository.Balance;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class InventoryStageConsumptionService {

    private final InventoryLedgerRepository ledger;

    public InventoryStageConsumptionService(InventoryLedgerRepository ledger) {
        this.ledger = ledger;
    }

    @Transactional
    public ObjectNode enqueueStageCompletion(
        String encounterId,
        String stage,
        long completionVersion,
        String owningDepartmentId,
        String caseToken,
        String route,
        LocalDate visitDate,
        String requestedBy
    ) {
        return enqueue(encounterId, stage, completionVersion, "CONSUME", owningDepartmentId,
            caseToken, route, visitDate, requestedBy, null, null);
    }

    @Transactional
    public ObjectNode enqueueStageReversal(
        String encounterId,
        String stage,
        long completionVersion,
        String owningDepartmentId,
        String requestedBy,
        String reason
    ) {
        List<PreviousCommand> previous = ledger.jdbc().query(
            """
            SELECT id, status FROM inventory_stage_consumption_commands
            WHERE encounter_id = ? AND trigger_stage = ? AND command_type = 'CONSUME'
              AND completion_version < ?
            ORDER BY completion_version DESC, created_at DESC LIMIT 1 FOR UPDATE
            """,
            (rs, rowNum) -> new PreviousCommand(rs.getString("id"), rs.getString("status")),
            encounterId,
            normalizeStage(stage),
            completionVersion
        );
        if (previous.isEmpty()) return ledger.mapper().createObjectNode();
        PreviousCommand original = previous.get(0);
        if (List.of("PENDING", "RETRY", "FAILED").contains(original.status())) {
            ledger.jdbc().update(
                """
                UPDATE inventory_stage_consumption_commands
                SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP(3), next_attempt_at = NULL,
                    reason = ?, error_code = NULL, error_message = NULL
                WHERE id = ? AND status IN ('PENDING', 'RETRY', 'FAILED')
                """,
                blankToNull(reason),
                original.id()
            );
            ledger.jdbc().update(
                """
                UPDATE inventory_exception_tasks
                SET status = 'RESOLVED', resolved_by = ?, resolution_note = ?, resolved_at = CURRENT_TIMESTAMP(3)
                WHERE command_id = ? AND status <> 'RESOLVED'
                """,
                requestedBy,
                reason == null || reason.isBlank() ? "阶段已退回或纠错，未执行的耗用命令已取消" : reason,
                original.id()
            );
            return command(original.id());
        }
        if (!"SUCCEEDED".equals(original.status())) return command(original.id());
        return enqueue(encounterId, stage, completionVersion, "REVERSAL", owningDepartmentId,
            null, null, null, requestedBy, reason, original.id());
    }

    @Transactional
    public ObjectNode retry(String commandId, String operator) {
        int updated = ledger.jdbc().update(
            """
            UPDATE inventory_stage_consumption_commands
            SET status = 'PENDING', next_attempt_at = NULL, error_code = NULL, error_message = NULL,
                requested_by = ?, started_at = NULL
            WHERE id = ? AND status IN ('FAILED', 'RETRY')
            """,
            operator,
            commandId
        );
        if (updated == 0) throw new IllegalArgumentException("仅失败的耗用任务可以重试");
        ledger.jdbc().update(
            "UPDATE inventory_exception_tasks SET retry_count = retry_count + 1, status = 'RETRYING' WHERE command_id = ? AND status <> 'RESOLVED'",
            commandId
        );
        return command(commandId);
    }

    public String nextPendingId() {
        List<String> rows = ledger.jdbc().query(
            """
            SELECT id FROM inventory_stage_consumption_commands
            WHERE status IN ('PENDING', 'RETRY') AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP(3))
            ORDER BY created_at, CASE WHEN command_type = 'REVERSAL' THEN 0 ELSE 1 END, id LIMIT 1
            """,
            (rs, rowNum) -> rs.getString(1)
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public void processCommand(String commandId) {
        List<Command> rows = ledger.jdbc().query(
            """
            SELECT id, encounter_id, trigger_stage, completion_version, command_type, department_id,
                   department_name_snapshot, case_token, route, visit_date, reversal_of_command_id,
                   requested_by, reason, status
            FROM inventory_stage_consumption_commands WHERE id = ? FOR UPDATE
            """,
            (rs, rowNum) -> new Command(
                rs.getString("id"), rs.getString("encounter_id"), rs.getString("trigger_stage"),
                rs.getLong("completion_version"), rs.getString("command_type"), rs.getString("department_id"),
                rs.getString("department_name_snapshot"), rs.getString("case_token"), rs.getString("route"),
                rs.getObject("visit_date", LocalDate.class), rs.getString("reversal_of_command_id"),
                rs.getString("requested_by"), rs.getString("reason"), rs.getString("status")
            ),
            commandId
        );
        if (rows.isEmpty()) return;
        Command command = rows.get(0);
        if (!List.of("PENDING", "RETRY").contains(command.status())) return;
        ledger.jdbc().update(
            "UPDATE inventory_stage_consumption_commands SET status = 'PROCESSING', started_at = CURRENT_TIMESTAMP(3), attempt_count = attempt_count + 1 WHERE id = ?",
            command.id()
        );
        if ("REVERSAL".equals(command.type())) reverse(command);
        else consume(command);
    }

    @Transactional
    public void markUnexpectedFailure(String commandId, Exception error) {
        fail(commandId, "SYSTEM_ERROR", safeMessage(error), null, null, null);
    }

    public ObjectNode command(String commandId) {
        var rows = ledger.queryJson(
            """
            SELECT id, encounter_id encounterId, trigger_stage triggerStage,
                   completion_version completionVersion, command_type commandType,
                   department_id departmentId, department_name_snapshot department,
                   status, attempt_count attemptCount, error_code errorCode,
                   error_message errorMessage, created_at createdAt, completed_at completedAt
            FROM inventory_stage_consumption_commands WHERE id = ?
            """,
            commandId
        );
        return rows.isEmpty() ? ledger.mapper().createObjectNode() : (ObjectNode) rows.get(0);
    }

    private ObjectNode enqueue(
        String encounterId,
        String stage,
        long completionVersion,
        String type,
        String departmentId,
        String caseToken,
        String route,
        LocalDate visitDate,
        String requestedBy,
        String reason,
        String reversalOfCommandId
    ) {
        if (encounterId == null || encounterId.isBlank()) throw new IllegalArgumentException("就诊标识不能为空");
        String normalizedStage = normalizeStage(stage);
        String resolvedDepartment = ledger.resolveDepartmentId(departmentId, departmentId);
        String id = "consume-command-" + UUID.randomUUID();
        ObjectNode payload = ledger.mapper().createObjectNode();
        payload.put("encounterId", encounterId);
        payload.put("triggerStage", normalizedStage);
        payload.put("completionVersion", completionVersion);
        payload.put("commandType", type);
        ledger.jdbc().update(
            """
            INSERT INTO inventory_stage_consumption_commands
              (id, encounter_id, trigger_stage, completion_version, command_type, department_id,
               department_name_snapshot, case_token, route, visit_date, status,
               reversal_of_command_id, requested_by, reason, payload_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE id = id
            """,
            id, encounterId, normalizedStage, completionVersion, type, resolvedDepartment,
            ledger.departmentName(resolvedDepartment), blankToNull(caseToken), blankToNull(route), visitDate,
            blankToNull(reversalOfCommandId), requestedBy, reason, payload.toString()
        );
        String actualId = ledger.jdbc().queryForObject(
            """
            SELECT id FROM inventory_stage_consumption_commands
            WHERE encounter_id = ? AND trigger_stage = ? AND completion_version = ? AND command_type = ?
            """,
            String.class,
            encounterId,
            normalizedStage,
            completionVersion,
            type
        );
        return command(actualId);
    }

    private void consume(Command command) {
        if (!ledger.isOpeningConfirmed(command.departmentId())) {
            fail(command.id(), "OPENING_UNCONFIRMED", "科室期初库存尚未盘点确认，自动耗用已暂停",
                command.departmentId(), command.encounterId(), command.stage());
            return;
        }
        PackageDefinition packageDefinition = findPackage(command);
        if (packageDefinition == null) {
            fail(command.id(), "PACKAGE_MISSING", "当前科室、就诊类型和执行环节没有生效套餐",
                command.departmentId(), command.encounterId(), command.stage());
            return;
        }
        List<PackageLine> lines = packageLines(packageDefinition.id());
        if (lines.isEmpty()) {
            fail(command.id(), "PACKAGE_EMPTY", "生效套餐未配置物资明细",
                command.departmentId(), command.encounterId(), command.stage());
            return;
        }
        String locationId = ledger.departmentLocation(command.departmentId());
        List<Allocation> allocations = new ArrayList<>();
        Map<String, BigDecimal> requiredByItem = new LinkedHashMap<>();
        for (PackageLine line : lines) requiredByItem.merge(line.itemId(), line.quantity(), BigDecimal::add);
        for (Map.Entry<String, BigDecimal> required : requiredByItem.entrySet()) {
            BigDecimal remaining = required.getValue();
            for (Balance balance : ledger.lockAvailableBalances(locationId, required.getKey())) {
                if (remaining.signum() <= 0) break;
                BigDecimal quantity = remaining.min(balance.available());
                if (quantity.signum() > 0) {
                    allocations.add(new Allocation(required.getKey(), balance, quantity));
                    remaining = remaining.subtract(quantity);
                }
            }
            if (remaining.signum() > 0) {
                fail(command.id(), "STOCK_SHORTAGE", "科室库存不足，无法自动扣减物资 " + required.getKey(),
                    command.departmentId(), command.encounterId(), command.stage());
                return;
            }
        }
        String eventId = "consume-" + UUID.randomUUID();
        ObjectNode raw = ledger.mapper().createObjectNode();
        raw.put("commandId", command.id());
        raw.put("triggerStage", command.stage());
        raw.put("completionVersion", command.completionVersion());
        ledger.jdbc().update(
            """
            INSERT INTO inventory_consumption_events
              (id, command_id, encounter_id, case_token, route, department, department_id,
               visit_date, package_id, status, event_kind, trigger_stage, completion_version,
               operator_name, created_at, raw_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'succeeded', 'CONSUMPTION', ?, ?, ?, ?, ?)
            """,
            eventId, command.id(), command.encounterId(), command.caseToken(), defaultRoute(command.route()),
            command.department(), command.departmentId(), command.visitDate() == null ? LocalDate.now().toString() : command.visitDate().toString(),
            packageDefinition.id(), command.stage(), command.completionVersion(), command.requestedBy(),
            java.time.LocalDateTime.now().toString(), raw.toString()
        );
        for (Allocation allocation : allocations) {
            ledger.changeBalance(allocation.balance().id(), allocation.quantity().negate(), BigDecimal.ZERO);
            String detailId = "consume-detail-" + UUID.randomUUID();
            ledger.jdbc().update(
                """
                INSERT INTO inventory_consumption_details
                  (id, event_id, item_id, batch_id, location_id, quantity, detail_kind, raw_json)
                VALUES (?, ?, ?, ?, ?, ?, 'CONSUMPTION', JSON_OBJECT('source', 'PACKAGE'))
                """,
                detailId, eventId, allocation.itemId(), allocation.balance().batchId(),
                locationId, allocation.quantity()
            );
            ledger.movement(
                "CONSUMPTION", allocation.itemId(), allocation.balance().batchId(), locationId, null,
                allocation.quantity(), command.departmentId(), "STAGE_COMMAND", command.id(), eventId,
                null, command.requestedBy(), "执行环节完成自动耗用"
            );
        }
        succeed(command.id());
    }

    private void reverse(Command command) {
        if (command.reversalOfCommandId() == null) {
            fail(command.id(), "ORIGINAL_NOT_FOUND", "未找到可冲销的已成功耗用任务",
                command.departmentId(), command.encounterId(), command.stage());
            return;
        }
        List<OriginalDetail> originals = ledger.jdbc().query(
            """
            SELECT e.id event_id, e.package_id, d.item_id, d.batch_id, d.location_id, d.quantity
            FROM inventory_consumption_events e
            JOIN inventory_consumption_details d ON d.event_id = e.id
            WHERE e.command_id = ? AND e.status = 'succeeded' AND e.event_kind = 'CONSUMPTION'
            FOR UPDATE
            """,
            (rs, rowNum) -> new OriginalDetail(
                rs.getString("event_id"), rs.getString("package_id"), rs.getString("item_id"),
                rs.getString("batch_id"), rs.getString("location_id"), rs.getBigDecimal("quantity")
            ),
            command.reversalOfCommandId()
        );
        if (originals.isEmpty()) {
            fail(command.id(), "ORIGINAL_NOT_FOUND", "原耗用明细不存在，无法冲销",
                command.departmentId(), command.encounterId(), command.stage());
            return;
        }
        String eventId = "consume-reversal-" + UUID.randomUUID();
        String originalEventId = originals.get(0).eventId();
        Integer existingReversal = ledger.jdbc().queryForObject(
            "SELECT COUNT(*) FROM inventory_consumption_events WHERE reversal_of_event_id = ? AND event_kind = 'REVERSAL' AND status = 'succeeded'",
            Integer.class,
            originalEventId
        );
        if (existingReversal != null && existingReversal > 0) {
            succeed(command.id());
            return;
        }
        ObjectNode raw = ledger.mapper().createObjectNode();
        raw.put("commandId", command.id());
        raw.put("reversalOfCommandId", command.reversalOfCommandId());
        ledger.jdbc().update(
            """
            INSERT INTO inventory_consumption_events
              (id, command_id, encounter_id, case_token, route, department, department_id,
               visit_date, package_id, status, event_kind, reversal_of_event_id, trigger_stage,
               completion_version, operator_name, created_at, raw_json)
            VALUES (?, ?, ?, NULL, 'reversal', ?, ?, ?, ?, 'succeeded', 'REVERSAL', ?, ?, ?, ?, ?, ?)
            """,
            eventId, command.id(), command.encounterId(), command.department(), command.departmentId(),
            LocalDate.now().toString(), originals.get(0).packageId(), originalEventId,
            command.stage(), command.completionVersion(), command.requestedBy(),
            java.time.LocalDateTime.now().toString(), raw.toString()
        );
        for (OriginalDetail original : originals) {
            Balance balance = ledger.lockBalance(original.locationId(), original.itemId(), original.batchId());
            ledger.changeBalance(balance.id(), original.quantity(), BigDecimal.ZERO);
            ledger.jdbc().update(
                """
                INSERT INTO inventory_consumption_details
                  (id, event_id, item_id, batch_id, location_id, quantity, detail_kind, raw_json)
                VALUES (?, ?, ?, ?, ?, ?, 'REVERSAL', JSON_OBJECT('reversalOfEventId', ?))
                """,
                "consume-detail-" + UUID.randomUUID(), eventId, original.itemId(), original.batchId(),
                original.locationId(), original.quantity(), originalEventId
            );
            ledger.movement(
                "CONSUMPTION_REVERSAL", original.itemId(), original.batchId(), null, original.locationId(),
                original.quantity(), command.departmentId(), "STAGE_COMMAND", command.id(), eventId,
                null, command.requestedBy(), command.reason() == null ? "阶段退回耗用冲销" : command.reason()
            );
        }
        succeed(command.id());
    }

    private PackageDefinition findPackage(Command command) {
        String careType = "inpatient".equalsIgnoreCase(command.route()) ? "inpatient" : "outpatient";
        List<PackageDefinition> rows = ledger.jdbc().query(
            """
            SELECT id, version_no FROM inventory_packages
            WHERE department_id = ? AND care_type = ? AND trigger_stage = ? AND status = 'enabled'
              AND (effective_date IS NULL OR effective_date = '' OR effective_date <= ?)
            ORDER BY version_no DESC, updated_at DESC LIMIT 1
            """,
            (rs, rowNum) -> new PackageDefinition(rs.getString("id"), rs.getInt("version_no")),
            command.departmentId(), careType, command.stage(),
            command.visitDate() == null ? LocalDate.now().toString() : command.visitDate().toString()
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<PackageLine> packageLines(String packageId) {
        return ledger.jdbc().query(
            "SELECT item_id, quantity FROM inventory_package_lines WHERE package_id = ? AND quantity > 0 ORDER BY id",
            (rs, rowNum) -> new PackageLine(rs.getString("item_id"), rs.getBigDecimal("quantity")),
            packageId
        );
    }

    private void succeed(String commandId) {
        ledger.jdbc().update(
            "UPDATE inventory_stage_consumption_commands SET status = 'SUCCEEDED', completed_at = CURRENT_TIMESTAMP(3), error_code = NULL, error_message = NULL WHERE id = ?",
            commandId
        );
        ledger.jdbc().update(
            "UPDATE inventory_exception_tasks SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP(3), resolution_note = '任务重试成功' WHERE command_id = ? AND status <> 'RESOLVED'",
            commandId
        );
    }

    private void fail(String commandId, String code, String message, String departmentId, String encounterId, String stage) {
        ledger.jdbc().update(
            "UPDATE inventory_stage_consumption_commands SET status = 'FAILED', error_code = ?, error_message = ?, completed_at = CURRENT_TIMESTAMP(3) WHERE id = ?",
            code, message, commandId
        );
        String department = departmentId == null ? "" : ledger.departmentName(departmentId);
        ledger.jdbc().update(
            """
            INSERT INTO inventory_exception_tasks
              (id, command_id, exception_type, severity, status, department_id,
               department_name_snapshot, encounter_id, trigger_stage, message)
            VALUES (?, ?, ?, 'HIGH', 'OPEN', ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE status = 'OPEN', message = VALUES(message), updated_at = CURRENT_TIMESTAMP(3)
            """,
            "inventory-exception-" + UUID.randomUUID(), commandId, code, blankToNull(departmentId),
            department, blankToNull(encounterId), blankToNull(stage), message
        );
    }

    private static String normalizeStage(String stage) {
        if (stage == null || stage.isBlank()) throw new IllegalArgumentException("执行环节不能为空");
        return stage.trim().toUpperCase();
    }

    private static String defaultRoute(String route) {
        return route == null || route.isBlank() ? "outpatient" : route;
    }

    private static String safeMessage(Exception error) {
        String message = error == null ? "库存任务执行失败" : error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.length() > 480 ? message.substring(0, 480) : message;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record Command(
        String id, String encounterId, String stage, long completionVersion, String type,
        String departmentId, String department, String caseToken, String route, LocalDate visitDate,
        String reversalOfCommandId, String requestedBy, String reason, String status
    ) {}

    private record PackageDefinition(String id, int version) {}
    private record PackageLine(String itemId, BigDecimal quantity) {}
    private record Allocation(String itemId, Balance balance, BigDecimal quantity) {}
    private record OriginalDetail(
        String eventId, String packageId, String itemId, String batchId, String locationId, BigDecimal quantity
    ) {}

    private record PreviousCommand(String id, String status) {}
}
