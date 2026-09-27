package com.coshare.patientrecord.clinic.controller;

import com.coshare.patientrecord.clinic.service.OpsDashboardService;
import com.coshare.patientrecord.clinic.service.OpsDashboardAnalysisService;
import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import com.coshare.patientrecord.auth.service.RoleCatalog;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
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
    private final OpsDashboardAnalysisService analysisService;

    public OpsDashboardController(OpsDashboardService opsDashboardService, OpsDashboardAnalysisService analysisService) {
        this.opsDashboardService = opsDashboardService;
        this.analysisService = analysisService;
    }

    @GetMapping("/clinic-api/ops/dashboard")
    public ApiResult<Map<String, Object>> dashboard(
        @RequestParam(required = false, defaultValue = "12") int months,
        @RequestParam(required = false, defaultValue = "") String from,
        @RequestParam(required = false, defaultValue = "") String to,
        @RequestParam(required = false, defaultValue = "day") String granularity,
        @RequestParam(required = false, defaultValue = "overview") String view
    ) {
        if (!from.isBlank() || !to.isBlank() || !"overview".equalsIgnoreCase(view) || !"day".equalsIgnoreCase(granularity)) {
            return ApiResult.success(analysisService.dashboard(from, to, granularity, view, months, AuthPermission.currentUserOrThrow()));
        }
        return ApiResult.success(opsDashboardService.dashboard(months, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/clinic-api/ops/dashboard/address-analysis")
    public ApiResult<Map<String, Object>> addressAnalysis(
        @RequestParam(required = false, defaultValue = "") String from,
        @RequestParam(required = false, defaultValue = "") String to,
        @RequestParam(required = false, defaultValue = "") String parentKey,
        @RequestParam(required = false, defaultValue = "COUNTY") String level,
        @RequestParam(required = false, defaultValue = "visits") String metric,
        @RequestParam(required = false, defaultValue = "") String keyword,
        @RequestParam(required = false, defaultValue = "1") int page,
        @RequestParam(required = false, defaultValue = "50") int pageSize
    ) {
        var user = AuthPermission.currentUserOrThrow();
        if ("PATIENT".equalsIgnoreCase(level) && !Set.of("admin", "nurse", "nursing").contains(RoleCatalog.canonicalize(user.role()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "patient cards are not available for this role");
        }
        return ApiResult.success(opsDashboardService.addressAnalysis(
            from, to, parentKey, level, metric, keyword, page, pageSize, user
        ));
    }
}

