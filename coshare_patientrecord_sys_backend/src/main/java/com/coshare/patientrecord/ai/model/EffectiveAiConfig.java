package com.coshare.patientrecord.ai.model;

public record EffectiveAiConfig(String baseUrl, String apiKey, String model, boolean runtimeConfig, boolean enabled) {}
