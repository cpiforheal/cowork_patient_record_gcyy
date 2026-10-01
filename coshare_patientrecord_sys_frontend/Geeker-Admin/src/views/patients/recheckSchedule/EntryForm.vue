<template>
  <form class="ef" aria-label="登记复查" @submit.prevent="submit">
    <div class="ef-head">
      <strong>登记复查</strong>
      <span class="ef-quick-label">设为当前行日期：</span>
      <button
        v-for="quick in QUICK_OFFSETS"
        :key="quick.days"
        type="button"
        class="ef-quick"
        :class="{ on: activeRow && activeRow.planDate === offsetDate(quick.days) }"
        @click="setActiveDate(offsetDate(quick.days))"
      >
        {{ quick.label }}
      </button>
    </div>

    <div class="ef-grid" role="table" aria-label="待登记患者">
      <div class="ef-row ef-row-head" role="row">
        <span role="columnheader">#</span>
        <span role="columnheader">复查日期</span>
        <span role="columnheader">姓名</span>
        <span role="columnheader">电话（选填）</span>
        <span role="columnheader">复查内容 / 备注（选填）</span>
        <span role="columnheader" class="sr-only">操作</span>
      </div>
      <div
        v-for="(row, index) in rows"
        :key="row.key"
        class="ef-row"
        :class="{ active: row.key === activeKey, failed: row.error }"
        role="row"
        @focusin="activeKey = row.key"
      >
        <span class="ef-idx" role="cell">{{ index + 1 }}</span>
        <span role="cell"><PlanDatePicker v-model="row.planDate" :today="today" :counts="counts" width="128px" /></span>
        <span role="cell">
          <el-input
            :ref="el => setNameRef(row.key, el)"
            v-model="row.patientName"
            maxlength="50"
            placeholder="姓名，可粘贴多个"
            :aria-label="`第 ${index + 1} 行姓名`"
            @paste="(e: ClipboardEvent) => onPaste(e, row)"
            @keydown.enter.prevent="(e: KeyboardEvent) => onEnter(e, index)"
          />
        </span>
        <span role="cell">
          <el-input
            v-model="row.phone"
            maxlength="30"
            :aria-label="`第 ${index + 1} 行电话`"
            @keydown.enter.prevent="(e: KeyboardEvent) => onEnter(e, index)"
          />
        </span>
        <span role="cell">
          <el-input
            v-model="row.note"
            maxlength="200"
            :aria-label="`第 ${index + 1} 行备注`"
            @keydown.enter.prevent="(e: KeyboardEvent) => onEnter(e, index)"
          />
        </span>
        <span role="cell" class="ef-ops">
          <el-button link :disabled="rows.length === 1" :aria-label="`删除第 ${index + 1} 行`" @click="removeRow(index)">
            删除
          </el-button>
        </span>
        <p v-if="row.error" class="ef-err" role="alert">{{ row.error }}</p>
      </div>
    </div>

    <div class="ef-foot">
      <el-button @click="addRow()">+ 加一行</el-button>
      <span class="ef-summary">{{ summary }}</span>
      <span class="ef-tip">回车换下一行，Ctrl+回车 提交</span>
      <el-button @click="reset">清空</el-button>
      <el-button type="primary" native-type="submit" :loading="saving" :disabled="!readyRows.length">
        登记 {{ readyRows.length || "" }} 人
      </el-button>
    </div>
  </form>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from "vue";
import dayjs from "dayjs";
import { ElMessage } from "element-plus";
import { createRecheckApi } from "@/api/modules/clinic/recheckSchedule";
import PlanDatePicker from "./PlanDatePicker.vue";
import { NAME_SEPARATORS, QUICK_OFFSETS, dateTitle, fmt } from "./dates";

const props = defineProps<{ today: string; counts: Record<string, number> }>();
const emit = defineEmits<{ saved: [] }>();

interface Row {
  key: number;
  planDate: string;
  patientName: string;
  phone: string;
  note: string;
  error: string;
}

let seq = 0;
const offsetDate = (days: number) => fmt(dayjs(props.today).add(days, "day"));
const newRow = (planDate: string, patientName = ""): Row => ({
  key: ++seq,
  planDate,
  patientName,
  phone: "",
  note: "",
  error: ""
});

const rows = ref<Row[]>([newRow(offsetDate(1))]);
const activeKey = ref(rows.value[0].key);
const saving = ref(false);

const activeRow = computed(() => rows.value.find(r => r.key === activeKey.value) ?? rows.value[rows.value.length - 1]);
const readyRows = computed(() => rows.value.filter(r => r.patientName.trim()));

/** 例："10/02（明天）2 人 · 10/08 1 人" */
const summary = computed(() => {
  const byDate = new Map<string, number>();
  for (const row of readyRows.value) byDate.set(row.planDate, (byDate.get(row.planDate) ?? 0) + 1);
  return [...byDate.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, n]) => `${dateTitle(date, props.today)} ${n} 人`)
    .join(" · ");
});

const nameRefs = new Map<number, { focus?: () => void }>();
const setNameRef = (key: number, el: unknown) => {
  if (el) nameRefs.set(key, el as { focus?: () => void });
  else nameRefs.delete(key);
};
const focusRow = async (key: number) => {
  activeKey.value = key;
  await nextTick();
  nameRefs.get(key)?.focus?.();
};

/** 新行默认沿用上一行日期，连续登记同一天时不用重选 */
const addRow = (planDate?: string, patientName = "") => {
  const row = newRow(planDate ?? rows.value[rows.value.length - 1]?.planDate ?? offsetDate(1), patientName);
  rows.value.push(row);
  void focusRow(row.key);
  return row;
};

const removeRow = (index: number) => {
  rows.value.splice(index, 1);
  if (!rows.value.some(r => r.key === activeKey.value)) activeKey.value = rows.value[rows.value.length - 1].key;
};

const setActiveDate = (date: string) => {
  if (activeRow.value) activeRow.value.planDate = date;
};

/** 粘贴多个姓名（顿号/逗号/空格/换行分隔）时自动拆成多行，日期沿用当前行 */
const onPaste = (event: ClipboardEvent, row: Row) => {
  const names = (event.clipboardData?.getData("text") ?? "")
    .split(NAME_SEPARATORS)
    .map(s => s.trim())
    .filter(Boolean);
  if (names.length < 2) return;
  event.preventDefault();
  // 当前行为空则填第一个名字，其余各成一行
  const queue = row.patientName.trim() ? names : names.slice(1);
  if (!row.patientName.trim()) row.patientName = names[0];
  const index = rows.value.indexOf(row);
  rows.value.splice(index + 1, 0, ...queue.map(name => newRow(row.planDate, name)));
  ElMessage.success(`已拆成 ${names.length} 行，可逐行补电话`);
};

const onEnter = (event: KeyboardEvent, index: number) => {
  if (event.ctrlKey || event.metaKey) return void submit();
  const next = rows.value[index + 1];
  if (next) void focusRow(next.key);
  else addRow();
};

const reset = () => {
  rows.value = [newRow(offsetDate(1))];
  void focusRow(rows.value[0].key);
};

/** 逐行提交：成功的行移除，失败的行留下并标出原因，便于修正后重试 */
const submit = async () => {
  if (saving.value) return;
  const pending = rows.value.filter(r => r.patientName.trim() || r.phone.trim() || r.note.trim());
  if (!pending.length) return ElMessage.warning("请至少填写一位患者姓名");
  saving.value = true;
  const done = new Set<number>();
  try {
    for (const row of pending) {
      row.error = "";
      if (!row.patientName.trim()) {
        row.error = "请填写姓名";
        continue;
      }
      try {
        await createRecheckApi({
          planDate: row.planDate,
          patientName: row.patientName.trim(),
          phone: row.phone.trim(),
          note: row.note.trim()
        });
        done.add(row.key);
      } catch (error: any) {
        row.error = error?.message || "登记失败";
      }
    }
  } finally {
    saving.value = false;
  }

  const failed = pending.length - done.size;
  if (done.size) {
    const keepDate = rows.value[rows.value.length - 1].planDate;
    rows.value = rows.value.filter(r => !done.has(r.key) && (r.patientName.trim() || r.error));
    if (!rows.value.length) rows.value = [newRow(keepDate)];
    void focusRow(rows.value[0].key);
    emit("saved");
  }
  if (failed) ElMessage.warning(`已登记 ${done.size} 人，${failed} 行未成功，原因见行内提示`);
  else ElMessage.success(`已登记 ${done.size} 人`);
};

/** 供日历点击某天"登记到这天"：复用末尾空行，否则新增一行 */
const addForDate = (date: string) => {
  const last = rows.value[rows.value.length - 1];
  if (last && !last.patientName.trim()) {
    last.planDate = date;
    void focusRow(last.key);
  } else addRow(date);
};

defineExpose({ addForDate });
</script>

<style scoped lang="scss">
.ef {
  --brand: #0f766e;

  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px;
  background: color-mix(in srgb, var(--brand) 5%, var(--el-bg-color));
  border: 1px solid color-mix(in srgb, var(--brand) 22%, transparent);
  border-radius: 10px;
}
.ef-head {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  strong {
    margin-right: 10px;
    font-size: 15px;
    color: var(--brand);
  }
}
.ef-quick-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ef-quick {
  height: 26px;
  padding: 0 11px;
  font: inherit;
  font-size: 12px;
  color: var(--el-text-color-regular);
  cursor: pointer;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 13px;
  transition:
    background-color 150ms ease,
    border-color 150ms ease,
    color 150ms ease;
  &.on {
    color: #ffffff;
    background: var(--brand);
    border-color: var(--brand);
  }
  &:focus-visible {
    outline: 2px solid var(--brand);
    outline-offset: 2px;
  }
}
.ef-grid {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ef-row {
  display: grid;
  grid-template-columns: 22px 210px 150px 140px minmax(0, 1fr) 40px;
  gap: 8px;
  align-items: center;
  padding: 4px 6px;
  border-radius: 8px;
  transition: background-color 150ms ease;
  &.active {
    background: color-mix(in srgb, var(--brand) 8%, transparent);
  }
  &.failed {
    background: color-mix(in srgb, #b45309 8%, transparent);
  }
}
.ef-row-head {
  padding-block: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ef-idx {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.ef-ops {
  justify-self: end;
}
.ef-err {
  grid-column: 3 / -1;
  margin: 0;
  font-size: 12px;
  color: #b45309;
}
.ef-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
.ef-summary {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--brand);
}
.ef-tip {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}

@media (width <= 900px) {
  .ef-row {
    grid-template-columns: 22px 1fr 40px;
  }
  .ef-row > span:nth-child(n + 3):not(.ef-ops) {
    grid-column: 2 / 3;
  }
  .ef-row-head {
    display: none;
  }
}
</style>
