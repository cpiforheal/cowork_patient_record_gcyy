<template>
  <aside class="history-panel" aria-label="历次病历只读对照">
    <header>
      <div>
        <el-tag type="info" effect="plain">只读对照</el-tag>
        <h3>历次病历回查</h3>
        <small>右侧切换不会改变当前复诊，也不会覆盖未保存内容。</small>
      </div>
      <el-button text aria-label="关闭历次病历对照" @click="$emit('close')">关闭</el-button>
    </header>

    <div class="history-actions">
      <el-button size="small" :disabled="!initialEncounter" @click="select(initialEncounter?.id)">查看初诊</el-button>
      <el-button size="small" :disabled="!previousEncounter" @click="select(previousEncounter?.id)">查看上次</el-button>
      <el-select :model-value="selectedEncounterId" filterable placeholder="选择其他就诊" @update:model-value="select">
        <el-option
          v-for="item in selectableHistory"
          :key="item.id"
          :value="item.id"
          :label="`${item.visitNo === 1 ? '初诊' : `第 ${item.visitNo} 次`} · ${item.visitDate || '日期待补'}`"
        />
      </el-select>
    </div>

    <div v-loading="loading" class="history-body">
      <el-empty v-if="!loading && !history.length" :image-size="70" description="当前患者暂无可回查的历史病历" />
      <el-empty v-else-if="!loading && !workspace" :image-size="70" description="请选择一份历史病历" />
      <template v-else-if="workspace">
        <section class="history-summary">
          <strong>{{ workspace.encounter.visitNo === 1 ? "初诊" : `第 ${workspace.encounter.visitNo} 次就诊` }}</strong>
          <el-tag size="small" type="info">{{ workspace.encounter.status }}</el-tag>
          <span>{{ workspace.encounter.patient.visitDate || "日期待补" }}</span>
          <span>{{ workspace.encounter.caseToken }}</span>
        </section>

        <el-collapse>
          <el-collapse-item
            v-for="stage in visibleStages"
            :key="stage.stageCode"
            :title="`${stageLabel(stage.stageCode)} · ${stageStatusLabel(stage.status)}`"
            :name="stage.stageCode"
          >
            <dl v-if="entries(stage.data).length" class="history-fields">
              <div v-for="[key, value] in entries(stage.data)" :key="key">
                <dt>{{ fieldLabel(stage.stageCode, key) }}</dt>
                <dd>{{ display(value) }}</dd>
              </div>
            </dl>
            <el-empty v-else :image-size="46" description="本阶段没有有效填写信息" />
          </el-collapse-item>
        </el-collapse>

        <section v-if="workspace.diagnoses.length" class="history-assets">
          <strong>诊断</strong>
          <p v-for="item in workspace.diagnoses" :key="String(item.id)">
            {{ item.diagnosisText || item.name || display(item) }}
          </p>
        </section>
        <section v-if="workspace.attachments.length" class="history-assets">
          <strong>检查资料与附件</strong>
          <AttachmentPreviewGallery :attachments="workspace.attachments" compact @download="$emit('download', $event)" />
        </section>
      </template>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type {
  PreAiAttachment,
  PreAiEncounterHistoryItem,
  PreAiStageCode,
  PreAiStageStatus,
  PreAiWorkspace
} from "@/api/modules/clinic";
import AttachmentPreviewGallery from "./AttachmentPreviewGallery.vue";

const props = defineProps<{
  history: PreAiEncounterHistoryItem[];
  currentEncounterId: string;
  selectedEncounterId: string;
  workspace?: PreAiWorkspace;
  loading: boolean;
  fieldLabel: (stageCode: PreAiStageCode, key: string) => string;
}>();

const emit = defineEmits<{
  select: [encounterId: string];
  close: [];
  download: [attachment: PreAiAttachment];
}>();

const selectableHistory = computed(() => props.history.filter(item => item.id !== props.currentEncounterId));
const initialEncounter = computed(() => selectableHistory.value.find(item => item.visitNo === 1));
const current = computed(() => props.history.find(item => item.id === props.currentEncounterId));
const previousEncounter = computed(() => {
  const linkedId = current.value?.previousEncounterId;
  return (
    selectableHistory.value.find(item => item.id === linkedId) ||
    selectableHistory.value.find(item => item.visitNo < (current.value?.visitNo || 1))
  );
});
const visibleStages = computed(() => props.workspace?.stages.filter(stage => stage.stageCode !== "REVIEW") || []);

const select = (encounterId?: string) => {
  if (encounterId) emit("select", encounterId);
};
const entries = (value: Record<string, any> = {}) =>
  Object.entries(value).filter(([, item]) => {
    if (item === undefined || item === null || item === "") return false;
    if (Array.isArray(item)) return item.length > 0;
    if (typeof item === "object") return Object.keys(item).length > 0;
    return true;
  });
const display = (value: any): string => {
  if (Array.isArray(value)) return value.map(display).filter(Boolean).join("、");
  if (value && typeof value === "object") {
    if ("value" in value) return [value.value, value.unit, value.status].filter(Boolean).join(" ");
    return Object.values(value).map(display).filter(Boolean).join("；");
  }
  return String(value ?? "");
};
const stageLabel = (stage: PreAiStageCode) =>
  ({
    REGISTRATION: "前台登记",
    INSPECTION: "检查室",
    RECEPTION: "接诊评估",
    TCM: "中医辨证",
    DOCTOR: "医生诊疗",
    SURGERY: "手术记录",
    REVIEW: "医生复核"
  })[stage];
const stageStatusLabel = (status: PreAiStageStatus) =>
  ({ DRAFT: "草稿", COMPLETED: "已完成", RETURNED: "已退回", SKIPPED: "已跳过" })[status];
</script>

<style scoped lang="scss">
.history-panel {
  min-width: 0;
  max-height: calc(100vh - 145px);
  position: sticky;
  top: 12px;
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  overflow: hidden;
  border: 1px solid var(--el-border-color-light);
  border-radius: 16px;
  background: var(--el-bg-color);
  box-shadow: 0 10px 30px rgb(31 78 120 / 9%);
}
.history-panel > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.history-panel h3 {
  margin: 7px 0 4px;
}
.history-panel small,
.history-summary span,
.history-fields dt {
  color: var(--el-text-color-secondary);
}
.history-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px 16px;
  background: var(--el-fill-color-light);
}
.history-actions :deep(.el-select) {
  width: 100%;
}
.history-body {
  min-height: 260px;
  overflow: auto;
  padding: 14px 16px 22px;
}
.history-summary {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 5px 10px;
  margin-bottom: 12px;
  padding: 12px;
  border-radius: 10px;
  background: var(--el-color-primary-light-9);
}
.history-fields {
  display: grid;
  gap: 10px;
  margin: 0;
}
.history-fields div {
  padding-bottom: 9px;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.history-fields dt {
  margin-bottom: 4px;
  font-size: 12px;
}
.history-fields dd {
  margin: 0;
  line-height: 1.65;
  white-space: pre-wrap;
}
.history-assets {
  display: grid;
  gap: 8px;
  margin-top: 14px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}
.history-assets p {
  margin: 0;
}
</style>
