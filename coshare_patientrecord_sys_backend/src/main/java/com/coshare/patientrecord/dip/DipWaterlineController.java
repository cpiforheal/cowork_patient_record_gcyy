package com.coshare.patientrecord.dip;

import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** DIP 费用预警：字段对齐 HIS 住院费用界面，85%/95% 阈值只在本系统内做预警渲染。 */
@RestController
@Profile("mysql")
public class DipWaterlineController {

    private final DipWaterlineService service;
    private final DipOcrService ocrService;

    public DipWaterlineController(DipWaterlineService service, DipOcrService ocrService) {
        this.service = service;
        this.ocrService = ocrService;
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

    /** DIP 工作台新增患者（HIS 之外）。 */
    @PostMapping("/clinic-api/dip/warning/patients")
    public ApiResult<Map<String, Object>> createPatient(@RequestBody Map<String, Object> body) {
        return ApiResult.of(200, "患者已新增", service.createPatient(body, AuthPermission.currentUserOrThrow()));
    }

    /** 录入 HIS 侧的未结算费用与住院信息。 */
    @PostMapping("/clinic-api/dip/warning/fee")
    public ApiResult<Map<String, Object>> saveFee(@RequestBody Map<String, Object> body) {
        return ApiResult.of(200, "已保存费用", service.saveFee(body, AuthPermission.currentUserOrThrow()));
    }

    /** 作废当前费用记录（软作废，可重新录入）。 */
    @PostMapping("/clinic-api/dip/warning/fee/void")
    public ApiResult<Map<String, Object>> voidFee(@RequestBody Map<String, Object> body) {
        return ApiResult.of(200, "费用记录已作废", service.voidFee(body, AuthPermission.currentUserOrThrow()));
    }

    /** 患者级病组费用覆盖；传空表示恢复病组目录标准费用。 */
    @PostMapping("/clinic-api/dip/warning/group-fee")
    public ApiResult<Map<String, Object>> saveGroupFee(@RequestBody Map<String, Object> body) {
        return ApiResult.of(200, "病组费用已更新", service.saveGroupFeeOverride(body, AuthPermission.currentUserOrThrow()));
    }

    /** 患者变更历史：费用、病组、病组费用、作废记录。 */
    @GetMapping("/clinic-api/dip/warning/history")
    public ApiResult<List<Map<String, Object>>> history(@RequestParam("patientId") String patientId) {
        return ApiResult.success(service.history(patientId, AuthPermission.currentUserOrThrow()));
    }

    /** 改选病组；groupCode 为空表示恢复主诊断自动匹配。 */
    @PostMapping("/clinic-api/dip/warning/group")
    public ApiResult<Map<String, Object>> saveGroup(@RequestBody Map<String, Object> body) {
        return ApiResult.of(200, "已更新病组", service.saveGroupSelection(body, AuthPermission.currentUserOrThrow()));
    }

    /** HIS 费用截图识别：只返回预览，不写入费用；原始截图不落库。 */
    @PostMapping(value = "/clinic-api/dip/warning/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<Map<String, Object>> parseScreenshot(
        @RequestParam("file") MultipartFile file
    ) throws IOException {
        return ApiResult.of(200, "识别完成", ocrService.parse(file, AuthPermission.currentUserOrThrow()));
    }
}
