package com.coshare.patientrecord.clinicqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.preai.PreAiEncounterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.mysql.MySQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClinicQueueServiceMySqlIntegrationTests {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("clinic_queue_test")
        .withUsername("clinic_test")
        .withPassword("clinic_test_password");

    private static final SessionUser ADMIN = new SessionUser(
        "test-admin",
        "test-admin",
        "测试管理员",
        "admin",
        "管理员",
        "测试科室",
        false,
        Instant.now().plusSeconds(3600)
    );

    private JdbcTemplate jdbcTemplate;
    private ClinicQueueService service;
    private PreAiEncounterService preAiService;
    private boolean containerStarted;

    @BeforeAll
    void initializeDatabase() {
        String externalUrl = System.getenv("CLINIC_TEST_MYSQL_URL");
        String jdbcUrl;
        String username;
        String password;
        if (externalUrl == null || externalUrl.isBlank()) {
            MYSQL.start();
            containerStarted = true;
            jdbcUrl = MYSQL.getJdbcUrl();
            username = MYSQL.getUsername();
            password = MYSQL.getPassword();
        } else {
            jdbcUrl = externalUrl;
            username = environmentValue("CLINIC_TEST_MYSQL_USERNAME", "root");
            password = environmentValue("CLINIC_TEST_MYSQL_PASSWORD", "");
        }

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(jdbcUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        jdbcTemplate = new JdbcTemplate(dataSource);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        ObjectMapper objectMapper = new ObjectMapper();
        service = new ClinicQueueService(jdbcTemplate, objectMapper);
        preAiService = new PreAiEncounterService(
            jdbcTemplate,
            objectMapper,
            null,
            null,
            null,
            service,
            null,
            "target/generated-pre-ai-tests"
        );
    }

    @AfterAll
    void stopContainer() {
        if (containerStarted && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @BeforeEach
    void cleanQueueData() {
        jdbcTemplate.update("DELETE FROM clinic_queue_audit_logs");
        jdbcTemplate.update("DELETE FROM clinic_queue_emergencies");
        jdbcTemplate.update("DELETE FROM clinic_queue_announcements");
        jdbcTemplate.update("DELETE FROM clinic_queue_print_tasks");
        jdbcTemplate.update("DELETE FROM clinic_queue_print_terminals");
        jdbcTemplate.update("DELETE FROM clinic_queue_tasks");
        jdbcTemplate.update("DELETE FROM clinic_queue_tickets");
        jdbcTemplate.update("DELETE FROM pre_ai_stage_submissions");
        jdbcTemplate.update("DELETE FROM pre_ai_encounters");
        jdbcTemplate.update("DELETE FROM pre_ai_patient_cases");
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

        Map<String, Object> firstHandoff = service.onClinicalStageCompleted(encounterId, "INSPECTION", ADMIN);
        Map<String, Object> repeatedHandoff = service.onClinicalStageCompleted(encounterId, "INSPECTION", ADMIN);

        assertEquals("RECEPTION", firstHandoff.get("nextStage"));
        assertEquals("WAITING", firstHandoff.get("nextStatus"));
        assertEquals(firstHandoff, repeatedHandoff);
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

    @Test
    void printCreationAndCompletionAreIdempotent() {
        String encounterId = insertEncounter("钱八", 1);
        Map<String, Object> workspace = service.issue(new ClinicQueueService.IssueRequest(encounterId, "FIRST_VISIT"), ADMIN);
        @SuppressWarnings("unchecked")
        String ticketId = String.valueOf(((Map<String, Object>) workspace.get("ticket")).get("id"));
        service.registerPrintTerminal(
            new ClinicQueueService.PrintTerminalRequest("terminal-1", "测试终端", "printer-1", "1.0"),
            ADMIN
        );

        ClinicQueueService.PrintTaskRequest request =
            new ClinicQueueService.PrintTaskRequest("terminal-1", "", "client-request-1");
        Map<String, Object> first = service.createPrintTask(ticketId, request, ADMIN);
        Map<String, Object> duplicate = service.createPrintTask(ticketId, request, ADMIN);
        assertEquals(first.get("id"), duplicate.get("id"));
        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_print_tasks"));

        String executionToken = String.valueOf(first.get("executionToken"));
        ClinicQueueService.PrintResultRequest result =
            new ClinicQueueService.PrintResultRequest("SUCCESS", "printer-1", "", executionToken);
        Map<String, Object> completed = service.completePrintTask(String.valueOf(first.get("id")), result, ADMIN);
        Map<String, Object> repeated = service.completePrintTask(String.valueOf(first.get("id")), result, ADMIN);
        assertEquals("SUCCESS", completed.get("status"));
        assertEquals(completed, repeated);
        assertEquals(1, count("SELECT attempt_count FROM clinic_queue_print_tasks WHERE id = ?", first.get("id")));
        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_audit_logs WHERE action_code = 'PRINT_SUCCESS'"));
    }

    @Test
    void eligibleEncountersExcludeIssuedDraftCancelledAndPreviousDayRecords() {
        String eligibleId = insertEncounter("eligible", 1);
        String issuedId = insertEncounter("issued", 2);
        service.issue(new ClinicQueueService.IssueRequest(issuedId, "FIRST_VISIT"), ADMIN);

        String draftId = insertEncounter("draft", 3);
        jdbcTemplate.update(
            "UPDATE pre_ai_stage_submissions SET status = 'DRAFT', completed_at = NULL WHERE encounter_id = ? AND stage_code = 'REGISTRATION'",
            draftId
        );

        String cancelledId = insertEncounter("cancelled", 4);
        jdbcTemplate.update("UPDATE pre_ai_encounters SET status = 'CANCELLED' WHERE id = ?", cancelledId);

        String previousDayId = insertEncounter("previous-day", 5);
        jdbcTemplate.update(
            "UPDATE pre_ai_encounters SET patient_json = JSON_SET(patient_json, '$.visitDate', ?) WHERE id = ?",
            LocalDate.now().minusDays(1) + " 12:00:00",
            previousDayId
        );

        Map<String, Object> response = service.eligibleEncounters(ADMIN);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> rows = (java.util.List<Map<String, Object>>) response.get("list");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> blocked = (java.util.List<Map<String, Object>>) response.get("blocked");

        assertEquals(1, rows.size());
        assertEquals(eligibleId, rows.get(0).get("encounterId"));
        assertEquals(1, blocked.size());
        assertEquals(draftId, blocked.get(0).get("encounterId"));
        assertEquals(false, blocked.get(0).get("eligible"));
        assertThrows(
            ResponseStatusException.class,
            () -> service.issue(new ClinicQueueService.IssueRequest(draftId, "FIRST_VISIT"), ADMIN)
        );
        assertThrows(
            ResponseStatusException.class,
            () -> service.issue(new ClinicQueueService.IssueRequest(cancelledId, "FIRST_VISIT"), ADMIN)
        );
        assertThrows(
            ResponseStatusException.class,
            () -> service.issue(new ClinicQueueService.IssueRequest(previousDayId, "FIRST_VISIT"), ADMIN)
        );
    }

    @Test
    void followUpRegistrationAndIssueIsIdempotentAndDerivesVisitTypeOnServer() {
        String initialRequestId = "initial-" + UUID.randomUUID();
        preAiService.registerAndIssue(
            new PreAiEncounterService.RegisterAndIssueRequest(
                Map.of(
                    "patientName", "follow-up-patient",
                    "gender", "FEMALE",
                    "age", "42",
                    "visitDate", LocalDate.now() + " 08:30:00"
                ),
                initialRequestId
            ),
            ADMIN
        );
        String patientCaseId = jdbcTemplate.queryForObject(
            "SELECT patient_case_id FROM pre_ai_encounters WHERE registration_request_id = ?",
            String.class,
            initialRequestId
        );
        String followUpRequestId = "follow-up-" + UUID.randomUUID();
        PreAiEncounterService.FollowUpRegisterAndIssueRequest request =
            new PreAiEncounterService.FollowUpRegisterAndIssueRequest(
                LocalDate.now() + " 09:15:00",
                Map.of("visitReason", "postoperative review"),
                followUpRequestId
            );

        Map<String, Object> first = preAiService.createFollowUpAndIssue(patientCaseId, request, ADMIN);
        Map<String, Object> repeated = preAiService.createFollowUpAndIssue(patientCaseId, request, ADMIN);

        String encounterId = jdbcTemplate.queryForObject(
            "SELECT id FROM pre_ai_encounters WHERE registration_request_id = ?",
            String.class,
            followUpRequestId
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> firstQueue = (Map<String, Object>) first.get("queueWorkspace");
        @SuppressWarnings("unchecked")
        Map<String, Object> repeatedQueue = (Map<String, Object>) repeated.get("queueWorkspace");
        assertEquals(firstQueue.get("ticket"), repeatedQueue.get("ticket"));
        assertEquals(2, count("SELECT visit_no FROM pre_ai_encounters WHERE id = ?", encounterId));
        assertEquals(1, count("SELECT COUNT(*) FROM pre_ai_stage_submissions WHERE encounter_id = ? AND stage_code = 'REGISTRATION' AND status = 'COMPLETED'", encounterId));
        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_tickets WHERE encounter_id = ? AND visit_type = 'FOLLOW_UP'", encounterId));
        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_tasks t JOIN clinic_queue_tickets q ON q.id = t.ticket_id WHERE q.encounter_id = ? AND t.stage_code = 'INSPECTION' AND t.status = 'WAITING'", encounterId));
        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_tasks t JOIN clinic_queue_tickets q ON q.id = t.ticket_id WHERE q.encounter_id = ? AND t.stage_code = 'RECEPTION' AND t.status = 'INACTIVE'", encounterId));
    }

    @Test
    void existingFollowUpDraftCanBeCompletedAndIssuedAtomically() {
        String initialRequestId = "initial-draft-" + UUID.randomUUID();
        preAiService.registerAndIssue(
            new PreAiEncounterService.RegisterAndIssueRequest(
                Map.of(
                    "patientName", "draft-patient",
                    "gender", "MALE",
                    "age", "38",
                    "visitDate", LocalDate.now() + " 08:40:00"
                ),
                initialRequestId
            ),
            ADMIN
        );
        String patientCaseId = jdbcTemplate.queryForObject(
            "SELECT patient_case_id FROM pre_ai_encounters WHERE registration_request_id = ?",
            String.class,
            initialRequestId
        );
        Map<String, Object> draft = preAiService.createFollowUp(
            patientCaseId,
            new PreAiEncounterService.FollowUpEncounterCreateRequest(
                LocalDate.now() + " 10:10:00",
                Map.of("visitReason", "draft recovery")
            ),
            ADMIN
        );
        @SuppressWarnings("unchecked")
        String encounterId = String.valueOf(((Map<String, Object>) draft.get("encounter")).get("id"));
        String requestId = "recover-" + UUID.randomUUID();

        preAiService.registerExistingAndIssue(
            encounterId,
            new PreAiEncounterService.ExistingRegisterAndIssueRequest(
                Map.of(
                    "patientName", "draft-patient",
                    "gender", "MALE",
                    "age", "38",
                    "visitDate", LocalDate.now() + " 10:10:00"
                ),
                requestId,
                0
            ),
            ADMIN
        );

        assertEquals(1, count("SELECT COUNT(*) FROM pre_ai_stage_submissions WHERE encounter_id = ? AND stage_code = 'REGISTRATION' AND status = 'COMPLETED'", encounterId));
        assertEquals(1, count("SELECT COUNT(*) FROM clinic_queue_tickets WHERE encounter_id = ? AND visit_type = 'FOLLOW_UP'", encounterId));
        assertEquals(requestId, jdbcTemplate.queryForObject("SELECT registration_request_id FROM pre_ai_encounters WHERE id = ?", String.class, encounterId));
    }

    private String insertEncounter(String patientName, int visitNo) {
        String id = "enc-" + UUID.randomUUID();
        String visitDate = LocalDate.now() + " 12:00:00";
        jdbcTemplate.update(
            """
            INSERT INTO pre_ai_encounters (
              id, source_patient_id, visit_no, case_token, status, current_stage, patient_json, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'IN_PROGRESS', 'FRONT_DESK', ?, ?, ?)
            """,
            id,
            "patient-" + UUID.randomUUID(),
            visitNo,
            "case-" + UUID.randomUUID(),
            "{\"patientName\":\"" + patientName + "\",\"visitDate\":\"" + visitDate + "\"}",
            visitDate,
            visitDate
        );
        jdbcTemplate.update(
            """
            INSERT INTO pre_ai_stage_submissions (
              encounter_id, stage_code, status, version, data_json, submitted_by, submitted_by_role, completed_at, updated_at
            ) VALUES (?, 'REGISTRATION', 'COMPLETED', 1, JSON_OBJECT('patientName', ?, 'visitDate', ?),
                      'integration-test', 'admin', ?, ?)
            """,
            id,
            patientName,
            visitDate,
            visitDate,
            visitDate
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

    private String environmentValue(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
