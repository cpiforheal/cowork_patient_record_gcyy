package com.coshare.patientrecord.medicalrecord.dto;

import java.util.Map;

public record WorkspaceSaveRequest(String patientId, Map<String, String> values) {}
