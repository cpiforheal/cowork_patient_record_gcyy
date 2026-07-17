package com.coshare.patientrecord.inventory.service.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component
public class InventorySummaryBuilder {

    private final ObjectMapper objectMapper;

    public InventorySummaryBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode build(ObjectNode db) {
        ObjectNode summary = objectMapper.createObjectNode();
        ArrayNode items = (ArrayNode) db.path("items");
        ArrayNode batches = (ArrayNode) db.path("batches");
        ArrayNode requests = (ArrayNode) db.path("requests");
        LocalDate today = LocalDate.now();
        int lowStock = 0;
        int expirySoon = 0;
        for (JsonNode item : items) {
            BigDecimal total = stockOf(text(item, "id"), batches);
            if (total.compareTo(quantity(item, "lowStockThreshold")) <= 0) lowStock += 1;
        }
        for (JsonNode batch : batches) {
            String expiryDate = text(batch, "expiryDate");
            if (!expiryDate.isBlank()) {
                try {
                    LocalDate expiry = LocalDate.parse(expiryDate);
                    if (!expiry.isBefore(today) && !expiry.isAfter(today.plusDays(30))) expirySoon += 1;
                } catch (Exception ignored) {
                    // Keep summary tolerant of legacy manual date text.
                }
            }
        }
        summary.put("itemCount", items.size());
        summary.put("batchCount", batches.size());
        summary.put("pendingRequestCount", countByStatus(requests, "pending"));
        summary.put("approvedRequestCount", countByStatus(requests, "approved"));
        summary.put("lowStockCount", lowStock);
        summary.put("expirySoonCount", expirySoon);
        summary.put("movementCount", db.path("movements").size());
        return summary;
    }

    private BigDecimal stockOf(String itemId, ArrayNode batches) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode batch : batches) {
            if (itemId.equals(text(batch, "itemId"))) total = total.add(quantity(batch, "quantity"));
        }
        return total;
    }

    private int countByStatus(ArrayNode rows, String status) {
        int count = 0;
        for (JsonNode row : rows) {
            if (status.equals(text(row, "status"))) count += 1;
        }
        return count;
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    private BigDecimal quantity(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isNumber() || value.isTextual()) {
            try {
                return new BigDecimal(value.asText()).setScale(2, RoundingMode.HALF_UP);
            } catch (Exception ignored) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }
}
