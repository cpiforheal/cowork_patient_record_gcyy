package com.example.coshare_patientrecord_sys;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("mysql")
public class ClinicApiController {

    private static final String FILE_ROUTE_PREFIX = "/clinic-api/files/";

    private final ClinicDatabaseService databaseService;
    private final ClinicFileService fileService;

    public ClinicApiController(ClinicDatabaseService databaseService, ClinicFileService fileService) {
        this.databaseService = databaseService;
        this.fileService = fileService;
    }

    @GetMapping("/clinic-api/db")
    public ApiResult<JsonNode> readDb() {
        return ApiResult.success(databaseService.readDb());
    }

    @PutMapping("/clinic-api/db")
    public ApiResult<Void> writeDb(@RequestBody JsonNode payload) {
        databaseService.writeDb(payload);
        return ApiResult.of(200, "saved", null);
    }

    @PostMapping("/clinic-api/files")
    public ApiResult<ClinicStoredFile> storeFile(@Valid @RequestBody ClinicFileUploadRequest request) throws IOException {
        return ApiResult.of(200, "stored", fileService.store(request));
    }

    @GetMapping("/clinic-api/files/**")
    public ResponseEntity<FileSystemResource> downloadFile(jakarta.servlet.http.HttpServletRequest request) {
        String storagePath = extractStoragePath(request);
        FileSystemResource file = fileService.load(storagePath);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic().immutable())
            .body(file);
    }

    private String extractStoragePath(jakarta.servlet.http.HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        String prefix = contextPath + FILE_ROUTE_PREFIX;
        int index = requestUri.indexOf(prefix);
        if (index < 0) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid file path");
        }
        return requestUri.substring(index + prefix.length());
    }
}
