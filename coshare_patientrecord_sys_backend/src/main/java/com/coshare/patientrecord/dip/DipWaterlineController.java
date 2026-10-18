package com.coshare.patientrecord.dip;

import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** DIP 费用预警：字段对齐 HIS 住院费用界面，85%/95% 阈值只在本系统内做预警渲染。 */
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

    /** 病组候选清单，供医生改选。 */
    @GetMapping("/clinic-api/dip/warning/groups")
    public ApiResult<List<Map<String, Object>>> groups() {
        AuthPermission.currentUserOrThrow();
        return ApiResult.success(service.groupOptions());
    }

    /** 录入 HIS 侧的未结算费用与住院信息。 */
    @PostMapping("/clinic-api/dip/warning/fee")
    public ApiResult<Map<String, Object>> saveFee(@RequestBody Map<String, Object> body) {
        return ApiResult.of(200, "已保存费用", service.saveFee(body, AuthPermission.currentUserOrThrow()));
    }

    /** 改选病组；groupCode 为空表示恢复主诊断自动匹配。 */
    @PostMapping("/clinic-api/dip/warning/group")
    public ApiResult<Map<String, Object>> saveGroup(@RequestBody Map<String, Object> body) {
        return ApiResult.of(200, "已更新病组", service.saveGroupSelection(body, AuthPermission.currentUserOrThrow()));
    }
}
