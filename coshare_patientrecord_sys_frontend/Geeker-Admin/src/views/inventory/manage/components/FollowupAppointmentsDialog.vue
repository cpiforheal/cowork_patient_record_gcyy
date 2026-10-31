<template>
  <el-dialog v-model="visible" title="复诊预约" width="760px" append-to-body>
    <div class="followup-shell" v-loading="loading">
      <div class="followup-head">
        <div>
          <span class="eyebrow">复查预约 · {{ date }}</span>
          <p>按上午/下午/晚上分组；已匹配患者可直接确认到院。</p>
        </div>
        <div class="followup-actions">
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          <el-button type="primary" plain @click="emit('batch')">套餐批处理</el-button>
        </div>
      </div>
      <div v-for="slot in slots" :key="slot.slot || 'NONE'" class="slot-group">
        <header class="slot-head">
          <strong>{{ slot.label }}</strong>
          <span>{{ slot.items.length }} 人</span>
        </header>
        <button
          v-for="item in slot.items"
          :key="item.recheckId"
          type="button"
          class="appointment-row"
          :class="{ 'is-pending': !item.matched }"
          :disabled="!item.matched || arrivingId === item.recheckId"
          @click="arrive(item)"
        >
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
          <span class="appointment-go">{{ item.matched ? "确认已到 →" : "请搜索确认" }}</span>
        </button>
      </div>
      <el-empty v-if="!slots.length && !loading" :image-size="70" description="今天没有复查预约" />
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import {
  arriveInventoryFollowupAppointmentApi,
  getInventoryFollowupAppointmentsApi,
  type InventoryFollowupAppointment,
  type InventoryFollowupSlot,
  type InventoryPatientDetail
} from "@/api/modules/inventory";

const props = defineProps<{
  modelValue: boolean;
  departmentKey: string;
  date: string;
}>();
const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  arrived: [detail: InventoryPatientDetail | undefined];
  batch: [];
}>();

const visible = computed({
  get: () => props.modelValue,
  set: value => emit("update:modelValue", value)
});
const slots = ref<InventoryFollowupSlot[]>([]);
const loading = ref(false);
const arrivingId = ref("");

const load = async () => {
  if (!props.departmentKey) return;
  loading.value = true;
  try {
    const result = await getInventoryFollowupAppointmentsApi({
      departmentKey: props.departmentKey,
      date: props.date
    });
    slots.value = result.data?.slots ?? [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取复诊预约失败");
  } finally {
    loading.value = false;
  }
};

const arrive = async (item: InventoryFollowupAppointment) => {
  if (!item.matched) return;
  arrivingId.value = item.recheckId;
  try {
    const result = await arriveInventoryFollowupAppointmentApi(item.recheckId, {
      departmentKey: props.departmentKey,
      businessDate: props.date
    });
    ElMessage.success(`${item.patientName} 已到院并加入工作台`);
    visible.value = false;
    emit("arrived", result.data);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "确认到院失败");
  } finally {
    arrivingId.value = "";
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
  grid-template-columns: minmax(0, 1fr) auto 90px;
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
