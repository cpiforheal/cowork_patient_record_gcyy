package com.coshare.patientrecord.medicalrecord.dto;

import java.util.List;

public record MedicalRecordWorkflowSubmitRequest(
    String reportId,
    String referenceAssetId,
    String sourceRecordId,
    String prompt,
    String mappingMode,
    List<String> targetNodeKeys,
    String preAiExportId,
    List<String> conversationHistory,
    String model
) {
    public MedicalRecordWorkflowSubmitRequest {
        targetNodeKeys = targetNodeKeys == null ? List.of() : List.copyOf(targetNodeKeys);
        conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
    }

    public MedicalRecordWorkflowSubmitRequest(
        String reportId,
        String referenceAssetId,
        String sourceRecordId,
        String prompt,
        String mappingMode,
        List<String> targetNodeKeys
    ) {
        this(reportId, referenceAssetId, sourceRecordId, prompt, mappingMode, targetNodeKeys, null, List.of(), null);
    }

    public MedicalRecordWorkflowSubmitRequest(
        String reportId,
        String referenceAssetId,
        String sourceRecordId,
        String prompt,
        String mappingMode,
        List<String> targetNodeKeys,
        String preAiExportId
    ) {
        this(reportId, referenceAssetId, sourceRecordId, prompt, mappingMode, targetNodeKeys, preAiExportId, List.of(), null);
    }
}
