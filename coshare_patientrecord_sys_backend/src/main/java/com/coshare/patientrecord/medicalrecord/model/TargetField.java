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
    List<String> anchors,
    List<String> viewerRoles,
    List<String> editorRoles,
    List<String> sourceArchiveKeys,
    String targetUse
) {
    public TargetField(
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
    ) {
        this(
            section,
            key,
            label,
            kind,
            required,
            aiPolishable,
            defaultValue,
            placeholder,
            sources,
            anchors,
            List.of("admin", "frontdesk", "reception", "lab", "ecg", "ultrasound", "inspection", "doctor", "nurse", "nursing", "quality"),
            List.of("admin", "doctor"),
            sources,
            "dynamic"
        );
    }
}
