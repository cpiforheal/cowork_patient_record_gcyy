<!-- 纵向布局 -->
<template>
  <el-container class="layout">
    <el-aside class="desktop-aside">
      <div class="aside-box" :style="{ width: isCollapse ? '65px' : '210px' }">
        <div class="logo flx-center">
          <img class="logo-img" src="@/assets/images/logo.jpg" alt="logo" />
          <span v-show="!isCollapse" class="logo-text">{{ title }}</span>
        </div>
        <el-scrollbar>
          <el-menu
            :router="false"
            :default-active="activeMenu"
            :collapse="isCollapse"
            :unique-opened="accordion"
            :collapse-transition="false"
          >
            <SubMenu :menu-list="menuList" />
          </el-menu>
        </el-scrollbar>
      </div>
    </el-aside>
    <el-container>
      <el-header>
        <button class="mobile-menu-trigger" type="button" aria-label="打开主导航" @click="mobileMenuOpen = true">
          <el-icon><Menu /></el-icon>
        </button>
        <ToolBarLeft />
        <ToolBarRight />
      </el-header>
      <Main />
    </el-container>
    <el-drawer v-model="mobileMenuOpen" class="mobile-navigation-drawer" direction="ltr" size="82%" :with-header="false">
      <nav aria-label="主导航">
        <div class="mobile-logo">
          <img class="logo-img" src="@/assets/images/logo.jpg" alt="" />
          <strong>{{ title }}</strong>
        </div>
        <el-menu :router="false" :default-active="activeMenu" :unique-opened="accordion">
          <SubMenu :menu-list="menuList" />
        </el-menu>
      </nav>
    </el-drawer>
    <nav v-if="mobileShortcuts.length" class="mobile-shortcuts" aria-label="常用快捷入口">
      <button v-for="item in mobileShortcuts" :key="item.path" type="button" @click="router.push(item.path)">
        <el-icon><component :is="item.icon" /></el-icon>
        <span>{{ item.title }}</span>
      </button>
    </nav>
  </el-container>
</template>

<script setup lang="ts" name="layoutVertical">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useRouter } from "vue-router";
import { Menu } from "@element-plus/icons-vue";
import { useAuthStore } from "@/stores/modules/auth";
import { useGlobalStore } from "@/stores/modules/global";
import Main from "@/layouts/components/Main/index.vue";
import ToolBarLeft from "@/layouts/components/Header/ToolBarLeft.vue";
import ToolBarRight from "@/layouts/components/Header/ToolBarRight.vue";
import SubMenu from "@/layouts/components/Menu/SubMenu.vue";

const title = import.meta.env.VITE_GLOB_APP_TITLE;

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const globalStore = useGlobalStore();
const accordion = computed(() => globalStore.accordion);
const isCollapse = computed(() => globalStore.isCollapse);
const menuList = computed(() => authStore.showMenuListGet);
const activeMenu = computed(() => (route.meta.activeMenu ? route.meta.activeMenu : route.path) as string);
const mobileMenuOpen = ref(false);
const mobileShortcuts = computed(() => authStore.shortcutsGet.slice(0, 4));
watch(
  () => route.fullPath,
  () => (mobileMenuOpen.value = false)
);
</script>

<style scoped lang="scss">
@use "./index.scss" as *;

.mobile-menu-trigger,
.mobile-shortcuts {
  display: none;
}

.mobile-logo {
  display: flex;
  gap: 10px;
  align-items: center;
  min-height: 56px;
  padding: 0 14px;
  border-bottom: 1px solid var(--el-border-color-light);

  .logo-img {
    width: 32px;
  }
}

@media (max-width: 768px) {
  .desktop-aside {
    display: none;
  }

  .mobile-menu-trigger {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 44px;
    height: 44px;
    padding: 0;
    border: 0;
    color: var(--el-header-text-color);
    background: transparent;
    cursor: pointer;
  }

  .mobile-shortcuts {
    position: fixed;
    right: 0;
    bottom: 0;
    left: 0;
    z-index: 999;
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    min-height: 64px;
    padding-bottom: env(safe-area-inset-bottom);
    border-top: 1px solid var(--el-border-color-light);
    background: var(--el-bg-color);
    box-shadow: 0 -8px 24px rgb(0 0 0 / 0.08);

    button {
      display: flex;
      flex-direction: column;
      gap: 3px;
      align-items: center;
      justify-content: center;
      min-width: 0;
      min-height: 56px;
      padding: 4px;
      border: 0;
      color: var(--el-text-color-regular);
      background: transparent;
      cursor: pointer;
    }

    span {
      width: 100%;
      overflow: hidden;
      font-size: 11px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }
}
</style>

<style lang="scss">
@media (max-width: 768px) {
  .mobile-navigation-drawer .el-drawer__body {
    padding: 0;
  }
}
</style>
