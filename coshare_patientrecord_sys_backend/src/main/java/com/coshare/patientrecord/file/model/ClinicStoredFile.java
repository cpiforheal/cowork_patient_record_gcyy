package com.coshare.patientrecord.file.model;

public record ClinicStoredFile(
    String fileName,
    String url,
    String storagePath,
    long size,
    String mimeType,
    String sha256
) {
}
