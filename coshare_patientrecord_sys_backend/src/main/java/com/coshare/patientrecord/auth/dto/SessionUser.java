package com.coshare.patientrecord.auth.dto;

import java.time.Instant;

public record SessionUser(
    String id,
    String username,
    String name,
    String role,
    String roleLabel,
    String activeDepartmentId,
    String department,
    boolean mustChangePassword,
    Instant expiresAt
) {}
