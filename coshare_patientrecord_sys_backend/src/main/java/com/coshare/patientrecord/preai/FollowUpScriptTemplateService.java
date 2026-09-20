package com.coshare.patientrecord.preai;

import com.coshare.patientrecord.auth.dto.SessionUser;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 随访话术模板库（热更新）：
 * - 话术文本下沉数据库，护理岗/医生/管理员在管理页热编辑即时生效（无缓存直查库）；
 * - 表空时播种内置默认话术（default 节点），不覆盖管理端后续修改；
 * - 归档即软删（ARCHIVED），可重新启用。
 */
@Service
@Profile("mysql")
public class FollowUpScriptTemplateService {

    public static final String DEFAULT_NODE_KEY = "default";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 照病种模板库模式：编辑角色名单，Service 层硬闸门（读宽松、写收敛）。 */
    private static final Set<String> MANAGE_ROLES = Set.of("nurse", "nursing", "doctor", "admin");

    private final JdbcTemplate jdbcTemplate;
    private final PreAiEncounterService encounterService;
    private volatile boolean seeded;

    public FollowUpScriptTemplateService(JdbcTemplate jdbcTemplate, PreAiEncounterService encounterService) {
        this.jdbcTemplate = jdbcTemplate;
        this.encounterService = encounterService;
    }

    // ---------- 读取 ----------

    public List<Map<String, Object>> listActive() {
        ensureSeeded();
        return jdbcTemplate.query(
            "SELECT id, node_key, label, content, status, sort_order, updated_by, updated_at "
                + "FROM clinic_followup_script_templates WHERE status = 'ACTIVE' ORDER BY sort_order ASC, updated_at DESC",
            this::mapRow);
    }

    public List<Map<String, Object>> listManage() {
        ensureSeeded();
        return jdbcTemplate.query(
            "SELECT id, node_key, label, content, status, sort_order, updated_by, updated_at "
                + "FROM clinic_followup_script_templates ORDER BY status ASC, sort_order ASC, updated_at DESC",
            this::mapRow);
    }

    // ---------- 写操作（护理岗/医生/管理员） ----------

    public Map<String, Object> create(Map<String, Object> body, SessionUser user) {
        requireManageRole(user);
        String label = text(body, "label");
        if (label.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板名称不能为空");
        String content = text(body, "content");
        if (content.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "话术内容不能为空");
        String id = "fus-" + UUID.randomUUID().toString().substring(0, 8);
        String nodeKey = text(body, "nodeKey").isBlank() ? id : text(body, "nodeKey");
        int sortOrder = intOf(body, "sortOrder");
        String now = TIME.format(LocalDateTime.now());
        jdbcTemplate.update(
            "INSERT INTO clinic_followup_script_templates (id, node_key, label, content, status, sort_order, created_by, created_at, updated_by, updated_at) "
                + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)",
            id, nodeKey, label, content, sortOrder, user.name(), now, user.name(), now);
        audit(user, "followup-script.create", "新建随访话术模板：" + label);
        return get(id);
    }

    public Map<String, Object> update(String id, Map<String, Object> body, SessionUser user) {
        requireManageRole(user);
        Map<String, Object> existing = get(id);
        String label = text(body, "label").isBlank() ? String.valueOf(existing.get("label")) : text(body, "label");
        String content = text(body, "content").isBlank() ? String.valueOf(existing.get("content")) : text(body, "content");
        int sortOrder = body.containsKey("sortOrder") ? intOf(body, "sortOrder") : intOf(existing, "sortOrder");
        jdbcTemplate.update(
            "UPDATE clinic_followup_script_templates SET label = ?, content = ?, sort_order = ?, updated_by = ?, updated_at = ? WHERE id = ?",
            label, content, sortOrder, user.name(), TIME.format(LocalDateTime.now()), id);
        audit(user, "followup-script.update", "编辑随访话术模板：" + label);
        return get(id);
    }

    public Map<String, Object> changeStatus(String id, String action, SessionUser user) {
        requireManageRole(user);
        Map<String, Object> existing = get(id);
        String target = switch (action) {
            case "archive" -> "ARCHIVED";
            case "activate" -> "ACTIVE";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知操作：" + action);
        };
        jdbcTemplate.update(
            "UPDATE clinic_followup_script_templates SET status = ?, updated_by = ?, updated_at = ? WHERE id = ?",
            target, user.name(), TIME.format(LocalDateTime.now()), id);
        audit(user, "followup-script." + action, "随访话术模板 " + existing.get("label") + " → " + target);
        return get(id);
    }

    // ---------- internals ----------

    /** 启动/首次访问时播种默认话术（表空才播种，不覆盖管理端修改）。 */
    private void ensureSeeded() {
        if (seeded) return;
        synchronized (this) {
            if (seeded) return;
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM clinic_followup_script_templates", Integer.class);
            if (count != null && count > 0) {
                seeded = true;
                return;
            }
            try {
                String content = new String(
                    getClass().getResourceAsStream("/clinic/followup-script-seed.txt").readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
                String now = TIME.format(LocalDateTime.now());
                jdbcTemplate.update(
                    "INSERT INTO clinic_followup_script_templates (id, node_key, label, content, status, sort_order, created_by, created_at, updated_by, updated_at) "
                        + "VALUES ('fus-default', ?, '默认随访话术', ?, 'ACTIVE', 0, 'system', ?, 'system', ?)",
                    DEFAULT_NODE_KEY, content, now, now);
            } catch (Exception ignored) {
                // 播种失败时前端回退内置话术
            }
            seeded = true;
        }
    }

    private Map<String, Object> mapRow(java.sql.ResultSet resultSet, int rowNum) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", resultSet.getString("id"));
        row.put("nodeKey", resultSet.getString("node_key"));
        row.put("label", resultSet.getString("label"));
        row.put("content", resultSet.getString("content"));
        row.put("status", resultSet.getString("status"));
        row.put("sortOrder", resultSet.getInt("sort_order"));
        row.put("updatedBy", resultSet.getString("updated_by"));
        row.put("updatedAt", resultSet.getString("updated_at"));
        return row;
    }

    private Map<String, Object> get(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
            "SELECT id, node_key, label, content, status, sort_order, updated_by, updated_at "
                + "FROM clinic_followup_script_templates WHERE id = ?",
            this::mapRow, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "话术模板不存在");
        return rows.get(0);
    }

    private void requireManageRole(SessionUser user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        if (!MANAGE_ROLES.contains(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅护理、医生或管理员可管理随访话术模板库");
        }
    }

    private void audit(SessionUser user, String action, String detail) {
        try {
            encounterService.auditExternal(action, user, detail);
        } catch (Exception ignored) {
            // 审计失败不阻断主流程
        }
    }

    private String text(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int intOf(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception error) {
            return 0;
        }
    }
}
