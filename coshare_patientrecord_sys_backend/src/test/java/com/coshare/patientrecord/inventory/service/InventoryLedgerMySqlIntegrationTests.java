package com.coshare.patientrecord.inventory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.coshare.patientrecord.inventory.service.builder.InventorySummaryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryLedgerMySqlIntegrationTests {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("inventory_ledger_test")
        .withUsername("inventory_test")
        .withPassword("inventory_test_password");

    private static final String DEPARTMENT_ID = "dept-inventory-test";
    private static final String DEPARTMENT_NAME = "库存测试科";
    private static final String DEPARTMENT_LOCATION = "loc-dept-inventory-test";
    private static final SessionUser ADMIN = new SessionUser(
        "inventory-admin", "inventory-admin", "库存管理员", "admin", "管理员",
        DEPARTMENT_ID, DEPARTMENT_NAME, false, Instant.now().plusSeconds(3600)
    );

    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private InventoryLedgerRepository ledger;
    private InventoryLedgerService ledgerService;
    private InventoryStageConsumptionService consumptionService;
    private boolean containerStarted;

    @BeforeAll
    void initializeDatabase() {
        String externalUrl = System.getenv("CLINIC_TEST_MYSQL_URL");
        String jdbcUrl;
        String username;
        String password;
        if (externalUrl == null || externalUrl.isBlank()) {
            MYSQL.start();
            containerStarted = true;
            jdbcUrl = MYSQL.getJdbcUrl();
            username = MYSQL.getUsername();
            password = MYSQL.getPassword();
        } else {
            jdbcUrl = externalUrl;
            username = environmentValue("CLINIC_TEST_MYSQL_USERNAME", "root");
            password = environmentValue("CLINIC_TEST_MYSQL_PASSWORD", "");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(jdbcUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        mapper = new ObjectMapper();
        ledger = new InventoryLedgerRepository(jdbc, mapper);
        InventoryRepository legacy = new InventoryRepository(jdbc, mapper, new InventorySummaryBuilder(mapper));
        ledgerService = new InventoryLedgerService(ledger, legacy);
        consumptionService = new InventoryStageConsumptionService(ledger);
    }

    @AfterAll
    void stopContainer() {
        if (containerStarted && MYSQL.isRunning()) MYSQL.stop();
    }

    @BeforeEach
    void resetInventory() {
        jdbc.update("DELETE FROM inventory_exception_tasks");
        jdbc.update("DELETE FROM inventory_consumption_details");
        jdbc.update("DELETE FROM inventory_consumption_events");
        jdbc.update("DELETE FROM inventory_stage_consumption_commands");
        jdbc.update("DELETE FROM inventory_package_lines");
        jdbc.update("DELETE FROM inventory_packages");
        jdbc.update("DELETE FROM inventory_ledger_movements");
        jdbc.update("DELETE FROM inventory_transfer_lines");
        jdbc.update("DELETE FROM inventory_transfers");
        jdbc.update("DELETE FROM inventory_batch_balances");
        jdbc.update("DELETE FROM inventory_batches");
        jdbc.update("DELETE FROM inventory_items");
        jdbc.update("DELETE FROM inventory_opening_suggestions");
        jdbc.update("DELETE FROM inventory_locations");
        jdbc.update("DELETE FROM clinic_departments WHERE id = ?", DEPARTMENT_ID);
        jdbc.update(
            "INSERT INTO clinic_departments (id, code, name, status, raw_json) VALUES (?, ?, ?, 'ACTIVE', JSON_OBJECT('status', 'ACTIVE'))",
            DEPARTMENT_ID, "DEPT-INVENTORY-TEST", DEPARTMENT_NAME
        );
        jdbc.update(
            """
            INSERT INTO inventory_locations
              (id, location_type, name, status, opening_confirmed)
            VALUES ('loc-central', 'CENTRAL', '中央仓库', 'ACTIVE', TRUE),
                   ('loc-in-transit', 'IN_TRANSIT', '配送在途', 'ACTIVE', TRUE)
            """
        );
        jdbc.update(
            """
            INSERT INTO inventory_locations
              (id, location_type, department_id, department_name_snapshot, name, status, opening_confirmed)
            VALUES (?, 'DEPARTMENT', ?, ?, ?, 'ACTIVE', TRUE)
            """,
            DEPARTMENT_LOCATION, DEPARTMENT_ID, DEPARTMENT_NAME, DEPARTMENT_NAME + "科室库"
        );
    }

    @Test
    void approvalOnlyReservesThenIssueAndReceiveMoveAcrossThreeLocationsIdempotently() {
        seedItemAndBatch("item-transfer", "batch-transfer", "2026-08-01", new BigDecimal("10"), "loc-central");
        ObjectNode request = request("request-transfer", "line-transfer", "item-transfer", new BigDecimal("4"));

        ObjectNode firstReservation = ledgerService.reserveRequest(request, ADMIN);
        ObjectNode repeatedReservation = ledgerService.reserveRequest(request, ADMIN);

        assertEquals(firstReservation.path("id").asText(), repeatedReservation.path("id").asText());
        assertBalance("loc-central", "batch-transfer", "10.00", "4.00");
        assertEquals(0, count("SELECT COUNT(*) FROM inventory_ledger_movements"));
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_transfers"));

        BigDecimal firstIssue = ledgerService.issueRequest(request, mapper.createObjectNode(), ADMIN);
        BigDecimal repeatedIssue = ledgerService.issueRequest(request, mapper.createObjectNode(), ADMIN);

        assertEquals(0, new BigDecimal("4").compareTo(firstIssue));
        assertEquals(0, BigDecimal.ZERO.compareTo(repeatedIssue));
        assertBalance("loc-central", "batch-transfer", "6.00", "0.00");
        assertBalance("loc-in-transit", "batch-transfer", "4.00", "0.00");
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_ledger_movements WHERE movement_type = 'TRANSFER_TO_TRANSIT'"));

        BigDecimal firstReceipt = ledgerService.receiveRequest(request, ADMIN);
        BigDecimal repeatedReceipt = ledgerService.receiveRequest(request, ADMIN);

        assertEquals(0, new BigDecimal("4").compareTo(firstReceipt));
        assertEquals(0, BigDecimal.ZERO.compareTo(repeatedReceipt));
        assertBalance("loc-in-transit", "batch-transfer", "0.00", "0.00");
        assertBalance(DEPARTMENT_LOCATION, "batch-transfer", "4.00", "0.00");
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_ledger_movements WHERE movement_type = 'TRANSFER_TO_DEPARTMENT'"));
        assertEquals("RECEIVED", jdbc.queryForObject(
            "SELECT status FROM inventory_transfers WHERE id = ?", String.class, firstReservation.path("id").asText()
        ));
    }

    @Test
    void stageCompletionIsUniqueUsesFefoAndReversalRestoresDepartmentBalances() {
        seedItemAndBatch("item-consume", "batch-early", "2026-08-01", new BigDecimal("2"), DEPARTMENT_LOCATION);
        seedBatch("item-consume", "batch-late", "2026-12-01", new BigDecimal("5"), DEPARTMENT_LOCATION);
        seedPackage("package-inspection", "INSPECTION", "item-consume", new BigDecimal("4"));

        ObjectNode first = consumptionService.enqueueStageCompletion(
            "encounter-consume", "INSPECTION", 7, DEPARTMENT_ID, "case-consume",
            "outpatient", LocalDate.now(), ADMIN.name()
        );
        ObjectNode duplicate = consumptionService.enqueueStageCompletion(
            "encounter-consume", "INSPECTION", 7, DEPARTMENT_ID, "case-consume",
            "outpatient", LocalDate.now(), ADMIN.name()
        );

        assertEquals(first.path("id").asText(), duplicate.path("id").asText());
        consumptionService.processCommand(first.path("id").asText());
        consumptionService.processCommand(first.path("id").asText());

        assertBalance(DEPARTMENT_LOCATION, "batch-early", "0.00", "0.00");
        assertBalance(DEPARTMENT_LOCATION, "batch-late", "3.00", "0.00");
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_consumption_events WHERE event_kind = 'CONSUMPTION'"));
        assertEquals(2, count("SELECT COUNT(*) FROM inventory_consumption_details WHERE detail_kind = 'CONSUMPTION'"));
        assertEquals(0, new BigDecimal("2").compareTo(jdbc.queryForObject(
            "SELECT quantity FROM inventory_consumption_details WHERE detail_kind = 'CONSUMPTION' AND batch_id = 'batch-early'",
            BigDecimal.class
        )));
        assertEquals(0, new BigDecimal("2").compareTo(jdbc.queryForObject(
            "SELECT quantity FROM inventory_consumption_details WHERE detail_kind = 'CONSUMPTION' AND batch_id = 'batch-late'",
            BigDecimal.class
        )));

        ObjectNode reversal = consumptionService.enqueueStageReversal(
            "encounter-consume", "INSPECTION", 8, DEPARTMENT_ID, ADMIN.name(), "阶段退回"
        );
        assertNotEquals(first.path("id").asText(), reversal.path("id").asText());
        consumptionService.processCommand(reversal.path("id").asText());
        consumptionService.processCommand(reversal.path("id").asText());

        ObjectNode laterReversal = consumptionService.enqueueStageReversal(
            "encounter-consume", "INSPECTION", 9, DEPARTMENT_ID, ADMIN.name(), "重复退回"
        );
        consumptionService.processCommand(laterReversal.path("id").asText());

        assertBalance(DEPARTMENT_LOCATION, "batch-early", "2.00", "0.00");
        assertBalance(DEPARTMENT_LOCATION, "batch-late", "5.00", "0.00");
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_consumption_events WHERE event_kind = 'REVERSAL'"));
        assertEquals(2, count("SELECT COUNT(*) FROM inventory_ledger_movements WHERE movement_type = 'CONSUMPTION_REVERSAL'"));
    }

    @Test
    void correctionCancelsPendingConsumptionBeforeEnqueuingTheNewVersion() {
        seedItemAndBatch("item-correction", "batch-correction", "2026-10-01", new BigDecimal("5"), DEPARTMENT_LOCATION);
        seedPackage("package-correction", "DOCTOR", "item-correction", new BigDecimal("2"));

        ObjectNode stale = consumptionService.enqueueStageCompletion(
            "encounter-correction", "DOCTOR", 3, DEPARTMENT_ID, "case-correction",
            "outpatient", LocalDate.now(), ADMIN.name()
        );
        ObjectNode cancellation = consumptionService.enqueueStageReversal(
            "encounter-correction", "DOCTOR", 4, DEPARTMENT_ID, ADMIN.name(), "Replace a pending consumption during correction"
        );

        assertEquals(stale.path("id").asText(), cancellation.path("id").asText());
        assertEquals("CANCELLED", jdbc.queryForObject(
            "SELECT status FROM inventory_stage_consumption_commands WHERE id = ?", String.class, stale.path("id").asText()
        ));
        assertEquals(0, count("SELECT COUNT(*) FROM inventory_stage_consumption_commands WHERE command_type = 'REVERSAL'"));

        ObjectNode corrected = consumptionService.enqueueStageCompletion(
            "encounter-correction", "DOCTOR", 4, DEPARTMENT_ID, "case-correction",
            "outpatient", LocalDate.now(), ADMIN.name()
        );
        consumptionService.processCommand(corrected.path("id").asText());

        assertBalance(DEPARTMENT_LOCATION, "batch-correction", "3.00", "0.00");
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_consumption_events WHERE event_kind = 'CONSUMPTION'"));
    }

    @Test
    void weeklySuggestionUsesLedgerConsumptionCurrentBalanceAndSafetyStock() {
        seedItemAndBatch("item-weekly", "batch-weekly", "2026-10-01", new BigDecimal("5"), DEPARTMENT_LOCATION);
        jdbc.update("UPDATE inventory_items SET safety_stock = 3 WHERE id = 'item-weekly'");
        seedPackage("package-weekly", "DOCTOR", "item-weekly", new BigDecimal("2"));
        ObjectNode command = consumptionService.enqueueStageCompletion(
            "encounter-weekly", "DOCTOR", 1, DEPARTMENT_ID, "case-weekly",
            "outpatient", LocalDate.now(), ADMIN.name()
        );
        consumptionService.processCommand(command.path("id").asText());

        ArrayNode rows = ledger.readWeeklySuggestions(DEPARTMENT_ID);
        ObjectNode suggestion = null;
        for (JsonNode row : rows) {
            if ("item-weekly".equals(row.path("itemId").asText())) {
                suggestion = (ObjectNode) row;
                break;
            }
        }
        assertTrue(suggestion != null);

        assertEquals(0, new BigDecimal("2").compareTo(suggestion.path("actualConsumption").decimalValue()));
        assertEquals(0, new BigDecimal("3").compareTo(suggestion.path("availableQuantity").decimalValue()));
        assertEquals(0, new BigDecimal("3").compareTo(suggestion.path("safetyQuantity").decimalValue()));
        assertEquals(0, new BigDecimal("2").compareTo(suggestion.path("suggestedQuantity").decimalValue()));
    }

    @Test
    void shortageCreatesRetryableExceptionWithoutAnyPartialDeduction() {
        seedItemAndBatch("item-short", "batch-short", "2026-09-01", new BigDecimal("3"), DEPARTMENT_LOCATION);
        seedPackage("package-short", "RECEPTION", "item-short", new BigDecimal("2"));
        seedPackageLine("package-short", "item-short", new BigDecimal("2"));
        ObjectNode command = consumptionService.enqueueStageCompletion(
            "encounter-short", "RECEPTION", 1, DEPARTMENT_ID, "case-short",
            "outpatient", LocalDate.now(), ADMIN.name()
        );

        consumptionService.processCommand(command.path("id").asText());

        assertBalance(DEPARTMENT_LOCATION, "batch-short", "3.00", "0.00");
        assertEquals(0, count("SELECT COUNT(*) FROM inventory_consumption_events WHERE encounter_id = 'encounter-short'"));
        assertEquals("FAILED", jdbc.queryForObject(
            "SELECT status FROM inventory_stage_consumption_commands WHERE id = ?", String.class, command.path("id").asText()
        ));
        assertEquals("STOCK_SHORTAGE", jdbc.queryForObject(
            "SELECT exception_type FROM inventory_exception_tasks WHERE command_id = ?", String.class, command.path("id").asText()
        ));
        ObjectNode retry = consumptionService.retry(command.path("id").asText(), ADMIN.name());
        assertEquals("PENDING", retry.path("status").asText());
        assertTrue(count("SELECT COUNT(*) FROM inventory_exception_tasks WHERE command_id = ? AND status = 'RETRYING'", command.path("id").asText()) == 1);
    }

    private void seedItemAndBatch(String itemId, String batchId, String expiry, BigDecimal quantity, String locationId) {
        jdbc.update(
            """
            INSERT INTO inventory_items
              (id, name, category, spec, unit, location, low_stock_threshold, safety_stock,
               is_sensitive, batch_required, expiry_required, enabled, raw_json)
            VALUES (?, ?, '耗材', '测试规格', '个', '测试库位', 0, 0, FALSE, TRUE, TRUE, TRUE,
                    JSON_OBJECT('id', ?, 'name', ?, 'unit', '个'))
            """,
            itemId, "测试物资-" + itemId, itemId, "测试物资-" + itemId
        );
        seedBatch(itemId, batchId, expiry, quantity, locationId);
    }

    private void seedBatch(String itemId, String batchId, String expiry, BigDecimal quantity, String locationId) {
        jdbc.update(
            """
            INSERT INTO inventory_batches (id, item_id, batch_no, expiry_date, quantity, location, raw_json)
            VALUES (?, ?, ?, ?, ?, '测试库位', JSON_OBJECT('id', ?, 'itemId', ?, 'quantity', ?))
            """,
            batchId, itemId, "NO-" + batchId, expiry,
            "loc-central".equals(locationId) ? quantity : BigDecimal.ZERO,
            batchId, itemId, "loc-central".equals(locationId) ? quantity : BigDecimal.ZERO
        );
        jdbc.update(
            """
            INSERT INTO inventory_batch_balances
              (id, location_id, item_id, batch_id, quantity, reserved_quantity)
            VALUES (?, ?, ?, ?, ?, 0)
            """,
            "balance-" + UUID.randomUUID(), locationId, itemId, batchId, quantity
        );
    }

    private void seedPackage(String packageId, String stage, String itemId, BigDecimal quantity) {
        jdbc.update(
            """
            INSERT INTO inventory_packages
              (id, department, department_id, care_type, trigger_stage, version_no, status,
               effective_date, name, updated_at, raw_json)
            VALUES (?, ?, ?, 'outpatient', ?, 1, 'enabled', ?, ?, CURRENT_TIMESTAMP(3),
                    JSON_OBJECT('id', ?, 'departmentId', ?, 'triggerStage', ?))
            """,
            packageId, DEPARTMENT_NAME, DEPARTMENT_ID, stage, LocalDate.now().minusDays(1).toString(),
            "测试套餐-" + stage, packageId, DEPARTMENT_ID, stage
        );
        seedPackageLine(packageId, itemId, quantity);
    }

    private void seedPackageLine(String packageId, String itemId, BigDecimal quantity) {
        jdbc.update(
            """
            INSERT INTO inventory_package_lines (id, package_id, item_id, quantity, raw_json)
            VALUES (?, ?, ?, ?, JSON_OBJECT('itemId', ?, 'quantity', ?))
            """,
            "package-line-" + UUID.randomUUID(), packageId, itemId, quantity, itemId, quantity
        );
    }

    private ObjectNode request(String requestId, String lineId, String itemId, BigDecimal quantity) {
        ObjectNode request = mapper.createObjectNode();
        request.put("id", requestId);
        request.put("departmentId", DEPARTMENT_ID);
        request.put("department", DEPARTMENT_NAME);
        request.put("applicant", ADMIN.name());
        request.put("reason", "测试申领");
        ArrayNode lines = request.putArray("lines");
        ObjectNode line = lines.addObject();
        line.put("id", lineId);
        line.put("itemId", itemId);
        line.put("quantity", quantity);
        line.put("issuedQuantity", BigDecimal.ZERO);
        return request;
    }

    private void assertBalance(String locationId, String batchId, String quantity, String reserved) {
        ObjectNode row = jdbc.queryForObject(
            "SELECT quantity, reserved_quantity FROM inventory_batch_balances WHERE location_id = ? AND batch_id = ?",
            (rs, rowNum) -> {
                ObjectNode value = mapper.createObjectNode();
                value.put("quantity", rs.getBigDecimal("quantity"));
                value.put("reserved", rs.getBigDecimal("reserved_quantity"));
                return value;
            },
            locationId, batchId
        );
        assertFalse(row == null);
        assertEquals(0, new BigDecimal(quantity).compareTo(row.path("quantity").decimalValue()));
        assertEquals(0, new BigDecimal(reserved).compareTo(row.path("reserved").decimalValue()));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String environmentValue(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
