package com.coshare.patientrecord.auth.dto;

import java.util.List;
import java.util.Map;

public record NavigationResult(
    String version,
    List<NavigationMenu> menus,
    Map<String, List<String>> buttonPermissions,
    List<NavigationShortcut> shortcuts
) {}
