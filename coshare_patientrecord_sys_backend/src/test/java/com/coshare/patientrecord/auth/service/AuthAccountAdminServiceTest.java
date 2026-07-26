package com.coshare.patientrecord.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.coshare.patientrecord.auth.dto.AccountUpsertRequest;
import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AuthAccountAdminServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthSessionService authSessionService;

    private AuthAccountAdminService service;

    @BeforeEach
    void setUp() {
        service = new AuthAccountAdminService(jdbcTemplate, new ObjectMapper(), passwordEncoder, authSessionService);
    }

    @Test
    void newAccountsMustUseCanonicalRolesInsteadOfLegacyAliases() {
        AccountUpsertRequest request = new AccountUpsertRequest(
            "nurse-01",
            "护理人员",
            "nursing",
            "启用",
            "password-123",
            List.of("dept-1"),
            "dept-1",
            ""
        );

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("规范岗位");
    }

    @Test
    void directoryCannotQueryOutsideTheActiveDepartment() {
        SessionUser user = new SessionUser(
            "account-1", "doctor-1", "医生", "doctor", "医生岗位",
            "dept-1", "门诊科", false, Instant.now().plusSeconds(3600)
        );

        assertThatThrownBy(() -> service.directoryAccounts(user, "dept-2"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("当前活动科室");
    }

    @Test
    void accountRequiresAtLeastOneAuthorizedDepartment() {
        AccountUpsertRequest request = new AccountUpsertRequest(
            "doctor-01",
            "医生人员",
            "doctor",
            "启用",
            "password-123",
            List.of(),
            "",
            ""
        );

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("至少一个科室");
    }

    @Test
    @SuppressWarnings("unchecked")
    void currentAdministratorCannotDisableOrDemoteItself() {
        ObjectNode existing = new ObjectMapper().createObjectNode();
        existing.put("username", "admin");
        existing.put("role", "admin");
        existing.put("status", "启用");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("admin-1"))).thenReturn(List.of(existing));
        AccountUpsertRequest request = new AccountUpsertRequest(
            "admin", "管理员", "manager", "启用", null, List.of("dept-1"), "dept-1", ""
        );

        assertThatThrownBy(() -> service.update("admin-1", request, "admin-1"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("当前管理员");
    }

    @Test
    @SuppressWarnings("unchecked")
    void lastEnabledAdministratorCannotBeDeleted() {
        ObjectNode existing = new ObjectMapper().createObjectNode();
        existing.put("username", "admin");
        existing.put("role", "admin");
        existing.put("status", "启用");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("admin-1"))).thenReturn(List.of(existing));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("admin-1"))).thenReturn(0);

        assertThatThrownBy(() -> service.delete("admin-1", "admin-2"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("至少保留一个");
    }
}
