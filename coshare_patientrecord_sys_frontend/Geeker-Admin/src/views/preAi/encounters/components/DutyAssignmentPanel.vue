<template>
  <section class="duty-panel">
    <header>
      <div>
        <strong>本病例岗位安排</strong>
        <small>同一人员可承担多个岗位；每个病例明确一名主管医生。</small>
      </div>
      <el-button v-if="!disabled" type="primary" plain :loading="saving" @click="save">保存岗位安排</el-button>
    </header>
    <el-alert v-if="!hasAttending" type="warning" :closable="false" show-icon title="尚未指定主管医生，最终事实包责任人不明确" />
    <el-alert
      v-if="userLoadError"
      type="warning"
      :closable="false"
      show-icon
      title="人员目录暂时不可用，已保留本病例现有岗位人员，可稍后重新进入登记节点重试"
    />
    <div class="duty-grid">
      <article v-for="duty in duties" :key="duty.code">
        <div class="duty-title">
          <strong>{{ duty.label }}</strong>
          <small>{{ duty.hint }}</small>
        </div>
        <label>
          <span>责任人</span>
          <el-select
            v-model="draft[duty.code].responsibleUserId"
            filterable
            clearable
            :disabled="disabled"
            @change="syncNames(duty.code)"
          >
            <el-option v-for="user in users" :key="user.id" :label="userLabel(user)" :value="user.id" />
          </el-select>
        </label>
        <label>
          <span>协作人员</span>
          <el-select
            v-model="draft[duty.code].participantUserIds"
            multiple
            filterable
            clearable
            collapse-tags
            :disabled="disabled"
            @change="syncNames(duty.code)"
          >
            <el-option v-for="user in users" :key="user.id" :label="userLabel(user)" :value="user.id" />
          </el-select>
        </label>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import {
  getPreAiDutyUserOptionsApi,
  type PreAiDutyAssignment,
  type PreAiDutyCode,
  type PreAiDutyUserOption
} from "@/api/modules/clinic";

const props = defineProps<{
  assignments: PreAiDutyAssignment[];
  disabled?: boolean;
  saving?: boolean;
}>();

const emit = defineEmits<{ (event: "save", assignments: PreAiDutyAssignment[]): void }>();

const duties: Array<{ code: PreAiDutyCode; label: string; hint: string }> = [
  { code: "FRONT_DESK", label: "前台/收费", hint: "登记与收费信息" },
  { code: "RECEPTION_DOCTOR", label: "接诊医生", hint: "病史与初步处置" },
  { code: "TCM_DOCTOR", label: "中医师", hint: "四诊与辨证" },
  { code: "INSPECTION_DOCTOR", label: "检查/肠镜医师", hint: "专科检查与肠镜" },
  { code: "LAB_STAFF", label: "化验人员", hint: "化验任务" },
  { code: "BASIC_NURSING", label: "基础护理", hint: "生命体征" },
  { code: "ATTENDING_DOCTOR", label: "主管医生", hint: "事实包最终版本责任人" },
  { code: "SURGEON", label: "手术医生", hint: "术式、所见和步骤确认" },
  { code: "OPERATING_ROOM_NURSE", label: "手术室护士", hint: "手术事实代录与交接" },
  { code: "FINAL_REVIEW_DOCTOR", label: "最终复核医生", hint: "复核与版本锁定" }
];

const emptyAssignment = (dutyCode: PreAiDutyCode): PreAiDutyAssignment => ({
  dutyCode,
  responsibleUserId: "",
  responsibleUserName: "",
  participantUserIds: [],
  participantUserNames: []
});

const draft = reactive<Record<PreAiDutyCode, PreAiDutyAssignment>>(
  Object.fromEntries(duties.map(duty => [duty.code, emptyAssignment(duty.code)])) as Record<PreAiDutyCode, PreAiDutyAssignment>
);
const directoryUsers = ref<PreAiDutyUserOption[]>([]);
const userLoadError = ref("");
const hasAttending = computed(() => Boolean(draft.ATTENDING_DOCTOR.responsibleUserId));

const users = computed<PreAiDutyUserOption[]>(() => {
  const merged = new Map(directoryUsers.value.map(user => [user.id, user]));
  const appendExisting = (id?: string, name?: string) => {
    const normalizedId = String(id || "").trim();
    if (!normalizedId || merged.has(normalizedId)) return;
    const normalizedName = String(name || normalizedId).trim();
    merged.set(normalizedId, {
      id: normalizedId,
      username: normalizedName,
      name: normalizedName,
      department: ""
    });
  };
  for (const duty of duties) {
    const row = draft[duty.code];
    appendExisting(row.responsibleUserId, row.responsibleUserName);
    (row.participantUserIds || []).forEach((id, index) => appendExisting(id, row.participantUserNames?.[index]));
  }
  return Array.from(merged.values());
});

const userLabel = (user: PreAiDutyUserOption) => {
  const name = String(user.name || "").trim();
  const username = String(user.username || user.id).trim();
  return name && name !== username ? `${name}（${username}）` : username;
};

const hydrate = (assignments: PreAiDutyAssignment[] = []) => {
  for (const duty of duties) Object.assign(draft[duty.code], emptyAssignment(duty.code));
  for (const assignment of assignments) {
    if (!draft[assignment.dutyCode]) continue;
    Object.assign(draft[assignment.dutyCode], JSON.parse(JSON.stringify(assignment)));
    draft[assignment.dutyCode].participantUserIds ||= [];
    draft[assignment.dutyCode].participantUserNames ||= [];
  }
};

const syncNames = (code: PreAiDutyCode) => {
  const row = draft[code];
  const responsible = users.value.find(user => user.id === row.responsibleUserId);
  row.responsibleUserName = responsible?.name || responsible?.username || "";
  row.participantUserNames = (row.participantUserIds || [])
    .map(id => {
      const user = users.value.find(item => item.id === id);
      return user?.name || user?.username || "";
    })
    .filter(Boolean);
};

const save = () => {
  const assignments = duties
    .map(({ code }) => {
      syncNames(code);
      return JSON.parse(JSON.stringify(draft[code])) as PreAiDutyAssignment;
    })
    .filter(item => item.responsibleUserId || item.participantUserIds?.length);
  emit("save", assignments);
};

onMounted(async () => {
  try {
    const { data } = await getPreAiDutyUserOptionsApi();
    directoryUsers.value = data.list;
    userLoadError.value = "";
  } catch (error: any) {
    userLoadError.value = error.message || "人员目录加载失败";
  }
});

watch(() => props.assignments, hydrate, { immediate: true, deep: true });
</script>

<style scoped lang="scss">
.duty-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: var(--el-bg-color);
}

.duty-panel > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.duty-panel header div,
.duty-title,
.duty-grid article,
.duty-grid label {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.duty-panel small,
.duty-grid label > span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.duty-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.duty-grid article {
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-extra-light);
}

@media (max-width: 900px) {
  .duty-grid {
    grid-template-columns: 1fr;
  }
}
</style>
