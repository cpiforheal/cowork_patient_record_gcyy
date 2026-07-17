package com.coshare.patientrecord.tcmpharmacy;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
public class TcmPharmacyService {

    private static final String TCM_PHARMACY_OPERATOR_ROLE = "tcmPharmacyOperator";
    private static final Set<String> READ_ROLES = Set.of("admin", "tcm", "doctor", TCM_PHARMACY_OPERATOR_ROLE, "pharmacist", "pharmacy", "decoction");
    private static final Set<String> DOCTOR_ROLES = Set.of("admin", "tcm", "doctor");
    private static final Set<String> CHARGE_ROLES = Set.of("admin", "frontdesk", TCM_PHARMACY_OPERATOR_ROLE);
    private static final Set<String> REVIEW_ROLES = Set.of("admin", TCM_PHARMACY_OPERATOR_ROLE);
    private static final Set<String> DISPENSING_ROLES = Set.of("admin", TCM_PHARMACY_OPERATOR_ROLE);
    private static final Set<String> DECOCTION_ROLES = Set.of("admin", TCM_PHARMACY_OPERATOR_ROLE);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TcmPharmacyService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> dashboard(SessionUser user) {
        requireRole(user, READ_ROLES, "当前岗位无权查看中药房工作台");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counts", statusCounts());
        result.put("recent", plain(queryPrescriptions("", "", 12)));
        result.put("display", displaySnapshot(user));
        result.put("currentUserRole", user.role());
        return result;
    }

    public Map<String, Object> list(String status, String keyword, SessionUser user) {
        requireRole(user, READ_ROLES, "当前岗位无权查看中药房处方");
        return Map.of("rows", plain(queryPrescriptions(safe(status), safe(keyword), 200)), "counts", statusCounts());
    }

    public Map<String, Object> workspace(String id, SessionUser user) {
        requireRole(user, READ_ROLES, "当前岗位无权查看中药房处方");
        ObjectNode prescription = load(id);
        ArrayNode audits = objectMapper.createArrayNode();
        jdbcTemplate.query("SELECT * FROM tcm_pharmacy_audit_logs WHERE prescription_id = ? ORDER BY created_at DESC", rs -> {
            audits.add(readAudit(rs));
        }, id);
        ArrayNode announcements = objectMapper.createArrayNode();
        jdbcTemplate.query("SELECT * FROM tcm_pharmacy_announcements WHERE prescription_id = ? ORDER BY created_at DESC", rs -> {
            announcements.add(readAnnouncement(rs));
        }, id);
        return Map.of(
            "prescription", plain(prescription),
            "audits", plain(audits),
            "announcements", plain(announcements),
            "currentUserRole", user.role()
        );
    }

    @Transactional
    public Map<String, Object> create(PrescriptionRequest request, SessionUser user) {
        requireRole(user, DOCTOR_ROLES, "仅中医师可创建电子处方");
        validateRequest(request);
        String id = "tcmp-" + UUID.randomUUID();
        String now = now();
        String type = normalizeType(request.dispenseType());
        jdbcTemplate.update("""
            INSERT INTO tcm_pharmacy_prescriptions (
              id, prescription_no, version_no, patient_id, patient_name, masked_name, visit_no, doctor_name,
              dispense_type, prescription_status, charge_status, review_status, dispensing_status,
              decoction_status, pickup_status, pickup_no, amount, herb_count, dose_count, items_json,
              requirements_json, created_by, updated_by, created_at, updated_at
            ) VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, 'DRAFT', 'UNPAID', 'PENDING', 'NOT_STARTED', 'NOT_REQUIRED',
                      'WAITING', '', ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, nextPrescriptionNo(), safe(request.patientId()), required(request.patientName(), "患者姓名"), maskName(request.patientName()),
            safe(request.visitNo()), user.name(), type, decimal(request.amount()), itemCount(request.items()), positive(request.doseCount()),
            toJson(request.items()), toJson(request.requirements()), user.name(), user.name(), now, now);
        audit(id, "CREATE", "", "DRAFT", user, "创建电子处方草稿");
        return workspace(id, user);
    }

    @Transactional
    public Map<String, Object> save(String id, PrescriptionRequest request, SessionUser user) {
        requireRole(user, DOCTOR_ROLES, "仅中医师可编辑电子处方");
        validateRequest(request);
        ObjectNode current = load(id);
        if (!Set.of("DRAFT", "RETURNED").contains(text(current, "prescriptionStatus"))) throw conflict("当前状态不可编辑处方");
        String type = normalizeType(request.dispenseType());
        jdbcTemplate.update("""
            UPDATE tcm_pharmacy_prescriptions SET patient_id = ?, patient_name = ?, masked_name = ?, visit_no = ?,
              dispense_type = ?, amount = ?, herb_count = ?, dose_count = ?, items_json = ?, requirements_json = ?,
              prescription_status = 'DRAFT', charge_status = 'UNPAID', review_status = 'PENDING', updated_by = ?, updated_at = ?
            WHERE id = ?
            """, safe(request.patientId()), required(request.patientName(), "患者姓名"), maskName(request.patientName()), safe(request.visitNo()),
            type, decimal(request.amount()), itemCount(request.items()), positive(request.doseCount()), toJson(request.items()),
            toJson(request.requirements()), user.name(), now(), id);
        audit(id, "SAVE", text(current, "prescriptionStatus"), "DRAFT", user, "保存电子处方草稿");
        return workspace(id, user);
    }

    @Transactional
    public Map<String, Object> submit(String id, SessionUser user) {
        requireRole(user, DOCTOR_ROLES, "仅中医师可签署提交处方");
        ObjectNode current = load(id);
        requireStatus(current, Set.of("DRAFT", "RETURNED"), "当前处方不可提交");
        updateStatuses(id, "WAITING_CHARGE", "UNPAID", "PENDING", text(current, "dispensingStatus"), text(current, "decoctionStatus"), "WAITING", user, "submitted_at");
        audit(id, "SUBMIT", text(current, "prescriptionStatus"), "WAITING_CHARGE", user, "医师签署提交，等待收费");
        return workspace(id, user);
    }

    @Transactional
    public Map<String, Object> confirmCharge(String id, ActionRequest request, SessionUser user) {
        requireRole(user, CHARGE_ROLES, "当前岗位无权确认收费");
        ObjectNode current = load(id);
        requireStatus(current, Set.of("WAITING_CHARGE"), "仅待收费处方可确认收费");
        updateStatuses(id, "WAITING_REVIEW", "PAID", "PENDING", text(current, "dispensingStatus"), text(current, "decoctionStatus"), "WAITING", user, "charged_at");
        audit(id, "CHARGE_CONFIRMED", "WAITING_CHARGE", "WAITING_REVIEW", user, detail(request, "人工确认收费"));
        return workspace(id, user);
    }

    @Transactional
    public Map<String, Object> review(String id, ReviewRequest request, SessionUser user) {
        requireRole(user, REVIEW_ROLES, "当前岗位无权审核处方");
        ObjectNode current = load(id);
        requireStatus(current, Set.of("WAITING_REVIEW", "REVIEW_HOLD"), "仅待审核或挂起处方可审方");
        String decision = safe(request == null ? "" : request.decision()).toUpperCase(Locale.ROOT);
        if ("APPROVE".equals(decision)) {
            String pickupNo = nextPickupNo();
            String type = text(current, "dispenseType");
            String decoction = "HOSPITAL_DECOCTION".equals(type) ? "WAITING_DISPENSING" : "NOT_REQUIRED";
            jdbcTemplate.update("""
                UPDATE tcm_pharmacy_prescriptions SET prescription_status = 'DISPENSING', review_status = 'APPROVED',
                  dispensing_status = 'WAITING', decoction_status = ?, pickup_no = ?, pickup_status = 'WAITING',
                  reviewed_at = ?, updated_by = ?, updated_at = ? WHERE id = ?
                """, decoction, pickupNo, now(), user.name(), now(), id);
            audit(id, "REVIEW_APPROVED", text(current, "prescriptionStatus"), "DISPENSING", user, detail(request == null ? null : new ActionRequest(request.reason()), "审方通过"));
        } else if ("RETURN".equals(decision)) {
            jdbcTemplate.update("UPDATE tcm_pharmacy_prescriptions SET prescription_status = 'RETURNED', review_status = 'RETURNED', updated_by = ?, updated_at = ? WHERE id = ?", user.name(), now(), id);
            audit(id, "REVIEW_RETURNED", text(current, "prescriptionStatus"), "RETURNED", user, required(request.reason(), "退回原因"));
        } else if ("HOLD".equals(decision)) {
            jdbcTemplate.update("UPDATE tcm_pharmacy_prescriptions SET prescription_status = 'REVIEW_HOLD', review_status = 'HOLD', updated_by = ?, updated_at = ? WHERE id = ?", user.name(), now(), id);
            audit(id, "REVIEW_HOLD", text(current, "prescriptionStatus"), "REVIEW_HOLD", user, required(request.reason(), "挂起原因"));
        } else {
            throw badRequest("审方决定必须为 APPROVE、RETURN 或 HOLD");
        }
        return workspace(id, user);
    }

    @Transactional
    public Map<String, Object> advanceDispensing(String id, String action, ActionRequest request, SessionUser user) {
        requireRole(user, DISPENSING_ROLES, "当前岗位无权操作调剂任务");
        ObjectNode current = load(id);
        requireStatus(current, Set.of("DISPENSING", "EXCEPTION"), "当前处方未进入调剂环节");
        String normalized = safe(action).toUpperCase(Locale.ROOT);
        String next = switch (normalized) {
            case "START" -> "IN_PROGRESS";
            case "COMPLETE" -> "COMPLETED";
            case "VERIFY" -> "VERIFIED";
            default -> throw badRequest("调剂操作必须为 start、complete 或 verify");
        };
        String expected = switch (normalized) {
            case "START" -> "WAITING";
            case "COMPLETE" -> "IN_PROGRESS";
            default -> "COMPLETED";
        };
        if (!expected.equals(text(current, "dispensingStatus"))) throw conflict("调剂操作顺序不正确");
        jdbcTemplate.update("UPDATE tcm_pharmacy_prescriptions SET dispensing_status = ?, updated_by = ?, updated_at = ? WHERE id = ?", next, user.name(), now(), id);
        audit(id, "DISPENSING_" + normalized, expected, next, user, detail(request, "调剂状态推进"));
        if ("VERIFIED".equals(next)) {
            if ("SELF_DECOCTION".equals(text(current, "dispenseType"))) markReady(id, user, "自煎处方调剂复核完成");
            else jdbcTemplate.update("UPDATE tcm_pharmacy_prescriptions SET prescription_status = 'DECOCTING', decoction_status = 'WAITING_SOAK', updated_by = ?, updated_at = ? WHERE id = ?", user.name(), now(), id);
        }
        return workspace(id, user);
    }

    @Transactional
    public Map<String, Object> advanceDecoction(String id, String action, ActionRequest request, SessionUser user) {
        requireRole(user, DECOCTION_ROLES, "当前岗位无权操作代煎任务");
        ObjectNode current = load(id);
        if (!"HOSPITAL_DECOCTION".equals(text(current, "dispenseType"))) throw conflict("自煎处方无需进入代煎生产");
        if (!Set.of("DECOCTING", "EXCEPTION").contains(text(current, "prescriptionStatus"))) throw conflict("当前处方未进入代煎环节");
        String normalized = safe(action).toUpperCase(Locale.ROOT);
        Map<String, String[]> flow = Map.of(
            "SOAK", new String[]{"WAITING_SOAK", "SOAKING"},
            "DECOCT", new String[]{"SOAKING", "DECOCTING"},
            "PACK", new String[]{"DECOCTING", "PACKAGING"},
            "COMPLETE", new String[]{"PACKAGING", "COMPLETED"},
            "VERIFY", new String[]{"COMPLETED", "VERIFIED"}
        );
        String[] transition = flow.get(normalized);
        if (transition == null) throw badRequest("代煎操作必须为 soak、decoct、pack、complete 或 verify");
        if (!transition[0].equals(text(current, "decoctionStatus"))) throw conflict("代煎生产操作顺序不正确");
        jdbcTemplate.update("UPDATE tcm_pharmacy_prescriptions SET decoction_status = ?, updated_by = ?, updated_at = ? WHERE id = ?", transition[1], user.name(), now(), id);
        audit(id, "DECOCTION_" + normalized, transition[0], transition[1], user, detail(request, "代煎生产状态推进"));
        if ("VERIFIED".equals(transition[1])) markReady(id, user, "院内代煎成品复核完成");
        return workspace(id, user);
    }

    @Transactional
    public Map<String, Object> call(String id, SessionUser user) {
        requireRole(user, DISPENSING_ROLES, "当前岗位无权发起叫号");
        ObjectNode current = load(id);
        if (!Set.of("READY", "CALLED").contains(text(current, "prescriptionStatus"))) throw conflict("仅可领取处方可叫号");
        String content = "请" + text(current, "pickupNo") + "号，" + text(current, "maskedName") + "患者，前往二楼中药房取药";
        jdbcTemplate.update("INSERT INTO tcm_pharmacy_announcements (id, prescription_id, pickup_no, masked_name, content, status, created_at) VALUES (?, ?, ?, ?, ?, 'PENDING', ?)",
            "tcma-" + UUID.randomUUID(), id, text(current, "pickupNo"), text(current, "maskedName"), content, now());
        jdbcTemplate.update("UPDATE tcm_pharmacy_prescriptions SET prescription_status = 'CALLED', pickup_status = 'CALLED', updated_by = ?, updated_at = ? WHERE id = ?", user.name(), now(), id);
        audit(id, "CALL", text(current, "prescriptionStatus"), "CALLED", user, content);
        return workspace(id, user);
    }

    @Transactional
    public Map<String, Object> collect(String id, ActionRequest request, SessionUser user) {
        requireRole(user, DISPENSING_ROLES, "当前岗位无权确认领取");
        ObjectNode current = load(id);
        if (!Set.of("READY", "CALLED").contains(text(current, "prescriptionStatus"))) throw conflict("当前处方不可确认领取");
        jdbcTemplate.update("UPDATE tcm_pharmacy_prescriptions SET prescription_status = 'COLLECTED', pickup_status = 'COLLECTED', collected_at = ?, updated_by = ?, updated_at = ? WHERE id = ?", now(), user.name(), now(), id);
        audit(id, "COLLECT", text(current, "prescriptionStatus"), "COLLECTED", user, detail(request, "患者身份核验后领取"));
        return workspace(id, user);
    }

    @Transactional
    public Map<String, Object> markException(String id, ActionRequest request, SessionUser user) {
        requireRole(user, REVIEW_ROLES, "当前岗位无权登记中药房异常");
        ObjectNode current = load(id);
        String reason = required(request == null ? "" : request.reason(), "异常原因");
        jdbcTemplate.update("UPDATE tcm_pharmacy_prescriptions SET prescription_status = 'EXCEPTION', exception_reason = ?, updated_by = ?, updated_at = ? WHERE id = ?", reason, user.name(), now(), id);
        audit(id, "EXCEPTION", text(current, "prescriptionStatus"), "EXCEPTION", user, reason);
        return workspace(id, user);
    }

    public Map<String, Object> displaySnapshot(SessionUser user) {
        requireRole(user, READ_ROLES, "当前岗位无权查看叫号大屏");
        ArrayNode ready = objectMapper.createArrayNode();
        ArrayNode waiting = objectMapper.createArrayNode();
        jdbcTemplate.query("""
            SELECT id, pickup_no, masked_name, dispense_type, prescription_status, pickup_status, ready_at, updated_at
            FROM tcm_pharmacy_prescriptions
            WHERE DATE(updated_at) = CURDATE() AND prescription_status IN ('READY', 'CALLED')
            ORDER BY ready_at ASC, updated_at ASC
            LIMIT 12
            """, rs -> {
                ready.add(readDisplayRow(rs));
            });
        jdbcTemplate.query("""
            SELECT id, pickup_no, masked_name, dispense_type, prescription_status, pickup_status, ready_at, updated_at
            FROM tcm_pharmacy_prescriptions
            WHERE DATE(updated_at) = CURDATE() AND prescription_status IN ('DISPENSING', 'DECOCTING')
            ORDER BY updated_at ASC LIMIT 12
            """, rs -> {
                waiting.add(readDisplayRow(rs));
            });
        return Map.of("ready", plain(ready), "waiting", plain(waiting), "counts", statusCounts(), "serverTime", now(), "refreshSeconds", 5);
    }

    public Map<String, Object> pendingAnnouncements(SessionUser user) {
        requireRole(user, READ_ROLES, "当前岗位无权读取播报队列");
        ArrayNode rows = objectMapper.createArrayNode();
        jdbcTemplate.query("""
            SELECT * FROM tcm_pharmacy_announcements
            WHERE status = 'PENDING'
               OR created_at >= DATE_SUB(NOW(), INTERVAL 2 MINUTE)
            ORDER BY created_at ASC
            LIMIT 20
            """, rs -> {
                rows.add(readAnnouncement(rs));
            });
        return Map.of("rows", plain(rows));
    }

    @Transactional
    public Map<String, Object> markAnnouncementPlayed(String id, SessionUser user) {
        requireRole(user, READ_ROLES, "当前岗位无权确认播报结果");
        jdbcTemplate.update("""
            UPDATE tcm_pharmacy_announcements
            SET status = 'PLAYED', play_count = play_count + 1, played_at = ?
            WHERE id = ? AND status = 'PENDING'
            """, now(), id);
        return Map.of("id", id, "status", "PLAYED");
    }

    @Transactional
    public Map<String, Object> resetDemo(SessionUser user) {
        requireRole(user, Set.of("admin"), "仅管理员可重置演示数据");
        jdbcTemplate.update("DELETE FROM tcm_pharmacy_announcements");
        jdbcTemplate.update("DELETE FROM tcm_pharmacy_audit_logs");
        jdbcTemplate.update("DELETE FROM tcm_pharmacy_prescriptions");
        seedDemoIfEmpty();
        return dashboard(user);
    }

    private void seedDemoIfEmpty() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tcm_pharmacy_prescriptions", Integer.class);
        if (count != null && count > 0) return;
        seed("演示患者甲", "SELF_DECOCTION", "READY", "VERIFIED", "NOT_REQUIRED", "READY", "A018", 8, 7);
        seed("演示患者乙", "HOSPITAL_DECOCTION", "CALLED", "VERIFIED", "VERIFIED", "CALLED", "A019", 12, 14);
        seed("演示患者丙", "HOSPITAL_DECOCTION", "DECOCTING", "VERIFIED", "DECOCTING", "WAITING", "A020", 10, 12);
        seed("演示患者丁", "SELF_DECOCTION", "DISPENSING", "IN_PROGRESS", "NOT_REQUIRED", "WAITING", "A021", 7, 9);
        seed("演示患者戊", "SELF_DECOCTION", "WAITING_REVIEW", "NOT_STARTED", "NOT_REQUIRED", "WAITING", "", 6, 8);
    }

    private void seed(String name, String type, String status, String dispensing, String decoction, String pickup, String pickupNo, int doses, int herbs) {
        String id = "demo-" + UUID.randomUUID();
        String time = now();
        ArrayNode items = objectMapper.createArrayNode();
        for (int index = 0; index < herbs; index++) {
            items.add(objectMapper.createObjectNode().put("name", "示例药味" + (index + 1)).put("dose", (index + 1) * 3).put("unit", "g"));
        }
        String charge = Set.of("DRAFT", "WAITING_CHARGE").contains(status) ? "UNPAID" : "PAID";
        String review = Set.of("WAITING_REVIEW").contains(status) ? "PENDING" : "APPROVED";
        jdbcTemplate.update("""
            INSERT INTO tcm_pharmacy_prescriptions (id, prescription_no, version_no, patient_id, patient_name, masked_name,
              visit_no, doctor_name, dispense_type, prescription_status, charge_status, review_status, dispensing_status,
              decoction_status, pickup_status, pickup_no, amount, herb_count, dose_count, items_json, requirements_json,
              created_by, updated_by, submitted_at, charged_at, reviewed_at, ready_at, created_at, updated_at)
            VALUES (?, ?, 1, ?, ?, ?, ?, '演示中医师', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '系统演示', '系统演示', ?, ?, ?, ?, ?, ?)
            """, id, nextPrescriptionNo(), "demo-patient", name, maskName(name), "DEMO-" + UUID.randomUUID().toString().substring(0, 6),
            type, status, charge, review, dispensing, decoction, pickup, pickupNo, herbs * doses * 2.5, herbs, doses,
            toJson(items), "{}", time, time, time, Set.of("READY", "CALLED").contains(status) ? time : null, time, time);
    }

    private ArrayNode queryPrescriptions(String status, String keyword, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM tcm_pharmacy_prescriptions WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!status.isBlank()) {
            sql.append(" AND prescription_status = ?");
            args.add(status.toUpperCase(Locale.ROOT));
        }
        if (!keyword.isBlank()) {
            sql.append(" AND (patient_name LIKE ? OR pickup_no LIKE ? OR prescription_no LIKE ? OR visit_no LIKE ?)");
            String like = "%" + keyword + "%";
            args.add(like); args.add(like); args.add(like); args.add(like);
        }
        sql.append(" ORDER BY updated_at DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 500)));
        ArrayNode rows = objectMapper.createArrayNode();
        jdbcTemplate.query(sql.toString(), rs -> {
            rows.add(readPrescription(rs));
        }, args.toArray());
        return rows;
    }

    private Map<String, Object> statusCounts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("waitingCharge", count("WAITING_CHARGE"));
        counts.put("waitingReview", count("WAITING_REVIEW"));
        counts.put("dispensing", count("DISPENSING"));
        counts.put("decocting", count("DECOCTING"));
        counts.put("ready", count("READY") + count("CALLED"));
        counts.put("collectedToday", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tcm_pharmacy_prescriptions WHERE prescription_status = 'COLLECTED' AND DATE(collected_at) = CURDATE()", Integer.class));
        counts.put("exception", count("EXCEPTION"));
        return counts;
    }

    private int count(String status) {
        Integer value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tcm_pharmacy_prescriptions WHERE prescription_status = ?", Integer.class, status);
        return value == null ? 0 : value;
    }

    private void markReady(String id, SessionUser user, String detail) {
        jdbcTemplate.update("UPDATE tcm_pharmacy_prescriptions SET prescription_status = 'READY', pickup_status = 'READY', ready_at = ?, updated_by = ?, updated_at = ? WHERE id = ?", now(), user.name(), now(), id);
        audit(id, "READY", "", "READY", user, detail);
    }

    private void updateStatuses(String id, String prescription, String charge, String review, String dispensing, String decoction, String pickup, SessionUser user, String timestampColumn) {
        String sql = "UPDATE tcm_pharmacy_prescriptions SET prescription_status = ?, charge_status = ?, review_status = ?, dispensing_status = ?, decoction_status = ?, pickup_status = ?, updated_by = ?, updated_at = ?, " + timestampColumn + " = ? WHERE id = ?";
        jdbcTemplate.update(sql, prescription, charge, review, dispensing, decoction, pickup, user.name(), now(), now(), id);
    }

    private ObjectNode load(String id) {
        List<ObjectNode> rows = jdbcTemplate.query("SELECT * FROM tcm_pharmacy_prescriptions WHERE id = ? LIMIT 1", (rs, rowNum) -> readPrescription(rs), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "中药处方不存在");
        return rows.get(0);
    }

    private ObjectNode readPrescription(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("prescriptionNo", rs.getString("prescription_no"));
        row.put("versionNo", rs.getInt("version_no"));
        row.put("patientId", rs.getString("patient_id"));
        row.put("patientName", rs.getString("patient_name"));
        row.put("maskedName", rs.getString("masked_name"));
        row.put("visitNo", rs.getString("visit_no"));
        row.put("doctorName", rs.getString("doctor_name"));
        row.put("dispenseType", rs.getString("dispense_type"));
        row.put("prescriptionStatus", rs.getString("prescription_status"));
        row.put("chargeStatus", rs.getString("charge_status"));
        row.put("reviewStatus", rs.getString("review_status"));
        row.put("dispensingStatus", rs.getString("dispensing_status"));
        row.put("decoctionStatus", rs.getString("decoction_status"));
        row.put("pickupStatus", rs.getString("pickup_status"));
        row.put("pickupNo", rs.getString("pickup_no"));
        row.put("amount", rs.getBigDecimal("amount"));
        row.put("herbCount", rs.getInt("herb_count"));
        row.put("doseCount", rs.getInt("dose_count"));
        row.set("items", readJson(rs.getString("items_json"), true));
        row.set("requirements", readJson(rs.getString("requirements_json"), false));
        row.put("exceptionReason", rs.getString("exception_reason"));
        row.put("createdBy", rs.getString("created_by"));
        row.put("updatedBy", rs.getString("updated_by"));
        putNullable(row, "submittedAt", rs.getString("submitted_at"));
        putNullable(row, "chargedAt", rs.getString("charged_at"));
        putNullable(row, "reviewedAt", rs.getString("reviewed_at"));
        putNullable(row, "readyAt", rs.getString("ready_at"));
        putNullable(row, "collectedAt", rs.getString("collected_at"));
        row.put("createdAt", rs.getString("created_at"));
        row.put("updatedAt", rs.getString("updated_at"));
        return row;
    }

    private ObjectNode readDisplayRow(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("pickupNo", rs.getString("pickup_no"));
        row.put("maskedName", rs.getString("masked_name"));
        row.put("dispenseType", rs.getString("dispense_type"));
        row.put("prescriptionStatus", rs.getString("prescription_status"));
        row.put("pickupStatus", rs.getString("pickup_status"));
        putNullable(row, "readyAt", rs.getString("ready_at"));
        row.put("updatedAt", rs.getString("updated_at"));
        return row;
    }

    private Object plain(JsonNode node) {
        return objectMapper.convertValue(node, Object.class);
    }

    private ObjectNode readAudit(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("actionCode", rs.getString("action_code"));
        row.put("fromStatus", rs.getString("from_status"));
        row.put("toStatus", rs.getString("to_status"));
        row.put("operatorName", rs.getString("operator_name"));
        row.put("operatorRole", rs.getString("operator_role"));
        row.put("detail", rs.getString("detail"));
        row.put("createdAt", rs.getString("created_at"));
        return row;
    }

    private ObjectNode readAnnouncement(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("prescriptionId", rs.getString("prescription_id"));
        row.put("pickupNo", rs.getString("pickup_no"));
        row.put("maskedName", rs.getString("masked_name"));
        row.put("content", rs.getString("content"));
        row.put("status", rs.getString("status"));
        row.put("playCount", rs.getInt("play_count"));
        row.put("createdAt", rs.getString("created_at"));
        putNullable(row, "playedAt", rs.getString("played_at"));
        return row;
    }

    private void audit(String prescriptionId, String action, String from, String to, SessionUser user, String detail) {
        jdbcTemplate.update("""
            INSERT INTO tcm_pharmacy_audit_logs (id, prescription_id, action_code, from_status, to_status,
              operator_id, operator_name, operator_role, detail, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, "tcml-" + UUID.randomUUID(), prescriptionId, action, safe(from), safe(to), safe(user.id()), user.name(), user.role(), safe(detail), now());
    }

    private void validateRequest(PrescriptionRequest request) {
        if (request == null) throw badRequest("处方内容不能为空");
        required(request.patientName(), "患者姓名");
        normalizeType(request.dispenseType());
        if (request.items() == null || request.items().isEmpty()) throw badRequest("处方至少需要一个药味");
        if (positive(request.doseCount()) < 1) throw badRequest("帖数必须大于 0");
    }

    private void requireStatus(ObjectNode row, Set<String> allowed, String message) {
        if (!allowed.contains(text(row, "prescriptionStatus"))) throw conflict(message);
    }

    private void requireRole(SessionUser user, Set<String> roles, String message) {
        if (user == null || !roles.contains(user.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    private String nextPrescriptionNo() {
        return "TCM" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + String.format("%04d", Math.abs(UUID.randomUUID().hashCode()) % 10000);
    }

    private String nextPickupNo() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tcm_pharmacy_prescriptions WHERE DATE(reviewed_at) = CURDATE() AND pickup_no <> ''", Integer.class);
        return "A" + String.format("%03d", (count == null ? 0 : count) + 1);
    }

    private String maskName(String value) {
        String name = safe(value);
        if (name.isBlank()) return "患者";
        if (name.length() == 1) return name + "*";
        if (name.length() == 2) return name.substring(0, 1) + "*";
        return name.substring(0, 1) + "*".repeat(name.length() - 2) + name.substring(name.length() - 1);
    }

    private String normalizeType(String value) {
        String type = safe(value).toUpperCase(Locale.ROOT);
        if (!Set.of("SELF_DECOCTION", "HOSPITAL_DECOCTION").contains(type)) throw badRequest("调剂方式必须为患者自煎或院内代煎");
        return type;
    }

    private JsonNode readJson(String json, boolean array) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? (array ? "[]" : "{}") : json);
        } catch (JsonProcessingException error) {
            return array ? objectMapper.createArrayNode() : objectMapper.createObjectNode();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException error) {
            throw badRequest("处方数据格式不正确");
        }
    }

    private void putNullable(ObjectNode node, String key, String value) {
        if (value == null) node.putNull(key); else node.put(key, value);
    }

    private String detail(ActionRequest request, String fallback) {
        return request == null || safe(request.reason()).isBlank() ? fallback : safe(request.reason());
    }

    private String text(JsonNode node, String key) {
        return node == null || node.path(key).isMissingNode() || node.path(key).isNull() ? "" : node.path(key).asText("");
    }

    private int itemCount(List<Map<String, Object>> items) { return items == null ? 0 : items.size(); }
    private int positive(Integer value) { return value == null ? 1 : Math.max(1, value); }
    private double decimal(Double value) { return value == null ? 0D : Math.max(0D, value); }
    private String now() { return LocalDateTime.now().format(DATE_TIME); }
    private String safe(String value) { return value == null ? "" : value.trim(); }

    private String required(String value, String label) {
        String normalized = safe(value);
        if (normalized.isBlank()) throw badRequest(label + "不能为空");
        return normalized;
    }

    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record PrescriptionRequest(
        String patientId,
        String patientName,
        String visitNo,
        String dispenseType,
        Integer doseCount,
        Double amount,
        List<Map<String, Object>> items,
        Map<String, Object> requirements
    ) {}

    public record ReviewRequest(String decision, String reason) {}
    public record ActionRequest(String reason) {}
}
