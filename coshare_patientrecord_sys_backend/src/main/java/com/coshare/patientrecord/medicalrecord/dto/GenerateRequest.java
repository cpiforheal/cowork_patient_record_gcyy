package com.coshare.patientrecord.medicalrecord.dto;

public record GenerateRequest(
    String patientId,
    String encounterId,
    String patientCaseId,
    String mode
) {}
