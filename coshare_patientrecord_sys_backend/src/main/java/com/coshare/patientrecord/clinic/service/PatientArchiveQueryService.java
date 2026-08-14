package com.coshare.patientrecord.clinic.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.RoleCatalog;
import com.coshare.patientrecord.clinic.service.policy.ClinicVisibilityPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class PatientArchiveQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ClinicDatabaseService databaseService;
    private final ClinicVisibilityPolicy visibilityPolicy;

    public PatientArchiveQueryService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        ClinicDatabaseService databaseService,
        ClinicVisibilityPolicy visibilityPolicy
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.databaseService = databaseService;
        this.visibilityPolicy = visibilityPolicy;
    }

    public Map<String, Object> list(
        int pageNum,
        int pageSize,
        String name,
        String visitNo,
        String visitType,
        String status,
        String dateFrom,
        String dateTo,
        SessionUser user
    ) {
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 200);
        if (usesDepartmentScopedArchiveAccess(user)) {
            return listDepartmentVisiblePatients(
                safePage, safeSize, name, visitNo, visitType, status, dateFrom, dateTo, user
            );
        }
        if (!hasFullHistoryAccess(user)) {
            return listVisibleDb(safePage, safeSize, name, visitNo, visitType, status, dateFrom, dateTo, user);
        }

        List<Object> args = new ArrayList<>();
        String where = where(name, visitNo, visitType, status, dateFrom, dateTo, args);
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM clinic_patients p " + where, Integer.class, args.toArray());
        args.add(safeSize);
        args.add((safePage - 1) * safeSize);
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT p.raw_json FROM clinic_patients p " + where + " ORDER BY p.visit_date DESC, p.updated_at DESC, p.id LIMIT ? OFFSET ?",
            (rs, rowNum) -> readObject(rs.getString("raw_json")),
            args.toArray()
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows.stream().map(this::toMap).toList());
        result.put("total", total == null ? 0 : total);
        result.put("pageNum", safePage);
        result.put("pageSize", safeSize);
        return result;
    }

    public Map<String, Object> detail(String patientId, SessionUser user) {
        ObjectNode patient;
        if (hasFullHistoryAccess(user)) {
            List<ObjectNode> rows = jdbcTemplate.query(
                "SELECT raw_json FROM clinic_patients WHERE id = ?",
                (rs, rowNum) -> readObject(rs.getString("raw_json")),
                patientId
            );
            patient = rows.isEmpty() ? null : rows.get(0);
        } else if (usesDepartmentScopedArchiveAccess(user)) {
            patient = departmentVisiblePatient(patientId, user);
        } else {
            patient = null;
            JsonNode visiblePatients = databaseService.readDbForUser(user).path("patients");
            if (visiblePatients.isArray()) {
                for (JsonNode visible : visiblePatients) {
                    if (patientId.equals(visible.path("id").asText("")) && visible.isObject()) {
                        patient = (ObjectNode) visible.deepCopy();
                        break;
                    }
                }
            }
        }
        if (patient == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "患者不存在或当前账号无权查看");

        ObjectNode fieldValues = objectMapper.createObjectNode();
        jdbcTemplate.query(
            "SELECT field_key, raw_json FROM clinic_record_field_values WHERE patient_id = ? ORDER BY field_key",
            (RowCallbackHandler) rs -> fieldValues.put(rs.getString("field_key"), valueText(readJson(rs.getString("raw_json")))),
            patientId
        );
        if (fieldValues.isEmpty()) {
            List<ObjectNode> legacy = jdbcTemplate.query(
                "SELECT fields_json FROM clinic_record_fields WHERE patient_id = ?",
                (rs, rowNum) -> readObject(rs.getString("fields_json")), patientId
            );
            if (!legacy.isEmpty()) fieldValues.setAll(legacy.get(0));
        }

        ObjectNode archive = firstObject("SELECT raw_json FROM clinic_archive WHERE patient_id = ?", patientId);
        ArrayNode encounters = readArray("SELECT raw_json FROM clinic_patient_encounters WHERE patient_id = ? ORDER BY sort_no ASC, visit_date ASC", patientId);
        ArrayNode documents = objectMapper.createArrayNode();
        jdbcTemplate.query("SELECT raw_json FROM clinic_documents WHERE patient_id = ? ORDER BY uploaded_at ASC, document_key ASC", (RowCallbackHandler) rs -> {
            JsonNode document = readJson(rs.getString("raw_json"));
            if (visibilityPolicy.isClinicAdmin(user) || visibilityPolicy.canReadDocument(document, user)) documents.add(document);
        }, patientId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patient", toMap(patient));
        result.put("fieldValues", toMap(fieldValues));
        result.put("attachments", objectMapper.convertValue(documents, List.class));
        result.put("archive", toMap(archive == null ? objectMapper.createObjectNode() : archive));
        result.put("archiveSubmitted", archive != null && archive.path("submitted").asBoolean(false));
        result.put("archiveVersion", archive == null ? "V0.1-草稿" : archive.path("version").asText("V0.1-草稿"));
        result.put("generatedAt", archive == null ? "" : archive.path("generatedAt").asText(""));
        result.put("encounters", objectMapper.convertValue(encounters, List.class));
        result.put("readOnly", user != null && "doctor".equals(user.role()));
        return result;
    }

    private Map<String, Object> listDepartmentVisiblePatients(
        int page,
        int size,
        String name,
        String visitNo,
        String visitType,
        String status,
        String dateFrom,
        String dateTo,
        SessionUser user
    ) {
        String departmentId = user == null ? "" : safe(user.activeDepartmentId());
        if (departmentId.isBlank()) return pagedResult(List.of(), 0, page, size);

        List<Object> args = new ArrayList<>();
        args.add(departmentId);
        String filters = where(name, visitNo, visitType, status, dateFrom, dateTo, args);
        String visibility = "EXISTS (SELECT 1 FROM pre_ai_encounters e "
            + "WHERE e.source_patient_id = p.id AND e.owning_department_id = ?)";
        String scopedWhere = filters.isBlank()
            ? "WHERE " + visibility
            : "WHERE " + visibility + " AND " + filters.substring(6);

        Integer total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM clinic_patients p " + scopedWhere, Integer.class, args.toArray()
        );
        args.add(size);
        args.add((page - 1) * size);
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT p.raw_json FROM clinic_patients p " + scopedWhere
                + " ORDER BY p.visit_date DESC, p.updated_at DESC, p.id LIMIT ? OFFSET ?",
            (rs, rowNum) -> readObject(rs.getString("raw_json")),
            args.toArray()
        );
        return pagedResult(rows.stream().map(this::toMap).toList(), total == null ? 0 : total, page, size);
    }

    private ObjectNode departmentVisiblePatient(String patientId, SessionUser user) {
        String departmentId = user == null ? "" : safe(user.activeDepartmentId());
        if (departmentId.isBlank()) return null;
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT p.raw_json FROM clinic_patients p WHERE p.id = ? AND EXISTS "
                + "(SELECT 1 FROM pre_ai_encounters e WHERE e.source_patient_id = p.id "
                + "AND e.owning_department_id = ?)",
            (rs, rowNum) -> readObject(rs.getString("raw_json")), patientId, departmentId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> pagedResult(List<?> rows, int total, int page, int size) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("total", total);
        result.put("pageNum", page);
        result.put("pageSize", size);
        return result;
    }

    private Map<String, Object> listVisibleDb(int page, int size, String name, String visitNo, String visitType, String status, String from, String to, SessionUser user) {
        JsonNode patients = databaseService.readDbForUser(user).path("patients");
        List<JsonNode> filtered = new ArrayList<>();
        if (patients.isArray()) for (JsonNode patient : patients) {
            if (!contains(patient.path("name").asText(""), name) || !contains(patient.path("visitNo").asText(""), visitNo)) continue;
            if (!visitType.isBlank() && !visitType.equals(patient.path("visitType").asText(""))) continue;
            if (!status.isBlank() && !status.equals(patient.path("status").asText(""))) continue;
            if (!matchesAnyVisitDate(patient, from, to)) continue;
            filtered.add(patient);
        }
        int start = Math.min((page - 1) * size, filtered.size());
        int end = Math.min(start + size, filtered.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", filtered.subList(start, end).stream().map(this::toMap).toList());
        result.put("total", filtered.size());
        result.put("pageNum", page);
        result.put("pageSize", size);
        return result;
    }

    private String where(String name, String visitNo, String visitType, String status, String from, String to, List<Object> args) {
        List<String> conditions = new ArrayList<>();
        if (!name.isBlank()) { conditions.add("p.name LIKE ?"); args.add("%" + name.trim() + "%"); }
        if (!visitNo.isBlank()) { conditions.add("(p.visit_no LIKE ? OR EXISTS (SELECT 1 FROM clinic_patient_encounters e WHERE e.patient_id = p.id AND e.visit_no LIKE ?))"); args.add("%" + visitNo.trim() + "%"); args.add("%" + visitNo.trim() + "%"); }
        if (!visitType.isBlank()) { conditions.add("(p.visit_type = ? OR EXISTS (SELECT 1 FROM clinic_patient_encounters e WHERE e.patient_id = p.id AND e.visit_type = ?))"); args.add(visitType.trim()); args.add(visitType.trim()); }
        if (!status.isBlank()) { conditions.add("p.status = ?"); args.add(status.trim()); }
        String start = calendarDateKey(from);
        String end = calendarDateKey(to);
        if (!start.isBlank() || !end.isBlank()) {
            StringBuilder dateCondition = new StringBuilder("((p.visit_date IS NOT NULL");
            if (!start.isBlank()) {
                dateCondition.append(" AND LEFT(p.visit_date, 10) >= ?");
                args.add(start);
            }
            if (!end.isBlank()) {
                dateCondition.append(" AND LEFT(p.visit_date, 10) <= ?");
                args.add(end);
            }
            dateCondition.append(") OR EXISTS (SELECT 1 FROM clinic_patient_encounters e WHERE e.patient_id = p.id");
            if (!start.isBlank()) {
                dateCondition.append(" AND LEFT(e.visit_date, 10) >= ?");
                args.add(start);
            }
            if (!end.isBlank()) {
                dateCondition.append(" AND LEFT(e.visit_date, 10) <= ?");
                args.add(end);
            }
            conditions.add(dateCondition.append("))").toString());
        }
        return conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
    }

    private boolean hasFullHistoryAccess(SessionUser user) {
        return user != null && Set.of("admin", "doctor").contains(RoleCatalog.canonicalize(user.role()));
    }

    private boolean usesDepartmentScopedArchiveAccess(SessionUser user) {
        return user != null && Set.of("frontdesk", "reception").contains(RoleCatalog.canonicalize(user.role()));
    }

    private ObjectNode firstObject(String sql, Object arg) {
        List<ObjectNode> rows = jdbcTemplate.query(sql, (rs, rowNum) -> readObject(rs.getString("raw_json")), arg);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ArrayNode readArray(String sql, Object arg) {
        ArrayNode result = objectMapper.createArrayNode();
        jdbcTemplate.query(sql, (RowCallbackHandler) rs -> result.add(readJson(rs.getString("raw_json"))), arg);
        return result;
    }

    private ObjectNode readObject(String value) {
        JsonNode node = readJson(value);
        return node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
    }

    private JsonNode readJson(String value) {
        try { return value == null || value.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(value); }
        catch (Exception ignored) { return objectMapper.createObjectNode(); }
    }

    private String valueText(JsonNode value) { return value == null || value.isNull() ? "" : value.isValueNode() ? value.asText() : value.toString(); }
    private Map<String, Object> toMap(JsonNode node) { return objectMapper.convertValue(node, Map.class); }
    private boolean contains(String value, String query) { return query == null || query.isBlank() || value.contains(query.trim()); }
    private boolean matchesAnyVisitDate(JsonNode patient, String from, String to) {
        JsonNode history = patient.path("encounterHistory");
        if (history.isArray() && !history.isEmpty()) {
            for (JsonNode encounter : history) {
                if (dateMatch(encounter.path("visitDate").asText(""), from, to)) return true;
            }
            return false;
        }
        return dateMatch(patient.path("visitDate").asText(""), from, to);
    }

    private boolean dateMatch(String value, String from, String to) {
        String date = calendarDateKey(value);
        if (date.isBlank()) return false;
        String start = calendarDateKey(from);
        String end = calendarDateKey(to);
        return (start.isBlank() || date.compareTo(start) >= 0) && (end.isBlank() || date.compareTo(end) <= 0);
    }

    private String calendarDateKey(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.matches("\\d{4}-\\d{2}-\\d{2}.*") ? normalized.substring(0, 10) : "";
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }
}
