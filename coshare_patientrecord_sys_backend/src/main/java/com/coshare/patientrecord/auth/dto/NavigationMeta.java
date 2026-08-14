package com.coshare.patientrecord.auth.dto;

public record NavigationMeta(
    String icon,
    String title,
    String activeMenu,
    String isLink,
    boolean isHide,
    boolean isFull,
    boolean isAffix,
    boolean isKeepAlive
) {}
