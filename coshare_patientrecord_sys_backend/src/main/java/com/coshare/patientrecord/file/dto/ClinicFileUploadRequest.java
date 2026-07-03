package com.coshare.patientrecord.file.dto;

import jakarta.validation.constraints.NotBlank;

public record ClinicFileUploadRequest(
    @NotBlank String fileName,
    @NotBlank String contentDataUrl,
    String patientId,
    String department,
    String operator,
    String operatorRole,
    String type,
    String typeLabel
) {
}
