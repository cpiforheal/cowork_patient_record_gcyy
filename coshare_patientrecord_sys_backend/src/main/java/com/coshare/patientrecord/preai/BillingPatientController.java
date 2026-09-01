package com.coshare.patientrecord.preai;

import com.coshare.patientrecord.auth.service.RoleCatalog;
import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 收费室专用只读查询：仅暴露患者姓名、身份证号、家庭住址、联系电话四项基础信息，
 * 供院外独立收费部门登记使用；不暴露任何临床资料。
 */
@RestController
@Profile("mysql")
@RequestMapping("/clinic-api/billing")
public class BillingPatientController {

    private final JdbcTemplate jdbcTemplate;

    public BillingPatientController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/patients")
    public ApiResult<Map<String, Object>> patients(@RequestParam(value = "keyword", defaultValue = "") String keyword) {
        requireBillingReader();
        String kw = keyword == null ? "" : keyword.trim();
        String like = "%" + kw + "%";
        List<Map<String, Object>> patients = jdbcTemplate.query(
            """
            SELECT id, patient_json, updated_at
            FROM pre_ai_patient_cases
            WHERE (? = '' OR patient_json LIKE ?)
            ORDER BY updated_at DESC
            LIMIT 200
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getString("id"));
                row.put("patientName", jsonField(rs.getString("patient_json"), "patientName"));
                row.put("identityNumber", jsonField(rs.getString("patient_json"), "identityNumber"));
                row.put("address", jsonField(rs.getString("patient_json"), "address"));
                row.put("phone", jsonField(rs.getString("patient_json"), "phone"));
                row.put("updatedAt", rs.getString("updated_at"));
                return row;
            },
            kw,
            like
        );
        // 空关键字时隐藏没有姓名的患者行，避免无意义数据
        if (kw.isEmpty()) {
            patients = patients.stream().filter(row -> notBlank((String) row.get("patientName"))).toList();
        } else {
            patients = patients.stream()
                .filter(row -> notBlank((String) row.get("patientName")) || notBlank((String) row.get("identityNumber")))
                .toList();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patients", patients);
        result.put("total", patients.size());
        return ApiResult.success(result);
    }

    private void requireBillingReader() {
        AuthPermission.requireAnyRole("仅收费室或管理员可查询患者收费信息", "billing", "admin");
    }

    private String jsonField(String patientJson, String field) {
        if (patientJson == null || patientJson.isBlank()) return "";
        var node = readTree(patientJson);
        String value = node == null ? "" : node.path(field).asText("");
        return value == null ? "" : value.trim();
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception error) {
            return null;
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
