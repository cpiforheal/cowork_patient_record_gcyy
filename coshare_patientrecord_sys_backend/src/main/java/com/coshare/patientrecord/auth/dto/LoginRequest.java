package com.coshare.patientrecord.auth.dto;

public record LoginRequest(String username, String accountHandle, String password) {
    public LoginRequest(String username, String password) {
        this(username, null, password);
    }
}
