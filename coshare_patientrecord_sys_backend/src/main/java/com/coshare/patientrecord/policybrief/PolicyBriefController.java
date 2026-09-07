package com.coshare.patientrecord.policybrief;

import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 医政早报：每日医疗政策/DIP/肛肠学术资讯（管理端资讯页 + 首页卡片）。 */
@RestController
@Profile("mysql")
@RequestMapping("/clinic-api/policy-brief")
public class PolicyBriefController {

    private final PolicyBriefCollectService collectService;

    public PolicyBriefController(PolicyBriefCollectService collectService) {
        this.collectService = collectService;
    }

    /** 按日期（可按分类过滤）查询当日资讯列表。 */
    @GetMapping("/items")
    public ApiResult<Map<String, Object>> items(
        @RequestParam(name = "date", required = false, defaultValue = "") String date,
        @RequestParam(name = "category", required = false, defaultValue = "") String category
    ) {
        AuthPermission.currentUserOrThrow();
        return ApiResult.success(collectService.items(date, category));
    }

    /** 最新一日资讯概览（首页卡片用）：briefDate + total + 前 3 条 + 采集状态。 */
    @GetMapping("/latest")
    public ApiResult<Map<String, Object>> latest() {
        AuthPermission.currentUserOrThrow();
        return ApiResult.success(collectService.latest());
    }

    /** 手动触发采集（仅管理员；异步执行，约 1-3 分钟完成）。 */
    @PostMapping("/collect")
    public ApiResult<Map<String, Object>> collect() {
        AuthPermission.requireAnyRole("仅管理员可手动采集医政早报", "admin");
        return ApiResult.success(collectService.triggerCollect());
    }
}
