package com.example.coshare_patientrecord_sys;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClinicFileService {

    private final Path attachmentDir;

    public ClinicFileService(@Value("${clinic.attachment-dir}") String attachmentDir) {
        this.attachmentDir = Path.of(attachmentDir).toAbsolutePath().normalize();
    }

    public ClinicStoredFile store(ClinicFileUploadRequest request) throws IOException {
        DataUrl dataUrl = parseDataUrl(request.contentDataUrl());
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String safeName = sanitizeFileName(request.fileName());
        String storagePath = datePath + "/" + System.currentTimeMillis() + "-" + UUID.randomUUID() + "-" + safeName;
        Path target = attachmentDir.resolve(storagePath).normalize();
        if (!target.startsWith(attachmentDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file path");
        }

        Files.createDirectories(target.getParent());
        Files.write(target, dataUrl.bytes());

        return new ClinicStoredFile(
            request.fileName(),
            "/clinic-api/files/" + storagePath.replace("\\", "/"),
            storagePath.replace("\\", "/"),
            dataUrl.bytes().length,
            dataUrl.mimeType()
        );
    }

    public FileSystemResource load(String rawStoragePath) {
        String storagePath = URLDecoder.decode(rawStoragePath, StandardCharsets.UTF_8);
        Path target = attachmentDir.resolve(storagePath).normalize();
        if (!target.startsWith(attachmentDir) || !Files.exists(target) || !Files.isRegularFile(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
        return new FileSystemResource(target);
    }

    private DataUrl parseDataUrl(String contentDataUrl) {
        int commaIndex = contentDataUrl.indexOf(',');
        if (!contentDataUrl.startsWith("data:") || commaIndex < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contentDataUrl must be a data URL");
        }

        String meta = contentDataUrl.substring(5, commaIndex);
        String mimeType = meta.split(";")[0];
        if (!meta.contains(";base64") || mimeType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contentDataUrl must be base64 encoded");
        }

        try {
            return new DataUrl(mimeType, Base64.getDecoder().decode(contentDataUrl.substring(commaIndex + 1)));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid base64 file content", error);
        }
    }

    private String sanitizeFileName(String fileName) {
        String safe = fileName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return safe.isBlank() ? "attachment" : safe;
    }

    private record DataUrl(String mimeType, byte[] bytes) {
    }
}
