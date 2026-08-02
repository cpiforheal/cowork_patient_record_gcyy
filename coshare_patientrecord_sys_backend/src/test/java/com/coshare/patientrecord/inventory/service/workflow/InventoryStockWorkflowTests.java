package com.coshare.patientrecord.inventory.service.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class InventoryStockWorkflowTests {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void oldItemPayloadReceivesV18Defaults() {
        ObjectNode item = mapper.createObjectNode();
        item.put("unit", "个");

        InventoryStockWorkflow.applyItemCompatibilityDefaults(item);

        assertEquals("个", item.path("baseUnit").asText());
        assertEquals("个", item.path("issueUnit").asText());
        assertEquals(2, item.path("quantityPrecision").asInt());
        assertEquals("standard", item.path("normalizationStatus").asText());
        assertFalse(item.path("effectiveLifeManaged").asBoolean());
    }

    @Test
    void newItemPayloadKeepsExplicitV18Fields() {
        ObjectNode item = mapper.createObjectNode();
        item.put("unit", "盒");
        item.put("baseUnit", "支");
        item.put("issueUnit", "盒");
        item.put("quantityPrecision", 0);
        item.put("normalizationStatus", "pending");
        item.put("effectiveLifeManaged", true);

        InventoryStockWorkflow.applyItemCompatibilityDefaults(item);

        assertEquals("支", item.path("baseUnit").asText());
        assertEquals("盒", item.path("issueUnit").asText());
        assertEquals(0, item.path("quantityPrecision").asInt());
        assertEquals("pending", item.path("normalizationStatus").asText());
        assertTrue(item.path("effectiveLifeManaged").asBoolean());
    }
}
