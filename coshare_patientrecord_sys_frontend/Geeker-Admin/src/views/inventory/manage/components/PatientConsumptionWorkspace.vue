<template>
  <section class="patient-workspace" v-loading="loading">
    <header class="patient-toolbar">
      <div class="toolbar-copy">
        <span class="eyebrow">患者耗材 · {{ departmentName || "本科室" }}</span>
        <h2>患者耗材录入</h2>
        <p>班后补录：选患者 → 选耗材 → 下一位；最后统一提交。</p>
      </div>
      <div class="toolbar-actions">
        <div class="date-strip">
          <button
            v-for="day in recentDays"
            :key="day.value"
            type="button"
            class="date-chip"
            :class="{ 'is-active': businessDate === day.value }"
            @click="changeDate(day.value)"
          >
            {{ day.label }}
          </button>
          <el-date-picker
            v-model="businessDate"
            type="date"
            value-format="YYYY-MM-DD"
            :clearable="false"
            :disabled="loading"
            class="date-picker-compact"
            @change="changeDate"
          />
        </div>
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
        <el-button type="primary" :loading="batchSubmitting" @click="openBatchSubmit">批量提交</el-button>
        <el-button type="primary" plain :icon="Search" @click="openPatientSource">患者来源</el-button>
      </div>
    </header>

    <div class="status-strip">
      <span class="status-progress">已录 <b>{{ summary.entered || 0 }}</b> / <b>{{ summary.total || 0 }}</b></span>
      <span><b>{{ summary.notStarted || 0 }}</b> 未录</span>
      <span><b>{{ summary.draft || 0 }}</b> 草稿</span>
      <span><b>{{ summary.submitted || 0 }}</b> 待审</span>
      <span><b>{{ summary.returned || 0 }}</b> 驳回</span>
      <span><b>{{ summary.approved || 0 }}</b> 通过</span>
      <span v-if="businessDate !== todayValue" class="backfill-badge">补录模式</span>
      <div class="status-progress-bar" aria-hidden="true">
        <span :style="{ width: `${progress}%` }"></span>
      </div>
    </div>

    <DepartmentFillCalendar
      class="workspace-calendar"
      wide
      :month="calendarMonth"
      :today="todayValue"
      :selected-date="businessDate"
      mode="PATIENT"
      :days="calendarDays"
      :today-new-patients="todayNewPatients"
      :loading="calendarLoading"
      @update:month="changeCalendarMonth"
      @select-date="selectCalendarDate"
    />

    <div class="patient-layout">
      <aside class="patient-panel">
        <div class="patient-panel-head">
          <el-input
            v-model="patientKeyword"
            placeholder="搜索患者 / 就诊号"
            clearable
            :prefix-icon="Search"
          />
          <el-select v-model="statusFilter" class="status-filter" aria-label="筛选患者状态">
            <el-option label="全部状态" value="ALL" />
            <el-option label="未录入优先" value="NOT_STARTED" />
            <el-option label="草稿" value="DRAFT" />
            <el-option label="待审核" value="SUBMITTED" />
            <el-option label="已驳回" value="RETURNED" />
            <el-option label="已通过" value="APPROVED" />
          </el-select>
        </div>

        <div class="patient-list" role="list">
          <button
            v-for="patient in filteredPatients"
            :key="patient.serviceInstanceId"
            type="button"
            class="patient-card"
            :class="{ 'is-active': selectedId === patient.serviceInstanceId }"
            role="listitem"
            @click="selectPatient(patient)"
          >
            <span class="patient-card-main">
              <span class="patient-name">{{ patient.patientName || "未命名患者" }}</span>
              <span class="patient-meta">
                {{ patient.serviceName || "服务" }}
                <i aria-hidden="true">·</i>
                {{ maskVisitNo(patient.visitNo) }}
              </span>
            </span>
            <span class="patient-card-side">
              <el-tag v-if="patient.attentionFlag" type="danger" size="small" effect="plain">重点</el-tag>
              <el-tag :type="statusType(patient.status)" size="small" effect="plain">
                {{ statusLabel(patient.status) }}
              </el-tag>
              <small>{{ patient.lineCount || 0 }} 项</small>
            </span>
          </button>
          <el-empty
            v-if="!filteredPatients.length"
            :image-size="70"
            description="当前筛选下没有患者；可手动补录"
          />
        </div>
      </aside>

      <main v-if="detail" class="detail-panel">
        <header class="detail-head">
          <div class="detail-title-row">
            <div>
              <div class="detail-name-row">
                <h3>{{ detail.patientName || "未命名患者" }}</h3>
                <el-tag :type="statusType(detail.status)" effect="plain">{{ statusLabel(detail.status) }}</el-tag>
                <el-tag v-if="detail.attentionFlag" type="danger" effect="plain">重点</el-tag>
                <el-tag v-if="detail.sourceType === 'MANUAL'" type="warning" effect="plain">手动补录</el-tag>
              </div>
              <p class="detail-meta">
                {{ detail.serviceName || "服务" }}
                <span>·</span>
                {{ maskVisitNo(detail.visitNo) }}
                <button type="button" class="link-button" @click="showFullVisitNo = !showFullVisitNo">
                  {{ showFullVisitNo ? "隐藏" : "显示完整" }}
                </button>
                <span>·</span>
                {{ formatDateTime(detail.serviceStartedAt) }}
                <span v-if="detail.stageCode">· {{ detail.stageCode }}</span>
              </p>
              <p v-if="detail.specialNote" class="patient-note">备注：{{ detail.specialNote }}</p>
            </div>
            <div class="detail-actions">
              <el-button size="small" @click="openHistory">历史</el-button>
              <el-button size="small" @click="openNoteDialog">{{ detail.attentionFlag ? "已标记" : "备注/标记" }}</el-button>
              <template v-if="editable">
                <el-button size="small" :disabled="!previousPatient" @click="copyPreviousPatient">同上一患者</el-button>
                <el-button size="small" @click="applyLastCombination">套用上次组合</el-button>
              </template>
              <el-tag v-else-if="detail.status === 'SUBMITTED'" type="warning" effect="plain">已提交，等待库管审核</el-tag>
              <el-tag v-else-if="detail.status === 'APPROVED'" type="success" effect="plain">已审核通过</el-tag>
              <el-tag v-else-if="detail.status === 'PARTIALLY_APPROVED'" type="warning" effect="plain">部分通过，可补交驳回项</el-tag>
              <el-tag v-else type="danger" effect="plain">已驳回，请修改后重提</el-tag>
            </div>
          </div>
        </header>

        <el-alert
          v-if="detail.status === 'RETURNED' || detail.status === 'PARTIALLY_APPROVED'"
          class="review-alert"
          type="warning"
          :closable="false"
          show-icon
          title="库管已处理该清单"
          :description="rejectedSummary"
        />

        <section class="selection-summary">
          <div class="selection-summary-head">
            <div>
              <h4>已选耗材</h4>
              <p>{{ selectedLines.length }} 项 · 数量默认取预设，可改</p>
            </div>
            <el-button type="primary" size="large" :icon="Plus" :disabled="!editable" @click="openConsumableDrawer">
              选择耗材
            </el-button>
          </div>
          <div v-if="selectedLines.length" class="selection-preview">
            <span v-for="line in selectedLines" :key="line.itemId" class="preview-chip">
              {{ line.itemName }} <b>{{ formatQuantity(line.actualQuantity) }}{{ line.unit }}</b>
            </span>
          </div>
          <el-empty
            v-else
            :image-size="72"
            description="还没有选耗材；点“选择耗材”从常用、最近或全部预设中添加"
          />
          <div class="selection-hints">
            <span v-if="commonItems.length">常用 {{ commonItems.length }} 项</span>
            <span v-if="recentItems.length">最近使用 {{ recentItems.length }} 项</span>
            <span v-if="dedupedPresetItems.length">全部预设 {{ dedupedPresetItems.length }} 项</span>
            <span v-if="detail.noConsumption" class="is-muted">已标记未使用耗材</span>
          </div>
          <div class="selection-actions">
            <el-button :disabled="!editable" @click="openAddItemDialog">追加预设外耗材</el-button>
            <el-button text :disabled="!editable || !selectedLines.length" @click="clearUnapproved">全部未使用</el-button>
          </div>
        </section>

        <footer class="sticky-actions">
          <span class="save-state">{{ saveState || "未保存" }}</span>
          <el-button
            type="primary"
            size="large"
            :loading="saving"
            :disabled="!editable"
            @click="submitAndNext"
          >
            提交并下一位
          </el-button>
          <el-button
            plain
            size="large"
            :loading="saving"
            :disabled="!editable || hasApprovedLines"
            @click="noConsumptionAndNext"
          >
            未使用并下一位
          </el-button>
          <el-button text size="large" :loading="saving" :disabled="!editable" @click="saveAndNext">
            仅保存
          </el-button>
        </footer>
      </main>

      <main v-else class="detail-panel detail-empty">
        <el-empty :image-size="96" description="选择左侧患者开始填写耗材清单" />
      </main>
    </div>

    <el-drawer
      v-model="drawerVisible"
      direction="rtl"
      size="82%"
      :with-header="false"
      append-to-body
      class="consumable-drawer"
      @opened="focusSearch"
    >
      <div class="drawer-shell">
        <header class="drawer-head">
          <div>
            <span class="eyebrow">为 {{ detail?.patientName || "患者" }} 选择耗材</span>
            <h3>{{ detail?.serviceName || "服务" }} · 已选 {{ selectedLines.length }} 项</h3>
          </div>
          <div class="drawer-head-actions">
            <span class="drawer-hint">/ 搜索 · Enter 添加第一项 · Esc 关闭</span>
            <el-button circle text :icon="Close" aria-label="关闭耗材选择" @click="drawerVisible = false" />
          </div>
        </header>
        <div class="drawer-grid">
          <section class="drawer-left">
                    <section class="selected-section">
                      <div class="section-title">
                        <h4>已选耗材</h4>
                        <span>{{ selectedLines.length }} 项 · 数量默认取预设，可改</span>
                      </div>
                      <div
                        v-for="line in selectedLines"
                        :key="line.itemId"
                        class="selected-row"
                        :class="{ 'is-approved': line.reviewStatus === 'APPROVED' }"
                      >
                        <span class="selected-name">
                          <strong>{{ line.itemName }}</strong>
                          <small>{{ line.unit }}<template v-if="line.exceptionReason"> · {{ line.exceptionReason }}</template></small>
                        </span>
                        <div class="quantity-control">
                          <el-button size="small" :disabled="line.reviewStatus === 'APPROVED'" @click="adjustLineQuantity(line, -1)">−</el-button>
                          <el-input-number
                            :model-value="line.actualQuantity"
                            :min="0.01"
                            :precision="2"
                            :step="1"
                            size="small"
                            :disabled="line.reviewStatus === 'APPROVED'"
                            controls-position="right"
                            @update:model-value="value => setLineQuantity(line, value)"
                          />
                          <el-button size="small" :disabled="line.reviewStatus === 'APPROVED'" @click="adjustLineQuantity(line, 1)">+</el-button>
                          <button
                            v-for="quick in [1, 2, 3]"
                            :key="quick"
                            type="button"
                            class="qty-chip"
                            :disabled="line.reviewStatus === 'APPROVED'"
                            @click="setLineQuantity(line, quick)"
                          >
                            {{ quick }}
                          </button>
                        </div>
                        <span class="selected-state">
                          <el-tag v-if="line.reviewStatus === 'APPROVED'" type="success" size="small" effect="plain">已通过</el-tag>
                          <el-tag v-else-if="line.reviewStatus === 'REJECTED'" type="danger" size="small" effect="plain">已驳回</el-tag>
                          <small v-if="line.reviewNote">{{ line.reviewNote }}</small>
                        </span>
                        <el-button text type="danger" :disabled="line.reviewStatus === 'APPROVED'" @click="removeLine(line.itemId)">移除</el-button>
                      </div>
                      <el-empty v-if="!selectedLines.length" :image-size="64" description="还没有选耗材；用搜索、常用或最近使用添加" />
                    </section>

                    <section class="package-section">
                      <div class="section-title">
                        <h4>患者套餐</h4>
                        <span>保存当前组合，后续一键套用</span>
                      </div>
                      <el-button
                        size="small"
                        :loading="packageSaving"
                        :disabled="!editable || !selectedLines.length"
                        @click="saveCurrentAsPackage"
                      >
                        保存当前为套餐
                      </el-button>
                      <div v-if="patientPackages.length" class="package-list">
                        <div v-for="pkg in patientPackages" :key="pkg.id" class="package-row">
                          <span class="package-name">
                            {{ pkg.name }}
                            <small>{{ pkg.items.length }} 项</small>
                          </span>
                          <el-button size="small" text type="primary" :disabled="!editable" @click="applyPackage(pkg)">套用</el-button>
                          <el-button size="small" text type="danger" @click="deletePackage(pkg)">删除</el-button>
                        </div>
                      </div>
                      <p v-else class="package-empty">还没有保存的患者套餐</p>
                    </section>
          </section>
          <section class="drawer-right">
                    <div class="detail-toolbar">
                      <el-input
                        ref="searchInputRef"
                        v-model="itemKeyword"
                        class="item-search"
                        placeholder="搜索耗材名称 / 拼音首字母 / 别名"
                        aria-label="搜索耗材"
                        clearable
                        :prefix-icon="Search"
                        @keyup.enter.prevent="addFirstVisibleItem"
                      />
                      <el-button :icon="Plus" :disabled="!editable" @click="openAddItemDialog">追加预设外耗材</el-button>
                      <el-button text :disabled="!editable || !selectedLines.length" @click="clearUnapproved">全部未使用</el-button>
                      <span class="line-counter">已选 {{ selectedLines.length }} 项</span>
                    </div>

                    <div class="drawer-mode">
                      <button
                        type="button"
                        class="mode-tab"
                        :class="{ 'is-active': drawerMode === 'QUICK' && !itemKeyword }"
                        @click="drawerMode = 'QUICK'; itemKeyword = ''"
                      >
                        快捷选择
                      </button>
                      <button
                        type="button"
                        class="mode-tab"
                        :class="{ 'is-active': drawerMode === 'ALL' || itemKeyword }"
                        @click="drawerMode = 'ALL'"
                      >
                        全部预设
                      </button>
                      <span v-if="itemKeyword" class="mode-count">搜索到 {{ searchCount }} 项</span>
                      <span v-else class="mode-count">{{ dedupedPresetItems.length }} 项可选用</span>
                    </div>

                    <section v-if="!showFullPresetList && commonItems.length" class="quick-section">
                      <div class="section-title"><h4>常用</h4><span>点一下加入，不默认勾选</span></div>
                      <div class="chip-row">
                        <button
                          v-for="item in commonItems"
                          :key="item.itemId"
                          type="button"
                          class="quick-chip"
                          :class="{ 'is-selected': isChecked(item) }"
                          @click="toggleItem(item, !isChecked(item))"
                        >
                          {{ item.materialName || item.itemName }}
                          <small>{{ formatQuantity(item.presetQuantity) }}{{ item.unit }}</small>
                        </button>
                      </div>
                    </section>

                    <section v-if="!showFullPresetList && recentItems.length" class="quick-section">
                      <div class="section-title"><h4>最近使用</h4><span>当前账号近 30 天</span></div>
                      <div class="chip-row">
                        <button
                          v-for="item in recentItems"
                          :key="item.itemId"
                          type="button"
                          class="quick-chip is-recent"
                          :class="{ 'is-selected': Boolean(lineState[item.itemId]) }"
                          @click="addRecentItem(item)"
                        >
                          {{ item.itemName }}
                          <small>{{ item.unit }} · 用过 {{ item.usageCount }} 次</small>
                        </button>
                      </div>
                    </section>

                    <div v-if="!showFullPresetList" class="quick-browse">
                      <p v-if="!commonItems.length && !recentItems.length">还没有常用项和最近使用记录。</p>
                      <el-button type="primary" plain @click="drawerMode = 'ALL'">
                        浏览全部预设（{{ dedupedPresetItems.length }} 项）
                      </el-button>
                    </div>

                    <div v-if="showFullPresetList" class="checklist-scroll">
                      <section v-for="group in visibleGroups" :key="group.name" class="checklist-group">
                        <header class="group-head">
                          <h4>{{ group.name }}</h4>
                          <span>{{ group.items.length }} 项</span>
                        </header>
                        <div
                          v-for="item in group.items"
                          :key="item.itemId"
                          class="checklist-row"
                          :class="{ 'is-checked': isChecked(item), 'is-approved': isApproved(item) }"
                        >
                          <el-checkbox
                            :model-value="isChecked(item)"
                            :disabled="!editable || isApproved(item)"
                            :aria-label="`使用 ${item.itemName}`"
                            @change="value => toggleItem(item, Boolean(value))"
                          />
                          <div class="item-copy">
                            <div class="item-name-row">
                              <strong>{{ item.materialName || item.itemName }}</strong>
                              <el-tag v-if="item.commonItem" size="small" type="success" effect="plain">常用</el-tag>
                              <el-tag v-if="item.mappingConflict" size="small" type="warning" effect="plain">映射冲突</el-tag>
                              <el-tag v-if="isApproved(item)" size="small" type="info" effect="plain">已通过</el-tag>
                              <el-tag v-else-if="lineState[item.itemId]?.reviewStatus === 'REJECTED'" size="small" type="danger" effect="plain">已驳回</el-tag>
                            </div>
                            <span class="item-meta">
                              {{ item.unit }}
                              <i aria-hidden="true">·</i>
                              预设 {{ formatQuantity(item.presetQuantity) }} {{ item.unit }}
                              <template v-if="item.serviceGroups?.length || item.serviceGroup">
                                <i aria-hidden="true">·</i>
                                {{ item.serviceGroups?.join(" / ") || item.serviceGroup }}
                              </template>
                            </span>
                            <span v-if="item.mappingConflict" class="mapping-hint">
                              实际物资：{{ item.mappedItemNames.join(" / ") }}
                            </span>
                            <span v-if="lineState[item.itemId]?.reviewNote" class="review-note">
                              审核意见：{{ lineState[item.itemId]?.reviewNote }}
                            </span>
                          </div>
                          <el-input-number
                            v-if="isChecked(item)"
                            :model-value="lineState[item.itemId]?.actualQuantity"
                            :min="0.01"
                            :precision="2"
                            :step="1"
                            :disabled="!editable || isApproved(item)"
                            controls-position="right"
                            size="small"
                            @update:model-value="value => updateQuantity(item, value)"
                          />
                          <span v-else class="preset-ghost">{{ formatQuantity(item.presetQuantity) }} {{ item.unit }}</span>
                        </div>
                      </section>



                      <el-empty
                        v-if="!visibleGroups.length && !manualLines.length"
                        :image-size="80"
                        :description="itemKeyword ? '没有匹配耗材，试试别名或拼音首字母' : '该科室还没有已映射到物资主数据的预设耗材，请先完成规则配置'"
                      />
                    </div>


          </section>
        </div>
        <footer class="drawer-footer">
          <span>已选 {{ selectedLines.length }} 项</span>
          <el-button @click="drawerVisible = false">完成选择</el-button>
        </footer>
      </div>
    </el-drawer>

    <el-dialog v-model="manualDialogVisible" title="手动补录患者" width="720px" class="patient-dialog">
      <div class="manual-search-row">
        <el-input
          v-model="manualKeyword"
          placeholder="输入患者姓名 / 就诊号"
          clearable
          :prefix-icon="Search"
          @keyup.enter="searchManualPatients"
        />
        <el-button :loading="manualSearching" @click="searchManualPatients">搜索患者</el-button>
      </div>
      <div class="manual-results">
        <button
          v-for="patient in manualResults"
          :key="patient.encounterId"
          type="button"
          class="manual-result"
          :class="{ 'is-active': manualSelected?.encounterId === patient.encounterId }"
          @click="manualSelected = patient"
        >
          <strong>{{ patient.patientName || "未命名患者" }}</strong>
          <span>{{ patient.visitNo || "无就诊号" }} · {{ patient.departmentName || "未归属科室" }}</span>
          <small>{{ patient.visitDate || "" }}</small>
        </button>
        <el-empty v-if="!manualResults.length" :image-size="60" description="输入关键词后搜索患者" />
      </div>
      <el-form label-position="top" class="manual-form">
        <el-form-item label="服务项目">
          <el-input v-model="manualServiceName" placeholder="例如：理疗、检查、手术" />
        </el-form-item>
        <el-form-item label="服务开始时间">
          <el-date-picker
            v-model="manualStartedAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :clearable="false"
          />
        </el-form-item>
        <el-form-item label="补录原因">
          <el-input v-model="manualNote" placeholder="例如：临床系统未生成服务事件、临时加做" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="manualSaving" :disabled="!manualSelected" @click="saveManualPatient">
          加入工作台
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="addItemDialogVisible" title="追加预设外耗材" width="560px" class="patient-dialog">
      <el-form label-position="top">
        <el-form-item label="耗材">
          <el-input
            v-model="manualItemKeyword"
            placeholder="搜索名称 / 拼音首字母 / 别名"
            clearable
            :prefix-icon="Search"
          />
          <div v-if="!manualItemSelected" class="manual-item-results">
            <button
              v-for="item in manualItemResults"
              :key="item.itemId"
              type="button"
              class="manual-item-option"
              @click="selectManualItem(item)"
            >
              <span>
                <strong>{{ item.itemName }}</strong>
                <small>{{ item.unit }} · {{ item.category || "物资" }}</small>
              </span>
              <el-tag size="small" effect="plain">选择</el-tag>
            </button>
            <el-empty v-if="!manualItemResults.length" :image-size="50" description="没有匹配耗材" />
          </div>
          <div v-else class="manual-item-selected">
            <span>
              <strong>{{ manualItemSelected.itemName }}</strong>
              <small>{{ manualItemSelected.unit }}</small>
            </span>
            <el-button text type="primary" @click="clearManualItem">重新选择</el-button>
          </div>
        </el-form-item>
        <el-form-item label="实际用量">
          <el-input-number v-model="addItemQuantity" :min="0.01" :precision="2" :step="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="追加原因">
          <el-input v-model="addItemReason" placeholder="必填，例如：患者临时增加耗材" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addItemDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="addManualLine">加入清单</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="sourceDrawerVisible" title="患者来源" size="70%" append-to-body>
      <div class="source-shell">
        <div class="source-toolbar">
          <el-input
            v-model="sourceKeyword"
            placeholder="姓名 / 就诊号 / 病例标识"
            clearable
            :prefix-icon="Search"
            @keyup.enter="loadPatientSource(true)"
          />
          <el-select v-model="sourceDepartment" clearable filterable placeholder="全院科室" @change="loadPatientSource(true)">
            <el-option v-for="department in sourceDepartments" :key="department" :label="department" :value="department" />
          </el-select>
          <el-select v-model="sourceVisitType" clearable placeholder="全部就诊情况" @change="loadPatientSource(true)">
            <el-option label="门诊" value="门诊" />
            <el-option label="住院" value="住院" />
          </el-select>
          <el-date-picker
            v-model="sourceDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="录入日期"
            clearable
            @change="loadPatientSource(true)"
          />
          <el-button :icon="Refresh" :loading="sourceLoading" @click="loadPatientSource(true)">刷新</el-button>
          <el-button @click="openManualDialog">手动新增</el-button>
          <el-switch v-model="sourceOnlyUnadded" active-text="只看未加入" />
        </div>
        <p class="source-summary">
          全院 {{ sourceTotal }} 位患者<span v-if="sourceDepartment"> · {{ sourceDepartment }}</span>；当前显示
          {{ visibleSourceRows.length }} 位未加入患者，点击整行即可加入。
        </p>
        <div v-loading="sourceLoading" class="source-list">
          <div
            v-for="row in visibleSourceRows"
            :key="row.patientId + '-' + (row.encounterId || '')"
            class="source-card"
            :class="{ 'is-added': isSourceAdded(row) }"
            @click="addPatientFromSource(row)"
          >
            <div class="source-main">
              <strong>{{ row.patientName }}</strong>
              <span>
                <em v-if="row.departmentName" class="source-department">{{ row.departmentName }}</em>
                {{ row.caseToken || row.visitNo }} · {{ row.encounterVisitType || row.visitType }} ·
                {{ row.encounterVisitDate || row.visitDate }}
              </span>
              <small>{{ row.currentStage || row.status || "未记录阶段" }} · {{ row.encounterCount }} 次就诊</small>
            </div>
            <el-button
              v-if="row.encounterId"
              size="small"
              :type="isSourceAdded(row) ? 'success' : 'primary'"
              :plain="isSourceAdded(row)"
              :loading="sourceAddingId === row.encounterId"
              @click.stop="addPatientFromSource(row)"
            >
              {{ isSourceAdded(row) ? "已在工作台" : "加入本科室" }}
            </el-button>
            <el-tag v-else size="small" type="info" effect="plain">无就诊记录</el-tag>
          </div>
          <el-empty v-if="!visibleSourceRows.length && !sourceLoading" description="当前没有未加入的患者">
            <el-button v-if="sourceRows.length" text type="primary" @click="sourceOnlyUnadded = false">
              显示已加入患者
            </el-button>
          </el-empty>
        </div>
        <div v-if="sourceRows.length < sourceTotal" class="source-more">
          <el-button :loading="sourceLoading" @click="loadMoreSource">加载更多</el-button>
        </div>
      </div>
    </el-drawer>

    <el-dialog v-model="noteDialogVisible" title="患者备注与标记" width="520px">
      <el-form label-position="top">
        <el-form-item label="重点标记">
          <el-switch v-model="noteForm.attentionFlag" active-text="重点患者" inactive-text="普通" />
        </el-form-item>
        <el-form-item label="特殊情况备注">
          <el-input
            v-model="noteForm.specialNote"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            placeholder="例如：术后特殊情况、耗材使用需复核、家属沟通事项"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="noteDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="noteSaving" @click="savePatientNote">保存备注</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="historyDrawerVisible" :title="`${historyPatientName} · 历史耗材`" size="70%" append-to-body>
      <div v-loading="historyLoading" class="history-shell">
        <article v-for="instance in patientHistory" :key="instance.serviceInstanceId" class="history-card">
          <header>
            <div>
              <strong>{{ instance.businessDate }}</strong>
              <span>{{ instance.departmentName }} · {{ instance.serviceName }}</span>
            </div>
            <el-tag :type="statusType(instance.status)" size="small" effect="plain">{{ statusLabel(instance.status) }}</el-tag>
          </header>
          <p v-if="instance.specialNote" class="history-note">备注：{{ instance.specialNote }}</p>
          <div v-for="line in instance.lines" :key="line.lineId" class="history-line">
            <span>{{ line.itemName }} × {{ formatQuantity(line.actualQuantity) }}{{ line.unit }}</span>
            <el-tag
              size="small"
              effect="plain"
              :type="line.reviewStatus === 'APPROVED' ? 'success' : line.reviewStatus === 'REJECTED' ? 'danger' : 'warning'"
            >
              {{ line.reviewStatus === "APPROVED" ? "已通过" : line.reviewStatus === "REJECTED" ? "已驳回" : "待审核" }}
            </el-tag>
          </div>
          <p v-if="!instance.lines.length" class="history-empty">未使用耗材</p>
        </article>
        <el-empty v-if="!patientHistory.length && !historyLoading" description="暂无历史记录" />
      </div>
    </el-drawer>

    <el-dialog v-model="batchDialogVisible" title="批量提交审核" width="640px">
      <div class="batch-summary">
        <div><small>今日患者</small><strong>{{ workbench.summary.total || 0 }}</strong></div>
        <div><small>已录/未使用</small><strong>{{ (workbench.summary.total || 0) - (workbench.summary.notStarted || 0) }}</strong></div>
        <div><small>未录入</small><strong class="danger">{{ workbench.summary.notStarted || 0 }}</strong></div>
        <div><small>已驳回</small><strong class="danger">{{ workbench.summary.returned || 0 }}</strong></div>
      </div>
      <el-alert
        v-if="batchMissing.length"
        type="warning"
        :closable="false"
        show-icon
        title="还有患者未录入，不能批量提交"
        :description="`${batchMissing.length} 位患者尚未录入耗材。请先补录或标记未使用。`"
      />
      <div v-if="batchMissing.length" class="batch-missing">
        <button
          v-for="patient in batchMissing"
          :key="patient.serviceInstanceId"
          type="button"
          class="missing-row"
          @click="selectPatient(patient); batchDialogVisible = false"
        >
          <span>{{ patient.patientName || "未命名患者" }}</span>
          <small>{{ maskVisitNo(patient.visitNo) }} · {{ patient.serviceName || "服务" }}</small>
          <el-tag size="small" type="warning" effect="plain">去补录</el-tag>
        </button>
      </div>
      <div v-if="batchResult" class="batch-result">
        <p>已提交 {{ batchResult.submitted }} 位患者。</p>
        <p v-if="batchResult.failed?.length" class="danger">
          失败 {{ batchResult.failed.length }} 位：{{ batchResult.failed.map(item => `${item.patientName}（${item.reason}）`).join("；") }}
        </p>
      </div>
      <template #footer>
        <el-button @click="batchDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchSubmitting" :disabled="!canSubmitBatch" @click="submitBatch">
          提交当天全部患者
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Close, Plus, Refresh, Search } from "@element-plus/icons-vue";
import dayjs from "dayjs";
import { pinyin } from "pinyin-pro";
import DepartmentFillCalendar from "./DepartmentFillCalendar.vue";
import {
  addInventoryManualPatientApi,
  deleteInventoryPatientPackageApi,
  getInventoryDepartmentDailyReportCalendarApi,
  getInventoryPatientDetailApi,
  getInventoryPatientLastCombinationApi,
  getInventoryPatientPackagesApi,
  getInventoryPatientRecentItemsApi,
  getInventoryPatientSourceApi,
  getInventoryPatientTraceApi,
  getInventoryPatientWorkbenchApi,
  saveInventoryPatientDocumentApi,
  saveInventoryPatientNoteApi,
  saveInventoryPatientPackageApi,
  searchInventoryPatientsApi,
  submitInventoryPatientBatchApi,
  submitInventoryPatientDocumentApi,
  type InventoryDepartmentDailyReportCalendarDay,
  type InventoryPatientAvailableItem,
  type InventoryPatientBatchSubmitResult,
  type InventoryPatientConsumablePackage,
  type InventoryPatientConsumptionLine,
  type InventoryPatientDetail,
  type InventoryPatientPresetItem,
  type InventoryPatientRecentItems,
  type InventoryPatientSearchResult,
  type InventoryPatientSourceRow,
  type InventoryPatientWorkbench,
  type InventoryPatientWorkbenchRow
} from "@/api/modules/inventory";

interface EditableLine {
  itemId: string;
  itemName: string;
  unit: string;
  ruleId?: string;
  presetQuantity?: number;
  actualQuantity: number;
  source: "COMMON" | "PRESET" | "MANUAL";
  exceptionReason?: string;
  lineId?: string;
  reviewStatus?: "PENDING" | "APPROVED" | "REJECTED";
  reviewNote?: string;
  lineRevision?: number;
}

interface DisplayItem extends InventoryPatientPresetItem {
  serviceGroups: string[];
  mappingConflict: boolean;
  mappedItemNames: string[];
}

const props = defineProps<{
  departmentKey: string;
  today?: string;
}>();

const emptyWorkbench = (): InventoryPatientWorkbench => ({
  departmentKey: props.departmentKey,
  departmentName: "",
  businessDate: props.today || dayjs().format("YYYY-MM-DD"),
  cutoverDate: "",
  patients: [],
  summary: { total: 0, draft: 0, submitted: 0, approved: 0, returned: 0, notStarted: 0 }
});

const todayValue = props.today || dayjs().format("YYYY-MM-DD");
const businessDate = ref(todayValue);
const calendarMonth = ref(dayjs(businessDate.value).format("YYYY-MM-01"));
const calendarDays = ref<InventoryDepartmentDailyReportCalendarDay[]>([]);
const todayNewPatients = ref(0);
const calendarLoading = ref(false);
const workbench = ref<InventoryPatientWorkbench>(emptyWorkbench());
const detail = ref<InventoryPatientDetail | null>(null);
const selectedId = ref("");
const loading = ref(false);
const saving = ref(false);
const submitting = ref(false);
const batchSubmitting = ref(false);
const patientKeyword = ref("");
const statusFilter = ref("NOT_STARTED");
const itemKeyword = ref("");
const lineState = reactive<Record<string, EditableLine>>({});
const recentItems = ref<InventoryPatientRecentItems["items"]>([]);
const patientPackages = ref<InventoryPatientConsumablePackage[]>([]);
const packageSaving = ref(false);
const saveState = ref("");
const showFullVisitNo = ref(false);
const drawerVisible = ref(false);
const drawerMode = ref<"QUICK" | "ALL">("QUICK");
const searchInputRef = ref<{ focus: () => void } | null>(null);
const batchDialogVisible = ref(false);
const batchResult = ref<InventoryPatientBatchSubmitResult | null>(null);
const sourceDrawerVisible = ref(false);
const sourceKeyword = ref("");
const sourceVisitType = ref("");
const sourceDepartment = ref("");
const sourceDepartments = ref<string[]>([]);
const sourceDate = ref(todayValue);
const sourceRows = ref<InventoryPatientSourceRow[]>([]);
const sourceTotal = ref(0);
const sourcePage = ref(1);
const sourceOnlyUnadded = ref(true);
const sourceLoading = ref(false);
const sourceAddingId = ref("");
const noteDialogVisible = ref(false);
const noteSaving = ref(false);
const noteForm = reactive({ specialNote: "", attentionFlag: false });
const historyDrawerVisible = ref(false);
const historyLoading = ref(false);
const historyPatientName = ref("");
const patientHistory = ref<InventoryPatientDetail[]>([]);
const manualItemKeyword = ref("");
const manualItemSelected = ref<InventoryPatientAvailableItem | null>(null);

const manualDialogVisible = ref(false);
const manualKeyword = ref("");
const manualSearching = ref(false);
const manualSaving = ref(false);
const manualResults = ref<InventoryPatientSearchResult["list"]>([]);
const manualSelected = ref<InventoryPatientSearchResult["list"][number] | null>(null);
const manualServiceName = ref("手动补录");
const manualStartedAt = ref(dayjs().format("YYYY-MM-DDTHH:mm:ss"));
const manualNote = ref("");

const addItemDialogVisible = ref(false);
const addItemId = ref("");
const addItemQuantity = ref(1);
const addItemReason = ref("");

const departmentName = computed(() => workbench.value.departmentName || detail.value?.departmentName || "");
const editable = computed(() =>
  detail.value ? ["DRAFT", "RETURNED", "PARTIALLY_APPROVED", "NOT_STARTED"].includes(detail.value.status) : false
);
const selectedLines = computed(() => Object.values(lineState));
const manualLines = computed(() => selectedLines.value.filter(line => line.source === "MANUAL"));

const filteredPatients = computed(() => {
  const keyword = patientKeyword.value.trim().toLowerCase();
  return workbench.value.patients.filter(patient => {
    if (statusFilter.value !== "ALL") {
      if (statusFilter.value === "SUBMITTED") {
        if (!["SUBMITTED", "PARTIALLY_APPROVED"].includes(patient.status)) return false;
      } else if (patient.status !== statusFilter.value) {
        return false;
      }
    }
    if (!keyword) return true;
    return [patient.patientName, patient.visitNo, patient.serviceName, patient.encounterId]
      .filter(Boolean)
      .some(value => String(value).toLowerCase().includes(keyword));
  });
});

const presetItems = computed(() => detail.value?.presetItems?.items ?? []);
const dedupedPresetItems = computed<DisplayItem[]>(() => {
  const map = new Map<string, DisplayItem>();
  for (const item of presetItems.value) {
    const key = `${item.materialName || item.itemName}|${item.unit || ""}`;
    const existing = map.get(key);
    if (!existing) {
      map.set(key, {
        ...item,
        serviceGroups: item.serviceGroup ? [item.serviceGroup] : [],
        mappingConflict: false,
        mappedItemNames: [item.itemName]
      });
      continue;
    }
    if (item.serviceGroup && !existing.serviceGroups.includes(item.serviceGroup)) {
      existing.serviceGroups.push(item.serviceGroup);
    }
    existing.commonItem = existing.commonItem || item.commonItem;
    existing.aliases = unique([...(existing.aliases ?? []), ...(item.aliases ?? [])]);
    existing.mappedItemNames = unique([...existing.mappedItemNames, item.itemName]);
    existing.mappingConflict = existing.mappedItemNames.length > 1;
    if (item.matchesService && !existing.matchesService) {
      existing.itemId = item.itemId;
      existing.ruleId = item.ruleId;
      existing.itemName = item.itemName;
      existing.presetQuantity = item.presetQuantity;
      existing.matchesService = true;
    } else if (existing.presetQuantity == null && item.presetQuantity != null) {
      existing.presetQuantity = item.presetQuantity;
    }
  }
  return Array.from(map.values());
});

const visibleGroups = computed(() => {
  const keyword = itemKeyword.value.trim().toLowerCase();
  const filtered = dedupedPresetItems.value.filter(item => {
    if (!keyword) return true;
    const initials = [item.itemName, item.materialName]
      .map(text => String(pinyin(text || "", { pattern: "first", toneType: "none" })))
      .join(" ");
    return [item.itemName, item.materialName, ...(item.serviceGroups ?? []), item.unit, ...(item.aliases ?? []), initials]
      .filter(Boolean)
      .some(value => String(value).toLowerCase().includes(keyword));
  });
  const groups = new Map<string, DisplayItem[]>();
  filtered
    .filter(item => item.commonItem)
    .forEach(item => {
      const key = "常用耗材";
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key)!.push(item);
    });
  filtered
    .filter(item => !item.commonItem)
    .forEach(item => {
      const key = item.serviceGroups[0] || item.serviceGroup || "其他预设";
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key)!.push(item);
    });
  return Array.from(groups.entries()).map(([name, items]) => ({ name, items }));
});

const commonItems = computed(() => dedupedPresetItems.value.filter(item => item.commonItem).slice(0, 8));
const hasApprovedLines = computed(() => selectedLines.value.some(line => line.reviewStatus === "APPROVED"));
const previousPatient = computed(() => {
  const index = workbench.value.patients.findIndex(patient => patient.serviceInstanceId === selectedId.value);
  return index > 0 ? workbench.value.patients[index - 1] : null;
});
const summary = computed(() => workbench.value.summary);
const progress = computed(() => {
  const total = summary.value.total || 0;
  const missing = summary.value.notStarted || 0;
  return total ? Math.round(((total - missing) * 100) / total) : 0;
});
const recentDays = computed(() =>
  Array.from({ length: 7 }, (_, index) => {
    const value = dayjs().subtract(index, "day").format("YYYY-MM-DD");
    return { value, label: index === 0 ? "今天" : index === 1 ? "昨天" : dayjs(value).format("MM-DD") };
  })
);
const showFullPresetList = computed(() => drawerMode.value === "ALL" || itemKeyword.value.trim().length > 0);
const searchCount = computed(() => visibleGroups.value.reduce((sum, group) => sum + group.items.length, 0));
const visibleSourceRows = computed(() =>
  sourceOnlyUnadded.value
    ? sourceRows.value.filter(row =>
        !workbench.value.patients.some(patient =>
          (row.encounterId && patient.encounterId === row.encounterId) || patient.patientId === row.patientId
        )
      )
    : sourceRows.value
);
const batchMissing = computed(() => workbench.value.patients.filter(patient => patient.status === "NOT_STARTED"));
const canSubmitBatch = computed(() => batchMissing.value.length === 0 && !batchSubmitting.value);

const availableItemOptions = computed(() => {
  const items = detail.value?.availableItems ?? [];
  return items.filter(item => !lineState[item.itemId]);
});

const manualItemResults = computed(() => {
  const keyword = manualItemKeyword.value.trim().toLowerCase();
  return (detail.value?.availableItems ?? [])
    .filter(item => !lineState[item.itemId] || item.itemId === addItemId.value)
    .filter(item => {
      if (!keyword) return true;
      const initials = String(pinyin(item.itemName, { pattern: "first", toneType: "none" }));
      return [item.itemName, ...(item.aliases ?? []), initials].join(" ").toLowerCase().includes(keyword);
    })
    .slice(0, 30);
});

const rejectedSummary = computed(() => {
  const rejected = selectedLines.value.filter(line => line.reviewStatus === "REJECTED");
  if (!rejected.length) return "已保留审核通过项，未通过项可修改后重新提交。";
  return `已驳回 ${rejected.length} 项：${rejected
    .map(line => `${line.itemName}${line.reviewNote ? `（${line.reviewNote}）` : ""}`)
    .join("、")}`;
});

const loadWorkbench = async (keepSelection = true) => {
  loading.value = true;
  try {
    const result = await getInventoryPatientWorkbenchApi({
      departmentKey: props.departmentKey,
      date: businessDate.value
    });
    workbench.value = result.data ?? emptyWorkbench();
    await loadRecentItems();
    await loadPackages();
    calendarMonth.value = dayjs(businessDate.value).startOf("month").format("YYYY-MM-01");
    void loadCalendar();
    if (!keepSelection || !selectedId.value) {
      const first = filteredPatients.value[0] || workbench.value.patients[0];
      if (first) await selectPatient(first);
      else {
        selectedId.value = "";
        detail.value = null;
      }
    } else {
      const current = workbench.value.patients.find(patient => patient.serviceInstanceId === selectedId.value);
      if (current) await selectPatient(current);
      else {
        selectedId.value = "";
        detail.value = null;
      }
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取患者工作台失败");
  } finally {
    loading.value = false;
  }
};

const reload = () => {
  void loadWorkbench(false);
};

const loadCalendar = async () => {
  calendarLoading.value = true;
  try {
    const result = await getInventoryDepartmentDailyReportCalendarApi({
      departmentKey: props.departmentKey,
      month: calendarMonth.value
    });
    calendarDays.value = result.data?.days ?? [];
    todayNewPatients.value = result.data?.todayNewPatients ?? 0;
  } catch {
    calendarDays.value = [];
    todayNewPatients.value = 0;
  } finally {
    calendarLoading.value = false;
  }
};

const changeCalendarMonth = (month: string) => {
  calendarMonth.value = month;
  void loadCalendar();
};

const selectCalendarDate = (date: string) => {
  if (date !== businessDate.value) void changeDate(date);
};

const loadRecentItems = async () => {
  try {
    const result = await getInventoryPatientRecentItemsApi({ departmentKey: props.departmentKey, limit: 5 });
    recentItems.value = result.data?.items ?? [];
  } catch {
    recentItems.value = [];
  }
};

const loadPackages = async () => {
  try {
    const result = await getInventoryPatientPackagesApi({ departmentKey: props.departmentKey });
    patientPackages.value = result.data?.packages ?? [];
  } catch {
    patientPackages.value = [];
  }
};

const saveCurrentAsPackage = async () => {
  if (!selectedLines.value.length) {
    ElMessage.warning("请先选择耗材");
    return;
  }
  try {
    const result = await ElMessageBox.prompt("保存当前已选耗材，后续可一键套用到其他患者。", "保存为患者套餐", {
      confirmButtonText: "保存",
      cancelButtonText: "取消",
      inputPlaceholder: "例如：检验科常规采血组合",
      inputValidator: value => (value && value.trim() ? true : "请填写套餐名称")
    });
    const name = String(result.value || "").trim();
    if (!name) return;
    packageSaving.value = true;
    const saved = await saveInventoryPatientPackageApi({
      departmentKey: props.departmentKey,
      name,
      lines: selectedLines.value.map(line => ({
        itemId: line.itemId,
        itemName: line.itemName,
        unit: line.unit,
        quantity: line.actualQuantity,
        source: line.source,
        exceptionReason: line.exceptionReason
      }))
    });
    patientPackages.value = saved.data?.packages ?? [];
    ElMessage.success("患者套餐已保存");
  } catch (error) {
    if (error === "cancel" || error === "close") return;
    ElMessage.error(error instanceof Error ? error.message : "保存患者套餐失败");
  } finally {
    packageSaving.value = false;
  }
};

const applyPackage = async (pkg: InventoryPatientConsumablePackage) => {
  if (!editable.value || !pkg.items.length) return;
  try {
    await ElMessageBox.confirm(
      `将当前未审核耗材替换为“${pkg.name}”的 ${pkg.items.length} 项内容？已审核通过的项会保留。`,
      "套用患者套餐",
      { confirmButtonText: "套用", cancelButtonText: "取消", type: "warning" }
    );
  } catch {
    return;
  }
  Object.keys(lineState).forEach(key => {
    if (lineState[key]?.reviewStatus !== "APPROVED") delete lineState[key];
  });
  pkg.items.forEach(item => {
    if (lineState[item.itemId]?.reviewStatus === "APPROVED") return;
    lineState[item.itemId] = {
      itemId: item.itemId,
      itemName: item.itemName,
      unit: item.unit,
      actualQuantity: Number(item.quantity || 1),
      source: (item.source as EditableLine["source"]) || "PRESET",
      exceptionReason: item.exceptionReason || (item.source === "MANUAL" ? "患者套餐" : undefined)
    };
  });
  ElMessage.success(`已套用“${pkg.name}”，当前清单已按套餐替换`);
};

const deletePackage = async (pkg: InventoryPatientConsumablePackage) => {
  try {
    await ElMessageBox.confirm(`删除套餐“${pkg.name}”？`, "删除患者套餐", {
      confirmButtonText: "删除",
      cancelButtonText: "取消",
      type: "warning"
    });
  } catch {
    return;
  }
  try {
    const result = await deleteInventoryPatientPackageApi(pkg.id);
    patientPackages.value = result.data?.packages ?? [];
    ElMessage.success("套餐已删除");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "删除患者套餐失败");
  }
};

const selectPatient = async (patient: InventoryPatientWorkbenchRow, skipSave = false) => {
  if (patient.serviceInstanceId === selectedId.value) return;
  if (!skipSave && detail.value && editable.value && selectedLines.value.some(line => line.reviewStatus !== "APPROVED")) {
    await persistDraft(true);
  }
  selectedId.value = patient.serviceInstanceId;
  showFullVisitNo.value = false;
  try {
    const result = await getInventoryPatientDetailApi(patient.serviceInstanceId);
    applyDetail(result.data);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取患者耗材清单失败");
  }
};

const applyDetail = (next: InventoryPatientDetail | undefined) => {
  detail.value = next ?? null;
  Object.keys(lineState).forEach(key => delete lineState[key]);
  (next?.lines ?? []).forEach((line: InventoryPatientConsumptionLine) => {
    lineState[line.itemId] = {
      itemId: line.itemId,
      itemName: line.itemName,
      unit: line.unit,
      ruleId: line.ruleId,
      presetQuantity: line.presetQuantity ?? undefined,
      actualQuantity: Number(line.actualQuantity || 0),
      source: (line.source as EditableLine["source"]) || "PRESET",
      exceptionReason: line.exceptionReason,
      lineId: line.lineId,
      reviewStatus: line.reviewStatus,
      reviewNote: line.reviewNote,
      lineRevision: line.lineRevision
    };
  });
  saveState.value = next?.status === "DRAFT" ? "已保存" : "";
};

const isChecked = (item: InventoryPatientPresetItem) => Boolean(lineState[item.itemId]);
const isApproved = (item: InventoryPatientPresetItem) => lineState[item.itemId]?.reviewStatus === "APPROVED";

const toggleItem = (item: InventoryPatientPresetItem, checked: boolean) => {
  if (!editable.value) return;
  if (isApproved(item)) return;
  if (checked && !lineState[item.itemId]) {
    lineState[item.itemId] = {
      itemId: item.itemId,
      itemName: item.materialName || item.itemName,
      unit: item.unit,
      ruleId: item.ruleId,
      presetQuantity: item.presetQuantity,
      actualQuantity: Number(item.presetQuantity || 1),
      source: item.commonItem ? "COMMON" : "PRESET"
    };
  } else if (!checked) {
    delete lineState[item.itemId];
  }
};

const updateQuantity = (item: InventoryPatientPresetItem, value: number | undefined) => {
  const line = lineState[item.itemId];
  if (!line) return;
  line.actualQuantity = Number(value || 0);
};

const updateManualQuantity = (itemId: string, value: number | undefined) => {
  const line = lineState[itemId];
  if (!line) return;
  line.actualQuantity = Number(value || 0);
};

const removeLine = (itemId: string) => {
  const line = lineState[itemId];
  if (line?.reviewStatus === "APPROVED") return;
  delete lineState[itemId];
};

const clearUnapproved = () => {
  Object.values(lineState).forEach(line => {
    if (line.reviewStatus !== "APPROVED") delete lineState[line.itemId];
  });
};

const buildPayload = (noConsumption = false) => ({
  serviceInstanceId: detail.value!.serviceInstanceId,
  revision: detail.value!.revision || 0,
  noConsumption,
  lines: noConsumption
    ? []
    : selectedLines.value
        .filter(line => line.reviewStatus !== "APPROVED")
        .map(line => ({
          itemId: line.itemId,
          itemName: line.itemName,
          unit: line.unit,
          ruleId: line.ruleId,
          presetQuantity: line.presetQuantity,
          actualQuantity: line.actualQuantity,
          source: line.source,
          exceptionReason: line.exceptionReason
        }))
});

const persistDraft = async (silent = false, noConsumption = false) => {
  if (!detail.value) return undefined;
  if (!noConsumption && selectedLines.value.filter(line => line.reviewStatus !== "APPROVED").length === 0) {
    if (!silent) ElMessage.warning("未选择耗材；如确实未使用，请点“未使用并下一位”");
    return undefined;
  }
  const result = await saveInventoryPatientDocumentApi(buildPayload(noConsumption));
  applyDetail(result.data);
  saveState.value = `已保存 ${dayjs().format("HH:mm")}`;
  await refreshWorkbenchOnly();
  void loadRecentItems();
  void loadCalendar();
  return result.data;
};

const saveDraft = async () => {
  if (!detail.value) return;
  if (selectedLines.value.some(line => line.actualQuantity <= 0)) {
    ElMessage.warning("已勾选耗材的数量必须大于零");
    return;
  }
  saving.value = true;
  try {
    await persistDraft();
    ElMessage.success("患者耗材清单已保存");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存患者耗材清单失败");
  } finally {
    saving.value = false;
  }
};

const submitDocument = async () => {
  if (!detail.value) return;
  if (selectedLines.value.some(line => line.actualQuantity <= 0)) {
    ElMessage.warning("已勾选耗材的数量必须大于零");
    return;
  }
  const message = selectedLines.value.length
    ? `确认提交 ${detail.value.patientName || "该患者"} 的 ${selectedLines.value.length} 项耗材？提交后科室不能直接修改。`
    : `确认 ${detail.value.patientName || "该患者"} 今天未使用任何耗材？`;
  try {
    await ElMessageBox.confirm(message, "提交审核", {
      confirmButtonText: "确认提交",
      cancelButtonText: "再看看",
      type: "warning"
    });
  } catch {
    return;
  }
  submitting.value = true;
  try {
    let current = detail.value;
    if (!current.documentId) {
      current = (await persistDraft()) ?? current;
    }
    const result = await submitInventoryPatientDocumentApi(current.documentId);
    applyDetail(result.data);
    await refreshWorkbenchOnly();
    void loadCalendar();
    ElMessage.success("已提交库管审核，审核通过前不会扣减库存");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "提交患者耗材清单失败");
  } finally {
    submitting.value = false;
  }
};

const setLineQuantity = (line: EditableLine, value: number | undefined) => {
  if (line.reviewStatus === "APPROVED" || !editable.value) return;
  line.actualQuantity = Math.max(0.01, Number(value || 0));
};

const adjustLineQuantity = (line: EditableLine, delta: number) => {
  setLineQuantity(line, line.actualQuantity + delta);
};

const addRecentItem = (item: InventoryPatientRecentItems["items"][number]) => {
  const preset = presetItems.value.find(entry => entry.itemId === item.itemId);
  if (preset) {
    toggleItem(preset, true);
    return;
  }
  if (lineState[item.itemId]) return;
  lineState[item.itemId] = {
    itemId: item.itemId,
    itemName: item.itemName,
    unit: item.unit,
    presetQuantity: 1,
    actualQuantity: 1,
    source: "MANUAL",
    exceptionReason: "最近使用"
  };
};

const selectNextPatient = async () => {
  const patients = workbench.value.patients;
  const index = patients.findIndex(patient => patient.serviceInstanceId === selectedId.value);
  const ordered = patients.slice(index + 1).concat(patients.slice(0, index + 1));
  const next = ordered.find(patient => ["NOT_STARTED", "DRAFT", "RETURNED"].includes(patient.status));
  if (!next) {
    ElMessage.success("今天到本科室的患者已全部录入");
    return;
  }
  await selectPatient(next, true);
};

const saveAndNext = async () => {
  const result = await persistDraft(false, false);
  if (result) await selectNextPatient();
};

const submitAndNext = async () => {
  const result = await persistDraft(false, false);
  if (!result?.documentId) return;
  try {
    await submitInventoryPatientDocumentApi(result.documentId);
    await loadWorkbench(true);
    await selectNextPatient();
    ElMessage.success("已提交库管审核");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "提交审核失败");
  }
};

const noConsumptionAndNext = async () => {
  const result = await persistDraft(false, true);
  if (!result?.documentId) return;
  try {
    await submitInventoryPatientDocumentApi(result.documentId);
    await loadWorkbench(true);
    await selectNextPatient();
    ElMessage.success("已标记未使用并提交审核");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "提交审核失败");
  }
};

const applyLinesFromDetail = (lines: InventoryPatientConsumptionLine[]) => {
  Object.keys(lineState).forEach(key => {
    if (lineState[key]?.reviewStatus !== "APPROVED") delete lineState[key];
  });
  lines.forEach(line => {
    if (line.reviewStatus === "APPROVED" || lineState[line.itemId]) return;
    lineState[line.itemId] = {
      itemId: line.itemId,
      itemName: line.itemName,
      unit: line.unit,
      presetQuantity: line.presetQuantity ?? undefined,
      actualQuantity: Number(line.actualQuantity || 1),
      source: (line.source as EditableLine["source"]) || "PRESET",
      exceptionReason: line.exceptionReason || (line.source === "MANUAL" ? "套用历史组合" : undefined),
      reviewStatus: "PENDING"
    };
  });
};

const copyPreviousPatient = async () => {
  const previous = previousPatient.value;
  if (!previous) {
    ElMessage.info("当前患者前面没有可复制的患者");
    return;
  }
  try {
    await ElMessageBox.confirm(
      `将 ${previous.patientName || "上一位患者"} 的耗材复制给当前患者？已审核通过的项不会被覆盖。`,
      "同上一患者",
      { confirmButtonText: "复制", cancelButtonText: "取消", type: "warning" }
    );
  } catch {
    return;
  }
  try {
    const result = await getInventoryPatientDetailApi(previous.serviceInstanceId);
    applyLinesFromDetail(result.data?.lines ?? []);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取上一位患者失败");
  }
};

const applyLastCombination = async () => {
  if (!detail.value) return;
  try {
    await ElMessageBox.confirm("套用该患者上一次的耗材组合？当前未保存的改动会被替换。", "套用上次组合", {
      confirmButtonText: "套用",
      cancelButtonText: "取消",
      type: "warning"
    });
  } catch {
    return;
  }
  try {
    const result = await getInventoryPatientLastCombinationApi({
      patientId: detail.value.patientId,
      departmentKey: props.departmentKey
    });
    const items = result.data?.items ?? [];
    if (!items.length) {
      ElMessage.info("该患者还没有可套用的历史组合");
      return;
    }
    applyLinesFromDetail(
      items.map(item => ({
        lineId: "",
        itemId: item.itemId,
        itemName: item.itemName,
        unit: item.unit,
        presetQuantity: null,
        actualQuantity: item.actualQuantity,
        source: item.source || "PRESET",
        reviewStatus: "PENDING",
        lineRevision: 0
      }))
    );
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取上次组合失败");
  }
};

const openBatchSubmit = async () => {
  if (detail.value && editable.value && selectedLines.value.some(line => line.reviewStatus !== "APPROVED")) {
    await persistDraft(true, false);
  }
  batchResult.value = null;
  batchDialogVisible.value = true;
};

const submitBatch = async () => {
  batchSubmitting.value = true;
  try {
    const result = await submitInventoryPatientBatchApi({
      departmentKey: props.departmentKey,
      businessDate: businessDate.value
    });
    batchResult.value = result.data ?? null;
    if (result.data?.blocked) {
      ElMessage.warning("还有患者未完成，请先补录或标记未使用");
    } else {
      ElMessage.success(`已提交 ${result.data?.submitted || 0} 位患者`);
      batchDialogVisible.value = false;
      await loadWorkbench(true);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "批量提交失败");
  } finally {
    batchSubmitting.value = false;
  }
};

const refreshWorkbenchOnly = async () => {
  const result = await getInventoryPatientWorkbenchApi({
    departmentKey: props.departmentKey,
    date: businessDate.value
  });
  workbench.value = result.data ?? emptyWorkbench();
};

const openManualDialog = () => {
  sourceDrawerVisible.value = false;
  manualDialogVisible.value = true;
  manualSelected.value = null;
  manualResults.value = [];
  manualKeyword.value = "";
  manualServiceName.value = "手动补录";
  manualStartedAt.value = dayjs().format("YYYY-MM-DDTHH:mm:ss");
  manualNote.value = "";
};

const searchManualPatients = async () => {
  manualSearching.value = true;
  try {
    const result = await searchInventoryPatientsApi(manualKeyword.value.trim());
    manualResults.value = result.data?.list ?? [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "搜索患者失败");
  } finally {
    manualSearching.value = false;
  }
};

const saveManualPatient = async () => {
  if (!manualSelected.value) return;
  manualSaving.value = true;
  try {
    const result = await addInventoryManualPatientApi({
      departmentKey: props.departmentKey,
      encounterId: manualSelected.value.encounterId,
      patientId: manualSelected.value.patientId,
      patientName: manualSelected.value.patientName,
      visitNo: manualSelected.value.visitNo,
      careType: manualSelected.value.careType,
      serviceCode: "MANUAL",
      serviceName: manualServiceName.value || "手动补录",
      serviceStartedAt: manualStartedAt.value,
      businessDate: businessDate.value,
      note: manualNote.value
    });
    manualDialogVisible.value = false;
    await refreshWorkbenchOnly();
    const added = workbench.value.patients.find(
      patient => patient.serviceInstanceId === result.data?.serviceInstanceId
    );
    if (added) await selectPatient(added);
    ElMessage.success("患者已加入本科室工作台");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "手动补录患者失败");
  } finally {
    manualSaving.value = false;
  }
};

const openPatientSource = async () => {
  sourceKeyword.value = "";
  sourceVisitType.value = "";
  sourceDepartment.value = "";
  sourceDate.value = businessDate.value;
  sourcePage.value = 1;
  sourceDrawerVisible.value = true;
  await loadPatientSource(true);
};

const loadPatientSource = async (reset = false) => {
  if (reset) {
    sourcePage.value = 1;
    sourceRows.value = [];
  }
  sourceLoading.value = true;
  try {
    const result = await getInventoryPatientSourceApi({
      keyword: sourceKeyword.value.trim() || undefined,
      visitType: sourceVisitType.value || undefined,
      department: sourceDepartment.value || undefined,
      date: sourceDate.value || undefined,
      page: sourcePage.value,
      size: 30
    });
    const list = result.data?.list ?? [];
    sourceRows.value = reset ? list : [...sourceRows.value, ...list];
    sourceTotal.value = result.data?.total ?? sourceRows.value.length;
    sourceDepartments.value = result.data?.departments ?? sourceDepartments.value;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取患者档案失败");
  } finally {
    sourceLoading.value = false;
  }
};

const loadMoreSource = async () => {
  sourcePage.value += 1;
  await loadPatientSource(false);
};

const isSourceAdded = (row: InventoryPatientSourceRow) =>
  workbench.value.patients.some(patient =>
    (row.encounterId && patient.encounterId === row.encounterId) || patient.patientId === row.patientId
  );

const addPatientFromSource = async (row: InventoryPatientSourceRow) => {
  if (!row.encounterId) {
    ElMessage.warning("该患者没有可用就诊记录");
    return;
  }
  const existing = workbench.value.patients.find(patient =>
    patient.encounterId === row.encounterId || patient.patientId === row.patientId
  );
  if (existing) {
    sourceDrawerVisible.value = false;
    await selectPatient(existing, true);
    return;
  }
  sourceAddingId.value = row.encounterId;
  try {
    const visitDate = row.encounterVisitDate || row.visitDate;
    const startedAt = visitDate ? `${visitDate.slice(0, 10)}T08:00:00` : dayjs().format("YYYY-MM-DDTHH:mm:ss");
    const visitType = row.encounterVisitType || row.visitType;
    const result = await addInventoryManualPatientApi({
      departmentKey: props.departmentKey,
      encounterId: row.encounterId,
      patientId: row.patientId,
      patientName: row.patientName,
      visitNo: row.encounterVisitNo || row.visitNo,
      careType: visitType.includes("住院") ? "inpatient" : "outpatient",
      serviceCode: "ARCHIVE",
      serviceName: "患者档案加入",
      serviceStartedAt: startedAt,
      businessDate: businessDate.value,
      note: "从患者档案加入"
    });
    sourceDrawerVisible.value = false;
    await loadWorkbench(false);
    const added = workbench.value.patients.find(patient => patient.serviceInstanceId === result.data?.serviceInstanceId);
    if (added) await selectPatient(added, true);
    ElMessage.success("患者已加入本科室工作台");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加入患者失败");
  } finally {
    sourceAddingId.value = "";
  }
};

const openNoteDialog = () => {
  if (!detail.value) return;
  noteForm.specialNote = detail.value.specialNote || "";
  noteForm.attentionFlag = Boolean(detail.value.attentionFlag);
  noteDialogVisible.value = true;
};

const savePatientNote = async () => {
  const current = detail.value;
  if (!current) return;
  noteSaving.value = true;
  try {
    const result = await saveInventoryPatientNoteApi({
      serviceInstanceId: current.serviceInstanceId,
      specialNote: noteForm.specialNote.trim(),
      attentionFlag: noteForm.attentionFlag
    });
    current.specialNote = result.data?.specialNote ?? noteForm.specialNote.trim();
    current.attentionFlag = Boolean(result.data?.attentionFlag ?? noteForm.attentionFlag);
    const row = workbench.value.patients.find(patient => patient.serviceInstanceId === current.serviceInstanceId);
    if (row) {
      row.specialNote = current.specialNote;
      row.attentionFlag = current.attentionFlag;
    }
    noteDialogVisible.value = false;
    ElMessage.success("患者备注已保存");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存患者备注失败");
  } finally {
    noteSaving.value = false;
  }
};

const openHistory = async () => {
  if (!detail.value?.patientId) return;
  historyPatientName.value = detail.value.patientName || "患者";
  historyDrawerVisible.value = true;
  historyLoading.value = true;
  try {
    const result = await getInventoryPatientTraceApi(detail.value.patientId);
    patientHistory.value = result.data?.instances ?? [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取患者历史失败");
  } finally {
    historyLoading.value = false;
  }
};

const openAddItemDialog = () => {
  addItemDialogVisible.value = true;
  addItemId.value = "";
  addItemQuantity.value = 1;
  addItemReason.value = "";
  manualItemKeyword.value = "";
  manualItemSelected.value = null;
};

const selectManualItem = (item: InventoryPatientAvailableItem) => {
  addItemId.value = item.itemId;
  manualItemSelected.value = item;
};

const clearManualItem = () => {
  addItemId.value = "";
  manualItemSelected.value = null;
};

const addManualLine = () => {
  const item = manualItemSelected.value;
  if (!item) {
    ElMessage.warning("请先选择耗材");
    return;
  }
  if (!addItemReason.value.trim()) {
    ElMessage.warning("追加预设外耗材必须填写原因");
    return;
  }
  if (lineState[item.itemId]) {
    ElMessage.warning("该耗材已在清单中");
    return;
  }
  if ((detail.value?.lines ?? []).some(line => line.itemId === item.itemId && line.reviewStatus === "APPROVED")) {
    ElMessage.warning("该耗材已审核通过，不能重复添加");
    return;
  }
  lineState[item.itemId] = {
    itemId: item.itemId,
    itemName: item.itemName,
    unit: item.unit,
    actualQuantity: Number(addItemQuantity.value || 1),
    source: "MANUAL",
    exceptionReason: addItemReason.value.trim()
  };
  addItemDialogVisible.value = false;
};

const statusLabel = (status: string) => {
  switch (status) {
    case "NOT_STARTED":
      return "未开始";
    case "DRAFT":
      return "草稿";
    case "SUBMITTED":
      return "待审核";
    case "PARTIALLY_APPROVED":
      return "部分通过";
    case "RETURNED":
      return "已驳回";
    case "APPROVED":
      return "已通过";
    default:
      return status || "未知";
  }
};

const statusType = (status: string): "success" | "warning" | "danger" | "info" => {
  switch (status) {
    case "APPROVED":
      return "success";
    case "RETURNED":
      return "danger";
    case "SUBMITTED":
    case "PARTIALLY_APPROVED":
      return "warning";
    default:
      return "info";
  }
};

const formatQuantity = (value?: number | null) => {
  if (value === null || value === undefined) return "1";
  return Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
};

const formatDateTime = (value?: string) => {
  if (!value) return "未记录服务时间";
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format("MM-DD HH:mm") : value;
};

const focusSearch = () => {
  searchInputRef.value?.focus();
};

const openConsumableDrawer = () => {
  drawerMode.value = "QUICK";
  itemKeyword.value = "";
  drawerVisible.value = true;
};

const addFirstVisibleItem = () => {
  if (!itemKeyword.value.trim()) return;
  const first = visibleGroups.value[0]?.items[0];
  if (first && !isChecked(first)) toggleItem(first, true);
};

const changeDate = async (value: string) => {
  if (!value || value === businessDate.value) return;
  if (detail.value && editable.value && selectedLines.value.some(line => line.reviewStatus !== "APPROVED")) {
    await persistDraft(true);
  }
  businessDate.value = value;
  selectedId.value = "";
  await loadWorkbench(false);
};

const unique = (values: string[]) => Array.from(new Set(values.filter(Boolean)));

const maskVisitNo = (value?: string) => {
  if (!value) return "无就诊号";
  if (showFullVisitNo.value) return value;
  return value.length <= 4 ? value : `****${value.slice(-4)}`;
};

watch(
  () => props.departmentKey,
  () => reload()
);

onMounted(() => {
  void loadWorkbench(false);
});
</script>

<style scoped lang="scss">
.patient-workspace {
  --hos-bg-primary: #fffdf9;
  --hos-bg-surface: #faf7f1;
  --hos-border: #e6ded1;
  --hos-border-light: #f1ebe1;
  --hos-text-primary: #2f2d27;
  --hos-text-secondary: #6d675c;
  --hos-text-muted: #9a9285;
  --hos-primary: #347d68;
  --hos-primary-soft: #edf7f1;
  --hos-status-success: #3f8a62;
  --hos-status-success-soft: #eef8f1;
  --hos-status-warning: #b7791f;
  --hos-status-warning-soft: #fff7e8;
  --hos-status-danger: #c45454;
  --hos-status-danger-soft: #fdf0ef;

  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px;
  min-height: calc(100vh - 180px);
  background:
    radial-gradient(circle at 8% 0%, rgb(237 247 241 / 85%), transparent 32%),
    radial-gradient(circle at 96% 8%, rgb(255 247 232 / 60%), transparent 26%),
    var(--hos-bg-surface);
}

.patient-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 22px 24px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 18px;
  box-shadow: var(--hos-shadow-card);
}

.toolbar-copy {
  max-width: 720px;

  .eyebrow {
    display: inline-flex;
    margin-bottom: 6px;
    color: var(--hos-primary);
    font-weight: 600;
    font-size: 12px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  h2 {
    margin: 0 0 6px;
    color: var(--hos-text-primary);
    font-size: 24px;
    font-weight: 700;
    letter-spacing: -0.02em;
  }

  p {
    margin: 0;
    color: var(--hos-text-secondary);
    font-size: 13px;
    line-height: 1.7;
  }
}

.toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.summary-strip {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
}

.summary-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 16px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 14px;

  small {
    color: var(--hos-text-muted);
    font-size: 12px;
  }

  strong {
    color: var(--hos-text-primary);
    font-variant-numeric: tabular-nums;
    font-size: 24px;
    line-height: 1.1;
  }

  &.is-draft strong {
    color: var(--hos-text-secondary);
  }

  &.is-pending strong {
    color: var(--hos-status-warning);
  }

  &.is-approved strong {
    color: var(--hos-status-success);
  }

  &.is-returned strong {
    color: var(--hos-status-danger);
  }

  &.is-empty strong {
    color: var(--hos-text-muted);
  }
}

.patient-layout {
  display: grid;
  grid-template-columns: minmax(280px, 340px) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.patient-panel,
.detail-panel {
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 18px;
  box-shadow: var(--hos-shadow-card);
}

.patient-panel {
  position: sticky;
  top: 16px;
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 220px);
  overflow: hidden;
}

.patient-panel-head {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border-bottom: 1px solid var(--hos-border-light);
}

.status-filter {
  width: 100%;
}

.patient-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  overflow: auto;
}

.patient-card {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 12px;
  background: var(--hos-bg-surface);
  border: 1px solid transparent;
  border-radius: 12px;
  cursor: pointer;
  text-align: left;
  transition:
    border-color 160ms ease,
    background 160ms ease,
    transform 160ms ease;

  &:hover {
    border-color: var(--hos-border);
    transform: translateY(-1px);
  }

  &.is-active {
    background: var(--hos-primary-soft);
    border-color: var(--hos-primary);
  }
}

.patient-card-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.patient-name {
  overflow: hidden;
  color: var(--hos-text-primary);
  font-weight: 600;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.patient-meta {
  overflow: hidden;
  color: var(--hos-text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;

  i {
    margin: 0 4px;
    font-style: normal;
    opacity: 0.5;
  }
}

.patient-card-side {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  gap: 6px;
  align-items: flex-end;

  small {
    color: var(--hos-text-muted);
    font-size: 11px;
  }
}

.detail-panel {
  display: flex;
  flex-direction: column;
  min-height: 620px;
  padding: 20px;
}

.detail-empty {
  display: grid;
  place-items: center;
}

.detail-title-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: flex-start;
  justify-content: space-between;
}

.detail-name-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;

  h3 {
    margin: 0;
    color: var(--hos-text-primary);
    font-size: 22px;
    font-weight: 700;
  }
}

.detail-meta {
  margin: 6px 0 0;
  color: var(--hos-text-secondary);
  font-size: 12px;

  span {
    margin: 0 4px;
    opacity: 0.5;
  }
}

.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.review-alert {
  margin-top: 14px;
}

.detail-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  padding: 14px 0;
  margin-top: 12px;
  border-top: 1px solid var(--hos-border-light);
  border-bottom: 1px solid var(--hos-border-light);
}

.item-search {
  width: min(320px, 100%);
}

.line-counter {
  margin-left: auto;
  color: var(--hos-text-secondary);
  font-variant-numeric: tabular-nums;
  font-size: 12px;
}

.checklist-scroll {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding-top: 14px;
  overflow: auto;
}

.checklist-group {
  border: 1px solid var(--hos-border-light);
  border-radius: 14px;
  overflow: hidden;
}

.group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  background: var(--hos-bg-surface);

  h4 {
    margin: 0;
    color: var(--hos-text-primary);
    font-size: 13px;
    font-weight: 600;
  }

  span {
    color: var(--hos-text-muted);
    font-size: 12px;
  }
}

.checklist-row {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) 132px;
  gap: 10px;
  align-items: center;
  min-height: 52px;
  padding: 8px 14px;
  border-top: 1px solid var(--hos-border-light);
  transition: background 160ms ease;

  &.is-checked {
    background: color-mix(in srgb, var(--hos-primary-soft) 58%, transparent);
  }

  &.is-approved {
    background: var(--hos-bg-surface);
  }
}

.item-copy {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.item-name-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;

  strong {
    color: var(--hos-text-primary);
    font-weight: 500;
    font-size: 13px;
  }
}

.item-meta {
  color: var(--hos-text-muted);
  font-size: 11px;

  i {
    margin: 0 4px;
    font-style: normal;
    opacity: 0.6;
  }
}

.review-note {
  color: var(--hos-status-danger);
  font-size: 11px;
}

.preset-ghost {
  color: var(--hos-text-muted);
  font-variant-numeric: tabular-nums;
  font-size: 12px;
  text-align: right;
}

.manual-group {
  border-color: color-mix(in srgb, var(--hos-status-warning) 30%, var(--hos-border-light));
}

.patient-dialog {
  :deep(.el-dialog__body) {
    padding-top: 8px;
  }
}

.manual-search-row {
  display: flex;
  gap: 8px;
}

.manual-results {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 240px;
  margin: 12px 0;
  overflow: auto;
}

.manual-result {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 2px 10px;
  padding: 10px 12px;
  background: var(--hos-bg-surface);
  border: 1px solid transparent;
  border-radius: 10px;
  cursor: pointer;
  text-align: left;

  &:hover,
  &.is-active {
    border-color: var(--hos-primary);
    background: var(--hos-primary-soft);
  }

  strong {
    color: var(--hos-text-primary);
    font-size: 13px;
  }

  span {
    color: var(--hos-text-secondary);
    font-size: 12px;
  }

  small {
    grid-column: 2;
    grid-row: 1 / span 2;
    color: var(--hos-text-muted);
    font-size: 11px;
  }
}

.manual-form {
  margin-top: 4px;
}

.add-item-select {
  width: 100%;
}

.quick-section,
.selected-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--hos-border-light);
}

.section-title {
  display: flex;
  gap: 10px;
  align-items: baseline;

  h4 {
    margin: 0;
    color: var(--hos-text-primary);
    font-size: 13px;
    font-weight: 600;
  }

  span {
    color: var(--hos-text-muted);
    font-size: 11px;
  }
}

.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.quick-chip {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  padding: 6px 10px;
  color: var(--hos-text-primary);
  background: var(--hos-bg-surface);
  border: 1px solid var(--hos-border-light);
  border-radius: 999px;
  cursor: pointer;
  font-size: 12px;

  small {
    color: var(--hos-text-muted);
    font-size: 10px;
  }

  &.is-selected {
    color: var(--hos-primary);
    background: var(--hos-primary-soft);
    border-color: var(--hos-primary);
  }

  &.is-recent {
    border-style: dashed;
  }
}

.selected-row {
  display: grid;
  grid-template-columns: minmax(140px, 1fr) auto minmax(80px, auto) auto;
  gap: 10px;
  align-items: center;
  padding: 8px 10px;
  background: var(--hos-bg-surface);
  border: 1px solid var(--hos-border-light);
  border-radius: 10px;

  &.is-approved {
    opacity: 0.8;
  }
}

.selected-name {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;

  strong {
    overflow: hidden;
    color: var(--hos-text-primary);
    font-weight: 500;
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    color: var(--hos-text-muted);
    font-size: 11px;
  }
}

.quantity-control {
  display: flex;
  gap: 4px;
  align-items: center;

  :deep(.el-input-number) {
    width: 92px;
  }
}

.qty-chip {
  min-width: 26px;
  height: 26px;
  padding: 0 6px;
  color: var(--hos-text-secondary);
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 6px;
  cursor: pointer;
  font-size: 11px;

  &:hover:not(:disabled) {
    color: var(--hos-primary);
    border-color: var(--hos-primary);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.45;
  }
}

.selected-state {
  display: flex;
  flex-direction: column;
  gap: 2px;
  align-items: flex-end;

  small {
    color: var(--hos-status-danger);
    font-size: 10px;
  }
}

.sticky-actions {
  position: sticky;
  bottom: 0;
  z-index: 20;
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  padding: 10px 18px;
  margin: 0 -20px -20px;
  background: color-mix(in srgb, var(--hos-bg-primary) 94%, transparent);
  border-top: 1px solid var(--hos-border-light);
  backdrop-filter: blur(8px);
}

.save-state {
  margin-right: auto;
  color: var(--hos-text-muted);
  font-size: 11px;
}

.link-button {
  padding: 0;
  color: var(--hos-primary);
  background: none;
  border: 0;
  cursor: pointer;
  font-size: 11px;
}

.batch-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 12px;

  > div {
    display: flex;
    flex-direction: column;
    gap: 3px;
    padding: 10px 12px;
    background: var(--hos-bg-surface);
    border-radius: 10px;
  }

  small {
    color: var(--hos-text-muted);
    font-size: 11px;
  }

  strong {
    color: var(--hos-text-primary);
    font-size: 20px;
    font-variant-numeric: tabular-nums;
  }

  .danger {
    color: var(--hos-status-danger);
  }
}

.batch-missing {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 220px;
  margin-top: 10px;
  overflow: auto;
}

.missing-row {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
  background: var(--hos-bg-surface);
  border: 1px solid transparent;
  border-radius: 8px;
  cursor: pointer;
  text-align: left;

  &:hover {
    border-color: var(--hos-primary);
  }

  span {
    color: var(--hos-text-primary);
    font-size: 12px;
  }

  small {
    color: var(--hos-text-muted);
    font-size: 11px;
  }

  :deep(.el-tag) {
    margin-left: auto;
  }
}

.batch-result {
  margin-top: 12px;
  color: var(--hos-text-secondary);
  font-size: 12px;

  .danger {
    color: var(--hos-status-danger);
  }
}

.summary-item {
  padding: 8px 12px;

  strong {
    font-size: 18px;
  }
}

.patient-toolbar {
  padding: 14px 18px;
  border-radius: 14px;
}

.toolbar-copy {
  h2 {
    margin-bottom: 3px;
    font-size: 20px;
  }

  p {
    font-size: 12px;
  }
}

.date-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  align-items: center;
}

.date-chip {
  padding: 5px 10px;
  color: var(--hos-text-secondary);
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 999px;
  cursor: pointer;
  font-size: 12px;

  &.is-active {
    color: #fff;
    background: var(--hos-primary);
    border-color: var(--hos-primary);
  }
}

.date-picker-compact {
  width: 128px;
}

.status-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: center;
  padding: 8px 4px 2px;
  color: var(--hos-text-muted);
  font-size: 12px;

  b {
    margin: 0 2px;
    color: var(--hos-text-primary);
    font-variant-numeric: tabular-nums;
  }
}

.status-progress {
  color: var(--hos-text-secondary);
  font-weight: 600;
}

.status-progress-bar {
  flex: 1 1 180px;
  height: 4px;
  overflow: hidden;
  background: var(--hos-border-light);
  border-radius: 999px;

  span {
    display: block;
    height: 100%;
    background: var(--hos-primary);
    transition: width 200ms ease;
  }
}

.backfill-badge {
  padding: 2px 8px;
  color: var(--hos-status-warning);
  background: var(--hos-status-warning-soft);
  border-radius: 999px;
}

.mapping-hint {
  color: var(--hos-status-warning);
  font-size: 10px;
}

.package-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 12px;
  margin-top: 4px;
  border-top: 1px solid var(--hos-border-light);
}

.package-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 220px;
  overflow: auto;
}

.package-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 4px;
  align-items: center;
  padding: 7px 9px;
  background: var(--hos-bg-surface);
  border: 1px solid var(--hos-border-light);
  border-radius: 9px;
}

.package-name {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  color: var(--hos-text-primary);
  font-size: 12px;

  small {
    color: var(--hos-text-muted);
    font-size: 10px;
  }
}

.package-empty {
  margin: 0;
  color: var(--hos-text-muted);
  font-size: 11px;
}

.drawer-mode {
  display: flex;
  gap: 6px;
  align-items: center;
  padding-bottom: 4px;
  border-bottom: 1px solid var(--hos-border-light);
}

.mode-tab {
  padding: 6px 12px;
  color: var(--hos-text-secondary);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 999px;
  cursor: pointer;
  font-size: 12px;

  &.is-active {
    color: var(--hos-primary);
    background: var(--hos-primary-soft);
    border-color: var(--hos-primary);
  }
}

.mode-count {
  margin-left: auto;
  color: var(--hos-text-muted);
  font-size: 11px;
}

.quick-browse {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
  padding: 14px;
  background: var(--hos-bg-primary);
  border: 1px dashed var(--hos-border);
  border-radius: 12px;

  p {
    margin: 0;
    color: var(--hos-text-muted);
    font-size: 12px;
  }
}

.drawer-right .detail-toolbar {
  margin-top: 0;
  padding-top: 0;
  border-top: 0;
}

.drawer-left .selected-section {
  padding-top: 0;
  border-top: 0;
}

.selection-summary {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px 0 8px;
}

.selection-summary-head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;

  h4 {
    margin: 0 0 3px;
    color: var(--hos-text-primary);
    font-size: 18px;
    font-weight: 700;
  }

  p {
    margin: 0;
    color: var(--hos-text-muted);
    font-size: 12px;
  }
}

.selection-preview {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 180px;
  overflow: auto;
  padding: 10px;
  background: var(--hos-bg-surface);
  border: 1px solid var(--hos-border-light);
  border-radius: 12px;
}

.preview-chip {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  padding: 5px 9px;
  color: var(--hos-text-primary);
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 999px;
  font-size: 12px;

  b {
    color: var(--hos-primary);
    font-variant-numeric: tabular-nums;
    font-weight: 600;
  }
}

.selection-hints {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: var(--hos-text-muted);
  font-size: 11px;

  .is-muted {
    color: var(--hos-status-warning);
  }
}

.selection-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.consumable-drawer {
  :deep(.el-drawer__body) {
    padding: 0;
    overflow: hidden;
  }
}

.drawer-shell {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--hos-bg-surface);
}

.drawer-head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 18px 24px;
  background: var(--hos-bg-primary);
  border-bottom: 1px solid var(--hos-border-light);

  h3 {
    margin: 4px 0 0;
    color: var(--hos-text-primary);
    font-size: 20px;
    font-weight: 700;
  }
}

.drawer-head-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.drawer-hint {
  color: var(--hos-text-muted);
  font-size: 11px;
}

.drawer-grid {
  display: grid;
  grid-template-columns: minmax(320px, 38%) minmax(0, 62%);
  gap: 0;
  flex: 1;
  min-height: 0;
}

.drawer-left {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px 20px;
  overflow: auto;
  background: var(--hos-bg-primary);
  border-right: 1px solid var(--hos-border-light);
}

.drawer-right {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 22px;
  overflow: auto;
}

.drawer-toolbar {
  margin-top: 0;
  padding-top: 0;
  border-top: 0;
}

.drawer-right .checklist-scroll {
  gap: 10px;
  padding-top: 0;
}

.drawer-footer {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: flex-end;
  padding: 12px 24px;
  background: var(--hos-bg-primary);
  border-top: 1px solid var(--hos-border-light);

  span {
    margin-right: auto;
    color: var(--hos-text-secondary);
    font-size: 12px;
  }
}

@media (max-width: 1040px) {
  .drawer-grid {
    grid-template-columns: 1fr;
  }

  .drawer-left {
    max-height: 38vh;
    border-right: 0;
    border-bottom: 1px solid var(--hos-border-light);
  }
}

.patient-note {
  margin: 4px 0 0;
  color: var(--hos-status-warning);
  font-size: 12px;
}

.manual-item-results {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 220px;
  margin-top: 6px;
  overflow: auto;
}

.manual-item-option {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  background: var(--hos-bg-surface);
  border: 1px solid transparent;
  border-radius: 8px;
  cursor: pointer;
  text-align: left;

  &:hover {
    border-color: var(--hos-primary);
  }

  span {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  strong {
    color: var(--hos-text-primary);
    font-size: 12px;
  }

  small {
    color: var(--hos-text-muted);
    font-size: 10px;
  }
}

.manual-item-selected {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  margin-top: 6px;
  background: var(--hos-primary-soft);
  border: 1px solid var(--hos-primary);
  border-radius: 8px;

  span {
    display: flex;
    flex-direction: column;
    gap: 1px;
  }

  strong {
    color: var(--hos-text-primary);
    font-size: 12px;
  }

  small {
    color: var(--hos-text-muted);
    font-size: 10px;
  }
}

.source-shell {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
}

.source-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;

  .el-input {
    width: 260px;
  }

  .el-select {
    width: 140px;
  }
}

.source-summary {
  margin: 0;
  color: var(--hos-text-muted);
  font-size: 12px;
}

.source-list {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
  min-height: 200px;
  overflow: auto;
}

.source-card {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 12px;
  cursor: pointer;
  transition:
    border-color 160ms ease,
    transform 160ms ease;

  &:hover {
    border-color: var(--hos-primary);
    transform: translateY(-1px);
  }

  &.is-added {
    cursor: default;
    opacity: 0.6;
  }

  &.is-added:hover {
    border-color: var(--hos-border-light);
    transform: none;
  }
}

.source-main {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;

  strong {
    color: var(--hos-text-primary);
    font-size: 14px;
  }

  span {
    color: var(--hos-text-secondary);
    font-size: 11px;
  }

  small {
    color: var(--hos-text-muted);
    font-size: 10px;
  }
}

.source-department {
  padding: 1px 6px;
  margin-right: 4px;
  color: var(--hos-primary);
  font-size: 10px;
  font-style: normal;
  background: var(--hos-primary-soft);
  border-radius: 999px;
}

.source-more {
  text-align: center;
}

.history-shell {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.history-card {
  padding: 14px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 12px;

  header {
    display: flex;
    gap: 8px;
    align-items: center;
    justify-content: space-between;

    div {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    strong {
      color: var(--hos-text-primary);
      font-size: 14px;
    }

    span {
      color: var(--hos-text-muted);
      font-size: 11px;
    }
  }
}

.history-line {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0;
  border-top: 1px solid var(--hos-border-light);
  color: var(--hos-text-secondary);
  font-size: 12px;
}

.history-note {
  margin: 8px 0 0;
  color: var(--hos-status-warning);
  font-size: 11px;
}

.history-empty {
  margin: 8px 0 0;
  color: var(--hos-text-muted);
  font-size: 11px;
}

@media (max-width: 1180px) {
  .summary-strip {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .patient-layout {
    grid-template-columns: minmax(240px, 300px) minmax(0, 1fr);
  }
}

@media (max-width: 960px) {
  .patient-workspace {
    padding: 12px;
  }

  .patient-layout {
    grid-template-columns: 1fr;
  }

  .patient-panel {
    position: static;
    max-height: 360px;
  }

  .summary-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .checklist-row {
    grid-template-columns: 28px minmax(0, 1fr);
  }

  .checklist-row :deep(.el-input-number) {
    grid-column: 2;
    width: 100%;
  }

  .preset-ghost {
    grid-column: 2;
    text-align: left;
  }
}
</style>
