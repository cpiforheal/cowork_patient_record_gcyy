package com.coshare.patientrecord.maintenance.datapurge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.backup.service.ClinicBackupService;
import com.coshare.patientrecord.maintenance.datapurge.dto.DataPurgeExecuteRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class DataPurgeServiceTest {

    @Mock JdbcTemplate jdbc;
    @Mock PasswordEncoder passwordEncoder;
    @Mock ClinicBackupService backupService;
    @Mock PlatformTransactionManager transactionManager;

    private DataPurgeService service;
    private DataPurgeMaintenanceState maintenanceState;

    @BeforeEach
    void setUp() {
        Path root = Path.of("target", "data-purge-test").toAbsolutePath();
        maintenanceState = new DataPurgeMaintenanceState();
        service = new DataPurgeService(
            jdbc,
            new ObjectMapper(),
            passwordEncoder,
            backupService,
            transactionManager,
            maintenanceState,
            root.toString(),
            root.resolve("attachments").toString(),
            root.resolve("generated-pre-ai").toString(),
            root.resolve("generated-medical-records").toString(),
            root.resolve("medical-record-workflow").toString(),
            root.resolve("generated-ai-documents").toString()
        );
        lenient().doReturn(0).when(jdbc).queryForObject(anyString(), eq(Integer.class), any(Object[].class));
        SessionUser admin = new SessionUser(
            "admin-1", "admin", "Administrator", "admin", "Administrator",
            "dept-1", "Information", false, Instant.now().plusSeconds(3600)
        );
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void previewIssuesShortLivedOperatorBoundTokenAndShowsExactConfirmationText() {
        var preview = service.preview();

        assertThat(preview.path("token").asText()).isNotBlank();
        assertThat(preview.path("expiresAt").asText()).isNotBlank();
        assertThat(preview.path("confirmationText").asText()).isEqualTo(DataPurgeService.CONFIRMATION_TEXT);
        assertThat(preview.path("counts").path("accounts").asLong()).isZero();
        assertThat(preview.path("managedFiles").path("directories").size()).isEqualTo(5);
    }

    @Test
    void executeRejectsWrongConfirmationBeforePasswordOrBackupWork() {
        String token = service.preview().path("token").asText();
        DataPurgeExecuteRequest request = new DataPurgeExecuteRequest("password", token, "wrong");

        assertThatThrownBy(() -> service.execute(request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("确认文本不正确");
    }

    @Test
    void nonAdminCannotPreviewDestructiveCounts() {
        SessionUser manager = new SessionUser(
            "manager-1", "manager", "Manager", "manager", "Manager",
            "dept-1", "Information", false, Instant.now().plusSeconds(3600)
        );
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(manager, null));

        assertThatThrownBy(service::preview)
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("仅系统管理员");
    }

    @Test
    void inventoryPurgeRetainsOnlyLocationInfrastructure() {
        assertThat(DataPurgeService.inventoryTablesForPurge())
            .contains("inventory_items", "inventory_batches", "inventory_weekly_snapshots")
            .doesNotContain("inventory_locations");
        assertThat(DataPurgeService.patientTablesForPurge())
            .contains(
                "pre_ai_patient_cases", "pre_ai_encounters", "clinic_record_fields", "clinic_record_field_values",
                "clinic_queue_tickets", "tcm_pharmacy_prescriptions"
            );
    }

    @Test
    @SuppressWarnings("unchecked")
    void preflightFailureAfterLockDoesNotLeaveApplicationInMaintenanceMode() {
        var preview = service.preview();
        reset(jdbc, passwordEncoder);
        doReturn(List.of("$2a$10$valid-hash-placeholder"))
            .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
            .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.execute(new DataPurgeExecuteRequest(
            "password", preview.path("token").asText(), DataPurgeService.CONFIRMATION_TEXT
        ))).isInstanceOf(ResponseStatusException.class);

        assertThat(maintenanceState.tryLock()).isTrue();
        maintenanceState.unlock();
    }

    @Test
    void fileQuarantineCanBeRetriedWithoutRepeatingDatabaseWork() throws Exception {
        Path root = Path.of("target", "data-purge-test").toAbsolutePath();
        Path attachment = root.resolve("attachments");
        String runId = "purge-idempotent-" + UUID.randomUUID();
        Files.createDirectories(attachment);
        Files.writeString(attachment.resolve("patient-test.txt"), "test");

        service.quarantineManagedFiles(runId);
        service.quarantineManagedFiles(runId);

        assertThat(attachment).isEmptyDirectory();
        assertThat(root.resolve("quarantine").resolve(runId).resolve("attachments/patient-test.txt")).isRegularFile();
    }
}
