package com.coshare.patientrecord.clinic.controller;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.clinic.service.HomeTrendInsightService;
import com.coshare.patientrecord.security.AuthPermission;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 首页数据看板 AI 汇总分析：SSE 流式输出（结果仅作看板暂存，不进入病历）。 */
@RestController
@Profile("mysql")
@RequestMapping("/clinic-api/home")
public class HomeTrendInsightController {

    private final HomeTrendInsightService insightService;

    public HomeTrendInsightController(HomeTrendInsightService insightService) {
        this.insightService = insightService;
    }

    @PostMapping("/trend-insight/stream")
    public void stream(@RequestBody HomeTrendInsightService.TrendInsightRequest request, HttpServletResponse response)
        throws IOException {
        SessionUser user = AuthPermission.currentUserOrThrow();
        insightService.streamInsight(request, user, response);
    }
}
