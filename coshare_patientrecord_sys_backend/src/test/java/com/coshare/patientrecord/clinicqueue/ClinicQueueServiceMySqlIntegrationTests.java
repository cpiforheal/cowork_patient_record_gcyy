package com.coshare.patientrecord.clinicqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClinicQueueServiceMySqlIntegrationTests {

    private static final SessionUser ADMIN = new SessionUser(
        "test-admin",
        "test-admin",
        "测试管理员",
        "admin",
        "管理员",
        "测试科室",
        Instant.now().plusSeconds(3600)
    );

    private JdbcTemplate jdbcTemplate;
    private ClinicQueueService service;

    @BeforeAll
    void initializeDatabase() {
        String url = System.getenv("CLINIC_QUEUE_TEST_MYSQL_URL");
        assumeTrue(url != null && !url.isBlank(), "未配置 CLINIC_QUEUE_TEST_MYSQL_URL，跳过 MySQL 集成测试");

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(System.getenv().getOrDefault("CLINIC_QUEUE_TEST_MYSQL_USERNAME", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("CLINIC_QUEUE_TEST_MYSQL_PASSWORD", "root"));
        jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS pre_ai_encounters (
              id VARCHAR(64) PRIMARY KEY,
              source_patient_id VARCHAR(64),
              visit_no INT NOT NULL DEFAULT 1,
              patient_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS pre_ai_stage_submissions (
              encounter_id VARCHAR(64) NOT NULL,
              stage_code VARCHAR(32) NOT NULL,
              status VARCHAR(32) NOT NULL,
              PRIMARY KEY (encounter_id, stage_code)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        service = new ClinicQueueService(jdbcTemplate, new ObjectMapper());
        service.initializeSchema();
    }

    @BeforeEach
    void cleanQueueData() {
        jdbcTemplate.update("DELETE FROM clinic_queue_audit_logs");
        jdbcTemplate.update("DELETE FROM clinic_queue_emergencies");
        jdbcTemplate.update("DELETE FROM clinic_queue_announcements");
        jdbcTemplate.update("DELETE FROM clinic_queue_tasks");
        jdbcTemplate.update("DELETE FROM clinic_queue_tickets");
        jdbcTemplate.update("DELETE FROM pre_ai_stage_submissions");
        jdbcTemplate.update("DELETE FROM pre_ai_encounters");
        jdbcTemplate.update("""
            UPDATE clinic_queue_rooms
            SET status = 'ACTIVE', pause_reason = '', follow_up_streak = 0, version = 0,
                updated_by = 'integration-test', updated_at = NOW()
            """);
    }

    @Test
    void issueIsIdempotentAndCreatesTwoStageTasks() {
        String encounterId = insertEncounter("张三", 1);

        Map<String, Object> first = service.issue(
            new ClinicQueueService.IssueRequest(encounterId, "FIRST_VISIT"),
            ADMIN
        );
        Map<String, Object> second = service.issue(
            new ClinicQueueService.IssueRequest(encounterId, "FIRST_VISIT"),
            ADMIN
        );

        assertNotNull(first.get("ticket"));
        assertEquals(first.get("ticket"), second.get("ticket"));
        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_tickets WHERE encounter_id = ?", encounterId));
        assertEquals(2, count("SELECT COUNT(*) FROM clinic_queue_tasks"));
        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_tasks WHERE stage_code = 'INSPECTION' AND status = 'WAITING'"));
        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_tasks WHERE stage_code = 'RECEPTION' AND status = 'INACTIVE'"));
    }

    @Test
    void clinicalInspectionCompletionIdempotentlyActivatesReception() {
        String encounterId = insertEncounter("李四", 2);
        service.issue(new ClinicQueueService.IssueRequest(encounterId, "FOLLOW_UP"), ADMIN);

        service.onClinicalStageCompleted(encounterId, "INSPECTION", ADMIN);
        service.onClinicalStageCompleted(encounterId, "INSPECTION", ADMIN);

        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_tasks WHERE stage_code = 'INSPECTION' AND status = 'COMPLETED'"));
        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_tasks WHERE stage_code = 'RECEPTION' AND status = 'WAITING'"));
        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_tickets WHERE overall_status = 'WAITING_RECEPTION'"));
        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_audit_logs WHERE action_code = 'AUTO_TRANSFER_RECEPTION'"));
    }

    @Test
    void taskTransitionsAndAnnouncementsAreOrderedAndIdempotent() {
        String encounterId = insertEncounter("王五", 1);
        service.issue(new ClinicQueueService.IssueRequest(encounterId, "FIRST_VISIT"), ADMIN);
        String taskId = inspectionTaskId();

        service.taskAction(taskId, "CALL", new ClinicQueueService.ActionRequest("首次叫号"), ADMIN);
        service.taskAction(taskId, "RECALL", new ClinicQueueService.ActionRequest("患者未听清"), ADMIN);

        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_announcements WHERE status = 'PENDING'"));
        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_announcements WHERE status = 'SUPERSEDED'"));
        String pendingId = jdbcTemplate.queryForObject(
            "SELECT id FROM clinic_queue_announcements WHERE status = 'PENDING'",
            String.class
        );
        service.markAnnouncementPlayed(pendingId, ADMIN);
        service.markAnnouncementPlayed(pendingId, ADMIN);
        assertEquals(1, count("SELECT play_count FROM clinic_queue_announcements WHERE id = ?", pendingId));

        service.taskAction(taskId, "ARRIVE", new ClinicQueueService.ActionRequest("已到场"), ADMIN);
        service.taskAction(taskId, "START", new ClinicQueueService.ActionRequest("开始检查"), ADMIN);
        assertEquals("IN_SERVICE", taskStatus(taskId));
    }

    @Test
    void expiredAnnouncementIsNotReturnedAsPending() {
        String encounterId = insertEncounter("赵六", 1);
        service.issue(new ClinicQueueService.IssueRequest(encounterId, "FIRST_VISIT"), ADMIN);
        service.taskAction(inspectionTaskId(), "CALL", new ClinicQueueService.ActionRequest("首次叫号"), ADMIN);
        jdbcTemplate.update("UPDATE clinic_queue_announcements SET expires_at = DATE_SUB(NOW(), INTERVAL 1 SECOND)");

        Map<String, Object> response = service.pendingAnnouncements(ADMIN);

        assertTrue(((java.util.List<?>) response.get("rows")).isEmpty());
        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_announcements WHERE status = 'EXPIRED'"));
    }

    @Test
    void emergencyPausesOnlyAffectedRoomAndInterruptsCurrentTask() {
        String encounterId = insertEncounter("孙七", 1);
        service.issue(new ClinicQueueService.IssueRequest(encounterId, "FIRST_VISIT"), ADMIN);
        String taskId = inspectionTaskId();
        service.taskAction(taskId, "CALL", new ClinicQueueService.ActionRequest("首次叫号"), ADMIN);

        service.roomAction(
            "INSPECTION_ROOM",
            "EMERGENCY",
            new ClinicQueueService.ActionRequest("急症患者优先处置"),
            ADMIN
        );

        assertEquals("INTERRUPTED", taskStatus(taskId));
        assertEquals("EMERGENCY_PAUSED", roomStatus("INSPECTION_ROOM"));
        assertEquals("ACTIVE", roomStatus("RECEPTION_ROOM"));
        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_emergencies WHERE room_code = 'INSPECTION_ROOM' AND status = 'ACTIVE'"));

        service.roomAction("INSPECTION_ROOM", "RESUME", new ClinicQueueService.ActionRequest("急症处理完成"), ADMIN);
        assertEquals("ACTIVE", roomStatus("INSPECTION_ROOM"));
        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_emergencies WHERE room_code = 'INSPECTION_ROOM' AND status = 'ENDED'"));
    }

    private String insertEncounter(String patientName, int visitNo) {
        String id = "enc-" + UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO pre_ai_encounters (id, source_patient_id, visit_no, patient_json) VALUES (?, ?, ?, ?)",
            id,
            "patient-" + UUID.randomUUID(),
            visitNo,
            "{\"patientName\":\"" + patientName + "\"}"
        );
        return id;
    }

    private String inspectionTaskId() {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM clinic_queue_tasks WHERE stage_code = 'INSPECTION' LIMIT 1",
            String.class
        );
    }

    private String taskStatus(String taskId) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM clinic_queue_tasks WHERE id = ?",
            String.class,
            taskId
        );
    }

    private String roomStatus(String roomCode) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM clinic_queue_rooms WHERE room_code = ?",
            String.class,
            roomCode
        );
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
