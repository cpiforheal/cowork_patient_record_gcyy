package com.coshare.patientrecord.clinic.recheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.clinic.recheck.RecheckScheduleService.Entry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RecheckScheduleServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    @Test
    @SuppressWarnings("unchecked")
    void boardHasOneRowPerDayAndCountsPlannedArrivedAbsent() {
        List<Entry> entries = List.of(
            entry("a", "2026-09-30", "PLANNED"),
            entry("b", "2026-09-30", "ARRIVED"),
            entry("c", "2026-09-30", "ABSENT"),
            entry("d", "2026-09-30", "RESCHEDULED"),
            entry("e", "2026-10-02", "PLANNED")
        );
        Map<String, Object> board = RecheckScheduleService.buildBoard(TODAY.minusDays(1), TODAY.plusDays(2), TODAY, entries);

        List<Map<String, Object>> days = (List<Map<String, Object>>) board.get("days");
        assertThat(days).extracting(day -> day.get("date"))
            .containsExactly("2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03");
        assertThat(days).extracting(day -> day.get("relation")).containsExactly("past", "today", "future", "future");

        Map<String, Object> past = (Map<String, Object>) days.get(0).get("stats");
        assertThat(past.get("planned")).isEqualTo(3L);
        assertThat(past.get("arrived")).isEqualTo(1L);
        assertThat(past.get("absent")).isEqualTo(1L);
        assertThat(past.get("rescheduled")).isEqualTo(1L);
        assertThat(past.get("unconfirmed")).isEqualTo(1L);
        assertThat(past.get("arrivalRate")).isEqualTo(50.0);

        Map<String, Object> future = (Map<String, Object>) days.get(2).get("stats");
        assertThat(future.get("planned")).isEqualTo(1L);
        assertThat(future.get("unconfirmed")).isEqualTo(0L);
        assertThat(future.get("arrivalRate")).isNull();

        assertThat(((Map<String, Object>) board.get("total")).get("planned")).isEqualTo(4L);
        assertThat(board.get("unconfirmedPast")).isEqualTo(1L);
    }

    @Test
    void transitionsGuardFutureDatesAndClosedEntries() {
        RecheckScheduleService.checkTransition("PLANNED", "ARRIVED", TODAY, TODAY);
        RecheckScheduleService.checkTransition("ARRIVED", "ABSENT", TODAY.minusDays(3), TODAY);
        RecheckScheduleService.checkTransition("ABSENT", "PLANNED", TODAY, TODAY);

        assertThatThrownBy(() -> RecheckScheduleService.checkTransition("PLANNED", "ARRIVED", TODAY.plusDays(1), TODAY))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("提前");
        assertThatThrownBy(() -> RecheckScheduleService.checkTransition("RESCHEDULED", "ARRIVED", TODAY, TODAY))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> RecheckScheduleService.checkTransition("PLANNED", "PLANNED", TODAY, TODAY))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> RecheckScheduleService.checkTransition("PLANNED", "CANCELLED", TODAY, TODAY))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void inputNormalizationAndRoles() {
        assertThat(RecheckScheduleService.requireName("  张  三 ")).isEqualTo("张 三");
        assertThat(RecheckScheduleService.normalizePhone("138 0000-1111")).isEqualTo("1380000-1111");
        assertThat(RecheckScheduleService.normalizePhone("")).isEmpty();
        assertThatThrownBy(() -> RecheckScheduleService.requireName("  ")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> RecheckScheduleService.parseDate("2026/10/01", "复查日期"))
            .isInstanceOf(ResponseStatusException.class);

        assertThat(RecheckScheduleService.canEdit(user("inspection"))).isTrue();
        assertThat(RecheckScheduleService.canEdit(user("nurse"))).isTrue();
        assertThat(RecheckScheduleService.canEdit(user("frontdesk"))).isFalse();
        assertThat(RecheckScheduleService.canEdit(null)).isFalse();
    }

    private static Entry entry(String id, String date, String status) {
        return new Entry(id, date, "患者" + id, "", "", status, "", null, null, "u", "", "u", "");
    }

    private static SessionUser user(String role) {
        return new SessionUser("id", role, role, role, role, "dept", "门诊", false, Instant.now().plusSeconds(60));
    }
}
