package com.coshare.patientrecord.medicalrecord.dto;

import java.util.List;

public record InpatientAiGenerateRequest(
    String patientId,
    String encounterId,
    String sourceRecordId,
    String prompt,
    Object preAiExport,
    List<String> conversationHistory
) {
    public InpatientAiGenerateRequest {
        conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
    }

    public InpatientAiGenerateRequest(
        String patientId,
        String encounterId,
        String sourceRecordId,
        String prompt,
        Object preAiExport
    ) {
        this(patientId, encounterId, sourceRecordId, prompt, preAiExport, List.of());
    }

    public InpatientAiGenerateRequest(
        String patientId,
        String encounterId,
        String sourceRecordId,
        String prompt
    ) {
        this(patientId, encounterId, sourceRecordId, prompt, null, List.of());
    }
}
