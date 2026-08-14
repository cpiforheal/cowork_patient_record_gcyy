package com.coshare.patientrecord.clinicqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClinicQueueServiceTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 15, 9, 0);

    @Test
    void returnsNullWhenNoPatientIsWaiting() {
        assertNull(ClinicQueueService.chooseCandidate(List.of(), 0, NOW));
    }

    @Test
    void manuallyLockedPatientAlwaysWins() {
        ClinicQueueService.Candidate ordinary = candidate("ordinary", "FIRST_VISIT", 12, false);
        ClinicQueueService.Candidate locked = candidate("locked", "FIRST_VISIT", 1, true);

        ClinicQueueService.Candidate selected = ClinicQueueService.chooseCandidate(List.of(ordinary, locked), 0, NOW);

        assertEquals("locked", selected.taskId());
        assertEquals("人工锁定优先", selected.reason());
    }

    @Test
    void firstVisitBeyondMaximumWaitOverridesFollowUpPriority() {
        ClinicQueueService.Candidate firstVisit = candidate("first", "FIRST_VISIT", 31, false);
        ClinicQueueService.Candidate followUp = candidate("follow", "FOLLOW_UP", 2, false);

        ClinicQueueService.Candidate selected = ClinicQueueService.chooseCandidate(List.of(followUp, firstVisit), 0, NOW);

        assertEquals("first", selected.taskId());
        assertEquals("初诊已超过最长等待阈值", selected.reason());
    }

    @Test
    void twoFollowUpsReleaseLongWaitingFirstVisit() {
        ClinicQueueService.Candidate firstVisit = candidate("first", "FIRST_VISIT", 12, false);
        ClinicQueueService.Candidate followUp = candidate("follow", "FOLLOW_UP", 3, false);

        ClinicQueueService.Candidate selected = ClinicQueueService.chooseCandidate(List.of(followUp, firstVisit), 2, NOW);

        assertEquals("first", selected.taskId());
        assertEquals("已连续服务两个复诊，放行久候初诊", selected.reason());
    }

    @Test
    void followUpHasWeightedPriorityBeforeAntiStarvationThreshold() {
        ClinicQueueService.Candidate firstVisit = candidate("first", "FIRST_VISIT", 8, false);
        ClinicQueueService.Candidate followUp = candidate("follow", "FOLLOW_UP", 2, false);

        ClinicQueueService.Candidate selected = ClinicQueueService.chooseCandidate(List.of(firstVisit, followUp), 1, NOW);

        assertEquals("follow", selected.taskId());
        assertEquals("复诊加权优先", selected.reason());
    }

    @Test
    void oldestOrdinaryFirstVisitIsSelectedWhenNoFollowUpExists() {
        ClinicQueueService.Candidate newest = candidate("newest", "FIRST_VISIT", 2, false);
        ClinicQueueService.Candidate oldest = candidate("oldest", "FIRST_VISIT", 9, false);

        ClinicQueueService.Candidate selected = ClinicQueueService.chooseCandidate(List.of(newest, oldest), 0, NOW);

        assertEquals("oldest", selected.taskId());
        assertEquals("按入队时间先后", selected.reason());
    }

    @Test
    void waitingAndExceptionRecoveryKeepOverallStageInSync() {
        assertEquals("WAITING_INSPECTION", ClinicQueueService.overallStatusFor("INSPECTION", "WAITING"));
        assertEquals("WAITING_INSPECTION", ClinicQueueService.overallStatusFor("INSPECTION", "MISSED"));
        assertEquals("WAITING_RECEPTION", ClinicQueueService.overallStatusFor("RECEPTION", "TEMPORARILY_AWAY"));
        assertEquals("RECEPTION_CALLED", ClinicQueueService.overallStatusFor("RECEPTION", "CALLED"));
        assertEquals("ON_HOLD", ClinicQueueService.overallStatusFor("INSPECTION", "INTERRUPTED"));
    }

    @Test
    void supplementaryInspectionCompletionReactivatesHeldReceptionTask() {
        assertEquals(true, ClinicQueueService.shouldActivateReceptionAfterInspection("INACTIVE"));
        assertEquals(true, ClinicQueueService.shouldActivateReceptionAfterInspection("ON_HOLD"));
        assertEquals(false, ClinicQueueService.shouldActivateReceptionAfterInspection("COMPLETED"));
    }

    private ClinicQueueService.Candidate candidate(String id, String visitType, long waitingMinutes, boolean locked) {
        return new ClinicQueueService.Candidate(
            id,
            "ticket-" + id,
            visitType,
            NOW.minusMinutes(waitingMinutes),
            locked,
            ""
        );
    }
}
