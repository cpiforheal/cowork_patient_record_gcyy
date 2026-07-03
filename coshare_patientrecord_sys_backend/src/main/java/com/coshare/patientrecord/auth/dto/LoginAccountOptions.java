package com.coshare.patientrecord.auth.dto;

import java.util.List;
import java.util.Map;

public record LoginAccountOptions(List<Map<String, String>> accounts) {}
