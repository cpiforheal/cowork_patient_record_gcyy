package com.example.coshare_patientrecord_sys;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
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
    private static final Set<String> ADMIN_CLINIC_SECTIONS = Set.of(
        "accounts",
        "roles",
        "departments",
        "dictionaries",
        "templateFieldRules"
    );

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
            databaseService.readDbForUser(AuthPermission.currentUserOrThrow()),
            new TypeReference<Map<String, Object>>() {}
        );
        return ApiResult.success(db);
    }

    @PutMapping("/clinic-api/db")
    public ApiResult<Map<String, String>> writeDb(@RequestBody Map<String, Object> payload) {
        requireClinicAdmin();
        String revision = databaseService.writeDb(objectMapper.valueToTree(payload));
        return ApiResult.of(200, "saved", Map.of("_revision", revision));
    }

    @PostMapping("/clinic-api/db/merge")
    public ApiResult<Map<String, Object>> mergeDb(@RequestBody Map<String, Object> payload) {
        AuthSessionService.SessionUser user = AuthPermission.currentUserOrThrow();
        requireClinicWriter(payload);
        ObjectNode sanitizedPayload = databaseService.prepareWritePayload(objectMapper.valueToTree(payload), user);
        ObjectNode result = databaseService.mergeDb(sanitizedPayload);
        result.set("db", databaseService.readDbForUser(user));
        Map<String, Object> data = objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
        return ApiResult.of(200, "merged", data);
    }

    @PostMapping("/clinic-api/db/patch")
    public ApiResult<Map<String, Object>> patchDb(@RequestBody Map<String, Object> payload) {
        AuthSessionService.SessionUser user = AuthPermission.currentUserOrThrow();
        requireClinicWriter(payload);
        ObjectNode sanitizedPayload = databaseService.prepareWritePayload(objectMapper.valueToTree(payload), user);
        ObjectNode result = databaseService.patchDb(sanitizedPayload);
        result.set("db", databaseService.readDbForUser(user));
        Map<String, Object> data = objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
        return ApiResult.of(200, "patched", data);
    }

    @GetMapping("/clinic-api/maintenance/status")
    public ApiResult<Map<String, Object>> maintenanceStatus() throws IOException {
        requireClinicAdmin();
        ObjectNode status = databaseService.maintenanceStatus(fileService.inspectStorage(databaseService.referencedStoragePaths()));
        Map<String, Object> data = objectMapper.convertValue(status, new TypeReference<Map<String, Object>>() {});
        return ApiResult.success(data);
    }

    @PostMapping("/clinic-api/maintenance/snapshot")
    public ApiResult<Map<String, Object>> createSnapshot() {
        requireClinicAdmin();
        ObjectNode result = databaseService.createSnapshot();
        Map<String, Object> data = objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
        return ApiResult.of(200, "snapshot saved", data);
    }

    @GetMapping("/clinic-api/patients/duplicates")
    public ApiResult<Object> duplicatePatients() {
        requireClinicAdmin();
        ArrayNode duplicates = databaseService.findDuplicatePatients();
        Object data = objectMapper.convertValue(duplicates, Object.class);
        return ApiResult.success(data);
    }

    @PostMapping("/clinic-api/files")
    public ApiResult<ClinicStoredFile> storeFile(@Valid @RequestBody ClinicFileUploadRequest request) throws IOException {
        requireClinicContributor();
        return ApiResult.of(200, "stored", fileService.store(request, AuthPermission.currentUserOrThrow()));
    }

    @GetMapping("/clinic-api/files/**")
    public ResponseEntity<FileSystemResource> downloadFile(jakarta.servlet.http.HttpServletRequest request) {
        AuthSessionService.SessionUser user = AuthPermission.currentUserOrThrow();
        String storagePath = extractStoragePath(request);
        if (!databaseService.canReadStoragePath(storagePath, user)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "File not found");
        }
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

    private void requireClinicWriter(Map<String, Object> payload) {
        if (containsAdminClinicSections(payload)) {
            requireClinicAdmin();
            return;
        }
        requireClinicContributor();
    }

    private boolean containsAdminClinicSections(Map<String, Object> payload) {
        JsonNode payloadNode = objectMapper.valueToTree(payload);
        return containsAdminClinicSections(payloadNode);
    }

    private boolean containsAdminClinicSections(JsonNode payload) {
        if (payload == null || !payload.isObject() || payload.isMissingNode() || payload.isNull()) return false;
        for (String section : ADMIN_CLINIC_SECTIONS) {
            if (payload.has(section)) return true;
        }
        return containsAdminClinicSections((JsonNode) payload.path("db"))
            || containsAdminClinicSections((JsonNode) payload.path("patch"))
            || containsAdminClinicSections((JsonNode) payload.path("data"))
            || containsAdminClinicSections((JsonNode) payload.path("payload"));
    }

    private void requireClinicAdmin() {
        AuthPermission.requireAnyRole("当前账号无病历系统管理权限", "admin", "quality");
    }

    private void requireClinicContributor() {
        AuthPermission.requireAnyRole(
            "当前账号无病历写入权限",
            "admin",
            "quality",
            "frontdesk",
            "doctor",
            "nurse",
            "lab",
            "ecg",
            "ultrasound"
        );
    }
}
