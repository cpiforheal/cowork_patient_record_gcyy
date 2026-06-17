<template>
  <el-watermark id="watermark" :font="font" :content="watermark ? ['门诊信息管理平台', '操作留痕'] : ''">
    <LayoutVertical />
    <ThemeDrawer />
  </el-watermark>
</template>

<script setup lang="ts" name="layout">
import { computed, reactive, watch } from "vue";
import { useGlobalStore } from "@/stores/modules/global";
import ThemeDrawer from "./components/ThemeDrawer/index.vue";
import LayoutVertical from "./LayoutVertical/index.vue";

const globalStore = useGlobalStore();

const isDark = computed(() => globalStore.isDark);
const watermark = computed(() => globalStore.watermark);

const font = reactive({ color: "rgba(0, 0, 0, .15)" });
watch(isDark, () => (font.color = isDark.value ? "rgba(255, 255, 255, .15)" : "rgba(0, 0, 0, .15)"), {
  immediate: true
});
</script>

<style scoped lang="scss">
.layout {
  min-width: 600px;
}
</style>
