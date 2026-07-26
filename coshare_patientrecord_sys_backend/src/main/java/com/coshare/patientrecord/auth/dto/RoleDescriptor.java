package com.coshare.patientrecord.auth.dto;

import java.util.List;

public record RoleDescriptor(
    String role,
    String name,
    String responsibility,
    List<String> entries,
    List<String> actions,
    String dataScope,
    long memberCount
) {}
