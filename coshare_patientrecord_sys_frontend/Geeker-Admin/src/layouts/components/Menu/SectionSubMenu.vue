<template>
  <!-- 带分组的菜单层级：按 section 输出小标题，条目之间保持扁平（不追加缩进层级） -->
  <template v-for="group in groups" :key="group.key">
    <el-menu-item class="nav-section-item" disabled>
      <span class="nav-section-text">{{ group.title }}</span>
    </el-menu-item>
    <SubMenu :menu-list="group.items" />
    <el-menu-item class="nav-section-gap" disabled />
  </template>
  <SubMenu :menu-list="plainMenus" />
</template>

<script setup lang="ts">
import { computed } from "vue";
import SubMenu from "@/layouts/components/Menu/SubMenu.vue";

const props = defineProps<{ menuList: Menu.MenuOptions[] }>();

type NavGroup = { key: string; title: string; items: Menu.MenuOptions[] };

/**
 * 把带 section 的菜单项按 section 归组，其余保持原有渲染顺序。
 * 用于"患者就诊"这类把日常作业与长期配置混装在同一层的分组。
 */
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

const plainMenus = computed(() => props.menuList.filter(item => !String(item.meta?.section || "").trim()));
</script>

<style lang="scss" scoped>
/* 分组小标题：不可点击、无 hover 反馈，纯视觉分隔 */
.nav-section-item {
  height: 30px !important;
  min-height: 30px !important;
  padding-left: 34px !important;
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
  height: 14px !important;
  min-height: 14px !important;
  padding: 0 !important;
  pointer-events: none;

  &::after {
    display: block;
    width: calc(100% - 60px);
    height: 1px;
    margin: 6px 0 7px 40px;
    content: "";
    background: var(--el-border-color-lighter);
  }
}
</style>
