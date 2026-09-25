<template>
  <div class="sidenav-root">
    <SubMenu :menu-list="menuList" />
  </div>
</template>

<script setup lang="ts">
import SubMenu from "@/layouts/components/Menu/SubMenu.vue";

/**
 * 侧边栏导航容器。
 *
 * 分组小标题（section）的渲染放在 SubMenu → SectionSubMenu 里递归处理，
 * 因为带 section 的条目位于"患者就诊"这类分组的**子级**而不是顶层；
 * 只在顶层归组会得到 0 个分组，标题永远不显示。
 */
defineProps<{
  menuList: Menu.MenuOptions[];
  activeMenu: string;
  isCollapse: boolean;
  accordion: boolean;
}>();
</script>

<style lang="scss" scoped>
.sidenav-root {
  width: 100%;
}
</style>
