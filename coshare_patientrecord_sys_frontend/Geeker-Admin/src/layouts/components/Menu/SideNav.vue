<template>
  <el-menu
    :router="false"
    :default-active="activeMenu"
    :collapse="isCollapse"
    :unique-opened="accordion"
    :collapse-transition="false"
  >
    <template v-for="group in groups" :key="group.key">
      <!-- 分组小标题：参考图的分组留白 + 小号灰字标题，让层级一眼可辨 -->
      <li v-if="group.title" class="nav-section-title">
        <span class="nav-section-text">{{ group.title }}</span>
      </li>
      <SubMenu :menu-list="group.items" />
      <li v-if="group.title" class="nav-section-gap" aria-hidden="true"></li>
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
.nav-section-title {
  display: flex;
  align-items: center;
  min-height: 30px;
  padding: 14px 18px 4px;
  margin: 0;
  list-style: none;
  overflow: hidden;

  .nav-section-text {
    font-size: 11px;
    font-weight: 600;
    color: var(--el-text-color-placeholder);
    letter-spacing: 0.08em;
    white-space: nowrap;
  }
}

/* 组间留白 + 细分隔线，制造"分组块"的呼吸感 */
.nav-section-gap {
  height: 1px;
  margin: 8px 14px 10px;
  list-style: none;
  background: var(--el-border-color-lighter);
}

/* 折叠态隐藏小标题与分隔线，只留图标 */
:deep(.el-menu--collapse) {
  .nav-section-title,
  .nav-section-gap {
    display: none;
  }
}
</style>
