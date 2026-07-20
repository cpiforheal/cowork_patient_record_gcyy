<template>
  <!-- eslint-disable vue/html-closing-bracket-newline -->
  <div class="pre-ai-page">
    <header class="page-hero">
      <div>
        <el-tag type="primary" effect="plain">前置病历</el-tag>
        <h2>病历事实采集</h2>
      </div>
      <div class="hero-actions">
        <el-button :icon="Refresh" @click="refreshWorkspace">刷新</el-button>
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

    <section
      ref="workspaceShellRef"
      class="workspace-shell"
      :class="{ 'with-history': historyPanelOpen && workspace }"
      :style="historyPaneStyle"
    >
      <button
        v-if="patientDrawerOpen"
        type="button"
        class="patient-drawer-mask"
        aria-label="关闭患者列表"
        @click="patientDrawerOpen = false"
      ></button>
      <aside class="encounter-sidebar" :class="{ expanded: patientDrawerOpen }">
        <button
          type="button"
          class="patient-drawer-rail"
          :aria-expanded="patientDrawerOpen"
          :aria-label="patientDrawerOpen ? '收起患者主档案' : '展开患者主档案'"
          @click="togglePatientDrawer"
        >
          <el-icon><User /></el-icon>
          <strong>{{ patientCases.length }}</strong>
          <span>{{ selectedPatientCase?.patientName || "患者" }}</span>
        </button>
        <div class="sidebar-title">
          <div class="sidebar-title__head">
            <strong>患者主档案</strong>
            <el-button link type="primary" @click="patientDrawerOpen = false">收起</el-button>
          </div>
          <el-input v-model="keyword" clearable placeholder="姓名/病例标识" :prefix-icon="Search" />
        </div>
        <el-scrollbar height="calc(100vh - 285px)">
          <button
            v-for="item in filteredPatientCases"
            :key="item.id"
            type="button"
            class="encounter-card"
            :class="{ active: item.id === selectedPatientCaseId }"
            @click="selectPatientCase(item)"
          >
            <div class="encounter-card__head">
              <strong>{{ item.patientName || "待补姓名" }}</strong>
              <el-tag v-if="item.latestEncounter" size="small" :type="encounterStatusType(item.latestEncounter.status)">
                {{ item.visitCount }} 次来访
              </el-tag>
            </div>
            <span>{{ item.latestEncounter?.caseToken || "尚无子病历" }}</span>
            <small>{{ item.latestEncounter?.visitDate || "待补就诊时间" }} · {{ routeLabel(item.latestEncounter?.route) }}</small>
            <div v-if="item.latestEncounter" class="mini-steps">
              <i
                v-for="stage in preAiStages"
                :key="stage.code"
                :title="`${stage.title}：${stageStatusLabel[item.latestEncounter.stageStatuses?.[stage.code] || 'DRAFT']}`"
                :class="stageStatusClass(item.latestEncounter.stageStatuses?.[stage.code] || 'DRAFT')"
              ></i>
            </div>
            <el-button v-if="canCreateEncounter" link type="primary" @click.stop="openFollowUpDialog(item)">新增复诊</el-button>
          </button>
          <el-empty v-if="!filteredPatientCases.length" :image-size="72" description="暂无患者主档案" />
        </el-scrollbar>
      </aside>

      <WorkflowSidebar
        v-if="workspace"
        :workspace="workspace"
        :cards="workflowCards"
        :encounter-status-label="encounterStatusLabel"
        :encounter-status-type="encounterStatusType"
        :route-label="routeLabel"
        :status-of="workflowCardStatus"
        :status-label="workflowCardStatusLabel"
        :status-type="stageStatusType"
        :is-active="isWorkflowCardActive"
        :is-current="isCurrentWorkflowCard"
        @select="selectWorkflowCard"
      />

      <main v-loading="workspaceLoading" class="encounter-workspace">
        <el-empty v-if="!workspace" description="请从左侧选择患者，或新建前置病历" />
        <section v-else-if="!workflowSelected" class="workflow-empty-panel">
          <el-empty :image-size="96" description="请选择左侧岗位节点" />
        </section>
        <template v-else>
          <section class="patient-banner">
            <div class="patient-banner__identity">
              <span class="patient-avatar">{{ (workspace.encounter.patient.patientName || "患").slice(0, 1) }}</span>
              <div>
                <small>当前就诊患者</small>
                <h3>
                  {{ workspace.encounter.patient.patientName || "待补姓名" }}
                </h3>
                <p>
                  {{ workspace.encounter.caseToken }} · {{ workspace.encounter.patient.gender || "待补性别" }} ·
                  {{ workspace.encounter.patient.age || "待补年龄" }} ·
                  {{ workspace.encounter.patient.visitDate || "待补就诊时间" }}
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
            <div class="mode-tags" aria-label="填写态和预览态切换">
              <button
                type="button"
                class="mode-pill edit"
                :class="{ active: editorMode === 'EDIT' }"
                @click="editorMode = 'EDIT'"
              >
                填写态
              </button>
              <button
                type="button"
                class="mode-pill preview"
                :class="{ active: editorMode === 'PREVIEW' }"
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
              <section v-if="selectedPanel === 'STAGE'" class="stage-panel">
                <template v-if="selectedStageCode !== 'REVIEW'">
                  <div class="panel-heading">
                    <div>
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
                    v-if="!canModifySelectedStage"
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
                      历史时间轴
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
                      <div class="timeline-facts">
                        <div v-for="entry in nonEmptyEntries(node.inspection)" :key="entry[0]">
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
                          <AttachmentPreviewGallery :attachments="group.items" compact @download="downloadPreAiAttachmentApi" />
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

                  <el-form
                    v-if="selectedStageCode !== 'INSPECTION' || inspectionView === 'CURRENT'"
                    label-position="top"
                    class="stage-form"
                  >
                    <div class="form-grid">
                      <el-form-item
                        v-for="field in visibleStageFields"
                        :key="field.key"
                        :label="field.label"
                        :required="field.required"
                        :class="{ 'span-2': field.span === 2 }"
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
                        />
                        <el-input
                          v-else-if="field.kind === 'textarea'"
                          v-model="stageForms[selectedStageCode][field.key]"
                          type="textarea"
                          :rows="field.rows || 3"
                          :placeholder="field.placeholder"
                          :disabled="isStageFieldDisabled(field)"
                        />
                        <CreatableSelect
                          v-else-if="field.kind === 'select' && field.creatable"
                          v-model="stageForms[selectedStageCode][field.key]"
                          :options="fieldOptions(field)"
                          :placeholder="field.placeholder || `请选择或直接输入${field.label}`"
                          :disabled="isStageFieldDisabled(field)"
                        />
                        <el-select
                          v-else-if="field.kind === 'select'"
                          v-model="stageForms[selectedStageCode][field.key]"
                          clearable
                          filterable
                          default-first-option
                          :placeholder="field.placeholder || `请选择${field.label}`"
                          :disabled="isStageFieldDisabled(field)"
                        >
                          <el-option
                            v-for="option in fieldOptions(field)"
                            :key="option.value"
                            :label="option.label"
                            :value="option.value"
                          />
                        </el-select>
                        <el-select
                          v-else-if="field.kind === 'multi'"
                          v-model="stageForms[selectedStageCode][field.key]"
                          multiple
                          clearable
                          filterable
                          :allow-create="field.creatable || !fieldOptions(field).length"
                          default-first-option
                          :placeholder="field.placeholder || `请选择或输入${field.label}`"
                          :disabled="isStageFieldDisabled(field)"
                        >
                          <el-option
                            v-for="option in fieldOptions(field)"
                            :key="option.value"
                            :label="option.label"
                            :value="option.value"
                          />
                        </el-select>
                        <el-date-picker
                          v-else
                          v-model="stageForms[selectedStageCode][field.key]"
                          :type="field.kind === 'date' ? 'date' : 'datetime'"
                          :value-format="field.kind === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM-DD HH:mm:ss'"
                          :placeholder="`请选择${field.label}`"
                          :disabled="isStageFieldDisabled(field)"
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
                    <el-button v-if="canEditSelectedStage" type="primary" :loading="actionLoading" @click="completeSelectedStage"
                      >完成并交接</el-button
                    >
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
                  :target-versions="targetMedicalRecordVersions"
                  :latest-target-version-id="latestGeneratedTargetVersionId"
                  :latest-export-version-id="latestGeneratedExportVersionId"
                  :deleting-target-version-id="deletingTargetVersionId"
                  @refresh="loadReviewPreview"
                  @confirm="confirmReview"
                  @generate="generateExport"
                  @generate-target="generateTargetMedicalRecord"
                  @download="downloadPreAiExportApi"
                  @download-target="downloadMedicalRecordApi"
                  @delete-target="deleteTargetMedicalRecord"
                />
              </section>

              <section v-else class="auxiliary-stack">
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
            </div>
          </Transition>
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
        <RegistrationFormFields :fields="registrationFields" :form="createForm" @patch="patchCreateForm" />
        <el-alert
          title="提交后会完成前台登记、生成号码并进入检查候诊；检查完成后沿用原号码自动转入接诊。"
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

    <el-dialog
      v-model="inpatientAiDialogVisible"
      title="GPT 兼容模型生成住院病历"
      width="720px"
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
          title="请上传本次生成使用的 DOCX 参考文档。模型只依据已复核前置事实和本次上传内容生成新的住院病历草稿，参考文档不会被保存。"
        />
        <div class="inpatient-ai-dialog__reference">
          <span class="inpatient-ai-dialog__label">本次参考文档（必选）</span>
          <input
            ref="inpatientAiReferenceInput"
            class="inpatient-ai-dialog__file-input"
            type="file"
            accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            :disabled="inpatientAiGenerating"
            @change="handleInpatientAiReferenceChange"
          />
          <div class="inpatient-ai-dialog__file-actions">
            <el-button :icon="Upload" :disabled="inpatientAiGenerating" @click="openInpatientAiReferencePicker">
              {{ inpatientAiReferenceDocument ? "替换 DOCX" : "选择 DOCX" }}
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
          <p>仅支持 DOCX，单个文件不超过 10 MB。文件只用于当前生成请求，关闭对话框后会清空选择。</p>
        </div>
        <label class="inpatient-ai-dialog__label" for="inpatient-ai-prompt">医生补充说明（可选）</label>
        <el-input
          id="inpatient-ai-prompt"
          v-model="inpatientAiPrompt"
          type="textarea"
          :rows="8"
          maxlength="4000"
          show-word-limit
          resize="vertical"
          :disabled="inpatientAiGenerating"
        />
        <p>Base URL、API Key 与模型由后台“病历 AI”配置读取，不会传到浏览器或写入病历文件。</p>
      </div>
      <template #footer>
        <el-button :disabled="inpatientAiGenerating" @click="closeInpatientAiDialog">取消 AI 加工</el-button>
        <el-button
          type="primary"
          :loading="inpatientAiGenerating"
          :disabled="!inpatientAiReferenceDocument"
          @click="completeInpatientAiGeneration"
        >
          完成并生成
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="inpatientAiResultDialogVisible"
      title="GPT 兼容模型已生成目标住院病历"
      width="860px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div class="inpatient-ai-result">
        <el-alert
          type="success"
          :closable="false"
          show-icon
          title="目标内容已生成并保存为新的病历草稿版本，请复制或下载后继续医生复核。"
        />
        <div class="inpatient-ai-result__meta">
          <span>模型：{{ inpatientAiResultModel || "已配置 GPT 兼容模型" }}</span>
          <span v-if="inpatientAiResultRecord">版本：V{{ inpatientAiResultRecord.version }}</span>
        </div>
        <el-input
          v-model="inpatientAiResultContent"
          type="textarea"
          :rows="18"
          readonly
          resize="vertical"
          aria-label="GPT 兼容模型生成的目标住院病历内容"
        />
      </div>
      <template #footer>
        <el-button @click="inpatientAiResultDialogVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="!inpatientAiResultContent" @click="copyInpatientAiResult"> 复制全部内容 </el-button>
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
  </div>
</template>

<script setup lang="ts" name="preAiEncounters">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { FolderOpened, Plus, Refresh, Search, Upload, User } from "@element-plus/icons-vue";
import { useAuthStore } from "@/stores/modules/auth";
import { useUserStore } from "@/stores/modules/user";
import { useRoute, useRouter } from "vue-router";
import { roleLabel } from "@/config/fieldPermissions";
import {
  deleteMedicalRecordApi,
  downloadMedicalRecordApi,
  generateInpatientAiMedicalRecordApi,
  generateMedicalRecordApi,
  getGeneratedMedicalRecordVersionsApi
} from "@/api/modules/clinic/medicalRecord";
import { completeQueuePrintTaskApi, createQueuePrintTaskApi } from "@/api/modules/clinic/clinicQueue";
import {
  completePreAiStageApi,
  correctPreAiStageApi,
  completePreAiLabApi,
  confirmPreAiReviewApi,
  registerAndIssuePreAiEncounterApi,
  registerAndIssuePreAiFollowUpApi,
  downloadPreAiAttachmentApi,
  downloadPreAiExportApi,
  generatePreAiExportApi,
  getPreAiAttachmentObjectUrlApi,
  getPreAiEncounterHistoryApi,
  getPreAiInspectionTimelineApi,
  getPreAiPatientCasesApi,
  getPatientListApi,
  getPreAiReviewPreviewApi,
  getPreAiReadOnlyWorkspaceApi,
  getPreAiWorkspaceApi,
  importLegacyPreAiEncounterApi,
  returnPreAiAuxiliaryTaskApi,
  returnPreAiStageApi,
  savePreAiDutyAssignmentsApi,
  savePreAiStageApi,
  uploadPreAiAttachmentApi,
  voidPreAiAttachmentApi,
  type PatientRow,
  type GeneratedMedicalRecord,
  type InspectionTimelineNode,
  type PreAiAttachment,
  type PreAiDutyAssignment,
  type PreAiDutyCode,
  type PreAiEncounterStatus,
  type PreAiEncounterHistoryItem,
  type PreAiEncounterSummary,
  type PreAiExportVersion,
  type PreAiPatientCase,
  type PreAiReviewPreview,
  type PreAiStageCode,
  type PreAiStageStatus,
  type PreAiWorkspace
} from "@/api/modules/clinic";
import WorkflowSidebar, { type WorkflowCard } from "./components/WorkflowSidebar.vue";
import MedicalRecordPreview from "./components/MedicalRecordPreview.vue";
import LabReportPanel from "./components/LabReportPanel.vue";
import DoctorReviewPanel from "./components/DoctorReviewPanel.vue";
import AuxiliaryTaskPanel from "./components/AuxiliaryTaskPanel.vue";
import DutyAssignmentPanel from "./components/DutyAssignmentPanel.vue";
import StructuredField from "./components/StructuredField.vue";
import CreatableSelect from "./components/CreatableSelect.vue";
import RegistrationFormFields from "./components/RegistrationFormFields.vue";
import EncounterHistoryPanel from "./components/EncounterHistoryPanel.vue";
import AttachmentPreviewGallery from "./components/AttachmentPreviewGallery.vue";
import { getLocalPrintAgentStatus, printQueueTicketLocally } from "../../clinicQueue/printAgent";
import {
  auxiliaryTaskLabel,
  encounterStatusLabel,
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
  buildHandoffText,
  buildInspectionConclusion,
  buildPresentIllnessText,
  buildProcedureSteps,
  buildSurgeryFindings,
  buildSyndromeBasis,
  buildTreatmentPlan,
  stableSourceHash
} from "./utils/templateTextGenerator";

const userStore = useUserStore();
const authStore = useAuthStore();
const route = useRoute();
const router = useRouter();
const currentRole = computed(() => userStore.userInfo.role || "");
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
const canMaintainDuties = computed(
  () => hasCapability("preai:duties:manage") || hasAssignedDuty("FRONT_DESK", "ATTENDING_DOCTOR", "FINAL_REVIEW_DOCTOR")
);
const canConfirmSurgery = computed(() => hasCapability("preai:surgery:confirm") || hasAssignedDuty("SURGEON"));

const encounters = ref<PreAiEncounterSummary[]>([]);
const patientCases = ref<PreAiPatientCase[]>([]);
const keyword = ref("");
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
const activeLabReportId = ref("");
const attachmentUpload = reactive({
  total: 0,
  success: 0,
  failed: 0,
  percent: 0
});
const selectedPanel = ref<"STAGE" | "AUX">("STAGE");
const selectedStageCode = ref<PreAiStageCode>("REGISTRATION");
const workflowSelected = ref(false);
const editorMode = ref<"EDIT" | "PREVIEW">("EDIT");
const inspectionView = ref<"CURRENT" | "HISTORY">("CURRENT");
const inspectionTimeline = ref<InspectionTimelineNode[]>([]);
const timelineLoading = ref(false);
const workspaceImageUrls = reactive<Record<string, string>>({});
const stageForms = reactive<Record<PreAiStageCode, Record<string, any>>>({
  REGISTRATION: {},
  INSPECTION: {},
  RECEPTION: {},
  TCM: {},
  DOCTOR: {},
  SURGERY: {},
  REVIEW: {}
});
const auxForms = reactive<Record<string, { title: string; requiredBeforeExport: boolean; data: Record<string, any> }>>({});
const reviewPreview = ref<PreAiReviewPreview>();
const reviewStatement = ref("");
const criticalAcknowledged = ref(false);
const targetMedicalRecordVersions = ref<GeneratedMedicalRecord[]>([]);
const targetVersionsLoading = ref(false);
const deletingTargetVersionId = ref("");
const latestGeneratedTargetVersionId = ref("");
const latestGeneratedExportVersionId = ref("");
const inpatientAiDialogVisible = ref(false);
const inpatientAiGenerating = ref(false);
const inpatientAiPrompt = ref("");
const inpatientAiReferenceDocument = ref<File>();
const inpatientAiReferenceInput = ref<HTMLInputElement>();
const pendingGeneratedTargetRecord = ref<GeneratedMedicalRecord>();
const inpatientAiResultDialogVisible = ref(false);
const inpatientAiResultContent = ref("");
const inpatientAiResultModel = ref("");
const inpatientAiResultRecord = ref<GeneratedMedicalRecord>();
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
  visitDate: currentLocalDateTime()
});
const patchCreateForm = (key: string, value: any) => {
  createForm[key] = value;
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

const filteredPatientCases = computed(() => {
  const value = keyword.value.trim().toLowerCase();
  if (!value) return patientCases.value;
  return patientCases.value.filter(item =>
    `${item.patientName} ${item.latestEncounter?.caseToken || ""}`.toLowerCase().includes(value)
  );
});
const selectedPatientCase = computed(() => patientCases.value.find(item => item.id === selectedPatientCaseId.value));
const canHandleStage = (stageCode: PreAiStageCode, ...duties: PreAiDutyCode[]) =>
  Boolean(authStore.stagePermissions[stageCode]?.editable) || hasAssignedDuty(...duties);
const workflowCards = computed<WorkflowCard[]>(() => [
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
    editable: canHandleStage("INSPECTION", "INSPECTION_DOCTOR")
  },
  {
    key: "RECEPTION",
    order: 3,
    kind: "STAGE",
    stageCode: "RECEPTION",
    title: "接诊评估",
    owner: "接诊室",
    editable: canHandleStage("RECEPTION", "RECEPTION_DOCTOR", "ATTENDING_DOCTOR")
  },
  {
    key: "AUX",
    order: 4,
    kind: "AUX",
    title: "化验室",
    owner: "检验报告模板填写与交接",
    editable: canOpenLabWorkbench.value
  },
  {
    key: "TCM",
    order: 5,
    kind: "STAGE",
    stageCode: "TCM",
    title: "中医辨证",
    owner: "中医岗位",
    editable: canHandleStage("TCM", "TCM_DOCTOR")
  },
  {
    key: "DOCTOR",
    order: 6,
    kind: "STAGE",
    stageCode: "DOCTOR",
    title: "医生诊疗方案",
    owner: "医生",
    editable: canHandleStage("DOCTOR", "ATTENDING_DOCTOR")
  },
  {
    key: "SURGERY",
    order: 7,
    kind: "STAGE",
    stageCode: "SURGERY",
    title: "手术结果登记",
    owner: "手术室护士",
    editable: canHandleStage("SURGERY", "SURGEON", "OPERATING_ROOM_NURSE")
  },
  {
    key: "REVIEW",
    order: 8,
    kind: "STAGE",
    stageCode: "REVIEW",
    title: "医生最终复核",
    owner: "医生",
    editable: canReview.value
  }
]);
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
const registrationFields = computed(() => stageByCode("REGISTRATION").fields.filter(field => field.key !== "visitNo"));
const labTask = computed(() => workspace.value?.auxiliaryTasks.find(task => task.taskType === "LAB"));
const legacyAuxiliaryTasks = computed(() => workspace.value?.auxiliaryTasks.filter(task => task.taskType !== "LAB") || []);
const activeWorkflowTitle = computed(() =>
  selectedPanel.value === "AUX" ? "化验室检验报告" : stageByCode(selectedStageCode.value).title
);
const activeWorkflowOwner = computed(() =>
  selectedPanel.value === "AUX" ? "责任岗位：化验室" : `责任岗位：${stageByCode(selectedStageCode.value).owner}`
);
const stageDutyCodes: Partial<Record<PreAiStageCode, PreAiDutyCode[]>> = {
  REGISTRATION: ["FRONT_DESK"],
  INSPECTION: ["INSPECTION_DOCTOR"],
  RECEPTION: ["RECEPTION_DOCTOR", "ATTENDING_DOCTOR"],
  TCM: ["TCM_DOCTOR"],
  DOCTOR: ["ATTENDING_DOCTOR"],
  SURGERY: ["SURGEON", "OPERATING_ROOM_NURSE"],
  REVIEW: ["FINAL_REVIEW_DOCTOR", "ATTENDING_DOCTOR"]
};
const canEditSelectedStage = computed(() => {
  if (!workspace.value || selectedStageCode.value === "REVIEW") return false;
  const submission = selectedStageSubmission.value;
  const roleAllowed = Boolean(authStore.stagePermissions[selectedStageCode.value]?.editable);
  const dutyAllowed = hasAssignedDuty(...(stageDutyCodes[selectedStageCode.value] || []));
  if (!roleAllowed && !dutyAllowed) return false;
  return !["COMPLETED", "SKIPPED"].includes(submission.status);
});
const canCorrectSelectedStage = computed(
  () =>
    Boolean(authStore.stagePermissions[selectedStageCode.value]?.correctable) &&
    ["COMPLETED", "SKIPPED"].includes(selectedStageSubmission.value?.status || "") &&
    selectedStageCode.value !== "REVIEW"
);
const canModifySelectedStage = computed(() => canEditSelectedStage.value || canCorrectSelectedStage.value);
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
  REGISTRATION: ["patientName", "gender", "age", "visitDate"],
  INSPECTION: ["diseaseDirections", "examinationTypes", "factualConclusion"],
  RECEPTION: ["chiefComplaint", "symptomDuration", "presentIllness"],
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
    case "inspectionConclusion":
      return buildInspectionConclusion(form);
    case "syndromeBasis":
      return buildSyndromeBasis(form);
    case "diagnosisBasis":
      return buildDiagnosisBasis(form);
    case "treatmentPlan":
      return buildTreatmentPlan(form);
    case "surgeryFindings":
      return buildSurgeryFindings(form);
    case "procedureSteps":
      return buildProcedureSteps(form);
    case "handoff":
      return buildHandoffText(form);
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
const patchStageForm = (code: PreAiStageCode, value: Record<string, any>) => Object.assign(stageForms[code], value);
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
  status === "COMPLETED" ? "success" : status === "RETURNED" ? "warning" : status === "SKIPPED" ? "info" : "info";
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
  const keepInspectionImagesVisible =
    workspace.value?.encounter.id === value.encounter.id &&
    (editorMode.value === "PREVIEW" ||
      (workflowSelected.value && selectedPanel.value === "STAGE" && selectedStageCode.value === "RECEPTION"));
  syncWorkspaceImageContext(value);
  syncTimelineContext(value);
  workspace.value = value;
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
    Object.keys(stageForms[stage.stageCode]).forEach(key => delete stageForms[stage.stageCode][key]);
    Object.assign(stageForms[stage.stageCode], normalized);
  });
  Object.keys(auxForms).forEach(key => delete auxForms[key]);
  value.auxiliaryTasks.forEach(task => {
    auxForms[task.id] = {
      title: task.title,
      requiredBeforeExport: task.requiredBeforeExport,
      data: deepCopy(task.data)
    };
  });
  if (!value.labReports.some(report => report.id === activeLabReportId.value)) {
    activeLabReportId.value = value.labReports[0]?.id || "";
  }
  if (keepInspectionImagesVisible) void loadWorkspaceInspectionImages(value);
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
  selectedPatientCaseId.value = patientCase.id;
  patientDrawerOpen.value = false;
  if (!patientCase.latestEncounter) return;
  await selectEncounter(patientCase.latestEncounter.id);
};

const togglePatientDrawer = () => {
  patientDrawerOpen.value = !patientDrawerOpen.value;
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

const saveSelectedStage = async () =>
  runAction(async () => {
    const { data } = await savePreAiStageApi(
      selectedEncounterId.value,
      selectedStageCode.value,
      cleanStageForm(selectedStageCode.value),
      stageSubmission(selectedStageCode.value)?.version ?? 0
    );
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
      const { data } = await correctPreAiStageApi(
        selectedEncounterId.value,
        selectedStageCode.value,
        cleanStageForm(selectedStageCode.value),
        stageSubmission(selectedStageCode.value)?.version ?? 0,
        value.trim()
      );
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
    const { data } = await completePreAiStageApi(
      selectedEncounterId.value,
      selectedStageCode.value,
      cleanStageForm(selectedStageCode.value),
      stageSubmission(selectedStageCode.value)?.version ?? 0
    );
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
    ElMessage.success("化验室已完成并交接");
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

const uploadAttachments = async (event: Event, stageCode?: PreAiStageCode, taskId?: string, folderMode = false) => {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  if (!files.length) return;
  const encounterId = selectedEncounterId.value;
  const stageDraft = stageCode ? deepCopy(stageForms[stageCode]) : undefined;
  const timestamp = Date.now();
  const folderName = files[0]?.webkitRelativePath?.split("/")[0] || "";
  const batchId = `pre-att-${timestamp}`;
  const batchName = folderMode
    ? folderName || `检查室文件夹-${timestamp}`
    : files.length > 1
      ? `批量附件-${timestamp}`
      : files[0].name;
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
      if (stageCode && stageDraft) {
        Object.keys(stageForms[stageCode]).forEach(key => delete stageForms[stageCode][key]);
        Object.assign(stageForms[stageCode], stageDraft);
      }
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

const deleteTargetMedicalRecord = async (version: GeneratedMedicalRecord) => {
  if (version.status === "finalized") {
    ElMessage.warning("已定稿目标病历不可删除；如需更正，请先按病历管理流程作废");
    return;
  }

  try {
    await ElMessageBox.confirm(
      `确认删除目标病历 V${version.version} 及对应 Word 文件吗？此操作不可恢复。`,
      "删除历史病历",
      {
        type: "warning",
        confirmButtonText: "确认删除",
        cancelButtonText: "取消"
      }
    );
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

const buildInpatientAiPrompt = () => "";

const formatFileSize = (size: number) => {
  if (size < 1024 * 1024) return `${Math.max(1, Math.round(size / 1024))} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
};

const clearInpatientAiReference = () => {
  inpatientAiReferenceDocument.value = undefined;
  if (inpatientAiReferenceInput.value) inpatientAiReferenceInput.value.value = "";
};

const openInpatientAiReferencePicker = () => inpatientAiReferenceInput.value?.click();

const handleInpatientAiReferenceChange = (event: Event) => {
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
  inpatientAiReferenceDocument.value = file;
};

const closeInpatientAiDialog = () => {
  if (inpatientAiGenerating.value) return;
  inpatientAiDialogVisible.value = false;
  pendingGeneratedTargetRecord.value = undefined;
  clearInpatientAiReference();
  ElMessage.info("已取消 AI 加工，基础目标病历仍保留在版本列表中");
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

const completeInpatientAiGeneration = async () => {
  const encounterId = selectedEncounterId.value;
  const sourceRecord = pendingGeneratedTargetRecord.value;
  const referenceDocument = inpatientAiReferenceDocument.value;
  if (!encounterId || !sourceRecord) return;
  if (!referenceDocument) {
    ElMessage.warning("请先选择本次生成使用的 DOCX 参考文档");
    return;
  }
  inpatientAiGenerating.value = true;
  try {
    const { data } = await generateInpatientAiMedicalRecordApi({
      encounterId,
      sourceRecordId: sourceRecord.id,
      prompt: inpatientAiPrompt.value.trim(),
      referenceDocument
    });
    latestGeneratedTargetVersionId.value = data.record.id;
    latestGeneratedExportVersionId.value = "";
    inpatientAiResultContent.value = data.generatedContent || data.record.content || "";
    inpatientAiResultModel.value = data.model || data.record.model || "";
    inpatientAiResultRecord.value = data.record;
    inpatientAiDialogVisible.value = false;
    pendingGeneratedTargetRecord.value = undefined;
    clearInpatientAiReference();
    inpatientAiResultDialogVisible.value = true;
    await loadTargetMedicalRecordVersions();
    ElMessage.success(`AI 住院病历 V${data.record.version} 已生成，可复制内容或下载复核`);
  } catch (error: any) {
    ElMessage.error(error.message || "AI 住院病历生成失败，提示词已保留，可重试");
  } finally {
    inpatientAiGenerating.value = false;
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
      pendingGeneratedTargetRecord.value = data.record;
      inpatientAiPrompt.value = buildInpatientAiPrompt();
      clearInpatientAiReference();
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
    ElMessage.error(error.message || "操作失败");
  } finally {
    actionLoading.value = false;
  }
};

const cleanupTransientResources = () => {
  workspaceAbortController?.abort();
  workspaceAbortController = undefined;
  workspaceRequestSequence += 1;
  workspaceLoading.value = false;
  pendingWorkflowSelection.value = undefined;
  resetWorkspaceImageContext();
  resetTimelineContext();
  cancelReviewRequest();
  targetVersionsRequestSequence += 1;
  targetVersionsLoading.value = false;
  historyAbortController?.abort();
  historyAbortController = undefined;
  historyRequestSequence += 1;
  historyLoading.value = false;
};

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
  stopHistoryPointerResize?.();
  historyResizeObserver?.disconnect();
  historyResizeObserver = undefined;
});
</script>

<style scoped lang="scss">
.inpatient-ai-dialog {
  display: grid;
  gap: 16px;

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
.encounter-sidebar,
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
  padding: 20px 24px;
  background: linear-gradient(135deg, color-mix(in srgb, var(--el-color-primary) 10%, var(--el-bg-color)), var(--el-bg-color));
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
}
.panel-heading h3 {
  margin: 0;
}
.heading-tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
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
.workspace-shell {
  position: relative;
  display: grid;
  grid-template-columns: 250px minmax(0, 1fr);
  gap: 14px;
  padding-left: 64px;
  flex: 1;
  min-height: 650px;
}
.workspace-shell.with-history {
  grid-template-columns: 250px minmax(520px, 1fr) 8px minmax(360px, var(--history-pane-width, 410px));
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
.encounter-sidebar {
  position: absolute;
  z-index: 20;
  inset: 0 auto 0 0;
  width: 54px;
  min-height: 650px;
  padding: 14px 14px 14px 68px;
  overflow: hidden;
  box-sizing: border-box;
  transition:
    width 0.24s ease,
    box-shadow 0.24s ease;
}
.encounter-sidebar.expanded {
  width: 310px;
  box-shadow: 12px 12px 34px rgb(31 78 120 / 18%);
}
.encounter-sidebar .sidebar-title,
.encounter-sidebar :deep(.el-scrollbar) {
  width: 228px;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.16s ease;
}
.encounter-sidebar.expanded .sidebar-title,
.encounter-sidebar.expanded :deep(.el-scrollbar) {
  opacity: 1;
  pointer-events: auto;
}
.patient-drawer-rail {
  position: absolute;
  inset: 12px auto 12px 7px;
  width: 40px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 9px;
  padding: 12px 5px;
  color: var(--el-color-primary);
  border: 0;
  border-radius: 13px;
  background: var(--el-color-primary-light-9);
  cursor: pointer;
}
.patient-drawer-rail span {
  max-height: 130px;
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  writing-mode: vertical-rl;
  text-overflow: ellipsis;
}
.patient-drawer-mask {
  display: none;
}
.sidebar-title {
  display: grid;
  gap: 10px;
  margin-bottom: 12px;
}
.sidebar-title__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.sidebar-title__head :deep(.el-button) {
  margin-left: 0;
}
.sidebar-title strong {
  font-size: 17px;
}
.encounter-card {
  width: 100%;
  display: grid;
  gap: 7px;
  margin-bottom: 9px;
  padding: 13px;
  text-align: left;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  background: var(--el-fill-color-blank);
  cursor: pointer;
  transition: 0.2s;
}
.encounter-card:hover,
.encounter-card.active {
  border-color: var(--el-color-primary);
  transform: translateY(-1px);
  box-shadow: 0 8px 20px rgb(64 158 255 / 12%);
}
.encounter-card.active {
  background: color-mix(in srgb, var(--el-color-primary) 6%, var(--el-bg-color));
}
.encounter-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}
.encounter-card span {
  color: var(--el-text-color-regular);
}
.encounter-card small {
  color: var(--el-text-color-secondary);
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
  gap: 20px;
  padding: 16px 18px;
  margin-bottom: 10px;
  overflow: hidden;
  border-color: var(--el-border-color-light);
  background: var(--el-bg-color);
}
.patient-banner__identity {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 13px;
}
.patient-avatar {
  width: 46px;
  height: 46px;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  color: var(--el-color-primary);
  font-size: 20px;
  font-weight: 800;
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 14px;
  background: var(--el-color-primary-light-9);
}
.patient-banner__identity small,
.context-stat small {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.patient-banner h3 {
  margin: 2px 0 4px;
  font-size: 22px;
  line-height: 1.2;
}
.patient-banner p {
  margin: 0;
  color: var(--el-text-color-secondary);
}
.patient-banner__overview {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}
.context-stat {
  min-width: 76px;
  display: grid;
  gap: 2px;
  padding: 8px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-light);
}
.context-stat strong {
  color: var(--el-color-primary);
  font-size: 18px;
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
  gap: 14px;
  padding: 11px 15px;
  margin-bottom: 10px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  background: var(--el-bg-color);
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
  display: flex;
  gap: 5px;
  padding: 4px;
  border-radius: 999px;
  background: var(--el-fill-color-light);
}
.mode-pill {
  min-width: 104px;
  padding: 8px 16px;
  font-weight: 700;
  border: 0;
  border-radius: 999px;
  cursor: pointer;
  user-select: none;
  transition:
    color 0.2s ease,
    background-color 0.2s ease,
    box-shadow 0.2s ease,
    transform 0.2s ease;
}
.mode-pill:hover {
  transform: translateY(-1px);
}
.mode-pill.active {
  transform: translateY(-1px);
}
.editor-mode-content {
  min-width: 0;
}
.auxiliary-stack {
  display: grid;
  gap: 16px;
}
.workspace-mode-enter-active,
.workspace-mode-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}
.workspace-mode-enter-from {
  opacity: 0;
  transform: translateY(8px);
}
.workspace-mode-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
.mode-pill.edit {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.mode-pill.edit.active {
  color: white;
  background: var(--el-color-primary);
  box-shadow: 0 6px 16px rgb(64 158 255 / 24%);
}
.mode-pill.preview {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}
.mode-pill.preview.active {
  color: white;
  background: var(--el-color-success);
  box-shadow: 0 6px 16px rgb(103 194 58 / 24%);
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
  gap: 12px;
  margin-top: 18px;
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
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  background: var(--el-bg-color);
}
.upstream-stage-card > header {
  padding: 14px 16px 10px;
}
.upstream-summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  padding: 0 16px 14px;
}
.upstream-summary-grid > div {
  min-width: 0;
  display: grid;
  gap: 5px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
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
  border-top: 1px solid var(--el-border-color-lighter);
  border-bottom: 0;
}
.upstream-detail-collapse :deep(.el-collapse-item__header) {
  height: 44px;
  padding: 0 16px;
  color: var(--el-color-primary);
  font-size: 13px;
}
.read-only-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 20px;
  padding: 2px 16px 16px;
  margin: 0;
}
.read-only-grid > div {
  min-width: 0;
}
.read-only-grid dt {
  margin-bottom: 4px;
}
.read-only-grid dd {
  margin: 0;
  line-height: 1.65;
  white-space: pre-wrap;
}
.upstream-image-section,
.attachment-section {
  display: grid;
  gap: 12px;
  margin-top: 18px;
  padding: 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 14px;
  background: var(--el-fill-color-lighter);
}
.priority-image-section {
  margin-top: 16px;
  border-color: var(--el-color-primary-light-7);
  background: var(--el-color-primary-light-9);
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
    transform 0.2s ease;
}
.upstream-image-card:hover {
  border-color: var(--el-color-primary-light-5);
  transform: translateY(-2px);
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
  .workspace-shell {
    grid-template-columns: 225px minmax(0, 1fr);
  }
  .workspace-shell.with-history {
    grid-template-columns: 225px minmax(0, 1fr);
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
@media (max-width: 680px) {
  .page-hero,
  .patient-banner,
  .panel-heading,
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
    padding-left: 0;
  }
  .encounter-workspace {
    grid-column: auto;
  }
  .encounter-sidebar {
    max-height: none;
  }
  .encounter-sidebar {
    position: fixed;
    z-index: 42;
    top: 80px;
    bottom: 18px;
    left: 10px;
    min-height: 0;
  }
  .encounter-sidebar:not(.expanded) .sidebar-title,
  .encounter-sidebar:not(.expanded) :deep(.el-scrollbar) {
    opacity: 0;
    pointer-events: none;
  }
  .patient-drawer-mask {
    position: fixed;
    z-index: 41;
    inset: 0;
    display: block;
    border: 0;
    background: rgb(0 0 0 / 30%);
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
}
</style>
