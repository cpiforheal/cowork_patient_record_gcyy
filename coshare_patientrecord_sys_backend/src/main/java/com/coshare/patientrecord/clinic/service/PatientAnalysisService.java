package com.coshare.patientrecord.clinic.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.RoleCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
@Transactional(readOnly = true)
public class PatientAnalysisService {
    static final String UNKNOWN = "未记录";
    private static final Set<String> VIEW_ROLES = Set.of("admin", "doctor", "nurse", "nursing", "inspection", "quality");
    private static final Set<String> DETAIL_ROLES = Set.of("admin", "nurse", "nursing");
    private static final Pattern CONTACT_ID = Pattern.compile("(?:^|\\s)recall:([^\\s]+)(?=\\s|$)");
    private static final Pattern AGE = Pattern.compile("^(\\d{1,3})(?:岁)?$");
    private static final Map<String, String> STATUS_LABELS = Map.of(
        "IN_PROGRESS", "进行中", "PENDING_REVIEW", "待复核", "REVIEWED", "已复核", "EXPORTED", "已导出"
    );
    private static final Map<String, String> EXAM_LABELS = Map.of(
        "LAB", "检验报告", "ECG", "心电图", "IMAGING", "影像检查", "VITAL_SIGNS", "生命体征", "COLONOSCOPY", "肠镜"
    );
    private static final Map<String, String> SEVERITY_LABELS = Map.of(
        "NORMAL", "正常标记", "ABNORMAL", "异常标记", "CRITICAL", "危急标记", UNKNOWN, "未标记"
    );
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PatientAnalysisService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Map<String, Object> analysis(PatientAnalysisQuery query, SessionUser user) {
        requireRole(user, false);
        return analyze(query, load(query), canViewDetails(user));
    }

    public Map<String, Object> facets(PatientAnalysisQuery query, SessionUser user) {
        requireRole(user, false);
        return facetResult(query, load(query));
    }

    public Map<String, Object> details(PatientAnalysisQuery query, SessionUser user) {
        requireRole(user, true);
        return detailResult(query, load(query));
    }

    // Each fact table is fetched in batches, never joined into a multiplicative patient/report/diagnosis result.
    Data load(PatientAnalysisQuery query) {
        Map<String, Visit> visits = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
            "SELECT id, patient_case_id, source_patient_id, patient_json, created_at, status "
                + "FROM pre_ai_encounters WHERE status NOT IN ('CANCELLED', 'WITHDRAWN')"
        )) {
            JsonNode patient = json(row.get("patient_json"));
            LocalDate recorded = parseDate(text(patient, "visitDate"));
            LocalDate date = recorded == null ? parseDate(string(row.get("created_at"))) : recorded;
            Visit visit = new Visit(string(row.get("id")), patientKey(row), date, patient, string(row.get("status")));
            visit.dateFallback = recorded == null;
            visits.put(visit.id, visit);
        }
        // Snapshot selection precedes clinical filters; follow-up dates do not constrain original encounter dates.
        if (!"followup".equals(query.view())) {
            visits.values().removeIf(v -> !query.includes(v.date));
        }
        List<String> ids = new ArrayList<>(visits.keySet());
        for (Map<String, Object> row : batch(
            "SELECT encounter_id, stage_code, status, data_json FROM pre_ai_stage_submissions WHERE encounter_id IN (%s)", ids
        )) {
            Visit visit = visits.get(string(row.get("encounter_id")));
            visit.stages.put(string(row.get("stage_code")), json(row.get("data_json")));
            visit.stageStatuses.put(string(row.get("stage_code")), string(row.get("status")));
        }
        for (Map<String, Object> row : batch(
            "SELECT encounter_id, diagnosis_type, diagnosis_text FROM pre_ai_diagnoses WHERE encounter_id IN (%s)", ids
        )) {
            Visit visit = visits.get(string(row.get("encounter_id")));
            String value = string(row.get("diagnosis_text"));
            if (!value.isBlank()) visit.diagnoses.computeIfAbsent(string(row.get("diagnosis_type")), ignored -> new LinkedHashSet<>()).add(value);
        }
        for (Visit visit : visits.values()) visit.finishFields();
        for (Map<String, Object> row : batch(
            "SELECT encounter_id, task_type FROM pre_ai_auxiliary_tasks WHERE status = 'COMPLETED' AND encounter_id IN (%s)", ids
        )) {
            String type = string(row.get("task_type"));
            if (EXAM_LABELS.containsKey(type) && !"LAB".equals(type)) visits.get(string(row.get("encounter_id"))).examTypes.add(type);
        }
        boolean metricsNeeded = "exams".equals(query.view()) || query.filters().containsKey("labKey") || query.filters().containsKey("severity");
        List<Report> reports = new ArrayList<>();
        String metricColumn = metricsNeeded ? ", metrics_json" : "";
        for (Map<String, Object> row : batch(
            "SELECT id, encounter_id, template_id, template_name, report_date" + metricColumn
                + " FROM pre_ai_lab_reports WHERE status = 'ACTIVE' AND encounter_id IN (%s)", ids
        )) {
            Visit visit = visits.get(string(row.get("encounter_id")));
            visit.examTypes.add("LAB");
            List<LabMetric> metrics = new ArrayList<>();
            if (metricsNeeded) {
                JsonNode root = json(row.get("metrics_json"));
                JsonNode items = root.isArray() ? root : root.path("items");
                for (JsonNode item : items) {
                    String unit = text(item, "unit");
                    String key = text(item, "key");
                    if (key.isBlank()) key = text(item, "name");
                    String metricKey = string(row.get("template_id")) + "\u001f" + key + "\u001f" + unit;
                    String label = string(row.get("template_name")) + " / " + fallback(text(item, "name"), key)
                        + (unit.isBlank() ? "" : " (" + unit + ")");
                    metrics.add(new LabMetric(metricKey, label, severity(item), text(item, "value"), unit));
                }
            }
            Report report = new Report(string(row.get("id")), visit.id, parseDate(string(row.get("report_date"))), metrics);
            reports.add(report);
            visit.reports.add(report);
        }
        List<Node> nodes = new ArrayList<>();
        List<Contact> contacts = new ArrayList<>();
        if ("followup".equals(query.view())) {
            for (Map<String, Object> row : batch(
                "SELECT id, encounter_id, patient_case_id, seq, next_review_date FROM pre_ai_follow_up_visits WHERE encounter_id IN (%s)", ids
            )) {
                nodes.add(new Node(string(row.get("id")), string(row.get("encounter_id")),
                    parseDate(string(row.get("next_review_date"))), string(row.get("seq"))));
            }
            Set<String> nodeIds = new HashSet<>(nodes.stream().map(Node::id).toList());
            for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id, detail, created_at FROM pre_ai_audit_logs WHERE action = 'followup.recall.contact'"
            )) {
                Matcher match = CONTACT_ID.matcher(string(row.get("detail")));
                if (match.find() && nodeIds.contains(match.group(1))) {
                    LocalDateTime time = parseTime(string(row.get("created_at")));
                    if (time != null) contacts.add(new Contact(string(row.get("id")), match.group(1), time));
                }
            }
        }
        return new Data(new ArrayList<>(visits.values()), reports, nodes, contacts);
    }

    private List<Map<String, Object>> batch(String sql, List<String> ids) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += 400) {
            List<String> part = ids.subList(start, Math.min(start + 400, ids.size()));
            rows.addAll(jdbc.queryForList(sql.formatted(String.join(",", java.util.Collections.nCopies(part.size(), "?"))), part.toArray()));
        }
        return rows;
    }

    Map<String, Object> analyze(PatientAnalysisQuery query, Data data, boolean detailAccess) {
        Selection selection = select(query, data, true);
        List<Visit> visits = selection.visits;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meta", metadata(query, selection));
        result.put("summary", summary(selection));
        result.put("detailsAllowed", detailAccess);
        List<Map<String, Object>> charts = new ArrayList<>();
        switch (query.view()) {
            case "population" -> {
                charts.add(bar(query, visits, "age", "年龄分布", "ageBand", v -> List.of(v.snapshot.ageBand), Function.identity()));
                charts.add(bar(query, visits, "gender", "性别构成", "gender", v -> List.of(v.snapshot.gender), Function.identity()));
                charts.add(regionChart(query, visits));
                charts.add(matrix(query, visits, "ageDiagnosis", "年龄段与主诊断", "ageBand", v -> List.of(v.snapshot.ageBand),
                    "diagnosis", Visit::primary, "同一病例在不同诊断中可能重复出现"));
            }
            case "clinical" -> {
                charts.add(bar(query, visits, "diagnosis", "西医主诊断", "diagnosis", Visit::primary, Function.identity()));
                charts.add(bar(query, visits, "operation", "已记录实际术式", "operation", v -> orUnknown(v.operations), Function.identity()));
                charts.add(matrix(query, visits, "diagnosisOperation", "主诊断与实际主术式", "diagnosis", Visit::primary,
                    "primaryOperation", v -> List.of(v.primaryOperation), "已保存字段，非治疗效果或因果关系"));
                charts.add(bar(query, visits, "tcm", "中医病名", "tcmDisease", v -> v.diagnosis("TCM_DISEASE"), Function.identity()));
                charts.add(bar(query, visits, "syndrome", "中医证型", "syndrome", v -> v.diagnosis("PRIMARY_SYNDROME"), Function.identity()));
            }
            case "exams" -> {
                List<Report> reports = matchingReports(query, visits);
                charts.add(barWithMetric(query, visits, "exams", "检查类型覆盖患者", "examType",
                    v -> orUnknown(v.examTypes), v -> EXAM_LABELS.getOrDefault(v, v), "patients"));
                charts.add(reportTrend(query, visits, reports));
                charts.add(labChart(query, visits, reports));
            }
            case "followup" -> {
                charts.add(followTrend(query, selection));
                charts.add(delayChart(selection));
            }
            default -> {
                charts.add(visitTrend(query, visits));
                charts.add(bar(query, visits, "status", "当前病历状态", "status", v -> List.of(v.status), v -> STATUS_LABELS.getOrDefault(v, v)));
                charts.add(bar(query, visits, "diagnosis", "西医主诊断", "diagnosis", Visit::primary, Function.identity()));
            }
        }
        result.put("charts", charts);
        return result;
    }

    Map<String, Object> facetResult(PatientAnalysisQuery query, Data data) {
        Selection selection = select(query, data, false);
        Map<String, Set<String>> options = new LinkedHashMap<>();
        for (String dimension : PatientAnalysisQuery.DIMENSIONS) options.put(dimension, new LinkedHashSet<>());
        Map<String, String> labLabels = new HashMap<>();
        for (Visit visit : selection.visits) {
            options.get("gender").add(visit.snapshot.gender);
            options.get("ageBand").add(visit.snapshot.ageBand);
            options.get("region").addAll(visit.snapshot.regionPaths());
            options.get("diagnosis").addAll(visit.primary());
            options.get("operation").addAll(orUnknown(visit.operations));
            options.get("primaryOperation").add(visit.primaryOperation);
            options.get("examType").addAll(orUnknown(visit.examTypes));
            options.get("status").add(visit.status);
            options.get("tcmDisease").addAll(visit.diagnosis("TCM_DISEASE"));
            options.get("syndrome").addAll(visit.diagnosis("PRIMARY_SYNDROME"));
            for (Report report : visit.reports) for (LabMetric metric : report.metrics) labLabels.put(metric.key, metric.label);
        }
        options.get("labKey").addAll(labLabels.keySet());
        options.get("severity").addAll(SEVERITY_LABELS.keySet());
        Map<String, Object> facets = new LinkedHashMap<>();
        options.forEach((key, values) -> facets.put(key, values.stream().sorted().map(value -> Map.of(
            "value", value, "label", switch (key) {
                case "status" -> STATUS_LABELS.getOrDefault(value, value);
                case "examType" -> EXAM_LABELS.getOrDefault(value, value);
                case "labKey" -> labLabels.getOrDefault(value, value);
                case "severity" -> SEVERITY_LABELS.getOrDefault(value, value);
                default -> value;
            }
        )).toList()));
        return Map.of("meta", metadata(query, selection), "facets", facets);
    }

    Map<String, Object> detailResult(PatientAnalysisQuery query, Data data) {
        Selection selection = select(query, data, true);
        List<Map<String, Object>> rows = new ArrayList<>();
        if ("followup".equals(query.view())) {
            if ("contact".equals(query.basis()) && !query.unscheduled()) {
                Map<String, Node> nodes = indexNodes(selection.nodes);
                for (Contact contact : selection.contacts) {
                    Node node = nodes.get(contact.nodeId);
                    Map<String, Object> row = detail(selection.byId.get(node.encounterId), contact.id, contact.time.toString().replace('T', ' '));
                    row.put("node", node.seq);
                    row.put("dueDate", dateText(node.due));
                    row.put("firstContactAt", timeText(selection.firstContacts.get(node.id)));
                    rows.add(row);
                }
            } else {
                for (Node node : selection.nodes) {
                    Map<String, Object> row = detail(selection.byId.get(node.encounterId), node.id, dateText(node.due));
                    row.put("node", node.seq);
                    row.put("dueDate", dateText(node.due));
                    row.put("firstContactAt", timeText(selection.firstContacts.get(node.id)));
                    rows.add(row);
                }
            }
        } else if ("exams".equals(query.view()) && (query.filters().containsKey("labKey") || query.filters().containsKey("severity"))) {
            for (Report report : matchingReports(query, selection.visits)) {
                for (LabMetric metric : report.metrics) {
                    if (!metricMatches(query, metric)) continue;
                    Map<String, Object> row = detail(selection.byId.get(report.encounterId), report.id + ":" + metric.key, dateText(report.date));
                    row.put("labMetric", metric.label);
                    row.put("labValue", metric.value);
                    row.put("severity", SEVERITY_LABELS.getOrDefault(metric.severity, metric.severity));
                    rows.add(row);
                }
            }
        } else {
            List<Visit> detailVisits = selection.visits;
            if ("patients".equals(query.metric())) {
                Map<String, Visit> latest = new LinkedHashMap<>();
                detailVisits.forEach(v -> latest.merge(v.patientKey, v, PatientAnalysisService::latest));
                detailVisits = new ArrayList<>(latest.values());
            }
            for (Visit visit : detailVisits) rows.add(detail(visit, visit.id, dateText(visit.date)));
        }
        Comparator<Map<String, Object>> comparator = Comparator.comparing(row -> string(row.get("date")));
        if ("desc".equals(query.sort())) comparator = comparator.reversed();
        rows.sort(comparator.thenComparing(row -> string(row.get("id"))));
        int from = (int) Math.min((long) (query.page() - 1) * query.pageSize(), rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> meta = metadata(query, selection);
        if ("exams".equals(query.view()) && (query.filters().containsKey("labKey") || query.filters().containsKey("severity"))) {
            meta.put("unit", "项次");
            meta.put("sampleSize", rows.size());
        }
        result.put("meta", meta);
        result.put("rows", rows.subList(from, Math.min(from + query.pageSize(), rows.size())));
        result.put("total", rows.size());
        result.put("page", query.page());
        result.put("pageSize", query.pageSize());
        return result;
    }

    private Map<String, Object> detail(Visit visit, String id, String date) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("encounterId", visit.id);
        row.put("name", maskedName(fallback(text(visit.patient, "patientName"), text(visit.patient, "name"))));
        row.put("date", date);
        row.put("gender", visit.snapshot.gender);
        row.put("age", visit.snapshot.age == null ? UNKNOWN : visit.snapshot.age);
        row.put("region", String.join(" / ", visit.snapshot.region));
        row.put("diagnosis", visit.primary());
        row.put("operations", orUnknown(visit.operations));
        row.put("examTypes", visit.examTypes.stream().map(v -> EXAM_LABELS.getOrDefault(v, v)).toList());
        row.put("status", STATUS_LABELS.getOrDefault(visit.status, visit.status));
        row.put("stageStatuses", visit.stageStatuses);
        row.put("dateFallback", visit.dateFallback);
        return row;
    }

    private Selection select(PatientAnalysisQuery query, Data data, boolean applyFilters) {
        Map<String, Snapshot> snapshots = new HashMap<>();
        Map<String, Visit> latest = new HashMap<>();
        boolean followup = "followup".equals(query.view());
        for (Visit visit : data.visits) {
            if (Set.of("CANCELLED", "WITHDRAWN").contains(visit.status)) continue;
            if (followup ? visit.date != null && !visit.date.isAfter(query.to()) : query.includes(visit.date)) {
                latest.merge(visit.patientKey, visit, PatientAnalysisService::latest);
            }
        }
        latest.forEach((key, visit) -> snapshots.put(key, snapshot(visit)));
        List<Visit> cohort = new ArrayList<>();
        for (Visit visit : data.visits) {
            if (Set.of("CANCELLED", "WITHDRAWN").contains(visit.status)) continue;
            if (!followup && !query.includes(visit.date)) continue;
            visit.snapshot = snapshots.getOrDefault(visit.patientKey, snapshot(visit));
            if (!applyFilters || matches(query, visit)) cohort.add(visit);
        }
        Map<String, Visit> byId = new LinkedHashMap<>();
        cohort.forEach(v -> byId.put(v.id, v));
        Map<String, LocalDateTime> first = new HashMap<>();
        data.contacts.stream().filter(c -> !c.time.toLocalDate().isAfter(query.to()))
            .forEach(c -> first.merge(c.nodeId, c.time, (a, b) -> a.isBefore(b) ? a : b));
        Set<String> contactedInRange = new HashSet<>();
        data.contacts.stream().filter(c -> query.includes(c.time.toLocalDate())).forEach(c -> contactedInRange.add(c.nodeId));
        List<Node> nodes = new ArrayList<>();
        int unscheduled = 0;
        for (Node node : data.nodes) {
            if (!byId.containsKey(node.encounterId)) continue;
            if (node.due == null) unscheduled++;
            if (applyFilters && (!query.accepts("contactState", first.containsKey(node.id) ? "contacted" : "unrecorded")
                || !query.accepts("delayBand", delayBand(node.due, first.get(node.id))))) continue;
            boolean inRange = query.unscheduled() ? node.due == null
                : "contact".equals(query.basis()) ? contactedInRange.contains(node.id) : query.includes(node.due);
            if (inRange) nodes.add(node);
        }
        Set<String> nodeIds = new HashSet<>(nodes.stream().map(Node::id).toList());
        List<Contact> contacts = data.contacts.stream().filter(c -> nodeIds.contains(c.nodeId) && query.includes(c.time.toLocalDate())).toList();
        if (followup) {
            Set<String> encounterIds = new HashSet<>(nodes.stream().map(Node::encounterId).toList());
            cohort.removeIf(v -> !encounterIds.contains(v.id));
        }
        return new Selection(cohort, byId, nodes, contacts, first, unscheduled);
    }

    private boolean matches(PatientAnalysisQuery q, Visit v) {
        Snapshot s = v.snapshot;
        if (!q.accepts("gender", s.gender) || !q.accepts("ageBand", s.ageBand)) return false;
        if (q.ageMin() != null && (s.age == null || s.age < q.ageMin())) return false;
        if (q.ageMax() != null && (s.age == null || s.age > q.ageMax())) return false;
        if (!q.acceptsAny("region", s.regionPaths()) || !q.accepts("status", v.status)) return false;
        if (!q.acceptsAny("diagnosis", v.primary()) || !q.acceptsAny("operation", orUnknown(v.operations))) return false;
        if (!q.accepts("primaryOperation", v.primaryOperation)) return false;
        if (!q.acceptsAny("examType", orUnknown(v.examTypes))) return false;
        if (!q.acceptsAny("tcmDisease", v.diagnosis("TCM_DISEASE")) || !q.acceptsAny("syndrome", v.diagnosis("PRIMARY_SYNDROME"))) return false;
        return (!q.filters().containsKey("labKey") && !q.filters().containsKey("severity"))
            || v.reports.stream().flatMap(r -> r.metrics.stream()).anyMatch(m -> metricMatches(q, m));
    }

    private Map<String, Object> metadata(PatientAnalysisQuery q, Selection selection) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("from", q.from().toString());
        meta.put("to", q.to().toString());
        meta.put("view", q.view());
        meta.put("basis", q.basis());
        meta.put("granularity", q.granularity());
        meta.put("metric", q.metric());
        meta.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        meta.put("sampleSize", "followup".equals(q.view())
            ? "contact".equals(q.basis()) && !q.unscheduled() ? selection.contacts.size() : selection.nodes.size()
            : count(selection.visits, q.metric()));
        meta.put("unit", "followup".equals(q.view())
            ? "contact".equals(q.basis()) && !q.unscheduled() ? "联系次" : "节点"
            : "patients".equals(q.metric()) ? "位患者" : "人次");
        Set<String> missingAge = new HashSet<>(), missingRegion = new HashSet<>(), missingGender = new HashSet<>();
        int diagnosisMissing = 0, fallbackDates = 0;
        for (Visit visit : selection.visits) {
            if (visit.snapshot.age == null) missingAge.add(visit.patientKey);
            if (visit.snapshot.region.contains(UNKNOWN)) missingRegion.add(visit.patientKey);
            if (UNKNOWN.equals(visit.snapshot.gender)) missingGender.add(visit.patientKey);
            if (visit.primary().contains(UNKNOWN)) diagnosisMissing++;
            if (visit.dateFallback) fallbackDates++;
        }
        meta.put("missing", Map.of("agePatients", missingAge.size(), "regionPatients", missingRegion.size(),
            "genderPatients", missingGender.size(), "diagnosisVisits", diagnosisMissing, "fallbackDates", fallbackDates));
        meta.put("unscheduledIgnoresDate", true);
        return meta;
    }

    private Map<String, Object> summary(Selection selection) {
        return Map.of(
            "visits", selection.visits.size(), "patients", count(selection.visits, "patients"),
            "reports", selection.visits.stream().mapToInt(v -> v.reports.size()).sum(),
            "nodes", selection.nodes.size(), "contacts", selection.contacts.size(),
            "contactedNodes", selection.nodes.stream().filter(n -> selection.firstContacts.containsKey(n.id)).count(),
            "unscheduledNodes", selection.unscheduled
        );
    }

    private Map<String, Object> bar(PatientAnalysisQuery q, List<Visit> visits, String id, String title,
                                    String dimension, Function<Visit, List<String>> values, Function<String, String> labels) {
        return barWithMetric(q, visits, id, title, dimension, values, labels, q.metric());
    }

    private Map<String, Object> barWithMetric(PatientAnalysisQuery q, List<Visit> visits, String id, String title,
        String dimension, Function<Visit, List<String>> values, Function<String, String> labels, String metric) {
        Map<String, Counter> groups = groups(visits, values);
        int denominator = count(visits, metric);
        List<String> keys = orderedKeys(groups, metric);
        List<Map<String, Object>> rows = new ArrayList<>();
        // Unknown remains visible even when it falls outside the top ten.
        List<String> visible = keys.stream().filter(k -> !UNKNOWN.equals(k)).limit(10).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (keys.contains(UNKNOWN)) visible.add(UNKNOWN);
        for (String key : visible) rows.add(countRow(labels.apply(key), groups.get(key), metric, denominator, Map.of(dimension, List.of(key)), UNKNOWN.equals(key)));
        List<String> rest = keys.stream().filter(k -> !visible.contains(k)).toList();
        if (!rest.isEmpty()) {
            Counter others = new Counter();
            rest.forEach(k -> others.merge(groups.get(k)));
            rows.add(countRow("其他 (" + rest.size() + ")", others, metric, denominator, Map.of(dimension, rest), false));
        }
        List<Map<String, Object>> tableRows = keys.stream().map(key ->
            countRow(labels.apply(key), groups.get(key), metric, denominator, Map.of(dimension, List.of(key)), UNKNOWN.equals(key))).toList();
        Map<String, Object> chart = chart(id, title, "bar", "patients".equals(metric) ? "位患者" : "人次", rows,
            "按当前筛选集合统计；多标签分组可能交叉");
        chart.put("tableRows", tableRows);
        chart.put("denominator", denominator);
        return chart;
    }

    private Map<String, Object> regionChart(PatientAnalysisQuery q, List<Visit> visits) {
        List<String> selected = q.filters().getOrDefault("region", List.of());
        int depth = selected.size() == 1 ? Math.min(selected.get(0).split(" / ", -1).length, 2) : 0;
        Map<String, Object> chart = bar(q, visits, "region", switch (depth) {
            case 1 -> "乡镇 / 街道"; case 2 -> "村 / 社区"; default -> "县 / 区";
        }, "region", v -> List.of(String.join(" / ", v.snapshot.region.subList(0, depth + 1))), value -> {
            String[] parts = value.split(" / ");
            return parts[parts.length - 1];
        });
        chart.put("note", "登记住址解析，非标准行政区编码；缺失层级保留为未记录");
        chart.put("regionDepth", depth);
        return chart;
    }

    private Map<String, Object> matrix(PatientAnalysisQuery q, List<Visit> visits, String id, String title,
        String xDimension, Function<Visit, List<String>> xs, String yDimension, Function<Visit, List<String>> ys, String note) {
        List<String> xKeys = orderedKeys(groups(visits, xs), q.metric());
        List<String> yKeys = orderedKeys(groups(visits, ys), q.metric());
        List<List<String>> xBuckets = matrixBuckets(xKeys), yBuckets = matrixBuckets(yKeys);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<String> xb : xBuckets) for (List<String> yb : yBuckets) {
            Counter counter = new Counter();
            for (Visit visit : visits) {
                if (xs.apply(visit).stream().anyMatch(xb::contains) && ys.apply(visit).stream().anyMatch(yb::contains)) counter.add(visit);
            }
            Map<String, Object> row = countRow("", counter, q.metric(), count(visits, q.metric()),
                Map.of(xDimension, xb, yDimension, yb), xb.contains(UNKNOWN) || yb.contains(UNKNOWN));
            row.put("x", bucketLabel(xb));
            row.put("y", bucketLabel(yb));
            rows.add(row);
        }
        Map<String, Object> chart = chart(id, title, "matrix", "patients".equals(q.metric()) ? "位患者" : "人次", rows, note);
        List<Map<String, Object>> full = new ArrayList<>();
        for (String x : xKeys) for (String y : yKeys) {
            Counter counter = new Counter();
            for (Visit visit : visits) if (xs.apply(visit).contains(x) && ys.apply(visit).contains(y)) counter.add(visit);
            if (counter.visits.isEmpty()) continue;
            Map<String, Object> row = countRow("", counter, q.metric(), count(visits, q.metric()),
                Map.of(xDimension, List.of(x), yDimension, List.of(y)), UNKNOWN.equals(x) || UNKNOWN.equals(y));
            row.put("x", x); row.put("y", y); full.add(row);
        }
        chart.put("tableRows", full);
        return chart;
    }

    private List<List<String>> matrixBuckets(List<String> keys) {
        List<List<String>> buckets = new ArrayList<>();
        keys.stream().filter(k -> !UNKNOWN.equals(k)).limit(10).forEach(k -> buckets.add(List.of(k)));
        if (keys.contains(UNKNOWN)) buckets.add(List.of(UNKNOWN));
        List<String> rest = keys.stream().filter(k -> buckets.stream().noneMatch(b -> b.contains(k))).toList();
        if (!rest.isEmpty()) buckets.add(rest);
        return buckets;
    }

    private String bucketLabel(List<String> keys) {
        return keys.size() == 1 ? keys.get(0) : "其他 (" + keys.size() + ")";
    }

    private Map<String, Object> visitTrend(PatientAnalysisQuery q, List<Visit> visits) {
        Map<LocalDate, Counter> buckets = emptyBuckets(q);
        visits.forEach(v -> buckets.get(bucket(v.date, q.granularity())).add(v));
        List<Map<String, Object>> rows = new ArrayList<>();
        buckets.forEach((date, counter) -> {
            Map<String, Object> row = trendRow(q, date);
            row.put("primary", counter.visits.size());
            row.put("secondary", counter.patients.size());
            rows.add(row);
        });
        Map<String, Object> chart = chart("visits", "来访与患者趋势", "trend", "人次 / 位患者", rows, "患者在各时间段内独立去重，不能跨段相加");
        chart.put("series", List.of("来访人次", "去重患者"));
        return chart;
    }

    private Map<String, Object> reportTrend(PatientAnalysisQuery q, List<Visit> visits, List<Report> reports) {
        Map<LocalDate, Counter> buckets = emptyBuckets(q);
        Map<LocalDate, Set<String>> reportIds = new LinkedHashMap<>();
        buckets.keySet().forEach(k -> reportIds.put(k, new HashSet<>()));
        Map<String, Visit> byId = new HashMap<>();
        visits.forEach(v -> byId.put(v.id, v));
        for (Report report : reports) {
            // The global date basis remains encounter date, including report counts.
            Visit visit = byId.get(report.encounterId);
            LocalDate key = bucket(visit.date, q.granularity());
            reportIds.get(key).add(report.id);
            buckets.get(key).add(visit);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        buckets.forEach((date, counter) -> {
            Map<String, Object> row = trendRow(q, date);
            row.put("primary", reportIds.get(date).size());
            row.put("secondary", counter.patients.size());
            rows.add(row);
        });
        Map<String, Object> chart = chart("reports", "检验报告与覆盖患者", "trend", "份 / 位患者", rows, "按原就诊日期归组，统计其关联有效报告，不是报告出具日期趋势");
        chart.put("series", List.of("有效报告", "覆盖患者"));
        return chart;
    }

    private Map<String, Object> labChart(PatientAnalysisQuery q, List<Visit> visits, List<Report> reports) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (Report report : reports) for (LabMetric metric : report.metrics) {
            if (!metricMatches(q, metric)) continue;
            labels.put(metric.key, metric.label);
            int[] values = counts.computeIfAbsent(metric.key, ignored -> new int[4]);
            values[switch (metric.severity) { case "NORMAL" -> 0; case "ABNORMAL" -> 1; case "CRITICAL" -> 2; default -> 3; }]++;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        counts.entrySet().stream().sorted(Comparator.<Map.Entry<String, int[]>>comparingInt(e -> java.util.Arrays.stream(e.getValue()).sum()).reversed()
            .thenComparing(Map.Entry::getKey)).forEach(entry -> {
                int[] c = entry.getValue();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("label", labels.get(entry.getKey()));
                row.put("normal", c[0]); row.put("abnormal", c[1]); row.put("critical", c[2]); row.put("unmarked", c[3]);
                row.put("value", java.util.Arrays.stream(c).sum());
                row.put("denominator", java.util.Arrays.stream(c).sum());
                row.put("unknownCount", c[3]);
                row.put("filters", Map.of("labKey", List.of(entry.getKey())));
                rows.add(row);
            });
        List<Map<String, Object>> visible = new ArrayList<>(rows.stream().limit(10).toList());
        if (rows.size() > 10) {
            Map<String, Object> others = new LinkedHashMap<>();
            others.put("label", "其他 (" + (rows.size() - 10) + ")");
            for (String key : List.of("normal", "abnormal", "critical", "unmarked", "value", "denominator", "unknownCount")) {
                others.put(key, rows.subList(10, rows.size()).stream().mapToInt(row -> ((Number) row.get(key)).intValue()).sum());
            }
            List<String> keys = new ArrayList<>();
            for (Map<String, Object> row : rows.subList(10, rows.size())) {
                @SuppressWarnings("unchecked")
                Map<String, List<String>> filters = (Map<String, List<String>>) row.get("filters");
                keys.addAll(filters.get("labKey"));
            }
            others.put("filters", Map.of("labKey", keys));
            visible.add(others);
        }
        Map<String, Object> chart = chart("labMarkers", "检验项目标记分布", "stack", "项次", visible,
            "正常、异常、危急均来自已保存标记；未标记不代表正常。项目按模板、项目键及单位区分");
        chart.put("tableRows", rows);
        return chart;
    }

    private Map<String, Object> followTrend(PatientAnalysisQuery q, Selection selection) {
        Map<LocalDate, int[]> counts = new LinkedHashMap<>();
        emptyBuckets(q).keySet().forEach(date -> counts.put(date, new int[2]));
        if (!q.unscheduled()) {
            if ("contact".equals(q.basis())) {
                Map<LocalDate, Set<String>> nodes = new HashMap<>();
                for (Contact contact : selection.contacts) {
                    LocalDate key = bucket(contact.time.toLocalDate(), q.granularity());
                    counts.get(key)[0]++;
                    nodes.computeIfAbsent(key, ignored -> new HashSet<>()).add(contact.nodeId);
                }
                nodes.forEach((key, values) -> counts.get(key)[1] = values.size());
            } else {
                for (Node node : selection.nodes) {
                    int[] values = counts.get(bucket(node.due, q.granularity()));
                    values[0]++;
                    if (selection.firstContacts.containsKey(node.id)) values[1]++;
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        counts.forEach((date, values) -> {
            Map<String, Object> row = trendRow(q, date);
            row.put("primary", values[0]); row.put("secondary", values[1]);
            rows.add(row);
        });
        Map<String, Object> chart = chart("followup", "contact".equals(q.basis()) ? "实际联系记录趋势" : "到期节点与联系留痕", "trend",
            "contact".equals(q.basis()) ? "次 / 节点" : "节点", rows,
            q.unscheduled() ? "未排期节点没有日期，不计入时间趋势" : "服务器留痕截至所选结束日；无留痕不等于未开展工作");
        chart.put("series", "contact".equals(q.basis()) ? List.of("联系次数", "覆盖节点") : List.of("到期节点", "有联系留痕"));
        return chart;
    }

    private Map<String, Object> delayChart(Selection selection) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List.of("提前", "当日", "晚1-3天", "晚4-7天", "晚8天及以上", "无联系留痕", "未排期").forEach(k -> counts.put(k, 0));
        for (Node node : selection.nodes) counts.merge(delayBand(node.due, selection.firstContacts.get(node.id)), 1, Integer::sum);
        List<Map<String, Object>> rows = counts.entrySet().stream().map(entry -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", entry.getKey()); row.put("value", entry.getValue());
            row.put("denominator", selection.nodes.size());
            row.put("share", percent(entry.getValue(), selection.nodes.size()));
            row.put("unknownCount", "无联系留痕".equals(entry.getKey()) || "未排期".equals(entry.getKey()) ? entry.getValue() : 0);
            row.put("filters", Map.of("delayBand", List.of(entry.getKey())));
            return row;
        }).toList();
        return chart("delay", "首次联系与节点日期间隔", "bar", "节点", rows, "按首次服务器联系留痕计算；不是随访完成率或工作评价");
    }

    private Map<String, Object> chart(String id, String title, String kind, String unit, List<Map<String, Object>> rows, String note) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id); result.put("title", title); result.put("kind", kind);
        result.put("unit", unit); result.put("rows", rows); result.put("note", note);
        return result;
    }

    private Map<String, Object> countRow(String label, Counter counter, String metric, int denominator,
                                        Map<String, List<String>> filters, boolean unknown) {
        int value = counter.value(metric);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label); row.put("value", value); row.put("visits", counter.visits.size()); row.put("patients", counter.patients.size());
        row.put("denominator", denominator); row.put("share", percent(value, denominator)); row.put("unknownCount", unknown ? value : 0);
        row.put("filters", filters);
        return row;
    }

    private Map<String, Counter> groups(List<Visit> visits, Function<Visit, List<String>> values) {
        Map<String, Counter> result = new LinkedHashMap<>();
        for (Visit visit : visits) for (String value : values.apply(visit)) result.computeIfAbsent(value, ignored -> new Counter()).add(visit);
        return result;
    }

    private List<String> orderedKeys(Map<String, Counter> groups, String metric) {
        return groups.keySet().stream().sorted(Comparator.<String>comparingInt(key -> groups.get(key).value(metric)).reversed().thenComparing(Function.identity())).toList();
    }

    private Map<LocalDate, Counter> emptyBuckets(PatientAnalysisQuery q) {
        Map<LocalDate, Counter> values = new LinkedHashMap<>();
        for (LocalDate date = bucket(q.from(), q.granularity()); !date.isAfter(q.to()); date = nextBucket(date, q.granularity())) values.put(date, new Counter());
        return values;
    }

    private Map<String, Object> trendRow(PatientAnalysisQuery q, LocalDate date) {
        LocalDate from = date.isBefore(q.from()) ? q.from() : date;
        LocalDate end = nextBucket(date, q.granularity()).minusDays(1);
        LocalDate to = end.isAfter(q.to()) ? q.to() : end;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", date.toString());
        row.put("filters", Map.of("from", List.of(from.toString()), "to", List.of(to.toString())));
        return row;
    }

    private LocalDate bucket(LocalDate date, String granularity) {
        return switch (granularity) { case "month" -> date.withDayOfMonth(1); case "week" -> date.with(DayOfWeek.MONDAY); default -> date; };
    }

    private LocalDate nextBucket(LocalDate date, String granularity) {
        return switch (granularity) { case "month" -> date.plusMonths(1); case "week" -> date.plusWeeks(1); default -> date.plusDays(1); };
    }

    private List<Report> matchingReports(PatientAnalysisQuery q, List<Visit> visits) {
        return visits.stream().flatMap(v -> v.reports.stream())
            .filter(r -> !q.filters().containsKey("labKey") && !q.filters().containsKey("severity") || r.metrics.stream().anyMatch(m -> metricMatches(q, m))).toList();
    }

    private boolean metricMatches(PatientAnalysisQuery q, LabMetric metric) {
        return q.accepts("labKey", metric.key) && q.accepts("severity", metric.severity);
    }

    private static Map<String, Node> indexNodes(List<Node> nodes) {
        Map<String, Node> result = new HashMap<>();
        nodes.forEach(n -> result.put(n.id, n));
        return result;
    }

    static String delayBand(LocalDate due, LocalDateTime first) {
        if (due == null) return "未排期";
        if (first == null) return "无联系留痕";
        long days = ChronoUnit.DAYS.between(due, first.toLocalDate());
        return days < 0 ? "提前" : days == 0 ? "当日" : days <= 3 ? "晚1-3天" : days <= 7 ? "晚4-7天" : "晚8天及以上";
    }

    static String severity(JsonNode metric) {
        if (metric.path("critical").asBoolean(false) || "CRITICAL".equals(text(metric, "severity"))) return "CRITICAL";
        String severity = text(metric, "severity");
        if (Set.of("NORMAL", "ABNORMAL").contains(severity)) return severity;
        if (metric.has("abnormal") && metric.path("abnormal").isBoolean()) return metric.path("abnormal").asBoolean() ? "ABNORMAL" : "NORMAL";
        return UNKNOWN;
    }

    static Snapshot snapshot(Visit visit) {
        String gender = text(visit.patient, "gender");
        gender = Set.of("男", "男性", "MALE", "M").contains(gender.toUpperCase()) ? "男"
            : Set.of("女", "女性", "FEMALE", "F").contains(gender.toUpperCase()) ? "女" : UNKNOWN;
        Integer age = null;
        LocalDate birth = parseDate(text(visit.patient, "birthDate"));
        if (birth != null && visit.date != null && !birth.isAfter(visit.date)) {
            int years = Period.between(birth, visit.date).getYears();
            if (years <= 130) age = years;
        }
        if (age == null) {
            Matcher match = AGE.matcher(text(visit.patient, "age"));
            if (match.matches() && Integer.parseInt(match.group(1)) <= 130) age = Integer.parseInt(match.group(1));
        }
        String band = age == null ? UNKNOWN : age < 18 ? "0-17岁" : age < 30 ? "18-29岁" : age < 45 ? "30-44岁" : age < 60 ? "45-59岁" : age < 75 ? "60-74岁" : "75岁及以上";
        return new Snapshot(gender, age, band, parseRegion(text(visit.patient, "address")));
    }

    static List<String> parseRegion(String value) {
        String remaining = string(value).replaceAll("\\s+", "");
        remaining = remaining.replaceFirst("^[\\p{IsHan}]{2,8}省", "");
        remaining = remaining.replaceFirst("^[\\p{IsHan}]{2,8}?市(?=[\\p{IsHan}]+[县区])", "");
        List<String> parts = new ArrayList<>();
        for (String pattern : List.of("^([\\p{IsHan}]{1,12}?[县区市])", "^([\\p{IsHan}]{1,16}?(?:街道|镇|乡|苏木))", "^([\\p{IsHan}]{1,24}?(?:社区|村|居委会))")) {
            Matcher match = Pattern.compile(pattern).matcher(remaining);
            if (match.find()) { parts.add(match.group(1)); remaining = remaining.substring(match.end()); }
            else parts.add(UNKNOWN);
        }
        return parts;
    }

    private static Visit latest(Visit left, Visit right) {
        int compare = Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder()).compare(left.date, right.date);
        return compare < 0 || compare == 0 && left.id.compareTo(right.id) < 0 ? right : left;
    }

    private static int count(List<Visit> visits, String metric) {
        return "patients".equals(metric) ? (int) visits.stream().map(v -> v.patientKey).distinct().count() : visits.size();
    }

    private static double percent(int value, int total) { return total == 0 ? 0 : Math.round(value * 1000d / total) / 10d; }
    private static List<String> orUnknown(Collection<String> values) { return values.isEmpty() ? List.of(UNKNOWN) : new ArrayList<>(values); }
    private static String fallback(String value, String other) { return value.isBlank() ? other : value; }
    static String string(Object value) { return value == null ? "" : value.toString().trim(); }
    static String text(JsonNode json, String field) { return json == null ? "" : string(json.path(field).asText("")); }
    private static String dateText(LocalDate date) { return date == null ? "" : date.toString(); }
    private static String timeText(LocalDateTime time) { return time == null ? "" : time.toString().replace('T', ' '); }
    private static String maskedName(String name) { return name.isBlank() ? UNKNOWN : name.substring(0, 1) + "*".repeat(Math.min(Math.max(name.length() - 1, 1), 3)); }
    static LocalDate parseDate(String value) {
        try { return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value); }
        catch (RuntimeException ignored) { return null; }
    }
    private static LocalDateTime parseTime(String value) {
        try { return LocalDateTime.parse(value.replace(' ', 'T')); }
        catch (RuntimeException ignored) { LocalDate date = parseDate(value); return date == null ? null : date.atStartOfDay(); }
    }
    private JsonNode json(Object value) {
        try { return value == null || string(value).isBlank() ? mapper.createObjectNode() : mapper.readTree(string(value)); }
        catch (Exception error) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "分析字段解析失败，未将错误转换为零值", error); }
    }
    private String patientKey(Map<String, Object> row) {
        String key = string(row.get("patient_case_id"));
        if (!key.isBlank()) return "case:" + key;
        key = string(row.get("source_patient_id"));
        return key.isBlank() ? "encounter:" + string(row.get("id")) : "source:" + key;
    }
    private boolean canViewDetails(SessionUser user) { return DETAIL_ROLES.contains(RoleCatalog.canonicalize(user.role())); }
    private void requireRole(SessionUser user, boolean details) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        String role = RoleCatalog.canonicalize(user.role());
        if (!(details ? DETAIL_ROLES : VIEW_ROLES).contains(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无权访问该分析数据");
    }

    static final class Visit {
        final String id, patientKey, status;
        final LocalDate date;
        final JsonNode patient;
        boolean dateFallback;
        Snapshot snapshot;
        final Map<String, JsonNode> stages = new HashMap<>();
        final Map<String, String> stageStatuses = new LinkedHashMap<>();
        final Map<String, Set<String>> diagnoses = new HashMap<>();
        final Set<String> operations = new LinkedHashSet<>(), examTypes = new LinkedHashSet<>();
        final List<Report> reports = new ArrayList<>();
        String primaryOperation = UNKNOWN;

        Visit(String id, String patientKey, LocalDate date, JsonNode patient, String status) {
            this.id = id; this.patientKey = patientKey; this.date = date; this.patient = patient; this.status = status;
        }
        List<String> primary() { return diagnosis("WESTERN_PRIMARY"); }
        List<String> diagnosis(String type) { return orUnknown(diagnoses.getOrDefault(type, Set.of())); }
        void finishFields() {
            fallbackDiagnosis("WESTERN_PRIMARY", "DOCTOR", "primaryWesternDiagnosis");
            fallbackDiagnosis("TCM_DISEASE", "TCM", "tcmDisease");
            fallbackDiagnosis("PRIMARY_SYNDROME", "TCM", "primarySyndrome");
            JsonNode surgery = stages.get("SURGERY");
            String operation = fallback(text(surgery, "actualPrimaryOperation"), text(surgery, "actualOperationName"));
            if (!operation.isBlank()) { primaryOperation = operation; operations.add(operation); }
            if (surgery != null && surgery.path("actualSecondaryOperations").isArray()) {
                for (JsonNode item : surgery.path("actualSecondaryOperations")) {
                    String value = item.isTextual() ? item.asText().trim() : text(item, "name");
                    if (!value.isBlank()) operations.add(value);
                }
            }
        }
        private void fallbackDiagnosis(String type, String stage, String field) {
            if (!diagnoses.getOrDefault(type, Set.of()).isEmpty()) return;
            String value = text(stages.get(stage), field);
            if (!value.isBlank()) diagnoses.put(type, new LinkedHashSet<>(List.of(value)));
        }
    }
    record Snapshot(String gender, Integer age, String ageBand, List<String> region) {
        List<String> regionPaths() {
            return List.of(region.get(0), String.join(" / ", region.subList(0, 2)), String.join(" / ", region));
        }
    }
    record LabMetric(String key, String label, String severity, String value, String unit) {}
    record Report(String id, String encounterId, LocalDate date, List<LabMetric> metrics) {}
    record Node(String id, String encounterId, LocalDate due, String seq) {}
    record Contact(String id, String nodeId, LocalDateTime time) {}
    record Data(List<Visit> visits, List<Report> reports, List<Node> nodes, List<Contact> contacts) {}
    private record Selection(List<Visit> visits, Map<String, Visit> byId, List<Node> nodes, List<Contact> contacts,
                             Map<String, LocalDateTime> firstContacts, int unscheduled) {}
    private static final class Counter {
        final Set<String> visits = new HashSet<>(), patients = new HashSet<>();
        void add(Visit visit) { visits.add(visit.id); patients.add(visit.patientKey); }
        void merge(Counter other) { visits.addAll(other.visits); patients.addAll(other.patients); }
        int value(String metric) { return "patients".equals(metric) ? patients.size() : visits.size(); }
    }
}
