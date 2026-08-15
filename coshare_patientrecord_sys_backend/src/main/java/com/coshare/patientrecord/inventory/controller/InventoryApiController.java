package com.coshare.patientrecord.inventory.controller;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.AuthNavigationService;
import com.coshare.patientrecord.auth.service.InventoryAccessService;
import com.coshare.patientrecord.auth.service.InventoryPortalAccountAdminService;
import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.config.PortalMode;
import com.coshare.patientrecord.inventory.service.InventoryDatabaseService;
import com.coshare.patientrecord.inventory.service.InventoryMessageBoardService;
import com.coshare.patientrecord.inventory.service.InventoryQuotaGovernanceService;
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
import org.springframework.web.bind.annotation.PutMapping;
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
    private final InventoryPortalAccountAdminService portalAccountAdminService;
    private final InventoryMessageBoardService messageBoardService;
    private final InventoryQuotaGovernanceService quotaGovernanceService;
    private final PortalMode portalMode;

    public InventoryApiController(
        InventoryDatabaseService databaseService,
        ObjectMapper objectMapper,
        AuthNavigationService navigationService,
        InventoryAccessService inventoryAccessService,
        InventoryPortalAccountAdminService portalAccountAdminService,
        InventoryMessageBoardService messageBoardService,
        InventoryQuotaGovernanceService quotaGovernanceService,
        PortalMode portalMode
    ) {
        this.databaseService = databaseService;
        this.objectMapper = objectMapper;
        this.navigationService = navigationService;
        this.inventoryAccessService = inventoryAccessService;
        this.portalAccountAdminService = portalAccountAdminService;
        this.messageBoardService = messageBoardService;
        this.quotaGovernanceService = quotaGovernanceService;
        this.portalMode = portalMode;
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

    @GetMapping("/inventory-api/department-daily-drafts")
    public ApiResult<Map<String, Object>> departmentDailyDraft(
        @RequestParam String departmentKey,
        @RequestParam LocalDate date
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(databaseService.departmentDailyDraft(departmentKey, date, user)));
    }

    @GetMapping("/inventory-api/department-daily-drafts/summary")
    public ApiResult<Map<String, Object>> departmentDailyDraftSummary(@RequestParam LocalDate date) {
        SessionUser user = requireCapability("inventory:read");
        if (portalMode.isInventoryPortal()) requireCapability("inventory:role:manage");
        return ApiResult.success(databaseService.asMap(databaseService.departmentDailyDraftSummary(date, user)));
    }

    @GetMapping("/inventory-api/department-daily-drafts/admin-rollup")
    public ApiResult<Map<String, Object>> adminDepartmentDailyRollup(
        @RequestParam(required = false) LocalDate date,
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to
    ) {
        SessionUser user = requireCapability("inventory:role:manage");
        LocalDate[] period = resolveDailyRollupPeriod(date, from, to);
        return ApiResult.success(databaseService.asMap(databaseService.adminDepartmentDailyRollup(period[0], period[1], user)));
    }

    @GetMapping("/inventory-api/quota-governance")
    public ApiResult<Map<String, Object>> inventoryQuotaGovernance(@RequestParam(required = false) LocalDate date) {
        requireCapability("inventory:role:manage");
        return ApiResult.success(objectMapper.convertValue(quotaGovernanceService.governance(date), Map.class));
    }

    @PostMapping("/inventory-api/quota-governance/versions")
    public ApiResult<Map<String, Object>> createInventoryQuotaVersion(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:role:manage");
        return ApiResult.of(200, "定额版本已创建", objectMapper.convertValue(quotaGovernanceService.createVersion(toJson(payload), user), Map.class));
    }

    @PutMapping("/inventory-api/quota-governance/rules/{ruleId}")
    public ApiResult<Map<String, Object>> updateInventoryQuotaRule(
        @org.springframework.web.bind.annotation.PathVariable String ruleId,
        @RequestBody Map<String, Object> payload
    ) {
        requireCapability("inventory:role:manage");
        return ApiResult.of(200, "定额规则已更新", objectMapper.convertValue(quotaGovernanceService.updateRule(ruleId, toJson(payload)), Map.class));
    }

    @PutMapping("/inventory-api/quota-governance/special-rules")
    public ApiResult<Map<String, Object>> updateInventorySpecialMaterialRule(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:role:manage");
        return ApiResult.of(200, "特殊耗材规则已更新", objectMapper.convertValue(quotaGovernanceService.upsertSpecial(toJson(payload), user), Map.class));
    }

    @PutMapping("/inventory-api/quota-governance/reviews")
    public ApiResult<Map<String, Object>> saveInventoryQuotaReview(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:role:manage");
        return ApiResult.of(200, "复核记录已保存", objectMapper.convertValue(quotaGovernanceService.saveReview(toJson(payload), user), Map.class));
    }

    @GetMapping("/inventory-api/portal-accounts")
    public ApiResult<Map<String, Object>> inventoryPortalAccounts() {
        requireCapability("inventory:role:manage");
        return ApiResult.success(portalAccountAdminService.accounts());
    }

    @PutMapping("/inventory-api/portal-accounts/{accountId}")
    public ApiResult<Map<String, Object>> updateInventoryPortalAccount(
        @org.springframework.web.bind.annotation.PathVariable String accountId,
        @RequestBody Map<String, Object> payload
    ) {
        SessionUser user = requireCapability("inventory:role:manage");
        portalAccountAdminService.update(accountId, toJson(payload), user);
        return ApiResult.of(200, "进销存门户账号已更新", portalAccountAdminService.accounts());
    }

    @PostMapping("/inventory-api/portal-accounts/{accountId}/reset-password")
    public ApiResult<Map<String, Object>> resetInventoryPortalAccountPassword(
        @org.springframework.web.bind.annotation.PathVariable String accountId
    ) {
        requireCapability("inventory:role:manage");
        portalAccountAdminService.resetPassword(accountId);
        return ApiResult.of(200, "密码已重置为 123456，账号下次登录必须修改密码", portalAccountAdminService.accounts());
    }

    @GetMapping("/inventory-api/message-board/posts")
    public ApiResult<Map<String, Object>> inventoryMessageBoardPosts(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String departmentKey,
        @RequestParam(defaultValue = "false") boolean onlyMine,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(messageBoardService.posts(
            user, navigationService.hasCapability(user, "inventory:role:manage"), keyword, category, status, departmentKey, onlyMine, page, size
        ));
    }

    @GetMapping("/inventory-api/message-board/posts/{postId}")
    public ApiResult<Map<String, Object>> inventoryMessageBoardPost(
        @org.springframework.web.bind.annotation.PathVariable String postId
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(messageBoardService.postDetail(
            postId, user, navigationService.hasCapability(user, "inventory:role:manage")
        ));
    }

    @PostMapping("/inventory-api/message-board/posts")
    public ApiResult<Map<String, Object>> createInventoryMessageBoardPost(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "需求或建议已发布", messageBoardService.createPost(
            toJson(payload), user, navigationService.hasCapability(user, "inventory:role:manage")
        ));
    }

    @PutMapping("/inventory-api/message-board/posts/{postId}")
    public ApiResult<Map<String, Object>> updateInventoryMessageBoardPost(
        @org.springframework.web.bind.annotation.PathVariable String postId,
        @RequestBody Map<String, Object> payload
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "主题内容已更新", messageBoardService.updatePost(
            postId, toJson(payload), user, navigationService.hasCapability(user, "inventory:role:manage")
        ));
    }

    @PostMapping("/inventory-api/message-board/posts/{postId}/withdraw")
    public ApiResult<Map<String, Object>> withdrawInventoryMessageBoardPost(
        @org.springframework.web.bind.annotation.PathVariable String postId
    ) {
        SessionUser user = requireCapability("inventory:read");
        messageBoardService.withdrawPost(postId, user);
        return ApiResult.of(200, "主题已撤回", Map.of("id", postId, "withdrawn", true));
    }

    @PostMapping("/inventory-api/message-board/posts/{postId}/replies")
    public ApiResult<Map<String, Object>> createInventoryMessageBoardReply(
        @org.springframework.web.bind.annotation.PathVariable String postId,
        @RequestBody Map<String, Object> payload
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "回复已发布", messageBoardService.createReply(
            postId, toJson(payload), user, navigationService.hasCapability(user, "inventory:role:manage")
        ));
    }

    @PutMapping("/inventory-api/message-board/replies/{replyId}")
    public ApiResult<Map<String, Object>> updateInventoryMessageBoardReply(
        @org.springframework.web.bind.annotation.PathVariable String replyId,
        @RequestBody Map<String, Object> payload
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "回复已更新", messageBoardService.updateReply(
            replyId, toJson(payload), user, navigationService.hasCapability(user, "inventory:role:manage")
        ));
    }

    @PostMapping("/inventory-api/message-board/replies/{replyId}/withdraw")
    public ApiResult<Map<String, Object>> withdrawInventoryMessageBoardReply(
        @org.springframework.web.bind.annotation.PathVariable String replyId
    ) {
        SessionUser user = requireCapability("inventory:read");
        messageBoardService.withdrawReply(replyId, user);
        return ApiResult.of(200, "回复已撤回", Map.of("id", replyId, "withdrawn", true));
    }

    @PutMapping("/inventory-api/message-board/admin/posts/{postId}/status")
    public ApiResult<Map<String, Object>> updateInventoryMessageBoardStatus(
        @org.springframework.web.bind.annotation.PathVariable String postId,
        @RequestBody Map<String, Object> payload
    ) {
        SessionUser user = requireCapability("inventory:role:manage");
        return ApiResult.of(200, "处理状态已更新", messageBoardService.updateStatus(postId, toJson(payload), user));
    }

    @PutMapping("/inventory-api/message-board/admin/posts/{postId}/pin")
    public ApiResult<Map<String, Object>> updateInventoryMessageBoardPinned(
        @org.springframework.web.bind.annotation.PathVariable String postId,
        @RequestBody Map<String, Object> payload
    ) {
        SessionUser user = requireCapability("inventory:role:manage");
        return ApiResult.of(200, "置顶状态已更新", messageBoardService.updatePinned(postId, toJson(payload).path("pinned").asBoolean(false), user));
    }

    @PutMapping("/inventory-api/message-board/admin/posts/{postId}/visibility")
    public ApiResult<Map<String, Object>> updateInventoryMessageBoardPostVisibility(
        @org.springframework.web.bind.annotation.PathVariable String postId,
        @RequestBody Map<String, Object> payload
    ) {
        SessionUser user = requireCapability("inventory:role:manage");
        return ApiResult.of(200, "主题可见性已更新", messageBoardService.updatePostVisibility(postId, toJson(payload).path("hidden").asBoolean(false), user));
    }

    @PutMapping("/inventory-api/message-board/admin/replies/{replyId}/visibility")
    public ApiResult<Map<String, Object>> updateInventoryMessageBoardReplyVisibility(
        @org.springframework.web.bind.annotation.PathVariable String replyId,
        @RequestBody Map<String, Object> payload
    ) {
        SessionUser user = requireCapability("inventory:role:manage");
        return ApiResult.of(200, "回复可见性已更新", messageBoardService.updateReplyVisibility(replyId, toJson(payload).path("hidden").asBoolean(false), user));
    }

    @GetMapping("/inventory-api/message-board/admin/audit-logs")
    public ApiResult<Map<String, Object>> inventoryMessageBoardAuditLogs(
        @RequestParam(required = false) String targetType,
        @RequestParam(required = false) String targetId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        requireCapability("inventory:role:manage");
        return ApiResult.success(messageBoardService.auditLogs(targetType, targetId, page, size));
    }

    @GetMapping("/inventory-api/department-daily-drafts/admin-rollup/export")
    public ResponseEntity<byte[]> exportAdminDepartmentDailyRollup(
        @RequestParam(required = false) LocalDate date,
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to
    ) {
        SessionUser user = requireCapability("inventory:role:manage");
        LocalDate[] period = resolveDailyRollupPeriod(date, from, to);
        return attachment(
            databaseService.exportAdminDepartmentDailyRollup(period[0], period[1], user),
            "管理员12科室耗材日报-" + periodFilename(period) + ".csv",
            "text/csv;charset=UTF-8"
        );
    }

    @GetMapping("/inventory-api/department-daily-drafts/admin-rollup/export.xlsx")
    public ResponseEntity<byte[]> exportAdminDepartmentDailyRollupXlsx(
        @RequestParam(required = false) LocalDate date,
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to
    ) {
        SessionUser user = requireCapability("inventory:role:manage");
        LocalDate[] period = resolveDailyRollupPeriod(date, from, to);
        return attachment(
            databaseService.exportAdminDepartmentDailyRollupXlsx(period[0], period[1], user),
            "管理员12科室耗材日报-" + periodFilename(period) + ".xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        );
    }

    @PutMapping("/inventory-api/department-daily-drafts")
    public ApiResult<Map<String, Object>> saveDepartmentDailyDraft(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "科室耗材日草稿已保存", databaseService.asMap(databaseService.saveDepartmentDailyDraft(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/department-daily-drafts/export/{kind}")
    public ResponseEntity<byte[]> exportDepartmentDailyDraft(
        @org.springframework.web.bind.annotation.PathVariable String kind,
        @RequestBody Map<String, Object> payload
    ) {
        SessionUser user = requireCapability("inventory:export");
        if (!"details".equals(kind) && !"summary".equals(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的科室日核算导出类型");
        }
        return attachment(
            databaseService.exportDepartmentDailyDraft(kind, toJson(payload), user),
            "department-daily-" + kind + ".csv",
            "text/csv;charset=UTF-8"
        );
    }

    @GetMapping("/inventory-api/department-period-reports")
    public ApiResult<Map<String, Object>> departmentPeriodReport(
        @RequestParam String departmentKey,
        @RequestParam String periodType,
        @RequestParam LocalDate anchorDate
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(databaseService.departmentPeriodReport(departmentKey, periodType, anchorDate, user)));
    }

    @GetMapping("/inventory-api/department-period-reports/export")
    public ResponseEntity<byte[]> exportDepartmentPeriodReport(
        @RequestParam String departmentKey,
        @RequestParam String periodType,
        @RequestParam LocalDate anchorDate,
        @RequestParam(defaultValue = "xlsx") String format
    ) {
        SessionUser user = requireCapability("inventory:export");
        var file = databaseService.exportDepartmentPeriodReport(departmentKey, periodType, anchorDate, format, user);
        return attachment(file.body(), file.filename(), file.mediaType());
    }

    @GetMapping("/inventory-api/department-allocation-plans")
    public ApiResult<Map<String, Object>> departmentAllocationPlan(
        @RequestParam String departmentKey,
        @RequestParam String month,
        @RequestParam(required = false) LocalDate throughDate
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(databaseService.departmentAllocationPlan(departmentKey, month, throughDate, user)));
    }

    @PutMapping("/inventory-api/department-allocation-plans")
    public ApiResult<Map<String, Object>> saveDepartmentAllocationPlan(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "Department allocation plan saved without inventory movement", databaseService.asMap(databaseService.saveDepartmentAllocationPlan(toJson(payload), user)));
    }

    @GetMapping("/inventory-api/patient-consumption-drafts/detail")
    public ApiResult<Map<String, Object>> patientConsumptionDraft(@RequestParam String id) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(databaseService.patientConsumptionDraft(id, user)));
    }

    @GetMapping("/inventory-api/patient-consumption-drafts")
    public ApiResult<Map<String, Object>> patientConsumptionDrafts(
        @RequestParam(required = false) String departmentKey,
        @RequestParam(required = false) LocalDate date,
        @RequestParam(required = false) String patientId
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(databaseService.patientConsumptionDrafts(departmentKey, date, patientId, user)));
    }

    @PutMapping("/inventory-api/patient-consumption-drafts")
    public ApiResult<Map<String, Object>> savePatientConsumptionDraft(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "患者耗用草稿已保存", databaseService.asMap(databaseService.savePatientConsumptionDraft(toJson(payload), user)));
    }

    @GetMapping("/inventory-api/patient-consumption-drafts/export/{kind}")
    public ResponseEntity<byte[]> exportPatientConsumptionDrafts(
        @org.springframework.web.bind.annotation.PathVariable String kind,
        @RequestParam(required = false) String departmentKey,
        @RequestParam(required = false) LocalDate date
    ) {
        SessionUser user = requireCapability("inventory:export");
        if (!"details".equals(kind) && !"summary".equals(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的患者耗用导出类型");
        }
        return attachment(
            databaseService.exportPatientConsumptionDrafts(kind, departmentKey, date, user),
            "patient-consumption-" + kind + ".csv",
            "text/csv;charset=UTF-8"
        );
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

    private static LocalDate[] resolveDailyRollupPeriod(LocalDate date, LocalDate from, LocalDate to) {
        if (date != null && (from != null || to != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期参数不能混用");
        }
        if (date != null) return new LocalDate[] { date, date };
        if (from == null || to == null || from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供有效的查询日期范围");
        }
        return new LocalDate[] { from, to };
    }

    private static String periodFilename(LocalDate[] period) {
        return period[0].equals(period[1]) ? period[0].toString() : period[0] + "至" + period[1];
    }

    private static ResponseEntity<byte[]> attachment(byte[] body, String filename, String mediaType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mediaType));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename, java.nio.charset.StandardCharsets.UTF_8).build());
        headers.setContentLength(body.length);
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
