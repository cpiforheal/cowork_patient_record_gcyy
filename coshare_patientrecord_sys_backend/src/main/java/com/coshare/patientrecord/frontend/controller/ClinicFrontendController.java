package com.coshare.patientrecord.frontend.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ClinicFrontendController {

    private final String frontendDir;

    public ClinicFrontendController(@Value("${clinic.frontend-dir:}") String frontendDir) {
        this.frontendDir = frontendDir == null ? "" : frontendDir.trim();
    }

    @GetMapping({
        "/",
        "/index.html",
        "/login",
        "/home",
        "/workbench/**",
        "/patients/**",
        "/system/**",
        "/templates/**",
        "/inventory/**",
        "/encounters/**",
        "/pre-ai/**",
        "/tcm-pharmacy/**"
    })
    public ResponseEntity<FileSystemResource> index() {
        if (frontendDir.isBlank()) {
            return ResponseEntity.notFound().build();
        }

        Path indexFile = Path.of(frontendDir).toAbsolutePath().normalize().resolve("index.html");
        if (!Files.isRegularFile(indexFile)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.TEXT_HTML)
            .body(new FileSystemResource(indexFile));
    }
}
