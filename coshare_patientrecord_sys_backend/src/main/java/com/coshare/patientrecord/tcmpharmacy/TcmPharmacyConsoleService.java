package com.coshare.patientrecord.tcmpharmacy;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

@Service
@Profile("mysql")
public class TcmPharmacyConsoleService {

    private static final String STATE_ID = "default";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> DATA_SOURCES = Set.of("DEMO_SEED", "MANUAL", "EXCEL", "EMPTY");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TcmPharmacyConsoleService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> layout(SessionUser user) {
        requireAdmin(user);
        return buildLayout();
    }

    @Transactional
    public Map<String, Object> save(JsonNode payload, SessionUser user) {
        requireAdmin(user);
        long revision = payload.path("revision").asLong(-1);
        JsonNode slotsNode = payload.path("slots");
        if (!slotsNode.isArray() || slotsNode.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提交完整的药架格位");
        }

        List<ShelfConfig> shelves = loadShelves();
        Map<Integer, ShelfConfig> shelfByNo = new LinkedHashMap<>();
        for (ShelfConfig shelf : shelves) shelfByNo.put(shelf.shelfNo(), shelf);

        int expected = shelves.stream().mapToInt(shelf -> shelf.layerCount() * shelf.columnCount()).sum();
        if (slotsNode.size() != expected) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "格位数量与药架配置不一致，应为 " + expected + " 个");
        }

        Set<String> positions = new HashSet<>();
        Set<String> codes = new HashSet<>();
        Set<String> names = new HashSet<>();
        List<SlotInput> inputs = new ArrayList<>();
        for (JsonNode node : slotsNode) {
            int shelfNo = node.path("shelfNo").asInt(0);
            int layerNo = node.path("layerNo").asInt(0);
            int columnNo = node.path("columnNo").asInt(0);
            ShelfConfig shelf = shelfByNo.get(shelfNo);
            if (shelf == null || layerNo < 1 || layerNo > shelf.layerCount()
                || columnNo < 1 || columnNo > shelf.columnCount()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "存在超出药架配置的格位");
            }
            String position = positionKey(shelfNo, layerNo, columnNo);
            if (!positions.add(position)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "格位重复：" + position);
            }

            String code = text(node, "herbCode");
            String name = text(node, "name");
            String alias = text(node, "alias");
            String pinyin = text(node, "pinyin");
            String source = text(node, "dataSource").toUpperCase();
            if (code.isEmpty() && name.isEmpty()) {
                inputs.add(new SlotInput(shelfNo, layerNo, columnNo, null, null, null, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, "EMPTY"));
                continue;
            }
            if (code.isEmpty() && !name.isEmpty()) {
                code = "M-" + UUID.randomUUID().toString().substring(0, 8);
                source = "MANUAL";
            }
            if (name.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "格位 " + position + " 的药品编码和名称必须同时存在");
            }
            if (name.length() > 80 || alias.length() > 160 || pinyin.length() > 160 || code.length() > 64) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "药品名称、别名、拼音或编码过长");
            }
            if (!codes.add(code)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "药品编码重复：" + code);
            }
            if (!names.add(name)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "药名重复：" + name);
            }
            BigDecimal stock = decimal(node, "stock");
            BigDecimal warn = decimal(node, "warnThreshold");
            if (stock.signum() < 0 || warn.signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " 的库存和预警阈值不能为负数");
            }
            if (!DATA_SOURCES.contains(source) || "EMPTY".equals(source)) source = "MANUAL";
            inputs.add(new SlotInput(shelfNo, layerNo, columnNo, code, name,
                alias.isEmpty() ? null : alias, pinyin.isEmpty() ? null : pinyin, stock, warn, source));
        }

        for (ShelfConfig shelf : shelves) {
            for (int layer = 1; layer <= shelf.layerCount(); layer++) {
                for (int column = 1; column <= shelf.columnCount(); column++) {
                    if (!positions.contains(positionKey(shelf.shelfNo(), layer, column))) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "缺少格位：" + shelf.shelfNo() + "架 " + layer + "层 " + column + "格");
                    }
                }
            }
        }

        Map<String, String> oldByCode = new HashMap<>();
        jdbcTemplate.query(
            "SELECT herb_code, shelf_no, layer_no, column_no FROM tcm_pharmacy_herb_slots WHERE herb_code IS NOT NULL",
            rs -> {
                oldByCode.put(rs.getString("herb_code"),
                    positionKey(rs.getInt("shelf_no"), rs.getInt("layer_no"), rs.getInt("column_no")));
            }
        );

        long current = lockRevision();
        if (revision != current) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "地图已被其他管理员修改，请刷新后重试");
        }

        int moved = 0;
        int occupied = 0;
        for (SlotInput input : inputs) {
            if (input.code() == null) continue;
            occupied++;
            String oldPosition = oldByCode.get(input.code());
            if (oldPosition != null && !oldPosition.equals(positionKey(input.shelfNo(), input.layerNo(), input.columnNo()))) {
                moved++;
            }
        }

        jdbcTemplate.update("DELETE FROM tcm_pharmacy_herb_slots");
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO tcm_pharmacy_herb_slots
              (shelf_no, layer_no, column_no, herb_code, herb_name, alias, pinyin,
               stock_quantity, warn_threshold, data_source, updated_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            inputs.stream().map(input -> new Object[]{
                input.shelfNo(), input.layerNo(), input.columnNo(), input.code(), input.name(),
                input.alias(), input.pinyin(), input.stock(), input.warn(), input.source(), user.name()
            }).toList()
        );
        jdbcTemplate.update(
            """
            UPDATE tcm_pharmacy_herb_map_state
            SET revision = revision + 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = ?
            """,
            user.name(), STATE_ID
        );
        writeAudit(user, revision, occupied, inputs.size() - occupied, moved);
        return buildLayout();
    }

    private Map<String, Object> buildLayout() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> shelves = jdbcTemplate.query(
            """
            SELECT shelf_no, name, orientation, layer_count, column_count, sort_order,
                   layout_x, layout_y, layout_w, layout_h
            FROM tcm_pharmacy_herb_shelves ORDER BY sort_order, shelf_no
            """,
            (rs, rowNum) -> {
                Map<String, Object> shelf = new LinkedHashMap<>();
                shelf.put("shelfNo", rs.getInt("shelf_no"));
                shelf.put("name", rs.getString("name"));
                shelf.put("orientation", rs.getString("orientation"));
                shelf.put("layerCount", rs.getInt("layer_count"));
                shelf.put("columnCount", rs.getInt("column_count"));
                shelf.put("sortOrder", rs.getInt("sort_order"));
                shelf.put("x", rs.getInt("layout_x"));
                shelf.put("y", rs.getInt("layout_y"));
                shelf.put("w", rs.getInt("layout_w"));
                shelf.put("h", rs.getInt("layout_h"));
                return shelf;
            }
        );
        List<Map<String, Object>> slots = jdbcTemplate.query(
            """
            SELECT shelf_no, layer_no, column_no, herb_code, herb_name, alias, pinyin,
                   stock_quantity, warn_threshold, data_source
            FROM tcm_pharmacy_herb_slots ORDER BY shelf_no, layer_no, column_no
            """,
            (rs, rowNum) -> {
                Map<String, Object> slot = new LinkedHashMap<>();
                slot.put("shelfNo", rs.getInt("shelf_no"));
                slot.put("layerNo", rs.getInt("layer_no"));
                slot.put("columnNo", rs.getInt("column_no"));
                slot.put("herbCode", rs.getString("herb_code"));
                slot.put("name", rs.getString("herb_name"));
                slot.put("alias", rs.getString("alias"));
                slot.put("pinyin", rs.getString("pinyin"));
                slot.put("stock", rs.getBigDecimal("stock_quantity"));
                slot.put("warnThreshold", rs.getBigDecimal("warn_threshold"));
                slot.put("dataSource", rs.getString("data_source"));
                return slot;
            }
        );

        List<Map<String, Object>> state = jdbcTemplate.query(
            "SELECT revision, updated_by, updated_at FROM tcm_pharmacy_herb_map_state WHERE id = ?",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("revision", rs.getLong("revision"));
                row.put("updatedBy", rs.getString("updated_by"));
                row.put("updatedAt", rs.getTimestamp("updated_at") == null
                    ? "" : rs.getTimestamp("updated_at").toLocalDateTime().format(DATE_TIME));
                return row;
            },
            STATE_ID
        );

        int occupied = 0;
        int outOfStock = 0;
        int lowStock = 0;
        for (Map<String, Object> slot : slots) {
            if (slot.get("herbCode") == null) continue;
            occupied++;
            BigDecimal stock = (BigDecimal) slot.get("stock");
            BigDecimal warn = (BigDecimal) slot.get("warnThreshold");
            if (stock == null || stock.signum() <= 0) outOfStock++;
            else if (warn != null && stock.compareTo(warn) <= 0) lowStock++;
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", slots.size());
        stats.put("occupied", occupied);
        stats.put("empty", slots.size() - occupied);
        stats.put("outOfStock", outOfStock);
        stats.put("lowStock", lowStock);

        result.put("revision", state.isEmpty() ? 1L : state.get(0).get("revision"));
        result.put("updatedBy", state.isEmpty() ? "" : state.get(0).get("updatedBy"));
        result.put("updatedAt", state.isEmpty() ? "" : state.get(0).get("updatedAt"));
        result.put("shelves", shelves);
        result.put("slots", slots);
        result.put("stats", stats);
        return result;
    }

    private long lockRevision() {
        List<Long> rows = jdbcTemplate.query(
            "SELECT revision FROM tcm_pharmacy_herb_map_state WHERE id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getLong(1), STATE_ID
        );
        if (rows.isEmpty()) {
            jdbcTemplate.update(
                "INSERT INTO tcm_pharmacy_herb_map_state (id, revision, updated_by) VALUES (?, 1, 'system')",
                STATE_ID
            );
            return 1L;
        }
        return rows.get(0);
    }

    private List<ShelfConfig> loadShelves() {
        return jdbcTemplate.query(
            "SELECT shelf_no, layer_count, column_count FROM tcm_pharmacy_herb_shelves ORDER BY sort_order, shelf_no",
            (rs, rowNum) -> new ShelfConfig(
                rs.getInt("shelf_no"), rs.getInt("layer_count"), rs.getInt("column_count")
            )
        );
    }

    private void writeAudit(SessionUser user, long fromRevision, int occupied, int empty, int moved) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("revisionFrom", fromRevision);
        detail.put("revisionTo", fromRevision + 1);
        detail.put("occupied", occupied);
        detail.put("empty", empty);
        detail.put("moved", moved);
        jdbcTemplate.update(
            """
            INSERT INTO clinic_audit_logs
              (id, time, operator, role, patient, patient_id, module, action, result, raw_json)
            VALUES (?, ?, ?, ?, NULL, NULL, 'TCM_PHARMACY', 'HERB_MAP_SAVE', 'SUCCESS', CAST(? AS JSON))
            """,
            "tcm-herb-map-" + UUID.randomUUID(),
            LocalDateTime.now().format(DATE_TIME),
            user.name(), user.role() == null ? "" : user.role(),
            json(detail)
        );
    }

    private static void requireAdmin(SessionUser user) {
        if (user == null || !"admin".equalsIgnoreCase(user.role() == null ? "" : user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可访问中药房控制台");
        }
    }

    private static String positionKey(int shelfNo, int layerNo, int columnNo) {
        return shelfNo + "-" + layerNo + "-" + columnNo;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        return value.asText("").trim();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "库存或预警阈值格式不正确");
        }
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalStateException("JSON 序列化失败", error);
        }
    }

    private record ShelfConfig(int shelfNo, int layerCount, int columnCount) {}

    private record SlotInput(int shelfNo, int layerNo, int columnNo, String code, String name,
                             String alias, String pinyin, BigDecimal stock, BigDecimal warn, String source) {}
}
