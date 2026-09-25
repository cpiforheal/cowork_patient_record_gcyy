package com.coshare.patientrecord.clinic.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 运营数据看板：面向管理层的轻量聚合，回答"门诊量有没有变化、随访闭环跑到哪一步"。
 *
 * 设计取舍：
 *  - 全部为 COUNT / GROUP BY 聚合，**不读取任何患者隐私字段**，与 HomeSummaryService 定位一致。
 *  - 只做"来访量"与"随访闭环"两类真实可度量的指标。
 *    刻意**不做**"新诊 vs 复诊"构成图：当前数据 254 位患者中 253 位只来过 1 次，
 *    画出来全是 0，属于自欺欺人；等复诊数据积累后再开。
 */
@Service
@Profile("mysql")
public class OpsDashboardService {

    private static final Logger log = LoggerFactory.getLogger(OpsDashboardService.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final Set<String> VIEW_ROLES =
        Set.of("admin", "doctor", "nurse", "nursing", "inspection", "quality");

    private final JdbcTemplate jdbcTemplate;

    public OpsDashboardService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> dashboard(int months, SessionUser user) {
        requireViewRole(user);
        int span = Math.min(Math.max(months, 3), 24);
        YearMonth current = YearMonth.from(LocalDate.now());
        YearMonth from = current.minusMonths(span - 1L);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        result.put("from", from.format(MONTH));
        result.put("to", current.format(MONTH));
        result.put("kpi", kpi(current));
        result.put("trend", trend(from, current));
        result.put("statusDistribution", statusDistribution());
        result.put("followUp", followUp());
        result.put("departments", departments(from, current));
        result.put("monthlyPatients", monthlyPatients(from, current));
        return result;
    }

    // ------------------------------------------------------------ KPI

    /** 关键数字：每项都带环比，让"差异"直接可见。 */
    private Map<String, Object> kpi(YearMonth current) {
        int thisMonth = visitsOf(current);
        int lastMonth = visitsOf(current.minusMonths(1));
        Map<String, Object> visits = metric(thisMonth, lastMonth);

        Map<String, Object> followUpStats = followUp();
        int dueTotal = number(followUpStats.get("dueTotal"));
        int arrived = number(followUpStats.get("arrived"));
        int overdue = number(followUpStats.get("overdue"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("visitsThisMonth", visits);
        result.put("visitsLastMonth", lastMonth);
        result.put("followUpDue", dueTotal);
        result.put("followUpArrived", arrived);
        result.put("followUpOverdue", overdue);
        result.put("followUpArrivalRate", percent(arrived, dueTotal));
        // 患者总数与本月新增病例，反映"病源池"是否在扩大
        result.put("patientCases", scalar("SELECT COUNT(*) FROM pre_ai_patient_cases"));
        result.put("newCasesThisMonth", scalar(
            "SELECT COUNT(*) FROM pre_ai_patient_cases WHERE created_at LIKE ?", current.format(MONTH) + "%"));
        return result;
    }

    /** 环比：上月为 0 时不做除法，返回 null 让前端显示"—"而不是 Infinity。 */
    private Map<String, Object> metric(int current, int previous) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("current", current);
        value.put("previous", previous);
        if (previous <= 0) {
            value.put("deltaRate", null);
            value.put("delta", current - previous);
            return value;
        }
        value.put("delta", current - previous);
        value.put("deltaRate", Math.round((current - previous) * 1000f / previous) / 10.0);
        return value;
    }

    // ---------------------------------------------------------- trend

    /** 按月来访量趋势（排除已取消）。 */
    private List<Map<String, Object>> trend(YearMonth from, YearMonth to) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbcTemplate.query(
            "SELECT LEFT(created_at, 7) AS ym, COUNT(*) AS cnt FROM pre_ai_encounters "
                + "WHERE status <> 'CANCELLED' AND LEFT(created_at, 7) >= ? GROUP BY ym",
            (RowCallbackHandler) rs -> counts.put(rs.getString("ym"), rs.getInt("cnt")),
            from.format(MONTH));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (YearMonth cursor = from; !cursor.isAfter(to); cursor = cursor.plusMonths(1)) {
            String key = cursor.format(MONTH);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", key);
            row.put("visits", counts.getOrDefault(key, 0));
            rows.add(row);
        }
        return rows;
    }

    /** 每月"接诊患者数"（按病例去重），与"来访次数"区分：一个患者可能多次来访。 */
    private List<Map<String, Object>> monthlyPatients(YearMonth from, YearMonth to) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbcTemplate.query(
            "SELECT LEFT(created_at, 7) AS ym, COUNT(DISTINCT patient_case_id) AS cnt FROM pre_ai_encounters "
                + "WHERE status <> 'CANCELLED' AND LEFT(created_at, 7) >= ? GROUP BY ym",
            (RowCallbackHandler) rs -> counts.put(rs.getString("ym"), rs.getInt("cnt")),
            from.format(MONTH));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (YearMonth cursor = from; !cursor.isAfter(to); cursor = cursor.plusMonths(1)) {
            String key = cursor.format(MONTH);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", key);
            row.put("patients", counts.getOrDefault(key, 0));
            rows.add(row);
        }
        return rows;
    }

    // ----------------------------------------------- status / follow-up

    private List<Map<String, Object>> statusDistribution() {
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbcTemplate.query(
            "SELECT status, COUNT(*) AS cnt FROM pre_ai_encounters GROUP BY status ORDER BY cnt DESC",
            (RowCallbackHandler) rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("status", rs.getString("status"));
                row.put("label", statusLabel(rs.getString("status")));
                row.put("count", rs.getInt("cnt"));
                rows.add(row);
            });
        return rows;
    }

    /** 随访闭环：应随访 → 已触达 → 已回院，并给出逾期/今日/未到期拆分。 */
    private Map<String, Object> followUp() {
        String today = LocalDate.now().toString();
        Map<String, Object> result = new LinkedHashMap<>();
        int dueTotal = scalar("SELECT COUNT(*) FROM pre_ai_follow_up_visits "
            + "WHERE next_review_date IS NOT NULL AND next_review_date <> ''");
        int notScheduled = scalar("SELECT COUNT(*) FROM pre_ai_follow_up_visits "
            + "WHERE next_review_date IS NULL OR next_review_date = ''");
        int arrived = scalar("SELECT COUNT(*) FROM pre_ai_follow_up_visits "
            + "WHERE arrived_at IS NOT NULL AND arrived_at <> ''");
        int overdue = scalar("SELECT COUNT(*) FROM pre_ai_follow_up_visits "
            + "WHERE next_review_date IS NOT NULL AND next_review_date <> '' AND next_review_date < ? "
            + "AND (arrived_at IS NULL OR arrived_at = '')", today);
        int dueToday = scalar("SELECT COUNT(*) FROM pre_ai_follow_up_visits "
            + "WHERE next_review_date = ? AND (arrived_at IS NULL OR arrived_at = '')", today);
        int upcoming = Math.max(0, dueTotal - arrived - overdue - dueToday);
        int reached = touchedCount();

        result.put("dueTotal", dueTotal);
        result.put("notScheduled", notScheduled);
        result.put("reached", reached);
        result.put("arrived", arrived);
        result.put("overdue", overdue);
        result.put("dueToday", dueToday);
        result.put("upcoming", upcoming);
        result.put("reachRate", percent(reached, dueTotal));
        result.put("arrivalRate", percent(arrived, dueTotal));
        return result;
    }

    /**
     * 已触达数：沿用 followup.recall.contact 审计事件，
     * 与随访工作台/监控台的触达口径保持一致（避免同一指标两套算法）。
     */
    private int touchedCount() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(DISTINCT SUBSTRING_INDEX(SUBSTRING_INDEX(detail, 'recall:', -1), ' ', 1)) "
                + "FROM pre_ai_audit_logs WHERE action = 'followup.recall.contact'",
            Integer.class);
        return count == null ? 0 : count;
    }

    // ----------------------------------------------------- departments

    private List<Map<String, Object>> departments(YearMonth from, YearMonth to) {
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbcTemplate.query(
            "SELECT COALESCE(NULLIF(owning_department_name_snapshot, ''), '未归属科室') AS dept, COUNT(*) AS cnt "
                + "FROM pre_ai_encounters WHERE status <> 'CANCELLED' AND LEFT(created_at, 7) >= ? "
                + "GROUP BY dept ORDER BY cnt DESC LIMIT 12",
            (RowCallbackHandler) rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("department", rs.getString("dept"));
                row.put("count", rs.getInt("cnt"));
                rows.add(row);
            },
            from.format(MONTH));
        return rows;
    }

    // ------------------------------------------------------------ utils

    private void requireViewRole(SessionUser user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        if (!VIEW_ROLES.contains(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无权查看运营数据看板");
        }
    }

    private int visitsOf(YearMonth month) {
        return scalar("SELECT COUNT(*) FROM pre_ai_encounters WHERE status <> 'CANCELLED' AND LEFT(created_at, 7) = ?",
            month.format(MONTH));
    }

    private int scalar(String sql, Object... args) {
        try {
            Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
            return value == null ? 0 : value;
        } catch (Exception error) {
            // 看板是只读聚合，单块失败不应让整页 500；但要留日志便于排查
            log.warn("运营看板聚合查询失败: {}", sql, error);
            return 0;
        }
    }

    private int number(Object value) {
        return value instanceof Number numeric ? numeric.intValue() : 0;
    }

    private int percent(int numerator, int denominator) {
        return denominator <= 0 ? 0 : Math.round(numerator * 100f / denominator);
    }

    private String statusLabel(String status) {
        if (status == null) return "未知";
        return switch (status) {
            case "IN_PROGRESS" -> "进行中";
            case "EXPORTED" -> "已导出";
            case "PENDING_REVIEW" -> "待复核";
            case "REVIEWED" -> "已复核";
            case "CANCELLED" -> "已取消";
            case "WITHDRAWN" -> "已作废";
            default -> status;
        };
    }
}
