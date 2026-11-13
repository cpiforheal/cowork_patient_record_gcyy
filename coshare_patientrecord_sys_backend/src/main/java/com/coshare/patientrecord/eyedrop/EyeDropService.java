package com.coshare.patientrecord.eyedrop;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.RoleCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class EyeDropService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final Set<String> EYE_SIDES = Set.of("LEFT", "RIGHT", "BOTH");
    private static final int DAY_CUTOFF_HOUR = 4;
    private static final int LATE_THRESHOLD_MINUTES = 15;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EyeDropService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> today(String date, SessionUser user) {
        requireAdmin(user);
        LocalDate day = parseDate(date, LocalDate.now());
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> plans = loadPlans();
        Map<String, Map<String, Object>> logs = loadLogs(day);
        List<Map<String, Object>> items = new ArrayList<>();
        int due = 0;
        int taken = 0;
        int lateCount = 0;
        int missed = 0;
        int upcoming = 0;
        for (Map<String, Object> plan : plans) {
            String planId = text(plan, "id");
            Map<String, Object> log = logs.get(planId);
            LocalTime plannedTime = LocalTime.parse(text(plan, "planned_time_text"));
            LocalDateTime plannedAt = LocalDateTime.of(day, plannedTime);
            int window = number(plan, "window_minutes", 120);
            String state;
            if (log != null) {
                state = hasLate(log) ? "TAKEN_LATE" : "TAKEN";
                taken++;
                if (hasLate(log)) lateCount++;
                due++;
            } else if (now.isBefore(plannedAt.minusMinutes(window))) {
                state = "UPCOMING";
                upcoming++;
            } else if (now.isAfter(plannedAt.plusMinutes(window))) {
                state = "MISSED";
                missed++;
                due++;
            } else if (now.isAfter(plannedAt.plusMinutes(LATE_THRESHOLD_MINUTES))) {
                state = "LATE";
                due++;
            } else {
                state = "DUE";
                due++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("planId", planId);
            item.put("drugCode", text(plan, "drug_code"));
            item.put("drugName", text(plan, "drug_name"));
            item.put("slotCode", text(plan, "slot_code"));
            item.put("slotLabel", text(plan, "slot_label"));
            item.put("plannedTime", text(plan, "planned_time_text"));
            item.put("windowMinutes", window);
            item.put("state", state);
            item.put("canVoid", log != null && canVoid(day, now));
            if (log != null) {
                item.put("logId", text(log, "id"));
                item.put("eyeSide", text(log, "eye_side"));
                item.put("takenAt", text(log, "taken_at_text"));
                item.put("late", hasLate(log));
                item.put("minutesLate", number(log, "minutes_late", 0));
                item.put("note", text(log, "note"));
            }
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", day.toString());
        result.put("now", now.format(DATE_TIME));
        result.put("items", items);
        result.put("measurements", listMeasurements(day));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", items.size());
        summary.put("due", due);
        summary.put("taken", taken);
        summary.put("late", lateCount);
        summary.put("missed", missed);
        summary.put("upcoming", upcoming);
        summary.put("adherence", percentage(taken, due));
        result.put("summary", summary);
        return result;
    }

    public Map<String, Object> month(String month, SessionUser user) {
        requireAdmin(user);
        LocalDate start = parseMonth(month, LocalDate.now()).withDayOfMonth(1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        LocalDate today = LocalDate.now();
        int planCount = loadPlans().size();
        Map<String, int[]> aggregates = new LinkedHashMap<>();
        jdbcTemplate.query("""
            SELECT DATE_FORMAT(take_date, '%Y-%m-%d') AS day_text, COUNT(*) AS taken_count, COALESCE(SUM(late), 0) AS late_count
            FROM clinic_eye_drop_log
            WHERE status = 'TAKEN' AND take_date BETWEEN ? AND ?
            GROUP BY take_date
            """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                aggregates.put(rs.getString("day_text"), new int[]{rs.getInt("taken_count"), rs.getInt("late_count")});
            }, start, end);
        List<Map<String, Object>> days = new ArrayList<>();
        int totalTaken = 0;
        int totalLate = 0;
        int totalDue = 0;
        int peak = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            int[] agg = aggregates.getOrDefault(day.toString(), new int[]{0, 0});
            int taken = agg[0];
            int late = agg[1];
            int due = applicableCount(planCount, day, today);
            int missed = Math.max(0, due - taken);
            totalTaken += taken;
            totalLate += late;
            totalDue += due;
            peak = Math.max(peak, taken);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day.toString());
            row.put("day", day.getDayOfMonth());
            row.put("total", planCount);
            row.put("due", due);
            row.put("taken", taken);
            row.put("late", late);
            row.put("missed", missed);
            row.put("adherence", percentage(taken, due));
            days.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", start.format(MONTH));
        result.put("monthLabel", start.getYear() + "年" + start.getMonthValue() + "月");
        result.put("days", days);
        result.put("peak", peak);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("due", totalDue);
        summary.put("taken", totalTaken);
        summary.put("late", totalLate);
        summary.put("missed", Math.max(0, totalDue - totalTaken));
        summary.put("adherence", percentage(totalTaken, totalDue));
        result.put("summary", summary);
        return result;
    }

    @Transactional
    public Map<String, Object> createDose(DoseRequest request, SessionUser user) {
        requireAdmin(user);
        if (request == null || blank(request.planId())) throw badRequest("请选择滴注药物");
        Map<String, Object> plan = loadPlan(request.planId());
        String eyeSide = normalizeEyeSide(request.eyeSide());
        LocalDateTime takenAt = parseDateTime(request.takenAt(), LocalDateTime.now());
        LocalDate takeDate = takenAt.toLocalTime().isBefore(LocalTime.of(DAY_CUTOFF_HOUR, 0))
            ? takenAt.toLocalDate().minusDays(1)
            : takenAt.toLocalDate();
        LocalTime plannedTime = LocalTime.parse(text(plan, "planned_time_text"));
        LocalDateTime plannedAt = LocalDateTime.of(takeDate, plannedTime);
        boolean late = takenAt.isAfter(plannedAt.plusMinutes(LATE_THRESHOLD_MINUTES));
        long minutesLate = late ? Math.max(0, Duration.between(plannedAt, takenAt).toMinutes()) : 0;
        String requestId = text(request.requestId());
        if (!requestId.isBlank()) {
            List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT id FROM clinic_eye_drop_log WHERE request_id = ? LIMIT 1", requestId
            );
            if (!existing.isEmpty()) return loadLog(String.valueOf(existing.get(0).get("id")));
        }
        String id = "edl-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO clinic_eye_drop_log (
              id, plan_id, take_date, drug_code, slot_code, eye_side, planned_time, taken_at,
              late, minutes_late, note, status, request_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'TAKEN', ?)
            ON DUPLICATE KEY UPDATE
              eye_side = VALUES(eye_side), taken_at = VALUES(taken_at), late = VALUES(late),
              minutes_late = VALUES(minutes_late), note = VALUES(note), status = 'TAKEN',
              request_id = VALUES(request_id), voided_at = NULL, voided_by = NULL,
              updated_at = CURRENT_TIMESTAMP(3)
            """, id, text(plan, "id"), takeDate, text(plan, "drug_code"), text(plan, "slot_code"),
            eyeSide, plannedTime, takenAt, late ? 1 : 0, minutesLate, text(request.note()),
            requestId.isBlank() ? null : requestId);
        return loadLogByDayPlan(takeDate, text(plan, "id"));
    }

    @Transactional
    public Map<String, Object> voidDose(String id, SessionUser user) {
        requireAdmin(user);
        Map<String, Object> log = loadLog(id);
        if ("VOID".equals(text(log, "status"))) return log;
        LocalDate takeDate = LocalDate.parse(text(log, "takeDate"));
        if (!canVoid(takeDate, LocalDateTime.now())) {
            throw conflict("仅可撤销当天或睡前跨零点尚未结算的记录");
        }
        jdbcTemplate.update("""
            UPDATE clinic_eye_drop_log
            SET status = 'VOID', voided_at = ?, voided_by = ?, updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = ? AND status = 'TAKEN'
            """, LocalDateTime.now(), user.name(), id);
        return loadLog(id);
    }

    @Transactional
    public Map<String, Object> updatePlan(String planId, PlanTimeRequest request, SessionUser user) {
        requireAdmin(user);
        if (request == null || blank(request.plannedTime())) throw badRequest("请填写计划时间");
        LocalTime time = parseTime(request.plannedTime());
        int updated = jdbcTemplate.update(
            "UPDATE clinic_eye_drop_plan SET planned_time = ? WHERE id = ?", time, planId
        );
        if (updated == 0) throw notFound("滴注计划不存在");
        return loadPlan(planId);
    }

    @Transactional
    public Map<String, Object> createMeasurement(MeasurementRequest request, SessionUser user) {
        requireAdmin(user);
        if (request == null) throw badRequest("请填写眼压或症状");
        LocalDateTime measuredAt = parseDateTime(request.measuredAt(), LocalDateTime.now());
        Double iopLeft = normalizeIop(request.iopLeft());
        Double iopRight = normalizeIop(request.iopRight());
        List<String> symptoms = request.symptoms() == null ? List.of() : request.symptoms();
        String note = text(request.note());
        if (iopLeft == null && iopRight == null && symptoms.isEmpty() && note.isBlank()) {
            throw badRequest("请至少填写一项眼压、症状或备注");
        }
        String doseLogId = text(request.doseLogId());
        if (!doseLogId.isBlank()) {
            jdbcTemplate.queryForList("SELECT id FROM clinic_eye_drop_log WHERE id = ? LIMIT 1", doseLogId);
        }
        String id = "edm-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO clinic_eye_drop_measurement (
              id, measured_at, iop_left, iop_right, symptoms_json, symptom_note, dose_log_id
            ) VALUES (?, ?, ?, ?, CAST(? AS JSON), ?, ?)
            """, id, measuredAt, iopLeft, iopRight, toJson(symptoms), note,
            doseLogId.isBlank() ? null : doseLogId);
        return loadMeasurement(id);
    }

    @Transactional
    public Map<String, Object> deleteMeasurement(String id, SessionUser user) {
        requireAdmin(user);
        int deleted = jdbcTemplate.update("DELETE FROM clinic_eye_drop_measurement WHERE id = ?", id);
        if (deleted == 0) throw notFound("记录不存在");
        return Map.of("id", id, "deleted", true);
    }

    public ExportFile exportCsv(String month, SessionUser user) {
        requireAdmin(user);
        LocalDate start = parseMonth(month, LocalDate.now()).withDayOfMonth(1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> plans = loadPlans();
        Map<String, Map<String, Map<String, Object>>> logs = new LinkedHashMap<>();
        jdbcTemplate.query("""
            SELECT DATE_FORMAT(take_date, '%Y-%m-%d') AS take_date_text, plan_id, eye_side,
                   DATE_FORMAT(taken_at, '%H:%i') AS taken_time_text, late, minutes_late, note
            FROM clinic_eye_drop_log
            WHERE status = 'TAKEN' AND take_date BETWEEN ? AND ?
            """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                String day = rs.getString("take_date_text");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("eye_side", rs.getString("eye_side"));
                row.put("taken_time_text", rs.getString("taken_time_text"));
                row.put("late", rs.getInt("late"));
                row.put("minutes_late", rs.getInt("minutes_late"));
                row.put("note", rs.getString("note"));
                logs.computeIfAbsent(day, key -> new LinkedHashMap<>()).put(rs.getString("plan_id"), row);
            }, start, end);
        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF');
        csv.append("记录类型,日期,时间,药物,时段,眼别,状态,迟到分钟,左眼压,右眼压,症状,备注\r\n");
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            Map<String, Map<String, Object>> dayLogs = logs.getOrDefault(day.toString(), Map.of());
            for (Map<String, Object> plan : plans) {
                Map<String, Object> log = dayLogs.get(text(plan, "id"));
                String state;
                if (log != null) state = hasLate(log) ? "迟滴" : "已滴";
                else state = day.isBefore(today) ? "未滴" : (day.isAfter(today) ? "未到" : "未滴");
                csv.append(csvRow(
                    "滴注", day.toString(), log == null ? text(plan, "planned_time_text") : text(log, "taken_time_text"),
                    text(plan, "drug_name"), text(plan, "slot_label"), log == null ? "" : eyeSideLabel(text(log, "eye_side")),
                    state, log == null ? "" : String.valueOf(number(log, "minutes_late", 0)),
                    "", "", "", log == null ? "" : text(log, "note")
                ));
            }
        }
        jdbcTemplate.query("""
            SELECT DATE_FORMAT(measured_at, '%Y-%m-%d') AS day_text, DATE_FORMAT(measured_at, '%H:%i') AS time_text,
                   iop_left, iop_right, symptoms_json, symptom_note
            FROM clinic_eye_drop_measurement
            WHERE DATE(measured_at) BETWEEN ? AND ?
            ORDER BY measured_at
            """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                csv.append(csvRow(
                    "眼压/症状", rs.getString("day_text"), rs.getString("time_text"),
                    "", "", "", "", "", rs.getString("iop_left"), rs.getString("iop_right"),
                    String.join("、", parseSymptoms(rs.getString("symptoms_json"))), rs.getString("symptom_note")
                ));
            }, start, end);
        String fileName = "眼药水滴注记录-" + start.format(MONTH).replace("-", "") + ".csv";
        return new ExportFile(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), fileName);
    }

    private int applicableCount(int planCount, LocalDate day, LocalDate today) {
        if (day.isBefore(today)) return planCount;
        if (day.isAfter(today)) return 0;
        LocalDateTime now = LocalDateTime.now();
        int due = 0;
        for (Map<String, Object> plan : loadPlans()) {
            LocalTime time = LocalTime.parse(text(plan, "planned_time_text"));
            int window = number(plan, "window_minutes", 120);
            if (!now.isBefore(LocalDateTime.of(day, time).minusMinutes(window))) due++;
        }
        return due;
    }

    private boolean canVoid(LocalDate day, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        if (day.equals(today)) return true;
        return now.toLocalTime().isBefore(LocalTime.of(DAY_CUTOFF_HOUR, 0)) && day.equals(today.minusDays(1));
    }

    private List<Map<String, Object>> loadPlans() {
        return jdbcTemplate.queryForList("""
            SELECT id, drug_code, drug_name, slot_code, slot_label,
                   DATE_FORMAT(planned_time, '%H:%i') AS planned_time_text, window_minutes, sort_no
            FROM clinic_eye_drop_plan
            WHERE active = 1
            ORDER BY sort_no
            """);
    }

    private Map<String, Object> loadPlan(String planId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT id, drug_code, drug_name, slot_code, slot_label,
                   DATE_FORMAT(planned_time, '%H:%i') AS planned_time_text, window_minutes, sort_no
            FROM clinic_eye_drop_plan
            WHERE id = ?
            """, planId);
        if (rows.isEmpty()) throw notFound("滴注计划不存在");
        return rows.get(0);
    }

    private Map<String, Map<String, Object>> loadLogs(LocalDate day) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        jdbcTemplate.query("""
            SELECT id, plan_id, drug_code, slot_code, eye_side,
                   DATE_FORMAT(taken_at, '%Y-%m-%d %H:%i:%s') AS taken_at_text, late, minutes_late, note
            FROM clinic_eye_drop_log
            WHERE take_date = ? AND status = 'TAKEN'
            """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getString("id"));
                row.put("plan_id", rs.getString("plan_id"));
                row.put("eye_side", rs.getString("eye_side"));
                row.put("taken_at_text", rs.getString("taken_at_text"));
                row.put("late", rs.getInt("late"));
                row.put("minutes_late", rs.getInt("minutes_late"));
                row.put("note", rs.getString("note"));
                result.put(rs.getString("plan_id"), row);
            }, day);
        return result;
    }

    private Map<String, Object> loadLogByDayPlan(LocalDate day, String planId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT id, plan_id, DATE_FORMAT(take_date, '%Y-%m-%d') AS take_date_text,
                   drug_code, slot_code, eye_side,
                   DATE_FORMAT(taken_at, '%Y-%m-%d %H:%i:%s') AS taken_at_text,
                   DATE_FORMAT(planned_time, '%H:%i') AS planned_time_text,
                   late, minutes_late, note, status
            FROM clinic_eye_drop_log
            WHERE take_date = ? AND plan_id = ?
            """, day, planId);
        if (rows.isEmpty()) throw conflict("打卡未保存，请重试");
        return normalizeLog(rows.get(0));
    }

    private Map<String, Object> loadLog(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT id, plan_id, DATE_FORMAT(take_date, '%Y-%m-%d') AS take_date_text,
                   drug_code, slot_code, eye_side,
                   DATE_FORMAT(taken_at, '%Y-%m-%d %H:%i:%s') AS taken_at_text,
                   DATE_FORMAT(planned_time, '%H:%i') AS planned_time_text,
                   late, minutes_late, note, status
            FROM clinic_eye_drop_log WHERE id = ?
            """, id);
        if (rows.isEmpty()) throw notFound("滴注记录不存在");
        return normalizeLog(rows.get(0));
    }

    private Map<String, Object> normalizeLog(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", text(row.get("id")));
        result.put("planId", text(row.get("plan_id")));
        result.put("takeDate", text(row.get("take_date_text")));
        result.put("drugCode", text(row.get("drug_code")));
        result.put("slotCode", text(row.get("slot_code")));
        result.put("eyeSide", text(row.get("eye_side")));
        result.put("plannedTime", text(row.get("planned_time_text")));
        result.put("takenAt", text(row.get("taken_at_text")));
        result.put("late", number(row, "late", 0) == 1);
        result.put("minutesLate", number(row, "minutes_late", 0));
        result.put("note", text(row.get("note")));
        result.put("status", text(row.get("status")));
        return result;
    }

    private List<Map<String, Object>> listMeasurements(LocalDate day) {
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbcTemplate.query("""
            SELECT id, DATE_FORMAT(measured_at, '%Y-%m-%d %H:%i') AS measured_at_text,
                   iop_left, iop_right, symptoms_json, symptom_note, dose_log_id
            FROM clinic_eye_drop_measurement
            WHERE DATE(measured_at) = ?
            ORDER BY measured_at DESC
            """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getString("id"));
                row.put("measuredAt", rs.getString("measured_at_text"));
                row.put("iopLeft", rs.getBigDecimal("iop_left"));
                row.put("iopRight", rs.getBigDecimal("iop_right"));
                row.put("symptoms", parseSymptoms(rs.getString("symptoms_json")));
                row.put("note", rs.getString("symptom_note"));
                row.put("doseLogId", rs.getString("dose_log_id"));
                rows.add(row);
            }, day);
        return rows;
    }

    private Map<String, Object> loadMeasurement(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT id, DATE_FORMAT(measured_at, '%Y-%m-%d %H:%i') AS measured_at_text,
                   iop_left, iop_right, symptoms_json, symptom_note, dose_log_id
            FROM clinic_eye_drop_measurement WHERE id = ?
            """, id);
        if (rows.isEmpty()) throw notFound("记录不存在");
        Map<String, Object> row = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", text(row.get("id")));
        result.put("measuredAt", text(row.get("measured_at_text")));
        result.put("iopLeft", row.get("iop_left"));
        result.put("iopRight", row.get("iop_right"));
        result.put("symptoms", parseSymptoms(text(row.get("symptoms_json"))));
        result.put("note", text(row.get("symptom_note")));
        result.put("doseLogId", text(row.get("dose_log_id")));
        return result;
    }

    private List<String> parseSymptoms(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception error) {
            throw badRequest("症状数据格式不正确");
        }
    }

    private Double normalizeIop(Double value) {
        if (value == null) return null;
        if (value < 0 || value > 80) throw badRequest("眼压数值应在 0–80 mmHg 之间");
        return Math.round(value * 10d) / 10d;
    }

    private String normalizeEyeSide(String value) {
        String normalized = text(value).toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) return "BOTH";
        if (!EYE_SIDES.contains(normalized)) throw badRequest("眼别只能为左眼、右眼或双眼");
        return normalized;
    }

    private String eyeSideLabel(String value) {
        return switch (text(value)) {
            case "LEFT" -> "左眼";
            case "RIGHT" -> "右眼";
            default -> "双眼";
        };
    }

    private Integer percentage(int taken, int due) {
        if (due <= 0) return null;
        return (int) Math.round(taken * 100d / due);
    }

    private boolean hasLate(Map<String, Object> row) {
        return number(row, "late", 0) == 1;
    }

    private int number(Map<String, Object> row, String key, int fallback) {
        Object value = row.get(key);
        if (value == null) return fallback;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String text(Map<String, Object> row, String key) {
        return text(row.get(key));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        if (blank(value)) return fallback;
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException error) {
            return fallback;
        }
    }

    private LocalDate parseMonth(String value, LocalDate fallback) {
        if (blank(value)) return fallback.withDayOfMonth(1);
        try {
            return LocalDate.parse(value.length() > 7 ? value.substring(0, 7) + "-01" : value + "-01");
        } catch (DateTimeParseException error) {
            return fallback.withDayOfMonth(1);
        }
    }

    private LocalDateTime parseDateTime(String value, LocalDateTime fallback) {
        if (blank(value)) return fallback;
        String normalized = value.trim().replace('T', ' ');
        if (normalized.length() == 16) normalized = normalized + ":00";
        try {
            return LocalDateTime.parse(normalized, DATE_TIME);
        } catch (DateTimeParseException error) {
            throw badRequest("时间格式不正确");
        }
    }

    private LocalTime parseTime(String value) {
        String normalized = text(value);
        try {
            return LocalTime.parse(normalized.length() == 5 ? normalized : normalized.substring(0, 5));
        } catch (RuntimeException error) {
            throw badRequest("时间格式应为 HH:mm");
        }
    }

    private String csvRow(Object... values) {
        StringBuilder row = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) row.append(',');
            String value = values[index] == null ? "" : String.valueOf(values[index]);
            if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
                value = "\"" + value.replace("\"", "\"\"") + "\"";
            }
            row.append(value);
        }
        return row.append("\r\n").toString();
    }

    private void requireAdmin(SessionUser user) {
        if (user == null || !"admin".equals(RoleCatalog.canonicalize(user.role()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可使用个人眼药水工作台");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record DoseRequest(String planId, String eyeSide, String takenAt, String note, String requestId) {}

    public record PlanTimeRequest(String plannedTime) {}

    public record MeasurementRequest(
        String measuredAt,
        Double iopLeft,
        Double iopRight,
        List<String> symptoms,
        String note,
        String doseLogId
    ) {}

    public record ExportFile(byte[] content, String fileName) {}
}
