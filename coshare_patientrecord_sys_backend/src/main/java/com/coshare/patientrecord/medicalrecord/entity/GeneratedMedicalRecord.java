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
    String sourceEncounterId,
    String sourceDigest,
    Long sourceFactsRevision,
    String validityStatus,
    String invalidatedAt,
    String invalidatedReason,
    String finalizedBy,
    String voidedAt,
    String voidedBy,
    String voidReason,
    String rawJson
) {}
