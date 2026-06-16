package com.example.coshare_patientrecord_sys;

public record ClinicStoredFile(
    String fileName,
    String url,
    String storagePath,
    long size,
    String mimeType,
    String sha256
) {
}
