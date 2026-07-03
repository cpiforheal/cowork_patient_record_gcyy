package com.coshare.patientrecord.medicalrecord.dto;

import org.springframework.core.io.FileSystemResource;

public record DownloadFile(FileSystemResource resource, String fileName) {}
