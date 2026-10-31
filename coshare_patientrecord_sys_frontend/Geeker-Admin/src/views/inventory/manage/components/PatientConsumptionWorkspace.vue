<template>
  <section class="patient-workspace" v-loading="loading">
    <header class="patient-toolbar">
      <div class="toolbar-copy">
        <span class="eyebrow">患者耗材 · {{ departmentName || "本科室" }}</span>
        <h2>患者耗材录入</h2>
        <p>选患者 → 选耗材 → 下一位；修改自动保存，最后统一提交。</p>
      </div>
      <div class="toolbar-actions">
        <el-button v-if="fixedCount" plain @click="fixedVisible = true">固定耗材 · {{ fixedStatusLabel }}</el-button>
        <el-dropdown trigger="click" @command="handleAddPatientCommand">
          <el-button type="primary" plain>加入患者 ▾</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="SOURCE">患者来源</el-dropdown-item>
              <el-dropdown-item command="FOLLOWUP">复诊预约</el-dropdown-item>
              <el-dropdown-item command="MANUAL">手动补录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button :icon="Refresh" :loading="loading" aria-label="刷新工作台" @click="reload" />
        <el-button v-if="selectedPatientKeys.length" type="primary" :loading="batchSubmitting" @click="openBatchSubmit">
          提交当天全部
        </el-button>
      </div>
    </header>

    <button type="button" class="status-strip" aria-label="打开今日摘要" @click="summaryVisible = true">
      <span class="status-progress">已录 <b>{{ summary.entered || 0 }}</b> / <b>{{ summary.expected ?? summary.total ?? 0 }}</b></span>
      <span v-if="summary.notStarted"><b>{{ summary.notStarted }}</b> 未录</span>
      <span v-if="summary.returned" class="is-danger"><b>{{ summary.returned }}</b> 驳回</span>
      <span v-if="(summary.notArrived || 0) + (summary.deferred || 0)">
        <b>{{ (summary.notArrived || 0) + (summary.deferred || 0) }}</b> 未到/暂缓
      </span>
      <span v-if="businessDate !== todayValue" class="backfill-badge">补录模式</span>
      <span class="status-hint" aria-hidden="true">摘要 ›</span>
      <span class="status-progress-bar" aria-hidden="true">
        <span :style="{ width: `${progress}%` }"></span>
      </span>
    </button>

    <div class="calendar-card">
      <div class="calendar-strip">
        <button
          v-for="day in stripDays"
          :key="day.value"
          type="button"
          class="calendar-day-chip"
          :class="[`is-${calendarStateOf(day.value)}`, { 'is-active': businessDate === day.value }]"
          @click="changeDate(day.value)"
        >
          <span class="chip-label">{{ day.label }}</span>
          <span v-if="calendarDayOf(day.value)" class="chip-count">
            {{ calendarDayOf(day.value)?.patientCount || 0 }}/{{ calendarDayOf(day.value)?.patientTotal || 0 }}
          </span>
        </button>
        <span class="calendar-month-summary">本月已填 <b>{{ monthFilledDays }}</b>/<b>{{ monthTrackedDays }}</b> 天</span>
        <el-button text type="primary" class="calendar-toggle" @click="calendarExpanded = !calendarExpanded">
          {{ calendarExpanded ? "收起月历" : "展开月历" }}
        </el-button>
      </div>
      <DepartmentFillCalendar
        v-if="calendarExpanded"
        class="workspace-calendar"
        wide
        :month="calendarMonth"
        :today="todayValue"
        :selected-date="businessDate"
        mode="PATIENT"
        :days="calendarDays"
        :expected-from="calendarExpectedFrom"
        :loading="calendarLoading"
        @update:month="changeCalendarMonth"
        @select-date="selectCalendarDate"
      />
    </div>

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
            <el-option label="未录入" value="NOT_STARTED" />
            <el-option label="草稿" value="DRAFT" />
            <el-option label="待审核" value="SUBMITTED" />
            <el-option label="已驳回" value="RETURNED" />
            <el-option label="已通过" value="APPROVED" />
            <el-option label="未到" value="NOT_ARRIVED" />
            <el-option label="暂缓" value="DEFERRED" />
          </el-select>
          <div class="patient-panel-tools">
            <el-checkbox :model-value="allVisibleSelected" @update:model-value="value => toggleAllVisible(Boolean(value))">
              全选当前筛选
            </el-checkbox>
            <span>{{ filteredPatients.length }} 人</span>
          </div>
        </div>

        <div class="patient-list" role="list">
          <template v-for="(patient, index) in filteredPatients" :key="patientKey(patient)">
            <div v-if="showTimeGroup(index)" class="timeline-mark" aria-hidden="true">
              <span>{{ timeGroupLabel(patient.arrivalAt || patient.serviceStartedAt) }}</span>
              <i />
            </div>
            <div
              class="patient-card"
              :class="{
                'is-active': selectedId === patient.serviceInstanceId,
                [`tone-${statusTone(patient.status)}`]: true,
                'is-not-arrived': patient.patientState === 'NOT_ARRIVED',
                'is-deferred': patient.patientState === 'DEFERRED'
              }"
              role="listitem"
            >
              <el-checkbox
                class="patient-select"
                :model-value="selectedPatientKeys.includes(patientKey(patient))"
                :aria-label="`选择 ${patient.patientName || '患者'}`"
                @update:model-value="value => togglePatientSelection(patient, Boolean(value))"
                @click.stop
              />
              <button type="button" class="patient-card-body" @click="selectPatient(patient)">
                <span class="patient-card-main">
                  <span class="patient-name">{{ patient.patientName || "未命名患者" }}</span>
                  <span class="patient-meta">
                    {{ arrivalLabel(patient) }}
                    <i aria-hidden="true">·</i>
                    {{ patient.serviceName || "服务" }}
                    <i aria-hidden="true">·</i>
                    {{ maskVisitNo(patient.visitNo) }}
                  </span>
                  <span v-if="slaLabel(patient)" class="sla-label" :class="`is-${slaTone(patient)}`">
                    {{ slaLabel(patient) }}
                  </span>
                </span>
                <span class="patient-card-side">
                  <el-tag v-if="patient.attentionFlag" type="danger" size="small" effect="plain">重点</el-tag>
                  <el-tag v-if="patient.patientState === 'NOT_ARRIVED'" type="info" size="small" effect="plain">未到</el-tag>
                  <el-tag v-else-if="patient.patientState === 'DEFERRED'" type="warning" size="small" effect="plain">暂缓</el-tag>
                  <el-tag v-if="patient.noConsumption" type="info" size="small" effect="plain">未使用</el-tag>
                  <el-tag :type="statusType(patient.status)" size="small" effect="plain">
                    {{ statusLabel(patient.status) }}
                  </el-tag>
                  <small>{{ patient.lineCount || 0 }} 项</small>
                </span>
              </button>
            </div>
          </template>
          <el-empty
            v-if="!filteredPatients.length"
            :image-size="70"
            description="当前筛选下没有患者；可手动补录"
          />
        </div>
        <div v-if="selectedPatientKeys.length" class="batch-bar">
          <div class="batch-bar-head">
            <strong>已选 {{ selectedPatientKeys.length }} 人</strong>
            <el-checkbox :model-value="allVisibleSelected" @update:model-value="value => toggleAllVisible(Boolean(value))">
              全选当前
            </el-checkbox>
          </div>
          <div class="batch-bar-actions">
            <el-button size="small" :loading="batchBusy" @click="runBatchStatus('NOT_ARRIVED')">未到</el-button>
            <el-button size="small" :loading="batchBusy" @click="runBatchStatus('DEFERRED')">暂缓</el-button>
            <el-button size="small" :loading="batchBusy" @click="restoreBatchStatus">恢复待录</el-button>
            <el-button size="small" type="warning" plain :loading="batchBusy" @click="runBatchZeroUse">零使用</el-button>
            <el-button size="small" type="danger" text :loading="batchBusy" @click="withdrawBatchZeroUse">撤回零使用</el-button>
            <el-button size="small" type="primary" :loading="batchBusy" @click="batchSubmitSelected">提交草稿</el-button>
          </div>
          <div class="batch-package">
            <el-checkbox v-model="batchPackageEnabled">套用科室套餐</el-checkbox>
            <el-select
              v-if="batchPackageEnabled"
              v-model="batchPackageId"
              size="small"
              filterable
              placeholder="选择本科室套餐"
              class="batch-package-select"
            >
              <el-option
                v-for="pkg in patientPackages"
                :key="pkg.id"
                :label="`${pkg.name}（${pkg.items.length} 项）`"
                :value="pkg.id"
              />
            </el-select>
            <el-button
              v-if="batchPackageEnabled"
              size="small"
              type="primary"
              plain
              :disabled="!batchPackageId"
              :loading="batchBusy"
              @click="applyBatchPackage"
            >
              套用到已选
            </el-button>
          </div>
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
                <el-tag v-else-if="detail.sourceType === 'REGISTRATION'" type="info" effect="plain">前台登记</el-tag>
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
              <el-dropdown v-if="editable" trigger="click" @command="handleReuseCommand">
                <el-button size="small">复用 ▾</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="PREVIOUS" :disabled="!previousPatient">同上一患者</el-dropdown-item>
                    <el-dropdown-item command="LAST">套用上次组合</el-dropdown-item>
                    <el-dropdown-item command="PACKAGE">套用科室套餐</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <el-dropdown trigger="click" @command="handleMoreCommand">
                <el-button size="small">更多 ▾</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="HISTORY">历史耗材</el-dropdown-item>
                    <el-dropdown-item command="NOTE">{{ detail.attentionFlag ? "备注/标记（已标记）" : "备注/标记" }}</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <el-tag v-if="!editable && detail.status === 'SUBMITTED'" type="warning" effect="plain">已提交，等待库管审核</el-tag>
              <el-tag v-else-if="!editable && detail.status === 'APPROVED'" type="success" effect="plain">已审核通过</el-tag>
              <el-tag v-else-if="!editable && detail.status === 'PARTIALLY_APPROVED'" type="warning" effect="plain">部分通过，可补交驳回项</el-tag>
              <el-tag v-else-if="!editable" type="danger" effect="plain">已驳回，请修改后重提</el-tag>
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
          <div v-if="selectedLines.length" class="selection-board">
            <div class="board-head">
              <div class="board-summary">
                <strong>{{ selectedLines.length }}</strong>
                <span>项已选</span>
                <span class="board-total">合计 {{ formatQuantity(selectedTotalQuantity) }}</span>
                <span v-if="selectedFilterOptions[2].count" class="board-rejected">
                  {{ selectedFilterOptions[2].count }} 项需处理
                </span>
              </div>
              <el-input
                v-model="selectedQuery"
                class="board-search"
                placeholder="在已选中查找耗材"
                clearable
                :prefix-icon="Search"
              />
            </div>
            <div class="board-filters" role="tablist" aria-label="已选耗材状态筛选">
              <button
                v-for="option in selectedFilterOptions"
                :key="option.key"
                type="button"
                role="tab"
                class="board-filter"
                :class="{ 'is-active': selectedFilter === option.key }"
                :aria-selected="selectedFilter === option.key"
                @click="selectedFilter = option.key"
              >
                {{ option.label }}
                <b v-if="option.count">{{ option.count }}</b>
              </button>
            </div>

            <div class="selected-grid">
              <article
                v-for="line in visibleSelectedLines"
                :key="line.itemId"
                class="selected-card"
                :class="`tone-${selectedTone(line)}`"
              >
                <div class="card-line">
                  <span class="state-dot" aria-hidden="true" />
                  <div class="card-copy">
                    <div class="card-title">
                      <strong>{{ line.itemName }}</strong>
                      <el-tag v-if="line.reviewStatus === 'REJECTED'" type="danger" size="small" effect="plain">
                        已驳回
                      </el-tag>
                      <el-tag v-else-if="line.reviewStatus === 'APPROVED'" type="success" size="small" effect="plain">
                        已通过
                      </el-tag>
                    </div>
                    <small>
                      {{ line.unit }} · {{ selectedSourceLabel(line.source) }}
                      <template v-if="line.exceptionReason"> · {{ line.exceptionReason }}</template>
                    </small>
                    <p v-if="line.reviewNote" class="review-note">{{ line.reviewNote }}</p>
                  </div>
                  <button
                    type="button"
                    class="card-remove"
                    :disabled="!editable || line.reviewStatus === 'APPROVED'"
                    :aria-label="`移除 ${line.itemName}`"
                    @click="removeLine(line.itemId)"
                  >
                    ×
                  </button>
                </div>
                <div class="card-quantity">
                  <button
                    type="button"
                    class="qty-step"
                    :disabled="!editable || line.reviewStatus === 'APPROVED'"
                    :aria-label="`减少 ${line.itemName}`"
                    @click="adjustLineQuantity(line, -1)"
                  >
                    −
                  </button>
                  <el-input-number
                    :model-value="line.actualQuantity"
                    :min="0.01"
                    :precision="2"
                    :step="1"
                    :controls="false"
                    size="small"
                    class="board-number"
                    :disabled="!editable || line.reviewStatus === 'APPROVED'"
                    @update:model-value="value => setLineQuantity(line, value)"
                  />
                  <button
                    type="button"
                    class="qty-step"
                    :disabled="!editable || line.reviewStatus === 'APPROVED'"
                    :aria-label="`增加 ${line.itemName}`"
                    @click="adjustLineQuantity(line, 1)"
                  >
                    +
                  </button>
                  <div class="quick-set" aria-label="快捷数量">
                    <button
                      v-for="quick in [1, 2, 3]"
                      :key="quick"
                      type="button"
                      :disabled="!editable || line.reviewStatus === 'APPROVED'"
                      @click="setLineQuantity(line, quick)"
                    >
                      {{ quick }}
                    </button>
                  </div>
                  <span class="card-unit">{{ line.unit }}</span>
                </div>
              </article>
            </div>
            <p v-if="!visibleSelectedLines.length" class="board-empty">当前筛选下没有耗材</p>
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
            <el-button text :disabled="!editable || !selectedLines.length" @click="clearUnapproved">全部未使用</el-button>
          </div>
        </section>

        <footer class="sticky-actions">
          <span class="save-state">{{ saveState || "修改自动保存" }}</span>
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
        </footer>
      </main>

      <main v-else class="detail-panel detail-empty">
        <el-empty :image-size="96" description="选择左侧患者开始填写耗材清单" />
      </main>
    </div>

    <div class="package-summary">
      <div class="package-summary-copy">
        <strong>科室套餐</strong>
        <span v-if="patientPackages.length">{{ patientPackages.length }} 个 · {{ packagePreview }}</span>
        <span v-else>暂无套餐，可把常用组合存为套餐</span>
      </div>
      <div class="package-summary-actions">
        <el-button @click="packageDrawerVisible = true">管理</el-button>
        <el-button type="primary" plain @click="followupBatchVisible = true">复诊批处理</el-button>
      </div>
    </div>

    <el-drawer v-model="packageDrawerVisible" title="科室套餐" size="60%" append-to-body>
      <DepartmentPackageBoard
        embedded
        :packages="patientPackages"
        :department-key="props.departmentKey"
        :date="businessDate"
        :existing-patient-ids="existingPatientIds"
        :can-apply="Boolean(detail) && editable"
        :current-items="currentPackageItems"
        @refresh="loadPackages"
        @apply="handleApplyPackageFromDrawer"
      />
    </el-drawer>

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
                        <button
                          type="button"
                          class="row-delete"
                          :disabled="!editable || line.reviewStatus === 'APPROVED'"
                          :aria-label="`移除 ${line.itemName}`"
                          title="移除"
                          @click="removeLine(line.itemId)"
                        >
                          ×
                        </button>
                        <span class="selected-name">
                          <strong>{{ line.itemName }}</strong>
                          <small>{{ line.unit }}<template v-if="line.exceptionReason"> · {{ line.exceptionReason }}</template></small>
                        </span>
                        <span class="selected-state">
                          <el-tag v-if="line.reviewStatus === 'APPROVED'" type="success" size="small" effect="plain">已通过</el-tag>
                          <el-tag v-else-if="line.reviewStatus === 'REJECTED'" type="danger" size="small" effect="plain">已驳回</el-tag>
                          <small v-if="line.reviewNote">{{ line.reviewNote }}</small>
                        </span>
                        <div class="quantity-control">
                          <el-button size="small" :disabled="!editable || line.reviewStatus === 'APPROVED'" @click="adjustLineQuantity(line, -1)">−</el-button>
                          <el-input-number
                            :model-value="line.actualQuantity"
                            :min="0.01"
                            :precision="2"
                            :step="1"
                            size="small"
                            :disabled="!editable || line.reviewStatus === 'APPROVED'"
                            controls-position="right"
                            @update:model-value="value => setLineQuantity(line, value)"
                          />
                          <el-button size="small" :disabled="!editable || line.reviewStatus === 'APPROVED'" @click="adjustLineQuantity(line, 1)">+</el-button>
                          <button
                            v-for="quick in [1, 2, 3]"
                            :key="quick"
                            type="button"
                            class="qty-chip"
                            :disabled="!editable || line.reviewStatus === 'APPROVED'"
                            @click="setLineQuantity(line, quick)"
                          >
                            {{ quick }}
                          </button>
                        </div>
                      </div>
                      <el-empty v-if="!selectedLines.length" :image-size="64" description="还没有选耗材；用搜索、常用或最近使用添加" />
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
          <el-input v-model="sourceLateReason" placeholder="补录原因（补录历史日必填）" clearable />
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
          :key="patient.serviceInstanceId || patient.encounterId || patient.patientId"
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

    <FixedConsumablesDialog
      v-model="fixedVisible"
      :department-key="props.departmentKey"
      :department-name="workbench.departmentName"
      :date="businessDate"
      @updated="loadFixedStatus"
    />
    <FollowupAppointmentsDialog
      v-model="followupVisible"
      :department-key="props.departmentKey"
      :date="businessDate"
      @batch-arrived="handleFollowupBatchArrived"
      @batch="followupVisible = false; followupBatchVisible = true"
    />
    <FollowupBatchDialog
      v-model="followupBatchVisible"
      :department-key="props.departmentKey"
      :date="businessDate"
      :packages="patientPackages"
      :existing-patient-ids="existingPatientIds"
      @completed="handleFollowupBatchCompleted"
    />

    <el-dialog v-model="summaryVisible" title="今日摘要" width="520px">
      <div class="summary-dialog">
        <div class="summary-dialog-grid">
          <div><small>应填</small><strong>{{ summary.expected ?? summary.total ?? 0 }}</strong></div>
          <div><small>已填</small><strong>{{ summary.entered || 0 }}</strong></div>
          <div><small>未录</small><strong>{{ summary.notStarted || 0 }}</strong></div>
          <div><small>待审核</small><strong>{{ summary.submitted || 0 }}</strong></div>
          <div><small>驳回</small><strong>{{ summary.returned || 0 }}</strong></div>
          <div><small>未到</small><strong>{{ summary.notArrived || 0 }}</strong></div>
          <div><small>暂缓</small><strong>{{ summary.deferred || 0 }}</strong></div>
          <div><small>待审/驳回超8h</small><strong>{{ (summary.pendingSlaOverdue || 0) + (summary.returnedSlaOverdue || 0) }}</strong></div>
        </div>
        <p class="summary-time">生成于 {{ dayjs().format("YYYY-MM-DD HH:mm") }} · {{ businessDate }}</p>
        <p class="summary-text">{{ summaryText }}</p>
      </div>
      <template #footer>
        <el-button @click="summaryVisible = false">关闭</el-button>
        <el-button type="primary" :loading="summaryCopying" @click="copySummary">复制摘要</el-button>
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
import FixedConsumablesDialog from "./FixedConsumablesDialog.vue";
import FollowupAppointmentsDialog from "./FollowupAppointmentsDialog.vue";
import FollowupBatchDialog from "./FollowupBatchDialog.vue";
import DepartmentPackageBoard from "./DepartmentPackageBoard.vue";
import {
  addInventoryManualPatientApi,
  getInventoryFixedConsumablesApi,
  batchInventoryPatientZeroUseApi,
  restoreInventoryPatientDayStatusApi,
  setInventoryPatientDayStatusApi,
  withdrawInventoryPatientZeroUseApi,
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
const calendarExpectedFrom = ref("");
const calendarLoading = ref(false);
const calendarExpanded = ref(false);
const fixedVisible = ref(false);
const fixedCount = ref(0);
const fixedStatus = ref("");
const followupVisible = ref(false);
const followupBatchVisible = ref(false);
const workbench = ref<InventoryPatientWorkbench>(emptyWorkbench());
const detail = ref<InventoryPatientDetail | null>(null);
const selectedId = ref("");
const loading = ref(false);
const saving = ref(false);
const batchSubmitting = ref(false);
const patientKeyword = ref("");
const statusFilter = ref("ALL");
const itemKeyword = ref("");
const lineState = reactive<Record<string, EditableLine>>({});
const recentItems = ref<InventoryPatientRecentItems["items"]>([]);
const patientPackages = ref<InventoryPatientConsumablePackage[]>([]);
const packageDrawerVisible = ref(false);
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
const sourceLateReason = ref("");
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
const selectedQuery = ref("");
const selectedFilter = ref<"ALL" | "PENDING" | "REJECTED" | "APPROVED">("ALL");

const selectedTotalQuantity = computed(() =>
  selectedLines.value.reduce((sum, line) => sum + Number(line.actualQuantity || 0), 0)
);
const selectedFilterOptions = computed(() => {
  const pending = selectedLines.value.filter(
    line => line.reviewStatus !== "APPROVED" && line.reviewStatus !== "REJECTED"
  ).length;
  const rejected = selectedLines.value.filter(line => line.reviewStatus === "REJECTED").length;
  const approved = selectedLines.value.filter(line => line.reviewStatus === "APPROVED").length;
  return [
    { key: "ALL" as const, label: "全部", count: selectedLines.value.length },
    { key: "PENDING" as const, label: "待录", count: pending },
    { key: "REJECTED" as const, label: "驳回", count: rejected },
    { key: "APPROVED" as const, label: "通过", count: approved }
  ];
});
const visibleSelectedLines = computed(() => {
  const keyword = selectedQuery.value.trim().toLowerCase();
  const statusRank = (line: EditableLine) => {
    if (line.reviewStatus === "REJECTED") return 0;
    if (line.reviewStatus === "APPROVED") return 2;
    return 1;
  };
  const sourceRank = (line: EditableLine) => (line.source === "COMMON" ? 0 : line.source === "PRESET" ? 1 : 2);
  return selectedLines.value
    .filter(line => {
      if (selectedFilter.value === "PENDING") {
        if (line.reviewStatus === "APPROVED" || line.reviewStatus === "REJECTED") return false;
      } else if (selectedFilter.value !== "ALL" && line.reviewStatus !== selectedFilter.value) {
        return false;
      }
      if (!keyword) return true;
      return [line.itemName, line.unit, line.exceptionReason, line.reviewNote]
        .filter(Boolean)
        .some(value => String(value).toLowerCase().includes(keyword));
    })
    .sort(
      (a, b) =>
        statusRank(a) - statusRank(b) ||
        sourceRank(a) - sourceRank(b) ||
        String(a.itemName).localeCompare(String(b.itemName), "zh-CN")
    );
});
const selectedTone = (line: EditableLine) => {
  if (line.reviewStatus === "REJECTED") return "rejected";
  if (line.reviewStatus === "APPROVED") return "approved";
  return "pending";
};
const selectedSourceLabel = (source: EditableLine["source"]) =>
  source === "COMMON" ? "常用" : source === "MANUAL" ? "追加" : "预设";

const patientKey = (patient: InventoryPatientWorkbenchRow) =>
  patient.serviceInstanceId || patient.encounterId || patient.patientId;

const filteredPatients = computed(() => {
  const keyword = patientKeyword.value.trim().toLowerCase();
  return workbench.value.patients
    .filter(patient => {
      if (["NOT_ARRIVED", "DEFERRED"].includes(statusFilter.value)) {
        if (patient.patientState !== statusFilter.value) return false;
      } else if (statusFilter.value !== "ALL") {
        if (patient.patientState === "NOT_ARRIVED") return false;
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
    })
    .sort((a, b) => String(a.arrivalAt || a.serviceStartedAt || "").localeCompare(String(b.arrivalAt || b.serviceStartedAt || "")));
});

const selectedPatientKeys = ref<string[]>([]);
const selectedPatients = computed(() =>
  workbench.value.patients.filter(patient => selectedPatientKeys.value.includes(patientKey(patient)))
);
const allVisibleSelected = computed(
  () => filteredPatients.value.length > 0 && filteredPatients.value.every(patient => selectedPatientKeys.value.includes(patientKey(patient)))
);
const summaryVisible = ref(false);
const summaryCopying = ref(false);
const batchBusy = ref(false);
const batchPackageEnabled = ref(false);
const batchPackageId = ref("");

const timeGroupLabel = (value?: string) => {
  const time = value ? dayjs(value) : null;
  return time?.isValid() ? time.format("HH:00") : "时间未记录";
};
const showTimeGroup = (index: number) => {
  if (index === 0) return true;
  return timeGroupLabel(filteredPatients.value[index].arrivalAt || filteredPatients.value[index].serviceStartedAt)
    !== timeGroupLabel(filteredPatients.value[index - 1].arrivalAt || filteredPatients.value[index - 1].serviceStartedAt);
};
const arrivalLabel = (patient: InventoryPatientWorkbenchRow) => {
  const time = patient.arrivalAt || patient.serviceStartedAt;
  return time && dayjs(time).isValid() ? dayjs(time).format("HH:mm") : "—";
};
const slaLabel = (patient: InventoryPatientWorkbenchRow) => {
  const age = Number(patient.statusAgeMinutes || 0);
  if (!age) return "";
  if (patient.status === "RETURNED") return `驳回 ${Math.floor(age / 60)}h`;
  if (["SUBMITTED", "PARTIALLY_APPROVED"].includes(patient.status)) return `待审 ${Math.floor(age / 60)}h`;
  return "";
};
const slaTone = (patient: InventoryPatientWorkbenchRow) => {
  const age = Number(patient.statusAgeMinutes || 0);
  if (age >= 480) return "overdue";
  if (age >= 120) return "warn";
  return "";
};
const togglePatientSelection = (patient: InventoryPatientWorkbenchRow, checked: boolean) => {
  const key = patientKey(patient);
  if (checked) {
    if (!selectedPatientKeys.value.includes(key)) selectedPatientKeys.value.push(key);
  } else {
    selectedPatientKeys.value = selectedPatientKeys.value.filter(item => item !== key);
  }
};
const toggleAllVisible = (checked: boolean) => {
  selectedPatientKeys.value = checked ? filteredPatients.value.map(patientKey) : [];
};

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
const calendarDayOf = (date: string) => calendarDays.value.find(day => day.date === date);
const calendarStateOf = (date: string) => {
  if (calendarExpectedFrom.value && date < calendarExpectedFrom.value) return "none";
  if (date > todayValue) return "future";
  const day = calendarDayOf(date);
  if (day && Number(day.patientTotal || 0) > 0) {
    if (Number(day.missing || 0) === 0 && Number(day.draft || 0) === 0 && Number(day.returned || 0) === 0) return "filled";
    if (Number(day.patientCount || 0) > 0 || Number(day.draft || 0) > 0 || Number(day.returned || 0) > 0) return "suggest";
    return date === todayValue ? "due" : "missing";
  }
  return date === todayValue ? "due" : "none";
};
const stripDays = computed(() => {
  const days = recentDays.value
    .slice()
    .reverse()
    .map(day => ({ value: day.value, label: day.label }));
  if (businessDate.value && !days.some(day => day.value === businessDate.value)) {
    days.unshift({ value: businessDate.value, label: dayjs(businessDate.value).format("MM-DD") });
  }
  return days;
});
const monthCalendarDays = computed(() =>
  calendarDays.value.filter(
    day => day.date.slice(0, 7) === calendarMonth.value.slice(0, 7) && (!calendarExpectedFrom.value || day.date >= calendarExpectedFrom.value)
  )
);
const monthTrackedDays = computed(() => monthCalendarDays.value.filter(day => Number(day.patientTotal || 0) > 0).length);
const monthFilledDays = computed(
  () =>
    monthCalendarDays.value.filter(
      day =>
        Number(day.patientTotal || 0) > 0 &&
        Number(day.missing || 0) === 0 &&
        Number(day.draft || 0) === 0 &&
        Number(day.returned || 0) === 0
    ).length
);
const packagePreview = computed(() =>
  patientPackages.value
    .slice(0, 3)
    .map(pkg => pkg.name)
    .join("、") + (patientPackages.value.length > 3 ? "…" : "")
);
const currentPackageItems = computed(() =>
  selectedLines.value
    .filter(line => line.reviewStatus !== "APPROVED")
    .map(line => ({
      itemId: line.itemId,
      itemName: line.itemName,
      unit: line.unit,
      quantity: Number(line.actualQuantity || 0)
    }))
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
  await flushAutoSave();
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
    void loadFixedStatus();
    if (!keepSelection || !selectedId.value) {
      const first =
        filteredPatients.value.find(patient => patient.serviceInstanceId) ||
        workbench.value.patients.find(patient => patient.serviceInstanceId);
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

const loadFixedStatus = async () => {
  try {
    const result = await getInventoryFixedConsumablesApi({
      departmentKey: props.departmentKey,
      date: businessDate.value
    });
    fixedCount.value = result.data?.items?.length ?? 0;
    fixedStatus.value = result.data?.status || "DRAFT";
  } catch {
    fixedCount.value = 0;
    fixedStatus.value = "";
  }
};

const existingPatientIds = computed(() => workbench.value.patients.map(patient => patient.patientId).filter(Boolean));

const handleFollowupBatchArrived = async () => {
  await loadWorkbench(false);
};

const handleFollowupBatchCompleted = async () => {
  await loadWorkbench(false);
};

const fixedStatusLabel = computed(() => {
  if (fixedStatus.value === "APPROVED") return "已通过";
  if (fixedStatus.value === "SUBMITTED") return "待审核";
  if (fixedStatus.value === "RETURNED") return "已驳回";
  return "待确认";
});

const loadCalendar = async () => {
  calendarLoading.value = true;
  try {
    const result = await getInventoryDepartmentDailyReportCalendarApi({
      departmentKey: props.departmentKey,
      month: calendarMonth.value
    });
    calendarDays.value = result.data?.days ?? [];
    calendarExpectedFrom.value = result.data?.expectedFrom ?? "";
  } catch {
    calendarDays.value = [];
    calendarExpectedFrom.value = "";
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
  calendarExpanded.value = false;
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

const applyPackage = async (pkg: InventoryPatientConsumablePackage) => {
  if (!editable.value || !pkg.items.length) return false;
  try {
    await ElMessageBox.confirm(
      `将当前未审核耗材替换为“${pkg.name}”的 ${pkg.items.length} 项内容？已审核通过的项会保留。`,
      "套用患者套餐",
      { confirmButtonText: "套用", cancelButtonText: "取消", type: "warning" }
    );
  } catch {
    return false;
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
  return true;
};

const handleApplyPackageFromDrawer = async (pkg: InventoryPatientConsumablePackage) => {
  if (await applyPackage(pkg)) packageDrawerVisible.value = false;
};

const handleAddPatientCommand = (command: string) => {
  if (command === "SOURCE") void openPatientSource();
  else if (command === "FOLLOWUP") followupVisible.value = true;
  else if (command === "MANUAL") openManualDialog();
};

const handleReuseCommand = (command: string) => {
  if (command === "PREVIOUS") void copyPreviousPatient();
  else if (command === "LAST") void applyLastCombination();
  else if (command === "PACKAGE") packageDrawerVisible.value = true;
};

const handleMoreCommand = (command: string) => {
  if (command === "HISTORY") void openHistory();
  else if (command === "NOTE") openNoteDialog();
};

const selectPatient = async (patient: InventoryPatientWorkbenchRow, skipSave = false) => {
  if (patient.serviceInstanceId && patient.serviceInstanceId === selectedId.value) return;
  cancelAutoSave();
  if (autoSavePromise) await autoSavePromise;
  if (!skipSave && detail.value && editable.value && selectedLines.value.some(line => line.reviewStatus !== "APPROVED")) {
    await persistDraft(true);
  }
  if (!patient.serviceInstanceId) {
    try {
      const result = await addInventoryManualPatientApi({
        departmentKey: props.departmentKey,
        encounterId: patient.encounterId,
        patientId: patient.patientId,
        patientName: patient.patientName,
        visitNo: patient.visitNo,
        careType: patient.careType,
        serviceCode: "REGISTRATION",
        serviceName: "前台登记",
        serviceStartedAt: patient.serviceStartedAt,
        businessDate: businessDate.value,
        stageCode: patient.stageCode,
        sourceType: "REGISTRATION",
        note: "前台登记患者首次录入"
      });
      selectedId.value = result.data?.serviceInstanceId || "";
      showFullVisitNo.value = false;
      applyDetail(result.data);
      await refreshWorkbenchOnly();
      void loadCalendar();
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : "登记患者进入本科室失败");
    }
    return;
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
  lastSavedSignature = lineSignature.value;
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

const buildPayload = (noConsumption = false, correctionReason = "") => ({
  serviceInstanceId: detail.value!.serviceInstanceId,
  revision: detail.value!.revision || 0,
  noConsumption,
  correctionReason: correctionReason || undefined,
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

const persistDraft = async (silent = false, noConsumption = false, correctionReason = "") => {
  if (!detail.value) return undefined;
  if (!noConsumption && selectedLines.value.filter(line => line.reviewStatus !== "APPROVED").length === 0) {
    if (!silent) ElMessage.warning("未选择耗材；如确实未使用，请点“未使用并下一位”");
    return undefined;
  }
  const result = await saveInventoryPatientDocumentApi(buildPayload(noConsumption, correctionReason));
  applyDetail(result.data);
  lastSavedSignature = lineSignature.value;
  saveState.value = `已自动保存 ${dayjs().format("HH:mm")}`;
  await refreshWorkbenchOnly();
  void loadRecentItems();
  void loadCalendar();
  return result.data;
};

const lineSignature = computed(() =>
  selectedLines.value
    .filter(line => line.reviewStatus !== "APPROVED")
    .map(line => `${line.itemId}:${Number(line.actualQuantity || 0)}`)
    .sort()
    .join("|")
);
let lastSavedSignature = "";
let autoSaveTimer: ReturnType<typeof setTimeout> | undefined;
let autoSavePromise: Promise<void> | null = null;

const canAutoSave = () =>
  Boolean(detail.value) &&
  editable.value &&
  businessDate.value === todayValue &&
  !saving.value &&
  selectedLines.value.some(line => line.reviewStatus !== "APPROVED") &&
  selectedLines.value.every(line => line.reviewStatus === "APPROVED" || Number(line.actualQuantity || 0) > 0);

const flushAutoSave = async () => {
  if (autoSaveTimer) {
    clearTimeout(autoSaveTimer);
    autoSaveTimer = undefined;
  }
  if (!canAutoSave() || lineSignature.value === lastSavedSignature) return;
  if (autoSavePromise) {
    await autoSavePromise;
    return;
  }
  autoSavePromise = persistDraft(true)
    .then(() => undefined)
    .catch(() => {
      // 自动保存失败时保留当前编辑内容，由用户主动提交
    })
    .finally(() => {
      autoSavePromise = null;
    });
  await autoSavePromise;
};

const cancelAutoSave = () => {
  if (autoSaveTimer) {
    clearTimeout(autoSaveTimer);
    autoSaveTimer = undefined;
  }
};

const scheduleAutoSave = () => {
  if (!canAutoSave() || lineSignature.value === lastSavedSignature) return;
  if (autoSaveTimer) clearTimeout(autoSaveTimer);
  saveState.value = "等待自动保存…";
  autoSaveTimer = setTimeout(() => {
    autoSaveTimer = undefined;
    void flushAutoSave();
  }, 1200);
};

watch(lineSignature, scheduleAutoSave);

const promptCorrectionReason = async () => {
  if (businessDate.value >= todayValue) return "";
  try {
    const result = await ElMessageBox.prompt("业务日已过，请填写补录/更正原因", "补录与更正", {
      confirmButtonText: "继续保存",
      cancelButtonText: "取消",
      inputPlaceholder: "例如：患者离开后才补录",
      inputValidator: value => Boolean(value && String(value).trim()) || "必须填写原因"
    });
    return String(result.value || "").trim();
  } catch {
    return null;
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

const submitAndNext = async () => {
  const correctionReason = await promptCorrectionReason();
  if (correctionReason === null) return;
  const result = await persistDraft(false, false, correctionReason);
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

const refreshAfterBatch = async () => {
  selectedPatientKeys.value = [];
  await loadWorkbench(false);
  void loadCalendar();
};

const runBatchStatus = async (status: "NOT_ARRIVED" | "DEFERRED") => {
  const targets = selectedPatients.value.filter(patient => patient.status === "NOT_STARTED");
  if (!targets.length) {
    ElMessage.warning("只能对尚未开始录入的患者标记未到或暂缓");
    return;
  }
  batchBusy.value = true;
  try {
    await setInventoryPatientDayStatusApi({
      departmentKey: props.departmentKey,
      businessDate: businessDate.value,
      patientIds: targets.map(patient => patient.patientId),
      status
    });
    ElMessage.success(status === "NOT_ARRIVED" ? "已标记未到" : "已标记暂缓");
    await refreshAfterBatch();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "批量状态更新失败");
  } finally {
    batchBusy.value = false;
  }
};

const restoreBatchStatus = async () => {
  if (!selectedPatients.value.length) {
    ElMessage.warning("请先勾选患者");
    return;
  }
  batchBusy.value = true;
  try {
    await restoreInventoryPatientDayStatusApi({
      departmentKey: props.departmentKey,
      businessDate: businessDate.value,
      patientIds: selectedPatients.value.map(patient => patient.patientId)
    });
    ElMessage.success("已恢复为待录");
    await refreshAfterBatch();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "恢复待录失败");
  } finally {
    batchBusy.value = false;
  }
};

const zeroUseTargets = computed(() =>
  selectedPatients.value.length ? selectedPatients.value : filteredPatients.value.filter(patient => patient.patientState !== "NOT_ARRIVED")
);

const runBatchZeroUse = async () => {
  const targets = zeroUseTargets.value;
  if (!targets.length) {
    ElMessage.warning("当前没有可标记的患者");
    return;
  }
  try {
    await ElMessageBox.confirm(
      `将为当前 ${targets.length} 位患者逐人标记“未使用”。之后新到的患者仍保持待录。`,
      "批量零使用",
      { confirmButtonText: "确认标记", cancelButtonText: "取消", type: "warning" }
    );
  } catch {
    return;
  }
  batchBusy.value = true;
  try {
    const result = await batchInventoryPatientZeroUseApi({
      departmentKey: props.departmentKey,
      businessDate: businessDate.value,
      patients: targets.map(patient => ({
        patientId: patient.patientId,
        encounterId: patient.encounterId,
        patientName: patient.patientName,
        visitNo: patient.visitNo,
        careType: patient.careType
      }))
    });
    const updated = result.data?.updated || 0;
    const errors = result.data?.errors || [];
    if (errors.length) ElMessage.warning(`已处理 ${updated} 人，${errors.length} 人需检查`);
    else ElMessage.success(`已标记 ${updated} 位患者未使用`);
    await refreshAfterBatch();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "批量零使用失败");
  } finally {
    batchBusy.value = false;
  }
};

const withdrawBatchZeroUse = async () => {
  const targets = zeroUseTargets.value;
  if (!targets.length) return;
  try {
    await ElMessageBox.confirm(`撤回当前 ${targets.length} 位患者的未使用记录？`, "撤回零使用", {
      confirmButtonText: "撤回",
      cancelButtonText: "取消",
      type: "warning"
    });
  } catch {
    return;
  }
  batchBusy.value = true;
  try {
    const result = await withdrawInventoryPatientZeroUseApi({
      departmentKey: props.departmentKey,
      businessDate: businessDate.value,
      patientIds: targets.map(patient => patient.patientId)
    });
    ElMessage.success(`已撤回 ${result.data?.withdrawn || 0} 份未使用记录`);
    await refreshAfterBatch();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "撤回零使用失败");
  } finally {
    batchBusy.value = false;
  }
};

const batchSubmitSelected = async () => {
  const targets = selectedPatients.value.filter(patient => patient.documentId && patient.status === "DRAFT");
  if (!targets.length) {
    ElMessage.warning("已选患者中没有可提交的草稿");
    return;
  }
  batchBusy.value = true;
  let submitted = 0;
  try {
    for (const patient of targets) {
      await submitInventoryPatientDocumentApi(patient.documentId);
      submitted++;
    }
    ElMessage.success(`已提交 ${submitted} 位患者`);
    await refreshAfterBatch();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "批量提交失败");
  } finally {
    batchBusy.value = false;
  }
};

const applyBatchPackage = async () => {
  const pkg = patientPackages.value.find(item => item.id === batchPackageId.value);
  if (!pkg || !pkg.items.length) {
    ElMessage.warning("请选择包含耗材的科室套餐");
    return;
  }
  const targets = selectedPatients.value.filter(patient =>
    ["NOT_STARTED", "DRAFT", "RETURNED"].includes(patient.status) && !patient.noConsumption
  );
  if (!targets.length) {
    ElMessage.warning("已选患者中没有可套用套餐的未完成清单");
    return;
  }
  try {
    await ElMessageBox.confirm(
      `将把“${pkg.name}”套用到 ${targets.length} 位患者。已有草稿/驳回清单会被套餐内容替换，已审核通过的清单会跳过。`,
      "批量套用科室套餐",
      { confirmButtonText: "套用", cancelButtonText: "取消", type: "warning" }
    );
  } catch {
    return;
  }
  batchBusy.value = true;
  let applied = 0;
  const errors: string[] = [];
  try {
    for (const patient of targets) {
      try {
        let serviceInstanceId = patient.serviceInstanceId;
        if (!serviceInstanceId) {
          const added = await addInventoryManualPatientApi({
            departmentKey: props.departmentKey,
            encounterId: patient.encounterId,
            patientId: patient.patientId,
            patientName: patient.patientName,
            visitNo: patient.visitNo,
            careType: patient.careType,
            serviceCode: "REGISTRATION",
            serviceName: "前台登记",
            sourceType: "REGISTRATION",
            serviceStartedAt: patient.serviceStartedAt,
            businessDate: businessDate.value,
            note: "初诊套用科室套餐"
          });
          serviceInstanceId = added.data?.serviceInstanceId || "";
        }
        if (!serviceInstanceId) throw new Error("未生成服务实例");
        await saveInventoryPatientDocumentApi({
          serviceInstanceId,
          revision: patient.revision || 0,
          noConsumption: false,
          lines: pkg.items.map(item => ({
            itemId: item.itemId,
            itemName: item.itemName,
            unit: item.unit,
            actualQuantity: Number(item.quantity || 1),
            source: (item.source as "COMMON" | "PRESET" | "MANUAL" | undefined) || "PRESET",
            exceptionReason: item.exceptionReason
          }))
        });
        applied++;
      } catch (error) {
        errors.push(`${patient.patientName}：${error instanceof Error ? error.message : "套用失败"}`);
      }
    }
    if (errors.length) ElMessage.warning(`已套用 ${applied} 位，${errors.length} 位失败`);
    else ElMessage.success(`已套用 ${applied} 位患者`);
    await refreshAfterBatch();
  } finally {
    batchBusy.value = false;
  }
};

const summaryText = computed(() => {
  const value = summary.value;
  return [
    `今日摘要 ${businessDate.value}`,
    `应填 ${value.expected ?? value.total ?? 0} 人`,
    `已填 ${value.entered || 0} 人`,
    `未录 ${value.notStarted || 0} 人`,
    `待审 ${value.submitted || 0} 人`,
    `驳回 ${value.returned || 0} 人`,
    `未到 ${value.notArrived || 0} 人`,
    `暂缓 ${value.deferred || 0} 人`
  ].join(" · ");
});

const copySummary = async () => {
  summaryCopying.value = true;
  try {
    await navigator.clipboard.writeText(summaryText.value);
    ElMessage.success("摘要已复制");
  } catch {
    ElMessage.warning("当前浏览器不允许复制，请手动选择文本");
  } finally {
    summaryCopying.value = false;
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
  sourceLateReason.value = "";
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
  const targetDate = sourceDate.value || businessDate.value;
  if (targetDate !== businessDate.value && !sourceLateReason.value.trim()) {
    ElMessage.warning("补录历史日期需要填写补录原因");
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
      businessDate: targetDate,
      lateReason: targetDate !== todayValue ? sourceLateReason.value.trim() : undefined,
      note: targetDate !== todayValue ? "患者档案补录" : "从患者档案加入"
    });
    sourceDrawerVisible.value = false;
    if (targetDate !== businessDate.value) businessDate.value = targetDate;
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

const statusTone = (status: string) => {
  switch (status) {
    case "NOT_STARTED":
      return "missing";
    case "DRAFT":
      return "draft";
    case "RETURNED":
      return "returned";
    case "APPROVED":
      return "approved";
    default:
      return "submitted";
  }
};

const statusType = (status: string): "success" | "warning" | "danger" | "info" => {
  switch (status) {
    case "APPROVED":
      return "success";
    case "RETURNED":
      return "danger";
    case "NOT_STARTED":
      return "warning";
    case "DRAFT":
      return "info";
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
  cancelAutoSave();
  if (autoSavePromise) await autoSavePromise;
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
  flex: 1;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  padding: 10px;
  overflow: auto;
}

.patient-panel-tools {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  color: var(--hos-text-muted);
  font-size: 10px;
}

.timeline-mark {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 2px 2px 0;
  color: var(--hos-text-muted);
  font-size: 10px;

  span {
    min-width: 42px;
    font-variant-numeric: tabular-nums;
  }

  i {
    flex: 1;
    height: 1px;
    background: var(--hos-border-light);
  }
}

.patient-card {
  display: flex;
  gap: 6px;
  align-items: flex-start;
  padding: 8px 10px 8px 6px;
  background: var(--hos-bg-surface);
  border: 1px solid transparent;
  border-radius: 12px;
  cursor: pointer;
  text-align: left;
  transition:
    border-color 160ms ease,
    background 160ms ease;

  &:hover {
    border-color: var(--hos-border);
  }

  &.tone-missing {
    background: #fff8ef;
    border-left: 3px solid #d97706;
  }

  &.tone-draft {
    background: #fffbeb;
    border-left: 3px solid #b7791f;
  }

  &.tone-submitted {
    background: #eff6ff;
    border-left: 3px solid #4f7cac;
  }

  &.tone-returned {
    background: #fdf0f0;
    border-left: 3px solid #c45454;
  }

  &.tone-approved {
    background: #eef8f1;
    border-left: 3px solid #3f8a62;
  }

  &.is-not-arrived {
    opacity: 0.62;
    border-left-style: dashed;
  }

  &.is-deferred {
    border-left-color: #b7791f;
  }

  &.is-active {
    background: var(--hos-primary-soft);
    border-color: var(--hos-primary);
    border-left: 3px solid var(--hos-primary);
  }
}

.patient-select {
  flex: 0 0 auto;
  margin-top: 8px;
}

.patient-card-body {
  display: flex;
  flex: 1;
  gap: 8px;
  align-items: flex-start;
  justify-content: space-between;
  min-width: 0;
  padding: 4px 2px;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;

  &:focus-visible {
    outline: 2px solid var(--hos-primary);
    outline-offset: 2px;
    border-radius: 8px;
  }
}

.sla-label {
  align-self: flex-start;
  padding: 1px 6px;
  font-size: 9px;
  font-variant-numeric: tabular-nums;
  border-radius: 999px;

  &.is-warn {
    color: #8a5a13;
    background: #fdf6e7;
  }

  &.is-overdue {
    color: #a33f3f;
    background: #fbefef;
  }
}

.batch-bar {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 10px;
  background: #fffdf9;
  border-top: 1px solid var(--hos-border-light);
  box-shadow: 0 -6px 16px rgb(47 45 39 / 6%);
}

.batch-bar-head {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  color: var(--hos-text-secondary);
  font-size: 11px;
}

.batch-bar-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.batch-package {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  padding-top: 4px;
  border-top: 1px dashed var(--hos-border-light);
}

.batch-package-select {
  min-width: 170px;
}

.summary-dialog {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.summary-dialog-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;

  > div {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 9px 10px;
    background: #f8f5ef;
    border-radius: 9px;
  }

  small {
    color: var(--hos-text-muted);
    font-size: 10px;
  }

  strong {
    color: var(--hos-text-primary);
    font-size: 19px;
    font-variant-numeric: tabular-nums;
  }
}

.summary-time,
.summary-text {
  margin: 0;
  color: var(--hos-text-muted);
  font-size: 11px;
}

.summary-text {
  padding: 8px 10px;
  color: var(--hos-text-secondary);
  background: #f8f5ef;
  border-radius: 8px;
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
  position: relative;
  display: grid;
  grid-template-areas:
    "name state"
    "qty qty";
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 7px 10px;
  align-items: center;
  padding: 9px 10px 9px 34px;
  background: var(--hos-bg-surface);
  border: 1px solid var(--hos-border-light);
  border-radius: 10px;
  transition:
    background-color 140ms ease,
    border-color 140ms ease;

  &:hover,
  &:focus-within {
    background: var(--hos-bg-primary);
    border-color: var(--hos-border);
  }

  &.is-approved {
    opacity: 0.8;
  }
}

.row-delete {
  position: absolute;
  top: 8px;
  left: 7px;
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  padding: 0;
  color: #a33f3f;
  font-size: 15px;
  line-height: 1;
  pointer-events: none;
  cursor: pointer;
  background: #fbefef;
  border: 1px solid #efd3d3;
  border-radius: 50%;
  opacity: 0;
  transition: opacity 140ms ease;

  .selected-row:hover &,
  .selected-row:focus-within & {
    pointer-events: auto;
    opacity: 1;
  }

  &:hover:not(:disabled) {
    color: #fff;
    background: #c45454;
    border-color: #c45454;
  }

  &:disabled {
    display: none;
  }

  &:focus-visible {
    outline: 2px solid #c45454;
    outline-offset: 1px;
  }
}

@media (hover: none) {
  .row-delete:not(:disabled) {
    pointer-events: auto;
    opacity: 0.55;
  }
}

.selected-name {
  grid-area: name;
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
  grid-area: qty;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;

  :deep(.el-input-number) {
    width: 84px;
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
  grid-area: state;
  display: flex;
  flex-direction: column;
  gap: 2px;
  align-items: flex-end;

  small {
    max-width: 150px;
    color: var(--hos-status-danger);
    font-size: 10px;
    text-align: right;
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

.calendar-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 10px 14px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 14px;
}

.calendar-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  min-height: 36px;
}

.calendar-day-chip {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  padding: 5px 10px;
  color: var(--hos-text-secondary);
  background: var(--hos-bg-surface);
  border: 1px solid var(--hos-border-light);
  border-radius: 999px;
  cursor: pointer;
  font-size: 12px;

  .chip-count {
    color: var(--hos-text-muted);
    font-variant-numeric: tabular-nums;
    font-size: 11px;
  }

  &.is-filled {
    background: var(--hos-status-success-soft);
    border-color: #cfe6da;
  }

  &.is-suggest {
    background: var(--hos-status-warning-soft);
    border-color: #f0dcb4;
  }

  &.is-missing {
    background: var(--hos-status-danger-soft);
    border-color: #f0cfcf;
  }

  &.is-active {
    color: #fff;
    background: var(--hos-primary);
    border-color: var(--hos-primary);

    .chip-count {
      color: rgb(255 255 255 / 78%);
    }
  }
}

.calendar-month-summary {
  margin-left: auto;
  color: var(--hos-text-muted);
  font-size: 12px;

  b {
    color: var(--hos-text-primary);
    font-variant-numeric: tabular-nums;
  }
}

.calendar-toggle {
  padding: 0;
  font-size: 12px;
}

.package-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 14px;
}

.package-summary-copy {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: baseline;
  min-width: 0;

  strong {
    color: var(--hos-text-primary);
    font-size: 13px;
  }

  span {
    overflow: hidden;
    color: var(--hos-text-muted);
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.package-summary-actions {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
}

.status-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: center;
  width: 100%;
  padding: 8px 10px 6px;
  color: var(--hos-text-muted);
  font: inherit;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 10px;

  &:hover {
    background: var(--hos-bg-primary);
  }

  &:focus-visible {
    outline: 2px solid var(--hos-primary);
    outline-offset: 2px;
  }

  b {
    margin: 0 2px;
    color: var(--hos-text-primary);
    font-variant-numeric: tabular-nums;
  }

  .is-danger b {
    color: var(--hos-status-danger);
  }

  .status-hint {
    margin-left: auto;
    color: var(--hos-primary);
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

.selection-board {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  background:
    radial-gradient(circle at 0 0, rgb(52 125 104 / 8%), transparent 42%),
    var(--hos-bg-surface);
  border: 1px solid var(--hos-border-light);
  border-radius: 14px;
}

.board-head {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
}

.board-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: baseline;
  color: var(--hos-text-secondary);
  font-size: 12px;

  strong {
    color: var(--hos-text-primary);
    font-size: 22px;
    font-variant-numeric: tabular-nums;
    line-height: 1;
  }
}

.board-total {
  margin-left: 4px;
  color: var(--hos-text-muted);
  font-variant-numeric: tabular-nums;
}

.board-rejected {
  padding: 2px 7px;
  color: #a33f3f;
  font-size: 10px;
  background: #fbefef;
  border-radius: 999px;
}

.board-search {
  width: 230px;
}

.board-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.board-filter {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  padding: 4px 9px;
  color: var(--hos-text-secondary);
  font-size: 11px;
  cursor: pointer;
  background: rgb(255 255 255 / 68%);
  border: 1px solid var(--hos-border-light);
  border-radius: 999px;

  b {
    min-width: 16px;
    padding: 0 4px;
    color: var(--hos-text-muted);
    font-size: 10px;
    font-variant-numeric: tabular-nums;
    text-align: center;
    background: rgb(0 0 0 / 4%);
    border-radius: 999px;
  }

  &:hover {
    border-color: var(--hos-border);
  }

  &.is-active {
    color: #fff;
    background: var(--hos-primary);
    border-color: var(--hos-primary);

    b {
      color: #fff;
      background: rgb(255 255 255 / 22%);
    }
  }

  &:focus-visible {
    outline: 2px solid var(--hos-primary);
    outline-offset: 1px;
  }
}

.selected-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 8px;
  max-height: min(42vh, 420px);
  padding: 1px 3px 1px 1px;
  overflow: auto;
}

.selected-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  background: #fffdf9;
  border: 1px solid #e9e1d5;
  border-left: 3px solid #b7791f;
  border-radius: 12px;

  &.tone-rejected {
    background: #fff7f4;
    border-color: #efd8d1;
    border-left-color: #c45454;
  }

  &.tone-approved {
    background: #f4faf6;
    border-color: #d8e9dd;
    border-left-color: #3f8a62;
  }
}

.card-line {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.state-dot {
  flex: 0 0 auto;
  width: 7px;
  height: 7px;
  margin-top: 5px;
  background: #b7791f;
  border-radius: 50%;

  .tone-rejected & {
    background: #c45454;
  }

  .tone-approved & {
    background: #3f8a62;
  }
}

.card-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.card-title {
  display: flex;
  gap: 6px;
  align-items: center;
  min-width: 0;

  strong {
    overflow: hidden;
    color: var(--hos-text-primary);
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.card-copy small {
  color: var(--hos-text-muted);
  font-size: 10px;
}

.review-note {
  margin: 1px 0 0;
  color: #a33f3f;
  font-size: 10px;
}

.card-remove {
  flex: 0 0 auto;
  width: 24px;
  height: 24px;
  padding: 0;
  color: var(--hos-text-muted);
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 6px;

  &:hover {
    color: #a33f3f;
    background: #fbefef;
  }

  &:disabled {
    opacity: 0.25;
    cursor: not-allowed;
  }
}

.card-quantity {
  display: flex;
  gap: 4px;
  align-items: center;
  padding-left: 15px;
}

.qty-step {
  width: 30px;
  height: 30px;
  padding: 0;
  color: var(--hos-text-secondary);
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  background: #fff;
  border: 1px solid var(--hos-border-light);
  border-radius: 8px;

  &:hover:not(:disabled) {
    color: var(--hos-primary);
    border-color: var(--hos-primary);
  }

  &:disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }
}

.board-number {
  width: 68px;

  :deep(.el-input__wrapper) {
    padding: 0 6px;
    background: #fff;
    border-radius: 8px;
    box-shadow: 0 0 0 1px var(--hos-border-light) inset;
  }

  :deep(.el-input__inner) {
    color: var(--hos-text-primary);
    font-size: 13px;
    font-variant-numeric: tabular-nums;
    text-align: center;
  }
}

.quick-set {
  display: flex;
  gap: 2px;
  margin-left: 2px;

  button {
    width: 22px;
    height: 22px;
    padding: 0;
    color: var(--hos-text-muted);
    font-size: 10px;
    cursor: pointer;
    background: transparent;
    border: 1px solid var(--hos-border-light);
    border-radius: 6px;

    &:hover:not(:disabled) {
      color: var(--hos-primary);
      background: var(--hos-primary-soft);
      border-color: var(--hos-primary);
    }

    &:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }
  }
}

.card-unit {
  margin-left: auto;
  color: var(--hos-text-muted);
  font-size: 10px;
}

.board-empty {
  margin: 2px 0;
  color: var(--hos-text-muted);
  font-size: 11px;
  text-align: center;
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

  .selected-grid {
    grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
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

  .board-head {
    flex-direction: column;
    align-items: stretch;
  }

  .board-search {
    width: 100%;
  }

  .selected-grid {
    grid-template-columns: 1fr;
    max-height: none;
  }
}
</style>
