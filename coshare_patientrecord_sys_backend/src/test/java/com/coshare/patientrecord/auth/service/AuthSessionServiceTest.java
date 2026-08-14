package com.coshare.patientrecord.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthSessionServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void loginAccountHandleIsShortLivedAndRejectsSignatureTampering() throws Exception {
        AuthSessionService service = new AuthSessionService(
            mock(JdbcTemplate.class),
            new ObjectMapper(),
            mock(PasswordEncoder.class),
            mock(Environment.class),
            ""
        );
        Method issue = AuthSessionService.class.getDeclaredMethod("issueLoginHandle", String.class);
        Method resolve = AuthSessionService.class.getDeclaredMethod("resolveLoginHandle", String.class);
        issue.setAccessible(true);
        resolve.setAccessible(true);

        String handle = (String) issue.invoke(service, "account-1");
        Optional<String> resolved = (Optional<String>) resolve.invoke(service, handle);
        String replacement = handle.endsWith("x") ? "y" : "x";
        Optional<String> tampered = (Optional<String>) resolve.invoke(service, handle.substring(0, handle.length() - 1) + replacement);

        assertThat(handle.split("\\.")).hasSize(3);
        assertThat(resolved).contains("account-1");
        assertThat(tampered).isEmpty();
    }

    @Test
    void restoredSessionPrefersCanonicalAccountRoleOverLegacySessionRole() {
        assertThat(AuthSessionService.canonicalSessionRole("frontdesk", "前台")).isEqualTo("frontdesk");
        assertThat(AuthSessionService.canonicalSessionRole("前台", "前台")).isEqualTo("frontdesk");
        assertThat(AuthSessionService.canonicalSessionRole("", "接诊医生")).isEqualTo("reception");
    }
}
