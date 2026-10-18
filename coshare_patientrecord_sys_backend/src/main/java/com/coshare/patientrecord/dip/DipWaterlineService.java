package com.coshare.patientrecord.dip;

import com.coshare.patientrecord.auth.dto.SessionUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * DIP 费用预警（费用水位）：
 * - 病组由医生确定的主诊断匹配 dip_group_mapping，得出预估费用与 85%/95% 阈值；
 * - 当前费用来自 HIS（系统无法直连，由截图识别或人工录入写入 dip_fee_snapshot）；
 * - 水位 = 当前费用 / 病组预估费用，≥85% 预警、≥95% 超限。
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

    /** 病组映射（含关键词优先级），按关键词长度与优先级排序，供最长匹配使用。 */
    private record GroupRule(
        String categoryCode,
        String categoryName,
        String groupCode,
        String groupName,
        BigDecimal estimatedFee,
        List<String> keywords,
        int keywordPriority,
        int sortNo
    ) {}

    private record FeeSnapshot(BigDecimal currentFee, String source, String updatedBy, String updatedAt) {}

    public List<GroupRule> loadRules() {
        return jdbcTemplate.query(
            "SELECT category_code, category_name, group_code, group_name, estimated_fee, match_keywords, keyword_priority, sort_no"
                + " FROM dip_group_mapping ORDER BY keyword_priority DESC, sort_no ASC",
            (rs, rowNum) -> {
                String keywords = rs.getString("match_keywords");
                List<String> list = new ArrayList<>();
                if (keywords != null && !keywords.isBlank()) {
                    for (String part : keywords.split(",")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty()) list.add(trimmed);
                    }
                }
                return new GroupRule(
                    rs.getString("category_code"),
                    rs.getString("category_name"),
                    rs.getString("group_code"),
                    rs.getString("group_name"),
                    rs.getBigDecimal("estimated_fee"),
                    List.copyOf(list),
                    rs.getInt("keyword_priority"),
                    rs.getInt("sort_no")
                );
            }
        );
    }

    /** 主诊断 → 病组：取命中的最长关键词；同长度时用 keyword_priority 决胜。 */
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

    private Map<String, FeeSnapshot> loadSnapshots() {
        Map<String, FeeSnapshot> map = new LinkedHashMap<>();
        jdbcTemplate.query(
            "SELECT patient_id, current_fee, fee_source, updated_by, updated_at FROM dip_fee_snapshot",
            rs -> {
                map.put(
                    rs.getString("patient_id"),
                    new FeeSnapshot(
                        rs.getBigDecimal("current_fee"),
                        rs.getString("fee_source"),
                        rs.getString("updated_by"),
                        rs.getString("updated_at")
                    )
                );
            }
        );
        return map;
    }

    /** 每位在院患者的最新一次就诊主诊断（西医主诊断）。 */
    private List<Map<String, Object>> loadLatestPrimaryDiagnoses() {
        return jdbcTemplate.query(
            "SELECT p.id AS patient_id, p.name AS patient_name, p.doctor, p.status AS patient_status,"
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
                row.put("doctor", rs.getString("doctor"));
                row.put("patientStatus", rs.getString("patient_status"));
                row.put("encounterId", rs.getString("encounter_id"));
                row.put("encounterStatus", rs.getString("encounter_status"));
                row.put("encounterUpdatedAt", rs.getString("encounter_updated_at"));
                row.put("diagnosisText", rs.getString("diagnosis_text"));
                return row;
            }
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> board(SessionUser user) {
        List<GroupRule> rules = loadRules();
        Map<String, FeeSnapshot> snapshots = loadSnapshots();
        List<Map<String, Object>> diagnoses = loadLatestPrimaryDiagnoses();

        List<Map<String, Object>> patients = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

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
            GroupRule rule = match(diagnosisText, rules);
            FeeSnapshot snapshot = snapshots.get(patientId);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("patientId", patientId);
            item.put("patientName", row.get("patientName"));
            item.put("doctor", row.get("doctor"));
            item.put("patientStatus", row.get("patientStatus"));
            item.put("encounterId", row.get("encounterId"));
            item.put("diagnosis", diagnosisText);
            item.put("encounterUpdatedAt", row.get("encounterUpdatedAt"));
            item.put("matched", rule != null);

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
            item.put("currentFee", currentFee);
            item.put("restTo95", threshold95.subtract(currentFee).setScale(2, RoundingMode.HALF_UP));
            item.put("feeSource", snapshot.source());
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
        summary.put("feeRecordedAt", LocalDateTime.now().format(TIME));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patients", patients);
        result.put("summary", summary);
        result.put("diagnosisCoverage", coverage());
        return result;
    }

    /** 映射表覆盖面：便于发现"病组表未覆盖"的主诊断。 */
    private List<Map<String, Object>> coverage() {
        return jdbcTemplate.query(
            "SELECT d.diagnosis_text AS diagnosis, COUNT(*) AS c"
                + " FROM pre_ai_encounters e"
                + " JOIN pre_ai_diagnoses d ON d.encounter_id = e.id AND d.diagnosis_type = 'WESTERN_PRIMARY'"
                + " WHERE e.status <> 'CANCELLED'"
                + " GROUP BY d.diagnosis_text ORDER BY c DESC",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("diagnosis", rs.getString("diagnosis"));
                row.put("count", rs.getInt("c"));
                return row;
            }
        );
    }

    @Transactional
    public Map<String, Object> saveFee(String patientId, BigDecimal currentFee, String source, SessionUser user) {
        if (patientId == null || patientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者标识");
        }
        if (currentFee == null || currentFee.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前费用必须为非负数");
        }
        Integer exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM clinic_patients WHERE id = ?", Integer.class, patientId);
        if (exists == null || exists == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "患者不存在");
        }
        String normalizedSource = "SCREENSHOT".equalsIgnoreCase(source) ? "SCREENSHOT" : "MANUAL";
        String now = LocalDateTime.now().format(TIME);
        String operator = user == null ? "" : (user.name() == null ? user.username() : user.name());
        jdbcTemplate.update(
            "INSERT INTO dip_fee_snapshot (patient_id, current_fee, fee_source, updated_by, updated_at) VALUES (?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE current_fee = VALUES(current_fee), fee_source = VALUES(fee_source),"
                + " updated_by = VALUES(updated_by), updated_at = VALUES(updated_at)",
            patientId, currentFee.setScale(2, RoundingMode.HALF_UP), normalizedSource, operator == null ? "" : operator, now
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", patientId);
        payload.put("currentFee", currentFee.setScale(2, RoundingMode.HALF_UP));
        payload.put("feeSource", normalizedSource);
        payload.put("updatedAt", now);
        return payload;
    }
}
