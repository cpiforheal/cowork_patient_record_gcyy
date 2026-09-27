package com.coshare.patientrecord.clinic.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.RoleCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
    private static final Set<String> ADDRESS_ANALYSIS_ROLES =
        Set.of("admin", "doctor", "nurse", "nursing");
    private static final List<String> EXAM_TYPES =
        List.of("LAB", "ECG", "IMAGING", "VITAL_SIGNS", "COLONOSCOPY");
    private static final String UNKNOWN_ADDRESS = "\u672a\u8bc6\u522b\u5730\u5740";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OpsDashboardService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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

    public Map<String, Object> addressAnalysis(
        String from,
        String to,
        String parentKey,
        String level,
        String metric,
        String keyword,
        int page,
        int pageSize,
        SessionUser user
    ) {
        requireAddressAnalysisRole(user);
        LocalDate end = parseDate(to, LocalDate.now());
        LocalDate start = parseDate(from, end.minusDays(364));
        if (start.isAfter(end)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        String normalizedLevel = normalizeLevel(level);
        String normalizedMetric = "patients".equalsIgnoreCase(metric) ? "patients" : "visits";
        String normalizedParent = safe(parentKey);
        String normalizedKeyword = safe(keyword).toLowerCase();
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);

        List<AddressVisit> visits = loadAddressVisits(start, end);
        Map<String, AddressPatient> patients = aggregatePatients(visits);
        List<AddressNode> nodes = addressNodes(patients.values(), normalizedLevel, normalizedParent, normalizedKeyword);
        nodes.sort(Comparator.comparingInt((AddressNode row) -> "patients".equals(normalizedMetric) ? row.patientCount : row.visitCount)
            .reversed()
            .thenComparing(row -> row.label));

        int totalNodes = nodes.size();
        int fromIndex = Math.min((safePage - 1) * safePageSize, totalNodes);
        int toIndex = Math.min(fromIndex + safePageSize, totalNodes);
        List<Map<String, Object>> nodeRows = new ArrayList<>();
        int totalPatients = 0;
        int totalVisits = 0;
        for (AddressPatient patient : patients.values()) {
            if (matchesParent(patient, normalizedLevel, normalizedParent)) {
                totalPatients++;
                totalVisits += patient.visitCount;
            }
        }
        for (AddressNode node : nodes.subList(fromIndex, toIndex)) nodeRows.add(node.toMap(totalPatients, totalVisits, normalizedMetric));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        result.put("from", start.toString());
        result.put("to", end.toString());
        result.put("level", normalizedLevel);
        result.put("parentKey", normalizedParent);
        result.put("metric", normalizedMetric);
        result.put("breadcrumb", breadcrumb(normalizedLevel, normalizedParent));
        result.put("summary", Map.of(
            "patientCount", totalPatients,
            "visitCount", totalVisits,
            "unidentifiedCount", unidentifiedCount(patients.values(), normalizedLevel, normalizedParent)
        ));
        result.put("nodes", nodeRows);
        result.put("total", totalNodes);
        result.put("page", safePage);
        result.put("pageSize", safePageSize);
        if ("PATIENT".equals(normalizedLevel)) {
            List<Map<String, Object>> cards = patients.values().stream()
                .filter(patient -> matchesParent(patient, normalizedLevel, normalizedParent))
                .filter(patient -> normalizedKeyword.isBlank() || patient.searchText().contains(normalizedKeyword))
                .sorted(Comparator.comparing(AddressPatient::latestVisitDate).reversed())
                .skip((long) (safePage - 1) * safePageSize)
                .limit(safePageSize)
                .map(AddressPatient::toMap)
                .toList();
            result.put("patients", cards);
            result.put("patientTotal", patients.values().stream()
                .filter(patient -> matchesParent(patient, normalizedLevel, normalizedParent))
                .filter(patient -> normalizedKeyword.isBlank() || patient.searchText().contains(normalizedKeyword))
                .count());
        }
        return result;
    }

    private List<AddressVisit> loadAddressVisits(LocalDate start, LocalDate end) {
        List<AddressVisit> rows = new ArrayList<>();
        jdbcTemplate.query(
            "SELECT id, patient_case_id, patient_json, created_at, status FROM pre_ai_encounters WHERE status <> 'CANCELLED'",
            (RowCallbackHandler) rs -> {
                JsonNode patient = readJson(rs.getString("patient_json"));
                LocalDate visitDate = parseDate(text(patient, "visitDate"), parseDate(rs.getString("created_at"), LocalDate.MIN));
                if (!visitDate.isBefore(start) && !visitDate.isAfter(end)) {
                    rows.add(new AddressVisit(
                        rs.getString("id"),
                        safe(rs.getString("patient_case_id")),
                        patient,
                        visitDate
                    ));
                }
            }
        );
        return rows;
    }

    private Map<String, AddressPatient> aggregatePatients(List<AddressVisit> visits) {
        Map<String, AddressPatient> patients = new LinkedHashMap<>();
        for (AddressVisit visit : visits) {
            String key = visit.patientCaseId.isBlank() ? "encounter:" + visit.encounterId : visit.patientCaseId;
            AddressPatient patient = patients.computeIfAbsent(key, ignored -> new AddressPatient(visit));
            patient.addVisit(visit);
        }
        Map<String, ExamSummary> examByEncounter = examSummaries(visits.stream().map(row -> row.encounterId).toList());
        patients.values().forEach(patient -> patient.applyExamSummaries(examByEncounter));
        return patients;
    }

    private Map<String, ExamSummary> examSummaries(List<String> encounterIds) {
        Map<String, ExamSummary> result = new HashMap<>();
        Set<String> requested = new HashSet<>(encounterIds);
        jdbcTemplate.query(
            "SELECT encounter_id, metrics_json FROM pre_ai_lab_reports WHERE status = 'ACTIVE'",
            (RowCallbackHandler) rs -> {
                String encounterId = rs.getString("encounter_id");
                if (!requested.contains(encounterId)) return;
                ExamSummary summary = result.computeIfAbsent(encounterId, ignored -> new ExamSummary());
                summary.add("LAB", readJson(rs.getString("metrics_json")));
            }
        );
        jdbcTemplate.query(
            "SELECT encounter_id, task_type, status, data_json FROM pre_ai_auxiliary_tasks "
                + "WHERE status NOT IN ('CANCELLED', 'INACTIVE')",
            (RowCallbackHandler) rs -> {
                String encounterId = rs.getString("encounter_id");
                if (!requested.contains(encounterId)) return;
                String type = safe(rs.getString("task_type"));
                if (!EXAM_TYPES.contains(type) || "LAB".equals(type)) return;
                ExamSummary summary = result.computeIfAbsent(encounterId, ignored -> new ExamSummary());
                summary.add(type, readJson(rs.getString("data_json")));
            }
        );
        return result;
    }

    private List<AddressNode> addressNodes(Iterable<AddressPatient> patients, String level, String parentKey, String keyword) {
        Map<String, AddressNode> nodes = new LinkedHashMap<>();
        for (AddressPatient patient : patients) {
            if (!matchesParent(patient, level, parentKey)) continue;
            String label = patient.childLabel(level);
            String key = patient.childKey(level);
            if (!keyword.isBlank() && !label.toLowerCase().contains(keyword)) continue;
            AddressNode node = nodes.computeIfAbsent(key, ignored -> new AddressNode(key, label, nextLevel(level)));
            node.patientCount += 1;
            node.visitCount += patient.visitCount;
            node.hasChildren = node.hasChildren || (!"VILLAGE".equals(level) && !patient.unidentified());
        }
        return new ArrayList<>(nodes.values());
    }

    private boolean matchesParent(AddressPatient patient, String level, String parentKey) {
        if (parentKey.isBlank()) return true;
        return switch (level) {
            case "TOWNSHIP" -> parentKey.equals(patient.countyKey());
            case "VILLAGE" -> parentKey.equals(patient.townshipKey());
            case "PATIENT" -> parentKey.equals(patient.villageKey());
            default -> true;
        };
    }

    private int unidentifiedCount(Iterable<AddressPatient> patients, String level, String parentKey) {
        int count = 0;
        for (AddressPatient patient : patients) {
            if (matchesParent(patient, level, parentKey) && patient.unidentified()) count++;
        }
        return count;
    }

    private List<Map<String, String>> breadcrumb(String level, String parentKey) {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(Map.of("key", "", "label", "全部地区", "level", "COUNTY"));
        if (parentKey.isBlank()) return rows;
        String[] parts = parentKey.split("/", -1);
        if (parts.length > 0 && !parts[0].isBlank()) rows.add(Map.of("key", parts[0], "label", parts[0].replace("county:", ""), "level", "TOWNSHIP"));
        if (parts.length > 1 && !parts[1].isBlank()) rows.add(Map.of("key", parentKey, "label", parts[1].replace("township:", ""), "level", "VILLAGE"));
        if (parts.length > 2 && !parts[2].isBlank()) rows.add(Map.of("key", parentKey, "label", parts[2].replace("village:", ""), "level", "PATIENT"));
        return rows;
    }

    private String nextLevel(String level) {
        return switch (level) {
            case "COUNTY" -> "TOWNSHIP";
            case "TOWNSHIP" -> "VILLAGE";
            default -> "PATIENT";
        };
    }

    private String normalizeLevel(String value) {
        String normalized = safe(value).toUpperCase();
        return Set.of("TOWNSHIP", "VILLAGE", "PATIENT").contains(normalized) ? normalized : "COUNTY";
    }

    private JsonNode readJson(String value) {
        try {
            return value == null || value.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(value);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        String normalized = safe(value).replace('T', ' ');
        if (normalized.length() >= 10) normalized = normalized.substring(0, 10);
        try {
            return normalized.isBlank() ? fallback : LocalDate.parse(normalized);
        } catch (DateTimeParseException ignored) {
            return fallback;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String text(JsonNode node, String field) {
        return node == null ? "" : safe(node.path(field).asText(""));
    }

    private static final class AddressVisit {
        private final String encounterId;
        private final String patientCaseId;
        private final JsonNode patient;
        private final LocalDate visitDate;

        private AddressVisit(String encounterId, String patientCaseId, JsonNode patient, LocalDate visitDate) {
            this.encounterId = encounterId;
            this.patientCaseId = patientCaseId;
            this.patient = patient;
            this.visitDate = visitDate;
        }
    }

    private final class AddressPatient {
        private final String id;
        private final String name;
        private final String gender;
        private final String age;
        private final String phone;
        private final String address;
        private final String county;
        private final String township;
        private final String village;
        private int visitCount;
        private LocalDate latestVisitDate = LocalDate.MIN;
        private String latestEncounterId = "";
        private final Map<String, ExamSummary> exams = new LinkedHashMap<>();

        private AddressPatient(AddressVisit visit) {
            id = visit.patientCaseId.isBlank() ? "encounter:" + visit.encounterId : visit.patientCaseId;
            name = text(visit.patient, "patientName");
            gender = text(visit.patient, "gender");
            age = text(visit.patient, "age");
            phone = text(visit.patient, "phone");
            address = text(visit.patient, "address");
            String[] parts = parseAddress(address);
            county = parts[0];
            township = parts[1];
            village = parts[2];
        }

        private void addVisit(AddressVisit visit) {
            visitCount++;
            if (visit.visitDate.isAfter(latestVisitDate)) {
                latestVisitDate = visit.visitDate;
                latestEncounterId = visit.encounterId;
            }
        }

        private void applyExamSummaries(Map<String, ExamSummary> byEncounter) {
            if (!latestEncounterId.isBlank() && byEncounter.containsKey(latestEncounterId)) {
                exams.put(latestEncounterId, byEncounter.get(latestEncounterId));
            }
        }

        private String countyKey() {
            return "county:" + county;
        }

        private String townshipKey() {
            return countyKey() + "/township:" + township;
        }

        private String villageKey() {
            return townshipKey() + "/village:" + village;
        }

        private String childKey(String level) {
            return switch (level) {
                case "COUNTY" -> countyKey();
                case "TOWNSHIP" -> townshipKey();
                default -> villageKey();
            };
        }

        private String childLabel(String level) {
            return switch (level) {
                case "COUNTY" -> county;
                case "TOWNSHIP" -> township;
                default -> village;
            };
        }

        private boolean matches(String level, String parentKey) {
            if (parentKey.isBlank()) return true;
            return switch (level) {
                case "TOWNSHIP" -> parentKey.equals(countyKey());
                case "VILLAGE" -> parentKey.equals(townshipKey());
                default -> true;
            };
        }

        private boolean unidentified() {
            return "未识别地址".equals(county) || "未识别地址".equals(township) || "未识别地址".equals(village);
        }

        private String searchText() {
            return String.join(" ", name, address, phone).toLowerCase();
        }

        private String latestVisitDate() {
            return latestVisitDate.equals(LocalDate.MIN) ? "" : latestVisitDate.toString();
        }

        private Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", name);
            row.put("gender", gender);
            row.put("age", age);
            row.put("phone", maskPhone(phone));
            row.put("address", address);
            row.put("county", county);
            row.put("township", township);
            row.put("village", village);
            row.put("visitCount", visitCount);
            row.put("latestVisitDate", latestVisitDate.equals(LocalDate.MIN) ? "" : latestVisitDate.toString());
            row.put("encounterId", latestEncounterId);
            return row;
        }
    }

    private final class AddressNode {
        private final String key;
        private final String label;
        private final String level;
        private int patientCount;
        private int visitCount;
        private boolean hasChildren;

        private AddressNode(String key, String label, String level) {
            this.key = key;
            this.label = label;
            this.level = level;
        }

        private Map<String, Object> toMap(int totalPatients, int totalVisits, String metric) {
            int value = "patients".equals(metric) ? patientCount : visitCount;
            int denominator = "patients".equals(metric) ? totalPatients : totalVisits;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("label", label);
            row.put("level", level);
            row.put("patientCount", patientCount);
            row.put("visitCount", visitCount);
            row.put("share", denominator <= 0 ? 0 : Math.round(value * 1000f / denominator) / 10.0);
            row.put("hasChildren", hasChildren);
            return row;
        }
    }

    private final class ExamSummary {
        private final Map<String, int[]> byType = new LinkedHashMap<>();

        private void add(String type, JsonNode data) {
            int[] stats = byType.computeIfAbsent(type, ignored -> new int[3]);
            stats[0]++;
            if ("LAB".equals(type)) {
                JsonNode metrics = data.isArray() ? data : data.path("items");
                for (JsonNode metric : metrics) {
                    String severity = metric.path("severity").asText("");
                    if (metric.path("abnormal").asBoolean(false) || (!severity.isBlank() && !"NORMAL".equalsIgnoreCase(severity))) stats[1]++;
                    if ("CRITICAL".equalsIgnoreCase(severity)) stats[2]++;
                }
            } else {
                if (data.path("abnormal").asBoolean(false) || !data.path("abnormalDescription").asText("").isBlank()) stats[1]++;
                if ("CRITICAL".equalsIgnoreCase(data.path("severity").asText("")) || data.path("critical").asBoolean(false)) stats[2]++;
            }
        }

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            byType.forEach((type, stats) -> result.put(type, Map.of(
                "reports", stats[0],
                "abnormal", stats[1],
                "critical", stats[2]
            )));
            return result;
        }
    }

    private String[] parseAddress(String raw) {
        String address = safe(raw).replaceAll("\\s+", "");
        if (address.isBlank()) return new String[] {UNKNOWN_ADDRESS, UNKNOWN_ADDRESS, UNKNOWN_ADDRESS};

        // Parse each level from the remaining address so a city prefix cannot swallow the county.
        String county = matchAddressPart(address, "(?:^|[\\u7701\\u5e02])([\\p{IsHan}]{2,8}(?:\\u53bf|\\u533a))");
        if (county.isBlank()) county = matchAddressPart(address, "([\\p{IsHan}]{2,8}(?:\\u53bf|\\u533a))");
        if (county.isBlank()) county = matchAddressPart(address, "(?:^|[\\u7701])([\\p{IsHan}]{2,8}\\u5e02)");
        String remainder = county.isBlank() ? address : address.substring(address.indexOf(county) + county.length());
        String township = matchAddressPart(remainder, "([\\p{IsHan}]{2,12}(?:\\u9547|\\u4e61|\\u8857\\u9053|\\u82cf\\u6728))");
        String villageRemainder = township.isBlank() ? remainder : remainder.substring(remainder.indexOf(township) + township.length());
        String village = matchAddressPart(villageRemainder, "([\\p{IsHan}]{2,20}(?:\\u6751|\\u793e\\u533a|\\u5c45\\u59d4\\u4f1a|\\u5c45\\u59d4))");
        return new String[] {
            county.isBlank() ? UNKNOWN_ADDRESS : county,
            township.isBlank() ? UNKNOWN_ADDRESS : township,
            village.isBlank() ? UNKNOWN_ADDRESS : village
        };
    }

    private String[] parseAddressLegacy(String raw) {
        String address = safe(raw).replaceAll("\\s+", "");
        if (address.isBlank()) return new String[] {"未识别地址", "未识别地址", "未识别地址"};
        String county = matchAddressPart(address, "([\\p{IsHan}]{2,12}(?:县|区|市))");
        String remainder = county.isBlank() ? address : address.substring(address.indexOf(county) + county.length());
        String township = matchAddressPart(remainder, "([\\p{IsHan}]{2,12}(?:镇|乡|街道|苏木))");
        String villageRemainder = township.isBlank() ? remainder : remainder.substring(remainder.indexOf(township) + township.length());
        String village = matchAddressPart(villageRemainder, "([\\p{IsHan}]{2,20}(?:村|社区|居委会|居))");
        return new String[] {
            county.isBlank() ? "未识别地址" : county,
            township.isBlank() ? "未识别地址" : township,
            village.isBlank() ? "未识别地址" : village
        };
    }

    private String matchAddressPart(String value, String regex) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String maskPhone(String value) {
        String phone = safe(value);
        return phone.length() >= 7 ? phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4) : phone;
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
        if (!VIEW_ROLES.contains(RoleCatalog.canonicalize(user.role()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无权查看运营数据看板");
        }
    }

    private void requireAddressAnalysisRole(SessionUser user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthorized");
        if (!ADDRESS_ANALYSIS_ROLES.contains(RoleCatalog.canonicalize(user.role()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "address analysis is not available for this role");
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

