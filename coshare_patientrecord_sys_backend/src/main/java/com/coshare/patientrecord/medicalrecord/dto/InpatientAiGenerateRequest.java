package com.coshare.patientrecord.medicalrecord.dto;

public record InpatientAiGenerateRequest(
    String patientId,
    String encounterId,
    String sourceRecordId,
    String prompt,
    Object preAiExport
) {
    public InpatientAiGenerateRequest(
        String patientId,
        String encounterId,
        String sourceRecordId,
        String prompt
    ) {
        this(patientId, encounterId, sourceRecordId, prompt, null);
    }
}
