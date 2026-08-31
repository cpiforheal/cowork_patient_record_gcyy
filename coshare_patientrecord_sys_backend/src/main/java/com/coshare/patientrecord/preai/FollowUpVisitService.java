package com.coshare.patientrecord.preai;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.medicalrecord.service.MedicalRecordSourceBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 复诊随访：由检查室岗位随检查创建，时间轴形式沉淀；数据仅供后置科室查看，
 * 独立存储、独立表，不参与前置病历导出与 AI 成档病历内容生成。
 */
@Service
@Profile("mysql")
public class FollowUpVisitService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> MANAGE_ROLES = Set.of("inspection", "admin", "doctor");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MedicalRecordSourceBuilder sourceBuilder;
    private final Path archiveRoot;

    public FollowUpVisitService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        MedicalRecordSourceBuilder sourceBuilder,
        @Value("${clinic.attachment-dir:runtime/clinic-attachments}") String attachmentDir
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.sourceBuilder = sourceBuilder;
        this.archiveRoot = Path.of(attachmentDir).toAbsolutePath().normalize().resolve("follow-up");
    }

    public Map<String, Object> create(String encounterId, JsonNode body, SessionUser user) {
        requireEncounter(encounterId);
        requireManageRole(user, "仅检查室、医生或管理员可创建复诊记录");
        assertCanRead(encounterId, user);
        String reason = text(body, "reason");
        if (reason.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "复诊原因不能为空");
        String conditionNote = text(body, "conditionNote");
        String nextReviewDate = text(body, "nextReviewDate");

        String visitId = "fuv-" + UUID.randomUUID();
        String now = TIME.format(LocalDateTime.now());
        int seq = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(seq), 0) + 1 FROM pre_ai_follow_up_visits WHERE encounter_id = ?", Integer.class, encounterId);
        jdbcTemplate.update("""
            INSERT INTO pre_ai_follow_up_visits (
              id, encounter_id, seq, reason, condition_note, next_review_date, status,
              created_by, created_by_role, created_at, updated_by, updated_by_role, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?)
            """,
            visitId, encounterId, seq, truncate(reason, 500), conditionNote, nextReviewDate,
            user.name(), user.role(), now, user.name(), user.role(), now
        );
        JsonNode images = body.path("images");
        if (images.isArray()) {
            for (JsonNode image : images) {
                storeImage(visitId, encounterId, image.path("fileName").asText("复诊图片"), image.path("dataUrl").asText(""), user);
            }
        }
        audit(encounterId, "followup.create", user, "创建第 " + seq + " 次复诊记录：" + truncate(reason, 80));
        return visitWithImages(visitId);
    }

    public Map<String, Object> list(String encounterId, SessionUser user) {
        requireEncounter(encounterId);
        assertCanRead(encounterId, user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("encounterId", encounterId);
        result.put("canManage", MANAGE_ROLES.contains(user.role()));
        result.put("visits", visits(encounterId));
        return result;
    }

    public Map<String, Object> addImage(String visitId, JsonNode body, SessionUser user) {
        Map<String, String> visit = loadVisit(visitId);
        requireManageRole(user, "仅检查室、医生或管理员可上传复诊图片");
        assertCanRead(visit.get("encounterId"), user);
        String fileName = text(body, "fileName");
        if (fileName.isBlank()) fileName = "复诊图片";
        storeImage(visitId, visit.get("encounterId"), fileName, text(body, "dataUrl"), user);
        audit(visit.get("encounterId"), "followup.image.upload", user, "复诊记录补充图片");
        return visitWithImages(visitId);
    }

    public Map<String, Object> removeImage(String visitId, String imageId, SessionUser user) {
        Map<String, String> visit = loadVisit(visitId);
        requireManageRole(user, "仅检查室、医生或管理员可删除复诊图片");
        assertCanRead(visit.get("encounterId"), user);
        Map<String, String> image = loadImage(visitId, imageId);
        jdbcTemplate.update("DELETE FROM pre_ai_follow_up_images WHERE id = ? AND visit_id = ?", imageId, visitId);
        try {
            Files.deleteIfExists(Path.of(image.get("storagePath")).toAbsolutePath().normalize());
        } catch (Exception ignored) {
            // 文件清理失败不阻断主流程
        }
        audit(visit.get("encounterId"), "followup.image.remove", user, "删除复诊图片");
        return visitWithImages(visitId);
    }

    public Map<String, String> imageContent(String imageId, SessionUser user) {
        List<Map<String, String>> rows = jdbcTemplate.query(
            "SELECT visit_id, encounter_id, file_name, storage_path, mime_type FROM pre_ai_follow_up_images WHERE id = ?",
            (resultSet, rowNum) -> Map.of(
                "visitId", resultSet.getString("visit_id"),
                "encounterId", resultSet.getString("encounter_id"),
                "fileName", resultSet.getString("file_name"),
                "storagePath", resultSet.getString("storage_path"),
                "mimeType", resultSet.getString("mime_type")
            ),
            imageId
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "复诊图片不存在");
        Map<String, String> image = rows.get(0);
        assertCanRead(image.get("encounterId"), user);
        return image;
    }

    // ---------- internals ----------

    private String storeImage(String visitId, String encounterId, String fileName, String dataUrl, SessionUser user) {
        if (dataUrl == null || !dataUrl.startsWith("data:image/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "复诊图片格式不正确，仅支持图片文件");
        }
        int comma = dataUrl.indexOf(";base64,");
        if (comma < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "复诊图片编码不正确");
        String mimeType = dataUrl.substring(5, comma);
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(dataUrl.substring(comma + ";base64,".length()));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "复诊图片解码失败，请重新选择文件");
        }
        String extension = switch (mimeType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".img";
        };
        int seq = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(seq), 0) + 1 FROM pre_ai_follow_up_images WHERE visit_id = ?", Integer.class, visitId);
        String imageId = "fuimg-" + UUID.randomUUID();
        Path directory = archiveRoot.resolve(encounterId).resolve(visitId);
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(imageId + extension);
            Files.write(target, bytes);
            jdbcTemplate.update("""
                INSERT INTO pre_ai_follow_up_images (
                  id, visit_id, encounter_id, seq, file_name, storage_path, mime_type, file_size, created_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                imageId, visitId, encounterId, seq, truncate(fileName, 250), target.toString(), mimeType,
                (long) bytes.length, user.name(), TIME.format(LocalDateTime.now())
            );
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "复诊图片写入失败", error);
        }
        return imageId;
    }

    private ArrayNode visits(String encounterId) {
        ArrayNode result = objectMapper.createArrayNode();
        jdbcTemplate.query(
            "SELECT id, seq, reason, condition_note, next_review_date, status, created_by, created_by_role, created_at "
                + "FROM pre_ai_follow_up_visits WHERE encounter_id = ? ORDER BY seq ASC",
            resultSet -> {
                String visitId = resultSet.getString("id");
                ObjectNode visit = result.addObject();
                visit.put("id", visitId);
                visit.put("seq", resultSet.getInt("seq"));
                visit.put("reason", resultSet.getString("reason"));
                visit.put("conditionNote", resultSet.getString("condition_note"));
                visit.put("nextReviewDate", resultSet.getString("next_review_date"));
                visit.put("status", resultSet.getString("status"));
                visit.put("createdBy", resultSet.getString("created_by"));
                visit.put("createdByRole", resultSet.getString("created_by_role"));
                visit.put("createdAt", resultSet.getString("created_at"));
                ArrayNode images = visit.putArray("images");
                jdbcTemplate.query(
                    "SELECT id, file_name, seq FROM pre_ai_follow_up_images WHERE visit_id = ? ORDER BY seq ASC",
                    imageResultSet -> {
                        ObjectNode image = images.addObject();
                        image.put("id", imageResultSet.getString("id"));
                        image.put("fileName", imageResultSet.getString("file_name"));
                        image.put("url", "/clinic-api/follow-up/visits/images/" + imageResultSet.getString("id") + "/file");
                    },
                    visitId
                );
            },
            encounterId
        );
        return result;
    }

    private Map<String, Object> visitWithImages(String visitId) {
        Map<String, Object> result = new LinkedHashMap<>(loadVisit(visitId));
        List<Map<String, Object>> images = new ArrayList<>();
        jdbcTemplate.query(
            "SELECT id, file_name, seq FROM pre_ai_follow_up_images WHERE visit_id = ? ORDER BY seq ASC",
            resultSet -> {
                images.add(Map.of(
                    "id", resultSet.getString("id"),
                    "fileName", resultSet.getString("file_name"),
                    "seq", resultSet.getInt("seq")
                ));
            },
            visitId
        );
        result.put("images", images);
        return result;
    }

    private Map<String, String> loadVisit(String visitId) {
        List<Map<String, String>> rows = jdbcTemplate.query(
            "SELECT encounter_id, reason FROM pre_ai_follow_up_visits WHERE id = ?",
            (resultSet, rowNum) -> Map.of(
                "encounterId", resultSet.getString("encounter_id"),
                "reason", resultSet.getString("reason")
            ),
            visitId
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "复诊记录不存在");
        return rows.get(0);
    }

    private Map<String, String> loadImage(String visitId, String imageId) {
        List<Map<String, String>> rows = jdbcTemplate.query(
            "SELECT storage_path, file_name FROM pre_ai_follow_up_images WHERE id = ? AND visit_id = ?",
            (resultSet, rowNum) -> Map.of(
                "storagePath", resultSet.getString("storage_path"),
                "fileName", resultSet.getString("file_name")
            ),
            imageId, visitId
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "复诊图片不存在");
        return rows.get(0);
    }

    private void assertCanRead(String encounterId, SessionUser user) {
        sourceBuilder.assertCanReadScope("preai:" + encounterId, user);
    }

    private void requireManageRole(SessionUser user, String message) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        if (!MANAGE_ROLES.contains(user.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    private void requireEncounter(String encounterId) {
        if (encounterId == null || encounterId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少前置病例ID");
        }
    }

    private void audit(String encounterId, String action, SessionUser user, String detail) {
        try {
            jdbcTemplate.update(
                "INSERT INTO pre_ai_audit_logs (id, encounter_id, action, stage_code, operator, operator_role, detail, created_at) "
                    + "VALUES (?, ?, ?, 'INSPECTION', ?, ?, ?, ?)",
                "audit-" + UUID.randomUUID(), encounterId, action, user.name(), user.role(), truncate(detail, 500),
                TIME.format(LocalDateTime.now())
            );
        } catch (Exception ignored) {
            // 审计失败不阻断主流程
        }
    }

    private String truncate(String value, int limit) {
        String text = value == null ? "" : value.trim();
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }
}
