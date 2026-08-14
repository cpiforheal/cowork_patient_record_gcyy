<template>
  <div class="login-container">
    <SwitchDark class="dark" />
    <div class="login-shell" :class="{ 'is-form-mode': isFormMode }">
      <section class="login-panel panel-form">
        <button v-if="isFormMode" type="button" class="form-switch" @click="showWelcome">返回欢迎页</button>
        <div class="login-logo">
          <img class="login-icon" src="@/assets/images/logo.jpg" alt="" />
          <div>
            <p class="logo-kicker">{{ portalBrand.kicker }}</p>
            <h1 class="logo-text">{{ portalBrand.title }}</h1>
          </div>
        </div>
        <p class="form-intro">{{ portalBrand.intro }}</p>
        <LoginForm />
      </section>

      <section class="login-panel panel-hello">
        <div class="hello-brand">
          <img class="hello-brand-icon" src="@/assets/images/logo.jpg" alt="" />
          <span>{{ portalBrand.helloBrand }}</span>
        </div>
        <div class="hello-copy">
          <span class="hello-kicker">{{ portalBrand.helloKicker }}</span>
          <h2>{{ portalBrand.helloTitle }}</h2>
          <h3>{{ portalBrand.helloSubtitle }}</h3>
          <el-button class="hello-action" round size="large" @click="isFormMode ? showWelcome() : showForm()">
            {{ isFormMode ? portalBrand.formSwitchBack : portalBrand.loginAction }}
          </el-button>
        </div>

        <div class="medical-scene" aria-hidden="true">
          <div class="scene-orbit orbit-one"></div>
          <div class="scene-orbit orbit-two"></div>
          <div class="scene-card card-record">
            <i></i>
            <span>病历同步</span>
          </div>
          <div class="scene-card card-stock">
            <i></i>
            <span>病历协同</span>
          </div>
          <div class="nurse-figure">
            <span class="nurse-cap"></span>
            <span class="nurse-head"></span>
            <span class="nurse-hair hair-left"></span>
            <span class="nurse-hair hair-right"></span>
            <span class="nurse-body"></span>
            <span class="nurse-arm arm-left"></span>
            <span class="nurse-arm arm-right"></span>
            <span class="nurse-leg leg-left"></span>
            <span class="nurse-leg leg-right"></span>
            <span class="clipboard">
              <i></i>
              <i></i>
              <i></i>
            </span>
          </div>
          <div class="plant">
            <span></span>
            <span></span>
            <i></i>
          </div>
          <div class="soft-cross cross-one"></div>
          <div class="soft-cross cross-two"></div>
          <div class="soft-dot dot-one"></div>
          <div class="soft-dot dot-two"></div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts" name="login">
import { computed, ref } from "vue";
import LoginForm from "./components/LoginForm.vue";
import SwitchDark from "@/components/SwitchDark/index.vue";

const isInventoryPortal = import.meta.env.VITE_PORTAL_MODE === "inventory";
const portalBrand = computed(() =>
  isInventoryPortal
    ? {
        kicker: "进销存门户",
        title: "欢迎回来",
        intro: "请正确选择您的科室或管理端并输入密码，进入耗材日报工作台。",
        helloKicker: "Inventory Workbench",
        helloTitle: "进销存管理平台",
        helloSubtitle: "耗材日报填报与汇总",
        helloBrand: "进销存门户",
        formSwitchBack: "返回欢迎页",
        loginAction: "进入登录"
      }
    : {
        kicker: "门诊协同入口",
        title: "欢迎回来！",
        intro: "请正确选择您的岗位并输入密码，进入对应工作台。",
        helloKicker: "Clinic Workbench",
        helloTitle: "门诊信息统一管理平台",
        helloSubtitle: "让工作更便捷",
        helloBrand: "门诊协同入口",
        formSwitchBack: "返回欢迎页",
        loginAction: "进入登录"
      }
);

const isFormMode = ref(false);

const showForm = () => {
  isFormMode.value = true;
};

const showWelcome = () => {
  isFormMode.value = false;
};
</script>

<style scoped lang="scss">
@use "./index.scss" as *;
</style>
