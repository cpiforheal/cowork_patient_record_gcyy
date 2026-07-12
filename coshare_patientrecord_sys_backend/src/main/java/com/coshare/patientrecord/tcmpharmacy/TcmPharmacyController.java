package com.coshare.patientrecord.tcmpharmacy;

import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
@RequestMapping("/clinic-api/tcm-pharmacy")
public class TcmPharmacyController {

    private final TcmPharmacyService service;

    public TcmPharmacyController(TcmPharmacyService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public ApiResult<Map<String, Object>> dashboard() {
        return ApiResult.success(service.dashboard(AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/prescriptions")
    public ApiResult<Map<String, Object>> prescriptions(
        @RequestParam(required = false, defaultValue = "") String status,
        @RequestParam(required = false, defaultValue = "") String keyword
    ) {
        return ApiResult.success(service.list(status, keyword, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/prescriptions/{id}")
    public ApiResult<Map<String, Object>> prescription(@PathVariable String id) {
        return ApiResult.success(service.workspace(id, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/prescriptions")
    public ApiResult<Map<String, Object>> create(@RequestBody TcmPharmacyService.PrescriptionRequest request) {
        return ApiResult.of(200, "电子处方草稿已创建", service.create(request, AuthPermission.currentUserOrThrow()));
    }

    @PutMapping("/prescriptions/{id}")
    public ApiResult<Map<String, Object>> save(
        @PathVariable String id,
        @RequestBody TcmPharmacyService.PrescriptionRequest request
    ) {
        return ApiResult.of(200, "电子处方草稿已保存", service.save(id, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/prescriptions/{id}/submit")
    public ApiResult<Map<String, Object>> submit(@PathVariable String id) {
        return ApiResult.of(200, "处方已签署并进入待收费", service.submit(id, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/prescriptions/{id}/charge")
    public ApiResult<Map<String, Object>> confirmCharge(
        @PathVariable String id,
        @RequestBody(required = false) TcmPharmacyService.ActionRequest request
    ) {
        return ApiResult.of(200, "收费已确认，处方进入药师审核", service.confirmCharge(id, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/prescriptions/{id}/review")
    public ApiResult<Map<String, Object>> review(
        @PathVariable String id,
        @RequestBody TcmPharmacyService.ReviewRequest request
    ) {
        return ApiResult.of(200, "审方结果已提交", service.review(id, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/prescriptions/{id}/dispensing/{action}")
    public ApiResult<Map<String, Object>> dispensing(
        @PathVariable String id,
        @PathVariable String action,
        @RequestBody(required = false) TcmPharmacyService.ActionRequest request
    ) {
        return ApiResult.of(200, "调剂状态已更新", service.advanceDispensing(id, action, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/prescriptions/{id}/decoction/{action}")
    public ApiResult<Map<String, Object>> decoction(
        @PathVariable String id,
        @PathVariable String action,
        @RequestBody(required = false) TcmPharmacyService.ActionRequest request
    ) {
        return ApiResult.of(200, "代煎生产状态已更新", service.advanceDecoction(id, action, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/prescriptions/{id}/call")
    public ApiResult<Map<String, Object>> call(@PathVariable String id) {
        return ApiResult.of(200, "叫号任务已创建", service.call(id, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/prescriptions/{id}/collect")
    public ApiResult<Map<String, Object>> collect(
        @PathVariable String id,
        @RequestBody(required = false) TcmPharmacyService.ActionRequest request
    ) {
        return ApiResult.of(200, "药品已核验领取", service.collect(id, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/prescriptions/{id}/exception")
    public ApiResult<Map<String, Object>> exception(
        @PathVariable String id,
        @RequestBody TcmPharmacyService.ActionRequest request
    ) {
        return ApiResult.of(200, "异常状态已记录", service.markException(id, request, AuthPermission.currentUserOrThrow()));
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

    @PostMapping("/demo/reset")
    public ApiResult<Map<String, Object>> resetDemo() {
        return ApiResult.of(200, "中药房演示数据已重置", service.resetDemo(AuthPermission.currentUserOrThrow()));
    }
}
