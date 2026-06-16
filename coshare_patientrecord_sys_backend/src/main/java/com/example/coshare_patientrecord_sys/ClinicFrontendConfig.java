package com.example.coshare_patientrecord_sys;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ClinicFrontendConfig implements WebMvcConfigurer {

    private final String frontendDir;

    public ClinicFrontendConfig(@Value("${clinic.frontend-dir:}") String frontendDir) {
        this.frontendDir = frontendDir == null ? "" : frontendDir.trim();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (frontendDir.isBlank()) {
            return;
        }

        String root = Path.of(frontendDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/assets/**").addResourceLocations(root + "assets/");
        registry.addResourceHandler("/favicon.ico").addResourceLocations(root);
    }
}
