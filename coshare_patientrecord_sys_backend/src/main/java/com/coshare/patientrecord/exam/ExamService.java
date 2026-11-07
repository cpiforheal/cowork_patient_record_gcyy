package com.coshare.patientrecord.exam;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class ExamService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<Character> OPTION_LETTERS = Set.of('A', 'B', 'C', 'D');

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ExamService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ---------- public ----------

    @Transactional(readOnly = true)
    public Map<String, Object> paperView() {
        PaperRow paper = loadPaper();
        if (paper == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "试卷不存在");
        ObjectNode result = objectMapper.createObjectNode();
        result.set("paper", paperJson(paper));
        ArrayNode departments = result.putArray("departments");
        for (String department : departments()) departments.add(department);
        ArrayNode questions = result.putArray("questions");
        for (QuestionRow question : loadQuestions(paper.id())) {
            ObjectNode row = questions.addObject();
            row.put("id", question.id());
            row.put("seq", question.seq());
            row.put("stem", question.stem());
            ArrayNode options = row.putArray("options");
            for (String option : question.options()) options.add(option);
            row.put("score", question.score());
        }
        return toMap(result);
    }

    @Transactional
    public Map<String, Object> start(JsonNode payload) {
        PaperRow paper = loadPaper();
        if (paper == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "试卷不存在");
        String name = normalizeName(text(payload, "name"));
        String department = normalizeDepartment(text(payload, "department"));
        validateIdentity(name, department, paper);

        String activeKey = activeKey(paper.id(), name, department);
        AttemptRow existing = loadAttemptByActiveKey(activeKey);
        if (existing != null) {
            return attemptResponse(existing, paper);
        }
        if (!"OPEN".equals(paper.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "考试尚未开放，请联系管理员");
        }

        String id = "exam-attempt-" + UUID.randomUUID();
        String token = newToken();
        String reattemptOf = latestVoidAttempt(paper.id(), name, department);
        jdbcTemplate.update(
            """
            INSERT INTO clinic_exam_attempt
              (id, paper_id, name, name_key, department, active_key, status, attempt_token,
               answers_json, score, correct_count, reattempt_of)
            VALUES (?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?, CAST('{}' AS JSON), 0, 0, ?)
            """,
            id, paper.id(), name, nameKey(name), department, activeKey, token, reattemptOf
        );
        AttemptRow created = loadAttempt(id);
        return attemptResponse(created, paper);
    }

    @Transactional
    public Map<String, Object> saveDraft(String attemptId, JsonNode payload) {
        AttemptRow attempt = lockAttempt(attemptId);
        requireToken(attempt, text(payload, "token"));
        if ("SUBMITTED".equals(attempt.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "答卷已提交，不能再修改");
        }
        if ("VOID".equals(attempt.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "答卷已作废，可重新答题");
        }
        PaperRow paper = loadPaper();
        Map<String, String> answers = normalizeAnswers(payload.path("answers"), loadQuestions(paper.id()));
        jdbcTemplate.update(
            "UPDATE clinic_exam_attempt SET answers_json = CAST(? AS JSON), last_saved_at = CURRENT_TIMESTAMP(3) WHERE id = ?",
            json(answers), attemptId
        );
        ObjectNode result = objectMapper.createObjectNode();
        result.put("savedAt", LocalDateTime.now().format(DATE_TIME));
        return toMap(result);
    }

    @Transactional
    public Map<String, Object> submit(String attemptId, JsonNode payload) {
        AttemptRow attempt = lockAttempt(attemptId);
        requireToken(attempt, text(payload, "token"));
        PaperRow paper = loadPaper();
        if ("SUBMITTED".equals(attempt.status())) {
            return attemptResponse(attempt, paper);
        }
        if ("VOID".equals(attempt.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "答卷已作废，可重新答题");
        }
        List<QuestionRow> questions = loadQuestions(paper.id());
        Map<String, String> answers = normalizeAnswers(payload.path("answers"), questions);
        List<Integer> missing = new ArrayList<>();
        for (QuestionRow question : questions) {
            if (!answers.containsKey(question.id())) missing.add(question.seq());
        }
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "还有 " + missing.size() + " 题未作答，请补完后再交卷（第一题：第 " + missing.get(0) + " 题）");
        }

        int correctCount = 0;
        for (QuestionRow question : questions) {
            if (answerMatches(answers.get(question.id()), question.answer())) correctCount++;
        }
        BigDecimal score = paper.pointsPerQuestion()
            .multiply(BigDecimal.valueOf(correctCount))
            .setScale(1, RoundingMode.HALF_UP);
        LocalDateTime submittedAt = LocalDateTime.now();
        int seconds = (int) Math.max(0, Duration.between(attempt.startedAt(), submittedAt).getSeconds());
        jdbcTemplate.update(
            """
            UPDATE clinic_exam_attempt
            SET status = 'SUBMITTED', answers_json = CAST(? AS JSON), score = ?, correct_count = ?,
                submitted_at = ?, duration_seconds = ?, last_saved_at = ?
            WHERE id = ?
            """,
            json(answers), score, correctCount, Timestamp.valueOf(submittedAt), seconds,
            Timestamp.valueOf(submittedAt), attemptId
        );
        return attemptResponse(loadAttempt(attemptId), paper);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> attempt(String attemptId, String token) {
        AttemptRow attempt = requireAttempt(attemptId);
        requireToken(attempt, token == null ? "" : token);
        if ("VOID".equals(attempt.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "答卷已作废，可重新答题");
        }
        return attemptResponse(attempt, loadPaper());
    }

    // ---------- admin ----------

    @Transactional(readOnly = true)
    public Map<String, Object> adminPaper(SessionUser user) {
        requireAdmin(user);
        PaperRow paper = loadPaper();
        ObjectNode result = objectMapper.createObjectNode();
        result.set("paper", paperJson(paper));
        ObjectNode stats = result.putObject("stats");
        stats.put("total", countAttempts(paper.id(), null));
        stats.put("inProgress", countAttempts(paper.id(), "IN_PROGRESS"));
        stats.put("submitted", countAttempts(paper.id(), "SUBMITTED"));
        stats.put("void", countAttempts(paper.id(), "VOID"));
        BigDecimal average = jdbcTemplate.queryForObject(
            "SELECT COALESCE(AVG(score), 0) FROM clinic_exam_attempt WHERE paper_id = ? AND status = 'SUBMITTED'",
            BigDecimal.class, paper.id()
        );
        stats.put("averageScore", average == null ? BigDecimal.ZERO : average.setScale(1, RoundingMode.HALF_UP));
        return toMap(result);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> adminQuestions(SessionUser user) {
        requireAdmin(user);
        PaperRow paper = loadPaper();
        ObjectNode result = objectMapper.createObjectNode();
        result.set("paper", paperJson(paper));
        ArrayNode questions = result.putArray("questions");
        for (QuestionRow question : loadQuestions(paper.id())) {
            ObjectNode row = questions.addObject();
            row.put("id", question.id());
            row.put("seq", question.seq());
            row.put("stem", question.stem());
            ArrayNode options = row.putArray("options");
            for (String option : question.options()) options.add(option);
            ArrayNode answer = row.putArray("answer");
            for (String letter : question.answer()) answer.add(letter);
            row.put("explanation", question.explanation());
            row.put("score", question.score());
        }
        return toMap(result);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> roster(SessionUser user) {
        requireAdmin(user);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode list = result.putArray("list");
        for (RosterRow row : loadRoster()) {
            ObjectNode item = list.addObject();
            item.put("id", row.id());
            item.put("name", row.name());
            item.put("department", row.department());
            item.put("source", row.source());
        }
        ArrayNode departments = result.putArray("departments");
        for (String department : departments()) departments.add(department);
        return toMap(result);
    }

    @Transactional
    public Map<String, Object> addRoster(JsonNode payload, SessionUser user) {
        requireAdmin(user);
        String name = normalizeName(text(payload, "name"));
        String department = normalizeDepartment(text(payload, "department"));
        if (name.length() < 2 || department.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写姓名和科室");
        }
        jdbcTemplate.update(
            """
            INSERT INTO clinic_exam_roster (id, name, name_key, department, source)
            VALUES (?, ?, ?, ?, 'MANUAL')
            ON DUPLICATE KEY UPDATE name = VALUES(name), source = 'MANUAL'
            """,
            "exam-roster-" + UUID.randomUUID(), name, nameKey(name), department
        );
        return roster(user);
    }

    @Transactional
    public Map<String, Object> deleteRoster(String id, SessionUser user) {
        requireAdmin(user);
        jdbcTemplate.update("DELETE FROM clinic_exam_roster WHERE id = ?", id);
        return roster(user);
    }

    @Transactional
    public Map<String, Object> importRoster(MultipartFile file, SessionUser user) {
        requireAdmin(user);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传花名册文件");
        }
        List<ImportRow> rows;
        try {
            rows = readRosterFile(file);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "花名册解析失败：" + error.getMessage());
        }
        int inserted = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        for (ImportRow row : rows) {
            String name = normalizeName(row.name());
            String department = normalizeDepartment(row.department());
            if (name.length() < 2 || department.isBlank()) {
                errors.add("第 " + row.rowNo() + " 行姓名或科室为空");
                continue;
            }
            int changed = jdbcTemplate.update(
                """
                INSERT IGNORE INTO clinic_exam_roster (id, name, name_key, department, source)
                VALUES (?, ?, ?, ?, 'EXCEL')
                """,
                "exam-roster-" + UUID.randomUUID(), name, nameKey(name), department
            );
            if (changed > 0) inserted++;
            else skipped++;
        }
        audit(user, "ROSTER_IMPORT", "导入花名册：" + inserted + " 条，跳过 " + skipped + " 条");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("inserted", inserted);
        result.put("skipped", skipped);
        ArrayNode errorRows = result.putArray("errors");
        for (String error : errors) errorRows.add(error);
        return toMap(result);
    }

    @Transactional
    public Map<String, Object> importAccounts(SessionUser user) {
        requireAdmin(user);
        List<ImportRow> rows = jdbcTemplate.query(
            """
            SELECT a.id,
                   MAX(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.raw_json, '$.name')), a.username)) AS person_name,
                   d.name AS department
            FROM clinic_accounts a
            JOIN clinic_account_departments ad
              ON ad.account_id = a.id AND ad.status = 'ACTIVE' AND ad.is_primary = 1
            JOIN clinic_departments d ON d.id = ad.department_id AND d.status = 'ACTIVE'
            WHERE a.status = '启用'
            GROUP BY a.id, d.name
            ORDER BY d.name, person_name
            """,
            (rs, rowNum) -> new ImportRow(
                rowNum + 1, rs.getString("person_name"), rs.getString("department"), rs.getString("id")
            )
        );
        int inserted = 0;
        int skipped = 0;
        for (ImportRow row : rows) {
            String name = normalizeName(row.name());
            String department = normalizeDepartment(row.department());
            if (name.length() < 2 || department.isBlank()) {
                skipped++;
                continue;
            }
            int changed = jdbcTemplate.update(
                """
                INSERT IGNORE INTO clinic_exam_roster (id, name, name_key, department, source, account_id)
                VALUES (?, ?, ?, ?, 'ACCOUNT', ?)
                """,
                "exam-roster-" + UUID.randomUUID(), name, nameKey(name), department, row.accountId()
            );
            if (changed > 0) inserted++;
            else skipped++;
        }
        audit(user, "ROSTER_IMPORT_ACCOUNTS", "从账号导入花名册：" + inserted + " 条，跳过 " + skipped + " 条");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("inserted", inserted);
        result.put("skipped", skipped);
        return toMap(result);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> attempts(String department, String status, SessionUser user) {
        requireAdmin(user);
        PaperRow paper = loadPaper();
        StringBuilder sql = new StringBuilder(
            """
            SELECT id, name, department, status, score, correct_count, started_at, submitted_at,
                   duration_seconds, void_reason
            FROM clinic_exam_attempt WHERE paper_id = ?
            """
        );
        List<Object> args = new ArrayList<>();
        args.add(paper.id());
        if (department != null && !department.isBlank()) {
            sql.append(" AND department = ?");
            args.add(department.trim());
        }
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!normalizedStatus.isBlank()) {
            sql.append(" AND status = ?");
            args.add(normalizedStatus);
        }
        sql.append(" ORDER BY COALESCE(submitted_at, started_at) DESC LIMIT 500");
        List<Map<String, Object>> list = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getString("id"));
            row.put("name", rs.getString("name"));
            row.put("department", rs.getString("department"));
            row.put("status", rs.getString("status"));
            row.put("score", rs.getBigDecimal("score"));
            row.put("correctCount", rs.getInt("correct_count"));
            row.put("startedAt", format(rs.getTimestamp("started_at")));
            row.put("submittedAt", format(rs.getTimestamp("submitted_at")));
            row.put("durationSeconds", rs.getInt("duration_seconds"));
            row.put("voidReason", rs.getString("void_reason"));
            return row;
        }, args.toArray());
        ObjectNode result = objectMapper.createObjectNode();
        result.put("total", list.size());
        result.set("list", objectMapper.valueToTree(list));
        return toMap(result);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> attemptDetail(String id, SessionUser user) {
        requireAdmin(user);
        AttemptRow attempt = requireAttempt(id);
        PaperRow paper = loadPaper();
        List<QuestionRow> questions = loadQuestions(paper.id());
        Map<String, String> answers = parseAnswers(attempt.answersJson());
        ObjectNode result = objectMapper.createObjectNode();
        result.set("attempt", attemptJson(attempt, false));
        ArrayNode rows = result.putArray("questions");
        for (QuestionRow question : questions) {
            String chosen = answers.getOrDefault(question.id(), "");
            boolean correct = answerMatches(chosen, question.answer());
            ObjectNode row = rows.addObject();
            row.put("id", question.id());
            row.put("seq", question.seq());
            row.put("stem", question.stem());
            ArrayNode options = row.putArray("options");
            for (String option : question.options()) options.add(option);
            row.put("chosen", chosen);
            ArrayNode answer = row.putArray("answer");
            for (String letter : question.answer()) answer.add(letter);
            row.put("explanation", question.explanation());
            row.put("correct", correct);
            row.put("score", correct ? question.score() : BigDecimal.ZERO);
        }
        return toMap(result);
    }

    @Transactional
    public Map<String, Object> voidAttempt(String id, JsonNode payload, SessionUser user) {
        requireAdmin(user);
        String reason = text(payload, "reason");
        if (reason.length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写作废原因");
        }
        AttemptRow attempt = lockAttempt(id);
        if ("VOID".equals(attempt.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该答卷已作废");
        }
        jdbcTemplate.update(
            """
            UPDATE clinic_exam_attempt
            SET status = 'VOID', active_key = NULL, voided_by = ?, voided_at = CURRENT_TIMESTAMP(3),
                void_reason = ?
            WHERE id = ?
            """,
            user.name(), reason, id
        );
        audit(user, "ATTEMPT_VOID", "作废答卷 " + attempt.name() + " / " + attempt.department() + "：" + reason);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("id", id);
        result.put("status", "VOID");
        return toMap(result);
    }

    @Transactional
    public Map<String, Object> updatePaperStatus(JsonNode payload, SessionUser user) {
        requireAdmin(user);
        String status = text(payload, "status").toUpperCase(Locale.ROOT);
        if (!"OPEN".equals(status) && !"CLOSED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态只能为 OPEN 或 CLOSED");
        }
        PaperRow paper = loadPaper();
        jdbcTemplate.update(
            "UPDATE clinic_exam_paper SET status = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP(3) WHERE id = ?",
            status, user.name(), paper.id()
        );
        audit(user, "PAPER_STATUS", "考试状态：" + status);
        return adminPaper(user);
    }

    @Transactional(readOnly = true)
    public byte[] exportAttempts(SessionUser user) {
        requireAdmin(user);
        PaperRow paper = loadPaper();
        List<Map<String, Object>> rows = jdbcTemplate.query(
            """
            SELECT name, department, score, correct_count, duration_seconds, submitted_at
            FROM clinic_exam_attempt
            WHERE paper_id = ? AND status = 'SUBMITTED'
            ORDER BY department, name
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", rs.getString("name"));
                row.put("department", rs.getString("department"));
                row.put("score", rs.getBigDecimal("score"));
                row.put("correctCount", rs.getInt("correct_count"));
                row.put("durationSeconds", rs.getInt("duration_seconds"));
                row.put("submittedAt", format(rs.getTimestamp("submitted_at")));
                return row;
            },
            paper.id()
        );
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("答卷记录");
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            String[] headers = {"姓名", "科室", "得分", "答对题数", "用时（秒）", "交卷时间"};
            Row header = sheet.createRow(0);
            for (int index = 0; index < headers.length; index++) {
                Cell cell = header.createCell(index);
                cell.setCellValue(headers[index]);
                cell.setCellStyle(headerStyle);
            }
            int rowIndex = 1;
            for (Map<String, Object> item : rows) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(String.valueOf(item.get("name")));
                row.createCell(1).setCellValue(String.valueOf(item.get("department")));
                row.createCell(2).setCellValue(String.valueOf(item.get("score")));
                row.createCell(3).setCellValue(String.valueOf(item.get("correctCount")));
                row.createCell(4).setCellValue(String.valueOf(item.get("durationSeconds")));
                row.createCell(5).setCellValue(String.valueOf(item.get("submittedAt")));
            }
            for (int index = 0; index < headers.length; index++) sheet.autoSizeColumn(index);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("导出答卷失败", error);
        }
    }

    // ---------- helpers ----------

    private Map<String, Object> attemptResponse(AttemptRow attempt, PaperRow paper) {
        ObjectNode result = objectMapper.createObjectNode();
        result.set("attempt", attemptJson(attempt, true));
        if ("SUBMITTED".equals(attempt.status())) {
            result.set("result", resultJson(attempt, paper, loadQuestions(paper.id())));
        } else {
            result.putNull("result");
        }
        return toMap(result);
    }

    private ObjectNode resultJson(AttemptRow attempt, PaperRow paper, List<QuestionRow> questions) {
        Map<String, String> answers = parseAnswers(attempt.answersJson());
        ObjectNode result = objectMapper.createObjectNode();
        result.put("score", attempt.score());
        result.put("correctCount", attempt.correctCount());
        result.put("totalQuestions", questions.size());
        result.put("totalScore", paper.totalScore());
        result.put("durationSeconds", attempt.durationSeconds());
        result.put("submittedAt", format(attempt.submittedAt()));
        ArrayNode wrong = result.putArray("wrongReview");
        for (QuestionRow question : questions) {
            String chosen = answers.getOrDefault(question.id(), "");
            if (answerMatches(chosen, question.answer())) continue;
            ObjectNode row = wrong.addObject();
            row.put("seq", question.seq());
            row.put("stem", question.stem());
            row.put("chosen", chosen.isBlank() ? "未作答" : chosen);
            row.put("answer", String.join("", question.answer()));
            row.put("explanation", question.explanation());
        }
        return result;
    }

    private ObjectNode attemptJson(AttemptRow attempt, boolean includeToken) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", attempt.id());
        row.put("name", attempt.name());
        row.put("department", attempt.department());
        row.put("status", attempt.status());
        if (includeToken) row.put("token", attempt.token());
        ObjectNode answers = row.putObject("answers");
        for (Map.Entry<String, String> entry : parseAnswers(attempt.answersJson()).entrySet()) {
            answers.put(entry.getKey(), entry.getValue());
        }
        row.put("score", attempt.score());
        row.put("correctCount", attempt.correctCount());
        row.put("startedAt", format(attempt.startedAt()));
        row.put("lastSavedAt", format(attempt.lastSavedAt()));
        row.put("submittedAt", format(attempt.submittedAt()));
        row.put("durationSeconds", attempt.durationSeconds());
        row.put("voidReason", attempt.voidReason());
        return row;
    }

    private ObjectNode paperJson(PaperRow paper) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", paper.id());
        row.put("code", paper.code());
        row.put("title", paper.title());
        row.put("description", paper.description());
        row.put("questionCount", paper.questionCount());
        row.put("pointsPerQuestion", paper.pointsPerQuestion());
        row.put("totalScore", paper.totalScore());
        row.put("status", paper.status());
        return row;
    }

    private PaperRow loadPaper() {
        List<PaperRow> rows = jdbcTemplate.query(
            """
            SELECT id, code, title, description, question_count, points_per_question, total_score, status
            FROM clinic_exam_paper ORDER BY updated_at DESC, id LIMIT 1
            """,
            (rs, rowNum) -> new PaperRow(
                rs.getString("id"), rs.getString("code"), rs.getString("title"), rs.getString("description"),
                rs.getInt("question_count"), rs.getBigDecimal("points_per_question"),
                rs.getBigDecimal("total_score"), rs.getString("status")
            )
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<QuestionRow> loadQuestions(String paperId) {
        return jdbcTemplate.query(
            """
            SELECT id, seq, stem, options_json, answer_json, explanation, score
            FROM clinic_exam_question WHERE paper_id = ? AND status = 'ACTIVE' ORDER BY seq
            """,
            (rs, rowNum) -> new QuestionRow(
                rs.getString("id"), rs.getInt("seq"), rs.getString("stem"),
                readStringArray(rs.getString("options_json")),
                readStringArray(rs.getString("answer_json")),
                rs.getString("explanation"), rs.getBigDecimal("score")
            ),
            paperId
        );
    }

    private AttemptRow loadAttempt(String id) {
        List<AttemptRow> rows = jdbcTemplate.query(
            attemptSelect() + " WHERE id = ?", (rs, rowNum) -> attemptRow(rs), id
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private AttemptRow loadAttemptByActiveKey(String activeKey) {
        List<AttemptRow> rows = jdbcTemplate.query(
            attemptSelect() + " WHERE active_key = ?", (rs, rowNum) -> attemptRow(rs), activeKey
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private AttemptRow lockAttempt(String id) {
        List<AttemptRow> rows = jdbcTemplate.query(
            attemptSelect() + " WHERE id = ? FOR UPDATE", (rs, rowNum) -> attemptRow(rs), id
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "答卷不存在");
        return rows.get(0);
    }

    private AttemptRow requireAttempt(String id) {
        AttemptRow attempt = loadAttempt(id);
        if (attempt == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "答卷不存在");
        return attempt;
    }

    private String attemptSelect() {
        return """
            SELECT id, paper_id, name, name_key, department, status, attempt_token, answers_json,
                   score, correct_count, started_at, last_saved_at, submitted_at, duration_seconds, void_reason
            FROM clinic_exam_attempt
            """;
    }

    private AttemptRow attemptRow(ResultSet rs) throws SQLException {
        return new AttemptRow(
            rs.getString("id"), rs.getString("paper_id"), rs.getString("name"), rs.getString("name_key"),
            rs.getString("department"), rs.getString("status"), rs.getString("attempt_token"),
            rs.getString("answers_json"), rs.getBigDecimal("score"), rs.getInt("correct_count"),
            toLocal(rs.getTimestamp("started_at")), toLocal(rs.getTimestamp("last_saved_at")),
            toLocal(rs.getTimestamp("submitted_at")), rs.getInt("duration_seconds"),
            rs.getString("void_reason")
        );
    }

    private String latestVoidAttempt(String paperId, String name, String department) {
        List<String> rows = jdbcTemplate.query(
            """
            SELECT id FROM clinic_exam_attempt
            WHERE paper_id = ? AND name_key = ? AND department = ? AND status = 'VOID'
            ORDER BY voided_at DESC LIMIT 1
            """,
            (rs, rowNum) -> rs.getString(1), paperId, nameKey(name), department
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<RosterRow> loadRoster() {
        return jdbcTemplate.query(
            "SELECT id, name, department, source FROM clinic_exam_roster ORDER BY department, name",
            (rs, rowNum) -> new RosterRow(
                rs.getString("id"), rs.getString("name"), rs.getString("department"), rs.getString("source")
            )
        );
    }

    private List<String> departments() {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        jdbcTemplate.query(
            "SELECT name FROM clinic_departments WHERE status = 'ACTIVE' ORDER BY name",
            rs -> { values.add(rs.getString(1)); }
        );
        jdbcTemplate.query(
            "SELECT DISTINCT department FROM clinic_exam_roster ORDER BY department",
            rs -> { values.add(rs.getString(1)); }
        );
        values.removeIf(value -> value == null || value.isBlank());
        List<String> result = new ArrayList<>(values);
        Collections.sort(result);
        return result;
    }

    private void validateIdentity(String name, String department, PaperRow paper) {
        if (name.length() < 2 || name.length() > 40) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写 2–40 个字符的姓名");
        }
        if (department.isBlank() || department.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择科室");
        }
        if (!departments().contains(department)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "科室不在可选列表中，请联系管理员");
        }
        if (paper == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "试卷不存在");
    }

    private Map<String, String> normalizeAnswers(JsonNode node, List<QuestionRow> questions) {
        if (node == null || !node.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "答案格式不正确");
        }
        Map<String, QuestionRow> byId = new HashMap<>();
        for (QuestionRow question : questions) byId.put(question.id(), question);
        Map<String, String> result = new LinkedHashMap<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            QuestionRow question = byId.get(entry.getKey());
            if (question == null) continue;
            String letters = normalizeLetters(entry.getValue().asText(""));
            if (!letters.isEmpty()) result.put(question.id(), letters);
        }
        return result;
    }

    private static String normalizeLetters(String value) {
        if (value == null) return "";
        Set<Character> letters = new HashSet<>();
        for (char character : value.toUpperCase(Locale.ROOT).toCharArray()) {
            if (OPTION_LETTERS.contains(character)) letters.add(character);
        }
        List<Character> ordered = new ArrayList<>(letters);
        Collections.sort(ordered);
        StringBuilder result = new StringBuilder();
        for (Character letter : ordered) result.append(letter);
        return result.toString();
    }

    private static boolean answerMatches(String chosen, List<String> answer) {
        Set<Character> chosenSet = new HashSet<>();
        for (char character : normalizeLetters(chosen).toCharArray()) chosenSet.add(character);
        Set<Character> answerSet = new HashSet<>();
        for (String letter : answer) {
            if (!letter.isBlank()) answerSet.add(letter.charAt(0));
        }
        return !chosenSet.isEmpty() && chosenSet.equals(answerSet);
    }

    private Map<String, String> parseAnswers(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            JsonNode node = objectMapper.readTree(json);
            Map<String, String> answers = new LinkedHashMap<>();
            if (node.isObject()) {
                var fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    answers.put(entry.getKey(), entry.getValue().asText(""));
                }
            }
            return answers;
        } catch (Exception error) {
            return new LinkedHashMap<>();
        }
    }

    private List<String> readStringArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = objectMapper.readTree(json);
            List<String> values = new ArrayList<>();
            if (node.isArray()) node.forEach(item -> values.add(item.asText("")));
            return values;
        } catch (Exception error) {
            return List.of();
        }
    }

    private static String normalizeName(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
        return normalized.replaceAll("\\s+", "").trim();
    }

    private static String nameKey(String value) {
        return normalizeName(value);
    }

    private static String normalizeDepartment(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).trim();
    }

    private static String activeKey(String paperId, String name, String department) {
        return paperId + "|" + nameKey(name) + "|" + normalizeDepartment(department);
    }

    private static void requireToken(AttemptRow attempt, String token) {
        if (token == null || !token.equals(attempt.token())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "答题凭证无效，请重新进入");
        }
    }

    private static void requireAdmin(SessionUser user) {
        if (user == null || !"admin".equalsIgnoreCase(user.role() == null ? "" : user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无培训考核管理权限");
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        return value.asText("").trim();
    }

    private static String format(Timestamp timestamp) {
        return timestamp == null ? "" : timestamp.toLocalDateTime().format(DATE_TIME);
    }

    private static String format(LocalDateTime value) {
        return value == null ? "" : value.format(DATE_TIME);
    }

    private static LocalDateTime toLocal(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private int countAttempts(String paperId, String status) {
        if (status == null) {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM clinic_exam_attempt WHERE paper_id = ?", Integer.class, paperId
            );
            return count == null ? 0 : count;
        }
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM clinic_exam_attempt WHERE paper_id = ? AND status = ?",
            Integer.class, paperId, status
        );
        return count == null ? 0 : count;
    }

    private List<ImportRow> readRosterFile(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".csv") || filename.endsWith(".txt")) {
            return readCsv(file.getBytes());
        }
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            List<ImportRow> rows = new ArrayList<>();
            int rowNo = 0;
            for (Row row : sheet) {
                rowNo++;
                if (rowNo == 1 && isHeader(row.getCell(0), row.getCell(1))) continue;
                String name = cellText(row.getCell(0));
                String department = cellText(row.getCell(1));
                if (name.isBlank() && department.isBlank()) continue;
                rows.add(new ImportRow(rowNo, name, department));
            }
            return rows;
        }
    }

    private List<ImportRow> readCsv(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<ImportRow> rows = new ArrayList<>();
        int rowNo = 0;
        for (String line : text.split("\\r?\\n")) {
            rowNo++;
            if (line.isBlank()) continue;
            String[] cells = line.contains("\t") ? line.split("\t", -1) : line.split(",", -1);
            String name = cells.length > 0 ? cells[0].trim() : "";
            String department = cells.length > 1 ? cells[1].trim() : "";
            if (rowNo == 1 && isHeaderText(name, department)) continue;
            if (name.isBlank() && department.isBlank()) continue;
            rows.add(new ImportRow(rowNo, name, department));
        }
        return rows;
    }

    private static boolean isHeader(Cell first, Cell second) {
        return isHeaderText(cellText(first), cellText(second));
    }

    private static boolean isHeaderText(String first, String second) {
        String value = (first + second).toLowerCase(Locale.ROOT);
        return value.contains("姓名") || value.contains("name") || value.contains("科室") || value.contains("department");
    }

    private static String cellText(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default -> "";
        };
    }

    private void audit(SessionUser user, String action, String detail) {
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("detail", detail);
        jdbcTemplate.update(
            """
            INSERT INTO clinic_audit_logs
              (id, time, operator, role, patient, patient_id, module, action, result, raw_json)
            VALUES (?, ?, ?, ?, NULL, NULL, 'exam', ?, 'SUCCESS', CAST(? AS JSON))
            """,
            "exam-audit-" + UUID.randomUUID(), LocalDateTime.now().format(DATE_TIME),
            user.name(), user.role() == null ? "" : user.role(), action, json(raw)
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("JSON 序列化失败", error);
        }
    }

    private Map<String, Object> toMap(ObjectNode node) {
        return objectMapper.convertValue(node, Map.class);
    }

    private record PaperRow(String id, String code, String title, String description, int questionCount,
                            BigDecimal pointsPerQuestion, BigDecimal totalScore, String status) {}

    private record QuestionRow(String id, int seq, String stem, List<String> options, List<String> answer,
                               String explanation, BigDecimal score) {}

    private record AttemptRow(String id, String paperId, String name, String nameKey, String department,
                              String status, String token, String answersJson, BigDecimal score, int correctCount,
                              LocalDateTime startedAt, LocalDateTime lastSavedAt, LocalDateTime submittedAt,
                              int durationSeconds, String voidReason) {}

    private record RosterRow(String id, String name, String department, String source) {}

    private record ImportRow(int rowNo, String name, String department, String accountId) {
        ImportRow(int rowNo, String name, String department) {
            this(rowNo, name, department, null);
        }
    }
}