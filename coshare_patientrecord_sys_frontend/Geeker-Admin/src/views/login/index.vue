<template>
  <div class="login-container">
    <div class="login-box">
      <SwitchDark class="dark" />
      <div class="login-left" aria-hidden="true">
        <div class="login-brief">
          <span class="brief-kicker">院内协同工作入口</span>
          <h1>让接诊、收录与追溯更清晰</h1>
          <p>病历、随访、审核与物资台账集中管理，减少重复录入。</p>
        </div>
        <div class="workflow-board">
          <div class="board-header">
            <span>今日工作</span>
            <strong>清晰留痕</strong>
          </div>
          <div class="board-row is-active">
            <i></i>
            <div>
              <strong>前台登记</strong>
              <span>门诊 / 住院来源同步</span>
            </div>
          </div>
          <div class="board-row">
            <i></i>
            <div>
              <strong>病历填写</strong>
              <span>字段选择器与校验辅助</span>
            </div>
          </div>
          <div class="board-row">
            <i></i>
            <div>
              <strong>审核归档</strong>
              <span>操作记录自动留存</span>
            </div>
          </div>
        </div>
        <div class="login-stats">
          <div>
            <span>岗位权限</span>
            <strong>按需进入</strong>
          </div>
          <div>
            <span>数据流转</span>
            <strong>可查可追</strong>
          </div>
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
        <LoginForm />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts" name="login">
import LoginForm from "./components/LoginForm.vue";
import SwitchDark from "@/components/SwitchDark/index.vue";
</script>

<style scoped lang="scss">
@import "./index.scss";
</style>
