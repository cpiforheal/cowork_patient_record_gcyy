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
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_queue_print_templates (
              template_code VARCHAR(64) PRIMARY KEY,
              config_json JSON NOT NULL,
              updated_by VARCHAR(100) NOT NULL,
              updated_at DATETIME NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_queue_print_terminals (
              terminal_id VARCHAR(80) PRIMARY KEY,
              terminal_name VARCHAR(120) NOT NULL,
              printer_name VARCHAR(200) NOT NULL DEFAULT '',
              agent_version VARCHAR(40) NOT NULL DEFAULT '',
              status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
              last_seen_at DATETIME NOT NULL,
              registered_by VARCHAR(100) NOT NULL,
              updated_at DATETIME NOT NULL,
              INDEX idx_clinic_queue_print_terminal_status (status, last_seen_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_queue_print_tasks (
              id VARCHAR(64) PRIMARY KEY,
              ticket_id VARCHAR(64) NOT NULL,
              terminal_id VARCHAR(80) NOT NULL,
              printer_name VARCHAR(200) NOT NULL DEFAULT '',
              status VARCHAR(24) NOT NULL,
              print_type VARCHAR(24) NOT NULL,
              reprint_reason VARCHAR(500) NOT NULL DEFAULT '',
              attempt_count INT NOT NULL DEFAULT 0,
              payload_json JSON NOT NULL,
              execution_token VARCHAR(80) NOT NULL,
              error_message VARCHAR(1000) NOT NULL DEFAULT '',
              created_by VARCHAR(100) NOT NULL,
              created_by_role VARCHAR(64) NOT NULL,
              created_at DATETIME NOT NULL,
              started_at DATETIME NULL,
              completed_at DATETIME NULL,
              updated_at DATETIME NOT NULL,
              UNIQUE KEY uq_clinic_queue_print_token (execution_token),
              INDEX idx_clinic_queue_print_ticket (ticket_id, created_at),
              INDEX idx_clinic_queue_print_terminal (terminal_id, status, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        addColumnIfMissing("clinic_queue_announcements", "expires_at", "DATETIME NULL");
        jdbcTemplate.update("UPDATE clinic_queue_announcements SET expires_at = DATE_ADD(created_at, INTERVAL 2 MINUTE) WHERE expires_at IS NULL");
        seedPrintTemplate();
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
        if (!existing.isEmpty()) return issueResult(String.valueOf(existing.get(0).get("id")), user, false);

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
            if (!concurrent.isEmpty()) return issueResult(String.valueOf(concurrent.get(0).get("id")), user, false);
            throw conflict("号码生成冲突，请重试");
        }
        audit(id, inspectionTaskId, "INSPECTION_ROOM", "TICKET_ISSUED", "", "WAITING", user,
            "前台发号：" + publicNo + "，" + visitTypeLabel(visitType));
        return issueResult(id, user, true);
    }

    private Map<String, Object> issueResult(String ticketId, SessionUser user, boolean newlyIssued) {
        Map<String, Object> result = new LinkedHashMap<>(workspace(ticketId, user));
        result.put("newlyIssued", newlyIssued);
        result.put("issueMessage", newlyIssued ? "新号码已生成" : "该就诊记录已有排队号码");
        return result;
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
            case "START" -> transition(task, Set.of("ARRIVED"), "IN_SERVICE", user, reason, "service_started_at = ?", now());
            case "RESTORE" -> restoreInterruptedTask(task, user, reason);
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

    public Map<String, Object> printTemplate(SessionUser user) {
        requireRole(user, READ_ROLES, "当前岗位无权查看票据模板");
        return Map.of("config", plain(loadPrintTemplate()));
    }

    public Map<String, Object> savePrintTemplate(PrintTemplateRequest request, SessionUser user) {
        requireRole(user, Set.of("admin"), "仅管理员可修改票据模板");
        ObjectNode config = normalizePrintTemplate(request);
        String timestamp = now();
        jdbcTemplate.update("""
            INSERT INTO clinic_queue_print_templates (template_code, config_json, updated_by, updated_at)
            VALUES ('CLINIC_QUEUE_TICKET', CAST(? AS JSON), ?, ?)
            ON DUPLICATE KEY UPDATE config_json = VALUES(config_json), updated_by = VALUES(updated_by), updated_at = VALUES(updated_at)
            """, config.toString(), user.name(), timestamp);
        audit("", "", "", "PRINT_TEMPLATE_UPDATED", "", "", user, "排队票据模板已更新");
        return Map.of("config", plain(config));
    }

    public Map<String, Object> testPrintPayload(PrintTaskRequest request, SessionUser user) {
        requireRole(user, ISSUE_ROLES, "仅前台或管理员可测试打印");
        String terminalId = required(request == null ? "" : request.terminalId(), "打印终端");
        Map<String, Object> terminal = printTerminal(terminalId);
        ObjectNode payload = buildPrintPayload("TEST-" + UUID.randomUUID(), "TEST-001", "测*者", "初诊", terminal, false, true);
        return Map.of("payload", plain(payload));
    }

    public Map<String, Object> registerPrintTerminal(PrintTerminalRequest request, SessionUser user) {
        requireRole(user, ISSUE_ROLES, "仅前台或管理员可登记打印终端");
        String terminalId = required(request == null ? "" : request.terminalId(), "终端编号");
        String terminalName = required(request == null ? "" : request.terminalName(), "终端名称");
        String printerName = safe(request == null ? "" : request.printerName());
        String agentVersion = safe(request == null ? "" : request.agentVersion());
        String timestamp = now();
        jdbcTemplate.update("""
            INSERT INTO clinic_queue_print_terminals (
              terminal_id, terminal_name, printer_name, agent_version, status, last_seen_at, registered_by, updated_at
            ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
            ON DUPLICATE KEY UPDATE terminal_name = VALUES(terminal_name), printer_name = VALUES(printer_name),
              agent_version = VALUES(agent_version), status = 'ACTIVE', last_seen_at = VALUES(last_seen_at), updated_at = VALUES(updated_at)
            """, terminalId, terminalName, printerName, agentVersion, timestamp, user.name(), timestamp);
        return printTerminal(terminalId);
    }

    public Map<String, Object> createPrintTask(String ticketId, PrintTaskRequest request, SessionUser user) {
        requireRole(user, ISSUE_ROLES, "仅前台或管理员可打印排队票");
        ObjectNode ticket = loadTicket(ticketId);
        String terminalId = required(request == null ? "" : request.terminalId(), "打印终端");
        Map<String, Object> terminal = printTerminal(terminalId);
        if (!"ACTIVE".equals(String.valueOf(terminal.get("status")))) throw conflict("打印终端未启用");
        Integer previousSuccess = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM clinic_queue_print_tasks WHERE ticket_id = ? AND status = 'SUCCESS'", Integer.class, ticketId
        );
        boolean reprint = previousSuccess != null && previousSuccess > 0;
        String reason = safe(request == null ? "" : request.reason());
        if (reprint && reason.isBlank()) throw badRequest("补打票据必须填写原因");
        String id = "cqprint-" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        String timestamp = now();
        ObjectNode payload = buildPrintPayload(id, text(ticket, "publicNo"), text(ticket, "maskedName"),
            visitTypeLabel(text(ticket, "visitType")), terminal, reprint, false);
        payload.put("executionToken", token);
        jdbcTemplate.update("""
            INSERT INTO clinic_queue_print_tasks (
              id, ticket_id, terminal_id, printer_name, status, print_type, reprint_reason, attempt_count,
              payload_json, execution_token, created_by, created_by_role, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'PENDING', ?, ?, 0, CAST(? AS JSON), ?, ?, ?, ?, ?)
            """, id, ticketId, terminalId, String.valueOf(terminal.getOrDefault("printerName", "")),
            reprint ? "REPRINT" : "INITIAL", reason, payload.toString(), token, user.name(), user.role(), timestamp, timestamp);
        audit(ticketId, "", "", reprint ? "TICKET_REPRINT_REQUESTED" : "TICKET_PRINT_REQUESTED", "", "PENDING", user,
            (reprint ? "补打" : "打印") + "排队票，终端：" + terminalId + (reason.isBlank() ? "" : "，原因：" + reason));
        return printTask(id);
    }

    public Map<String, Object> completePrintTask(String id, PrintResultRequest request, SessionUser user) {
        requireRole(user, ISSUE_ROLES, "仅前台或管理员可确认打印结果");
        Map<String, Object> task = printTask(id);
        String status = safe(request == null ? "" : request.status()).toUpperCase(Locale.ROOT);
        if (!Set.of("SUCCESS", "FAILED").contains(status)) throw badRequest("打印结果必须为 SUCCESS 或 FAILED");
        String printerName = safe(request == null ? "" : request.printerName());
        String error = safe(request == null ? "" : request.errorMessage());
        String timestamp = now();
        jdbcTemplate.update("""
            UPDATE clinic_queue_print_tasks SET status = ?, printer_name = ?, error_message = ?,
              attempt_count = attempt_count + 1, started_at = COALESCE(started_at, ?), completed_at = ?, updated_at = ?
            WHERE id = ?
            """, status, printerName, error, timestamp, timestamp, timestamp, id);
        audit(String.valueOf(task.get("ticketId")), "", "", "PRINT_" + status, "PENDING", status, user,
            status.equals("SUCCESS") ? "排队票打印成功：" + printerName : "排队票打印失败：" + error);
        return printTask(id);
    }

    public Map<String, Object> printTasks(String ticketId, SessionUser user) {
        requireRole(user, READ_ROLES, "当前岗位无权查看打印记录");
        ArrayNode rows = objectMapper.createArrayNode();
        jdbcTemplate.query("SELECT * FROM clinic_queue_print_tasks WHERE ticket_id = ? ORDER BY created_at DESC LIMIT 50",
            (RowCallbackHandler) rs -> rows.add(readPrintTask(rs)), ticketId);
        return Map.of("rows", plain(rows));
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

    private Map<String, Object> restoreInterruptedTask(ObjectNode task, SessionUser user, String reason) {
        requireState(text(task, "status"), Set.of("INTERRUPTED"), "仅急症中断患者可恢复原状态");
        String restored = text(task, "interruptedFromStatus");
        if (!Set.of("CALLED", "ARRIVED", "IN_SERVICE").contains(restored)) {
            throw conflict("缺少有效的中断前状态，请挂起后重新排队");
        }
        if ("CALLED".equals(restored)) createAnnouncement(task, user, true);
        updateTask(task, restored, user, reason.isBlank() ? "急症处理结束，恢复中断前状态" : reason,
            "interrupted_from_status = NULL", new Object[0]);
        updateOverallStatus(text(task, "ticketId"), text(task, "stageCode"), restored);
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
            WHERE room_code = ? AND status IN ('CALLED', 'ARRIVED', 'IN_SERVICE', 'INTERRUPTED') AND id <> ?
            """, Integer.class, text(task, "roomCode"), taskId);
        if (active != null && active > 0) throw conflict("该房间仍有已叫号、办理中或急症中断患者，请先完成处理");
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
            String receptionStatus = text(reception, "status");
            if (shouldActivateReceptionAfterInspection(receptionStatus)) {
                updateTask(reception, "WAITING", user, "检查完成，自动转入接诊队列", "queue_entered_at = ?", now());
                audit(text(task, "ticketId"), text(reception, "id"), text(reception, "roomCode"), "AUTO_TRANSFER_RECEPTION",
                    receptionStatus, "WAITING", user, "沿用同一号码自动进入接诊队列");
            }
            jdbcTemplate.update("UPDATE clinic_queue_tickets SET overall_status = 'WAITING_RECEPTION', version = version + 1, updated_at = ? WHERE id = ?",
                now(), text(task, "ticketId"));
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
        updateOverallStatus(text(task, "ticketId"), text(task, "stageCode"), "INTERRUPTED");
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
            WHERE t.stage_code = ? AND q.business_date = CURDATE() AND t.status IN ('CALLED', 'ARRIVED', 'IN_SERVICE')
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
        String overall = overallStatusFor(stage, taskStatus);
        if (overall != null) jdbcTemplate.update("UPDATE clinic_queue_tickets SET overall_status = ?, version = version + 1, updated_at = ? WHERE id = ?", overall, now(), ticketId);
    }

    static String overallStatusFor(String stage, String taskStatus) {
        return switch (stage + ":" + taskStatus) {
            case "INSPECTION:WAITING", "INSPECTION:MISSED", "INSPECTION:TEMPORARILY_AWAY" -> "WAITING_INSPECTION";
            case "INSPECTION:CALLED" -> "INSPECTION_CALLED";
            case "INSPECTION:ARRIVED", "INSPECTION:IN_SERVICE" -> "INSPECTION_IN_SERVICE";
            case "RECEPTION:WAITING", "RECEPTION:MISSED", "RECEPTION:TEMPORARILY_AWAY" -> "WAITING_RECEPTION";
            case "RECEPTION:CALLED" -> "RECEPTION_CALLED";
            case "RECEPTION:ARRIVED", "RECEPTION:IN_SERVICE" -> "RECEPTION_IN_SERVICE";
            case "INSPECTION:ON_HOLD", "RECEPTION:ON_HOLD", "INSPECTION:INTERRUPTED", "RECEPTION:INTERRUPTED" -> "ON_HOLD";
            default -> null;
        };
    }

    static boolean shouldActivateReceptionAfterInspection(String receptionStatus) {
        return Set.of("INACTIVE", "ON_HOLD").contains(receptionStatus);
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

    private Map<String, Object> printTerminal(String terminalId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("SELECT * FROM clinic_queue_print_terminals WHERE terminal_id = ? LIMIT 1", (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("terminalId", rs.getString("terminal_id"));
            row.put("terminalName", rs.getString("terminal_name"));
            row.put("printerName", rs.getString("printer_name"));
            row.put("agentVersion", rs.getString("agent_version"));
            row.put("status", rs.getString("status"));
            row.put("lastSeenAt", rs.getString("last_seen_at"));
            return row;
        }, terminalId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "打印终端尚未登记");
        return rows.get(0);
    }

    private Map<String, Object> printTask(String id) {
        List<ObjectNode> rows = jdbcTemplate.query("SELECT * FROM clinic_queue_print_tasks WHERE id = ? LIMIT 1", (rs, rowNum) -> readPrintTask(rs), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "打印任务不存在");
        return objectMapper.convertValue(rows.get(0), Map.class);
    }

    private ObjectNode readPrintTask(ResultSet rs) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", rs.getString("id"));
        row.put("ticketId", rs.getString("ticket_id"));
        row.put("terminalId", rs.getString("terminal_id"));
        row.put("printerName", rs.getString("printer_name"));
        row.put("status", rs.getString("status"));
        row.put("printType", rs.getString("print_type"));
        row.put("reprintReason", rs.getString("reprint_reason"));
        row.put("attemptCount", rs.getInt("attempt_count"));
        row.set("payload", readJson(rs.getString("payload_json")));
        row.put("createdBy", rs.getString("created_by"));
        row.put("createdAt", rs.getString("created_at"));
        putNullable(row, "completedAt", rs.getString("completed_at"));
        row.put("errorMessage", rs.getString("error_message"));
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

    private ObjectNode loadPrintTemplate() {
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT config_json FROM clinic_queue_print_templates WHERE template_code = 'CLINIC_QUEUE_TICKET' LIMIT 1",
            (rs, rowNum) -> (ObjectNode) readJson(rs.getString("config_json"))
        );
        return rows.isEmpty() ? defaultPrintTemplate() : rows.get(0);
    }

    private void seedPrintTemplate() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM clinic_queue_print_templates WHERE template_code = 'CLINIC_QUEUE_TICKET'", Integer.class
        );
        if (count != null && count > 0) return;
        jdbcTemplate.update(
            "INSERT INTO clinic_queue_print_templates (template_code, config_json, updated_by, updated_at) VALUES ('CLINIC_QUEUE_TICKET', CAST(? AS JSON), 'system', ?)",
            defaultPrintTemplate().toString(), now()
        );
    }

    private ObjectNode defaultPrintTemplate() {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("institutionName", "门诊部");
        config.put("title", "排队凭证");
        config.put("paperWidth", 58);
        config.put("numberFontSize", 42);
        config.put("compact", true);
        config.put("showMaskedName", true);
        config.put("showVisitType", true);
        config.put("showFirstStage", true);
        config.put("showIssuedAt", true);
        config.put("showNotice", true);
        config.put("notice", "请留意大屏及语音叫号");
        config.put("secondaryNotice", "检查完成后沿用本号码");
        return config;
    }

    private ObjectNode normalizePrintTemplate(PrintTemplateRequest request) {
        ObjectNode config = defaultPrintTemplate();
        if (request == null) return config;
        config.put("institutionName", safe(request.institutionName()).isBlank() ? "门诊部" : safe(request.institutionName()));
        config.put("title", safe(request.title()).isBlank() ? "排队凭证" : safe(request.title()));
        config.put("paperWidth", request.paperWidth() == 80 ? 80 : 58);
        config.put("numberFontSize", Math.max(30, Math.min(64, request.numberFontSize())));
        config.put("compact", request.compact());
        config.put("showMaskedName", request.showMaskedName());
        config.put("showVisitType", request.showVisitType());
        config.put("showFirstStage", request.showFirstStage());
        config.put("showIssuedAt", request.showIssuedAt());
        config.put("showNotice", request.showNotice());
        config.put("notice", safe(request.notice()));
        config.put("secondaryNotice", safe(request.secondaryNotice()));
        return config;
    }

    private ObjectNode buildPrintPayload(String taskId, String publicNo, String maskedName, String visitType,
                                         Map<String, Object> terminal, boolean reprint, boolean test) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", taskId);
        payload.put("executionToken", UUID.randomUUID().toString());
        payload.put("terminalId", String.valueOf(terminal.get("terminalId")));
        payload.put("printerName", String.valueOf(terminal.getOrDefault("printerName", "")));
        payload.put("publicNo", publicNo);
        payload.put("maskedName", maskedName);
        payload.put("visitType", visitType);
        payload.put("firstStage", "检查室");
        payload.put("issuedAt", now());
        payload.put("reprint", reprint);
        payload.put("test", test);
        payload.set("template", loadPrintTemplate());
        return payload;
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
    public record PrintTemplateRequest(String institutionName, String title, int paperWidth, int numberFontSize,
                                       boolean compact, boolean showMaskedName, boolean showVisitType,
                                       boolean showFirstStage, boolean showIssuedAt, boolean showNotice,
                                       String notice, String secondaryNotice) {}
    public record PrintTerminalRequest(String terminalId, String terminalName, String printerName, String agentVersion) {}
    public record PrintTaskRequest(String terminalId, String reason) {}
    public record PrintResultRequest(String status, String printerName, String errorMessage) {}
    record EncounterPatient(String encounterId, String patientId, String patientName, int visitNo) {}
    public record Candidate(String taskId, String ticketId, String visitType, LocalDateTime enteredAt, boolean priorityLocked, String reason) {
        Candidate withReason(String nextReason) {
            return new Candidate(taskId, ticketId, visitType, enteredAt, priorityLocked, nextReason);
        }
    }
}
