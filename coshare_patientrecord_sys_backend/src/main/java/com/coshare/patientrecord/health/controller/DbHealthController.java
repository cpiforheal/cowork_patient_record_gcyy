package com.coshare.patientrecord.health.controller;

import com.coshare.patientrecord.health.service.DatabaseHealthService;
import com.coshare.patientrecord.health.service.RuntimeHealthService;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
public class DbHealthController {

    private final DatabaseHealthService databaseHealthService;
    private final RuntimeHealthService runtimeHealthService;

    public DbHealthController(DatabaseHealthService databaseHealthService, RuntimeHealthService runtimeHealthService) {
        this.databaseHealthService = databaseHealthService;
        this.runtimeHealthService = runtimeHealthService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return runtimeHealthService.summary();
    }

    @GetMapping("/health/db")
    public Map<String, Object> databaseHealth() {
        return databaseHealthService.check();
    }
}
