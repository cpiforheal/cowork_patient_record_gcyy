package com.coshare.patientrecord.ai.dto;

import org.springframework.core.io.FileSystemResource;

public record DownloadFile(FileSystemResource resource, String fileName) {}
