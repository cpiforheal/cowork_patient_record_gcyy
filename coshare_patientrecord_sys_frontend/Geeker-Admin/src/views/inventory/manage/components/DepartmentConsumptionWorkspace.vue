<template>
  <section v-if="template" class="department-consumption-workspace">
    <div class="department-toolbar">
      <div>
        <h2>{{ template.department }}耗材填报</h2>
      </div>
      <div class="toolbar-actions">
        <el-date-picker v-model="businessDate" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="loadDraft" />
        <el-button :icon="Refresh" :disabled="saving" @click="loadDraft">刷新</el-button>
        <el-tooltip v-if="!isInventoryPortal" content="恢复该科室的原始表格模板" placement="bottom">
          <el-button :icon="RefreshLeft" circle aria-label="恢复原始表格模板" @click="restoreTemplate" />
        </el-tooltip>
        <el-select v-if="!isInventoryPortal" v-model="reportPeriod" class="report-period-select" :disabled="saving || Boolean(exporting)">
          <el-option label="自然周报" value="week" />
          <el-option label="自然月报" value="month" />
        </el-select>
        <el-button v-if="!isInventoryPortal" :loading="exporting === 'xlsx'" :disabled="saving" @click="exportPeriod('xlsx')">
          导出{{ reportPeriod === "week" ? "周报" : "月报" }}
        </el-button>
        <el-button v-if="!isInventoryPortal" :loading="exporting === 'csv'" :disabled="saving" @click="exportPeriod('csv')">CSV 兼容包</el-button>
        <el-button v-if="!isInventoryPortal" plain :disabled="saving" @click="openAllocationPlan">下拨量与预警</el-button>
        <el-button type="primary" :loading="saving" :icon="DocumentChecked" @click="saveDraft">保存日草稿</el-button>
      </div>
    </div>

    <div v-if="todayPending" class="fill-reminder" role="status">
      <span class="reminder-dot" aria-hidden="true" />
      <div class="reminder-text">
        <strong>今日（{{ today }}）耗材日报尚未填报</strong>
        <span>保存后才会计入全院汇总与定额核对{{ lastFilledDate ? `；最近一次填报：${lastFilledDate}` : "" }}</span>
      </div>
      <el-button v-if="lastFilledDate" size="small" type="primary" plain :loading="bringingLast" @click="applyLastFilledDraft">
        带入 {{ lastFilledDate }} 填报值
      </el-button>
    </div>

    <div
      ref="workspaceGridRef"
      class="department-workspace-grid"
      :class="{ 'is-resizing': isResizing }"
      :style="{ '--input-pane-width': inputPaneWidth ? inputPaneWidth + 'px' : undefined }"
    >
      <section class="input-pane">
        <div class="pane-heading">
          <div>
            <h3>耗材明细</h3>
            <p class="patient-flow-hint">先填写当前科室流转患者人次；系统按“每人次定额 × 人次”计算参考使用量。</p>
          </div>
          <div class="patient-flow-total"><small>当前填报总人次</small><strong>{{ currentPatientFlow }}</strong></div>
        </div>

        <div class="input-pane-secondary-actions">
          <div class="editor-filter">
            <span>服务类型</span>
            <el-select v-model="careTypeFilter" class="care-type-filter" aria-label="筛选服务类型">
              <el-option label="全部" value="all" />
              <el-option v-for="option in careTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </div>
          <div class="input-pane-tools">
            <el-button :icon="Plus" @click="addLine">新增耗材</el-button>
            <el-tooltip v-if="!draft.revision && lastFilledDate" :content="`复制 ${lastFilledDate} 已保存的人次与用量，核对后再保存`" placement="top">
              <el-button :icon="CopyDocument" :loading="bringingLast" @click="applyLastFilledDraft">带入上次填报</el-button>
            </el-tooltip>
            <el-button type="primary" plain class="expand-editor-btn" :icon="EditPen" @click="openExpandedEditor">展开编辑</el-button>
          </div>
        </div>


        <div class="volume-grid" aria-label="当前科室流转患者人次">
          <label v-for="group in serviceGroups" :key="group" class="volume-field">
            <span><b>当前流转人次</b>{{ group }}</span>
            <el-input-number
              v-model="draft.groupVolumes[group]"
              :min="0"
              :precision="0"
              controls-position="right"
              @change="normalizeGroupVolume(group)"
            />
          </label>
          <label class="volume-field month-days">
            <span>本月天数</span>
            <el-input-number
              v-model="draft.monthDays"
              :min="1"
              :max="31"
              :precision="0"
              controls-position="right"
              @change="normalizeMonthDays"
            />
          </label>
        </div>

        <el-table :data="visibleDraftLines" class="input-table" height="calc(100vh - 330px)" min-height="380" table-layout="fixed">
          <el-table-column label="服务项目 / 类型" min-width="180">
            <template #default="{ row }">
              <el-select v-model="row.serviceGroup" filterable allow-create default-first-option :disabled="isInventoryPortal && !canEditQuota && !row.isSupplemental">
                <el-option v-for="group in serviceGroups" :key="group" :label="group" :value="group" />
              </el-select>
              <el-select v-model="row.careType" class="care-type-select" :disabled="isInventoryPortal && !canEditQuota && !row.isSupplemental">
                <el-option v-for="option in careTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="耗材" min-width="190">
            <template #default="{ row }">
              <el-select v-model="row.materialName" filterable allow-create default-first-option placeholder="选择或输入耗材" :disabled="isInventoryPortal && !canEditQuota && !row.isSupplemental">
                <el-option v-for="name in materialOptions" :key="name" :label="name" :value="name" />
              </el-select>
              <div v-if="row.isSpecial" class="special-material-marker">
                <el-tag type="warning" size="small" effect="plain">特殊耗材</el-tag>
                <span>{{ row.specialAdminNote || "按实际量管理" }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="单位" width="96">
            <template #default="{ row }"><el-input v-model="row.unit" placeholder="单位" :disabled="isInventoryPortal && !canEditQuota && !row.isSupplemental" /></template>
          </el-table-column>
          <el-table-column label="每人次定额" width="122">
            <template #default="{ row }"
              ><el-input-number v-if="canEditQuota" v-model="row.standardQuantity" :min="0" :precision="6" controls-position="right" />
              <span v-else class="readonly-quantity">{{ row.standardQuantity == null ? "待核定" : formatQuantity(row.standardQuantity) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="手工调整" width="122">
            <template #default="{ row }"><el-input-number v-if="canEditQuota" v-model="row.manualAdjustment" :precision="6" controls-position="right" />
              <span v-else class="readonly-quantity">{{ formatQuantity(row.manualAdjustment) }}</span></template>
          </el-table-column>
          <el-table-column label="实际耗材（选填）" width="140" header-class-name="actual-consumable-header">
            <template #default="{ row }"><el-input-number v-model="row.actualQuantity" :min="0" :precision="6" controls-position="right" placeholder="选填，留空即可" /></template>
          </el-table-column>
          <el-table-column label="特殊情况说明" min-width="190">
            <template #default="{ row }">
              <el-input v-model="row.specialDailyNote" maxlength="500" show-word-limit placeholder="可选填写当日特殊情况" />
            </template>
          </el-table-column>
          <el-table-column label="适用流转人次" width="122">
            <template #default="{ row }"
              ><el-input-number
                v-model="row.volumeOverride"
                :min="0"
                :precision="0"
                controls-position="right"
                placeholder="跟随分组"
                @change="normalizeLineVolume(row)"
            /></template>
          </el-table-column>
          <el-table-column label="单价" width="116">
            <template #default="{ row }"
              ><el-input-number v-model="row.unitPrice" :min="0" :precision="4" controls-position="right" :disabled="isInventoryPortal && !canEditQuota && !row.isSupplemental"
            /></template>
          </el-table-column>
          <el-table-column width="54" align="center">
            <template #default="{ row }">
              <el-tooltip content="删除本次草稿中的耗材行" placement="left">
                <el-button :icon="Delete" circle text type="danger" aria-label="删除耗材行" @click="removeLine(row.id)" />
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <div
        class="workspace-resizer"
        role="separator"
        aria-orientation="vertical"
        aria-label="调整填写区和预览区宽度"
        @pointerdown="startResize"
      />

      <section class="preview-pane">
        <div class="pane-heading">
          <h3>核对预览</h3>
          <span class="draft-state">{{ quotaVersionLabel }} · {{ draft.revision ? `已保存 v${draft.revision}` : "未保存" }}</span>
        </div>

        <div class="preview-summary">
          <span
            ><small>耗材行</small><strong>{{ visiblePreviewRows.length }}</strong></span
          >
          <span><small>当前流转总人次</small><strong>{{ currentPatientFlow }}</strong></span>
          <span
            ><small>参考使用量（定额 × 人次）</small
            ><strong class="unit-summary">{{ referenceQuantitySummary || "暂无可汇总数量" }}</strong></span
          >
          <span><small>实际使用量（选填）</small><strong>{{ excludedQuantityLineCount }} 行未填</strong></span>
          <span
            ><small>按实际外推月金额</small
            ><strong>{{ pricedMonthlyAmount === null ? "未核价" : formatMoney(pricedMonthlyAmount) }}</strong></span
          >
        </div>

        <el-table :data="visiblePreviewRows" class="preview-table" height="calc(100vh - 390px)" min-height="380" table-layout="fixed">
          <el-table-column prop="serviceGroup" label="服务项目" min-width="140" show-overflow-tooltip />
          <el-table-column prop="materialName" label="耗材" min-width="178" show-overflow-tooltip />
          <el-table-column prop="standardQuantity" label="标准用量" width="104">
            <template #default="{ row }">{{
              row.standardQuantity === null ? "待核定" : formatQuantity(row.standardQuantity)
            }}</template>
          </el-table-column>
                    <el-table-column prop="manualAdjustment" label="手工调整" width="104">
            <template #default="{ row }">{{ formatQuantity(row.manualAdjustment) }}</template>
          </el-table-column>
          <el-table-column prop="volume" label="流转人次" width="88" />
          <el-table-column prop="referenceQuantity" label="参考使用量（定额×人次）" width="162">
            <template #default="{ row }">{{ formatQuantity(row.referenceQuantity) }}</template>
          </el-table-column>
          <el-table-column prop="actualQuantity" label="实际使用量" width="112">
            <template #default="{ row }">{{ row.actualFilled ? formatQuantity(row.dailyQuantity) : "未填报" }}</template>
          </el-table-column>
          <el-table-column prop="monthlyQuantity" label="按实际外推月量" width="132">
            <template #default="{ row }">{{ row.actualFilled ? formatQuantity(row.monthlyQuantity) : "—" }}</template>
          </el-table-column>
          <el-table-column prop="monthlyAmount" label="月金额" width="104">
            <template #default="{ row }">{{ row.monthlyAmount === null ? "未核价" : formatMoney(row.monthlyAmount) }}</template>
          </el-table-column>
          <el-table-column v-if="!isInventoryPortal" label="下拨结余 / 预警" width="154">
            <template #default="{ row }">
              <span v-if="allocationLineFor(row)" class="allocation-cell">
                日余 {{ formatQuantity(allocationDailyRemaining(row)) }}<br />
                月余 {{ formatQuantity(allocationLineFor(row)!.monthRemainingQuantity || 0) }}
                <el-tag :type="allocationTagType(allocationLineFor(row)!.status)" size="small" effect="plain">
                  {{ allocationStatusText(allocationLineFor(row)!.status) }}
                </el-tag>
              </span>
              <el-tag v-else type="info" size="small" effect="plain">待设定</el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="!isInventoryPortal" label="库存关联" width="118">
            <template #default="{ row }">
              <el-tag v-if="row.stock !== null" effect="plain" type="success">库存 {{ formatQuantity(row.stock) }}</el-tag>
              <el-tag v-else effect="plain" type="info">待关联</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>

    <section class="draft-history-card" aria-label="填报历史日历">
      <div class="history-toolbar">
        <div>
          <div class="history-eyebrow">填报历史 · {{ template.department }}</div>
          <h3 class="history-title">{{ historyMonthLabel }} 填报日历</h3>
          <p class="history-sub">
            本月已填报 <b>{{ historyMonthStats.filled }}</b> 天<template v-if="historyMonthStats.missing">，缺报
              <b class="history-missing-num">{{ historyMonthStats.missing }}</b> 天</template
            >；点击日期可直接切换到该日填报。
          </p>
        </div>
        <div class="history-actions">
          <el-button :icon="ArrowLeft" circle aria-label="上个月" @click="shiftHistoryMonth(-1)" />
          <el-button size="small" @click="resetHistoryMonth">本月</el-button>
          <el-button :icon="ArrowRight" circle aria-label="下个月" @click="shiftHistoryMonth(1)" />
        </div>
      </div>
      <div class="history-weekdays"><span v-for="label in historyWeekdayLabels" :key="label">{{ label }}</span></div>
      <div v-loading="historyLoading" class="history-grid">
        <button
          v-for="cell in historyCells"
          :key="cell.key"
          type="button"
          class="history-day"
          :class="cell.classes"
          :disabled="cell.disabled"
          :aria-label="cell.ariaLabel"
          @click="selectHistoryDate(cell)"
        >
          <span class="history-day-num">{{ cell.day || "" }}</span>
          <span class="history-day-state">{{ cell.state }}</span>
        </button>
      </div>
      <div class="history-legend">
        <span><i class="legend-chip is-filled" />已填报</span>
        <span><i class="legend-chip is-missing" />缺报</span>
        <span><i class="legend-chip is-pending" />今日待填</span>
        <span class="history-legend-note">浅灰为未到填报日，选中日会以描边标记</span>
      </div>
    </section>

    <el-dialog
      v-model="editorOpen"
      class="consumption-editor-dialog"
      title="耗材明细编辑"
      width="min(1220px, calc(100vw - 32px))"
      top="4vh"
      append-to-body
      destroy-on-close
      :close-on-click-modal="false"
    >
      <div class="editor-dialog-shell">
        <div class="editor-dialog-toolbar">
          <div class="editor-filter">
            <span>服务类型</span>
            <el-select v-model="careTypeFilter" class="care-type-filter" aria-label="筛选服务类型">
              <el-option label="全部" value="all" />
              <el-option v-for="option in careTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </div>
          <el-button type="primary" plain :icon="Plus" @click="addLine">新增耗材</el-button>
        </div>

        <div class="volume-grid editor-volume-grid" aria-label="当前科室流转患者人次">
          <label v-for="group in serviceGroups" :key="`editor-${group}`" class="volume-field">
            <span><b>当前流转人次</b>{{ group }}</span>
            <el-input-number
              v-model="draft.groupVolumes[group]"
              :min="0"
              :precision="0"
              controls-position="right"
              @change="normalizeGroupVolume(group)"
            />
          </label>
          <label class="volume-field month-days">
            <span>本月天数</span>
            <el-input-number
              v-model="draft.monthDays"
              :min="1"
              :max="31"
              :precision="0"
              controls-position="right"
              @change="normalizeMonthDays"
            />
          </label>
        </div>

        <div class="editor-table-wrap">
          <el-table
            :data="visibleDraftLines"
            class="input-table expanded-input-table"
            height="calc(84vh - 300px)"
            min-height="360"
            table-layout="fixed"
          >
            <el-table-column label="服务项目 / 类型" min-width="190">
              <template #default="{ row }">
                <el-select v-model="row.serviceGroup" filterable allow-create default-first-option :disabled="isInventoryPortal && !canEditQuota && !row.isSupplemental">
                  <el-option v-for="group in serviceGroups" :key="group" :label="group" :value="group" />
                </el-select>
                <el-select v-model="row.careType" class="care-type-select" :disabled="isInventoryPortal && !canEditQuota && !row.isSupplemental">
                  <el-option v-for="option in careTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="耗材" min-width="220">
              <template #default="{ row }">
                <el-select v-model="row.materialName" filterable allow-create default-first-option placeholder="选择或输入耗材" :disabled="isInventoryPortal && !canEditQuota && !row.isSupplemental">
                  <el-option v-for="name in materialOptions" :key="name" :label="name" :value="name" />
                </el-select>
                <div v-if="row.isSpecial" class="special-material-marker">
                  <el-tag type="warning" size="small" effect="plain">特殊耗材</el-tag>
                  <span>{{ row.specialAdminNote || "按实际量管理" }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="单位" width="110">
              <template #default="{ row }"><el-input v-model="row.unit" placeholder="单位" :disabled="isInventoryPortal && !canEditQuota && !row.isSupplemental" /></template>
            </el-table-column>
            <el-table-column label="每人次定额" width="138">
              <template #default="{ row }">
                <el-input-number v-if="canEditQuota" v-model="row.standardQuantity" :min="0" :precision="6" controls-position="right" />
                <span v-else class="readonly-quantity">{{ row.standardQuantity == null ? "待核定" : formatQuantity(row.standardQuantity) }}</span>
              </template>
            </el-table-column>
          <el-table-column label="手工调整" width="122">
            <template #default="{ row }"><el-input-number v-if="canEditQuota" v-model="row.manualAdjustment" :precision="6" controls-position="right" />
              <span v-else class="readonly-quantity">{{ formatQuantity(row.manualAdjustment) }}</span></template>
          </el-table-column>
          <el-table-column label="实际耗材（选填）" width="140" header-class-name="actual-consumable-header">
            <template #default="{ row }"><el-input-number v-model="row.actualQuantity" :min="0" :precision="6" controls-position="right" placeholder="选填，留空即可" /></template>
          </el-table-column>
          <el-table-column label="特殊情况说明" min-width="210">
            <template #default="{ row }">
              <el-input v-model="row.specialDailyNote" maxlength="500" show-word-limit placeholder="可选填写当日特殊情况" />
            </template>
          </el-table-column>
            <el-table-column label="适用流转人次" width="138">
              <template #default="{ row }">
                <el-input-number
                  v-model="row.volumeOverride"
                  :min="0"
                  :precision="0"
                  controls-position="right"
                  placeholder="跟随分组"
                  @change="normalizeLineVolume(row)"
                />
              </template>
            </el-table-column>
            <el-table-column label="单价" width="126">
              <template #default="{ row }">
                <el-input-number v-model="row.unitPrice" :min="0" :precision="4" controls-position="right" :disabled="isInventoryPortal && !canEditQuota && !row.isSupplemental" />
              </template>
            </el-table-column>
            <el-table-column fixed="right" width="60" align="center">
              <template #default="{ row }">
                <el-tooltip content="删除本次草稿中的耗材行" placement="left">
                  <el-button :icon="Delete" circle text type="danger" aria-label="删除耗材行" @click="removeLine(row.id)" />
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
      <template #footer>
        <div class="editor-dialog-footer">
          <div class="editor-dialog-actions">
            <el-button :disabled="saving" @click="closeExpandedEditor">关闭</el-button>
            <el-button type="primary" :loading="saving" :icon="DocumentChecked" @click="saveDraft">保存草稿</el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <el-dialog
      v-if="!isInventoryPortal"
      v-model="allocationOpen"
      class="allocation-plan-dialog"
      title="科室耗材下拨量与预警"
      width="min(1120px, calc(100vw - 32px))"
      append-to-body
      :close-on-click-modal="false"
    >
      <div class="allocation-plan-note">
        这是核对与预警计划，不扣库存、不生成库存流水。默认预警值为当前月已保存日草稿的平均日使用量 × 3；没有下拨量时显示“待设定”。
      </div>
      <el-table v-loading="allocationLoading" :data="allocationLines" max-height="480" table-layout="fixed">
        <el-table-column prop="materialName" label="耗材" min-width="180" />
        <el-table-column prop="unit" label="单位" width="92" />
        <el-table-column label="下拨量" width="132">
          <template #default="{ row }"><el-input-number v-model="row.allocatedQuantity" :min="0" :precision="6" controls-position="right" /></template>
        </el-table-column>
        <el-table-column label="来源" width="132">
          <template #default="{ row }">
            <el-select v-model="row.sourceType">
              <el-option label="盘点表" value="COUNT" />
              <el-option label="手工填写" value="MANUAL" />
              <el-option label="上月实际建议" value="PREVIOUS_MONTH" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="上月实际建议" width="132">
          <template #default="{ row }">{{ formatQuantity(row.previousMonthSuggestedQuantity || 0) }}</template>
        </el-table-column>
        <el-table-column label="盘点表引用" min-width="140">
          <template #default="{ row }"><el-input v-model="row.countReference" placeholder="可选" /></template>
        </el-table-column>
        <el-table-column label="手工调整" width="132">
          <template #default="{ row }"><el-input-number v-model="row.manualAdjustment" :precision="6" controls-position="right" /></template>
        </el-table-column>
        <el-table-column label="预警值" width="132">
          <template #default="{ row }"><el-input-number v-model="row.warningThreshold" :min="0" :precision="6" controls-position="right" placeholder="三日建议" /></template>
        </el-table-column>
        <el-table-column label="月累计 / 月结余" width="148">
          <template #default="{ row }">
            {{ formatQuantity(row.monthUsedQuantity || 0) }} / {{ formatQuantity(row.monthRemainingQuantity || 0) }}
            <el-tag :type="allocationTagType(row.status)" size="small" effect="plain">{{ allocationStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <span class="allocation-dialog-state">{{ allocation?.revision ? `已保存 v${allocation.revision}` : "未保存" }}</span>
        <el-button @click="allocationOpen = false">关闭</el-button>
        <el-button type="primary" :loading="allocationSaving" @click="saveAllocationPlan">保存下拨计划</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";

const isInventoryPortal = import.meta.env.VITE_PORTAL_MODE === "inventory";
import { ArrowLeft, ArrowRight, CopyDocument, Delete, DocumentChecked, EditPen, Plus, Refresh, RefreshLeft } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import {
  downloadInventoryDepartmentPeriodReportApi,
  getInventoryDepartmentAllocationPlanApi,
  getInventoryDepartmentDailyDraftApi,
  getInventoryDepartmentDailyDraftHistoryApi,
  saveInventoryDepartmentAllocationPlanApi,
  saveInventoryDepartmentDailyDraftApi,
  type InventoryBatch,
  type InventoryDepartmentAllocationPlan,
  type InventoryDepartmentAllocationPlanLine,
  type InventoryDepartmentDailyDraft,
  type InventoryDepartmentDraftCareType,
  type InventoryDepartmentDraftHistoryDay,
  type InventoryDepartmentDraftLine,
  type InventoryItem
} from "@/api/modules/inventory";
import { departmentTemplateByKey, type DepartmentTemplate, type DepartmentTemplateLine } from "../departmentConsumptionTemplates";

type DraftState = Required<Pick<InventoryDepartmentDailyDraft, "monthDays" | "revision" | "groupVolumes" | "lines">>
  & Pick<InventoryDepartmentDailyDraft, "templateVersion" | "quotaVersionId" | "quotaVersionCode" | "quotaEffectiveDate" | "frozenQuota">;
type PreviewRow = InventoryDepartmentDraftLine & {
  volume: number;
  referenceQuantity: number;
  actualFilled: boolean;
  dailyQuantity: number;
  monthlyQuantity: number;
  monthlyAmount: number | null;
  stock: number | null;
};

const props = defineProps<{
  departmentKey: string;
  items: InventoryItem[];
  batches: InventoryBatch[];
  today: string;
}>();

const businessDate = ref(props.today);
const saving = ref(false);
const exporting = ref<"xlsx" | "csv" | "">("");
const editorOpen = ref(false);
const allocationOpen = ref(false);
const allocationLoading = ref(false);
const allocationSaving = ref(false);
const reportPeriod = ref<"week" | "month">("month");
const allocation = ref<InventoryDepartmentAllocationPlan | null>(null);
const careTypeFilter = ref<InventoryDepartmentDraftCareType | "all">("all");
const canEditQuota = computed(() => !isInventoryPortal);
const latestLoadKey = ref("");
const workspaceGridRef = ref<HTMLElement>();
const inputPaneWidth = ref<number>();
const isResizing = ref(false);
const workspaceWidthKey = "inventory-department-workspace-input-width";
let resizeCleanup: (() => void) | undefined;
const template = computed(() => departmentTemplateByKey.get(props.departmentKey));
const careTypeOptions: { label: string; value: InventoryDepartmentDraftCareType }[] = [
  { label: "门诊", value: "outpatient" },
  { label: "住院", value: "inpatient" },
  { label: "其他", value: "other" }
];
const normalizeSavedDraftLine = (line: InventoryDepartmentDraftLine): InventoryDepartmentDraftLine => {
  const rawCareType = (line as { careType?: unknown }).careType;
  const validCareType = careTypeOptions.some(option => option.value === rawCareType)
    ? (rawCareType as InventoryDepartmentDraftCareType)
    : null;
  const sourceCareType = template.value?.lines.find(source => Number(source.sourceRow) === Number(line.sourceRow))?.careType;
  return {
    ...line,
    careType: validCareType || sourceCareType || "other",
    unitPrice: line.unitPrice ?? null,
    volumeOverride: line.volumeOverride ?? null,
    manualAdjustment: Number(line.manualAdjustment || 0),
    actualQuantity: line.actualQuantity ?? null,
    isSupplemental: Boolean(line.isSupplemental)
  };
};

const lineFromTemplate = (line: DepartmentTemplateLine): InventoryDepartmentDraftLine => ({
  id: `source-${line.sourceRow}`,
  sourceRow: line.sourceRow,
  serviceGroup: line.serviceGroup,
  careType: line.careType,
  materialName: line.materialName,
  unit: line.unit,
  standardQuantity: line.standardQuantity,
  unitPrice: null,
  volumeOverride: null,
  manualAdjustment: 0,
  actualQuantity: null,
  isSupplemental: false
});

const blankState = (source: DepartmentTemplate): DraftState => ({
  monthDays: source.monthDays,
  revision: 0,
  groupVolumes: Object.fromEntries(
    source.lines.reduce((groups, line) => groups.set(line.serviceGroup, line.defaultVolume), new Map<string, number>())
  ),
  lines: source.lines.map(lineFromTemplate),
  templateVersion: "xlsx-20260808",
  frozenQuota: false
});

const draft = ref<DraftState>(
  template.value ? blankState(template.value) : { monthDays: 30, revision: 0, groupVolumes: {}, lines: [] }
);
const serviceGroups = computed(() => [...new Set(draft.value.lines.map(line => line.serviceGroup).filter(Boolean))]);
const visibleDraftLines = computed(() =>
  careTypeFilter.value === "all"
    ? draft.value.lines
    : draft.value.lines.filter(line => line.careType === careTypeFilter.value)
);
const quotaVersionLabel = computed(() =>
  draft.value.frozenQuota && draft.value.quotaVersionCode
    ? `定额 ${draft.value.quotaVersionCode}${draft.value.quotaEffectiveDate ? `（${draft.value.quotaEffectiveDate} 起）` : ""}`
    : "未冻结定额"
);
const materialOptions = computed(() =>
  [...new Set(props.items.map(item => item.name).filter(Boolean))].sort((left, right) => left.localeCompare(right, "zh-CN"))
);
const itemByName = computed(() => new Map(props.items.map(item => [item.name, item])));
const stockByItemId = computed(() => {
  const result = new Map<string, number>();
  props.batches.forEach(batch =>
    result.set(batch.itemId, Number(((result.get(batch.itemId) || 0) + Number(batch.quantity || 0)).toFixed(6)))
  );
  return result;
});
const nonNegativeInteger = (value: unknown) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.max(0, Math.floor(parsed)) : 0;
};
const currentPatientFlow = computed(() =>
  serviceGroups.value.reduce((sum, group) => sum + nonNegativeInteger(draft.value.groupVolumes[group]), 0)
);
const combinedBusinessVolume = computed(() => {
  const groups = new Set(
    draft.value.lines
      .filter(line => line.careType === "outpatient" || line.careType === "inpatient")
      .map(line => line.serviceGroup)
      .filter(Boolean)
  );
  return [...groups].reduce((sum, group) => sum + nonNegativeInteger(draft.value.groupVolumes[group]), 0);
});
const volumeFor = (line: InventoryDepartmentDraftLine) =>
  nonNegativeInteger(
    line.volumeOverride ?? (line.measurementScope === "COMBINED" ? combinedBusinessVolume.value : draft.value.groupVolumes[line.serviceGroup]) ?? 0
  );
const calculateReferenceQuantity = (line: InventoryDepartmentDraftLine, volume: number) =>
  Math.max(0, Number(((Number(line.standardQuantity || 0) * volume + Number(line.manualAdjustment || 0)).toFixed(6))));
const actualQuantityFor = (line: InventoryDepartmentDraftLine) =>
  line.actualQuantity === null || line.actualQuantity === undefined ? null : Math.max(0, Number(line.actualQuantity || 0));
const previewRows = computed<PreviewRow[]>(() =>
  draft.value.lines.map(line => {
    const volume = volumeFor(line);
    const referenceQuantity = calculateReferenceQuantity(line, volume);
    const actualQuantity = actualQuantityFor(line);
    const actualFilled = actualQuantity !== null;
    const dailyQuantity = actualQuantity ?? 0;
    const monthlyQuantity = Number((dailyQuantity * draft.value.monthDays).toFixed(2));
    const monthlyAmount =
      !actualFilled || line.unitPrice === null || line.unitPrice === undefined
        ? null
        : Number((monthlyQuantity * line.unitPrice).toFixed(2));
    const item = itemByName.value.get(line.materialName);
    return {
      ...line,
      volume,
      referenceQuantity,
      actualFilled,
      dailyQuantity,
      monthlyQuantity,
      monthlyAmount,
      stock: item ? stockByItemId.value.get(item.id) || 0 : null
    };
  })
);
const visiblePreviewRows = computed(() =>
  careTypeFilter.value === "all"
    ? previewRows.value
    : previewRows.value.filter(row => row.careType === careTypeFilter.value)
);
const quantitySummary = (field: "dailyQuantity" | "monthlyQuantity") => {
  const totals = new Map<string, number>();
  visiblePreviewRows.value.forEach(row => {
    const unit = row.unit.trim();
    if (!row.actualFilled || !unit) return;
    totals.set(unit, Number(((totals.get(unit) || 0) + row[field]).toFixed(6)));
  });
  return [...totals.entries()].map(([unit, quantity]) => `${formatQuantity(quantity)} ${unit}`).join(" · ");
};
const dailyQuantitySummary = computed(() => quantitySummary("dailyQuantity"));
const referenceQuantitySummary = computed(() => {
  const totals = new Map<string, number>();
  visiblePreviewRows.value.forEach(row => {
    const unit = row.unit.trim();
    if (!unit) return;
    totals.set(unit, Number(((totals.get(unit) || 0) + row.referenceQuantity).toFixed(6)));
  });
  return [...totals.entries()].map(([unit, quantity]) => `${formatQuantity(quantity)} ${unit}`).join(" · ");
});
const excludedQuantityLineCount = computed(
  () => visiblePreviewRows.value.filter(row => !row.actualFilled || !row.unit.trim()).length
);
const pricedMonthlyAmount = computed(() => {
  const reportedRows = visiblePreviewRows.value.filter(row => row.actualFilled);
  if (!reportedRows.length || reportedRows.some(row => row.monthlyAmount === null)) return null;
  return Number(reportedRows.reduce((sum, row) => sum + Number(row.monthlyAmount || 0), 0).toFixed(2));
});
const allocationLines = computed(() => allocation.value?.lines || []);
const allocationByMaterialUnit = computed(
  () => new Map(allocationLines.value.map(line => [`${line.materialName}\u0000${line.unit}`, line]))
);
const allocationLineFor = (row: { materialName?: string; unit?: string }) =>
  allocationByMaterialUnit.value.get(`${row.materialName || ""}\u0000${row.unit || ""}`);
const allocationDailyRemaining = (row: { materialName?: string; unit?: string; dailyQuantity?: number }) => {
  const line = allocationLineFor(row);
  return (line?.allocatedQuantity || 0) + (line?.manualAdjustment || 0) - (row.dailyQuantity || 0);
};
const allocationTagType = (status?: InventoryDepartmentAllocationPlanLine["status"]) =>
  status === "WARNING" ? "warning" : status === "PENDING" ? "info" : "success";
const allocationStatusText = (status?: InventoryDepartmentAllocationPlanLine["status"]) =>
  status === "WARNING" ? "预警" : status === "PENDING" || !status ? "待设定" : "正常";

type HistoryCell = {
  key: string;
  date: string;
  day: number;
  state: string;
  disabled: boolean;
  ariaLabel: string;
  classes: Record<string, boolean>;
};

const draftHistory = ref(new Map<string, InventoryDepartmentDraftHistoryDay>());
const historyMonth = ref("");
const historyLoading = ref(false);
const bringingLast = ref(false);
const historyWeekdayLabels = ["一", "二", "三", "四", "五", "六", "日"];
const pad2 = (value: number) => String(value).padStart(2, "0");
const monthOf = (date: Date) => `${date.getFullYear()}-${pad2(date.getMonth() + 1)}`;

const loadHistory = async (month: string) => {
  if (!template.value || !/^\d{4}-\d{2}$/.test(month)) return;
  const [year, monthIndex] = month.split("-").map(Number);
  const from = `${month}-01`;
  const to = `${month}-${pad2(new Date(year, monthIndex, 0).getDate())}`;
  historyLoading.value = true;
  try {
    const response = await getInventoryDepartmentDailyDraftHistoryApi({ departmentKey: template.value.key, from, to });
    const merged = new Map(draftHistory.value);
    response.data.days.forEach(day => merged.set(day.businessDate, day));
    draftHistory.value = merged;
  } catch {
    // 历史加载失败不影响当日填报，仅日历缺少标记，切换月份时会重试。
  } finally {
    historyLoading.value = false;
  }
};

const loadHistoryAround = (today: string) => {
  if (!today) return;
  historyMonth.value = today.slice(0, 7);
  void loadHistory(historyMonth.value);
  const [year, month] = historyMonth.value.split("-").map(Number);
  void loadHistory(monthOf(new Date(year, month - 2, 1)));
};

const shiftHistoryMonth = (offset: number) => {
  if (!historyMonth.value) return;
  const [year, month] = historyMonth.value.split("-").map(Number);
  historyMonth.value = monthOf(new Date(year, month - 1 + offset, 1));
  void loadHistory(historyMonth.value);
};

const resetHistoryMonth = () => {
  loadHistoryAround(props.today);
};

const historyCells = computed<HistoryCell[]>(() => {
  if (!historyMonth.value) return [];
  const [year, month] = historyMonth.value.split("-").map(Number);
  const leadBlanks = (new Date(year, month - 1, 1).getDay() + 6) % 7;
  const daysInMonth = new Date(year, month, 0).getDate();
  const cells: HistoryCell[] = [];
  for (let index = 0; index < leadBlanks; index++) {
    cells.push({ key: `blank-${index}`, date: "", day: 0, state: "", disabled: true, ariaLabel: "", classes: { "is-blank": true } });
  }
  for (let day = 1; day <= daysInMonth; day++) {
    const date = `${historyMonth.value}-${pad2(day)}`;
    const record = draftHistory.value.get(date);
    const saved = Boolean(record);
    const isToday = date === props.today;
    const isFuture = date > props.today;
    cells.push({
      key: date,
      date,
      day,
      state: saved ? "已填" : isToday ? "待填" : isFuture ? "" : "缺报",
      disabled: isFuture,
      ariaLabel: saved
        ? `${date} 已填报 v${record?.revision ?? 0}，共 ${record?.lineCount ?? 0} 行`
        : `${date}${isToday ? " 今日待填" : isFuture ? " 未到填报日" : " 缺报"}`,
      classes: {
        "is-filled": saved,
        "is-missing": !saved && !isToday && !isFuture,
        "is-pending": !saved && isToday,
        "is-future": isFuture,
        "is-today": isToday,
        "is-selected": date === businessDate.value
      }
    });
  }
  return cells;
});

const historyMonthLabel = computed(() => {
  if (!historyMonth.value) return "";
  const [year, month] = historyMonth.value.split("-").map(Number);
  return `${year} 年 ${month} 月`;
});

const historyMonthStats = computed(() => {
  let filled = 0;
  let missing = 0;
  if (historyMonth.value) {
    const [year, month] = historyMonth.value.split("-").map(Number);
    const daysInMonth = new Date(year, month, 0).getDate();
    for (let day = 1; day <= daysInMonth; day++) {
      const date = `${historyMonth.value}-${pad2(day)}`;
      if (date > props.today) continue;
      if (draftHistory.value.has(date)) filled++;
      else if (date < props.today) missing++;
    }
  }
  return { filled, missing };
});

const lastFilledDate = computed(() => {
  let latest = "";
  draftHistory.value.forEach((_record, date) => {
    if (date < businessDate.value && date > latest) latest = date;
  });
  return latest || null;
});

const todayPending = computed(() => businessDate.value === props.today && !draft.value.revision);

const selectHistoryDate = (cell: HistoryCell) => {
  if (cell.disabled || !cell.date || cell.date === businessDate.value) return;
  businessDate.value = cell.date;
};

const applyLastFilledDraft = async () => {
  if (!template.value || !lastFilledDate.value || bringingLast.value) return;
  bringingLast.value = true;
  try {
    const response = await getInventoryDepartmentDailyDraftApi({ departmentKey: template.value.key, date: lastFilledDate.value });
    const source = response.data;
    if (!source.exists) {
      ElMessage.warning("上次填报记录不存在，请刷新后重试");
      return;
    }
    const blank = blankState(template.value);
    draft.value = {
      monthDays: source.monthDays || template.value.monthDays,
      revision: 0,
      groupVolumes: { ...blank.groupVolumes, ...(source.groupVolumes || {}) },
      lines: (source.lines || []).map(line => ({ ...normalizeSavedDraftLine(line), specialDailyNote: "" })),
      templateVersion: blank.templateVersion,
      frozenQuota: blank.frozenQuota
    };
    ElMessage.success(`已带入 ${lastFilledDate.value} 的填报值（${(source.lines || []).length} 行），核对后请保存`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "带入上次填报失败");
  } finally {
    bringingLast.value = false;
  }
};

const restoreTemplate = () => {
  if (!template.value) return;
  draft.value = blankState(template.value);
  ElMessage.info("已恢复原始表格模板，保存后才会覆盖当前日草稿");
};

const normalizeGroupVolume = (group: string) => {
  draft.value.groupVolumes[group] = nonNegativeInteger(draft.value.groupVolumes[group]);
};

const normalizeLineVolume = (line: { volumeOverride?: number | null }) => {
  if (line.volumeOverride !== null && line.volumeOverride !== undefined)
    line.volumeOverride = nonNegativeInteger(line.volumeOverride);
};

const normalizeMonthDays = () => {
  draft.value.monthDays = Math.min(31, Math.max(1, nonNegativeInteger(draft.value.monthDays)));
};

const addLine = () => {
  const group = serviceGroups.value[0] || "门诊患者";
  if (!(group in draft.value.groupVolumes)) draft.value.groupVolumes[group] = 0;
  const supplemental = isInventoryPortal && !canEditQuota.value;
  draft.value.lines.push({
    id: `${supplemental ? "supplement" : "manual"}-${Date.now()}-${draft.value.lines.length}`,
    serviceGroup: supplemental ? "" : group,
    careType: "other",
    materialName: "",
    unit: "",
    standardQuantity: null,
    unitPrice: null,
    volumeOverride: null,
    manualAdjustment: 0,
    actualQuantity: null,
    isSupplemental: supplemental
  });
};

const openExpandedEditor = () => {
  editorOpen.value = true;
};
const closeExpandedEditor = () => {
  editorOpen.value = false;
};

const removeLine = (lineId: string) => {
  const index = draft.value.lines.findIndex(line => line.id === lineId);
  if (index < 0) return;
  if (isInventoryPortal && !canEditQuota.value && !draft.value.lines[index].isSupplemental) {
    ElMessage.warning("科室只能删除自己新增的补充行");
    return;
  }
  draft.value.lines.splice(index, 1);
};
const formatQuantity = (value: number) => Number(value || 0).toLocaleString("zh-CN", { maximumFractionDigits: 6 });
const formatMoney = (value: number) =>
  `¥${Number(value || 0).toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

const loadDraft = async () => {
  if (!template.value || !businessDate.value) return;
  const requestKey = `${template.value.key}:${businessDate.value}`;
  latestLoadKey.value = requestKey;
  try {
    const response = await getInventoryDepartmentDailyDraftApi({ departmentKey: template.value.key, date: businessDate.value });
    if (latestLoadKey.value !== requestKey) return;
    const saved = response.data;
    if (!saved.exists) {
      draft.value = blankState(template.value);
      return;
    }
    draft.value = {
      monthDays: saved.monthDays || template.value.monthDays,
      revision: saved.revision || 0,
      groupVolumes: { ...blankState(template.value).groupVolumes, ...(saved.groupVolumes || {}) },
      lines: (saved.lines || []).map(normalizeSavedDraftLine),
      templateVersion: saved.templateVersion,
      quotaVersionId: saved.quotaVersionId,
      quotaVersionCode: saved.quotaVersionCode,
      quotaEffectiveDate: saved.quotaEffectiveDate,
      frozenQuota: saved.frozenQuota
    };
  } catch (error) {
    if (latestLoadKey.value !== requestKey) return;
    ElMessage.error(error instanceof Error ? error.message : "读取科室日草稿失败");
  }
};

const saveDraft = async () => {
  if (!template.value) return;
  if (isInventoryPortal && !canEditQuota.value) {
    const invalid = draft.value.lines.find(
      line =>
        line.isSupplemental &&
        (!line.materialName.trim() || !line.unit.trim() || !line.careType || line.actualQuantity == null)
    );
    if (invalid) {
      ElMessage.warning("科室补充行必须填写耗材名称、单位、服务类型和实际数量");
      return;
    }
    draft.value.lines.forEach(line => {
      if (line.isSupplemental) {
        line.standardQuantity = null;
        line.manualAdjustment = 0;
      }
    });
  }
  const specialWithoutNote = draft.value.lines.find(
    line => line.isSpecial && Number(line.actualQuantity || 0) > 0 && !line.specialDailyNote?.trim()
  );
  if (specialWithoutNote) {
    ElMessage.warning(`${specialWithoutNote.materialName || "该耗材"}为特殊耗材，填写非零实际量时必须说明当日情况`);
    return;
  }
  saving.value = true;
  try {
    const response = await saveInventoryDepartmentDailyDraftApi({
      departmentKey: template.value.key,
      departmentName: template.value.department,
      businessDate: businessDate.value,
      templateVersion: draft.value.templateVersion || "xlsx-20260808",
      quotaVersionId: draft.value.quotaVersionId,
      quotaVersionCode: draft.value.quotaVersionCode,
      quotaEffectiveDate: draft.value.quotaEffectiveDate,
      frozenQuota: draft.value.frozenQuota,
      monthDays: draft.value.monthDays,
      revision: draft.value.revision,
      groupVolumes: draft.value.groupVolumes,
      lines: draft.value.lines
    });
    const saved = response.data;
    draft.value = {
      monthDays: saved.monthDays || draft.value.monthDays,
      revision: saved.revision || 0,
      groupVolumes: { ...draft.value.groupVolumes, ...(saved.groupVolumes || {}) },
      lines: (saved.lines || []).map(normalizeSavedDraftLine),
      templateVersion: saved.templateVersion,
      quotaVersionId: saved.quotaVersionId,
      quotaVersionCode: saved.quotaVersionCode,
      quotaEffectiveDate: saved.quotaEffectiveDate,
      frozenQuota: saved.frozenQuota
    };
    ElMessage.success("科室耗材日草稿已保存，未扣减库存");
    const mergedHistory = new Map(draftHistory.value);
    mergedHistory.set(businessDate.value, {
      businessDate: businessDate.value,
      revision: saved.revision || 0,
      lineCount: (saved.lines || []).length,
      filledCount: (saved.lines || []).filter(line => Number(line.actualQuantity || 0) > 0).length
    });
    draftHistory.value = mergedHistory;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存科室日草稿失败");
  } finally {
    saving.value = false;
  }
};

const exportPeriod = async (format: "xlsx" | "csv") => {
  if (!template.value || !businessDate.value) return;
  exporting.value = format;
  try {
    ElMessage.info("周/月报只统计已保存的科室日草稿；当前未保存编辑值不会写入报表。");
    const { blob, filename } = await downloadInventoryDepartmentPeriodReportApi({
      departmentKey: template.value.key,
      periodType: reportPeriod.value,
      anchorDate: businessDate.value,
      format
    });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
    URL.revokeObjectURL(url);
    ElMessage.success("周/月报已导出；文件包含汇总和每日审计，不扣减库存。");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "科室周/月报导出失败");
  } finally {
    exporting.value = "";
  }
};

const openAllocationPlan = async () => {
  if (!template.value || !businessDate.value) return;
  allocationOpen.value = true;
  allocationLoading.value = true;
  try {
    const response = await getInventoryDepartmentAllocationPlanApi({
      departmentKey: template.value.key,
      month: businessDate.value.slice(0, 7),
      throughDate: businessDate.value
    });
    const saved = response.data;
    const planLines = saved.lines.map(line => ({ ...line, warningThreshold: line.warningThreshold ?? undefined }));
    const existing = new Set(planLines.map(line => `${line.materialName}\u0000${line.unit}`));
    const currentUsage = new Map((saved.usage || []).map(line => [`${line.materialName}\u0000${line.unit}`, line.quantity]));
    const previousUsage = new Map((saved.previousUsage || []).map(line => [`${line.materialName}\u0000${line.unit}`, line.quantity]));
    planLines.forEach(line => {
      line.previousMonthSuggestedQuantity = line.previousMonthSuggestedQuantity ?? previousUsage.get(`${line.materialName}\u0000${line.unit}`) ?? 0;
    });
    previewRows.value.forEach(row => {
      const key = `${row.materialName}\u0000${row.unit}`;
      if (!row.materialName || !row.unit || existing.has(key)) return;
      existing.add(key);
      planLines.push({
        materialName: row.materialName,
        unit: row.unit,
        allocatedQuantity: 0,
        sourceType: "MANUAL",
        manualAdjustment: 0,
        warningThreshold: undefined,
        suggestedWarningThreshold: Number(((currentUsage.get(key) || 0) / Math.max(1, Number(businessDate.value.slice(8, 10))) * 3).toFixed(6)),
        previousMonthSuggestedQuantity: previousUsage.get(key) || 0,
        monthUsedQuantity: currentUsage.get(key) || 0,
        monthRemainingQuantity: -(currentUsage.get(key) || 0),
        status: "PENDING"
      });
    });
    allocation.value = { ...saved, lines: planLines };
  } catch (error) {
    allocationOpen.value = false;
    ElMessage.error(error instanceof Error ? error.message : "读取下拨计划失败");
  } finally {
    allocationLoading.value = false;
  }
};

const saveAllocationPlan = async () => {
  if (!allocation.value) return;
  allocationSaving.value = true;
  try {
    const response = await saveInventoryDepartmentAllocationPlanApi({
      ...allocation.value,
      lines: allocation.value.lines.map(line => ({
        ...line,
        allocatedQuantity: Math.max(0, Number(line.allocatedQuantity || 0)),
        manualAdjustment: Number(line.manualAdjustment || 0),
        warningThreshold: line.warningThreshold === undefined ? null : Math.max(0, Number(line.warningThreshold || 0))
      }))
    });
    allocation.value = response.data;
    ElMessage.success("下拨计划已保存，仅用于核对和预警，未写入库存流水。");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存下拨计划失败");
  } finally {
    allocationSaving.value = false;
  }
};

const clamp = (value: number, minimum: number, maximum: number) => Math.min(Math.max(value, minimum), maximum);
const stopResize = () => {
  resizeCleanup?.();
  resizeCleanup = undefined;
  if (isResizing.value) isResizing.value = false;
  document.body.style.userSelect = "";
};
const startResize = (event: PointerEvent) => {
  const grid = workspaceGridRef.value;
  if (!grid || !window.matchMedia("(min-width: 1241px)").matches) return;
  event.preventDefault();
  const rect = grid.getBoundingClientRect();
  const minInput = 360;
  const minPreview = 420;
  const divider = 10;
  const updateWidth = (clientX: number) => {
    const maximum = Math.max(minInput, rect.width - minPreview - divider);
    inputPaneWidth.value = clamp(clientX - rect.left, minInput, maximum);
  };
  const onPointerMove = (moveEvent: PointerEvent) => updateWidth(moveEvent.clientX);
  const onPointerUp = () => {
    stopResize();
    try {
      if (inputPaneWidth.value) localStorage.setItem(workspaceWidthKey, String(Math.round(inputPaneWidth.value)));
    } catch {
      // 本机存储不可用时不影响拖动和保存草稿。
    }
  };
  isResizing.value = true;
  document.body.style.userSelect = "none";
  updateWidth(event.clientX);
  window.addEventListener("pointermove", onPointerMove);
  window.addEventListener("pointerup", onPointerUp, { once: true });
  resizeCleanup = () => {
    window.removeEventListener("pointermove", onPointerMove);
    window.removeEventListener("pointerup", onPointerUp);
  };
};

onMounted(() => {
  try {
    const saved = Number(localStorage.getItem(workspaceWidthKey));
    if (Number.isFinite(saved) && saved >= 360) inputPaneWidth.value = saved;
  } catch {
    // 本机存储不可用时使用默认比例。
  }
});
onBeforeUnmount(stopResize);
watch(
  () => props.today,
  today => {
    if (today && businessDate.value !== today) businessDate.value = today;
  },
  { immediate: true }
);

watch(
  [() => props.departmentKey, businessDate],
  ([key]) => {
    if (key) void loadDraft();
  },
  { immediate: true }
);

watch(
  () => props.departmentKey,
  key => {
    draftHistory.value = new Map();
    if (key) loadHistoryAround(props.today);
  },
  { immediate: true }
);
</script>

<style scoped lang="scss">
.department-consumption-workspace {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.department-toolbar,
.toolbar-actions,
.pane-heading {
  display: flex;
  align-items: center;
  gap: 12px;
}
.department-toolbar,
.pane-heading {
  justify-content: space-between;
  flex-wrap: wrap;
}
.patient-flow-hint {
  margin: 4px 0 0;
  color: var(--inventory-muted);
  font-size: 12px;
}
.patient-flow-total {
  display: grid;
  min-width: 112px;
  padding: 7px 10px;
  color: #075985;
  text-align: right;
  background: #f0f9ff;
  border: 1px solid #bae6fd;
  border-radius: 6px;
}
.patient-flow-total small { font-size: 12px; }
.patient-flow-total strong { font-size: 22px; font-variant-numeric: tabular-nums; }
.department-toolbar h2,
.pane-heading h3 {
  margin: 0;
  color: var(--inventory-text);
}
.department-toolbar h2 {
  font-size: 20px;
}
.pane-heading h3 {
  font-size: 16px;
}
.input-pane-secondary-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.input-pane-tools {
  display: inline-flex;
  gap: 8px;
}
.toolbar-actions {
  flex-wrap: wrap;
}
.report-period-select {
  width: 108px;
}
.department-workspace-grid {
  display: grid;
  grid-template-columns: minmax(520px, var(--input-pane-width, 58%)) 10px minmax(360px, 1fr);
  gap: 0;
  min-width: 0;
}
.workspace-resizer {
  position: relative;
  min-width: 10px;
  cursor: col-resize;
  touch-action: none;
}
.workspace-resizer::after {
  position: absolute;
  top: 12px;
  bottom: 12px;
  left: 4px;
  width: 2px;
  content: "";
  background: var(--inventory-line);
  border-radius: 2px;
  opacity: .75;
  transition: background .16s ease, opacity .16s ease;
}
.workspace-resizer:hover::after,
.department-workspace-grid.is-resizing .workspace-resizer::after {
  background: var(--el-color-primary);
  opacity: 1;
}
.department-workspace-grid.is-resizing,
.department-workspace-grid.is-resizing * {
  cursor: col-resize !important;
}
.input-pane,
.preview-pane {
  display: grid;
  gap: 10px;
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--inventory-line);
  border-radius: 6px;
  background: #fff;
}

.editor-filter {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--inventory-muted);
  font-size: 12px;
}
.care-type-filter {
  width: 116px;
}
.readonly-quantity {
  display: inline-flex;
  min-height: 28px;
  align-items: center;
  color: var(--inventory-text);
  font-variant-numeric: tabular-nums;
}
.special-material-marker {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 5px;
  color: var(--inventory-muted);
  font-size: 12px;
  line-height: 1.35;
}
.special-material-marker span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.special-note-placeholder {
  color: var(--inventory-muted);
  font-size: 12px;
}
:deep(.actual-consumable-header) {
  color: #b45309;
  background: #fff7ed !important;
}
.input-table :deep(.el-table__body td:nth-child(6)) {
  background: #fffbeb;
}

.volume-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 12px;
  max-height: 150px;
  overflow: auto;
  padding-right: 4px;
}
.volume-field {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px;
  align-items: center;
  gap: 8px;
  color: var(--inventory-text);
  font-size: 13px;
}
.volume-field span { display: grid; gap: 2px; }
.volume-field b { color: #075985; font-size: 11px; font-weight: 600; }
.volume-field :deep(.el-input-number) {
  width: 100%;
}
.month-days {
  border-top: 1px solid var(--inventory-line);
  padding-top: 8px;
}
.care-type-select {
  margin-top: 6px;
}
.input-table :deep(.el-input-number),
.input-table :deep(.el-select) {
  width: 100%;
}
.preview-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-bottom: 1px solid var(--inventory-line);
}
.preview-summary span {
  display: grid;
  gap: 2px;
  min-width: 0;
  padding: 0 10px 8px;
  border-right: 1px solid var(--inventory-line);
}
.preview-summary span:first-child { padding-left: 0; }
.preview-summary span:last-child { padding-right: 0; border-right: 0; }
.preview-summary small { color: var(--inventory-muted); font-size: 12px; }
.preview-summary strong { overflow: hidden; color: var(--inventory-text); font-size: 15px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.preview-summary .unit-summary {
  font-size: 14px;
  line-height: 1.45;
  word-break: break-word;
}
.draft-state {
  color: var(--inventory-muted);
  font-size: 13px;
}
.allocation-cell {
  display: grid;
  gap: 3px;
  color: var(--inventory-text);
  font-size: 12px;
}
.allocation-plan-note {
  margin-bottom: 12px;
  padding: 10px 12px;
  color: var(--inventory-muted);
  font-size: 13px;
  line-height: 1.55;
  background: #fbfdfd;
  border: 1px solid var(--inventory-line);
  border-radius: 6px;
}
.allocation-dialog-state {
  margin-right: auto;
  color: var(--inventory-muted);
  font-size: 12px;
}
:global(.allocation-plan-dialog .el-dialog__footer) {
  display: flex;
  align-items: center;
}
.editor-dialog-shell {
  display: grid;
  gap: 10px;
}
.editor-dialog-toolbar,
.editor-dialog-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}
.editor-dialog-footer {
  justify-content: flex-end;
}
.editor-volume-grid {
  max-height: 126px;
  padding: 10px 12px;
  border: 1px solid var(--inventory-line);
  border-radius: 6px;
  background: #fbfdfd;
}
.editor-table-wrap {
  min-width: 0;
  overflow-x: auto;
  border: 1px solid var(--inventory-line);
  border-radius: 6px;
}
.expanded-input-table {
  min-width: 1120px;
}
:global(.consumption-editor-dialog .el-dialog__body) {
  padding: 0 16px 10px;
}
:global(.consumption-editor-dialog .el-dialog__footer) {
  position: relative;
  z-index: 100;
  padding-top: 0;
  background: #fff;
  pointer-events: auto;
}
.editor-dialog-actions {
  position: relative;
  z-index: 101;
  display: flex;
  gap: 8px;
  pointer-events: auto;
}
@media (max-width: 1240px) {
  .department-workspace-grid {
    grid-template-columns: 1fr !important;
  }
  .workspace-resizer {
    display: none;
  }
}
@media (max-width: 680px) {
  .input-pane-secondary-actions,
  .editor-dialog-toolbar,
  .editor-dialog-footer {
    align-items: stretch;
    flex-direction: column;
  }
  .volume-grid,
  .preview-summary {
    grid-template-columns: 1fr;
  }
  .preview-summary span {
    border-right: 0;
    border-bottom: 1px solid var(--inventory-line);
  }
  .preview-summary span:last-child {
    border-bottom: 0;
  }
}

/* 展开编辑：强调色 + hover 轻反馈 */
.expand-editor-btn {
  transition:
    transform 220ms ease-out,
    box-shadow 220ms ease-out,
    background-color 220ms ease-out,
    border-color 220ms ease-out,
    color 220ms ease-out;

  &:not(.is-disabled):hover {
    transform: translateY(-1px);
    box-shadow: 0 5px 14px rgb(15 118 110 / 14%);
  }
}

/* 今日未填报提醒横幅 */
.fill-reminder {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  background: rgb(255 251 235 / 72%);
  border: 1px solid rgb(217 169 82 / 32%);
  border-radius: 10px;
  animation: reminder-in 420ms ease-out backwards;
}
.reminder-dot {
  flex: 0 0 auto;
  width: 8px;
  height: 8px;
  background: #f59e0b;
  border-radius: 50%;
  box-shadow: 0 0 0 4px rgb(245 158 11 / 14%);
}
.reminder-text {
  display: grid;
  gap: 2px;
  min-width: 0;

  strong {
    color: #92600a;
    font-size: 13px;
    font-weight: 600;
  }

  span {
    color: var(--inventory-muted);
    font-size: 12px;
  }
}
@keyframes reminder-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 填报历史日历 */
.draft-history-card {
  display: grid;
  gap: 12px;
  padding: 16px;
  background: linear-gradient(135deg, rgb(236 253 245 / 40%), rgb(255 255 255 / 90%)), #fff;
  border: 1px solid rgb(20 184 166 / 16%);
  border-radius: 10px;
  box-shadow: 0 1px 2px rgb(23 33 43 / 2%);
  animation: reminder-in 420ms ease-out 60ms backwards;
}
.history-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
}
.history-eyebrow {
  color: #0f8f82;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
}
.history-title {
  margin: 3px 0 0;
  color: var(--inventory-text);
  font-size: 17px;
  font-weight: 500;
}
.history-sub {
  margin: 4px 0 0;
  color: var(--inventory-muted);
  font-size: 12px;

  b {
    color: #0b7a63;
    font-variant-numeric: tabular-nums;
  }

  .history-missing-num {
    color: #b4552d;
  }
}
.history-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.history-weekdays,
.history-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 6px;
}
.history-weekdays span {
  color: var(--inventory-muted);
  font-size: 12px;
  font-weight: 600;
  text-align: center;
}
.history-day {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: space-between;
  min-height: 52px;
  min-width: 0;
  padding: 6px 8px;
  color: var(--inventory-text);
  text-align: left;
  background: #fbfcfd;
  border: 1px solid #eef2f5;
  border-radius: 8px;
  cursor: pointer;
  transition:
    transform 220ms ease-out,
    box-shadow 220ms ease-out,
    border-color 220ms ease-out,
    background-color 220ms ease-out;

  &:not(:disabled):hover {
    background: #f2fbf7;
    border-color: #46b89d;
    box-shadow: 0 6px 16px rgb(15 118 110 / 12%);
    transform: translateY(-1px);
  }

  &.is-blank {
    visibility: hidden;
    pointer-events: none;
  }

  &.is-filled {
    background: #edf9f3;
    border-color: #c9ecdd;

    .history-day-state {
      color: #0b7a63;
    }
  }

  &.is-missing {
    background: #fdf4f0;
    border-color: #f2d8cb;

    .history-day-state {
      color: #b4552d;
    }
  }

  &.is-pending {
    background: #fff8ec;
    border-color: #efdda9;

    .history-day-state {
      color: #b45309;
    }
  }

  &.is-future {
    color: var(--inventory-muted);
    cursor: default;

    &:hover {
      background: #fbfcfd;
      border-color: #eef2f5;
      box-shadow: none;
      transform: none;
    }
  }

  &.is-today {
    box-shadow: 0 0 0 2px rgb(245 158 11 / 34%);
  }

  &.is-selected {
    border-color: #0f9f8f;
    box-shadow: 0 0 0 2px rgb(15 118 110 / 22%);
  }
}
.history-day-num {
  font-size: 14px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.history-day-state {
  overflow: hidden;
  max-width: 100%;
  color: var(--inventory-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.history-legend {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
  color: var(--inventory-muted);
  font-size: 12px;

  span {
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }
}
.legend-chip {
  width: 16px;
  height: 10px;
  border: 1px solid #e3e9ee;
  border-radius: 3px;

  &.is-filled {
    background: #edf9f3;
    border-color: #c9ecdd;
  }

  &.is-missing {
    background: #fdf4f0;
    border-color: #f2d8cb;
  }

  &.is-pending {
    background: #fff8ec;
    border-color: #efdda9;
  }
}
.history-legend-note {
  margin-left: auto;
}

@media (prefers-reduced-motion: reduce) {
  .fill-reminder,
  .draft-history-card,
  .history-day,
  .expand-editor-btn {
    animation: none;
    transition: none;
  }
}
</style>
