package com.coshare.patientrecord.medicalrecord.entity;

public record GeneratedMedicalRecord(
    String id,
    String patientId,
    int version,
    String status,
    String content,
    String contentHash,
    String model,
    String operator,
    String operatorRole,
    String generatedAt,
    String finalizedAt,
    String voidedAt,
    String voidReason,
    String rawJson
) {}
