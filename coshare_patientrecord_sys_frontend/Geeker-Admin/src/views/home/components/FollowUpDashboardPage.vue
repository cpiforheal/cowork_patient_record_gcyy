<template>
  <div class="fud-page">
    <FollowUpDashboardPanel @open-script-manage="router.push('/pre-ai/script-manage')" />
  </div>
</template>

<script setup lang="ts" name="followUpDashboard">
import { useRouter } from "vue-router";
import FollowUpDashboardPanel from "./FollowUpDashboardPanel.vue";

const router = useRouter();
</script>

<style scoped lang="scss">
.fud-page {
  padding: 16px;
  max-width: 1280px;
  margin: 0 auto;
}
</style>
