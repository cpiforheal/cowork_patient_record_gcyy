package com.example.coshare_patientrecord_sys;

import com.fasterxml.jackson.databind.JsonNode;
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

    public InventoryApiController(InventoryDatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    @GetMapping("/inventory-api/db")
    public ApiResult<Map<String, Object>> readDb() {
        AuthSessionService.SessionUser user = InventoryPermission.currentUserOrThrow();
        return ApiResult.success(databaseService.asMap(databaseService.readDbForUser(user)));
    }

    @PostMapping("/inventory-api/items")
    public ApiResult<Map<String, Object>> saveItem(@RequestBody JsonNode payload) {
        AuthSessionService.SessionUser user = InventoryPermission.requireManager();
        return ApiResult.of(200, "saved", databaseService.asMap(databaseService.saveItem(payload, user)));
    }

    @PostMapping("/inventory-api/inbounds")
    public ApiResult<Map<String, Object>> inbound(@RequestBody JsonNode payload) {
        AuthSessionService.SessionUser user = InventoryPermission.requireManager();
        return ApiResult.of(200, "inbound saved", databaseService.asMap(databaseService.inbound(payload, user)));
    }

    @PostMapping("/inventory-api/requests")
    public ApiResult<Map<String, Object>> createRequest(@RequestBody JsonNode payload) {
        AuthSessionService.SessionUser user = InventoryPermission.requireStaff();
        return ApiResult.of(200, "request created", databaseService.asMap(databaseService.createRequest(payload, user)));
    }

    @PostMapping("/inventory-api/requests/approve")
    public ApiResult<Map<String, Object>> approveRequest(@RequestBody JsonNode payload) {
        AuthSessionService.SessionUser user = InventoryPermission.requireManager();
        return ApiResult.of(200, "request approved", databaseService.asMap(databaseService.approveRequest(payload, user)));
    }

    @PostMapping("/inventory-api/requests/issue")
    public ApiResult<Map<String, Object>> issueRequest(@RequestBody JsonNode payload) {
        AuthSessionService.SessionUser user = InventoryPermission.requireManager();
        return ApiResult.of(200, "request issued", databaseService.asMap(databaseService.issueRequest(payload, user)));
    }

    @PostMapping("/inventory-api/requests/receive")
    public ApiResult<Map<String, Object>> receiveRequest(@RequestBody JsonNode payload) {
        AuthSessionService.SessionUser user = InventoryPermission.requireStaff();
        return ApiResult.of(200, "request received", databaseService.asMap(databaseService.receiveRequest(payload, user)));
    }

    @PostMapping("/inventory-api/requests/reject")
    public ApiResult<Map<String, Object>> rejectRequest(@RequestBody JsonNode payload) {
        AuthSessionService.SessionUser user = InventoryPermission.requireManager();
        return ApiResult.of(200, "request rejected", databaseService.asMap(databaseService.rejectRequest(payload, user)));
    }

    @PostMapping("/inventory-api/requests/cancel")
    public ApiResult<Map<String, Object>> cancelRequest(@RequestBody JsonNode payload) {
        AuthSessionService.SessionUser user = InventoryPermission.requireStaff();
        return ApiResult.of(200, "request cancelled", databaseService.asMap(databaseService.cancelRequest(payload, user)));
    }

    @PostMapping("/inventory-api/requests/void")
    public ApiResult<Map<String, Object>> voidRequest(@RequestBody JsonNode payload) {
        AuthSessionService.SessionUser user = InventoryPermission.requireManager();
        return ApiResult.of(200, "request voided", databaseService.asMap(databaseService.voidRequest(payload, user)));
    }

    @PostMapping("/inventory-api/weekly-consumptions")
    public ApiResult<Map<String, Object>> weeklyConsumption(@RequestBody JsonNode payload) {
        AuthSessionService.SessionUser user = InventoryPermission.requireStaff();
        return ApiResult.of(200, "weekly consumption saved", databaseService.asMap(databaseService.weeklyConsumption(payload, user)));
    }

    @PostMapping("/inventory-api/movements/return-or-scrap")
    public ApiResult<Map<String, Object>> returnOrScrap(@RequestBody JsonNode payload) {
        AuthSessionService.SessionUser user = InventoryPermission.requireManager();
        return ApiResult.of(200, "movement saved", databaseService.asMap(databaseService.returnOrScrap(payload, user)));
    }

    @PostMapping("/inventory-api/counts")
    public ApiResult<Map<String, Object>> inventoryCount(@RequestBody JsonNode payload) {
        AuthSessionService.SessionUser user = InventoryPermission.requireManager();
        return ApiResult.of(200, "count saved", databaseService.asMap(databaseService.inventoryCount(payload, user)));
    }
}
