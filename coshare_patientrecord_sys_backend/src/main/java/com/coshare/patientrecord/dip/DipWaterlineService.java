package com.coshare.patientrecord.dip;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.preai.PreAiEncounterService;
import com.coshare.patientrecord.security.AuthPermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.UUID;
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
 * - 病组费用双轨：患者级 fee_override 优先，否则使用病组目录标准费用；
 * - 费用水位 = 未结算费用 / 有效病组费用，≥85% 预警、≥95% 超限。
 */
@Service
@Profile("mysql")
public class DipWaterlineService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final BigDecimal RATIO_WARN = BigDecimal.valueOf(85);
    private static final BigDecimal RATIO_DANGER = BigDecimal.valueOf(95);

    private final JdbcTemplate jdbcTemplate;
    private final PreAiEncounterService preAiEncounterService;
    private final DipChangeLogService changeLogService;
    private final ObjectMapper objectMapper;

    public DipWaterlineService(
        JdbcTemplate jdbcTemplate,
        PreAiEncounterService preAiEncounterService,
        DipChangeLogService changeLogService,
        ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.preAiEncounterService = preAiEncounterService;
        this.changeLogService = changeLogService;
        this.objectMapper = objectMapper;
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

    private record GroupSelection(String groupCode, BigDecimal feeOverride) {}

    private record FeeSnapshot(
        BigDecimal currentFee,
        String recordStatus,
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
        String remark,
        String voidedAt,
        String voidedBy,
        String voidReason
    ) {}

    // ---------------- 病组映射 ----------------

    public List<GroupRule> loadRules() {
        return jdbcTemplate.query(
            "SELECT category_code, category_name, group_code, group_name, estimated_fee, match_keywords, keyword_priority, sort_no"
                + " FROM dip_group_mapping WHERE status = 'ACTIVE' ORDER BY keyword_priority DESC, sort_no ASC",
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

    /** 病组候选清单：供医生改选时挑选（按大类分组排序，只含启用病组）。 */
    public List<Map<String, Object>> groupOptions() {
        return jdbcTemplate.query(
            "SELECT category_code, category_name, group_code, group_name, estimated_fee FROM dip_group_mapping"
                + " WHERE status = 'ACTIVE' ORDER BY category_code ASC, sort_no ASC",
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
            "SELECT patient_id, current_fee, record_status, fee_source, updated_by, updated_at, admission_no, patient_type,"
                + " bed_no, nursing_level, deposit_total, deposit_balance, admission_date, attending_doctor, remark,"
                + " voided_at, voided_by, void_reason"
                + " FROM dip_fee_snapshot",
            rs -> {
                map.put(
                    rs.getString("patient_id"),
                    new FeeSnapshot(
                        rs.getBigDecimal("current_fee"),
                        rs.getString("record_status"),
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
                        rs.getString("remark"),
                        rs.getString("voided_at"),
                        rs.getString("voided_by"),
                        rs.getString("void_reason")
                    )
                );
            }
        );
        return map;
    }

    private Map<String, GroupSelection> loadGroupSelections() {
        Map<String, GroupSelection> map = new LinkedHashMap<>();
        jdbcTemplate.query(
            "SELECT patient_id, group_code, fee_override FROM dip_group_selection",
            rs -> {
                map.put(
                    rs.getString("patient_id"),
                    new GroupSelection(rs.getString("group_code"), rs.getBigDecimal("fee_override"))
                );
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
        Map<String, GroupSelection> selections = loadGroupSelections();
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
            // 医生视角只看自己科室 / 岗位分配 / 显式授权的病例；管理员与质控保持全量。
            if (!preAiEncounterService.canReadEncounterScoped((String) row.get("encounterId"), user)) continue;

            String diagnosisText = (String) row.get("diagnosisText");
            GroupRule autoRule = match(diagnosisText, rules);
            GroupSelection selection = selections.get(patientId);
            GroupRule selectedRule = selection == null ? null : ruleByCode.get(selection.groupCode());
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

            boolean feeVoided = snapshot != null && "VOIDED".equalsIgnoreCase(snapshot.recordStatus());
            item.put("feeVoided", feeVoided);
            item.put("voidedAt", feeVoided ? nullToEmpty(snapshot.voidedAt()) : "");
            item.put("voidedBy", feeVoided ? nullToEmpty(snapshot.voidedBy()) : "");
            item.put("voidReason", feeVoided ? nullToEmpty(snapshot.voidReason()) : "");

            if (rule == null) {
                unmatched++;
                item.put("status", "UNMATCHED");
                item.put("waterline", null);
                patients.add(item);
                continue;
            }

            BigDecimal groupStandardFee = rule.estimatedFee().setScale(2, RoundingMode.HALF_UP);
            BigDecimal feeOverride = selectedRule == null ? null : selection.feeOverride();
            BigDecimal estimatedFee = feeOverride == null
                ? groupStandardFee
                : feeOverride.setScale(2, RoundingMode.HALF_UP);
            BigDecimal threshold85 = estimatedFee.multiply(RATIO_WARN).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal threshold95 = estimatedFee.multiply(RATIO_DANGER).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            item.put("categoryCode", rule.categoryCode());
            item.put("categoryName", rule.categoryName());
            item.put("groupCode", rule.groupCode());
            item.put("groupName", rule.groupName());
            item.put("groupStandardFee", groupStandardFee);
            item.put("feeOverride", feeOverride == null ? null : feeOverride.setScale(2, RoundingMode.HALF_UP));
            item.put("feeSourceType", feeOverride == null ? "GROUP" : "PATIENT");
            item.put("estimatedFee", estimatedFee);
            item.put("threshold85", threshold85);
            item.put("threshold95", threshold95);

            if (snapshot == null || feeVoided) {
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
        ensurePatientAccess(patientId, user);

        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
            "SELECT current_fee, record_status FROM dip_fee_snapshot WHERE patient_id = ?", patientId);
        BigDecimal beforeFee = null;
        if (!existing.isEmpty()) {
            Object rawFee = existing.get(0).get("current_fee");
            if (rawFee != null && !"VOIDED".equalsIgnoreCase(str(existing.get(0).get("record_status")))) {
                beforeFee = new BigDecimal(String.valueOf(rawFee)).setScale(2, RoundingMode.HALF_UP);
            }
        }

        String source = "SCREENSHOT".equalsIgnoreCase(str(body.get("source"))) ? "SCREENSHOT" : "MANUAL";
        String now = LocalDateTime.now().format(TIME);
        String operator = operator(user);
        BigDecimal scaledFee = currentFee.setScale(2, RoundingMode.HALF_UP);

        jdbcTemplate.update(
            "INSERT INTO dip_fee_snapshot (patient_id, current_fee, record_status, fee_source, updated_by, updated_at, admission_no,"
                + " patient_type, bed_no, nursing_level, deposit_total, deposit_balance, admission_date, attending_doctor, remark,"
                + " voided_at, voided_by, void_reason)"
                + " VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '', '', '')"
                + " ON DUPLICATE KEY UPDATE current_fee = VALUES(current_fee), record_status = 'ACTIVE',"
                + " fee_source = VALUES(fee_source), updated_by = VALUES(updated_by), updated_at = VALUES(updated_at),"
                + " admission_no = VALUES(admission_no), patient_type = VALUES(patient_type), bed_no = VALUES(bed_no),"
                + " nursing_level = VALUES(nursing_level), deposit_total = VALUES(deposit_total),"
                + " deposit_balance = VALUES(deposit_balance), admission_date = VALUES(admission_date),"
                + " attending_doctor = VALUES(attending_doctor), remark = VALUES(remark),"
                + " voided_at = '', voided_by = '', void_reason = ''",
            patientId,
            scaledFee,
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

        changeLogService.record(
            patientId,
            "FEE",
            currentGroupCode(patientId),
            beforeFee == null ? "" : beforeFee.toPlainString(),
            scaledFee.toPlainString(),
            str(body.get("remark")),
            user
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", patientId);
        payload.put("unsettledFee", scaledFee);
        payload.put("updatedAt", now);
        return payload;
    }

    /** 医生改选病组；groupCode 为空表示恢复主诊断自动匹配，并清除患者级费用覆盖。 */
    @Transactional
    public Map<String, Object> saveGroupSelection(Map<String, Object> body, SessionUser user) {
        String patientId = str(body.get("patientId"));
        if (patientId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者标识");
        ensurePatientAccess(patientId, user);
        String groupCode = str(body.get("groupCode"));
        String note = str(body.get("note"));

        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
            "SELECT group_code, fee_override FROM dip_group_selection WHERE patient_id = ?", patientId);
        String previousCode = existing.isEmpty() ? "" : str(existing.get(0).get("group_code"));
        Object rawOverride = existing.isEmpty() ? null : existing.get(0).get("fee_override");
        BigDecimal previousOverride = rawOverride == null ? null : new BigDecimal(String.valueOf(rawOverride));

        if (groupCode.isBlank()) {
            if (previousCode.isBlank()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("patientId", patientId);
                payload.put("groupCode", "");
                return payload;
            }
            jdbcTemplate.update("DELETE FROM dip_group_selection WHERE patient_id = ?", patientId);
            changeLogService.record(
                patientId,
                "GROUP",
                previousCode,
                previousCode,
                "主诊断自动匹配",
                previousOverride == null ? note : "恢复自动匹配，同时清除患者级费用调整",
                user
            );
        } else {
            Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dip_group_mapping WHERE group_code = ? AND status = 'ACTIVE'",
                Integer.class,
                groupCode
            );
            if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "病组编码不存在或已停用");
            BigDecimal keepOverride = previousCode.equals(groupCode) ? previousOverride : null;
            jdbcTemplate.update(
                "INSERT INTO dip_group_selection (patient_id, group_code, selection_note, updated_by, updated_at, fee_override)"
                    + " VALUES (?, ?, ?, ?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE group_code = VALUES(group_code), selection_note = VALUES(selection_note),"
                    + " updated_by = VALUES(updated_by), updated_at = VALUES(updated_at), fee_override = VALUES(fee_override)",
                patientId,
                groupCode,
                note,
                operator(user),
                LocalDateTime.now().format(TIME),
                keepOverride
            );
            changeLogService.record(
                patientId,
                "GROUP",
                groupCode,
                previousCode.isBlank() ? "主诊断自动匹配" : previousCode,
                groupCode,
                note,
                user
            );
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", patientId);
        payload.put("groupCode", groupCode);
        return payload;
    }

    /** 患者级病组费用覆盖；传空表示恢复病组目录标准费用。 */
    @Transactional
    public Map<String, Object> saveGroupFeeOverride(Map<String, Object> body, SessionUser user) {
        String patientId = str(body.get("patientId"));
        if (patientId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者标识");
        ensurePatientAccess(patientId, user);
        BigDecimal override = dec(body.get("estimatedFeeOverride"), "病组费用");
        if (override != null && override.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "病组费用必须大于 0");
        }
        String groupCode = effectiveGroupCode(patientId);
        if (groupCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该患者尚未匹配病组，无法调整病组费用");
        }

        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
            "SELECT fee_override FROM dip_group_selection WHERE patient_id = ?", patientId);
        Object rawPrevious = existing.isEmpty() ? null : existing.get(0).get("fee_override");
        BigDecimal previousOverride = rawPrevious == null ? null : new BigDecimal(String.valueOf(rawPrevious));
        String beforeValue = previousOverride == null ? "病组目录标准" : previousOverride.setScale(2, RoundingMode.HALF_UP).toPlainString();
        String note = str(body.get("note"));

        if (override == null) {
            if (existing.isEmpty()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("patientId", patientId);
                payload.put("estimatedFeeOverride", null);
                return payload;
            }
            jdbcTemplate.update("UPDATE dip_group_selection SET fee_override = NULL WHERE patient_id = ?", patientId);
            changeLogService.record(patientId, "GROUP_FEE", groupCode, beforeValue, "病组目录标准", note, user);
        } else {
            BigDecimal scaled = override.setScale(2, RoundingMode.HALF_UP);
            jdbcTemplate.update(
                "INSERT INTO dip_group_selection (patient_id, group_code, selection_note, updated_by, updated_at, fee_override)"
                    + " VALUES (?, ?, '', ?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE group_code = VALUES(group_code), fee_override = VALUES(fee_override),"
                    + " updated_by = VALUES(updated_by), updated_at = VALUES(updated_at)",
                patientId,
                groupCode,
                operator(user),
                LocalDateTime.now().format(TIME),
                scaled
            );
            changeLogService.record(patientId, "GROUP_FEE", groupCode, beforeValue, scaled.toPlainString(), note, user);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", patientId);
        payload.put("estimatedFeeOverride", override == null ? null : override.setScale(2, RoundingMode.HALF_UP));
        return payload;
    }

    /** 作废当前费用记录（软作废，可重新录入）。 */
    @Transactional
    public Map<String, Object> voidFee(Map<String, Object> body, SessionUser user) {
        String patientId = str(body.get("patientId"));
        if (patientId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者标识");
        String reason = str(body.get("reason"));
        if (reason.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写作废原因");
        ensurePatientAccess(patientId, user);

        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
            "SELECT current_fee, record_status FROM dip_fee_snapshot WHERE patient_id = ?", patientId);
        if (existing.isEmpty() || "VOIDED".equalsIgnoreCase(str(existing.get(0).get("record_status")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未找到可作废的费用记录");
        }
        BigDecimal fee = new BigDecimal(String.valueOf(existing.get(0).get("current_fee"))).setScale(2, RoundingMode.HALF_UP);
        String now = LocalDateTime.now().format(TIME);

        jdbcTemplate.update(
            "UPDATE dip_fee_snapshot SET record_status = 'VOIDED', voided_at = ?, voided_by = ?, void_reason = ?"
                + " WHERE patient_id = ?",
            now,
            operator(user),
            reason,
            patientId
        );
        changeLogService.record(patientId, "FEE_VOID", currentGroupCode(patientId), fee.toPlainString(), "", reason, user);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", patientId);
        payload.put("recordStatus", "VOIDED");
        payload.put("voidedAt", now);
        return payload;
    }

    /** DIP 工作台新增患者（HIS 之外），同时创建最小就诊与主诊断，使其可进入费用水位看板。 */
    @Transactional
    public Map<String, Object> createPatient(Map<String, Object> body, SessionUser user) {
        AuthPermission.requireAnyRole("仅医生或管理员可在 DIP 工作台新增患者", "admin", "doctor");
        String name = str(body.get("patientName"));
        String diagnosis = str(body.get("diagnosis"));
        if (name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写患者姓名");
        if (diagnosis.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写西医主诊断");

        String gender = str(body.get("gender"));
        String age = str(body.get("age"));
        String admissionNo = str(body.get("admissionNo"));
        String admissionDate = str(body.get("admissionDate"));
        String attendingDoctor = str(body.get("attendingDoctor"));
        if (admissionDate.isBlank()) admissionDate = LocalDate.now().toString();

        String departmentId = resolveDepartmentId(user);
        String departmentName = jdbcTemplate.queryForObject(
            "SELECT name FROM clinic_departments WHERE id = ?", String.class, departmentId);

        String timestamp = LocalDateTime.now().format(TIME);
        String patientId = "patient-" + UUID.randomUUID();
        String patientCaseId = "pcase-" + UUID.randomUUID();
        String encounterId = "preai-" + UUID.randomUUID();

        ObjectNode patientJson = objectMapper.createObjectNode();
        patientJson.put("patientName", name);
        patientJson.put("gender", gender);
        patientJson.put("age", age);
        patientJson.put("visitNo", admissionNo);
        patientJson.put("visitDate", admissionDate);
        patientJson.put("doctor", attendingDoctor);
        patientJson.put("inventoryCareType", "outpatient");
        patientJson.put("createSource", "DIP_WORKBENCH");

        ObjectNode archive = patientJson.deepCopy();
        archive.put("id", patientId);
        archive.put("name", name);
        archive.put("visitType", "DIP工作台");
        archive.put("currentStage", "DIP费用管理");
        archive.put("completedCount", 1);
        archive.put("progressPercent", 0);
        archive.put("status", "DIP费用管理中");
        archive.put("riskType", "info");
        archive.put("createdAt", timestamp);
        archive.put("updatedAt", timestamp);
        archive.put("encounterCount", 1);
        archive.put("sourceType", "dip");
        archive.set("encounterHistory", objectMapper.createArrayNode());

        jdbcTemplate.update(
            "INSERT INTO clinic_patients (id, name, visit_no, visit_date, visit_type, doctor, current_stage,"
                + " completed_count, progress_percent, status, risk_type, created_at, updated_at, encounter_count,"
                + " encounter_history_json, raw_json)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON))",
            patientId,
            name,
            admissionNo,
            admissionDate,
            "DIP工作台",
            attendingDoctor,
            "DIP费用管理",
            1,
            0,
            "DIP费用管理中",
            "info",
            timestamp,
            timestamp,
            1,
            "[]",
            toJson(archive)
        );

        jdbcTemplate.update(
            "INSERT INTO pre_ai_patient_cases (id, source_patient_id, patient_json, created_at, updated_at)"
                + " VALUES (?, ?, CAST(? AS JSON), ?, ?)",
            patientCaseId,
            patientId,
            toJson(patientJson),
            timestamp,
            timestamp
        );

        ObjectNode visitMeta = objectMapper.createObjectNode();
        visitMeta.put("createSource", "DIP_WORKBENCH");
        visitMeta.put("department", departmentName);

        jdbcTemplate.update(
            "INSERT INTO pre_ai_encounters (id, source_patient_id, patient_case_id, owning_department_id,"
                + " owning_department_name_snapshot, visit_no, follow_up_of_encounter_id, case_token, route,"
                + " inventory_care_type, care_situation_tags, care_type_locked_at, treatment_path, status, current_stage,"
                + " patient_json, visit_meta_json, legacy_reference_json, duty_assignments_json, reviewed_at, reviewed_by,"
                + " reviewed_by_role, registration_request_id, created_at, updated_at, created_by, created_by_role)"
                + " VALUES (?, ?, ?, ?, ?, 1, '', ?, '', 'outpatient', '', ?, '', 'IN_PROGRESS', 'DOCTOR',"
                + " CAST(? AS JSON), CAST(? AS JSON), JSON_OBJECT(), JSON_ARRAY(), '', '', '', '', ?, ?, ?, ?)",
            encounterId,
            patientId,
            patientCaseId,
            departmentId,
            departmentName,
            "DIP-" + UUID.randomUUID(),
            timestamp,
            toJson(patientJson),
            toJson(visitMeta),
            timestamp,
            timestamp,
            operator(user),
            user.role()
        );

        jdbcTemplate.update(
            "INSERT INTO pre_ai_stage_submissions (encounter_id, stage_code, status, version, data_json, returned_reason,"
                + " requires_reconfirmation, submitted_by, submitted_by_role, completed_at, updated_at)"
                + " VALUES (?, 'REGISTRATION', 'DRAFT', 0, CAST(? AS JSON), '', FALSE, '', '', '', ?)",
            encounterId,
            toJson(patientJson),
            timestamp
        );

        jdbcTemplate.update(
            "INSERT INTO pre_ai_diagnoses (id, encounter_id, diagnosis_type, diagnosis_text, sort_no, source_stage, updated_at)"
                + " VALUES (?, ?, 'WESTERN_PRIMARY', ?, 0, 'DOCTOR', ?)",
            "diag-" + UUID.randomUUID(),
            encounterId,
            diagnosis,
            timestamp
        );

        String groupCode = str(body.get("groupCode"));
        if (!groupCode.isBlank()) {
            Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dip_group_mapping WHERE group_code = ? AND status = 'ACTIVE'",
                Integer.class,
                groupCode
            );
            if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "病组编码不存在或已停用");
            jdbcTemplate.update(
                "INSERT INTO dip_group_selection (patient_id, group_code, selection_note, updated_by, updated_at, fee_override)"
                    + " VALUES (?, ?, 'DIP工作台新增患者时指定', ?, ?, NULL)"
                    + " ON DUPLICATE KEY UPDATE group_code = VALUES(group_code), updated_by = VALUES(updated_by),"
                    + " updated_at = VALUES(updated_at)",
                patientId,
                groupCode,
                operator(user),
                timestamp
            );
        }

        BigDecimal initialFee = dec(body.get("unsettledFee"), "未结算费用");
        if (initialFee != null && initialFee.signum() > 0) {
            Map<String, Object> feeBody = new LinkedHashMap<>();
            feeBody.put("patientId", patientId);
            feeBody.put("unsettledFee", initialFee);
            feeBody.put("source", "MANUAL");
            feeBody.put("admissionNo", admissionNo);
            feeBody.put("admissionDate", admissionDate);
            feeBody.put("attendingDoctor", attendingDoctor);
            feeBody.put("depositBalance", body.get("depositBalance"));
            feeBody.put("remark", "DIP工作台新增患者");
            saveFee(feeBody, user);
        }

        changeLogService.record(
            patientId,
            "PATIENT_CREATE",
            groupCode,
            "",
            name + " / " + diagnosis,
            str(body.get("note")),
            user
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", patientId);
        payload.put("encounterId", encounterId);
        return payload;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(String patientId, SessionUser user) {
        if (str(patientId).isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者标识");
        ensurePatientAccess(patientId, user);
        return changeLogService.listByPatient(patientId);
    }

    // ---------------- 内部工具 ----------------

    private String resolveDepartmentId(SessionUser user) {
        String activeId = str(user.activeDepartmentId());
        List<String> ids = jdbcTemplate.query(
            "SELECT d.id FROM clinic_account_departments ad"
                + " JOIN clinic_departments d ON d.id = ad.department_id"
                + " WHERE ad.account_id = ? AND ad.status = 'ACTIVE' AND d.status = 'ACTIVE'"
                + " AND d.id <> 'dept-unassigned' AND (? = '' OR d.id = ?)"
                + " ORDER BY CASE WHEN ad.is_primary = TRUE THEN 0 ELSE 1 END, d.name LIMIT 1",
            (rs, rowNum) -> rs.getString("id"),
            user.id(),
            activeId,
            activeId
        );
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前账号未配置活动科室，无法新增 DIP 患者");
        }
        return ids.get(0);
    }

    /** 患者当前有效病组：启用的人工改选优先，否则按最新主诊断自动匹配。 */
    private String effectiveGroupCode(String patientId) {
        List<String> selected = jdbcTemplate.queryForList(
            "SELECT group_code FROM dip_group_selection WHERE patient_id = ?", String.class, patientId);
        if (!selected.isEmpty()) {
            Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dip_group_mapping WHERE group_code = ? AND status = 'ACTIVE'",
                Integer.class,
                selected.get(0)
            );
            if (active != null && active > 0) return selected.get(0);
        }
        List<String> diagnoses = jdbcTemplate.queryForList(
            "SELECT d.diagnosis_text FROM pre_ai_encounters e"
                + " JOIN pre_ai_diagnoses d ON d.encounter_id = e.id AND d.diagnosis_type = 'WESTERN_PRIMARY'"
                + " WHERE e.source_patient_id = ? AND e.status <> 'CANCELLED'"
                + " ORDER BY e.updated_at DESC LIMIT 1",
            String.class,
            patientId
        );
        if (diagnoses.isEmpty()) return "";
        GroupRule auto = match(diagnoses.get(0), loadRules());
        return auto == null ? "" : auto.groupCode();
    }

    private String currentGroupCode(String patientId) {
        List<String> codes = jdbcTemplate.queryForList(
            "SELECT group_code FROM dip_group_selection WHERE patient_id = ?", String.class, patientId);
        return codes.isEmpty() ? "" : codes.get(0);
    }

    private void ensurePatient(String patientId) {
        Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM clinic_patients WHERE id = ?", Integer.class, patientId);
        if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "患者不存在");
    }

    /** 写入前校验：患者存在且当前账号对该患者最新就诊有病例级读权限，避免改 patientId 越权。 */
    private void ensurePatientAccess(String patientId, SessionUser user) {
        ensurePatient(patientId);
        List<String> encounterIds = jdbcTemplate.queryForList(
            "SELECT id FROM pre_ai_encounters WHERE source_patient_id = ? AND status <> 'CANCELLED'"
                + " ORDER BY updated_at DESC LIMIT 1",
            String.class,
            patientId
        );
        if (encounterIds.isEmpty() || !preAiEncounterService.canReadEncounterScoped(encounterIds.get(0), user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无权操作该患者病历");
        }
    }

    private String toJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "患者数据序列化失败");
        }
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
