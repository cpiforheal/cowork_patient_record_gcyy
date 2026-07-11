package com.coshare.patientrecord.preai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class PreAiEncounterServiceTests {

    @Test
    void exportDownloadUrlUsesClinicRelativeBusinessPath() {
        String url = PreAiEncounterService.exportDownloadUrl("encounter-1", "export-1");

        assertEquals("/pre-ai/encounters/encounter-1/exports/export-1/download", url);
        assertFalse(url.startsWith("/clinic-api/"));
    }
}
