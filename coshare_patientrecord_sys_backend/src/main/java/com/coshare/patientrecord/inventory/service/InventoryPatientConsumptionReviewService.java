package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.InventoryAccessService;
import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository;
import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository.Balance;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Warehouse review for patient-bound consumable documents. Only approved lines
 * produce inventory consumption events and ledger movements.
 */
@Service
@Profile("mysql")
public class InventoryPatientConsumptionReviewService {

    private static final Set<String> DECISIONS = Set.of("APPROVED", "REJECTED");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryLedgerRepository ledger;
    private final InventoryAccessService inventoryAccess;
    private final InventoryPatientConsumptionService consumptionService;
    private final InventoryRepository repository;

    public InventoryPatientConsumptionReviewService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        InventoryLedgerRepository ledger,
        InventoryAccessService inventoryAccess,
        InventoryPatientConsumptionService consumptionService,
        InventoryRepository repository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.ledger = ledger;
        this.inventoryAccess = inventoryAccess;
        this.consumptionService = consumptionService;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ObjectNode batches(LocalDate businessDate, String departmentKey, String status, SessionUser user) {
        LocalDate date = businessDate == null ? LocalDate.now() : businessDate;
        StringBuilder sql = new StringBuilder("SELECT * FROM inventory_consumption_approval_batches WHERE business_date = ?");
        List<Object> params = new ArrayList<>();
        params.add(date);
        if (departmentKey != null && !departmentKey.isBlank()) {
            sql.append(" AND department_key = ?");
            params.add(departmentKey.trim());
        } else if (!inventoryAccess.canViewAllDepartments(user)) {
            sql.append(" AND department_name = ?");
            params.add(canonicalDepartmentName(user.department()));
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status.trim().toUpperCase(Locale.ROOT));
        }
        sql.append(" ORDER BY department_name");
        ArrayNode list = jdbcTemplate.query(sql.toString(), resultSet -> {
            ArrayNode rows = JsonNodeFactory.instance.arrayNode();
            while (resultSet.next()) rows.add(batchRow(resultSet));
            return rows;
        }, params.toArray());
        ObjectNode summary = objectMapper.createObjectNode();
        int pending = 0, partial = 0, approved = 0, returned = 0;
        for (JsonNode row : list) {
            switch (row.path("status").asText("")) {
                case "PENDING", "IN_REVIEW" -> pending++;
                case "PARTIALLY_APPROVED" -> partial++;
                case "APPROVED" -> approved++;
                case "RETURNED" -> returned++;
                default -> { }
            }
        }
        summary.put("total", list.size());
        summary.put("pending", pending);
        summary.put("partiallyApproved", partial);
        summary.put("approved", approved);
        summary.put("returned", returned);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("businessDate", date.toString());
        result.set("summary", summary);
        result.set("list", list);
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode batch(String batchId, SessionUser user) {
        BatchRow batch = requireBatch(batchId, user);
        if ("DAILY".equals(batch.batchType())) return dailyBatch(batch);
        List<ObjectNode> documents = jdbcTemplate.query(
            """
            SELECT d.*, si.patient_name, si.visit_no, si.service_name, si.service_code, si.stage_code,
                   si.service_started_at, si.care_type, si.source_type
            FROM inventory_patient_consumption_documents d
            JOIN inventory_service_instances si ON si.id = d.service_instance_id
            WHERE d.department_id = ? AND d.business_date = ?
            ORDER BY si.service_started_at, si.patient_name
            """,
            (resultSet, rowNum) -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("documentId", resultSet.getString("id"));
                row.put("serviceInstanceId", resultSet.getString("service_instance_id"));
                row.put("patientId", resultSet.getString("patient_id"));
                row.put("patientName", resultSet.getString("patient_name"));
                row.put("visitNo", resultSet.getString("visit_no"));
                row.put("encounterId", resultSet.getString("encounter_id"));
                row.put("careType", resultSet.getString("care_type"));
                row.put("serviceCode", resultSet.getString("service_code"));
                row.put("serviceName", resultSet.getString("service_name"));
                row.put("stageCode", resultSet.getString("stage_code"));
                row.put("sourceType", resultSet.getString("source_type"));
                Timestamp startedAt = resultSet.getTimestamp("service_started_at");
                row.put("serviceStartedAt", startedAt == null ? "" : startedAt.toLocalDateTime().toString());
                row.put("status", resultSet.getString("status"));
                row.put("revision", resultSet.getInt("revision"));
                Timestamp submittedAt = resultSet.getTimestamp("submitted_at");
                row.put("submittedAt", submittedAt == null ? "" : submittedAt.toLocalDateTime().toString());
                row.put("submittedBy", resultSet.getString("submitted_by"));
                return row;
            },
            batch.departmentId(), batch.businessDate()
        );
        ArrayNode documentArray = JsonNodeFactory.instance.arrayNode();
        for (ObjectNode document : documents) {
            document.set("lines", loadLines(document.path("documentId").asText()));
            documentArray.add(document);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("batch", batchJson(batch));
        result.set("documents", documentArray);
        result.put("deferredLineCount", deferredLineCount(batch));
        return result;
    }

    @Transactional
    public ObjectNode decide(String batchId, JsonNode payload, SessionUser user) {
        BatchRow batch = requireBatchForUpdate(batchId, user);
        if ("DAILY".equals(batch.batchType())) return decideDaily(batch, payload, user);
        JsonNode decisions = payload == null ? null : payload.path("decisions");
        if (decisions == null || !decisions.isArray() || decisions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择需要审核的耗材行");
        }
        Set<String> seen = new HashSet<>();
        ArrayNode errors = objectMapper.createArrayNode();
        ArrayNode deferred = objectMapper.createArrayNode();
        int approved = 0;
        int rejected = 0;
        int deferredCount = 0;
        for (JsonNode decisionNode : decisions) {
            String lineId = text(decisionNode, "lineId");
            String decision = text(decisionNode, "decision").toUpperCase(Locale.ROOT);
            String note = text(decisionNode, "note");
            if (lineId.isBlank() || !DECISIONS.contains(decision)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审核决策格式不正确");
            }
            if (!seen.add(lineId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同一耗材行不能重复审核");
            }
            ReviewLine line = lockLine(lineId, batch);
            if (decision.equals(line.reviewStatus())) {
                continue;
            }
            if ("APPROVED".equals(line.reviewStatus())) {
                errors.add(error(lineId, "该行已审核通过，不能重复处理"));
                continue;
            }
            if ("REJECTED".equals(decision)) {
                reject(line, batch, note, user);
                rejected++;
                continue;
            }
            try {
                approve(line, batch, note, user);
                approved++;
            } catch (StockShortageException shortage) {
                approveWithoutDeduction(line, batch, note, shortage.getMessage(), deferred, user);
                deferredCount++;
            }
        }
        consumptionService.refreshBatch(batchId);
        repository.log(user.name(), "审核患者耗材清单", "patient_consumption_approval_batch",
            batch.departmentName() + " " + batch.businessDate(),
            "通过 " + approved + " 行，驳回 " + rejected + " 行，待补扣 " + deferredCount + " 行"
                + (errors.isEmpty() ? "" : "，异常 " + errors.size() + " 行"));
        ObjectNode result = batch(batchId, user);
        result.set("errors", errors);
        result.set("deferred", deferred);
        result.put("deferredLineCount", deferredLineCount(batch));
        return result;
    }

    private ObjectNode dailyBatch(BatchRow batch) {
        ArrayNode documents = JsonNodeFactory.instance.arrayNode();
        DailyReportRow report = loadDailyReport(batch);
        if (report != null) {
            ObjectNode document = documents.addObject();
            document.put("documentId", report.id());
            document.put("serviceInstanceId", "");
            document.put("patientId", "");
            document.put("patientName", "科室日报");
            document.put("visitNo", "");
            document.put("encounterId", "");
            document.put("careType", "");
            document.put("serviceCode", "DAILY_REPORT");
            document.put("serviceName", "科室日报");
            document.put("stageCode", "DAILY_REPORT");
            document.put("sourceType", "DAILY");
            document.put("serviceStartedAt", report.submittedAt() == null ? "" : report.submittedAt().toString());
            document.put("status", report.status());
            document.put("revision", report.revision());
            document.put("submittedAt", report.submittedAt() == null ? "" : report.submittedAt().toString());
            document.put("submittedBy", report.submittedBy());
            document.set("lines", loadDailyLines(report.id(), report.revision()));
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("batch", batchJson(batch));
        result.set("documents", documents);
        result.put("deferredLineCount", deferredLineCount(batch));
        return result;
    }

    private ObjectNode decideDaily(BatchRow batch, JsonNode payload, SessionUser user) {
        JsonNode decisions = payload == null ? null : payload.path("decisions");
        if (decisions == null || !decisions.isArray() || decisions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择需要审核的耗材行");
        }
        DailyReportRow report = loadDailyReport(batch);
        if (report == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "科室日报不存在");
        if (!Set.of("SUBMITTED", "PARTIALLY_APPROVED").contains(report.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "科室日报当前状态不可审核");
        }
        Set<String> seen = new HashSet<>();
        ArrayNode errors = objectMapper.createArrayNode();
        ArrayNode deferred = objectMapper.createArrayNode();
        int approved = 0;
        int rejected = 0;
        int deferredCount = 0;
        for (JsonNode decisionNode : decisions) {
            String lineId = text(decisionNode, "lineId");
            String decision = text(decisionNode, "decision").toUpperCase(Locale.ROOT);
            String note = text(decisionNode, "note");
            if (lineId.isBlank() || !DECISIONS.contains(decision)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审核决策格式不正确");
            }
            if (!seen.add(lineId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同一耗材行不能重复审核");
            }
            DailyReviewLine line = lockDailyLine(lineId, batch);
            if (decision.equals(line.reviewStatus())) continue;
            if ("APPROVED".equals(line.reviewStatus())) {
                errors.add(error(lineId, "该行已审核通过，不能重复处理"));
                continue;
            }
            if ("REJECTED".equals(decision)) {
                rejectDaily(line, batch, report, note, user);
                rejected++;
                continue;
            }
            try {
                approveDaily(line, batch, report, note, user);
                approved++;
            } catch (StockShortageException shortage) {
                approveDailyWithoutDeduction(line, batch, report, note, shortage.getMessage(), deferred, user);
                deferredCount++;
            }
        }
        refreshDailyReview(batch, report, user);
        repository.log(user.name(), "审核科室日报", "inventory_consumption_approval_batch",
            batch.departmentName() + " " + batch.businessDate(),
            "通过 " + approved + " 行，驳回 " + rejected + " 行，待补扣 " + deferredCount + " 行"
                + (errors.isEmpty() ? "" : "，异常 " + errors.size() + " 行"));
        ObjectNode result = batch(batch.id(), user);
        result.set("errors", errors);
        result.set("deferred", deferred);
        result.put("deferredLineCount", deferredLineCount(batch));
        return result;
    }

    private void approveDaily(DailyReviewLine line, BatchRow batch, DailyReportRow report, String note, SessionUser user) {
        if (line.actualQuantity() == null || line.actualQuantity().signum() <= 0) {
            jdbcTemplate.update(
                "UPDATE inventory_department_daily_report_lines SET review_status = 'APPROVED', review_note = ? WHERE id = ?",
                blankToNull(note), line.lineId()
            );
            insertDecision(batch, new ReviewLine(line.lineId(), report.id(), line.itemId(), BigDecimal.ZERO,
                "APPROVED", report.revision(), report.status(), "", "", report.id(), "outpatient",
                "DAILY_REPORT", "DAILY_REPORT"), "APPROVED", note, user, null);
            return;
        }
        String eventId = deductDaily(line, batch, report, user);
        jdbcTemplate.update(
            """
            UPDATE inventory_department_daily_report_lines
            SET review_status = 'APPROVED', consumption_event_id = ?, review_note = ?
            WHERE id = ?
            """,
            eventId, blankToNull(note), line.lineId()
        );
        insertDecision(batch, new ReviewLine(line.lineId(), report.id(), line.itemId(), line.actualQuantity(),
            "APPROVED", report.revision(), report.status(), "", "", report.id(), "outpatient",
            "DAILY_REPORT", "DAILY_REPORT"), "APPROVED", note, user, eventId);
    }

    private void approveDailyWithoutDeduction(
        DailyReviewLine line, BatchRow batch, DailyReportRow report, String note, String reason,
        ArrayNode deferred, SessionUser user
    ) {
        String deferredNote = appendNote(note, "待补扣：" + reason);
        jdbcTemplate.update(
            "UPDATE inventory_department_daily_report_lines SET review_status = 'APPROVED', review_note = ? WHERE id = ?",
            deferredNote, line.lineId()
        );
        insertDecision(batch, new ReviewLine(line.lineId(), report.id(), line.itemId(), line.actualQuantity(),
            "APPROVED", report.revision(), report.status(), "", "", report.id(), "outpatient",
            "DAILY_REPORT", "DAILY_REPORT"), "APPROVED", deferredNote, user, null);
        createDeductionTask(batch, "DAILY_REPORT", report.id(), line.lineId(), line.itemId(), reason, user);
        deferred.add(deferredLine(line.lineId(), line.itemId(), reason));
    }

    private String deductDaily(DailyReviewLine line, BatchRow batch, DailyReportRow report, SessionUser user) {
        ItemRow item = requireItem(line.itemId());
        String idempotencyKey = "daily-line:" + line.lineId() + ":" + report.revision() + ":CONSUMPTION";
        List<String> existing = jdbcTemplate.query(
            "SELECT id FROM inventory_consumption_events WHERE idempotency_key = ? LIMIT 1",
            (resultSet, rowNum) -> resultSet.getString(1),
            idempotencyKey
        );
        if (!existing.isEmpty()) return existing.get(0);
        if (!ledger.isOpeningConfirmed(batch.departmentId())) {
            // 科室期初为零时，只要已有发放到科室的可用库存，就允许正常扣减
            boolean hasStock = ledger.lockAvailableBalances(ledger.departmentLocation(batch.departmentId()), line.itemId()).stream()
                .map(Balance::available).reduce(BigDecimal.ZERO, BigDecimal::add).signum() > 0;
            if (!hasStock) {
                throw new StockShortageException("科室期初库存尚未盘点确认，暂不能扣减");
            }
        }
        String locationId = ledger.departmentLocation(batch.departmentId());
        List<Balance> balances = ledger.lockAvailableBalances(locationId, line.itemId());
        BigDecimal available = balances.stream().map(Balance::available).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (available.compareTo(line.actualQuantity()) < 0) {
            throw new StockShortageException(item.name() + " 库存不足，当前可用 " + available.stripTrailingZeros().toPlainString());
        }
        List<Allocation> allocations = new ArrayList<>();
        BigDecimal remaining = line.actualQuantity();
        for (Balance balance : balances) {
            if (remaining.signum() <= 0) break;
            BigDecimal quantity = remaining.min(balance.available());
            if (quantity.signum() > 0) {
                allocations.add(new Allocation(balance, quantity));
                remaining = remaining.subtract(quantity);
            }
        }
        String eventId = "daily-consumption-" + UUID.randomUUID();
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("sourceType", "DAILY_REPORT");
        raw.put("sourceDocumentId", report.id());
        raw.put("sourceLineId", line.lineId());
        raw.put("itemId", line.itemId());
        raw.put("quantity", line.actualQuantity());
        jdbcTemplate.update(
            """
            INSERT INTO inventory_consumption_events
              (id, command_id, source_document_id, source_line_id, correction_document_id, service_instance_id,
               patient_id, idempotency_key, encounter_id, case_token, route, care_type, care_encounter_id,
               department, department_id, visit_date, package_id, package_version, status, event_kind,
               trigger_stage, completion_version, operator_name, created_at, raw_json)
            VALUES (?, NULL, ?, ?, NULL, NULL, NULL, ?, ?, NULL, 'DAILY_REPORT', 'outpatient', NULL,
                    ?, ?, ?, NULL, NULL, 'succeeded', 'CONSUMPTION', 'DAILY_REPORT', NULL, ?, ?, CAST(? AS JSON))
            """,
            eventId, report.id(), line.lineId(), idempotencyKey, report.id(),
            batch.departmentName(), batch.departmentId(), batch.businessDate().toString(),
            user.name(), LocalDateTime.now().toString(), json(raw)
        );
        for (Allocation allocation : allocations) {
            ledger.changeBalance(allocation.balance().id(), allocation.quantity().negate(), BigDecimal.ZERO);
            jdbcTemplate.update(
                """
                INSERT INTO inventory_consumption_details
                  (id, event_id, item_id, batch_id, location_id, quantity, detail_kind, raw_json)
                VALUES (?, ?, ?, ?, ?, ?, 'CONSUMPTION', JSON_OBJECT('source', 'DAILY_REPORT'))
                """,
                "daily-consumption-detail-" + UUID.randomUUID(), eventId, line.itemId(),
                allocation.balance().batchId(), locationId, allocation.quantity()
            );
            ledger.movement(
                "CONSUMPTION", line.itemId(), allocation.balance().batchId(), locationId, null,
                allocation.quantity(), batch.departmentId(), "DAILY_REPORT_LINE", line.lineId(), eventId,
                null, user.name(), "科室日报审核通过"
            );
        }
        return eventId;
    }

    private void rejectDaily(DailyReviewLine line, BatchRow batch, DailyReportRow report, String note, SessionUser user) {
        jdbcTemplate.update(
            "UPDATE inventory_department_daily_report_lines SET review_status = 'REJECTED', review_note = ? WHERE id = ?",
            blankToNull(note), line.lineId()
        );
        insertDecision(batch, new ReviewLine(line.lineId(), report.id(), line.itemId(), line.actualQuantity(),
            "REJECTED", report.revision(), report.status(), "", "", report.id(), "outpatient",
            "DAILY_REPORT", "DAILY_REPORT"), "REJECTED", note, user, null);
    }

    private void refreshDailyReview(BatchRow batch, DailyReportRow report, SessionUser user) {
        ObjectNode counts = jdbcTemplate.query(
            """
            SELECT COUNT(*) total,
                   COALESCE(SUM(CASE WHEN review_status = 'APPROVED' THEN 1 ELSE 0 END), 0) approved,
                   COALESCE(SUM(CASE WHEN review_status = 'REJECTED' THEN 1 ELSE 0 END), 0) rejected,
                   COALESCE(SUM(CASE WHEN review_status = 'PENDING' THEN 1 ELSE 0 END), 0) pending
            FROM inventory_department_daily_report_lines WHERE report_id = ?
            """,
            resultSet -> {
                ObjectNode row = objectMapper.createObjectNode();
                if (resultSet.next()) {
                    row.put("total", resultSet.getInt("total"));
                    row.put("approved", resultSet.getInt("approved"));
                    row.put("rejected", resultSet.getInt("rejected"));
                    row.put("pending", resultSet.getInt("pending"));
                }
                return row;
            },
            report.id()
        );
        int total = counts.path("total").asInt();
        int approved = counts.path("approved").asInt();
        int rejected = counts.path("rejected").asInt();
        int pending = counts.path("pending").asInt();
        String batchStatus;
        String reportStatus;
        if (pending > 0 && approved + rejected > 0) {
            batchStatus = "PARTIALLY_APPROVED";
            reportStatus = "PARTIALLY_APPROVED";
        } else if (pending > 0) {
            batchStatus = "PENDING";
            reportStatus = "SUBMITTED";
        } else if (rejected > 0 && approved > 0) {
            batchStatus = "PARTIALLY_APPROVED";
            reportStatus = "PARTIALLY_APPROVED";
        } else if (rejected > 0) {
            batchStatus = "RETURNED";
            reportStatus = "RETURNED";
        } else {
            batchStatus = "APPROVED";
            reportStatus = "APPROVED";
        }
        jdbcTemplate.update(
            "UPDATE inventory_department_daily_reports SET status = ?, reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP(3) WHERE id = ?",
            reportStatus, user.name(), report.id()
        );
        jdbcTemplate.update(
            """
            UPDATE inventory_consumption_approval_batches
            SET status = ?, line_count = ?, approved_line_count = ?, rejected_line_count = ?,
                reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP(3)
            WHERE id = ?
            """,
            batchStatus, total, approved, rejected, user.name(), batch.id()
        );
        if ("APPROVED".equals(reportStatus)) {
            jdbcTemplate.update(
                "UPDATE inventory_department_day_closures SET status = 'APPROVED' WHERE department_id = ? AND business_date = ?",
                report.departmentId(), report.businessDate()
            );
        }
    }

    private DailyReviewLine lockDailyLine(String lineId, BatchRow batch) {
        List<DailyReviewLine> rows = jdbcTemplate.query(
            """
            SELECT l.id, l.report_id, l.item_id, l.actual_quantity, l.review_status,
                   r.status report_status, r.revision report_revision
            FROM inventory_department_daily_report_lines l
            JOIN inventory_department_daily_reports r ON r.id = l.report_id
            WHERE l.id = ? AND r.department_id = ? AND r.business_date = ?
            FOR UPDATE
            """,
            (resultSet, rowNum) -> new DailyReviewLine(
                resultSet.getString("id"), resultSet.getString("report_id"), resultSet.getString("item_id"),
                resultSet.getBigDecimal("actual_quantity"), resultSet.getString("review_status"),
                resultSet.getString("report_status"), resultSet.getInt("report_revision")
            ),
            lineId, batch.departmentId(), batch.businessDate()
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "耗材行不存在或不属于该科室日报");
        return rows.get(0);
    }

    private DailyReportRow loadDailyReport(BatchRow batch) {
        return jdbcTemplate.query(
            "SELECT * FROM inventory_department_daily_reports WHERE department_id = ? AND business_date = ?",
            resultSet -> {
                if (!resultSet.next()) return null;
                Timestamp submittedAt = resultSet.getTimestamp("submitted_at");
                return new DailyReportRow(
                    resultSet.getString("id"), resultSet.getString("department_id"),
                    resultSet.getString("department_key"), resultSet.getString("department_name"),
                    resultSet.getDate("business_date").toLocalDate(), resultSet.getString("status"),
                    resultSet.getInt("revision"), resultSet.getString("submitted_by"),
                    submittedAt == null ? null : submittedAt.toLocalDateTime()
                );
            },
            batch.departmentId(), batch.businessDate()
        );
    }

    private ArrayNode loadDailyLines(String reportId, int reportRevision) {
        return jdbcTemplate.query(
            """
            SELECT l.*, i.name item_name, i.unit item_unit
            FROM inventory_department_daily_report_lines l
            LEFT JOIN inventory_items i ON i.id = l.item_id
            WHERE l.report_id = ?
            ORDER BY l.line_no
            """,
            resultSet -> {
                ArrayNode rows = JsonNodeFactory.instance.arrayNode();
                while (resultSet.next()) {
                    ObjectNode row = rows.addObject();
                    row.put("lineId", resultSet.getString("id"));
                    row.put("itemId", resultSet.getString("item_id"));
                    row.put("itemName", firstNonBlank(resultSet.getString("item_name_snapshot"), resultSet.getString("item_name")));
                    row.put("unit", firstNonBlank(resultSet.getString("unit_snapshot"), resultSet.getString("item_unit")));
                    row.put("presetQuantity", resultSet.getBigDecimal("preset_quantity"));
                    row.put("actualQuantity", resultSet.getBigDecimal("actual_quantity"));
                    row.put("source", resultSet.getString("source"));
                    row.put("exceptionReason", resultSet.getString("exception_reason"));
                    row.put("reviewStatus", resultSet.getString("review_status"));
                    row.put("reviewNote", resultSet.getString("review_note"));
                    row.put("lineRevision", reportRevision);
                    row.put("approvedQuantity", "APPROVED".equals(resultSet.getString("review_status"))
                        ? resultSet.getBigDecimal("actual_quantity") : null);
                    row.put("consumptionEventId", resultSet.getString("consumption_event_id"));
                }
                return rows;
            },
            reportId
        );
    }

    private void approve(ReviewLine line, BatchRow batch, String note, SessionUser user) {
        if (!Set.of("SUBMITTED", "PARTIALLY_APPROVED").contains(line.documentStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "患者清单当前状态不可审核");
        }
        if (line.actualQuantity().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "实际用量必须大于零");
        }
        String eventId = deductPatient(line, batch, user);
        jdbcTemplate.update(
            """
            UPDATE inventory_patient_consumption_lines
            SET review_status = 'APPROVED', approved_quantity = actual_quantity,
                consumption_event_id = ?, review_note = ?
            WHERE id = ?
            """,
            eventId, blankToNull(note), line.lineId()
        );
        insertDecision(batch, line, "APPROVED", note, user, eventId);
        refreshDocument(line.documentId());
    }

    private void approveWithoutDeduction(
        ReviewLine line, BatchRow batch, String note, String reason, ArrayNode deferred, SessionUser user
    ) {
        String deferredNote = appendNote(note, "待补扣：" + reason);
        jdbcTemplate.update(
            """
            UPDATE inventory_patient_consumption_lines
            SET review_status = 'APPROVED', approved_quantity = actual_quantity, review_note = ?
            WHERE id = ?
            """,
            deferredNote, line.lineId()
        );
        insertDecision(batch, line, "APPROVED", deferredNote, user, null);
        createDeductionTask(batch, "PATIENT_DOCUMENT", line.documentId(), line.lineId(), line.itemId(), reason, user);
        deferred.add(deferredLine(line.lineId(), line.itemId(), reason));
        refreshDocument(line.documentId());
    }

    private String deductPatient(ReviewLine line, BatchRow batch, SessionUser user) {
        ItemRow item = requireItem(line.itemId());
        String idempotencyKey = "patient-line:" + line.lineId() + ":" + line.lineRevision() + ":CONSUMPTION";
        List<String> existing = jdbcTemplate.query(
            "SELECT id FROM inventory_consumption_events WHERE idempotency_key = ? LIMIT 1",
            (resultSet, rowNum) -> resultSet.getString(1),
            idempotencyKey
        );
        if (!existing.isEmpty()) return existing.get(0);
        if (!ledger.isOpeningConfirmed(batch.departmentId())) {
            // 科室期初为零时，只要已有发放到科室的可用库存，就允许正常扣减
            boolean hasStock = ledger.lockAvailableBalances(ledger.departmentLocation(batch.departmentId()), line.itemId()).stream()
                .map(Balance::available).reduce(BigDecimal.ZERO, BigDecimal::add).signum() > 0;
            if (!hasStock) {
                throw new StockShortageException("科室期初库存尚未盘点确认，暂不能扣减");
            }
        }
        String locationId = ledger.departmentLocation(batch.departmentId());
        List<Balance> balances = ledger.lockAvailableBalances(locationId, line.itemId());
        BigDecimal available = balances.stream().map(Balance::available).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (available.compareTo(line.actualQuantity()) < 0) {
            throw new StockShortageException(item.name() + " 库存不足，当前可用 " + available.stripTrailingZeros().toPlainString());
        }
        List<Allocation> allocations = new ArrayList<>();
        BigDecimal remaining = line.actualQuantity();
        for (Balance balance : balances) {
            if (remaining.signum() <= 0) break;
            BigDecimal quantity = remaining.min(balance.available());
            if (quantity.signum() > 0) {
                allocations.add(new Allocation(balance, quantity));
                remaining = remaining.subtract(quantity);
            }
        }
        String eventId = "patient-consumption-" + UUID.randomUUID();
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("sourceDocumentId", line.documentId());
        raw.put("sourceLineId", line.lineId());
        raw.put("serviceInstanceId", line.serviceInstanceId());
        raw.put("patientId", line.patientId());
        raw.put("itemId", line.itemId());
        raw.put("quantity", line.actualQuantity());
        jdbcTemplate.update(
            """
            INSERT INTO inventory_consumption_events
              (id, command_id, source_document_id, source_line_id, correction_document_id, service_instance_id,
               patient_id, idempotency_key, encounter_id, case_token, route, care_type, care_encounter_id,
               department, department_id, visit_date, package_id, package_version, status, event_kind,
               trigger_stage, completion_version, operator_name, created_at, raw_json)
            VALUES (?, NULL, ?, ?, NULL, ?, ?, ?, ?, NULL, ?, ?, NULL, ?, ?, ?, NULL, NULL,
                    'succeeded', 'CONSUMPTION', ?, NULL, ?, ?, CAST(? AS JSON))
            """,
            eventId, line.documentId(), line.lineId(), line.serviceInstanceId(), line.patientId(), idempotencyKey,
            line.encounterId(), line.careType(), line.careType(), batch.departmentName(), batch.departmentId(),
            batch.businessDate().toString(), firstNonBlank(line.stageCode(), line.serviceCode()),
            user.name(), LocalDateTime.now().toString(), json(raw)
        );
        for (Allocation allocation : allocations) {
            ledger.changeBalance(allocation.balance().id(), allocation.quantity().negate(), BigDecimal.ZERO);
            jdbcTemplate.update(
                """
                INSERT INTO inventory_consumption_details
                  (id, event_id, item_id, batch_id, location_id, quantity, detail_kind, raw_json)
                VALUES (?, ?, ?, ?, ?, ?, 'CONSUMPTION', JSON_OBJECT('source', 'PATIENT_DOCUMENT'))
                """,
                "patient-consumption-detail-" + UUID.randomUUID(), eventId, line.itemId(),
                allocation.balance().batchId(), locationId, allocation.quantity()
            );
            ledger.movement(
                "CONSUMPTION", line.itemId(), allocation.balance().batchId(), locationId, null,
                allocation.quantity(), batch.departmentId(), "PATIENT_DOC_LINE", line.lineId(), eventId,
                null, user.name(), "患者耗材审核通过"
            );
        }
        return eventId;
    }

    private void reject(ReviewLine line, BatchRow batch, String note, SessionUser user) {
        if (!Set.of("SUBMITTED", "PARTIALLY_APPROVED").contains(line.documentStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "患者清单当前状态不可审核");
        }
        jdbcTemplate.update(
            "UPDATE inventory_patient_consumption_lines SET review_status = 'REJECTED', review_note = ? WHERE id = ?",
            blankToNull(note), line.lineId()
        );
        insertDecision(batch, line, "REJECTED", note, user, null);
        refreshDocument(line.documentId());
    }

    private void insertDecision(BatchRow batch, ReviewLine line, String decision, String note, SessionUser user, String eventId) {
        jdbcTemplate.update(
            """
            INSERT INTO inventory_consumption_approval_decisions
              (id, batch_id, document_id, line_id, line_revision, decision, decision_note,
               reviewer_username, reviewer_name, consumption_event_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE id = id
            """,
            "approval-decision-" + UUID.randomUUID(), batch.id(), line.documentId(), line.lineId(),
            line.lineRevision(), decision, blankToNull(note), user.username(), user.name(), blankToNull(eventId)
        );
    }

    private void refreshDocument(String documentId) {
        ObjectNode counts = jdbcTemplate.query(
            """
            SELECT COUNT(*) total,
                   COALESCE(SUM(CASE WHEN review_status = 'APPROVED' THEN 1 ELSE 0 END), 0) approved,
                   COALESCE(SUM(CASE WHEN review_status = 'REJECTED' THEN 1 ELSE 0 END), 0) rejected,
                   COALESCE(SUM(CASE WHEN review_status = 'PENDING' THEN 1 ELSE 0 END), 0) pending
            FROM inventory_patient_consumption_lines WHERE document_id = ?
            """,
            resultSet -> {
                ObjectNode row = objectMapper.createObjectNode();
                if (resultSet.next()) {
                    row.put("total", resultSet.getInt("total"));
                    row.put("approved", resultSet.getInt("approved"));
                    row.put("rejected", resultSet.getInt("rejected"));
                    row.put("pending", resultSet.getInt("pending"));
                }
                return row;
            },
            documentId
        );
        int total = counts.path("total").asInt();
        int approved = counts.path("approved").asInt();
        int rejected = counts.path("rejected").asInt();
        int pending = counts.path("pending").asInt();
        String status;
        if (pending > 0 && approved + rejected > 0) status = "PARTIALLY_APPROVED";
        else if (pending > 0) status = "SUBMITTED";
        else if (rejected > 0 && approved > 0) status = "PARTIALLY_APPROVED";
        else if (rejected > 0) status = "RETURNED";
        else if (total == 0 || approved == total) status = "APPROVED";
        else status = "SUBMITTED";
        jdbcTemplate.update(
            "UPDATE inventory_patient_consumption_documents SET status = ? WHERE id = ?",
            status, documentId
        );
        jdbcTemplate.update(
            """
            UPDATE inventory_service_instances si
            JOIN inventory_patient_consumption_documents d ON d.service_instance_id = si.id
            SET si.status = ?
            WHERE d.id = ?
            """,
            status, documentId
        );
    }

    private ReviewLine lockLine(String lineId, BatchRow batch) {
        List<ReviewLine> rows = jdbcTemplate.query(
            """
            SELECT l.*, d.status document_status, d.department_id document_department_id,
                   d.department_key document_department_key, d.department_name document_department_name,
                   d.business_date document_business_date, d.patient_id document_patient_id,
                   d.encounter_id document_encounter_id, d.service_instance_id document_service_instance_id,
                   si.care_type, si.stage_code, si.service_code
            FROM inventory_patient_consumption_lines l
            JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
            JOIN inventory_service_instances si ON si.id = d.service_instance_id
            WHERE l.id = ? AND d.department_id = ? AND d.business_date = ?
            FOR UPDATE
            """,
            (resultSet, rowNum) -> new ReviewLine(
                resultSet.getString("id"), resultSet.getString("document_id"), resultSet.getString("item_id"),
                resultSet.getBigDecimal("actual_quantity"), resultSet.getString("review_status"),
                resultSet.getInt("line_revision"), resultSet.getString("document_status"),
                resultSet.getString("document_service_instance_id"), resultSet.getString("document_patient_id"),
                resultSet.getString("document_encounter_id"), resultSet.getString("care_type"),
                resultSet.getString("stage_code"), resultSet.getString("service_code")
            ),
            lineId, batch.departmentId(), batch.businessDate()
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "耗材行不存在或不属于该审核批次");
        return rows.get(0);
    }

    private BatchRow requireBatch(String batchId, SessionUser user) {
        BatchRow batch = loadBatch(batchId);
        if (!inventoryAccess.canViewAllDepartments(user) && !sameDepartment(user.department(), batch.departmentName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权审核其他科室的耗材批次");
        }
        return batch;
    }

    private BatchRow requireBatchForUpdate(String batchId, SessionUser user) {
        BatchRow batch = requireBatch(batchId, user);
        List<BatchRow> rows = jdbcTemplate.query(
            "SELECT * FROM inventory_consumption_approval_batches WHERE id = ? FOR UPDATE",
            (resultSet, rowNum) -> mapBatch(resultSet),
            batchId
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "审核批次不存在");
        return rows.get(0);
    }

    private BatchRow loadBatch(String batchId) {
        return jdbcTemplate.query(
            "SELECT * FROM inventory_consumption_approval_batches WHERE id = ?",
            resultSet -> resultSet.next() ? mapBatch(resultSet) : null,
            batchId
        );
    }

    private BatchRow mapBatch(ResultSet resultSet) throws SQLException {
        Timestamp reviewedAt = resultSet.getTimestamp("reviewed_at");
        return new BatchRow(
            resultSet.getString("id"), resultSet.getString("department_id"), resultSet.getString("department_key"),
            resultSet.getString("department_name"), resultSet.getString("batch_type"),
            resultSet.getDate("business_date").toLocalDate(),
            resultSet.getString("status"), resultSet.getInt("document_count"), resultSet.getInt("line_count"),
            resultSet.getInt("approved_line_count"), resultSet.getInt("rejected_line_count"),
            resultSet.getString("reviewed_by"), reviewedAt == null ? null : reviewedAt.toLocalDateTime()
        );
    }

    private ObjectNode batchRow(ResultSet resultSet) throws SQLException {
        Timestamp reviewedAt = resultSet.getTimestamp("reviewed_at");
        ObjectNode row = objectMapper.createObjectNode();
        row.put("batchId", resultSet.getString("id"));
        row.put("departmentId", resultSet.getString("department_id"));
        row.put("departmentKey", resultSet.getString("department_key"));
        row.put("departmentName", resultSet.getString("department_name"));
        row.put("batchType", resultSet.getString("batch_type"));
        row.put("businessDate", resultSet.getDate("business_date").toLocalDate().toString());
        row.put("status", resultSet.getString("status"));
        row.put("documentCount", resultSet.getInt("document_count"));
        row.put("lineCount", resultSet.getInt("line_count"));
        row.put("approvedLineCount", resultSet.getInt("approved_line_count"));
        row.put("rejectedLineCount", resultSet.getInt("rejected_line_count"));
        row.put("reviewedBy", resultSet.getString("reviewed_by"));
        row.put("reviewedAt", reviewedAt == null ? "" : reviewedAt.toLocalDateTime().toString());
        return row;
    }

    private ObjectNode batchJson(BatchRow batch) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("batchId", batch.id());
        row.put("departmentId", batch.departmentId());
        row.put("departmentKey", batch.departmentKey());
        row.put("departmentName", batch.departmentName());
        row.put("batchType", batch.batchType());
        row.put("businessDate", batch.businessDate().toString());
        row.put("status", batch.status());
        row.put("documentCount", batch.documentCount());
        row.put("lineCount", batch.lineCount());
        row.put("approvedLineCount", batch.approvedLineCount());
        row.put("rejectedLineCount", batch.rejectedLineCount());
        row.put("reviewedBy", batch.reviewedBy());
        row.put("reviewedAt", batch.reviewedAt() == null ? "" : batch.reviewedAt().toString());
        return row;
    }

    private ArrayNode loadLines(String documentId) {
        return jdbcTemplate.query(
            """
            SELECT l.*, i.name item_name, i.unit item_unit
            FROM inventory_patient_consumption_lines l
            JOIN inventory_items i ON i.id = l.item_id
            WHERE l.document_id = ?
            ORDER BY l.line_no
            """,
            resultSet -> {
                ArrayNode rows = JsonNodeFactory.instance.arrayNode();
                while (resultSet.next()) {
                    ObjectNode row = rows.addObject();
                    row.put("lineId", resultSet.getString("id"));
                    row.put("itemId", resultSet.getString("item_id"));
                    row.put("itemName", firstNonBlank(resultSet.getString("item_name"), resultSet.getString("item_name_snapshot")));
                    row.put("unit", firstNonBlank(resultSet.getString("item_unit"), resultSet.getString("unit_snapshot")));
                    row.put("presetQuantity", resultSet.getBigDecimal("preset_quantity"));
                    row.put("actualQuantity", resultSet.getBigDecimal("actual_quantity"));
                    row.put("source", resultSet.getString("source"));
                    row.put("exceptionReason", resultSet.getString("exception_reason"));
                    row.put("reviewStatus", resultSet.getString("review_status"));
                    row.put("reviewNote", resultSet.getString("review_note"));
                    row.put("lineRevision", resultSet.getInt("line_revision"));
                    row.put("approvedQuantity", resultSet.getBigDecimal("approved_quantity"));
                    row.put("consumptionEventId", resultSet.getString("consumption_event_id"));
                }
                return rows;
            },
            documentId
        );
    }

    private ItemRow requireItem(String itemId) {
        List<ItemRow> rows = jdbcTemplate.query(
            "SELECT id, name, unit FROM inventory_items WHERE id = ? AND enabled = 1",
            (resultSet, rowNum) -> new ItemRow(
                resultSet.getString("id"), resultSet.getString("name"), resultSet.getString("unit")
            ),
            itemId
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "耗材不存在或已停用");
        return rows.get(0);
    }

    private ObjectNode error(String lineId, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("lineId", lineId);
        error.put("message", message);
        return error;
    }

    private ObjectNode deferredLine(String lineId, String itemId, String message) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("lineId", lineId);
        row.put("itemId", itemId);
        row.put("message", message);
        try {
            row.put("itemName", requireItem(itemId).name());
        } catch (RuntimeException ignored) {
            row.put("itemName", itemId);
        }
        return row;
    }

    private void createDeductionTask(
        BatchRow batch, String sourceType, String sourceId, String lineId, String itemId,
        String reason, SessionUser user
    ) {
        String itemName;
        try {
            itemName = requireItem(itemId).name();
        } catch (RuntimeException ignored) {
            itemName = itemId;
        }
        String message = itemName + " 已通过，库存待补扣：" + reason;
        jdbcTemplate.update(
            """
            INSERT INTO inventory_exception_tasks
              (id, command_id, exception_type, severity, status, department_id, department_name_snapshot,
               encounter_id, trigger_stage, item_id, message, source_type, source_id, line_id)
            VALUES (?, NULL, 'REVIEW_DEDUCTION_PENDING', 'HIGH', 'OPEN', ?, ?, NULL, 'REVIEW', ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE status = 'OPEN', message = VALUES(message), resolved_by = NULL,
              resolution_note = NULL, resolved_at = NULL, updated_at = CURRENT_TIMESTAMP(3)
            """,
            "inventory-exception-" + UUID.randomUUID(), batch.departmentId(), batch.departmentName(),
            itemId, message, sourceType, sourceId, lineId
        );
    }

    private void resolveDeductionTask(String lineId, SessionUser user) {
        jdbcTemplate.update(
            """
            UPDATE inventory_exception_tasks
            SET status = 'RESOLVED', resolved_by = ?, resolution_note = '库存补扣完成',
                resolved_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
            WHERE line_id = ? AND exception_type = 'REVIEW_DEDUCTION_PENDING' AND status <> 'RESOLVED'
            """,
            user.name(), lineId
        );
    }

    private int deferredLineCount(BatchRow batch) {
        Integer count;
        if ("DAILY".equals(batch.batchType())) {
            count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM inventory_department_daily_report_lines l
                JOIN inventory_department_daily_reports r ON r.id = l.report_id
                WHERE r.department_id = ? AND r.business_date = ?
                  AND l.review_status = 'APPROVED' AND l.consumption_event_id IS NULL
                  AND l.actual_quantity > 0
                """,
                Integer.class, batch.departmentId(), batch.businessDate()
            );
        } else {
            count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM inventory_patient_consumption_lines l
                JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
                WHERE d.department_id = ? AND d.business_date = ?
                  AND l.review_status = 'APPROVED' AND l.consumption_event_id IS NULL
                  AND l.actual_quantity > 0
                """,
                Integer.class, batch.departmentId(), batch.businessDate()
            );
        }
        return count == null ? 0 : count;
    }

    @Transactional
    public ObjectNode retryDeductions(String batchId, SessionUser user) {
        BatchRow batch = requireBatchForUpdate(batchId, user);
        ArrayNode errors = objectMapper.createArrayNode();
        int deducted = 0;
        if ("DAILY".equals(batch.batchType())) {
            DailyReportRow report = loadDailyReport(batch);
            if (report == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "科室日报不存在");
            List<String> lineIds = jdbcTemplate.query(
                """
                SELECT id FROM inventory_department_daily_report_lines
                WHERE report_id = ? AND review_status = 'APPROVED'
                  AND consumption_event_id IS NULL AND actual_quantity > 0
                ORDER BY line_no FOR UPDATE
                """,
                (resultSet, rowNum) -> resultSet.getString(1),
                report.id()
            );
            for (String lineId : lineIds) {
                DailyReviewLine line = lockDailyLine(lineId, batch);
                try {
                    String eventId = deductDaily(line, batch, report, user);
                    jdbcTemplate.update(
                        """
                        UPDATE inventory_department_daily_report_lines
                        SET consumption_event_id = ?, review_note = CASE
                            WHEN review_note LIKE '%待补扣%' THEN '补扣完成' ELSE review_note END
                        WHERE id = ?
                        """,
                        eventId, lineId
                    );
                    resolveDeductionTask(lineId, user);
                    deducted++;
                } catch (StockShortageException shortage) {
                    errors.add(error(lineId, shortage.getMessage()));
                }
            }
            refreshDailyReview(batch, report, user);
        } else {
            List<String> lineIds = jdbcTemplate.query(
                """
                SELECT l.id
                FROM inventory_patient_consumption_lines l
                JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
                WHERE d.department_id = ? AND d.business_date = ?
                  AND l.review_status = 'APPROVED' AND l.consumption_event_id IS NULL
                  AND l.actual_quantity > 0
                ORDER BY d.id, l.line_no FOR UPDATE
                """,
                (resultSet, rowNum) -> resultSet.getString(1),
                batch.departmentId(), batch.businessDate()
            );
            Set<String> documents = new HashSet<>();
            for (String lineId : lineIds) {
                ReviewLine line = lockLine(lineId, batch);
                try {
                    String eventId = deductPatient(line, batch, user);
                    jdbcTemplate.update(
                        """
                        UPDATE inventory_patient_consumption_lines
                        SET consumption_event_id = ?, review_note = CASE
                            WHEN review_note LIKE '%待补扣%' THEN '补扣完成' ELSE review_note END
                        WHERE id = ?
                        """,
                        eventId, lineId
                    );
                    resolveDeductionTask(lineId, user);
                    documents.add(line.documentId());
                    deducted++;
                } catch (StockShortageException shortage) {
                    errors.add(error(lineId, shortage.getMessage()));
                }
            }
            for (String documentId : documents) refreshDocument(documentId);
            consumptionService.refreshBatch(batchId);
        }
        repository.log(user.name(), "库存补扣", "inventory_consumption_approval_batch",
            batch.departmentName() + " " + batch.businessDate(),
            "补扣 " + deducted + " 行" + (errors.isEmpty() ? "" : "，待处理 " + errors.size() + " 行"));
        ObjectNode result = batch(batchId, user);
        result.set("errors", errors);
        result.put("deducted", deducted);
        return result;
    }

    private static String appendNote(String note, String extra) {
        String base = note == null ? "" : note.trim();
        return base.isBlank() ? extra : base + "；" + extra;
    }

    private static boolean sameDepartment(String userDepartment, String canonicalName) {
        if (userDepartment == null) return false;
        String value = userDepartment.trim();
        return value.equals(canonicalName)
            || (value.equals("后勤保洁") && canonicalName.equals("后勤"))
            || (value.equals("后勤") && canonicalName.equals("后勤保洁"));
    }

    private static String canonicalDepartmentName(String value) {
        if (value == null) return "";
        return "后勤保洁".equals(value.trim()) ? "后勤" : value.trim();
    }

    private static String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalArgumentException("审核事件序列化失败", error);
        }
    }

    private record BatchRow(
        String id, String departmentId, String departmentKey, String departmentName, String batchType,
        LocalDate businessDate, String status, int documentCount, int lineCount, int approvedLineCount,
        int rejectedLineCount, String reviewedBy, LocalDateTime reviewedAt
    ) {}

    private record DailyReportRow(
        String id, String departmentId, String departmentKey, String departmentName, LocalDate businessDate,
        String status, int revision, String submittedBy, LocalDateTime submittedAt
    ) {}

    private record DailyReviewLine(
        String lineId, String reportId, String itemId, BigDecimal actualQuantity, String reviewStatus,
        String reportStatus, int reportRevision
    ) {}

    private record ReviewLine(
        String lineId, String documentId, String itemId, BigDecimal actualQuantity, String reviewStatus,
        int lineRevision, String documentStatus, String serviceInstanceId, String patientId, String encounterId,
        String careType, String stageCode, String serviceCode
    ) {}

    private record ItemRow(String id, String name, String unit) {}

    private record Allocation(Balance balance, BigDecimal quantity) {}

    private static class StockShortageException extends RuntimeException {
        StockShortageException(String message) {
            super(message);
        }
    }
}
