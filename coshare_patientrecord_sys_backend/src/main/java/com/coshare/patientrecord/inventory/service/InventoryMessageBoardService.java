package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class InventoryMessageBoardService {

    private static final Set<String> CATEGORIES = Set.of("NEW_ITEM", "DATA_CORRECTION", "SUGGESTION", "OTHER");
    private static final Set<String> STATUSES = Set.of("PENDING", "FOLLOWING", "COMPLETED", "REJECTED");
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public InventoryMessageBoardService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> posts(
        SessionUser user,
        boolean administrator,
        String keyword,
        String category,
        String status,
        String departmentKey,
        boolean onlyMine,
        int page,
        int size
    ) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!administrator) where.append(" AND p.hidden = FALSE");
        if (notBlank(keyword)) {
            where.append(" AND (p.title LIKE ? OR p.content LIKE ?)");
            String pattern = "%" + keyword.trim() + "%";
            args.add(pattern);
            args.add(pattern);
        }
        if (notBlank(category)) {
            String normalized = requireCategory(category);
            where.append(" AND p.category = ?");
            args.add(normalized);
        }
        if (notBlank(status)) {
            String normalized = requireStatus(status);
            where.append(" AND p.process_status = ?");
            args.add(normalized);
        }
        if (notBlank(departmentKey)) {
            where.append(" AND p.department_key = ?");
            args.add(departmentKey.trim());
        }
        if (onlyMine) {
            where.append(" AND p.author_id = ?");
            args.add(user.id());
        }

        Long total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM inventory_message_board_posts p" + where,
            Long.class,
            args.toArray()
        );
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safeSize);
        queryArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> list = jdbcTemplate.query(
            "SELECT p.*, (SELECT COUNT(*) FROM inventory_message_board_replies r WHERE r.post_id = p.id AND r.hidden = FALSE) reply_count "
                + "FROM inventory_message_board_posts p" + where
                + " ORDER BY p.pinned DESC, p.last_activity_at DESC, p.created_at DESC LIMIT ? OFFSET ?",
            (rs, rowNum) -> postRow(rs, user, administrator),
            queryArgs.toArray()
        );
        return Map.of("list", list, "total", total == null ? 0L : total, "page", safePage, "size", safeSize);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> postDetail(String postId, SessionUser user, boolean administrator) {
        Map<String, Object> post = requirePostView(postId, user, administrator);
        String visibility = administrator ? "" : " AND hidden = FALSE";
        List<Map<String, Object>> replies = jdbcTemplate.query(
            "SELECT * FROM inventory_message_board_replies WHERE post_id = ?" + visibility + " ORDER BY created_at ASC",
            (rs, rowNum) -> replyRow(rs, user, administrator),
            postId
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("post", post);
        result.put("replies", replies);
        return result;
    }

    @Transactional
    public Map<String, Object> createPost(JsonNode payload, SessionUser user, boolean administrator) {
        String title = requiredText(payload, "title", 100, "标题");
        String content = requiredText(payload, "content", 2000, "正文");
        String category = requireCategory(text(payload, "category"));
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
            "INSERT INTO inventory_message_board_posts "
                + "(id, title, content, category, process_status, author_id, author_username, author_name, department_key, department_name) "
                + "VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?)",
            id, title, content, category, user.id(), value(user.username()), value(user.name()), value(user.activeDepartmentId()), value(user.department())
        );
        audit("POST", id, "CREATE_POST", user, Map.of("title", title, "category", category));
        return requirePostView(id, user, administrator);
    }

    @Transactional
    public Map<String, Object> updatePost(String postId, JsonNode payload, SessionUser user, boolean administrator) {
        PostState post = requirePostState(postId);
        requireAuthor(post.authorId(), user, "只能编辑自己发布的主题");
        requireEditable(post.withdrawn(), post.hidden());
        String title = requiredText(payload, "title", 100, "标题");
        String content = requiredText(payload, "content", 2000, "正文");
        String category = requireCategory(text(payload, "category"));
        jdbcTemplate.update(
            "UPDATE inventory_message_board_posts SET title = ?, content = ?, category = ?, updated_at = CURRENT_TIMESTAMP(6), last_activity_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
            title, content, category, postId
        );
        audit("POST", postId, "EDIT_POST", user, Map.of("title", title, "category", category));
        return requirePostView(postId, user, administrator);
    }

    @Transactional
    public void withdrawPost(String postId, SessionUser user) {
        PostState post = requirePostState(postId);
        requireAuthor(post.authorId(), user, "只能撤回自己发布的主题");
        if (post.withdrawn()) throw conflict("主题已经撤回");
        jdbcTemplate.update(
            "UPDATE inventory_message_board_posts SET withdrawn = TRUE, withdrawn_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
            postId
        );
        audit("POST", postId, "WITHDRAW_POST", user, Map.of());
    }

    @Transactional
    public Map<String, Object> createReply(String postId, JsonNode payload, SessionUser user, boolean administrator) {
        PostState post = requirePostState(postId);
        if (post.hidden() && !administrator) throw notFound("主题不存在或不可见");
        if (post.withdrawn()) throw conflict("主题已撤回，不能继续回复");
        String content = requiredText(payload, "content", 2000, "回复");
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
            "INSERT INTO inventory_message_board_replies "
                + "(id, post_id, content, author_id, author_username, author_name, department_key, department_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, postId, content, user.id(), value(user.username()), value(user.name()), value(user.activeDepartmentId()), value(user.department())
        );
        jdbcTemplate.update("UPDATE inventory_message_board_posts SET last_activity_at = CURRENT_TIMESTAMP(6) WHERE id = ?", postId);
        audit("REPLY", id, "CREATE_REPLY", user, Map.of("postId", postId));
        return requireReplyView(id, user, administrator);
    }

    @Transactional
    public Map<String, Object> updateReply(String replyId, JsonNode payload, SessionUser user, boolean administrator) {
        ReplyState reply = requireReplyState(replyId);
        requireAuthor(reply.authorId(), user, "只能编辑自己的回复");
        requireEditable(reply.withdrawn(), reply.hidden());
        String content = requiredText(payload, "content", 2000, "回复");
        jdbcTemplate.update(
            "UPDATE inventory_message_board_replies SET content = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
            content, replyId
        );
        jdbcTemplate.update("UPDATE inventory_message_board_posts SET last_activity_at = CURRENT_TIMESTAMP(6) WHERE id = ?", reply.postId());
        audit("REPLY", replyId, "EDIT_REPLY", user, Map.of("postId", reply.postId()));
        return requireReplyView(replyId, user, administrator);
    }

    @Transactional
    public void withdrawReply(String replyId, SessionUser user) {
        ReplyState reply = requireReplyState(replyId);
        requireAuthor(reply.authorId(), user, "只能撤回自己的回复");
        if (reply.withdrawn()) throw conflict("回复已经撤回");
        jdbcTemplate.update(
            "UPDATE inventory_message_board_replies SET withdrawn = TRUE, withdrawn_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
            replyId
        );
        audit("REPLY", replyId, "WITHDRAW_REPLY", user, Map.of("postId", reply.postId()));
    }

    @Transactional
    public Map<String, Object> updateStatus(String postId, JsonNode payload, SessionUser administrator) {
        requirePostState(postId);
        String status = requireStatus(text(payload, "status"));
        String note = optionalText(payload, "handlingNote", 2000, "处理说明");
        jdbcTemplate.update(
            "UPDATE inventory_message_board_posts SET process_status = ?, handling_note = ?, updated_at = CURRENT_TIMESTAMP(6), last_activity_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
            status, note.isBlank() ? null : note, postId
        );
        audit("POST", postId, "UPDATE_STATUS", administrator, Map.of("status", status, "handlingNote", note));
        return requirePostView(postId, administrator, true);
    }

    @Transactional
    public Map<String, Object> updatePinned(String postId, boolean pinned, SessionUser administrator) {
        requirePostState(postId);
        jdbcTemplate.update(
            "UPDATE inventory_message_board_posts SET pinned = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
            pinned, postId
        );
        audit("POST", postId, pinned ? "PIN_POST" : "UNPIN_POST", administrator, Map.of("pinned", pinned));
        return requirePostView(postId, administrator, true);
    }

    @Transactional
    public Map<String, Object> updatePostVisibility(String postId, boolean hidden, SessionUser administrator) {
        requirePostState(postId);
        jdbcTemplate.update(
            "UPDATE inventory_message_board_posts SET hidden = ?, hidden_at = CASE WHEN ? THEN CURRENT_TIMESTAMP(6) ELSE NULL END, hidden_by = CASE WHEN ? THEN ? ELSE NULL END, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
            hidden, hidden, hidden, value(administrator.username()), postId
        );
        audit("POST", postId, hidden ? "HIDE_POST" : "RESTORE_POST", administrator, Map.of("hidden", hidden));
        return requirePostView(postId, administrator, true);
    }

    @Transactional
    public Map<String, Object> updateReplyVisibility(String replyId, boolean hidden, SessionUser administrator) {
        ReplyState reply = requireReplyState(replyId);
        jdbcTemplate.update(
            "UPDATE inventory_message_board_replies SET hidden = ?, hidden_at = CASE WHEN ? THEN CURRENT_TIMESTAMP(6) ELSE NULL END, hidden_by = CASE WHEN ? THEN ? ELSE NULL END, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
            hidden, hidden, hidden, value(administrator.username()), replyId
        );
        audit("REPLY", replyId, hidden ? "HIDE_REPLY" : "RESTORE_REPLY", administrator, Map.of("hidden", hidden, "postId", reply.postId()));
        return requireReplyView(replyId, administrator, true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> auditLogs(String targetType, String targetId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (notBlank(targetType)) {
            String normalized = targetType.trim().toUpperCase();
            if (!Set.of("POST", "REPLY").contains(normalized)) throw badRequest("操作对象类型不正确");
            where.append(" AND target_type = ?");
            args.add(normalized);
        }
        if (notBlank(targetId)) {
            where.append(" AND target_id = ?");
            args.add(targetId.trim());
        }
        Long total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM inventory_message_board_audit_logs" + where,
            Long.class,
            args.toArray()
        );
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safeSize);
        queryArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> list = jdbcTemplate.query(
            "SELECT * FROM inventory_message_board_audit_logs" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
            (rs, rowNum) -> auditRow(rs),
            queryArgs.toArray()
        );
        return Map.of("list", list, "total", total == null ? 0L : total, "page", safePage, "size", safeSize);
    }

    private Map<String, Object> requirePostView(String postId, SessionUser user, boolean administrator) {
        return jdbcTemplate.query(
            "SELECT p.*, (SELECT COUNT(*) FROM inventory_message_board_replies r WHERE r.post_id = p.id AND r.hidden = FALSE) reply_count FROM inventory_message_board_posts p WHERE p.id = ?",
            rs -> {
                if (!rs.next()) throw notFound("主题不存在");
                if (rs.getBoolean("hidden") && !administrator) throw notFound("主题不存在或不可见");
                return postRow(rs, user, administrator);
            },
            postId
        );
    }

    private Map<String, Object> requireReplyView(String replyId, SessionUser user, boolean administrator) {
        return jdbcTemplate.query(
            "SELECT * FROM inventory_message_board_replies WHERE id = ?",
            rs -> {
                if (!rs.next()) throw notFound("回复不存在");
                if (rs.getBoolean("hidden") && !administrator) throw notFound("回复不存在或不可见");
                return replyRow(rs, user, administrator);
            },
            replyId
        );
    }

    private PostState requirePostState(String postId) {
        return jdbcTemplate.query(
            "SELECT id, author_id, hidden, withdrawn FROM inventory_message_board_posts WHERE id = ?",
            rs -> rs.next()
                ? new PostState(rs.getString("id"), rs.getString("author_id"), rs.getBoolean("hidden"), rs.getBoolean("withdrawn"))
                : throwNotFoundPost(),
            postId
        );
    }

    private ReplyState requireReplyState(String replyId) {
        return jdbcTemplate.query(
            "SELECT id, post_id, author_id, hidden, withdrawn FROM inventory_message_board_replies WHERE id = ?",
            rs -> rs.next()
                ? new ReplyState(rs.getString("id"), rs.getString("post_id"), rs.getString("author_id"), rs.getBoolean("hidden"), rs.getBoolean("withdrawn"))
                : throwNotFoundReply(),
            replyId
        );
    }

    private PostState throwNotFoundPost() {
        throw notFound("主题不存在");
    }

    private ReplyState throwNotFoundReply() {
        throw notFound("回复不存在");
    }

    private Map<String, Object> postRow(ResultSet rs, SessionUser user, boolean administrator) throws SQLException {
        boolean withdrawn = rs.getBoolean("withdrawn");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id"));
        row.put("title", withdrawn ? "内容已撤回" : rs.getString("title"));
        row.put("content", withdrawn ? "内容已撤回" : rs.getString("content"));
        row.put("category", rs.getString("category"));
        row.put("status", rs.getString("process_status"));
        row.put("handlingNote", value(rs.getString("handling_note")));
        row.put("authorId", rs.getString("author_id"));
        row.put("authorUsername", rs.getString("author_username"));
        row.put("authorName", rs.getString("author_name"));
        row.put("departmentKey", rs.getString("department_key"));
        row.put("departmentName", rs.getString("department_name"));
        row.put("pinned", rs.getBoolean("pinned"));
        row.put("hidden", rs.getBoolean("hidden"));
        row.put("withdrawn", withdrawn);
        row.put("replyCount", hasColumn(rs, "reply_count") ? rs.getInt("reply_count") : 0);
        row.put("createdAt", localDateTime(rs, "created_at"));
        row.put("updatedAt", localDateTime(rs, "updated_at"));
        row.put("lastActivityAt", localDateTime(rs, "last_activity_at"));
        boolean own = user.id().equals(rs.getString("author_id"));
        row.put("mine", own);
        row.put("canEdit", own && !withdrawn && !rs.getBoolean("hidden"));
        row.put("canWithdraw", own && !withdrawn);
        row.put("administrator", administrator);
        return row;
    }

    private Map<String, Object> replyRow(ResultSet rs, SessionUser user, boolean administrator) throws SQLException {
        boolean withdrawn = rs.getBoolean("withdrawn");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id"));
        row.put("postId", rs.getString("post_id"));
        row.put("content", withdrawn ? "内容已撤回" : rs.getString("content"));
        row.put("authorId", rs.getString("author_id"));
        row.put("authorUsername", rs.getString("author_username"));
        row.put("authorName", rs.getString("author_name"));
        row.put("departmentKey", rs.getString("department_key"));
        row.put("departmentName", rs.getString("department_name"));
        row.put("hidden", rs.getBoolean("hidden"));
        row.put("withdrawn", withdrawn);
        row.put("createdAt", localDateTime(rs, "created_at"));
        row.put("updatedAt", localDateTime(rs, "updated_at"));
        boolean own = user.id().equals(rs.getString("author_id"));
        row.put("mine", own);
        row.put("canEdit", own && !withdrawn && !rs.getBoolean("hidden"));
        row.put("canWithdraw", own && !withdrawn);
        row.put("administrator", administrator);
        return row;
    }

    private Map<String, Object> auditRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("targetType", rs.getString("target_type"));
        row.put("targetId", rs.getString("target_id"));
        row.put("action", rs.getString("action"));
        row.put("operatorId", rs.getString("operator_id"));
        row.put("operatorUsername", rs.getString("operator_username"));
        row.put("operatorName", rs.getString("operator_name"));
        row.put("departmentKey", rs.getString("department_key"));
        row.put("departmentName", rs.getString("department_name"));
        row.put("detail", parseDetail(rs.getString("detail_json")));
        row.put("createdAt", localDateTime(rs, "created_at"));
        return row;
    }

    private void audit(String targetType, String targetId, String action, SessionUser user, Map<String, Object> detail) {
        jdbcTemplate.update(
            "INSERT INTO inventory_message_board_audit_logs "
                + "(target_type, target_id, action, operator_id, operator_username, operator_name, department_key, department_name, detail_json) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            targetType, targetId, action, user.id(), value(user.username()), value(user.name()), value(user.activeDepartmentId()), value(user.department()), json(detail)
        );
    }

    private Object parseDetail(String detail) {
        if (!notBlank(detail)) return Map.of();
        try {
            return objectMapper.readValue(detail, Object.class);
        } catch (JsonProcessingException ignored) {
            return detail;
        }
    }

    private String json(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法记录留言板操作日志", error);
        }
    }

    private String requiredText(JsonNode payload, String field, int maxLength, String label) {
        String value = text(payload, field);
        if (value.isBlank()) throw badRequest(label + "不能为空");
        if (value.length() > maxLength) throw badRequest(label + "不能超过 " + maxLength + " 字");
        return value;
    }

    private String optionalText(JsonNode payload, String field, int maxLength, String label) {
        String value = text(payload, field);
        if (value.length() > maxLength) throw badRequest(label + "不能超过 " + maxLength + " 字");
        return value;
    }

    private String requireCategory(String category) {
        String normalized = value(category).trim().toUpperCase();
        if (!CATEGORIES.contains(normalized)) throw badRequest("需求分类不正确");
        return normalized;
    }

    private String requireStatus(String status) {
        String normalized = value(status).trim().toUpperCase();
        if (!STATUSES.contains(normalized)) throw badRequest("处理状态不正确");
        return normalized;
    }

    private void requireAuthor(String authorId, SessionUser user, String message) {
        if (!user.id().equals(authorId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    private void requireEditable(boolean withdrawn, boolean hidden) {
        if (withdrawn) throw conflict("内容已撤回，不能继续编辑");
        if (hidden) throw conflict("内容已被管理员隐藏，不能继续编辑");
    }

    private static String text(JsonNode payload, String field) {
        return payload == null ? "" : value(payload.path(field).asText("")).trim();
    }

    private static boolean hasColumn(ResultSet rs, String column) {
        try {
            rs.findColumn(column);
            return true;
        } catch (SQLException ignored) {
            return false;
        }
    }

    private static LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record PostState(String id, String authorId, boolean hidden, boolean withdrawn) {}
    private record ReplyState(String id, String postId, String authorId, boolean hidden, boolean withdrawn) {}
}