package com.coshare.patientrecord.auth.dto;

public record StagePermission(boolean readable, boolean editable, boolean correctable) {}
