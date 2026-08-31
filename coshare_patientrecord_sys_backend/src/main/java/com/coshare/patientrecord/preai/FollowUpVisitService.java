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
 * 复诊随访：复诊患者不经前台登记，由检查室按患者主档案直接创建（锚点 = 患者病例 patientCaseId），
 * 时间轴按患者累计第 N 次；后置科室可查看；独立表存储，不参与前置病历导出与 AI 成档病历内容生成。
 */
@Service
@Profile("mysql")
public class FollowUpVisitService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> MANAGE_ROLES = Set.of("inspection", "admin", "doctor", "tcm");
    private static final Set<String> VIEW_ROLES =
        Set.of("admin", "quality", "reception", "inspection", "tcm", "doctor", "nurse", "lab", "ecg", "ultrasound");

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

    public Map<String, Object> create(String patientCaseId, JsonNode body, SessionUser user) {
        requirePatientCase(patientCaseId);
        requireManageRole(user, "仅检查室、医生或管理员可创建复诊记录");
        assertCanReadPatientCase(patientCaseId, user);
        String reason = text(body, "reason");
        if (reason.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "复诊原因不能为空");
        String conditionNote = text(body, "conditionNote");
        String nextReviewDate = text(body, "nextReviewDate");
        String encounterId = text(body, "encounterId");

        String visitId = "fuv-" + UUID.randomUUID();
        String now = TIME.format(LocalDateTime.now());
        int seq = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(seq), 0) + 1 FROM pre_ai_follow_up_visits WHERE patient_case_id = ?",
            Integer.class, patientCaseId);
        jdbcTemplate.update("""
            INSERT INTO pre_ai_follow_up_visits (
              id, encounter_id, patient_case_id, seq, reason, condition_note, next_review_date, status,
              created_by, created_by_role, created_at, updated_by, updated_by_role, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?)
            """,
            visitId, encounterId, patientCaseId, seq, truncate(reason, 500), conditionNote, nextReviewDate,
            user.name(), user.role(), now, user.name(), user.role(), now
        );
        JsonNode images = body.path("images");
        if (images.isArray()) {
            for (JsonNode image : images) {
                storeImage(visitId, patientCaseId, encounterId, image.path("fileName").asText("复诊图片"),
                    image.path("dataUrl").asText(""), user);
            }
        }
        audit(patientCaseId, "followup.create", user, "创建第 " + seq + " 次复诊记录：" + truncate(reason, 80));
        return visitWithImages(visitId);
    }

    public Map<String, Object> list(String patientCaseId, SessionUser user) {
        requirePatientCase(patientCaseId);
        assertCanReadPatientCase(patientCaseId, user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patientCaseId", patientCaseId);
        result.put("canManage", MANAGE_ROLES.contains(user.role()));
        result.put("visits", visits(patientCaseId));
        return result;
    }

    public Map<String, Object> addImage(String visitId, JsonNode body, SessionUser user) {
        Map<String, String> visit = loadVisit(visitId);
        requireManageRole(user, "仅检查室、医生或管理员可上传复诊图片");
        assertCanReadPatientCase(visit.get("patientCaseId"), user);
        String fileName = text(body, "fileName");
        if (fileName.isBlank()) fileName = "复诊图片";
        storeImage(visitId, visit.get("patientCaseId"), visit.get("encounterId"), fileName, text(body, "dataUrl"), user);
        audit(visit.get("patientCaseId"), "followup.image.upload", user, "复诊记录补充图片");
        return visitWithImages(visitId);
    }

    public Map<String, Object> removeImage(String visitId, String imageId, SessionUser user) {
        Map<String, String> visit = loadVisit(visitId);
        requireManageRole(user, "仅检查室、医生或管理员可删除复诊图片");
        assertCanReadPatientCase(visit.get("patientCaseId"), user);
        Map<String, String> image = loadImage(visitId, imageId);
        jdbcTemplate.update("DELETE FROM pre_ai_follow_up_images WHERE id = ? AND visit_id = ?", imageId, visitId);
        try {
            Files.deleteIfExists(Path.of(image.get("storagePath")).toAbsolutePath().normalize());
        } catch (Exception ignored) {
            // 文件清理失败不阻断主流程
        }
        audit(visit.get("patientCaseId"), "followup.image.remove", user, "删除复诊图片");
        return visitWithImages(visitId);
    }

    public Map<String, String> imageContent(String imageId, SessionUser user) {
        List<Map<String, String>> rows = jdbcTemplate.query(
            "SELECT v.patient_case_id, i.file_name, i.storage_path, i.mime_type "
                + "FROM pre_ai_follow_up_images i JOIN pre_ai_follow_up_visits v ON v.id = i.visit_id WHERE i.id = ?",
            (resultSet, rowNum) -> Map.of(
                "patientCaseId", resultSet.getString("patient_case_id"),
                "fileName", resultSet.getString("file_name"),
                "storagePath", resultSet.getString("storage_path"),
                "mimeType", resultSet.getString("mime_type")
            ),
            imageId
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "复诊图片不存在");
        Map<String, String> image = rows.get(0);
        assertCanReadPatientCase(image.get("patientCaseId"), user);
        return image;
    }

    // ---------- internals ----------

    private String storeImage(String visitId, String patientCaseId, String encounterId, String fileName,
                              String dataUrl, SessionUser user) {
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
        Path directory = archiveRoot.resolve(patientCaseId).resolve(visitId);
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

    private ArrayNode visits(String patientCaseId) {
        ArrayNode result = objectMapper.createArrayNode();
        List<ObjectNode> visitNodes = new ArrayList<>();
        Map<String, ObjectNode> visitById = new LinkedHashMap<>();
        jdbcTemplate.query(
            "SELECT id, seq, reason, condition_note, next_review_date, status, created_by, created_by_role, created_at "
                + "FROM pre_ai_follow_up_visits WHERE patient_case_id = ? ORDER BY seq ASC",
            resultSet -> {
                String visitId = resultSet.getString("id");
                ObjectNode visit = objectMapper.createObjectNode();
                visit.put("id", visitId);
                visit.put("seq", resultSet.getInt("seq"));
                visit.put("reason", resultSet.getString("reason"));
                visit.put("conditionNote", resultSet.getString("condition_note"));
                visit.put("nextReviewDate", resultSet.getString("next_review_date"));
                visit.put("status", resultSet.getString("status"));
                visit.put("createdBy", resultSet.getString("created_by"));
                visit.put("createdByRole", resultSet.getString("created_by_role"));
                visit.put("createdAt", resultSet.getString("created_at"));
                visit.set("images", objectMapper.createArrayNode());
                visitById.put(visitId, visit);
                visitNodes.add(visit);
            },
            patientCaseId
        );
        jdbcTemplate.query(
            "SELECT visit_id, id, file_name FROM pre_ai_follow_up_images WHERE encounter_id IN "
                + "(SELECT encounter_id FROM pre_ai_follow_up_visits WHERE patient_case_id = ?) ORDER BY seq ASC",
            resultSet -> {
                ObjectNode visit = visitById.get(resultSet.getString("visit_id"));
                if (visit == null) return;
                ObjectNode image = ((ArrayNode) visit.get("images")).addObject();
                image.put("id", resultSet.getString("id"));
                image.put("fileName", resultSet.getString("file_name"));
                image.put("url", "/clinic-api/follow-up/visits/images/" + resultSet.getString("id") + "/file");
            },
            patientCaseId
        );
        result.addAll(visitNodes);
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
            "SELECT patient_case_id, encounter_id, seq, reason FROM pre_ai_follow_up_visits WHERE id = ?",
            (resultSet, rowNum) -> Map.of(
                "patientCaseId", resultSet.getString("patient_case_id"),
                "encounterId", resultSet.getString("encounter_id"),
                "seq", String.valueOf(resultSet.getInt("seq")),
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

    /** 复诊查看权限：患者病例下任一就诊可读即放行；无就诊的病例回退到岗位白名单。 */
    private void assertCanReadPatientCase(String patientCaseId, SessionUser user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        List<String> encounterIds = jdbcTemplate.queryForList(
            "SELECT id FROM pre_ai_encounters WHERE patient_case_id = ? LIMIT 1", String.class, patientCaseId);
        if (!encounterIds.isEmpty()) {
            sourceBuilder.assertCanReadScope("preai:" + encounterIds.get(0), user);
            return;
        }
        if (!VIEW_ROLES.contains(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无权查看该患者复诊记录");
        }
    }

    private void requireManageRole(SessionUser user, String message) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        if (!MANAGE_ROLES.contains(user.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    private void requirePatientCase(String patientCaseId) {
        if (patientCaseId == null || patientCaseId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者病例ID");
        }
    }

    private void audit(String patientCaseId, String action, SessionUser user, String detail) {
        try {
            jdbcTemplate.update(
                "INSERT INTO pre_ai_audit_logs (id, encounter_id, action, stage_code, operator, operator_role, detail, created_at) "
                    + "VALUES (?, '', ?, 'INSPECTION', ?, ?, ?, ?)",
                "audit-" + UUID.randomUUID(), action, user.name(), user.role(), truncate(detail, 500),
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
