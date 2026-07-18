<template>
  <section class="auxiliary-editor">
    <header>
      <div>
        <strong>辅助任务</strong>
        <small>生命体征、肠镜、心电和影像均为轻量任务，不增加主流程阶段。</small>
      </div>
      <el-dropdown v-if="canCreate" @command="createTask">
        <el-button type="primary" plain>新增辅助任务</el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-for="item in taskCreationOptions" :key="item.type" :command="item.type">{{
              item.label
            }}</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </header>

    <article v-for="task in editableTasks" :key="task.id" class="task-card">
      <header>
        <div>
          <strong>{{ auxiliaryTaskLabel[task.taskType] }} · {{ forms[task.id]?.title }}</strong>
          <small>{{ task.status === "COMPLETED" ? "已完成" : task.status === "RETURNED" ? "已退回" : "填写中" }}</small>
          <small v-if="task.updatedBy"
            >实际填写人：{{ task.updatedBy }}{{ task.completedBy ? ` · 完成人：${task.completedBy}` : "" }}</small
          >
        </div>
        <el-tag :type="task.requiredBeforeExport ? 'warning' : 'info'">{{
          task.requiredBeforeExport ? "复核前必需" : "非阻断"
        }}</el-tag>
      </header>

      <div class="task-meta">
        <el-input v-model="forms[task.id].title" :disabled="!canEdit(task)" placeholder="任务名称" />
        <el-checkbox v-model="forms[task.id].requiredBeforeExport" :disabled="!canEdit(task)">复核前必须完成</el-checkbox>
      </div>

      <div class="task-grid">
        <el-form-item
          v-for="field in visibleFields(task)"
          :key="field.key"
          :label="field.label"
          :required="field.required"
          :class="{ 'span-2': field.span === 2 }"
        >
          <StructuredField
            v-if="['measurement', 'repeatable', 'template-text'].includes(field.kind)"
            v-model="forms[task.id].data[field.key]"
            :field="field"
            :form="forms[task.id].data"
            :generated-text="generatedText(field, forms[task.id].data)"
            :source-hash="stableSourceHash([forms[task.id].data])"
            :disabled="!canEdit(task)"
            @patch="value => patchForm(task.id, value)"
          />
          <el-input
            v-else-if="field.kind === 'input' || field.kind === 'number'"
            v-model="forms[task.id].data[field.key]"
            :type="field.kind === 'number' ? 'number' : 'text'"
            :disabled="!canEdit(task)"
          />
          <el-input
            v-else-if="field.kind === 'textarea'"
            v-model="forms[task.id].data[field.key]"
            type="textarea"
            :rows="field.rows || 3"
            :disabled="!canEdit(task)"
          />
          <el-select
            v-else-if="field.kind === 'select' || field.kind === 'multi'"
            v-model="forms[task.id].data[field.key]"
            :multiple="field.kind === 'multi'"
            clearable
            filterable
            :allow-create="field.creatable"
            :disabled="!canEdit(task)"
          >
            <el-option
              v-for="option in fieldOptions(field, forms[task.id].data)"
              :key="String(option.value)"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-date-picker
            v-else
            v-model="forms[task.id].data[field.key]"
            :type="field.kind === 'date' ? 'date' : 'datetime'"
            :value-format="field.kind === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM-DD HH:mm:ss'"
            :disabled="!canEdit(task)"
          />
        </el-form-item>
      </div>

      <footer>
        <el-button v-if="canReturn" type="warning" plain @click="$emit('return-task', task.id)">退回</el-button>
        <span></span>
        <el-button v-if="canEdit(task)" :loading="loading" @click="saveTask(task, false)">保存草稿</el-button>
        <el-button v-if="canEdit(task)" type="primary" :loading="loading" @click="saveTask(task, true)">完成任务</el-button>
      </footer>
    </article>
    <el-empty v-if="!editableTasks.length" :image-size="72" description="尚未创建生命体征、肠镜、心电或影像任务" />
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from "vue";
import { ElMessage } from "element-plus";
import {
  createPreAiAuxiliaryTaskApi,
  savePreAiAuxiliaryTaskApi,
  type PreAiAuxiliaryTask,
  type PreAiAuxiliaryTaskType,
  type PreAiWorkspace
} from "@/api/modules/clinic";
import StructuredField from "./StructuredField.vue";
import { auxiliaryTaskFields, auxiliaryTaskLabel, type PreAiFieldConfig } from "../fieldConfig";
import { buildColonoscopyConclusion, stableSourceHash } from "../utils/templateTextGenerator";

const props = defineProps<{
  workspace: PreAiWorkspace;
  currentUserId?: string;
  currentUserName?: string;
  loading?: boolean;
  canReturn?: boolean;
  capabilities: string[];
  permissions: Record<string, { readable: boolean; editable: boolean; returnable: boolean }>;
}>();

const emit = defineEmits<{
  (event: "updated", workspace: PreAiWorkspace): void;
  (event: "return-task", taskId: string): void;
}>();

const forms = reactive<Record<string, { title: string; requiredBeforeExport: boolean; data: Record<string, any> }>>({});
const editableTasks = computed(() => props.workspace.auxiliaryTasks.filter(task => task.taskType !== "LAB"));
const taskDutyCodes: Partial<Record<PreAiAuxiliaryTaskType, string[]>> = {
  VITAL_SIGNS: ["BASIC_NURSING"],
  COLONOSCOPY: ["INSPECTION_DOCTOR"],
  LAB: ["LAB_STAFF"]
};
const creationCandidates: Array<{ type: PreAiAuxiliaryTaskType; label: string }> = [
  { type: "VITAL_SIGNS", label: "生命体征" },
  { type: "COLONOSCOPY", label: "肠镜" },
  { type: "ECG", label: "心电" },
  { type: "IMAGING", label: "影像" }
];
const hasDuty = (...codes: string[]) => {
  const userId = props.currentUserId || "";
  const userName = props.currentUserName || "";
  return props.workspace.dutyAssignments.some(
    item =>
      codes.includes(item.dutyCode) &&
      ((Boolean(userId) && (item.responsibleUserId === userId || item.participantUserIds?.includes(userId))) ||
        (Boolean(userName) && (item.responsibleUserName === userName || item.participantUserNames?.includes(userName))))
  );
};
const canCreateTask = (type: PreAiAuxiliaryTaskType) => {
  const capability = `preai:auxiliary:${type.toLowerCase()}:create`;
  return (
    props.capabilities.includes(capability) ||
    hasDuty("ATTENDING_DOCTOR", "RECEPTION_DOCTOR") ||
    hasDuty(...(taskDutyCodes[type] || []))
  );
};
const taskCreationOptions = computed(() => creationCandidates.filter(item => canCreateTask(item.type)));
const canCreate = computed(() => taskCreationOptions.value.length > 0);

const hydrate = () => {
  for (const task of editableTasks.value) {
    forms[task.id] = {
      title: task.title,
      requiredBeforeExport: task.requiredBeforeExport,
      data: JSON.parse(JSON.stringify(task.data || {}))
    };
    for (const field of auxiliaryTaskFields[task.taskType]) {
      if (field.kind === "multi" && !Array.isArray(forms[task.id].data[field.key])) forms[task.id].data[field.key] = [];
      if (field.kind === "repeatable" && !Array.isArray(forms[task.id].data[field.key])) forms[task.id].data[field.key] = [];
      if (field.kind === "measurement" && !forms[task.id].data[field.key]) {
        forms[task.id].data[field.key] = { value: "", unit: field.unitOptions?.[0] || "", status: "" };
      }
    }
    if (task.taskType === "VITAL_SIGNS" && !forms[task.id].data.measuredAt) {
      forms[task.id].data.measuredAt = new Date().toISOString().slice(0, 19).replace("T", " ");
    }
  }
};

const canEdit = (task: PreAiAuxiliaryTask) =>
  task.status !== "COMPLETED" &&
  (Boolean(props.permissions[task.taskType]?.editable) || hasDuty(...(taskDutyCodes[task.taskType] || [])));
const fieldOptions = (field: PreAiFieldConfig, form: Record<string, any>) => field.optionsFor?.(form) || field.options || [];
const visibleFields = (task: PreAiAuxiliaryTask) =>
  auxiliaryTaskFields[task.taskType].filter(field => !field.visible || field.visible(forms[task.id]?.data || {}));
const patchForm = (taskId: string, value: Record<string, any>) => Object.assign(forms[taskId].data, value);
const generatedText = (field: PreAiFieldConfig, form: Record<string, any>) =>
  field.templateGenerator === "colonoscopyConclusion" ? buildColonoscopyConclusion(form) : "";

const createTask = async (type: PreAiAuxiliaryTaskType) => {
  try {
    const title = auxiliaryTaskLabel[type];
    const { data } = await createPreAiAuxiliaryTaskApi(props.workspace.encounter.id, {
      taskType: type,
      title,
      requiredBeforeExport: false
    });
    emit("updated", data);
    ElMessage.success(`${title}任务已创建`);
  } catch (error: any) {
    ElMessage.error(error.message || "辅助任务创建失败");
  }
};

const cleanData = (task: PreAiAuxiliaryTask) => {
  const form = forms[task.id].data;
  if (task.taskType === "COLONOSCOPY" && form.status === "COMPLETED") form.conclusion = buildColonoscopyConclusion(form);
  const result: Record<string, any> = {};
  for (const field of visibleFields(task)) {
    const value = form[field.key];
    if (value === undefined || value === null || value === "" || (Array.isArray(value) && !value.length)) continue;
    if (field.kind === "measurement" && !value.value) continue;
    result[field.key] = value;
  }
  return result;
};

const saveTask = async (task: PreAiAuxiliaryTask, complete: boolean) => {
  try {
    const form = forms[task.id];
    const { data } = await savePreAiAuxiliaryTaskApi(
      props.workspace.encounter.id,
      task.id,
      {
        title: form.title,
        requiredBeforeExport: form.requiredBeforeExport,
        data: cleanData(task),
        expectedVersion: task.version
      },
      complete
    );
    emit("updated", data);
    ElMessage.success(complete ? "辅助任务已完成" : "辅助任务草稿已保存");
  } catch (error: any) {
    ElMessage.error(error.message || "辅助任务保存失败");
  }
};

watch(() => props.workspace.auxiliaryTasks, hydrate, { immediate: true, deep: true });
</script>

<style scoped lang="scss">
.auxiliary-editor,
.task-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.auxiliary-editor > header,
.task-card > header,
.task-card > footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.auxiliary-editor header div,
.task-card header div {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.auxiliary-editor small,
.task-card small {
  color: var(--el-text-color-secondary);
}

.task-card {
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
}

.task-meta,
.task-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.task-grid .span-2 {
  grid-column: 1 / -1;
}

.task-card > footer span {
  flex: 1;
}

@media (max-width: 760px) {
  .task-meta,
  .task-grid {
    grid-template-columns: 1fr;
  }
}
</style>
