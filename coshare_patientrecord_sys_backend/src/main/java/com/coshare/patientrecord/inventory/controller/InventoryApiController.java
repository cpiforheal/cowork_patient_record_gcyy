package com.coshare.patientrecord.inventory.controller;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.inventory.service.InventoryDatabaseService;
import com.coshare.patientrecord.security.InventoryPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
public class InventoryApiController {

    private final InventoryDatabaseService databaseService;
    private final ObjectMapper objectMapper;

    public InventoryApiController(InventoryDatabaseService databaseService, ObjectMapper objectMapper) {
        this.databaseService = databaseService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/inventory-api/db")
    public ApiResult<Map<String, Object>> readDb() {
        SessionUser user = InventoryPermission.currentUserOrThrow();
        return ApiResult.success(databaseService.asMap(databaseService.readDbForUser(user)));
    }

    @PostMapping("/inventory-api/items")
    public ApiResult<Map<String, Object>> saveItem(@RequestBody Map<String, Object> payload) {
        SessionUser user = InventoryPermission.requireStockKeeper();
        return ApiResult.of(200, "saved", databaseService.asMap(databaseService.saveItem(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/inbounds")
    public ApiResult<Map<String, Object>> inbound(@RequestBody Map<String, Object> payload) {
        SessionUser user = InventoryPermission.requireStockKeeper();
        return ApiResult.of(200, "inbound saved", databaseService.asMap(databaseService.inbound(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/requests")
    public ApiResult<Map<String, Object>> createRequest(@RequestBody Map<String, Object> payload) {
        SessionUser user = InventoryPermission.requireStaff();
        return ApiResult.of(200, "request created", databaseService.asMap(databaseService.createRequest(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/requests/approve")
    public ApiResult<Map<String, Object>> approveRequest(@RequestBody Map<String, Object> payload) {
        SessionUser user = InventoryPermission.requireApprover();
        return ApiResult.of(200, "request approved", databaseService.asMap(databaseService.approveRequest(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/requests/issue")
    public ApiResult<Map<String, Object>> issueRequest(@RequestBody Map<String, Object> payload) {
        SessionUser user = InventoryPermission.requireStockKeeper();
        return ApiResult.of(200, "request issued", databaseService.asMap(databaseService.issueRequest(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/requests/receive")
    public ApiResult<Map<String, Object>> receiveRequest(@RequestBody Map<String, Object> payload) {
        SessionUser user = InventoryPermission.requireStaff();
        return ApiResult.of(200, "request received", databaseService.asMap(databaseService.receiveRequest(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/requests/reject")
    public ApiResult<Map<String, Object>> rejectRequest(@RequestBody Map<String, Object> payload) {
        SessionUser user = InventoryPermission.requireApprover();
        return ApiResult.of(200, "request rejected", databaseService.asMap(databaseService.rejectRequest(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/requests/cancel")
    public ApiResult<Map<String, Object>> cancelRequest(@RequestBody Map<String, Object> payload) {
        SessionUser user = InventoryPermission.requireStaff();
        return ApiResult.of(200, "request cancelled", databaseService.asMap(databaseService.cancelRequest(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/requests/void")
    public ApiResult<Map<String, Object>> voidRequest(@RequestBody Map<String, Object> payload) {
        SessionUser user = InventoryPermission.requireApprover();
        return ApiResult.of(200, "request voided", databaseService.asMap(databaseService.voidRequest(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/weekly-consumptions")
    public ApiResult<Map<String, Object>> weeklyConsumption(@RequestBody Map<String, Object> payload) {
        SessionUser user = InventoryPermission.requireStaff();
        return ApiResult.of(200, "weekly consumption saved", databaseService.asMap(databaseService.weeklyConsumption(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/movements/return-or-scrap")
    public ApiResult<Map<String, Object>> returnOrScrap(@RequestBody Map<String, Object> payload) {
        JsonNode jsonPayload = toJson(payload);
        SessionUser user = "return".equals(jsonPayload.path("type").asText(""))
            ? InventoryPermission.requireStockKeeper()
            : InventoryPermission.requireCounter();
        return ApiResult.of(200, "movement saved", databaseService.asMap(databaseService.returnOrScrap(jsonPayload, user)));
    }

    @PostMapping("/inventory-api/counts")
    public ApiResult<Map<String, Object>> inventoryCount(@RequestBody Map<String, Object> payload) {
        SessionUser user = InventoryPermission.requireCounter();
        return ApiResult.of(200, "count saved", databaseService.asMap(databaseService.inventoryCount(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/packages")
    public ApiResult<Map<String, Object>> savePackage(@RequestBody Map<String, Object> payload) {
        SessionUser user = InventoryPermission.requireApprover();
        return ApiResult.of(200, "套餐草稿已保存", databaseService.asMap(databaseService.savePackage(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/packages/enable")
    public ApiResult<Map<String, Object>> enablePackage(@RequestBody Map<String, Object> payload) {
        SessionUser user = InventoryPermission.requireApprover();
        return ApiResult.of(200, "套餐已启用", databaseService.asMap(databaseService.enablePackage(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/packages/disable")
    public ApiResult<Map<String, Object>> disablePackage(@RequestBody Map<String, Object> payload) {
        SessionUser user = InventoryPermission.requireApprover();
        return ApiResult.of(200, "套餐已停用", databaseService.asMap(databaseService.disablePackage(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/consumption-events")
    public ApiResult<Map<String, Object>> consumeEncounter(@RequestBody Map<String, Object> payload) {
        SessionUser user = InventoryPermission.requireApprover();
        return ApiResult.of(200, "消耗事件已记录", databaseService.asMap(databaseService.consumeEncounter(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/consumption-events/retry")
    public ApiResult<Map<String, Object>> retryConsumption(@RequestBody Map<String, Object> payload) {
        SessionUser user = InventoryPermission.requireApprover();
        return ApiResult.of(200, "消耗事件已重试", databaseService.asMap(databaseService.retryConsumption(toJson(payload), user)));
    }

    private JsonNode toJson(Map<String, Object> payload) {
        return objectMapper.valueToTree(payload == null ? Map.of() : payload);
    }
}
