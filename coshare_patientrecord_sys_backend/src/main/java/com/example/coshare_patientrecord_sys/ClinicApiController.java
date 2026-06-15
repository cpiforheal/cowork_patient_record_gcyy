package com.example.coshare_patientrecord_sys;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
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
    private final ObjectMapper objectMapper;

    public ClinicApiController(ClinicDatabaseService databaseService, ClinicFileService fileService, ObjectMapper objectMapper) {
        this.databaseService = databaseService;
        this.fileService = fileService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/clinic-api/db")
    public ApiResult<Map<String, Object>> readDb() {
        Map<String, Object> db = objectMapper.convertValue(
            databaseService.readDb(),
            new TypeReference<Map<String, Object>>() {}
        );
        return ApiResult.success(db);
    }

    @PutMapping("/clinic-api/db")
    public ApiResult<Map<String, String>> writeDb(@RequestBody Map<String, Object> payload) {
        String revision = databaseService.writeDb(objectMapper.valueToTree(payload));
        return ApiResult.of(200, "saved", Map.of("_revision", revision));
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
            .cacheControl(CacheControl.noStore().cachePrivate())
            .header(HttpHeaders.PRAGMA, "no-cache")
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
