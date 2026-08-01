package com.coshare.patientrecord.preai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.AuthNavigationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class PreAiEncounterServiceTests {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private AuthNavigationService navigationService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PreAiEncounterService service;

    @BeforeEach
    void setUp() {
        service = new PreAiEncounterService(
            jdbcTemplate,
            objectMapper,
            null,
            null,
            null,
            null,
            null,
            navigationService,
            "build/generated-pre-ai-test"
        );
    }

    @Test
    void exportDownloadUrlUsesClinicRelativeBusinessPath() {
        String url = PreAiEncounterService.exportDownloadUrl("encounter-1", "export-1");

        assertEquals("/pre-ai/encounters/encounter-1/exports/export-1/download", url);
        assertFalse(url.startsWith("/clinic-api/"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void currentWorkflowStageGrantsVisibilityToResponsiblePost() throws Exception {
        stubEncounter("INSPECTION");
        doReturn(0).when(jdbcTemplate).queryForObject(
            contains("pre_ai_encounter_department_grants"),
            eq(Integer.class),
            eq("encounter-1"),
            eq("user-1")
        );
        doReturn(true).when(navigationService).canEditStage("inspection", "INSPECTION");

        assertThat(canAccessEncounter("encounter-1", user("inspection"))).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeQueueStageGrantsVisibilityBeforeCurrentStageAdvances() throws Exception {
        stubEncounter("REGISTRATION");
        doReturn(0).when(jdbcTemplate).queryForObject(
            contains("pre_ai_encounter_department_grants"),
            eq(Integer.class),
            eq("encounter-1"),
            eq("user-1")
        );
        doReturn(false).when(navigationService).canEditStage("inspection", "REGISTRATION");
        doReturn(List.of("INSPECTION")).when(jdbcTemplate).query(
            contains("FROM clinic_queue_tickets"),
            any(RowMapper.class),
            eq("encounter-1")
        );
        doReturn(true).when(navigationService).canEditStage("inspection", "INSPECTION");

        assertThat(canAccessEncounter("encounter-1", user("inspection"))).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void unrelatedPostCannotSeeEncounterWithoutDepartmentDutyStageOrQueueAccess() throws Exception {
        stubEncounter("REGISTRATION");
        doReturn(0).when(jdbcTemplate).queryForObject(
            contains("pre_ai_encounter_department_grants"),
            eq(Integer.class),
            eq("encounter-1"),
            eq("user-1")
        );
        doReturn(false).when(navigationService).canEditStage("inspection", "REGISTRATION");
        doReturn(List.of()).when(jdbcTemplate).query(
            contains("FROM clinic_queue_tickets"),
            any(RowMapper.class),
            eq("encounter-1")
        );

        assertThat(canAccessEncounter("encounter-1", user("inspection"))).isFalse();
    }

    @SuppressWarnings("unchecked")
    private void stubEncounter(String currentStage) {
        ObjectNode encounter = objectMapper.createObjectNode();
        encounter.put("id", "encounter-1");
        encounter.put("owningDepartmentId", "dept-other");
        encounter.put("currentStage", currentStage);
        encounter.putArray("dutyAssignments");
        doReturn(List.of(encounter)).when(jdbcTemplate).query(
            eq("SELECT * FROM pre_ai_encounters WHERE id = ? LIMIT 1"),
            any(RowMapper.class),
            eq("encounter-1")
        );
    }

    private boolean canAccessEncounter(String encounterId, SessionUser user) throws Exception {
        Method method = PreAiEncounterService.class.getDeclaredMethod("canAccessEncounter", String.class, SessionUser.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, encounterId, user);
    }

    private SessionUser user(String role) {
        return new SessionUser("user-1", role, role, role, role, "dept-user", "门诊", false, Instant.now().plusSeconds(3600));
    }
}
