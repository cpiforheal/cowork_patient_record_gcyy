package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.InventoryAccessService;
import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class InventoryOpeningImportService {

    private static final List<String> HEADERS = List.of(
        "物资名称*", "规格", "分类", "基准单位", "领用单位*", "换算系数*", "期初数量*",
        "批号", "有效期", "供应商", "存放位置", "预警线"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryRepository repository;
    private final InventoryLedgerRepository ledgerRepository;
    private final InventoryAccessService accessService;

    public InventoryOpeningImportService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        InventoryRepository repository,
        InventoryLedgerRepository ledgerRepository,
        InventoryAccessService accessService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.ledgerRepository = ledgerRepository;
        this.accessService = accessService;
    }

    public byte[] template() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("期初库存");
            Row header = sheet.createRow(0);
            for (int index = 0; index < HEADERS.size(); index += 1) {
                header.createCell(index).setCellValue(HEADERS.get(index));
                sheet.setColumnWidth(index, index == 0 ? 5200 : 3600);
            }
            Row example = sheet.createRow(1);
            String[] values = { "PVC手套", "100只/盒", "手套", "只", "盒", "100", "10", "", "", "演示供应商", "中心库", "" };
            for (int index = 0; index < values.length; index += 1) {
                example.createCell(index).setCellValue(values[index]);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("生成期初模板失败", error);
        }
    }

    public ObjectNode preview(MultipartFile file, SessionUser user) {
        requireWarehouse(user);
        List<ImportRow> rows = parse(file);
        ArrayNode valid = objectMapper.createArrayNode();
        ArrayNode errors = objectMapper.createArrayNode();
        for (ImportRow row : rows) {
            try {
                ObjectNode preview = row.preview(objectMapper, findItem(row.name(), row.spec()));
                valid.add(preview);
            } catch (RuntimeException error) {
                ObjectNode errorRow = objectMapper.createObjectNode();
                errorRow.put("row", row.rowNumber());
                errorRow.put("name", row.name());
                errorRow.put("message", error.getMessage());
                errors.add(errorRow);
            }
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("rowCount", rows.size());
        result.set("rows", valid);
        result.set("errors", errors);
        return result;
    }

    @Transactional
    public ObjectNode commit(MultipartFile file, SessionUser user) {
        requireWarehouse(user);
        List<ImportRow> rows = parse(file);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Excel 没有可导入的数据行");
        int created = 0;
        int updated = 0;
        for (ImportRow row : rows) {
            row.validate();
            ExistingItem existing = findItem(row.name(), row.spec());
            ObjectNode item;
            if (existing == null) {
                item = newItem(row);
                created += 1;
            } else {
                item = repository.loadItem(existing.id());
                updated += 1;
                applyItemFields(item, row, existing.baseUnit());
            }
            String itemId = item.path("id").asText();
            repository.upsertItem(item);
            saveConversion(itemId, row.issueUnit(), item.path("baseUnit").asText(), row.factor());
            boolean batchRequired = item.path("batchRequired").asBoolean(false);
            boolean expiryRequired = item.path("expiryRequired").asBoolean(false);
            if (batchRequired && row.batchNo().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第 " + row.rowNumber() + " 行 " + row.name() + " 必须填写批号");
            }
            if (expiryRequired && row.expiryDate().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第 " + row.rowNumber() + " 行 " + row.name() + " 必须填写有效期");
            }
            ObjectNode batch = repository.reusableInboundBatch(itemId, row.batchNo(), row.expiryDate(), batchRequired);
            String batchId = batch.path("id").asText();
            repository.putQuantity(batch, "quantity", repository.quantity(batch, "quantity").add(row.baseQuantity()));
            batch.put("batchNo", row.batchNo().isBlank() ? "常备库存" : row.batchNo());
            batch.put("expiryDate", row.expiryDate());
            batch.put("location", row.location().isBlank() ? repository.itemLocation(itemId) : row.location());
            batch.put("source", row.supplier());
            if (batch.path("createdAt").asText("").isBlank()) batch.put("createdAt", LocalDateTime.now().toString());
            repository.upsertBatch(batch);
            var central = ledgerRepository.lockBalance(InventoryLedgerRepository.CENTRAL_LOCATION, itemId, batchId);
            ledgerRepository.changeBalance(central.id(), row.baseQuantity(), BigDecimal.ZERO);
            ledgerRepository.syncLegacyCentralBatch(batchId);
            ledgerRepository.movement(
                "OPENING_BALANCE", itemId, batchId, null, InventoryLedgerRepository.CENTRAL_LOCATION,
                row.baseQuantity(), null, "OPENING_IMPORT", batchId, null, null, user.name(), "期初库存导入"
            );
        }
        repository.log(user.name(), "导入期初库存", "opening", "Excel",
            "新增 " + created + " 项，更新 " + updated + " 项，共 " + rows.size() + " 行");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("rows", rows.size());
        result.put("created", created);
        result.put("updated", updated);
        return result;
    }

    private ObjectNode newItem(ImportRow row) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("id", "item-import-" + UUID.randomUUID());
        item.put("name", row.name());
        item.put("spec", row.spec());
        item.put("category", row.category());
        item.put("unit", row.issueUnit());
        item.put("baseUnit", row.baseUnit().isBlank() ? row.issueUnit() : row.baseUnit());
        item.put("issueUnit", row.issueUnit());
        item.put("quantityPrecision", 2);
        item.put("normalizationStatus", "standard");
        item.put("effectiveLifeManaged", false);
        item.put("location", row.location());
        item.put("lowStockThreshold", row.warningLine().signum() > 0 ? row.warningLine() : row.defaultWarningLine());
        item.put("safetyStock", BigDecimal.ZERO);
        item.put("sensitive", false);
        item.put("batchRequired", false);
        item.put("expiryRequired", false);
        item.put("enabled", true);
        return item;
    }

    private void applyItemFields(ObjectNode item, ImportRow row, String existingBaseUnit) {
        if (item.path("category").asText("").isBlank()) item.put("category", row.category());
        if (item.path("unit").asText("").isBlank()) item.put("unit", row.issueUnit());
        item.put("issueUnit", row.issueUnit());
        item.put("baseUnit", existingBaseUnit == null || existingBaseUnit.isBlank() ? row.baseUnit() : existingBaseUnit);
        if (item.path("lowStockThreshold").asDouble(0) <= 0) {
            item.put("lowStockThreshold", row.warningLine().signum() > 0 ? row.warningLine() : row.defaultWarningLine());
        }
        if (item.path("location").asText("").isBlank() && !row.location().isBlank()) item.put("location", row.location());
    }

    private void saveConversion(String itemId, String issueUnit, String baseUnit, BigDecimal factor) {
        if (issueUnit.equals(baseUnit) || factor.compareTo(BigDecimal.ONE) == 0) return;
        Integer exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM inventory_unit_conversions WHERE item_id = ? AND source_unit = ? AND target_unit = ? AND status = 'active'",
            Integer.class, itemId, issueUnit, baseUnit
        );
        if (exists != null && exists > 0) return;
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("itemId", itemId);
        raw.put("sourceUnit", issueUnit);
        raw.put("targetUnit", baseUnit);
        raw.put("factor", factor);
        jdbcTemplate.update(
            """
            INSERT INTO inventory_unit_conversions (id, item_id, source_unit, target_unit, factor, status, raw_json, created_at)
            VALUES (?, ?, ?, ?, ?, 'active', ?, ?)
            """,
            "unit-conversion-" + UUID.randomUUID(), itemId, issueUnit, baseUnit, factor,
            raw.toString(), LocalDateTime.now().toString()
        );
    }

    private ExistingItem findItem(String name, String spec) {
        List<ExistingItem> rows = jdbcTemplate.query(
            """
            SELECT id, name, spec, base_unit FROM inventory_items
            WHERE name = ? AND COALESCE(spec, '') = ?
            ORDER BY enabled DESC, updated_at DESC LIMIT 1
            """,
            (resultSet, rowNum) -> new ExistingItem(
                resultSet.getString("id"), resultSet.getString("name"),
                resultSet.getString("spec"), resultSet.getString("base_unit")
            ),
            name, spec
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<ImportRow> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传 Excel 文件");
        List<ImportRow> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int index = 1; index <= sheet.getLastRowNum(); index += 1) {
                Row row = sheet.getRow(index);
                if (row == null) continue;
                String name = cell(formatter, row, 0);
                if (name.isBlank() && cell(formatter, row, 4).isBlank()) continue;
                rows.add(new ImportRow(
                    index + 1,
                    name,
                    cell(formatter, row, 1),
                    cell(formatter, row, 2),
                    cell(formatter, row, 3),
                    cell(formatter, row, 4),
                    decimal(cell(formatter, row, 5), BigDecimal.ONE),
                    decimal(cell(formatter, row, 6), BigDecimal.ZERO),
                    cell(formatter, row, 7),
                    cell(formatter, row, 8),
                    cell(formatter, row, 9),
                    cell(formatter, row, 10),
                    decimal(cell(formatter, row, 11), BigDecimal.ZERO)
                ));
            }
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Excel 解析失败：" + error.getMessage());
        }
        return rows;
    }

    private static String cell(DataFormatter formatter, Row row, int index) {
        Cell cell = row.getCell(index);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private static BigDecimal decimal(String value, BigDecimal fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return new BigDecimal(value.replace(",", "")).setScale(4, RoundingMode.HALF_UP);
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数字格式不正确：" + value);
        }
    }

    private void requireWarehouse(SessionUser user) {
        if (user == null || !accessService.hasCapability(user, "inventory:issue")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅库管可以导入期初库存");
        }
    }

    private record ExistingItem(String id, String name, String spec, String baseUnit) {}

    private record ImportRow(
        int rowNumber, String name, String spec, String category, String baseUnit, String issueUnit,
        BigDecimal factor, BigDecimal issueQuantity, String batchNo, String expiryDate,
        String supplier, String location, BigDecimal warningLine
    ) {
        void validate() {
            if (name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第 " + rowNumber + " 行物资名称不能为空");
            if (issueUnit.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第 " + rowNumber + " 行领用单位不能为空");
            if (factor.signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第 " + rowNumber + " 行换算系数必须大于 0");
            if (factor.compareTo(BigDecimal.ONE) != 0 && baseUnit.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第 " + rowNumber + " 行换算系数不为 1 时必须填写基准单位");
            }
            if (issueQuantity.signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第 " + rowNumber + " 行期初数量必须大于 0");
            if (!expiryDate.isBlank()) {
                try {
                    LocalDate.parse(expiryDate);
                } catch (Exception error) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第 " + rowNumber + " 行有效期格式应为 YYYY-MM-DD");
                }
            }
        }

        BigDecimal baseQuantity() {
            return issueQuantity.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal defaultWarningLine() {
            BigDecimal line = baseQuantity().multiply(new BigDecimal("0.10")).setScale(0, RoundingMode.CEILING);
            return line.signum() <= 0 ? BigDecimal.ONE : line;
        }

        ObjectNode preview(ObjectMapper mapper, ExistingItem existing) {
            validate();
            ObjectNode row = mapper.createObjectNode();
            row.put("row", rowNumber);
            row.put("name", name);
            row.put("spec", spec);
            row.put("category", category);
            row.put("baseUnit", baseUnit.isBlank() ? issueUnit : baseUnit);
            row.put("issueUnit", issueUnit);
            row.put("factor", factor);
            row.put("issueQuantity", issueQuantity);
            row.put("baseQuantity", baseQuantity());
            row.put("batchNo", batchNo);
            row.put("expiryDate", expiryDate);
            row.put("supplier", supplier);
            row.put("location", location);
            row.put("warningLine", warningLine.signum() > 0 ? warningLine : defaultWarningLine());
            row.put("status", existing == null ? "NEW" : "MATCHED");
            if (existing != null) row.put("itemId", existing.id());
            return row;
        }
    }
}
