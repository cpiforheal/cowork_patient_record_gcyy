package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository;
import com.coshare.patientrecord.inventory.service.workflow.InventoryRequestWorkflow;
import com.coshare.patientrecord.inventory.service.workflow.InventoryStockWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class InventoryDatabaseService {

    private final InventoryRepository repository;
    private final InventoryRequestWorkflow requestWorkflow;
    private final InventoryStockWorkflow stockWorkflow;
    private final InventoryPackageService packageService;
    private final InventoryLedgerRepository ledgerRepository;
    private final InventoryLedgerService ledgerService;
    private final InventoryStageConsumptionService consumptionService;
    private final InventoryDepartmentReportService reportService;

    public InventoryDatabaseService(
        InventoryRepository repository,
        InventoryRequestWorkflow requestWorkflow,
        InventoryStockWorkflow stockWorkflow,
        InventoryPackageService packageService,
        InventoryLedgerRepository ledgerRepository,
        InventoryLedgerService ledgerService,
        InventoryStageConsumptionService consumptionService,
        InventoryDepartmentReportService reportService
    ) {
        this.repository = repository;
        this.requestWorkflow = requestWorkflow;
        this.stockWorkflow = stockWorkflow;
        this.packageService = packageService;
        this.ledgerRepository = ledgerRepository;
        this.ledgerService = ledgerService;
        this.consumptionService = consumptionService;
        this.reportService = reportService;
    }

    public ObjectNode readDb() {
        ObjectNode db = repository.readDb();
        db.set("packages", packageService.readPackages());
        db.set("consumptionEvents", packageService.readConsumptionEvents());
        return db;
    }

    public ObjectNode readDbForUser(SessionUser user) {
        ObjectNode db = repository.readDbForUser(user);
        if ("admin".equals(user.role()) || "quality".equals(user.role()) || "manager".equals(user.role())) {
            db.set("packages", packageService.readPackages());
            db.set("consumptionEvents", packageService.readConsumptionEvents());
        } else {
            db.set("packages", filterByDepartment(packageService.readPackages(), user.department()));
            db.set("consumptionEvents", filterByDepartment(packageService.readConsumptionEvents(), user.department()));
        }
        return db;
    }

    public ObjectNode saveItem(JsonNode payload, SessionUser user) {
        return stockWorkflow.saveItem(payload, user);
    }

    public ObjectNode inbound(JsonNode payload, SessionUser user) {
        return stockWorkflow.inbound(payload, user);
    }

    public ObjectNode createRequest(JsonNode payload, SessionUser user) {
        return requestWorkflow.createRequest(payload, user);
    }

    public ObjectNode approveRequest(JsonNode payload, SessionUser user) {
        return requestWorkflow.approveRequest(payload, user);
    }

    public ObjectNode issueRequest(JsonNode payload, SessionUser user) {
        return requestWorkflow.issueRequest(payload, user);
    }

    public ObjectNode receiveRequest(JsonNode payload, SessionUser user) {
        return requestWorkflow.receiveRequest(payload, user);
    }

    public ObjectNode rejectRequest(JsonNode payload, SessionUser user) {
        return requestWorkflow.rejectRequest(payload, user);
    }

    public ObjectNode cancelRequest(JsonNode payload, SessionUser user) {
        return requestWorkflow.cancelRequest(payload, user);
    }

    public ObjectNode voidRequest(JsonNode payload, SessionUser user) {
        return requestWorkflow.voidRequest(payload, user);
    }

    public ObjectNode weeklyConsumption(JsonNode payload, SessionUser user) {
        return stockWorkflow.weeklyConsumption(payload, user);
    }

    public ObjectNode returnOrScrap(JsonNode payload, SessionUser user) {
        return stockWorkflow.returnOrScrap(payload, user);
    }

    public ObjectNode inventoryCount(JsonNode payload, SessionUser user) {
        return stockWorkflow.inventoryCount(payload, user);
    }

    public ObjectNode savePackage(JsonNode payload, SessionUser user) {
        packageService.saveDraft(payload, user);
        return readDbForUser(user);
    }

    public ObjectNode enablePackage(JsonNode payload, SessionUser user) {
        packageService.enable(repository.text(payload, "id"), user);
        return readDbForUser(user);
    }

    public ObjectNode disablePackage(JsonNode payload, SessionUser user) {
        packageService.disable(repository.text(payload, "id"), user);
        return readDbForUser(user);
    }

    public ObjectNode consumeEncounter(JsonNode payload, SessionUser user) {
        String departmentId = scopedDepartmentId(user, repository.text(payload, "departmentId"));
        String visitDate = repository.text(payload, "visitDate");
        consumptionService.enqueueStageCompletion(
            repository.text(payload, "encounterId"),
            repository.text(payload, "triggerStage", "REVIEW"),
            payload.path("completionVersion").asLong(1),
            departmentId,
            repository.text(payload, "caseToken"),
            repository.text(payload, "route", "outpatient"),
            visitDate.isBlank() ? LocalDate.now() : LocalDate.parse(visitDate),
            user.name()
        );
        return readDbForUser(user);
    }

    public ObjectNode retryConsumption(JsonNode payload, SessionUser user) {
        consumptionService.retry(repository.text(payload, "id"), user.name());
        return readDbForUser(user);
    }

    public ObjectNode workbench(SessionUser user, String requestedDepartmentId) {
        String departmentId = scopedDepartmentId(user, requestedDepartmentId);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("departmentId", departmentId);
        result.put("department", ledgerRepository.departmentName(departmentId));
        result.set("balances", ledgerRepository.readBalances(departmentId, null));
        result.set("exceptions", ledgerRepository.readExceptions(departmentId, "OPEN"));
        result.set("opening", ledgerRepository.openingState(departmentId));
        result.set("flow", ledgerRepository.queryJson(
            """
            SELECT status, COUNT(*) count FROM inventory_transfers
            WHERE department_id = ? GROUP BY status ORDER BY status
            """,
            departmentId
        ));
        return result;
    }

    public ArrayNode departmentBalances(SessionUser user, String requestedDepartmentId, String itemId) {
        return ledgerRepository.readBalances(scopedDepartmentId(user, requestedDepartmentId), itemId);
    }

    public ArrayNode exceptionTasks(SessionUser user, String requestedDepartmentId, String status) {
        return ledgerRepository.readExceptions(scopedDepartmentId(user, requestedDepartmentId), status);
    }

    public ObjectNode consumptionEvents(
        SessionUser user,
        String requestedDepartmentId,
        LocalDate from,
        LocalDate to,
        int page,
        int size
    ) {
        ArrayNode all = ledgerRepository.readConsumptions(scopedDepartmentId(user, requestedDepartmentId), from, to);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int fromIndex = Math.min((safePage - 1) * safeSize, all.size());
        int toIndex = Math.min(fromIndex + safeSize, all.size());
        ArrayNode list = JsonNodeFactory.instance.arrayNode();
        for (int index = fromIndex; index < toIndex; index++) list.add(all.get(index));
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("total", all.size());
        result.set("list", list);
        return result;
    }

    public ObjectNode confirmOpening(JsonNode payload, SessionUser user) {
        return ledgerService.confirmOpening(payload, user);
    }

    public ObjectNode departmentUsageReport(
        SessionUser user,
        LocalDate from,
        LocalDate to,
        List<String> departmentIds,
        String itemId,
        String category,
        String triggerStage,
        String action
    ) {
        ObjectNode result = reportService.query(from, to, departmentIds, itemId, category, triggerStage);
        reportService.audit(user, action, from, to, departmentIds == null ? List.of() : departmentIds);
        return result;
    }

    public byte[] exportDepartmentUsageXlsx(ObjectNode report) {
        return reportService.exportXlsx(report);
    }

    public byte[] exportDepartmentUsagePdf(ObjectNode report) {
        return reportService.exportPdf(report);
    }

    private String scopedDepartmentId(SessionUser user, String requestedDepartmentId) {
        if (List.of("admin", "quality", "manager").contains(user.role())
            && requestedDepartmentId != null && !requestedDepartmentId.isBlank()) {
            return ledgerRepository.resolveDepartmentId(requestedDepartmentId, requestedDepartmentId);
        }
        return ledgerRepository.resolveDepartmentId("", user.department());
    }

    private ArrayNode filterByDepartment(JsonNode rows, String department) {
        ArrayNode filtered = JsonNodeFactory.instance.arrayNode();
        if (rows != null && rows.isArray()) {
            for (JsonNode row : rows) {
                if (department != null && department.equals(repository.text(row, "department"))) filtered.add(row);
            }
        }
        return filtered;
    }

    public Map<String, Object> asMap(ObjectNode db) {
        return repository.asMap(db);
    }
}
