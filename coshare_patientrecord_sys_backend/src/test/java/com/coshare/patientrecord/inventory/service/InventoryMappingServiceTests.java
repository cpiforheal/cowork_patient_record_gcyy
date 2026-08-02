package com.coshare.patientrecord.inventory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InventoryMappingServiceTests {

    private static final String CONDITIONAL_PACKAGE = "\u6761\u4ef6\u5957\u9910";
    private static final String FIXED_RUNNING = "\u56fa\u5b9a\u8fd0\u884c\u6d88\u8017";
    private static final String ON_DEMAND = "\u6309\u9700\u7533\u9886";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void summarizesExpectedFirstRoundCounts() {
        List<String> ruleTypes = new ArrayList<>();
        add(ruleTypes, InventoryMappingService.PATIENT_ONCE_PACKAGE, 27);
        add(ruleTypes, CONDITIONAL_PACKAGE, 145);
        add(ruleTypes, FIXED_RUNNING, 48);
        add(ruleTypes, ON_DEMAND, 21);

        Map<String, Long> counts = InventoryMappingService.summarizeRuleTypes(ruleTypes);

        assertEquals(241, ruleTypes.size());
        assertEquals(27L, counts.get(InventoryMappingService.PATIENT_ONCE_PACKAGE));
        assertEquals(145L, counts.get(CONDITIONAL_PACKAGE));
        assertEquals(48L, counts.get(FIXED_RUNNING));
        assertEquals(21L, counts.get(ON_DEMAND));
    }

    @Test
    void blocksNonPatientRulesFromPackageDrafts() {
        ObjectNode row = confirmedPatientRow();
        row.put("ruleType", CONDITIONAL_PACKAGE);

        String reason = InventoryMappingService.cannotCreatePackageDraftReason(row, true, "个", true);

        assertEquals("Only patient-once mappings can create package drafts.", reason);
    }

    @Test
    void blocksPendingOrIncompletePatientMappings() {
        ObjectNode row = confirmedPatientRow();
        row.put("status", "pending");

        assertEquals(
            "Mapping is pending confirmation.",
            InventoryMappingService.cannotCreatePackageDraftReason(row, true, "个", true)
        );

        row.put("status", "confirmed");
        row.put("triggerStage", InventoryMappingService.PENDING_STAGE);
        assertEquals(
            "Trigger stage needs confirmation.",
            InventoryMappingService.cannotCreatePackageDraftReason(row, true, "个", true)
        );
    }

    @Test
    void blocksMissingItemUnitAndPositiveQuantityForDrafts() {
        ObjectNode row = confirmedPatientRow();
        row.remove("matchedItemId");
        assertEquals(
            "Matched inventory item is required.",
            InventoryMappingService.cannotCreatePackageDraftReason(row, true, "个", true)
        );

        row = confirmedPatientRow();
        row.put("suggestedUnit", "");
        assertEquals(
            "Unit is required.",
            InventoryMappingService.cannotCreatePackageDraftReason(row, true, "个", true)
        );

        row = confirmedPatientRow();
        row.put("suggestedQuantity", BigDecimal.ZERO);
        assertEquals(
            "Quantity must be greater than zero.",
            InventoryMappingService.cannotCreatePackageDraftReason(row, true, "个", true)
        );
    }

    @Test
    void allowsConfirmedPatientMappingWhenUnitMatches() {
        ObjectNode row = confirmedPatientRow();

        String reason = InventoryMappingService.cannotCreatePackageDraftReason(row, true, "个", false);

        assertTrue(reason.isBlank());
    }

    @Test
    void blocksDuplicateDraftGenerationAndMissingConversion() {
        ObjectNode row = confirmedPatientRow();
        row.put("draftPackageId", "pkg-existing");

        assertEquals(
            "Package draft already exists for this mapping.",
            InventoryMappingService.cannotCreatePackageDraftReason(row, true, "个", true)
        );

        row.remove("draftPackageId");
        row.put("suggestedUnit", "包");
        assertEquals(
            "Unit conversion is required before creating a package draft.",
            InventoryMappingService.cannotCreatePackageDraftReason(row, true, "个", false)
        );
    }

    @Test
    void classifiesMappingMaturityWithoutEnablingAutomation() {
        ObjectNode row = confirmedPatientRow();
        assertEquals("可生成草稿", InventoryMappingService.maturity(row, true));
        assertEquals(InventoryMappingService.PENDING_STAGE, InventoryMappingService.maturity(row, false));

        row.put("ruleType", CONDITIONAL_PACKAGE);
        assertEquals("仅预测", InventoryMappingService.maturity(row, false));

        row.put("status", "pending");
        assertEquals(InventoryMappingService.PENDING_STAGE, InventoryMappingService.maturity(row, false));

        row.put("ruleType", FIXED_RUNNING);
        assertEquals("非患者耗用", InventoryMappingService.maturity(row, false));

        row.put("ruleType", ON_DEMAND);
        assertEquals("走申领", InventoryMappingService.maturity(row, false));
    }

    @Test
    void normalizesAliasNamesForGovernanceMatching() {
        assertEquals("pvc手套", InventoryMappingService.normalizeAliasName(" PVC 手套 "));
    }

    @Test
    void requiresOneScopeForDraftPackageRows() {
        ObjectNode first = confirmedPatientRow();
        ObjectNode second = confirmedPatientRow();
        second.put("careType", "inpatient");

        assertThrows(
            IllegalArgumentException.class,
            () -> InventoryMappingService.validateSingleDraftScope(List.of(first, second))
        );
    }

    private ObjectNode confirmedPatientRow() {
        ObjectNode row = mapper.createObjectNode();
        row.put("id", "inventory-mapping-test");
        row.put("ruleType", InventoryMappingService.PATIENT_ONCE_PACKAGE);
        row.put("status", "confirmed");
        row.put("department", "理疗室");
        row.put("careType", "outpatient");
        row.put("triggerStage", "REVIEW");
        row.put("matchedItemId", "item-1");
        row.put("matchedItemName", "耗材");
        row.put("suggestedUnit", "个");
        row.put("suggestedQuantity", new BigDecimal("1.00"));
        return row;
    }

    private static void add(List<String> values, String value, int count) {
        for (int i = 0; i < count; i++) values.add(value);
    }
}
