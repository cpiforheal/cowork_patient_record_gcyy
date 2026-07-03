<template>
  <div class="executive-panel">
    <section class="executive-filter-bar" aria-label="领导驾驶舱筛选">
      <div class="filter-pills">
        <button class="active" type="button">全部</button>
        <button type="button" @click="$emit('goTab', 'stock')">库存预警</button>
        <button type="button" @click="$emit('goTab', 'requests')">科室申领</button>
        <button type="button" @click="$emit('goTab', 'trace')">出入库</button>
        <button type="button" @click="$emit('goTab', 'requests')">待签字</button>
      </div>
      <div class="filter-fields">
        <span>统计范围</span>
        <strong>今日 00:00 至今</strong>
        <strong>{{ canViewAllDepartments ? "全院科室" : currentDepartment || "本科室" }}</strong>
        <strong>低库存优先</strong>
      </div>
    </section>

    <section class="executive-summary-grid">
      <article class="executive-summary-card signal" :class="executiveSignal.level">
        <div>
          <h2>今日红绿灯</h2>
          <p>基于库存下限、申领时效、签字和风险综合判断</p>
        </div>
        <div class="signal-body">
          <span class="signal-dot"></span>
          <div>
            <strong>{{ executiveSignal.title.replace("有", "") }}</strong>
            <small>{{ executiveSignal.desc }}</small>
          </div>
        </div>
        <div class="signal-tags">
          <span>闭环率 {{ requestClosureRate }}%</span>
          <span>待签字 {{ signatureCount }}</span>
        </div>
      </article>

      <button class="executive-summary-card urgent" type="button" @click="$emit('goTab', 'stock')">
        <div>
          <h2>紧急风险</h2>
          <p>影响手术、抢救、基础护理的关键耗材</p>
        </div>
        <div class="large-number">
          <strong>{{ urgentCount }}</strong>
          <span>项</span>
        </div>
        <small>{{ riskText }}</small>
      </button>

      <button class="executive-summary-card attention" type="button" @click="$emit('goTab', 'requests')">
        <div>
          <h2>关注事项</h2>
          <p>需要科室或库房协同跟进的单据</p>
        </div>
        <div class="large-number">
          <strong>{{ attentionCount }}</strong>
          <span>单</span>
        </div>
        <small>其中 {{ overdueAttentionCount }} 单超过 30 分钟未更新状态</small>
      </button>

      <article class="executive-summary-card compact">
        <div>
          <h2>关键指标</h2>
        </div>
        <div class="metric-stack">
          <div v-for="item in keyMetrics" :key="item.label">
            <span>{{ item.label }}</span>
            <strong :class="item.tone">{{ item.value }}</strong>
          </div>
        </div>
      </article>
    </section>

    <div class="executive-content-grid primary">
      <section class="panel">
        <div class="panel-head compact">
          <div>
            <h2>科室消耗 TOP</h2>
            <p>按今日出库金额与高频耗材综合排序</p>
          </div>
        </div>
        <div v-if="departmentConsumptionTop.length" class="bar-list">
          <div v-for="row in departmentConsumptionTop.slice(0, 5)" :key="row.department" class="bar-row">
            <span>{{ row.department }}</span>
            <div><i :style="{ width: `${Math.max(8, (row.value / maxDepartmentConsumption) * 100)}%` }"></i></div>
            <strong>{{ Math.round((row.value / maxDepartmentConsumption) * 100) }}%</strong>
          </div>
        </div>
        <el-empty v-else description="暂无周消耗数据" :image-size="72" />
      </section>

      <section class="panel">
        <div class="panel-head compact">
          <div>
            <h2>物资库存明细</h2>
            <p>保留原表格字段，增强风险状态可读性</p>
          </div>
        </div>
        <el-table :data="stockPreviewRows" border>
          <el-table-column prop="name" label="物资名称" min-width="150" />
          <el-table-column prop="stockText" label="当前库存" width="120" />
          <el-table-column prop="safeText" label="安全库存" width="120" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.level" effect="light">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>

    <div class="executive-content-grid secondary">
      <section class="panel">
        <div class="panel-head compact">
          <div>
            <h2>待签字事项</h2>
            <p>按超时风险排序，减少领导视角下的信息噪声</p>
          </div>
        </div>
        <div v-if="todoRows.length" class="signature-list">
          <button
            v-for="row in todoRows.slice(0, 3)"
            :key="row.id"
            class="signature-row"
            type="button"
            @click="$emit('openTodo', row)"
          >
            <span>{{ row.title.split(" ")[0] }}</span>
            <strong>{{ row.title.replace(row.title.split(" ")[0], "").trim() || row.type }}</strong>
            <em>{{ row.type }}</em>
            <small>{{ todoAge(row) }}</small>
          </button>
          <el-button v-if="todoRows.length > 3" type="primary" @click="$emit('goTab', 'requests')">进入签字中心</el-button>
        </div>
        <el-empty v-else description="暂无待签字事项" :image-size="72" />
      </section>

      <section class="panel">
        <div class="panel-head compact">
          <div>
            <h2>风险闭环</h2>
            <p>把原来的分散卡片收敛成可追踪处理链路</p>
          </div>
        </div>
        <div class="risk-flow">
          <div v-for="step in riskFlow" :key="step.label" class="risk-step" :class="step.tone">
            <span>{{ step.index }}</span>
            <strong>{{ step.label }}</strong>
            <small>{{ step.desc }}</small>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { InventoryRequest } from "@/api/modules/inventory";

type TagLevel = "primary" | "success" | "warning" | "danger" | "info";

type ExecutiveSignal = {
  level: string;
  title: string;
  desc: string;
};

type TodoRow = {
  id: string;
  type: string;
  level: TagLevel;
  title: string;
  desc: string;
  actionLabel: string;
  tab: string;
  action?: "approve" | "issue" | "receive";
  request?: InventoryRequest;
};

type KeyMetric = {
  label: string;
  value: string | number;
  tone: string;
};

type StockPreviewRow = {
  id: string;
  name: string;
  stock: number;
  stockText: string;
  safeText: string;
  status: string;
  level: TagLevel;
};

type RiskFlowStep = {
  index: number;
  label: string;
  desc: string;
  tone: string;
};

type DepartmentConsumptionRow = {
  department: string;
  value: number;
};

defineProps<{
  executiveSignal: ExecutiveSignal;
  requestClosureRate: number;
  signatureCount: number;
  urgentCount: number;
  riskText: string;
  attentionCount: number;
  overdueAttentionCount: number;
  keyMetrics: KeyMetric[];
  departmentConsumptionTop: DepartmentConsumptionRow[];
  maxDepartmentConsumption: number;
  stockPreviewRows: StockPreviewRow[];
  todoRows: TodoRow[];
  riskFlow: RiskFlowStep[];
  canViewAllDepartments: boolean;
  currentDepartment: string;
  todoAge: (row: TodoRow) => string;
}>();

defineEmits<{
  goTab: [tab: string];
  openTodo: [row: TodoRow];
}>();
</script>

<style scoped lang="scss">
.executive-panel {
  display: grid;
  gap: 12px;
}

.panel {
  padding: 13px 14px;
  background: var(--inventory-panel);
  border: 1px solid var(--inventory-line);
  border-radius: 8px;
  box-shadow: 0 1px 1px rgb(15 23 42 / 3%);
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;

  h2,
  p {
    margin: 0;
  }

  h2 {
    color: var(--inventory-text);
    font-size: 16px;
    line-height: 1.35;
  }

  p {
    margin-top: 4px;
    color: var(--inventory-muted);
    font-size: 13px;
  }

  &.compact {
    margin-bottom: 12px;
  }
}

.executive-filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 18px;
  background: var(--inventory-panel);
  border: 1px solid var(--inventory-line);
  border-radius: 8px;
}

.filter-pills,
.filter-fields {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.filter-pills button,
.filter-fields strong {
  min-height: 32px;
  padding: 6px 17px;
  color: #183044;
  font-size: 13px;
  font-weight: 700;
  background: #ffffff;
  border: 1px solid #cfd9e3;
  border-radius: 999px;
}

.filter-pills button {
  cursor: pointer;

  &.active,
  &:hover {
    color: #ffffff;
    background: var(--inventory-primary);
    border-color: var(--inventory-primary);
  }
}

.filter-fields {
  justify-content: flex-end;

  span {
    color: #40556a;
    font-size: 13px;
    font-weight: 700;
  }

  strong {
    min-width: 118px;
    text-align: center;
    border-radius: 7px;
  }
}

.executive-summary-grid {
  display: grid;
  grid-template-columns: minmax(260px, 0.9fr) minmax(240px, 0.9fr) minmax(260px, 0.9fr) minmax(260px, 0.92fr);
  gap: 12px;
}

.executive-summary-card {
  display: grid;
  align-content: space-between;
  gap: 14px;
  min-height: 154px;
  padding: 22px 22px 18px;
  text-align: left;
  background: #ffffff;
  border: 1px solid var(--inventory-line);
  border-radius: 8px;

  h2,
  p,
  strong,
  small {
    display: block;
    margin: 0;
  }

  h2 {
    color: #061833;
    font-size: 16px;
    line-height: 1.3;
  }

  p,
  small {
    color: #53677d;
    font-size: 13px;
    line-height: 1.45;
  }

  &.urgent,
  &.attention {
    cursor: pointer;
    transition:
      border-color 0.16s ease,
      background 0.16s ease;

    &:hover {
      background: #fbfdfd;
      border-color: rgb(8 118 111 / 28%);
    }
  }
}

.signal-body {
  display: flex;
  align-items: center;
  gap: 20px;

  strong {
    color: var(--inventory-success);
    font-size: 26px;
    line-height: 1.1;
  }

  small {
    margin-top: 4px;
  }
}

.signal-dot {
  display: inline-grid;
  place-items: center;
  width: 62px;
  height: 62px;
  background: rgb(35 128 95 / 14%);
  border: 1px solid rgb(35 128 95 / 22%);
  border-radius: 50%;

  &::after {
    width: 28px;
    height: 28px;
    content: "";
    background: var(--inventory-success);
    border-radius: 50%;
  }
}

.executive-summary-card.signal.warning {
  .signal-dot {
    background: rgb(183 121 31 / 14%);
    border-color: rgb(183 121 31 / 24%);

    &::after {
      background: var(--inventory-warning);
    }
  }

  .signal-body strong {
    color: var(--inventory-warning);
  }
}

.executive-summary-card.signal.danger {
  .signal-dot {
    background: rgb(200 50 50 / 12%);
    border-color: rgb(200 50 50 / 22%);

    &::after {
      background: var(--inventory-danger);
    }
  }

  .signal-body strong {
    color: var(--inventory-danger);
  }
}

.signal-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;

  span {
    padding: 4px 10px;
    color: var(--inventory-primary);
    font-size: 12px;
    font-weight: 700;
    background: var(--inventory-primary-soft);
    border-radius: 999px;

    &:last-child {
      color: var(--inventory-warning);
      background: var(--inventory-warning-soft);
    }
  }
}

.large-number {
  display: flex;
  align-items: flex-end;
  gap: 8px;

  strong {
    color: var(--inventory-danger);
    font-size: 54px;
    line-height: 0.9;
    font-variant-numeric: tabular-nums;
  }

  span {
    color: var(--inventory-danger);
    font-size: 18px;
    font-weight: 700;
  }
}

.attention .large-number {
  strong,
  span {
    color: #071a34;
  }
}

.metric-stack {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  align-items: end;

  div {
    min-width: 0;
  }

  span,
  strong {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: #4b5f73;
    font-size: 12px;
    font-weight: 700;
  }

  strong {
    margin-top: 10px;
    color: var(--inventory-primary);
    font-size: 26px;
    line-height: 1;
    font-variant-numeric: tabular-nums;
  }

  strong.warning {
    color: var(--inventory-warning);
  }

  strong.danger {
    color: var(--inventory-danger);
  }
}

.executive-content-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.88fr) minmax(420px, 1fr);
  gap: 12px;

  &.secondary {
    grid-template-columns: minmax(0, 0.88fr) minmax(420px, 1fr);
  }
}

.bar-list {
  display: grid;
  gap: 14px;
  padding: 12px 6px 8px;
}

.bar-row {
  display: grid;
  grid-template-columns: minmax(86px, 104px) minmax(0, 1fr) 48px;
  align-items: center;
  gap: 16px;

  span,
  strong {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  div {
    height: 10px;
    overflow: hidden;
    background: #eef3f7;
    border-radius: 999px;
  }

  i {
    display: block;
    height: 100%;
    background: var(--inventory-primary);
    border-radius: inherit;
  }

  strong {
    color: #4d5f75;
    font-variant-numeric: tabular-nums;
    text-align: right;
  }
}

.bar-row:nth-child(2) i {
  background: #2f8b5d;
}

.bar-row:nth-child(3) i {
  background: #2563eb;
}

.bar-row:nth-child(4) i {
  background: #b8751c;
}

.bar-row:nth-child(5) i {
  background: #6b7280;
}

.signature-list {
  display: grid;
  gap: 0;

  .el-button {
    justify-self: start;
    margin-top: 10px;
  }
}

.signature-row {
  display: grid;
  grid-template-columns: minmax(86px, 120px) minmax(0, 1fr) minmax(96px, 130px) 76px;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 12px 0;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-bottom: 1px solid var(--inventory-line-soft);

  span,
  strong,
  em,
  small {
    overflow: hidden;
    font-style: normal;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span,
  strong {
    color: #071a34;
    font-weight: 700;
  }

  em {
    color: var(--inventory-warning);
    font-size: 13px;
    font-weight: 700;
  }

  small {
    color: #586b80;
    font-size: 14px;
    font-weight: 700;
    text-align: right;
  }

  &:hover {
    background: #fbfdfd;
  }
}

.risk-flow {
  position: relative;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 28px;
  padding: 30px 10px 18px;

  &::before {
    position: absolute;
    top: 50px;
    right: 13%;
    left: 13%;
    height: 1px;
    content: "";
    background: #cdd6df;
  }
}

.risk-step {
  position: relative;
  z-index: 1;
  display: grid;
  justify-items: start;
  gap: 8px;

  span {
    display: inline-grid;
    place-items: center;
    width: 39px;
    height: 39px;
    color: var(--inventory-primary);
    font-weight: 800;
    background: #ffffff;
    border: 3px solid currentcolor;
    border-radius: 50%;
  }

  strong,
  small {
    display: block;
  }

  strong {
    color: #071a34;
    font-size: 14px;
  }

  small {
    color: #53677d;
    line-height: 1.45;
  }

  &.danger span {
    color: var(--inventory-danger);
  }

  &.warning span {
    color: var(--inventory-warning);
  }

  &.primary span {
    color: #2563eb;
  }

  &.success span {
    color: var(--inventory-success);
  }
}

@media (max-width: 1080px) {
  .executive-summary-grid,
  .executive-content-grid,
  .executive-content-grid.secondary {
    grid-template-columns: 1fr;
  }

  .executive-filter-bar {
    display: grid;
  }

  .executive-summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .executive-summary-grid,
  .metric-stack,
  .risk-flow {
    grid-template-columns: 1fr;
  }

  .signature-row {
    grid-template-columns: 1fr;
  }

  .risk-flow::before {
    display: none;
  }

  .signature-row small {
    text-align: left;
  }

  .signal-body strong {
    font-size: 24px;
  }
}
</style>
