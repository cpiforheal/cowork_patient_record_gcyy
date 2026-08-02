<template>
  <main class="system-select-page">
    <section class="system-select-hero">
      <span class="eyebrow">工作台入口</span>
      <h1>选择要进入的业务系统</h1>
      <p>前置病历与进销存使用独立导航和工作台，业务数据及权限仍沿用当前账号配置。</p>
    </section>

    <section class="system-cards" aria-label="业务系统选择">
      <button v-if="authStore.hasMedicalSystemAccessGet" class="system-card medical" type="button" @click="enterMedical">
        <span class="card-icon"><el-icon><Document /></el-icon></span>
        <span class="card-copy">
          <strong>前置病历系统</strong>
          <small>接诊、病历协作、患者与质控工作台</small>
        </span>
        <span class="card-action">进入系统 <el-icon><ArrowRight /></el-icon></span>
      </button>

      <button v-if="authStore.hasInventorySystemAccessGet" class="system-card inventory" type="button" @click="enterInventory">
        <span class="card-icon"><el-icon><Box /></el-icon></span>
        <span class="card-copy">
          <strong>进销存系统</strong>
          <small>库存、申领、耗材套餐、盘点与追溯</small>
        </span>
        <span class="card-action">进入系统 <el-icon><ArrowRight /></el-icon></span>
      </button>
    </section>
  </main>
</template>

<script setup lang="ts" name="systemSelect">
import { computed, onMounted } from "vue";
import { ArrowRight, Box, Document } from "@element-plus/icons-vue";
import { HOME_URL } from "@/config";
import { INVENTORY_SYSTEM_DASHBOARD } from "@/routers/modules/inventorySystem";
import { useRouter } from "vue-router";
import { useAuthStore } from "@/stores/modules/auth";

const router = useRouter();
const authStore = useAuthStore();
const availableSystemCount = computed(
  () => Number(authStore.hasMedicalSystemAccessGet) + Number(authStore.hasInventorySystemAccessGet)
);

const enterMedical = () => router.push(HOME_URL);
const enterInventory = () => router.push(INVENTORY_SYSTEM_DASHBOARD);

onMounted(() => {
  if (availableSystemCount.value !== 1) return;
  if (authStore.hasInventorySystemAccessGet) enterInventory();
  else enterMedical();
});
</script>

<style scoped lang="scss">
.system-select-page {
  min-height: calc(100vh - 148px);
  padding: clamp(32px, 7vw, 88px) clamp(20px, 6vw, 96px);
  background:
    radial-gradient(circle at 7% 7%, rgb(64 158 255 / 13%), transparent 33%),
    radial-gradient(circle at 93% 93%, rgb(103 194 58 / 12%), transparent 34%),
    var(--el-bg-color-page);
}

.system-select-hero {
  max-width: 760px;
  margin: 0 auto 42px;
  text-align: center;

  .eyebrow {
    display: inline-flex;
    padding: 5px 10px;
    color: var(--el-color-primary);
    font-size: 13px;
    font-weight: 600;
    letter-spacing: 0.08em;
    background: var(--el-color-primary-light-9);
    border-radius: 999px;
  }

  h1 {
    margin: 14px 0 12px;
    color: var(--el-text-color-primary);
    font-size: clamp(28px, 4vw, 38px);
    letter-spacing: -0.03em;
  }

  p {
    margin: 0;
    color: var(--el-text-color-secondary);
    line-height: 1.8;
  }
}

.system-cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
  max-width: 920px;
  margin: 0 auto;
}

.system-card {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 18px;
  align-items: center;
  min-height: 156px;
  padding: 28px;
  color: var(--el-text-color-primary);
  text-align: left;
  cursor: pointer;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 18px;
  box-shadow: 0 12px 32px rgb(31 45 61 / 7%);
  transition: transform 180ms ease, box-shadow 180ms ease, border-color 180ms ease;

  &:hover {
    border-color: var(--el-color-primary-light-5);
    box-shadow: 0 20px 38px rgb(31 45 61 / 14%);
    transform: translateY(-4px);
  }

  &.inventory:hover {
    border-color: var(--el-color-success-light-5);
  }
}

.card-icon {
  display: grid;
  width: 52px;
  height: 52px;
  color: var(--el-color-primary);
  font-size: 26px;
  place-items: center;
  background: var(--el-color-primary-light-9);
  border-radius: 15px;
}

.inventory .card-icon {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}

.card-copy {
  display: grid;
  gap: 8px;

  strong {
    font-size: 20px;
  }

  small {
    color: var(--el-text-color-secondary);
    font-size: 14px;
    line-height: 1.55;
  }
}

.card-action {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  color: var(--el-color-primary);
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
}

.inventory .card-action {
  color: var(--el-color-success);
}

@media (max-width: 720px) {
  .system-cards {
    grid-template-columns: 1fr;
  }

  .system-card {
    min-height: 132px;
    padding: 22px;
  }
}
</style>
