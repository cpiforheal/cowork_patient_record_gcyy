package com.coshare.patientrecord.inventory.controller;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.AuthNavigationService;
import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.inventory.service.InventoryDatabaseService;
import com.coshare.patientrecord.security.InventoryPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.List;
import java.time.LocalDate;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("mysql")
public class InventoryApiController {

    private final InventoryDatabaseService databaseService;
    private final ObjectMapper objectMapper;
    private final AuthNavigationService navigationService;

    public InventoryApiController(
        InventoryDatabaseService databaseService,
        ObjectMapper objectMapper,
        AuthNavigationService navigationService
    ) {
        this.databaseService = databaseService;
        this.objectMapper = objectMapper;
        this.navigationService = navigationService;
    }

    @GetMapping("/inventory-api/db")
    public ApiResult<Map<String, Object>> readDb() {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(databaseService.readDbForUser(user)));
    }

    @GetMapping("/inventory-api/workbench")
    public ApiResult<Map<String, Object>> workbench(
        @RequestParam(required = false) String departmentId
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(databaseService.workbench(user, departmentId)));
    }

    @GetMapping("/inventory-api/department-balances")
    public ApiResult<Map<String, Object>> departmentBalances(
        @RequestParam(required = false) String departmentId,
        @RequestParam(required = false) String itemId
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(Map.of(
            "list", objectMapper.convertValue(databaseService.departmentBalances(user, departmentId, itemId), List.class)
        ));
    }

    @GetMapping("/inventory-api/exception-tasks")
    public ApiResult<Map<String, Object>> exceptionTasks(
        @RequestParam(required = false) String departmentId,
        @RequestParam(defaultValue = "OPEN") String status
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(Map.of(
            "list", objectMapper.convertValue(databaseService.exceptionTasks(user, departmentId, status), List.class)
        ));
    }

    @GetMapping("/inventory-api/consumption-events")
    public ApiResult<Map<String, Object>> consumptionEvents(
        @RequestParam(required = false) String departmentId,
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(
            databaseService.consumptionEvents(user, departmentId, from, to, page, size)
        ));
    }

    @PostMapping("/inventory-api/department-openings/confirm")
    public ApiResult<Map<String, Object>> confirmDepartmentOpening(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:count");
        return ApiResult.of(200, "科室期初库存已确认", databaseService.asMap(databaseService.confirmOpening(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/exception-tasks/retry")
    public ApiResult<Map<String, Object>> retryException(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:approve");
        return ApiResult.of(200, "库存异常任务已重新排队", databaseService.asMap(databaseService.retryConsumption(toJson(payload), user)));
    }

    @GetMapping("/inventory-api/reports/department-usage")
    public ApiResult<Map<String, Object>> departmentUsage(
        @RequestParam LocalDate from,
        @RequestParam LocalDate to,
        @RequestParam(required = false) List<String> departmentIds,
        @RequestParam(required = false) String itemId,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String triggerStage
    ) {
        SessionUser user = requireCapability("inventory:export");
        return ApiResult.success(databaseService.asMap(
            databaseService.departmentUsageReport(user, from, to, safeList(departmentIds), itemId, category, triggerStage, "查询科室耗材报表")
        ));
    }

    @GetMapping("/inventory-api/reports/department-usage.xlsx")
    public ResponseEntity<byte[]> departmentUsageXlsx(
        @RequestParam LocalDate from,
        @RequestParam LocalDate to,
        @RequestParam(required = false) List<String> departmentIds,
        @RequestParam(required = false) String itemId,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String triggerStage
    ) {
        SessionUser user = requireCapability("inventory:export");
        var report = databaseService.departmentUsageReport(user, from, to, safeList(departmentIds), itemId, category, triggerStage, "导出科室耗材XLSX");
        return attachment(
            databaseService.exportDepartmentUsageXlsx(report),
            "department-usage-" + from + "-" + to + ".xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        );
    }

    @GetMapping("/inventory-api/reports/department-usage.pdf")
    public ResponseEntity<byte[]> departmentUsagePdf(
        @RequestParam LocalDate from,
        @RequestParam LocalDate to,
        @RequestParam(required = false) List<String> departmentIds,
        @RequestParam(required = false) String itemId,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String triggerStage
    ) {
        SessionUser user = requireCapability("inventory:export");
        var report = databaseService.departmentUsageReport(user, from, to, safeList(departmentIds), itemId, category, triggerStage, "导出科室耗材PDF");
        return attachment(
            databaseService.exportDepartmentUsagePdf(report),
            "department-usage-" + from + "-" + to + ".pdf",
            MediaType.APPLICATION_PDF_VALUE
        );
    }

    @PostMapping("/inventory-api/items")
    public ApiResult<Map<String, Object>> saveItem(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:issue");
        return ApiResult.of(200, "saved", databaseService.asMap(databaseService.saveItem(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/inbounds")
    public ApiResult<Map<String, Object>> inbound(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:issue");
        return ApiResult.of(200, "inbound saved", databaseService.asMap(databaseService.inbound(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/requests")
    public ApiResult<Map<String, Object>> createRequest(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:request");
        return ApiResult.of(200, "request created", databaseService.asMap(databaseService.createRequest(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/requests/approve")
    public ApiResult<Map<String, Object>> approveRequest(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:approve");
        return ApiResult.of(200, "request approved", databaseService.asMap(databaseService.approveRequest(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/requests/issue")
    public ApiResult<Map<String, Object>> issueRequest(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:issue");
        return ApiResult.of(200, "request issued", databaseService.asMap(databaseService.issueRequest(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/requests/receive")
    public ApiResult<Map<String, Object>> receiveRequest(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:receive");
        return ApiResult.of(200, "request received", databaseService.asMap(databaseService.receiveRequest(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/requests/reject")
    public ApiResult<Map<String, Object>> rejectRequest(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:approve");
        return ApiResult.of(200, "request rejected", databaseService.asMap(databaseService.rejectRequest(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/requests/cancel")
    public ApiResult<Map<String, Object>> cancelRequest(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:request");
        return ApiResult.of(200, "request cancelled", databaseService.asMap(databaseService.cancelRequest(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/requests/void")
    public ApiResult<Map<String, Object>> voidRequest(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:approve");
        return ApiResult.of(200, "request voided", databaseService.asMap(databaseService.voidRequest(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/weekly-consumptions")
    public ApiResult<Map<String, Object>> weeklyConsumption(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:request");
        return ApiResult.of(200, "weekly consumption saved", databaseService.asMap(databaseService.weeklyConsumption(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/movements/return-or-scrap")
    public ApiResult<Map<String, Object>> returnOrScrap(@RequestBody Map<String, Object> payload) {
        JsonNode jsonPayload = toJson(payload);
        SessionUser user = "return".equals(jsonPayload.path("type").asText(""))
            ? requireCapability("inventory:receive")
            : requireCapability("inventory:count");
        return ApiResult.of(200, "movement saved", databaseService.asMap(databaseService.returnOrScrap(jsonPayload, user)));
    }

    @PostMapping("/inventory-api/counts")
    public ApiResult<Map<String, Object>> inventoryCount(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:count");
        return ApiResult.of(200, "count saved", databaseService.asMap(databaseService.inventoryCount(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/packages")
    public ApiResult<Map<String, Object>> savePackage(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:approve");
        return ApiResult.of(200, "套餐草稿已保存", databaseService.asMap(databaseService.savePackage(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/packages/enable")
    public ApiResult<Map<String, Object>> enablePackage(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:approve");
        return ApiResult.of(200, "套餐已启用", databaseService.asMap(databaseService.enablePackage(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/packages/disable")
    public ApiResult<Map<String, Object>> disablePackage(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:approve");
        return ApiResult.of(200, "套餐已停用", databaseService.asMap(databaseService.disablePackage(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/consumption-events")
    public ApiResult<Map<String, Object>> consumeEncounter(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:approve");
        return ApiResult.of(200, "消耗事件已记录", databaseService.asMap(databaseService.consumeEncounter(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/consumption-events/retry")
    public ApiResult<Map<String, Object>> retryConsumption(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:approve");
        return ApiResult.of(200, "消耗事件已重试", databaseService.asMap(databaseService.retryConsumption(toJson(payload), user)));
    }

    private SessionUser requireCapability(String capability) {
        SessionUser user = InventoryPermission.currentUserOrThrow();
        if (!navigationService.hasCapability(user, capability)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前岗位无此库存操作权限");
        }
        return user;
    }

    private JsonNode toJson(Map<String, Object> payload) {
        return objectMapper.valueToTree(payload == null ? Map.of() : payload);
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank()).toList();
    }

    private static ResponseEntity<byte[]> attachment(byte[] body, String filename, String mediaType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mediaType));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        headers.setContentLength(body.length);
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
