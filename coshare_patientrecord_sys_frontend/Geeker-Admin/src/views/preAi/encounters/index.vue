<template>
  <!-- eslint-disable vue/html-closing-bracket-newline -->
  <div class="pre-ai-page">
    <header class="page-hero">
      <div>
        <el-tag type="primary" effect="plain">前置病历</el-tag>
        <h2>病历事实采集</h2>
      </div>
      <div class="hero-actions">
        <el-button :icon="Refresh" @click="loadEncounterList">刷新</el-button>
        <el-button v-if="canImportLegacy" :icon="FolderOpened" @click="openLegacyDialog">导入进行中的旧患者</el-button>
        <el-button v-if="canCreateEncounter" type="primary" :icon="Plus" @click="createDialogVisible = true"
          >新建前置病历</el-button
        >
      </div>
    </header>

    <section class="workspace-shell">
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
        :current-role="currentRole"
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
                <h3>{{ workspace.encounter.patient.patientName || "待补姓名" }}</h3>
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
                    v-if="!canEditSelectedStage"
                    type="info"
                    show-icon
                    :closable="false"
                    :title="
                      selectedStageSubmission.status === 'COMPLETED'
                        ? '本阶段已完成；需要修改时请由医生退回。'
                        : `当前账号为${roleLabel(currentRole)}，本页仅可查看。`
                    "
                  />

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
                      :class="{ latest: index === inspectionTimeline.length - 1 }"
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
                          <div class="timeline-images">
                            <button
                              v-for="attachment in group.items"
                              :key="attachment.id"
                              type="button"
                              class="timeline-image"
                              @click="openTimelineAttachment(attachment)"
                            >
                              <img
                                v-if="timelineImageUrls[attachment.id]"
                                :src="timelineImageUrls[attachment.id]"
                                :alt="attachment.fileName"
                              />
                              <span v-else>{{ attachment.mimeType?.startsWith("image/") ? "加载图片" : "查看文件" }}</span>
                              <small>{{ attachment.fileName }}</small>
                            </button>
                          </div>
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

                  <section
                    v-if="upstreamStages.length && (selectedStageCode !== 'INSPECTION' || inspectionView === 'CURRENT')"
                    class="upstream-section"
                  >
                    <div class="section-caption">上游只读事实</div>
                    <el-collapse>
                      <el-collapse-item
                        v-for="item in upstreamStages"
                        :key="item.stageCode"
                        :title="stageByCode(item.stageCode).title"
                      >
                        <div class="read-only-grid">
                          <div v-for="entry in nonEmptyEntries(item.data)" :key="entry[0]">
                            <span>{{ fieldLabel(item.stageCode, entry[0]) }}</span>
                            <p>{{ humanValue(entry[1]) }}</p>
                          </div>
                        </div>
                      </el-collapse-item>
                    </el-collapse>
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
                        <el-select
                          v-else-if="field.kind === 'select'"
                          v-model="stageForms[selectedStageCode][field.key]"
                          clearable
                          filterable
                          :allow-create="field.creatable"
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

                  <section v-if="selectedStageCode === 'RECEPTION'" class="upstream-image-section">
                    <div class="section-caption">检查室原始图片</div>
                    <div v-if="inspectionImageAttachments.length" class="upstream-image-grid">
                      <button
                        v-for="attachment in inspectionImageAttachments"
                        :key="attachment.id"
                        type="button"
                        class="upstream-image-card"
                        @click="openWorkspaceAttachment(attachment)"
                      >
                        <img
                          v-if="workspaceImageUrls[attachment.id]"
                          :src="workspaceImageUrls[attachment.id]"
                          :alt="attachment.fileName"
                        />
                        <span v-else>点击查看图片</span>
                        <small>{{ attachment.fileName }}</small>
                      </button>
                    </div>
                    <el-empty v-else :image-size="64" description="检查室尚未上传原始图片" />
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
                          <el-button v-if="canEditSelectedStage" link type="danger" @click="voidAttachment(attachment.id)"
                            >作废</el-button
                          >
                        </div>
                      </section>
                      <div v-if="canEditSelectedStage" class="upload-actions">
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
                    <template v-if="queueHandoffMessage">
                      <div class="queue-handoff-result">
                        <span class="queue-handoff-mark">✓</span>
                        <div>
                          <strong>{{ queueHandoffTitle }}</strong>
                          <small>{{ queueHandoffMessage }}</small>
                        </div>
                      </div>
                      <el-button type="primary" @click="returnToQueueWorkbench">返回叫号台处理下一位</el-button>
                    </template>
                    <template v-else>
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
                    </template>
                  </footer>
                </template>

                <DoctorReviewPanel
                  v-else
                  v-model:statement="reviewStatement"
                  v-model:critical-acknowledged="criticalAcknowledged"
                  :preview="reviewPreview"
                  :sections="maskedSections"
                  :can-review="canReview"
                  :loading="actionLoading"
                  :encounter-status="workspace.encounter.status"
                  :exports="workspace.exports"
                  :field-label="reviewFieldLabel"
                  :human-value="humanValue"
                  @refresh="loadReviewPreview"
                  @confirm="confirmReview"
                  @generate="generateExport"
                  @download="downloadPreAiExportApi"
                />
              </section>

              <section v-else class="auxiliary-stack">
                <AuxiliaryTaskPanel
                  :workspace="workspace"
                  :current-role="currentRole"
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
    </section>

    <el-dialog v-model="createDialogVisible" title="新建前置病历" width="760px" destroy-on-close>
      <el-form label-position="top">
        <div class="form-grid dialog-grid">
          <el-form-item
            v-for="field in registrationFields"
            :key="field.key"
            :label="field.label"
            :required="field.required"
            :class="{ 'span-2': field.span === 2 }"
          >
            <el-input v-if="field.kind === 'input'" v-model="createForm[field.key]" :placeholder="field.placeholder" />
            <el-input
              v-else-if="field.kind === 'textarea'"
              v-model="createForm[field.key]"
              type="textarea"
              :rows="field.rows || 3"
            />
            <el-select v-else-if="field.kind === 'select'" v-model="createForm[field.key]" clearable filterable>
              <el-option v-for="option in field.options || []" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
            <el-date-picker
              v-else
              v-model="createForm[field.key]"
              :type="field.kind === 'date' ? 'date' : 'datetime'"
              :value-format="field.kind === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM-DD HH:mm:ss'"
            />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" @click="createEncounter">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="followUpDialogVisible" :title="`新增复诊 · ${followUpPatientCase?.patientName || ''}`" width="680px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="只复制患者基础身份信息；检查、诊断、图片、化验和复核状态均从空白开始。"
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
        <el-button type="primary" :loading="actionLoading" @click="createFollowUp">创建复诊子病历</el-button>
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
import { useUserStore } from "@/stores/modules/user";
import { useRoute, useRouter } from "vue-router";
import { roleLabel } from "@/config/fieldPermissions";
import {
  completePreAiStageApi,
  completePreAiLabApi,
  confirmPreAiReviewApi,
  createPreAiEncounterApi,
  createPreAiFollowUpApi,
  downloadPreAiAttachmentApi,
  downloadPreAiExportApi,
  generatePreAiExportApi,
  getPreAiAttachmentObjectUrlApi,
  getPreAiInspectionTimelineApi,
  getPreAiPatientCasesApi,
  getPatientListApi,
  getPreAiReviewPreviewApi,
  getPreAiWorkspaceApi,
  importLegacyPreAiEncounterApi,
  returnPreAiAuxiliaryTaskApi,
  returnPreAiStageApi,
  savePreAiDutyAssignmentsApi,
  savePreAiStageApi,
  uploadPreAiAttachmentApi,
  voidPreAiAttachmentApi,
  type PatientRow,
  type InspectionTimelineNode,
  type PreAiAttachment,
  type PreAiDutyAssignment,
  type PreAiDutyCode,
  type PreAiEncounterStatus,
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
import {
  auxiliaryTaskFields,
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
const route = useRoute();
const router = useRouter();
const currentRole = computed(() => userStore.userInfo.role || "frontdesk");
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
const canCreateEncounter = computed(() => ["admin", "frontdesk"].includes(currentRole.value));
const canImportLegacy = computed(() => ["admin", "frontdesk", "doctor"].includes(currentRole.value));
const canReview = computed(
  () => ["admin", "doctor"].includes(currentRole.value) || hasAssignedDuty("FINAL_REVIEW_DOCTOR", "ATTENDING_DOCTOR")
);
const canOpenLabWorkbench = computed(
  () => ["admin", "doctor", "lab"].includes(currentRole.value) || hasAssignedDuty("LAB_STAFF", "ATTENDING_DOCTOR")
);
const canCompleteLab = computed(
  () => ["admin", "doctor", "lab"].includes(currentRole.value) || hasAssignedDuty("LAB_STAFF", "ATTENDING_DOCTOR")
);
const canMaintainDuties = computed(
  () =>
    ["admin", "frontdesk", "doctor"].includes(currentRole.value) ||
    hasAssignedDuty("FRONT_DESK", "ATTENDING_DOCTOR", "FINAL_REVIEW_DOCTOR")
);
const canConfirmSurgery = computed(() => ["admin", "doctor"].includes(currentRole.value) || hasAssignedDuty("SURGEON"));

const encounters = ref<PreAiEncounterSummary[]>([]);
const patientCases = ref<PreAiPatientCase[]>([]);
const keyword = ref("");
const selectedPatientCaseId = ref("");
const patientDrawerOpen = ref(false);
const selectedEncounterId = ref("");
const workspace = ref<PreAiWorkspace>();
const workspaceLoading = ref(false);
const actionLoading = ref(false);
const activeLabReportId = ref("");
const attachmentUpload = reactive({ total: 0, success: 0, failed: 0, percent: 0 });
const selectedPanel = ref<"STAGE" | "AUX">("STAGE");
const selectedStageCode = ref<PreAiStageCode>("REGISTRATION");
const workflowSelected = ref(false);
const editorMode = ref<"EDIT" | "PREVIEW">("EDIT");
const inspectionView = ref<"CURRENT" | "HISTORY">("CURRENT");
const inspectionTimeline = ref<InspectionTimelineNode[]>([]);
const timelineLoading = ref(false);
const timelineImageUrls = reactive<Record<string, string>>({});
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
let workspaceRequestSequence = 0;
let workspaceImageRequestSequence = 0;
let timelineRequestSequence = 0;
let reviewRequestSequence = 0;
let workspaceAbortController: AbortController | undefined;
let workspaceImageAbortController: AbortController | undefined;
let timelineAbortController: AbortController | undefined;
let reviewAbortController: AbortController | undefined;
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

const createDialogVisible = ref(false);
const createForm = reactive<Record<string, any>>({ visitDate: new Date().toISOString().slice(0, 10) + " 08:00:00" });
const legacyDialogVisible = ref(false);
const selectedLegacyPatientId = ref("");
const legacyPatients = ref<PatientRow[]>([]);
const followUpDialogVisible = ref(false);
const followUpPatientCase = ref<PreAiPatientCase>();
const followUpForm = reactive<Record<string, any>>({ visitDate: new Date().toISOString().slice(0, 10) + " 08:00:00" });

const filteredPatientCases = computed(() => {
  const value = keyword.value.trim().toLowerCase();
  if (!value) return patientCases.value;
  return patientCases.value.filter(item =>
    `${item.patientName} ${item.latestEncounter?.caseToken || ""}`.toLowerCase().includes(value)
  );
});
const selectedPatientCase = computed(() => patientCases.value.find(item => item.id === selectedPatientCaseId.value));
const workflowCards = computed<WorkflowCard[]>(() => [
  {
    key: "REGISTRATION",
    order: 1,
    kind: "STAGE",
    stageCode: "REGISTRATION",
    title: "前台登记",
    owner: "前台",
    roles: ["admin", "frontdesk"]
  },
  {
    key: "INSPECTION",
    order: 2,
    kind: "STAGE",
    stageCode: "INSPECTION",
    title: "检查室",
    owner: "检查室",
    roles: ["admin", "inspection"]
  },
  {
    key: "RECEPTION",
    order: 3,
    kind: "STAGE",
    stageCode: "RECEPTION",
    title: "接诊评估",
    owner: "接诊室",
    roles: ["admin", "reception", "doctor"]
  },
  {
    key: "AUX",
    order: 4,
    kind: "AUX",
    title: "化验室",
    owner: "检验报告模板填写与交接",
    roles: ["admin", "doctor", "lab"]
  },
  { key: "TCM", order: 5, kind: "STAGE", stageCode: "TCM", title: "中医辨证", owner: "中医岗位", roles: ["admin", "tcm"] },
  {
    key: "DOCTOR",
    order: 6,
    kind: "STAGE",
    stageCode: "DOCTOR",
    title: "医生诊疗方案",
    owner: "医生",
    roles: ["admin", "doctor"]
  },
  {
    key: "SURGERY",
    order: 7,
    kind: "STAGE",
    stageCode: "SURGERY",
    title: "手术结果登记",
    owner: "手术室护士",
    roles: ["admin", "nurse", "nursing"]
  },
  {
    key: "REVIEW",
    order: 8,
    kind: "STAGE",
    stageCode: "REVIEW",
    title: "医生最终复核",
    owner: "医生",
    roles: ["admin", "doctor"]
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
  const roleAllowed = selectedStage.value.roles.includes(currentRole.value);
  const dutyAllowed = hasAssignedDuty(...(stageDutyCodes[selectedStageCode.value] || []));
  if (!roleAllowed && !dutyAllowed) return false;
  return submission.status !== "COMPLETED" || currentRole.value === "admin";
});
const canReturnSelectedStage = computed(
  () =>
    canReview.value &&
    ["COMPLETED", "SKIPPED"].includes(selectedStageSubmission.value?.status || "") &&
    selectedStageCode.value !== "REVIEW"
);
const queueHandoffTitle = computed(() => (selectedStageCode.value === "INSPECTION" ? "检查阶段已完成" : "接诊阶段已完成"));
const queueHandoffMessage = computed(() => {
  if (!workspace.value || selectedStageSubmission.value?.status !== "COMPLETED") return "";
  if (selectedStageCode.value === "INSPECTION") return "患者已自动沿用当前号码进入接诊候诊队列。";
  if (selectedStageCode.value === "RECEPTION") return "本次检查与接诊排队流程已闭环，患者已退出候诊队列。";
  return "";
});
const upstreamStages = computed(() => {
  if (!workspace.value) return [];
  const index = preAiStages.findIndex(stage => stage.code === selectedStageCode.value);
  return workspace.value.stages.filter(item => {
    const stageIndex = preAiStages.findIndex(stage => stage.code === item.stageCode);
    return stageIndex >= 0 && stageIndex < index && nonEmptyEntries(item.data).length;
  });
});
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
  if (!reviewPreview.value) return [];
  const masked = reviewPreview.value.maskedPreview as Record<string, any>;
  const sections: Array<{ title: string; entries: Array<[string, any]> }> = [];
  if (masked.metadata) sections.push({ title: "病例标识及就诊信息", entries: nonEmptyEntries(masked.metadata) });
  if (masked.patient) sections.push({ title: "患者基础信息（已脱敏）", entries: nonEmptyEntries(masked.patient) });
  const stageTitles: Record<string, string> = {
    RECEPTION: "主诉和现病情况",
    INSPECTION: "专科检查事实",
    TCM: "中医四诊和辨证",
    DOCTOR: "西医诊断与治疗方案",
    SURGERY: "实际手术情况"
  };
  Object.entries(masked.stages || {}).forEach(([key, value]) =>
    sections.push({ title: stageTitles[key] || key, entries: nonEmptyEntries(value as Record<string, any>) })
  );
  if (Array.isArray(masked.auxiliaryTasks)) {
    masked.auxiliaryTasks.forEach((task: Record<string, any>, index: number) =>
      sections.push({ title: `辅助检查 ${index + 1}`, entries: nonEmptyEntries(task) })
    );
  }
  if (Array.isArray(masked.labReports)) {
    masked.labReports.forEach((report: Record<string, any>, index: number) =>
      sections.push({ title: `化验报告 ${index + 1}`, entries: nonEmptyEntries(report) })
    );
  }
  if (masked.review) sections.push({ title: "医生复核信息", entries: nonEmptyEntries(masked.review) });
  return sections;
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
  !canEditSelectedStage.value ||
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
const reviewFieldLabel = (key: string) => {
  for (const stage of preAiStages) {
    const field = stage.fields.find(item => item.key === key);
    if (field) return field.label;
  }
  const auxField = Object.values(auxiliaryTaskFields)
    .flat()
    .find(item => item.key === key);
  return (
    auxField?.label ||
    (
      {
        caseToken: "病例标识",
        visitDate: "就诊日期",
        route: "就诊分支",
        treatmentPath: "治疗路径",
        generatedAt: "生成时间",
        reviewerRole: "复核岗位",
        statement: "说明",
        taskType: "检查类型",
        title: "任务名称"
      } as Record<string, string>
    )[key] ||
    key
  );
};
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

const clearTimelineImageUrls = () => {
  Object.values(timelineImageUrls).forEach(url => URL.revokeObjectURL(url));
  Object.keys(timelineImageUrls).forEach(key => delete timelineImageUrls[key]);
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
  clearTimelineImageUrls();
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
    auxForms[task.id] = { title: task.title, requiredBeforeExport: task.requiredBeforeExport, data: deepCopy(task.data) };
  });
  if (!value.labReports.some(report => report.id === activeLabReportId.value)) {
    activeLabReportId.value = value.labReports[0]?.id || "";
  }
  if (keepInspectionImagesVisible) void loadWorkspaceInspectionImages(value);
};

const selectEncounter = async (id: string) => {
  if (id === selectedEncounterId.value && workspaceLoading.value) return;
  if (id === selectedEncounterId.value && workspace.value?.encounter.id === id) return;

  const requestSequence = ++workspaceRequestSequence;
  workspaceAbortController?.abort();
  if (workspace.value?.encounter.id !== id) {
    resetWorkspaceImageContext();
    resetTimelineContext();
    cancelReviewRequest();
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
    workspaceLoaded = true;
    selectedPatientCaseId.value = data.encounter.patientCaseId;
    const pendingSelection = readPendingWorkflowSelection();
    if (pendingSelection?.encounterId === id) {
      activateWorkflowCard(pendingSelection.card);
    } else {
      selectedStageCode.value = data.encounter.currentStage || "REGISTRATION";
      selectedPanel.value = "STAGE";
      workflowSelected.value = false;
    }
    editorMode.value = "EDIT";
    reviewPreview.value = undefined;
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
      const imageAttachments = Array.from(
        new Map(
          data.nodes
            .flatMap(node => node.attachments)
            .filter(item => item.mimeType?.startsWith("image/"))
            .map(attachment => [attachment.id, attachment])
        ).values()
      );
      const attachmentIds = new Set(imageAttachments.map(attachment => attachment.id));
      for (const [attachmentId, url] of Object.entries(timelineImageUrls)) {
        if (attachmentIds.has(attachmentId)) continue;
        URL.revokeObjectURL(url);
        delete timelineImageUrls[attachmentId];
      }

      await runWithConcurrency(imageAttachments, 4, async attachment => {
        if (timelineImageUrls[attachment.id]) return;
        try {
          const url = await getPreAiAttachmentObjectUrlApi(attachment, requestController.signal);
          if (!isCurrentRequest()) {
            URL.revokeObjectURL(url);
            return;
          }
          timelineImageUrls[attachment.id] = url;
        } catch (error: any) {
          if (error?.name !== "AbortError") {
            // 单张历史图片加载失败时保留下载入口，不阻断其余时间轴内容。
          }
        }
      });
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

const openWorkspaceAttachment = async (attachment: PreAiAttachment) => {
  const url = workspaceImageUrls[attachment.id];
  if (url) {
    window.open(url, "_blank", "noopener,noreferrer");
    return;
  }
  await downloadPreAiAttachmentApi(attachment);
};

const openTimelineAttachment = async (attachment: PreAiAttachment) => {
  const url = timelineImageUrls[attachment.id];
  if (url) {
    window.open(url, "_blank", "noopener,noreferrer");
    return;
  }
  await downloadPreAiAttachmentApi(attachment);
};

const timelineAttachmentGroups = (attachments: PreAiAttachment[]) => groupAttachments(attachments);

const paymentStatusLabel = (status?: string) =>
  ({ UNPAID: "未交", PARTIAL: "部分缴费", PAID: "已交", REFUNDED: "退费" })[status || ""] || "未记录";
const hasVisitMeta = (visitMeta: Record<string, any> = {}) => nonEmptyEntries(visitMeta).length > 0;

const openFollowUpDialog = (patientCase: PreAiPatientCase) => {
  followUpPatientCase.value = patientCase;
  Object.keys(followUpForm).forEach(key => delete followUpForm[key]);
  followUpForm.visitDate = new Date().toISOString().slice(0, 10) + " 08:00:00";
  followUpDialogVisible.value = true;
};

const createFollowUp = async () => {
  if (!followUpPatientCase.value || !followUpForm.visitDate) {
    ElMessage.warning("请选择本次来访时间");
    return;
  }
  await runAction(async () => {
    const { visitDate, ...visitMeta } = deepCopy(followUpForm);
    const { data } = await createPreAiFollowUpApi(followUpPatientCase.value!.id, { visitDate, visitMeta });
    followUpDialogVisible.value = false;
    await loadEncounterList();
    selectedPatientCaseId.value = data.encounter.patientCaseId;
    selectedEncounterId.value = data.encounter.id;
    hydrateWorkspace(data);
    workflowSelected.value = false;
    editorMode.value = "EDIT";
    ElMessage.success("复诊子病历已创建，请从岗位卡片开始办理");
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
      cleanStageForm(selectedStageCode.value)
    );
    hydrateWorkspace(data);
    ElMessage.success("阶段草稿已保存");
  });

const completeSelectedStage = async () =>
  runAction(async () => {
    const completedStage = selectedStageCode.value;
    const { data } = await completePreAiStageApi(selectedEncounterId.value, completedStage, cleanStageForm(completedStage));
    hydrateWorkspace(data);
    await loadEncounterList();
    ElMessage.success(
      completedStage === "INSPECTION"
        ? "检查已完成，患者已自动进入接诊候诊"
        : completedStage === "RECEPTION"
          ? "接诊已完成，排队流程已闭环"
          : "本阶段已完成并交接"
    );
  });

const returnToQueueWorkbench = () => router.push("/tcm-pharmacy/clinic-queue/workbench");

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
      const { data } = await returnPreAiStageApi(selectedEncounterId.value, code, value);
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
    const { data } = await createPreAiEncounterApi(deepCopy(createForm));
    createDialogVisible.value = false;
    Object.keys(createForm).forEach(key => delete createForm[key]);
    createForm.visitDate = new Date().toISOString().slice(0, 10) + " 08:00:00";
    await loadEncounterList();
    selectedPatientCaseId.value = data.encounter.patientCaseId;
    selectedEncounterId.value = data.encounter.id;
    hydrateWorkspace(data);
    selectedStageCode.value = "REGISTRATION";
    workflowSelected.value = false;
    editorMode.value = "EDIT";
    ElMessage.success("前置病历已创建");
  });

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
  const query: Record<string, string> = { encounterId: selectedEncounterId.value };
  if (workspace.value?.encounter.sourcePatientId) query.patientId = workspace.value.encounter.sourcePatientId;
  router.push({ path: "/workbench/lab-report", query });
};

const completeLab = async () =>
  runAction(async () => {
    const { data } = await completePreAiLabApi(selectedEncounterId.value);
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
      const { data } = await returnPreAiAuxiliaryTaskApi(selectedEncounterId.value, taskId, value);
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
  const timestamp = Date.now();
  const folderName = files[0]?.webkitRelativePath?.split("/")[0] || "";
  const batchId = `pre-att-${timestamp}`;
  const batchName = folderMode
    ? folderName || `检查室文件夹-${timestamp}`
    : files.length > 1
      ? `批量附件-${timestamp}`
      : files[0].name;
  Object.assign(attachmentUpload, { total: files.length, success: 0, failed: 0, percent: 0 });
  actionLoading.value = true;
  for (const [index, file] of files.entries()) {
    const allowed = file.type.startsWith("image/") || file.type === "application/pdf" || file.name.toLowerCase().endsWith(".pdf");
    if (!allowed || file.size > 50 * 1024 * 1024) {
      attachmentUpload.failed += 1;
      attachmentUpload.percent = Math.round(((index + 1) / files.length) * 100);
      continue;
    }
    try {
      await uploadPreAiAttachmentApi(selectedEncounterId.value, {
        stageCode,
        taskId,
        fileName: file.name,
        contentDataUrl: await readAsDataUrl(file),
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
    const { data } = await getPreAiWorkspaceApi(selectedEncounterId.value);
    hydrateWorkspace(data);
    if (attachmentUpload.success) ElMessage.success(`已上传 ${attachmentUpload.success} 个附件；外部 DOCX 不会包含原图`);
    if (attachmentUpload.failed) ElMessage.warning(`${attachmentUpload.failed} 个文件因格式、大小或上传异常未成功`);
  } finally {
    actionLoading.value = false;
  }
  input.value = "";
};

const readAsDataUrl = (file: File) =>
  new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ""));
    reader.onerror = () => reject(new Error("文件读取失败"));
    reader.readAsDataURL(file);
  });

const voidAttachment = async (attachmentId: string) => {
  await ElMessageBox.confirm("作废后文件不再显示，但保留审计记录。", "作废附件", { type: "warning" });
  await runAction(async () => {
    const { data } = await voidPreAiAttachmentApi(selectedEncounterId.value, attachmentId);
    hydrateWorkspace(data);
    ElMessage.success("附件引用已作废");
  });
};

const loadReviewPreview = async () => {
  const encounterId = selectedEncounterId.value;
  if (!encounterId || !canReview.value) return;
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
    const { data } = await confirmPreAiReviewApi(selectedEncounterId.value, reviewStatement.value, criticalAcknowledged.value);
    hydrateWorkspace(data);
    await loadEncounterList();
    await loadReviewPreview();
    ElMessage.success("医生复核已确认，现在可以生成脱敏 DOCX");
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
    await loadEncounterList();
    try {
      await downloadPreAiExportApi(data.export);
    } catch (error: any) {
      throw new Error(error.message || "文档已生成，但下载失败，请从导出版本列表重试");
    }
    ElMessage.success("脱敏前置资料已生成并下载");
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
};

onMounted(loadEncounterList);
onActivated(() => {
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
</script>

<style scoped lang="scss">
.pre-ai-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: calc(100vh - 120px);
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
.queue-handoff-result {
  display: flex;
  min-width: min(100%, 420px);
  align-items: center;
  gap: 12px;
}
.queue-handoff-result strong,
.queue-handoff-result small {
  display: block;
}
.queue-handoff-result strong {
  color: var(--el-color-success-dark-2);
  font-size: 15px;
}
.queue-handoff-result small {
  margin-top: 3px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.queue-handoff-mark {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  place-items: center;
  border-radius: 50%;
  color: #ffffff;
  background: var(--el-color-success);
  font-size: 20px;
  font-weight: 800;
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
.upstream-image-card img,
.upstream-image-card > span {
  width: 100%;
  height: 120px;
  display: grid;
  place-items: center;
  object-fit: cover;
  color: var(--el-text-color-secondary);
  border-radius: 8px;
  background: var(--el-fill-color);
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
  .page-hero {
    align-items: flex-start;
  }
}
@media (max-width: 680px) {
  .page-hero,
  .patient-banner,
  .panel-heading,
  .history-template-toolbar {
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
  .read-only-grid {
    grid-template-columns: 1fr;
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
  .sticky-actions > .queue-handoff-result {
    display: flex;
    flex: 1 1 100%;
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
