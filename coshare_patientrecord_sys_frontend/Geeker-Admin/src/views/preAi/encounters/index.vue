<template>
  <!-- eslint-disable vue/html-closing-bracket-newline -->
  <div class="pre-ai-page">
    <header class="page-hero" :class="{ 'is-context-compact': topContextCompacted }" @pointermove="scheduleTopContextCompaction">
      <div v-if="!topContextCompacted" class="page-hero__copy">
        <el-tag type="primary" effect="plain">患者就诊</el-tag>
        <h2>登记与事实采集</h2>
      </div>
      <button v-else type="button" class="context-restore" @click="restoreTopContext">展开说明</button>
      <div class="hero-actions">
        <el-button class="patient-archive-trigger" type="primary" @click="patientDrawerOpen = true">
          <span class="patient-archive-trigger__glyph"
            ><el-icon :size="22"><User /></el-icon
          ></span>
          <span class="patient-archive-trigger__copy">
            患者主档案
            <small>筛选与查看全部在管患者</small>
          </span>
          <span class="patient-archive-trigger__count">{{ filteredPatientCases.length }}</span>
        </el-button>
        <el-button :icon="Refresh" @click="refreshWorkspace">刷新</el-button>
        <el-button v-if="workspace" @click="openResponsibilityTimeline">责任时间轴</el-button>
        <el-button v-if="workspace?.admissionProfile" @click="openAdmissionProfile">住院补录</el-button>
        <el-button v-if="canImportLegacy" :icon="FolderOpened" @click="openLegacyDialog">导入进行中的旧患者</el-button>
        <el-button v-if="canCreateEncounter" type="primary" :icon="Plus" @click="openCreateDialog">就诊登记并发号</el-button>
      </div>
    </header>
    <el-alert
      v-if="handoffNotice"
      class="handoff-notice"
      type="success"
      :closable="true"
      show-icon
      :title="handoffNotice"
      @close="handoffNotice = ''"
    />

    <el-dialog v-model="patientDrawerOpen" class="patient-archive-dialog" width="min(1180px, 92vw)" top="7vh" destroy-on-close>
      <template #header>
        <div class="sidebar-title__head patient-archive-dialog__head">
          <div>
            <strong>患者主档案</strong>
            <small>{{ patientCases.length }} 位患者 · 按录入时间从新到旧排列</small>
          </div>
        </div>
      </template>
      <section class="patient-archive-dialog__body">
        <div class="patient-archive-filters">
          <el-input v-model="keyword" clearable placeholder="姓名/病例标识" :prefix-icon="Search" />
          <el-select v-model="careSituationFilter" class="care-situation-filter" aria-label="就诊情况筛选">
            <el-option label="全部就诊情况" value="ALL" />
            <el-option label="门诊" value="OUTPATIENT" />
            <el-option label="住院" value="INPATIENT" />
            <el-option label="低保" value="LOW_INCOME" />
          </el-select>
          <el-date-picker
            v-model="patientArchiveDate"
            class="patient-archive-date-filter"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择录入日期"
            clearable
            aria-label="录入日期筛选"
          />
        </div>
        <div class="patient-archive-toolbar">
          <div class="patient-archive-view-tags" role="tablist" aria-label="患者主档案视图切换">
            <button
              type="button"
              :class="{ active: patientArchiveView === 'LIST' }"
              :aria-selected="patientArchiveView === 'LIST'"
              role="tab"
              @click="patientArchiveView = 'LIST'"
            >
              横向列表
            </button>
            <button
              type="button"
              :class="{ active: patientArchiveView === 'MASONRY' }"
              :aria-selected="patientArchiveView === 'MASONRY'"
              role="tab"
              @click="patientArchiveView = 'MASONRY'"
            >
              瀑布流
            </button>
          </div>
          <small v-if="patientArchiveView === 'MASONRY'" class="patient-archive-toolbar__hint">
            {{ patientArchiveMasonryLoading ? "正在加载图片与主诉" : "保留关键信息，图片点击可放大" }}
          </small>
        </div>
        <el-scrollbar height="min(62vh, 620px)">
          <div v-if="patientArchiveView === 'LIST'" class="patient-archive-card-grid">
            <article
              v-for="item in filteredPatientCases"
              :key="item.id"
              class="encounter-row"
              :class="{ active: item.id === selectedPatientCaseId }"
            >
              <button type="button" class="encounter-row-main" @click="selectPatientCase(item)">
                <div class="encounter-row__head">
                  <strong>{{ item.patientName || "待补姓名" }}</strong>
                  <el-tag v-if="item.latestEncounter" size="small" :type="encounterStatusType(item.latestEncounter.status)">
                    {{ item.visitCount }} 次来访
                  </el-tag>
                </div>
                <span>{{ item.latestEncounter?.caseToken || "尚无子病历" }}</span>
                <small>录入 {{ formatPatientCaseRecordTime(item) }} · {{ routeLabel(item.latestEncounter?.route) }}</small>
                <div v-if="item.latestEncounter?.careSituationTags" class="encounter-card__care-tags">
                  <el-tag
                    v-for="tag in item.latestEncounter.careSituationTags.split(',')"
                    :key="tag"
                    size="small"
                    effect="plain"
                    >{{ tag }}</el-tag
                  >
                </div>
                <div v-if="item.latestEncounter" class="mini-steps">
                  <i
                    v-for="stage in preAiStages"
                    :key="stage.code"
                    :title="`${stage.title}：${stageStatusLabel[item.latestEncounter.stageStatuses?.[stage.code] || 'DRAFT']}`"
                    :class="stageStatusClass(item.latestEncounter.stageStatuses?.[stage.code] || 'DRAFT')"
                  ></i>
                </div>
              </button>
              <button
                v-if="canCreateEncounter"
                type="button"
                class="encounter-row-followup"
                @click.stop="openFollowUpDialog(item)"
              >
                新增复诊
              </button>
            </article>
          </div>
          <div v-else class="patient-archive-masonry">
            <article
              v-for="item in filteredPatientCases"
              :key="item.id"
              :ref="element => setPatientArchiveMasonryCardRef(element, item)"
              class="patient-archive-masonry-card"
              :class="{ active: item.id === selectedPatientCaseId }"
              :data-patient-case-id="item.id"
            >
              <button type="button" class="patient-archive-masonry-main" @click="selectPatientCase(item)">
                <header class="patient-archive-masonry-head">
                  <strong>{{ item.patientName || "待补姓名" }}</strong>
                  <span>{{ item.visitCount }} 次来访</span>
                </header>
                <div class="patient-archive-info-tags">
                  <span
                    v-for="tag in patientArchiveCardTags(item)"
                    :key="tag.key"
                    :class="patientArchiveTagClass(tag.key, item.id)"
                  >
                    {{ tag.label }}
                  </span>
                </div>
                <div v-if="patientArchiveCardImages(item).length" class="patient-archive-image-strip" @click.stop>
                  <template v-for="attachment in patientArchiveCardImages(item)" :key="attachment.id">
                    <el-image
                      v-if="patientArchiveImageUrls[attachment.id]"
                      class="patient-archive-thumbnail"
                      :src="patientArchiveImageUrls[attachment.id]"
                      :preview-src-list="patientArchiveCardPreviewUrls(item)"
                      :initial-index="patientArchiveCardPreviewIndex(item, attachment)"
                      fit="contain"
                      preview-teleported
                      hide-on-click-modal
                    />
                    <div v-else class="patient-archive-thumbnail-state">
                      {{ patientArchiveImageErrors[attachment.id] || "缩略图加载中" }}
                    </div>
                  </template>
                </div>
                <div v-else class="patient-archive-thumbnail-empty">
                  {{ patientArchiveCardEmptyText(item) }}
                </div>
                <footer class="patient-archive-masonry-foot">
                  <span>{{ item.latestEncounter?.caseToken || "尚无子病历" }}</span>
                  <small>{{ formatPatientCaseRecordTime(item) }}</small>
                </footer>
              </button>
              <button
                v-if="canCreateEncounter"
                type="button"
                class="encounter-row-followup"
                @click.stop="openFollowUpDialog(item)"
              >
                新增复诊
              </button>
            </article>
          </div>
          <el-empty v-if="!filteredPatientCases.length" :image-size="92" description="暂无患者主档案" />
        </el-scrollbar>
      </section>
    </el-dialog>

    <section
      ref="workspaceShellRef"
      class="workspace-shell"
      :class="{ 'with-history': historyPanelOpen && workspace }"
      :style="historyPaneStyle"
    >
      <main v-loading="workspaceLoading" class="encounter-workspace">
        <el-empty v-if="!workspace" description="请点击顶部患者主档案选择患者，或新建前置病历" />
        <template v-else>
          <WorkflowSidebar
            :workspace="workspace"
            :cards="workflowCards"
            :compact="workflowContextCompacted"
            :encounter-status-label="encounterStatusLabel"
            :encounter-status-type="encounterStatusType"
            :route-label="routeLabel"
            :status-of="workflowCardStatus"
            :status-label="workflowCardStatusLabel"
            :status-type="stageStatusType"
            :is-active="isWorkflowCardActive"
            :is-current="isCurrentWorkflowCard"
            @select="selectWorkflowCard"
            @restore="restoreWorkflowContext"
            @interact="scheduleWorkflowContextCompaction"
          />

          <section v-if="!workflowSelected" class="workflow-empty-panel">
            <el-empty :image-size="96" description="请选择上方岗位节点" />
          </section>
          <template v-else>
            <section
              class="patient-banner"
              :class="{ 'is-context-compact': topContextCompacted }"
              @pointermove="scheduleTopContextCompaction"
            >
              <div class="patient-banner__identity">
                <span class="patient-avatar">{{ (workspace.encounter.patient.patientName || "患").slice(0, 1) }}</span>
                <div>
                  <small v-if="!topContextCompacted">当前就诊患者</small>
                  <h3>
                    {{ workspace.encounter.patient.patientName || "待补姓名" }}
                  </h3>
                  <p v-if="!topContextCompacted">
                    {{ workspace.encounter.caseToken }} · {{ workspace.encounter.patient.gender || "待补性别" }} ·
                    {{ workspace.encounter.patient.age || "待补年龄" }} ·
                    {{ workspace.encounter.patient.visitDate || "待补就诊时间" }}
                  </p>
                  <p v-else class="patient-banner__compact-meta">
                    {{ workspace.encounter.caseToken }} · {{ routeLabel(workspace.encounter.route) }}
                  </p>
                </div>
              </div>
              <div class="patient-banner__overview">
                <div class="context-stat">
                  <small>流程进度</small>
                  <strong>{{ workflowProgress.completed }}/{{ workflowProgress.total }}</strong>
                </div>
                <div class="context-stat" :class="{ warning: workflowProgress.returned }">
                  <small>待处理异常</small>
                  <strong>{{ workflowProgress.returned }}</strong>
                </div>
                <div class="patient-banner__meta">
                  <el-tag :type="encounterStatusType(workspace.encounter.status)">
                    {{ encounterStatusLabel[workspace.encounter.status] || workspace.encounter.status }}
                  </el-tag>
                  <span>{{ routeLabel(workspace.encounter.route) }}</span>
                  <span>{{ treatmentPathLabel(workspace.encounter.treatmentPath) }}</span>
                </div>
              </div>
            </section>

            <section v-if="registrationImageAttachments.length" class="patient-dr-strip">
              <div class="patient-dr-strip__head">
                <div>
                  <span class="section-caption">患者信息 · 优先视觉核对</span>
                  <strong>DR 影像（前台/化验岗采集）</strong>
                </div>
                <el-tag type="primary" effect="plain">{{ registrationImageAttachments.length }} 张</el-tag>
              </div>
              <AttachmentPreviewGallery
                :attachments="registrationImageAttachments"
                compact
                @download="downloadPreAiAttachmentApi"
              />
            </section>

            <section v-if="endoscopyReportAttachments.length" class="patient-dr-strip">
              <div class="patient-dr-strip__head">
                <div>
                  <span class="section-caption">患者信息 · 辅助检查报告</span>
                  <strong>胃肠镜检查报告单</strong>
                </div>
                <el-tag type="primary" effect="plain">{{ endoscopyReportAttachments.length }} 张</el-tag>
              </div>
              <AttachmentPreviewGallery
                :attachments="endoscopyReportAttachments"
                compact
                @download="downloadPreAiAttachmentApi"
              />
            </section>

            <section v-if="encounterHistory.length > 1" class="history-entry-bar">
              <div>
                <strong>本次为第 {{ workspace.encounter.visitNo }} 次就诊</strong>
                <small>可在右侧只读回查初诊、上次或其他历次病历，当前未保存内容不会受影响。</small>
              </div>
              <el-button type="primary" plain @click="openHistoricalComparison">查看历次病历</el-button>
            </section>

            <div class="workspace-modebar">
              <div>
                <span>{{ activeWorkflowTitle }}</span>
                <small>{{ activeWorkflowOwner }}</small>
              </div>
              <div class="mode-tags" :class="{ preview: editorMode === 'PREVIEW' }" role="group" aria-label="填写态和预览态切换">
                <span class="mode-slider" aria-hidden="true"></span>
                <button
                  type="button"
                  class="mode-pill edit"
                  :class="{ active: editorMode === 'EDIT' }"
                  :aria-pressed="editorMode === 'EDIT'"
                  @click="editorMode = 'EDIT'"
                >
                  填写态
                </button>
                <button
                  type="button"
                  class="mode-pill preview"
                  :class="{ active: editorMode === 'PREVIEW' }"
                  :aria-pressed="editorMode === 'PREVIEW'"
                  @click="openPreviewMode"
                >
                  模板预览态
                </button>
              </div>
              <el-tag :type="stageStatusType(workflowCardStatus(activeWorkflowCard))">
                {{ workflowCardStatusLabel(activeWorkflowCard) }}
              </el-tag>
            </div>

            <Transition name="workspace-mode" mode="out-in">
              <MedicalRecordPreview
                v-if="editorMode === 'PREVIEW'"
                key="preview"
                :case-token="workspace.encounter.caseToken"
                :visit-date="workspace.encounter.patient.visitDate"
                :route-label="routeLabel(workspace.encounter.route)"
                :sections="documentPreviewSections"
                :inspection-images="inspectionPreviewImages"
              />

              <div v-else key="edit" class="editor-mode-content">
                <Transition name="stage-switch" mode="out-in">
                  <section v-if="selectedPanel === 'STAGE'" :key="`stage-${selectedStageCode}`" class="stage-panel">
                    <template v-if="selectedStageCode !== 'REVIEW'">
                      <div class="panel-heading">
                        <div>
                          <span class="work-surface-kicker">当前填写</span>
                          <h3>{{ selectedStage.title }}</h3>
                        </div>
                        <div class="heading-tags">
                          <el-tag effect="plain">责任岗位：{{ selectedStage.owner }}</el-tag>
                          <el-tag :type="stageStatusType(selectedStageSubmission.status)">
                            {{ stageStatusLabel[selectedStageSubmission.status] }}
                          </el-tag>
                        </div>
                      </div>

                      <el-alert
                        v-if="selectedStageSubmission.status === 'RETURNED'"
                        type="warning"
                        show-icon
                        :closable="false"
                        :title="`医生退回：${selectedStageSubmission.returnedReason || '请核对后重新提交'}`"
                      />
                      <el-alert
                        v-if="selectedStageCode === 'NURSING' && !nursingUnlocked"
                        type="warning"
                        show-icon
                        :closable="false"
                        title="护理部暂未开放：待接诊室完成交接并确定住院后填写；判定门诊的病例自动跳过本环节"
                      />
                      <el-alert
                        v-if="!canModifySelectedStage && !(selectedStageCode === 'NURSING' && !nursingUnlocked)"
                        type="info"
                        show-icon
                        :closable="false"
                        :title="
                          selectedStageSubmission.status === 'COMPLETED'
                            ? '本阶段已完成；当前账号没有纠错权限。'
                            : `当前账号为${currentRole ? roleLabel(currentRole) : '未授权岗位'}，本页仅可查看。`
                        "
                      />

                      <section v-if="selectedStageCode === 'RECEPTION'" class="upstream-image-section priority-image-section">
                        <header class="upstream-image-heading">
                          <div>
                            <strong>检查影像优先核对</strong>
                            <small>接诊前先核对检查室上传的一手影像，点击图片可查看原图。</small>
                          </div>
                          <el-tag :type="inspectionImageAttachments.length ? 'primary' : 'info'" effect="plain">
                            {{ inspectionImageAttachments.length ? `${inspectionImageAttachments.length} 张影像` : "暂无影像" }}
                          </el-tag>
                        </header>
                        <AttachmentPreviewGallery
                          v-if="inspectionImageAttachments.length"
                          :attachments="inspectionImageAttachments"
                          @download="downloadPreAiAttachmentApi"
                        />
                        <el-empty v-else :image-size="64" description="检查室尚未上传原始图片" />
                      </section>

                      <div v-if="selectedStageCode === 'INSPECTION'" class="inspection-view-tabs">
                        <button type="button" :class="{ active: inspectionView === 'CURRENT' }" @click="showCurrentInspection">
                          本次检查
                        </button>
                        <button type="button" :class="{ active: inspectionView === 'HISTORY' }" @click="showInspectionTimeline">
                          检查与复查时间轴
                        </button>
                      </div>

                      <section
                        v-if="selectedStageCode === 'INSPECTION' && inspectionView === 'HISTORY'"
                        v-loading="timelineLoading"
                        class="inspection-timeline"
                      >
                        <el-empty v-if="!inspectionTimeline.length" description="暂无历次检查记录" />
                        <article
                          v-for="(node, index) in inspectionTimeline"
                          :key="node.encounterId"
                          class="timeline-node"
                          :class="{
                            latest: index === inspectionTimeline.length - 1
                          }"
                        >
                          <i class="timeline-dot"></i>
                          <header>
                            <div>
                              <strong>第 {{ node.visitNo }} 次来访 · {{ node.visitDate || "日期待补" }}</strong>
                              <small>{{ node.caseToken }} · {{ routeLabel(node.route) }}</small>
                            </div>
                            <el-tag :type="stageStatusType(node.inspectionStatus)">{{
                              stageStatusLabel[node.inspectionStatus]
                            }}</el-tag>
                          </header>
                          <div v-if="node.inspection.nextReviewAt || node.inspection.nextReviewNote" class="timeline-follow-up">
                            <strong>复查安排</strong>
                            <span v-if="node.inspection.nextReviewAt"
                              >下次复查：{{ humanValue(node.inspection.nextReviewAt) }}</span
                            >
                            <p v-if="node.inspection.nextReviewNote">{{ humanValue(node.inspection.nextReviewNote) }}</p>
                          </div>
                          <div class="timeline-facts">
                            <div v-for="entry in inspectionTimelineEntries(node.inspection)" :key="entry[0]">
                              <span>{{ fieldLabel("INSPECTION", entry[0]) }}</span>
                              <p>{{ humanValue(entry[1]) }}</p>
                            </div>
                          </div>
                          <div v-if="node.attachments.length" class="timeline-attachment-groups">
                            <section
                              v-for="group in timelineAttachmentGroups(node.attachments)"
                              :key="group.id"
                              class="timeline-attachment-group"
                            >
                              <strong>{{ group.name }}</strong>
                              <AttachmentPreviewGallery
                                :attachments="group.items"
                                compact
                                @download="downloadPreAiAttachmentApi"
                              />
                            </section>
                          </div>
                          <details v-if="hasVisitMeta(node.visitMeta)" class="visit-meta-summary">
                            <summary>来访描述与交费参考</summary>
                            <p v-if="node.visitMeta.visitReason">来访原因：{{ node.visitMeta.visitReason }}</p>
                            <p v-if="node.visitMeta.description">描述：{{ node.visitMeta.description }}</p>
                            <p>交费参考：{{ paymentStatusLabel(node.visitMeta.paymentStatus) }}</p>
                          </details>
                        </article>
                      </section>

                      <section v-if="selectedStageCode === 'RECEPTION'" class="upstream-image-section primary-evidence-section">
                        <div class="primary-evidence-heading">
                          <div>
                            <span class="section-caption">接诊首要复核资料</span>
                            <strong>检查室原始图片</strong>
                            <small>先核对一手图片，再结合检查事实完成接诊评估。</small>
                          </div>
                          <el-tag effect="plain">{{ inspectionImageAttachments.length }} 张</el-tag>
                        </div>
                        <AttachmentPreviewGallery
                          v-if="inspectionImageAttachments.length"
                          :attachments="inspectionImageAttachments"
                          @download="downloadPreAiAttachmentApi"
                        />
                        <el-empty v-else :image-size="64" description="检查室尚未上传原始图片" />
                      </section>

                      <section
                        v-if="upstreamStages.length && (selectedStageCode !== 'INSPECTION' || inspectionView === 'CURRENT')"
                        class="upstream-section"
                      >
                        <header class="upstream-heading">
                          <div>
                            <strong>前置岗位事实</strong>
                            <small>关键结论直接展示，完整采集项按岗位展开核对。</small>
                          </div>
                          <el-tag type="info" effect="plain">{{ upstreamStages.length }} 个岗位</el-tag>
                        </header>
                        <div class="upstream-stage-list">
                          <article v-for="item in upstreamStages" :key="item.stageCode" class="upstream-stage-card">
                            <header>
                              <div class="upstream-stage-title">
                                <strong>{{ stageByCode(item.stageCode).title }}</strong>
                                <small>{{ upstreamStageTime(item) }}</small>
                              </div>
                              <el-tag :type="stageStatusType(item.status)" size="small" effect="plain">
                                {{ stageStatusLabel[item.status] }}
                              </el-tag>
                            </header>
                            <div class="upstream-summary-label">
                              <span>重点事实</span>
                              <small>优先核对影响本岗位判断的已完成信息</small>
                            </div>
                            <div class="upstream-summary-grid">
                              <div v-for="entry in upstreamSummaryEntries(item)" :key="entry[0]">
                                <span>{{ fieldLabel(item.stageCode, entry[0]) }}</span>
                                <strong :title="humanValue(entry[1])">{{ humanValue(entry[1]) }}</strong>
                              </div>
                            </div>
                            <el-collapse class="upstream-detail-collapse">
                              <el-collapse-item :title="`查看全部 ${nonEmptyEntries(item.data).length} 项已采集事实`">
                                <dl class="read-only-grid">
                                  <div v-for="entry in nonEmptyEntries(item.data)" :key="entry[0]">
                                    <dt>{{ fieldLabel(item.stageCode, entry[0]) }}</dt>
                                    <dd>{{ humanValue(entry[1]) }}</dd>
                                  </div>
                                </dl>
                              </el-collapse-item>
                            </el-collapse>
                          </article>
                        </div>
                      </section>

                      <ClinicalTemplateToolbar
                        v-if="
                          ['INSPECTION', 'RECEPTION'].includes(selectedStageCode) &&
                          (selectedStageCode !== 'INSPECTION' || inspectionView === 'CURRENT')
                        "
                        :simplified="selectedStageCode === 'INSPECTION'"
                        :model-value="clinicalTemplateIds(selectedStageCode)"
                        :slot-values="stageForms[selectedStageCode].clinicalTemplateSlots || {}"
                        :disabled="!canModifySelectedStage"
                        :auto-match-label="selectedStageCode === 'RECEPTION' ? autoMatchedTemplateLabel : ''"
                        @update:model-value="value => onClinicalTemplateSelection(selectedStageCode, value)"
                        @update:slot-values="value => updateStageTemplateSlots(selectedStageCode, value)"
                        @apply="(mode, ids) => applyStageClinicalTemplate(selectedStageCode, mode, ids)"
                      />

                      <section
                        v-if="selectedStageCode === 'INSPECTION' && inspectionView === 'CURRENT'"
                        class="inspection-narrative-edit"
                      >
                        <div class="narrative-heading">
                          <strong>检查记录</strong>
                          <small>模板已按默认变量生成全文，可直接在本框修改；切换病种将重新生成并覆盖</small>
                        </div>
                        <el-input
                          v-model="stageForms.INSPECTION.inspectionNarrative"
                          type="textarea"
                          :rows="16"
                          :disabled="!canModifySelectedStage"
                          @update:model-value="markStageDirty('INSPECTION')"
                        />
                      </section>

                      <section v-if="selectedStageCode === 'NURSING'" class="nursing-vitals-section">
                        <header class="nursing-vitals-heading">
                          <div>
                            <strong>四测信息采集</strong>
                            <small>
                              住院患者生命体征按轮次记录：填写后点击「记录本轮」，下方时间轴逐轮汇总；仅系统留存，不进入导出文档。
                            </small>
                          </div>
                          <el-tag effect="dark">重点区域</el-tag>
                        </header>
                        <div class="nursing-vitals-input">
                          <label v-for="field in nursingVitalFields" :key="field.key" class="vital-field">
                            <span>{{ field.label }}</span>
                            <StructuredField
                              :model-value="stageForms.NURSING[field.key]"
                              :field="field"
                              :form="stageForms.NURSING"
                              :disabled="isStageFieldDisabled(field)"
                              @update:model-value="
                                value => {
                                  stageForms.NURSING[field.key] = value;
                                  markStageDirty('NURSING');
                                }
                              "
                            />
                          </label>
                          <div class="vital-record-action">
                            <el-button type="primary" :disabled="!canModifySelectedStage" @click="recordVitalRound">
                              记录本轮
                            </el-button>
                          </div>
                        </div>
                        <div v-if="vitalRounds.length" class="nursing-vitals-summary">
                          <div class="vitals-summary-title">
                            <strong>四测轮次汇总</strong>
                            <small>共 {{ vitalRounds.length }} 轮，最新一轮在最上方</small>
                          </div>
                          <el-timeline class="vitals-timeline">
                            <el-timeline-item
                              v-for="item in vitalRoundItems"
                              :key="`${item.idx}-${vitalRoundTime(item.round)}`"
                              :timestamp="vitalRoundTime(item.round)"
                              :type="
                                vitalRoundHasCritical(item.round)
                                  ? 'danger'
                                  : vitalRoundHasAbnormal(item.round)
                                    ? 'warning'
                                    : 'primary'
                              "
                              placement="top"
                            >
                              <div class="vital-round-card">
                                <div class="vital-round-values">
                                  <span
                                    v-for="entry in vitalRoundEntries(item.round)"
                                    :key="entry.key"
                                    class="vital-value-chip"
                                    :class="{ abnormal: vitalEntryAbnormal(entry.status) }"
                                  >
                                    <em>{{ entry.label }}</em>
                                    <strong>{{ entry.text }}</strong>
                                    <i v-if="entry.status && vitalEntryAbnormal(entry.status)">{{ entry.status }}</i>
                                  </span>
                                </div>
                                <el-button
                                  link
                                  type="danger"
                                  size="small"
                                  :disabled="!canModifySelectedStage"
                                  @click="removeVitalRound(item.idx)"
                                >
                                  删除本轮
                                </el-button>
                              </div>
                            </el-timeline-item>
                          </el-timeline>
                        </div>
                        <p v-else class="vitals-empty-hint">暂无轮次记录：填写上方数值后点击「记录本轮」生成第一轮汇总。</p>
                      </section>

                      <div v-if="selectedStageCode === 'NURSING'" class="narrative-heading nursing-history-heading">
                        <strong>病史采集与护理评估</strong>
                        <small>病史采集随完成交接归档进前置资料文档；护理评估项仅系统留存</small>
                      </div>

                      <section v-if="selectedStageCode !== 'INSPECTION' && secondaryStageFieldsCount" class="field-noise-toolbar">
                        <div>
                          <strong>精简填写视图</strong>
                          <small>默认收起低频补充项，仅降低录入噪音；原字段、自动生成和提交载荷保持不变。</small>
                        </div>
                        <el-button size="small" plain @click="compactStageFieldsExpanded = !compactStageFieldsExpanded">
                          {{ compactStageFieldsExpanded ? "收起低频字段" : `展开 ${secondaryStageFieldsCount} 个可选字段` }}
                        </el-button>
                      </section>

                      <el-form
                        v-if="selectedStageCode !== 'INSPECTION' || inspectionView === 'CURRENT'"
                        label-position="top"
                        class="stage-form"
                      >
                        <div class="form-grid">
                          <el-form-item
                            v-for="field in stageFormFields"
                            :key="field.key"
                            :label="field.label"
                            :required="field.required"
                            v-show="!isSecondaryStageField(field) || compactStageFieldsExpanded"
                            :class="{
                              'span-2': field.span === 2,
                              'priority-field': field.emphasis === 'priority',
                              'secondary-field': isSecondaryStageField(field),
                              'history-intake-field': isHistoryIntakeKey(field.key) && selectedStageCode === 'INSPECTION'
                            }"
                          >
                            <StructuredField
                              v-if="['measurement', 'repeatable', 'template-text'].includes(field.kind)"
                              v-model="stageForms[selectedStageCode][field.key]"
                              :field="field"
                              :form="stageForms[selectedStageCode]"
                              :generated-text="generatedTemplateText(field, stageForms[selectedStageCode])"
                              :source-hash="templateSourceHash(field)"
                              :disabled="isStageFieldDisabled(field)"
                              @patch="value => patchStageForm(selectedStageCode, value)"
                            />
                            <el-input
                              v-else-if="field.kind === 'input' || field.kind === 'number'"
                              v-model="stageForms[selectedStageCode][field.key]"
                              :type="field.kind === 'number' ? 'number' : 'text'"
                              :placeholder="field.placeholder"
                              :disabled="isStageFieldDisabled(field)"
                              @update:model-value="markStageDirty(selectedStageCode)"
                            />
                            <div v-else-if="field.kind === 'textarea'" class="textarea-field">
                              <div v-if="field.quickTemplates?.length" class="quick-template-actions">
                                <el-button
                                  v-for="template in field.quickTemplates"
                                  :key="template.label"
                                  size="small"
                                  plain
                                  :disabled="isStageFieldDisabled(field)"
                                  @click="applyQuickTemplate(field.key, template.value)"
                                >
                                  {{ template.label }}
                                </el-button>
                              </div>
                              <el-input
                                v-model="stageForms[selectedStageCode][field.key]"
                                type="textarea"
                                :rows="field.rows || 3"
                                :placeholder="field.placeholder"
                                :disabled="isStageFieldDisabled(field)"
                                @update:model-value="markStageDirty(selectedStageCode)"
                              />
                            </div>
                            <div v-else-if="field.kind === 'diagnosis'" class="diagnosis-field">
                              <el-select
                                v-model="stageForms[selectedStageCode][field.key]"
                                filterable
                                allow-create
                                default-first-option
                                clearable
                                :placeholder="field.placeholder || `请选择${field.label}`"
                                :disabled="isStageFieldDisabled(field)"
                                @update:model-value="markStageDirty(selectedStageCode)"
                              >
                                <el-option
                                  v-for="option in fieldOptions(field)"
                                  :key="option.value"
                                  :label="option.label"
                                  :value="option.value"
                                />
                              </el-select>
                              <el-input
                                v-if="field.supplementKey"
                                v-model="stageForms[selectedStageCode][field.supplementKey]"
                                type="textarea"
                                :rows="2"
                                placeholder="需要时补充一句自然语言所见或判断依据（可选）"
                                :disabled="isStageFieldDisabled(field)"
                                @update:model-value="markStageDirty(selectedStageCode)"
                              />
                            </div>
                            <CreatableSelect
                              v-else-if="field.kind === 'select' && field.creatable"
                              v-model="stageForms[selectedStageCode][field.key]"
                              :options="fieldOptions(field)"
                              :placeholder="field.placeholder || `请选择或直接输入${field.label}`"
                              :disabled="isStageFieldDisabled(field)"
                              @update:model-value="markStageDirty(selectedStageCode)"
                            />
                            <el-select
                              v-else-if="field.kind === 'select'"
                              v-model="stageForms[selectedStageCode][field.key]"
                              clearable
                              filterable
                              default-first-option
                              :placeholder="field.placeholder || `请选择${field.label}`"
                              :disabled="isStageFieldDisabled(field)"
                              @update:model-value="markStageDirty(selectedStageCode)"
                            >
                              <el-option
                                v-for="option in fieldOptions(field)"
                                :key="option.value"
                                :label="option.label"
                                :value="option.value"
                              />
                            </el-select>
                            <div v-else-if="field.kind === 'multi'" class="multi-field">
                              <el-select
                                v-model="stageForms[selectedStageCode][field.key]"
                                multiple
                                clearable
                                filterable
                                :allow-create="field.creatable || !fieldOptions(field).length"
                                default-first-option
                                :placeholder="field.placeholder || `请选择或输入${field.label}`"
                                :disabled="isStageFieldDisabled(field)"
                                @update:model-value="markStageDirty(selectedStageCode)"
                              >
                                <el-option
                                  v-for="option in fieldOptions(field)"
                                  :key="option.value"
                                  :label="option.label"
                                  :value="option.value"
                                />
                              </el-select>
                              <el-input
                                v-if="field.supplementKey"
                                v-model="stageForms[selectedStageCode][field.supplementKey]"
                                type="textarea"
                                :rows="2"
                                placeholder="可补充口语化描述（如患者自述过敏反应、具体过敏原，可选）"
                                :disabled="isStageFieldDisabled(field)"
                                @update:model-value="markStageDirty(selectedStageCode)"
                              />
                            </div>
                            <el-date-picker
                              v-else
                              v-model="stageForms[selectedStageCode][field.key]"
                              :type="field.kind === 'date' ? 'date' : 'datetime'"
                              :value-format="field.kind === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM-DD HH:mm:ss'"
                              :placeholder="`请选择${field.label}`"
                              :disabled="isStageFieldDisabled(field)"
                              @update:model-value="markStageDirty(selectedStageCode)"
                            />
                          </el-form-item>
                        </div>
                      </el-form>

                      <DutyAssignmentPanel
                        v-if="selectedStageCode === 'REGISTRATION'"
                        :assignments="workspace.dutyAssignments || []"
                        :disabled="!canMaintainDuties"
                        :saving="actionLoading"
                        @save="saveDutyAssignments"
                      />

                      <section v-if="selectedStageCode === 'REGISTRATION'" class="dr-image-section">
                        <header class="dr-image-heading">
                          <div>
                            <span class="section-caption">前台岗影像采集</span>
                            <strong>DR 影像资料</strong>
                            <small>作为独立附件存储，不参与前置病历元数据生成；上传后各岗位在患者信息区优先可见。</small>
                          </div>
                          <el-tag :type="registrationImageAttachments.length ? 'primary' : 'info'" effect="plain">
                            {{ registrationImageAttachments.length ? `${registrationImageAttachments.length} 张` : "暂无" }}
                          </el-tag>
                        </header>
                        <AttachmentPreviewGallery
                          v-if="registrationImageAttachments.length"
                          :attachments="registrationImageAttachments"
                          :removable="canModifySelectedStage"
                          @download="downloadPreAiAttachmentApi"
                          @remove="removeImageAttachment"
                        />
                        <el-empty v-else :image-size="56" description="暂无 DR 影像，可在下方上传或拍照采集" />
                        <div v-if="canModifySelectedStage" class="upload-actions">
                          <label class="upload-button">
                            <input
                              type="file"
                              multiple
                              accept="image/*"
                              @change="event => uploadAttachments(event, 'REGISTRATION', undefined, false, '前台DR影像')"
                            />
                            <el-icon><Upload /></el-icon> 选择 DR 图片
                          </label>
                          <label class="upload-button camera-button">
                            <input
                              type="file"
                              accept="image/*"
                              capture="environment"
                              @change="event => uploadAttachments(event, 'REGISTRATION', undefined, false, '前台DR影像')"
                            />
                            <el-icon><Camera /></el-icon> 拍照上传
                          </label>
                        </div>
                        <el-progress
                          v-if="attachmentUpload.total"
                          :percentage="attachmentUpload.percent"
                          :status="
                            attachmentUpload.failed
                              ? 'warning'
                              : attachmentUpload.success === attachmentUpload.total
                                ? 'success'
                                : undefined
                          "
                        />
                        <small v-if="attachmentUpload.total" class="upload-summary">
                          共 {{ attachmentUpload.total }} 个，成功 {{ attachmentUpload.success }} 个，失败
                          {{ attachmentUpload.failed }} 个
                        </small>
                        <div v-if="voidedAttachments.length" class="voided-attachments-row">
                          <span class="voided-caption">已作废 {{ voidedAttachments.length }} 张（可恢复）</span>
                          <el-button
                            v-for="attachment in voidedAttachments"
                            :key="attachment.id"
                            link
                            type="primary"
                            size="small"
                            @click="restoreAttachment(attachment)"
                          >
                            恢复 {{ attachment.fileName }}
                          </el-button>
                        </div>
                      </section>

                      <section
                        v-if="
                          (selectedStageCode !== 'INSPECTION' || inspectionView === 'CURRENT') &&
                          (selectedStageCode === 'INSPECTION' || selectedStageCode === 'SURGERY')
                        "
                        class="attachment-section"
                      >
                        <div class="section-caption">本阶段附件</div>
                        <div class="attachment-list">
                          <section v-for="group in selectedAttachmentGroups" :key="group.id" class="attachment-batch">
                            <header>
                              <strong>{{ group.name }}</strong>
                              <small>{{ group.items.length }} 个文件</small>
                            </header>
                            <div v-for="attachment in group.items" :key="attachment.id" class="attachment-row">
                              <div class="attachment-name">
                                <span>{{ attachment.fileName }}</span>
                                <small>{{ attachment.relativePath || attachment.description || "独立文件" }}</small>
                              </div>
                              <el-button link type="primary" @click="downloadPreAiAttachmentApi(attachment)">下载</el-button>
                              <el-button v-if="canModifySelectedStage" link type="danger" @click="voidAttachment(attachment.id)"
                                >作废</el-button
                              >
                            </div>
                          </section>
                          <div v-if="canModifySelectedStage" class="upload-actions">
                            <label class="upload-button">
                              <input
                                type="file"
                                multiple
                                accept="image/*,.pdf"
                                @change="event => uploadAttachments(event, selectedStageCode)"
                              />
                              <el-icon><Upload /></el-icon> 选择多个文件
                            </label>
                            <label class="upload-button">
                              <input
                                type="file"
                                multiple
                                webkitdirectory
                                @change="event => uploadAttachments(event, selectedStageCode, undefined, true)"
                              />
                              <el-icon><FolderOpened /></el-icon> 选择文件夹
                            </label>
                          </div>
                          <el-progress
                            v-if="attachmentUpload.total"
                            :percentage="attachmentUpload.percent"
                            :status="
                              attachmentUpload.failed
                                ? 'warning'
                                : attachmentUpload.success === attachmentUpload.total
                                  ? 'success'
                                  : undefined
                            "
                          />
                          <small v-if="attachmentUpload.total" class="upload-summary">
                            共 {{ attachmentUpload.total }} 个，成功 {{ attachmentUpload.success }} 个，失败
                            {{ attachmentUpload.failed }} 个
                          </small>
                        </div>
                      </section>

                      <FollowUpTimeline
                        v-if="selectedStageCode === 'INSPECTION' && inspectionView === 'CURRENT'"
                        :patient-case-id="workspace?.encounter?.patientCaseId"
                        :encounter-id="selectedEncounterId"
                        :can-manage="canManageFollowUp"
                      />

                      <footer
                        v-if="selectedStageCode !== 'INSPECTION' || inspectionView === 'CURRENT'"
                        class="panel-actions sticky-actions"
                      >
                        <el-button v-if="canReturnSelectedStage" type="warning" plain @click="returnStage(selectedStageCode)"
                          >退回修改</el-button
                        >
                        <div></div>
                        <el-button v-if="canEditSelectedStage" :loading="actionLoading" @click="saveSelectedStage"
                          >保存草稿</el-button
                        >
                        <el-button
                          v-if="canEditSelectedStage"
                          type="primary"
                          :loading="actionLoading"
                          @click="completeSelectedStage"
                          >完成并交接</el-button
                        >
                        <el-button
                          v-if="canTerminateReception"
                          type="danger"
                          plain
                          :loading="actionLoading"
                          @click="terminateReception"
                          >患者离院（不治疗）</el-button
                        >
                        <el-button
                          v-if="canPhysicianConfirmSelectedSurgery"
                          type="primary"
                          :loading="actionLoading"
                          @click="confirmSelectedSurgery"
                        >
                          医生确认手术事实
                        </el-button>
                        <el-button
                          v-if="canCorrectSelectedStage"
                          type="primary"
                          :loading="actionLoading"
                          @click="correctSelectedStage"
                          >保存纠错并重新复核</el-button
                        >
                      </footer>
                    </template>

                    <DoctorReviewPanel
                      v-else
                      v-model:statement="reviewStatement"
                      v-model:critical-acknowledged="criticalAcknowledged"
                      :preview="reviewPreview"
                      :sections="maskedSections"
                      :can-review="canReview"
                      :can-generate-target="canReview && Boolean(workspace.encounter.id)"
                      :loading="actionLoading"
                      :version-loading="targetVersionsLoading"
                      :encounter-status="workspace.encounter.status"
                      :exports="workspace.exports"
                      :target-versions="templateTargetVersions"
                      :ai-versions="aiGeneratedVersions"
                      :latest-target-version-id="latestGeneratedTargetVersionId"
                      :latest-export-version-id="latestGeneratedExportVersionId"
                      :latest-ai-version-id="latestAiGeneratedVersionId"
                      :deleting-target-version-id="deletingTargetVersionId"
                      @refresh="loadReviewPreview"
                      @confirm="confirmReview"
                      @save-row-override="saveReviewRowOverride"
                      @generate="generateExport"
                      @generate-target="generateTargetMedicalRecord"
                      @open-record-chat="openRecordChat"
                      @open-health-archive="openHealthArchive"
                      @open-outpatient-record="openOutpatientRecord"
                      @download="downloadPreAiExportApi"
                      @download-target="downloadMedicalRecordApi"
                      @download-ai="downloadAiGeneratedRecord"
                      @delete-target="deleteTargetMedicalRecord"
                    />
                  </section>

                  <section v-else :key="'auxiliary'" class="auxiliary-stack">
                    <section class="dr-image-section aux-dr-section">
                      <header class="dr-image-heading">
                        <div>
                          <span class="section-caption">辅助检查 · 影像采集</span>
                          <strong>DR 影像资料</strong>
                          <small
                            >与前台岗共用同一影像库，作为独立附件存储、不参与病历元数据；上传后各岗位在患者信息区优先可见。</small
                          >
                        </div>
                        <el-tag :type="registrationImageAttachments.length ? 'primary' : 'info'" effect="plain">
                          {{ registrationImageAttachments.length ? `${registrationImageAttachments.length} 张` : "暂无" }}
                        </el-tag>
                      </header>
                      <AttachmentPreviewGallery
                        v-if="registrationImageAttachments.length"
                        :attachments="registrationImageAttachments"
                        :removable="canOpenLabWorkbench"
                        @download="downloadPreAiAttachmentApi"
                        @remove="removeImageAttachment"
                      />
                      <el-empty v-else :image-size="56" description="暂无 DR 影像，可在下方上传或拍照采集" />
                      <div v-if="canOpenLabWorkbench" class="upload-actions">
                        <label class="upload-button">
                          <input
                            type="file"
                            multiple
                            accept="image/*"
                            @change="event => uploadAttachments(event, 'REGISTRATION', undefined, false, '化验岗DR影像')"
                          />
                          <el-icon><Upload /></el-icon> 选择 DR 图片
                        </label>
                        <label class="upload-button camera-button">
                          <input
                            type="file"
                            accept="image/*"
                            capture="environment"
                            @change="event => uploadAttachments(event, 'REGISTRATION', undefined, false, '化验岗DR影像')"
                          />
                          <el-icon><Camera /></el-icon> 拍照上传
                        </label>
                      </div>
                      <el-progress
                        v-if="attachmentUpload.total"
                        :percentage="attachmentUpload.percent"
                        :status="
                          attachmentUpload.failed
                            ? 'warning'
                            : attachmentUpload.success === attachmentUpload.total
                              ? 'success'
                              : undefined
                        "
                      />
                      <small v-if="attachmentUpload.total" class="upload-summary">
                        共 {{ attachmentUpload.total }} 个，成功 {{ attachmentUpload.success }} 个，失败
                        {{ attachmentUpload.failed }} 个
                      </small>
                    </section>
                    <section class="dr-image-section aux-dr-section endoscopy-report-section">
                      <header class="dr-image-heading">
                        <div>
                          <span class="section-caption">辅助检查 · 报告采集</span>
                          <strong>胃肠镜检查报告单</strong>
                          <small
                            >作为独立附件存储、不参与病历元数据；上传后各岗位在患者信息区可见，支持重复上传与误删恢复。</small
                          >
                        </div>
                        <el-tag :type="endoscopyReportAttachments.length ? 'primary' : 'info'" effect="plain">
                          {{ endoscopyReportAttachments.length ? `${endoscopyReportAttachments.length} 张` : "暂无" }}
                        </el-tag>
                      </header>
                      <AttachmentPreviewGallery
                        v-if="endoscopyReportAttachments.length"
                        :attachments="endoscopyReportAttachments"
                        :removable="canOpenLabWorkbench"
                        @download="downloadPreAiAttachmentApi"
                        @remove="removeImageAttachment"
                      />
                      <el-empty v-else :image-size="56" description="暂无胃肠镜报告，可在下方上传或拍照采集" />
                      <div v-if="canOpenLabWorkbench" class="upload-actions">
                        <label class="upload-button">
                          <input
                            type="file"
                            multiple
                            accept="image/*,.pdf"
                            @change="
                              event =>
                                uploadAttachments(
                                  event,
                                  'REGISTRATION',
                                  undefined,
                                  false,
                                  '胃肠镜报告',
                                  endoscopyReportDescription
                                )
                            "
                          />
                          <el-icon><Upload /></el-icon> 选择报告图片/PDF
                        </label>
                        <label class="upload-button camera-button">
                          <input
                            type="file"
                            accept="image/*"
                            capture="environment"
                            @change="
                              event =>
                                uploadAttachments(
                                  event,
                                  'REGISTRATION',
                                  undefined,
                                  false,
                                  '胃肠镜报告',
                                  endoscopyReportDescription
                                )
                            "
                          />
                          <el-icon><Camera /></el-icon> 拍照上传
                        </label>
                      </div>
                      <el-progress
                        v-if="attachmentUpload.total"
                        :percentage="attachmentUpload.percent"
                        :status="
                          attachmentUpload.failed
                            ? 'warning'
                            : attachmentUpload.success === attachmentUpload.total
                              ? 'success'
                              : undefined
                        "
                      />
                      <small v-if="attachmentUpload.total" class="upload-summary">
                        共 {{ attachmentUpload.total }} 个，成功 {{ attachmentUpload.success }} 个，失败
                        {{ attachmentUpload.failed }} 个
                      </small>
                    </section>
                    <div v-if="voidedAttachments.length" class="voided-attachments-row aux-voided-row">
                      <span class="voided-caption">已作废 {{ voidedAttachments.length }} 张（可恢复）</span>
                      <el-button
                        v-for="attachment in voidedAttachments"
                        :key="attachment.id"
                        link
                        type="primary"
                        size="small"
                        @click="restoreAttachment(attachment)"
                      >
                        恢复 {{ attachment.fileName }}
                      </el-button>
                    </div>
                    <AuxiliaryTaskPanel
                      :workspace="workspace"
                      :capabilities="authStore.capabilities"
                      :permissions="authStore.auxiliaryPermissions"
                      :current-user-id="currentUserId"
                      :current-user-name="currentUserName"
                      :loading="actionLoading"
                      :can-return="canReview"
                      @updated="hydrateWorkspace"
                      @return-task="returnAuxTask"
                      @draft-change="auxiliaryTasksDirty = $event"
                    />
                    <LabReportPanel
                      v-model:active-report-id="activeLabReportId"
                      :workspace="workspace"
                      :lab-task="labTask"
                      :legacy-tasks="legacyAuxiliaryTasks"
                      :can-open-workbench="canOpenLabWorkbench"
                      :can-review="canReview"
                      :can-complete="canCompleteLab"
                      :loading="actionLoading"
                      :task-label="auxiliaryTaskLabel"
                      :human-value="humanValue"
                      :abnormal-label="labMetricAbnormalLabel"
                      :is-metric-abnormal="isLabMetricAbnormal"
                      @open-workbench="openLabWorkbench"
                      @return-task="returnAuxTask"
                      @complete="completeLab"
                    />
                  </section>
                </Transition>
              </div>
            </Transition>
          </template>
        </template>
      </main>

      <div
        v-if="historyPanelOpen && workspace"
        class="history-resizer"
        role="separator"
        aria-label="调整当前病历与历史病历宽度"
        aria-orientation="vertical"
        :aria-valuemin="historyMinWidth"
        :aria-valuemax="historyMaxWidth"
        :aria-valuenow="Math.round(historyPaneWidth)"
        tabindex="0"
        @pointerdown="startHistoryResize"
        @keydown="adjustHistoryPaneByKeyboard"
        @dblclick="resetHistoryPaneRatio"
      >
        <span aria-hidden="true"></span>
      </div>

      <EncounterHistoryPanel
        v-if="historyPanelOpen && workspace"
        :history="encounterHistory"
        :current-encounter-id="workspace.encounter.id"
        :selected-encounter-id="historicalEncounterId"
        :workspace="historicalWorkspace"
        :loading="historyLoading"
        :field-label="fieldLabel"
        @select="loadHistoricalWorkspace"
        @close="historyPanelOpen = false"
        @download="downloadPreAiAttachmentApi"
      />
    </section>

    <el-dialog v-model="createDialogVisible" title="就诊登记并发号" width="760px" destroy-on-close>
      <el-form label-position="top">
        <section v-if="createSecondaryRegistrationFieldsCount" class="field-noise-toolbar dialog-field-noise-toolbar">
          <div>
            <strong>精简登记视图</strong>
            <small>先登记发号必须信息，证件、地址、病史补充等低频项仍可展开填写。</small>
          </div>
          <el-button size="small" plain @click="createOptionalFieldsExpanded = !createOptionalFieldsExpanded">
            {{ createOptionalFieldsExpanded ? "收起低频字段" : `展开 ${createSecondaryRegistrationFieldsCount} 个可选字段` }}
          </el-button>
        </section>
        <RegistrationFormFields :fields="registrationDialogFields" :form="createForm" @patch="patchCreateForm" />
        <el-alert
          title="常规诊疗进入检查候诊；选择胃肠镜检查/咨询后会直接进入接诊室，号码全程不变。"
          type="info"
          :closable="false"
          show-icon
        />
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" @click="createEncounter">登记并发号</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="followUpDialogVisible" :title="`新增复诊 · ${followUpPatientCase?.patientName || ''}`" width="680px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="提交后将创建复诊、完成登记并直接发号；检查、诊断、图片、化验和复核状态仍从空白开始。"
      />
      <el-form label-position="top" class="follow-up-form">
        <div class="form-grid dialog-grid">
          <el-form-item label="本次来访时间" required>
            <el-date-picker v-model="followUpForm.visitDate" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" />
          </el-form-item>
          <el-form-item label="来访原因">
            <el-input v-model="followUpForm.visitReason" placeholder="例如：术后复查、症状复发" />
          </el-form-item>
          <el-form-item label="简短描述" class="span-2">
            <el-input v-model="followUpForm.description" type="textarea" :rows="2" />
          </el-form-item>
          <el-form-item label="交费参考状态">
            <el-select v-model="followUpForm.paymentStatus" clearable>
              <el-option label="未交" value="UNPAID" />
              <el-option label="部分缴费" value="PARTIAL" />
              <el-option label="已交" value="PAID" />
              <el-option label="退费" value="REFUNDED" />
            </el-select>
          </el-form-item>
          <el-form-item label="参考金额">
            <el-input v-model="followUpForm.paymentAmount" placeholder="仅作协作参考" />
          </el-form-item>
          <el-form-item label="收费项目">
            <el-input v-model="followUpForm.paymentItems" />
          </el-form-item>
          <el-form-item label="交费时间">
            <el-date-picker v-model="followUpForm.paidAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" />
          </el-form-item>
          <el-form-item label="交费备注" class="span-2">
            <el-input v-model="followUpForm.paymentRemark" type="textarea" :rows="2" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="followUpDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" @click="createFollowUp">创建复诊并发号</el-button>
      </template>
    </el-dialog>

    <RecordAiChat
      v-model="recordChatVisible"
      :encounter-id="selectedEncounterId"
      :patient-case-id="workspace?.encounter?.patientCaseId"
      :patient-name="recordChatPatientName"
      :main-diagnosis-text="recordChatDiagnosisText"
      :exports="workspace?.exports ?? []"
      @record-generated="loadTargetMedicalRecordVersions"
    />

    <HealthArchiveDialog
      v-model="healthArchiveVisible"
      :encounter-id="selectedEncounterId"
      :encounter-patient-name="recordChatPatientName"
      @completed="loadTargetMedicalRecordVersions"
    />

    <OutpatientRecordDialog
      v-model="outpatientDialogVisible"
      :loading="outpatientLoading"
      :generating="outpatientGenerating"
      :summary="outpatientSummary"
      :versions="outpatientVersions"
      @generate="generateOutpatientRecord"
      @download="downloadOutpatientRecord"
    />

    <el-dialog
      v-model="inpatientAiDialogVisible"
      title="以前置 DOCX 为基础生成住院病历"
      width="920px"
      :close-on-click-modal="false"
      :close-on-press-escape="!inpatientAiGenerating"
      :show-close="!inpatientAiGenerating"
      destroy-on-close
    >
      <div class="inpatient-ai-dialog">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="系统先对 DOCX 原包进行安全检查，再按受控节点精准回填；不会提取纯文本后重建默认样式文档。"
        />
        <div class="inpatient-ai-dialog__reference">
          <span class="inpatient-ai-dialog__label">前置病历 DOCX（必选）</span>
          <input
            ref="inpatientAiReferenceInput"
            class="inpatient-ai-dialog__file-input"
            type="file"
            accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            :disabled="inpatientAiGenerating || inpatientAiInspecting"
            @change="handleInpatientAiReferenceChange"
          />
          <div class="inpatient-ai-dialog__file-actions">
            <el-button
              :icon="Upload"
              :loading="inpatientAiInspecting"
              :disabled="inpatientAiGenerating"
              @click="openInpatientAiReferencePicker"
            >
              {{ inpatientAiReferenceDocument ? "替换并重新检查" : "选择并检查 DOCX" }}
            </el-button>
            <span v-if="inpatientAiReferenceDocument" class="inpatient-ai-dialog__file-name">
              {{ inpatientAiReferenceDocument.name }}（{{ formatFileSize(inpatientAiReferenceDocument.size) }}）
            </span>
            <span v-else class="inpatient-ai-dialog__file-empty">尚未选择文件</span>
            <el-button
              v-if="inpatientAiReferenceDocument"
              link
              type="danger"
              :disabled="inpatientAiGenerating"
              @click="clearInpatientAiReference"
            >
              清除
            </el-button>
          </div>
          <p>仅支持 DOCX，单个文件不超过 10 MB。原包、净化包和检查报告受服务端权限及路径门禁保护。</p>
        </div>

        <section v-if="inpatientAiInspection" class="workflow-card">
          <header class="workflow-card__head">
            <strong>DOCX 安全检查</strong>
            <div>
              <el-tag :type="inspectionDecisionType">{{ inspectionDecisionLabel }}</el-tag>
              <el-tag :type="inspectionRiskType" effect="plain">风险 {{ inpatientAiInspection.highestRiskLevel }}</el-tag>
            </div>
          </header>
          <el-alert
            v-if="!inpatientAiInspection.packageValidation.valid"
            type="error"
            :closable="false"
            show-icon
            title="OOXML 包结构校验未通过，禁止生成"
          />
          <div v-if="inpatientAiInspection.findings.length" class="finding-list">
            <div v-for="finding in inpatientAiInspection.findings" :key="`${finding.code}-${finding.partName}`">
              <el-tag size="small" :type="finding.risk === 'CRITICAL' || finding.risk === 'HIGH' ? 'danger' : 'warning'">{{
                finding.risk
              }}</el-tag>
              <span
                >{{ finding.message }}<small v-if="finding.partName"> · {{ finding.partName }}</small></span
              >
            </div>
          </div>
          <el-empty v-else :image-size="52" description="未发现危险部件" />
        </section>

        <section v-if="inpatientAiInspection?.nodes.length" class="workflow-card">
          <header class="workflow-card__head">
            <div>
              <strong>受控节点目录</strong>
              <small
                >已选择 {{ inpatientAiTargetNodeKeys.length }} /
                {{ inpatientAiInspection.nodes.length }} 个节点；不选择表示按全部可映射节点处理。</small
              >
            </div>
            <el-select v-model="inpatientAiMappingMode" :disabled="inpatientAiGenerating" style="width: 180px">
              <el-option label="受控语义映射" value="CONTROLLED" />
              <el-option label="旧文档顺序映射" value="LEGACY_ORDINAL" />
            </el-select>
          </header>
          <el-checkbox-group v-model="inpatientAiTargetNodeKeys" class="node-catalog" :disabled="inpatientAiGenerating">
            <el-checkbox v-for="node in inpatientAiInspection.nodes" :key="node.nodeKey" :value="node.nodeKey">
              <span>#{{ node.sequenceNo }} {{ node.preview || "空段落" }}</span>
              <small>{{ node.locatorType }} · {{ node.locator || node.structuralPath }}</small>
            </el-checkbox>
          </el-checkbox-group>
        </section>

        <label class="inpatient-ai-dialog__label" for="inpatient-ai-prompt">生成要求</label>
        <el-input
          id="inpatient-ai-prompt"
          v-model="inpatientAiPrompt"
          type="textarea"
          :rows="6"
          maxlength="4000"
          show-word-limit
          resize="vertical"
          :disabled="inpatientAiGenerating"
        />

        <section v-if="inpatientAiTask" class="workflow-card">
          <header class="workflow-card__head">
            <div>
              <strong>异步生成任务</strong><small>{{ inpatientAiTask.taskId }}</small>
            </div>
            <el-tag :type="workflowTaskStatusType">{{ inpatientAiTask.status }}</el-tag>
          </header>
          <el-progress
            :percentage="workflowProgressPercent"
            :status="
              inpatientAiTask.status === 'FAILED' ? 'exception' : inpatientAiTask.status === 'SUCCEEDED' ? 'success' : undefined
            "
          />
          <p>当前阶段：{{ workflowStageLabel(inpatientAiTask.currentStage) }} · 第 {{ inpatientAiTask.attemptCount }} 次尝试</p>
          <el-alert
            v-if="inpatientAiTask.status === 'FAILED'"
            type="error"
            show-icon
            :closable="false"
            :title="`${inpatientAiTask.errorCode || 'GENERATION_FAILED'}：${inpatientAiTask.errorMessage || '生成失败'}`"
          />
          <el-timeline v-if="inpatientAiTask.events.length" class="task-events">
            <el-timeline-item
              v-for="event in inpatientAiTask.events"
              :key="event.sequenceNo"
              :timestamp="event.occurredAt"
              placement="top"
            >
              {{ event.message || workflowStageLabel(event.stage) }}
            </el-timeline-item>
          </el-timeline>
        </section>
      </div>
      <template #footer>
        <el-button @click="closeInpatientAiDialog">{{ inpatientAiGenerating ? "取消轮询" : "取消 AI 加工" }}</el-button>
        <el-button
          v-if="inpatientAiTask?.status === 'FAILED'"
          type="warning"
          :loading="inpatientAiGenerating"
          @click="retryInpatientAiGeneration"
          >失败重试</el-button
        >
        <el-button
          v-else
          type="primary"
          :loading="inpatientAiGenerating"
          :disabled="!inpatientAiInspection?.canGenerate || inpatientAiInspecting || Boolean(inpatientAiTask)"
          @click="completeInpatientAiGeneration"
        >
          提交保真生成任务
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="inpatientAiResultDialogVisible"
      title="保真病历生成结果"
      width="920px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div class="inpatient-ai-result">
        <el-alert
          type="success"
          :closable="false"
          show-icon
          title="新 DOCX 已基于检查后的原包完成节点级回填并另存为病历草稿版本。"
        />
        <div class="inpatient-ai-result__meta">
          <span>模型：{{ inpatientAiResultModel || "已配置 GPT 兼容模型" }}</span>
          <span v-if="inpatientAiResultRecord">版本：V{{ inpatientAiResultRecord.version }}</span>
          <span v-if="inpatientAiTask">任务：{{ inpatientAiTask.taskId }}</span>
          <span v-if="inpatientAiMappings">映射：{{ mappedNodeCount }} / {{ inpatientAiMappings.mappings.length }}</span>
        </div>
        <el-input
          v-model="inpatientAiResultContent"
          type="textarea"
          :rows="14"
          readonly
          resize="vertical"
          aria-label="GPT 兼容模型生成的目标住院病历内容"
        />
        <el-collapse v-if="inpatientAiMappings?.mappings.length">
          <el-collapse-item :title="`节点映射明细（${inpatientAiMappings.mappings.length}）`">
            <div class="mapping-list">
              <div v-for="mapping in inpatientAiMappings.mappings" :key="mapping.sequenceNo">
                <el-tag size="small" :type="mapping.status === 'MAPPED' ? 'success' : 'warning'">{{ mapping.status }}</el-tag>
                <span>{{ mapping.beforePreview || "空节点" }} → {{ mapping.afterPreview || "未回填" }}</span>
              </div>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>
      <template #footer>
        <el-button @click="inpatientAiResultDialogVisible = false">关闭</el-button>
        <el-button :disabled="!inpatientAiResultContent" @click="copyInpatientAiResult">复制全部内容</el-button>
        <el-button :disabled="!inpatientAiTask?.outputAssetId" @click="downloadInpatientAiOutputAsset"
          >下载校验后输出资产</el-button
        >
        <el-button type="primary" :disabled="!inpatientAiResultRecord" @click="downloadInpatientAiResultRecord"
          >下载 / 导出新 DOCX</el-button
        >
      </template>
    </el-dialog>

    <el-dialog v-model="legacyDialogVisible" title="导入进行中的旧患者" width="620px">
      <el-alert type="info" :closable="false" show-icon title="只复制可明确映射的字段和附件引用，不会修改或反写旧档案。" />
      <el-select
        v-model="selectedLegacyPatientId"
        filterable
        clearable
        placeholder="按姓名或门诊/住院号选择"
        class="legacy-select"
      >
        <el-option
          v-for="patient in legacyPatients"
          :key="patient.id"
          :label="`${patient.name}｜${patient.visitNo}｜${patient.status}`"
          :value="patient.id"
        />
      </el-select>
      <template #footer>
        <el-button @click="legacyDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!selectedLegacyPatientId" :loading="actionLoading" @click="importLegacyPatient"
          >导入</el-button
        >
      </template>
    </el-dialog>

    <el-dialog v-model="admissionProfileDialogVisible" title="护士住院资料补录" width="760px" destroy-on-close>
      <el-alert
        :title="
          admissionProfile?.status === 'COMPLETED'
            ? '住院资料已完成；再次保存将回到待补录状态。'
            : '仅医生确认住院后创建；患者改回门诊时任务会关闭，已填写资料不会删除。'
        "
        type="info"
        :closable="false"
        show-icon
      />
      <el-form label-position="top" class="form-grid dialog-grid admission-profile-form">
        <el-form-item label="联系人姓名"
          ><el-input v-model="admissionProfileForm.contactName" :disabled="!canEditAdmissionProfile"
        /></el-form-item>
        <el-form-item label="联系人电话"
          ><el-input v-model="admissionProfileForm.contactPhone" :disabled="!canEditAdmissionProfile"
        /></el-form-item>
        <el-form-item label="联系人关系"
          ><el-input v-model="admissionProfileForm.contactRelation" :disabled="!canEditAdmissionProfile"
        /></el-form-item>
        <el-form-item label="籍贯"
          ><el-input v-model="admissionProfileForm.nativePlace" :disabled="!canEditAdmissionProfile"
        /></el-form-item>
        <el-form-item label="出生地"
          ><el-input v-model="admissionProfileForm.birthplace" :disabled="!canEditAdmissionProfile"
        /></el-form-item>
        <el-form-item label="婚姻状态"
          ><el-input v-model="admissionProfileForm.maritalStatus" :disabled="!canEditAdmissionProfile"
        /></el-form-item>
        <el-form-item label="参保险种"
          ><el-input v-model="admissionProfileForm.insuranceType" :disabled="!canEditAdmissionProfile"
        /></el-form-item>
        <el-form-item label="付费方式"
          ><el-input v-model="admissionProfileForm.paymentMethod" :disabled="!canEditAdmissionProfile"
        /></el-form-item>
        <el-form-item label="病案号"
          ><el-input v-model="admissionProfileForm.medicalRecordNo" :disabled="!canEditAdmissionProfile"
        /></el-form-item>
        <el-form-item label="住院号"
          ><el-input v-model="admissionProfileForm.inpatientNo" :disabled="!canEditAdmissionProfile"
        /></el-form-item>
        <el-form-item label="病区"
          ><el-input v-model="admissionProfileForm.ward" :disabled="!canEditAdmissionProfile"
        /></el-form-item>
        <el-form-item label="床号"
          ><el-input v-model="admissionProfileForm.bedNo" :disabled="!canEditAdmissionProfile"
        /></el-form-item>
        <el-form-item label="第几次入院"
          ><el-input v-model="admissionProfileForm.admissionCount" :disabled="!canEditAdmissionProfile"
        /></el-form-item>
        <el-form-item label="入院方式"
          ><el-input v-model="admissionProfileForm.admissionMethod" :disabled="!canEditAdmissionProfile"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="admissionProfileDialogVisible = false">关闭</el-button>
        <template v-if="canEditAdmissionProfile">
          <el-button :loading="actionLoading" @click="saveAdmissionProfile(false)">保存草稿</el-button>
          <el-button type="primary" :loading="actionLoading" @click="saveAdmissionProfile(true)">完成补录</el-button>
        </template>
      </template>
    </el-dialog>
    <el-drawer v-model="responsibilityTimelineVisible" title="责任时间轴" size="480px">
      <el-alert type="info" :closable="false" show-icon title="按实际提交顺序展示；历史记录未留存账号信息时会明确标注。" />
      <el-timeline v-loading="responsibilityTimelineLoading" class="responsibility-timeline">
        <el-timeline-item
          v-for="group in responsibilityTimelineGroups"
          :key="group.id"
          :timestamp="group.firstAt"
          placement="top"
        >
          <strong>{{ group.operatorLabel }}</strong>
          <p>
            {{ group.actionLabels.join("、") }} · {{ group.events.length }} 次操作
            <template v-if="group.events.length > 1">（{{ group.firstAt }} - {{ group.lastAt }}）</template>
          </p>
          <small>
            {{ group.events[group.events.length - 1]?.operator || "历史记录未留存账号信息" }}
            <template v-if="group.department"> · {{ group.department }}</template>
          </small>
          <details v-if="group.events.length > 1" class="responsibility-group-details">
            <summary>展开操作明细</summary>
            <ul>
              <li v-for="event in group.events" :key="event.id">
                <span>{{ event.submittedAt || event.occurredAt || event.createdAt }}</span>
                <strong>{{ responsibilityActionLabel(event.action) }}</strong>
                <em>{{ event.detail || "无补充说明" }}</em>
              </li>
            </ul>
          </details>
        </el-timeline-item>
        <el-empty v-if="!responsibilityTimelineLoading && !responsibilityTimeline.length" description="暂无责任操作记录" />
      </el-timeline>
    </el-drawer>
  </div>
</template>

<script setup lang="ts" name="preAiEncounters">
import { computed, h, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref, watch } from "vue";
import { ElButton, ElMessage, ElMessageBox } from "element-plus";
import { Camera, FolderOpened, Plus, Refresh, Search, Upload, User } from "@element-plus/icons-vue";
import { useAuthStore } from "@/stores/modules/auth";
import { useUserStore } from "@/stores/modules/user";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";
import { roleLabel } from "@/config/fieldPermissions";
import {
  deleteMedicalRecordApi,
  downloadGeneratedMedicalRecordV2Api,
  downloadMedicalRecordApi,
  downloadMedicalRecordAssetV2Api,
  generateMedicalRecordApi,
  getGeneratedMedicalRecordVersionsApi,
  getMedicalRecordWorkflowMappingsApi,
  inspectMedicalRecordDocumentV2Api,
  pollMedicalRecordWorkflowTask,
  retryMedicalRecordWorkflowTaskApi,
  submitMedicalRecordWorkflowTaskApi
} from "@/api/modules/clinic/medicalRecord";
import type {
  MedicalRecordDocxInspection,
  MedicalRecordMappingMode,
  MedicalRecordWorkflowMappings,
  MedicalRecordWorkflowTask
} from "@/api/modules/clinic/types";
import { completeQueuePrintTaskApi, createQueuePrintTaskApi } from "@/api/modules/clinic/clinicQueue";
import {
  completePreAiStageApi,
  confirmPreAiSurgeryApi,
  correctPreAiStageApi,
  completePreAiLabApi,
  confirmPreAiReviewApi,
  registerAndIssuePreAiEncounterApi,
  registerAndIssuePreAiFollowUpApi,
  downloadPreAiAttachmentApi,
  downloadPreAiExportApi,
  downloadPreAiOutpatientRecordApi,
  generatePreAiExportApi,
  generatePreAiOutpatientRecordApi,
  getPreAiAttachmentObjectUrlApi,
  getPreAiEncounterHistoryApi,
  getPreAiInspectionTimelineApi,
  getPreAiOutpatientPreviewApi,
  getPreAiOutpatientRecordsApi,
  getPreAiResponsibilityTimelineApi,
  getPreAiPatientCasesApi,
  getPatientListApi,
  getPreAiReviewPreviewApi,
  getPreAiReadOnlyWorkspaceApi,
  getPreAiWorkspaceApi,
  importLegacyPreAiEncounterApi,
  returnPreAiAuxiliaryTaskApi,
  returnPreAiStageApi,
  restorePreAiAttachmentApi,
  savePreAiReviewOverridesApi,
  savePreAiDutyAssignmentsApi,
  savePreAiAdmissionProfileApi,
  savePreAiStageApi,
  terminatePreAiReceptionApi,
  uploadPreAiAttachmentApi,
  voidPreAiAttachmentApi,
  type PatientRow,
  type GeneratedMedicalRecord,
  type InspectionTimelineNode,
  type PreAiAttachment,
  type PreAiAdmissionProfile,
  type PreAiAuditLog,
  type PreAiDutyAssignment,
  type PreAiDutyCode,
  type PreAiEncounterStatus,
  type PreAiEncounterHistoryItem,
  type PreAiEncounterSummary,
  type PreAiExportVersion,
  type PreAiOutpatientRecordVersion,
  type PreAiOutpatientSummary,
  type PreAiPatientCase,
  type PreAiReviewOverride,
  type PreAiReviewPreview,
  type PreAiStageCode,
  type PreAiStageStatus,
  type PreAiWorkspace
} from "@/api/modules/clinic";
import WorkflowSidebar, { type WorkflowCard } from "./components/WorkflowSidebar.vue";
import MedicalRecordPreview from "./components/MedicalRecordPreview.vue";
import LabReportPanel from "./components/LabReportPanel.vue";
import DoctorReviewPanel from "./components/DoctorReviewPanel.vue";
import OutpatientRecordDialog from "./components/OutpatientRecordDialog.vue";
import RecordAiChat from "./components/RecordAiChat.vue";
import HealthArchiveDialog from "./components/HealthArchiveDialog.vue";
import FollowUpTimeline from "./components/FollowUpTimeline.vue";
import AuxiliaryTaskPanel from "./components/AuxiliaryTaskPanel.vue";
import DutyAssignmentPanel from "./components/DutyAssignmentPanel.vue";
import StructuredField from "./components/StructuredField.vue";
import CreatableSelect from "./components/CreatableSelect.vue";
import RegistrationFormFields from "./components/RegistrationFormFields.vue";
import ClinicalTemplateToolbar from "./components/ClinicalTemplateToolbar.vue";
import EncounterHistoryPanel from "./components/EncounterHistoryPanel.vue";
import AttachmentPreviewGallery from "./components/AttachmentPreviewGallery.vue";
import { getLocalPrintAgentStatus, printQueueTicketLocally } from "../../clinicQueue/printAgent";
import {
  auxiliaryTaskLabel,
  encounterStatusLabel,
  isHistoryIntakeKey,
  preAiStages,
  stageByCode,
  stageStatusLabel,
  type PreAiFieldConfig
} from "./fieldConfig";
import { groupAttachments, isImageAttachment } from "./utils/attachment";
import { isLabMetricAbnormal, labMetricAbnormalLabel } from "./utils/labResult";
import { buildDocumentPreviewSections, humanValue, nonEmptyEntries } from "./utils/previewBuilder";
import {
  buildChiefComplaintText,
  buildColonoscopyConclusion,
  buildDiagnosisBasis,
  buildInspectionConclusion,
  buildPhysicalExamText,
  buildPresentIllnessText,
  buildSyndromeBasis,
  buildTreatmentPlan,
  stableSourceHash
} from "./utils/templateTextGenerator";
import {
  applyClinicalTemplate,
  clinicalTemplateById,
  clinicalTemplateIdsForDiseases,
  inferTemplateIdsBySymptoms,
  mergeClinicalTemplateSlots,
  type ClinicalTemplateMode
} from "./utils/clinicalTemplateCatalog";

const userStore = useUserStore();
const authStore = useAuthStore();
const route = useRoute();
const router = useRouter();
const currentRole = computed(() => userStore.userInfo.role || "");
const canManageFollowUp = computed(() => ["inspection", "admin", "doctor", "tcm"].includes(currentRole.value));
const currentUser = computed(() => userStore.userInfo as typeof userStore.userInfo & { id?: string; username?: string });
const currentUserId = computed(() => String(currentUser.value.id || ""));
const currentUserName = computed(() => String(currentUser.value.name || currentUser.value.username || ""));
const hasAssignedDuty = (...duties: PreAiDutyCode[]) =>
  (workspace.value?.dutyAssignments || []).some(
    item =>
      duties.includes(item.dutyCode) &&
      ((Boolean(currentUserId.value) &&
        (item.responsibleUserId === currentUserId.value || item.participantUserIds?.includes(currentUserId.value))) ||
        (Boolean(currentUserName.value) &&
          (item.responsibleUserName === currentUserName.value || item.participantUserNames?.includes(currentUserName.value))))
  );
const hasCapability = (capability: string) => authStore.capabilities.includes(capability);
const canCreateEncounter = computed(() => hasCapability("preai:encounter:create"));
const canImportLegacy = computed(() => hasCapability("preai:legacy:import"));
const canReview = computed(() => hasCapability("preai:review") || hasAssignedDuty("FINAL_REVIEW_DOCTOR", "ATTENDING_DOCTOR"));
const canOpenLabWorkbench = computed(() => Boolean(authStore.auxiliaryPermissions.LAB?.editable) || hasAssignedDuty("LAB_STAFF"));
const canCompleteLab = computed(() => Boolean(authStore.auxiliaryPermissions.LAB?.editable) || hasAssignedDuty("LAB_STAFF"));
const canMaintainDuties = computed(() => hasCapability("preai:duties:manage"));
const canConfirmSurgery = computed(() => hasCapability("preai:surgery:confirm") || hasAssignedDuty("SURGEON"));

const encounters = ref<PreAiEncounterSummary[]>([]);
const patientCases = ref<PreAiPatientCase[]>([]);
const keyword = ref("");
const patientArchiveDate = ref("");
const careSituationFilter = ref<"ALL" | "OUTPATIENT" | "INPATIENT" | "LOW_INCOME">("ALL");
const patientArchiveView = ref<"LIST" | "MASONRY">("LIST");
const selectedPatientCaseId = ref("");
const patientDrawerOpen = ref(false);
const selectedEncounterId = ref("");
const workspace = ref<PreAiWorkspace>();
const workspaceLoading = ref(false);
const encounterHistory = ref<PreAiEncounterHistoryItem[]>([]);
const historicalWorkspace = ref<PreAiWorkspace>();
const historicalEncounterId = ref("");
const historyPanelOpen = ref(false);
const historyLoading = ref(false);
const workspaceShellRef = ref<HTMLElement>();
const historyShellWidth = ref(0);
const HISTORY_PANE_RATIO_KEY = "pre-ai-history-pane-ratio";
const DEFAULT_HISTORY_PANE_RATIO = 0.38;
const historyMinWidth = 360;
const readHistoryPaneRatio = () => {
  try {
    const saved = Number(globalThis.localStorage?.getItem(HISTORY_PANE_RATIO_KEY));
    return Number.isFinite(saved) && saved >= 0.2 && saved <= 0.65 ? saved : DEFAULT_HISTORY_PANE_RATIO;
  } catch {
    return DEFAULT_HISTORY_PANE_RATIO;
  }
};
const historyPaneRatio = ref(readHistoryPaneRatio());
const historyAvailableWidth = computed(() => Math.max(880, historyShellWidth.value - 250 - 8 - 42));
const historyMaxWidth = computed(() => Math.max(historyMinWidth, historyAvailableWidth.value - 520));
const historyPaneWidth = computed(() =>
  Math.min(historyMaxWidth.value, Math.max(historyMinWidth, historyAvailableWidth.value * historyPaneRatio.value))
);
const historyPaneStyle = computed(() => ({
  "--history-pane-width": `${Math.round(historyPaneWidth.value)}px`
}));
let historyResizeObserver: ResizeObserver | undefined;
let stopHistoryPointerResize: (() => void) | undefined;
const actionLoading = ref(false);
const admissionProfileDialogVisible = ref(false);
const admissionProfileForm = reactive<Record<string, any>>({});
const admissionProfile = computed<PreAiAdmissionProfile | null>(() => workspace.value?.admissionProfile || null);
const canEditAdmissionProfile = computed(() => ["admin", "tcm", "nurse", "nursing"].includes(currentRole.value));
const activeLabReportId = ref("");
const attachmentUpload = reactive({
  total: 0,
  success: 0,
  failed: 0,
  percent: 0
});
const selectedPanel = ref<"STAGE" | "AUX">("STAGE");
const selectedStageCode = ref<PreAiStageCode>("REGISTRATION");
const topContextCompacted = ref(false);
const workflowContextCompacted = ref(false);
const TOP_CONTEXT_IDLE_MS = 7000;
const WORKFLOW_CONTEXT_IDLE_MS = 9000;
type IdleCompactionTarget = { value: boolean };
const createIdleCompactionController = (target: IdleCompactionTarget, delayMs: number) => {
  let timer: number | undefined;
  const clear = () => {
    if (timer !== undefined) window.clearTimeout(timer);
    timer = undefined;
  };
  const schedule = () => {
    if (typeof window === "undefined") return;
    clear();
    timer = window.setTimeout(() => {
      target.value = true;
      timer = undefined;
    }, delayMs);
  };
  const restore = () => {
    target.value = false;
    schedule();
  };
  return { clear, restore, schedule };
};
const topContextIdle = createIdleCompactionController(topContextCompacted, TOP_CONTEXT_IDLE_MS);
const workflowContextIdle = createIdleCompactionController(workflowContextCompacted, WORKFLOW_CONTEXT_IDLE_MS);
const scheduleTopContextCompaction = () => topContextIdle.schedule();
const restoreTopContext = () => topContextIdle.restore();
const scheduleWorkflowContextCompaction = () => workflowContextIdle.schedule();
const restoreWorkflowContext = () => workflowContextIdle.restore();
const compactStageFieldsExpanded = ref(false);
const createOptionalFieldsExpanded = ref(false);
const compactStageFieldKeys: Partial<Record<PreAiStageCode, Set<string>>> = {
  REGISTRATION: new Set([
    "identityType",
    "identityNumber",
    "address",
    "patientSource",
    "careSituationDescription",
    "registrationPastHistory",
    "registrationCurrentIllness",
    "chronicDiseaseItems",
    "surgicalHistoryItems",
    "traumaHistory",
    "transfusionHistory",
    "vaccinationHistory",
    "medicationHistory",
    "maritalHistory",
    "familyHistory",
    "registrationNote"
  ]),
  INSPECTION: new Set(["inspectionSpecialDescription", "nextReviewAt", "nextReviewNote"]),
  NURSING: new Set([
    "heightCm",
    "weightKg",
    "painScore",
    "fallRisk",
    "pressureUlcerRisk",
    "nutritionScreening",
    "selfCareAbility",
    "nursingAssessmentNote"
  ]),
  RECEPTION: new Set([
    "onsetTrigger",
    "symptomPattern",
    "aggravatingFactors",
    "bleedingFeatures",
    "painFeatures",
    "prolapseReduction",
    "associatedSymptoms",
    "recentAggravation",
    "chiefComplaintSupplement",
    "previousTreatment",
    "generalCondition",
    "stoolFrequency",
    "stoolCharacteristics",
    "chronicDiseaseItems",
    "surgicalHistoryItems",
    "traumaHistory",
    "transfusionHistory",
    "vaccinationHistory",
    "medicationHistory",
    "personalHistory",
    "maritalHistory",
    "familyHistory",
    "historySupplement",
    "specialCircumstances",
    "receptionSpecialDescription",
    "reviewOpinion",
    "dispositionSupplement",
    "recommendedAuxiliaryExams"
  ]),
  TCM: new Set(["auscultationOlfaction", "palpation", "comorbidTcmItems"]),
  DOCTOR: new Set([
    "diagnosisEvidence",
    "differentialDiagnoses",
    "medicationDirections",
    "examPlans",
    "observationFocus",
    "admissionSeverity",
    "treatmentCategory",
    "plannedSecondaryOperations",
    "operationIndications",
    "plannedOperationSite",
    "recommendedAnesthesia",
    "operationGrade",
    "specialOperationPlan"
  ]),
  SURGERY: new Set([
    "actualSecondaryOperations",
    "operationEndTime",
    "intraoperativeFindingOptions",
    "procedureStepOptions",
    "specimenPathology",
    "drainageOptions",
    "dressingOptions",
    "postoperativeHandoffOptions"
  ])
};
const createRegistrationOptionalFieldKeys = compactStageFieldKeys.REGISTRATION || new Set<string>();
const workflowSelected = ref(false);
const editorMode = ref<"EDIT" | "PREVIEW">("EDIT");
const inspectionView = ref<"CURRENT" | "HISTORY">("CURRENT");
const inspectionTimeline = ref<InspectionTimelineNode[]>([]);
const timelineLoading = ref(false);
const responsibilityTimelineVisible = ref(false);
const responsibilityTimelineLoading = ref(false);
const responsibilityTimeline = ref<PreAiAuditLog[]>([]);
const responsibilityActionLabel = (action: string) =>
  (
    ({
      "encounter.create": "创建病历",
      "registration.complete-and-issue": "登记并发号",
      "encounter.followup.register-and-issue": "复诊登记并发号",
      "stage.save": "保存阶段草稿",
      "stage.complete": "完成并交接阶段",
      "stage.correct": "纠错并重新提交",
      "stage.return": "退回阶段",
      "attachment.upload": "上传附件",
      "attachment.void": "作废附件",
      "lab.report.save": "保存化验报告",
      "lab.complete": "确认化验完成",
      "review.confirm": "完成病历复核",
      "export.generate": "生成病历导出",
      "admission-profile.save": "保存住院补录",
      "admission-profile.complete": "完成住院补录"
    }) as Record<string, string>
  )[action] || action;
type ResponsibilityTimelineGroup = {
  id: string;
  roleKey: string;
  operatorLabel: string;
  department: string;
  firstAt: string;
  lastAt: string;
  actionLabels: string[];
  events: PreAiAuditLog[];
};
const responsibilityEventTime = (event: PreAiAuditLog) => event.submittedAt || event.occurredAt || event.createdAt || "";
const responsibilityTimelineGroups = computed<ResponsibilityTimelineGroup[]>(() => {
  const groups: ResponsibilityTimelineGroup[] = [];
  responsibilityTimeline.value.forEach(event => {
    const department = event.operatorDepartment || "";
    const roleKey = `${event.operatorRole || "UNKNOWN"}|${department}`;
    const operatorLabel = event.operatorRole ? roleLabel(event.operatorRole) : department || "历史操作";
    const timestamp = responsibilityEventTime(event);
    const previous = groups[groups.length - 1];
    if (!previous || previous.roleKey !== roleKey) {
      groups.push({
        id: `${event.id}-${roleKey}`,
        roleKey,
        operatorLabel,
        department,
        firstAt: timestamp,
        lastAt: timestamp,
        actionLabels: [responsibilityActionLabel(event.action)],
        events: [event]
      });
      return;
    }
    previous.events.push(event);
    previous.lastAt = timestamp || previous.lastAt;
    const actionLabel = responsibilityActionLabel(event.action);
    if (!previous.actionLabels.includes(actionLabel)) previous.actionLabels.push(actionLabel);
  });
  return groups;
});
const inspectionTimelineEntries = (inspection: Record<string, any>) =>
  nonEmptyEntries(inspection).filter(([key]) => key !== "nextReviewAt" && key !== "nextReviewNote");
const workspaceImageUrls = reactive<Record<string, string>>({});
const stageForms = reactive<Record<PreAiStageCode, Record<string, any>>>({
  REGISTRATION: {},
  INSPECTION: {},
  RECEPTION: {},
  NURSING: {},
  TCM: {},
  DOCTOR: {},
  SURGERY: {},
  REVIEW: {}
});
const stageDirty = reactive<Record<PreAiStageCode, boolean>>({
  REGISTRATION: false,
  INSPECTION: false,
  RECEPTION: false,
  NURSING: false,
  TCM: false,
  DOCTOR: false,
  SURGERY: false,
  REVIEW: false
});
const auxiliaryTasksDirty = ref(false);
const reviewPreview = ref<PreAiReviewPreview>();
const reviewStatement = ref("");
const criticalAcknowledged = ref(false);
const targetMedicalRecordVersions = ref<GeneratedMedicalRecord[]>([]);
const templateTargetVersions = computed(() =>
  targetMedicalRecordVersions.value.filter(record => !record.model || record.model === "docx-template")
);
const aiGeneratedVersions = computed(() =>
  targetMedicalRecordVersions.value.filter(record => Boolean(record.model) && record.model !== "docx-template")
);
const latestAiGeneratedVersionId = computed(() => {
  const list = aiGeneratedVersions.value;
  if (!list.length) return "";
  return [...list].sort((left, right) => right.version - left.version)[0].id;
});
const downloadAiGeneratedRecord = async (version: GeneratedMedicalRecord) => {
  try {
    saveMedicalRecordDownload(await downloadGeneratedMedicalRecordV2Api(version.id));
    ElMessage.success(`AI 病历 V${version.version} 已下载`);
  } catch (error: any) {
    ElMessage.error(error?.message || "AI 病历下载失败");
  }
};
const targetVersionsLoading = ref(false);
const deletingTargetVersionId = ref("");
const latestGeneratedTargetVersionId = ref("");
const latestGeneratedExportVersionId = ref("");
const medicalRecordV2Enabled = true;
const recordChatVisible = ref(false);
const openRecordChat = () => {
  recordChatVisible.value = true;
};
const healthArchiveVisible = ref(false);
const openHealthArchive = () => {
  healthArchiveVisible.value = true;
};
// ===== 门诊病历生成（医生复核确认后可用） =====
const outpatientDialogVisible = ref(false);
const outpatientLoading = ref(false);
const outpatientGenerating = ref(false);
const outpatientSummary = ref<PreAiOutpatientSummary | null>(null);
const outpatientVersions = ref<PreAiOutpatientRecordVersion[]>([]);
const openOutpatientRecord = async () => {
  outpatientDialogVisible.value = true;
  outpatientLoading.value = true;
  try {
    const encounterId = selectedEncounterId.value;
    const preview = await getPreAiOutpatientPreviewApi(encounterId);
    outpatientSummary.value = preview.data;
    try {
      const versions = await getPreAiOutpatientRecordsApi(encounterId);
      outpatientVersions.value = versions.data.versions;
    } catch {
      outpatientVersions.value = [];
    }
  } catch (error: any) {
    ElMessage.error(error?.message || "门诊病历汇总加载失败");
  } finally {
    outpatientLoading.value = false;
  }
};
const generateOutpatientRecord = async () => {
  outpatientGenerating.value = true;
  try {
    const encounterId = selectedEncounterId.value;
    const { data } = await generatePreAiOutpatientRecordApi(encounterId);
    outpatientVersions.value = [data.record, ...outpatientVersions.value];
    ElMessage.success("门诊病历已生成，开始下载");
    await downloadPreAiOutpatientRecordApi(data.record);
  } catch (error: any) {
    ElMessage.error(error?.message || "门诊病历生成失败");
  } finally {
    outpatientGenerating.value = false;
  }
};
const downloadOutpatientRecord = async (version: PreAiOutpatientRecordVersion) => {
  try {
    await downloadPreAiOutpatientRecordApi(version);
  } catch (error: any) {
    ElMessage.error(error?.message || "门诊病历下载失败");
  }
};
const recordChatPatientName = computed(() => workspace.value?.encounter?.patient?.patientName || "");
const recordChatDiagnosisText = computed(() => {
  const rows = workspace.value?.diagnoses ?? [];
  return rows
    .filter(
      item =>
        item.diagnosisType === "WESTERN_PRIMARY" ||
        item.diagnosisType === "WESTERN_SECONDARY" ||
        item.diagnosisType === "WESTERN_COMORBIDITY"
    )
    .map(item => String(item.diagnosisText || "").trim())
    .filter(Boolean)
    .join("、");
});
const DEFAULT_INPATIENT_AI_PROMPT =
  "请按照周xx病历的格式、结构、段落、排版、查房时序，完整生成【姓名】【西医主诊断+次诊断 】的住院病历，要求自动生成中药方剂参考主病、主证及兼证、四诊内容，理法一致，不改动任何格式与写法，排版相同。";
const inpatientAiDialogVisible = ref(false);
const inpatientAiGenerating = ref(false);
const inpatientAiInspecting = ref(false);
const inpatientAiPrompt = ref(DEFAULT_INPATIENT_AI_PROMPT);
const inpatientAiReferenceDocument = ref<File>();
const inpatientAiReferenceInput = ref<HTMLInputElement>();
const inpatientAiInspection = ref<MedicalRecordDocxInspection>();
const inpatientAiMappingMode = ref<MedicalRecordMappingMode>("CONTROLLED");
const inpatientAiTargetNodeKeys = ref<string[]>([]);
const inpatientAiTask = ref<MedicalRecordWorkflowTask>();
const inpatientAiMappings = ref<MedicalRecordWorkflowMappings>();
const pendingGeneratedTargetRecord = ref<GeneratedMedicalRecord>();
const inpatientAiResultDialogVisible = ref(false);
const inpatientAiResultContent = ref("");
const inpatientAiResultModel = ref("");
const inpatientAiResultRecord = ref<GeneratedMedicalRecord>();
let inpatientAiAbortController: AbortController | undefined;
let workspaceRequestSequence = 0;
let workspaceImageRequestSequence = 0;
let timelineRequestSequence = 0;
let reviewRequestSequence = 0;
let targetVersionsRequestSequence = 0;
let historyRequestSequence = 0;
let workspaceAbortController: AbortController | undefined;
let workspaceImageAbortController: AbortController | undefined;
let timelineAbortController: AbortController | undefined;
let reviewAbortController: AbortController | undefined;
let historyAbortController: AbortController | undefined;
let workspaceImageEncounterId = "";
let workspaceImageAttachmentKey = "";
let workspaceImageLoadPromise: Promise<void> | undefined;
let timelinePatientCaseId = "";
let timelineSourceKey = "";
let timelineLoaded = false;
let timelineLoadPromise: Promise<void> | undefined;
let reviewRequestInFlightEncounterId = "";
const pendingWorkflowSelection = ref<{ encounterId: string; card: WorkflowCard }>();
const readPendingWorkflowSelection = () => pendingWorkflowSelection.value;

const currentLocalDateTime = () => {
  const now = new Date();
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(
    now.getMinutes()
  )}:00`;
};

const createClientRequestId = () =>
  globalThis.crypto?.randomUUID?.() || `registration-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;

const createDialogVisible = ref(false);
const createRequestId = ref("");
const handoffNotice = ref("");
const createForm = reactive<Record<string, any>>({
  visitDate: currentLocalDateTime(),
  inventoryCareType: "outpatient"
});
const createTemplateIds = ref<string[]>([]);
const patchCreateForm = (key: string, value: any) => {
  createForm[key] = value;
};

const clinicalTemplateIds = (code: PreAiStageCode) => {
  const saved = stageForms[code].clinicalTemplateIds;
  if (Array.isArray(saved) && saved.length) return saved.map(String);
  if (code === "INSPECTION") return clinicalTemplateIdsForDiseases(stageForms[code].diseaseDirections);
  if (code === "RECEPTION") {
    return clinicalTemplateIdsForDiseases(stageForms.INSPECTION.diseaseDirections).length
      ? clinicalTemplateIdsForDiseases(stageForms.INSPECTION.diseaseDirections)
      : clinicalTemplateIdsForDiseases(stageForms.REGISTRATION.clinicalTemplateDiseases);
  }
  return [];
};

const manualTemplateTouched = reactive(new Set<PreAiStageCode>());
const autoMatchedTemplateLabel = ref("");
let hydrationQuiet = false;

const setClinicalTemplateIds = (code: PreAiStageCode, ids: string[]) => {
  manualTemplateTouched.add(code);
  if (code === "RECEPTION") autoMatchedTemplateLabel.value = "";
  stageForms[code].clinicalTemplateIds = ids;
  const merged = mergeClinicalTemplateSlots(stageForms[code], ids);
  if (merged) stageForms[code].clinicalTemplateSlots = merged;
  markStageDirty(code);
};

/** 前台登记：点选主要症状后自动匹配病种模板，直接填充主诉/现病史，全文落在输入框内可继续修改。 */
watch(
  () => stageForms.REGISTRATION.registrationSymptoms,
  symptoms => {
    if (hydrationQuiet) return;
    const ids = inferTemplateIdsBySymptoms(symptoms);
    if (!ids.length) return;
    const patched = applyClinicalTemplate("REGISTRATION", stageForms.REGISTRATION, ids, "fill");
    const complaint = String(patched.registrationChiefComplaint || "").trim();
    const illness = String(patched.registrationCurrentIllness || "").trim();
    if (!complaint && !illness) return;
    Object.assign(stageForms.REGISTRATION, patched);
    markStageDirty("REGISTRATION");
    ElMessage.success(`已按症状自动匹配「${clinicalTemplateById(ids[0])?.label || ""}」模板，主诉与现病史已生成，可直接修改`);
  },
  { deep: true }
);

/** 接诊室：点选主诉症状后自动填充规范主诉与现病史，可继续修改。 */
watch(
  () => stageForms.RECEPTION.chiefComplaint,
  symptoms => {
    if (hydrationQuiet || manualTemplateTouched.has("RECEPTION")) return;
    if ((stageForms.RECEPTION.clinicalTemplateIds || []).length) return;
    const ids = inferTemplateIdsBySymptoms(symptoms);
    if (!ids.length) return;
    Object.assign(stageForms.RECEPTION, applyClinicalTemplate("RECEPTION", stageForms.RECEPTION, ids, "fill"));
    const merged = mergeClinicalTemplateSlots(stageForms.RECEPTION, ids);
    if (merged) stageForms.RECEPTION.clinicalTemplateSlots = merged;
    autoMatchedTemplateLabel.value = clinicalTemplateById(ids[0])?.label || "";
    markStageDirty("RECEPTION");
    ElMessage.success(`已按症状自动匹配「${autoMatchedTemplateLabel.value}」模板，空字段已填充，可继续修改`);
  },
  { deep: true }
);

const updateStageTemplateSlots = (code: PreAiStageCode, value: Record<string, any>) => {
  stageForms[code].clinicalTemplateSlots = value;
  markStageDirty(code);
};

const manualCreateTemplateTouched = ref(false);
const autoMatchedCreateLabel = ref("");

const setCreateTemplateIds = (ids: string[]) => {
  manualCreateTemplateTouched.value = true;
  autoMatchedCreateLabel.value = "";
  createTemplateIds.value = ids;
  const merged = mergeClinicalTemplateSlots(createForm, ids);
  if (merged) createForm.clinicalTemplateSlots = merged;
};

watch(
  () => createForm.registrationSymptoms,
  symptoms => {
    if (manualCreateTemplateTouched.value) return;
    if (createTemplateIds.value.length) return;
    const ids = inferTemplateIdsBySymptoms(symptoms);
    if (!ids.length) return;
    Object.assign(createForm, applyClinicalTemplate("REGISTRATION", createForm, ids, "fill"));
    createTemplateIds.value = ids;
    autoMatchedCreateLabel.value = clinicalTemplateById(ids[0])?.label || "";
    ElMessage.success(`已按症状自动匹配「${autoMatchedCreateLabel.value}」模板，主诉与现病史已生成，可继续修改`);
  },
  { deep: true }
);

const confirmClinicalTemplateApply = async (mode: ClinicalTemplateMode) => {
  if (mode === "fill" || mode === "render") return true;
  try {
    await ElMessageBox.confirm(
      mode === "overwrite"
        ? "将覆盖本模板关联字段中的现有内容，覆盖后需要重新确认自动结论。是否继续？"
        : "将把模板内容追加到现有内容中。请确认追加前已核对重复内容。",
      mode === "overwrite" ? "覆盖模板字段" : "追加模板",
      { type: "warning", confirmButtonText: "继续", cancelButtonText: "取消" }
    );
    return true;
  } catch {
    return false;
  }
};

const applyStageClinicalTemplate = async (code: PreAiStageCode, mode: ClinicalTemplateMode, ids: string[]) => {
  if (!ids.length || !(await confirmClinicalTemplateApply(mode))) return false;
  Object.assign(stageForms[code], applyClinicalTemplate(code, stageForms[code], ids, mode));
  if (code === "INSPECTION" && mode !== "fill") {
    const conclusion = buildInspectionConclusion(stageForms.INSPECTION);
    stageForms.INSPECTION.factualConclusion = conclusion;
    stageForms.INSPECTION.factualConclusionOverride = conclusion;
    stageForms.INSPECTION.factualConclusionConfirmed = false;
  }
  markStageDirty(code);
  ElMessage.success(
    mode === "render"
      ? "已按当前模板变量重新生成，手工修改过的内容保持不变"
      : mode === "fill"
        ? "已生成检查记录全文，可在下方文本框直接修改"
        : "检查记录已按模板重新生成，请核对后提交"
  );
  return true;
};

// 检查室试点：选择病种即按默认变量生成检查记录全文（textarea 可直接修改）
const onClinicalTemplateSelection = async (code: PreAiStageCode, ids: string[]) => {
  if (code !== "INSPECTION") return;
  const previous = [...clinicalTemplateIds(code)];
  setClinicalTemplateIds(code, ids);
  const narrative = String(stageForms.INSPECTION.inspectionNarrative || "").trim();
  const applied = await applyStageClinicalTemplate("INSPECTION", narrative ? "overwrite" : "fill", ids);
  if (!applied && narrative) setClinicalTemplateIds(code, previous);
};

const applyCreateClinicalTemplate = async (mode: ClinicalTemplateMode, ids: string[]) => {
  if (!ids.length || !(await confirmClinicalTemplateApply(mode))) return;
  Object.assign(createForm, applyClinicalTemplate("REGISTRATION", createForm, ids, mode));
  createTemplateIds.value = ids;
  ElMessage.success(
    mode === "render"
      ? "已按当前模板变量重新生成，手工修改过的内容保持不变"
      : mode === "fill"
        ? "已填充空白字段，可继续修改"
        : "模板内容已更新，请核对后登记"
  );
};

const openResponsibilityTimeline = async () => {
  if (!selectedEncounterId.value) return;
  responsibilityTimelineVisible.value = true;
  responsibilityTimelineLoading.value = true;
  try {
    const { data } = await getPreAiResponsibilityTimelineApi(selectedEncounterId.value);
    responsibilityTimeline.value = data.events || [];
  } catch (error: any) {
    ElMessage.error(error.message || "责任时间轴加载失败");
  } finally {
    responsibilityTimelineLoading.value = false;
  }
};

const openAdmissionProfile = () => {
  const profile = admissionProfile.value;
  if (!profile) return;
  Object.keys(admissionProfileForm).forEach(key => delete admissionProfileForm[key]);
  Object.assign(admissionProfileForm, profile.data || {});
  admissionProfileDialogVisible.value = true;
};

const saveAdmissionProfile = (complete: boolean) => {
  const profile = admissionProfile.value;
  if (!profile || !selectedEncounterId.value) return;
  runAction(async () => {
    const { data } = await savePreAiAdmissionProfileApi(
      selectedEncounterId.value,
      admissionProfileForm,
      profile.version,
      complete
    );
    hydrateWorkspace(data);
    admissionProfileDialogVisible.value = false;
    ElMessage.success(complete ? "住院资料补录已完成" : "住院资料草稿已保存");
  });
};
const openCreateDialog = () => {
  createRequestId.value = createClientRequestId();
  createDialogVisible.value = true;
};
const legacyDialogVisible = ref(false);
const selectedLegacyPatientId = ref("");
const legacyPatients = ref<PatientRow[]>([]);
const followUpDialogVisible = ref(false);
const followUpPatientCase = ref<PreAiPatientCase>();
const followUpRequestId = ref("");
const followUpForm = reactive<Record<string, any>>({
  visitDate: currentLocalDateTime()
});

const patientCaseRecordTime = (item: PreAiPatientCase) =>
  item.createdAt || item.latestEncounter?.visitDate || item.updatedAt || "";
const patientCaseRecordTimestamp = (item: PreAiPatientCase) => {
  const raw = patientCaseRecordTime(item);
  if (!raw) return 0;
  const parsed = Date.parse(raw.replace(" ", "T"));
  return Number.isFinite(parsed) ? parsed : 0;
};
const patientCaseDatePart = (value?: string) => value?.split(/[ T]/)[0] || "";
const patientCaseDateCandidates = (item: PreAiPatientCase) =>
  [item.createdAt, item.latestEncounter?.visitDate, item.updatedAt].map(patientCaseDatePart).filter(Boolean);
const formatPatientCaseRecordTime = (item: PreAiPatientCase) => patientCaseRecordTime(item) || "待补录入时间";

const filteredPatientCases = computed(() => {
  const value = keyword.value.trim().toLowerCase();
  const recordDate = patientArchiveDate.value;
  return patientCases.value
    .filter(item => {
      const tags = item.latestEncounter?.careSituationTags || "";
      const matchesSituation =
        careSituationFilter.value === "ALL" ||
        (careSituationFilter.value === "OUTPATIENT" && tags.includes("门诊")) ||
        (careSituationFilter.value === "INPATIENT" && tags.includes("住院")) ||
        (careSituationFilter.value === "LOW_INCOME" && tags.includes("低保"));
      const matchesDate = !recordDate || patientCaseDateCandidates(item).includes(recordDate);
      const searchable = `${item.patientName} ${item.latestEncounter?.caseToken || ""}`.toLowerCase();
      return matchesSituation && matchesDate && (!value || searchable.includes(value));
    })
    .sort((left, right) => patientCaseRecordTimestamp(right) - patientCaseRecordTimestamp(left));
});

interface PatientArchiveCardDetail {
  loading: boolean;
  loaded: boolean;
  imageLoading: boolean;
  chiefComplaint: string;
  diseaseDirection?: string;
  images: PreAiAttachment[];
  error?: string;
}

interface PatientArchiveInfoTag {
  key: string;
  label: string;
}

const patientArchiveCardDetails = reactive<Record<string, PatientArchiveCardDetail>>({});
const patientArchiveImageUrls = reactive<Record<string, string>>({});
const patientArchiveImageErrors = reactive<Record<string, string>>({});
const patientArchiveMasonryLoading = ref(false);
const patientArchiveImageStageCodes: PreAiStageCode[] = ["RECEPTION", "INSPECTION"];
const patientArchiveTagTones = ["teal", "blue", "violet", "amber", "rose", "green"];
const patientArchiveSampleSvgUrl = (accent: string, soft: string, label: string) =>
  `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 360 240"><rect width="360" height="240" rx="22" fill="${soft}"/><circle cx="86" cy="82" r="46" fill="${accent}" opacity="0.18"/><rect x="142" y="56" width="146" height="18" rx="9" fill="${accent}" opacity="0.32"/><rect x="142" y="90" width="96" height="14" rx="7" fill="${accent}" opacity="0.22"/><path d="M56 172c42-46 72-44 104-8 26 30 52 30 86-4 20-20 38-28 58-18v46H56z" fill="${accent}" opacity="0.28"/><text x="180" y="214" text-anchor="middle" font-size="18" font-family="Arial, sans-serif" fill="${accent}" font-weight="700">${label}</text></svg>`
  )}`;
const patientArchiveSampleAttachments: PreAiAttachment[] = [
  {
    id: "patient-archive-sample-1",
    encounterId: "patient-archive-sample",
    stageCode: "RECEPTION",
    fileName: "sample-reception-1.svg",
    mimeType: "image/svg+xml",
    fileSize: 0,
    description: "示例缩略图 1",
    createdAt: "",
    downloadUrl: ""
  },
  {
    id: "patient-archive-sample-2",
    encounterId: "patient-archive-sample",
    stageCode: "RECEPTION",
    fileName: "sample-reception-2.svg",
    mimeType: "image/svg+xml",
    fileSize: 0,
    description: "示例缩略图 2",
    createdAt: "",
    downloadUrl: ""
  },
  {
    id: "patient-archive-sample-3",
    encounterId: "patient-archive-sample",
    stageCode: "INSPECTION",
    fileName: "sample-inspection-3.svg",
    mimeType: "image/svg+xml",
    fileSize: 0,
    description: "示例缩略图 3",
    createdAt: "",
    downloadUrl: ""
  }
];
const patientArchiveSampleImageUrls: Record<string, string> = {
  "patient-archive-sample-1": patientArchiveSampleSvgUrl("#0f766e", "#ecfdf5", "Sample A"),
  "patient-archive-sample-2": patientArchiveSampleSvgUrl("#2563eb", "#eff6ff", "Sample B"),
  "patient-archive-sample-3": patientArchiveSampleSvgUrl("#b45309", "#fffbeb", "Sample C")
};
const patientArchiveCardElements = new Map<string, Element>();
const patientArchiveLoadQueue: PreAiPatientCase[] = [];
const patientArchiveQueuedCaseIds = new Set<string>();
const PATIENT_ARCHIVE_CARD_LOAD_LIMIT = 2;
const PATIENT_ARCHIVE_THUMBNAIL_LIMIT = 4;
let patientArchiveActiveLoads = 0;
let patientArchiveRequestSequence = 0;
let patientArchiveAbortController: AbortController | undefined;
let patientArchiveObserver: IntersectionObserver | undefined;

const patientArchiveHash = (value: string) =>
  Array.from(value).reduce((total, char) => ((total << 5) - total + char.charCodeAt(0)) | 0, 0);
const patientArchiveTagClass = (key: string, seed: string) => {
  const tone = patientArchiveTagTones[Math.abs(patientArchiveHash(`${seed}:${key}`)) % patientArchiveTagTones.length];
  return ["patient-archive-info-tag", `tone-${tone}`];
};
const truncatePatientArchiveText = (value: string, maxLength = 36) => {
  const compact = value.replace(/\s+/g, " ").trim();
  return compact.length > maxLength ? `${compact.slice(0, maxLength)}...` : compact;
};
const patientArchiveHumanText = (value: any, maxLength = 36) => truncatePatientArchiveText(humanValue(value), maxLength);
const firstPatientArchiveText = (...values: any[]) => values.map(value => patientArchiveHumanText(value)).find(Boolean) || "";
const patientArchiveDiseaseDirectionText = (value: any) => {
  const directions = Array.isArray(value) ? value : value ? [value] : [];
  return directions
    .map(item => patientArchiveHumanText(item, 18))
    .filter(Boolean)
    .slice(0, 2)
    .join(" / ");
};
const patientArchiveStageData = (value: PreAiWorkspace, code: PreAiStageCode) =>
  value.stages.find(stage => stage.stageCode === code)?.data || {};
const patientArchiveImagesFromWorkspace = (value: PreAiWorkspace) =>
  value.attachments
    .filter(
      attachment =>
        attachment.stageCode && patientArchiveImageStageCodes.includes(attachment.stageCode) && isImageAttachment(attachment)
    )
    .sort((left, right) => {
      const leftStage = patientArchiveImageStageCodes.indexOf(left.stageCode as PreAiStageCode);
      const rightStage = patientArchiveImageStageCodes.indexOf(right.stageCode as PreAiStageCode);
      if (leftStage !== rightStage) return leftStage - rightStage;
      return (
        (left.sequenceNo || 0) - (right.sequenceNo || 0) ||
        String(right.createdAt || "").localeCompare(String(left.createdAt || ""))
      );
    });
const patientArchiveChiefComplaintFromWorkspace = (value: PreAiWorkspace) => {
  const reception = patientArchiveStageData(value, "RECEPTION");
  const registration = patientArchiveStageData(value, "REGISTRATION");
  return firstPatientArchiveText(
    reception.chiefComplaint,
    reception.chiefComplaintSupplement,
    registration.registrationChiefComplaint,
    registration.registrationSymptoms,
    value.encounter.visitMeta?.visitReason,
    value.encounter.visitMeta?.description
  );
};
const patientArchiveDiseaseDirectionFromWorkspace = (value: PreAiWorkspace) =>
  patientArchiveDiseaseDirectionText(patientArchiveStageData(value, "INSPECTION").diseaseDirections);
const patientArchiveDetailOf = (item: PreAiPatientCase) => patientArchiveCardDetails[item.id];
const isTemplateValidationPatient = (item: PreAiPatientCase) => String(item.patientName || "").includes("模板验证患者");
const patientArchiveDiseaseDirection = (item: PreAiPatientCase) =>
  patientArchiveDetailOf(item)?.diseaseDirection ||
  patientArchiveDiseaseDirectionText(
    item.patient?.diseaseDirections || item.patient?.diseaseDirection || item.patient?.inspectionDiseaseDirections
  );
const patientArchiveChiefComplaint = (item: PreAiPatientCase) =>
  patientArchiveDetailOf(item)?.chiefComplaint ||
  firstPatientArchiveText(
    item.patient?.chiefComplaint,
    item.patient?.registrationChiefComplaint,
    item.patient?.registrationSymptoms,
    item.patient?.visitReason
  );
const hydratePatientArchiveSampleCard = (item: PreAiPatientCase) => {
  patientArchiveCardDetails[item.id] = {
    loading: false,
    loaded: true,
    imageLoading: false,
    chiefComplaint: patientArchiveChiefComplaint(item) || "示例主诉：肛周不适，便后偶有出血",
    diseaseDirection: patientArchiveDiseaseDirection(item) || "混合痔方向",
    images: patientArchiveSampleAttachments
  };
  Object.assign(patientArchiveImageUrls, patientArchiveSampleImageUrls);
};
const patientArchiveCardTags = (item: PreAiPatientCase): PatientArchiveInfoTag[] => {
  const tags: PatientArchiveInfoTag[] = [];
  const genderAge = [item.gender, item.age].filter(Boolean).join(" / ");
  if (genderAge) tags.push({ key: "gender-age", label: genderAge });
  if (item.latestEncounter?.route) tags.push({ key: "route", label: routeLabel(item.latestEncounter.route) });
  const chiefComplaint = patientArchiveChiefComplaint(item);
  const diseaseDirection = patientArchiveDiseaseDirection(item);
  if (diseaseDirection) tags.push({ key: "disease-direction", label: `病种：${diseaseDirection}` });
  if (chiefComplaint) tags.push({ key: "chief-complaint", label: `主诉：${chiefComplaint}` });
  (item.latestEncounter?.careSituationTags || "")
    .split(",")
    .map(tag => tag.trim())
    .filter(Boolean)
    .slice(0, 2)
    .forEach((tag, index) => tags.push({ key: `care-${index}-${tag}`, label: tag }));
  if (item.latestEncounter) tags.push({ key: "status", label: encounterStatusLabel[item.latestEncounter.status] });
  return tags.slice(0, 6);
};
const patientArchiveCardImages = (item: PreAiPatientCase) =>
  (patientArchiveDetailOf(item)?.images || []).slice(0, PATIENT_ARCHIVE_THUMBNAIL_LIMIT);
const patientArchiveCardPreviewUrls = (item: PreAiPatientCase) =>
  patientArchiveCardImages(item)
    .map(attachment => patientArchiveImageUrls[attachment.id])
    .filter(Boolean);
const patientArchiveCardPreviewIndex = (item: PreAiPatientCase, attachment: PreAiAttachment) => {
  const imageIds = patientArchiveCardImages(item)
    .filter(image => patientArchiveImageUrls[image.id])
    .map(image => image.id);
  return Math.max(0, imageIds.indexOf(attachment.id));
};
const patientArchiveCardEmptyText = (item: PreAiPatientCase) => {
  const detail = patientArchiveDetailOf(item);
  if (!item.latestEncounter) return "尚无患者图片";
  if (!detail) return "进入视图后加载图片";
  if (detail.loading) return "正在读取病历";
  if (detail.imageLoading) return "正在加载缩略图";
  if (detail.error) return detail.error;
  return "暂无患者图片";
};
const revokePatientArchiveImageUrl = (attachmentId: string) => {
  const url = patientArchiveImageUrls[attachmentId];
  if (url) URL.revokeObjectURL(url);
  delete patientArchiveImageUrls[attachmentId];
};
const updatePatientArchiveMasonryLoading = () => {
  patientArchiveMasonryLoading.value = patientArchiveActiveLoads > 0 || patientArchiveLoadQueue.length > 0;
};
const attachmentWithDownloadUrl = (attachment: PreAiAttachment): PreAiAttachment => ({
  ...attachment,
  downloadUrl:
    attachment.downloadUrl ||
    `/clinic-api/pre-ai/encounters/${encodeURIComponent(attachment.encounterId)}/attachments/${encodeURIComponent(attachment.id)}/download`
});
const loadPatientArchiveImage = async (attachment: PreAiAttachment, signal: AbortSignal, requestSequence: number) => {
  if (patientArchiveImageUrls[attachment.id]) return;
  delete patientArchiveImageErrors[attachment.id];
  try {
    const url = await getPreAiAttachmentObjectUrlApi(attachmentWithDownloadUrl(attachment), signal);
    if (signal.aborted || requestSequence !== patientArchiveRequestSequence) {
      URL.revokeObjectURL(url);
      return;
    }
    patientArchiveImageUrls[attachment.id] = url;
  } catch (error: any) {
    if (error?.name !== "AbortError" && requestSequence === patientArchiveRequestSequence) {
      patientArchiveImageErrors[attachment.id] = error?.message || "缩略图加载失败";
    }
  }
};
const loadPatientArchiveCard = async (item: PreAiPatientCase, requestSequence: number, signal: AbortSignal) => {
  if (isTemplateValidationPatient(item)) {
    hydratePatientArchiveSampleCard(item);
    return;
  }
  if (!item.latestEncounter || signal.aborted || requestSequence !== patientArchiveRequestSequence) return;
  patientArchiveCardDetails[item.id] = {
    loading: true,
    loaded: false,
    imageLoading: false,
    chiefComplaint: patientArchiveChiefComplaint(item),
    diseaseDirection: patientArchiveDiseaseDirection(item),
    images: []
  };

  try {
    const { data } = await getPreAiReadOnlyWorkspaceApi(item.latestEncounter.id, item.id, signal);
    if (signal.aborted || requestSequence !== patientArchiveRequestSequence) return;
    const images = patientArchiveImagesFromWorkspace(data);
    patientArchiveCardDetails[item.id] = {
      loading: false,
      loaded: true,
      imageLoading: Boolean(images.length),
      chiefComplaint: patientArchiveChiefComplaintFromWorkspace(data),
      diseaseDirection: patientArchiveDiseaseDirectionFromWorkspace(data),
      images
    };
    for (const attachment of images.slice(0, PATIENT_ARCHIVE_THUMBNAIL_LIMIT)) {
      if (signal.aborted || requestSequence !== patientArchiveRequestSequence) return;
      await loadPatientArchiveImage(attachment, signal, requestSequence);
    }
    if (requestSequence === patientArchiveRequestSequence && patientArchiveCardDetails[item.id]) {
      patientArchiveCardDetails[item.id].imageLoading = false;
    }
  } catch (error: any) {
    if (error?.name !== "AbortError" && requestSequence === patientArchiveRequestSequence) {
      patientArchiveCardDetails[item.id] = {
        loading: false,
        loaded: true,
        imageLoading: false,
        chiefComplaint: patientArchiveChiefComplaint(item),
        diseaseDirection: patientArchiveDiseaseDirection(item),
        images: [],
        error: error?.message || "病历详情加载失败"
      };
    }
  }
};
const processPatientArchiveLoadQueue = () => {
  if (!patientDrawerOpen.value || patientArchiveView.value !== "MASONRY") return;
  if (!patientArchiveAbortController || patientArchiveAbortController.signal.aborted)
    patientArchiveAbortController = new AbortController();
  const signal = patientArchiveAbortController.signal;
  const requestSequence = patientArchiveRequestSequence;

  while (patientArchiveActiveLoads < PATIENT_ARCHIVE_CARD_LOAD_LIMIT && patientArchiveLoadQueue.length) {
    const item = patientArchiveLoadQueue.shift();
    if (!item || patientArchiveCardDetails[item.id]?.loaded || patientArchiveCardDetails[item.id]?.loading) continue;
    patientArchiveQueuedCaseIds.delete(item.id);
    patientArchiveActiveLoads += 1;
    updatePatientArchiveMasonryLoading();
    void loadPatientArchiveCard(item, requestSequence, signal).finally(() => {
      if (requestSequence === patientArchiveRequestSequence) {
        patientArchiveActiveLoads = Math.max(0, patientArchiveActiveLoads - 1);
        updatePatientArchiveMasonryLoading();
        processPatientArchiveLoadQueue();
      }
    });
  }
  updatePatientArchiveMasonryLoading();
};
const queuePatientArchiveCardLoad = (item: PreAiPatientCase) => {
  if (
    (!item.latestEncounter && !isTemplateValidationPatient(item)) ||
    patientArchiveCardDetails[item.id]?.loaded ||
    patientArchiveCardDetails[item.id]?.loading
  )
    return;
  if (patientArchiveQueuedCaseIds.has(item.id)) return;
  patientArchiveQueuedCaseIds.add(item.id);
  patientArchiveLoadQueue.push(item);
  processPatientArchiveLoadQueue();
};
const ensurePatientArchiveObserver = () => {
  if (patientArchiveObserver || typeof IntersectionObserver === "undefined") return patientArchiveObserver;
  patientArchiveObserver = new IntersectionObserver(
    entries => {
      entries.forEach(entry => {
        if (!entry.isIntersecting) return;
        patientArchiveObserver?.unobserve(entry.target);
        const id = (entry.target as HTMLElement).dataset.patientCaseId || "";
        const item = filteredPatientCases.value.find(candidate => candidate.id === id);
        if (item) queuePatientArchiveCardLoad(item);
      });
    },
    { root: null, rootMargin: "180px 0px", threshold: 0.01 }
  );
  return patientArchiveObserver;
};
const observePatientArchiveCard = (element: Element, item: PreAiPatientCase) => {
  if (!patientDrawerOpen.value || patientArchiveView.value !== "MASONRY") return;
  if (patientArchiveCardDetails[item.id]?.loaded || patientArchiveCardDetails[item.id]?.loading) return;
  const observer = ensurePatientArchiveObserver();
  if (observer) observer.observe(element);
  else queuePatientArchiveCardLoad(item);
};
const setPatientArchiveMasonryCardRef = (element: Element | null, item: PreAiPatientCase) => {
  const existing = patientArchiveCardElements.get(item.id);
  if (existing) patientArchiveObserver?.unobserve(existing);
  if (!element) {
    patientArchiveCardElements.delete(item.id);
    return;
  }
  patientArchiveCardElements.set(item.id, element);
  observePatientArchiveCard(element, item);
};
const abortPatientArchiveMasonryRequests = () => {
  patientArchiveAbortController?.abort();
  patientArchiveAbortController = undefined;
  patientArchiveRequestSequence += 1;
  patientArchiveLoadQueue.length = 0;
  patientArchiveQueuedCaseIds.clear();
  patientArchiveActiveLoads = 0;
  Object.keys(patientArchiveCardDetails).forEach(key => {
    const detail = patientArchiveCardDetails[key];
    if (detail.loading || detail.imageLoading) delete patientArchiveCardDetails[key];
  });
  updatePatientArchiveMasonryLoading();
};
const clearPatientArchiveMasonryResources = () => {
  abortPatientArchiveMasonryRequests();
  patientArchiveObserver?.disconnect();
  patientArchiveObserver = undefined;
  patientArchiveCardElements.clear();
  Object.keys(patientArchiveCardDetails).forEach(key => delete patientArchiveCardDetails[key]);
  Object.keys(patientArchiveImageUrls).forEach(revokePatientArchiveImageUrl);
  Object.keys(patientArchiveImageErrors).forEach(key => delete patientArchiveImageErrors[key]);
};
watch(
  () => [patientDrawerOpen.value, patientArchiveView.value, filteredPatientCases.value.map(item => item.id).join("|")],
  async () => {
    if (!patientDrawerOpen.value || patientArchiveView.value !== "MASONRY") {
      abortPatientArchiveMasonryRequests();
      patientArchiveObserver?.disconnect();
      patientArchiveObserver = undefined;
      return;
    }
    if (!patientArchiveAbortController || patientArchiveAbortController.signal.aborted) {
      patientArchiveRequestSequence += 1;
      patientArchiveAbortController = new AbortController();
    }
    await nextTick();
    patientArchiveCardElements.forEach((element, id) => {
      const item = filteredPatientCases.value.find(candidate => candidate.id === id);
      if (item) observePatientArchiveCard(element, item);
    });
    updatePatientArchiveMasonryLoading();
  },
  { flush: "post" }
);
const canHandleStage = (stageCode: PreAiStageCode, ...duties: PreAiDutyCode[]) =>
  Boolean(authStore.stagePermissions[stageCode]?.editable) || hasAssignedDuty(...duties);
const workflowCards = computed<WorkflowCard[]>(() => {
  const cards: WorkflowCard[] = [
    {
      key: "REGISTRATION",
      order: 1,
      kind: "STAGE",
      stageCode: "REGISTRATION",
      title: "前台登记",
      owner: "前台",
      editable: canHandleStage("REGISTRATION", "FRONT_DESK")
    },
    {
      key: "INSPECTION",
      order: 2,
      kind: "STAGE",
      stageCode: "INSPECTION",
      title: "检查室",
      owner: "检查室",
      editable: canHandleStage("INSPECTION", "INSPECTION_DOCTOR", "RECEPTION_DOCTOR", "ATTENDING_DOCTOR")
    },
    {
      key: "RECEPTION",
      order: 3,
      kind: "STAGE",
      stageCode: "RECEPTION",
      title: "接诊评估",
      owner: "接诊室",
      editable: canHandleStage("RECEPTION", "RECEPTION_DOCTOR", "INSPECTION_DOCTOR", "ATTENDING_DOCTOR")
    },
    {
      // 护理部固定在接诊室之后：是否开放由接诊室最终判定住院决定（nursingUnlocked），门诊自动跳过
      key: "NURSING",
      order: 4,
      kind: "STAGE",
      stageCode: "NURSING",
      title: "护理部评估",
      owner: "护理部",
      editable: nursingUnlocked.value && canHandleStage("NURSING", "BASIC_NURSING")
    },
    {
      key: "AUX",
      order: 5,
      kind: "AUX",
      title: "化验等辅助检查",
      owner: "检验报告、心电、影像与其他辅助检查",
      editable: canOpenLabWorkbench.value
    },
    {
      key: "TCM",
      order: 6,
      kind: "STAGE",
      stageCode: "TCM",
      title: "中医辨证",
      owner: "中医岗位",
      editable: canHandleStage("TCM", "TCM_DOCTOR")
    },
    {
      key: "DOCTOR",
      order: 7,
      kind: "STAGE",
      stageCode: "DOCTOR",
      title: "医生诊疗方案",
      owner: "医生",
      editable: canHandleStage("DOCTOR", "ATTENDING_DOCTOR")
    },
    {
      key: "SURGERY",
      order: 8,
      kind: "STAGE",
      stageCode: "SURGERY",
      title: "手术结果登记",
      owner: "手术室护士",
      editable: canHandleStage("SURGERY", "SURGEON", "OPERATING_ROOM_NURSE")
    },
    {
      key: "REVIEW",
      order: 9,
      kind: "STAGE",
      stageCode: "REVIEW",
      title: "医生最终复核",
      owner: "医生",
      editable: canReview.value
    }
  ];
  return cards;
});
const workflowProgress = computed(() => {
  const statuses = workflowCards.value.map(card => workflowCardStatus(card));
  return {
    total: statuses.length,
    completed: statuses.filter(status => ["COMPLETED", "SKIPPED"].includes(status)).length,
    returned: statuses.filter(status => status === "RETURNED").length
  };
});
const activeWorkflowCard = computed(
  () =>
    workflowCards.value.find(card =>
      selectedPanel.value === "AUX" ? card.kind === "AUX" : card.kind === "STAGE" && card.stageCode === selectedStageCode.value
    ) || workflowCards.value[0]
);
const selectedStage = computed(() => stageByCode(selectedStageCode.value));
const selectedStageSubmission = computed(() => stageSubmission(selectedStageCode.value)!);
const visibleStageFields = computed(() =>
  selectedStage.value.fields.filter(field => !field.visible || field.visible(stageForms[selectedStageCode.value]))
);
// 检查室收束视图：病种模板 + 检查记录 textarea 承载全部录入，旧槽位字段不再渲染
const stageFormFields = computed(() => {
  if (selectedStageCode.value === "INSPECTION") return [];
  // 护理部：四测在上方强调区单独录入并按轮次生成时间轴，常规表单仅保留病史采集与护理评估
  if (selectedStageCode.value === "NURSING")
    return visibleStageFields.value.filter(field => !nursingVitalFieldKeys.has(field.key));
  return visibleStageFields.value;
});

// 护理部四测：强调区录入字段与轮次记录（vitalSignRounds 随阶段草稿持久化，仅系统留存）
const nursingVitalFieldKeys = new Set(["measuredAt", "systolicBp", "diastolicBp", "temperature", "pulse", "respiration"]);
const VITAL_NORMAL_STATUSES = new Set(["", "正常", "未判断"]);
const nursingVitalFields = computed(() => stageByCode("NURSING").fields.filter(field => nursingVitalFieldKeys.has(field.key)));
const vitalRounds = computed<Record<string, any>[]>(() => {
  const rounds = stageForms.NURSING.vitalSignRounds;
  return Array.isArray(rounds) ? rounds : [];
});
const vitalRoundItems = computed(() => vitalRounds.value.map((round, idx) => ({ round, idx })).reverse());
const nowTimeString = () => new Date().toISOString().slice(0, 19).replace("T", " ");
const vitalRoundEntries = (round: Record<string, any>) =>
  nursingVitalFields.value
    .filter(field => field.key !== "measuredAt")
    .map(field => {
      const value = round[field.key];
      if (value && typeof value === "object") {
        const text = `${value.value ?? ""}${value.unit ?? ""}`.trim();
        return { key: field.key, label: field.label, text, status: String(value.status || "").trim() };
      }
      const text = String(value ?? "").trim();
      return { key: field.key, label: field.label, text, status: "" };
    })
    .filter(item => item.text);
const vitalEntryAbnormal = (status: string) => !VITAL_NORMAL_STATUSES.has(String(status || "").trim());
const vitalRoundHasCritical = (round: Record<string, any>) =>
  vitalRoundEntries(round).some(entry => String(entry.status).trim() === "危急值");
const vitalRoundHasAbnormal = (round: Record<string, any>) =>
  vitalRoundEntries(round).some(entry => vitalEntryAbnormal(entry.status));
const vitalRoundTime = (round: Record<string, any>) =>
  String(round?.measuredAt || "")
    .replace("T", " ")
    .slice(0, 16) || "时间未记录";
const recordVitalRound = () => {
  if (!canModifySelectedStage.value) return;
  const round: Record<string, any> = {};
  nursingVitalFields.value.forEach(field => {
    const value = stageForms.NURSING[field.key];
    if (hasFormValue(value, field)) round[field.key] = value;
  });
  if (!["systolicBp", "diastolicBp", "temperature", "pulse", "respiration"].some(key => round[key])) {
    ElMessage.warning("请先填写至少一项四测数值再记录本轮");
    return;
  }
  if (!Array.isArray(stageForms.NURSING.vitalSignRounds)) stageForms.NURSING.vitalSignRounds = [];
  stageForms.NURSING.vitalSignRounds.push(round);
  nursingVitalFields.value.forEach(field => {
    stageForms.NURSING[field.key] =
      field.key === "measuredAt" ? nowTimeString() : { value: "", unit: field.unitOptions?.[0] || "", status: "" };
  });
  markStageDirty("NURSING");
  ElMessage.success("本轮四测已记录；请点击保存或完成交接持久化");
};
const removeVitalRound = (index: number) => {
  if (!canModifySelectedStage.value) return;
  const rounds = stageForms.NURSING.vitalSignRounds;
  if (Array.isArray(rounds)) {
    rounds.splice(index, 1);
    markStageDirty("NURSING");
  }
};
watch(selectedStageCode, code => {
  if (code === "NURSING" && !stageForms.NURSING.measuredAt) stageForms.NURSING.measuredAt = nowTimeString();
});
// 手术卡自动带出：进入卡片时仅预填空值（手术者←主管医生、责任护士←手术室护士、
// 术前诊断←医生岗西医主诊断、术后诊断←术前诊断值），全部可手工覆盖；
// hydration 静默期不触发，避免刷新时覆盖服务端已保存的值。
watch(selectedStageCode, code => {
  if (code !== "SURGERY" || hydrationQuiet || !workspace.value) return;
  const form = stageForms.SURGERY;
  const dutyName = (dutyCode: PreAiDutyCode) => {
    const assignment = (workspace.value?.dutyAssignments || []).find(item => item.dutyCode === dutyCode);
    return String(assignment?.responsibleUserName || assignment?.participantUserNames?.[0] || "").trim();
  };
  const doctorPrimaryDiagnosis = String(stageForms.DOCTOR.primaryWesternDiagnosis || "").trim();
  if (!String(form.surgeonName || "").trim()) form.surgeonName = dutyName("ATTENDING_DOCTOR");
  if (!String(form.nurseName || "").trim()) form.nurseName = dutyName("OPERATING_ROOM_NURSE");
  if (!String(form.preoperativeDiagnosis || "").trim() && doctorPrimaryDiagnosis) {
    form.preoperativeDiagnosis = doctorPrimaryDiagnosis;
  }
  if (!String(form.postoperativeDiagnosis || "").trim() && String(form.preoperativeDiagnosis || "").trim()) {
    form.postoperativeDiagnosis = form.preoperativeDiagnosis;
  }
});
const isSecondaryStageField = (field: PreAiFieldConfig) =>
  Boolean(compactStageFieldKeys[selectedStageCode.value]?.has(field.key));
const secondaryStageFieldsCount = computed(() => visibleStageFields.value.filter(field => isSecondaryStageField(field)).length);
const registrationFields = computed(() => stageByCode("REGISTRATION").fields.filter(field => field.key !== "visitNo"));
const registrationDialogFields = computed(() =>
  createOptionalFieldsExpanded.value
    ? registrationFields.value.filter(field => field.kind !== "repeatable")
    : registrationFields.value.filter(field => !createRegistrationOptionalFieldKeys.has(field.key) && field.kind !== "repeatable")
);
const createSecondaryRegistrationFieldsCount = computed(
  () =>
    registrationFields.value.filter(field => createRegistrationOptionalFieldKeys.has(field.key) && field.kind !== "repeatable")
      .length
);
watch(selectedStageCode, () => {
  compactStageFieldsExpanded.value = false;
  restoreWorkflowContext();
});
watch(selectedPanel, restoreWorkflowContext);
watch(workspace, value => {
  if (!value) {
    topContextCompacted.value = false;
    workflowContextCompacted.value = false;
    topContextIdle.clear();
    workflowContextIdle.clear();
    return;
  }
  restoreTopContext();
  restoreWorkflowContext();
});
const labTask = computed(() => workspace.value?.auxiliaryTasks.find(task => task.taskType === "LAB"));
const legacyAuxiliaryTasks = computed(() => workspace.value?.auxiliaryTasks.filter(task => task.taskType !== "LAB") || []);
const activeWorkflowTitle = computed(() =>
  selectedPanel.value === "AUX" ? "化验等辅助检查" : stageByCode(selectedStageCode.value).title
);
const activeWorkflowOwner = computed(() =>
  selectedPanel.value === "AUX" ? "责任岗位：化验等辅助检查" : `责任岗位：${stageByCode(selectedStageCode.value).owner}`
);
const stageDutyCodes: Partial<Record<PreAiStageCode, PreAiDutyCode[]>> = {
  REGISTRATION: ["FRONT_DESK"],
  INSPECTION: ["INSPECTION_DOCTOR", "RECEPTION_DOCTOR", "ATTENDING_DOCTOR"],
  RECEPTION: ["RECEPTION_DOCTOR", "INSPECTION_DOCTOR", "ATTENDING_DOCTOR"],
  NURSING: ["BASIC_NURSING"],
  TCM: ["TCM_DOCTOR"],
  DOCTOR: ["ATTENDING_DOCTOR"],
  SURGERY: ["SURGEON", "OPERATING_ROOM_NURSE"],
  REVIEW: ["FINAL_REVIEW_DOCTOR", "ATTENDING_DOCTOR"]
};
// 护理部开放条件：接诊室完成交接且判定住院（决定权在接诊室最后一步，前台口径不影响）
const nursingUnlocked = computed(() => {
  if (!workspace.value) return false;
  const reception = stageSubmission("RECEPTION");
  const disposition = String(reception?.data?.dispositionSuggestion || "").toUpperCase();
  const nursingStatus = stageSubmission("NURSING")?.status;
  return reception?.status === "COMPLETED" && disposition === "INPATIENT" && nursingStatus !== "SKIPPED";
});
const canEditSelectedStage = computed(() => {
  if (!workspace.value || selectedStageCode.value === "REVIEW") return false;
  if (workspace.value.encounter.status === "CANCELLED") return false;
  if (selectedStageCode.value === "NURSING" && !nursingUnlocked.value) return false;
  const submission = selectedStageSubmission.value;
  const roleAllowed = Boolean(authStore.stagePermissions[selectedStageCode.value]?.editable);
  const dutyAllowed = hasAssignedDuty(...(stageDutyCodes[selectedStageCode.value] || []));
  if (!roleAllowed && !dutyAllowed) return false;
  if (submission.status === "PENDING_CONFIRMATION") return false;
  return !["COMPLETED", "SKIPPED"].includes(submission.status);
});
const canPhysicianConfirmSelectedSurgery = computed(
  () =>
    selectedStageCode.value === "SURGERY" &&
    selectedStageSubmission.value?.status === "PENDING_CONFIRMATION" &&
    canConfirmSurgery.value
);
const canCorrectSelectedStage = computed(
  () =>
    workspace.value?.encounter.status !== "CANCELLED" &&
    Boolean(authStore.stagePermissions[selectedStageCode.value]?.correctable) &&
    ["COMPLETED", "SKIPPED"].includes(selectedStageSubmission.value?.status || "") &&
    selectedStageCode.value !== "REVIEW"
);
const canModifySelectedStage = computed(() => canEditSelectedStage.value || canCorrectSelectedStage.value);
const canTerminateReception = computed(
  () =>
    selectedStageCode.value === "RECEPTION" &&
    canEditSelectedStage.value &&
    ["admin", "inspection", "reception", "doctor", "tcm"].includes(currentRole.value)
);
const canReturnSelectedStage = computed(
  () =>
    canReview.value &&
    ["COMPLETED", "SKIPPED"].includes(selectedStageSubmission.value?.status || "") &&
    selectedStageCode.value !== "REVIEW"
);
const upstreamStages = computed(() => {
  if (!workspace.value) return [];
  const index = preAiStages.findIndex(stage => stage.code === selectedStageCode.value);
  return workspace.value.stages.filter(item => {
    const stageIndex = preAiStages.findIndex(stage => stage.code === item.stageCode);
    return stageIndex >= 0 && stageIndex < index && nonEmptyEntries(item.data).length;
  });
});
const upstreamPriorityKeys: Partial<Record<PreAiStageCode, string[]>> = {
  REGISTRATION: [
    "patientName",
    "gender",
    "age",
    "visitDate",
    "visitPurpose",
    "patientSource",
    "registrationChiefComplaint",
    "registrationPastHistory",
    "registrationCurrentIllness"
  ],
  INSPECTION: ["diseaseDirections", "examinationTypes", "preliminaryDiagnosis", "inspectionNarrative"],
  RECEPTION: ["chiefComplaint", "presentIllness", "physicalExam"],
  NURSING: ["allergyHistory", "personalHistory", "chronicDiseaseItems"],
  TCM: ["tcmDisease", "primarySyndrome", "treatmentPrinciple"],
  DOCTOR: ["primaryWesternDiagnosis", "treatmentPath", "treatmentPlan"],
  SURGERY: ["actualOperationName", "operationDate", "intraoperativeFindings"]
};
const upstreamSummaryEntries = (item: PreAiWorkspace["stages"][number]) => {
  const entries = nonEmptyEntries(item.data);
  const entryMap = new Map(entries);
  const priorityEntries = (upstreamPriorityKeys[item.stageCode] || [])
    .filter(key => entryMap.has(key))
    .map(key => [key, entryMap.get(key)] as [string, any]);
  const prioritySet = new Set(priorityEntries.map(([key]) => key));
  return [...priorityEntries, ...entries.filter(([key]) => !prioritySet.has(key))].slice(0, 3);
};
const upstreamStageTime = (item: PreAiWorkspace["stages"][number]) => {
  const value = item.completedAt || item.updatedAt;
  if (!value) return "更新时间未记录";
  return `${item.completedAt ? "完成" : "更新"}于 ${value.replace("T", " ").slice(0, 16)}`;
};
const selectedStageAttachments = computed(
  () => workspace.value?.attachments.filter(item => item.stageCode === selectedStageCode.value && !item.taskId) || []
);
const endoscopyReportDescription = "胃肠镜检查报告单";
const endoscopyReportAttachments = computed(
  () =>
    workspace.value?.attachments.filter(item => !item.taskId && (item.description || "").includes(endoscopyReportDescription)) ||
    []
);
const registrationImageAttachments = computed(
  () =>
    workspace.value?.attachments.filter(
      item =>
        item.stageCode === "REGISTRATION" &&
        !item.taskId &&
        isImageAttachment(item) &&
        !(item.description || "").includes(endoscopyReportDescription)
    ) || []
);
const voidedAttachments = computed(() => workspace.value?.voidedAttachments || []);
const inspectionImageAttachments = computed(
  () => workspace.value?.attachments.filter(item => item.stageCode === "INSPECTION" && isImageAttachment(item)) || []
);
const inspectionPreviewImages = computed(() =>
  inspectionImageAttachments.value.map(attachment => ({
    id: attachment.id,
    fileName: attachment.fileName,
    description: attachment.description,
    capturedAt: attachment.capturedAt || attachment.createdAt,
    uploader: attachment.uploader,
    url: workspaceImageUrls[attachment.id]
  }))
);
const selectedAttachmentGroups = computed(() => groupAttachments(selectedStageAttachments.value, true));
const maskedSections = computed(() => {
  return reviewPreview.value?.documentSections || [];
});
const documentPreviewSections = computed(() =>
  workspace.value
    ? buildDocumentPreviewSections({
        workspace: workspace.value,
        stageForms,
        reviewStatement: reviewStatement.value,
        stageByCode
      })
    : []
);

const deepCopy = <T,>(value: T): T => JSON.parse(JSON.stringify(value ?? {}));
const runWithConcurrency = async <T,>(items: T[], limit: number, worker: (item: T) => Promise<void>) => {
  let cursor = 0;
  const workerCount = Math.min(Math.max(limit, 1), items.length);
  await Promise.all(
    Array.from({ length: workerCount }, async () => {
      while (cursor < items.length) {
        const item = items[cursor++];
        await worker(item);
      }
    })
  );
};
const stageSubmission = (code: PreAiStageCode) => workspace.value?.stages.find(item => item.stageCode === code);
const workflowCardStatus = (card: WorkflowCard): PreAiStageStatus => {
  if (card.kind === "STAGE" && card.stageCode) return stageSubmission(card.stageCode)?.status || "DRAFT";
  return (labTask.value?.status as PreAiStageStatus) || "DRAFT";
};
const workflowCardStatusLabel = (card: WorkflowCard) => {
  if (card.kind === "AUX") {
    if (labTask.value?.status === "COMPLETED") return `${workspace.value?.labReports.length || 0} 份 · 已完成`;
    if (labTask.value?.status === "RETURNED") return "已退回";
    return workspace.value?.labReports.length ? `${workspace.value.labReports.length} 份 · 待交接` : "待填写";
  }
  return stageStatusLabel[workflowCardStatus(card)];
};
const isWorkflowCardActive = (card: WorkflowCard) =>
  workflowSelected.value &&
  ((card.kind === "AUX" && selectedPanel.value === "AUX") ||
    (card.kind === "STAGE" && selectedPanel.value === "STAGE" && selectedStageCode.value === card.stageCode));
const isCurrentWorkflowCard = (card: WorkflowCard) => {
  if (!workspace.value) return false;
  if (card.kind === "STAGE") return workspace.value.encounter.currentStage === card.stageCode;
  return Boolean(labTask.value?.requiredBeforeExport && labTask.value.status !== "COMPLETED");
};
const generatedTemplateText = (field: PreAiFieldConfig, form: Record<string, any>) => {
  switch (field.templateGenerator) {
    case "chiefComplaint":
      return buildChiefComplaintText(form);
    case "presentIllness":
      return buildPresentIllnessText(form);
    case "physicalExam":
      return buildPhysicalExamText(form);
    case "inspectionConclusion":
      return buildInspectionConclusion(form);
    case "syndromeBasis":
      return buildSyndromeBasis(form);
    case "diagnosisBasis":
      return buildDiagnosisBasis(form);
    case "treatmentPlan":
      return buildTreatmentPlan(form);
    case "colonoscopyConclusion":
      return buildColonoscopyConclusion(form);
    default:
      return "";
  }
};
const selectedStageTemplateSourceHash = computed(() => {
  const code = selectedStageCode.value;
  const source = deepCopy(stageForms[code]);
  for (const templateField of stageByCode(code).fields.filter(item => item.kind === "template-text")) {
    for (const key of [
      templateField.key,
      templateField.overrideKey,
      templateField.sourceHashKey,
      templateField.confirmedKey
    ].filter(Boolean) as string[]) {
      delete source[key];
    }
  }
  return stableSourceHash([source]);
});
const templateSourceHash = (field: PreAiFieldConfig) => {
  if (field.kind !== "template-text" || !field.sourceHashKey) return "";
  return selectedStageTemplateSourceHash.value;
};
const markStageDirty = (code: PreAiStageCode) => {
  stageDirty[code] = true;
};
const clearStageDirty = (code: PreAiStageCode) => {
  stageDirty[code] = false;
};
const clearAllStageDirty = () => {
  (Object.keys(stageDirty) as PreAiStageCode[]).forEach(code => clearStageDirty(code));
};
const hasUnsavedStageDrafts = computed(() => Object.values(stageDirty).some(Boolean));
const hasUnsavedDrafts = computed(() => hasUnsavedStageDrafts.value || auxiliaryTasksDirty.value);
const patchStageForm = (code: PreAiStageCode, value: Record<string, any>) => {
  Object.assign(stageForms[code], value);
  markStageDirty(code);
};
const applyQuickTemplate = (fieldKey: string, value: string) => {
  stageForms[selectedStageCode.value][fieldKey] = value;
  markStageDirty(selectedStageCode.value);
};
const isStageFieldDisabled = (field: PreAiFieldConfig) =>
  !canModifySelectedStage.value ||
  (selectedStageCode.value === "SURGERY" && field.key === "physicianConfirmed" && !canConfirmSurgery.value);
const fieldOptions = (field: PreAiFieldConfig) => field.optionsFor?.(stageForms[selectedStageCode.value]) || field.options || [];
const routeLabel = (route?: string) => (route === "OUTPATIENT" ? "门诊" : route === "INPATIENT" ? "住院" : "分支待确认");
const treatmentPathLabel = (path?: string) =>
  path === "CONSERVATIVE" ? "保守治疗" : path === "SURGICAL" ? "手术治疗" : "方案待确认";
const stageStatusClass = (status: PreAiStageStatus) =>
  status === "COMPLETED" ? "done" : status === "RETURNED" ? "returned" : status === "SKIPPED" ? "skipped" : "waiting";
const stageStatusType = (status: PreAiStageStatus) =>
  status === "COMPLETED"
    ? "success"
    : status === "RETURNED" || status === "PENDING_CONFIRMATION"
      ? "warning"
      : status === "SKIPPED"
        ? "info"
        : "info";
const encounterStatusType = (status: PreAiEncounterStatus) =>
  status === "EXPORTED" ? "success" : status === "REVIEWED" ? "success" : status === "PENDING_REVIEW" ? "warning" : "info";
const fieldLabel = (stageCode: PreAiStageCode, key: string) =>
  stageByCode(stageCode).fields.find(field => field.key === key)?.label || key;
const loadEncounterList = async () => {
  try {
    const { data } = await getPreAiPatientCasesApi();
    patientCases.value = data.list;
    encounters.value = data.list.flatMap(item => (item.latestEncounter ? [item.latestEncounter] : []));
    if (selectedPatientCaseId.value && !patientCases.value.some(item => item.id === selectedPatientCaseId.value)) {
      selectedPatientCaseId.value = "";
      selectedEncounterId.value = "";
    }

    const requestedEncounterId = String(route.query.id || route.query.encounterId || "").trim();
    if (requestedEncounterId && requestedEncounterId !== selectedEncounterId.value) {
      await selectEncounter(requestedEncounterId);
    }
    const requestedStage = String(route.query.stage || "").trim() as PreAiStageCode;
    if (requestedEncounterId && ["INSPECTION", "RECEPTION"].includes(requestedStage) && workspace.value) {
      await selectStage(requestedStage);
    }
  } catch (error: any) {
    ElMessage.error(error.message || "患者列表加载失败");
  }
};

let queueUpdateRefreshTimer: number | undefined;
const refreshEncounterListAfterQueueUpdate = () => {
  if (queueUpdateRefreshTimer) clearTimeout(queueUpdateRefreshTimer);
  queueUpdateRefreshTimer = window.setTimeout(() => void loadEncounterList(), 120);
};

const clearWorkspaceImageUrls = () => {
  Object.values(workspaceImageUrls).forEach(url => URL.revokeObjectURL(url));
  Object.keys(workspaceImageUrls).forEach(key => delete workspaceImageUrls[key]);
};

const workspaceInspectionImages = (value: PreAiWorkspace) =>
  value.attachments.filter(item => item.stageCode === "INSPECTION" && isImageAttachment(item));

const workspaceInspectionImageKey = (value: PreAiWorkspace) =>
  workspaceInspectionImages(value)
    .map(item => `${item.id}:${item.downloadUrl}`)
    .sort()
    .join("|");

const resetWorkspaceImageContext = () => {
  workspaceImageAbortController?.abort();
  workspaceImageAbortController = undefined;
  workspaceImageLoadPromise = undefined;
  workspaceImageRequestSequence += 1;
  workspaceImageEncounterId = "";
  workspaceImageAttachmentKey = "";
  clearWorkspaceImageUrls();
};

const syncWorkspaceImageContext = (value: PreAiWorkspace) => {
  const encounterId = value.encounter.id;
  const attachmentKey = workspaceInspectionImageKey(value);
  if (workspaceImageEncounterId === encounterId && workspaceImageAttachmentKey === attachmentKey) return;

  resetWorkspaceImageContext();
  workspaceImageEncounterId = encounterId;
  workspaceImageAttachmentKey = attachmentKey;
};

const loadWorkspaceInspectionImages = async (value: PreAiWorkspace) => {
  syncWorkspaceImageContext(value);
  const images = workspaceInspectionImages(value);
  if (!images.length || images.every(attachment => Boolean(workspaceImageUrls[attachment.id]))) return;
  if (workspaceImageLoadPromise) return workspaceImageLoadPromise;

  const requestSequence = ++workspaceImageRequestSequence;
  const requestController = new AbortController();
  workspaceImageAbortController = requestController;
  const request = runWithConcurrency(images, 4, async attachment => {
    if (workspaceImageUrls[attachment.id]) return;
    try {
      const url = await getPreAiAttachmentObjectUrlApi(attachment, requestController.signal);
      if (requestSequence !== workspaceImageRequestSequence || workspace.value?.encounter.id !== value.encounter.id) {
        URL.revokeObjectURL(url);
        return;
      }
      workspaceImageUrls[attachment.id] = url;
    } catch (error: any) {
      if (error?.name !== "AbortError") {
        // 单张检查图片失败时保留下载入口，不阻断工作区加载。
      }
    }
  });
  workspaceImageLoadPromise = request;
  try {
    await request;
  } finally {
    if (workspaceImageAbortController === requestController) workspaceImageAbortController = undefined;
    if (workspaceImageLoadPromise === request) workspaceImageLoadPromise = undefined;
  }
};

const workspaceTimelineSourceKey = (value: PreAiWorkspace) => {
  const inspectionStage = value.stages.find(stage => stage.stageCode === "INSPECTION");
  const attachments = value.attachments
    .filter(attachment => attachment.stageCode === "INSPECTION")
    .map(attachment => `${attachment.id}:${attachment.downloadUrl}:${attachment.fileSize}`)
    .sort()
    .join("|");
  return [value.encounter.patientCaseId, inspectionStage?.version || 0, inspectionStage?.updatedAt || "", attachments].join("::");
};

const cancelTimelineLoad = () => {
  timelineAbortController?.abort();
  timelineAbortController = undefined;
  timelineLoadPromise = undefined;
  timelineRequestSequence += 1;
  timelineLoading.value = false;
};

const resetTimelineContext = () => {
  cancelTimelineLoad();
  timelinePatientCaseId = "";
  timelineSourceKey = "";
  timelineLoaded = false;
  inspectionTimeline.value = [];
};

const syncTimelineContext = (value: PreAiWorkspace) => {
  const patientCaseId = value.encounter.patientCaseId;
  const sourceKey = workspaceTimelineSourceKey(value);
  if (timelinePatientCaseId === patientCaseId && timelineSourceKey === sourceKey) return;

  resetTimelineContext();
  timelinePatientCaseId = patientCaseId;
  timelineSourceKey = sourceKey;
};

const showCurrentInspection = () => {
  inspectionView.value = "CURRENT";
  if (timelineLoadPromise || timelineAbortController) cancelTimelineLoad();
};

const cancelReviewRequest = () => {
  reviewAbortController?.abort();
  reviewAbortController = undefined;
  reviewRequestInFlightEncounterId = "";
  reviewRequestSequence += 1;
};

const hydrateWorkspace = (value: PreAiWorkspace) => {
  const switchedEncounter = workspace.value?.encounter.id !== value.encounter.id;
  if (switchedEncounter) {
    clearAllStageDirty();
    auxiliaryTasksDirty.value = false;
  }
  const keepInspectionImagesVisible =
    workspace.value?.encounter.id === value.encounter.id &&
    (editorMode.value === "PREVIEW" ||
      (workflowSelected.value && selectedPanel.value === "STAGE" && selectedStageCode.value === "RECEPTION"));
  syncWorkspaceImageContext(value);
  syncTimelineContext(value);
  workspace.value = value;
  hydrationQuiet = true;
  manualTemplateTouched.clear();
  autoMatchedTemplateLabel.value = "";
  value.stages.forEach(stage => {
    const normalized = deepCopy(stage.data);
    if (stage.stageCode === "DOCTOR") {
      if (!normalized.plannedPrimaryOperation && normalized.plannedOperationName) {
        normalized.plannedPrimaryOperation = normalized.plannedOperationName;
      }
      if (!Array.isArray(normalized.secondaryDiagnosisItems) && Array.isArray(normalized.secondaryWesternDiagnoses)) {
        normalized.secondaryDiagnosisItems = normalized.secondaryWesternDiagnoses.map((name: string) => ({
          name,
          category: "LOCAL"
        }));
      }
    }
    if (stage.stageCode === "SURGERY" && !normalized.actualPrimaryOperation && normalized.actualOperationName) {
      normalized.actualPrimaryOperation = normalized.actualOperationName;
    }
    stageByCode(stage.stageCode).fields.forEach(field => {
      const value = normalized[field.key];
      if (field.kind === "multi" && value !== undefined && value !== null && value !== "" && !Array.isArray(value)) {
        normalized[field.key] = [String(value)];
      }
      if ((field.kind === "multi" || field.kind === "repeatable") && !Array.isArray(normalized[field.key])) {
        normalized[field.key] = [];
      }
      if (field.kind === "measurement") {
        if (value === undefined || value === null || value === "") {
          normalized[field.key] = { value: "", unit: field.unitOptions?.[0] || "", status: "" };
        } else if (typeof value !== "object" || Array.isArray(value)) {
          normalized[field.key] = { value, unit: field.unitOptions?.[0] || "", status: "" };
        }
      }
      if (field.kind === "template-text" && field.overrideKey && normalized[field.overrideKey] !== undefined) {
        normalized[field.key] = normalized[field.overrideKey];
      }
    });
    if (!stageDirty[stage.stageCode]) {
      Object.keys(stageForms[stage.stageCode]).forEach(key => delete stageForms[stage.stageCode][key]);
      Object.assign(stageForms[stage.stageCode], normalized);
    }
  });
  if (!stageDirty.REGISTRATION) {
    // 病史采集字段数据归属接诊室，前台仅作集中填写入口；两侧表单值保持一致。
    const registration = stageForms.REGISTRATION;
    const reception = stageForms.RECEPTION;
    stageByCode("REGISTRATION").fields.forEach(field => {
      if (!isHistoryIntakeKey(field.key)) return;
      const source = reception[field.key];
      if (Array.isArray(source) ? source.length : String(source ?? "").trim()) {
        registration[field.key] = Array.isArray(source) ? [...source] : source;
      }
    });
  }
  if (!stageDirty.RECEPTION) {
    const registration = stageForms.REGISTRATION;
    const reception = stageForms.RECEPTION;
    stageByCode("REGISTRATION").fields.forEach(field => {
      if (!isHistoryIntakeKey(field.key)) return;
      const source = reception[field.key];
      const fromRegistration = registration[field.key];
      const receptionEmpty = Array.isArray(source) ? !source.length : !String(source ?? "").trim();
      const registrationHas = Array.isArray(fromRegistration)
        ? fromRegistration.length > 0
        : Boolean(String(fromRegistration ?? "").trim());
      if (receptionEmpty && registrationHas) {
        reception[field.key] = Array.isArray(fromRegistration) ? [...fromRegistration] : fromRegistration;
      }
    });
    if (
      Array.isArray(registration.registrationSymptoms) &&
      registration.registrationSymptoms.length &&
      !reception.chiefComplaint?.length
    ) {
      reception.chiefComplaint = [...registration.registrationSymptoms];
    }
    const registrationComplaint = String(registration.registrationChiefComplaint || "").trim();
    const registrationIllness = String(registration.registrationCurrentIllness || "").trim();
    if (!String(reception.chiefComplaintSupplement || "").trim() && registrationComplaint) {
      reception.chiefComplaintSupplement = `前台登记主诉：${registrationComplaint}`;
    }
    if (!String(reception.presentIllnessOverride || reception.presentIllness || "").trim() && registrationIllness) {
      reception.presentIllness = registrationIllness;
      reception.presentIllnessOverride = registrationIllness;
      reception.presentIllnessConfirmed = false;
    }
    if (!Array.isArray(reception.clinicalTemplateIds) || !reception.clinicalTemplateIds.length) {
      reception.clinicalTemplateIds = clinicalTemplateIdsForDiseases(
        stageForms.INSPECTION.diseaseDirections?.length
          ? stageForms.INSPECTION.diseaseDirections
          : registration.clinicalTemplateDiseases
      );
    }
  }
  if (!stageDirty.NURSING) {
    // 护理部：过敏史/个人史同步自前台登记（数据归属接诊室草稿），其余病史由护理部自行采集
    const nursing = stageForms.NURSING;
    const reception = stageForms.RECEPTION;
    ["allergyHistory", "allergyHistoryNote", "personalHistory"].forEach(key => {
      const source = reception[key];
      if (Array.isArray(source) ? source.length : String(source ?? "").trim()) {
        nursing[key] = Array.isArray(source) ? [...source] : source;
      }
    });
  }
  if (!value.labReports.some(report => report.id === activeLabReportId.value)) {
    activeLabReportId.value = value.labReports[0]?.id || "";
  }
  if (keepInspectionImagesVisible) void loadWorkspaceInspectionImages(value);
  void nextTick(() => {
    hydrationQuiet = false;
  });
};

const resetHistoricalComparison = () => {
  historyAbortController?.abort();
  historyAbortController = undefined;
  historyRequestSequence += 1;
  historyLoading.value = false;
  encounterHistory.value = [];
  historicalWorkspace.value = undefined;
  historicalEncounterId.value = "";
  historyPanelOpen.value = false;
};

const loadHistoricalWorkspace = async (encounterId: string) => {
  const currentWorkspace = workspace.value;
  if (!currentWorkspace || !encounterId || encounterId === currentWorkspace.encounter.id) return;
  const patientCaseId = currentWorkspace.encounter.patientCaseId;
  const requestSequence = ++historyRequestSequence;
  historyAbortController?.abort();
  const requestController = new AbortController();
  historyAbortController = requestController;
  historicalEncounterId.value = encounterId;
  historyLoading.value = true;
  try {
    const { data } = await getPreAiReadOnlyWorkspaceApi(encounterId, patientCaseId, requestController.signal);
    if (
      requestSequence !== historyRequestSequence ||
      workspace.value?.encounter.patientCaseId !== patientCaseId ||
      historicalEncounterId.value !== encounterId
    )
      return;
    historicalWorkspace.value = data;
  } catch (error: any) {
    if (error?.name !== "AbortError" && requestSequence === historyRequestSequence) {
      historicalWorkspace.value = undefined;
      ElMessage.error(error.message || "历史病历加载失败");
    }
  } finally {
    if (requestSequence === historyRequestSequence) {
      historyLoading.value = false;
      if (historyAbortController === requestController) historyAbortController = undefined;
    }
  }
};

const loadEncounterHistory = async (patientCaseId: string, currentEncounterId: string, autoOpen = false) => {
  const requestSequence = ++historyRequestSequence;
  historyAbortController?.abort();
  const requestController = new AbortController();
  historyAbortController = requestController;
  historyLoading.value = true;
  try {
    const { data } = await getPreAiEncounterHistoryApi(patientCaseId, requestController.signal);
    if (
      requestSequence !== historyRequestSequence ||
      workspace.value?.encounter.patientCaseId !== patientCaseId ||
      workspace.value?.encounter.id !== currentEncounterId
    )
      return;
    encounterHistory.value = data.encounters;
    const current = data.encounters.find(item => item.id === currentEncounterId);
    const otherEncounters = data.encounters.filter(item => item.id !== currentEncounterId);
    const earlierEncounters = otherEncounters.filter(item => item.visitNo < (current?.visitNo || 1));
    const preferred =
      earlierEncounters.find(item => item.id === current?.previousEncounterId) || earlierEncounters[0] || otherEncounters[0];
    if (!preferred) {
      historicalEncounterId.value = "";
      historicalWorkspace.value = undefined;
      historyPanelOpen.value = false;
      return;
    }
    if (autoOpen) historyPanelOpen.value = true;
    if (autoOpen || historyPanelOpen.value) await loadHistoricalWorkspace(preferred.id);
  } catch (error: any) {
    if (error?.name !== "AbortError" && requestSequence === historyRequestSequence) {
      ElMessage.error(error.message || "历次病历列表加载失败");
    }
  } finally {
    if (requestSequence === historyRequestSequence) {
      historyLoading.value = false;
      if (historyAbortController === requestController) historyAbortController = undefined;
    }
  }
};

const openHistoricalComparison = async () => {
  if (!workspace.value) return;
  historyPanelOpen.value = true;
  const current = workspace.value.encounter;
  const existing = encounterHistory.value.find(item => item.id === current.id);
  if (!existing) {
    await loadEncounterHistory(current.patientCaseId, current.id, true);
    return;
  }
  const otherEncounters = encounterHistory.value.filter(item => item.id !== current.id);
  const earlierEncounters = otherEncounters.filter(item => item.visitNo < current.visitNo);
  const preferred =
    earlierEncounters.find(item => item.id === current.followUpOfEncounterId) || earlierEncounters[0] || otherEncounters[0];
  if (preferred) await loadHistoricalWorkspace(preferred.id);
};

const selectEncounter = async (id: string, preserveView = false) => {
  if (!preserveView && id === selectedEncounterId.value && workspace.value?.encounter.id === id && !workspaceLoading.value)
    return;
  if (id !== selectedEncounterId.value && hasUnsavedDrafts.value) {
    try {
      await ElMessageBox.confirm("当前病例有未保存的填写内容，切换后将不再保留。", "确认切换病例", {
        type: "warning",
        confirmButtonText: "放弃并切换",
        cancelButtonText: "继续填写"
      });
    } catch {
      return;
    }
  }

  const requestSequence = ++workspaceRequestSequence;
  workspaceAbortController?.abort();
  if (workspace.value?.encounter.id !== id) {
    resetWorkspaceImageContext();
    resetTimelineContext();
    cancelReviewRequest();
    resetHistoricalComparison();
    targetVersionsRequestSequence += 1;
    targetVersionsLoading.value = false;
    targetMedicalRecordVersions.value = [];
    latestGeneratedTargetVersionId.value = "";
    latestGeneratedExportVersionId.value = "";
  }
  const requestController = new AbortController();
  workspaceAbortController = requestController;
  pendingWorkflowSelection.value = undefined;
  selectedEncounterId.value = id;
  workspaceLoading.value = true;
  let workspaceLoaded = false;
  try {
    const { data } = await getPreAiWorkspaceApi(id, requestController.signal);
    if (requestSequence !== workspaceRequestSequence || selectedEncounterId.value !== id) return;

    hydrateWorkspace(data);
    selectedPatientCaseId.value = data.encounter.patientCaseId;
    if (!preserveView || encounterHistory.value.every(item => item.patientCaseId !== data.encounter.patientCaseId)) {
      await loadEncounterHistory(data.encounter.patientCaseId, data.encounter.id, false);
    }
    workspaceLoaded = true;
    if (!preserveView) {
      const pendingSelection = readPendingWorkflowSelection();
      if (pendingSelection?.encounterId === id) {
        activateWorkflowCard(pendingSelection.card);
      } else {
        const currentStage = (data.encounter.currentStage || "REGISTRATION") as PreAiStageCode;
        const currentCard = workflowCards.value.find(card => card.kind === "STAGE" && card.stageCode === currentStage);
        if (currentCard) {
          activateWorkflowCard(currentCard);
        } else {
          selectedStageCode.value = currentStage;
          selectedPanel.value = "STAGE";
          workflowSelected.value = true;
        }
      }
      editorMode.value = "EDIT";
      reviewPreview.value = undefined;
    }
  } catch (error: any) {
    if (error?.name !== "AbortError" && requestSequence === workspaceRequestSequence) {
      ElMessage.error(error.message || "前置病历加载失败");
    }
  } finally {
    if (requestSequence === workspaceRequestSequence) {
      workspaceLoading.value = false;
      if (workspaceAbortController === requestController) workspaceAbortController = undefined;
      const pendingSelection = readPendingWorkflowSelection();
      if (pendingSelection?.encounterId === id) {
        pendingWorkflowSelection.value = undefined;
        if (workspaceLoaded && pendingSelection.card.kind === "STAGE" && pendingSelection.card.stageCode === "REVIEW") {
          await loadReviewPreview();
        }
      }
    }
  }
};

const refreshWorkspace = async () => {
  await loadEncounterList();
  if (selectedEncounterId.value) await selectEncounter(selectedEncounterId.value, true);
};

const selectPatientCase = async (patientCase: PreAiPatientCase) => {
  if (!patientCase.latestEncounter) {
    selectedPatientCaseId.value = patientCase.id;
    patientDrawerOpen.value = false;
    return;
  }
  await selectEncounter(patientCase.latestEncounter.id);
  if (selectedEncounterId.value !== patientCase.latestEncounter.id) return;
  selectedPatientCaseId.value = patientCase.id;
  patientDrawerOpen.value = false;
};

const activateWorkflowCard = (card: WorkflowCard) => {
  if (card.kind !== "STAGE" || card.stageCode !== "REVIEW") cancelReviewRequest();
  showCurrentInspection();
  if (card.kind === "AUX") {
    selectedPanel.value = "AUX";
  } else if (card.stageCode) {
    selectedStageCode.value = card.stageCode;
    selectedPanel.value = "STAGE";
  }
  workflowSelected.value = true;
  editorMode.value = "EDIT";
  if (card.kind === "STAGE" && card.stageCode === "RECEPTION" && workspace.value) {
    void loadWorkspaceInspectionImages(workspace.value);
  }
};

const openPreviewMode = () => {
  if (editorMode.value === "PREVIEW") return;
  editorMode.value = "PREVIEW";
  if (workspace.value) void loadWorkspaceInspectionImages(workspace.value);
};

const selectStage = async (code: PreAiStageCode) => {
  const wasActive = workflowSelected.value && selectedPanel.value === "STAGE" && selectedStageCode.value === code;
  if (code !== "REVIEW") cancelReviewRequest();
  selectedStageCode.value = code;
  showCurrentInspection();
  selectedPanel.value = "STAGE";
  workflowSelected.value = true;
  editorMode.value = "EDIT";
  if (code === "RECEPTION" && workspace.value) void loadWorkspaceInspectionImages(workspace.value);
  if (code === "REVIEW" && (!wasActive || !reviewPreview.value)) await loadReviewPreview();
};

const showInspectionTimeline = async () => {
  inspectionView.value = "HISTORY";
  const currentWorkspace = workspace.value;
  if (!currentWorkspace) return;

  syncTimelineContext(currentWorkspace);
  if (timelineLoaded) return;
  if (timelineLoadPromise) return timelineLoadPromise;

  const patientCaseId = currentWorkspace.encounter.patientCaseId;
  const sourceKey = timelineSourceKey;
  const requestSequence = ++timelineRequestSequence;
  const requestController = new AbortController();
  timelineAbortController = requestController;
  timelineLoading.value = true;

  let request!: Promise<void>;
  const isCurrentRequest = () =>
    timelineLoadPromise === request &&
    timelineAbortController === requestController &&
    requestSequence === timelineRequestSequence &&
    timelinePatientCaseId === patientCaseId &&
    timelineSourceKey === sourceKey &&
    workspace.value?.encounter.patientCaseId === patientCaseId;

  request = (async () => {
    try {
      const { data } = await getPreAiInspectionTimelineApi(patientCaseId, requestController.signal);
      if (!isCurrentRequest()) return;

      inspectionTimeline.value = data.nodes;
      if (isCurrentRequest()) timelineLoaded = true;
    } catch (error: any) {
      if (error?.name !== "AbortError" && isCurrentRequest()) {
        ElMessage.error(error.message || "检查室历史时间轴加载失败");
      }
    } finally {
      if (timelineAbortController === requestController) timelineAbortController = undefined;
      if (timelineLoadPromise === request) timelineLoadPromise = undefined;
      if (requestSequence === timelineRequestSequence) timelineLoading.value = false;
    }
  })();
  timelineLoadPromise = request;
  return request;
};

const timelineAttachmentGroups = (attachments: PreAiAttachment[]) => groupAttachments(attachments);

const paymentStatusLabel = (status?: string) =>
  ({ UNPAID: "未交", PARTIAL: "部分缴费", PAID: "已交", REFUNDED: "退费" })[status || ""] || "未记录";
const hasVisitMeta = (visitMeta: Record<string, any> = {}) => nonEmptyEntries(visitMeta).length > 0;

const persistHistoryPaneRatio = () => {
  try {
    globalThis.localStorage?.setItem(HISTORY_PANE_RATIO_KEY, historyPaneRatio.value.toFixed(4));
  } catch {
    // 浏览器禁止本地存储时仍保持当前会话内的拖动比例。
  }
};

const setHistoryPaneWidth = (width: number) => {
  const bounded = Math.min(historyMaxWidth.value, Math.max(historyMinWidth, width));
  historyPaneRatio.value = bounded / historyAvailableWidth.value;
  persistHistoryPaneRatio();
};

const resetHistoryPaneRatio = () => {
  historyPaneRatio.value = DEFAULT_HISTORY_PANE_RATIO;
  persistHistoryPaneRatio();
};

const startHistoryResize = (event: PointerEvent) => {
  if (globalThis.innerWidth < 1100 || !workspaceShellRef.value) return;
  event.preventDefault();
  stopHistoryPointerResize?.();
  const shellRect = workspaceShellRef.value.getBoundingClientRect();
  const onMove = (moveEvent: PointerEvent) => setHistoryPaneWidth(shellRect.right - moveEvent.clientX);
  const onStop = () => {
    globalThis.removeEventListener("pointermove", onMove);
    globalThis.removeEventListener("pointerup", onStop);
    globalThis.removeEventListener("pointercancel", onStop);
    document.body.classList.remove("history-pane-resizing");
    stopHistoryPointerResize = undefined;
  };
  document.body.classList.add("history-pane-resizing");
  globalThis.addEventListener("pointermove", onMove);
  globalThis.addEventListener("pointerup", onStop, { once: true });
  globalThis.addEventListener("pointercancel", onStop, { once: true });
  stopHistoryPointerResize = onStop;
};

const adjustHistoryPaneByKeyboard = (event: KeyboardEvent) => {
  if (!["ArrowLeft", "ArrowRight", "Home"].includes(event.key)) return;
  event.preventDefault();
  if (event.key === "Home") {
    resetHistoryPaneRatio();
    return;
  }
  const step = event.shiftKey ? 64 : 24;
  setHistoryPaneWidth(historyPaneWidth.value + (event.key === "ArrowLeft" ? step : -step));
};

const openFollowUpDialog = (patientCase: PreAiPatientCase) => {
  followUpPatientCase.value = patientCase;
  followUpRequestId.value = createClientRequestId();
  Object.keys(followUpForm).forEach(key => delete followUpForm[key]);
  followUpForm.visitDate = currentLocalDateTime();
  followUpDialogVisible.value = true;
};

const createFollowUp = async () => {
  if (!followUpPatientCase.value || !followUpForm.visitDate) {
    ElMessage.warning("请选择本次来访时间");
    return;
  }
  await runAction(async () => {
    const { visitDate, ...visitMeta } = deepCopy(followUpForm);
    followUpRequestId.value ||= createClientRequestId();
    const { data } = await registerAndIssuePreAiFollowUpApi(followUpPatientCase.value!.id, {
      visitDate,
      visitMeta,
      clientRequestId: followUpRequestId.value
    });
    const encounterWorkspace = data.encounterWorkspace;
    const queueWorkspace = data.queueWorkspace;
    followUpDialogVisible.value = false;
    followUpRequestId.value = "";
    await loadEncounterList();
    selectedPatientCaseId.value = encounterWorkspace.encounter.patientCaseId;
    selectedEncounterId.value = encounterWorkspace.encounter.id;
    hydrateWorkspace(encounterWorkspace);
    await loadEncounterHistory(encounterWorkspace.encounter.patientCaseId, encounterWorkspace.encounter.id, true);
    selectedStageCode.value = "INSPECTION";
    workflowSelected.value = true;
    editorMode.value = "EDIT";
    const printed = queueWorkspace.newlyIssued === false ? false : await printIssuedQueueTicket(queueWorkspace.ticket.id);
    handoffNotice.value = `${queueWorkspace.ticket.publicNo} 复诊已登记并发号，当前进入检查候诊；${
      printed ? "票据已打印。" : "打印不可用或未成功，请到叫号工作台补打。"
    }`;
    ElMessage.success(handoffNotice.value);
  });
};

const selectWorkflowCard = async (card: WorkflowCard) => {
  const alreadyActive = isWorkflowCardActive(card);
  if (
    alreadyActive &&
    !workspaceLoading.value &&
    editorMode.value === "EDIT" &&
    (card.kind !== "STAGE" || card.stageCode !== "INSPECTION" || inspectionView.value === "CURRENT") &&
    (card.kind !== "STAGE" || card.stageCode !== "REVIEW" || Boolean(reviewPreview.value))
  ) {
    return;
  }
  activateWorkflowCard(card);
  if (workspaceLoading.value) {
    pendingWorkflowSelection.value = {
      encounterId: selectedEncounterId.value,
      card: { ...card }
    };
    return;
  }
  pendingWorkflowSelection.value = undefined;
  if (card.kind === "STAGE" && card.stageCode === "REVIEW") await selectStage(card.stageCode);
};

const syncRegistrationHistoryToReception = (fromStage: PreAiStageCode) => {
  const registration = stageForms[fromStage];
  const reception = stageForms.RECEPTION;
  stageByCode(fromStage).fields.forEach(field => {
    if (!isHistoryIntakeKey(field.key)) return;
    const value = registration[field.key];
    if (Array.isArray(value) ? value.length : String(value ?? "").trim()) {
      reception[field.key] = Array.isArray(value) ? [...value] : value;
    }
  });
};

const persistReceptionHistoryFromStage = async (fromStage: PreAiStageCode) => {
  // 病史采集数据归属接诊室（病历生成从接诊室读取），前台保存时联动落一份接诊室草稿。
  syncRegistrationHistoryToReception(fromStage);
  const receptionStatus = stageSubmission("RECEPTION")?.status;
  if (receptionStatus === "COMPLETED") {
    ElMessage.info("接诊室已完成交接，本次病史修改暂存于前台登记，接诊室纠错保存后才会更新到病历");
    return;
  }
  const hasHistoryValue = stageByCode(fromStage).fields.some(field => {
    if (!isHistoryIntakeKey(field.key)) return false;
    const value = stageForms.RECEPTION[field.key];
    return Array.isArray(value) ? value.length > 0 : Boolean(String(value ?? "").trim());
  });
  if (!hasHistoryValue) return;
  try {
    const { data } = await savePreAiStageApi(
      selectedEncounterId.value,
      "RECEPTION",
      cleanStageForm("RECEPTION"),
      stageSubmission("RECEPTION")?.version ?? 0
    );
    hydrateWorkspace(data);
  } catch (error: any) {
    ElMessage.warning("病史采集数据同步至接诊室草稿失败，请稍后重试保存");
    throw error;
  }
};

const persistNursingHistoryFromStage = async () => {
  // 护理部：病史采集（含同步修订的过敏史/个人史）落接诊室草稿；过敏史/个人史回写前台登记草稿
  const nursingKeys = ["allergyHistory", "allergyHistoryNote", "personalHistory"];
  const nursing = stageForms.NURSING;
  const registration = stageForms.REGISTRATION;
  nursingKeys.forEach(key => {
    const value = nursing[key];
    registration[key] = Array.isArray(value) ? [...value] : value;
  });
  await persistReceptionHistoryFromStage("NURSING");
  const registrationStatus = stageSubmission("REGISTRATION")?.status;
  if (stageDirty.REGISTRATION || registrationStatus === "COMPLETED") return;
  const registrationChanged = nursingKeys.some(key => {
    const value = nursing[key];
    const hasValue = Array.isArray(value) ? value.length > 0 : Boolean(String(value ?? "").trim());
    return hasValue;
  });
  if (!registrationChanged) return;
  try {
    const { data } = await savePreAiStageApi(
      selectedEncounterId.value,
      "REGISTRATION",
      cleanStageForm("REGISTRATION"),
      stageSubmission("REGISTRATION")?.version ?? 0
    );
    hydrateWorkspace(data);
  } catch (error: any) {
    ElMessage.warning("过敏史/个人史同步回前台登记失败，请稍后重试保存");
    throw error;
  }
};

const saveSelectedStage = async () =>
  runAction(async () => {
    if (["REGISTRATION", "INSPECTION"].includes(selectedStageCode.value))
      await persistReceptionHistoryFromStage(selectedStageCode.value);
    if (selectedStageCode.value === "NURSING") await persistNursingHistoryFromStage();
    const { data } = await savePreAiStageApi(
      selectedEncounterId.value,
      selectedStageCode.value,
      cleanStageForm(selectedStageCode.value),
      stageSubmission(selectedStageCode.value)?.version ?? 0
    );
    clearStageDirty(selectedStageCode.value);
    hydrateWorkspace(data);
    ElMessage.success("阶段草稿已保存");
  });

const correctSelectedStage = async () => {
  try {
    const { value } = await ElMessageBox.prompt(
      "请说明本次纠错原因；保存后原终审与既有导出将失效，需要重新复核。",
      `纠错${selectedStage.value.title}`,
      {
        inputType: "textarea",
        inputPattern: /\S+/,
        inputErrorMessage: "纠错原因不能为空",
        confirmButtonText: "确认纠错",
        cancelButtonText: "取消"
      }
    );
    await runAction(async () => {
      if (["REGISTRATION", "INSPECTION"].includes(selectedStageCode.value))
        await persistReceptionHistoryFromStage(selectedStageCode.value);
      if (selectedStageCode.value === "NURSING") await persistNursingHistoryFromStage();
      const { data } = await correctPreAiStageApi(
        selectedEncounterId.value,
        selectedStageCode.value,
        cleanStageForm(selectedStageCode.value),
        stageSubmission(selectedStageCode.value)?.version ?? 0,
        value.trim()
      );
      clearStageDirty(selectedStageCode.value);
      hydrateWorkspace(data);
      await loadEncounterList();
      ElMessage.success("纠错已保存，病例已转为待重新复核");
    });
  } catch (error: any) {
    if (error !== "cancel" && error !== "close") ElMessage.error(error.message || "阶段纠错失败");
  }
};

const completeSelectedStage = async () =>
  runAction(async () => {
    if (["REGISTRATION", "INSPECTION"].includes(selectedStageCode.value))
      await persistReceptionHistoryFromStage(selectedStageCode.value);
    if (selectedStageCode.value === "NURSING") await persistNursingHistoryFromStage();
    const { data } = await completePreAiStageApi(
      selectedEncounterId.value,
      selectedStageCode.value,
      cleanStageForm(selectedStageCode.value),
      stageSubmission(selectedStageCode.value)?.version ?? 0
    );
    clearStageDirty(selectedStageCode.value);
    hydrateWorkspace(data);
    await loadEncounterList();
    if (data.queueHandoff?.nextStage === "RECEPTION") {
      handoffNotice.value = `检查已完成，${data.queueHandoff.publicNo} 已转入接诊候诊，号码保持不变。`;
      ElMessage.success(handoffNotice.value);
    } else if (data.queueHandoff?.fromStage === "RECEPTION") {
      handoffNotice.value = `${data.queueHandoff.publicNo} 接诊已完成，排队票据已自动关闭。`;
      ElMessage.success(handoffNotice.value);
    } else {
      ElMessage.success("本阶段已完成并交接");
    }
  });

const terminateReception = async () => {
  try {
    const { value } = await ElMessageBox.prompt(
      "将保留当前已填写的接诊内容，立即关闭排队与后续流转；此操作仅适用于患者明确离院且不继续治疗。",
      "患者离院（不治疗）",
      {
        inputType: "textarea",
        inputPlaceholder: "请填写离院或不治疗原因",
        inputPattern: /\S+/,
        inputErrorMessage: "离院原因不能为空",
        confirmButtonText: "确认离院并终止流程",
        cancelButtonText: "继续接诊"
      }
    );
    await runAction(async () => {
      const { data } = await terminatePreAiReceptionApi(
        selectedEncounterId.value,
        cleanStageForm("RECEPTION"),
        stageSubmission("RECEPTION")?.version ?? 0,
        value.trim()
      );
      hydrateWorkspace(data);
      await loadEncounterList();
      ElMessage.success("患者已离院，后续流程已终止；本次接诊记录已保留供回查");
    });
  } catch (error: any) {
    if (error !== "cancel" && error !== "close") ElMessage.error(error.message || "办理离院失败");
  }
};

const confirmSelectedSurgery = async () =>
  runAction(async () => {
    const { data } = await confirmPreAiSurgeryApi(selectedEncounterId.value, stageSubmission("SURGERY")?.version ?? 0);
    hydrateWorkspace(data);
    await loadEncounterList();
    ElMessage.success("手术事实已由医生确认并完成交接");
  });

const hasFormValue = (value: any, field?: PreAiFieldConfig) => {
  if (value === undefined || value === null || value === "") return false;
  if (Array.isArray(value)) return value.length > 0;
  if (field?.kind === "measurement" && typeof value === "object") {
    return value.value !== undefined && value.value !== null && value.value !== "";
  }
  return true;
};
const cleanStageForm = (code: PreAiStageCode) => {
  const fields = stageByCode(code).fields;
  const result: Record<string, any> = {};
  fields.forEach(field => {
    if (field.visible && !field.visible(stageForms[code])) return;
    const value = stageForms[code][field.key];
    if (hasFormValue(value, field)) result[field.key] = value;
    for (const key of [field.overrideKey, field.sourceHashKey, field.confirmedKey].filter(Boolean) as string[]) {
      const metadataValue = stageForms[code][key];
      if (metadataValue !== undefined && metadataValue !== null && metadataValue !== "") result[key] = metadataValue;
    }
  });
  if (code === "RECEPTION") {
    // 过敏史等病史采集入口已前移至前台，字段不再出现在接诊室表单定义中；
    // 保存时必须把内存中的既有值随载荷提交，否则整体替换会丢失数据。
    for (const key of Object.keys(stageForms.RECEPTION)) {
      if (!isHistoryIntakeKey(key) || key in result) continue;
      const value = stageForms.RECEPTION[key];
      if (hasFormValue(value)) result[key] = value;
    }
  }
  if (code === "INSPECTION") {
    // 检查记录全文是收束视图的唯一编辑入口，由模板区 textarea 承载、不在字段定义中；
    // 必须随载荷提交，后端才能把正文同步为检查事实结论（否则完成交接会报"请先补齐：检查事实结论"）。
    const narrative = stageForms.INSPECTION.inspectionNarrative;
    if (hasFormValue(narrative)) result.inspectionNarrative = narrative;
  }
  if (code === "NURSING") {
    // 四测轮次记录为数组，由强调区时间轴维护，不经过字段定义收集
    const rounds = stageForms.NURSING.vitalSignRounds;
    if (Array.isArray(rounds) && rounds.length) result.vitalSignRounds = rounds;
  }
  for (const key of ["clinicalTemplateIds", "clinicalTemplateDiseases", "clinicalTemplateVersion", "clinicalTemplateAppliedAt"]) {
    const metadataValue = stageForms[code][key];
    if (hasFormValue(metadataValue)) result[key] = metadataValue;
  }
  return result;
};

const saveDutyAssignments = async (assignments: PreAiDutyAssignment[]) =>
  runAction(async () => {
    const { data } = await savePreAiDutyAssignmentsApi(selectedEncounterId.value, assignments);
    hydrateWorkspace(data);
    ElMessage.success("本病例岗位安排已保存");
  });

const returnStage = async (code: PreAiStageCode) => {
  try {
    const { value } = await ElMessageBox.prompt("请填写退回原因", `退回${stageByCode(code).title}`, {
      inputPattern: /\S+/,
      inputErrorMessage: "退回原因不能为空"
    });
    await runAction(async () => {
      const { data } = await returnPreAiStageApi(selectedEncounterId.value, code, value, stageSubmission(code)?.version ?? 0);
      hydrateWorkspace(data);
      await loadEncounterList();
      ElMessage.success("阶段已退回");
    });
  } catch (error: any) {
    if (error !== "cancel" && error !== "close") ElMessage.error(error.message || "退回失败");
  }
};

const createEncounter = async () =>
  runAction(async () => {
    createRequestId.value ||= createClientRequestId();
    const { data } = await registerAndIssuePreAiEncounterApi(deepCopy(createForm), createRequestId.value);
    const encounterWorkspace = data.encounterWorkspace;
    const queueWorkspace = data.queueWorkspace;
    createDialogVisible.value = false;
    Object.keys(createForm).forEach(key => delete createForm[key]);
    createForm.visitDate = currentLocalDateTime();
    createForm.inventoryCareType = "outpatient";
    createTemplateIds.value = [];
    manualCreateTemplateTouched.value = false;
    autoMatchedCreateLabel.value = "";
    createRequestId.value = "";
    await loadEncounterList();
    selectedPatientCaseId.value = encounterWorkspace.encounter.patientCaseId;
    selectedEncounterId.value = encounterWorkspace.encounter.id;
    hydrateWorkspace(encounterWorkspace);
    selectedStageCode.value = "INSPECTION";
    workflowSelected.value = true;
    editorMode.value = "EDIT";
    const printed = queueWorkspace.newlyIssued === false ? false : await printIssuedQueueTicket(queueWorkspace.ticket.id);
    handoffNotice.value = `${queueWorkspace.ticket.publicNo} 已发号，当前进入检查候诊；${
      printed ? "票据已打印。" : "打印不可用或未成功，请到叫号工作台补打。"
    }`;
    ElMessage.success(handoffNotice.value);
  });

const printIssuedQueueTicket = async (ticketId: string) => {
  try {
    const agent = await getLocalPrintAgentStatus();
    if (agent.status !== "ok" || !agent.terminalId) return false;
    const { data: task } = await createQueuePrintTaskApi(ticketId, agent.terminalId);
    try {
      const result = await printQueueTicketLocally(task.payload);
      await completeQueuePrintTaskApi(task.id, { ...result, executionToken: task.executionToken });
      return true;
    } catch (error: any) {
      await completeQueuePrintTaskApi(task.id, {
        status: "FAILED",
        printerName: agent.printerName,
        errorMessage: error?.message || "打印失败",
        executionToken: task.executionToken
      });
      return false;
    }
  } catch {
    return false;
  }
};

const openLegacyDialog = async () => {
  legacyDialogVisible.value = true;
  try {
    const { data } = await getPatientListApi({ pageNum: 1, pageSize: 200 });
    legacyPatients.value = data.list.filter(item => !["已归档", "旧资料已归档"].includes(item.status));
  } catch (error: any) {
    ElMessage.error(error.message || "旧患者列表加载失败");
  }
};

const importLegacyPatient = async () =>
  runAction(async () => {
    const { data } = await importLegacyPreAiEncounterApi(selectedLegacyPatientId.value);
    legacyDialogVisible.value = false;
    selectedLegacyPatientId.value = "";
    await loadEncounterList();
    selectedPatientCaseId.value = data.encounter.patientCaseId;
    selectedEncounterId.value = data.encounter.id;
    hydrateWorkspace(data);
    selectedStageCode.value = data.encounter.currentStage;
    workflowSelected.value = false;
    editorMode.value = "EDIT";
    ElMessage.success("旧资料已导入，请各岗位逐项核实");
  });

const openLabWorkbench = () => {
  const query: Record<string, string> = {
    encounterId: selectedEncounterId.value
  };
  if (workspace.value?.encounter.sourcePatientId) query.patientId = workspace.value.encounter.sourcePatientId;
  router.push({ path: "/workbench/lab-report", query });
};

const completeLab = async () =>
  runAction(async () => {
    const { data } = await completePreAiLabApi(selectedEncounterId.value, labTask.value?.version ?? 0);
    hydrateWorkspace(data);
    await loadEncounterList();
    ElMessage.success("化验检验报告已完成并交接");
  });

const returnAuxTask = async (taskId: string) => {
  try {
    const { value } = await ElMessageBox.prompt("请填写退回原因", "退回辅助任务", {
      inputPattern: /\S+/,
      inputErrorMessage: "退回原因不能为空"
    });
    await runAction(async () => {
      const version = workspace.value?.auxiliaryTasks.find(task => task.id === taskId)?.version ?? 0;
      const { data } = await returnPreAiAuxiliaryTaskApi(selectedEncounterId.value, taskId, value, version);
      hydrateWorkspace(data);
      await loadEncounterList();
      ElMessage.success("辅助任务已退回");
    });
  } catch (error: any) {
    if (error !== "cancel" && error !== "close") ElMessage.error(error.message || "退回失败");
  }
};

const uploadAttachments = async (
  event: Event,
  stageCode?: PreAiStageCode,
  taskId?: string,
  folderMode = false,
  customBatchName = "",
  customDescription = ""
) => {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  if (!files.length) return;
  const encounterId = selectedEncounterId.value;
  const timestamp = Date.now();
  const folderName = files[0]?.webkitRelativePath?.split("/")[0] || "";
  const batchId = `pre-att-${timestamp}`;
  const batchName =
    customBatchName ||
    (folderMode ? folderName || `检查室文件夹-${timestamp}` : files.length > 1 ? `批量附件-${timestamp}` : files[0].name);
  Object.assign(attachmentUpload, {
    total: files.length,
    success: 0,
    failed: 0,
    percent: 0
  });
  actionLoading.value = true;
  for (const [index, file] of files.entries()) {
    const allowed = file.type.startsWith("image/") || file.type === "application/pdf" || file.name.toLowerCase().endsWith(".pdf");
    if (!allowed || file.size > 50 * 1024 * 1024) {
      attachmentUpload.failed += 1;
      attachmentUpload.percent = Math.round(((index + 1) / files.length) * 100);
      continue;
    }
    try {
      await uploadPreAiAttachmentApi(encounterId, {
        stageCode,
        taskId,
        file,
        description: customDescription || undefined,
        capturedAt: new Date().toISOString().slice(0, 19).replace("T", " "),
        batchId,
        batchName,
        relativePath: file.webkitRelativePath || file.name,
        sequenceNo: index + 1
      });
      attachmentUpload.success += 1;
    } catch {
      attachmentUpload.failed += 1;
    }
    attachmentUpload.percent = Math.round(((index + 1) / files.length) * 100);
  }
  try {
    const { data } = await getPreAiWorkspaceApi(encounterId);
    if (selectedEncounterId.value === encounterId) {
      hydrateWorkspace(data);
    }
    if (attachmentUpload.success) ElMessage.success(`已上传 ${attachmentUpload.success} 个附件；外部 DOCX 不会包含原图`);
    if (attachmentUpload.failed) ElMessage.warning(`${attachmentUpload.failed} 个文件因格式、大小或上传异常未成功`);
  } finally {
    actionLoading.value = false;
  }
  input.value = "";
};

const voidAttachment = async (attachmentId: string) => {
  await ElMessageBox.confirm("作废后文件不再显示，但保留审计记录。", "作废附件", { type: "warning" });
  await runAction(async () => {
    const { data } = await voidPreAiAttachmentApi(selectedEncounterId.value, attachmentId);
    hydrateWorkspace(data);
    ElMessage.success("附件引用已作废");
  });
};

const restoreAttachment = async (attachment: PreAiAttachment) => {
  try {
    await runAction(async () => {
      const { data } = await restorePreAiAttachmentApi(selectedEncounterId.value, attachment.id);
      hydrateWorkspace(data);
      ElMessage.success(`已恢复「${attachment.fileName}」`);
    });
  } catch (error: any) {
    ElMessage.error(error?.message || "附件恢复失败");
  }
};

const removeImageAttachment = async (attachment: PreAiAttachment) => {
  try {
    await ElMessageBox.confirm(
      `确认删除「${attachment.fileName}」吗？删除后可在 10 秒内撤销，或稍后在“已作废（可恢复）”中找回。`,
      "删除影像",
      { type: "warning", confirmButtonText: "确认删除", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  try {
    const { data } = await voidPreAiAttachmentApi(selectedEncounterId.value, attachment.id);
    hydrateWorkspace(data);
    const undoMessage = ElMessage({
      type: "success",
      duration: 10000,
      showClose: true,
      message: h("span", { class: "attachment-undo-message" }, [
        `已删除「${attachment.fileName}」`,
        h(
          ElButton,
          {
            link: true,
            type: "primary",
            size: "small",
            onClick: async () => {
              undoMessage.close();
              await restoreAttachment(attachment);
            }
          },
          () => "撤销删除"
        )
      ])
    });
  } catch (error: any) {
    ElMessage.error(error?.message || "删除失败");
  }
};

const deleteTargetMedicalRecord = async (version: GeneratedMedicalRecord) => {
  if (version.status === "finalized") {
    ElMessage.warning("已定稿目标病历不可删除；如需更正，请先按病历管理流程作废");
    return;
  }

  try {
    await ElMessageBox.confirm(`确认删除目标病历 V${version.version} 及对应 Word 文件吗？此操作不可恢复。`, "删除历史病历", {
      type: "warning",
      confirmButtonText: "确认删除",
      cancelButtonText: "取消"
    });
    deletingTargetVersionId.value = version.id;
    await deleteMedicalRecordApi(version.id);

    if (latestGeneratedTargetVersionId.value === version.id) latestGeneratedTargetVersionId.value = "";
    if (pendingGeneratedTargetRecord.value?.id === version.id) {
      pendingGeneratedTargetRecord.value = undefined;
      inpatientAiDialogVisible.value = false;
      clearInpatientAiReference();
    }
    if (inpatientAiResultRecord.value?.id === version.id) {
      inpatientAiResultRecord.value = undefined;
      inpatientAiResultContent.value = "";
      inpatientAiResultModel.value = "";
      inpatientAiResultDialogVisible.value = false;
    }

    await loadTargetMedicalRecordVersions();
    ElMessage.success(`目标病历 V${version.version} 已删除`);
  } catch (error: any) {
    if (error !== "cancel" && error !== "close") ElMessage.error(error?.message || "目标病历删除失败");
  } finally {
    deletingTargetVersionId.value = "";
  }
};

const loadTargetMedicalRecordVersions = async () => {
  const encounterId = selectedEncounterId.value;
  const requestSequence = ++targetVersionsRequestSequence;
  if (!encounterId) {
    targetMedicalRecordVersions.value = [];
    targetVersionsLoading.value = false;
    return;
  }

  targetVersionsLoading.value = true;
  try {
    const { data } = await getGeneratedMedicalRecordVersionsApi({ encounterId });
    if (requestSequence !== targetVersionsRequestSequence || selectedEncounterId.value !== encounterId) {
      return;
    }
    targetMedicalRecordVersions.value = data;
  } catch (error: any) {
    if (requestSequence === targetVersionsRequestSequence) {
      ElMessage.error(error.message || "目标病历版本加载失败");
    }
  } finally {
    if (requestSequence === targetVersionsRequestSequence) targetVersionsLoading.value = false;
  }
};

const loadReviewPreview = async () => {
  const encounterId = selectedEncounterId.value;
  if (!encounterId || !canReview.value) return;
  void loadTargetMedicalRecordVersions();
  if (reviewRequestInFlightEncounterId === encounterId) return;
  if (reviewAbortController) cancelReviewRequest();

  const requestSequence = ++reviewRequestSequence;
  const requestController = new AbortController();
  reviewAbortController = requestController;
  reviewRequestInFlightEncounterId = encounterId;
  try {
    const { data } = await getPreAiReviewPreviewApi(encounterId, requestController.signal);
    if (
      requestSequence !== reviewRequestSequence ||
      reviewAbortController !== requestController ||
      selectedEncounterId.value !== encounterId
    ) {
      return;
    }
    reviewPreview.value = data;
  } catch (error: any) {
    if (error?.name !== "AbortError" && requestSequence === reviewRequestSequence) {
      ElMessage.error(error.message || "脱敏预览加载失败");
    }
  } finally {
    if (reviewAbortController === requestController) reviewAbortController = undefined;
    if (reviewRequestSequence === requestSequence && reviewRequestInFlightEncounterId === encounterId && !reviewAbortController) {
      reviewRequestInFlightEncounterId = "";
    }
  }
};

const reviewOverrideKey = (item: Pick<PreAiReviewOverride, "sectionCode" | "rowId">) => `${item.sectionCode}:${item.rowId}`;
const reviewOverrides = () => {
  const reviewData = stageSubmission("REVIEW")?.data as Record<string, any> | undefined;
  return Array.isArray(reviewData?.reviewOverrides) ? ([...reviewData.reviewOverrides] as PreAiReviewOverride[]) : [];
};
const saveReviewRowOverride = async (override: PreAiReviewOverride) =>
  runAction(async () => {
    const next = new Map(reviewOverrides().map(item => [reviewOverrideKey(item), item]));
    next.set(reviewOverrideKey(override), override);
    const { data } = await savePreAiReviewOverridesApi(
      selectedEncounterId.value,
      Array.from(next.values()),
      stageSubmission("REVIEW")?.version ?? 0
    );
    reviewPreview.value = data;
    hydrateWorkspace(data.workspace);
    await loadEncounterList();
    ElMessage.success("医生复核修改已保存，后续生成以本次修改为准");
  });

const confirmReview = async () =>
  runAction(async () => {
    const { data } = await confirmPreAiReviewApi(
      selectedEncounterId.value,
      reviewStatement.value,
      criticalAcknowledged.value,
      stageSubmission("REVIEW")?.version ?? 0
    );
    hydrateWorkspace(data);
    await loadEncounterList();
    await loadReviewPreview();
    ElMessage.success("医生复核已确认，现在可以生成脱敏 DOCX");
  });

const buildInpatientAiPrompt = () => DEFAULT_INPATIENT_AI_PROMPT;

const formatFileSize = (size: number) => {
  if (size < 1024 * 1024) return `${Math.max(1, Math.round(size / 1024))} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
};

const inspectionDecisionLabel = computed(() => {
  const decision = inpatientAiInspection.value?.decision;
  return decision === "ACCEPTED" ? "检查通过" : decision === "SANITIZED" ? "已安全净化" : "已拒绝";
});
const inspectionDecisionType = computed(() =>
  inpatientAiInspection.value?.decision === "REJECTED"
    ? "danger"
    : inpatientAiInspection.value?.decision === "SANITIZED"
      ? "warning"
      : "success"
);
const inspectionRiskType = computed(() =>
  ["CRITICAL", "HIGH"].includes(inpatientAiInspection.value?.highestRiskLevel || "")
    ? "danger"
    : inpatientAiInspection.value?.highestRiskLevel === "MEDIUM"
      ? "warning"
      : "success"
);
const workflowTaskStatusType = computed(() =>
  inpatientAiTask.value?.status === "SUCCEEDED" ? "success" : inpatientAiTask.value?.status === "FAILED" ? "danger" : "warning"
);
const workflowStageOrder = ["PENDING", "ASSET_LOADING", "AI_GENERATION", "OUTPUT_VALIDATION", "MAPPING_PERSISTENCE", "COMPLETED"];
const workflowProgressPercent = computed(() => {
  if (inpatientAiTask.value?.status === "SUCCEEDED") return 100;
  const index = workflowStageOrder.indexOf(inpatientAiTask.value?.currentStage || "PENDING");
  return Math.max(5, Math.round(((Math.max(0, index) + 1) / workflowStageOrder.length) * 100));
});
const mappedNodeCount = computed(() => inpatientAiMappings.value?.mappings.filter(item => item.status === "MAPPED").length || 0);
const workflowStageLabel = (stage: string) =>
  ({
    PENDING: "等待执行",
    ASSET_LOADING: "读取已检查资产",
    AI_GENERATION: "模型生成与节点回填",
    OUTPUT_VALIDATION: "输出 DOCX 安全复检",
    MAPPING_PERSISTENCE: "保存节点映射",
    COMPLETED: "生成完成",
    QUEUE_REJECTED: "任务队列拒绝"
  })[stage] ||
  stage ||
  "等待执行";

const abortInpatientAiWorkflow = () => {
  inpatientAiAbortController?.abort();
  inpatientAiAbortController = undefined;
  inpatientAiInspecting.value = false;
  inpatientAiGenerating.value = false;
};

const clearInpatientAiReference = () => {
  abortInpatientAiWorkflow();
  inpatientAiReferenceDocument.value = undefined;
  inpatientAiInspection.value = undefined;
  inpatientAiTargetNodeKeys.value = [];
  inpatientAiTask.value = undefined;
  inpatientAiMappings.value = undefined;
  if (inpatientAiReferenceInput.value) inpatientAiReferenceInput.value.value = "";
};

const openInpatientAiReferencePicker = () => inpatientAiReferenceInput.value?.click();

const handleInpatientAiReferenceChange = async (event: Event) => {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  if (!file.name.toLowerCase().endsWith(".docx")) {
    clearInpatientAiReference();
    ElMessage.error("参考文档仅支持 DOCX 格式");
    return;
  }
  if (file.size > 10 * 1024 * 1024) {
    clearInpatientAiReference();
    ElMessage.error("参考文档不能超过 10 MB");
    return;
  }

  abortInpatientAiWorkflow();
  inpatientAiReferenceDocument.value = file;
  inpatientAiInspection.value = undefined;
  inpatientAiTargetNodeKeys.value = [];
  inpatientAiTask.value = undefined;
  inpatientAiMappings.value = undefined;
  const encounterId = selectedEncounterId.value;
  if (!encounterId) return;
  const requestController = new AbortController();
  inpatientAiAbortController = requestController;
  inpatientAiInspecting.value = true;
  try {
    const { data } = await inspectMedicalRecordDocumentV2Api({ encounterId, document: file, signal: requestController.signal });
    if (inpatientAiAbortController !== requestController || inpatientAiReferenceDocument.value !== file) return;
    inpatientAiInspection.value = data;
    if (data.decision === "REJECTED") ElMessage.error("DOCX 安全检查未通过，已禁止提交生成");
    else ElMessage.success(data.decision === "SANITIZED" ? "危险部件已移除，可使用净化后的原包生成" : "DOCX 原包检查通过");
  } catch (error: any) {
    if (error?.name !== "AbortError") {
      inpatientAiInspection.value = undefined;
      ElMessage.error(error.message || "DOCX 安全检查失败");
    }
  } finally {
    if (inpatientAiAbortController === requestController) inpatientAiAbortController = undefined;
    inpatientAiInspecting.value = false;
  }
};

const closeInpatientAiDialog = () => {
  const wasRunning = inpatientAiGenerating.value || inpatientAiInspecting.value;
  abortInpatientAiWorkflow();
  inpatientAiDialogVisible.value = false;
  pendingGeneratedTargetRecord.value = undefined;
  clearInpatientAiReference();
  ElMessage.info(
    wasRunning ? "已取消当前请求；服务端已提交的任务仍保留审计记录" : "已取消 AI 加工，基础目标病历仍保留在版本列表中"
  );
};

const copyInpatientAiResult = async () => {
  const content = inpatientAiResultContent.value;
  if (!content) return;

  try {
    if (navigator.clipboard && globalThis.isSecureContext) {
      await navigator.clipboard.writeText(content);
    } else {
      const textarea = document.createElement("textarea");
      textarea.value = content;
      textarea.setAttribute("readonly", "");
      textarea.style.position = "fixed";
      textarea.style.opacity = "0";
      document.body.appendChild(textarea);
      textarea.select();
      const copied = document.execCommand("copy");
      textarea.remove();
      if (!copied) throw new Error("浏览器未允许复制");
    }
    ElMessage.success("目标病历内容已复制");
  } catch {
    ElMessage.warning("自动复制失败，请在文本框内全选并复制");
  }
};

const saveMedicalRecordDownload = (download: { blob: Blob; filename: string }) => {
  const url = URL.createObjectURL(download.blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = download.filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
};

const loadSuccessfulWorkflowResult = async (task: MedicalRecordWorkflowTask, signal?: AbortSignal) => {
  const result = task.result;
  const record = result.record;
  if (!record) throw new Error("生成任务成功但缺少病历版本信息");
  inpatientAiTask.value = task;
  const mappingsResponse = await getMedicalRecordWorkflowMappingsApi(task.taskId, signal);
  inpatientAiMappings.value = mappingsResponse.data;
  latestGeneratedTargetVersionId.value = record.id;
  latestGeneratedExportVersionId.value = "";
  inpatientAiResultContent.value = result.generatedContent || record.content || "";
  inpatientAiResultModel.value = task.model || result.model || record.model || "";
  inpatientAiResultRecord.value = record;
  inpatientAiDialogVisible.value = false;
  pendingGeneratedTargetRecord.value = undefined;
  inpatientAiReferenceDocument.value = undefined;
  inpatientAiInspection.value = undefined;
  inpatientAiTargetNodeKeys.value = [];
  if (inpatientAiReferenceInput.value) inpatientAiReferenceInput.value.value = "";
  inpatientAiResultDialogVisible.value = true;
  await loadTargetMedicalRecordVersions();
  ElMessage.success(`AI 住院病历 V${record.version} 已完成原包保真回填，可直接下载或导出`);
};

const pollSubmittedInpatientAiTask = async (taskId: string, requestController: AbortController) => {
  const task = await pollMedicalRecordWorkflowTask(taskId, {
    signal: requestController.signal,
    onUpdate: update => {
      if (inpatientAiAbortController === requestController) inpatientAiTask.value = update;
    }
  });
  inpatientAiTask.value = task;
  if (task.status === "FAILED") {
    ElMessage.error(`${task.errorCode || "GENERATION_FAILED"}：${task.errorMessage || "病历生成失败，可保留当前检查结果重试"}`);
    return;
  }
  await loadSuccessfulWorkflowResult(task, requestController.signal);
};

const completeInpatientAiGeneration = async () => {
  const sourceRecord = pendingGeneratedTargetRecord.value;
  const inspection = inpatientAiInspection.value;
  if (!sourceRecord || !inspection?.canGenerate) {
    ElMessage.warning("请先上传并通过 DOCX 安全检查");
    return;
  }
  abortInpatientAiWorkflow();
  const requestController = new AbortController();
  inpatientAiAbortController = requestController;
  inpatientAiGenerating.value = true;
  try {
    const { data } = await submitMedicalRecordWorkflowTaskApi(
      {
        reportId: inspection.reportId,
        sourceRecordId: sourceRecord.id,
        prompt: inpatientAiPrompt.value.trim(),
        mappingMode: inpatientAiMappingMode.value,
        targetNodeKeys: inpatientAiTargetNodeKeys.value
      },
      requestController.signal
    );
    inpatientAiTask.value = data;
    await pollSubmittedInpatientAiTask(data.taskId, requestController);
  } catch (error: any) {
    if (error?.name !== "AbortError") ElMessage.error(error.message || "病历生成任务提交或轮询失败，检查结果已保留");
  } finally {
    if (inpatientAiAbortController === requestController) inpatientAiAbortController = undefined;
    inpatientAiGenerating.value = false;
  }
};

const retryInpatientAiGeneration = async () => {
  const failedTask = inpatientAiTask.value;
  if (!failedTask || failedTask.status !== "FAILED") return;
  abortInpatientAiWorkflow();
  const requestController = new AbortController();
  inpatientAiAbortController = requestController;
  inpatientAiGenerating.value = true;
  try {
    const { data } = await retryMedicalRecordWorkflowTaskApi(failedTask.taskId, requestController.signal);
    inpatientAiTask.value = data;
    await pollSubmittedInpatientAiTask(data.taskId, requestController);
  } catch (error: any) {
    if (error?.name !== "AbortError") ElMessage.error(error.message || "病历生成任务重试失败");
  } finally {
    if (inpatientAiAbortController === requestController) inpatientAiAbortController = undefined;
    inpatientAiGenerating.value = false;
  }
};

const downloadInpatientAiOutputAsset = async () => {
  const assetId = inpatientAiTask.value?.outputAssetId;
  if (!assetId) return;
  try {
    saveMedicalRecordDownload(await downloadMedicalRecordAssetV2Api(assetId));
  } catch (error: any) {
    ElMessage.error(error.message || "输出资产下载失败");
  }
};

const downloadInpatientAiResultRecord = async () => {
  const recordId = inpatientAiResultRecord.value?.id;
  if (!recordId) return;
  try {
    saveMedicalRecordDownload(await downloadGeneratedMedicalRecordV2Api(recordId));
  } catch (error: any) {
    ElMessage.error(error.message || "目标病历下载失败");
  }
};

const generateTargetMedicalRecord = async () =>
  runAction(async () => {
    const encounterId = selectedEncounterId.value;
    if (!encounterId) throw new Error("请先选择前置病例");
    const { data } = await generateMedicalRecordApi({
      encounterId,
      patientCaseId: workspace.value?.encounter.patientCaseId
    });
    targetMedicalRecordVersions.value = [
      data.record,
      ...targetMedicalRecordVersions.value.filter(record => record.id !== data.record.id)
    ];
    latestGeneratedTargetVersionId.value = data.record.id;
    latestGeneratedExportVersionId.value = "";
    await loadTargetMedicalRecordVersions();
    if (!medicalRecordV2Enabled) {
      ElMessage.success(`目标病历 V${data.record.version} 已生成并保留在稳定版版本列表中`);
      return;
    }

    const missingHint = data.missingItems.length
      ? `当前仍有 ${data.missingItems.length} 个模板字段缺失，基础草稿已按前置病历现有事实生成。`
      : "基础草稿已按前置病历事实完整生成。";
    try {
      await ElMessageBox.confirm(
        `${missingHint}\n\n是否继续使用 GPT 兼容模型进行 AI 加工？需要在下一步显式上传 DOCX 参考文档，AI 加工会另存新版本，不会覆盖基础草稿。`,
        `基础目标病历 V${data.record.version} 已生成`,
        {
          confirmButtonText: "继续 AI 加工",
          cancelButtonText: "暂不加工",
          type: "success",
          distinguishCancelAndClose: true
        }
      );
      clearInpatientAiReference();
      pendingGeneratedTargetRecord.value = data.record;
      inpatientAiPrompt.value = buildInpatientAiPrompt();
      inpatientAiMappingMode.value = "CONTROLLED";
      inpatientAiDialogVisible.value = true;
    } catch {
      pendingGeneratedTargetRecord.value = undefined;
      ElMessage.success(`已保留基础目标病历 V${data.record.version}，本次跳过 AI 加工`);
    }
  });

const generateExport = async () =>
  runAction(async () => {
    let data: { export: PreAiExportVersion; workspace: PreAiWorkspace };
    try {
      ({ data } = await generatePreAiExportApi(selectedEncounterId.value));
    } catch (error: any) {
      throw new Error(error.message || "生成失败，请根据请求编号查看后台日志");
    }
    hydrateWorkspace(data.workspace);
    latestGeneratedTargetVersionId.value = "";
    latestGeneratedExportVersionId.value = data.export.id;
    await loadEncounterList();
    ElMessage.success(`脱敏前置资料 V${data.export.version} 已生成并进入版本列表`);
  });

const runAction = async (action: () => Promise<void>) => {
  actionLoading.value = true;
  try {
    await action();
  } catch (error: any) {
    const message = error.message || "操作失败";
    ElMessage.error(/版本|冲突|version|conflict/i.test(message) ? "远端数据已更新，未覆盖本地草稿，请核对后重新保存" : message);
  } finally {
    actionLoading.value = false;
  }
};

const cleanupTransientResources = () => {
  abortInpatientAiWorkflow();
  workspaceAbortController?.abort();
  workspaceAbortController = undefined;
  workspaceRequestSequence += 1;
  workspaceLoading.value = false;
  pendingWorkflowSelection.value = undefined;
  resetWorkspaceImageContext();
  resetTimelineContext();
  clearPatientArchiveMasonryResources();
  cancelReviewRequest();
  targetVersionsRequestSequence += 1;
  targetVersionsLoading.value = false;
  historyAbortController?.abort();
  historyAbortController = undefined;
  historyRequestSequence += 1;
  historyLoading.value = false;
};

const beforeUnloadWithUnsavedDrafts = (event: BeforeUnloadEvent) => {
  if (!hasUnsavedDrafts.value) return;
  event.preventDefault();
  event.returnValue = "";
};

onBeforeRouteLeave(() => {
  if (!hasUnsavedDrafts.value) return true;
  return window.confirm("当前病例有未保存的填写内容，离开后将不再保留。确定离开吗？");
});

const refreshActiveWorkspace = async () => {
  const encounterId = selectedEncounterId.value;
  if (!encounterId) return;
  try {
    const { data } = await getPreAiWorkspaceApi(encounterId);
    if (selectedEncounterId.value !== encounterId) return;
    hydrateWorkspace(data);
    await loadEncounterList();
  } catch (error: any) {
    ElMessage.error(error.message || "前置病历刷新失败");
  }
};

onMounted(() => {
  if (workspaceShellRef.value) {
    historyShellWidth.value = workspaceShellRef.value.clientWidth;
    historyResizeObserver = new ResizeObserver(entries => {
      historyShellWidth.value = entries[0]?.contentRect.width || workspaceShellRef.value?.clientWidth || 0;
    });
    historyResizeObserver.observe(workspaceShellRef.value);
  }
  void loadEncounterList();
  scheduleTopContextCompaction();
  scheduleWorkflowContextCompaction();
  window.addEventListener("clinic-queue-updated", refreshEncounterListAfterQueueUpdate);
  window.addEventListener("beforeunload", beforeUnloadWithUnsavedDrafts);
});
onActivated(async () => {
  if (!workspace.value) return;
  await refreshActiveWorkspace();
  if (!workspace.value) return;
  syncWorkspaceImageContext(workspace.value);
  syncTimelineContext(workspace.value);
  if (
    editorMode.value === "PREVIEW" ||
    (workflowSelected.value && selectedPanel.value === "STAGE" && selectedStageCode.value === "RECEPTION")
  ) {
    void loadWorkspaceInspectionImages(workspace.value);
  }
  if (
    workflowSelected.value &&
    editorMode.value === "EDIT" &&
    selectedPanel.value === "STAGE" &&
    selectedStageCode.value === "INSPECTION" &&
    inspectionView.value === "HISTORY"
  ) {
    void showInspectionTimeline();
  }
  if (
    workflowSelected.value &&
    editorMode.value === "EDIT" &&
    selectedPanel.value === "STAGE" &&
    selectedStageCode.value === "REVIEW"
  ) {
    void loadReviewPreview();
  }
});
onDeactivated(cleanupTransientResources);
onBeforeUnmount(cleanupTransientResources);
onBeforeUnmount(() => {
  window.removeEventListener("clinic-queue-updated", refreshEncounterListAfterQueueUpdate);
  window.removeEventListener("beforeunload", beforeUnloadWithUnsavedDrafts);
  if (queueUpdateRefreshTimer) clearTimeout(queueUpdateRefreshTimer);
  topContextIdle.clear();
  workflowContextIdle.clear();
  stopHistoryPointerResize?.();
  historyResizeObserver?.disconnect();
  historyResizeObserver = undefined;
});
</script>

<style scoped lang="scss">
.inpatient-ai-dialog {
  display: grid;
  gap: 16px;
  max-height: 68vh;
  padding-right: 4px;
  overflow-y: auto;

  &__reference {
    display: grid;
    gap: 8px;
  }

  &__file-input {
    display: none;
  }

  &__file-actions {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px 12px;
  }

  &__file-name {
    max-width: 420px;
    overflow: hidden;
    color: #374151;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__file-empty {
    color: #9ca3af;
    font-size: 13px;
  }

  &__label {
    color: #1f2937;
    font-size: 14px;
    font-weight: 650;
  }

  p {
    margin: 0;
    color: #6b7280;
    font-size: 12px;
    line-height: 1.7;
  }
}

.workflow-card {
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  background: var(--el-fill-color-lighter);
}

.workflow-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.workflow-card__head > div {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.workflow-card__head > div:first-child {
  display: grid;
  gap: 4px;
}

.workflow-card__head small {
  color: var(--el-text-color-secondary);
  font-weight: 400;
}

.finding-list,
.mapping-list {
  display: grid;
  gap: 8px;
}

.finding-list > div,
.mapping-list > div {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.6;
}

.node-catalog {
  display: grid;
  max-height: 240px;
  padding: 8px;
  overflow-y: auto;
  border-radius: 8px;
  background: var(--el-bg-color);
}

.node-catalog :deep(.el-checkbox) {
  height: auto;
  min-height: 38px;
  margin-right: 0;
  padding: 6px 4px;
  white-space: normal;
}

.node-catalog :deep(.el-checkbox__label) {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.node-catalog small {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-events {
  max-height: 220px;
  padding: 4px 8px;
  overflow-y: auto;
}

.inpatient-ai-result {
  display: grid;
  gap: 16px;

  &__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 8px 20px;
    color: #4b5563;
    font-size: 13px;
  }

  :deep(.el-textarea__inner) {
    color: #1f2937;
    font-family: inherit;
    line-height: 1.8;
    background: #f8fafc;
  }
}

.pre-ai-page {
  --ease-standard: cubic-bezier(0.2, 0.8, 0.2, 1);
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: calc(100vh - 120px);
}
.handoff-notice {
  margin: 12px 0;
}
.page-hero,
.patient-banner,
.stage-panel,
.workflow-empty-panel,
.template-preview-panel {
  border: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
  border-radius: 16px;
  box-shadow: 0 10px 30px rgb(31 78 120 / 8%);
}
.page-hero {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  min-height: 86px;
  padding: 20px 24px;
  background: linear-gradient(135deg, color-mix(in srgb, var(--el-color-primary) 10%, var(--el-bg-color)), var(--el-bg-color));
  transition:
    min-height 0.28s var(--ease-standard),
    padding 0.28s var(--ease-standard),
    box-shadow 0.28s var(--ease-standard),
    background-color 0.28s var(--ease-standard);
}
.page-hero.is-context-compact {
  min-height: 58px;
  padding: 12px 24px;
  box-shadow: 0 6px 18px rgb(31 78 120 / 5%);
}
.page-hero__copy {
  min-width: 0;
}
.context-restore {
  padding: 6px 12px;
  color: var(--el-color-primary);
  font-size: 13px;
  font-weight: 700;
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 999px;
  background: color-mix(in srgb, var(--el-color-primary-light-9) 70%, var(--el-bg-color));
  cursor: pointer;
}
.context-restore:focus-visible {
  outline: 2px solid var(--el-color-primary-light-3);
  outline-offset: 2px;
}
.page-hero h2 {
  margin: 8px 0 0;
  font-size: 24px;
}
.hero-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}
.hero-actions :deep(.el-button),
.panel-actions :deep(.el-button),
.heading-tags :deep(.el-tag) {
  margin-left: 0;
}
.panel-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 14px;
  padding-bottom: 12px;
  border-bottom: 1px solid color-mix(in srgb, var(--el-color-primary) 18%, var(--el-border-color-lighter));
}
.panel-heading > div:first-child {
  min-width: 0;
  display: grid;
  gap: 4px;
}
.work-surface-kicker {
  width: fit-content;
  display: inline-flex;
  align-items: center;
  padding: 3px 8px;
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0;
  border-radius: 999px;
  background: color-mix(in srgb, var(--el-color-primary) 10%, var(--el-bg-color));
}
.panel-heading h3 {
  margin: 0;
  font-size: 22px;
  line-height: 1.2;
}
.stage-panel {
  position: relative;
  padding: 22px 24px 88px;
  overflow: hidden;
  border-color: color-mix(in srgb, var(--el-color-primary) 28%, var(--el-border-color-light));
  box-shadow: 0 18px 44px rgb(15 23 42 / 10%);
}
.stage-panel::before {
  position: absolute;
  top: 18px;
  bottom: 18px;
  left: 0;
  width: 4px;
  content: "";
  border-radius: 0 999px 999px 0;
  background: var(--el-color-primary);
}
// 护理部四测强调区：与病史采集常规表单形成视觉分层
.nursing-vitals-section {
  padding: 14px 16px;
  margin-bottom: 14px;
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
  border-left: 4px solid var(--el-color-primary);
  border-radius: 10px;

  .nursing-vitals-heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 10px;

    strong {
      font-size: 15px;
      color: var(--el-text-color-primary);
    }

    small {
      display: block;
      margin-top: 3px;
      color: var(--el-text-color-secondary);
      font-size: 12px;
      line-height: 1.6;
    }
  }

  .nursing-vitals-input {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px 16px;
    padding: 12px 14px;
    background: var(--el-bg-color);
    border: 1px dashed var(--el-color-primary-light-5);
    border-radius: 8px;

    .vital-field {
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 0;

      > span {
        font-size: 12px;
        color: var(--el-text-color-regular);
      }
    }

    .vital-record-action {
      display: flex;
      grid-column: 1 / -1;
      justify-content: flex-end;
      padding-top: 2px;
      border-top: 1px solid var(--el-border-color-lighter);
    }
  }

  .nursing-vitals-summary {
    margin-top: 12px;

    .vitals-summary-title {
      display: flex;
      align-items: baseline;
      gap: 8px;
      margin-bottom: 8px;

      strong {
        font-size: 13px;
        color: var(--el-text-color-primary);
      }

      small {
        font-size: 12px;
        color: var(--el-text-color-secondary);
      }
    }

    .vitals-timeline {
      padding-left: 2px;

      :deep(.el-timeline-item__timestamp) {
        font-size: 12px;
        font-variant-numeric: tabular-nums;
      }
    }

    .vital-round-card {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 10px;
      padding: 8px 10px;
      background: var(--el-bg-color);
      border: 1px solid var(--el-border-color-lighter);
      border-radius: 8px;
    }

    .vital-round-values {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }

    .vital-value-chip {
      display: inline-flex;
      gap: 4px;
      align-items: baseline;
      padding: 3px 8px;
      font-size: 12px;
      background: var(--el-fill-color-light);
      border-radius: 6px;

      em {
        font-style: normal;
        color: var(--el-text-color-secondary);
      }

      strong {
        font-variant-numeric: tabular-nums;
      }

      i {
        font-style: normal;
        color: var(--el-color-warning);
      }

      &.abnormal {
        background: var(--el-color-warning-light-9);

        i {
          color: var(--el-color-danger);
          font-weight: 600;
        }
      }
    }
  }

  .vitals-empty-hint {
    margin: 10px 0 0;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}

.nursing-history-heading {
  margin-top: 4px;
}

.inspection-narrative-edit {
  margin: 0 0 16px;
  padding: 14px 16px;
  border: 1px solid var(--el-color-primary-light-5);
  border-radius: 10px;
  background: var(--el-color-primary-light-9);
}
.narrative-heading {
  display: grid;
  gap: 3px;
  margin-bottom: 8px;

  strong {
    font-size: 14px;
    color: var(--el-text-color-primary);
  }

  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}
.stage-form {
  padding: 16px;
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 16%, var(--el-border-color-lighter));
  border-radius: 14px;
  background: color-mix(in srgb, var(--el-bg-color) 88%, var(--el-color-primary-light-9));
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 18px;
}
.form-grid .span-2 {
  grid-column: span 2;
}
.form-grid .priority-field {
  padding: 12px;
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 18%, var(--el-border-color-lighter));
  border-radius: 12px;
  background: var(--el-bg-color);
}
.form-grid .secondary-field {
  opacity: 0.88;
}
.stage-form :deep(.el-form-item) {
  margin-bottom: 0;
}
.stage-form :deep(.el-form-item__label) {
  padding-bottom: 6px;
  color: var(--el-text-color-primary);
  font-weight: 700;
}
.stage-form :deep(.el-input__wrapper),
.stage-form :deep(.el-textarea__inner),
.stage-form :deep(.el-select__wrapper) {
  background: var(--el-bg-color);
}
.heading-tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}
.field-noise-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin: 12px 0 14px;
  padding: 12px 14px;
  border: 1px dashed var(--el-color-primary-light-5);
  border-radius: 12px;
  background: color-mix(in srgb, var(--el-color-primary-light-9) 76%, var(--el-bg-color));
}
.field-noise-toolbar > div {
  min-width: 0;
  display: grid;
  gap: 3px;
}
.field-noise-toolbar small {
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.dialog-field-noise-toolbar {
  margin-top: 14px;
}
.secondary-field {
  padding-top: 6px;
  border-top: 1px dashed var(--el-border-color-lighter);
}
.secondary-field :deep(.el-form-item__label) {
  color: var(--el-text-color-regular);
}
.history-intake-field :deep(.el-form-item__label) {
  color: var(--el-color-primary);
  font-weight: 700;
}
.history-intake-field :deep(.el-form-item__label)::before {
  content: "";
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 6px;
  border-radius: 50%;
  background: var(--el-color-primary);
  vertical-align: middle;
}
.panel-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 18px;
}
.panel-actions > div {
  flex: 1 1 auto;
}
.patient-archive-trigger {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-height: 54px;
  padding: 8px 20px;
  color: #ffffff;
  font-weight: 800;
  border: 0;
  border-radius: 14px;
  background: linear-gradient(135deg, var(--el-color-primary), color-mix(in srgb, var(--el-color-primary) 76%, #0f766e));
  box-shadow: 0 12px 24px rgb(0 150 136 / 24%);
}
.patient-archive-trigger__glyph {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: rgb(255 255 255 / 18%);
}
.patient-archive-trigger__copy {
  display: grid;
  gap: 1px;
  line-height: 1.25;
  text-align: left;

  small {
    font-size: 11px;
    font-weight: 500;
    color: rgb(255 255 255 / 78%);
  }
}
.patient-archive-trigger:hover,
.patient-archive-trigger:focus-visible {
  color: #ffffff;
  background: linear-gradient(135deg, color-mix(in srgb, var(--el-color-primary) 88%, #0f766e), #0f766e);
  box-shadow: 0 14px 28px rgb(0 150 136 / 30%);
}
.patient-archive-trigger__count {
  min-width: 30px;
  display: inline-flex;
  justify-content: center;
  padding: 2px 9px;
  color: var(--el-color-primary);
  border-radius: 999px;
  background: #ffffff;
  font-size: 13px;
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 70%);
}
.workspace-shell {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 14px;
  flex: 1;
  min-height: 650px;
}
.workspace-shell.with-history {
  grid-template-columns: minmax(520px, 1fr) 8px minmax(360px, var(--history-pane-width, 410px));
}
.history-resizer {
  position: sticky;
  top: 12px;
  align-self: stretch;
  min-height: 240px;
  cursor: col-resize;
  border-radius: 999px;
  touch-action: none;
  transition: background-color 0.16s ease;
}
.history-resizer::before {
  position: absolute;
  top: 0;
  right: -7px;
  bottom: 0;
  left: -7px;
  content: "";
}
.history-resizer span {
  position: sticky;
  top: 45%;
  display: block;
  width: 4px;
  height: 56px;
  margin: 0 auto;
  border-radius: 999px;
  background: var(--el-border-color);
  transition:
    background-color 0.16s ease,
    transform 0.16s ease;
}
.history-resizer:hover,
.history-resizer:focus-visible {
  background: var(--el-color-primary-light-9);
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: 2px;
}
.history-resizer:hover span,
.history-resizer:focus-visible span,
:global(body.history-pane-resizing) .history-resizer span {
  background: var(--el-color-primary);
  transform: scaleY(1.15);
}
.history-entry-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 12px;
  background: var(--el-color-primary-light-9);
}
.history-entry-bar > div {
  min-width: 0;
  display: grid;
  gap: 4px;
}
.history-entry-bar small {
  color: var(--el-text-color-secondary);
}
:global(.patient-archive-dialog) {
  border-radius: 10px;
  box-shadow: 0 24px 70px rgb(15 23 42 / 28%);
}
:global(.patient-archive-dialog .el-dialog__header) {
  padding: 24px 28px 14px;
  margin-right: 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
:global(.patient-archive-dialog .el-dialog__body) {
  padding: 18px 28px 24px;
}
.patient-archive-dialog__body {
  display: grid;
  gap: 16px;
}
.patient-archive-dialog__head strong {
  font-size: 18px;
}
.patient-archive-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.patient-archive-view-tags {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 999px;
  background: var(--el-fill-color-light);
}
.patient-archive-view-tags button {
  min-width: 86px;
  padding: 6px 14px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  cursor: pointer;
  transition:
    color 0.16s ease,
    background-color 0.16s ease,
    box-shadow 0.16s ease;
}
.patient-archive-view-tags button.active {
  color: var(--el-color-primary);
  font-weight: 700;
  background: var(--el-bg-color);
  box-shadow: 0 4px 14px rgb(15 23 42 / 8%);
}
.patient-archive-view-tags button:focus-visible {
  outline: 2px solid var(--el-color-primary-light-3);
  outline-offset: 2px;
}
.patient-archive-toolbar__hint {
  color: var(--el-text-color-secondary);
}
.patient-archive-masonry {
  columns: 3 260px;
  column-gap: 14px;
  padding-right: 8px;
}
.patient-archive-masonry-card {
  display: inline-block;
  width: 100%;
  margin: 0 0 14px;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-left: 4px solid transparent;
  border-radius: 14px;
  background: var(--el-bg-color);
  box-shadow: 0 10px 24px rgb(15 23 42 / 6%);
  break-inside: avoid;
  transition:
    border-color 0.16s ease,
    box-shadow 0.16s ease,
    background-color 0.16s ease;
}
.patient-archive-masonry-card:hover,
.patient-archive-masonry-card.active {
  border-color: var(--el-color-primary-light-3);
  background: color-mix(in srgb, var(--el-color-primary) 5%, var(--el-bg-color));
  box-shadow: 0 14px 30px rgb(0 150 136 / 13%);
}
.patient-archive-masonry-card.active {
  border-left-color: var(--el-color-primary);
}
.patient-archive-masonry-main {
  width: 100%;
  display: grid;
  gap: 10px;
  padding: 12px;
  text-align: left;
  border: 0;
  background: transparent;
  cursor: pointer;
}
.patient-archive-masonry-main:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -3px;
}
.patient-archive-masonry-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.patient-archive-masonry-head strong {
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.patient-archive-masonry-head span {
  flex: 0 0 auto;
  padding: 2px 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 999px;
  background: var(--el-fill-color-light);
}
.patient-archive-info-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}
.patient-archive-info-tag {
  max-width: 100%;
  display: inline-flex;
  align-items: center;
  padding: 4px 8px;
  overflow: hidden;
  font-size: 12px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
  border-radius: 999px;
}
.patient-archive-info-tag.tone-teal {
  color: #0f766e;
  background: #ccfbf1;
}
.patient-archive-info-tag.tone-blue {
  color: #1d4ed8;
  background: #dbeafe;
}
.patient-archive-info-tag.tone-violet {
  color: #6d28d9;
  background: #ede9fe;
}
.patient-archive-info-tag.tone-amber {
  color: #92400e;
  background: #fef3c7;
}
.patient-archive-info-tag.tone-rose {
  color: #be123c;
  background: #ffe4e6;
}
.patient-archive-info-tag.tone-green {
  color: #15803d;
  background: #dcfce7;
}
.patient-archive-image-strip {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
}
.patient-archive-thumbnail {
  width: 100%;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}
.patient-archive-thumbnail :deep(.el-image__inner) {
  width: 100%;
  height: auto;
  max-height: 86px;
  display: block;
  object-fit: contain;
}
.patient-archive-thumbnail-state,
.patient-archive-thumbnail-empty {
  min-height: 76px;
  display: grid;
  place-items: center;
  padding: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  border: 1px dashed var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}
.patient-archive-masonry-foot {
  display: grid;
  gap: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.patient-archive-masonry-foot span {
  color: var(--el-text-color-regular);
}
.patient-archive-card-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding-right: 8px;
}
.sidebar-title {
  display: grid;
  gap: 12px;
  margin-bottom: 0;
}
.patient-archive-filters {
  display: grid;
  grid-template-columns: minmax(240px, 1.2fr) minmax(180px, 0.8fr) minmax(190px, 0.8fr);
  gap: 10px;
}
.care-situation-filter,
.patient-archive-date-filter {
  width: 100%;
}
.sidebar-title__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.sidebar-title__head > div {
  min-width: 0;
  display: grid;
  gap: 4px;
}
.sidebar-title__head small {
  color: var(--el-text-color-secondary);
  line-height: 1.45;
}
.sidebar-title__head :deep(.el-button) {
  margin-left: 0;
}
.sidebar-title strong {
  font-size: 17px;
}
.encounter-row {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px 12px;
  margin-bottom: 10px;
  padding: 12px;
  text-align: left;
  border: 1px solid var(--el-border-color-lighter);
  border-left: 4px solid transparent;
  border-radius: 12px;
  background: var(--el-bg-color);
  transition:
    background-color 0.16s ease,
    border-color 0.16s ease,
    box-shadow 0.16s ease;
}
.encounter-row:hover,
.encounter-row.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  box-shadow: 0 10px 22px rgb(0 150 136 / 12%);
}
.encounter-row.active {
  border-left-color: var(--el-color-primary);
}
.encounter-row-main {
  min-width: 0;
  display: grid;
  gap: 6px;
  padding: 0;
  text-align: left;
  border: 0;
  background: transparent;
  cursor: pointer;
}
.encounter-row-main:focus-visible,
.encounter-row-followup:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}
.encounter-row__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.encounter-row__head strong {
  overflow: hidden;
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.encounter-row span {
  color: var(--el-text-color-regular);
}
.encounter-row small {
  color: var(--el-text-color-secondary);
}
.encounter-row-followup {
  align-self: end;
  padding: 4px 0;
  color: var(--el-color-primary);
  font-size: 12px;
  white-space: nowrap;
  border: 0;
  background: transparent;
  cursor: pointer;
}
.mini-steps {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 3px;
}
.mini-steps i {
  height: 4px;
  border-radius: 8px;
  background: var(--el-fill-color-dark);
}
.mini-steps i.done {
  background: var(--el-color-success);
}
.mini-steps i.returned {
  background: var(--el-color-warning);
}
.mini-steps i.skipped {
  background: var(--el-color-info-light-5);
}
.encounter-workspace {
  min-width: 0;
}
.workflow-empty-panel {
  min-height: 650px;
  display: grid;
  place-items: center;
  padding: 30px;
}
.workflow-empty-panel :deep(.el-empty__description) {
  display: grid;
  gap: 5px;
  text-align: center;
}
.workflow-empty-panel :deep(.el-empty__description p) {
  margin: 0;
  color: var(--el-text-color-secondary);
}
.patient-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 14px;
  margin-bottom: 8px;
  overflow: hidden;
  border-color: var(--el-border-color-lighter);
  background: color-mix(in srgb, var(--el-bg-color) 94%, var(--el-fill-color-light));
  box-shadow: 0 6px 18px rgb(31 78 120 / 5%);
  transition:
    padding 0.28s var(--ease-standard),
    box-shadow 0.28s var(--ease-standard),
    background-color 0.28s var(--ease-standard);
}
.patient-banner.is-context-compact {
  padding: 9px 12px;
  box-shadow: 0 4px 12px rgb(31 78 120 / 4%);
}
.patient-banner__identity {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
}
.patient-avatar {
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  color: var(--el-color-primary);
  font-size: 17px;
  font-weight: 800;
  border: 1px solid var(--el-color-primary-light-8);
  border-radius: 12px;
  background: color-mix(in srgb, var(--el-color-primary-light-9) 72%, var(--el-bg-color));
  transition:
    width 0.28s var(--ease-standard),
    height 0.28s var(--ease-standard),
    font-size 0.28s var(--ease-standard);
}
.patient-banner.is-context-compact .patient-avatar {
  width: 32px;
  height: 32px;
  font-size: 15px;
}
.patient-banner__identity small,
.context-stat small {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.patient-banner h3 {
  margin: 1px 0 2px;
  font-size: 18px;
  line-height: 1.2;
}
.patient-banner p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.patient-banner__compact-meta {
  overflow: hidden;
  max-width: min(64vw, 640px);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.patient-banner__overview {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}
.context-stat {
  min-width: 68px;
  display: grid;
  gap: 1px;
  padding: 6px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: color-mix(in srgb, var(--el-bg-color) 72%, var(--el-fill-color-light));
}
.context-stat strong {
  color: var(--el-color-primary);
  font-size: 15px;
}
.context-stat.warning strong {
  color: var(--el-color-warning);
}
.patient-banner__meta {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.patient-banner__meta > span {
  padding: 5px 9px;
  color: var(--el-text-color-regular);
  font-size: 12px;
  border-radius: 999px;
  background: var(--el-fill-color-light);
}
.workspace-modebar {
  position: sticky;
  z-index: 10;
  top: 0;
  display: grid;
  grid-template-columns: minmax(160px, 1fr) auto minmax(160px, 1fr);
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  margin-bottom: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: color-mix(in srgb, var(--el-bg-color) 92%, var(--el-fill-color-lighter));
  box-shadow: 0 5px 14px rgb(31 78 120 / 5%);
}
.workspace-modebar > .el-tag:last-child {
  justify-self: end;
}
.workspace-modebar > div:first-child {
  display: grid;
  gap: 2px;
}
.workspace-modebar > div:first-child span {
  font-weight: 700;
}
.workspace-modebar small {
  color: var(--el-text-color-secondary);
}
.mode-tags {
  position: relative;
  display: inline-grid;
  grid-template-columns: repeat(2, minmax(104px, 1fr));
  gap: 0;
  padding: 4px;
  border-radius: 999px;
  background: var(--el-fill-color-light);
}
.mode-slider {
  position: absolute;
  top: 4px;
  bottom: 4px;
  left: 4px;
  width: calc((100% - 8px) / 2);
  border-radius: 999px;
  background-color: var(--el-color-primary);
  transition:
    transform 0.16s ease,
    background-color 0.16s ease;
}
.mode-tags.preview .mode-slider {
  background-color: var(--el-color-success);
  transform: translateX(100%);
}
.mode-pill {
  position: relative;
  z-index: 1;
  min-width: 104px;
  padding: 8px 16px;
  color: var(--el-text-color-regular);
  font-weight: 700;
  border: 0;
  border-radius: 999px;
  background: transparent;
  cursor: pointer;
  user-select: none;
  transition: color 0.16s ease;
}
.mode-pill:focus-visible {
  outline: 2px solid var(--el-color-primary-light-3);
  outline-offset: 2px;
}
.mode-pill.edit.active,
.mode-pill.preview.active {
  color: #ffffff;
}
.mode-pill.edit:not(.active) {
  color: var(--el-color-primary);
}
.mode-pill.preview:not(.active) {
  color: var(--el-color-success);
}
.editor-mode-content {
  min-width: 0;
}
.auxiliary-stack {
  display: grid;
  gap: 16px;
}
.aux-dr-section {
  margin-top: 0;
}
.workspace-mode-enter-active,
.workspace-mode-leave-active {
  transition:
    opacity 0.22s var(--ease-standard),
    transform 0.22s var(--ease-standard);
}
.stage-switch-enter-active,
.stage-switch-leave-active {
  transition:
    opacity 0.26s var(--ease-standard),
    transform 0.26s var(--ease-standard);
}
.workspace-mode-enter-from,
.stage-switch-enter-from {
  opacity: 0;
  transform: translateY(8px);
}
.workspace-mode-leave-to,
.stage-switch-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
.stage-switch-enter-from,
.stage-switch-leave-to {
  pointer-events: none;
}
@media (prefers-reduced-motion: reduce) {
  .page-hero,
  .patient-banner,
  .patient-avatar,
  .mode-slider,
  .mode-pill,
  .workspace-mode-enter-active,
  .workspace-mode-leave-active,
  .stage-switch-enter-active,
  .stage-switch-leave-active,
  .upstream-image-card {
    transition: none;
  }
  .workspace-mode-enter-from,
  .workspace-mode-leave-to,
  .stage-switch-enter-from,
  .stage-switch-leave-to {
    transform: none;
  }
}
.inspection-view-tabs {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  margin: 12px 0;
  border-radius: 10px;
  background: var(--el-fill-color-light);
}
.inspection-view-tabs button {
  padding: 8px 18px;
  color: var(--el-text-color-secondary);
  border: 0;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
}
.inspection-view-tabs button.active {
  color: var(--el-color-primary);
  font-weight: 700;
  background: var(--el-bg-color);
  box-shadow: 0 3px 10px rgb(31 78 120 / 10%);
}
.inspection-timeline {
  position: relative;
  display: grid;
  gap: 18px;
  padding: 10px 0 12px 28px;
}
.inspection-timeline::before {
  position: absolute;
  inset: 12px auto 20px 8px;
  width: 2px;
  content: "";
  background: var(--el-border-color);
}
.timeline-node {
  position: relative;
  padding: 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 14px;
  background: var(--el-fill-color-blank);
}
.timeline-node.latest {
  border-color: var(--el-color-primary-light-5);
  box-shadow: 0 8px 22px rgb(64 158 255 / 10%);
}
.timeline-dot {
  position: absolute;
  top: 20px;
  left: -26px;
  width: 12px;
  height: 12px;
  border: 3px solid var(--el-bg-color);
  border-radius: 50%;
  background: var(--el-color-info);
  box-shadow: 0 0 0 1px var(--el-border-color);
}
.timeline-node.latest .timeline-dot {
  background: var(--el-color-primary);
}
.timeline-node > header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.timeline-node > header div {
  display: grid;
  gap: 4px;
}
.timeline-node > header small,
.timeline-facts span {
  color: var(--el-text-color-secondary);
}
.timeline-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 18px;
  margin-top: 14px;
}
.encounter-card__care-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}
.responsibility-timeline {
  --el-timeline-node-color: var(--el-color-primary-light-7);
  margin-top: 18px;
}
.responsibility-timeline :deep(.el-timeline-item) {
  padding-bottom: 14px;
}
.responsibility-timeline :deep(.el-timeline-item__tail) {
  border-left-color: var(--el-color-primary-light-7);
}
.responsibility-timeline :deep(.el-timeline-item__node) {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary);
  box-shadow: 0 0 0 3px var(--el-color-primary-light-9);
}
.responsibility-timeline :deep(.el-timeline-item__timestamp) {
  margin-bottom: 6px;
  color: var(--el-color-primary-dark-2);
  font-weight: 600;
}
.responsibility-timeline :deep(.el-timeline-item__wrapper) {
  top: -4px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  background: var(--el-bg-color);
  box-shadow: 0 6px 16px rgb(31 78 120 / 6%);
}
.responsibility-timeline p {
  margin: 6px 0;
}
.responsibility-timeline small {
  color: var(--el-text-color-secondary);
}
.responsibility-group-details {
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.responsibility-group-details summary {
  color: var(--el-color-primary);
  cursor: pointer;
}
.responsibility-group-details ul {
  display: grid;
  gap: 6px;
  padding-left: 16px;
  margin: 8px 0 0;
}
.responsibility-group-details li {
  display: grid;
  grid-template-columns: auto auto minmax(0, 1fr);
  gap: 6px;
  align-items: baseline;
}
.responsibility-group-details li span {
  white-space: nowrap;
}
.responsibility-group-details li strong {
  color: var(--el-text-color-primary);
  white-space: nowrap;
}
.responsibility-group-details li em {
  min-width: 0;
  overflow: hidden;
  font-style: normal;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.timeline-follow-up {
  display: grid;
  gap: 4px;
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: 8px;
  color: var(--el-color-primary-dark-2);
  background: var(--el-color-primary-light-9);
}
.timeline-follow-up span,
.timeline-follow-up p {
  margin: 0;
}
.timeline-facts p,
.visit-meta-summary p {
  margin: 4px 0 0;
  white-space: pre-wrap;
}
.timeline-attachment-groups,
.timeline-attachment-group {
  display: grid;
  gap: 8px;
  margin-top: 14px;
}
.timeline-attachment-group {
  padding: 10px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
}
.timeline-attachment-group .timeline-images {
  margin-top: 0;
}
.timeline-images {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 10px;
  margin-top: 14px;
}
.timeline-image {
  display: grid;
  gap: 5px;
  padding: 7px;
  overflow: hidden;
  text-align: left;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  background: var(--el-fill-color-light);
  cursor: pointer;
}
.timeline-image img,
.timeline-image > span {
  width: 100%;
  height: 92px;
  display: grid;
  place-items: center;
  object-fit: cover;
  border-radius: 7px;
  background: var(--el-fill-color);
}
.timeline-image small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.visit-meta-summary {
  margin-top: 14px;
  padding-top: 10px;
  color: var(--el-text-color-secondary);
  border-top: 1px dashed var(--el-border-color);
}
.history-template-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
  padding: 14px 16px;
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 12px;
  background: var(--el-color-primary-light-9);
}
.history-template-toolbar > div {
  min-width: 0;
  display: grid;
  gap: 4px;
}
.history-template-toolbar small {
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.section-caption {
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 700;
}
.primary-evidence-section {
  margin-top: 0;
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
}
.primary-evidence-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.primary-evidence-heading > div {
  min-width: 0;
  display: grid;
  gap: 4px;
}
.primary-evidence-heading strong {
  font-size: 17px;
}
.primary-evidence-heading small {
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.primary-evidence-section .upstream-image-grid {
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
}
.primary-evidence-section .upstream-image-card img,
.primary-evidence-section .upstream-image-card > span {
  height: 180px;
}
.upstream-section {
  display: grid;
  gap: 10px;
  margin-top: 14px;
  padding-top: 2px;
}
.upstream-heading,
.upstream-image-heading,
.upstream-stage-card > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.upstream-heading > div,
.upstream-image-heading > div,
.upstream-stage-title {
  display: grid;
  gap: 4px;
}
.upstream-heading small,
.upstream-image-heading small,
.upstream-stage-title small {
  color: var(--el-text-color-secondary);
}
.upstream-stage-list {
  display: grid;
  gap: 12px;
}
.upstream-stage-card {
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: color-mix(in srgb, var(--el-bg-color) 92%, var(--el-fill-color-lighter));
}
.upstream-stage-card > header {
  padding: 12px 14px 8px;
}
.upstream-summary-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 0 14px 8px;
}
.upstream-summary-label span {
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 800;
}
.upstream-summary-label small {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.upstream-summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  padding: 0 14px 12px;
}
.upstream-summary-grid > div {
  min-width: 0;
  display: grid;
  gap: 4px;
  padding: 8px 10px;
  border-radius: 8px;
  background: color-mix(in srgb, var(--el-bg-color) 70%, var(--el-fill-color-light));
}
.upstream-summary-grid span,
.read-only-grid dt {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.upstream-summary-grid strong {
  overflow: hidden;
  line-height: 1.55;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.upstream-detail-collapse {
  border-top: 1px solid color-mix(in srgb, var(--el-border-color-lighter) 82%, transparent);
  border-bottom: 0;
  background: color-mix(in srgb, var(--el-bg-color) 80%, var(--el-fill-color-lighter));
}
.upstream-detail-collapse :deep(.el-collapse-item__header) {
  height: 40px;
  padding: 0 14px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 700;
  background: transparent;
}
.read-only-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 18px;
  padding: 4px 14px 14px;
  margin: 0;
}
.read-only-grid > div {
  min-width: 0;
  padding-left: 8px;
  border-left: 2px solid var(--el-border-color-lighter);
}
.read-only-grid dt {
  margin-bottom: 4px;
}
.read-only-grid dd {
  margin: 0;
  color: var(--el-text-color-regular);
  line-height: 1.6;
  white-space: pre-wrap;
}
/* ===== 前台 DR 影像：独立附件，不参与病历元数据 ===== */
.patient-dr-strip {
  display: grid;
  gap: 8px;
  margin-top: 4px;
  padding: 10px 12px;
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 22%, var(--el-border-color-light));
  border-radius: 12px;
  background: color-mix(in srgb, var(--el-bg-color) 82%, var(--el-color-primary-light-9));
}
.patient-dr-strip__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.patient-dr-strip__head strong {
  display: block;
  font-size: 14px;
}
.dr-image-section {
  display: grid;
  gap: 10px;
  margin-top: 14px;
  padding: 13px;
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 20%, var(--el-border-color-light));
  border-radius: 14px;
  background: color-mix(in srgb, var(--el-bg-color) 76%, var(--el-color-primary-light-9));
}
.dr-image-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.dr-image-heading > div {
  display: grid;
  gap: 4px;
}
.dr-image-heading strong {
  font-size: 14px;
}
.dr-image-heading small {
  color: var(--el-text-color-secondary);
}
.voided-attachments-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding-top: 4px;
}
.voided-attachments-row .voided-caption {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.voided-attachments-row :deep(.el-button) {
  margin-left: 0;
  font-size: 12px;
}
.aux-voided-row {
  margin-top: -4px;
  padding: 2px 4px 6px;
}
.attachment-undo-message {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}
.camera-button {
  border-color: var(--el-color-success-light-5);
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
.camera-button:hover {
  border-color: var(--el-color-success);
  background: var(--el-color-success-light-8);
}
.upstream-image-section,
.attachment-section {
  display: grid;
  gap: 10px;
  margin-top: 14px;
  padding: 13px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 14px;
  background: color-mix(in srgb, var(--el-bg-color) 70%, var(--el-fill-color-lighter));
}
.priority-image-section {
  margin-top: 14px;
  border-color: color-mix(in srgb, var(--el-color-primary) 20%, var(--el-border-color-light));
  background: color-mix(in srgb, var(--el-bg-color) 78%, var(--el-color-primary-light-9));
}
.upstream-image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 12px;
}
.upstream-image-card {
  min-width: 0;
  display: grid;
  gap: 8px;
  padding: 8px;
  overflow: hidden;
  color: var(--el-text-color-primary);
  text-align: left;
  border: 1px solid var(--el-border-color-light);
  border-radius: 11px;
  background: var(--el-bg-color);
  cursor: pointer;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    background-color 0.2s ease;
}
.upstream-image-card:hover {
  border-color: var(--el-color-primary-light-5);
  background: color-mix(in srgb, var(--el-color-primary) 5%, var(--el-bg-color));
  box-shadow: 0 8px 20px rgb(64 158 255 / 12%);
}
.upstream-image-card.featured {
  grid-column: span 2;
}
.upstream-image-card img,
.upstream-image-card > span:not(.upstream-image-caption) {
  width: 100%;
  height: 120px;
  display: grid;
  place-items: center;
  object-fit: cover;
  color: var(--el-text-color-secondary);
  border-radius: 8px;
  background: var(--el-fill-color);
}
.upstream-image-card.featured img,
.upstream-image-card.featured > span:not(.upstream-image-caption) {
  height: 240px;
}
.upstream-image-caption {
  min-width: 0;
  display: grid;
  gap: 3px;
}
.upstream-image-caption strong,
.upstream-image-caption small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.upstream-image-card small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.attachment-list {
  display: grid;
  gap: 12px;
}
.attachment-batch {
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 11px;
  background: var(--el-bg-color);
}
.attachment-batch > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-light);
}
.attachment-batch > header small,
.attachment-name small,
.upload-summary {
  color: var(--el-text-color-secondary);
}
.attachment-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.attachment-row:last-child {
  border-bottom: 0;
}
.attachment-row :deep(.el-button) {
  flex: 0 0 auto;
  margin-left: 0;
}
.attachment-name {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 3px;
}
.attachment-name span,
.attachment-name small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.upload-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.upload-button {
  min-height: 38px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 0 15px;
  color: var(--el-color-primary);
  font-size: 14px;
  border: 1px dashed var(--el-color-primary-light-5);
  border-radius: 9px;
  background: var(--el-color-primary-light-9);
  cursor: pointer;
  transition:
    border-color 0.2s ease,
    background-color 0.2s ease;
}
.upload-button:hover {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-8);
}
.upload-button input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
}
.upload-summary {
  display: block;
}
.priority-field {
  padding: 14px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 8px;
  background: var(--el-color-warning-light-9);
}
.priority-field :deep(.el-form-item__label) {
  color: var(--el-color-warning-dark-2);
  font-weight: 700;
}
.diagnosis-field {
  display: grid;
  gap: 8px;
  width: 100%;
}
.diagnosis-field :deep(.el-select),
.diagnosis-field :deep(.el-input) {
  width: 100%;
}
.multi-field {
  display: grid;
  gap: 8px;
  width: 100%;
}
.multi-field :deep(.el-select),
.multi-field :deep(.el-input) {
  width: 100%;
}
.textarea-field {
  display: grid;
  gap: 8px;
}
.quick-template-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.quick-template-actions :deep(.el-button) {
  margin-left: 0;
}
.template-preview-panel {
  padding: 18px;
  background: var(--el-fill-color-light);
}
.legacy-select {
  width: 100%;
  margin-top: 18px;
}
.sticky-actions {
  position: sticky;
  z-index: 9;
  bottom: 10px;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  background: color-mix(in srgb, var(--el-bg-color) 94%, transparent);
  box-shadow: 0 -8px 26px rgb(31 78 120 / 10%);
  backdrop-filter: blur(10px);
}
@media (max-width: 1100px) {
  .workspace-shell,
  .workspace-shell.with-history {
    grid-template-columns: minmax(0, 1fr);
  }
  .history-resizer {
    display: none;
  }
  .workspace-shell.with-history :deep(.history-panel) {
    grid-column: 1 / -1;
    position: static;
    max-height: 680px;
  }
  .page-hero {
    align-items: flex-start;
  }
}
@media (prefers-reduced-motion: reduce) {
  .upstream-image-card,
  .patient-archive-view-tags button,
  .patient-archive-masonry-card {
    transition: none;
  }
}
@media (max-width: 680px) {
  .page-hero,
  .patient-banner,
  .panel-heading,
  .field-noise-toolbar,
  .history-template-toolbar,
  .history-entry-bar {
    flex-direction: column;
    align-items: stretch;
  }
  .hero-actions,
  .heading-tags,
  .panel-actions {
    justify-content: stretch;
  }
  .hero-actions :deep(.el-button),
  .panel-actions :deep(.el-button) {
    flex: 1 1 140px;
    margin-left: 0;
  }
  .form-grid,
  .read-only-grid,
  .upstream-summary-grid {
    grid-template-columns: 1fr;
  }
  .upstream-image-card.featured {
    grid-column: span 1;
  }
  .upstream-image-card.featured img,
  .upstream-image-card.featured > span:not(.upstream-image-caption) {
    height: 180px;
  }
  .form-grid .span-2 {
    grid-column: span 1;
  }
  .workspace-shell {
    grid-template-columns: 1fr;
    min-height: 420px;
  }
  .workflow-empty-panel {
    min-height: 320px;
    padding: 18px;
  }
  .encounter-workspace {
    grid-column: auto;
  }
  .patient-archive-filters,
  .patient-archive-card-grid {
    grid-template-columns: 1fr;
  }
  .patient-archive-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
  .patient-archive-view-tags {
    width: 100%;
  }
  .patient-archive-view-tags button {
    flex: 1;
  }
  .patient-archive-masonry {
    columns: 1;
  }
  :global(.patient-archive-dialog) {
    width: calc(100vw - 20px) !important;
    margin-top: 5vh;
  }
  :global(.patient-archive-dialog .el-dialog__header),
  :global(.patient-archive-dialog .el-dialog__body) {
    padding-right: 16px;
    padding-left: 16px;
  }
  .workspace-modebar {
    align-items: flex-start;
    flex-direction: column;
  }
  .workspace-modebar {
    grid-template-columns: 1fr;
  }
  .workspace-modebar > .el-tag:last-child {
    justify-self: start;
  }
  .patient-banner__overview {
    width: 100%;
    justify-content: flex-start;
  }
  .context-stat {
    flex: 1;
  }
  .sticky-actions {
    bottom: 6px;
    display: flex;
    flex-wrap: wrap;
  }
  .sticky-actions > div {
    display: none;
  }
  .sticky-actions :deep(.el-button) {
    width: auto;
    min-width: 0;
    flex: 1 1 120px;
    margin-left: 0;
  }
  .document-sheet {
    min-height: auto;
    padding: 30px 22px;
  }
  .document-meta,
  .document-fields {
    grid-template-columns: 1fr;
  }
  .document-fields > div.wide {
    grid-column: span 1;
  }
  .attachment-row {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .timeline-facts {
    grid-template-columns: 1fr;
  }

  /* ===== 前台 DR 影像移动端：拍照为主路径，尺寸收敛 ===== */
  .patient-dr-strip {
    gap: 6px;
    padding: 7px 9px;
    border-radius: 9px;
  }
  .patient-dr-strip__head strong {
    font-size: 12px;
  }
  .dr-image-section {
    gap: 7px;
    margin-top: 8px;
    padding: 8px;
    border-radius: 9px;
  }
  .dr-image-heading {
    flex-direction: column;
    gap: 5px;
  }
  .dr-image-heading strong {
    font-size: 13px;
  }
  .dr-image-heading small {
    font-size: 11px;
  }
  .dr-image-section .upload-actions {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 6px;
  }
  .dr-image-section .upload-button {
    min-height: 44px;
    padding: 0 6px;
    font-size: 13px;
    border-radius: 7px;
  }
  .voided-attachments-row :deep(.el-button) {
    margin-left: 0;
  }
}
</style>
