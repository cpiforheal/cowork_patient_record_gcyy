package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.InventoryAccessService;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Stores editable daily accounting sheets without producing any inventory movement. */
@Service
@Profile("mysql")
public class InventoryDepartmentDraftService {

    private static final Map<String, String> DEPARTMENTS = departments();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryAccessService inventoryAccess;
    private final InventoryRepository repository;

    public InventoryDepartmentDraftService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        InventoryAccessService inventoryAccess,
        InventoryRepository repository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.inventoryAccess = inventoryAccess;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ObjectNode read(String departmentKey, LocalDate businessDate, SessionUser user) {
        String departmentName = requireDepartment(departmentKey, user);
        ObjectNode result = jdbcTemplate.query(
            "SELECT department_key, department_name, business_date, template_version, revision, operator_name, raw_json, updated_at "
                + "FROM inventory_department_daily_drafts WHERE department_key = ? AND business_date = ?",
            rowSet -> rowSet.next() ? row(rowSet) : empty(departmentKey, departmentName, businessDate),
            departmentKey,
            businessDate
        );
        result.put("exists", result.has("id"));
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode summary(LocalDate businessDate, SessionUser user) {
        if (!inventoryAccess.canViewAllDepartments(user)) {
            return JsonNodeFactory.instance.objectNode().set("list", JsonNodeFactory.instance.arrayNode());
        }
        ArrayNode rows = jdbcTemplate.query(
            "SELECT department_key, department_name, business_date, template_version, revision, operator_name, raw_json, updated_at "
                + "FROM inventory_department_daily_drafts WHERE business_date = ? ORDER BY department_name",
            resultSet -> {
                ArrayNode list = JsonNodeFactory.instance.arrayNode();
                while (resultSet.next()) list.add(row(resultSet));
                return list;
            },
            businessDate
        );
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("businessDate", businessDate.toString());
        result.set("list", rows);
        return result;
    }

    @Transactional
    public ObjectNode save(JsonNode payload, SessionUser user) {
        String departmentKey = text(payload, "departmentKey");
        String departmentName = requireDepartment(departmentKey, user);
        LocalDate businessDate = parseDate(text(payload, "businessDate"));
        JsonNode lines = payload == null ? null : payload.path("lines");
        if (!lines.isArray() || lines.size() > 400) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "耗材明细格式不正确");
        }
        int expectedRevision = Math.max(payload.path("revision").asInt(0), 0);
        String templateVersion = text(payload, "templateVersion");
        if (templateVersion.isBlank()) templateVersion = "department-template-v1";

        ObjectNode stored = JsonNodeFactory.instance.objectNode();
        stored.put("monthDays", Math.max(payload.path("monthDays").asInt(30), 1));
        stored.put("templateVersion", templateVersion);
        stored.set("groupVolumes", payload.path("groupVolumes").isObject() ? payload.path("groupVolumes") : JsonNodeFactory.instance.objectNode());
        stored.set("lines", lines);

        DraftVersion current = jdbcTemplate.query(
            "SELECT id, revision FROM inventory_department_daily_drafts WHERE department_key = ? AND business_date = ? FOR UPDATE",
            resultSet -> resultSet.next() ? new DraftVersion(resultSet.getString("id"), resultSet.getInt("revision")) : null,
            departmentKey,
            businessDate
        );
        if (current == null) {
            if (expectedRevision != 0) throw staleDraft();
            jdbcTemplate.update(
                "INSERT INTO inventory_department_daily_drafts "
                    + "(id, department_key, department_name, business_date, template_version, revision, operator_name, raw_json) "
                    + "VALUES (?, ?, ?, ?, ?, 1, ?, CAST(? AS JSON))",
                "inv-department-draft-" + UUID.randomUUID(),
                departmentKey,
                departmentName,
                businessDate,
                templateVersion,
                user.name(),
                json(stored)
            );
        } else {
            if (current.revision() != expectedRevision) throw staleDraft();
            jdbcTemplate.update(
                "UPDATE inventory_department_daily_drafts "
                    + "SET template_version = ?, revision = revision + 1, operator_name = ?, raw_json = CAST(? AS JSON) "
                    + "WHERE id = ?",
                templateVersion,
                user.name(),
                json(stored),
                current.id()
            );
        }
        repository.log(user.name(), "保存科室耗材日草稿", "department_daily_draft", departmentName + " " + businessDate, "仅保存核算草稿，未生成库存流水");
        return read(departmentKey, businessDate, user);
    }

    private ObjectNode row(ResultSet resultSet) throws SQLException {
        ObjectNode result = readJson(resultSet.getString("raw_json"));
        result.put("id", resultSet.getString("id"));
        result.put("departmentKey", resultSet.getString("department_key"));
        result.put("departmentName", resultSet.getString("department_name"));
        result.put("businessDate", resultSet.getDate("business_date").toLocalDate().toString());
        result.put("templateVersion", resultSet.getString("template_version"));
        result.put("revision", resultSet.getInt("revision"));
        result.put("operator", resultSet.getString("operator_name"));
        result.put("updatedAt", resultSet.getTimestamp("updated_at").toLocalDateTime().toString());
        return result;
    }

    private ObjectNode empty(String departmentKey, String departmentName, LocalDate businessDate) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("departmentKey", departmentKey);
        result.put("departmentName", departmentName);
        result.put("businessDate", businessDate.toString());
        result.put("revision", 0);
        return result;
    }

    private String requireDepartment(String departmentKey, SessionUser user) {
        String departmentName = DEPARTMENTS.get(departmentKey == null ? "" : departmentKey.trim());
        if (departmentName == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知科室核算模板");
        if (!inventoryAccess.canViewAllDepartments(user) && !departmentName.equals(user.department())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问其他科室的核算草稿");
        }
        return departmentName;
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "业务日期格式不正确");
        }
    }

    private String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("核算草稿序列化失败", error);
        }
    }

    private ObjectNode readJson(String value) {
        try {
            JsonNode parsed = objectMapper.readTree(value);
            return parsed != null && parsed.isObject() ? (ObjectNode) parsed : JsonNodeFactory.instance.objectNode();
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("核算草稿数据损坏", error);
        }
    }

    private ResponseStatusException staleDraft() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "草稿已被其他人更新，请刷新后再保存");
    }

    private static Map<String, String> departments() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("physiotherapy", "理疗室");
        result.put("laboratory", "检验科");
        result.put("nursing", "护理部");
        result.put("tcm", "中医科");
        result.put("operating", "手术室");
        result.put("anesthesia", "麻醉室");
        result.put("endoscopy", "胃肠镜");
        result.put("inspection", "检查室");
        result.put("logistics", "后勤保洁");
        result.put("western-pharmacy", "西药房");
        result.put("cashier", "收费室");
        result.put("tcm-pharmacy", "中药房");
        return Map.copyOf(result);
    }

    private record DraftVersion(String id, int revision) {}
}
