package com.coshare.patientrecord.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import com.coshare.patientrecord.auth.dto.NavigationResult;
import com.coshare.patientrecord.auth.dto.SessionUser;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AuthNavigationServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AuthNavigationService service;

    @BeforeEach
    void setUp() {
        service = new AuthNavigationService(jdbcTemplate);
    }

    @Test
    void doctorCanEditAndCorrectEveryMainClinicalStage() {
        for (String stage : List.of("REGISTRATION", "INSPECTION", "RECEPTION", "TCM", "DOCTOR", "SURGERY")) {
            assertThat(service.canEditStage("doctor", stage)).as(stage).isTrue();
            assertThat(service.canCorrectStage("doctor", stage)).as(stage).isTrue();
        }
        assertThat(service.canEditStage("doctor", "REVIEW")).isTrue();
        assertThat(service.canCorrectStage("doctor", "REVIEW")).isFalse();
    }

    @Test
    void auxiliaryResultsRemainOwnedByTheResponsiblePost() {
        assertThat(service.canEditAuxiliary("lab", "LAB")).isTrue();
        assertThat(service.canEditAuxiliary("doctor", "LAB")).isFalse();
        assertThat(service.canEditAuxiliary("inspection", "COLONOSCOPY")).isTrue();
        assertThat(service.canEditAuxiliary("ultrasound", "IMAGING")).isTrue();
        assertThat(service.canEditAuxiliary("frontdesk", "IMAGING")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void navigationPublishesCapabilitiesFromTheFixedPolicy() {
        doReturn(List.of()).when(jdbcTemplate).query(anyString(), any(RowMapper.class), anyString());
        NavigationResult doctor = service.navigationFor(user("doctor"));
        assertThat(doctor.policyVersion()).isEqualTo(AuthNavigationService.POLICY_VERSION);
        assertThat(doctor.capabilities()).contains(
            "preai:review",
            "preai:duties:manage",
            "preai:stage:registration:edit",
            "preai:stage:inspection:correct",
            "preai:auxiliary:lab:create"
        );
        assertThat(doctor.capabilities()).doesNotContain("preai:encounter:create", "preai:auxiliary:lab:edit");

        NavigationResult quality = service.navigationFor(user("quality"));
        assertThat(quality.capabilities()).doesNotContain("user:create", "preai:review");
        assertThat(quality.buttonPermissions()).doesNotContainKey("accountManage");

        SessionUser manager = user("manager");
        assertThat(service.hasCapability(manager, "inventory:read")).isTrue();
        assertThat(service.hasCapability(manager, "inventory:export")).isTrue();
        assertThat(service.hasCapability(manager, "inventory:issue")).isFalse();
        assertThat(service.hasCapability(manager, "inventory:count")).isFalse();
        assertThat(service.hasCapability(manager, "inventory:receive")).isFalse();

        SessionUser qualityUser = user("quality");
        assertThat(service.hasCapability(qualityUser, "inventory:receive")).isTrue();
    }

    @Test
    void unknownRoleIsDeniedInsteadOfFallingBackToFrontDesk() {
        assertThatThrownBy(() -> service.navigationFor(user("unknown-role")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("角色未配置");
    }

    private SessionUser user(String role) {
        return new SessionUser("account-1", role, role, role, role, "dept-1", "门诊", false, Instant.now().plusSeconds(3600));
    }
}
