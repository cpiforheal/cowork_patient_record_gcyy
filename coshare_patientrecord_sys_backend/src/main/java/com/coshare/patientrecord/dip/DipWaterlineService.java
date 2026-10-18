package com.coshare.patientrecord.dip;

import com.coshare.patientrecord.auth.dto.SessionUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * DIP 费用预警（费用水位）：
 * - 列表字段尽量与 HIS 住院费用界面一致：姓名/性别/年龄/主诊断来自本系统，住院号/病人类型/床号/押金/护理级别来自 HIS 录入；
 * - 病组由主诊断自动匹配 dip_group_mapping，医生确定术式后可在 dip_group_selection 中改选；
 * - 费用水位 = 未结算费用 / 病组预估费用，≥85% 预警、≥95% 超限。
 */
@Service
@Profile("mysql")
public class DipWaterlineService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final BigDecimal RATIO_WARN = BigDecimal.valueOf(85);
    private static final BigDecimal RATIO_DANGER = BigDecimal.valueOf(95);

    private final JdbcTemplate jdbcTemplate;

    public DipWaterlineService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record GroupRule(
        String categoryCode,
        String categoryName,
        String groupCode,
        String groupName,
        BigDecimal estimatedFee,
        List<String> keywords,
        int keywordPriority,
        int sortNo
    ) {}

    private record FeeSnapshot(
        BigDecimal currentFee,
        String feeSource,
        String updatedBy,
        String updatedAt,
        String admissionNo,
        String patientType,
        String bedNo,
        String nursingLevel,
        BigDecimal depositTotal,
        BigDecimal depositBalance,
        String admissionDate,
        String attendingDoctor,
        String remark
    ) {}

    // ---------------- 病组映射 ----------------

    public List<GroupRule> loadRules() {
        return jdbcTemplate.query(
            "SELECT category_code, category_name, group_code, group_name, estimated_fee, match_keywords, keyword_priority, sort_no"
                + " FROM dip_group_mapping ORDER BY keyword_priority DESC, sort_no ASC",
            (rs, rowNum) -> new GroupRule(
                rs.getString("category_code"),
                rs.getString("category_name"),
                rs.getString("group_code"),
                rs.getString("group_name"),
                rs.getBigDecimal("estimated_fee"),
                splitKeywords(rs.getString("match_keywords")),
                rs.getInt("keyword_priority"),
                rs.getInt("sort_no")
            )
        );
    }

    private static List<String> splitKeywords(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> list = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) list.add(trimmed);
        }
        return List.copyOf(list);
    }

    GroupRule match(String diagnosisText, List<GroupRule> rules) {
        if (diagnosisText == null || diagnosisText.isBlank()) return null;
        String text = diagnosisText.replace(" ", "").toUpperCase(Locale.ROOT);
        GroupRule best = null;
        int bestLength = 0;
        for (GroupRule rule : rules) {
            for (String keyword : rule.keywords()) {
                String probe = keyword.replace(" ", "").toUpperCase(Locale.ROOT);
                if (probe.isEmpty() || !text.contains(probe)) continue;
                int length = probe.length();
                if (length > bestLength || (length == bestLength && best != null && rule.keywordPriority() > best.keywordPriority())) {
                    best = rule;
                    bestLength = length;
                }
            }
        }
        return best;
    }

    /** 病组候选清单：供医生改选时挑选（按大类分组排序）。 */
    public List<Map<String, Object>> groupOptions() {
        return jdbcTemplate.query(
            "SELECT category_code, category_name, group_code, group_name, estimated_fee FROM dip_group_mapping"
                + " ORDER BY category_code ASC, sort_no ASC",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("categoryCode", rs.getString("category_code"));
                row.put("categoryName", rs.getString("category_name"));
                row.put("groupCode", rs.getString("group_code"));
                row.put("groupName", rs.getString("group_name"));
                row.put("estimatedFee", rs.getBigDecimal("estimated_fee").setScale(2, RoundingMode.HALF_UP));
                return row;
            }
        );
    }

    // ---------------- 数据装载 ----------------

    private Map<String, FeeSnapshot> loadSnapshots() {
        Map<String, FeeSnapshot> map = new LinkedHashMap<>();
        jdbcTemplate.query(
            "SELECT patient_id, current_fee, fee_source, updated_by, updated_at, admission_no, patient_type,"
                + " bed_no, nursing_level, deposit_total, deposit_balance, admission_date, attending_doctor, remark"
                + " FROM dip_fee_snapshot",
            rs -> {
                map.put(
                    rs.getString("patient_id"),
                    new FeeSnapshot(
                        rs.getBigDecimal("current_fee"),
                        rs.getString("fee_source"),
                        rs.getString("updated_by"),
                        rs.getString("updated_at"),
                        rs.getString("admission_no"),
                        rs.getString("patient_type"),
                        rs.getString("bed_no"),
                        rs.getString("nursing_level"),
                        rs.getBigDecimal("deposit_total"),
                        rs.getBigDecimal("deposit_balance"),
                        rs.getString("admission_date"),
                        rs.getString("attending_doctor"),
                        rs.getString("remark")
                    )
                );
            }
        );
        return map;
    }

    private Map<String, String> loadGroupSelections() {
        Map<String, String> map = new LinkedHashMap<>();
        jdbcTemplate.query(
            "SELECT patient_id, group_code FROM dip_group_selection",
            rs -> {
                map.put(rs.getString("patient_id"), rs.getString("group_code"));
            }
        );
        return map;
    }

    /**
     * 每位患者最新一次就诊的西医主诊断，同时带出 HIS 侧缺失的本系统字段：
     * 性别取自 raw_json，年龄取自 patient_json（可能是出生日期或纯数字）。
     */
    private List<Map<String, Object>> loadLatestPrimaryDiagnoses() {
        return jdbcTemplate.query(
            "SELECT p.id AS patient_id, p.name AS patient_name, p.visit_date,"
                + " JSON_UNQUOTE(JSON_EXTRACT(p.raw_json, '$.gender')) AS gender,"
                + " JSON_UNQUOTE(JSON_EXTRACT(p.raw_json, '$.doctor')) AS patient_doctor,"
                + " JSON_UNQUOTE(JSON_EXTRACT(e.patient_json, '$.age')) AS age_raw,"
                + " e.id AS encounter_id, e.status AS encounter_status, e.updated_at AS encounter_updated_at,"
                + " d.diagnosis_text"
                + " FROM clinic_patients p"
                + " JOIN pre_ai_encounters e ON e.source_patient_id = p.id AND e.status <> 'CANCELLED'"
                + " JOIN pre_ai_diagnoses d ON d.encounter_id = e.id AND d.diagnosis_type = 'WESTERN_PRIMARY'"
                + " WHERE NOT EXISTS ("
                + "   SELECT 1 FROM pre_ai_encounters e2"
                + "   JOIN pre_ai_diagnoses d2 ON d2.encounter_id = e2.id AND d2.diagnosis_type = 'WESTERN_PRIMARY'"
                + "   WHERE e2.source_patient_id = p.id AND e2.status <> 'CANCELLED' AND e2.updated_at > e.updated_at"
                + " )"
                + " ORDER BY e.updated_at DESC",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("patientId", rs.getString("patient_id"));
                row.put("patientName", rs.getString("patient_name"));
                row.put("gender", blankToDash(rs.getString("gender")));
                row.put("patientDoctor", rs.getString("patient_doctor"));
                row.put("age", parseAge(rs.getString("age_raw")));
                row.put("visitDate", rs.getString("visit_date"));
                row.put("encounterId", rs.getString("encounter_id"));
                row.put("encounterUpdatedAt", rs.getString("encounter_updated_at"));
                row.put("diagnosisText", rs.getString("diagnosis_text"));
                return row;
            }
        );
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    /** patient_json.age 可能是出生日期（1989-07-26）也可能是纯数字（56）。 */
    static Integer parseAge(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            try {
                LocalDate birth = LocalDate.parse(value.substring(0, 10));
                int years = Period.between(birth, LocalDate.now()).getYears();
                return years >= 0 && years < 130 ? years : null;
            } catch (RuntimeException ex) {
                return null;
            }
        }
        if (value.matches("\\d{1,3}")) {
            try {
                int years = Integer.parseInt(value);
                return years >= 0 && years < 130 ? years : null;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static Integer hospitalDays(String admissionDate) {
        if (admissionDate == null || admissionDate.isBlank()) return null;
        try {
            LocalDate admitted = LocalDate.parse(admissionDate.trim().substring(0, 10));
            long total = admitted.until(LocalDate.now()).getDays() + 1;
            return total > 0 && total < 3650 ? (int) total : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ---------------- 看板 ----------------

    @Transactional(readOnly = true)
    public Map<String, Object> board(SessionUser user) {
        List<GroupRule> rules = loadRules();
        Map<String, GroupRule> ruleByCode = new LinkedHashMap<>();
        rules.forEach(rule -> ruleByCode.put(rule.groupCode(), rule));

        Map<String, FeeSnapshot> snapshots = loadSnapshots();
        Map<String, String> selections = loadGroupSelections();
        List<Map<String, Object>> diagnoses = loadLatestPrimaryDiagnoses();

        List<Map<String, Object>> patients = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        int normal = 0;
        int warning = 0;
        int danger = 0;
        int pending = 0;
        int unmatched = 0;
        BigDecimal overrunTotal = BigDecimal.ZERO;

        for (Map<String, Object> row : diagnoses) {
            String patientId = (String) row.get("patientId");
            if (patientId == null || !seen.add(patientId)) continue;

            String diagnosisText = (String) row.get("diagnosisText");
            GroupRule autoRule = match(diagnosisText, rules);
            String selectedCode = selections.get(patientId);
            GroupRule selectedRule = selectedCode == null ? null : ruleByCode.get(selectedCode);
            GroupRule rule = selectedRule != null ? selectedRule : autoRule;
            FeeSnapshot snapshot = snapshots.get(patientId);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("patientId", patientId);
            item.put("patientName", row.get("patientName"));
            item.put("gender", row.get("gender"));
            item.put("age", row.get("age"));
            item.put("diagnosis", diagnosisText);
            item.put("matched", rule != null);
            item.put("groupSource", selectedRule != null ? "MANUAL" : "AUTO");
            item.put("autoGroupCode", autoRule == null ? null : autoRule.groupCode());
            item.put("autoGroupName", autoRule == null ? null : autoRule.groupName());

            String attendingDoctor = snapshot != null && !snapshot.attendingDoctor().isBlank()
                ? snapshot.attendingDoctor()
                : blankToDash((String) row.get("patientDoctor"));
            item.put("attendingDoctor", attendingDoctor);

            String admissionDate = snapshot != null && !snapshot.admissionDate().isBlank()
                ? snapshot.admissionDate()
                : nullToEmpty((String) row.get("visitDate"));
            item.put("admissionDate", admissionDate);
            item.put("hospitalDays", hospitalDays(admissionDate));

            // HIS 侧字段（来自录入）
            item.put("admissionNo", snapshot == null ? "" : nullToEmpty(snapshot.admissionNo()));
            item.put("patientType", snapshot == null ? "" : nullToEmpty(snapshot.patientType()));
            item.put("bedNo", snapshot == null ? "" : nullToEmpty(snapshot.bedNo()));
            item.put("nursingLevel", snapshot == null ? "" : nullToEmpty(snapshot.nursingLevel()));
            item.put("depositTotal", snapshot == null ? null : snapshot.depositTotal());
            item.put("depositBalance", snapshot == null ? null : snapshot.depositBalance());

            if (rule == null) {
                unmatched++;
                item.put("status", "UNMATCHED");
                item.put("waterline", null);
                patients.add(item);
                continue;
            }

            BigDecimal estimatedFee = rule.estimatedFee().setScale(2, RoundingMode.HALF_UP);
            BigDecimal threshold85 = estimatedFee.multiply(RATIO_WARN).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal threshold95 = estimatedFee.multiply(RATIO_DANGER).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            item.put("categoryCode", rule.categoryCode());
            item.put("categoryName", rule.categoryName());
            item.put("groupCode", rule.groupCode());
            item.put("groupName", rule.groupName());
            item.put("estimatedFee", estimatedFee);
            item.put("threshold85", threshold85);
            item.put("threshold95", threshold95);

            if (snapshot == null) {
                pending++;
                item.put("status", "PENDING_FEE");
                item.put("waterline", null);
                patients.add(item);
                continue;
            }

            BigDecimal currentFee = snapshot.currentFee().setScale(2, RoundingMode.HALF_UP);
            BigDecimal ratio = estimatedFee.signum() == 0
                ? BigDecimal.ZERO
                : currentFee.multiply(BigDecimal.valueOf(100)).divide(estimatedFee, 2, RoundingMode.HALF_UP);

            String status;
            if (ratio.compareTo(RATIO_DANGER) >= 0) {
                status = "DANGER";
                danger++;
                if (currentFee.compareTo(threshold95) > 0) overrunTotal = overrunTotal.add(currentFee.subtract(threshold95));
            } else if (ratio.compareTo(RATIO_WARN) >= 0) {
                status = "WARNING";
                warning++;
            } else {
                status = "NORMAL";
                normal++;
            }

            item.put("status", status);
            item.put("unsettledFee", currentFee);
            item.put("restTo95", threshold95.subtract(currentFee).setScale(2, RoundingMode.HALF_UP));
            item.put("feeSource", snapshot.feeSource());
            item.put("feeUpdatedBy", snapshot.updatedBy());
            item.put("feeUpdatedAt", snapshot.updatedAt());

            Map<String, Object> waterline = new LinkedHashMap<>();
            waterline.put("ratio", ratio);
            waterline.put("level", status.toLowerCase(Locale.ROOT));
            item.put("waterline", waterline);

            patients.add(item);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", patients.size());
        summary.put("normal", normal);
        summary.put("warning", warning);
        summary.put("danger", danger);
        summary.put("pendingFee", pending);
        summary.put("unmatched", unmatched);
        summary.put("overrunTotal", overrunTotal.setScale(2, RoundingMode.HALF_UP));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patients", patients);
        result.put("summary", summary);
        result.put("generatedAt", LocalDateTime.now().format(TIME));
        return result;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ---------------- 写入 ----------------

    @Transactional
    public Map<String, Object> saveFee(Map<String, Object> body, SessionUser user) {
        String patientId = str(body.get("patientId"));
        if (patientId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者标识");
        BigDecimal currentFee = dec(body.get("unsettledFee"), "未结算费用");
        if (currentFee == null || currentFee.signum() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未结算费用必须为非负数");
        ensurePatient(patientId);

        String source = "SCREENSHOT".equalsIgnoreCase(str(body.get("source"))) ? "SCREENSHOT" : "MANUAL";
        String now = LocalDateTime.now().format(TIME);
        String operator = operator(user);

        jdbcTemplate.update(
            "INSERT INTO dip_fee_snapshot (patient_id, current_fee, fee_source, updated_by, updated_at, admission_no,"
                + " patient_type, bed_no, nursing_level, deposit_total, deposit_balance, admission_date, attending_doctor, remark)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE current_fee = VALUES(current_fee), fee_source = VALUES(fee_source),"
                + " updated_by = VALUES(updated_by), updated_at = VALUES(updated_at), admission_no = VALUES(admission_no),"
                + " patient_type = VALUES(patient_type), bed_no = VALUES(bed_no), nursing_level = VALUES(nursing_level),"
                + " deposit_total = VALUES(deposit_total), deposit_balance = VALUES(deposit_balance),"
                + " admission_date = VALUES(admission_date), attending_doctor = VALUES(attending_doctor), remark = VALUES(remark)",
            patientId,
            currentFee.setScale(2, RoundingMode.HALF_UP),
            source,
            operator,
            now,
            str(body.get("admissionNo")),
            str(body.get("patientType")),
            str(body.get("bedNo")),
            str(body.get("nursingLevel")),
            dec(body.get("depositTotal"), "押金总额"),
            dec(body.get("depositBalance"), "押金余额"),
            str(body.get("admissionDate")),
            str(body.get("attendingDoctor")),
            str(body.get("remark"))
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", patientId);
        payload.put("unsettledFee", currentFee.setScale(2, RoundingMode.HALF_UP));
        payload.put("updatedAt", now);
        return payload;
    }

    /** 医生改选病组；groupCode 为空表示恢复主诊断自动匹配。 */
    @Transactional
    public Map<String, Object> saveGroupSelection(Map<String, Object> body, SessionUser user) {
        String patientId = str(body.get("patientId"));
        if (patientId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者标识");
        ensurePatient(patientId);
        String groupCode = str(body.get("groupCode"));
        String note = str(body.get("note"));

        if (groupCode.isBlank()) {
            jdbcTemplate.update("DELETE FROM dip_group_selection WHERE patient_id = ?", patientId);
        } else {
            Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dip_group_mapping WHERE group_code = ?", Integer.class, groupCode);
            if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "病组编码不存在");
            jdbcTemplate.update(
                "INSERT INTO dip_group_selection (patient_id, group_code, selection_note, updated_by, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE group_code = VALUES(group_code), selection_note = VALUES(selection_note),"
                    + " updated_by = VALUES(updated_by), updated_at = VALUES(updated_at)",
                patientId, groupCode, note, operator(user), LocalDateTime.now().format(TIME)
            );
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", patientId);
        payload.put("groupCode", groupCode);
        return payload;
    }

    private void ensurePatient(String patientId) {
        Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM clinic_patients WHERE id = ?", Integer.class, patientId);
        if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "患者不存在");
    }

    private static String operator(SessionUser user) {
        if (user == null) return "";
        String name = user.name();
        if (name != null && !name.isBlank()) return name;
        return user.username() == null ? "" : user.username();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static BigDecimal dec(Object value, String label) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return null;
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "格式不正确");
        }
    }
}
