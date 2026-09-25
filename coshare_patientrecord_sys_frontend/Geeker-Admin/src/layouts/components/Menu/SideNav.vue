<template>
  <el-menu
    :router="false"
    :default-active="activeMenu"
    :collapse="isCollapse"
    :unique-opened="accordion"
    :collapse-transition="false"
  >
    <!-- 分组标题用 el-menu-item(disabled) 承载：el-menu 只认菜单项/子菜单，
         放自定义 <li> 会被丢弃，必须用组件自身的子项类型。 -->
    <template v-for="group in groups" :key="group.key">
      <el-menu-item class="nav-section-item" disabled>
        <span class="nav-section-text">{{ group.title }}</span>
      </el-menu-item>
      <SubMenu :menu-list="group.items" />
      <el-menu-item class="nav-section-gap" disabled />
    </template>
    <SubMenu :menu-list="plainMenus" />
  </el-menu>
</template>

<script setup lang="ts">
import { computed } from "vue";
import SubMenu from "@/layouts/components/Menu/SubMenu.vue";

const props = defineProps<{
  menuList: Menu.MenuOptions[];
  activeMenu: string;
  isCollapse: boolean;
  accordion: boolean;
}>();

/**
 * 把带 section 的菜单项按 section 归组，其余（进销存、系统管理等）保持原有分组/子菜单渲染。
 *
 * 这样"患者就诊"这类混装了日常作业与长期配置的分组，可以拆成若干并列小标题，
 * 既保留扁平点击路径、又不追加缩进层级；而进销存那种真正的层级菜单完全不受影响。
 */
type NavGroup = { key: string; title: string; items: Menu.MenuOptions[] };

const groups = computed<NavGroup[]>(() => {
  const result: NavGroup[] = [];
  const index = new Map<string, NavGroup>();
  for (const item of props.menuList) {
    const section = String(item.meta?.section || "").trim();
    if (!section) continue;
    let group = index.get(section);
    if (!group) {
      group = { key: `section-${section}`, title: section, items: [] };
      index.set(section, group);
      result.push(group);
    }
    group.items.push(item);
  }
  return result;
});

/** 未声明 section 的菜单项：维持原有渲染顺序与层级 */
const plainMenus = computed(() => props.menuList.filter(item => !String(item.meta?.section || "").trim()));
</script>

<style lang="scss" scoped>
/* 分组小标题：不可点击、无 hover 反馈，纯视觉分隔 */
.nav-section-item {
  height: 32px !important;
  min-height: 32px !important;
  padding-left: 18px !important;
  pointer-events: none;

  .nav-section-text {
    font-size: 11px;
    font-weight: 600;
    color: var(--el-text-color-placeholder);
    letter-spacing: 0.08em;
  }

  &:hover {
    background-color: transparent !important;
  }
}

/* 组间留白 + 细分隔线 */
.nav-section-gap {
  height: 18px !important;
  min-height: 18px !important;
  padding: 0 !important;
  pointer-events: none;

  &::after {
    display: block;
    width: calc(100% - 28px);
    height: 1px;
    margin: 8px auto 9px;
    content: "";
    background: var(--el-border-color-lighter);
  }
}
</style>
