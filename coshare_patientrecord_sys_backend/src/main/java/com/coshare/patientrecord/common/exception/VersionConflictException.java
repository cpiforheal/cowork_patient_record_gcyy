package com.coshare.patientrecord.common.exception;

import java.util.Map;

public final class VersionConflictException extends RuntimeException {

    private final Map<String, Object> current;

    public VersionConflictException(String message, Map<String, Object> current) {
        super(message);
        this.current = current == null ? Map.of() : Map.copyOf(current);
    }

    public Map<String, Object> current() {
        return current;
    }
}
