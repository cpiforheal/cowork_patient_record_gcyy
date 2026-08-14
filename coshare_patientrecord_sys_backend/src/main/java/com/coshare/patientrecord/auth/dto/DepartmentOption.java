package com.coshare.patientrecord.auth.dto;

public record DepartmentOption(
    String id,
    String code,
    String name,
    boolean primary,
    String status
) {}
