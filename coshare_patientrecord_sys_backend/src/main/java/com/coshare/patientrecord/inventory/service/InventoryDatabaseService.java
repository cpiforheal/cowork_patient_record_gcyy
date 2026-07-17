package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.coshare.patientrecord.inventory.service.workflow.InventoryRequestWorkflow;
import com.coshare.patientrecord.inventory.service.workflow.InventoryStockWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class InventoryDatabaseService {

    private final InventoryRepository repository;
    private final InventoryRequestWorkflow requestWorkflow;
    private final InventoryStockWorkflow stockWorkflow;

    public InventoryDatabaseService(
        InventoryRepository repository,
        InventoryRequestWorkflow requestWorkflow,
        InventoryStockWorkflow stockWorkflow
    ) {
        this.repository = repository;
        this.requestWorkflow = requestWorkflow;
        this.stockWorkflow = stockWorkflow;
    }

    public void initializeSchema() {
        repository.initializeSchema();
    }

    public ObjectNode readDb() {
        return repository.readDb();
    }

    public ObjectNode readDbForUser(SessionUser user) {
        return repository.readDbForUser(user);
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

    public Map<String, Object> asMap(ObjectNode db) {
        return repository.asMap(db);
    }
}
