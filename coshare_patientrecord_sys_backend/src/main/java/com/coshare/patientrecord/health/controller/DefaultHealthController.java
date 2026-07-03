package com.coshare.patientrecord.health.controller;

import com.coshare.patientrecord.health.service.RuntimeHealthService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!mysql")
public class DefaultHealthController {

    private final RuntimeHealthService runtimeHealthService;

    public DefaultHealthController(RuntimeHealthService runtimeHealthService) {
        this.runtimeHealthService = runtimeHealthService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return runtimeHealthService.summary();
    }

    @GetMapping("/health/db")
    public Map<String, Object> databaseHealth() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("enabled", false);
        response.put("status", "disabled");
        response.put("profile", "mysql inactive");
        return response;
    }
}
