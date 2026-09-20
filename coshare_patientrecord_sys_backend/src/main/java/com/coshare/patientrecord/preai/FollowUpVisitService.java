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
import java.time.LocalDate;
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
    /** 复诊记录内容校准（编辑）：仅医生岗与管理员——创建时间由系统精确记录，编辑仅对齐内容描述精度 */
    private static final Set<String> EDIT_ROLES = Set.of("doctor", "admin");
    private static final Set<String> VIEW_ROLES =
        Set.of("admin", "quality", "reception", "inspection", "tcm", "doctor", "nurse", "nursing", "lab", "ecg", "ultrasound");

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

    /**
     * 编辑复诊记录内容（医生岗回看校准）：仅允许修改复诊原因、病情描述、下次复查时间；
     * 创建时间（created_at）与次序（seq）由系统精确记录，一律不改，仅追加 updated_* 留痕。
     */
    public Map<String, Object> update(String visitId, JsonNode body, SessionUser user) {
        Map<String, String> visit = loadVisit(visitId);
        requireEditRole(user, "仅医生或管理员可编辑复诊记录内容");
        assertCanReadPatientCase(visit.get("patientCaseId"), user);
        String reason = text(body, "reason");
        if (reason.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "复诊原因不能为空");
        String conditionNote = text(body, "conditionNote");
        String nextReviewDate = text(body, "nextReviewDate");
        String now = TIME.format(LocalDateTime.now());
        jdbcTemplate.update(
            "UPDATE pre_ai_follow_up_visits SET reason = ?, condition_note = ?, next_review_date = ?, "
                + "updated_by = ?, updated_by_role = ?, updated_at = ? WHERE id = ?",
            truncate(reason, 500), conditionNote, nextReviewDate, user.name(), user.role(), now, visitId);
        audit(visit.get("patientCaseId"), "followup.update", user,
            "编辑第 " + visit.get("seq") + " 次复诊记录（医生校准）：" + truncate(reason, 80));
        return visitWithImages(visitId);
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

    // ---------- 复查召回：到期/逾期未复查患者清单（复诊到访转化） ----------

    /**
     * 召回汇总：按患者取最近一次复诊的"下次复查安排"作为应复查日，三态判定
     * （逾期未复 / 3 日内到期 / 已复查），并附患者联系信息与术式上下文。
     */
    public Map<String, Object> recallSummary(SessionUser user) {
        requireRecallRole(user);
        String today = TIME.format(LocalDateTime.now()).substring(0, 10);
        String dueSoonLimit = TIME.format(LocalDateTime.now().plusDays(3)).substring(0, 10);
        Map<String, Object[]> latestByCase = new LinkedHashMap<>();
        jdbcTemplate.query(
            "SELECT id, patient_case_id, seq, reason, condition_note, next_review_date, created_by, created_at "
                + "FROM pre_ai_follow_up_visits WHERE next_review_date IS NOT NULL AND next_review_date <> '' "
                + "ORDER BY patient_case_id, seq ASC",
            resultSet -> {
                String caseId = resultSet.getString("patient_case_id");
                latestByCase.put(caseId, new Object[] {
                    resultSet.getString("id"), caseId, resultSet.getInt("seq"), resultSet.getString("reason"),
                    resultSet.getString("condition_note"), resultSet.getString("next_review_date").substring(0, 10),
                    resultSet.getString("created_by"), resultSet.getString("created_at")
                });
            }
        );
        List<Map<String, Object>> overdue = new ArrayList<>();
        List<Map<String, Object>> dueSoon = new ArrayList<>();
        int upcoming = 0;
        for (Object[] latest : latestByCase.values()) {
            String visitId = String.valueOf(latest[0]);
            String caseId = String.valueOf(latest[1]);
            int seq = (Integer) latest[2];
            String dueDate = String.valueOf(latest[5]);
            if (hasActivityAfter(caseId, dueDate)) continue; // 已复查，无需召回
            Map<String, Object> row = buildRecallRow(visitId, caseId, seq, latest[3], latest[4], dueDate, latest[6], today);
            if (dueDate.compareTo(today) < 0) {
                overdue.add(row);
            } else if (dueDate.compareTo(dueSoonLimit) <= 0) {
                dueSoon.add(row);
            } else {
                upcoming += 1;
            }
        }
        overdue.sort((a, b) -> String.valueOf(a.get("dueDate")).compareTo(String.valueOf(b.get("dueDate"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overdue", overdue);
        result.put("dueSoon", dueSoon);
        result.put("upcomingCount", upcoming);
        result.put("generatedAt", TIME.format(LocalDateTime.now()));
        audit("", "followup.recall.query", user, "复查召回看板查询：逾期 " + overdue.size() + " 人");
        return result;
    }

    /** 随访工作台聚合：本周新建复诊数 + 最近随访动作（供 dashboard 板块 1/4）。 */
    public Map<String, Object> dashboardSummary(SessionUser user) {
        requireRecallRole(user);
        String now = TIME.format(LocalDateTime.now());
        String weekAgo = TIME.format(LocalDateTime.now().minusDays(7)).substring(0, 10);
        Integer createdThisWeek = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pre_ai_follow_up_visits WHERE created_at >= ?", Integer.class, weekAgo);
        List<Map<String, Object>> recentActions = jdbcTemplate.query(
            "SELECT action, operator, operator_role, detail, created_at FROM pre_ai_audit_logs "
                + "WHERE action LIKE 'followup.%' AND action <> 'followup.recall.query' "
                + "ORDER BY created_at DESC LIMIT 20",
            (resultSet, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("action", resultSet.getString("action"));
                row.put("operator", resultSet.getString("operator"));
                row.put("operatorRole", resultSet.getString("operator_role"));
                row.put("detail", resultSet.getString("detail"));
                row.put("createdAt", resultSet.getString("created_at"));
                return row;
            });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("createdThisWeek", createdThisWeek == null ? 0 : createdThisWeek);
        result.put("recentActions", recentActions);
        result.put("generatedAt", now);
        return result;
    }

    public Map<String, Object> statistics(String from, String to, String basis, SessionUser user) {
        requireRecallRole(user);
        LocalDate start = parseDate(from, LocalDate.now().withDayOfMonth(1));
        LocalDate end = parseDate(to, start.withDayOfMonth(start.lengthOfMonth()));
        boolean byContact = "contact".equalsIgnoreCase(basis);
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbcTemplate.query(
            "SELECT v.id, v.patient_case_id, v.seq, v.next_review_date, v.created_by, v.created_by_role, "
                + "v.created_at, v.updated_at, v.updated_by, v.updated_by_role, e.owning_department_name_snapshot "
                + "FROM pre_ai_follow_up_visits v LEFT JOIN pre_ai_encounters e ON e.id = v.encounter_id "
                + "WHERE v.next_review_date IS NOT NULL AND v.next_review_date <> '' ORDER BY v.next_review_date DESC",
            rs -> {
                String due = safeDate(rs.getString("next_review_date"));
                String contacted = lastRecallContact(rs.getString("id"));
                String bucket = byContact ? (contacted == null ? "" : contacted.substring(0, 10)) : due;
                if (bucket.length() < 7) return;
                String month = bucket.substring(0, 7);
                String fromMonth = start.toString().substring(0, 7);
                String toMonth = end.toString().substring(0, 7);
                if (month.compareTo(fromMonth) < 0 || month.compareTo(toMonth) > 0) return;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("visitId", rs.getString("id"));
                row.put("patientCaseId", rs.getString("patient_case_id"));
                row.put("seq", rs.getInt("seq"));
                row.put("dueDate", due);
                row.put("contactedAt", contacted);
                row.put("createdBy", safe(rs.getString("created_by")));
                row.put("createdByRole", safe(rs.getString("created_by_role")));
                row.put("operator", safe(rs.getString("updated_by")));
                row.put("operatorRole", safe(rs.getString("updated_by_role")));
                row.put("department", safe(rs.getString("owning_department_name_snapshot")));
                row.put("node", "复查节点 " + rs.getInt("seq"));
                row.put("onTime", contacted != null && contacted.substring(0, 10).compareTo(due) <= 0);
                rows.add(row);
            }
        );
        int completed = (int) rows.stream().filter(row -> row.get("contactedAt") != null).count();
        int onTime = (int) rows.stream().filter(row -> Boolean.TRUE.equals(row.get("onTime"))).count();
        Map<String, Map<String, Object>> months = new LinkedHashMap<>();
        Map<String, Map<String, Object>> departments = new LinkedHashMap<>();
        Map<String, Map<String, Object>> operators = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = byContact && row.get("contactedAt") != null
                ? String.valueOf(row.get("contactedAt")).substring(0, 7)
                : String.valueOf(row.get("dueDate")).substring(0, 7);
            accumulate(months, key, row);
            accumulate(departments, blankFallback(row.get("department"), "未归属科室"), row);
            accumulate(operators, blankFallback(row.get("operator"), blankFallback(row.get("createdBy"), "未记录人员")), row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("basis", byContact ? "contact" : "due");
        result.put("from", start.toString());
        result.put("to", end.toString());
        result.put("total", rows.size());
        result.put("completed", completed);
        result.put("onTime", onTime);
        result.put("overduePending", rows.stream().filter(row -> row.get("contactedAt") == null && String.valueOf(row.get("dueDate")).compareTo(LocalDate.now().toString()) < 0).count());
        result.put("completionRate", percent(completed, rows.size()));
        result.put("onTimeRate", percent(onTime, completed));
        result.put("trend", new ArrayList<>(months.values()));
        result.put("departments", new ArrayList<>(departments.values()));
        result.put("operators", new ArrayList<>(operators.values()));
        result.put("details", rows);
        return result;
    }

    /** 标记已联系：审计留痕，避免多人重复打扰同一患者。 */
    public Map<String, Object> markContacted(String visitId, SessionUser user) {
        Map<String, String> visit = loadVisit(visitId);
        requireRecallRole(user);
        String now = TIME.format(LocalDateTime.now());
        jdbcTemplate.update(
            "UPDATE pre_ai_follow_up_visits SET updated_by = ?, updated_by_role = ?, updated_at = ? WHERE id = ?",
            user.name(), user.role(), now, visitId);
        audit(visit.get("patientCaseId"), "followup.recall.contact", user,
            "复查召回已联系 recall:" + visitId + " 第 " + visit.get("seq") + " 次复诊记录（" + truncate(visit.get("reason"), 60) + "）");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("visitId", visitId);
        result.put("contactedAt", now);
        result.put("contactedBy", user.name());
        return result;
    }

    private boolean hasActivityAfter(String patientCaseId, String dueDate) {
        Integer laterVisits = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pre_ai_follow_up_visits WHERE patient_case_id = ? AND created_at > ?",
            Integer.class, patientCaseId, dueDate + " 23:59:59");
        if (laterVisits != null && laterVisits > 0) return true;
        Integer laterEncounters = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pre_ai_encounters WHERE patient_case_id = ? "
                + "AND created_at > ?",
            Integer.class, patientCaseId, dueDate + " 23:59:59");
        return laterEncounters != null && laterEncounters > 0;
    }

    private Map<String, Object> buildRecallRow(String visitId, String caseId, int seq, Object reason, Object note,
                                               String dueDate, Object createdBy, String today) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("visitId", visitId);
        row.put("patientCaseId", caseId);
        row.put("seq", seq);
        row.put("dueDate", dueDate);
        row.put("overdueDays", (int) java.time.temporal.ChronoUnit.DAYS.between(
            java.time.LocalDate.parse(dueDate), java.time.LocalDate.parse(today)));
        row.put("reason", reason == null ? "" : reason);
        row.put("conditionNote", note == null ? "" : note);
        row.put("createdBy", createdBy == null ? "" : createdBy);
        row.put("name", "");
        row.put("phone", "");
        row.put("address", "");
        row.put("age", "");
        row.put("gender", "");
        row.put("surgery", "");
        row.put("lastContactAt", lastRecallContact(visitId));
        row.put("priority", dueDate.compareTo(today) < 0 && Math.abs((int) java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(dueDate), java.time.LocalDate.parse(today))) >= 7 ? "CRITICAL" : dueDate.equals(today) ? "TODAY" : dueDate.equals(java.time.LocalDate.parse(today).plusDays(1).toString()) ? "TOMORROW" : "UPCOMING");
        row.put("node", "复查节点 " + seq);
        row.put("department", "未归属科室");
        row.put("responsible", createdBy == null ? "未记录责任人" : createdBy);
        try {
            jdbcTemplate.query(
                "SELECT patient_json FROM pre_ai_patient_cases WHERE id = ?",
                resultSet -> {
                    JsonNode patient = json(resultSet.getString("patient_json"));
                    row.put("name", patient.path("name").asText(patient.path("patientName").asText("")));
                    row.put("phone", patient.path("phone").asText(""));
                    row.put("address", patient.path("address").asText(""));
                    row.put("age", patient.path("age").asText(""));
                    row.put("gender", patient.path("gender").asText(""));
                },
                caseId);
        } catch (Exception ignored) {
            // 患者信息缺失时仍返回召回行
        }
        try {
            jdbcTemplate.query(
                "SELECT s.data_json FROM pre_ai_stage_submissions s "
                    + "JOIN pre_ai_encounters e ON e.id = s.encounter_id "
                    + "WHERE e.patient_case_id = ? AND s.stage_code = 'SURGERY' "
                    + "ORDER BY s.submitted_at DESC LIMIT 1",
                resultSet -> {
                    JsonNode data = json(resultSet.getString("data_json"));
                    String operation = firstNonBlankJson(data, "actualPrimaryOperation", "actualOperationName", "plannedPrimaryOperation");
                    row.put("surgery", operation);
                },
                caseId);
        } catch (Exception ignored) {
            // 术式缺失不阻断
        }
        return row;
    }

    private String firstNonBlankJson(JsonNode data, String... fields) {
        for (String field : fields) {
            String value = data.path(field).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String lastRecallContact(String visitId) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT MAX(created_at) FROM pre_ai_audit_logs WHERE action = 'followup.recall.contact' "
                    + "AND detail LIKE ?",
                String.class, "%recall:" + visitId + "%");
        } catch (Exception ignored) {
            return null;
        }
    }

    private void requireRecallRole(SessionUser user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        if (!Set.of("doctor", "admin", "inspection", "nurse", "nursing").contains(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无权查看复查召回看板");
        }
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        try { return value == null || value.isBlank() ? fallback : LocalDate.parse(value); }
        catch (Exception ignored) { return fallback; }
    }

    private String safeDate(String value) { return value == null ? "" : value.length() >= 10 ? value.substring(0, 10) : value; }
    private String safe(String value) { return value == null ? "" : value; }
    private String blankFallback(Object value, String fallback) { return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value); }
    private int percent(int numerator, int denominator) { return denominator == 0 ? 0 : Math.round(numerator * 100f / denominator); }
    private void accumulate(Map<String, Map<String, Object>> target, String key, Map<String, Object> row) {
        Map<String, Object> item = target.computeIfAbsent(key, ignored -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("label", key); value.put("total", 0); value.put("completed", 0); value.put("onTime", 0); return value;
        });
        item.put("total", ((Integer) item.get("total")) + 1);
        if (row.get("contactedAt") != null) item.put("completed", ((Integer) item.get("completed")) + 1);
        if (Boolean.TRUE.equals(row.get("onTime"))) item.put("onTime", ((Integer) item.get("onTime")) + 1);
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
            "SELECT id, encounter_id, seq, reason, condition_note, next_review_date, status, created_by, created_by_role, created_at, "
                + "updated_by, updated_by_role, updated_at "
                + "FROM pre_ai_follow_up_visits WHERE patient_case_id = ? ORDER BY seq ASC",
            resultSet -> {
                String visitId = resultSet.getString("id");
                ObjectNode visit = objectMapper.createObjectNode();
                visit.put("id", visitId);
                visit.put("encounterId", resultSet.getString("encounter_id"));
                visit.put("seq", resultSet.getInt("seq"));
                visit.put("reason", resultSet.getString("reason"));
                visit.put("conditionNote", resultSet.getString("condition_note"));
                visit.put("nextReviewDate", resultSet.getString("next_review_date"));
                visit.put("status", resultSet.getString("status"));
                visit.put("createdBy", resultSet.getString("created_by"));
                visit.put("createdByRole", resultSet.getString("created_by_role"));
                visit.put("createdAt", resultSet.getString("created_at"));
                visit.put("updatedBy", resultSet.getString("updated_by"));
                visit.put("updatedByRole", resultSet.getString("updated_by_role"));
                visit.put("updatedAt", resultSet.getString("updated_at"));
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
        result.put("id", visitId);
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
            "SELECT id, patient_case_id, encounter_id, seq, reason FROM pre_ai_follow_up_visits WHERE id = ?",
            (resultSet, rowNum) -> Map.of(
                "id", resultSet.getString("id"),
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

    private void requireEditRole(SessionUser user, String message) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        if (!EDIT_ROLES.contains(user.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
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

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw == null ? "{}" : raw);
        } catch (Exception error) {
            return objectMapper.createObjectNode();
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
