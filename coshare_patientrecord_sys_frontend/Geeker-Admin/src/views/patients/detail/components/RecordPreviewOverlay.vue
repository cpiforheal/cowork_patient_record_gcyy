<template>
  <Teleport to="body">
    <Transition name="preview-overlay">
      <div v-if="visible" class="preview-overlay" @click.self="closePreview">
        <div class="preview-overlay-inner">
          <div class="preview-overlay-toolbar">
            <div class="preview-toolbar-title">
              <span>健康档案预览</span>

              <small>{{ title }}</small>
            </div>

            <div class="preview-page-pills" aria-label="预览页码">
              <button
                v-for="item in navigation"
                :key="item.key"
                type="button"
                :class="{ active: activePage === item.page }"
                @click="emit('scrollPage', item.page)"
              >
                {{ item.label }}
              </button>
            </div>

            <div>
              <el-button type="primary" :icon="Printer" @click="emit('print')">打印/导出 PDF</el-button>

              <el-button @click="closePreview">关闭</el-button>
            </div>
          </div>

          <div id="record-print-area" class="preview-overlay-scroll">
            <slot />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { Printer } from "@element-plus/icons-vue";

export type PreviewPageNav = {
  key: string;
  label: string;
  page: number;
};

defineProps<{
  visible: boolean;
  title: string;
  navigation: PreviewPageNav[];
  activePage: number;
}>();

const emit = defineEmits<{
  "update:visible": [visible: boolean];
  print: [];
  scrollPage: [page: number];
}>();

const closePreview = () => {
  emit("update:visible", false);
};
</script>

<style scoped lang="scss">
.preview-overlay {
  position: fixed;
  inset: 0;
  z-index: 2500;
  display: grid;
  place-items: center;
  background: rgb(var(--hos-primary-rgb) / 8%);
  backdrop-filter: blur(14px) saturate(122%);
  -webkit-backdrop-filter: blur(14px) saturate(122%);
}

.preview-overlay-inner {
  position: relative;
  display: flex;
  flex-direction: column;
  width: min(96vw, 1080px);
  height: 92vh;
  overflow: hidden;
  background: rgb(250 252 247 / 90%);
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-card);
  box-shadow: var(--hos-shadow-soft);
}

.preview-overlay-toolbar {
  position: absolute;
  top: 14px;
  right: 18px;
  left: 18px;
  z-index: 8;
  display: grid;
  grid-template-columns: minmax(180px, 1fr) auto auto;
  gap: 12px;
  align-items: center;
  padding: 10px 12px;
  background: rgb(250 255 249 / 78%);
  border: 1px solid rgb(255 255 255 / 72%);
  border-radius: var(--hos-radius-card);
  box-shadow:
    inset 0 1px 0 rgb(255 255 255 / 78%),
    0 18px 42px rgb(var(--hos-primary-rgb) / 10%);
  backdrop-filter: blur(18px) saturate(140%);
  -webkit-backdrop-filter: blur(18px) saturate(140%);

  > div {
    display: flex;
    gap: 8px;
  }
}

.preview-toolbar-title {
  display: grid !important;
  gap: 2px;
  min-width: 0;

  span,
  small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: var(--hos-text-primary);
    font-size: 16px;
    font-weight: 600;
  }

  small {
    color: var(--hos-text-secondary);
    font-size: 12px;
  }
}

.preview-page-pills {
  display: inline-flex;
  max-width: min(480px, 46vw);
  padding: 3px;
  overflow-x: auto;
  background: rgb(var(--hos-primary-rgb) / 8%);
  border: 1px solid var(--hos-border-light);
  border-radius: 8px;

  button {
    display: inline-grid;
    flex: 0 0 auto;
    place-items: center;
    min-width: 64px;
    height: 28px;
    padding: 0 10px;
    color: var(--hos-text-secondary);
    cursor: pointer;
    background: transparent;
    border: 0;
    border-radius: 6px;
    font-size: 12px;
    font-weight: 700;
    white-space: nowrap;

    &.active {
      color: #ffffff;
      background: var(--hos-primary-deep);
      box-shadow: 0 8px 18px rgb(var(--hos-primary-rgb) / 18%);
    }
  }
}

.preview-overlay-scroll {
  flex: 1;
  padding: 92px 20px 20px;
  overflow-y: auto;
  scroll-behavior: smooth;
  background: var(--hos-glass-mist);
}

.preview-overlay-enter-active {
  transition: opacity 200ms ease-out;

  .preview-overlay-inner {
    transition:
      transform 200ms ease-out,
      opacity 200ms ease-out;
  }
}

.preview-overlay-leave-active {
  transition: opacity 150ms ease-in;

  .preview-overlay-inner {
    transition:
      transform 150ms ease-in,
      opacity 150ms ease-in;
  }
}

.preview-overlay-enter-from {
  opacity: 0;

  .preview-overlay-inner {
    opacity: 0;
    transform: scale(0.95) translateY(10px);
  }
}

.preview-overlay-leave-to {
  opacity: 0;

  .preview-overlay-inner {
    opacity: 0;
    transform: scale(0.97) translateY(5px);
  }
}

@media (width <= 768px) {
  .preview-overlay-toolbar {
    grid-template-columns: 1fr;
  }

  .preview-page-pills {
    order: 3;
    width: 100%;
    max-width: 100%;
  }

  .preview-page-pills button {
    flex: 1 0 auto;
  }
}

@media print {
  @page {
    size: A4;
    margin: 12mm;
  }

  :global(body *) {
    visibility: hidden;
  }

  :global(body) {
    margin: 0;
    background: #ffffff;
  }

  .preview-overlay,
  .preview-overlay *,
  .preview-overlay-inner,
  .preview-overlay-scroll,
  .preview-overlay-scroll :deep(*) {
    visibility: visible;
  }

  :deep(.screen-only),
  .preview-overlay-toolbar {
    display: none !important;
  }

  .preview-overlay,
  .preview-overlay-inner,
  .preview-overlay-scroll {
    position: static;
    display: block;
    width: auto;
    height: auto;
    max-width: none;
    max-height: none;
    padding: 0;
    overflow: visible;
    background: #ffffff;
    border: 0;
    box-shadow: none;
  }
}
</style>
