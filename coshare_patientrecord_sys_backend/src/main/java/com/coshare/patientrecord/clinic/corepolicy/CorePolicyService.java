package com.coshare.patientrecord.clinic.corepolicy;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 医疗质量安全核心制度学习（一期）：每日一条 + 打卡 + 每月小测。
 * 内容包在 resources/content/core-policy-seed.json，版本化懒播种（仿病种模板库模式）；
 * "每日一条"按日期对条款总数取模确定性轮转，不引入定时任务；
 * 每月小测按 用户+月份 确定性抽题（CRC32 排序），保证取题与判分看到同一份卷子。
 */
@Service
@Profile("mysql")
@Transactional(readOnly = true)
public class CorePolicyService {
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    static final int QUIZ_SIZE = 10;
    private static final Set<String> STATS_ROLES = Set.of("admin", "quality");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate writeTx;
    private final Object seedLock = new Object();
    private volatile boolean seeded;

    public CorePolicyService(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        // 播种必须独立于调用方的只读事务执行
        this.writeTx = new TransactionTemplate(transactionManager);
        this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Map<String, Object> today(SessionUser user) {
        ensureSeeded();
        LocalDate today = LocalDate.now();
        Map<String, Object> clause = clauseForDate(today);
        Map<String, Object> result = new LinkedHashMap<>(clause);
        List<String> checked = jdbc.queryForList(
            "SELECT checked_at FROM clinic_core_policy_check_in WHERE user_id = ? AND plan_date = ?",
            String.class, user.id(), today.format(DATE));
        boolean isChecked = !checked.isEmpty();
        result.put("checked", isChecked);
        result.put("checkedAt", isChecked ? checked.get(0) : "");
        result.put("streak", streak(user.id(), today, isChecked));
        return result;
    }

    @Transactional
    public Map<String, Object> checkIn(SessionUser user) {
        ensureSeeded();
        LocalDate today = LocalDate.now();
        Map<String, Object> clause = clauseForDate(today);
        jdbc.update(
            "INSERT INTO clinic_core_policy_check_in (id, user_id, user_name, policy_id, plan_date, checked_at) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE id = id",
            uuid(), user.id(), user.name(), string(clause.get("policyId")), today.format(DATE), now());
        Map<String, Object> result = new LinkedHashMap<>(clause);
        result.put("checked", true);
        result.put("checkedAt", now());
        result.put("streak", streak(user.id(), today, true));
        return result;
    }

    public List<Map<String, Object>> policies(SessionUser user) {
        ensureSeeded();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT p.id, p.code, p.title, p.summary, c.id AS clause_id, c.clause_no, c.content "
                + "FROM clinic_core_policy p LEFT JOIN clinic_core_policy_clause c ON c.policy_id = p.id AND c.status = 'ACTIVE' "
                + "WHERE p.status = 'ACTIVE' ORDER BY p.sort_order, c.sort_order");
        Map<String, Map<String, Object>> tree = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> policy = tree.computeIfAbsent(string(row.get("id")), id -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", id);
                value.put("code", row.get("code"));
                value.put("title", row.get("title"));
                value.put("summary", row.get("summary"));
                value.put("clauses", new ArrayList<Map<String, Object>>());
                return value;
            });
            if (row.get("clause_id") != null) {
                Map<String, Object> clause = new LinkedHashMap<>();
                clause.put("id", row.get("clause_id"));
                clause.put("clauseNo", row.get("clause_no"));
                clause.put("content", row.get("content"));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> clauses = (List<Map<String, Object>>) policy.get("clauses");
                clauses.add(clause);
            }
        }
        return new ArrayList<>(tree.values());
    }

    public Map<String, Object> quizCurrent(SessionUser user) {
        ensureSeeded();
        String month = quizMonth();
        Map<String, Object> attempt = attemptFor(user.id(), month);
        if (attempt != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("attempted", true);
            result.put("score", attempt.get("score"));
            result.put("total", attempt.get("total"));
            result.put("submittedAt", attempt.get("submitted_at"));
            return result;
        }
        return Map.of("attempted", false, "quizMonth", month, "questions", selectedQuestions(user.id(), month, false));
    }

    @Transactional
    public Map<String, Object> quizSubmit(SessionUser user, Map<String, Object> body) {
        ensureSeeded();
        String month = quizMonth();
        Map<String, Object> previous = attemptFor(user.id(), month);
        if (previous != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("attempted", true);
            result.put("score", previous.get("score"));
            result.put("total", previous.get("total"));
            result.put("submittedAt", previous.get("submitted_at"));
            return result;
        }
        Map<String, String> submitted = new HashMap<>();
        if (body != null && body.get("answers") instanceof Map<?, ?> answers) {
            answers.forEach((key, value) -> submitted.put(String.valueOf(key), string(value).toUpperCase()));
        }
        List<Map<String, Object>> questions = selectedQuestions(user.id(), month, true);
        List<Map<String, Object>> review = new ArrayList<>();
        int score = 0;
        for (Map<String, Object> question : questions) {
            String chosen = submitted.getOrDefault(string(question.get("id")), "");
            String answer = string(question.get("answer")).toUpperCase();
            boolean correct = chosen.equals(answer);
            if (correct) score++;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("questionId", question.get("id"));
            item.put("stem", question.get("stem"));
            item.put("chosen", chosen);
            item.put("answer", answer);
            item.put("correct", correct);
            item.put("explanation", question.get("explanation"));
            review.add(item);
        }
        jdbc.update(
            "INSERT INTO clinic_core_policy_quiz_attempt (id, user_id, user_name, quiz_month, question_ids_json, answers_json, score, total, submitted_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE id = id",
            uuid(), user.id(), user.name(), month, toJson(questions.stream().map(q -> string(q.get("id"))).toList()),
            toJson(submitted), score, questions.size(), now());
        return Map.of("attempted", false, "quizMonth", month, "score", score, "total", questions.size(), "review", review);
    }

    public Map<String, Object> quizStats(SessionUser user) {
        requireStatsRole(user);
        ensureSeeded();
        String month = quizMonth();
        List<Map<String, Object>> attempts = jdbc.queryForList(
            "SELECT user_id, question_ids_json, answers_json, score, total FROM clinic_core_policy_quiz_attempt WHERE quiz_month = ?",
            month);
        Map<String, Map<String, Object>> questions = new HashMap<>();
        jdbc.queryForList(
            "SELECT q.id, q.answer, q.policy_id, p.code, p.title FROM clinic_core_policy_quiz_question q "
                + "JOIN clinic_core_policy p ON p.id = q.policy_id WHERE q.status = 'ACTIVE'")
            .forEach(row -> questions.put(string(row.get("id")), row));
        int totalScore = 0, totalCount = 0, perfect = 0;
        Map<String, int[]> perPolicy = new HashMap<>();
        for (Map<String, Object> attempt : attempts) {
            int score = attempt.get("score") instanceof Number number ? number.intValue() : 0;
            int total = attempt.get("total") instanceof Number number ? number.intValue() : 0;
            totalScore += score;
            totalCount += total;
            if (total > 0 && score == total) perfect++;
            if (!(attempt.get("answers_json") instanceof JsonNode answers)) continue;
            answers.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                Map<String, Object> question = questions.get(name);
                if (question == null) return;
                int[] counter = perPolicy.computeIfAbsent(string(question.get("policy_id")), key -> new int[2]);
                counter[0]++;
                if (string(answers.get(name)).equalsIgnoreCase(string(question.get("answer")))) counter[1]++;
            });
        }
        List<Map<String, Object>> policyStats = new ArrayList<>();
        perPolicy.entrySet().stream()
            .sorted((left, right) -> {
                double leftRate = left.getValue()[0] == 0 ? 0 : (double) left.getValue()[1] / left.getValue()[0];
                double rightRate = right.getValue()[0] == 0 ? 0 : (double) right.getValue()[1] / right.getValue()[0];
                return Double.compare(leftRate, rightRate);
            })
            .forEach(entry -> {
                Map<String, Object> row = new LinkedHashMap<>();
                Map<String, Object> question = questions.values().stream()
                    .filter(item -> string(item.get("policy_id")).equals(entry.getKey())).findFirst().orElse(Map.of());
                row.put("policyId", entry.getKey());
                row.put("code", question.get("code"));
                row.put("title", question.get("title"));
                row.put("asked", entry.getValue()[0]);
                row.put("correct", entry.getValue()[1]);
                row.put("rate", entry.getValue()[0] == 0 ? 0
                    : Math.round(entry.getValue()[1] * 1000d / entry.getValue()[0]) / 10d);
                policyStats.add(row);
            });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", month);
        result.put("participants", attempts.size());
        result.put("avgScorePct", totalCount == 0 ? 0 : Math.round(totalScore * 1000d / totalCount) / 10d);
        result.put("perfectCount", perfect);
        result.put("policies", policyStats);
        return result;
    }

    /** 当日条款：按 dayOfYear 对有效条款总数取模确定性轮转。 */
    private Map<String, Object> clauseForDate(LocalDate date) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT c.id, c.clause_no, c.content, p.id AS policy_id, p.code, p.title, p.summary "
                + "FROM clinic_core_policy_clause c JOIN clinic_core_policy p ON p.id = c.policy_id "
                + "WHERE c.status = 'ACTIVE' AND p.status = 'ACTIVE' ORDER BY p.sort_order, c.sort_order");
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "制度内容尚未初始化");
        int index = Math.floorMod(date.getDayOfYear() - 1, rows.size());
        Map<String, Object> row = rows.get(index);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planDate", date.format(DATE));
        result.put("policyId", row.get("policy_id"));
        result.put("code", row.get("code"));
        result.put("title", row.get("title"));
        result.put("summary", row.get("summary"));
        result.put("clauseId", row.get("id"));
        result.put("clauseNo", row.get("clause_no"));
        result.put("content", row.get("content"));
        result.put("totalClauses", rows.size());
        result.put("rotationIndex", index);
        return result;
    }

    private int streak(String userId, LocalDate today, boolean checkedToday) {
        List<String> dates = jdbc.queryForList(
            "SELECT plan_date FROM clinic_core_policy_check_in WHERE user_id = ? AND plan_date <= ? "
                + "ORDER BY plan_date DESC LIMIT 400",
            String.class, userId, today.format(DATE));
        LocalDate cursor = checkedToday ? today : today.minusDays(1);
        int streak = 0;
        for (String value : dates) {
            LocalDate date = parseDate(value);
            if (date == null) continue;
            if (date.equals(cursor)) {
                streak++;
                cursor = cursor.minusDays(1);
            } else if (date.isBefore(cursor)) {
                break;
            }
        }
        return streak;
    }

    private Map<String, Object> attemptFor(String userId, String month) {
        return jdbc.query(
            "SELECT score, total, submitted_at FROM clinic_core_policy_quiz_attempt WHERE user_id = ? AND quiz_month = ?",
            rs -> rs.next()
                ? Map.of("score", rs.getInt(1), "total", rs.getInt(2), "submitted_at", rs.getString(3))
                : null,
            userId, month);
    }

    /** 用户+月份确定性抽题：月内多次取题为同一份，判分以同一规则重算。includeAnswer 仅供服务端判分使用。 */
    private List<Map<String, Object>> selectedQuestions(String userId, String month, boolean includeAnswer) {
        String seed = userId + ":" + month;
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, policy_id, question_type, stem, options_json, answer, explanation "
                + "FROM clinic_core_policy_quiz_question WHERE status = 'ACTIVE' "
                + "ORDER BY CRC32(CONCAT(id, ?)) LIMIT " + QUIZ_SIZE, seed);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> question = new LinkedHashMap<>();
            question.put("id", row.get("id"));
            question.put("type", row.get("question_type"));
            question.put("stem", row.get("stem"));
            question.put("options", parseOptions(row.get("options_json")));
            question.put("explanation", row.get("explanation"));
            if (includeAnswer) question.put("answer", row.get("answer"));
            result.add(question);
        }
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "题库尚未初始化");
        return result;
    }

    void ensureSeeded() {
        if (seeded) return;
        synchronized (seedLock) {
            if (seeded) return;
            // 类级 readOnly=true 会传染到这里；播种用独立读写事务，避免"Connection is read-only"
            writeTx.executeWithoutResult(status -> seed());
            seeded = true;
        }
    }

    private void seed() {
        JsonNode root;
        try {
            root = mapper.readTree(new ClassPathResource("content/core-policy-seed.json").getInputStream());
        } catch (Exception error) {
            throw new IllegalStateException("核心制度内容包读取失败", error);
        }
        String version = root.path("version").asText("v1-2018");
        int current = jdbc.queryForObject(
            "SELECT COUNT(*) FROM clinic_core_policy WHERE content_version = ?", Integer.class, version);
        if (current > 0) return;
        // 旧版本内容归档（当前仅 v1，正常首轮为空表）
        jdbc.update("UPDATE clinic_core_policy SET status = 'ARCHIVED' WHERE content_version <> ?", version);
        jdbc.update(
            "UPDATE clinic_core_policy_clause c JOIN clinic_core_policy p ON p.id = c.policy_id "
                + "SET c.status = 'ARCHIVED' WHERE p.content_version <> ?", version);
        jdbc.update(
            "UPDATE clinic_core_policy_quiz_question q JOIN clinic_core_policy p ON p.id = q.policy_id "
                + "SET q.status = 'ARCHIVED' WHERE p.content_version <> ?", version);
        String now = now();
        for (JsonNode policy : root.path("policies")) {
            String policyId = "cp-" + policy.path("code").asText();
            jdbc.update(
                "INSERT INTO clinic_core_policy (id, code, title, summary, content_version, sort_order, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?) AS seed "
                    + "ON DUPLICATE KEY UPDATE title = seed.title, summary = seed.summary, content_version = seed.content_version, "
                    + "sort_order = seed.sort_order, status = 'ACTIVE', updated_at = seed.updated_at",
                policyId, policy.path("code").asText(), policy.path("title").asText(),
                policy.path("summary").asText(), version, policy.path("code").asInt(99), now, now);
            int clauseOrder = 0;
            for (JsonNode clause : policy.path("clauses")) {
                clauseOrder++;
                jdbc.update(
                    "INSERT INTO clinic_core_policy_clause (id, policy_id, clause_no, content, sort_order, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?) AS seed "
                        + "ON DUPLICATE KEY UPDATE content = seed.content, sort_order = seed.sort_order, status = 'ACTIVE'",
                    policyId + "-c" + clauseOrder, policyId, String.valueOf(clauseOrder),
                    clause.asText(), clauseOrder, now);
            }
            int questionOrder = 0;
            for (JsonNode question : policy.path("questions")) {
                questionOrder++;
                jdbc.update(
                    "INSERT INTO clinic_core_policy_quiz_question (id, policy_id, clause_id, question_type, stem, options_json, answer, explanation, generated_by, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'hand', 'ACTIVE', ?) AS seed "
                        + "ON DUPLICATE KEY UPDATE stem = seed.stem, options_json = seed.options_json, answer = seed.answer, "
                        + "explanation = seed.explanation, status = 'ACTIVE'",
                    policyId + "-q" + questionOrder, policyId, "", question.path("type").asText("SINGLE"),
                    question.path("stem").asText(), toJson(question.path("options")), question.path("answer").asText(),
                    question.path("explanation").asText(), now);
            }
        }
    }

    private List<String> parseOptions(Object value) {
        try {
            if (value instanceof JsonNode node) {
                List<String> options = new ArrayList<>();
                node.forEach(item -> options.add(item.asText()));
                return options;
            }
            JsonNode node = mapper.readTree(string(value));
            List<String> options = new ArrayList<>();
            node.forEach(item -> options.add(item.asText()));
            return options;
        } catch (Exception error) {
            return List.of();
        }
    }

    private void requireStatsRole(SessionUser user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        if (!STATS_ROLES.contains(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理与质控角色可查看学习统计");
        }
    }

    static String quizMonth() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    private static String string(Object value) { return value == null ? "" : value.toString().trim(); }
    private static String now() { return java.time.LocalDateTime.now().format(TS); }
    private static String uuid() { return UUID.randomUUID().toString(); }
    private static LocalDate parseDate(String value) {
        try { return LocalDate.parse(value); } catch (RuntimeException ignored) { return null; }
    }
    private String toJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "序列化失败", error); }
    }
}
