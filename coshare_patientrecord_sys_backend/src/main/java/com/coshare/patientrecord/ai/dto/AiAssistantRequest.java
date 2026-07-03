package com.coshare.patientrecord.ai.dto;

import java.util.List;
import java.util.Map;

public record AiAssistantRequest(
    String assistantType,
    String prompt,
    List<AiAssistantMessage> messages,
    String patientId,
    Map<String, Object> context,
    List<String> attachmentIds,
    List<AiAssistantAttachment> attachments
) {}
