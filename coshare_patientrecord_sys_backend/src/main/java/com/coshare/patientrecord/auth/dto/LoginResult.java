package com.coshare.patientrecord.auth.dto;

import java.util.Map;

public record LoginResult(String access_token, Map<String, String> userInfo) {}
