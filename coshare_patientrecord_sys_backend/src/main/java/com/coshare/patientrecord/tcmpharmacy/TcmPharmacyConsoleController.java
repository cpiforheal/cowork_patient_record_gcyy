package com.coshare.patientrecord.tcmpharmacy;

import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
@RequestMapping("/clinic-api/tcm-pharmacy/console")
public class TcmPharmacyConsoleController {

    private final TcmPharmacyConsoleService service;
    private final ObjectMapper objectMapper;

    public TcmPharmacyConsoleController(TcmPharmacyConsoleService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/layout")
    public ApiResult<Map<String, Object>> layout() {
        return ApiResult.success(service.layout(AuthPermission.currentUserOrThrow()));
    }

    @PutMapping("/layout")
    public ApiResult<Map<String, Object>> save(@RequestBody Map<String, Object> payload) {
        return ApiResult.of(200, "药架位置已保存", service.save(toJson(payload), AuthPermission.currentUserOrThrow()));
    }

    private JsonNode toJson(Map<String, Object> payload) {
        return objectMapper.valueToTree(payload == null ? Map.of() : payload);
    }
}
