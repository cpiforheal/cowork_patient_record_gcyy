package com.coshare.patientrecord.maintenance.datapurge.dto;

import jakarta.validation.constraints.NotBlank;

public record DataPurgeExecuteRequest(
    @NotBlank String password,
    @NotBlank String previewToken,
    @NotBlank String confirmationText
) {
}
