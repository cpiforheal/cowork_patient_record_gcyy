package com.coshare.patientrecord.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import com.coshare.patientrecord.auth.dto.NavigationMenu;
import com.coshare.patientrecord.auth.dto.NavigationResult;
import com.coshare.patientrecord.auth.dto.SessionUser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 展示终端（候诊大屏）专用账号：只读两块大屏，长效滑动会话。
 */
@ExtendWith(MockitoExtension.class)
class DisplayTerminalAccessTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AuthNavigationService service;

    @BeforeEach
    void setUp() {
        service = new AuthNavigationService(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void displayRoleOnlyReachesTheTwoDisplayScreens() {
        doReturn(List.of()).when(jdbcTemplate).query(anyString(), any(RowMapper.class), anyString());
        NavigationResult navigation = service.navigationFor(user("display"));

        assertThat(findMenuOrNull(navigation.menus(), "/tcm-pharmacy/clinic-queue/display")).isNotNull();
        assertThat(findMenuOrNull(navigation.menus(), "/tcm-pharmacy/display")).isNotNull();

        for (String forbidden : List.of(
            "/welcome/index",
            "/home/index",
            "/patients/list",
            "/pre-ai/encounters",
            "/tcm-pharmacy/workbench",
            "/tcm-pharmacy/clinic-queue/workbench",
            "/inventory"
        )) {
            assertThat(findMenuOrNull(navigation.menus(), forbidden)).as(forbidden).isNull();
        }

        assertThat(navigation.buttonPermissions())
            .containsKeys("clinicQueueDisplayMenu", "tcmPharmacyDisplayMenu")
            .doesNotContainKeys("clinicQueueWorkbench", "tcmPharmacyWorkbench", "accountManage");
        assertThat(navigation.shortcuts()).isEmpty();
    }

    @Test
    void displaySessionsUseLongTtlWhileStaffSessionsKeepTwelveHours() {
        assertThat(AuthSessionService.tokenTtlForRole("display")).isEqualTo(Duration.ofDays(30));
        assertThat(AuthSessionService.tokenTtlForRole(" Display ")).isEqualTo(Duration.ofDays(30));
        assertThat(AuthSessionService.tokenTtlForRole("doctor")).isEqualTo(Duration.ofHours(12));
        assertThat(AuthSessionService.tokenTtlForRole("admin")).isEqualTo(Duration.ofHours(12));
        assertThat(AuthSessionService.tokenTtlForRole(null)).isEqualTo(Duration.ofHours(12));
        assertThat(AuthSessionService.isDisplayRole("display")).isTrue();
        assertThat(AuthSessionService.isDisplayRole("frontdesk")).isFalse();
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
