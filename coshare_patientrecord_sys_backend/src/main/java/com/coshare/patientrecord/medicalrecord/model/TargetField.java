package com.coshare.patientrecord.medicalrecord.model;

import java.util.List;

public record TargetField(
    String section,
    String key,
    String label,
    String kind,
    boolean required,
    boolean aiPolishable,
    String defaultValue,
    String placeholder,
    List<String> sources,
    List<String> anchors
) {}
