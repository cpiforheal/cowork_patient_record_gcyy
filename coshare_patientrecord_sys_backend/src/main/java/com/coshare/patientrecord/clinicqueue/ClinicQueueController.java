package com.coshare.patientrecord.clinicqueue;

import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
@RequestMapping("/clinic-api/clinic-queue")
public class ClinicQueueController {

    private final ClinicQueueService service;

    public ClinicQueueController(ClinicQueueService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public ApiResult<Map<String, Object>> dashboard(
        @RequestParam(required = false, defaultValue = "") String keyword
    ) {
        return ApiResult.success(service.dashboard(keyword, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/tickets")
    public ApiResult<Map<String, Object>> issue(@RequestBody ClinicQueueService.IssueRequest request) {
        return ApiResult.of(200, "排队号码已生成", service.issue(request, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/print-template")
    public ApiResult<Map<String, Object>> printTemplate() {
        return ApiResult.success(service.printTemplate(AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/print-template")
    public ApiResult<Map<String, Object>> savePrintTemplate(@RequestBody ClinicQueueService.PrintTemplateRequest request) {
        return ApiResult.of(200, "票据模板已保存", service.savePrintTemplate(request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/print-template/test-payload")
    public ApiResult<Map<String, Object>> testPrintPayload(@RequestBody ClinicQueueService.PrintTaskRequest request) {
        return ApiResult.success(service.testPrintPayload(request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/print-terminals/register")
    public ApiResult<Map<String, Object>> registerPrintTerminal(@RequestBody ClinicQueueService.PrintTerminalRequest request) {
        return ApiResult.of(200, "打印终端已登记", service.registerPrintTerminal(request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/tickets/{ticketId}/print-tasks")
    public ApiResult<Map<String, Object>> createPrintTask(
        @PathVariable String ticketId,
        @RequestBody ClinicQueueService.PrintTaskRequest request
    ) {
        return ApiResult.of(200, "打印任务已创建", service.createPrintTask(ticketId, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/print-tasks/{id}/result")
    public ApiResult<Map<String, Object>> completePrintTask(
        @PathVariable String id,
        @RequestBody ClinicQueueService.PrintResultRequest request
    ) {
        return ApiResult.of(200, "打印结果已记录", service.completePrintTask(id, request, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/tickets/{ticketId}/print-tasks")
    public ApiResult<Map<String, Object>> printTasks(@PathVariable String ticketId) {
        return ApiResult.success(service.printTasks(ticketId, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/tickets/{ticketId}")
    public ApiResult<Map<String, Object>> workspace(@PathVariable String ticketId) {
        return ApiResult.success(service.workspace(ticketId, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/stages/{stageCode}/call-next")
    public ApiResult<Map<String, Object>> callNext(@PathVariable String stageCode) {
        return ApiResult.of(200, "已按调度规则叫号", service.callNext(stageCode, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/tasks/{taskId}/{action}")
    public ApiResult<Map<String, Object>> taskAction(
        @PathVariable String taskId,
        @PathVariable String action,
        @RequestBody(required = false) ClinicQueueService.ActionRequest request
    ) {
        return ApiResult.of(200, "排队任务状态已更新", service.taskAction(taskId, action, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/rooms/{roomCode}/{action}")
    public ApiResult<Map<String, Object>> roomAction(
        @PathVariable String roomCode,
        @PathVariable String action,
        @RequestBody(required = false) ClinicQueueService.ActionRequest request
    ) {
        return ApiResult.of(200, "房间状态已更新", service.roomAction(roomCode, action, request, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/display")
    public ApiResult<Map<String, Object>> display() {
        return ApiResult.success(service.displaySnapshot(AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/announcements/pending")
    public ApiResult<Map<String, Object>> pendingAnnouncements() {
        return ApiResult.success(service.pendingAnnouncements(AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/announcements/{id}/played")
    public ApiResult<Map<String, Object>> announcementPlayed(@PathVariable String id) {
        return ApiResult.of(200, "播报结果已确认", service.markAnnouncementPlayed(id, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/audits")
    public ApiResult<Map<String, Object>> audits(
        @RequestParam(required = false, defaultValue = "") String ticketId,
        @RequestParam(required = false, defaultValue = "") String roomCode
    ) {
        return ApiResult.success(service.audits(ticketId, roomCode, AuthPermission.currentUserOrThrow()));
    }
}
