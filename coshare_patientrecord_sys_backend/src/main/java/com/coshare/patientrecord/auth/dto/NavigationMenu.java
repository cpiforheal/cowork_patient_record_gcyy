package com.coshare.patientrecord.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NavigationMenu(
    String path,
    String name,
    String component,
    String redirect,
    NavigationMeta meta,
    List<NavigationMenu> children
) {}
