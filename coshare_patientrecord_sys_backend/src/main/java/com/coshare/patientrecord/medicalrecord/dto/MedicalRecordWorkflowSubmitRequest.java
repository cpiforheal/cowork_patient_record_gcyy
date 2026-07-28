package com.coshare.patientrecord.medicalrecord.dto;

import java.util.List;

public record MedicalRecordWorkflowSubmitRequest(
    String reportId,
    String sourceRecordId,
    String prompt,
    String mappingMode,
    List<String> targetNodeKeys
) {
    public MedicalRecordWorkflowSubmitRequest {
        targetNodeKeys = targetNodeKeys == null ? List.of() : List.copyOf(targetNodeKeys);
    }
}
