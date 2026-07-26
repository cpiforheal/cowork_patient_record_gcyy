package com.coshare.patientrecord.inventory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InventoryPackageServiceTests {

    @Test
    void normalizesSupportedCareTypesAndRejectsUnknownRoutes() {
        assertEquals("outpatient", InventoryPackageService.normalizeCareType("OUTPATIENT"));
        assertEquals("outpatient", InventoryPackageService.normalizeCareType("门诊"));
        assertEquals("inpatient", InventoryPackageService.normalizeCareType("住院"));
        assertTrue(InventoryPackageService.normalizeCareType("emergency").isBlank());
    }

    @Test
    void normalizesOnlyPerVisitConsumptionModeForCurrentPhase() {
        assertEquals("per_visit", InventoryPackageService.normalizeConsumptionMode(null));
        assertEquals("per_visit", InventoryPackageService.normalizeConsumptionMode("  PER_VISIT  "));
        assertTrue(InventoryPackageService.isSupportedConsumptionMode("per_visit"));
        assertFalse(InventoryPackageService.isSupportedConsumptionMode("per_day"));
        assertFalse(InventoryPackageService.isSupportedConsumptionMode("per_admission"));
        assertFalse(InventoryPackageService.isSupportedConsumptionMode("per_procedure"));
    }
}
