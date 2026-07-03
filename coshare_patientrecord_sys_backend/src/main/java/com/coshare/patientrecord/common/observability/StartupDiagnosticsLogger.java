package com.coshare.patientrecord.common.observability;

import com.coshare.patientrecord.file.service.ClinicFileService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StartupDiagnosticsLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupDiagnosticsLogger.class);

    private final Environment environment;
    private final ClinicFileService fileService;
    private final String datasourceUrl;
    private final String datasourceUsername;
    private final String backupCron;
    private final String backupZone;
    private final String aiApiKey;
    private final String aiModel;
    private final String doubaoApiKey;
    private final String doubaoModel;
    private final String doubaoTtsApiKey;
    private final String doubaoTtsModel;

    public StartupDiagnosticsLogger(
        Environment environment,
        ClinicFileService fileService,
        @Value("${spring.datasource.url:}") String datasourceUrl,
        @Value("${spring.datasource.username:}") String datasourceUsername,
        @Value("${clinic.backup.cron:}") String backupCron,
        @Value("${clinic.backup.zone:}") String backupZone,
        @Value("${clinic.ai.api-key:}") String aiApiKey,
        @Value("${clinic.ai.model:}") String aiModel,
        @Value("${clinic.ai.doubao.api-key:}") String doubaoApiKey,
        @Value("${clinic.ai.doubao.model:}") String doubaoModel,
        @Value("${clinic.ai.doubao.tts.api-key:}") String doubaoTtsApiKey,
        @Value("${clinic.ai.doubao.tts.model:}") String doubaoTtsModel
    ) {
        this.environment = environment;
        this.fileService = fileService;
        this.datasourceUrl = datasourceUrl == null ? "" : datasourceUrl;
        this.datasourceUsername = datasourceUsername == null ? "" : datasourceUsername;
        this.backupCron = backupCron == null ? "" : backupCron;
        this.backupZone = backupZone == null ? "" : backupZone;
        this.aiApiKey = aiApiKey == null ? "" : aiApiKey;
        this.aiModel = aiModel == null ? "" : aiModel;
        this.doubaoApiKey = doubaoApiKey == null ? "" : doubaoApiKey;
        this.doubaoModel = doubaoModel == null ? "" : doubaoModel;
        this.doubaoTtsApiKey = doubaoTtsApiKey == null ? "" : doubaoTtsApiKey;
        this.doubaoTtsModel = doubaoTtsModel == null ? "" : doubaoTtsModel;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path attachmentDir = validateAttachmentDirectory();
        log.info(
            "Application startup diagnostics: profiles={}, port={}, datasource={}, datasourceUser={}, attachmentDir={}, backupCron={}, backupZone={}, aiConfigured={}, doubaoConfigured={}, ttsConfigured={}",
            activeProfiles(),
            environment.getProperty("server.port", "8080"),
            jdbcTarget(datasourceUrl),
            datasourceUsername.isBlank() ? "not-configured" : datasourceUsername,
            attachmentDir,
            backupCron,
            backupZone,
            configured(aiApiKey, aiModel),
            configured(doubaoApiKey, doubaoModel),
            configured(doubaoTtsApiKey, doubaoTtsModel)
        );
    }

    private Path validateAttachmentDirectory() {
        Path attachmentDir = fileService.attachmentDir();
        try {
            Files.createDirectories(attachmentDir);
            Path testFile = attachmentDir.resolve(".startup-write-test-" + UUID.randomUUID() + ".tmp");
            Files.writeString(testFile, "ok", StandardCharsets.UTF_8);
            Files.deleteIfExists(testFile);
            return attachmentDir;
        } catch (Exception error) {
            throw new IllegalStateException("附件目录不可写，请检查 CLINIC_ATTACHMENT_DIR：" + attachmentDir, error);
        }
    }

    private boolean configured(String key, String model) {
        return !key.isBlank() && !model.isBlank();
    }

    private String activeProfiles() {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 0 ? "default" : String.join(",", Arrays.asList(profiles));
    }

    private String jdbcTarget(String jdbcUrl) {
        if (jdbcUrl.isBlank()) {
            return "not-configured";
        }
        String withoutPrefix = jdbcUrl.replaceFirst("^jdbc:mysql://", "");
        int queryIndex = withoutPrefix.indexOf('?');
        return queryIndex >= 0 ? withoutPrefix.substring(0, queryIndex) : withoutPrefix;
    }
}
