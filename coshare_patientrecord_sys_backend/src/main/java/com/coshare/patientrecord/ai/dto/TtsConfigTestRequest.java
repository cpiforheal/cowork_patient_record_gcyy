package com.coshare.patientrecord.ai.dto;

public record TtsConfigTestRequest(
    String baseUrl,
    String apiKey,
    Boolean keepExistingApiKey,
    String model,
    String resourceId,
    String voiceType,
    Double speedRatio,
    String text
) {}
