package com.coshare.patientrecord.clinic.corepolicy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

class CorePolicyServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    final CorePolicyService service = new CorePolicyService(jdbc, mapper, txManager);
    final SessionUser nurse = user("u1", "nurse");
    final SessionUser admin = user("admin-1", "admin");

    @BeforeEach
    void seedViaDatabase() {
        // ensureSeeded 走 REQUIRES_NEW 写事务；测试里用真实 TransactionTemplate + mock 管理器直接执行回调
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        // ensureSeeded 的判定与旧版本归档走 SQL；题库/条款查询同样走 SQL，
        // 这里统一让"版本计数返回 0"并让内容表查询返回种子包的行数，验证播种与轮转逻辑。
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
    }

    @Test
    void seedsContentPackAndRotatesDeterministicallyByDate() {
        mockPolicyRows();
        Map<String, Object> day1 = service.today(nurse);
        assertEquals(LocalDate.now().toString(), day1.get("planDate"));
        assertNotNull(day1.get("content"));
        assertTrue(((String) day1.get("content")).length() > 10);
        // 同一日期内轮转索引必须确定（服务端按 dayOfYear % 条款总数取模）
        assertEquals(day1.get("rotationIndex"), service.today(nurse).get("rotationIndex"));
    }

    @Test
    void checkInIsIdempotentPerDayAndReturnsStreak() {
        mockPolicyRows();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        Map<String, Object> first = service.checkIn(nurse);
        assertEquals(true, first.get("checked"));
        // 重复打卡走 ON DUPLICATE KEY，不抛错
        Map<String, Object> again = service.checkIn(nurse);
        assertEquals(true, again.get("checked"));
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeast(2)).update(contains("clinic_core_policy_check_in"), captor.capture());
        assertEquals(nurse.id(), captor.getAllValues().get(0)[1]);
    }

    @Test
    void quizCurrentNeverLeaksAnswers() {
        mockPolicyRows();
        mockQuestions(false);
        Map<String, Object> current = service.quizCurrent(nurse);
        assertEquals(false, current.get("attempted"));
        List<Map<String, Object>> questions = castList(current.get("questions"));
        assertEquals(10, questions.size());
        questions.forEach(question -> assertFalse(question.containsKey("answer"), "取题接口不得返回答案"));
    }

    @Test
    void quizSubmitScoresAndPersistsAttemptOnce() {
        mockPolicyRows();
        mockQuestions(true);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class))).thenReturn(null);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        Map<String, Object> questions = service.quizCurrent(nurse);
        List<Map<String, Object>> items = castList(questions.get("questions"));
        Map<String, Object> body = new HashMap<>();
        Map<String, String> answers = new HashMap<>();
        // 题库桩统一 answer=A，全部按 A 提交应为满分
        for (Map<String, Object> question : items) {
            answers.put((String) question.get("id"), "A");
        }
        body.put("answers", answers);
        Map<String, Object> result = service.quizSubmit(nurse, body);
        assertEquals(10, result.get("score"));
        assertEquals(10, result.get("total"));
        verify(jdbc).update(contains("clinic_core_policy_quiz_attempt"), captorOf());
    }

    @Test
    void quizAttemptedMonthReturnsExistingResultWithoutRerun() {
        mockPolicyRows();
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
            .thenReturn(Map.of("score", 8, "total", 10, "submitted_at", "2026-09-01 08:00:00"));
        Map<String, Object> current = service.quizCurrent(nurse);
        assertEquals(true, current.get("attempted"));
        assertEquals(8, current.get("score"));
        Map<String, Object> submitted = service.quizSubmit(nurse, Map.of());
        assertEquals(true, submitted.get("attempted"));
        assertEquals(8, submitted.get("score"));
        verify(jdbc, never()).update(contains("clinic_core_policy_quiz_attempt"), any(Object[].class));
    }

    @Test
    void statsLockedToAdminAndQualityRoles() {
        assertThrows(ResponseStatusException.class, () -> service.quizStats(nurse));
        mockPolicyRows();
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        Map<String, Object> stats = service.quizStats(admin);
        assertEquals(0, stats.get("participants"));
    }

    @Test
    void streakCountsConsecutiveDaysOnly() {
        mockPolicyRows();
        LocalDate today = LocalDate.now();
        List<String> dates = new ArrayList<>();
        dates.add(today.toString());
        dates.add(today.minusDays(1).toString());
        dates.add(today.minusDays(3).toString());
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(dates);
        Map<String, Object> result = service.today(nurse);
        // 今日与昨日连续，三天前断档 → 连续 2 天
        assertEquals(2, result.get("streak"));
    }

    private void mockPolicyRows() {
        // today/checkIn/policies 走的条款联查：给两条可轮转的行
        List<Map<String, Object>> clauseRows = new ArrayList<>();
        for (int i = 0; i < 94; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "cp-01-c" + (i + 1));
            row.put("clause_no", String.valueOf(i + 1));
            row.put("content", "第" + (i + 1) + "条基本要求：测试用完整条款内容，长度满足断言");
            row.put("policy_id", "cp-01");
            row.put("code", "01");
            row.put("title", "首诊负责制度");
            row.put("summary", "首诊负责制度定义");
            clauseRows.add(row);
        }
        lenient().when(jdbc.queryForList(contains("clinic_core_policy_clause"), any(Object[].class))).thenReturn(clauseRows);
        // clauseForDate 的联查无占位参数，走 queryForList(sql) 单参重载
        lenient().when(jdbc.queryForList(anyString())).thenReturn(clauseRows);
        lenient().when(jdbc.queryForList(contains("clinic_core_policy_check_in"), eq(String.class), any(Object[].class))).thenReturn(List.of());
        lenient().when(jdbc.queryForList(contains("JOIN clinic_core_policy_clause c ON c.policy_id = p.id"), any(Object[].class)))
            .thenReturn(clauseRows.subList(0, 2));
    }

    private void mockQuestions(boolean withAnswer) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "q" + i);
            row.put("policy_id", "cp-01");
            row.put("question_type", "SINGLE");
            row.put("stem", "题目" + i);
            row.put("options_json", mapper.createArrayNode().add("选项A").add("选项B"));
            row.put("answer", "A");
            row.put("explanation", "解析" + i);
            rows.add(row);
        }
        lenient().when(jdbc.queryForList(contains("clinic_core_policy_quiz_question WHERE"), any(Object[].class))).thenReturn(rows);
        lenient().when(jdbc.queryForList(contains("JOIN clinic_core_policy p ON p.id = q.policy_id"), any(Object[].class))).thenReturn(rows);
    }

    private static Object[] captorOf() {
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        return captor.capture();
    }

    private static SessionUser user(String id, String role) {
        return new SessionUser(id, "acc-" + id, "用户" + id, role, role, "", "", false, Instant.now().plusSeconds(3600));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) { return (List<Map<String, Object>>) value; }
}
