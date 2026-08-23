package com.coshare.patientrecord.medicalrecord.dto;

import java.util.List;

public record MedicalRecordWorkflowSubmitRequest(
    String reportId,
    String referenceAssetId,
    String sourceRecordId,
    String prompt,
    String mappingMode,
    List<String> targetNodeKeys,
    String preAiExportId
) {
    public MedicalRecordWorkflowSubmitRequest {
        targetNodeKeys = targetNodeKeys == null ? List.of() : List.copyOf(targetNodeKeys);
    }

    public MedicalRecordWorkflowSubmitRequest(
        String reportId,
        String referenceAssetId,
        String sourceRecordId,
        String prompt,
        String mappingMode,
        List<String> targetNodeKeys
    ) {
        this(reportId, referenceAssetId, sourceRecordId, prompt, mappingMode, targetNodeKeys, null);
    }
}
