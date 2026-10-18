package com.coshare.patientrecord.dip;

import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** DIP 费用预警：按主诊断匹配病组，结合 HIS 费用计算 85%/95% 水位。 */
@RestController
@Profile("mysql")
public class DipWaterlineController {

    private final DipWaterlineService service;

    public DipWaterlineController(DipWaterlineService service) {
        this.service = service;
    }

    @GetMapping("/clinic-api/dip/warning/patients")
    public ApiResult<Map<String, Object>> board() {
        return ApiResult.success(service.board(AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/clinic-api/dip/warning/fee")
    public ApiResult<Map<String, Object>> saveFee(@RequestBody Map<String, Object> body) {
        String patientId = body.get("patientId") == null ? null : String.valueOf(body.get("patientId"));
        BigDecimal currentFee = null;
        Object rawFee = body.get("currentFee");
        if (rawFee != null && !String.valueOf(rawFee).isBlank()) {
            try {
                currentFee = new BigDecimal(String.valueOf(rawFee).trim());
            } catch (NumberFormatException ex) {
                currentFee = null;
            }
        }
        String source = body.get("source") == null ? "MANUAL" : String.valueOf(body.get("source"));
        return ApiResult.of(200, "已更新费用", service.saveFee(patientId, currentFee, source, AuthPermission.currentUserOrThrow()));
    }
}
