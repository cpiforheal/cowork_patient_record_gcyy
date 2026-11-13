package com.coshare.patientrecord.eyedrop;

import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/clinic-api/eye-drop")
public class EyeDropController {

    private final EyeDropService service;

    public EyeDropController(EyeDropService service) {
        this.service = service;
    }

    @GetMapping("/today")
    public ApiResult<Map<String, Object>> today(@RequestParam(required = false, defaultValue = "") String date) {
        return ApiResult.success(service.today(date, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/month")
    public ApiResult<Map<String, Object>> month(@RequestParam(required = false, defaultValue = "") String month) {
        return ApiResult.success(service.month(month, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/doses")
    public ApiResult<Map<String, Object>> createDose(@RequestBody EyeDropService.DoseRequest request) {
        return ApiResult.of(200, "已记录滴注", service.createDose(request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/doses/{id}/void")
    public ApiResult<Map<String, Object>> voidDose(@PathVariable String id) {
        return ApiResult.of(200, "已撤销", service.voidDose(id, AuthPermission.currentUserOrThrow()));
    }

    @PutMapping("/plan/{planId}")
    public ApiResult<Map<String, Object>> updatePlan(
        @PathVariable String planId,
        @RequestBody EyeDropService.PlanTimeRequest request
    ) {
        return ApiResult.of(200, "计划时间已更新", service.updatePlan(planId, request, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/measurements")
    public ApiResult<Map<String, Object>> createMeasurement(@RequestBody EyeDropService.MeasurementRequest request) {
        return ApiResult.of(200, "已保存眼压与症状", service.createMeasurement(request, AuthPermission.currentUserOrThrow()));
    }

    @DeleteMapping("/measurements/{id}")
    public ApiResult<Map<String, Object>> deleteMeasurement(@PathVariable String id) {
        return ApiResult.of(200, "记录已删除", service.deleteMeasurement(id, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> exportCsv(@RequestParam(required = false, defaultValue = "") String month) {
        EyeDropService.ExportFile file = service.exportCsv(month, AuthPermission.currentUserOrThrow());
        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                    .filename(file.fileName(), StandardCharsets.UTF_8)
                    .build()
                    .toString()
            )
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(file.content());
    }
}
