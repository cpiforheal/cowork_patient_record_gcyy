<template>
  <div class="login-container" :class="`is-${focusMode}`" @pointermove="handlePointerMove" @pointerleave="resetPointer">
    <div class="login-box">
      <SwitchDark class="dark" />
      <div class="login-left" aria-hidden="true">
        <div class="care-scene">
          <div class="scene-copy">
            <span class="scene-kicker">门诊病历协同</span>
            <h1>让每一次接诊都有清晰留痕</h1>
          </div>
          <div class="soft-panel panel-main">
            <div class="panel-head"></div>
            <div class="panel-lines">
              <i></i>
              <i></i>
              <i></i>
            </div>
          </div>
          <div class="soft-panel panel-side">
            <div class="panel-ring"></div>
            <div class="panel-lines short">
              <i></i>
              <i></i>
            </div>
          </div>
          <div class="clinic-dog">
            <div class="dog-glow"></div>
            <img class="dog-photo" src="@/assets/images/login-dog.png" alt="" />
          </div>
          <div class="bubble bubble-one"></div>
          <div class="bubble bubble-two"></div>
          <div class="bubble bubble-three"></div>
        </div>
      </div>
      <div class="login-form">
        <div class="login-logo">
          <img class="login-icon" src="@/assets/images/logo.jpg" alt="" />
          <div>
            <h2 class="logo-text">门诊信息管理平台</h2>
            <p class="logo-subtitle">院内病历协同与追踪工作入口</p>
          </div>
        </div>
        <LoginForm @field-focus="focusMode = $event" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts" name="login">
import { ref } from "vue";
import LoginForm from "./components/LoginForm.vue";
import SwitchDark from "@/components/SwitchDark/index.vue";

type FocusMode = "idle" | "username" | "password";

const focusMode = ref<FocusMode>("idle");

const handlePointerMove = (event: PointerEvent) => {
  const target = event.currentTarget as HTMLElement;
  const rect = target.getBoundingClientRect();
  const x = ((event.clientX - rect.left) / rect.width - 0.5) * 2;
  const y = ((event.clientY - rect.top) / rect.height - 0.5) * 2;
  target.style.setProperty("--look-x", `${Math.max(-1, Math.min(1, x)) * 8}px`);
  target.style.setProperty("--look-y", `${Math.max(-1, Math.min(1, y)) * 5}px`);
};

const resetPointer = (event: PointerEvent) => {
  const target = event.currentTarget as HTMLElement;
  target.style.setProperty("--look-x", "0px");
  target.style.setProperty("--look-y", "0px");
};
</script>

<style scoped lang="scss">
@import "./index.scss";
</style>
