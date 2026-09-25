package com.coshare.patientrecord.clinic.controller;

import com.coshare.patientrecord.clinic.service.OpsDashboardService;
import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营数据看板：面向管理层的只读聚合接口。
 * 提供来访量环比、月度趋势、就诊状态分布、随访闭环与科室分布，
 * 供"运营总览 → 运营数据看板"页面渲染多形态图表。
 */
@RestController
@Profile("mysql")
public class OpsDashboardController {

    private final OpsDashboardService opsDashboardService;

    public OpsDashboardController(OpsDashboardService opsDashboardService) {
        this.opsDashboardService = opsDashboardService;
    }

    @GetMapping("/clinic-api/ops/dashboard")
    public ApiResult<Map<String, Object>> dashboard(
        @RequestParam(required = false, defaultValue = "12") int months
    ) {
        return ApiResult.success(opsDashboardService.dashboard(months, AuthPermission.currentUserOrThrow()));
    }
}
