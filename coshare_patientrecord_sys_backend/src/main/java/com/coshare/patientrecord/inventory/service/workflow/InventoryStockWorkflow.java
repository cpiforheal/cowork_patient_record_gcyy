package com.coshare.patientrecord.inventory.service.workflow;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.coshare.patientrecord.inventory.service.InventoryLedgerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("mysql")
public class InventoryStockWorkflow {

    private final InventoryRepository repository;
    private final InventoryLedgerService ledgerService;

    public InventoryStockWorkflow(InventoryRepository repository, InventoryLedgerService ledgerService) {
        this.repository = repository;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public ObjectNode saveItem(JsonNode payload, SessionUser user) {
        ObjectNode item = repository.object(payload).deepCopy();
        repository.applyOperator(item, user);
        if (repository.text(item, "id").isBlank()) item.put("id", "item-" + UUID.randomUUID());
        if (repository.text(item, "name").isBlank()) throw new IllegalArgumentException("物资名称不能为空");
        item.put("enabled", item.path("enabled").asBoolean(true));
        repository.upsertItem(item);
        repository.log(repository.text(item, "operator", "系统"), "维护物资档案", "item", repository.text(item, "name"), "新增或更新物资基础信息");
        return repository.readDbForUser(user);
    }

    @Transactional
    public ObjectNode inbound(JsonNode payload, SessionUser user) {
        ObjectNode row = repository.object(payload).deepCopy();
        repository.applyOperator(row, user);
        String itemId = repository.text(row, "itemId");
        if (itemId.isBlank()) throw new IllegalArgumentException("请选择入库物资");
        BigDecimal quantity = repository.quantity(row, "quantity");
        if (quantity.signum() <= 0) throw new IllegalArgumentException("入库数量必须大于 0");
        ObjectNode item = repository.loadItem(itemId);
        boolean batchRequired = item.path("batchRequired").asBoolean(false);
        boolean expiryRequired = item.path("expiryRequired").asBoolean(false);
        String batchNo = repository.text(row, "batchNo");
        String expiryDate = repository.text(row, "expiryDate");
        if (batchRequired && batchNo.isBlank()) throw new IllegalArgumentException("该物资要求批号，入库时必须填写批号");
        if (expiryRequired && expiryDate.isBlank()) throw new IllegalArgumentException("该物资要求效期，入库时必须填写有效期");

        ObjectNode batch = repository.reusableInboundBatch(itemId, batchNo, expiryDate, batchRequired);
        repository.putQuantity(batch, "quantity", repository.quantity(batch, "quantity").add(quantity));
        batch.put("batchNo", batchNo.isBlank() && repository.text(batch, "id").startsWith("stock-") ? "常备库存" : batchNo);
        batch.put("expiryDate", expiryDate);
        batch.put("location", repository.text(row, "location", repository.itemLocation(itemId)));
        batch.put("source", repository.text(row, "source"));
        if (repository.text(batch, "createdAt").isBlank()) batch.put("createdAt", repository.now());
        repository.upsertBatch(batch);
        ledgerService.recordInbound(itemId, repository.text(batch, "id"), quantity, user, repository.text(row, "source"));
        repository.movement("inbound", itemId, repository.text(batch, "id"), quantity, "", repository.text(row, "operator"), repository.text(row, "source"), repository.text(batch, "id"));
        repository.log(repository.text(row, "operator"), "物资入库", "batch", repository.itemLabel(itemId), "入库 " + quantity + " " + repository.itemUnit(itemId));
        return repository.readDbForUser(user);
    }

    @Transactional
    public ObjectNode weeklyConsumption(JsonNode payload, SessionUser user) {
        ObjectNode row = repository.object(payload).deepCopy();
        repository.applyOperator(row, user);
        repository.applyUserDepartment(row, user);
        row.put("owner", user.name());
        if (repository.text(row, "weekNo").isBlank()) throw new IllegalArgumentException("请选择周次");
        if (repository.text(row, "department").isBlank()) throw new IllegalArgumentException("请选择科室");
        if (repository.text(row, "itemId").isBlank()) throw new IllegalArgumentException("请选择物资");
        ledgerService.populateWeeklySuggestion(row, user);
        row.put("id", repository.weeklyConsumptionId(row));
        row.put("confirmedAt", repository.now());
        repository.saveWeeklyConsumption(row);
        repository.log(
            repository.text(row, "operator", repository.text(row, "owner")),
            "周消耗确认",
            "weekly",
            repository.itemLabel(repository.text(row, "itemId")),
            repository.text(row, "department") + " 已确认周消耗"
        );
        return repository.readDbForUser(user);
    }

    @Transactional
    public ObjectNode returnOrScrap(JsonNode payload, SessionUser user) {
        ObjectNode row = repository.object(payload).deepCopy();
        repository.applyOperator(row, user);
        repository.applyUserDepartment(row, user);
        String type = repository.text(row, "type");
        if (!List.of("return", "scrap").contains(type)) throw new IllegalArgumentException("请选择退回或报废");
        String itemId = repository.text(row, "itemId");
        BigDecimal quantity = repository.quantity(row, "quantity");
        if (itemId.isBlank() || quantity.signum() <= 0) throw new IllegalArgumentException("请选择物资并填写数量");
        if ("return".equals(type)) {
            ledgerService.returnToCentral(row, user);
            repository.log(repository.text(row, "operator"), "物资退回", "movement", repository.itemLabel(itemId), repository.text(row, "department") + " 退回 " + quantity);
        } else {
            ledgerService.scrap(row, user);
            repository.log(repository.text(row, "operator"), "物资报废", "movement", repository.itemLabel(itemId), "报废 " + quantity);
        }
        return repository.readDbForUser(user);
    }

    @Transactional
    public ObjectNode inventoryCount(JsonNode payload, SessionUser user) {
        ObjectNode row = repository.object(payload).deepCopy();
        repository.applyOperator(row, user);
        String itemId = repository.text(row, "itemId");
        if (itemId.isBlank()) throw new IllegalArgumentException("请选择物资");
        InventoryLedgerService.CountResult result = ledgerService.count(row, user);
        BigDecimal bookQuantity = result.bookQuantity();
        BigDecimal actualQuantity = result.actualQuantity();
        BigDecimal difference = result.difference();
        ObjectNode batch = row.objectNode();
        batch.put("id", result.batchId());

        row.put("id", "count-" + UUID.randomUUID());
        row.put("batchId", repository.text(batch, "id"));
        repository.putQuantity(row, "bookQuantity", bookQuantity);
        repository.putQuantity(row, "actualQuantity", actualQuantity);
        repository.putQuantity(row, "differenceQuantity", difference);
        row.put("countedAt", repository.now());
        repository.saveInventoryCount(row, itemId, batch, bookQuantity, actualQuantity, difference);
        repository.log(repository.text(row, "operator"), "库存盘点", "count", repository.itemLabel(itemId), "账面 " + bookQuantity + "，实盘 " + actualQuantity);
        return repository.readDbForUser(user);
    }
}
