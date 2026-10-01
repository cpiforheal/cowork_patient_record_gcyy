package com.coshare.patientrecord.clinic.recheck;

import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 复查预约登记：日期看板 + 登记/编辑/标记到未到/改期/撤销。 */
@RestController
@Profile("mysql")
public class RecheckScheduleController {

    private final RecheckScheduleService service;

    public RecheckScheduleController(RecheckScheduleService service) {
        this.service = service;
    }

    @GetMapping("/clinic-api/recheck-schedule")
    public ApiResult<Map<String, Object>> board(
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to
    ) {
        return ApiResult.success(service.board(from, to, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/clinic-api/recheck-schedule")
    public ApiResult<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return ApiResult.of(200, "已登记", service.create(body, AuthPermission.currentUserOrThrow()));
    }

    @PutMapping("/clinic-api/recheck-schedule/{id}")
    public ApiResult<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResult.of(200, "已更新", service.update(id.trim(), body, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/clinic-api/recheck-schedule/{id}/status")
    public ApiResult<Map<String, Object>> status(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResult.of(200, "已标记", service.changeStatus(id.trim(), body, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/clinic-api/recheck-schedule/{id}/reschedule")
    public ApiResult<Map<String, Object>> reschedule(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResult.of(200, "已改期", service.reschedule(id.trim(), body, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/clinic-api/recheck-schedule/{id}/cancel")
    public ApiResult<Map<String, Object>> cancel(@PathVariable String id) {
        return ApiResult.of(200, "已撤销", service.cancel(id.trim(), AuthPermission.currentUserOrThrow()));
    }
}
