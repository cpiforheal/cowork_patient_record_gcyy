package com.coshare.patientrecord.ai.entity;

public record AiAssistantLog(
    String id,
    String createdAt,
    String assistantType,
    String status,
    String operatorId,
    String operatorName,
    String operatorRole,
    String operatorDepartment,
    String pageSource,
    String pagePath,
    String model,
    long latencyMs,
    String intentCategory,
    String promptPreview,
    String errorMessage,
    boolean templateCandidate,
    String rawJson
) {}
