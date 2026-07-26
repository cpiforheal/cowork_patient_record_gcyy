package com.coshare.patientrecord.auth.dto;

import java.util.List;

public record AccountUpsertRequest(
    String username,
    String name,
    String role,
    String status,
    String password,
    List<String> departmentIds,
    String primaryDepartmentId,
    String scope
) {}
