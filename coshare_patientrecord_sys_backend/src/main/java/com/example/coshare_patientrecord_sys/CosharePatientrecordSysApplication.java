package com.example.coshare_patientrecord_sys;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CosharePatientrecordSysApplication {

    public static void main(String[] args) {
        loadRuntimeEnv();
        SpringApplication.run(CosharePatientrecordSysApplication.class, args);
    }

    private static void loadRuntimeEnv() {
        for (Path path : runtimeEnvCandidates()) {
            if (Files.isRegularFile(path)) {
                loadRuntimeEnv(path);
                return;
            }
        }
    }

    private static List<Path> runtimeEnvCandidates() {
        String userDir = System.getProperty("user.dir", ".");
        return List.of(
            Path.of(userDir, "config", "runtime.env"),
            Path.of(userDir, "..", "config", "runtime.env").normalize(),
            Path.of(userDir, "..", "..", "config", "runtime.env").normalize()
        );
    }

    private static void loadRuntimeEnv(Path path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to read runtime config: " + path.toAbsolutePath(), error);
        }

        for (String line : lines) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            String[] parts = text.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            String name = parts[0].trim();
            String value = parts[1].trim();
            if (!name.isEmpty() && System.getenv(name) == null && System.getProperty(name) == null) {
                System.setProperty(name, value);
            }
        }
    }

}
