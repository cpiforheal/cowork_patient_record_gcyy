package com.coshare.patientrecord.backup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.coshare.patientrecord.backup.repository.ClinicBackupRepository;
import com.coshare.patientrecord.clinic.service.ClinicDatabaseService;
import com.coshare.patientrecord.file.service.ClinicFileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClinicBackupServiceProtectedBackupTest {

    @TempDir
    Path tempDir;

    @Test
    void protectedBackupIncludesDumpFiveManagedDirectoriesManifestHashesAndRestoreScript() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClinicBackupService service = new ClinicBackupService(
            mock(ClinicBackupRepository.class),
            mapper,
            mock(ClinicDatabaseService.class),
            mock(ClinicFileService.class),
            "jdbc:mysql://127.0.0.1:3307/clinic_test",
            "clinic",
            "secret",
            "",
            ""
        ) {
            @Override
            protected void writeDatabaseDump(Path target) throws IOException {
                Files.writeString(target, "-- deterministic test dump\n");
            }
        };

        Map<String, Path> directories = new LinkedHashMap<>();
        for (String name : new String[] {
            "attachments", "generated-pre-ai", "generated-medical-records", "medical-record-workflow", "generated-ai-documents"
        }) {
            Path directory = tempDir.resolve("source").resolve(name);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(name + ".txt"), name);
            directories.put(name, directory);
        }

        Path backupDir = tempDir.resolve("manual-clean-backups").resolve("purge-test");
        var backup = service.createProtectedBackup(backupDir, directories, mapper.createObjectNode().put("patients", 4));

        assertThat(backup.directory()).isEqualTo(backupDir.toAbsolutePath().normalize());
        assertThat(backup.sha256()).matches("[0-9a-f]{64}");
        assertThat(Files.readString(backupDir.resolve("database.sql"))).contains("deterministic test dump");
        assertThat(backup.manifest().path("managedDirectories").size()).isEqualTo(5);
        assertThat(backup.manifest().path("beforeCounts").path("patients").asInt()).isEqualTo(4);
        assertThat(backupDir.resolve("sha256.json")).isRegularFile();
        assertThat(mapper.readTree(backupDir.resolve("sha256.json").toFile()).size()).isEqualTo(8);
        assertThat(backupDir.resolve("backup.sha256")).hasContent(backup.sha256());
        assertThat(backupDir.resolve("restore-protected-backup.ps1")).isRegularFile();
        for (String name : directories.keySet()) {
            assertThat(backupDir.resolve("managed-files").resolve(name).resolve(name + ".txt")).isRegularFile();
        }
    }
}
