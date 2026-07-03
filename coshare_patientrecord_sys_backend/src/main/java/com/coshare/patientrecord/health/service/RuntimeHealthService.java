package com.coshare.patientrecord.health.service;

import com.coshare.patientrecord.backup.entity.BackupConfig;
import com.coshare.patientrecord.backup.repository.ClinicBackupRepository;
import com.coshare.patientrecord.file.service.ClinicFileService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class RuntimeHealthService {

    private static final String BACKUP_CONFIG_ID = "primary";

    private final Instant startedAt = Instant.now();
    private final Environment environment;
    private final ObjectProvider<DatabaseHealthService> databaseHealthService;
    private final ObjectProvider<ClinicBackupRepository> backupRepository;
    private final ClinicFileService fileService;
    private final String aiApiKey;
    private final String aiModel;
    private final String doubaoApiKey;
    private final String doubaoModel;
    private final String doubaoTtsApiKey;
    private final String doubaoTtsModel;

    public RuntimeHealthService(
        Environment environment,
        ObjectProvider<DatabaseHealthService> databaseHealthService,
        ObjectProvider<ClinicBackupRepository> backupRepository,
        ClinicFileService fileService,
        @Value("${clinic.ai.api-key:}") String aiApiKey,
        @Value("${clinic.ai.model:}") String aiModel,
        @Value("${clinic.ai.doubao.api-key:}") String doubaoApiKey,
        @Value("${clinic.ai.doubao.model:}") String doubaoModel,
        @Value("${clinic.ai.doubao.tts.api-key:}") String doubaoTtsApiKey,
        @Value("${clinic.ai.doubao.tts.model:}") String doubaoTtsModel
    ) {
        this.environment = environment;
        this.databaseHealthService = databaseHealthService;
        this.backupRepository = backupRepository;
        this.fileService = fileService;
        this.aiApiKey = aiApiKey == null ? "" : aiApiKey;
        this.aiModel = aiModel == null ? "" : aiModel;
        this.doubaoApiKey = doubaoApiKey == null ? "" : doubaoApiKey;
        this.doubaoModel = doubaoModel == null ? "" : doubaoModel;
        this.doubaoTtsApiKey = doubaoTtsApiKey == null ? "" : doubaoTtsApiKey;
        this.doubaoTtsModel = doubaoTtsModel == null ? "" : doubaoTtsModel;
    }

    public Map<String, Object> summary() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("database", database());
        components.put("attachmentStorage", attachmentStorage());
        components.put("backup", backup());
        components.put("ai", ai());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", overallStatus(components));
        response.put("service", "coshare-patientrecord-backend");
        response.put("profile", activeProfiles());
        response.put("startedAt", startedAt.toString());
        response.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds());
        response.put("components", components);
        return response;
    }

    private Map<String, Object> database() {
        DatabaseHealthService service = databaseHealthService.getIfAvailable();
        if (service == null) {
            return component("disabled", "mysql profile inactive");
        }
        try {
            Map<String, Object> check = service.check();
            Map<String, Object> component = component("ok", "database reachable");
            component.put("durationMs", check.getOrDefault("durationMs", 0));
            return component;
        } catch (Exception error) {
            return component("down", "database unavailable");
        }
    }

    private Map<String, Object> attachmentStorage() {
        Path attachmentDir = fileService.attachmentDir();
        try {
            Files.createDirectories(attachmentDir);
            Path testFile = attachmentDir.resolve(".health-write-test-" + UUID.randomUUID() + ".tmp");
            Files.writeString(testFile, "ok", StandardCharsets.UTF_8);
            Files.deleteIfExists(testFile);

            Map<String, Object> component = component("ok", "attachment directory writable");
            component.put("usableSpaceBytes", attachmentDir.toFile().getUsableSpace());
            return component;
        } catch (Exception error) {
            return component("down", "attachment directory is not writable");
        }
    }

    private Map<String, Object> backup() {
        ClinicBackupRepository repository = backupRepository.getIfAvailable();
        if (repository == null) {
            return component("disabled", "mysql profile inactive");
        }
        try {
            BackupConfig config = repository.readConfig(BACKUP_CONFIG_ID);
            boolean configured = config.backupDir() != null && !config.backupDir().isBlank();
            Map<String, Object> component = component(config.enabled() && !configured ? "warn" : "ok", backupMessage(config, configured));
            component.put("enabled", config.enabled());
            component.put("configured", configured);
            Map<String, Object> latestRun = repository.latestRun();
            if (latestRun != null) {
                component.put("latestRunStatus", latestRun.get("status"));
            }
            return component;
        } catch (Exception error) {
            return component("warn", "backup status unavailable");
        }
    }

    private Map<String, Object> ai() {
        boolean genericConfigured = !aiApiKey.isBlank() && !aiModel.isBlank();
        boolean doubaoConfigured = !doubaoApiKey.isBlank() && !doubaoModel.isBlank();
        boolean ttsConfigured = !doubaoTtsApiKey.isBlank() && !doubaoTtsModel.isBlank();
        List<String> providers = new ArrayList<>();
        if (genericConfigured) providers.add("generic");
        if (doubaoConfigured) providers.add("doubao");
        if (ttsConfigured) providers.add("doubao-tts");

        Map<String, Object> component = component(providers.isEmpty() ? "warn" : "ok", providers.isEmpty() ? "ai provider not configured" : "ai provider configured");
        component.put("configuredProviders", providers);
        return component;
    }

    private String backupMessage(BackupConfig config, boolean configured) {
        if (!config.enabled()) {
            return "backup disabled";
        }
        return configured ? "backup configured" : "backup enabled but directory not configured";
    }

    private String overallStatus(Map<String, Object> components) {
        boolean hasWarning = false;
        for (Object value : components.values()) {
            if (!(value instanceof Map<?, ?> component)) {
                continue;
            }
            Object status = component.get("status");
            if ("down".equals(status)) {
                return "degraded";
            }
            if ("warn".equals(status)) {
                hasWarning = true;
            }
        }
        return hasWarning ? "degraded" : "ok";
    }

    private Map<String, Object> component(String status, String message) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("status", status);
        component.put("message", message);
        return component;
    }

    private String activeProfiles() {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 0 ? "default" : String.join(",", Arrays.asList(profiles));
    }
}
