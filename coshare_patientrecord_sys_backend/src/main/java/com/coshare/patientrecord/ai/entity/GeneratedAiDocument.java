package com.coshare.patientrecord.ai.entity;

public record GeneratedAiDocument(
    String id,
    String title,
    String docType,
    String fileName,
    String filePath,
    String contentHash,
    String operator,
    String operatorRole,
    String generatedAt,
    String rawJson
) {}
