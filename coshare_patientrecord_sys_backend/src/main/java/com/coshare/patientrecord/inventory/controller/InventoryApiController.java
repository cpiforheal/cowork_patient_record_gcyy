package com.coshare.patientrecord.inventory.controller;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.AuthNavigationService;
import com.coshare.patientrecord.auth.service.InventoryAccessService;
import com.coshare.patientrecord.auth.service.InventoryPortalAccountAdminService;
import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.config.PortalMode;
import com.coshare.patientrecord.inventory.service.InventoryAdminReportService;
import com.coshare.patientrecord.inventory.service.InventoryAdminReviewService;
import com.coshare.patientrecord.inventory.service.InventoryDatabaseService;
import com.coshare.patientrecord.inventory.service.InventoryDayCloseService;
import com.coshare.patientrecord.inventory.service.InventoryDepartmentDailyReportService;
import com.coshare.patientrecord.inventory.service.InventoryDepartmentDraftService;
import com.coshare.patientrecord.inventory.service.InventoryFollowupService;
import com.coshare.patientrecord.inventory.service.InventoryInboundService;
import com.coshare.patientrecord.inventory.service.InventoryMessageBoardService;
import com.coshare.patientrecord.inventory.service.InventoryOpeningImportService;
import com.coshare.patientrecord.inventory.service.InventoryPatientConsumptionReviewService;
import com.coshare.patientrecord.inventory.service.InventoryPatientConsumptionService;
import com.coshare.patientrecord.inventory.service.InventoryQuotaGovernanceService;
import com.coshare.patientrecord.inventory.service.InventoryStockOverviewService;
import com.coshare.patientrecord.security.InventoryPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.List;
import java.time.LocalDate;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
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
    private final InventoryDepartmentDraftService departmentDraftService;
    private final InventoryPatientConsumptionService patientConsumptionService;
    private final InventoryPatientConsumptionReviewService patientConsumptionReviewService;
    private final InventoryDepartmentDailyReportService departmentDailyReportService;
    private final InventoryDayCloseService dayCloseService;
    private final InventoryAdminReviewService adminReviewService;
    private final InventoryAdminReportService adminReportService;
    private final InventoryFollowupService followupService;
    private final InventoryInboundService inboundService;
    private final InventoryOpeningImportService openingImportService;
    private final InventoryStockOverviewService stockOverviewService;
    private final PortalMode portalMode;

    public InventoryApiController(
        InventoryDatabaseService databaseService,
        ObjectMapper objectMapper,
        AuthNavigationService navigationService,
        InventoryAccessService inventoryAccessService,
        InventoryPortalAccountAdminService portalAccountAdminService,
        InventoryMessageBoardService messageBoardService,
        InventoryQuotaGovernanceService quotaGovernanceService,
        InventoryDepartmentDraftService departmentDraftService,
        InventoryPatientConsumptionService patientConsumptionService,
        InventoryPatientConsumptionReviewService patientConsumptionReviewService,
        InventoryDepartmentDailyReportService departmentDailyReportService,
        InventoryDayCloseService dayCloseService,
        InventoryAdminReviewService adminReviewService,
        InventoryAdminReportService adminReportService,
        InventoryFollowupService followupService,
        InventoryInboundService inboundService,
        InventoryOpeningImportService openingImportService,
        InventoryStockOverviewService stockOverviewService,
        PortalMode portalMode
    ) {
        this.databaseService = databaseService;
        this.objectMapper = objectMapper;
        this.navigationService = navigationService;
        this.inventoryAccessService = inventoryAccessService;
        this.portalAccountAdminService = portalAccountAdminService;
        this.messageBoardService = messageBoardService;
        this.quotaGovernanceService = quotaGovernanceService;
        this.departmentDraftService = departmentDraftService;
        this.patientConsumptionService = patientConsumptionService;
        this.patientConsumptionReviewService = patientConsumptionReviewService;
        this.departmentDailyReportService = departmentDailyReportService;
        this.dayCloseService = dayCloseService;
        this.adminReviewService = adminReviewService;
        this.adminReportService = adminReportService;
        this.followupService = followupService;
        this.inboundService = inboundService;
        this.openingImportService = openingImportService;
        this.stockOverviewService = stockOverviewService;
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

    @GetMapping("/inventory-api/department-daily-drafts/history")
    public ApiResult<Map<String, Object>> departmentDailyDraftHistory(
        @RequestParam String departmentKey,
        @RequestParam LocalDate from,
        @RequestParam LocalDate to
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(databaseService.asMap(databaseService.departmentDailyDraftHistory(departmentKey, from, to, user)));
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

    @GetMapping("/inventory-api/department-daily-reports")
    public ApiResult<Map<String, Object>> departmentDailyReport(
        @RequestParam String departmentKey,
        @RequestParam(required = false) LocalDate date
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(asMap(departmentDailyReportService.read(departmentKey, date, user)));
    }

    @GetMapping("/inventory-api/department-daily-reports/calendar")
    public ApiResult<Map<String, Object>> departmentDailyReportCalendar(
        @RequestParam String departmentKey,
        @RequestParam(required = false) String month
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(asMap(departmentDailyReportService.calendar(departmentKey, month, user)));
    }

    @PutMapping("/inventory-api/department-daily-reports")
    public ApiResult<Map<String, Object>> saveDepartmentDailyReport(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "科室日报已保存", asMap(departmentDailyReportService.save(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/department-daily-reports/{reportId}/submit")
    public ApiResult<Map<String, Object>> submitDepartmentDailyReport(
        @org.springframework.web.bind.annotation.PathVariable String reportId
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "科室日报已提交", asMap(departmentDailyReportService.submit(reportId, user)));
    }

    @PostMapping("/inventory-api/department-daily-reports/{reportId}/zero-use/withdraw")
    public ApiResult<Map<String, Object>> withdrawDepartmentZeroUse(
        @org.springframework.web.bind.annotation.PathVariable String reportId
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "零使用日已撤回", asMap(departmentDailyReportService.withdrawZeroUse(reportId, user)));
    }

    @GetMapping("/inventory-api/department-daily-reports/{reportId}/export/{format}")
    public ResponseEntity<byte[]> exportDepartmentDailyReport(
        @org.springframework.web.bind.annotation.PathVariable String reportId,
        @org.springframework.web.bind.annotation.PathVariable String format
    ) {
        SessionUser user = requireCapability("inventory:export");
        boolean xlsx = "xlsx".equalsIgnoreCase(format);
        return attachment(
            departmentDailyReportService.export(reportId, format, user),
            "department-daily-report-" + reportId + (xlsx ? ".xlsx" : ".csv"),
            xlsx ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "text/csv;charset=UTF-8"
        );
    }

    @GetMapping("/inventory-api/quota-governance")
    public ApiResult<Map<String, Object>> inventoryQuotaGovernance(
        @RequestParam(required = false) LocalDate date,
        @RequestParam(required = false) String versionId
    ) {
        requireCapability("inventory:role:manage");
        return ApiResult.success(objectMapper.convertValue(quotaGovernanceService.governance(date, versionId), Map.class));
    }

    @GetMapping("/inventory-api/quota-governance/mapping-worklist")
    public ApiResult<Map<String, Object>> inventoryQuotaMappingWorklist(
        @RequestParam(required = false) String departmentKey,
        @RequestParam(required = false) String keyword
    ) {
        requireCapability("inventory:rule");
        return ApiResult.success(asMap(quotaGovernanceService.mappingWorklist(departmentKey, keyword)));
    }

    @PutMapping("/inventory-api/quota-governance/mapping-worklist")
    public ApiResult<Map<String, Object>> saveInventoryQuotaMappings(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:rule");
        return ApiResult.of(200, "物资映射已保存", asMap(quotaGovernanceService.saveMappings(toJson(payload), user)));
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
        SessionUser user = requireCapability("inventory:role:manage");
        return ApiResult.of(200, "定额规则已更新", objectMapper.convertValue(quotaGovernanceService.updateRule(ruleId, toJson(payload), user), Map.class));
    }

    @PostMapping("/inventory-api/quota-governance/rules")
    public ApiResult<Map<String, Object>> createInventoryQuotaRule(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:role:manage");
        return ApiResult.of(200, "定额规则已新增", objectMapper.convertValue(quotaGovernanceService.createRule(toJson(payload), user), Map.class));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/inventory-api/quota-governance/rules/{ruleId}")
    public ApiResult<Map<String, Object>> deleteInventoryQuotaRule(
        @org.springframework.web.bind.annotation.PathVariable String ruleId
    ) {
        SessionUser user = requireCapability("inventory:role:manage");
        return ApiResult.of(200, "定额规则已删除", objectMapper.convertValue(quotaGovernanceService.deleteRule(ruleId, user), Map.class));
    }

    @PutMapping("/inventory-api/quota-governance/rules/batch")
    public ApiResult<Map<String, Object>> updateInventoryQuotaRulesBatch(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:role:manage");
        return ApiResult.of(200, "定额规则已批量保存", objectMapper.convertValue(quotaGovernanceService.updateRulesBatch(toJson(payload), user), Map.class));
    }

    @GetMapping("/inventory-api/quota-governance/audit-log")
    public ApiResult<Map<String, Object>> inventoryQuotaAuditLog(
        @RequestParam(required = false) String versionId,
        @RequestParam(required = false, defaultValue = "200") Integer limit
    ) {
        requireCapability("inventory:role:manage");
        ObjectNode result = objectMapper.createObjectNode();
        result.set("list", quotaGovernanceService.auditLog(versionId, limit == null ? 200 : limit));
        return ApiResult.success(objectMapper.convertValue(result, Map.class));
    }

    @PostMapping("/inventory-api/quota-governance/console-save")
    public ApiResult<Map<String, Object>> consoleSaveInventoryQuota(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:role:manage");
        JsonNode request = toJson(payload);
        ObjectNode result = quotaGovernanceService.consoleSave(request, user);
        if (request.path("applyToday").asBoolean(false) && result.hasNonNull("savedVersionId")) {
            result.set("applyTodayResult", departmentDraftService.applyVersionToday(result.get("savedVersionId").asText(), user));
        }
        return ApiResult.of(200, "定额控制台已保存", objectMapper.convertValue(result, Map.class));
    }

    @PostMapping("/inventory-api/quota-governance/apply-today")
    public ApiResult<Map<String, Object>> applyInventoryQuotaToday(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:role:manage");
        return ApiResult.of(200, "定额已应用到当日未填报科室", objectMapper.convertValue(departmentDraftService.applyVersionToday(toJson(payload).path("versionId").asText(""), user), Map.class));
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

    @GetMapping("/inventory-api/quota-governance/export.xlsx")
    public ResponseEntity<byte[]> exportInventoryQuotaGovernanceXlsx(
        @RequestParam(required = false) LocalDate date,
        @RequestParam(required = false) String versionId
    ) {
        SessionUser user = requireCapability("inventory:role:manage");
        return attachment(
            quotaGovernanceService.exportGovernanceXlsx(date, versionId, user),
            "全院耗材每人次定额总表-" + java.time.LocalDate.now() + ".xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        );
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

    @GetMapping("/inventory-api/patient-workbench")
    public ApiResult<Map<String, Object>> patientWorkbench(
        @RequestParam String departmentKey,
        @RequestParam(required = false) LocalDate date
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(asMap(patientConsumptionService.workbench(departmentKey, date, user)));
    }

    @GetMapping("/inventory-api/patient-workbench/detail")
    public ApiResult<Map<String, Object>> patientWorkbenchDetail(@RequestParam String serviceInstanceId) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(asMap(patientConsumptionService.document(serviceInstanceId, user)));
    }

    @GetMapping("/inventory-api/patient-workbench/patients")
    public ApiResult<Map<String, Object>> patientWorkbenchPatients(
        @RequestParam(required = false) String keyword
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(asMap(patientConsumptionService.searchPatients(keyword, user)));
    }

    @PostMapping("/inventory-api/patient-workbench/manual-patients")
    public ApiResult<Map<String, Object>> addManualPatient(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "患者已加入本科室工作台", asMap(patientConsumptionService.manualAdd(toJson(payload), user)));
    }

    @GetMapping("/inventory-api/patient-workbench/fixed-consumables")
    public ApiResult<Map<String, Object>> fixedConsumables(
        @RequestParam String departmentKey,
        @RequestParam(required = false) LocalDate date
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(asMap(departmentDailyReportService.fixedConsumables(departmentKey, date, user)));
    }

    @GetMapping("/inventory-api/patient-workbench/fixed-consumables/history")
    public ApiResult<Map<String, Object>> fixedConsumablesHistory(
        @RequestParam String departmentKey,
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(asMap(departmentDailyReportService.fixedHistory(departmentKey, from, to, user)));
    }

    @GetMapping("/inventory-api/patient-workbench/followup-appointments")
    public ApiResult<Map<String, Object>> followupAppointments(
        @RequestParam(required = false) String departmentKey,
        @RequestParam(required = false) LocalDate date
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(asMap(followupService.appointments(departmentKey, date, user)));
    }

    @PostMapping("/inventory-api/patient-workbench/followup-appointments/{recheckId}/arrive")
    public ApiResult<Map<String, Object>> arriveFollowupAppointment(
        @org.springframework.web.bind.annotation.PathVariable String recheckId,
        @RequestBody Map<String, Object> payload
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "复诊患者已到院", asMap(followupService.arrive(recheckId, toJson(payload), user)));
    }

    @PutMapping("/inventory-api/patient-workbench/day-status")
    public ApiResult<Map<String, Object>> setPatientDayStatus(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "患者状态已更新", asMap(patientConsumptionService.setDayStatuses(toJson(payload), user)));
    }

    @PutMapping("/inventory-api/patient-workbench/day-status/restore")
    public ApiResult<Map<String, Object>> restorePatientDayStatus(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "患者已恢复待录", asMap(patientConsumptionService.restoreDayStatuses(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/patient-workbench/zero-use")
    public ApiResult<Map<String, Object>> batchZeroUse(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "已标记未使用", asMap(patientConsumptionService.batchZeroUse(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/patient-workbench/zero-use/withdraw")
    public ApiResult<Map<String, Object>> withdrawZeroUse(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "已撤回未使用", asMap(patientConsumptionService.withdrawZeroUse(toJson(payload), user)));
    }

    @PutMapping("/inventory-api/patient-workbench/documents")
    public ApiResult<Map<String, Object>> savePatientWorkbenchDocument(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "患者耗材清单已保存", asMap(patientConsumptionService.saveDraft(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/patient-workbench/documents/{documentId}/submit")
    public ApiResult<Map<String, Object>> submitPatientWorkbenchDocument(
        @org.springframework.web.bind.annotation.PathVariable String documentId
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "患者耗材清单已提交审核", asMap(patientConsumptionService.submit(documentId, user)));
    }

    @PostMapping("/inventory-api/patient-workbench/batches/submit")
    public ApiResult<Map<String, Object>> submitPatientWorkbenchBatch(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:read");
        String departmentKey = String.valueOf(payload.getOrDefault("departmentKey", ""));
        String dateText = String.valueOf(payload.getOrDefault("businessDate", ""));
        LocalDate date = dateText == null || dateText.isBlank() ? null : LocalDate.parse(dateText);
        return ApiResult.of(200, "批量提交已处理", asMap(patientConsumptionService.submitBatch(departmentKey, date, user)));
    }

    @GetMapping("/inventory-api/patient-workbench/recent-items")
    public ApiResult<Map<String, Object>> patientWorkbenchRecentItems(
        @RequestParam String departmentKey,
        @RequestParam(defaultValue = "5") int limit
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(asMap(patientConsumptionService.recentItems(departmentKey, limit, user)));
    }

    @GetMapping("/inventory-api/patient-workbench/last-combination")
    public ApiResult<Map<String, Object>> patientWorkbenchLastCombination(
        @RequestParam String patientId,
        @RequestParam String departmentKey
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(asMap(patientConsumptionService.lastCombination(patientId, departmentKey, user)));
    }

    @GetMapping("/inventory-api/patient-workbench/patient-source")
    public ApiResult<Map<String, Object>> patientWorkbenchSource(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String visitType,
        @RequestParam(required = false) LocalDate date,
        @RequestParam(required = false) String department,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "30") int size
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(asMap(patientConsumptionService.patientSource(keyword, visitType, date, department, page, size, user)));
    }

    @PutMapping("/inventory-api/patient-workbench/notes")
    public ApiResult<Map<String, Object>> savePatientWorkbenchNote(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "患者备注已保存", asMap(patientConsumptionService.updateInstanceNote(toJson(payload), user)));
    }

    @GetMapping("/inventory-api/patient-workbench/packages/all")
    public ApiResult<Map<String, Object>> allPatientWorkbenchPackages() {
        requireCapability("inventory:role:manage");
        return ApiResult.success(asMap(patientConsumptionService.allPackages()));
    }

    @GetMapping("/inventory-api/admin-review/anomalies")
    public ApiResult<Map<String, Object>> adminReviewAnomalies(
        @RequestParam(required = false) LocalDate date,
        @RequestParam(defaultValue = "1") int range,
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String departmentKey
    ) {
        requireCapability("inventory:role:manage");
        return ApiResult.success(asMap(adminReviewService.anomalies(date, range, type, departmentKey)));
    }

    @GetMapping("/inventory-api/admin-review/data-quality")
    public ApiResult<Map<String, Object>> adminReviewDataQuality() {
        requireCapability("inventory:role:manage");
        return ApiResult.success(asMap(adminReviewService.dataQuality()));
    }

    @GetMapping("/inventory-api/admin-review/matrix")
    public ApiResult<Map<String, Object>> adminReviewMatrix(
        @RequestParam(required = false) String departmentKey,
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to,
        @RequestParam(defaultValue = "QUANTITY") String metric
    ) {
        requireCapability("inventory:role:manage");
        return ApiResult.success(asMap(adminReviewService.matrix(departmentKey, from, to, metric)));
    }

    @GetMapping("/inventory-api/admin-review/trends")
    public ApiResult<Map<String, Object>> adminReviewTrends(
        @RequestParam(required = false) LocalDate periodStart,
        @RequestParam(required = false) LocalDate periodEnd,
        @RequestParam(required = false) String departmentKey
    ) {
        requireCapability("inventory:role:manage");
        return ApiResult.success(asMap(adminReviewService.trends(periodStart, periodEnd, departmentKey)));
    }

    @GetMapping("/inventory-api/admin-review/thresholds")
    public ApiResult<Map<String, Object>> adminReviewThresholds() {
        requireCapability("inventory:role:manage");
        return ApiResult.success(asMap(adminReviewService.thresholds()));
    }

    @PutMapping("/inventory-api/admin-review/thresholds")
    public ApiResult<Map<String, Object>> saveAdminReviewThresholds(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:role:manage");
        return ApiResult.of(200, "审查阈值已保存", asMap(adminReviewService.saveThresholds(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/admin-review/reports")
    public ApiResult<Map<String, Object>> generateAdminReviewReport(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:role:manage");
        return ApiResult.of(200, "报表快照已生成", asMap(adminReportService.generate(toJson(payload), user)));
    }

    @GetMapping("/inventory-api/admin-review/reports")
    public ApiResult<Map<String, Object>> adminReviewReports() {
        requireCapability("inventory:role:manage");
        return ApiResult.success(asMap(adminReportService.list()));
    }

    @GetMapping("/inventory-api/admin-review/reports/{reportId}")
    public ApiResult<Map<String, Object>> adminReviewReport(
        @org.springframework.web.bind.annotation.PathVariable String reportId
    ) {
        requireCapability("inventory:role:manage");
        return ApiResult.success(asMap(adminReportService.detail(reportId)));
    }

    @GetMapping("/inventory-api/admin-review/reports/{reportId}/export/{format}")
    public ResponseEntity<byte[]> exportAdminReviewReport(
        @org.springframework.web.bind.annotation.PathVariable String reportId,
        @org.springframework.web.bind.annotation.PathVariable String format
    ) {
        requireCapability("inventory:role:manage");
        boolean xlsx = "xlsx".equalsIgnoreCase(format);
        boolean pdf = "pdf".equalsIgnoreCase(format);
        String extension = xlsx ? ".xlsx" : pdf ? ".pdf" : ".csv";
        String media = xlsx ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            : pdf ? "application/pdf" : "text/csv;charset=UTF-8";
        return attachment(adminReportService.export(reportId, format),
            "admin-review-report-" + reportId + extension, media);
    }

    @GetMapping("/inventory-api/consumable-usage")
    public ApiResult<Map<String, Object>> consumableUsage(
        @RequestParam(required = false) LocalDate date,
        @RequestParam(required = false) String departmentKey,
        @RequestParam(required = false) String itemId
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(asMap(dayCloseService.usage(date, departmentKey, itemId, user)));
    }

    @PostMapping("/inventory-api/consumable-usage/close")
    public ApiResult<Map<String, Object>> closeConsumableUsageDay(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "日结已处理", asMap(dayCloseService.close(toJson(payload), user)));
    }

    @GetMapping("/inventory-api/consumable-usage/export/{format}")
    public ResponseEntity<byte[]> exportConsumableUsageDay(
        @org.springframework.web.bind.annotation.PathVariable String format,
        @RequestParam(required = false) LocalDate date,
        @RequestParam(required = false) String departmentKey
    ) {
        SessionUser user = requireCapability("inventory:export");
        boolean xlsx = "xlsx".equalsIgnoreCase(format);
        LocalDate businessDate = date == null ? LocalDate.now() : date;
        return attachment(
            dayCloseService.export(businessDate, departmentKey, format, user),
            "consumable-day-" + businessDate + (xlsx ? ".xlsx" : ".csv"),
            xlsx ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "text/csv;charset=UTF-8"
        );
    }

    @GetMapping("/inventory-api/patient-workbench/packages")
    public ApiResult<Map<String, Object>> patientWorkbenchPackages(@RequestParam String departmentKey) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(asMap(patientConsumptionService.packages(departmentKey, user)));
    }

    @PostMapping("/inventory-api/patient-workbench/packages")
    public ApiResult<Map<String, Object>> savePatientWorkbenchPackage(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "患者耗材套餐已保存", asMap(patientConsumptionService.savePackage(toJson(payload), user)));
    }

    @DeleteMapping("/inventory-api/patient-workbench/packages/{packageId}")
    public ApiResult<Map<String, Object>> deletePatientWorkbenchPackage(
        @org.springframework.web.bind.annotation.PathVariable String packageId
    ) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.of(200, "患者耗材套餐已删除", asMap(patientConsumptionService.deletePackage(packageId, user)));
    }

    @PostMapping("/inventory-api/patient-workbench/clinical-events")
    public ApiResult<Map<String, Object>> enrollClinicalEvent(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:rule");
        return ApiResult.of(200, "临床服务事件已进入患者工作台", asMap(patientConsumptionService.enrollClinicalEvent(toJson(payload), user)));
    }

    @GetMapping("/inventory-api/runtime-settings")
    public ApiResult<Map<String, Object>> runtimeSettings() {
        requireCapability("inventory:read");
        return ApiResult.success(asMap(patientConsumptionService.runtimeSettings()));
    }

    @PutMapping("/inventory-api/runtime-settings/cutover-date")
    public ApiResult<Map<String, Object>> saveCutoverDate(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:role:manage");
        return ApiResult.of(200, "切换日期已保存", asMap(patientConsumptionService.saveCutoverDate(String.valueOf(payload.getOrDefault("date", "")), user)));
    }

    @GetMapping("/inventory-api/patient-trace")
    public ApiResult<Map<String, Object>> patientTrace(@RequestParam String patientId) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(asMap(patientConsumptionService.trace(patientId, user)));
    }

    @GetMapping("/inventory-api/patient-dashboard")
    public ApiResult<Map<String, Object>> patientDashboard(@RequestParam(required = false) LocalDate date) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(asMap(patientConsumptionService.dashboard(date, user)));
    }

    @GetMapping("/inventory-api/patient-review/batches")
    public ApiResult<Map<String, Object>> patientReviewBatches(
        @RequestParam(required = false) LocalDate date,
        @RequestParam(required = false) String departmentKey,
        @RequestParam(required = false) String status
    ) {
        SessionUser user = requireCapability("inventory:approve");
        return ApiResult.success(asMap(patientConsumptionReviewService.batches(date, departmentKey, status, user)));
    }

    @GetMapping("/inventory-api/patient-review/batches/{batchId}")
    public ApiResult<Map<String, Object>> patientReviewBatch(
        @org.springframework.web.bind.annotation.PathVariable String batchId
    ) {
        SessionUser user = requireCapability("inventory:approve");
        return ApiResult.success(asMap(patientConsumptionReviewService.batch(batchId, user)));
    }

    @PostMapping("/inventory-api/patient-review/batches/{batchId}/decisions")
    public ApiResult<Map<String, Object>> patientReviewDecisions(
        @org.springframework.web.bind.annotation.PathVariable String batchId,
        @RequestBody Map<String, Object> payload
    ) {
        SessionUser user = requireCapability("inventory:approve");
        return ApiResult.of(200, "审核已处理", asMap(patientConsumptionReviewService.decide(batchId, toJson(payload), user)));
    }

    @PostMapping("/inventory-api/patient-review/batches/{batchId}/retry-deductions")
    public ApiResult<Map<String, Object>> patientReviewRetryDeductions(
        @org.springframework.web.bind.annotation.PathVariable String batchId
    ) {
        SessionUser user = requireCapability("inventory:approve");
        return ApiResult.of(200, "库存补扣已处理", asMap(patientConsumptionReviewService.retryDeductions(batchId, user)));
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

    @GetMapping("/inventory-api/warehouse/inbound-orders")
    public ApiResult<Map<String, Object>> inboundOrders(
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to,
        @RequestParam(required = false) String supplier,
        @RequestParam(required = false) String status
    ) {
        requireCapability("inventory:issue");
        return ApiResult.success(asMap(inboundService.listOrders(from, to, supplier, status)));
    }

    @GetMapping("/inventory-api/warehouse/inbound-orders/{orderId}")
    public ApiResult<Map<String, Object>> inboundOrder(@org.springframework.web.bind.annotation.PathVariable String orderId) {
        requireCapability("inventory:issue");
        return ApiResult.success(asMap(inboundService.getOrder(orderId)));
    }

    @PostMapping("/inventory-api/warehouse/inbound-orders")
    public ApiResult<Map<String, Object>> createInboundOrder(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:issue");
        return ApiResult.of(200, "入库单已保存", asMap(inboundService.createOrder(toJson(payload), user)));
    }

    @PostMapping("/inventory-api/warehouse/inbound-orders/{orderId}/reverse")
    public ApiResult<Map<String, Object>> reverseInboundOrder(
        @org.springframework.web.bind.annotation.PathVariable String orderId,
        @RequestBody Map<String, Object> payload
    ) {
        SessionUser user = requireCapability("inventory:issue");
        String reason = payload == null ? "" : String.valueOf(payload.getOrDefault("reason", "")).trim();
        return ApiResult.of(200, "入库单已红冲", asMap(inboundService.reverseOrder(orderId, reason, user)));
    }

    @GetMapping("/inventory-api/warehouse/suppliers")
    public ApiResult<List<String>> inboundSuppliers() {
        requireCapability("inventory:issue");
        return ApiResult.success(objectMapper.convertValue(inboundService.supplierSuggestions(), List.class));
    }

    @GetMapping("/inventory-api/item-options")
    public ApiResult<java.util.List<Map<String, Object>>> inventoryItemOptions() {
        requireCapability("inventory:read");
        return ApiResult.success(objectMapper.convertValue(stockOverviewService.itemOptions(), java.util.List.class));
    }

    @GetMapping("/inventory-api/warehouse/stock")
    public ApiResult<Map<String, Object>> warehouseStock(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String filter
    ) {
        requireCapability("inventory:issue");
        return ApiResult.success(asMap(stockOverviewService.warehouseStock(keyword, category, filter)));
    }

    @GetMapping("/inventory-api/warehouse/alerts")
    public ApiResult<Map<String, Object>> warehouseAlerts() {
        requireCapability("inventory:issue");
        return ApiResult.success(asMap(stockOverviewService.alerts()));
    }

    @GetMapping("/inventory-api/department-stock")
    public ApiResult<Map<String, Object>> departmentStock(@RequestParam(required = false) String departmentId) {
        SessionUser user = requireCapability("inventory:read");
        return ApiResult.success(asMap(stockOverviewService.departmentStock(user, departmentId)));
    }

    @GetMapping("/inventory-api/warehouse/opening-template")
    public ResponseEntity<byte[]> openingTemplate() {
        requireCapability("inventory:issue");
        return attachment(openingImportService.template(), "期初库存导入模板.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @PostMapping("/inventory-api/warehouse/opening-import/preview")
    public ApiResult<Map<String, Object>> previewOpeningImport(@RequestParam("file") MultipartFile file) {
        SessionUser user = requireCapability("inventory:issue");
        return ApiResult.success(asMap(openingImportService.preview(file, user)));
    }

    @PostMapping("/inventory-api/warehouse/opening-import/commit")
    public ApiResult<Map<String, Object>> commitOpeningImport(@RequestParam("file") MultipartFile file) {
        SessionUser user = requireCapability("inventory:issue");
        return ApiResult.of(200, "期初库存已导入", asMap(openingImportService.commit(file, user)));
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

    @PostMapping("/inventory-api/requests/fulfill")
    public ApiResult<Map<String, Object>> fulfillRequest(@RequestBody Map<String, Object> payload) {
        SessionUser user = requireCapability("inventory:issue");
        return ApiResult.of(200, "申领已发放到科室库", databaseService.asMap(databaseService.fulfillRequest(toJson(payload), user)));
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

    private Map<String, Object> asMap(ObjectNode node) {
        return objectMapper.convertValue(node, Map.class);
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
