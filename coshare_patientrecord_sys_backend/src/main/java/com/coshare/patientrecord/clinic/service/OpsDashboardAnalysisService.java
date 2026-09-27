package com.coshare.patientrecord.clinic.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.RoleCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class OpsDashboardAnalysisService {
    private static final Set<String> VIEW_ROLES = Set.of("admin", "doctor", "nurse", "nursing", "inspection", "quality");
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OpsDashboardAnalysisService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> dashboard(String from, String to, String granularity, String view, int months, SessionUser user) {
        requireViewRole(user);
        LocalDate today = LocalDate.now();
        LocalDate end = parseDate(to, today);
        LocalDate start = parseDate(from, today.withDayOfMonth(1));
        if (from == null || from.isBlank()) {
            start = months > 1 ? today.withDayOfMonth(1).minusMonths(Math.min(Math.max(months, 1), 24) - 1L) : today.withDayOfMonth(1);
        }
        if (start.isAfter(end)) { LocalDate swap = start; start = end; end = swap; }
        String bucketMode = Set.of("day", "week", "month").contains(safe(granularity).toLowerCase()) ? safe(granularity).toLowerCase() : "day";
        final LocalDate rangeStart = start;
        final LocalDate rangeEnd = end;
        List<Encounter> all = loadEncounters();
        List<Encounter> period = all.stream().filter(row -> !row.date().isBefore(rangeStart) && !row.date().isAfter(rangeEnd)).toList();
        Map<String, String> firstVisit = new HashMap<>();
        for (Encounter row : all) firstVisit.merge(row.patientKey(), row.date().toString(), (a, b) -> a.compareTo(b) <= 0 ? a : b);
        Set<String> patients = new HashSet<>();
        Set<String> newPatients = new HashSet<>();
        Map<String, Bucket> trend = new LinkedHashMap<>();
        Map<String, DepartmentBucket> departments = new LinkedHashMap<>();
        Set<String> unidentified = new HashSet<>();
        for (Encounter row : period) {
            patients.add(row.patientKey());
            if (row.date().toString().equals(firstVisit.get(row.patientKey()))) newPatients.add(row.patientKey());
            trend.computeIfAbsent(bucket(row.date(), bucketMode), ignored -> new Bucket()).add(row);
            departments.computeIfAbsent(row.department(), ignored -> new DepartmentBucket()).add(row);
            if (row.address().isBlank()) unidentified.add(row.patientKey());
        }
        int overdue = followUpCount(true);
        int dueToday = followUpCount(false);
        int visits = period.size();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", Map.of("from", rangeStart.toString(), "to", rangeEnd.toString(), "generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
        result.put("kpi", Map.of("visits", visits, "uniquePatients", patients.size(), "newPatients", newPatients.size(), "overdueFollowUps", overdue));
        result.put("trend", trend.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> entry.getValue().toMap(entry.getKey())).toList());
        result.put("attention", Map.of("overdueFollowUps", overdue, "dueTodayFollowUps", dueToday, "unidentifiedAddresses", unidentified.size()));
        result.put("departments", departments.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue().visits, a.getValue().visits)).limit(12).map(entry -> entry.getValue().toMap(entry.getKey(), visits)).toList());
        result.put("view", safe(view).isBlank() ? "overview" : safe(view));
        return result;
    }

    private List<Encounter> loadEncounters() {
        List<Encounter> rows = new ArrayList<>();
        jdbcTemplate.query("SELECT id, patient_case_id, source_patient_id, patient_json, created_at, owning_department_name_snapshot FROM pre_ai_encounters WHERE status <> 'CANCELLED'", rs -> {
            JsonNode patient = json(rs.getString("patient_json"));
            LocalDate created = parseDate(rs.getString("created_at"), LocalDate.MIN);
            LocalDate date = parseDate(text(patient, "visitDate"), created);
            String key = safe(rs.getString("patient_case_id"));
            if (key.isBlank()) key = safe(rs.getString("source_patient_id"));
            if (key.isBlank()) key = "encounter:" + rs.getString("id");
            String department = safe(rs.getString("owning_department_name_snapshot"));
            rows.add(new Encounter(key, date, department.isBlank() ? "未归属科室" : department, text(patient, "address")));
        });
        return rows;
    }

    private int followUpCount(boolean overdue) {
        String today = LocalDate.now().toString();
        String sql = overdue
            ? "SELECT COUNT(*) FROM pre_ai_follow_up_visits v WHERE v.next_review_date IS NOT NULL AND v.next_review_date <> '' AND v.next_review_date < ? "
                + "AND NOT EXISTS (SELECT 1 FROM pre_ai_audit_logs a WHERE a.action = 'followup.recall.contact' "
                + "AND a.detail LIKE CONCAT('%recall:', v.id, '%'))"
            : "SELECT COUNT(*) FROM pre_ai_follow_up_visits v WHERE v.next_review_date = ? "
                + "AND NOT EXISTS (SELECT 1 FROM pre_ai_audit_logs a WHERE a.action = 'followup.recall.contact' "
                + "AND a.detail LIKE CONCAT('%recall:', v.id, '%'))";
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, today);
        return value == null ? 0 : value;
    }

    private String bucket(LocalDate date, String mode) {
        return switch (mode) { case "month" -> date.toString().substring(0, 7); case "week" -> date.with(DayOfWeek.MONDAY).toString(); default -> date.toString(); };
    }

    private void requireViewRole(SessionUser user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthorized");
        if (!VIEW_ROLES.contains(RoleCatalog.canonicalize(user.role()))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "dashboard access denied");
    }

    private JsonNode json(String value) {
        try { return value == null || value.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(value); }
        catch (Exception ignored) { return objectMapper.createObjectNode(); }
    }

    private String text(JsonNode node, String field) { return safe(node == null ? "" : node.path(field).asText("")); }
    private String safe(String value) { return value == null ? "" : value.trim(); }
    private LocalDate parseDate(String value, LocalDate fallback) {
        String normalized = safe(value).replace('T', ' ');
        if (normalized.length() >= 10) normalized = normalized.substring(0, 10);
        try { return normalized.isBlank() ? fallback : LocalDate.parse(normalized); }
        catch (DateTimeParseException ignored) { return fallback; }
    }

    private record Encounter(String patientKey, LocalDate date, String department, String address) {}
    private static final class Bucket {
        private int visits;
        private final Set<String> patients = new HashSet<>();
        private void add(Encounter row) { visits++; patients.add(row.patientKey()); }
        private Map<String, Object> toMap(String period) { return Map.of("period", period, "visits", visits, "uniquePatients", patients.size()); }
    }
    private static final class DepartmentBucket {
        private int visits;
        private final Set<String> patients = new HashSet<>();
        private void add(Encounter row) { visits++; patients.add(row.patientKey()); }
        private Map<String, Object> toMap(String department, int total) { return Map.of("department", department, "visits", visits, "uniquePatients", patients.size(), "share", total <= 0 ? 0 : Math.round(visits * 1000f / total) / 10.0); }
    }
}


