<template>
  <div class="recheck-page">
    <header class="rk-head">
      <div>
        <h2>复查预约登记</h2>
        <p>
          检查室共享登记表：按日期登记次日及之后的复查患者，到/未到由当班人工标记，交接班时核对。改期会在新日期生成登记，原日期记为"改期"。
        </p>
      </div>
      <el-button v-if="canEdit" type="primary" @click="openCreate()">登记复查</el-button>
    </header>

    <section class="rk-summary" aria-label="交接摘要">
      <div v-for="card in summaryCards" :key="card.label" class="rk-card" :class="card.tone">
        <span class="rk-card-label">{{ card.label }}</span>
        <strong class="rk-card-value">{{ card.value }}</strong>
        <span class="rk-card-sub">{{ card.sub }}</span>
      </div>
    </section>

    <section class="rk-toolbar">
      <el-button-group>
        <el-button @click="shift(-7)">上一周</el-button>
        <el-button @click="resetRange">今天起</el-button>
        <el-button @click="shift(7)">下一周</el-button>
      </el-button-group>
      <el-date-picker
        v-model="range"
        type="daterange"
        value-format="YYYY-MM-DD"
        range-separator="至"
        :clearable="false"
        style="width: 260px"
        @change="load"
      />
      <el-switch v-model="onlyFilled" active-text="只看有登记的日期" />
      <el-input v-model="keyword" placeholder="按姓名/电话查找" clearable style="width: 180px" />
      <el-button :loading="loading" @click="load">刷新</el-button>
    </section>

    <section v-loading="loading" class="rk-table" role="table" aria-label="复查登记表">
      <div class="rk-row rk-row-head" role="row">
        <div role="columnheader">日期</div>
        <div role="columnheader">计划 / 已到 / 未到 / 待到</div>
        <div role="columnheader">患者</div>
      </div>
      <div v-if="!visibleDays.length" class="rk-empty">所选范围内没有登记</div>
      <div v-for="day in visibleDays" :key="day.date" class="rk-row" :class="`is-${day.relation}`" role="row">
        <div class="rk-date" role="cell">
          <strong>{{ day.date.slice(5) }}</strong>
          <span>{{ day.weekday }}</span>
          <el-tag v-if="day.relation === 'today'" size="small" effect="dark" type="success">今天</el-tag>
          <el-tag v-else-if="day.date === tomorrow" size="small" type="success">明天</el-tag>
        </div>
        <div class="rk-stats" role="cell">
          <span class="n-plan" title="计划">{{ day.stats.planned }}</span>
          <span class="n-arrived" title="已到">{{ day.stats.arrived }}</span>
          <span class="n-absent" title="未到">{{ day.stats.absent }}</span>
          <span class="n-pending" title="待到">{{ day.stats.pending }}</span>
          <span v-if="day.stats.rescheduled" class="rk-extra">改期 {{ day.stats.rescheduled }}</span>
          <span v-if="day.stats.unconfirmed" class="rk-warn">{{ day.stats.unconfirmed }} 人未确认</span>
        </div>
        <div class="rk-chips" role="cell">
          <el-dropdown
            v-for="entry in filterEntries(day.entries)"
            :key="entry.id"
            trigger="click"
            :disabled="!canEdit || entry.status === 'RESCHEDULED'"
            @command="(cmd: string) => onCommand(cmd, entry)"
          >
            <button type="button" class="rk-chip" :class="`s-${entry.status.toLowerCase()}`" :title="chipTitle(entry)">
              <span class="rk-dot" aria-hidden="true" />
              {{ entry.patientName }}
              <small>{{ STATUS_LABEL[entry.status] }}</small>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="ARRIVED" :disabled="day.relation === 'future' || entry.status === 'ARRIVED'"
                  >标记已到</el-dropdown-item
                >
                <el-dropdown-item command="ABSENT" :disabled="day.relation === 'future' || entry.status === 'ABSENT'"
                  >标记未到…</el-dropdown-item
                >
                <el-dropdown-item v-if="entry.status === 'ARRIVED' || entry.status === 'ABSENT'" command="PLANNED"
                  >撤回为待到</el-dropdown-item
                >
                <el-dropdown-item command="reschedule" :disabled="entry.status === 'ARRIVED'" divided>改期…</el-dropdown-item>
                <el-dropdown-item command="edit">编辑信息…</el-dropdown-item>
                <el-dropdown-item command="cancel" divided>撤销登记</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <button
            v-if="canEdit"
            type="button"
            class="rk-add"
            :aria-label="`登记 ${day.date} 的复查`"
            @click="openCreate(day.date)"
          >
            + 登记
          </button>
        </div>
      </div>
    </section>

    <el-dialog v-model="formVisible" :title="formMode === 'edit' ? '编辑登记' : '登记复查'" width="480px" destroy-on-close>
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="复查日期" required>
          <el-date-picker
            v-model="form.planDate"
            type="date"
            value-format="YYYY-MM-DD"
            :disabled="formMode === 'edit' && editing?.status !== 'PLANNED'"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="患者姓名" required>
          <el-input
            v-model="form.patientName"
            maxlength="200"
            :placeholder="formMode === 'create' ? '多名患者可用 、或空格分隔批量登记' : ''"
          />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="form.phone" maxlength="30" placeholder="选填；同名患者用电话区分" :disabled="isBatch" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" maxlength="200" placeholder="如：术后复查、换药、看报告" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveForm">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rescheduleVisible" title="改期" width="420px" destroy-on-close>
      <p class="rk-dialog-tip">
        {{ editing?.patientName }}：原定 {{ editing?.planDate }}，改期后原日期记为"改期"，不计入计划人数。
      </p>
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="新日期" required>
          <el-date-picker v-model="rescheduleForm.newDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="rescheduleForm.reason" maxlength="200" placeholder="如：患者来电改约、发热暂缓" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rescheduleVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveReschedule">确认改期</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="recheckSchedule">
import { computed, onMounted, reactive, ref } from "vue";
import dayjs from "dayjs";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  cancelRecheckApi,
  createRecheckApi,
  getRecheckBoardApi,
  markRecheckApi,
  rescheduleRecheckApi,
  updateRecheckApi,
  type RecheckBoard,
  type RecheckEntry,
  type RecheckStatus
} from "@/api/modules/clinic/recheckSchedule";

const STATUS_LABEL: Record<RecheckStatus, string> = {
  PLANNED: "待到",
  ARRIVED: "已到",
  ABSENT: "未到",
  RESCHEDULED: "已改期",
  CANCELLED: "已撤销"
};

const defaultRange = (): [string, string] => [
  dayjs().subtract(1, "day").format("YYYY-MM-DD"),
  dayjs().add(14, "day").format("YYYY-MM-DD")
];

const range = ref<[string, string]>(defaultRange());
const board = ref<RecheckBoard | null>(null);
const loading = ref(false);
const saving = ref(false);
const onlyFilled = ref(false);
const keyword = ref("");

const canEdit = computed(() => board.value?.canEdit === true);
const tomorrow = computed(() => dayjs(board.value?.today).add(1, "day").format("YYYY-MM-DD"));

const load = async () => {
  loading.value = true;
  try {
    board.value = await getRecheckBoardApi(range.value[0], range.value[1]);
  } catch (error: any) {
    ElMessage.error(error?.message || "加载失败");
  } finally {
    loading.value = false;
  }
};

const shift = (days: number) => {
  range.value = [
    dayjs(range.value[0]).add(days, "day").format("YYYY-MM-DD"),
    dayjs(range.value[1]).add(days, "day").format("YYYY-MM-DD")
  ];
  void load();
};

const resetRange = () => {
  range.value = defaultRange();
  void load();
};

const matches = (entry: RecheckEntry) => {
  const key = keyword.value.trim();
  return !key || entry.patientName.includes(key) || entry.phone.includes(key);
};

const filterEntries = (entries: RecheckEntry[]) => entries.filter(matches);

const visibleDays = computed(() => {
  const days = board.value?.days ?? [];
  if (!onlyFilled.value && !keyword.value.trim()) return days;
  return days.filter(day => filterEntries(day.entries).length > 0);
});

const dayOf = (date: string) => board.value?.days.find(day => day.date === date);

const summaryCards = computed(() => {
  const today = board.value ? dayOf(board.value.today) : undefined;
  const next = dayOf(tomorrow.value);
  return [
    {
      label: "今日复查",
      value: today ? `${today.stats.arrived} / ${today.stats.planned}` : "—",
      sub: today ? `已到 / 计划，未到 ${today.stats.absent}，待到 ${today.stats.pending}` : "不在当前范围",
      tone: "tone-brand"
    },
    {
      label: "明日计划",
      value: next ? String(next.stats.planned) : "—",
      sub: next ? "人（交接时提醒接班同事）" : "不在当前范围",
      tone: ""
    },
    {
      label: "范围内到诊",
      value: board.value?.total.arrivalRate == null ? "—" : `${board.value.total.arrivalRate}%`,
      sub: board.value
        ? `已确认 ${board.value.total.arrived + board.value.total.absent} 人中已到 ${board.value.total.arrived}`
        : "",
      tone: ""
    },
    {
      label: "过去待确认",
      value: String(board.value?.unconfirmedPast ?? 0),
      sub: "已过日期仍是待到，请补标到/未到",
      tone: (board.value?.unconfirmedPast ?? 0) > 0 ? "tone-warn" : ""
    }
  ];
});

const chipTitle = (entry: RecheckEntry) =>
  [
    entry.phone && `电话：${entry.phone}`,
    entry.note && `备注：${entry.note}`,
    entry.statusNote && `${entry.status === "RESCHEDULED" ? "改期原因" : "未到原因"}：${entry.statusNote}`,
    entry.rescheduledTo && `已改至 ${entry.rescheduledTo}`,
    entry.sourceId && "由其他日期改期而来",
    `登记：${entry.createdBy || "—"} ${entry.createdAt || ""}`,
    entry.updatedAt !== entry.createdAt && `最近：${entry.updatedBy || "—"} ${entry.updatedAt || ""}`
  ]
    .filter(Boolean)
    .join("\n");

// ---------- 登记 / 编辑 ----------

const formVisible = ref(false);
const formMode = ref<"create" | "edit">("create");
const editing = ref<RecheckEntry | null>(null);
const form = reactive({ planDate: "", patientName: "", phone: "", note: "" });

const splitNames = (text: string) =>
  text
    .split(/[、，,;；\s]+/)
    .map(name => name.trim())
    .filter(Boolean);

const isBatch = computed(() => formMode.value === "create" && splitNames(form.patientName).length > 1);

const openCreate = (date?: string) => {
  formMode.value = "create";
  editing.value = null;
  Object.assign(form, { planDate: date || tomorrow.value, patientName: "", phone: "", note: "" });
  formVisible.value = true;
};

const openEdit = (entry: RecheckEntry) => {
  formMode.value = "edit";
  editing.value = entry;
  Object.assign(form, { planDate: entry.planDate, patientName: entry.patientName, phone: entry.phone, note: entry.note });
  formVisible.value = true;
};

const saveForm = async () => {
  if (!form.planDate) return ElMessage.warning("请选择复查日期");
  const names = formMode.value === "create" ? splitNames(form.patientName) : [form.patientName.trim()];
  if (!names.length || !names[0]) return ElMessage.warning("请填写患者姓名");
  saving.value = true;
  try {
    if (formMode.value === "edit" && editing.value) {
      await updateRecheckApi(editing.value.id, { ...form, patientName: names[0] });
      ElMessage.success("已更新");
    } else {
      // 批量登记逐条提交：重复的单条失败不影响其余
      const failures: string[] = [];
      for (const name of names) {
        try {
          await createRecheckApi({
            planDate: form.planDate,
            patientName: name,
            phone: isBatch.value ? "" : form.phone,
            note: form.note
          });
        } catch (error: any) {
          failures.push(error?.message || name);
        }
      }
      if (failures.length) ElMessage.warning(`已登记 ${names.length - failures.length} 人；未成功：${failures.join("；")}`);
      else ElMessage.success(`已登记 ${names.length} 人`);
    }
    formVisible.value = false;
    await load();
  } catch (error: any) {
    ElMessage.error(error?.message || "保存失败");
  } finally {
    saving.value = false;
  }
};

// ---------- 状态 / 改期 / 撤销 ----------

const rescheduleVisible = ref(false);
const rescheduleForm = reactive({ newDate: "", reason: "" });

const saveReschedule = async () => {
  if (!editing.value) return;
  if (!rescheduleForm.newDate) return ElMessage.warning("请选择新日期");
  saving.value = true;
  try {
    await rescheduleRecheckApi(editing.value.id, rescheduleForm.newDate, rescheduleForm.reason.trim());
    ElMessage.success(`已改期至 ${rescheduleForm.newDate}`);
    rescheduleVisible.value = false;
    await load();
  } catch (error: any) {
    ElMessage.error(error?.message || "改期失败");
  } finally {
    saving.value = false;
  }
};

const onCommand = async (command: string, entry: RecheckEntry) => {
  try {
    if (command === "edit") return openEdit(entry);
    if (command === "reschedule") {
      editing.value = entry;
      Object.assign(rescheduleForm, { newDate: dayjs(entry.planDate).add(7, "day").format("YYYY-MM-DD"), reason: "" });
      rescheduleVisible.value = true;
      return;
    }
    if (command === "cancel") {
      await ElMessageBox.confirm(
        `撤销 ${entry.planDate} ${entry.patientName} 的登记？撤销后不计入统计（用于登记错误）。`,
        "撤销登记",
        {
          type: "warning",
          confirmButtonText: "撤销",
          cancelButtonText: "取消"
        }
      );
      await cancelRecheckApi(entry.id);
      ElMessage.success("已撤销");
    } else if (command === "ABSENT") {
      const { value } = await ElMessageBox.prompt(`${entry.patientName} 未到的原因（选填）`, "标记未到", {
        inputPlaceholder: "如：电话未接通、患者临时有事",
        inputValidator: text => (text || "").length <= 200 || "不超过 200 字",
        confirmButtonText: "标记未到",
        cancelButtonText: "取消"
      });
      await markRecheckApi(entry.id, "ABSENT", (value || "").trim());
    } else {
      await markRecheckApi(entry.id, command as "ARRIVED" | "PLANNED");
    }
    await load();
  } catch (error: any) {
    if (error === "cancel" || error === "close") return;
    ElMessage.error(error?.message || "操作失败");
  }
};

onMounted(() => {
  void load();
});
</script>

<style scoped lang="scss">
.recheck-page {
  --rk-brand: #0f766e;
  --rk-arrived: #15803d;
  --rk-absent: #b45309;
  --rk-pending: #475569;

  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
}
.rk-head {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  justify-content: space-between;
  h2 {
    margin: 0 0 6px;
    font-size: 18px;
  }
  p {
    max-width: 760px;
    margin: 0;
    font-size: 12px;
    line-height: 1.6;
    color: var(--el-text-color-secondary);
  }
}
.rk-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}
.rk-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  &.tone-brand {
    background: color-mix(in srgb, var(--rk-brand) 7%, var(--el-bg-color));
    border-color: color-mix(in srgb, var(--rk-brand) 25%, transparent);
  }
  &.tone-warn {
    background: color-mix(in srgb, var(--rk-absent) 8%, var(--el-bg-color));
    border-color: color-mix(in srgb, var(--rk-absent) 30%, transparent);
  }
}
.rk-card-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.rk-card-value {
  font-size: 22px;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-primary);
}
.rk-card-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.rk-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
.rk-table {
  overflow: hidden;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}
.rk-row {
  display: grid;
  grid-template-columns: 120px 210px 1fr;
  align-items: start;
  border-top: 1px solid var(--el-border-color-lighter);
  > div {
    padding: 10px 12px;
  }
  &.is-today {
    background: color-mix(in srgb, var(--rk-brand) 5%, transparent);
    box-shadow: inset 3px 0 0 var(--rk-brand);
  }
  &.is-past .rk-date {
    color: var(--el-text-color-secondary);
  }
}
.rk-row-head {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-top: none;
}
.rk-empty {
  padding: 32px;
  color: var(--el-text-color-secondary);
  text-align: center;
  border-top: 1px solid var(--el-border-color-lighter);
}
.rk-date {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  strong {
    font-size: 15px;
    font-variant-numeric: tabular-nums;
  }
  span {
    font-size: 12px;
  }
}
.rk-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  font-variant-numeric: tabular-nums;
  > span[title] {
    min-width: 28px;
    padding: 1px 6px;
    font-size: 13px;
    text-align: center;
    border-radius: 6px;
  }
  .n-plan {
    font-weight: 600;
    color: var(--rk-brand);
    background: color-mix(in srgb, var(--rk-brand) 10%, transparent);
  }
  .n-arrived {
    color: var(--rk-arrived);
    background: color-mix(in srgb, var(--rk-arrived) 10%, transparent);
  }
  .n-absent {
    color: var(--rk-absent);
    background: color-mix(in srgb, var(--rk-absent) 10%, transparent);
  }
  .n-pending {
    color: var(--rk-pending);
    background: color-mix(in srgb, var(--rk-pending) 10%, transparent);
  }
}
.rk-extra {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.rk-warn {
  font-size: 12px;
  color: var(--rk-absent);
}
.rk-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
.rk-chip,
.rk-add {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  height: 28px;
  padding: 0 10px;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  border-radius: 14px;
  transition:
    background-color 160ms ease,
    border-color 160ms ease;
  &:focus-visible {
    outline: 2px solid var(--rk-brand);
    outline-offset: 2px;
  }
}
.rk-chip {
  --chip: var(--rk-pending);

  color: var(--el-text-color-primary);
  background: color-mix(in srgb, var(--chip) 8%, var(--el-bg-color));
  border: 1px solid color-mix(in srgb, var(--chip) 30%, transparent);
  small {
    font-size: 11px;
    color: var(--chip);
  }
  &.s-arrived {
    --chip: var(--rk-arrived);
  }
  &.s-absent {
    --chip: var(--rk-absent);
  }
  &.s-rescheduled {
    --chip: #94a3b8;

    color: var(--el-text-color-secondary);
    text-decoration: line-through;
    cursor: default;
  }
}
.rk-dot {
  width: 7px;
  height: 7px;
  background: var(--chip);
  border-radius: 50%;
}
.rk-add {
  color: var(--rk-brand);
  background: transparent;
  border: 1px dashed color-mix(in srgb, var(--rk-brand) 45%, transparent);

  @media (hover: hover) {
    &:hover {
      background: color-mix(in srgb, var(--rk-brand) 8%, transparent);
    }
  }
}
.rk-dialog-tip {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

@media (width <= 760px) {
  .rk-row {
    grid-template-columns: 1fr;
    > div {
      padding: 6px 12px;
    }
  }
  .rk-row-head {
    display: none;
  }
}
</style>
