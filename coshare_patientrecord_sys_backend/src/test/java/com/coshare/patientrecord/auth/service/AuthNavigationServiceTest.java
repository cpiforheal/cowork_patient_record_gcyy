package com.coshare.patientrecord.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import com.coshare.patientrecord.auth.dto.NavigationMenu;
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
    @SuppressWarnings("unchecked")
    void inventoryNavigationExposesFourEntriesAndKeepsLegacyRoutesHidden() {
        doReturn(List.of()).when(jdbcTemplate).query(anyString(), any(RowMapper.class), anyString());

        NavigationMenu inventory = findMenu(service.navigationFor(user("quality")).menus(), "/inventory");
        assertThat(inventory.children()).isNotNull();
        assertThat(inventory.children().stream().filter(item -> !item.meta().isHide()).map(NavigationMenu::path))
            .containsExactly("/inventory/overview", "/inventory/requests", "/inventory/packages", "/inventory/weekly");

        assertThat(findMenu(inventory.children(), "/inventory/executive").meta().activeMenu()).isEqualTo("/inventory/overview");
        assertThat(findMenu(inventory.children(), "/inventory/stock").meta().activeMenu()).isEqualTo("/inventory/requests");
        assertThat(findMenu(inventory.children(), "/inventory/controls").meta().activeMenu()).isEqualTo("/inventory/requests");
        assertThat(findMenu(inventory.children(), "/inventory/items").meta().activeMenu()).isEqualTo("/inventory/packages");
        assertThat(findMenu(inventory.children(), "/inventory/trace").meta().activeMenu()).isEqualTo("/inventory/weekly");

        NavigationMenu compatibility = findMenu(inventory.children(), "/inventory/manage");
        assertThat(compatibility.meta().isHide()).isTrue();
        assertThat(compatibility.redirect()).isEqualTo("/inventory/overview");
    }

    @Test
    void unknownRoleIsDeniedInsteadOfFallingBackToFrontDesk() {
        assertThatThrownBy(() -> service.navigationFor(user("unknown-role")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("角色未配置");
    }

    private NavigationMenu findMenu(List<NavigationMenu> menus, String path) {
        NavigationMenu result = findMenuOrNull(menus, path);
        if (result != null) return result;
        throw new AssertionError("Menu not found: " + path);
    }

    private NavigationMenu findMenuOrNull(List<NavigationMenu> menus, String path) {
        for (NavigationMenu item : menus) {
            if (path.equals(item.path())) return item;
            if (item.children() != null && !item.children().isEmpty()) {
                NavigationMenu nested = findMenuOrNull(item.children(), path);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private SessionUser user(String role) {
        return new SessionUser("account-1", role, role, role, role, "dept-1", "门诊", false, Instant.now().plusSeconds(3600));
    }
}
