package com.coshare.patientrecord.ai.entity;

public record StoredAiConfig(
    String baseUrl,
    String apiKeyCipher,
    String model,
    String resourceId,
    String voiceType,
    double speedRatio,
    boolean enabled,
    String updatedAt,
    String updatedBy
) {}
