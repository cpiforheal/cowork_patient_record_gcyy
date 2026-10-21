package com.coshare.patientrecord.dip;

import com.coshare.patientrecord.auth.dto.SessionUser;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** DIP 变更留痕：费用、病组、病组费用、患者新增与全局病组目录的完整前后值记录。 */
@Service
@Profile("mysql")
public class DipChangeLogService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public DipChangeLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(
        String patientId,
        String scope,
        String targetCode,
        String beforeValue,
        String afterValue,
        String note,
        SessionUser user
    ) {
        jdbcTemplate.update(
            "INSERT INTO dip_change_log (id, patient_id, change_scope, target_code, before_value, after_value,"
                + " note, operator, operator_role, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "dchg-" + UUID.randomUUID(),
            safe(patientId),
            safe(scope),
            truncate(safe(targetCode), 64),
            truncate(safe(beforeValue), 500),
            truncate(safe(afterValue), 500),
            truncate(safe(note), 200),
            operator(user),
            user == null ? "" : safe(user.role()),
            LocalDateTime.now().format(TIME)
        );
    }

    public List<Map<String, Object>> listByPatient(String patientId) {
        return jdbcTemplate.query(
            "SELECT id, patient_id, change_scope, target_code, before_value, after_value, note, operator,"
                + " operator_role, created_at FROM dip_change_log WHERE patient_id = ?"
                + " ORDER BY created_at DESC, id DESC LIMIT 200",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getString("id"));
                row.put("patientId", rs.getString("patient_id"));
                row.put("scope", rs.getString("change_scope"));
                row.put("targetCode", rs.getString("target_code"));
                row.put("beforeValue", rs.getString("before_value"));
                row.put("afterValue", rs.getString("after_value"));
                row.put("note", rs.getString("note"));
                row.put("operator", rs.getString("operator"));
                row.put("operatorRole", rs.getString("operator_role"));
                row.put("createdAt", rs.getString("created_at"));
                return row;
            },
            safe(patientId)
        );
    }

    public List<Map<String, Object>> listByTarget(String groupCode) {
        return jdbcTemplate.query(
            "SELECT id, patient_id, change_scope, target_code, before_value, after_value, note, operator,"
                + " operator_role, created_at FROM dip_change_log WHERE target_code = ?"
                + " ORDER BY created_at DESC, id DESC LIMIT 200",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getString("id"));
                row.put("patientId", rs.getString("patient_id"));
                row.put("scope", rs.getString("change_scope"));
                row.put("targetCode", rs.getString("target_code"));
                row.put("beforeValue", rs.getString("before_value"));
                row.put("afterValue", rs.getString("after_value"));
                row.put("note", rs.getString("note"));
                row.put("operator", rs.getString("operator"));
                row.put("operatorRole", rs.getString("operator_role"));
                row.put("createdAt", rs.getString("created_at"));
                return row;
            },
            safe(groupCode)
        );
    }

    private static String operator(SessionUser user) {
        if (user == null) return "";
        String name = user.name();
        if (name != null && !name.isBlank()) return name;
        return user.username() == null ? "" : user.username();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
