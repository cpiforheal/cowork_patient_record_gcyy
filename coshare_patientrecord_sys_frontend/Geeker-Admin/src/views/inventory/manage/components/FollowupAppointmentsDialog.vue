<template>
  <el-dialog v-model="visible" title="复诊预约" width="760px" append-to-body>
    <div class="followup-shell" v-loading="loading">
      <div class="followup-head">
        <div>
          <span class="eyebrow">复查预约 · {{ date }}</span>
          <p>按上午/下午/晚上分组；已匹配患者可直接确认到院。</p>
        </div>
        <div class="followup-actions">
          <el-checkbox
            :model-value="allMatchedSelected"
            :indeterminate="selectedIds.length > 0 && !allMatchedSelected"
            :disabled="!matchedItems.length"
            @update:model-value="toggleAll"
          >
            全选已匹配
          </el-checkbox>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          <el-button type="primary" plain @click="emit('batch')">套餐批处理</el-button>
        </div>
      </div>
      <div v-for="slot in slots" :key="slot.slot || 'NONE'" class="slot-group">
        <header class="slot-head">
          <strong>{{ slot.label }}</strong>
          <span>{{ slot.items.length }} 人</span>
        </header>
        <div
          v-for="item in slot.items"
          :key="item.recheckId"
          class="appointment-row"
          :class="{ 'is-pending': !item.matched, 'is-selected': selectedIds.includes(item.recheckId) }"
          @click="toggleItem(item)"
        >
          <el-checkbox
            :model-value="selectedIds.includes(item.recheckId)"
            :disabled="!item.matched"
            :aria-label="`选择 ${item.patientName}`"
            @click.stop
            @update:model-value="value => setSelected(item, Boolean(value))"
          />
          <span class="appointment-main">
            <strong>{{ item.patientName }}</strong>
            <small>{{ item.phone || "无电话" }} · {{ item.note || "复查" }}</small>
          </span>
          <span class="appointment-side">
            <el-tag v-if="!item.matched" type="warning" size="small" effect="plain">待确认患者</el-tag>
            <el-tag v-else-if="item.matchType === 'NAME_PHONE'" type="info" size="small" effect="plain">姓名电话匹配</el-tag>
            <el-tag v-else type="success" size="small" effect="plain">已关联</el-tag>
            <el-tag :type="item.status === 'ARRIVED' ? 'success' : 'info'" size="small" effect="plain">
              {{ statusLabel(item.status) }}
            </el-tag>
          </span>
          <span class="appointment-go">{{ item.matched ? "勾选后批量确认" : "请搜索确认" }}</span>
        </div>
      </div>
      <el-empty v-if="!slots.length && !loading" :image-size="70" description="今天没有复查预约" />
    </div>
    <template #footer>
      <span class="selected-count">已选 {{ selectedIds.length }} 人</span>
      <el-button :disabled="!selectedIds.length || arriving" @click="selectedIds = []">清空选择</el-button>
      <el-button type="primary" :disabled="!selectedIds.length" :loading="arriving" @click="arriveSelected">
        确认已到并加入 {{ selectedIds.length || "" }} 位
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import {
  arriveInventoryFollowupAppointmentApi,
  getInventoryFollowupAppointmentsApi,
  type InventoryFollowupAppointment,
  type InventoryFollowupSlot
} from "@/api/modules/inventory";

const props = defineProps<{
  modelValue: boolean;
  departmentKey: string;
  date: string;
}>();
const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  "batch-arrived": [count: number];
  batch: [];
}>();

const visible = computed({
  get: () => props.modelValue,
  set: value => emit("update:modelValue", value)
});
const slots = ref<InventoryFollowupSlot[]>([]);
const loading = ref(false);
const arriving = ref(false);
const selectedIds = ref<string[]>([]);

const allItems = computed(() => slots.value.flatMap(slot => slot.items));
const matchedItems = computed(() => allItems.value.filter(item => item.matched));
const allMatchedSelected = computed(
  () => matchedItems.value.length > 0 && matchedItems.value.every(item => selectedIds.value.includes(item.recheckId))
);

const load = async () => {
  if (!props.departmentKey) return;
  loading.value = true;
  try {
    const result = await getInventoryFollowupAppointmentsApi({
      departmentKey: props.departmentKey,
      date: props.date
    });
    slots.value = result.data?.slots ?? [];
    selectedIds.value = [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取复诊预约失败");
  } finally {
    loading.value = false;
  }
};

const setSelected = (item: InventoryFollowupAppointment, checked: boolean) => {
  if (!item.matched) return;
  if (checked) {
    if (!selectedIds.value.includes(item.recheckId)) selectedIds.value.push(item.recheckId);
  } else {
    selectedIds.value = selectedIds.value.filter(id => id !== item.recheckId);
  }
};

const toggleItem = (item: InventoryFollowupAppointment) => {
  if (!item.matched) return;
  setSelected(item, !selectedIds.value.includes(item.recheckId));
};

const toggleAll = (checked: boolean | string | number) => {
  selectedIds.value = Boolean(checked) ? matchedItems.value.map(item => item.recheckId) : [];
};

const arriveSelected = async () => {
  const targets = allItems.value.filter(item => item.matched && selectedIds.value.includes(item.recheckId));
  if (!targets.length) return;
  try {
    await ElMessageBox.confirm(
      `确认 ${targets.length} 位复诊患者已到院，并加入今天的工作台？`,
      "批量确认到院",
      { confirmButtonText: "确认到院", cancelButtonText: "取消", type: "warning" }
    );
  } catch {
    return;
  }
  arriving.value = true;
  const succeeded = new Set<string>();
  const errors: string[] = [];
  try {
    for (const item of targets) {
      try {
        await arriveInventoryFollowupAppointmentApi(item.recheckId, {
          departmentKey: props.departmentKey,
          businessDate: props.date
        });
        succeeded.add(item.recheckId);
      } catch (error) {
        errors.push(`${item.patientName}：${error instanceof Error ? error.message : "确认失败"}`);
      }
    }
    if (succeeded.size) {
      slots.value = slots.value
        .map(slot => ({ ...slot, items: slot.items.filter(item => !succeeded.has(item.recheckId)) }))
        .filter(slot => slot.items.length > 0);
      selectedIds.value = selectedIds.value.filter(id => !succeeded.has(id));
      emit("batch-arrived", succeeded.size);
      ElMessage.success(`已确认 ${succeeded.size} 位患者到院`);
    }
    if (errors.length) ElMessage.warning(`成功 ${succeeded.size} 位，${errors.length} 位失败`);
  } finally {
    arriving.value = false;
  }
};

const statusLabel = (status: string) =>
  ({ PLANNED: "待复查", ARRIVED: "已到", ABSENT: "未到" })[status] || status;

watch(
  () => [props.modelValue, props.departmentKey, props.date],
  () => {
    if (props.modelValue && props.departmentKey) void load();
  },
  { immediate: true }
);
</script>

<style scoped lang="scss">
.followup-shell {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.followup-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;

  p {
    margin: 4px 0 0;
    color: #9a9285;
    font-size: 11px;
  }
}

.eyebrow {
  color: #347d68;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.1em;
}

.followup-actions {
  display: flex;
  gap: 6px;
}

.slot-group {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.slot-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
  padding: 2px 2px 0;
  color: #2f2d27;
  font-size: 13px;

  span {
    color: #9a9285;
    font-size: 10px;
  }
}

.appointment-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto 90px;
  gap: 10px;
  align-items: center;
  padding: 9px 11px;
  color: #2f2d27;
  text-align: left;
  cursor: pointer;
  background: #fffdf9;
  border: 1px solid #efe8dd;
  border-radius: 10px;

  &:hover:not(:disabled) {
    border-color: #d9cfc0;
  }

  &:disabled {
    cursor: default;
  }

  &.is-pending {
    opacity: 0.65;
    background: #f8f5ef;
  }

  &.is-selected {
    background: #edf7f1;
    border-color: #347d68;
  }
}

.selected-count {
  margin-right: auto;
  color: #6d675c;
  font-size: 12px;
}

.appointment-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;

  strong {
    font-size: 13px;
  }

  small {
    color: #9a9285;
    font-size: 10px;
  }
}

.appointment-side {
  display: flex;
  gap: 4px;
  align-items: center;
}

.appointment-go {
  color: #347d68;
  font-size: 10px;
  text-align: right;
}
</style>
