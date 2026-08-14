package com.coshare.patientrecord.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PortalMode {
    private final String value;

    public PortalMode(@Value("${clinic.portal-mode:medical}") String value) {
        this.value = value == null ? "medical" : value.trim();
    }

    public boolean isInventoryPortal() {
        return "inventory".equalsIgnoreCase(value);
    }
}