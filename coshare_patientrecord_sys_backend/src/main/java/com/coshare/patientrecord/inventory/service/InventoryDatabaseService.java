package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.coshare.patientrecord.inventory.service.workflow.InventoryRequestWorkflow;
import com.coshare.patientrecord.inventory.service.workflow.InventoryStockWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class InventoryDatabaseService {

    private final InventoryRepository repository;
    private final InventoryRequestWorkflow requestWorkflow;
    private final InventoryStockWorkflow stockWorkflow;
    private final InventoryPackageService packageService;

    public InventoryDatabaseService(
        InventoryRepository repository,
        InventoryRequestWorkflow requestWorkflow,
        InventoryStockWorkflow stockWorkflow,
        InventoryPackageService packageService
    ) {
        this.repository = repository;
        this.requestWorkflow = requestWorkflow;
        this.stockWorkflow = stockWorkflow;
        this.packageService = packageService;
    }

    public void initializeSchema() {
        repository.initializeSchema();
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
        String department = repository.text(payload, "department", user.department());
        if (!("admin".equals(user.role()) || "quality".equals(user.role()))) department = user.department();
        packageService.consumeEncounter(
            repository.text(payload, "encounterId"),
            repository.text(payload, "caseToken"),
            repository.text(payload, "route"),
            department,
            repository.text(payload, "visitDate"),
            user
        );
        return readDbForUser(user);
    }

    public ObjectNode retryConsumption(JsonNode payload, SessionUser user) {
        packageService.retryFailedConsumption(repository.text(payload, "id"), user);
        return readDbForUser(user);
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
