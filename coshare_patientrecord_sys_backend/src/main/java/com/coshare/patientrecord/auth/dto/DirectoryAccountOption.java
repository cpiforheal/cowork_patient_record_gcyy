package com.coshare.patientrecord.auth.dto;

public record DirectoryAccountOption(
    String id,
    String name,
    String username,
    String role,
    String roleLabel,
    String department
) {}
