package com.coshare.patientrecord.auth.dto;

public record PasswordChangeRequest(String currentPassword, String newPassword) {}
