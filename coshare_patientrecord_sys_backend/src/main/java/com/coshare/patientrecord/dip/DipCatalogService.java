package com.coshare.patientrecord.dip;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.security.AuthPermission;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** DIP 全局病组目录：仅管理员可新增、编辑、停用，所有变更写入 dip_change_log。 */
@Service
@Profile("mysql")
public class DipCatalogService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final DipChangeLogService changeLogService;

    public DipCatalogService(JdbcTemplate jdbcTemplate, DipChangeLogService changeLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.changeLogService = changeLogService;
    }

    public List<Map<String, Object>> list() {
        requireAdmin();
        return jdbcTemplate.query(
            "SELECT id, category_code, category_name, group_code, group_name, estimated_fee, match_keywords,"
                + " keyword_priority, sort_no, status, updated_at FROM dip_group_mapping"
                + " ORDER BY category_code ASC, sort_no ASC, group_code ASC",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getString("id"));
                row.put("categoryCode", rs.getString("category_code"));
                row.put("categoryName", rs.getString("category_name"));
                row.put("groupCode", rs.getString("group_code"));
                row.put("groupName", rs.getString("group_name"));
                row.put("estimatedFee", rs.getBigDecimal("estimated_fee").setScale(2, RoundingMode.HALF_UP));
                row.put("matchKeywords", rs.getString("match_keywords"));
                row.put("keywordPriority", rs.getInt("keyword_priority"));
                row.put("sortNo", rs.getInt("sort_no"));
                row.put("status", rs.getString("status"));
                row.put("updatedAt", rs.getString("updated_at"));
                return row;
            }
        );
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body, SessionUser user) {
        requireAdmin();
        String groupCode = required(body.get("groupCode"), "病组编码");
        Integer exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM dip_group_mapping WHERE group_code = ?", Integer.class, groupCode);
        if (exists != null && exists > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "病组编码已存在");

        String categoryCode = required(body.get("categoryCode"), "大类编码");
        String categoryName = required(body.get("categoryName"), "大类名称");
        String groupName = required(body.get("groupName"), "病组名称");
        BigDecimal estimatedFee = fee(body.get("estimatedFee"));
        String keywords = limit(text(body.get("matchKeywords")), 300, "匹配关键词");
        int priority = integer(body.get("keywordPriority"), 0);
        int sortNo = integer(body.get("sortNo"), 0);
        String timestamp = LocalDateTime.now().format(TIME);

        jdbcTemplate.update(
            "INSERT INTO dip_group_mapping (id, category_code, category_name, group_code, group_name, estimated_fee,"
                + " match_keywords, keyword_priority, sort_no, updated_at, status)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')",
            "dip-" + UUID.randomUUID(),
            categoryCode,
            categoryName,
            groupCode,
            groupName,
            estimatedFee,
            keywords,
            priority,
            sortNo,
            timestamp
        );
        changeLogService.record(
            "", "CATALOG", groupCode, "",
            categoryName + "|" + groupName + "|" + estimatedFee.toPlainString() + "|" + keywords + "|ACTIVE",
            "新增病组",
            user
        );
        return detail(groupCode);
    }

    @Transactional
    public Map<String, Object> update(String groupCode, Map<String, Object> body, SessionUser user) {
        requireAdmin();
        Map<String, Object> before = loadRow(groupCode);
        if (before == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "病组不存在");

        String categoryCode = required(body.get("categoryCode"), "大类编码");
        String categoryName = required(body.get("categoryName"), "大类名称");
        String groupName = required(body.get("groupName"), "病组名称");
        BigDecimal estimatedFee = fee(body.get("estimatedFee"));
        String keywords = limit(text(body.get("matchKeywords")), 300, "匹配关键词");
        int priority = integer(body.get("keywordPriority"), 0);
        int sortNo = integer(body.get("sortNo"), 0);

        jdbcTemplate.update(
            "UPDATE dip_group_mapping SET category_code = ?, category_name = ?, group_name = ?, estimated_fee = ?,"
                + " match_keywords = ?, keyword_priority = ?, sort_no = ?, updated_at = ? WHERE group_code = ?",
            categoryCode,
            categoryName,
            groupName,
            estimatedFee,
            keywords,
            priority,
            sortNo,
            LocalDateTime.now().format(TIME),
            groupCode
        );
        changeLogService.record("", "CATALOG", groupCode, snapshot(before), snapshot(loadRow(groupCode)), "编辑病组", user);
        return detail(groupCode);
    }

    @Transactional
    public Map<String, Object> setStatus(String groupCode, String status, SessionUser user) {
        requireAdmin();
        String next = text(status).toUpperCase(java.util.Locale.ROOT);
        if (!"ACTIVE".equals(next) && !"DISABLED".equals(next)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态只能是 ACTIVE 或 DISABLED");
        }
        Map<String, Object> before = loadRow(groupCode);
        if (before == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "病组不存在");
        jdbcTemplate.update(
            "UPDATE dip_group_mapping SET status = ?, updated_at = ? WHERE group_code = ?",
            next,
            LocalDateTime.now().format(TIME),
            groupCode
        );
        Map<String, Object> after = loadRow(groupCode);
        changeLogService.record(
            "", "CATALOG", groupCode, snapshot(before), snapshot(after),
            "DISABLED".equals(next) ? "停用病组" : "启用病组",
            user
        );
        return detail(groupCode);
    }

    public List<Map<String, Object>> history(String groupCode) {
        requireAdmin();
        return changeLogService.listByTarget(groupCode);
    }

    private Map<String, Object> detail(String groupCode) {
        Map<String, Object> row = loadRow(groupCode);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "病组不存在");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("categoryCode", row.get("category_code"));
        result.put("categoryName", row.get("category_name"));
        result.put("groupCode", row.get("group_code"));
        result.put("groupName", row.get("group_name"));
        Object fee = row.get("estimated_fee");
        result.put("estimatedFee", fee == null ? null : new BigDecimal(String.valueOf(fee)).setScale(2, RoundingMode.HALF_UP));
        result.put("matchKeywords", row.get("match_keywords"));
        result.put("keywordPriority", row.get("keyword_priority"));
        result.put("sortNo", row.get("sort_no"));
        result.put("status", row.get("status"));
        result.put("updatedAt", row.get("updated_at"));
        return result;
    }

    private Map<String, Object> loadRow(String groupCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT id, category_code, category_name, group_code, group_name, estimated_fee, match_keywords,"
                + " keyword_priority, sort_no, status, updated_at FROM dip_group_mapping WHERE group_code = ?",
            text(groupCode)
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String snapshot(Map<String, Object> row) {
        if (row == null) return "";
        return text(row.get("category_name")) + "|" + text(row.get("group_name")) + "|"
            + text(row.get("estimated_fee")) + "|" + text(row.get("match_keywords")) + "|" + text(row.get("status"));
    }

    private static void requireAdmin() {
        AuthPermission.requireAnyRole("仅管理员可维护 DIP 病组目录", "admin");
    }

    private static String required(Object value, String label) {
        String text = text(value);
        if (text.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写" + label);
        return text;
    }

    private static BigDecimal fee(Object value) {
        String text = text(value);
        if (text.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写标准费用");
        try {
            BigDecimal fee = new BigDecimal(text);
            if (fee.signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标准费用必须大于 0");
            return fee.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标准费用格式不正确");
        }
    }

    private static int integer(Object value, int fallback) {
        String text = text(value);
        if (text.isBlank()) return fallback;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String limit(String value, int max, String label) {
        if (value.length() <= max) return value;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能超过 " + max + " 个字符");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
