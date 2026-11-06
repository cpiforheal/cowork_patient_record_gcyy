<template>
  <section class="tpc" v-loading="loading">
    <header class="tpc-head">
      <div class="tpc-title">
        <span class="tpc-eyebrow">中药房 · 物理位置映射</span>
        <h1>中药房控制台</h1>
        <p>
          {{ shelves.length }} 个药架 · {{ stats.total }} 个格位 · 一药一址
          <span class="tpc-demo">演示库存</span>
        </p>
      </div>

      <div class="tpc-head-side">
        <div class="tpc-stats">
          <span>已用 <b>{{ stats.occupied }}</b></span>
          <span>空位 <b>{{ stats.empty }}</b></span>
          <span class="is-warn">低库存 <b>{{ stats.lowStock }}</b></span>
          <span class="is-danger">缺货 <b>{{ stats.outOfStock }}</b></span>
        </div>
        <div class="tpc-meta">
          <span>版本 {{ revision }} · {{ updatedAt || "尚未保存" }}<template v-if="updatedBy"> · {{ updatedBy }}</template></span>
          <el-tag v-if="dirty" size="small" type="warning" effect="light">未保存</el-tag>
        </div>
        <div class="tpc-actions">
          <el-switch v-model="editMode" :disabled="!canEdit" active-text="编辑布局" />
          <el-button :icon="Refresh" text @click="handleRefresh">刷新</el-button>
          <el-button :icon="RefreshLeft" :disabled="!dirty" @click="handleUndo">撤销</el-button>
          <el-button type="primary" :loading="saving" :disabled="!dirty" @click="handleSave">保存</el-button>
        </div>
      </div>
    </header>

    <el-alert v-if="error" type="error" show-icon :closable="false" :title="error">
      <el-button link type="primary" @click="load">重新加载</el-button>
    </el-alert>

    <div v-else-if="!shelves.length && !loading" class="tpc-empty">
      <el-empty description="还没有配置药架" />
    </div>

    <div v-else class="tpc-body">
      <section class="tpc-map-card">
        <header class="tpc-card-head">
          <div>
            <strong>药房俯瞰图</strong>
            <small>点击药架查看层格；搜索命中的药架会显示数量徽标。</small>
          </div>
          <div class="tpc-legend">
            <span><i class="dot is-ok" /> 正常</span>
            <span><i class="dot is-low" /> 低库存</span>
            <span><i class="dot is-out" /> 缺货</span>
            <span><i class="dot is-hit" /> 命中</span>
          </div>
        </header>

        <PharmacyFloorMap
          :shelves="shelves"
          :slots="slots"
          :hit-counts="shelfHitCounts"
          :selected-shelf-no="selectedShelfNo"
          @select="selectShelf"
        />
        <p class="tpc-map-hint">
          示意布局：上排 3 个横架，左右 2 个竖架，下方中间为工作台；入口在左下通道。
        </p>
      </section>

      <aside class="tpc-side">
        <ConsoleSidePanel
          v-model:searchKeyword="searchKeyword"
          :results="searchResults"
          :matched-count="searchMatchCount"
          :prescription-items="prescriptionItems"
          :selected-slot="selectedSlot"
          :position-label="positionLabel"
          :edit-mode="editMode"
          @open-prescription="prescriptionVisible = true"
          @focus="focusSlot"
          @add="addToPrescription"
          @remove="removeFromPrescription"
          @clear-prescription="clearPrescription"
          @edit="handleEditSlot"
          @add-first="handleAddFirst"
        />
      </aside>
    </div>

    <el-dialog
      v-model="shelfDialogVisible"
      :title="selectedShelf ? `${selectedShelf.name} · ${selectedShelf.layerCount} 层 × ${selectedShelf.columnCount} 格` : '药架层格'"
      width="1120px"
      style="max-width: 96vw"
      class="tpc-shelf-dialog"
    >
      <ShelfFrontGrid
        v-if="selectedShelf"
        :shelf="selectedShelf"
        :slots="shelfSlots"
        :edit-mode="editMode"
        :focus-key="selectedKey"
        :highlight-codes="highlightCodes"
        @select="handleSelectSlot"
        @edit="handleEditSlot"
        @move="handleMove"
      />
      <template #footer>
        <div class="tpc-shelf-footer">
          <span>拖拽换格后点“保存”才会写入数据库。</span>
          <div>
            <el-switch v-model="editMode" :disabled="!canEdit" active-text="编辑布局" />
            <el-button @click="shelfDialogVisible = false">关闭</el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <HerbEditDrawer
      v-model="editDrawerVisible"
      :slot="editSlot"
      :position-label="editPositionLabel"
      @save="handleSaveSlot"
      @clear="handleClearSlot"
    />

    <PrescriptionDialog v-model="prescriptionVisible" :parse="parsePrescription" @apply="handleApplyPrescription" />
  </section>
</template>

<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onMounted, ref } from "vue";
import { onBeforeRouteLeave } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { Refresh, RefreshLeft } from "@element-plus/icons-vue";
import { TcmHerbMapConflictError } from "@/api/modules/clinic/tcmPharmacyConsole";
import type { TcmHerbSlot } from "@/api/modules/clinic/tcmPharmacyConsole";
import { useUserStore } from "@/stores/modules/user";
import PharmacyFloorMap from "./components/PharmacyFloorMap.vue";
import ShelfFrontGrid from "./components/ShelfFrontGrid.vue";
import ConsoleSidePanel from "./components/ConsoleSidePanel.vue";
import HerbEditDrawer from "./components/HerbEditDrawer.vue";
import PrescriptionDialog from "./components/PrescriptionDialog.vue";
import {
  slotKeyOf,
  usePharmacyConsole,
  type HerbSearchItem,
  type HerbSlotPatch
} from "./composables/usePharmacyConsole";

defineOptions({ name: "tcmPharmacyConsole" });

const {
  shelves,
  slots,
  revision,
  updatedBy,
  updatedAt,
  loading,
  saving,
  error,
  editMode,
  searchKeyword,
  selectedShelfNo,
  selectedKey,
  dirty,
  stats,
  slotMap,
  searchResults,
  searchMatchCount,
  shelfHitCounts,
  highlightCodes,
  prescriptionItems,
  selectedSlot,
  selectedShelf,
  positionLabel,
  load,
  save,
  selectShelf,
  closeShelf,
  focusSlot,
  addToPrescription,
  removeFromPrescription,
  clearPrescription,
  setPrescription,
  parsePrescription,
  updateSlot,
  clearSlot,
  swapSlots
} = usePharmacyConsole();

const userStore = useUserStore();
const canEdit = computed(() => (userStore.userInfo.role || "") === "admin");

const loadedOnce = ref(false);
const prescriptionVisible = ref(false);
const editDrawerVisible = ref(false);
const editingKey = ref("");

const shelfDialogVisible = computed({
  get: () => Boolean(selectedShelf.value),
  set: visible => {
    if (!visible) closeShelf();
  }
});

const shelfSlots = computed(() =>
  selectedShelfNo.value == null ? [] : slots.value.filter(slot => slot.shelfNo === selectedShelfNo.value)
);

const editSlot = computed(() => slotMap.value.get(editingKey.value) || null);
const editPositionLabel = computed(() => (editSlot.value ? positionLabel(editSlot.value) : ""));

const handleAddFirst = () => {
  const first = searchResults.value[0];
  if (!first) {
    if (searchKeyword.value.trim()) ElMessage.info("没有匹配的药名");
    return;
  }
  addToPrescription(first.slot.herbCode);
  focusSlot(first.slot);
};

const handleSelectSlot = (slot: TcmHerbSlot) => {
  const key = slotKeyOf(slot);
  if (editMode.value && selectedKey.value && selectedKey.value !== key) {
    void handleMove({ from: selectedKey.value, to: key });
    return;
  }
  focusSlot(slot);
};

const handleEditSlot = (slot: TcmHerbSlot) => {
  editingKey.value = slotKeyOf(slot);
  editDrawerVisible.value = true;
};

const handleMove = async ({ from, to }: { from: string; to: string }) => {
  const fromSlot = slotMap.value.get(from);
  const toSlot = slotMap.value.get(to);
  if (!fromSlot?.herbCode || !toSlot) return;
  if (toSlot.herbCode && toSlot.herbCode !== fromSlot.herbCode) {
    try {
      await ElMessageBox.confirm(
        `目标格是「${toSlot.name}」，是否与「${fromSlot.name}」交换位置？`,
        "交换位置",
        { type: "warning", confirmButtonText: "交换", cancelButtonText: "取消" }
      );
    } catch {
      return;
    }
  }
  swapSlots(from, to);
  focusSlot(toSlot);
};

const handleSaveSlot = (patch: HerbSlotPatch) => {
  updateSlot(editingKey.value, patch);
  editDrawerVisible.value = false;
};

const handleClearSlot = () => {
  clearSlot(editingKey.value);
  editDrawerVisible.value = false;
};

const handleApplyPrescription = (matches: HerbSearchItem[]) => {
  setPrescription(matches);
  if (matches[0]) focusSlot(matches[0].slot);
};

const handleSave = async () => {
  if (!dirty.value) {
    ElMessage.info("没有需要保存的修改");
    return;
  }
  try {
    await save();
    ElMessage.success("药架位置已保存");
  } catch (saveError: any) {
    if (saveError instanceof TcmHerbMapConflictError) {
      try {
        await ElMessageBox.confirm("地图已被其他管理员修改，是否重新加载最新数据？本地未保存修改会丢失。", "保存冲突", {
          type: "warning",
          confirmButtonText: "重新加载",
          cancelButtonText: "取消"
        });
        await load();
      } catch {
        // 用户取消
      }
      return;
    }
    ElMessage.error(saveError?.message || "保存失败");
  }
};

const handleUndo = async () => {
  if (!dirty.value) return;
  try {
    await ElMessageBox.confirm("撤销后，本次未保存的调整会全部丢失。", "撤销修改？", {
      type: "warning",
      confirmButtonText: "撤销",
      cancelButtonText: "取消"
    });
  } catch {
    return;
  }
  await load();
};

const handleRefresh = async () => {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm("刷新会丢弃未保存的调整，是否继续？", "刷新地图？", {
        type: "warning",
        confirmButtonText: "刷新",
        cancelButtonText: "取消"
      });
    } catch {
      return;
    }
  }
  await load();
};

const beforeUnload = (event: BeforeUnloadEvent) => {
  if (!dirty.value) return;
  event.preventDefault();
  event.returnValue = "";
};

onMounted(async () => {
  await load();
  loadedOnce.value = true;
  window.addEventListener("beforeunload", beforeUnload);
});

onActivated(() => {
  if (loadedOnce.value && !dirty.value && !loading.value) void load();
});

onBeforeUnmount(() => {
  window.removeEventListener("beforeunload", beforeUnload);
});

onBeforeRouteLeave(async () => {
  if (!dirty.value) return true;
  try {
    await ElMessageBox.confirm("有未保存的药架调整，离开后这些修改会丢失。", "离开页面？", {
      type: "warning",
      confirmButtonText: "离开",
      cancelButtonText: "留下"
    });
    return true;
  } catch {
    return false;
  }
});
</script>

<style scoped lang="scss">
.tpc {
  --tpc-bg: #faf7f1;
  --tpc-surface: #fffdf9;
  --tpc-surface-2: #fbf6ee;
  --tpc-ink: #2f2a24;
  --tpc-muted: #7b7064;
  --tpc-line: #e8dfd2;
  --tpc-accent: #2f6b58;
  --tpc-accent-soft: #e9f1ed;
  --tpc-warm: #b7793f;
  --tpc-warm-soft: #f7ecdd;
  --tpc-danger: #b04a4a;

  box-sizing: border-box;
  width: 100%;
  max-width: 1440px;
  min-height: 100%;
  margin: 0 auto;
  padding: 18px 24px 28px;
  color: var(--tpc-ink);
  background: var(--tpc-bg);
}

.tpc-head {
  display: flex;
  gap: 18px;
  align-items: flex-end;
  justify-content: space-between;
  padding: 0 2px 14px;
}

.tpc-eyebrow {
  color: var(--tpc-muted);
  font-size: 12px;
  letter-spacing: 0.04em;
}

.tpc-title {
  h1 {
    margin: 2px 0 4px;
    font-size: 28px;
    line-height: 1.2;
  }

  p {
    margin: 0;
    color: var(--tpc-muted);
    font-size: 12px;
  }
}

.tpc-demo {
  display: inline-block;
  margin-left: 8px;
  padding: 1px 6px;
  color: #8a5a13;
  font-size: 10px;
  font-weight: 600;
  background: var(--tpc-warm-soft);
  border: 1px solid #ebd8bd;
  border-radius: 999px;
}

.tpc-head-side {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-end;
}

.tpc-stats {
  display: flex;
  gap: 12px;
  color: var(--tpc-muted);
  font-size: 12px;

  b {
    color: var(--tpc-ink);
    font-size: 15px;
  }

  .is-warn b {
    color: #a2671f;
  }

  .is-danger b {
    color: var(--tpc-danger);
  }
}

.tpc-meta {
  display: flex;
  gap: 8px;
  align-items: center;
  color: var(--tpc-muted);
  font-size: 11px;
}

.tpc-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.tpc-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: 16px;
  align-items: start;
}

.tpc-map-card,
.tpc-side {
  padding: 14px 16px;
  background: var(--tpc-surface);
  border: 1px solid var(--tpc-line);
  border-radius: 14px;
}

.tpc-side {
  position: sticky;
  top: 12px;
}

.tpc-card-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 10px;

  strong {
    display: block;
    margin-bottom: 2px;
    font-size: 14px;
  }

  small {
    color: var(--tpc-muted);
    font-size: 11px;
  }
}

.tpc-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--tpc-muted);
  font-size: 10px;

  span {
    display: inline-flex;
    gap: 4px;
    align-items: center;
    white-space: nowrap;
  }
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;

  &.is-ok {
    background: #3f7f62;
  }

  &.is-low {
    background: #d9a86c;
  }

  &.is-out {
    background: #cf7a7a;
  }

  &.is-hit {
    background: var(--tpc-warm);
  }
}

.tpc-map-hint {
  margin: 8px 0 0;
  color: var(--tpc-muted);
  font-size: 11px;
}

.tpc-empty {
  padding: 40px 0;
  background: var(--tpc-surface);
  border: 1px solid var(--tpc-line);
  border-radius: 14px;
}

.tpc-shelf-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;

  > span {
    color: var(--tpc-muted);
    font-size: 11px;
  }

  > div {
    display: flex;
    gap: 10px;
    align-items: center;
  }
}

@media (max-width: 1180px) {
  .tpc {
    padding-bottom: 96px;
  }

  .tpc-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .tpc-head-side {
    align-items: flex-start;
  }

  .tpc-body {
    grid-template-columns: 1fr;
  }

  .tpc-side {
    position: static;
  }
}
</style>
