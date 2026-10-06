package com.coshare.patientrecord.clinic.recheck;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.RoleCatalog;
import com.coshare.patientrecord.preai.PreAiEncounterService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 复查预约登记（共享登记表）：
 * - 检查室人员手工登记"某天谁来复查"，行=日期、格=患者；
 * - 到/未到由人工标记，不与就诊记录自动关联（同名、代登记等情况自动匹配不可靠）；
 * - 改期 = 原条目标记 RESCHEDULED 并指向新日期，新日期生成一条 PLANNED（source_id 回指）；
 * - 撤销为软删（CANCELLED），不进统计，审计留痕。
 */
@Service
@Profile("mysql")
public class RecheckScheduleService {

    static final String PLANNED = "PLANNED";
    static final String ARRIVED = "ARRIVED";
    static final String ABSENT = "ABSENT";
    static final String RESCHEDULED = "RESCHEDULED";
    static final String CANCELLED = "CANCELLED";

    /** 写入角色：检查室为主，医护与管理员可协助；接诊岗可代登记与编辑（2026-10-04 放开）；前台/导诊只读。 */
    static final Set<String> EDIT_ROLES = Set.of("inspection", "lab", "ecg", "ultrasound", "doctor", "nurse", "nursing", "admin", "reception");
    static final int MAX_RANGE_DAYS = 92;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String COLUMNS =
        "id, plan_date, patient_name, phone, note, status, status_note, source_id, rescheduled_to, created_by, created_at, updated_by, updated_at";

    private final JdbcTemplate jdbcTemplate;
    private final PreAiEncounterService encounterService;

    public RecheckScheduleService(JdbcTemplate jdbcTemplate, PreAiEncounterService encounterService) {
        this.jdbcTemplate = jdbcTemplate;
        this.encounterService = encounterService;
    }

    // ---------- 读取 ----------

    /** 日期区间看板：每天一行，含条目与当日统计；区间外的汇总给交接用。 */
    public Map<String, Object> board(String fromText, String toText, SessionUser user) {
        LocalDate today = LocalDate.now();
        LocalDate from = fromText == null || fromText.isBlank() ? today.minusDays(1) : parseDate(fromText, "开始日期");
        LocalDate to = toText == null || toText.isBlank() ? today.plusDays(14) : parseDate(toText, "结束日期");
        if (to.isBefore(from)) throw bad("结束日期不能早于开始日期");
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) throw bad("单次最多查看 " + MAX_RANGE_DAYS + " 天");
        List<Entry> entries = jdbcTemplate.query(
            "SELECT " + COLUMNS + " FROM clinic_recheck_schedule WHERE plan_date BETWEEN ? AND ? AND status <> ? "
                + "ORDER BY plan_date ASC, created_at ASC",
            (rs, rowNum) -> mapEntry(rs), from.toString(), to.toString(), CANCELLED);
        Map<String, Object> result = buildBoard(from, to, today, entries);
        result.put("canEdit", canEdit(user));
        return result;
    }

    /** 纯函数：按天分组 + 统计，便于单测。 */
    static Map<String, Object> buildBoard(LocalDate from, LocalDate to, LocalDate today, List<Entry> entries) {
        Map<String, List<Entry>> byDate = new LinkedHashMap<>();
        for (Entry entry : entries) byDate.computeIfAbsent(entry.planDate(), key -> new ArrayList<>()).add(entry);

        List<Map<String, Object>> days = new ArrayList<>();
        Stats total = new Stats();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            List<Entry> list = byDate.getOrDefault(day.toString(), List.of());
            Stats stats = Stats.of(list);
            total.add(stats);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day.toString());
            row.put("weekday", weekdayLabel(day.getDayOfWeek()));
            row.put("relation", day.isBefore(today) ? "past" : day.isEqual(today) ? "today" : "future");
            row.put("stats", stats.toMap(day.isBefore(today)));
            row.put("entries", list.stream().map(Entry::toMap).toList());
            days.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("today", today.toString());
        result.put("days", days);
        result.put("total", total.toMap(false));
        // 过去日期仍是"待到"的条目：交接时需补标到/未到
        result.put("unconfirmedPast", entries.stream()
            .filter(entry -> PLANNED.equals(entry.status()) && LocalDate.parse(entry.planDate()).isBefore(today))
            .count());
        return result;
    }

    // ---------- 写操作 ----------

    public Map<String, Object> create(Map<String, Object> body, SessionUser user) {
        requireEditor(user);
        LocalDate planDate = parseDate(text(body, "planDate"), "复查日期");
        String name = requireName(text(body, "patientName"));
        String phone = normalizePhone(text(body, "phone"));
        String note = limit(text(body, "note"), 200, "备注");
        ensureNoDuplicate(planDate.toString(), name, phone, null);
        String id = newId();
        String now = now();
        jdbcTemplate.update(
            "INSERT INTO clinic_recheck_schedule (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, '', NULL, NULL, ?, ?, ?, ?)",
            id, planDate.toString(), name, phone, note, PLANNED, user.name(), now, user.name(), now);
        audit(user, "recheck.create", "登记复查：" + name + " @ " + planDate);
        return get(id).toMap();
    }

    public Map<String, Object> update(String id, Map<String, Object> body, SessionUser user) {
        requireEditor(user);
        Entry existing = get(id);
        if (RESCHEDULED.equals(existing.status()) || CANCELLED.equals(existing.status())) {
            throw bad("已改期或已撤销的登记不能再编辑");
        }
        String planDate = existing.planDate();
        if (body.containsKey("planDate") && !text(body, "planDate").equals(existing.planDate())) {
            if (!PLANNED.equals(existing.status())) throw bad("已标记到/未到的登记请用「改期」调整日期");
            planDate = parseDate(text(body, "planDate"), "复查日期").toString();
        }
        String name = body.containsKey("patientName") ? requireName(text(body, "patientName")) : existing.patientName();
        String phone = body.containsKey("phone") ? normalizePhone(text(body, "phone")) : existing.phone();
        String note = body.containsKey("note") ? limit(text(body, "note"), 200, "备注") : existing.note();
        ensureNoDuplicate(planDate, name, phone, id);
        jdbcTemplate.update(
            "UPDATE clinic_recheck_schedule SET plan_date = ?, patient_name = ?, phone = ?, note = ?, updated_by = ?, updated_at = ? WHERE id = ?",
            planDate, name, phone, note, user.name(), now(), id);
        audit(user, "recheck.update", "编辑复查登记：" + name + " @ " + planDate);
        return get(id).toMap();
    }

    /** 标记到/未到/撤回为待到。 */
    public Map<String, Object> changeStatus(String id, Map<String, Object> body, SessionUser user) {
        requireEditor(user);
        Entry existing = get(id);
        String target = text(body, "status").toUpperCase();
        checkTransition(existing.status(), target, LocalDate.parse(existing.planDate()), LocalDate.now());
        String statusNote = ABSENT.equals(target) ? limit(text(body, "statusNote"), 200, "未到原因") : "";
        jdbcTemplate.update(
            "UPDATE clinic_recheck_schedule SET status = ?, status_note = ?, updated_by = ?, updated_at = ? WHERE id = ?",
            target, statusNote, user.name(), now(), id);
        audit(user, "recheck.status", existing.patientName() + " @ " + existing.planDate() + "：" + existing.status() + " → " + target);
        return get(id).toMap();
    }

    /** 改期：原条目保留为 RESCHEDULED（计入原日期的"改期"数），新日期生成 PLANNED。 */
    @Transactional
    public Map<String, Object> reschedule(String id, Map<String, Object> body, SessionUser user) {
        requireEditor(user);
        Entry existing = get(id);
        if (!PLANNED.equals(existing.status()) && !ABSENT.equals(existing.status())) {
            throw bad("只有待到或未到的登记可以改期");
        }
        LocalDate newDate = parseDate(text(body, "newDate"), "新复查日期");
        if (newDate.toString().equals(existing.planDate())) throw bad("新日期与原日期相同");
        String reason = limit(text(body, "reason"), 200, "改期原因");
        ensureNoDuplicate(newDate.toString(), existing.patientName(), existing.phone(), null);
        String newId = newId();
        String now = now();
        jdbcTemplate.update(
            "INSERT INTO clinic_recheck_schedule (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, '', ?, NULL, ?, ?, ?, ?)",
            newId, newDate.toString(), existing.patientName(), existing.phone(), existing.note(), PLANNED, id,
            user.name(), now, user.name(), now);
        jdbcTemplate.update(
            "UPDATE clinic_recheck_schedule SET status = ?, status_note = ?, rescheduled_to = ?, updated_by = ?, updated_at = ? WHERE id = ?",
            RESCHEDULED, reason, newDate.toString(), user.name(), now, id);
        audit(user, "recheck.reschedule", existing.patientName() + "：" + existing.planDate() + " → " + newDate + (reason.isEmpty() ? "" : "（" + reason + "）"));
        return get(newId).toMap();
    }

    /** 撤销（登记错误时使用）：软删，不进统计。 */
    public Map<String, Object> cancel(String id, SessionUser user) {
        requireEditor(user);
        Entry existing = get(id);
        if (RESCHEDULED.equals(existing.status())) throw bad("已改期的原登记不能撤销，请处理改期后的新登记");
        if (CANCELLED.equals(existing.status())) return existing.toMap();
        jdbcTemplate.update(
            "UPDATE clinic_recheck_schedule SET status = ?, updated_by = ?, updated_at = ? WHERE id = ?",
            CANCELLED, user.name(), now(), id);
        audit(user, "recheck.cancel", "撤销复查登记：" + existing.patientName() + " @ " + existing.planDate());
        return get(id).toMap();
    }

    // ---------- 规则（纯函数，便于单测） ----------

    static void checkTransition(String from, String to, LocalDate planDate, LocalDate today) {
        switch (to) {
            case ARRIVED, ABSENT -> {
                if (!PLANNED.equals(from) && !ARRIVED.equals(from) && !ABSENT.equals(from)) {
                    throw bad("已改期或已撤销的登记不能标记到/未到");
                }
                if (planDate.isAfter(today)) throw bad("复查日期未到，不能提前标记到/未到");
            }
            case PLANNED -> {
                if (!ARRIVED.equals(from) && !ABSENT.equals(from)) throw bad("只有已到/未到的登记可以撤回为待到");
            }
            default -> throw bad("未知状态：" + to);
        }
    }

    static LocalDate parseDate(String value, String label) {
        if (value == null || value.isBlank()) throw bad("请填写" + label);
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException error) {
            throw bad(label + "格式应为 yyyy-MM-dd");
        }
    }

    static String requireName(String value) {
        String name = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) throw bad("请填写患者姓名");
        if (name.length() > 50) throw bad("患者姓名过长");
        return name;
    }

    /** 仅保留数字与 +/-，空串允许（电话非必填）。 */
    static String normalizePhone(String value) {
        String phone = value == null ? "" : value.replaceAll("[^0-9+\\-]", "");
        if (phone.length() > 30) throw bad("电话号码过长");
        return phone;
    }

    static boolean canEdit(SessionUser user) {
        return user != null && EDIT_ROLES.contains(RoleCatalog.canonicalize(user.role()));
    }

    // ---------- internals ----------

    /** 同日同名同电话的有效登记视为重复（同名不同电话允许，避免误挡同名患者）。 */
    private void ensureNoDuplicate(String planDate, String name, String phone, String excludeId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM clinic_recheck_schedule WHERE plan_date = ? AND patient_name = ? AND phone = ? "
                + "AND status NOT IN (?, ?) AND id <> ?",
            Integer.class, planDate, name, phone, CANCELLED, RESCHEDULED, excludeId == null ? "" : excludeId);
        if (count != null && count > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, planDate + " 已登记过 " + name + (phone.isEmpty() ? "" : "（" + phone + "）"));
        }
    }

    private Entry get(String id) {
        List<Entry> rows = jdbcTemplate.query(
            "SELECT " + COLUMNS + " FROM clinic_recheck_schedule WHERE id = ?", (rs, rowNum) -> mapEntry(rs), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "复查登记不存在");
        return rows.get(0);
    }

    private static Entry mapEntry(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Entry(
            rs.getString("id"), rs.getString("plan_date"), rs.getString("patient_name"), nz(rs.getString("phone")),
            nz(rs.getString("note")), rs.getString("status"), nz(rs.getString("status_note")), rs.getString("source_id"),
            rs.getString("rescheduled_to"), rs.getString("created_by"), rs.getString("created_at"),
            rs.getString("updated_by"), rs.getString("updated_at"));
    }

    private void requireEditor(SessionUser user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        if (!canEdit(user)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前岗位只能查看复查登记");
    }

    private void audit(SessionUser user, String action, String detail) {
        try {
            encounterService.auditExternal(action, user, detail);
        } catch (Exception ignored) {
            // 审计失败不阻断主流程
        }
    }

    private static String limit(String value, int max, String label) {
        if (value.length() > max) throw bad(label + "不能超过 " + max + " 字");
        return value;
    }

    private static String text(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String newId() {
        return "rck-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String now() {
        return TIME.format(LocalDateTime.now());
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String weekdayLabel(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }

    record Entry(
        String id, String planDate, String patientName, String phone, String note, String status, String statusNote,
        String sourceId, String rescheduledTo, String createdBy, String createdAt, String updatedBy, String updatedAt
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("planDate", planDate);
            row.put("patientName", patientName);
            row.put("phone", phone);
            row.put("note", note);
            row.put("status", status);
            row.put("statusNote", statusNote);
            row.put("sourceId", sourceId);
            row.put("rescheduledTo", rescheduledTo);
            row.put("createdBy", createdBy);
            row.put("createdAt", createdAt);
            row.put("updatedBy", updatedBy);
            row.put("updatedAt", updatedAt);
            return row;
        }
    }

    /** 计划 = 待到 + 已到 + 未到（改期移出、撤销不计）；到诊率仅对已确认（到+未到）计算。 */
    static final class Stats {
        long planned;
        long arrived;
        long absent;
        long pending;
        long rescheduled;

        static Stats of(List<Entry> list) {
            Stats stats = new Stats();
            for (Entry entry : list) {
                switch (entry.status()) {
                    case ARRIVED -> stats.arrived++;
                    case ABSENT -> stats.absent++;
                    case PLANNED -> stats.pending++;
                    case RESCHEDULED -> stats.rescheduled++;
                    default -> { }
                }
            }
            stats.planned = stats.arrived + stats.absent + stats.pending;
            return stats;
        }

        void add(Stats other) {
            planned += other.planned;
            arrived += other.arrived;
            absent += other.absent;
            pending += other.pending;
            rescheduled += other.rescheduled;
        }

        Map<String, Object> toMap(boolean past) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("planned", planned);
            map.put("arrived", arrived);
            map.put("absent", absent);
            map.put("pending", pending);
            map.put("rescheduled", rescheduled);
            long confirmed = arrived + absent;
            map.put("arrivalRate", confirmed == 0 ? null : Math.round(arrived * 1000.0 / confirmed) / 10.0);
            map.put("unconfirmed", past ? pending : 0);
            return map;
        }
    }
}
