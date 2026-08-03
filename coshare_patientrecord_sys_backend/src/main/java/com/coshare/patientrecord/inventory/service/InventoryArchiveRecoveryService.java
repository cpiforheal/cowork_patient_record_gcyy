package com.coshare.patientrecord.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recovers the inventory master archive only when it has been lost while the imported mapping detail remains.
 * It never changes clinical records, stock balances, requests or an existing item archive.
 */
@Service
@Profile("mysql")
public class InventoryArchiveRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryArchiveRecoveryService.class);
    private static final String SOURCE_NAME = "mapping_archive_recovery";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public InventoryArchiveRecoveryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverWhenArchiveIsEmpty() {
        Long itemCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inventory_items", Long.class);
        if (itemCount != null && itemCount > 0) return;

        List<MappingRow> rows = jdbcTemplate.query(
            """
            SELECT source_item_name, suggested_unit, rule_type
            FROM inventory_mapping_entries
            WHERE source_item_name IS NOT NULL AND TRIM(source_item_name) <> ''
            ORDER BY source_item_name, id
            """,
            this::mapRow
        );
        if (rows.isEmpty()) {
            log.warn("Inventory archive is empty and no mapping detail is available for recovery.");
            return;
        }

        Map<String, ArchiveDraft> drafts = new LinkedHashMap<>();
        for (MappingRow row : rows) {
            String normalizedName = normalize(row.itemName());
            if (normalizedName.isBlank()) continue;
            ArchiveDraft draft = drafts.computeIfAbsent(normalizedName, ignored -> new ArchiveDraft(row.itemName().trim()));
            draft.add(row);
        }
        for (Map.Entry<String, ArchiveDraft> entry : drafts.entrySet()) {
            String normalizedName = entry.getKey();
            ArchiveDraft draft = entry.getValue();
            String itemId = "item-recovered-" + UUID.nameUUIDFromBytes(normalizedName.getBytes(StandardCharsets.UTF_8));
            String name = draft.commonName();
            String unit = draft.commonUnit();
            String category = draft.commonRuleType();
            ObjectNode raw = objectMapper.createObjectNode();
            raw.put("id", itemId);
            raw.put("name", name);
            raw.put("category", category);
            raw.put("spec", "");
            raw.put("unit", unit);
            raw.put("baseUnit", unit);
            raw.put("issueUnit", unit);
            raw.put("quantityPrecision", 2);
            raw.put("normalizationStatus", "standard");
            raw.put("effectiveLifeManaged", false);
            raw.put("location", "中央仓");
            raw.put("lowStockThreshold", 0);
            raw.put("safetyStock", 0);
            raw.put("sensitive", false);
            raw.put("batchRequired", false);
            raw.put("expiryRequired", false);
            raw.put("enabled", true);
            raw.put("recoveredFromMapping", true);
            raw.put("sourceDetailCount", draft.rowCount());
            jdbcTemplate.update(
                """
                INSERT INTO inventory_items (
                  id, name, category, spec, unit, base_unit, issue_unit, quantity_precision,
                  normalization_status, effective_life_managed, location, low_stock_threshold, safety_stock,
                  is_sensitive, batch_required, expiry_required, enabled, raw_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                itemId, name, category, "", unit, unit, unit, 2, "standard", false, "中央仓", 0, 0,
                false, false, false, true, json(raw)
            );
            jdbcTemplate.update(
                """
                UPDATE inventory_mapping_entries
                SET matched_item_id = ?, matched_item_name = ?
                WHERE LOWER(REPLACE(REPLACE(TRIM(source_item_name), ' ', ''), '　', '')) = ?
                """,
                itemId, name, normalizedName
            );
            jdbcTemplate.update(
                """
                UPDATE inventory_item_aliases
                SET item_id = ?, status = 'confirmed'
                WHERE normalized_alias = ?
                """,
                itemId, normalizedName
            );
        }
        log.info("Recovered {} de-duplicated inventory archive entries from {} mapping detail rows.", drafts.size(), rows.size());
    }

    private MappingRow mapRow(ResultSet resultSet, int ignored) throws SQLException {
        return new MappingRow(
            value(resultSet.getString("source_item_name")),
            value(resultSet.getString("suggested_unit")),
            value(resultSet.getString("rule_type"))
        );
    }

    private String json(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize recovered inventory item", error);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String value(String source) {
        return source == null ? "" : source.trim();
    }

    private record MappingRow(String itemName, String unit, String ruleType) {}

    private static final class ArchiveDraft {
        private final Map<String, Integer> names = new LinkedHashMap<>();
        private final Map<String, Integer> units = new LinkedHashMap<>();
        private final Map<String, Integer> ruleTypes = new LinkedHashMap<>();
        private int rowCount;

        private ArchiveDraft(String initialName) {
            names.put(initialName, 0);
        }

        private void add(MappingRow row) {
            rowCount++;
            increment(names, row.itemName());
            increment(units, row.unit());
            increment(ruleTypes, row.ruleType());
        }

        private String commonName() {
            return mostCommon(names, "未命名耗材");
        }

        private String commonUnit() {
            return mostCommon(units, "个");
        }

        private String commonRuleType() {
            return mostCommon(ruleTypes, "耗材");
        }

        private int rowCount() {
            return rowCount;
        }

        private static void increment(Map<String, Integer> counts, String value) {
            if (value == null || value.isBlank()) return;
            counts.merge(value.trim(), 1, Integer::sum);
        }

        private static String mostCommon(Map<String, Integer> counts, String fallback) {
            return counts.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                    .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElse(fallback);
        }
    }
}
