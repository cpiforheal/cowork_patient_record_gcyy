package com.coshare.patientrecord.ai.entity;

public record AiPromptTemplate(
    String id,
    String createdAt,
    String assistantType,
    String title,
    String roleScope,
    String sourceLogId,
    String rawJson
) {}
