package com.coshare.patientrecord.clinicqueue;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class ClinicQueueService {

    static final String INSPECTION = "INSPECTION";
    static final String RECEPTION = "RECEPTION";
    private static final Set<String> STAGES = Set.of(INSPECTION, RECEPTION);
    private static final Set<String> READ_ROLES = Set.of("admin", "frontdesk", "inspection", "reception", "doctor");
    private static final Set<String> ISSUE_ROLES = Set.of("admin", "frontdesk");
    private static final Set<String> INSPECTION_ROLES = Set.of("admin", "inspection");
    private static final Set<String> RECEPTION_ROLES = Set.of("admin", "reception", "doctor");
    private static final Set<String> ROOM_CONTROL_ROLES = Set.of("admin", "frontdesk", "inspection", "reception", "doctor");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int FOLLOW_UP_STREAK_LIMIT = 2;
    private static final long FIRST_VISIT_MIN_WAIT_MINUTES = 10;
    private static final long FIRST_VISIT_MAX_WAIT_MINUTES = 30;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ClinicQueueService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initializeSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_queue_tickets (
              id VARCHAR(64) PRIMARY KEY,
              encounter_id VARCHAR(64) NOT NULL,
              business_date DATE NOT NULL,
              public_no VARCHAR(16) NOT NULL,
              visit_type VARCHAR(24) NOT NULL,
              patient_id VARCHAR(64) NOT NULL DEFAULT '',
              patient_name VARCHAR(80) NOT NULL,
              masked_name VARCHAR(80) NOT NULL,
              overall_status VARCHAR(32) NOT NULL,
              version INT NOT NULL DEFAULT 0,
              created_by VARCHAR(100) NOT NULL,
              created_by_role VARCHAR(64) NOT NULL,
              created_at DATETIME NOT NULL,
              updated_at DATETIME NOT NULL,
              completed_at DATETIME NULL,
              UNIQUE KEY uq_clinic_queue_encounter (encounter_id),
              UNIQUE KEY uq_clinic_queue_public_no (business_date, public_no),
              INDEX idx_clinic_queue_status (business_date, overall_status, updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_queue_tasks (
              id VARCHAR(64) PRIMARY KEY,
              ticket_id VARCHAR(64) NOT NULL,
              stage_code VARCHAR(32) NOT NULL,
              room_code VARCHAR(32) NOT NULL,
              status VARCHAR(32) NOT NULL,
              version INT NOT NULL DEFAULT 0,
              queue_entered_at DATETIME NULL,
              called_at DATETIME NULL,
              arrived_at DATETIME NULL,
              service_started_at DATETIME NULL,
              completed_at DATETIME NULL,
              interrupted_from_status VARCHAR(32) NOT NULL DEFAULT '',
              priority_locked BOOLEAN NOT NULL DEFAULT FALSE,
              priority_reason VARCHAR(500) NOT NULL DEFAULT '',
              recall_count INT NOT NULL DEFAULT 0,
              exception_reason VARCHAR(500) NOT NULL DEFAULT '',
              updated_by VARCHAR(100) NOT NULL DEFAULT '',
              updated_at DATETIME NOT NULL,
              UNIQUE KEY uq_clinic_queue_task_stage (ticket_id, stage_code),
              INDEX idx_clinic_queue_task_dispatch (stage_code, status, priority_locked, queue_entered_at),
              INDEX idx_clinic_queue_task_room (room_code, status, updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_queue_rooms (
              room_code VARCHAR(32) PRIMARY KEY,
              room_name VARCHAR(80) NOT NULL,
              stage_code VARCHAR(32) NOT NULL,
              status VARCHAR(32) NOT NULL,
              pause_reason VARCHAR(500) NOT NULL DEFAULT '',
              follow_up_streak INT NOT NULL DEFAULT 0,
              version INT NOT NULL DEFAULT 0,
              updated_by VARCHAR(100) NOT NULL DEFAULT '',
              updated_at DATETIME NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_queue_announcements (
              id VARCHAR(64) PRIMARY KEY,
              ticket_id VARCHAR(64) NOT NULL,
              task_id VARCHAR(64) NOT NULL,
              public_no VARCHAR(16) NOT NULL,
              stage_code VARCHAR(32) NOT NULL,
              room_name VARCHAR(80) NOT NULL,
              content VARCHAR(500) NOT NULL,
              status VARCHAR(24) NOT NULL,
              play_count INT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL,
              expires_at DATETIME NOT NULL,
              played_at DATETIME NULL,
              INDEX idx_clinic_queue_announcement (status, created_at),
              INDEX idx_clinic_queue_announcement_expiry (status, expires_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_queue_emergencies (
              id VARCHAR(64) PRIMARY KEY,
              room_code VARCHAR(32) NOT NULL,
              status VARCHAR(24) NOT NULL,
              reason VARCHAR(500) NOT NULL,
              started_by VARCHAR(100) NOT NULL,
              started_by_role VARCHAR(64) NOT NULL,
              started_at DATETIME NOT NULL,
              ended_by VARCHAR(100) NOT NULL DEFAULT '',
              ended_at DATETIME NULL,
              INDEX idx_clinic_queue_emergency (room_code, status, started_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_queue_audit_logs (
              id VARCHAR(64) PRIMARY KEY,
              ticket_id VARCHAR(64) NOT NULL DEFAULT '',
              task_id VARCHAR(64) NOT NULL DEFAULT '',
              room_code VARCHAR(32) NOT NULL DEFAULT '',
              action_code VARCHAR(64) NOT NULL,
              from_status VARCHAR(64) NOT NULL DEFAULT '',
              to_status VARCHAR(64) NOT NULL DEFAULT '',
              operator_id VARCHAR(64) NOT NULL DEFAULT '',
              operator_name VARCHAR(100) NOT NULL,
              operator_role VARCHAR(64) NOT NULL,
              detail VARCHAR(1000) NOT NULL DEFAULT '',
              created_at DATETIME NOT NULL,
              INDEX idx_clinic_queue_audit_ticket (ticket_id, created_at),
              INDEX idx_clinic_queue_audit_room (room_code, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        addColumnIfMissing("clinic_queue_announcements", "expires_at", "DATETIME NULL");
        jdbcTemplate.update("UPDATE clinic_queue_announcements SET expires_at = DATE_ADD(created_at, INTERVAL 2 MINUTE) WHERE expires_at IS NULL");
        seedRoom("INSPECTION_ROOM", "检查室", INSPECTION);
        seedRoom("RECEPTION_ROOM", "接诊室", RECEPTION);
    }

    @Transactional
    public Map<String, Object> issue(IssueRequest request, SessionUser user) {
        requireRole(user, ISSUE_ROLES, "仅前台或管理员可发号");
        String encounterId = required(request == null ? "" : request.encounterId(), "就诊记录");
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
            "SELECT id FROM clinic_queue_tickets WHERE encounter_id = ? LIMIT 1", encounterId
        );
        if (!existing.isEmpty()) return workspace(String.valueOf(existing.get(0).get("id")), user);

        EncounterPatient encounter = loadEncounterPatient(encounterId);
        String visitType = normalizeVisitType(request == null ? "" : request.visitType(), encounter.visitNo());
        LocalDate businessDate = LocalDate.now();
        String id = "cqt-" + UUID.randomUUID();
        String inspectionTaskId = "cqtask-" + UUID.randomUUID();
        String receptionTaskId = "cqtask-" + UUID.randomUUID();
        String timestamp = now();
        String publicNo = nextPublicNo(businessDate, visitType);
        try {
            jdbcTemplate.update("""
                INSERT INTO clinic_queue_tickets (
                  id, encounter_id, business_date, public_no, visit_type, patient_id, patient_name, masked_name,
                  overall_status, version, created_by, created_by_role, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'WAITING_INSPECTION', 0, ?, ?, ?, ?)
                """, id, encounterId, businessDate, publicNo, visitType, encounter.patientId(), encounter.patientName(),
                maskName(encounter.patientName()), user.name(), user.role(), timestamp, timestamp);
            jdbcTemplate.update("""
                INSERT INTO clinic_queue_tasks (
                  id, ticket_id, stage_code, room_code, status, queue_entered_at, updated_by, updated_at
                ) VALUES (?, ?, 'INSPECTION', 'INSPECTION_ROOM', 'WAITING', ?, ?, ?)
                """, inspectionTaskId, id, timestamp, user.name(), timestamp);
            jdbcTemplate.update("""
                INSERT INTO clinic_queue_tasks (
                  id, ticket_id, stage_code, room_code, status, updated_by, updated_at
                ) VALUES (?, ?, 'RECEPTION', 'RECEPTION_ROOM', 'INACTIVE', ?, ?)
                """, receptionTaskId, id, user.name(), timestamp);
        } catch (DuplicateKeyException error) {
            List<Map<String, Object>> concurrent = jdbcTemplate.queryForList(
                "SELECT id FROM clinic_queue_tickets WHERE encounter_id = ? LIMIT 1", encounterId
            );
            if (!concurrent.isEmpty()) return workspace(String.valueOf(concurrent.get(0).get("id")), user);
            throw conflict("号码生成冲突，请重试");
        }
        audit(id, inspectionTaskId, "INSPECTION_ROOM", "TICKET_ISSUED", "", "WAITING", user,
            "前台发号：" + publicNo + "，" + visitTypeLabel(visitType));
        return workspace(id, user);
    }

    public Map<String, Object> dashboard(String keyword, SessionUser user) {
        requireRole(user, READ_ROLES, "当前岗位无权查看检查接诊队列");
        String normalizedKeyword = safe(keyword);
        String like = "%" + normalizedKeyword + "%";
        ArrayNode tickets = objectMapper.createArrayNode();
        String sql = """
            SELECT q.* FROM clinic_queue_tickets q
            WHERE q.business_date = CURDATE()
              AND (? = '' OR q.public_no LIKE ? OR q.patient_name LIKE ? OR q.encounter_id LIKE ?)
            ORDER BY q.created_at DESC LIMIT 300
            """;
        jdbcTemplate.query(sql, (RowCallbackHandler) rs -> tickets.add(readTicket(rs)), normalizedKeyword, like, like, like);
        ArrayNode rooms = objectMapper.createArrayNode();
        jdbcTemplate.query("SELECT * FROM clinic_queue_rooms ORDER BY stage_code", (RowCallbackHandler) rs -> rooms.add(readRoom(rs)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tickets", plain(tickets));
        result.put("rooms", plain(rooms));
        result.put("counts", counts());
        result.put("currentUserRole", user.role());
        result.put("serverTime", now());
        return result;
    }

    public Map<String, Object> workspace(String ticketId, SessionUser user) {
        requireRole(user, READ_ROLES, "当前岗位无权查看排队单");
        ObjectNode ticket = loadTicket(ticketId);
        ArrayNode tasks = objectMapper.createArrayNode();
        jdbcTemplate.query("SELECT * FROM clinic_queue_tasks WHERE ticket_id = ? ORDER BY stage_code", (RowCallbackHandler) rs -> tasks.add(readTask(rs)), ticketId);
        ArrayNode audits = objectMapper.createArrayNode();
        jdbcTemplate.query("SELECT * FROM clinic_queue_audit_logs WHERE ticket_id = ? ORDER BY created_at DESC LIMIT 200", (RowCallbackHandler) rs -> audits.add(readAudit(rs)), ticketId);
        return Map.of(
            "ticket", plain(ticket),
            "tasks", plain(tasks),
            "audits", plain(audits),
            "currentUserRole", user.role()
        );
    }

    @Transactional
    public Map<String, Object> callNext(String stageCode, SessionUser user) {
        String stage = normalizeStage(stageCode);
        requireStageRole(stage, user);
        ObjectNode room = loadRoomByStage(stage);
        ensureRoomActive(room);
        List<Candidate> candidates = waitingCandidates(stage);
        Candidate selected = chooseCandidate(candidates, room.path("followUpStreak").asInt(0), LocalDateTime.now());
        if (selected == null) throw conflict("当前没有可叫号患者");
        callTaskInternal(selected.taskId(), user, "系统推荐：" + selected.reason());
        return workspace(selected.ticketId(), user);
    }

    @Transactional
    public Map<String, Object> taskAction(String taskId, String action, ActionRequest request, SessionUser user) {
        ObjectNode task = loadTask(taskId);
        String stage = text(task, "stageCode");
        requireStageOrFrontdeskRole(stage, action, user);
        String normalized = safe(action).toUpperCase(Locale.ROOT);
        String current = text(task, "status");
        String reason = safe(request == null ? "" : request.reason());
        return switch (normalized) {
            case "CALL" -> {
                callTaskInternal(taskId, user, reason.isBlank() ? "指定叫号" : reason);
                yield workspace(text(task, "ticketId"), user);
            }
            case "RECALL" -> {
                requireState(current, Set.of("CALLED", "MISSED"), "仅已叫号或过号患者可重呼");
                createAnnouncement(task, user, true);
                updateTask(task, "CALLED", user, reason, "called_at = ?, recall_count = recall_count + 1", now());
                yield workspace(text(task, "ticketId"), user);
            }
            case "ARRIVE" -> transition(task, Set.of("CALLED", "MISSED"), "ARRIVED", user, reason, "arrived_at = ?", now());
            case "START" -> transition(task, Set.of("ARRIVED", "INTERRUPTED"), "IN_SERVICE", user, reason, "service_started_at = ?", now());
            case "MISSED" -> transition(task, Set.of("CALLED"), "MISSED", user, reason, "", new Object[0]);
            case "AWAY" -> transition(task, Set.of("WAITING", "MISSED", "ARRIVED"), "TEMPORARILY_AWAY", user, required(reason, "暂离原因"), "", new Object[0]);
            case "RESUME" -> transition(task, Set.of("TEMPORARILY_AWAY", "ON_HOLD", "MISSED"), "WAITING", user, reason, "queue_entered_at = ?", now());
            case "HOLD" -> transition(task, Set.of("WAITING", "CALLED", "ARRIVED", "IN_SERVICE", "INTERRUPTED"), "ON_HOLD", user, required(reason, "挂起原因"), "", new Object[0]);
            case "PRIORITIZE" -> {
                requireRole(user, Set.of("admin", "frontdesk"), "仅前台或管理员可人工优先");
                requireState(current, Set.of("WAITING"), "仅等候中患者可人工优先");
                String detail = required(reason, "人工优先原因");
                optimisticUpdate(task, "UPDATE clinic_queue_tasks SET priority_locked = TRUE, priority_reason = ?, version = version + 1, updated_by = ?, updated_at = ? WHERE id = ? AND version = ?",
                    detail, user.name(), now(), taskId, task.path("version").asInt());
                audit(text(task, "ticketId"), taskId, text(task, "roomCode"), "PRIORITIZE", current, current, user, detail);
                yield workspace(text(task, "ticketId"), user);
            }
            case "CANCEL" -> cancelOrLeave(task, "CANCELLED", user, required(reason, "取消原因"));
            case "LEAVE" -> cancelOrLeave(task, "LEFT", user, required(reason, "离院原因"));
            case "COMPLETE" -> completeFromQueue(task, user, reason);
            case "SUPPLEMENT" -> createSupplementaryInspection(task, user, required(reason, "补检原因"));
            default -> throw badRequest("不支持的队列操作：" + action);
        };
    }

    @Transactional
    public Map<String, Object> roomAction(String roomCode, String action, ActionRequest request, SessionUser user) {
        requireRole(user, ROOM_CONTROL_ROLES, "当前岗位无权控制房间状态");
        ObjectNode room = loadRoom(roomCode);
        assertRoomRole(room, user);
        String normalized = safe(action).toUpperCase(Locale.ROOT);
        String current = text(room, "status");
        String reason = safe(request == null ? "" : request.reason());
        String next;
        switch (normalized) {
            case "PAUSE" -> {
                next = "MANUAL_PAUSED";
                reason = required(reason, "暂停原因");
            }
            case "CLOSE" -> {
                next = "CLOSED";
                reason = required(reason, "停诊原因");
            }
            case "EMERGENCY" -> {
                if ("EMERGENCY_PAUSED".equals(current)) throw conflict("该房间已处于急症暂停");
                next = "EMERGENCY_PAUSED";
                reason = required(reason, "急症原因");
                interruptCurrentTask(text(room, "roomCode"), user, reason);
                jdbcTemplate.update("""
                    INSERT INTO clinic_queue_emergencies (
                      id, room_code, status, reason, started_by, started_by_role, started_at
                    ) VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?)
                    """, "cqe-" + UUID.randomUUID(), text(room, "roomCode"), reason, user.name(), user.role(), now());
            }
            case "RESUME" -> {
                if ("ACTIVE".equals(current)) throw conflict("房间已经正常服务");
                next = "ACTIVE";
                jdbcTemplate.update("""
                    UPDATE clinic_queue_emergencies SET status = 'ENDED', ended_by = ?, ended_at = ?
                    WHERE room_code = ? AND status = 'ACTIVE'
                    """, user.name(), now(), text(room, "roomCode"));
            }
            default -> throw badRequest("房间操作必须为 pause、close、emergency 或 resume");
        }
        optimisticUpdate(room, "UPDATE clinic_queue_rooms SET status = ?, pause_reason = ?, version = version + 1, updated_by = ?, updated_at = ? WHERE room_code = ? AND version = ?",
            next, "ACTIVE".equals(next) ? "" : reason, user.name(), now(), text(room, "roomCode"), room.path("version").asInt());
        audit("", "", text(room, "roomCode"), "ROOM_" + normalized, current, next, user, reason);
        return dashboard("", user);
    }

    @Transactional
    public void onClinicalStageCompleted(String encounterId, String stageCode, SessionUser user) {
        String stage = normalizeStage(stageCode);
        if (!STAGES.contains(stage)) return;
        List<String> tickets = jdbcTemplate.query(
            "SELECT id FROM clinic_queue_tickets WHERE encounter_id = ? LIMIT 1",
            (rs, rowNum) -> rs.getString("id"), encounterId
        );
        if (tickets.isEmpty()) return;
        ObjectNode task = loadTaskByTicketStage(tickets.get(0), stage);
        if ("COMPLETED".equals(text(task, "status"))) return;
        if (Set.of("CANCELLED").contains(text(task, "status"))) throw conflict("排队任务已取消，无法完成临床交接");
        completeTaskAndAdvance(task, user, "临床阶段完成，自动同步排队状态");
    }

    public Map<String, Object> displaySnapshot(SessionUser user) {
        requireRole(user, READ_ROLES, "当前岗位无权查看叫号大屏");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inspection", displayRoom(INSPECTION));
        result.put("reception", displayRoom(RECEPTION));
        result.put("counts", counts());
        result.put("serverTime", now());
        result.put("refreshSeconds", 5);
        return result;
    }

    @Transactional
    public Map<String, Object> pendingAnnouncements(SessionUser user) {
        requireRole(user, READ_ROLES, "当前岗位无权读取播报队列");
        jdbcTemplate.update("""
            UPDATE clinic_queue_announcements
            SET status = 'EXPIRED'
            WHERE status = 'PENDING' AND expires_at <= NOW()
            """);
        ArrayNode rows = objectMapper.createArrayNode();
        jdbcTemplate.query("""
            SELECT * FROM clinic_queue_announcements
            WHERE status = 'PENDING' AND expires_at > NOW()
            ORDER BY created_at ASC LIMIT 30
            """, (RowCallbackHandler) rs -> rows.add(readAnnouncement(rs)));
        return Map.of("rows", plain(rows));
    }

    @Transactional
    public Map<String, Object> markAnnouncementPlayed(String id, SessionUser user) {
        requireRole(user, READ_ROLES, "当前岗位无权确认播报结果");
        jdbcTemplate.update("""
            UPDATE clinic_queue_announcements
            SET status = 'PLAYED', play_count = play_count + 1, played_at = ?
            WHERE id = ? AND status = 'PENDING'
            """, now(), id);
        return Map.of("id", id, "status", "PLAYED");
    }

    public Map<String, Object> audits(String ticketId, String roomCode, SessionUser user) {
        requireRole(user, READ_ROLES, "当前岗位无权查看审计记录");
        ArrayNode rows = objectMapper.createArrayNode();
        jdbcTemplate.query("""
            SELECT * FROM clinic_queue_audit_logs
            WHERE (? = '' OR ticket_id = ?) AND (? = '' OR room_code = ?)
            ORDER BY created_at DESC LIMIT 500
            """, (RowCallbackHandler) rs -> rows.add(readAudit(rs)), safe(ticketId), safe(ticketId), safe(roomCode), safe(roomCode));
        return Map.of("rows", plain(rows));
    }

    private Map<String, Object> transition(ObjectNode task, Set<String> allowed, String next, SessionUser user, String reason, String extraSql, Object... extraArgs) {
        String current = text(task, "status");
        requireState(current, allowed, "当前状态不可执行该操作");
        updateTask(task, next, user, reason, extraSql, extraArgs);
        updateOverallStatus(text(task, "ticketId"), text(task, "stageCode"), next);
        return workspace(text(task, "ticketId"), user);
    }

    private void updateTask(ObjectNode task, String next, SessionUser user, String reason, String extraSql, Object... extraArgs) {
        StringBuilder sql = new StringBuilder("UPDATE clinic_queue_tasks SET status = ?, exception_reason = ?, version = version + 1, updated_by = ?, updated_at = ?");
        List<Object> args = new ArrayList<>();
        args.add(next);
        args.add(safe(reason));
        args.add(user.name());
        args.add(now());
        if (!safe(extraSql).isBlank()) {
            sql.append(", ").append(extraSql);
            for (Object arg : extraArgs) args.add(arg);
        }
        sql.append(" WHERE id = ? AND version = ?");
        args.add(text(task, "id"));
        args.add(task.path("version").asInt());
        optimisticUpdate(task, sql.toString(), args.toArray());
        audit(text(task, "ticketId"), text(task, "id"), text(task, "roomCode"), "TASK_" + next,
            text(task, "status"), next, user, reason);
    }

    private void callTaskInternal(String taskId, SessionUser user, String reason) {
        ObjectNode task = loadTask(taskId);
        requireState(text(task, "status"), Set.of("WAITING", "MISSED"), "仅等候或过号患者可叫号");
        ObjectNode room = loadRoom(text(task, "roomCode"));
        ensureRoomActive(room);
        Integer active = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM clinic_queue_tasks
            WHERE room_code = ? AND status IN ('CALLED', 'ARRIVED', 'IN_SERVICE') AND id <> ?
            """, Integer.class, text(task, "roomCode"), taskId);
        if (active != null && active > 0) throw conflict("该房间仍有已叫号或办理中的患者，请先完成处理");
        createAnnouncement(task, user, false);
        updateTask(task, "CALLED", user, reason, "called_at = ?", now());
        updateOverallStatus(text(task, "ticketId"), text(task, "stageCode"), "CALLED");
        String visitType = jdbcTemplate.queryForObject("SELECT visit_type FROM clinic_queue_tickets WHERE id = ?", String.class, text(task, "ticketId"));
        int streak = room.path("followUpStreak").asInt(0);
        int nextStreak = "FOLLOW_UP".equals(visitType) ? streak + 1 : 0;
        jdbcTemplate.update("UPDATE clinic_queue_rooms SET follow_up_streak = ?, updated_by = ?, updated_at = ? WHERE room_code = ?",
            nextStreak, user.name(), now(), text(task, "roomCode"));
    }

    private void createAnnouncement(ObjectNode task, SessionUser user, boolean recall) {
        ObjectNode ticket = loadTicket(text(task, "ticketId"));
        ObjectNode room = loadRoom(text(task, "roomCode"));
        String publicNo = text(ticket, "publicNo");
        String roomName = text(room, "roomName");
        String content = (recall ? "再次呼叫，" : "请") + publicNo + "号前往" + roomName;
        jdbcTemplate.update("""
            UPDATE clinic_queue_announcements
            SET status = 'SUPERSEDED'
            WHERE task_id = ? AND status = 'PENDING'
            """, text(task, "id"));
        jdbcTemplate.update("""
            INSERT INTO clinic_queue_announcements (
              id, ticket_id, task_id, public_no, stage_code, room_name, content, status, created_at, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, DATE_ADD(?, INTERVAL 2 MINUTE))
            """, "cqa-" + UUID.randomUUID(), text(task, "ticketId"), text(task, "id"), publicNo,
            text(task, "stageCode"), roomName, content, now(), now());
        audit(text(task, "ticketId"), text(task, "id"), text(task, "roomCode"), recall ? "RECALL" : "CALL",
            text(task, "status"), "CALLED", user, content);
    }

    private Map<String, Object> completeFromQueue(ObjectNode task, SessionUser user, String reason) {
        String stage = text(task, "stageCode");
        String encounterId = jdbcTemplate.queryForObject("SELECT encounter_id FROM clinic_queue_tickets WHERE id = ?", String.class, text(task, "ticketId"));
        Integer completed = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM pre_ai_stage_submissions
            WHERE encounter_id = ? AND stage_code = ? AND status = 'COMPLETED'
            """, Integer.class, encounterId, stage);
        if (completed == null || completed == 0) throw conflict("请先在病历工作台完成" + stageLabel(stage) + "临床阶段");
        completeTaskAndAdvance(task, user, reason.isBlank() ? "队列阶段完成" : reason);
        return workspace(text(task, "ticketId"), user);
    }

    private void completeTaskAndAdvance(ObjectNode task, SessionUser user, String reason) {
        String stage = text(task, "stageCode");
        String current = text(task, "status");
        if ("COMPLETED".equals(current)) return;
        if (!Set.of("WAITING", "CALLED", "ARRIVED", "IN_SERVICE", "INTERRUPTED", "ON_HOLD", "MISSED").contains(current)) {
            throw conflict("当前排队任务不可完成");
        }
        updateTask(task, "COMPLETED", user, reason, "completed_at = ?", now());
        if (INSPECTION.equals(stage)) {
            ObjectNode reception = loadTaskByTicketStage(text(task, "ticketId"), RECEPTION);
            if ("INACTIVE".equals(text(reception, "status"))) {
                updateTask(reception, "WAITING", user, "检查完成，自动转入接诊队列", "queue_entered_at = ?", now());
            }
            jdbcTemplate.update("UPDATE clinic_queue_tickets SET overall_status = 'WAITING_RECEPTION', version = version + 1, updated_at = ? WHERE id = ?",
                now(), text(task, "ticketId"));
            audit(text(task, "ticketId"), text(reception, "id"), text(reception, "roomCode"), "AUTO_TRANSFER_RECEPTION",
                "INACTIVE", "WAITING", user, "沿用同一号码自动进入接诊队列");
        } else {
            jdbcTemplate.update("UPDATE clinic_queue_tickets SET overall_status = 'COMPLETED', completed_at = ?, version = version + 1, updated_at = ? WHERE id = ?",
                now(), now(), text(task, "ticketId"));
        }
    }

    private Map<String, Object> createSupplementaryInspection(ObjectNode receptionTask, SessionUser user, String reason) {
        if (!RECEPTION.equals(text(receptionTask, "stageCode"))) throw conflict("仅接诊阶段可发起补检");
        requireState(text(receptionTask, "status"), Set.of("ARRIVED", "IN_SERVICE", "ON_HOLD"), "当前接诊状态不可发起补检");
        updateTask(receptionTask, "ON_HOLD", user, reason, "", new Object[0]);
        ObjectNode inspection = loadTaskByTicketStage(text(receptionTask, "ticketId"), INSPECTION);
        optimisticUpdate(inspection, """
            UPDATE clinic_queue_tasks
            SET status = 'WAITING', queue_entered_at = ?, completed_at = NULL, priority_locked = TRUE,
                priority_reason = ?, exception_reason = ?, version = version + 1, updated_by = ?, updated_at = ?
            WHERE id = ? AND version = ?
            """, now(), "接诊发起补检", reason, user.name(), now(), text(inspection, "id"), inspection.path("version").asInt());
        jdbcTemplate.update("UPDATE clinic_queue_tickets SET overall_status = 'WAITING_INSPECTION', version = version + 1, updated_at = ? WHERE id = ?",
            now(), text(receptionTask, "ticketId"));
        audit(text(receptionTask, "ticketId"), text(inspection, "id"), "INSPECTION_ROOM", "SUPPLEMENTARY_INSPECTION",
            text(inspection, "status"), "WAITING", user, reason);
        return workspace(text(receptionTask, "ticketId"), user);
    }

    private Map<String, Object> cancelOrLeave(ObjectNode task, String terminal, SessionUser user, String reason) {
        requireRole(user, Set.of("admin", "frontdesk"), "仅前台或管理员可取消或办理离院");
        String ticketId = text(task, "ticketId");
        jdbcTemplate.update("""
            UPDATE clinic_queue_tasks SET status = 'CANCELLED', exception_reason = ?, version = version + 1,
              updated_by = ?, updated_at = ? WHERE ticket_id = ? AND status <> 'COMPLETED'
            """, reason, user.name(), now(), ticketId);
        jdbcTemplate.update("UPDATE clinic_queue_tickets SET overall_status = ?, version = version + 1, updated_at = ? WHERE id = ?",
            terminal, now(), ticketId);
        audit(ticketId, text(task, "id"), text(task, "roomCode"), terminal, text(task, "status"), terminal, user, reason);
        return workspace(ticketId, user);
    }

    private void interruptCurrentTask(String roomCode, SessionUser user, String reason) {
        List<ObjectNode> active = jdbcTemplate.query("""
            SELECT * FROM clinic_queue_tasks WHERE room_code = ? AND status IN ('CALLED', 'ARRIVED', 'IN_SERVICE')
            ORDER BY updated_at LIMIT 1
            """, (rs, rowNum) -> readTask(rs), roomCode);
        if (active.isEmpty()) return;
        ObjectNode task = active.get(0);
        optimisticUpdate(task, """
            UPDATE clinic_queue_tasks SET status = 'INTERRUPTED', interrupted_from_status = ?, exception_reason = ?,
              version = version + 1, updated_by = ?, updated_at = ? WHERE id = ? AND version = ?
            """, text(task, "status"), reason, user.name(), now(), text(task, "id"), task.path("version").asInt());
        audit(text(task, "ticketId"), text(task, "id"), roomCode, "EMERGENCY_INTERRUPT", text(task, "status"), "INTERRUPTED", user, reason);
    }

    private List<Candidate> waitingCandidates(String stage) {
        return jdbcTemplate.query("""
            SELECT t.id AS task_id, t.ticket_id, q.visit_type, t.queue_entered_at, t.priority_locked
            FROM clinic_queue_tasks t JOIN clinic_queue_tickets q ON q.id = t.ticket_id
            WHERE t.stage_code = ? AND t.status = 'WAITING' AND q.business_date = CURDATE()
            ORDER BY t.queue_entered_at ASC
            """, (rs, rowNum) -> new Candidate(
                rs.getString("task_id"), rs.getString("ticket_id"), rs.getString("visit_type"),
                rs.getTimestamp("queue_entered_at").toLocalDateTime(), rs.getBoolean("priority_locked"), ""
            ), stage);
    }

    static Candidate chooseCandidate(List<Candidate> source, int followUpStreak, LocalDateTime now) {
        if (source == null || source.isEmpty()) return null;
        List<Candidate> candidates = source.stream()
            .sorted(Comparator.comparing(Candidate::enteredAt))
            .toList();
        Candidate locked = candidates.stream().filter(Candidate::priorityLocked).findFirst().orElse(null);
        if (locked != null) return locked.withReason("人工锁定优先");
        List<Candidate> firstVisits = candidates.stream().filter(item -> "FIRST_VISIT".equals(item.visitType())).toList();
        List<Candidate> followUps = candidates.stream().filter(item -> "FOLLOW_UP".equals(item.visitType())).toList();
        Candidate longestFirst = firstVisits.isEmpty() ? null : firstVisits.get(0);
        if (longestFirst != null && waitedMinutes(longestFirst, now) >= FIRST_VISIT_MAX_WAIT_MINUTES) {
            return longestFirst.withReason("初诊已超过最长等待阈值");
        }
        if (followUpStreak >= FOLLOW_UP_STREAK_LIMIT && longestFirst != null
            && waitedMinutes(longestFirst, now) >= FIRST_VISIT_MIN_WAIT_MINUTES) {
            return longestFirst.withReason("已连续服务两个复诊，放行久候初诊");
        }
        if (!followUps.isEmpty()) return followUps.get(0).withReason("复诊加权优先");
        return candidates.get(0).withReason("按入队时间先后");
    }

    private static long waitedMinutes(Candidate candidate, LocalDateTime now) {
        return Math.max(0, Duration.between(candidate.enteredAt(), now).toMinutes());
    }

    private Map<String, Object> displayRoom(String stage) {
        ObjectNode room = loadRoomByStage(stage);
        ArrayNode calling = objectMapper.createArrayNode();
        ArrayNode waiting = objectMapper.createArrayNode();
        jdbcTemplate.query("""
            SELECT t.id, t.status, t.called_at, t.updated_at, q.public_no, q.visit_type
            FROM clinic_queue_tasks t JOIN clinic_queue_tickets q ON q.id = t.ticket_id
            WHERE t.stage_code = ? AND q.business_date = CURDATE() AND t.status IN ('CALLED', 'ARRIVED', 'IN_SERVICE', 'INTERRUPTED')
            ORDER BY t.updated_at DESC LIMIT 3
            """, (RowCallbackHandler) rs -> calling.add(readDisplayTask(rs)), stage);
        jdbcTemplate.query("""
            SELECT t.id, t.status, t.called_at, t.updated_at, q.public_no, q.visit_type
            FROM clinic_queue_tasks t JOIN clinic_queue_tickets q ON q.id = t.ticket_id
            WHERE t.stage_code = ? AND q.business_date = CURDATE() AND t.status = 'WAITING'
            ORDER BY t.priority_locked DESC, t.queue_entered_at ASC LIMIT 10
            """, (RowCallbackHandler) rs -> waiting.add(readDisplayTask(rs)), stage);
        return Map.of("room", plain(room), "calling", plain(calling), "waiting", plain(waiting));
    }

    private Map<String, Object> counts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("inspectionWaiting", countTask(INSPECTION, "WAITING"));
        counts.put("inspectionActive", countTask(INSPECTION, "CALLED") + countTask(INSPECTION, "ARRIVED") + countTask(INSPECTION, "IN_SERVICE"));
        counts.put("receptionWaiting", countTask(RECEPTION, "WAITING"));
        counts.put("receptionActive", countTask(RECEPTION, "CALLED") + countTask(RECEPTION, "ARRIVED") + countTask(RECEPTION, "IN_SERVICE"));
        counts.put("completedToday", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM clinic_queue_tickets WHERE business_date = CURDATE() AND overall_status = 'COMPLETED'", Integer.class));
        counts.put("exceptions", jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM clinic_queue_tasks t JOIN clinic_queue_tickets q ON q.id = t.ticket_id
            WHERE q.business_date = CURDATE() AND t.status IN ('MISSED', 'TEMPORARILY_AWAY', 'INTERRUPTED', 'ON_HOLD')
            """, Integer.class));
        return counts;
    }

    private int countTask(String stage, String status) {
        Integer value = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM clinic_queue_tasks t JOIN clinic_queue_tickets q ON q.id = t.ticket_id
            WHERE q.business_date = CURDATE() AND t.stage_code = ? AND t.status = ?
            """, Integer.class, stage, status);
        return value == null ? 0 : value;
    }

    private void updateOverallStatus(String ticketId, String stage, String taskStatus) {
        String overall = switch (stage + ":" + taskStatus) {
            case "INSPECTION:CALLED" -> "INSPECTION_CALLED";
            case "INSPECTION:ARRIVED", "INSPECTION:IN_SERVICE" -> "INSPECTION_IN_SERVICE";
            case "RECEPTION:CALLED" -> "RECEPTION_CALLED";
            case "RECEPTION:ARRIVED", "RECEPTION:IN_SERVICE" -> "RECEPTION_IN_SERVICE";
            case "INSPECTION:ON_HOLD", "RECEPTION:ON_HOLD", "INSPECTION:INTERRUPTED", "RECEPTION:INTERRUPTED" -> "ON_HOLD";
            default -> null;
        };
        if (overall != null) jdbcTemplate.update("UPDATE clinic_queue_tickets SET overall_status = ?, version = version + 1, updated_at = ? WHERE id = ?", overall, now(), ticketId);
    }

    private EncounterPatient loadEncounterPatient(String encounterId) {
        List<EncounterPatient> rows = jdbcTemplate.query("""
            SELECT id, source_patient_id, visit_no, patient_json FROM pre_ai_encounters WHERE id = ? LIMIT 1
            """, (rs, rowNum) -> {
                JsonNode patient = readJson(rs.getString("patient_json"));
                return new EncounterPatient(
                    rs.getString("id"), safe(rs.getString("source_patient_id")),
                    text(patient, "patientName"), rs.getInt("visit_no")
                );
            }, encounterId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "就诊记录不存在");
        if (safe(rows.get(0).patientName()).isBlank()) throw conflict("就诊记录缺少患者姓名，无法发号");
        return rows.get(0);
    }

    private String nextPublicNo(LocalDate date, String visitType) {
        String prefix = "FOLLOW_UP".equals(visitType) ? "F" : "A";
        Integer current = jdbcTemplate.queryForObject("""
            SELECT COALESCE(MAX(CAST(SUBSTRING(public_no, 2) AS UNSIGNED)), 0)
            FROM clinic_queue_tickets WHERE business_date = ? AND public_no LIKE ?
            """, Integer.class, date, prefix + "%");
        return prefix + String.format("%03d", (current == null ? 0 : current) + 1);
    }

    private void seedRoom(String code, String name, String stage) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM clinic_queue_rooms WHERE room_code = ?", Integer.class, code);
        if (count != null && count > 0) return;
        jdbcTemplate.update("""
            INSERT INTO clinic_queue_rooms (room_code, room_name, stage_code, status, updated_by, updated_at)
            VALUES (?, ?, ?, 'ACTIVE', 'system', ?)
            """, code, name, stage, now());
    }

    private ObjectNode loadTicket(String id) {
        List<ObjectNode> rows = jdbcTemplate.query("SELECT * FROM clinic_queue_tickets WHERE id = ? LIMIT 1", (rs, rowNum) -> readTicket(rs), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "排队单不存在");
        return rows.get(0);
    }

    private ObjectNode loadTask(String id) {
        List<ObjectNode> rows = jdbcTemplate.query("SELECT * FROM clinic_queue_tasks WHERE id = ? LIMIT 1", (rs, rowNum) -> readTask(rs), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "排队任务不存在");
        return rows.get(0);
    }

    private ObjectNode loadTaskByTicketStage(String ticketId, String stage) {
        List<ObjectNode> rows = jdbcTemplate.query("SELECT * FROM clinic_queue_tasks WHERE ticket_id = ? AND stage_code = ? LIMIT 1", (rs, rowNum) -> readTask(rs), ticketId, stage);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "阶段排队任务不存在");
        return rows.get(0);
    }

    private ObjectNode loadRoom(String roomCode) {
        List<ObjectNode> rows = jdbcTemplate.query("SELECT * FROM clinic_queue_rooms WHERE room_code = ? LIMIT 1", (rs, rowNum) -> readRoom(rs), roomCode);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "房间配置不存在");
        return rows.get(0);
    }

    private ObjectNode loadRoomByStage(String stage) {
        List<ObjectNode> rows = jdbcTemplate.query("SELECT * FROM clinic_queue_rooms WHERE stage_code = ? LIMIT 1", (rs, rowNum) -> readRoom(rs), stage);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "阶段房间配置不存在");
        return rows.get(0);
    }

    private ObjectNode readTicket(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("encounterId", rs.getString("encounter_id"));
        row.put("businessDate", rs.getString("business_date"));
        row.put("publicNo", rs.getString("public_no"));
        row.put("visitType", rs.getString("visit_type"));
        row.put("patientId", rs.getString("patient_id"));
        row.put("patientName", rs.getString("patient_name"));
        row.put("maskedName", rs.getString("masked_name"));
        row.put("overallStatus", rs.getString("overall_status"));
        row.put("version", rs.getInt("version"));
        row.put("createdBy", rs.getString("created_by"));
        row.put("createdAt", rs.getString("created_at"));
        row.put("updatedAt", rs.getString("updated_at"));
        putNullable(row, "completedAt", rs.getString("completed_at"));
        return row;
    }

    private ObjectNode readTask(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("ticketId", rs.getString("ticket_id"));
        row.put("stageCode", rs.getString("stage_code"));
        row.put("roomCode", rs.getString("room_code"));
        row.put("status", rs.getString("status"));
        row.put("version", rs.getInt("version"));
        row.put("priorityLocked", rs.getBoolean("priority_locked"));
        row.put("priorityReason", rs.getString("priority_reason"));
        row.put("recallCount", rs.getInt("recall_count"));
        row.put("exceptionReason", rs.getString("exception_reason"));
        row.put("interruptedFromStatus", rs.getString("interrupted_from_status"));
        for (String[] field : List.of(
            new String[]{"queueEnteredAt", "queue_entered_at"}, new String[]{"calledAt", "called_at"},
            new String[]{"arrivedAt", "arrived_at"}, new String[]{"serviceStartedAt", "service_started_at"},
            new String[]{"completedAt", "completed_at"}, new String[]{"updatedAt", "updated_at"}
        )) putNullable(row, field[0], rs.getString(field[1]));
        return row;
    }

    private ObjectNode readRoom(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("roomCode", rs.getString("room_code"));
        row.put("roomName", rs.getString("room_name"));
        row.put("stageCode", rs.getString("stage_code"));
        row.put("status", rs.getString("status"));
        row.put("pauseReason", rs.getString("pause_reason"));
        row.put("followUpStreak", rs.getInt("follow_up_streak"));
        row.put("version", rs.getInt("version"));
        row.put("updatedAt", rs.getString("updated_at"));
        return row;
    }

    private ObjectNode readAnnouncement(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("ticketId", rs.getString("ticket_id"));
        row.put("taskId", rs.getString("task_id"));
        row.put("publicNo", rs.getString("public_no"));
        row.put("stageCode", rs.getString("stage_code"));
        row.put("roomName", rs.getString("room_name"));
        row.put("content", rs.getString("content"));
        row.put("status", rs.getString("status"));
        row.put("playCount", rs.getInt("play_count"));
        row.put("createdAt", rs.getString("created_at"));
        putNullable(row, "expiresAt", rs.getString("expires_at"));
        putNullable(row, "playedAt", rs.getString("played_at"));
        return row;
    }

    private ObjectNode readAudit(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("ticketId", rs.getString("ticket_id"));
        row.put("taskId", rs.getString("task_id"));
        row.put("roomCode", rs.getString("room_code"));
        row.put("actionCode", rs.getString("action_code"));
        row.put("fromStatus", rs.getString("from_status"));
        row.put("toStatus", rs.getString("to_status"));
        row.put("operatorName", rs.getString("operator_name"));
        row.put("operatorRole", rs.getString("operator_role"));
        row.put("detail", rs.getString("detail"));
        row.put("createdAt", rs.getString("created_at"));
        return row;
    }

    private ObjectNode readDisplayTask(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("publicNo", rs.getString("public_no"));
        row.put("visitType", rs.getString("visit_type"));
        row.put("status", rs.getString("status"));
        putNullable(row, "calledAt", rs.getString("called_at"));
        row.put("updatedAt", rs.getString("updated_at"));
        return row;
    }

    private void audit(String ticketId, String taskId, String roomCode, String action, String from, String to, SessionUser user, String detail) {
        jdbcTemplate.update("""
            INSERT INTO clinic_queue_audit_logs (
              id, ticket_id, task_id, room_code, action_code, from_status, to_status,
              operator_id, operator_name, operator_role, detail, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, "cql-" + UUID.randomUUID(), safe(ticketId), safe(taskId), safe(roomCode), action, safe(from), safe(to),
            safe(user == null ? "system" : user.id()), user == null ? "system" : user.name(), user == null ? "system" : user.role(), safe(detail), now());
    }

    private void optimisticUpdate(JsonNode row, String sql, Object... args) {
        int changed = jdbcTemplate.update(sql, args);
        if (changed != 1) throw conflict("数据已被其他终端更新，请刷新后重试");
    }

    private void requireStageRole(String stage, SessionUser user) {
        requireRole(user, INSPECTION.equals(stage) ? INSPECTION_ROLES : RECEPTION_ROLES, "当前岗位无权操作该房间队列");
    }

    private void requireStageOrFrontdeskRole(String stage, String action, SessionUser user) {
        String normalized = safe(action).toUpperCase(Locale.ROOT);
        if (Set.of("CANCEL", "LEAVE", "PRIORITIZE", "RESUME").contains(normalized) && ISSUE_ROLES.contains(user.role())) return;
        requireStageRole(stage, user);
    }

    private void assertRoomRole(ObjectNode room, SessionUser user) {
        if (Set.of("admin", "frontdesk").contains(user.role())) return;
        requireStageRole(text(room, "stageCode"), user);
    }

    private void ensureRoomActive(ObjectNode room) {
        if (!"ACTIVE".equals(text(room, "status"))) throw conflict(text(room, "roomName") + "当前已暂停或停诊，不能产生普通叫号");
    }

    private void requireState(String current, Set<String> allowed, String message) {
        if (!allowed.contains(current)) throw conflict(message + "（当前：" + current + "）");
    }

    private void requireRole(SessionUser user, Set<String> roles, String message) {
        if (user == null || !roles.contains(user.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    private String normalizeStage(String value) {
        String stage = safe(value).toUpperCase(Locale.ROOT);
        if (!STAGES.contains(stage)) throw badRequest("阶段必须为 INSPECTION 或 RECEPTION");
        return stage;
    }

    private String normalizeVisitType(String value, int visitNo) {
        String type = safe(value).toUpperCase(Locale.ROOT);
        if (type.isBlank()) type = visitNo > 1 ? "FOLLOW_UP" : "FIRST_VISIT";
        if (!Set.of("FIRST_VISIT", "FOLLOW_UP").contains(type)) throw badRequest("就诊类型必须为初诊或复诊");
        return type;
    }

    private String maskName(String value) {
        String name = safe(value);
        if (name.isBlank()) return "患者";
        if (name.length() == 1) return name + "*";
        if (name.length() == 2) return name.substring(0, 1) + "*";
        return name.substring(0, 1) + "*".repeat(name.length() - 2) + name.substring(name.length() - 1);
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private Object plain(JsonNode node) {
        return objectMapper.convertValue(node, Object.class);
    }

    private String text(JsonNode node, String key) {
        return node == null || node.path(key).isNull() || node.path(key).isMissingNode() ? "" : node.path(key).asText("");
    }

    private void putNullable(ObjectNode node, String key, String value) {
        if (value == null) node.putNull(key); else node.put(key, value);
    }

    private String stageLabel(String stage) {
        return INSPECTION.equals(stage) ? "检查室" : "接诊室";
    }

    private String visitTypeLabel(String visitType) {
        return "FOLLOW_UP".equals(visitType) ? "复诊" : "初诊";
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
            """, Integer.class, table, column);
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private String required(String value, String label) {
        String normalized = safe(value);
        if (normalized.isBlank()) throw badRequest(label + "不能为空");
        return normalized;
    }

    private String now() {
        return LocalDateTime.now().format(DATE_TIME);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record IssueRequest(String encounterId, String visitType) {}
    public record ActionRequest(String reason) {}
    record EncounterPatient(String encounterId, String patientId, String patientName, int visitNo) {}
    public record Candidate(String taskId, String ticketId, String visitType, LocalDateTime enteredAt, boolean priorityLocked, String reason) {
        Candidate withReason(String nextReason) {
            return new Candidate(taskId, ticketId, visitType, enteredAt, priorityLocked, nextReason);
        }
    }
}
