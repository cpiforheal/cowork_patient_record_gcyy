package com.coshare.patientrecord.inventory.controller;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.AuthNavigationService;
import com.coshare.patientrecord.auth.service.InventoryAccessService;
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
    private final InventoryAccessService inventoryAccessService;

    public InventoryApiController(
        InventoryDatabaseService databaseService,
        ObjectMapper objectMapper,
        AuthNavigationService navigationService,
        InventoryAccessService inventoryAccessService
    ) {
        this.databaseService = databaseService;
        this.objectMapper = objectMapper;
        this.navigationService = navigationService;
        this.inventoryAccessService = inventoryAccessService;
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

    @GetMapping("/inventory-api/ledger-movements")
    public ApiResult<Map<String, Object>> ledgerMovements(
        @RequestParam(required = false) String departmentId,
        @RequestParam(required = false) String itemId,
        @RequestParam(required = false) String movementType,
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(
            databaseService.ledgerMovements(user, departmentId, itemId, movementType, from, to, page, size)
        ));
    }

    @PostMapping("/inventory-api/department-openings/confirm")
    public ApiResult<Map<String, Object>> confirmDepartmentOpening(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:count");
        return ApiResult.of(200, "科室期初库存已确认", databaseService.asMap(databaseService.confirmOpening(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/exception-tasks/retry")
    public ApiResult<Map<String, Object>> retryException(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:retry");
        return ApiResult.of(200, "库存异常任务已重新排队", databaseService.asMap(databaseService.retryConsumption(toJson(payload), user)));
    }

    @GetMapping("/inventory-api/reports/department-usage")
    public ApiResult<Map<String, Object>> departmentUsage(
        @RequestParam LocalDate from,
        @RequestParam LocalDate to,
        @RequestParam(required = false) List<String> departmentIds,
        @RequestParam(required = false) String itemId,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String triggerStage,
        @RequestParam(defaultValue = "false") boolean patientOnly
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(
            databaseService.departmentUsageReport(user, from, to, safeList(departmentIds), itemId, category, triggerStage, patientOnly, "查询科室耗材报表")
        ));
    }

    @GetMapping("/inventory-api/reports/department-usage.xlsx")
    public ResponseEntity<byte[]> departmentUsageXlsx(
        @RequestParam LocalDate from,
        @RequestParam LocalDate to,
        @RequestParam(required = false) List<String> departmentIds,
        @RequestParam(required = false) String itemId,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String triggerStage,
        @RequestParam(defaultValue = "false") boolean patientOnly
    ) {
        SessionUser user = requireCapability("inventory:export");
        var report = databaseService.departmentUsageReport(user, from, to, safeList(departmentIds), itemId, category, triggerStage, patientOnly, "导出科室耗材XLSX");
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
        @RequestParam(required = false) String triggerStage,
        @RequestParam(defaultValue = "false") boolean patientOnly
    ) {
        SessionUser user = requireCapability("inventory:export");
        var report = databaseService.departmentUsageReport(user, from, to, safeList(departmentIds), itemId, category, triggerStage, patientOnly, "导出科室耗材PDF");
        return attachment(
            databaseService.exportDepartmentUsagePdf(report),
            "department-usage-" + from + "-" + to + ".pdf",
            MediaType.APPLICATION_PDF_VALUE
        );
    }

    @GetMapping("/inventory-api/weekly/standards")
    public ApiResult<Map<String, Object>> weeklyStandards() {
        requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(databaseService.weeklyStandards()));
    }

    @GetMapping("/inventory-api/weekly/standards/detail")
    public ApiResult<Map<String, Object>> weeklyStandard(@RequestParam String id) {
        requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(databaseService.weeklyStandard(id)));
    }

    @PostMapping("/inventory-api/weekly/standards")
    public ApiResult<Map<String, Object>> saveWeeklyStandard(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:rule");
        return ApiResult.of(200, "周度标准草稿已保存", databaseService.asMap(databaseService.saveWeeklyStandard(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/weekly/standards/publish")
    public ApiResult<Map<String, Object>> publishWeeklyStandard(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:rule");
        return ApiResult.of(200, "周度标准已发布", databaseService.asMap(databaseService.publishWeeklyStandard(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/weekly/standards/delete")
    public ApiResult<Map<String, Object>> deleteWeeklyStandard(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:rule");
        return ApiResult.of(200, "周度标准草稿已删除", databaseService.asMap(databaseService.deleteWeeklyStandard(toJson(payload), user)));
    }

    @GetMapping("/inventory-api/weekly/snapshots")
    public ApiResult<Map<String, Object>> weeklySnapshots(
        @RequestParam(required = false) String departmentId,
        @RequestParam(required = false) String weekNo
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(databaseService.weeklySnapshots(user, departmentId, weekNo)));
    }

    @GetMapping("/inventory-api/weekly/snapshots/detail")
    public ApiResult<Map<String, Object>> weeklySnapshot(@RequestParam String id) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(databaseService.weeklySnapshot(id, user)));
    }

    @PostMapping("/inventory-api/weekly/snapshots/generate")
    public ApiResult<Map<String, Object>> generateWeeklySnapshot(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:confirm");
        return ApiResult.of(200, "周度库存快照已生成", databaseService.asMap(databaseService.generateWeeklySnapshot(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/weekly/snapshots/confirm")
    public ApiResult<Map<String, Object>> confirmWeeklySnapshot(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:confirm");
        return ApiResult.of(200, "周度库存快照已确认", databaseService.asMap(databaseService.confirmWeeklySnapshot(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/weekly/snapshots/revise")
    public ApiResult<Map<String, Object>> reviseWeeklySnapshot(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:confirm");
        return ApiResult.of(200, "周度库存更正版本已生成", databaseService.asMap(databaseService.reviseWeeklySnapshot(toJson(payload), user)));
    }

    @GetMapping("/inventory-api/weekly/snapshots/export")
    public ResponseEntity<byte[]> exportWeeklySnapshot(@RequestParam String id, @RequestParam String format) {
        SessionUser user = requireCapability("inventory:export");
        var file = databaseService.exportWeeklySnapshot(id, format, user);
        return attachment(file.body(), file.filename(), file.mediaType());
    }

    @PostMapping("/inventory-api/items")
    public ApiResult<Map<String, Object>> saveItem(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:item:manage");
        return ApiResult.of(200, "saved", databaseService.asMap(databaseService.saveItem(toJson(payload), user)));
    }

    @GetMapping("/inventory-api/role-management")
    public ApiResult<Map<String, Object>> roleManagement() {
        requireCapability("inventory:role:manage");
        return ApiResult.success(Map.of(
            "roles", inventoryAccessService.roleCatalog(),
            "accounts", inventoryAccessService.accountAssignments()
        ));
    }

    @PostMapping("/inventory-api/role-management/assign")
    public ApiResult<Map<String, Object>> assignInventoryRole(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:role:manage");
        inventoryAccessService.assign(
            String.valueOf(payload == null ? "" : payload.getOrDefault("accountId", "")),
            String.valueOf(payload == null ? "" : payload.getOrDefault("roleCode", "")),
            user
        );
        return ApiResult.of(200, "进销存岗位权限已保存", Map.of(
            "roles", inventoryAccessService.roleCatalog(),
            "accounts", inventoryAccessService.accountAssignments()
        ));
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

    @GetMapping("/inventory-api/mapping/summary")
    public ApiResult<Map<String, Object>> mappingSummary() {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(databaseService.mappingSummary(user)));
    }

    @GetMapping("/inventory-api/mapping/entries")
    public ApiResult<Map<String, Object>> mappingEntries(
        @RequestParam(required = false) String ruleType,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String businessGroup,
        @RequestParam(required = false) String department,
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(
            databaseService.mappingEntries(user, ruleType, status, businessGroup, department, keyword, page, size)
        ));
    }

    @PostMapping("/inventory-api/mapping/entries/confirm")
    public ApiResult<Map<String, Object>> confirmMappingEntries(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:rule");
        return ApiResult.of(200, "mapping confirmed", databaseService.asMap(databaseService.confirmMappingEntries(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/mapping/entries/hold")
    public ApiResult<Map<String, Object>> holdMappingEntries(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:rule");
        return ApiResult.of(200, "mapping held", databaseService.asMap(databaseService.holdMappingEntries(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/mapping/entries/create-package-draft")
    public ApiResult<Map<String, Object>> createMappingPackageDraft(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:rule");
        return ApiResult.of(200, "package draft created", databaseService.asMap(databaseService.createMappingPackageDraft(toJson(payload), user)));
    }

    @GetMapping("/inventory-api/mapping/aliases")
    public ApiResult<Map<String, Object>> mappingAliases(
        @RequestParam(required = false) String itemId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String keyword
    ) {
        requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(databaseService.mappingAliases(itemId, status, keyword)));
    }

    @PostMapping("/inventory-api/mapping/aliases")
    public ApiResult<Map<String, Object>> saveMappingAliases(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireAnyCapability(List.of("inventory:item:manage", "inventory:rule"));
        return ApiResult.of(200, "mapping aliases saved", databaseService.asMap(databaseService.saveMappingAliases(toJson(payload), user)));
    }

    @GetMapping("/inventory-api/mapping/unit-conversions")
    public ApiResult<Map<String, Object>> mappingUnitConversions(
        @RequestParam(required = false) String itemId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String keyword
    ) {
        requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(databaseService.mappingUnitConversions(itemId, status, keyword)));
    }

    @PostMapping("/inventory-api/mapping/unit-conversions")
    public ApiResult<Map<String, Object>> saveMappingUnitConversions(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireAnyCapability(List.of("inventory:item:manage", "inventory:rule"));
        return ApiResult.of(200, "mapping unit conversions saved", databaseService.asMap(databaseService.saveMappingUnitConversions(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/packages")
    public ApiResult<Map<String, Object>> savePackage(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:rule");
        return ApiResult.of(200, "套餐草稿已保存", databaseService.asMap(databaseService.savePackage(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/packages/enable")
    public ApiResult<Map<String, Object>> enablePackage(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:rule");
        return ApiResult.of(200, "套餐已启用", databaseService.asMap(databaseService.enablePackage(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/packages/disable")
    public ApiResult<Map<String, Object>> disablePackage(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:rule");
        return ApiResult.of(200, "套餐已停用", databaseService.asMap(databaseService.disablePackage(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/consumption-events")
    public ApiResult<Map<String, Object>> consumeEncounter(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:retry");
        return ApiResult.of(200, "消耗事件已记录", databaseService.asMap(databaseService.consumeEncounter(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/consumption-events/retry")
    public ApiResult<Map<String, Object>> retryConsumption(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:retry");
        return ApiResult.of(200, "消耗事件已重试", databaseService.asMap(databaseService.retryConsumption(toJson(payload), user)));
    }

    private SessionUser requireCapability(String capability) {
        SessionUser user = InventoryPermission.currentUserOrThrow();
        if (!navigationService.hasCapability(user, capability)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前岗位无此库存操作权限");
        }
        return user;
    }

    private SessionUser requireAnyCapability(List<String> capabilities) {
        SessionUser user = InventoryPermission.currentUserOrThrow();
        boolean allowed = capabilities.stream().anyMatch(capability -> navigationService.hasCapability(user, capability));
        if (!allowed) {
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
