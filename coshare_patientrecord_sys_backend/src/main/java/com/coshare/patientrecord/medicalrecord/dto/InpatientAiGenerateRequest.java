package com.coshare.patientrecord.medicalrecord.dto;

public record InpatientAiGenerateRequest(
    String patientId,
    String encounterId,
    String sourceRecordId,
    String prompt
) {}
