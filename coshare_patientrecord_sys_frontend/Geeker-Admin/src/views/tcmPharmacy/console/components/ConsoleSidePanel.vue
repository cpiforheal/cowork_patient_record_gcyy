<template>
  <section class="csp">
    <div class="csp-search">
      <el-input
        :model-value="searchKeyword"
        placeholder="搜索药名 / 别名 / 拼音首字母"
        clearable
        :prefix-icon="Search"
        aria-label="搜索中药"
        @update:model-value="emit('update:searchKeyword', $event)"
        @keyup.enter="emit('add-first')"
      />
      <div class="csp-actions">
        <el-button plain type="primary" :icon="DocumentAdd" @click="emit('open-prescription')">
          整方录入
        </el-button>
        <el-button v-if="prescriptionItems.length" text @click="emit('clear-prescription')">
          清空清单
        </el-button>
      </div>
    </div>

    <section v-if="searchKeyword.trim()" class="csp-card">
      <header class="csp-card-head">
        <strong>查询结果</strong>
        <span v-if="matchedCount">共 {{ matchedCount }} 味<template v-if="matchedCount > results.length"> · 显示前 {{ results.length }}</template></span>
        <span v-else>0 味</span>
      </header>
      <div v-if="results.length" class="csp-list">
        <div
          v-for="item in results"
          :key="item.slot.herbCode || `${item.slot.shelfNo}-${item.slot.layerNo}-${item.slot.columnNo}`"
          class="csp-result"
          role="button"
          tabindex="0"
          @click="emit('focus', item.slot)"
          @keydown.enter.prevent="emit('focus', item.slot)"
          @keydown.space.prevent="emit('focus', item.slot)"
        >
          <span class="csp-result-main">
            <span class="csp-name">{{ item.name }}</span>
            <span class="csp-meta">{{ positionLabel(item.slot) }}</span>
          </span>
          <span class="csp-result-side">
            <el-tag size="small" :type="stockTagType(item.slot)" effect="light">
              {{ stockLabel(item.slot) }}
            </el-tag>
            <el-button
              link
              type="primary"
              :icon="Plus"
              aria-label="加入取药清单"
              @click.stop="emit('add', item.slot.herbCode)"
            />
          </span>
        </div>
      </div>
      <p v-else class="csp-empty">没有匹配的药名，试试全名、别名或拼音首字母。</p>
    </section>

    <section v-if="prescriptionItems.length" class="csp-card">
      <header class="csp-card-head">
        <strong>取药清单</strong>
        <span>{{ prescriptionItems.length }} 味 · 按架位排序</span>
      </header>
      <div class="csp-list">
        <div
          v-for="(item, index) in prescriptionItems"
          :key="item.herbCode || `${item.shelfNo}-${item.layerNo}-${item.columnNo}`"
          class="csp-result"
          role="button"
          tabindex="0"
          @click="emit('focus', item)"
          @keydown.enter.prevent="emit('focus', item)"
          @keydown.space.prevent="emit('focus', item)"
        >
          <span class="csp-order">{{ index + 1 }}</span>
          <span class="csp-result-main">
            <span class="csp-name">{{ item.name }}</span>
            <span class="csp-meta">{{ positionLabel(item) }}</span>
          </span>
          <span class="csp-result-side">
            <el-tag size="small" :type="stockTagType(item)" effect="light">{{ stockLabel(item) }}</el-tag>
            <el-button
              link
              type="danger"
              :icon="Delete"
              aria-label="移出清单"
              @click.stop="emit('remove', item.herbCode || '')"
            />
          </span>
        </div>
      </div>
    </section>

    <section v-if="selectedSlot" class="csp-card">
      <header class="csp-card-head">
        <strong>{{ selectedSlot.herbCode ? selectedSlot.name : "空格位" }}</strong>
        <el-button v-if="editMode" link type="primary" :icon="EditPen" @click="emit('edit', selectedSlot)">
          {{ selectedSlot.herbCode ? "编辑" : "放入药材" }}
        </el-button>
      </header>
      <p class="csp-position">{{ positionLabel(selectedSlot) }}</p>
      <dl v-if="selectedSlot.herbCode" class="csp-detail">
        <div><dt>别名</dt><dd>{{ selectedSlot.alias || "—" }}</dd></div>
        <div><dt>拼音</dt><dd>{{ selectedSlot.pinyin || "按药名自动生成" }}</dd></div>
        <div><dt>库存</dt><dd>{{ formatNumber(selectedSlot.stock) }}<span v-if="selectedSlot.dataSource === 'DEMO_SEED'">（演示）</span></dd></div>
        <div><dt>预警阈值</dt><dd>{{ formatNumber(selectedSlot.warnThreshold) }}</dd></div>
        <div><dt>数据来源</dt><dd>{{ dataSourceLabel(selectedSlot.dataSource) }}</dd></div>
        <div><dt>位置编码</dt><dd>{{ selectedSlot.shelfNo }}-{{ selectedSlot.layerNo }}-{{ selectedSlot.columnNo }}</dd></div>
      </dl>
      <p v-else class="csp-empty">这个格位还没有药材；开启编辑后可放入。</p>
    </section>

    <p v-if="!searchKeyword.trim() && !prescriptionItems.length && !selectedSlot" class="csp-hint">
      输入药名或拼音首字母，或整方录入；地图会自动高亮所在药架。点击药架可查看 5 层 × 12 格。
    </p>
  </section>
</template>

<script setup lang="ts">
import { Delete, DocumentAdd, EditPen, Plus, Search } from "@element-plus/icons-vue";
import type { HerbSearchItem } from "../composables/usePharmacyConsole";
import type { TcmHerbSlot } from "@/api/modules/clinic/tcmPharmacyConsole";

defineProps<{
  searchKeyword: string;
  results: HerbSearchItem[];
  matchedCount: number;
  prescriptionItems: TcmHerbSlot[];
  selectedSlot: TcmHerbSlot | null;
  positionLabel: (slot: TcmHerbSlot) => string;
  editMode: boolean;
}>();

const emit = defineEmits<{
  (event: "update:searchKeyword", value: string): void;
  (event: "open-prescription"): void;
  (event: "focus", slot: TcmHerbSlot): void;
  (event: "add", code: string | null): void;
  (event: "remove", code: string): void;
  (event: "clear-prescription"): void;
  (event: "edit", slot: TcmHerbSlot): void;
  (event: "add-first"): void;
}>();

const formatNumber = (value?: number | null) => {
  const number = Number(value || 0);
  return Number.isInteger(number) ? String(number) : number.toFixed(1);
};

const stockLabel = (slot: TcmHerbSlot) => {
  if (!slot.herbCode) return "空";
  if (Number(slot.stock || 0) <= 0) return "缺货";
  if (Number(slot.stock || 0) <= Number(slot.warnThreshold || 0)) return `偏低 ${formatNumber(slot.stock)}`;
  return `存 ${formatNumber(slot.stock)}`;
};

const stockTagType = (slot: TcmHerbSlot) => {
  if (Number(slot.stock || 0) <= 0) return "danger";
  if (Number(slot.stock || 0) <= Number(slot.warnThreshold || 0)) return "warning";
  return "success";
};

const dataSourceLabel = (source: string) => {
  if (source === "DEMO_SEED") return "演示数据";
  if (source === "EXCEL") return "Excel 导入";
  if (source === "MANUAL") return "手工维护";
  return source || "—";
};
</script>

<style scoped lang="scss">
.csp {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.csp-search {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.csp-actions {
  display: flex;
  gap: 6px;
  align-items: center;
  justify-content: space-between;
}

.csp-card {
  padding: 12px 14px;
  background: #fffdf9;
  border: 1px solid #e8dfd2;
  border-radius: 12px;
}

.csp-card-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 8px;

  strong {
    color: #2f2a24;
    font-size: 13px;
  }

  span {
    color: #8a7a67;
    font-size: 11px;
  }
}

.csp-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 264px;
  overflow: auto;
}

.csp-result {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 7px 6px;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 8px;

  &:hover {
    background: #fbf6ee;
  }

  &:focus-visible {
    outline: 2px solid #2f6b58;
  }
}

.csp-order {
  flex-shrink: 0;
  width: 18px;
  color: #b7793f;
  font-size: 11px;
  font-weight: 700;
  text-align: center;
}

.csp-result-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.csp-name {
  overflow: hidden;
  color: #2f2a24;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.csp-meta {
  overflow: hidden;
  color: #8a7a67;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.csp-result-side {
  display: flex;
  flex-shrink: 0;
  gap: 2px;
  align-items: center;
}

.csp-empty,
.csp-hint {
  margin: 6px 0;
  color: #8a7a67;
  font-size: 12px;
  line-height: 1.6;
}

.csp-hint {
  padding: 14px;
  background: #fbf6ee;
  border-radius: 12px;
}

.csp-position {
  margin: 0 0 8px;
  color: #2f6b58;
  font-size: 12px;
  font-weight: 600;
}

.csp-detail {
  display: grid;
  gap: 6px;
  margin: 0;

  div {
    display: flex;
    gap: 10px;
    justify-content: space-between;
    font-size: 12px;
  }

  dt {
    flex-shrink: 0;
    color: #8a7a67;
  }

  dd {
    margin: 0;
    color: #2f2a24;
    text-align: right;
    word-break: break-all;
  }
}
</style>
