package com.coshare.patientrecord.ai.model;

import com.coshare.patientrecord.auth.dto.SessionUser;
import java.util.List;
import java.util.Map;

public record AssistantLogDraft(
    String assistantType,
    String prompt,
    Map<String, Object> context,
    String patientId,
    int attachmentCount,
    int imageCount,
    List<String> knowledgeSources,
    String model,
    String answer,
    String errorMessage,
    String systemPromptSummary,
    long latencyMs,
    boolean success,
    SessionUser user
) {}
