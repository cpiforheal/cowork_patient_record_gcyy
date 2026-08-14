package com.coshare.patientrecord.auth.dto;

import java.util.List;

public record AccountSummary(
    String id,
    String username,
    String name,
    String role,
    String roleLabel,
    String status,
    List<String> departmentIds,
    String primaryDepartmentId,
    String department,
    String scope
) {}
