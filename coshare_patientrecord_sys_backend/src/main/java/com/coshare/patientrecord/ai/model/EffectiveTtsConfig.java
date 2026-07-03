package com.coshare.patientrecord.ai.model;

public record EffectiveTtsConfig(
    String baseUrl,
    String apiKey,
    String model,
    String resourceId,
    String voiceType,
    double speedRatio,
    boolean runtimeConfig,
    boolean enabled
) {}
