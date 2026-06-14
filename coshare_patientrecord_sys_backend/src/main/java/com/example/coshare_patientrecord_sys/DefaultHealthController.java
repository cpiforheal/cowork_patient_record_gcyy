package com.example.coshare_patientrecord_sys;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!mysql")
public class DefaultHealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("service", "coshare-patientrecord-backend");
        response.put("profile", "default");
        return response;
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
