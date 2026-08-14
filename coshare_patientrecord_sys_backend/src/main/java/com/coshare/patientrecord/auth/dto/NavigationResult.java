package com.coshare.patientrecord.auth.dto;

import java.util.List;
import java.util.Map;

public record NavigationResult(
    String version,
    String policyVersion,
    List<NavigationMenu> menus,
    Map<String, List<String>> buttonPermissions,
    List<NavigationShortcut> shortcuts,
    DepartmentOption activeDepartment,
    List<DepartmentOption> departments,
    List<String> capabilities,
    Map<String, StagePermission> stagePermissions,
    Map<String, AuxiliaryPermission> auxiliaryPermissions
) {}
