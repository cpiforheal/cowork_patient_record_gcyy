package com.example.coshare_patientrecord_sys;

import jakarta.validation.constraints.NotBlank;

public record ClinicFileUploadRequest(
    @NotBlank String fileName,
    @NotBlank String contentDataUrl
) {
}
