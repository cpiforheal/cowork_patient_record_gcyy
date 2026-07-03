package com.coshare.patientrecord.backup.entity;

public record BackupConfig(String backupDir, boolean enabled, String retentionPolicy) {}
