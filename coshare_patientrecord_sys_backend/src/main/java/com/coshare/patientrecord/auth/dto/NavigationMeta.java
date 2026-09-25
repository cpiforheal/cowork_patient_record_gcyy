package com.coshare.patientrecord.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @param section 侧边栏分组标题。同一分组内的条目在导航中并列展示并共享一个小标题，
 *                为空时按普通菜单项渲染（进销存、系统管理等仍走分组/子菜单）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NavigationMeta(
    String icon,
    String title,
    String activeMenu,
    String isLink,
    boolean isHide,
    boolean isFull,
    boolean isAffix,
    boolean isKeepAlive,
    String section
) {}
