<template>
  <header class="greeting-banner">
    <div class="banner-main">
      <p class="banner-greeting">
        {{ greeting }}，{{ userName }}
        <span class="banner-role">{{ roleName }} · {{ department }}</span>
      </p>
      <h1 v-if="taskTitle">今天要处理什么：{{ taskTitle }}</h1>
      <h1 v-else>今天暂无紧急待办，保持节奏就好</h1>
      <p class="banner-tip">
        <el-icon><Sunny /></el-icon>
        {{ careTip }}
      </p>
    </div>
    <div class="banner-side">
      <div class="banner-clock">
        <strong>{{ clock }}</strong>
        <span>{{ dateText }}</span>
      </div>
      <el-button v-if="taskTitle" type="primary" :icon="ArrowRight" @click="$emit('openFirst')">进入第一项待办</el-button>
    </div>
  </header>
</template>

<script setup lang="ts">
import { ArrowRight, Sunny } from "@element-plus/icons-vue";
import { useGreeting } from "../composables/useGreeting";

defineProps<{
  userName: string;
  roleName: string;
  department: string;
  taskTitle?: string;
}>();
defineEmits<{ openFirst: [] }>();

const { greeting, clock, dateText, careTip } = useGreeting();
</script>

<style scoped lang="scss">
.greeting-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 20px 24px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  background:
    radial-gradient(circle at 92% 10%, rgba(198, 248, 221, 0.35), transparent 42%),
    linear-gradient(120deg, #f3fbf9 0%, var(--el-bg-color) 55%);
}
.banner-main {
  min-width: 0;
  display: grid;
  gap: 6px;
}
.banner-greeting {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 14px;
  font-weight: 600;
}
.banner-role {
  margin-left: 8px;
  padding: 2px 9px;
  border-radius: 999px;
  color: var(--hos-primary, #0f766e);
  background: rgba(15, 118, 110, 0.08);
  font-size: 12px;
  white-space: nowrap;
}
.banner-main h1 {
  overflow: hidden;
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: clamp(18px, 1.8vw, 24px);
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.banner-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  color: var(--hos-primary, #0f766e);
  font-size: 13px;
  .el-icon {
    color: #d9a114;
  }
}
.banner-side {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 18px;
}
.banner-clock {
  display: grid;
  gap: 2px;
  text-align: right;
  strong {
    color: var(--el-text-color-primary);
    font-size: 26px;
    font-variant-numeric: tabular-nums;
    line-height: 1.1;
  }
  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}
@media (max-width: 760px) {
  .greeting-banner {
    align-items: stretch;
    flex-direction: column;
    gap: 12px;
  }
  .banner-side {
    justify-content: space-between;
  }
  .banner-clock {
    text-align: left;
  }
  .banner-main h1 {
    white-space: normal;
  }
}
</style>
