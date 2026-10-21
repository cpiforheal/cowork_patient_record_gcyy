package com.coshare.patientrecord.dip;

import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** DIP 全局病组目录维护（仅管理员）。 */
@RestController
@Profile("mysql")
public class DipCatalogController {

    private final DipCatalogService service;

    public DipCatalogController(DipCatalogService service) {
        this.service = service;
    }

    @GetMapping("/clinic-api/dip/catalog/groups")
    public ApiResult<List<Map<String, Object>>> list() {
        return ApiResult.success(service.list());
    }

    @PostMapping("/clinic-api/dip/catalog/groups")
    public ApiResult<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return ApiResult.of(200, "病组已新增", service.create(body, AuthPermission.currentUserOrThrow()));
    }

    @PutMapping("/clinic-api/dip/catalog/groups/{groupCode}")
    public ApiResult<Map<String, Object>> update(
        @PathVariable("groupCode") String groupCode,
        @RequestBody Map<String, Object> body
    ) {
        return ApiResult.of(200, "病组已更新", service.update(groupCode, body, AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/clinic-api/dip/catalog/groups/{groupCode}/status")
    public ApiResult<Map<String, Object>> setStatus(
        @PathVariable("groupCode") String groupCode,
        @RequestBody Map<String, Object> body
    ) {
        return ApiResult.of(200, "病组状态已更新",
            service.setStatus(groupCode, String.valueOf(body.get("status")), AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/clinic-api/dip/catalog/groups/{groupCode}/history")
    public ApiResult<List<Map<String, Object>>> history(@PathVariable("groupCode") String groupCode) {
        return ApiResult.success(service.history(groupCode));
    }
}
