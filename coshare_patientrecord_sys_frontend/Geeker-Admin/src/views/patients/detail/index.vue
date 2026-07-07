<template>
  <!-- eslint-disable vue/html-closing-bracket-newline -->
  <div class="main-box record-workspace">
    <TreeFilter
      v-if="detailWorkspaceMode === 'archive'"
      id="key"
      label="title"
      title="档案章节"
      class="screen-only"
      :data="recordSectionsByRule"
      :default-value="activeSectionKey"
      @change="changeSectionFilter"
    />

    <div class="table-box" v-loading="detailLoading" element-loading-text="正在打开健康档案...">
      <section v-if="detailError" class="detail-error-panel screen-only">
        <div>
          <strong>健康档案暂时打不开</strong>

          <small>{{ detailError }}</small>
        </div>

        <el-button type="primary" plain @click="retryLoadPatientDetail">重试</el-button>
      </section>

      <section v-else-if="isInitialDetailLoading" class="detail-loading-skeleton screen-only">
        <div class="detail-loading-head">
          <el-skeleton-item variant="h1" />

          <el-skeleton-item variant="text" />
        </div>

        <el-skeleton :rows="10" animated />
      </section>

      <section v-show="!isInitialDetailLoading" class="record-workbar screen-only">
        <div class="workbar-main">
          <div class="workbar-title">
            <h2>{{ fieldValues.patientName || "当前患者" }} · {{ recordTitle }}</h2>

            <div class="workbar-meta">
              <span>{{ visitNoLabel }}：{{ fieldValues.visitNo || patientId }}</span>

              <span>{{ currentVisitType }}</span>

              <span>{{ encounterCountLabel }}</span>

              <span>{{ archiveVersion }}</span>

              <span>{{ roleName }}</span>

              <span>附件 {{ currentAttachments.length }} 份</span>
            </div>
          </div>

          <div
            class="workbar-progress"
            :class="{ active: completionPercent > 0, complete: completionPercent >= 100 }"
            aria-label="档案完整度"
          >
            <div>
              <span>已填 {{ completionStats.completed }}/{{ completionStats.total }}</span>

              <strong>{{ completionPercent }}%</strong>
            </div>

            <i><em :style="{ width: `${completionPercent}%` }"></em></i>
          </div>

          <div class="archive-save-status">
            <Transition name="save-status-fade">
              <span v-if="autoSaveStatus === 'saving'" class="save-indicator saving">正在保存...</span>

              <span v-else-if="autoSaveStatus === 'saved'" class="save-indicator saved">
                <i class="save-check" aria-hidden="true"></i>

                {{ lastSavedAt ? `已保存 ${lastSavedAt}` : "已自动保存" }}
              </span>

              <span v-else-if="autoSaveStatus === 'conflict'" class="save-indicator conflict">保存冲突，草稿已保留</span>

              <span v-else-if="autoSaveStatus === 'error'" class="save-indicator error" @click="saveActiveMode">
                {{ lastSaveError || "保存失败，点击重试" }}
              </span>
            </Transition>

            <el-button
              v-if="lastSaveError"
              class="save-error-copy"
              text
              size="small"
              :icon="DocumentCopy"
              @click.stop="copySaveError"
            >
              复制错误
            </el-button>

            <small v-if="autoSaveStatus !== 'saved' && lastSavedAt" class="last-save-note">上次保存 {{ lastSavedAt }}</small>
          </div>
        </div>

        <div class="workbar-actions">
          <el-radio-group v-if="detailWorkspaceMode === 'archive'" v-model="recordViewMode">
            <el-radio-button label="mine">核心工作台</el-radio-button>

            <el-radio-button label="full">{{ canViewFullArchive ? "完整档案" : "岗位范围" }}</el-radio-button>
          </el-radio-group>

          <el-select
            v-if="detailWorkspaceMode === 'archive' && canUseRoleViewFilter"
            v-model="activeRoleView"
            class="role-view-select"
            size="default"
            placeholder="按岗位查看"
          >
            <el-option v-for="item in roleViewOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>

          <PatientFieldSearch
            v-if="detailWorkspaceMode === 'archive'"
            :items="patientFieldSearchItems"
            @select="selectPatientFieldSearchItem"
          />

          <el-button type="primary" plain :loading="saving" @click="saveActiveMode">保存</el-button>

          <el-button v-if="archiveSubmitted" @click="revokeArchive">撤回草稿</el-button>

          <el-button v-else type="primary" @click="submitArchive">提交档案审核</el-button>

          <el-button type="primary" :icon="Printer" @click="openPreviewThenPrint">打印/PDF</el-button>

          <el-dropdown trigger="click" @command="handleMoreAction">
            <el-button>更多操作</el-button>

            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="preview" :icon="View">预览</el-dropdown-item>

                <el-dropdown-item command="upload" :icon="Upload">补充图片</el-dropdown-item>

                <el-dropdown-item command="legacy" :icon="FolderOpened">导入旧共享资料</el-dropdown-item>

                <el-dropdown-item command="quality">档案审核</el-dropdown-item>

                <el-dropdown-item command="timeline" :icon="Clock">操作轨迹</el-dropdown-item>

                <el-dropdown-item command="ai" :disabled="aiSummaryLoading">AI总结</el-dropdown-item>

                <el-dropdown-item command="assistant" :icon="ChatDotRound">患者助手</el-dropdown-item>

                <el-dropdown-item command="medicalRecord" :disabled="medicalRecordLoading">医生目标病历</el-dropdown-item>

                <el-dropdown-item v-if="canUseRoleViewFilter" command="inspection">检查室视图</el-dropdown-item>

                <el-dropdown-item v-if="canUseRoleViewFilter" command="lab">化验报告视图</el-dropdown-item>

                <el-dropdown-item command="copy" :icon="DocumentCopy">复制</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </section>

      <section v-if="autoSaveStatus === 'conflict'" v-show="!isInitialDetailLoading" class="conflict-draft-banner screen-only">
        <div>
          <strong>检测到其他终端已更新</strong>

          <small>当前填写已保存在本机草稿{{ conflictDraftSavedAt ? `（${conflictDraftSavedAt}）` : "" }}，先不要刷新页面。</small>
        </div>

        <el-button plain @click="viewServerLatest">查看最新版本</el-button>

        <el-button type="primary" @click="restoreConflictDraft">恢复本机草稿</el-button>
      </section>

      <section v-show="!isInitialDetailLoading" class="record-context-strip screen-only">
        <article class="context-card fixed">
          <span>固定带入</span>

          <strong>{{ fieldValues.patientName || "未登记姓名" }}</strong>

          <small>
            {{ visitNoLabel }}：{{ fieldValues.visitNo || patientId }} · {{ currentVisitType }} · {{ visitDateLabel }}：{{
              fieldValues.admissionDate || patientInfo?.visitDate || "待补录"
            }}
          </small>
        </article>

        <article class="context-card editable">
          <span>可填写区</span>

          <strong>{{ layeredEditableFields.length }}/{{ myEditableFields.length }} 项显示</strong>

          <small>当前层 {{ fieldLayerLabel }} · 必填待补 {{ myRequiredMissingCount }} 项</small>
        </article>

        <article class="context-card attachments">
          <span>附件区</span>

          <strong>{{ currentAttachments.length }} 份证据</strong>

          <small>{{ validAttachmentCount }} 份可打开 · {{ invalidAttachmentCount }} 份待补源文件</small>
        </article>
      </section>

      <section
        v-if="detailWorkspaceMode === 'archive'"
        v-show="!isInitialDetailLoading"
        class="health-archive-summary screen-only"
      >
        <article>
          <span>当前流转</span>

          <strong>{{ activeLifecycleStage.title }}</strong>

          <small>
            {{ activeLifecycleStage.department }} · 生命周期 {{ lifecycleProgress.completed }}/{{ lifecycleProgress.total }} 环
          </small>
        </article>

        <article>
          <span>下次随访</span>

          <strong>{{ fieldValues.nextFollowupAt || "待安排" }}</strong>

          <small>{{ followupRecords.length }} 条复查记录</small>
        </article>

        <article>
          <span>当前关注</span>

          <strong>{{ archiveFocusSummary }}</strong>

          <small>{{ fieldValues.relationshipRisk || fieldValues.patientConcerns || "暂无风险提示" }}</small>
        </article>

        <article>
          <span>主观反馈</span>

          <strong>{{ fieldValues.patientSatisfaction || "待反馈" }}</strong>

          <small>{{ fieldValues.patientPainLevel || "疼痛程度待记录" }}</small>
        </article>
      </section>

      <section
        v-if="detailWorkspaceMode === 'archive' && workflowHint?.visible"
        v-show="!isInitialDetailLoading"
        class="workflow-hint screen-only"
        :class="`is-${workflowHint?.level || 'info'}`"
      >
        <div>
          <span>下一步</span>

          <strong>{{ workflowHint?.title }}</strong>

          <small>{{ workflowHint?.desc }}</small>
        </div>

        <el-button v-if="firstWorkflowIssue" text @click="focusIssue(firstWorkflowIssue)">定位处理</el-button>
      </section>

      <div v-show="!isInitialDetailLoading" class="detail-workspace-shell screen-only">
        <aside class="detail-side-nav" aria-label="患者详情模块">
          <button type="button" :class="{ active: detailWorkspaceMode === 'flow' }" @click="switchDetailWorkspace('flow')">
            <strong>流程视图</strong>

            <span>岗位卡片与右侧预览</span>
          </button>

          <button
            type="button"
            :class="{ active: detailWorkspaceMode === 'medicalRecord' }"
            @click="switchDetailWorkspace('medicalRecord')"
          >
            <strong>医生目标病历</strong>

            <span>{{ medicalRecordMissingItems.length ? `${medicalRecordMissingItems.length} 项待补` : "多岗位协作填写" }}</span>
          </button>

          <button type="button" :class="{ active: detailWorkspaceMode === 'archive' }" @click="switchDetailWorkspace('archive')">
            <strong>健康管理档案</strong>

            <span>管理、随访、协作字段</span>
          </button>

          <button
            type="button"
            :class="{ active: detailWorkspaceMode === 'attachments' }"
            @click="switchDetailWorkspace('attachments')"
          >
            <strong>检查与附件</strong>

            <span>{{ currentAttachments.length }} 份资料</span>
          </button>

          <button
            type="button"
            :class="{ active: detailWorkspaceMode === 'timeline' }"
            @click="switchDetailWorkspace('timeline')"
          >
            <strong>时间轴</strong>

            <span>{{ patientTimelineEvents.length }} 条追溯记录</span>
          </button>
        </aside>

        <main class="detail-workspace-content">
          <WorkflowRolePreviewPanel
            v-if="detailWorkspaceMode === 'flow'"
            v-model:selected-key="activeWorkflowRoleKey"
            :previews="patientRolePreviews"
            @edit="openWorkflowRolePreviewEdit"
            @open-attachments="openWorkflowRolePreviewAttachments"
            @focus-field="focusWorkflowPreviewField"
          />

          <section v-if="detailWorkspaceMode === 'medicalRecord'" class="doctor-record-workbench">
            <div class="doctor-record-hero">
              <div>
                <span>核心协作病历</span>

                <h3>目标病历协作工作台</h3>

                <p>前台、检查、化验、护理和医生共同维护目标病历字段；健康管理档案保留为完整档案和备用入口。</p>
              </div>

              <div class="doctor-record-progress">
                <strong>{{ medicalRecordCompletionPercent }}%</strong>

                <span>
                  已填 {{ medicalRecordCompletedCount }}/{{ medicalRecordTotalCount }} · 入文档

                  {{ medicalRecordDynamicFieldCount }} · 辅助确认

                  {{ medicalRecordFormOnlyFieldCount }}
                </span>

                <i><em :style="{ width: `${medicalRecordCompletionPercent}%` }"></em></i>
              </div>
            </div>

            <MedicalRecordWorkbench
              variant="inline"
              :loading="medicalRecordLoading"
              :template-status="medicalRecordTemplate"
              :missing-items="medicalRecordMissingItems"
              :unbound-fields="medicalRecordUnboundFields"
              :field-sections="medicalRecordFieldSections"
              v-model:active-sections="medicalRecordActiveSections"
              :field-values="fieldValues"
              :current-role="currentRole"
              :can-generate="canGenerateMedicalRecord"
              :lab-report-values="fieldValues"
              :patient-name="fieldValues.patientName"
              :patient-gender="fieldValues.gender"
              :visit-no="fieldValues.visitNo || patientId"
              :current-record="currentMedicalRecord"
              :versions="medicalRecordVersions"
              :completed-count="medicalRecordCompletedCount"
              :total-count="medicalRecordTotalCount"
              :can-manage-versions="canManageMedicalRecordVersions"
              :is-textarea-field="isMedicalRecordTextareaField"
              :is-field-missing="isMedicalRecordFieldMissing"
              :field-assist-text="medicalRecordFieldAssistText"
              :field-source-hint="medicalRecordArchiveSourceHint"
              :is-select-field="isMedicalRecordSelectField"
              :field-options="medicalRecordFieldOptions"
              :is-date-field="isMedicalRecordDateField"
              :status-label="medicalRecordStatusLabel"
              :status-type="medicalRecordStatusType"
              @update-field="updateMedicalRecordField"
              @precheck="() => precheckMedicalRecord()"
              @save-workspace="() => saveMedicalRecordWorkspace()"
              @sync-from-archive="fillMedicalRecordBlanksFromArchive"
              @generate="generateMedicalRecord"
              @download="openCurrentMedicalRecord"
              @finalize="finalizeMedicalRecord"
              @select-version="selectMedicalRecordVersion"
            />
          </section>

          <AttachmentWorkbench
            v-if="detailWorkspaceMode === 'attachments'"
            :attachments="currentAttachments"
            :can-open-attachment="canOpenAttachment"
            @upload="openSupplementUpload"
            @open="openAttachment"
          />

          <TimelineWorkbench
            v-if="detailWorkspaceMode === 'timeline'"
            :events="patientTimelineEvents"
            :loading="auditLoading"
            :timeline-display-time="timelineDisplayTime"
            :timeline-source-label="timelineSourceLabel"
            @refresh="refreshAuditTimeline"
          />

          <div v-if="detailWorkspaceMode === 'archive'" class="record-layout" :class="`mode-${recordViewMode}`">
            <aside v-if="recordViewMode === 'full' && !showLabReportOverview" class="section-rail screen-only">
              <div class="lifecycle-rail-summary">
                <span>当前流转</span>

                <strong>{{ activeLifecycleStage.title }}</strong>

                <small>{{ lifecycleRailSummary }}</small>
              </div>

              <button
                v-for="(section, index) in recordSectionsByRule"
                :key="section.key"
                type="button"
                class="rail-item"
                :class="{ active: activeSectionKey === section.key }"
                @click="activeSectionKey = section.key"
              >
                <span>{{ index + 1 }}</span>

                <strong>{{ shortTitle(section.title) }}</strong>

                <small
                  >{{ lifecycleStageForSection(section)?.shortTitle || section.department }} · {{ section.department }}</small
                >

                <em v-if="sectionRequiredMissingCount(section)" class="rail-missing-badge">
                  {{ sectionRequiredMissingCount(section) }}
                </em>
              </button>
            </aside>

            <main class="form-panel screen-only">
              <PatientOverviewPanel
                v-if="showPatientOverviewPanel"
                :state="patientWorkflowTasks"
                @focus-field="focusWorkflowTask"
                @focus-attachment="focusWorkflowAttachment"
                @focus-stage="focusWorkflowStage"
              />

              <WorkflowTaskPanel
                v-if="!showLabReportOverview"
                :state="patientWorkflowTasks"
                @focus-field="focusWorkflowTask"
                @focus-attachment="focusWorkflowAttachment"
                @focus-stage="focusWorkflowStage"
              />

              <section v-if="showLabReportOverview" class="lab-report-overview">
                <div class="lab-report-overview-head">
                  <div>
                    <strong>化验报告模板视图</strong>
                    <span>
                      {{ fieldValues.patientName || patientInfo?.name || "当前患者" }} ·
                      {{ fieldValues.visitNo || patientInfo?.visitNo || patientId }} · 最近更新
                      {{ patientInfo?.updatedAt || generatedAt || "待同步" }}
                    </span>
                  </div>
                  <el-button type="primary" plain @click="openLabReportWorkbench">填写/更新检验报告</el-button>
                </div>

                <LabReportPreview
                  :field-values="fieldValues"
                  :patient-name="fieldValues.patientName"
                  :patient-gender="fieldValues.gender"
                  :visit-no="fieldValues.visitNo || patientId"
                />

                <section class="lab-report-supplement">
                  <div>
                    <strong>化验室补充信息</strong>
                    <span>模板报告之外的少量状态和说明仍可在这里查看或补充。</span>
                  </div>
                  <div class="lab-report-supplement-grid">
                    <label>
                      <span>幽门螺杆菌检测</span>
                      <el-select v-model="fieldValues.hpTestStatus" clearable placeholder="选择状态">
                        <el-option label="未查" value="未查" />
                        <el-option label="已查" value="已查" />
                        <el-option label="异常" value="异常" />
                      </el-select>
                    </label>
                    <label class="wide">
                      <span>未查项目说明</span>
                      <el-input
                        v-model="fieldValues.uncheckedItemsNote"
                        :disabled="!canEditLabSupplementNote"
                        type="textarea"
                        :rows="2"
                        placeholder="如需说明暂缓检查、患者原因或医生补查安排，可在这里记录"
                      />
                    </label>
                  </div>
                  <div class="lab-report-supplement-actions">
                    <el-button :loading="saving" @click="saveLabSupplementFields">保存补充信息</el-button>
                    <el-button text @click="switchDetailWorkspace('attachments')">查看附件索引</el-button>
                  </div>
                </section>

                <div class="lab-report-overview-foot">
                  <span>原始图片/报告附件仍保留在“检查与附件”和对应字段证据中。</span>
                  <el-button text @click="switchDetailWorkspace('attachments')">查看附件索引</el-button>
                </div>
              </section>

              <section v-if="recordViewMode === 'mine'" class="my-fields-panel">
                <div class="my-fields-head">
                  <div>
                    <h3>{{ fieldLayerTitle }} · {{ layeredEditableFields.length }} 项</h3>

                    <p>
                      {{ fieldLayerDescription }}
                    </p>
                  </div>

                  <el-tag :type="myRequiredMissingCount ? 'warning' : 'success'" effect="plain">
                    {{ myRequiredMissingCount ? "待处理" : "已就绪" }}
                  </el-tag>
                </div>

                <div class="field-layer-switch">
                  <el-radio-group v-model="fieldLayerMode">
                    <el-radio-button label="core">核心</el-radio-button>

                    <el-radio-button label="recommended">推荐</el-radio-button>

                    <el-radio-button label="optional">按需</el-radio-button>

                    <el-radio-button label="all">全部</el-radio-button>
                  </el-radio-group>

                  <span>
                    核心 {{ fieldLayerStats.core }} · 推荐 {{ fieldLayerStats.recommended }} · 按需 {{ fieldLayerStats.optional }}
                  </span>
                </div>

                <section v-if="showNursingLabReportReference" class="nursing-lab-reference">
                  <div class="nursing-lab-reference-head">
                    <div>
                      <strong>检验报告参考</strong>
                      <span>护士站可查看已录入检验结果；医生最终确认后写入目标病历。</span>
                    </div>
                    <el-button text @click="openLabReportWorkbench">查看检验报告模板</el-button>
                  </div>
                  <LabReportPreview
                    v-if="hasArchiveLabReportData"
                    compact
                    :show-empty="false"
                    :field-values="fieldValues"
                    :patient-name="fieldValues.patientName"
                    :patient-gender="fieldValues.gender"
                    :visit-no="fieldValues.visitNo || patientId"
                  />
                  <el-empty v-else description="暂无检验报告数据" />
                </section>

                <div v-if="myFieldIssues.length" class="field-issue-banner">
                  <div>
                    <strong>先处理 {{ myFieldIssues.length }} 项</strong>

                    <span>{{ myFieldIssues[0].fieldLabel }}：{{ myFieldIssues[0].message }}</span>
                  </div>

                  <el-button text @click="focusIssue(myFieldIssues[0])">定位</el-button>
                </div>

                <el-empty v-if="!layeredEditableFields.length" description="当前层暂无待填字段">
                  <el-button type="primary" @click="recordViewMode = 'full'">查看完整档案</el-button>
                </el-empty>

                <div v-else class="my-field-grid">
                  <div
                    v-for="item in layeredEditableFields"
                    :key="item.field.key"
                    class="my-field-item"
                    :id="`my-field-${item.field.key}`"
                    :class="{
                      wide: item.field.kind === 'textarea' || isLabMetricField(item.field),

                      complete: isRecordFieldComplete(item.field),

                      missing: issueForField(item.field)?.level === 'missing',

                      invalid: issueForField(item.field)?.level === 'invalid',

                      focused: highlightedFieldKey === item.field.key
                    }"
                  >
                    <div class="my-field-label">
                      <label>
                        {{ item.field.label }}

                        <sup v-if="item.field.required">*</sup>
                      </label>

                      <span>{{ shortTitle(item.section.title) }}</span>
                    </div>

                    <ArchiveFieldRenderer
                      :field="item.field"
                      v-model="fieldValues[item.field.key]"
                      :disabled="!isEditable(item.field)"
                      :issue="issueForField(item.field)"
                      :attachments="matchedAttachments(item.field.key)"
                      :select-options="selectOptions(item.field)"
                      :followup-records="followupRecords"
                      :role-label="roleLabel"
                      :can-open-attachment="canOpenAttachment"
                      :is-image-attachment="isImageAttachment"
                      :attachment-preview-url="attachmentPreviewUrl"
                      :open-attachment="openAttachment"
                      :textarea-rows="3"
                      @update-lab-metric="updateLabMetricField"
                      @upload="uploadFieldAttachments"
                      @add-followup-record="addFollowupRecord"
                      @remove-followup-record="removeFollowupRecord"
                    />
                  </div>
                </div>

                <div v-if="layeredEditableFields.length" class="my-fields-footer">
                  <el-button :loading="saving" @click="saveMyFields">保存当前层内容</el-button>

                  <el-button type="primary" :loading="saving" @click="saveMyFieldsAndBack">保存并返回看板</el-button>
                </div>
              </section>

              <div v-if="recordViewMode === 'full' && !showLabReportOverview" class="workspace-anchor">
                <button
                  v-for="(section, index) in recordSectionsByRule"
                  :key="section.key"
                  :class="{
                    active: activeSectionKey === section.key,

                    done: isSectionComplete(section),

                    attention: sectionRequiredMissingCount(section) > 0
                  }"
                  type="button"
                  @click="scrollToSection(section.key)"
                >
                  <span>{{ index + 1 }}</span>

                  {{ shortTitle(section.title) }}

                  <em v-if="sectionRequiredMissingCount(section)">{{ sectionRequiredMissingCount(section) }}</em>
                </button>
              </div>

              <div v-if="recordViewMode === 'full' && !showLabReportOverview" class="all-section-form">
                <section
                  v-for="(section, sectionIndex) in recordSectionsByRule"
                  :id="`record-section-${section.key}`"
                  :key="section.key"
                  class="section-card"
                  :class="{
                    saved: savedSectionKey === section.key,

                    collapsed: isSectionCollapsed(section),

                    attention: sectionRequiredMissingCount(section) > 0,

                    readonly: !canEditRecordSection(section),

                    editable: canEditRecordSection(section)
                  }"
                >
                  <div class="section-card-head">
                    <div>
                      <span class="section-index">{{ sectionIndex + 1 }}</span>

                      <h3>{{ section.title }}</h3>

                      <p>{{ section.description }}</p>
                    </div>

                    <div class="section-head-actions">
                      <el-tag :type="sectionStatusType(section)" effect="plain">
                        {{ sectionStatusLabel(section) }}
                      </el-tag>

                      <el-button
                        v-if="canExpandFullSection(section)"
                        text
                        class="section-fold-button"
                        @click="toggleSectionCollapse(section.key)"
                      >
                        <el-icon :class="{ folded: isSectionCollapsed(section) }"><ArrowDown /></el-icon>

                        {{ isSectionCollapsed(section) ? "展开" : "收起" }}
                      </el-button>
                    </div>
                  </div>

                  <div class="section-summary-line">
                    <span>已填 {{ sectionCompletedCount(section) }}/{{ section.fields.length }}</span>

                    <span v-if="sectionRequiredMissingCount(section)" class="needs-fill">
                      必填待补 {{ sectionRequiredMissingCount(section) }}
                    </span>

                    <span v-else class="is-complete">必填已齐</span>

                    <span v-if="sectionEvidenceCount(section)">附件 {{ sectionEvidenceCount(section) }} 份</span>

                    <span>{{ canEditRecordSection(section) ? "可填写" : "其他岗位" }}</span>

                    <span v-if="sectionSaveTimes[section.key]" class="section-save-time">
                      最后保存 {{ sectionSaveTimes[section.key] }}
                    </span>
                  </div>

                  <div v-if="sectionIssues(section).length" class="section-issue-line">
                    <span>待处理：{{ sectionIssues(section)[0].fieldLabel }} - {{ sectionIssues(section)[0].message }}</span>

                    <el-button text @click="focusIssue(sectionIssues(section)[0])">定位</el-button>
                  </div>

                  <div v-show="canExpandFullSection(section) && !isSectionCollapsed(section)" class="section-fields">
                    <div
                      v-for="recordField in section.fields"
                      :key="recordField.key"
                      :id="`section-field-${recordField.key}`"
                      class="field-row workbench-field-row"
                      :class="{
                        locked: !isEditable(recordField),

                        complete: isRecordFieldComplete(recordField),

                        missing: issueForField(recordField)?.level === 'missing',

                        invalid: issueForField(recordField)?.level === 'invalid',

                        focused: highlightedFieldKey === recordField.key
                      }"
                    >
                      <div class="field-label">
                        <label>{{ recordField.label }}</label>

                        <small>{{ fieldAssistText(recordField) }}</small>
                      </div>

                      <ArchiveFieldRenderer
                        :field="recordField"
                        v-model="fieldValues[recordField.key]"
                        :disabled="!isEditable(recordField)"
                        :issue="issueForField(recordField)"
                        :attachments="matchedAttachments(recordField.key)"
                        :select-options="selectOptions(recordField)"
                        :followup-records="followupRecords"
                        :role-label="roleLabel"
                        :can-open-attachment="canOpenAttachment"
                        :is-image-attachment="isImageAttachment"
                        :attachment-preview-url="attachmentPreviewUrl"
                        :open-attachment="openAttachment"
                        @update-lab-metric="updateLabMetricField"
                        @upload="uploadFieldAttachments"
                        @add-followup-record="addFollowupRecord"
                        @remove-followup-record="removeFollowupRecord"
                      />
                    </div>
                  </div>

                  <div v-show="canExpandFullSection(section) && !isSectionCollapsed(section)" class="section-card-actions">
                    <el-button
                      type="primary"
                      :loading="saving && activeSectionKey === section.key"
                      :disabled="!canEditRecordSection(section)"
                      @click="saveSection(section.key)"
                    >
                      保存本段
                    </el-button>
                  </div>
                </section>
              </div>

              <div class="section-header">
                <div>
                  <h3>{{ activeSection.title }}</h3>

                  <p>{{ activeSection.description }}</p>
                </div>

                <el-tag :type="canEditRecordSection(activeSection) ? 'success' : 'info'" effect="plain">
                  {{ canEditRecordSection(activeSection) ? "本岗位可填写" : "当前只读" }}
                </el-tag>
              </div>

              <div class="compact-form">
                <div
                  v-for="activeField in activeSection.fields"
                  :id="`active-field-${activeField.key}`"
                  :key="activeField.key"
                  class="field-row"
                  :class="{
                    locked: !isEditable(activeField),

                    missing: issueForField(activeField)?.level === 'missing',

                    invalid: issueForField(activeField)?.level === 'invalid',

                    focused: highlightedFieldKey === activeField.key
                  }"
                >
                  <div class="field-label">
                    <label>{{ activeField.label }}</label>

                    <small>{{ fieldAssistText(activeField) }}</small>
                  </div>

                  <ArchiveFieldRenderer
                    :field="activeField"
                    v-model="fieldValues[activeField.key]"
                    :disabled="!isEditable(activeField)"
                    :issue="issueForField(activeField)"
                    :attachments="matchedAttachments(activeField.key)"
                    :select-options="selectOptions(activeField)"
                    :followup-records="followupRecords"
                    :role-label="roleLabel"
                    :can-open-attachment="canOpenAttachment"
                    :is-image-attachment="isImageAttachment"
                    :attachment-preview-url="attachmentPreviewUrl"
                    :open-attachment="openAttachment"
                    @update-lab-metric="updateLabMetricField"
                    @upload="uploadFieldAttachments"
                    @add-followup-record="addFollowupRecord"
                    @remove-followup-record="removeFollowupRecord"
                  />
                </div>
              </div>

              <div class="form-footer">
                <el-button type="success" :loading="saving" @click="saveCurrentSection">保存当前章节</el-button>

                <el-button :disabled="isFirstSection" @click="switchSection(-1)">上一段</el-button>

                <el-button :disabled="isLastSection" type="primary" @click="switchSection(1)">下一段</el-button>
              </div>
            </main>
          </div>
        </main>
      </div>

      <RecordPreviewOverlay
        v-if="previewVisible"
        v-model:visible="previewVisible"
        :title="`${fieldValues.patientName || '当前患者'} · ${fieldValues.visitNo || patientId}`"
        :navigation="previewNavigation"
        :active-page="previewActivePage"
        @print="printRecord"
        @scroll-page="scrollPreviewPage"
      >
        <div class="paper-stack">
          <article class="preview-paper print-cover" :data-preview-page="coverPreviewPage.page">
            <div class="paper-watermark" :data-watermark="archiveWatermark"></div>

            <div class="paper-page-mark">
              <span>档案封面</span>

              <span>第 {{ coverPreviewPage.page }} 页 / 共 {{ previewPageCount }} 页</span>
            </div>

            <div class="medical-record-head cover-head">
              <div class="medical-brand">
                <img v-if="logoVisible" class="medical-logo" :src="logoSrc" alt="医院标识" @error="fallbackLogo" />

                <div class="medical-title-block">
                  <h1>{{ fieldValues.hospitalName }}</h1>

                  <h2>{{ recordTitle }}</h2>
                </div>
              </div>

              <div class="medical-subtitle">{{ recordSubtitle }}</div>

              <p class="archive-scope-note">
                本档案用于院内患者健康管理、治疗跟踪和复查随访，不替代 HIS 官方病历或官方病历质控文书。
              </p>
            </div>

            <div class="cover-patient-card">
              <span v-for="item in paperMeta" :key="item.label">
                <b>{{ item.label }}</b>

                <em>{{ item.value || "____" }}</em>
              </span>
            </div>

            <div class="cover-archive-summary">
              <span>完整度 {{ completionPercent }}%</span>

              <span>附件 {{ currentAttachments.length }} 份</span>

              <span>校验码 {{ recordSignature }}</span>
            </div>

            <div class="paper-footer">
              <span>{{ fieldValues.hospitalName }} · {{ archiveVersion }}</span>

              <span>打印时间 {{ generatedAt }} · 操作人 {{ roleName }}</span>
            </div>
          </article>

          <article class="preview-paper registration-record-page" :data-preview-page="registrationPreviewPage.page">
            <div class="paper-watermark" :data-watermark="archiveWatermark"></div>

            <div class="paper-page-mark">
              <span>健康管理登记表</span>

              <span>第 {{ registrationPreviewPage.page }} 页 / 共 {{ previewPageCount }} 页</span>
            </div>

            <div class="medical-record-head registration-head">
              <div class="medical-brand">
                <img v-if="logoVisible" class="medical-logo" :src="logoSrc" alt="医院标识" @error="fallbackLogo" />

                <div class="medical-title-block">
                  <h1>{{ fieldValues.hospitalName }}</h1>

                  <h2>健康管理登记表</h2>
                </div>
              </div>

              <div class="medical-subtitle">建档信息 · 术前筛查 · 治疗方案 · 分级复诊 · 中医管理闭环</div>
            </div>

            <section class="registration-section">
              <h3>一、基础建档信息</h3>

              <table class="registration-table">
                <tbody>
                  <tr v-for="(row, index) in registrationBasicRows" :key="index">
                    <th>{{ row[0] }}</th>

                    <td>{{ row[1] }}</td>

                    <th>{{ row[2] }}</th>

                    <td>{{ row[3] }}</td>
                  </tr>
                </tbody>
              </table>
            </section>

            <section class="registration-section">
              <h3>二、病情建档</h3>

              <table class="registration-table single">
                <tbody>
                  <tr v-for="row in registrationConditionRows" :key="row[0]">
                    <th>{{ row[0] }}</th>

                    <td>{{ row[1] }}</td>
                  </tr>
                </tbody>
              </table>
            </section>

            <section class="registration-section">
              <h3>三、术前检验筛查汇总</h3>

              <table class="registration-table screening-table">
                <thead>
                  <tr>
                    <th>检验大类</th>

                    <th>结果汇总</th>

                    <th>检查状态</th>
                  </tr>
                </thead>

                <tbody>
                  <tr v-for="row in screeningRows" :key="row[0]">
                    <td>{{ row[0] }}</td>

                    <td>{{ row[1] }}</td>

                    <td>
                      <span class="screening-status" :class="screeningStatusClass(displayFieldValue(row[2], '未查'))">
                        {{ displayFieldValue(row[2], "未查") }}
                      </span>
                    </td>
                  </tr>
                </tbody>
              </table>

              <p class="registration-note">未查项目说明：{{ displayFieldValue("uncheckedItemsNote", "术后按需择期补查") }}</p>
            </section>

            <section class="registration-section compact-grid">
              <div>
                <h3>四、术前医患沟通与治疗方案管理</h3>

                <table class="registration-table single">
                  <tbody>
                    <tr v-for="row in treatmentManagementRows" :key="row[0]">
                      <th>{{ row[0] }}</th>

                      <td>{{ row[1] }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <div>
                <h3>六、中医特色健康管理专栏</h3>

                <table class="registration-table single">
                  <tbody>
                    <tr v-for="row in tcmManagementRows" :key="row[0]">
                      <th>{{ row[0] }}</th>

                      <td>{{ row[1] }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </section>

            <section class="registration-section">
              <h3>五、术后分级复诊健康管理台账</h3>

              <table class="registration-table followup-ledger-table">
                <thead>
                  <tr>
                    <th>复诊层级</th>

                    <th>预约时间</th>

                    <th>复查项目</th>

                    <th>健康管理内容</th>

                    <th>影像归档要求</th>

                    <th>完成状态</th>
                  </tr>
                </thead>

                <tbody>
                  <tr v-for="record in followupRecords" :key="record.id">
                    <td>{{ record.node || record.type }}</td>

                    <td>{{ record.date || "待预约" }}</td>

                    <td>{{ record.project || "待补充" }}</td>

                    <td>{{ record.management || record.recovery || "待补充" }}</td>

                    <td>{{ record.imagingRequirement || "按需归档" }}</td>

                    <td>{{ record.completed || "未完成" }}</td>
                  </tr>
                </tbody>
              </table>
            </section>

            <section class="registration-section">
              <h3>七、备注预留栏</h3>

              <div class="registration-remarks">
                <span>并发症：{{ displayFieldValue("complicationRecord") }}</span>

                <span>用药记录：{{ displayFieldValue("medicationRecord") }}</span>

                <span>依从性：{{ displayFieldValue("patientComplianceRecord") }}</span>
              </div>
            </section>

            <div class="paper-footer">
              <span>{{ fieldValues.patientName }} · {{ fieldValues.visitNo }} · {{ archiveVersion }}</span>

              <span>院内健康管理登记 · 追溯码 {{ recordSignature }}</span>
            </div>
          </article>

          <article
            v-if="clinicalPreviewPage"
            class="preview-paper record-page clinical-record-page"
            :data-preview-page="clinicalPreviewPage.page"
          >
            <div class="paper-watermark" :data-watermark="archiveWatermark"></div>

            <div class="paper-page-mark">
              <span>诊疗检查与治疗记录</span>

              <span>第 {{ clinicalPreviewPage.page }} 页 / 共 {{ previewPageCount }} 页</span>
            </div>

            <div class="medical-record-head module-record-head">
              <div class="medical-brand">
                <img class="medical-logo" src="@/assets/images/logo.jpg" alt="医院标识" />

                <div class="medical-title-block">
                  <h1>{{ fieldValues.hospitalName }}</h1>

                  <h2>诊疗检查与治疗记录</h2>
                </div>
              </div>

              <div class="medical-subtitle">{{ recordSubtitle }}</div>

              <div class="paper-meta medical-meta-table">
                <span v-for="item in paperMeta" :key="item.label">
                  <b>{{ item.label }}：</b><em class="paper-value">{{ item.value }}</em>
                </span>
              </div>
            </div>

            <div class="paper-legend">
              <span class="legend-fixed">固定模板</span>

              <span class="legend-filled">表单生成内容</span>
            </div>

            <div class="paper-archive-card clinical-summary-card">
              <span v-for="item in clinicalArchiveSummary" :key="item.label">
                <b>{{ item.label }}：</b>{{ item.value }}
              </span>
            </div>

            <section v-for="section in clinicalArchiveSections" :key="section.key" class="paper-section clinical-section">
              <h3>{{ section.title }}</h3>

              <div v-if="section.key === 'basic'" class="paper-grid">
                <div v-for="printField in section.fields" :key="printField.key">
                  <strong>{{ printField.printLabel || printField.label }}</strong>

                  <span class="paper-value">{{ printableFieldValue(printField) }}</span>
                </div>
              </div>

              <div v-else class="paper-field-list">
                <div v-for="printField in section.fields" :key="printField.key" class="paper-field-line">
                  <strong>
                    {{ printField.printLabel || printField.label }}

                    <small v-if="matchedAttachments(printField.key).length">有附件</small>
                  </strong>

                  <span class="paper-value inline">{{ printableFieldValue(printField) }}</span>
                </div>
              </div>
            </section>

            <div class="paper-sign">
              <span>医师签名：________</span>

              <span>档案审核：________</span>

              <span>生成时间：{{ generatedAt }}</span>
            </div>

            <div class="paper-footer">
              <span>{{ fieldValues.hospitalName }} · {{ archiveVersion }}</span>

              <span>状态：{{ archiveStatusText }} · 追溯码 {{ recordSignature }}</span>
            </div>
          </article>

          <article
            v-if="managementPreviewPage"
            class="preview-paper record-page management-record-page"
            :data-preview-page="managementPreviewPage.page"
          >
            <div class="paper-watermark" :data-watermark="archiveWatermark"></div>

            <div class="paper-page-mark">
              <span>患者管理与随访档案</span>

              <span>第 {{ managementPreviewPage.page }} 页 / 共 {{ previewPageCount }} 页</span>
            </div>

            <div class="medical-record-head module-record-head management-head">
              <div class="medical-brand">
                <img v-if="logoVisible" class="medical-logo" :src="logoSrc" alt="医院标识" @error="fallbackLogo" />

                <div class="medical-title-block">
                  <h1>{{ fieldValues.hospitalName }}</h1>

                  <h2>患者管理与随访档案</h2>
                </div>
              </div>

              <div class="medical-subtitle">来院来源 · 特殊诉求 · 家属关系 · 复查随访 · 主观反馈</div>

              <div class="paper-meta medical-meta-table">
                <span v-for="item in paperMeta" :key="item.label">
                  <b>{{ item.label }}：</b><em class="paper-value">{{ item.value }}</em>
                </span>
              </div>
            </div>

            <div class="paper-archive-card management-summary-card">
              <span v-for="item in managementArchiveSummary" :key="item.label">
                <b>{{ item.label }}：</b>{{ item.value }}
              </span>
            </div>

            <section v-for="section in managementArchiveSections" :key="section.key" class="paper-section management-section">
              <h3>{{ section.title }}</h3>

              <div class="paper-field-list">
                <div
                  v-for="managementField in section.fields"
                  :key="managementField.key"
                  class="paper-field-line"
                  :class="{ 'followup-line': managementField.key === 'followupRecordsJson' }"
                >
                  <strong>
                    {{ managementField.printLabel || managementField.label }}

                    <small v-if="matchedAttachments(managementField.key).length">有附件</small>
                  </strong>

                  <div v-if="managementField.key === 'followupRecordsJson'" class="paper-followup-timeline">
                    <article v-for="(record, index) in followupRecords" :key="record.id" class="paper-followup-item">
                      <b>第 {{ index + 1 }} 次 · {{ record.date || "日期待补" }}</b>

                      <span>{{ record.type || "复查" }} / {{ record.onTime || "复查状态待记录" }}</span>

                      <p>恢复：{{ record.recovery || "待记录" }}</p>

                      <p>异常：{{ record.abnormal || "无" }}</p>

                      <p>建议：{{ record.advice || "待记录" }}</p>

                      <em>下次：{{ record.nextDate || "待安排" }}</em>
                    </article>

                    <span v-if="!followupRecords.length" class="paper-followup-empty">暂无复查随访记录</span>
                  </div>

                  <span v-else class="paper-value inline">{{ printableFieldValue(managementField) }}</span>
                </div>
              </div>
            </section>

            <section v-if="auditArchiveSections.length" class="paper-section audit-summary-section">
              <h3>档案审核与归档信息</h3>

              <div class="audit-summary-grid">
                <div v-for="section in auditArchiveSections" :key="section.key">
                  <strong>{{ section.title }}</strong>

                  <p v-for="auditField in section.fields" :key="auditField.key">
                    <b>{{ auditField.printLabel || auditField.label }}：</b>{{ printableFieldValue(auditField) }}
                  </p>
                </div>
              </div>
            </section>

            <section v-if="previewTimelineEvents.length" class="paper-section archive-timeline-section">
              <h3>档案时间轴</h3>

              <div class="paper-archive-timeline">
                <article v-for="event in previewTimelineEvents" :key="event.id" class="paper-archive-timeline-item">
                  <span>{{ timelineDisplayTime(event.time) }}</span>

                  <div>
                    <strong>{{ event.title }}</strong>

                    <p>{{ event.detail || "暂无详情" }}</p>

                    <small>
                      来源：{{ timelineSourceLabel(event.source) }}

                      <template v-if="event.operator"> · 操作人：{{ event.operator }}</template>

                      <template v-if="event.targetLabel"> · {{ event.targetLabel }}</template>
                    </small>
                  </div>
                </article>
              </div>
            </section>

            <div class="paper-sign">
              <span>随访负责人：________</span>

              <span>档案审核：________</span>

              <span>生成时间：{{ generatedAt }}</span>
            </div>

            <div class="paper-footer">
              <span>{{ fieldValues.hospitalName }} · {{ archiveVersion }}</span>

              <span>状态：{{ archiveStatusText }} · 追溯码 {{ recordSignature }}</span>
            </div>
          </article>

          <article
            v-if="attachmentIndexPreviewPage"
            class="preview-paper attachment-index"
            :data-preview-page="attachmentIndexPreviewPage.page"
          >
            <div class="paper-watermark" :data-watermark="archiveWatermark"></div>

            <div class="paper-page-mark">
              <span>附件索引</span>

              <span>第 {{ attachmentIndexPreviewPage.page }} 页 / 共 {{ previewPageCount }} 页</span>
            </div>

            <div class="medical-record-head compact">
              <div class="medical-brand">
                <img v-if="logoVisible" class="medical-logo" :src="logoSrc" alt="医院标识" @error="fallbackLogo" />

                <div class="medical-title-block">
                  <h1>{{ fieldValues.hospitalName }}</h1>

                  <h2>检查检验附件索引</h2>
                </div>
              </div>
            </div>

            <table class="attachment-table">
              <thead>
                <tr>
                  <th>序号</th>

                  <th>附件名称</th>

                  <th>关联字段</th>

                  <th>上传科室</th>

                  <th>上传时间</th>

                  <th class="screen-only">操作</th>
                </tr>
              </thead>

              <tbody>
                <tr v-for="(attachment, index) in currentAttachments" :key="attachment.key">
                  <td>{{ index + 1 }}</td>

                  <td>{{ attachment.fileName }}</td>

                  <td>{{ attachment.fieldLabel }}</td>

                  <td>{{ attachment.department }}</td>

                  <td>{{ attachment.uploadedAt }}</td>

                  <td class="screen-only">
                    <el-button v-auth="'document:void'" type="danger" link @click="openVoid(attachment)">作废</el-button>
                  </td>
                </tr>
              </tbody>
            </table>

            <div class="paper-footer">
              <span>{{ fieldValues.patientName }} · {{ fieldValues.visitNo }} · {{ archiveVersion }}</span>

              <span>附件原图随健康档案保存 · 追溯码 {{ recordSignature }}</span>
            </div>
          </article>

          <article
            v-for="(attachment, index) in currentAttachments"
            :key="attachment.key"
            class="preview-paper attachment-page"
            :data-preview-page="attachmentPreviewStartPage + index"
          >
            <div class="paper-watermark" :data-watermark="archiveWatermark"></div>

            <div class="paper-page-mark">
              <span>检查检验附件</span>

              <span>第 {{ attachmentPreviewStartPage + index }} 页 / 共 {{ previewPageCount }} 页</span>
            </div>

            <div class="attachment-head">
              <div>
                <h2>附件 {{ index + 1 }}：{{ attachment.title }}</h2>

                <p>{{ attachment.fileName }} · {{ attachment.department }} · {{ attachment.uploadedAt }}</p>
              </div>

              <span>{{ attachment.fieldLabel }}</span>
            </div>

            <div class="attachment-image-frame">
              <img
                v-if="isImageAttachment(attachment) && canOpenAttachment(attachment)"
                :src="attachmentPreviewUrl(attachment.url)"
                :alt="attachment.title"
              />

              <div v-else class="attachment-file-placeholder">
                <strong>{{ attachment.fileName }}</strong>

                <span v-if="canOpenAttachment(attachment)">可打开原文件。</span>

                <span v-else>文件打不开，请重新上传。</span>

                <el-button
                  v-if="canOpenAttachment(attachment)"
                  class="screen-only"
                  type="primary"
                  plain
                  @click="openAttachment(attachment.url)"
                >
                  打开原文件
                </el-button>
              </div>
            </div>

            <div class="paper-footer">
              <span>{{ attachment.uploader }}</span>

              <span>关联字段：{{ attachment.fieldLabel }} · 追溯码 {{ recordSignature }}</span>
            </div>
          </article>
        </div>
      </RecordPreviewOverlay>

      <el-drawer v-model="auditTimelineVisible" title="档案时间轴与操作轨迹" size="680px" destroy-on-close>
        <div class="audit-timeline-drawer">
          <section class="timeline-summary">
            <div>
              <strong>{{ fieldValues.patientName || "当前患者" }}</strong>

              <span>{{ fieldValues.visitNo || patientId }}</span>
            </div>

            <el-button :loading="auditLoading" @click="refreshAuditTimeline">刷新</el-button>
          </section>

          <section class="archive-timeline-review">
            <div class="archive-timeline-review-head">
              <strong>健康档案时间轴</strong>

              <span>来自就诊、复查、附件、归档和操作日志，可用于确认资料补充顺序。</span>
            </div>

            <el-empty v-if="!patientTimelineEvents.length" description="暂无档案时间轴" />

            <el-timeline v-else class="patient-audit-timeline">
              <el-timeline-item
                v-for="event in patientTimelineEvents"
                :key="event.id"
                :timestamp="timelineDisplayTime(event.time)"
                placement="top"
                :type="timelineTagType(event)"
              >
                <article class="timeline-event archive-event">
                  <div class="timeline-event-head">
                    <strong>{{ event.title }}</strong>

                    <el-tag :type="timelineTagType(event)" effect="plain" size="small">
                      {{ timelineSourceLabel(event.source) }}
                    </el-tag>
                  </div>

                  <p>{{ event.detail || "暂无详情" }}</p>

                  <div class="timeline-meta">
                    <span>{{ event.module }}</span>

                    <span v-if="event.operator">{{ event.operator }}</span>

                    <span v-if="event.targetLabel">{{ event.targetLabel }}</span>

                    <span v-if="event.sourceId">定位：{{ event.sourceId }}</span>
                  </div>
                </article>
              </el-timeline-item>
            </el-timeline>
          </section>

          <el-empty v-if="!auditLoading && !patientAuditLogs.length" description="暂无操作轨迹" />

          <el-timeline v-else class="patient-audit-timeline">
            <el-timeline-item
              v-for="log in patientAuditLogs"
              :key="log.id"
              :timestamp="log.time"
              placement="top"
              :type="auditTimelineType(log)"
            >
              <article class="timeline-event">
                <div class="timeline-event-head">
                  <strong>{{ log.action }}</strong>

                  <el-tag :type="log.result === 'denied' ? 'danger' : 'success'" effect="plain" size="small">
                    {{ log.result === "denied" ? "已拒绝" : "成功" }}
                  </el-tag>
                </div>

                <p>{{ log.detail }}</p>

                <div class="timeline-meta">
                  <span>{{ log.operator }}</span>

                  <span>{{ log.role }}</span>

                  <span>{{ auditModuleLabel(log.module) }}</span>

                  <span v-if="log.targetLabel">{{ log.targetLabel }}</span>
                </div>

                <div v-if="log.beforeValue || log.afterValue" class="timeline-change">
                  <div>
                    <small>修改前</small>

                    <span>{{ log.beforeValue || "空" }}</span>
                  </div>

                  <div>
                    <small>修改后</small>

                    <span>{{ log.afterValue || "空" }}</span>
                  </div>
                </div>
              </article>
            </el-timeline-item>
          </el-timeline>
        </div>
      </el-drawer>

      <VoidAttachmentDialog
        v-if="voidDialogVisible"
        v-model:visible="voidDialogVisible"
        v-model:reason="voidReason"
        :target="voidTarget"
        :voiding="voiding"
        @confirm="confirmVoidDocument"
      />

      <PrintPreflightDialog
        v-if="printPreflightVisible"
        v-model:visible="printPreflightVisible"
        :items="printPreflightItems"
        @confirm="executePrint"
      />

      <ArchivePrecheckDialog
        v-if="archivePrecheckVisible"
        v-model:visible="archivePrecheckVisible"
        :issues="archivePrecheckIssues"
        @focus="focusPrecheckIssue"
      />

      <AiSummaryDialog
        v-if="aiSummaryVisible"
        v-model:visible="aiSummaryVisible"
        :loading="aiSummaryLoading"
        :speaking="aiSummarySpeaking"
        :summary="aiSummary"
        :patient-name="fieldValues.patientName || '当前患者'"
        :visit-no="fieldValues.visitNo || patientId"
        @closed="stopAiSummarySpeech"
        @close="closeAiSummaryDialog"
        @generate="generateAiSummary"
        @toggle-speech="toggleAiSummarySpeech"
        @copy="copyAiSummary"
      />

      <el-dialog
        v-if="medicalRecordVisible"
        v-model="medicalRecordVisible"
        title="目标病历协作填写与生成"
        width="1040px"
        append-to-body
        destroy-on-close
      >
        <MedicalRecordWorkbench
          variant="dialog"
          :loading="medicalRecordLoading"
          :template-status="medicalRecordTemplate"
          :missing-items="medicalRecordMissingItems"
          :unbound-fields="medicalRecordUnboundFields"
          :field-sections="medicalRecordFieldSections"
          v-model:active-sections="medicalRecordActiveSections"
          :field-values="fieldValues"
          :current-role="currentRole"
          :can-generate="canGenerateMedicalRecord"
          :lab-report-values="fieldValues"
          :patient-name="fieldValues.patientName"
          :patient-gender="fieldValues.gender"
          :visit-no="fieldValues.visitNo || patientId"
          :current-record="currentMedicalRecord"
          :versions="medicalRecordVersions"
          :completed-count="medicalRecordCompletedCount"
          :total-count="medicalRecordTotalCount"
          :can-manage-versions="canManageMedicalRecordVersions"
          :is-textarea-field="isMedicalRecordTextareaField"
          :is-field-missing="isMedicalRecordFieldMissing"
          :field-assist-text="medicalRecordFieldAssistText"
          :field-source-hint="medicalRecordArchiveSourceHint"
          :is-select-field="isMedicalRecordSelectField"
          :field-options="medicalRecordFieldOptions"
          :is-date-field="isMedicalRecordDateField"
          :status-label="medicalRecordStatusLabel"
          :status-type="medicalRecordStatusType"
          @update-field="updateMedicalRecordField"
          @close="medicalRecordVisible = false"
          @precheck="() => precheckMedicalRecord()"
          @save-workspace="() => saveMedicalRecordWorkspace()"
          @sync-from-archive="fillMedicalRecordBlanksFromArchive"
          @generate="generateMedicalRecord"
          @download="downloadMedicalRecord"
          @void="voidMedicalRecord"
          @finalize="finalizeMedicalRecord"
          @select-version="selectMedicalRecordVersion"
        />
      </el-dialog>

      <AiAssistantPanel
        v-model="patientAssistantVisible"
        assistant-type="patient"
        title="患者助手"
        :patient-id="patientId"
        :default-prompt="patientAssistantPrompt"
        :context="patientAssistantContext"
        :attachment-ids="patientAssistantAttachmentIds"
      />
    </div>
  </div>
</template>

<script setup lang="ts" name="patientDetail">
import {
  computed,
  defineAsyncComponent,
  nextTick,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  reactive,
  ref,
  watch
} from "vue";

import { useDebounceFn } from "@vueuse/core";

import { ElMessage } from "element-plus";

import { useRoute, useRouter } from "vue-router";

import { ArrowDown, ChatDotRound, Clock, DocumentCopy, FolderOpened, Printer, Upload, View } from "@element-plus/icons-vue";

import AiAssistantPanel from "@/components/AiAssistantPanel/index.vue";

import TreeFilter from "@/components/TreeFilter/index.vue";

import {
  downloadMedicalRecordApi,
  getAuditLogListApi,
  finalizeMedicalRecordApi,
  generateMedicalRecordApi,
  getGeneratedMedicalRecordVersionsApi,
  getMedicalRecordTemplateApi,
  getPatientDetailApi,
  getPatientTimelineApi,
  getTemplateFieldRulesApi,
  logPatientExportApi,
  precheckMedicalRecordApi,
  revokeArchiveApi,
  saveMedicalRecordWorkspaceApi,
  savePatientRecordApi,
  submitArchiveApi,
  uploadDocumentsApi,
  voidMedicalRecordApi,
  voidDocumentApi,
  type AuditLogRow,
  type GeneratedMedicalRecord,
  type MedicalRecordTemplateField,
  type MedicalRecordTemplateStatus,
  type PatientRow,
  type PatientTimelineEvent,
  type TemplateFieldRule
} from "@/api/modules/clinic";

import {
  editorLabels,
  isLifecycleStageSkipped,
  patientLifecycleStages,
  recordAttachments as defaultRecordAttachments,
  recordSections,
  roleLabel,
  serviceCollaborators,
  USER_ROLES,
  type RecordAttachment,
  type RecordField,
  type RecordSection,
  type UserRole
} from "@/config/fieldPermissions";

import { useUserStore } from "@/stores/modules/user";

import { traceAsync } from "@/utils/performanceTrace";

import medicalLogoUrl from "@/assets/images/logo.jpg";

import ArchiveFieldRenderer from "./components/ArchiveFieldRenderer.vue";
import LabReportPreview from "./components/LabReportPreview.vue";
import { useAttachmentPreview } from "./composables/useAttachmentPreview";
import { useArchiveAutosave } from "./composables/useArchiveAutosave";
import { useArchiveFieldIndex } from "./composables/useArchiveFieldIndex";
import { useAiSummary } from "./composables/useAiSummary";
import {
  usePatientWorkflowTasks,
  type WorkflowAttachmentTask,
  type WorkflowFieldTask,
  type WorkflowStageNode
} from "./composables/usePatientWorkflowTasks";
import { usePatientRolePreview, type WorkflowRolePreview } from "./composables/usePatientRolePreview";
import { useRecordPrintPreview } from "./composables/useRecordPrintPreview";
import type { FieldIssue, FollowupRecord } from "./components/types";
import { buildLabReportSummary, hasLabReportData } from "@/views/workbench/labReport/summary";

const AiSummaryDialog = defineAsyncComponent(() => import("./components/AiSummaryDialog.vue"));
const ArchivePrecheckDialog = defineAsyncComponent(() => import("./components/ArchivePrecheckDialog.vue"));
const AttachmentWorkbench = defineAsyncComponent(() => import("./components/AttachmentWorkbench.vue"));
const MedicalRecordWorkbench = defineAsyncComponent(() => import("./components/MedicalRecordWorkbench.vue"));
const PatientFieldSearch = defineAsyncComponent(() => import("./components/PatientFieldSearch.vue"));
const PatientOverviewPanel = defineAsyncComponent(() => import("./components/PatientOverviewPanel.vue"));
const PrintPreflightDialog = defineAsyncComponent(() => import("./components/PrintPreflightDialog.vue"));
const RecordPreviewOverlay = defineAsyncComponent(() => import("./components/RecordPreviewOverlay.vue"));
const TimelineWorkbench = defineAsyncComponent(() => import("./components/TimelineWorkbench.vue"));
const VoidAttachmentDialog = defineAsyncComponent(() => import("./components/VoidAttachmentDialog.vue"));
const WorkflowRolePreviewPanel = defineAsyncComponent(() => import("./components/WorkflowRolePreviewPanel.vue"));
const WorkflowTaskPanel = defineAsyncComponent(() => import("./components/WorkflowTaskPanel.vue"));

const router = useRouter();

const route = useRoute();

const userStore = useUserStore();

const normalizeUserRole = (role?: string): UserRole => (USER_ROLES.includes(role as UserRole) ? (role as UserRole) : "frontdesk");

const currentRole = computed<UserRole>(() => normalizeUserRole(userStore.userInfo.role));

const roleName = computed(() => roleLabel(currentRole.value));
const canManageMedicalRecordVersions = computed(() => currentRole.value === "admin" || currentRole.value === "doctor");

const patientId = computed(() => String(route.params.id || "").trim());

type DetailWorkspaceMode = "flow" | "medicalRecord" | "archive" | "attachments" | "timeline";
type PatientFieldSearchType = "field" | "attachment" | "stage";
type PatientFieldSearchItem = {
  key: string;
  label: string;
  description: string;
  type: PatientFieldSearchType;
  typeLabel: string;
  keywords: string;
};
type PatientDetailMoreCommand =
  | "preview"
  | "upload"
  | "legacy"
  | "quality"
  | "timeline"
  | "ai"
  | "assistant"
  | "medicalRecord"
  | "inspection"
  | "lab"
  | "copy";

const medicalRecordFirstRoles = new Set<UserRole>([
  "admin",
  "frontdesk",
  "reception",
  "lab",
  "ecg",
  "ultrasound",
  "inspection",
  "doctor",
  "nurse",
  "nursing",
  "quality"
]);

const detailWorkspaceMode = ref<DetailWorkspaceMode>("flow");

const activeWorkflowRoleKey = ref("");

const recordViewMode = ref<"mine" | "full">("mine");

type FieldLayerMode = "core" | "recommended" | "optional" | "all";

const fieldLayerMode = ref<FieldLayerMode>("core");

const activeSectionKey = ref(recordSections[0].key);

const archiveSubmitted = ref(false);

const archiveVersion = ref("V0.3-预归档");

const generatedAt = ref("2026-06-11 09:30:00");

const saving = ref(false);

const detailLoading = ref(false);

const detailError = ref("");

const isHydratingRecord = ref(false);

const savedSectionKey = ref("");

const highlightedFieldKey = ref("");

const patientAssistantVisible = ref(false);

const medicalRecordVisible = ref(false);

const medicalRecordLoading = ref(false);

const medicalRecordTemplate = ref<MedicalRecordTemplateStatus>();

const medicalRecordVersions = ref<GeneratedMedicalRecord[]>([]);

const currentMedicalRecord = ref<GeneratedMedicalRecord>();

const medicalRecordMissingItems = ref<string[]>([]);

const medicalRecordUnboundFields = ref<string[]>([]);

const medicalRecordActiveSections = ref<string[]>([]);

const auditTimelineVisible = ref(false);

const auditLoading = ref(false);

const voidDialogVisible = ref(false);

const voiding = ref(false);

const voidTarget = ref<RecordAttachment>();

const voidReason = ref("");

const archivePrecheckVisible = ref(false);

const currentAttachments = ref<RecordAttachment[]>(defaultRecordAttachments);

const {
  canOpenAttachment,
  isImageAttachment,
  attachmentPreviewUrl,
  preloadAttachmentPreviews,
  openAttachment,
  revokeAttachmentBlobUrls
} = useAttachmentPreview();

const patientAuditLogs = ref<AuditLogRow[]>([]);

const patientTimelineEvents = ref<PatientTimelineEvent[]>([]);

const templateRules = ref<TemplateFieldRule[]>([]);

const collapsedSectionKeys = ref<string[]>([]);

const patientInfo = ref<PatientRow>();

const isInitialDetailLoading = computed(() => detailLoading.value && !patientInfo.value && !detailError.value);

const logoCandidates = [medicalLogoUrl];

const logoIndex = ref(0);

const logoSrc = ref(logoCandidates[0]);

const logoVisible = ref(true);

const DETAIL_ERROR_COOLDOWN_MS = 5000;

type PatientDetailErrorWindow = Window & { __patientDetailErrorCooldowns?: Map<string, number> };

const detailErrorWindow = window as PatientDetailErrorWindow;

const detailErrorCooldowns = detailErrorWindow.__patientDetailErrorCooldowns || new Map<string, number>();

detailErrorWindow.__patientDetailErrorCooldowns = detailErrorCooldowns;

let highlightClearTimer: number | undefined;

type WorkflowHint = {
  visible: boolean;

  level: "info" | "warning" | "danger" | "success";

  title: string;

  desc: string;
};

const createFollowupRecord = (): FollowupRecord => ({
  id: `followup-${Date.now()}-${Math.random().toString(16).slice(2)}`,

  date: new Date().toISOString().slice(0, 10),

  type: "术后复查",

  node: "自定义复查",

  project: "",

  management: "",

  imagingRequirement: "",

  completed: "未完成",

  recovery: "",

  abnormal: "",

  advice: "",

  nextDate: "",

  onTime: "按时复查"
});

const standardFollowupNodes = [
  "首次复诊（术后第7天）",

  "二次复诊（术后14天）",

  "三次复诊（术后30天）",

  "远期随访（3/6/12月）",

  "痊愈归档"
];

const emptyWorkflowHint: WorkflowHint = {
  visible: false,

  level: "info",

  title: "",

  desc: ""
};

const ruleMap = computed(() =>
  templateRules.value.reduce<Record<string, TemplateFieldRule>>((map, rule) => {
    map[rule.fieldKey] = rule;

    return map;
  }, {})
);

const fullArchiveRoles = new Set<UserRole>(["admin", "doctor", "quality"]);
const firstVisitCollaboratorRoles: UserRole[] = ["admin", "frontdesk", "reception", "doctor"];
const firstVisitCollaborativeFieldKeys = new Set([
  "historyProvider",
  "historyReliable",
  "chiefComplaintText",
  "onset",
  "symptomPattern",
  "aggravation",
  "generalCondition",
  "operationHistory",
  "chronicDisease",
  "traumaTransfusion",
  "allergyHistory",
  "pastHistory",
  "personalHistory",
  "marriageBirthHistory",
  "familyHistory",
  "presentIllnessText",
  "admissionReason",
  "generalConditionText",
  "auxiliaryExamSummary"
]);

const roleVisibleSections: Partial<Record<UserRole, Set<string>>> = {
  frontdesk: new Set([
    "basic",

    "arrivalSource",

    "specialNeeds",

    "chiefComplaint",

    "presentIllness",

    "history",

    "familyRelationship",

    "trustCooperation",

    "followup",

    "patientFeedback"
  ]),

  reception: new Set([
    "basic",

    "arrivalSource",

    "specialNeeds",

    "chiefComplaint",

    "presentIllness",

    "history",

    "patientFeedback"
  ]),

  inspection: new Set(["basic", "specialExam", "preOpScreening", "documentScope"]),

  nurse: new Set([
    "basic",

    "specialExam",

    "auxiliary",

    "preOpScreening",

    "operation",

    "followup",

    "patientFeedback",

    "tcmHealthManagement",

    "supplementNotes"
  ]),

  nursing: new Set([
    "basic",

    "treatmentPlanManagement",

    "operation",

    "followup",

    "patientFeedback",

    "tcmHealthManagement",

    "supplementNotes",

    "documentScope"
  ]),

  lab: new Set(["basic", "auxiliary", "preOpScreening", "documentScope"]),

  ecg: new Set(["basic", "auxiliary", "preOpScreening", "documentScope"]),

  ultrasound: new Set(["basic", "auxiliary", "preOpScreening", "documentScope"])
};

const roleVisibleFieldKeys: Partial<Record<UserRole, Set<string>>> = {
  inspection: new Set([
    "patientName",

    "visitNo",

    "inspectionImages",

    "inspectionBriefNote",

    "lithotomyExam",

    "analTension",

    "digitalExam",

    "anoscope",

    "uncheckedItemsNote",

    "documentScope"
  ]),

  lab: new Set(["patientName", "visitNo", "hpTestStatus", "uncheckedItemsNote", "documentScope"]),

  ecg: new Set(["patientName", "visitNo", "ecgResult", "ecgStatus", "uncheckedItemsNote", "documentScope"]),

  ultrasound: new Set([
    "patientName",

    "visitNo",

    "colonoscopy",

    "gastroscopyStatus",

    "colonoscopyStatus",

    "drChestStatus",

    "uncheckedItemsNote",

    "documentScope"
  ])
};

type RoleViewKey = "all" | "doctorRecord" | "frontdesk" | "inspection" | "lab" | "nursing" | "management";

const roleViewOptions: { label: string; value: RoleViewKey }[] = [
  { label: "全部字段", value: "all" },
  { label: "医生目标病历", value: "doctorRecord" },
  { label: "前台/接诊", value: "frontdesk" },
  { label: "检查室", value: "inspection" },
  { label: "化验/心电/B超", value: "lab" },
  { label: "护理/治疗", value: "nursing" },
  { label: "随访管理", value: "management" }
];

const activeRoleView = ref<RoleViewKey>("all");
const canViewFullArchive = computed(() => fullArchiveRoles.has(currentRole.value));
const canUseRoleViewFilter = computed(() => currentRole.value === "admin" || currentRole.value === "doctor");
const showPatientOverviewPanel = computed(
  () => !showLabReportOverview.value && (currentRole.value === "admin" || currentRole.value === "quality")
);

const roleViewSections: Record<Exclude<RoleViewKey, "all">, Set<string>> = {
  doctorRecord: new Set([
    "basic",
    "chiefComplaint",
    "presentIllness",
    "history",
    "specialExam",
    "tcmInspection",
    "mainDiagnosis",
    "secondaryDiagnosis",
    "treatmentPlanManagement",
    "operation"
  ]),
  frontdesk: new Set([
    "basic",
    "arrivalSource",
    "specialNeeds",
    "chiefComplaint",
    "presentIllness",
    "history",
    "familyRelationship",
    "trustCooperation",
    "patientFeedback"
  ]),
  inspection: new Set(["basic", "specialExam", "preOpScreening", "documentScope"]),
  lab: new Set(["basic", "auxiliary", "preOpScreening", "documentScope"]),
  nursing: new Set([
    "basic",
    "treatmentPlanManagement",
    "operation",
    "followup",
    "patientFeedback",
    "tcmHealthManagement",
    "supplementNotes"
  ]),
  management: new Set([
    "followup",
    "patientFeedback",
    "familyRelationship",
    "trustCooperation",
    "supplementNotes",
    "documentScope"
  ])
};

const roleViewFieldKeys: Partial<Record<RoleViewKey, Set<string>>> = {
  inspection: new Set([...Array.from(roleVisibleFieldKeys.inspection || []), "inspectionPending"]),
  lab: new Set(["patientName", "visitNo", "ecgResult", "colonoscopy", "uncheckedItemsNote", "documentScope"])
};

const isVisibleForSelectedRoleView = (section: RecordSection, field: RecordField) => {
  if (!canUseRoleViewFilter.value || activeRoleView.value === "all") return true;
  const visibleSections = roleViewSections[activeRoleView.value];
  if (visibleSections && !visibleSections.has(section.key)) return false;
  const visibleFields = roleViewFieldKeys[activeRoleView.value];
  if (visibleFields && !visibleFields.has(field.key)) return false;
  return true;
};

const mergeFirstVisitEditors = (fieldKey: string, editors: UserRole[] = []) => {
  if (!firstVisitCollaborativeFieldKeys.has(fieldKey)) return editors;
  return Array.from(new Set([...editors, ...firstVisitCollaboratorRoles]));
};

const isAttachmentVisibleForSelectedRoleView = (attachment: RecordAttachment) => {
  if (!canUseRoleViewFilter.value || activeRoleView.value === "all") return true;
  if (activeRoleView.value === "inspection") {
    return (
      attachment.sourceRole === "inspection" || attachment.department === "检查室" || attachment.fieldKey === "inspectionPending"
    );
  }
  if (activeRoleView.value === "lab") {
    return (
      ["lab", "ecg", "ultrasound"].includes(attachment.sourceRole || "") ||
      ["化验室", "心电室", "B超/放射"].includes(attachment.department || "")
    );
  }
  if (activeRoleView.value === "frontdesk") return ["frontdesk", "reception"].includes(attachment.sourceRole || "");
  if (activeRoleView.value === "nursing") return ["nurse", "nursing"].includes(attachment.sourceRole || "");
  return true;
};

const visibleAttachments = computed(() => currentAttachments.value.filter(isAttachmentVisibleForSelectedRoleView));

const isVisibleForRole = (section: RecordSection, field: RecordField) => {
  if (!isVisibleForSelectedRoleView(section, field)) return false;

  if (canViewFullArchive.value) return true;

  const visibleSections = roleVisibleSections[currentRole.value];

  if (visibleSections && !visibleSections.has(section.key)) return false;

  const visibleFields = roleVisibleFieldKeys[currentRole.value];

  if (visibleFields && !visibleFields.has(field.key)) return false;

  return isEditable(field) || Boolean(field.required) || Boolean(field.evidence);
};

const applyRuleToField = (field: RecordField): RecordField | null => {
  const rule = ruleMap.value[field.key];

  if (rule && !rule.enabled) return null;

  return {
    ...field,

    editors: mergeFirstVisitEditors(field.key, rule?.editors || field.editors),

    required: rule?.required ?? field.required,

    evidence: rule?.evidence ?? field.evidence,

    printable: rule?.printable ?? true,

    qualityCheck: rule?.qualityCheck ?? field.qualityCheck,

    enabled: rule?.enabled ?? true
  };
};

const recordSectionsByRule = computed<RecordSection[]>(() =>
  recordSections

    .map(section => {
      const fields = section.fields.map(applyRuleToField).filter(Boolean) as RecordField[];

      return {
        ...section,

        fields: fields.filter(field => isVisibleForRole(section, field))
      };
    })

    .filter(section => section.fields.length)
);

const printableRecordSections = computed<RecordSection[]>(() =>
  recordSectionsByRule.value

    .map(section => ({
      ...section,

      fields: section.fields.filter(field => field.printable !== false)
    }))

    .filter(section => section.fields.length)
);

const clinicalSectionKeys = new Set([
  "basic",

  "chiefComplaint",

  "presentIllness",

  "history",

  "tcmInspection",

  "specialExam",

  "auxiliary",

  "preOpScreening",

  "mainDiagnosis",

  "secondaryDiagnosis",

  "comorbidityTcm",

  "operation",

  "treatmentPlanManagement",

  "dip",

  "roundSchedule"
]);

const managementSectionKeys = new Set([
  "arrivalSource",

  "specialNeeds",

  "familyRelationship",

  "trustCooperation",

  "followup",

  "patientFeedback",

  "tcmHealthManagement",

  "supplementNotes"
]);

const auditSectionKeys = new Set(["documentScope", "qualityCheck"]);

const clinicalArchiveSections = computed<RecordSection[]>(() =>
  printableRecordSections.value.filter(section => clinicalSectionKeys.has(section.key))
);

const managementArchiveSections = computed<RecordSection[]>(() =>
  printableRecordSections.value.filter(section => managementSectionKeys.has(section.key))
);

const auditArchiveSections = computed<RecordSection[]>(() =>
  printableRecordSections.value.filter(section => auditSectionKeys.has(section.key))
);

const activeIndex = computed(() => recordSectionsByRule.value.findIndex(section => section.key === activeSectionKey.value));

const activeSection = computed(
  () => recordSectionsByRule.value[activeIndex.value] || recordSectionsByRule.value[0] || recordSections[0]
);

const isFirstSection = computed(() => activeIndex.value === 0);

const isLastSection = computed(() => activeIndex.value === recordSectionsByRule.value.length - 1);

const createEmptyFieldValues = () =>
  recordSections.reduce<Record<string, string>>((values, section) => {
    section.fields.forEach(field => {
      values[field.key] = field.key === "hospitalName" ? field.value : "";
    });

    return values;
  }, {});

const fieldValues = reactive(createEmptyFieldValues());

const {
  visible: aiSummaryVisible,
  loading: aiSummaryLoading,
  speaking: aiSummarySpeaking,
  summary: aiSummary,
  stopSpeech: stopAiSummarySpeech,
  toggleSpeech: toggleAiSummarySpeech,
  generate: generateAiSummary,
  open: openAiSummary,
  close: closeAiSummaryDialog,
  copy: copyAiSummary
} = useAiSummary({ patientId, fieldValues });

const followupRecords = ref<FollowupRecord[]>([]);

let syncingFollowupJson = false;

const ensureArray = <T,>(value?: T[] | null): T[] => (Array.isArray(value) ? value : []);

const normalizeFollowupRecord = (record: Partial<FollowupRecord>): FollowupRecord => ({
  ...createFollowupRecord(),

  ...record,

  node: record.node || record.type || "自定义复查",

  project: record.project || "",

  management: record.management || "",

  imagingRequirement: record.imagingRequirement || "",

  completed: record.completed || (record.date || record.recovery || record.advice ? "已完成" : "未完成"),

  id: record.id || `followup-${Date.now()}-${Math.random().toString(16).slice(2)}`
});

const parseFollowupRecords = (value?: string): FollowupRecord[] => {
  const raw = String(value || "").trim();

  if (!raw) return [];

  try {
    const parsed = JSON.parse(raw);

    return Array.isArray(parsed) ? parsed.map(item => normalizeFollowupRecord(item || {})) : [];
  } catch {
    return [];
  }
};

const syncFollowupRecordsFromField = () => {
  syncingFollowupJson = true;

  const parsedRecords = parseFollowupRecords(fieldValues.followupRecordsJson);

  const recordsByNode = new Map(parsedRecords.map(record => [record.node, record]));

  const defaults = standardFollowupNodes.map(
    node =>
      recordsByNode.get(node) ||
      normalizeFollowupRecord({
        id: `followup-node-${node}`,

        node,

        type: node,

        completed: "未完成"
      })
  );

  const customRecords = parsedRecords.filter(record => !standardFollowupNodes.includes(record.node));

  followupRecords.value = [...defaults, ...customRecords];

  nextTick(() => {
    syncingFollowupJson = false;
  });
};

const formatFollowupRecord = (record: FollowupRecord, index: number) =>
  `${record.node || `第 ${index + 1} 次`}：${record.date || "日期待补"}；项目：${record.project || "待记录"}；管理：${
    record.management || "待记录"
  }；恢复：${record.recovery || "待记录"}；异常：${record.abnormal || "无"}；建议：${
    record.advice || "待记录"
  }；状态：${record.completed || record.onTime || "待记录"}`;

const printableFieldValue = (field: RecordField) => {
  if (field.key === "followupRecordsJson") {
    return followupRecords.value.length ? followupRecords.value.map(formatFollowupRecord).join("\n") : "暂无复查随访记录";
  }

  return fieldValues[field.key] || "____";
};

const addFollowupRecord = () => {
  followupRecords.value = [...followupRecords.value, createFollowupRecord()];
};

const removeFollowupRecord = (id: string) => {
  followupRecords.value = followupRecords.value.filter(record => record.id !== id);
};

watch(
  followupRecords,

  records => {
    if (syncingFollowupJson) return;

    fieldValues.followupRecordsJson = JSON.stringify(records);
  },

  { deep: true }
);

const {
  autoSaveStatus,
  lastSavedAt,
  lastSaveError,
  sectionSaveTimes,
  conflictDraftSavedAt,
  markRecordSaved,
  markRecordSaveError,
  markRecordConflict,
  copySaveError,
  persistLocalDraft,
  clearLocalDraft,
  restoreConflictDraft,
  viewServerLatest,
  resetArchiveAutosave
} = useArchiveAutosave({
  patientId,
  fieldValues,
  recordViewMode,
  activeSectionKey,
  recordSectionsByRule,
  getCurrentDraftValues: () => (recordViewMode.value === "mine" ? myFieldValues() : currentSectionValues()),
  syncFollowupRecordsFromField,
  loadPatientDetail: () => loadPatientDetail()
});

const fieldPresets: Record<string, string[]> = {
  hospitalName: ["固始中医肛肠医院"],

  admissionCount: ["第 1 次", "第 2 次", "第 3 次"],

  dischargeDate: ["____年__月__日", "2026年06月15日", "2026年06月17日"],

  hospitalDays: ["3 天", "5 天", "7 天", "10 天"],

  contactPhone: ["同患者联系电话", "家属电话待补", "________"],

  chiefComplaintText: ["肛门肿块伴便血 3 天，加重 1 天。", "便血伴肛门疼痛 2 天。", "肛门坠胀不适 1 周。"],

  onset: [
    "患者 3 天前无明显诱因出现肛门肿块，伴便血，色鲜红，呈滴下状。",

    "患者 2 天前排便后出现肛门疼痛，伴少量便血。",

    "患者 1 周前出现肛门坠胀不适，排便后明显。"
  ],

  symptomPattern: [
    "间断发作，伴肛门憋闷坠胀，保守治疗可缓解。",

    "排便时疼痛明显，便后逐渐缓解。",

    "便血色鲜红，量少，无明显头晕乏力。"
  ],

  aggravation: [
    "1 天前症状加重，门诊以混合痔收入院。",

    "症状反复发作，影响日常生活，门诊拟手术治疗。",

    "保守治疗效果欠佳，收入院进一步诊治。"
  ],

  generalCondition: [
    "精神可，饮食可，大便每日 1 次，小便无明显变化，体重无明显下降，无恶寒发热。",

    "精神可，睡眠一般，饮食尚可，小便正常，近期体重无明显变化。",

    "精神尚可，饮食睡眠可，二便基本正常。"
  ],

  operationHistory: ["否认手术史。", "既往行痔手术治疗，具体不详。", "既往行阑尾切除术，恢复可。"],

  chronicDisease: [
    "否认高血压、糖尿病、冠心病等慢性病史。",

    "有高血压病史，规律服药，血压控制尚可。",

    "有糖尿病病史，血糖控制情况待评估。"
  ],

  traumaTransfusion: [
    "否认重大外伤史，否认输血史；预防接种随社会。",

    "否认重大外伤史，输血史不详。",

    "有外伤史，已愈，否认输血史。"
  ],

  allergyHistory: ["否认药物及食物过敏史。", "青霉素过敏。", "头孢类药物过敏。", "过敏史不详。"],

  personalHistory: [
    "生于原籍，无外地长期居住史，无烟酒嗜好，否认特殊接触史，否认冶游史。",

    "有吸烟史，偶饮酒，否认特殊接触史。",

    "无烟酒嗜好，否认疫区及特殊接触史。"
  ],

  familyHistory: ["适龄结婚，配偶及子女体健。否认传染病、遗传病、肿瘤及类似病史。", "家族中无类似病史。", "家族史无特殊。"],

  tcmLook: ["神志清，精神可，面色正常，形体适中。", "神志清，精神一般，面色少华，形体适中。", "神志清，精神可，面色红润。"],

  tongue: ["舌质红，苔薄黄，舌中可见裂纹，边有齿痕。", "舌质淡红，苔薄白。", "舌质暗红，苔黄腻。"],

  tcmTreatment: ["清热利湿，消肿止痛。", "活血化瘀，消肿止痛。", "健脾益气，升提固脱。", "润肠通便，凉血止血。"],

  lithotomyExam: [
    "截石位 3、7、11 点位肛缘可见皮赘样隆起，屏气用腹压可见其缓慢增大，可自行还纳。",

    "截石位肛缘可见外痔赘皮，局部轻度充血水肿。",

    "截石位肛缘未见明显破溃，可见局部隆起。"
  ],

  analTension: [
    "肛门括约肌张力可，肛门赘皮轻度疼痛，入指约 7cm。",

    "肛门括约肌张力正常，入指顺利。",

    "肛门括约肌张力稍高，检查配合一般。"
  ],

  digitalExam: [
    "直肠腔内未触及硬性结节，齿线上方可触及柔软隆起，无明显压痛，指套少量染血。",

    "直肠腔内未触及明显肿物，指套无染血。",

    "直肠腔内未触及硬结，局部轻压痛。"
  ],

  anoscope: [
    "齿线上黏膜隆起、充血，部分糜烂，可见出血点，未见溃疡及占位。",

    "肛门镜见内痔黏膜充血隆起，未见明显活动性出血。",

    "肛门镜检查未见明显占位。"
  ],

  urineRoutine: ["尿常规余无异常。", "尿常规按实际填写，余无异常。", "尿常规待回报。"],

  biochemistry: ["生化全套余无异常。", "生化全套按实际填写，余无异常。", "肝肾功能、电解质待回报。"],

  coagulation: ["PT、APTT、TT、FIB、INR：余无异常。", "凝血功能按报告填写，余无异常。", "凝血功能待回报。"],

  preOpEight: ["乙肝五项、丙肝抗体、梅毒抗体、艾滋病抗体：按实际填写，余无异常。", "术前八项待回报。", "感染筛查未见明显异常。"],

  bloodRoutine: ["WBC、PLT、HGB 按实际填写，余无异常。", "血常规待回报。", "血常规白细胞轻度升高，余按实际填写。"],

  ecgResult: ["窦性心律，ST-T 改变按实际填写。", "窦性心律，大致正常心电图。", "心电图待回报。"],

  colonoscopy: ["未见异常。", "结直肠炎。", "直肠息肉。", "结肠息肉，已行内镜下处理。", "未行肠镜检查。"],

  vitalSigns: [
    "T：36.5℃，P：78次/分，R：20次/分，BP：120/80mmHg。",

    "T：36.8℃，P：82次/分，R：20次/分，BP：130/82mmHg。",

    "T：____℃，P：____次/分，R：____次/分，BP：____/____mmHg。"
  ],

  tcmDiagnosis: ["痔病（湿热下注证）", "痔病（气滞血瘀证）", "肛裂病（血热肠燥证）", "肛痈（热毒炽盛证）"],

  westernDiagnosis: ["混合痔", "内痔", "外痔", "肛裂", "肛周脓肿", "肛瘘"],

  otherMainDiagnosis: ["无。", "慢性直肠炎。", "结直肠息肉。", "慢性胃炎。"],

  secondaryDiagnosisList: ["无。", "肛门湿疹。", "血栓外痔。", "直肠黏膜松弛。", "结直肠息肉。"],

  comorbidityDisease: ["无明显合并病。", "高血压病。", "糖尿病。", "慢性胃炎。", "慢性肠炎。"],

  comorbiditySyndrome: ["无。", "肝阳上亢。", "脾胃虚弱。", "湿热困脾。", "气血亏虚。"],

  additionalOperation: ["内痔套扎术、外痔切除术按实际选择。", "无附加操作。", "肛门皮赘切除术。", "肛门成形术。"],

  operationIndication: [
    "反复脱出、保守治疗无效、便血反复发作，需手术治疗。",

    "疼痛及便血反复，保守治疗效果欠佳。",

    "症状明显，符合手术指征。"
  ],

  operationLevel: ["一类", "二类", "三类", "____级"],

  reasonableDays: ["3-5 天", "5-7 天", "7-10 天"],

  dipCompliance: ["无需调整分组，按临床实际选择。", "分组待质控复核。", "需结合手术方式和主要诊断复核。"],

  courseSchedule: [
    "入院第 1 天：首次病程；第 2 天：主治医师查房；第 2-3 天：术前讨论、手术医师查房、术前小结；手术日：术后首次病程；术后第 1 天手术医师查房；第 3 天副主任医师查房；第 5 天主治查房；拟定次日出院或出院。",

    "按 5 日住院路径生成：首次病程、术前小结、手术记录、术后病程、出院记录。",

    "按 7 日住院路径生成：三级查房、术前讨论、术后连续病程、出院小结。"
  ],

  generatedDocuments: [
    "入院记录、首次病程、术前讨论、术前小结、手术记录、术后每日病程、出院小结。",

    "入院记录、首次病程、手术记录、出院小结。",

    "按院内健康管理要求生成完整患者档案。"
  ],

  documentStandard: [
    "作为院内患者健康管理档案使用，不替代 HIS 官方病历和病历质控文书。",

    "按院内健康档案展示规范执行。",

    "待病案室最终复核。"
  ],

  qualityItems: [
    "中西医诊断一一对应；治法匹配；三级查房顺序规范；手术与诊断一致；分组合理、不高套；中医特色治疗齐全；合并病记录完整；肠镜按需选择、符合临床逻辑。",

    "诊断、手术、DIP、附件、时序待逐项复核。"
  ],

  qualityReview: ["待档案审核。", "资料完整，可提交归档。", "需补充检查附件后再归档。", "需医生补充诊断依据。"]
};

const currentVisitType = computed(() => patientInfo.value?.visitType || fieldValues.admissionWay || "门诊");

const isInpatientRecord = computed(() => String(currentVisitType.value || "").includes("住院"));

const recordTitle = computed(() => "患者健康管理档案");

const recordSubtitle = computed(() => `${currentVisitType.value} · 院内全周期管理记录`);

const visitNoLabel = computed(() => (isInpatientRecord.value ? "住院号" : "门诊号"));

const visitDateLabel = computed(() => (isInpatientRecord.value ? "入院日期" : "就诊日期"));

const encounterCountLabel = computed(() => `累计 ${patientInfo.value?.encounterCount || 1} 次就诊`);

const validAttachmentCount = computed(() => currentAttachments.value.filter(canOpenAttachment).length);

const invalidAttachmentCount = computed(() => currentAttachments.value.length - validAttachmentCount.value);

const latestFollowupRecord = computed(() =>
  [...followupRecords.value].reverse().find(record => record.date || record.recovery || record.advice)
);

const archiveFocusSummary = computed(
  () =>
    fieldValues.primaryConcern ||
    fieldValues.specialRequirements ||
    fieldValues.visitMotivation ||
    fieldValues.recoverySummary ||
    "待补充关注点"
);

const completedCountForSections = (sections: RecordSection[]) =>
  sections.reduce((count, section) => count + sectionCompletedCount(section), 0);

const fieldCountForSections = (sections: RecordSection[]) =>
  sections.reduce((count, section) => count + section.fields.length, 0);

const formatSectionProgress = (sections: RecordSection[]) => {
  const total = fieldCountForSections(sections);

  return total ? `${completedCountForSections(sections)}/${total}` : "0/0";
};

const paperMeta = computed(() => [
  { label: "患者", value: fieldValues.patientName },

  { label: visitNoLabel.value, value: fieldValues.visitNo },

  { label: visitDateLabel.value, value: fieldValues.admissionDate },

  { label: "就诊类型", value: currentVisitType.value },

  { label: "治疗类别", value: fieldValues.treatmentType }
]);

const displayFieldValue = (key: string, fallback = "待补充") => {
  const value = String(fieldValues[key] || "").trim();

  return value && value !== "____" && value !== "________" ? value : fallback;
};

const screeningStatusClass = (status: string) =>
  status === "异常" ? "is-abnormal" : status === "已查" ? "is-checked" : "is-unchecked";

const registrationBasicRows = computed(() => [
  ["姓名", displayFieldValue("patientName"), "联系电话", displayFieldValue("phone")],

  [
    "性别/年龄",

    `${displayFieldValue("gender", "未说明")} / ${displayFieldValue("age")}`,

    "户籍地/现住址",

    displayFieldValue("address")
  ],

  [
    "建档（首诊）日期",

    displayFieldValue("archiveCreatedAt", displayFieldValue("admissionDate")),

    "就诊类型/科室",

    `${currentVisitType.value} / ${displayFieldValue("departmentName")}`
  ],

  [
    "来诊渠道/医保类型",

    `${displayFieldValue("arrivalPath")} / ${displayFieldValue("insuranceType")}`,

    "本次建档充值",

    displayFieldValue("initialRechargeAmount")
  ],

  ["基础生命体征", displayFieldValue("vitalSigns"), "整体病情风险评级", displayFieldValue("overallRiskLevel")],

  [
    "健康管理专员",

    displayFieldValue("healthManager"),

    "主诊医师/责任护士",

    `${displayFieldValue("attendingDoctor")} / ${displayFieldValue("responsibleNurse")}`
  ]
]);

const registrationConditionRows = computed(() => [
  ["主诉", displayFieldValue("chiefComplaintText")],

  ["既往史/过敏史", `${displayFieldValue("operationHistory")} ${displayFieldValue("allergyHistory")}`],

  ["专科查体", displayFieldValue("lithotomyExam")],

  ["西医诊断", displayFieldValue("westernDiagnosis")],

  ["中医辨证", `${displayFieldValue("tcmDiagnosis")} / ${displayFieldValue("tcmSyndrome")}`],

  ["影像归档", currentAttachments.value.length ? `已归档 ${currentAttachments.value.length} 份附件` : "待补充附件"]
]);

const screeningRows = computed(() => [
  ["血常规", displayFieldValue("bloodRoutine"), "bloodRoutineStatus"],

  ["凝血四项", displayFieldValue("coagulation"), "coagulationStatus"],

  ["术前八项（传染病）", displayFieldValue("preOpEight"), "preOpEightStatus"],

  ["心电图", displayFieldValue("ecgResult"), "ecgStatus"],

  ["肝功能", displayFieldValue("biochemistry"), "liverFunctionStatus"],

  ["肾功能", displayFieldValue("biochemistry"), "renalFunctionStatus"],

  ["空腹血糖", displayFieldValue("biochemistry"), "fastingGlucoseStatus"],

  ["血脂四项", displayFieldValue("biochemistry"), "bloodLipidStatus"],

  ["尿常规", displayFieldValue("urineRoutine"), "urineRoutineStatus"],

  ["C反应蛋白（CRP）", "按报告或医嘱填写", "crpStatus"],

  ["幽门螺杆菌检测", "按报告或医嘱填写", "hpTestStatus"],

  ["电子胃镜检查", "按报告或医嘱填写", "gastroscopyStatus"],

  ["电子肠镜检查", displayFieldValue("colonoscopy"), "colonoscopyStatus"],

  ["DR胸片检查", "按报告或医嘱填写", "drChestStatus"]
]);

const showLabReportOverview = computed(
  () =>
    detailWorkspaceMode.value === "archive" &&
    (currentRole.value === "lab" || (canUseRoleViewFilter.value && activeRoleView.value === "lab"))
);

const canEditLabSupplementNote = computed(() => ["admin", "doctor", "lab", "nurse", "quality"].includes(currentRole.value));

const treatmentManagementRows = computed(() => [
  ["手术可行性评估", displayFieldValue("surgeryFeasibility")],

  ["患者核心诉求", displayFieldValue("primaryConcern", displayFieldValue("specialRequirements"))],

  ["术中特殊交代", displayFieldValue("intraoperativeNotice")],

  ["当日处置", displayFieldValue("sameDayTreatment")]
]);

const tcmManagementRows = computed(() => [
  ["术后中药坐浴方案", displayFieldValue("tcmSitzBathPlan")],

  ["中医内服调理", displayFieldValue("tcmOralRegulation")],

  ["饮食禁忌宣教", displayFieldValue("dietEducation")],

  ["肛门功能锻炼指导", displayFieldValue("analFunctionExercise")],

  ["慢病预防干预", displayFieldValue("chronicDiseaseIntervention")],

  ["档案闭环签字", displayFieldValue("archiveClosedSignature")]
]);

const archiveStatusText = computed(() => (archiveSubmitted.value ? "待档案审核" : "档案草稿"));

const archiveWatermark = computed(() => (archiveSubmitted.value ? "待档案审核" : "档案草稿"));

const isFieldComplete = (value: string) => {
  const normalized = value.trim();

  return Boolean(normalized) && !normalized.includes("____") && !normalized.includes("________");
};

const validateFieldValue = (field: RecordField, value?: string): string => {
  const normalized = String(value || "").trim();

  if (!normalized || !isFieldComplete(normalized)) return "";

  if (field.inputType === "number") {
    const numberValue = Number(normalized);

    if (!Number.isFinite(numberValue)) return `${field.label}必须填写数字`;

    if (field.min !== undefined && numberValue < field.min) return `${field.label}不能小于 ${field.min}${field.unit || ""}`;

    if (field.max !== undefined && numberValue > field.max) return `${field.label}不能大于 ${field.max}${field.unit || ""}`;
  }

  if (field.inputType === "date" && !/^\d{4}-\d{2}-\d{2}$/.test(normalized)) {
    return `${field.label}请选择正确日期`;
  }

  if (field.pattern && !new RegExp(field.pattern).test(normalized)) {
    return field.validationMessage || `${field.label}格式不正确`;
  }

  return "";
};

const {
  attachmentsByField,
  completionBySection,
  fieldIssues,
  completionStats,
  matchedAttachments,
  issueForField,
  sectionIssues,
  isRecordFieldComplete,
  sectionCompletedCount,
  sectionRequiredMissingCount,
  sectionEvidenceCount,
  sectionEvidenceCountForSections,
  isSectionComplete,
  isSectionMeaningful
} = useArchiveFieldIndex({
  sourceSections: recordSections,
  visibleSectionsSource: recordSectionsByRule,
  attachments: visibleAttachments,
  fieldValues,
  isValueComplete: isFieldComplete,
  validateFieldValue
});

const completionPercent = computed(() =>
  completionStats.value.total ? Math.round((completionStats.value.completed / completionStats.value.total) * 100) : 0
);

const medicalRecordFieldSections = computed(() =>
  ensureArray(medicalRecordTemplate.value?.fieldMatrix)
    .map(section => {
      const fields = ensureArray(section.fields).filter(
        field => canManageMedicalRecordVersions.value || firstVisitCollaborativeFieldKeys.has(field.key)
      );
      return { ...section, fields };
    })
    .filter(section => section.fields.length)
);

const medicalRecordFields = computed<MedicalRecordTemplateField[]>(() =>
  medicalRecordFieldSections.value.flatMap(section => ensureArray(section.fields))
);

const canGenerateMedicalRecord = computed(() => currentRole.value === "admin" || currentRole.value === "doctor");

const canEditMedicalRecordField = (field: MedicalRecordTemplateField) => {
  if (currentRole.value === "admin") return true;
  if (!field.editorRoles?.length) return currentRole.value === "doctor";

  return field.editorRoles.includes(currentRole.value);
};

const medicalRecordEditableFields = computed(() => medicalRecordFields.value.filter(canEditMedicalRecordField));

const medicalRecordDynamicFieldCount = computed(
  () => medicalRecordFields.value.filter(field => (field.targetUse || "dynamic") === "dynamic").length
);

const medicalRecordFormOnlyFieldCount = computed(
  () => medicalRecordFields.value.filter(field => field.targetUse === "formOnly").length
);

const medicalRecordCompletionPercent = computed(() =>
  medicalRecordTotalCount.value ? Math.round((medicalRecordCompletedCount.value / medicalRecordTotalCount.value) * 100) : 0
);

const medicalRecordFieldOptions = (field: MedicalRecordTemplateField) => ensureArray(field.options);

const isMedicalRecordCheckboxField = (field: MedicalRecordTemplateField) =>
  field.controlType === "checkboxParagraph" || field.renderMode === "paragraph" || field.kind === "checkboxParagraph";

const isMedicalRecordSelectField = (field: MedicalRecordTemplateField) =>
  !isMedicalRecordCheckboxField(field) && (field.kind === "select" || medicalRecordFieldOptions(field).length > 0);

const isMedicalRecordDateField = (field: MedicalRecordTemplateField) => field.kind === "date" || field.inputType === "date";

const isMedicalRecordTextareaField = (field: MedicalRecordTemplateField) =>
  field.kind === "textarea" || isMedicalRecordCheckboxField(field);

const medicalRecordFieldAssistText = (field: MedicalRecordTemplateField) => {
  if (firstVisitCollaborativeFieldKeys.has(field.key)) return "前台初填，医生审核";

  if (isMedicalRecordCheckboxField(field)) return `${medicalRecordFieldOptions(field).length} 个固定模板勾选项`;

  if (field.targetUse === "formOnly") return "医生确认字段，生成时合并进入目标病历对应位置";

  if (field.aiPolishable) return "固定段落可由医生确认后再润色";

  if (isMedicalRecordSelectField(field)) return `${medicalRecordFieldOptions(field).length} 个常用项`;

  if (isMedicalRecordDateField(field)) return "YYYY-MM-DD";

  return field.required ? "目标病历必填" : "目标病历可选";
};

const isMedicalRecordFieldMissing = (field: MedicalRecordTemplateField) => {
  if (!field.required) return false;

  const value = String(fieldValues[field.key] || "").trim();

  return !value || value === "待补充" || value === "未见记录" || value.includes("____") || value.includes("________");
};

const isMedicalRecordFieldFilled = (field: MedicalRecordTemplateField) => {
  const value = String(fieldValues[field.key] || "").trim();

  return Boolean(value) && value !== "待补充" && value !== "未见记录" && !value.includes("____") && !value.includes("________");
};

const medicalRecordTotalCount = computed(() => medicalRecordFields.value.length);

const medicalRecordCompletedCount = computed(() => medicalRecordFields.value.filter(isMedicalRecordFieldFilled).length);

const medicalRecordWorkspaceValues = () =>
  medicalRecordEditableFields.value.reduce<Record<string, string>>((payload, field) => {
    payload[field.key] = String(fieldValues[field.key] || "");

    return payload;
  }, {});

const medicalRecordArchiveSyncKeys = new Set([
  "presentIllnessText",
  "pastHistory",
  "personalHistory",
  "marriageBirthHistory",
  "familyHistory",
  "generalConditionText",
  "auxiliaryExamSummary"
]);

const cleanArchivePart = (value?: string) => {
  const normalized = String(value || "").trim();
  if (!normalized || !isFieldComplete(normalized) || normalized === "待补充" || normalized === "未见记录") return "";
  return normalized;
};

const joinArchiveParts = (parts: string[]) => {
  const unique = Array.from(new Set(parts.map(cleanArchivePart).filter(Boolean)));
  return unique.map(item => (/[。；;.!?？]$/.test(item) ? item : `${item}。`)).join("");
};

const buildPresentIllnessFromArchive = () =>
  joinArchiveParts([
    fieldValues.onset,
    fieldValues.symptomPattern,
    fieldValues.aggravation,
    fieldValues.admissionReason,
    fieldValues.generalConditionText || fieldValues.generalCondition
  ]);

const buildPastHistoryFromArchive = () =>
  joinArchiveParts([
    fieldValues.operationHistory,
    fieldValues.chronicDisease,
    fieldValues.traumaTransfusion,
    fieldValues.allergyHistory
  ]);

const archiveLabReportSummary = computed(() => buildLabReportSummary(fieldValues));
const hasArchiveLabReportData = computed(() => hasLabReportData(fieldValues));
const showNursingLabReportReference = computed(() => ["nurse", "nursing"].includes(currentRole.value));

const buildAuxiliaryExamSummaryFromArchive = () => cleanArchivePart(archiveLabReportSummary.value);

const archiveToMedicalRecordDraft = () => ({
  presentIllnessText: buildPresentIllnessFromArchive(),
  generalConditionText: cleanArchivePart(fieldValues.generalConditionText || fieldValues.generalCondition),
  pastHistory: buildPastHistoryFromArchive(),
  personalHistory: cleanArchivePart(fieldValues.personalHistory),
  marriageBirthHistory: cleanArchivePart(fieldValues.marriageBirthHistory || fieldValues.familyHistory),
  familyHistory: cleanArchivePart(fieldValues.familyHistory),
  auxiliaryExamSummary: buildAuxiliaryExamSummaryFromArchive()
});

const syncMedicalRecordDraftFromArchive = (showMessage = false) => {
  const draft = archiveToMedicalRecordDraft();
  const availableKeys = new Set(medicalRecordFields.value.map(field => field.key));
  let filledCount = 0;

  Object.entries(draft).forEach(([key, value]) => {
    if (!availableKeys.has(key) || !value) return;
    if (cleanArchivePart(fieldValues[key])) return;
    fieldValues[key] = value;
    filledCount += 1;
  });

  if (showMessage) {
    if (filledCount) ElMessage.success(`已从健康管理档案补齐 ${filledCount} 个目标病历空白项`);
    else ElMessage.info("目标病历暂无可补齐的空白项，医生已填写内容不会被覆盖");
  }

  return filledCount;
};

const medicalRecordArchiveSourceHint = (field: MedicalRecordTemplateField) =>
  medicalRecordArchiveSyncKeys.has(field.key) ? "来自健康管理档案，医生可修改为最终采用文本" : "";

const archivePrecheckIssues = computed(() => fieldIssues.value.slice(0, 12));

const recordSignature = computed(() => {
  const seed = `${fieldValues.visitNo}-${completionStats.value.completed}-${currentAttachments.value.length}-${archiveVersion.value}`;

  return Array.from(seed)

    .reduce((sum, char) => sum + char.charCodeAt(0), 0)

    .toString(16)

    .toUpperCase();
});

const clinicalArchiveSummary = computed(() => [
  { label: "诊疗字段", value: formatSectionProgress(clinicalArchiveSections.value) },

  { label: "主要诊断", value: fieldValues.westernDiagnosis || fieldValues.tcmDiagnosis || "待补充" },

  { label: "治疗方案", value: fieldValues.operationName || fieldValues.treatmentType || "待补充" },

  { label: "检查附件", value: `${sectionEvidenceCountForSections(clinicalArchiveSections.value)} 份` },

  { label: "治疗时序", value: fieldValues.courseSchedule ? "已记录" : "待补充" },

  { label: "生成时间", value: generatedAt.value }
]);

const managementArchiveSummary = computed(() => [
  { label: "管理字段", value: formatSectionProgress(managementArchiveSections.value) },

  { label: "来院来源", value: fieldValues.arrivalPath || fieldValues.sourceChannel || "待补充" },

  { label: "当前关注", value: archiveFocusSummary.value },

  { label: "最近复查", value: latestFollowupRecord.value?.date || "待记录" },

  { label: "下次随访", value: fieldValues.nextFollowupAt || latestFollowupRecord.value?.nextDate || "待安排" },

  { label: "风险提示", value: fieldValues.relationshipRisk || fieldValues.patientConcerns || "暂无" }
]);

const auditModuleOptions: Record<string, string> = {
  patient: "患者",

  record: "档案",

  document: "附件",

  collaboration: "协作补充",

  archive: "归档",

  template: "模板",

  system: "系统"
};

const auditModuleLabel = (module?: string) => auditModuleOptions[module || ""] || module || "旧日志";

const auditTimelineType = (log: AuditLogRow) =>
  log.result === "denied" ? "danger" : log.module === "archive" ? "warning" : "primary";

const timelineSourceOptions: Record<string, string> = {
  patient: "患者",

  encounter: "就诊",

  record: "字段",

  followup: "复查",

  document: "附件",

  archive: "归档",

  audit: "日志",

  system: "系统"
};

const timelineSourceLabel = (source?: string) => timelineSourceOptions[source || ""] || source || "记录";

const timelineDisplayTime = (time?: string) => (time && !time.startsWith("0000-00-00") ? time : "时间待补");

const timelineTagType = (event: PatientTimelineEvent) =>
  event.level === "danger" ? "danger" : event.level === "warning" ? "warning" : event.level === "success" ? "success" : "info";

const previewTimelineEvents = computed(() => {
  const importantSources = new Set(["patient", "encounter", "followup", "document", "collaboration", "archive"]);

  const important = patientTimelineEvents.value.filter(event => importantSources.has(event.source));

  return (important.length ? important : patientTimelineEvents.value).slice(0, 8);
});

const shortTitle = (title: string) => title.replace(/^.*?、/, "");

const isEditable = (field: RecordField) =>
  field.enabled !== false && (currentRole.value === "admin" || ensureArray(field.editors).includes(currentRole.value));

const canEditRecordSection = (section: RecordSection) => section.fields.some(isEditable);

const myEditableFields = computed(() =>
  recordSectionsByRule.value.flatMap(section => section.fields.filter(isEditable).map(field => ({ section, field })))
);

const coreFieldKeys = new Set([
  "patientName",

  "gender",

  "age",

  "phone",

  "visitNo",

  "archiveCreatedAt",

  "admissionDate",

  "departmentName",

  "healthManager",

  "attendingDoctor",

  "responsibleNurse",

  "overallRiskLevel",

  "arrivalPath",

  "sourceChannel",

  "visitMotivation",

  "chiefComplaintText",

  "presentIllnessText",

  "westernDiagnosis",

  "tcmDiagnosis",

  "tcmSyndrome",

  "operationName",

  "surgeryFeasibility",

  "sameDayTreatment",

  "ecgStatus",

  "uncheckedItemsNote",

  "followupRecordsJson",

  "recoverySummary",

  "nextFollowupAt",

  "relationshipRisk",

  "patientConcerns",

  "patientPainLevel",

  "patientSatisfaction"
]);

const recommendedSectionKeys = new Set([
  "arrivalSource",

  "specialNeeds",

  "familyRelationship",

  "trustCooperation",

  "patientFeedback",

  "tcmHealthManagement",

  "supplementNotes",

  "treatmentPlanManagement"
]);

const optionalHeavySectionKeys = new Set([
  "history",

  "tcmInspection",

  "secondaryDiagnosis",

  "comorbidityTcm",

  "dip",

  "qualityCheck"
]);

type FieldLayer = "core" | "recommended" | "optional";

const fieldLayerOf = (section: RecordSection, field: RecordField): FieldLayer => {
  if (field.required || coreFieldKeys.has(field.key) || field.key.endsWith("Status")) return "core";

  if (optionalHeavySectionKeys.has(section.key)) return "optional";

  if (recommendedSectionKeys.has(section.key)) return "recommended";

  if (field.kind === "textarea" && !field.evidence) return "optional";

  return "recommended";
};

const fieldLayerOptions: Record<FieldLayerMode, { label: string; title: string; desc: string }> = {
  core: {
    label: "核心",

    title: "核心工作台",

    desc: "只显示建档、诊疗结论、检查状态、风险和随访闭环等关键字段，先把主线跑通。"
  },

  recommended: {
    label: "推荐",

    title: "推荐补充",

    desc: "显示核心字段加上患者关系、诉求、反馈和中医管理等高价值补充信息。"
  },

  optional: {
    label: "按需",

    title: "按需展开",

    desc: "显示低频、长文本、归档参考类字段，需要精细补录时再处理。"
  },

  all: {
    label: "全部",

    title: "全部可填字段",

    desc: "显示当前岗位可编辑的全部字段，用于集中补录或管理员核对。"
  }
};

const fieldLayerLabel = computed(() => fieldLayerOptions[fieldLayerMode.value].label);

const fieldLayerTitle = computed(() => fieldLayerOptions[fieldLayerMode.value].title);

const fieldLayerDescription = computed(() => fieldLayerOptions[fieldLayerMode.value].desc);

const fieldLayerStats = computed(() =>
  myEditableFields.value.reduce(
    (stats, item) => {
      stats[fieldLayerOf(item.section, item.field)] += 1;

      return stats;
    },

    { core: 0, recommended: 0, optional: 0 }
  )
);

const layeredEditableFields = computed(() => {
  if (fieldLayerMode.value === "all") return myEditableFields.value;

  if (fieldLayerMode.value === "recommended") {
    return myEditableFields.value.filter(item => {
      const layer = fieldLayerOf(item.section, item.field);

      return layer === "core" || layer === "recommended";
    });
  }

  return myEditableFields.value.filter(item => fieldLayerOf(item.section, item.field) === fieldLayerMode.value);
});

const myRequiredMissingCount = computed(
  () => layeredEditableFields.value.filter(item => item.field.required && !isRecordFieldComplete(item.field)).length
);

const myFieldIssues = computed(() => {
  const editableKeys = new Set(layeredEditableFields.value.map(item => item.field.key));

  return fieldIssues.value.filter(issue => editableKeys.has(issue.fieldKey));
});

const firstWorkflowIssue = computed(() => myFieldIssues.value[0] || fieldIssues.value[0]);

const workflowHint = computed<WorkflowHint>(() => {
  if (!recordSectionsByRule.value.length) return emptyWorkflowHint;

  const stage = activeLifecycleStage.value;

  const nextStage = nextLifecycleStage.value;

  if (myFieldIssues.value.length) {
    const issue = myFieldIssues.value[0];

    return {
      visible: true,

      level: myFieldIssues.value.some(issue => issue.level === "invalid") ? "danger" : "warning",

      title: `${roleName.value}需处理：${stage.title}`,

      desc: `当前环节：${stage.department}，下一责任：${nextStage.owner}。${issue.sectionTitle}：${issue.fieldLabel} - ${issue.message}`
    };
  }

  if (fieldIssues.value.length) {
    const issue = fieldIssues.value[0];

    return {
      visible: true,

      level: "info",

      title: `当前流转：${stage.title}`,

      desc: `本岗位已处理，等待${stage.owner}补齐。${issue.sectionTitle}：${issue.fieldLabel} - ${issue.message}`
    };
  }

  return {
    visible: true,

    level: "success",

    title: "生命周期已闭环，可提交档案审核",

    desc: `已完成 ${lifecycleProgress.value.completed}/${lifecycleProgress.value.total} 个院内环节，保存后可预览、打印或提交质控归档。`
  };
});

const isLabMetricField = (field: RecordField) => Boolean(field.labPanel);

const fileToDataUrl = (file: File) =>
  new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ""));
    reader.onerror = () => reject(reader.error || new Error("文件读取失败"));
    reader.readAsDataURL(file);
  });

const uploadFieldAttachments = async (field: RecordField, files: File[], remark = "") => {
  const validFiles = files.filter(file => file.type.startsWith("image/") || file.type.startsWith("video/"));
  if (!validFiles.length) {
    ElMessage.warning("请选择图片或视频文件");
    return;
  }

  try {
    const documents = await Promise.all(
      validFiles.map(async file => ({
        type: field.key === "inspectionImages" ? "inspectionImage" : field.key,
        typeLabel: field.label,
        fileName: file.name,
        contentDataUrl: await fileToDataUrl(file),
        remark
      }))
    );

    const { data } = await uploadDocumentsApi({
      patientId: patientId.value,
      role: currentRole.value,
      operator: roleName.value,
      sourceRole: field.key === "inspectionImages" ? "inspection" : currentRole.value,
      batchId: `${field.key}-${patientId.value}-${Date.now()}`,
      batchName: `${field.label}-${new Date().toLocaleString()}`,
      autoClassify: false,
      remark,
      documents
    });

    currentAttachments.value = [...data.documents, ...currentAttachments.value];
    if (!fieldValues[field.key]) fieldValues[field.key] = `${field.label}已上传 ${validFiles.length} 份`;
    ElMessage.success(`已上传 ${validFiles.length} 份${field.label}`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "上传失败，请稍后重试");
  }
};

const labMetricStatusFields: Record<string, string[]> = {
  bloodRoutine: ["bloodRoutineStatus"],

  coagulation: ["coagulationStatus"],

  preOpEight: ["preOpEightStatus"],

  urineRoutine: ["urineRoutineStatus"],

  biochemistry: ["liverFunctionStatus", "renalFunctionStatus", "fastingGlucoseStatus", "bloodLipidStatus"],

  ecgResult: ["ecgStatus"],

  colonoscopy: ["colonoscopyStatus"]
};

const uncheckedStatusValues = new Set(["", "未查", "鏈煡"]);

const pendingLabResultHints = ["待回报", "待补", "未查", "寰呭洖鎶", "寰呰ˉ", "鏈煡"];

const checkedStatusValue = "已查";

const updateMedicalRecordField = (key: string, value: string) => {
  fieldValues[key] = value;
};

const updateLabMetricField = (field: RecordField, value: string) => {
  fieldValues[field.key] = value;

  const normalized = String(value || "").trim();

  if (!isFieldComplete(normalized) || pendingLabResultHints.some(hint => normalized.includes(hint))) return;

  const statusFields = labMetricStatusFields[field.key] || (field.labPanel ? labMetricStatusFields[field.labPanel] : []);

  statusFields?.forEach(statusField => {
    const currentStatus = String(fieldValues[statusField] || "").trim();

    if (uncheckedStatusValues.has(currentStatus)) fieldValues[statusField] = checkedStatusValue;
  });
};

const selectOptions = (field: RecordField) => {
  if (isLabMetricField(field)) return [];

  if (field.kind === "select") return ensureArray(field.options);

  if (["date", "number", "tel"].includes(field.inputType || "")) return [];

  return ensureArray(field.options).length ? ensureArray(field.options) : fieldPresets[field.key] || [];
};

const fieldAssistText = (field: RecordField) => {
  if (!isEditable(field)) {
    const editors = ensureArray(field.editors);

    const isServiceCollaborative =
      serviceCollaborators.length === editors.length && serviceCollaborators.every(role => editors.includes(role));

    return isServiceCollaborative ? "协作字段：服务相关岗位可补充" : `锁定：${editorLabels(editors) || "指定岗位"}`;
  }

  if (isLabMetricField(field)) return "指标面板自动生成文本";

  if (firstVisitCollaborativeFieldKeys.has(field.key)) return "前台初填，医生审核";

  if (field.inputType === "date") return "YYYY-MM-DD";

  if (field.inputType === "number") {
    const range = [field.min, field.max].filter(value => value !== undefined).join("-");

    return range ? `${range}${field.unit || ""}` : field.unit || "数字";
  }

  if (field.inputType === "tel") return "11位手机号";

  if (field.pattern) return field.validationMessage || "按固定格式填写";

  const count = selectOptions(field).length;

  return count ? `${count} 个常用项` : "可手填";
};

const {
  previewVisible,
  previewActivePage,
  printPreflightVisible,
  previewNavigation,
  coverPreviewPage,
  registrationPreviewPage,
  clinicalPreviewPage,
  managementPreviewPage,
  attachmentIndexPreviewPage,
  attachmentPreviewStartPage,
  previewPageCount,
  printPreflightItems,
  scrollPreviewPage,
  executePrint,
  printRecord,
  openPreviewThenPrint,
  resetPrintPreview
} = useRecordPrintPreview({
  fieldValues,
  clinicalArchiveSections,
  managementArchiveSections,
  auditArchiveSections,
  followupRecords,
  currentAttachments,
  fieldIssues,
  invalidAttachmentCount,
  autoSaveStatus,
  matchedAttachments,
  logPrintAction: () => logPrintAction()
});

const visitTypeForLifecycle = computed(() => currentVisitType.value || patientInfo.value?.visitType || fieldValues.admissionWay);

const activeLifecycleStages = computed(() =>
  patientLifecycleStages.filter(stage => !isLifecycleStageSkipped(stage, visitTypeForLifecycle.value))
);

const lifecycleStageForSection = (section: RecordSection) =>
  patientLifecycleStages.find(stage => ensureArray(stage.sectionKeys).includes(section.key));

const incompleteLifecycleIndex = computed(() =>
  activeLifecycleStages.value.findIndex(stage => {
    const sections = recordSectionsByRule.value.filter(section => ensureArray(stage.sectionKeys).includes(section.key));

    if (!sections.length) return false;

    return sections.some(section => sectionRequiredMissingCount(section) > 0 || !isSectionMeaningful(section));
  })
);

const firstIncompleteLifecycleIndex = computed(() =>
  incompleteLifecycleIndex.value >= 0 ? incompleteLifecycleIndex.value : activeLifecycleStages.value.length - 1
);

const activeLifecycleStage = computed(
  () =>
    activeLifecycleStages.value[firstIncompleteLifecycleIndex.value] ||
    activeLifecycleStages.value[0] ||
    patientLifecycleStages[0]
);

const nextLifecycleStage = computed(
  () => activeLifecycleStages.value[firstIncompleteLifecycleIndex.value + 1] || activeLifecycleStage.value
);

const lifecycleProgress = computed(() => {
  const total = activeLifecycleStages.value.length || patientLifecycleStages.length;

  const isComplete = completionPercent.value >= 100 || String(patientInfo.value?.currentStage || "").includes("归档");

  const completed =
    incompleteLifecycleIndex.value < 0 ? total : Math.min(firstIncompleteLifecycleIndex.value + (isComplete ? 1 : 0), total);

  return {
    completed,

    total,

    percent: total ? Math.round((completed / total) * 100) : 0
  };
});

const patientWorkflowTasks = usePatientWorkflowTasks({
  sections: recordSectionsByRule,
  lifecycleStages: activeLifecycleStages,
  activeLifecycleStage,
  currentRole,
  roleName,
  fieldIssues,
  completionBySection,
  attachmentsByField,
  isFieldComplete: isRecordFieldComplete,
  isFieldEditable: isEditable
});

const patientRolePreviews = usePatientRolePreview({
  sections: recordSectionsByRule,
  medicalRecordSections: medicalRecordFieldSections,
  workflowTasks: patientWorkflowTasks,
  attachmentsByField,
  currentRole,
  fieldValues
});

const patientFieldSearchItems = computed<PatientFieldSearchItem[]>(() => {
  const fieldItems = recordSectionsByRule.value.flatMap(section =>
    section.fields.map(field => {
      const issue = issueForField(field);
      const attachmentCount = matchedAttachments(field.key).length;
      const typeLabel = field.kind === "attachment" || field.evidence ? "附件字段" : "档案字段";
      const status = issue?.message || (attachmentCount ? `附件 ${attachmentCount} 份` : field.required ? "必填字段" : "可定位");

      return {
        key: `field:${field.key}`,
        label: field.label,
        description: `${shortTitle(section.title)} · ${status}`,
        type: "field" as const,
        typeLabel,
        keywords: `${section.title} ${section.department} ${field.key} ${field.printLabel || ""} ${fieldAssistText(field)}`
      };
    })
  );

  const attachmentItems = recordSectionsByRule.value.flatMap(section =>
    section.fields
      .filter(field => field.kind === "attachment" || field.evidence)
      .map(field => ({
        key: `attachment:${field.key}`,
        label: field.label,
        description: `${shortTitle(section.title)} · ${matchedAttachments(field.key).length || "待补"} 份证据`,
        type: "attachment" as const,
        typeLabel: "附件证据",
        keywords: `${section.title} ${section.department} ${field.key} ${field.printLabel || ""}`
      }))
  );

  const stageItems = patientWorkflowTasks.value.stageNodes.map(stage => ({
    key: `stage:${stage.key}`,
    label: stage.title,
    description: `${stage.department} · ${stage.completed}/${stage.total} · 待补 ${stage.missing}`,
    type: "stage" as const,
    typeLabel: "流程节点",
    keywords: `${stage.shortTitle} ${stage.owner} ${stage.department} ${stage.key}`
  }));

  return [...fieldItems, ...attachmentItems, ...stageItems];
});

const patientAssistantPrompt = computed(
  () => "请根据当前患者档案，帮我总结重点、缺失项、附件证据和下一步处理建议。请只给院内辅助建议，不要替代诊断。"
);

const patientAssistantAttachmentIds = computed(() =>
  currentAttachments.value

    .filter(attachment => attachment.status !== "voided")

    .map(attachment => attachment.key || attachment.storagePath || attachment.fileName)

    .filter(Boolean)
);

const patientAssistantContext = computed<Record<string, unknown>>(() => ({
  role: currentRole.value,

  roleName: roleName.value,

  patientId: patientId.value,

  patientName: fieldValues.patientName || patientInfo.value?.name || "",

  visitNo: fieldValues.visitNo || patientInfo.value?.visitNo || patientId.value,

  visitType: currentVisitType.value,

  currentStage: patientInfo.value?.currentStage || activeLifecycleStage.value.title,

  activeLifecycleStage: {
    title: activeLifecycleStage.value.title,

    owner: activeLifecycleStage.value.owner,

    department: activeLifecycleStage.value.department,

    nextOwner: nextLifecycleStage.value.owner
  },

  completion: {
    percent: completionPercent.value,

    completed: completionStats.value.completed,

    total: completionStats.value.total,

    lifecycleCompleted: lifecycleProgress.value.completed,

    lifecycleTotal: lifecycleProgress.value.total
  },

  workflowHint: workflowHint.value,

  missingOrInvalidItems: fieldIssues.value.slice(0, 30).map(issue => ({
    section: issue.sectionTitle,

    field: issue.fieldLabel,

    level: issue.level,

    message: issue.message
  })),

  attachments: currentAttachments.value.slice(0, 30).map(attachment => ({
    key: attachment.key,

    title: attachment.title,

    field: attachment.fieldLabel,

    department: attachment.department,

    fileName: attachment.fileName,

    status: attachment.status || "active",

    uploadedAt: attachment.uploadedAt
  })),

  archive: {
    submitted: archiveSubmitted.value,

    version: archiveVersion.value,

    lastSavedAt: lastSavedAt.value,

    autoSaveStatus: autoSaveStatus.value
  }
}));

const lifecycleRailSummary = computed(
  () => `${activeLifecycleStage.value.department} · ${lifecycleProgress.value.completed}/${lifecycleProgress.value.total} 环`
);

const rolePreferredSectionKeys: Partial<Record<UserRole, string[]>> = {
  frontdesk: ["basic", "arrivalSource"],

  reception: ["chiefComplaint", "presentIllness", "history", "specialNeeds"],

  inspection: ["specialExam", "preOpScreening", "documentScope"],

  lab: ["preOpScreening", "auxiliary"],

  ecg: ["preOpScreening", "auxiliary"],

  ultrasound: ["preOpScreening", "auxiliary"],

  doctor: ["mainDiagnosis", "treatmentPlanManagement", "specialExam"],

  nurse: ["operation", "followup", "specialExam"],

  nursing: ["treatmentPlanManagement", "followup", "patientFeedback"],

  quality: ["qualityCheck", "documentScope", "dip"]
};

const defaultSectionKeyForRole = () => {
  const preferredKeys = rolePreferredSectionKeys[currentRole.value] || [];

  return (
    preferredKeys.find(key => recordSectionsByRule.value.some(section => section.key === key)) ||
    recordSectionsByRule.value.find(section => ensureArray(activeLifecycleStage.value.sectionKeys).includes(section.key))?.key ||
    recordSectionsByRule.value[0]?.key ||
    recordSections[0].key
  );
};

const canExpandFullSection = (section: RecordSection) =>
  currentRole.value === "admin" || currentRole.value === "quality" || canEditRecordSection(section);

const isSectionCollapsed = (section: RecordSection) => ensureArray(collapsedSectionKeys.value).includes(section.key);

const shouldCollapseSection = (section: RecordSection) =>
  !canExpandFullSection(section) || (sectionRequiredMissingCount(section) === 0 && isSectionComplete(section));

const hydrateCollapsedSections = () => {
  collapsedSectionKeys.value = recordSectionsByRule.value.filter(shouldCollapseSection).map(section => section.key);
};

const toggleSectionCollapse = (sectionKey: string) => {
  const collapsedKeys = ensureArray(collapsedSectionKeys.value);

  collapsedSectionKeys.value = collapsedKeys.includes(sectionKey)
    ? collapsedKeys.filter(key => key !== sectionKey)
    : [...collapsedKeys, sectionKey];
};

const sectionStatusType = (section: RecordSection) => {
  if (sectionRequiredMissingCount(section)) return "warning";

  if (isSectionComplete(section)) return "success";

  return canEditRecordSection(section) ? "primary" : "info";
};

const sectionStatusLabel = (section: RecordSection) => {
  if (sectionRequiredMissingCount(section)) return "待补必填";

  if (isSectionComplete(section)) return "已完成";

  return canEditRecordSection(section) ? "当前岗位可填写" : "当前岗位只读";
};

const changeSectionFilter = (key?: string) => {
  activeSectionKey.value = key || recordSectionsByRule.value[0]?.key || recordSections[0].key;
};

const switchSection = (offset: number) => {
  const nextIndex = activeIndex.value + offset;

  if (recordSectionsByRule.value[nextIndex]) activeSectionKey.value = recordSectionsByRule.value[nextIndex].key;
};

const scrollToSection = (sectionKey: string) => {
  activeSectionKey.value = sectionKey;

  document.getElementById(`record-section-${sectionKey}`)?.scrollIntoView({ behavior: "smooth", block: "start" });
};

const focusIssue = async (issue?: FieldIssue) => {
  if (!issue) return;

  detailWorkspaceMode.value = "archive";

  if (activeRoleView.value === "lab") activeRoleView.value = "all";

  activeSectionKey.value = issue.sectionKey;

  if (recordViewMode.value === "full" && ensureArray(collapsedSectionKeys.value).includes(issue.sectionKey)) {
    collapsedSectionKeys.value = collapsedSectionKeys.value.filter(key => key !== issue.sectionKey);
  }

  await nextTick();

  const targetIds =
    recordViewMode.value === "mine"
      ? [`my-field-${issue.fieldKey}`, `section-field-${issue.fieldKey}`, `active-field-${issue.fieldKey}`]
      : [`section-field-${issue.fieldKey}`, `active-field-${issue.fieldKey}`, `my-field-${issue.fieldKey}`];

  const target = targetIds.map(id => document.getElementById(id)).find(Boolean);

  target?.scrollIntoView({ behavior: "smooth", block: "center" });

  highlightedFieldKey.value = issue.fieldKey;

  if (highlightClearTimer) window.clearTimeout(highlightClearTimer);

  highlightClearTimer = window.setTimeout(() => {
    if (highlightedFieldKey.value === issue.fieldKey) highlightedFieldKey.value = "";
  }, 1800);

  const input = target?.querySelector<HTMLElement>("input, textarea, .el-select__wrapper");

  input?.focus();
};

const focusWorkflowTask = async (task: WorkflowFieldTask) => {
  detailWorkspaceMode.value = "archive";

  recordViewMode.value = "full";

  await focusIssue({
    fieldKey: task.fieldKey,
    fieldLabel: task.fieldLabel,
    sectionKey: task.sectionKey,
    sectionTitle: task.sectionTitle,
    message: task.issueMessage || "请处理该字段",
    level: task.issueLevel || "missing"
  });
};

const focusWorkflowAttachment = async (task: WorkflowAttachmentTask) => {
  detailWorkspaceMode.value = "archive";

  recordViewMode.value = "full";

  await focusIssue({
    fieldKey: task.fieldKey,
    fieldLabel: task.fieldLabel,
    sectionKey: task.sectionKey,
    sectionTitle: task.sectionTitle,
    message: task.attachmentCount ? "查看附件证据" : "补充附件证据",
    level: "missing"
  });
};

const focusWorkflowStage = async (stage: WorkflowStageNode) => {
  const targetStage = activeLifecycleStages.value.find(item => item.key === stage.key);

  const targetSectionKey = targetStage?.sectionKeys.find(sectionKey =>
    recordSectionsByRule.value.some(section => section.key === sectionKey)
  );

  if (!targetSectionKey) return;

  detailWorkspaceMode.value = "archive";

  if (activeRoleView.value === "lab") activeRoleView.value = "all";

  recordViewMode.value = "full";

  if (ensureArray(collapsedSectionKeys.value).includes(targetSectionKey)) {
    collapsedSectionKeys.value = collapsedSectionKeys.value.filter(sectionKey => sectionKey !== targetSectionKey);
  }

  await nextTick();

  scrollToSection(targetSectionKey);
};

const openWorkflowRolePreviewEdit = async (preview: WorkflowRolePreview) => {
  if (preview.primaryTarget === "attachments") {
    await switchDetailWorkspace("attachments");
    return;
  }

  if (preview.primaryTarget === "archive") {
    recordViewMode.value = "full";
    await switchDetailWorkspace("archive");
    const targetSectionKey = preview.sectionKeys.find(sectionKey =>
      recordSectionsByRule.value.some(section => section.key === sectionKey)
    );
    if (targetSectionKey) scrollToSection(targetSectionKey);
    return;
  }

  await switchDetailWorkspace("medicalRecord");
};

const openWorkflowRolePreviewAttachments = async () => {
  await switchDetailWorkspace("attachments");
};

const focusWorkflowPreviewField = async (fieldKey: string) => {
  const medicalField = medicalRecordFields.value.find(field => field.key === fieldKey);

  if (medicalField) {
    await switchDetailWorkspace("medicalRecord");

    const activeSections = new Set(medicalRecordActiveSections.value);
    activeSections.add(medicalField.section);
    medicalRecordActiveSections.value = Array.from(activeSections);

    await nextTick();

    const target = document.getElementById(`medical-record-field-${fieldKey}`);
    target?.scrollIntoView({ behavior: "smooth", block: "center" });

    return;
  }

  const archiveContext = findSearchFieldContext(fieldKey);
  if (!archiveContext) return;

  recordViewMode.value = "full";

  await focusIssue({
    fieldKey: archiveContext.field.key,
    fieldLabel: archiveContext.field.label,
    sectionKey: archiveContext.section.key,
    sectionTitle: archiveContext.section.title,
    message: "从流程预览定位到原字段",
    level: "missing"
  });
};

const findSearchFieldContext = (fieldKey: string) => {
  for (const section of recordSectionsByRule.value) {
    const field = section.fields.find(item => item.key === fieldKey);

    if (field) return { section, field };
  }

  return undefined;
};

const selectPatientFieldSearchItem = async (key: string) => {
  const [type, targetKey] = key.split(":") as [PatientFieldSearchType, string];

  if (!targetKey) return;

  if (type === "stage") {
    const stage = patientWorkflowTasks.value.stageNodes.find(item => item.key === targetKey);
    if (stage) await focusWorkflowStage(stage);
    return;
  }

  const context = findSearchFieldContext(targetKey);
  if (!context) return;

  if (type === "attachment") {
    await focusWorkflowAttachment({
      fieldKey: context.field.key,
      fieldLabel: context.field.label,
      sectionKey: context.section.key,
      sectionTitle: context.section.title,
      attachmentCount: matchedAttachments(context.field.key).length,
      required: Boolean(context.field.required || context.field.evidence),
      editable: isEditable(context.field)
    });
    return;
  }

  await focusIssue({
    fieldKey: context.field.key,
    fieldLabel: context.field.label,
    sectionKey: context.section.key,
    sectionTitle: context.section.title,
    message: issueForField(context.field)?.message || "已定位到字段",
    level: issueForField(context.field)?.level || "missing"
  });
};

const handleMoreAction = (command: PatientDetailMoreCommand) => {
  if (command === "preview") {
    previewVisible.value = true;
    return;
  }

  if (command === "upload") {
    openSupplementUpload();
    return;
  }

  if (command === "legacy") {
    router.push("/workbench/legacy");
    return;
  }

  if (command === "quality") {
    openQualityReview();
    return;
  }

  if (command === "timeline") {
    void openAuditTimeline();
    return;
  }

  if (command === "ai") {
    openAiSummary();
    return;
  }

  if (command === "assistant") {
    patientAssistantVisible.value = true;
    return;
  }

  if (command === "medicalRecord") {
    void openMedicalRecord();
    return;
  }

  if (command === "inspection" || command === "lab") {
    openRoleView(command);
    return;
  }

  copyRecord();
};

const focusPrecheckIssue = async (issue?: FieldIssue) => {
  archivePrecheckVisible.value = false;

  recordViewMode.value = "full";

  await focusIssue(issue);
};

const ensureNoBlockingIssues = (scope: "mine" | "all", actionText: string) => {
  const issues = scope === "mine" ? myFieldIssues.value : fieldIssues.value;

  if (!issues.length) return true;

  const issue = issues[0];

  recordViewMode.value = scope === "mine" ? "mine" : "full";

  ElMessage.warning(`${actionText}前请先处理：${issue.fieldLabel} - ${issue.message}`);

  void focusIssue(issue);

  return false;
};

const ensureNoInvalidIssues = (scope: "mine" | "section", actionText: string) => {
  const issues =
    scope === "mine"
      ? myFieldIssues.value
      : fieldIssues.value.filter(
          issue =>
            issue.sectionKey === activeSection.value.key &&
            activeSection.value.fields.some(field => field.key === issue.fieldKey && isEditable(field))
        );

  const invalid = issues.find(issue => issue.level === "invalid");

  if (!invalid) return true;

  ElMessage.warning(`${actionText}前请先修正：${invalid.fieldLabel} - ${invalid.message}`);

  void focusIssue(invalid);

  return false;
};

const saveSection = async (sectionKey: string) => {
  activeSectionKey.value = sectionKey;

  const saved = await saveCurrentSection();

  if (saved) {
    savedSectionKey.value = sectionKey;

    const section = recordSectionsByRule.value.find(item => item.key === sectionKey);

    if (section && shouldCollapseSection(section) && !ensureArray(collapsedSectionKeys.value).includes(sectionKey)) {
      collapsedSectionKeys.value = [...ensureArray(collapsedSectionKeys.value), sectionKey];
    }

    window.setTimeout(() => {
      if (savedSectionKey.value === sectionKey) savedSectionKey.value = "";
    }, 1200);
  }
};

const attachmentLine = (attachment: RecordAttachment, index: number) => {
  return `${index + 1}. ${attachment.fileName}，关联${attachment.fieldLabel}，${attachment.department}上传于${attachment.uploadedAt}`;
};

const formatTextSections = (sections: RecordSection[]) =>
  sections

    .map(section => {
      const lines = section.fields.map(field => `${field.printLabel || field.label}：${printableFieldValue(field)}`);

      return `${section.title}\n${lines.join("\n")}`;
    })

    .join("\n\n");

const buildRecordText = () => {
  const registrationText = [
    "健康管理登记表",

    ...registrationBasicRows.value.map(row => `${row[0]}：${row[1]}；${row[2]}：${row[3]}`),

    "",

    "病情建档",

    ...registrationConditionRows.value.map(row => `${row[0]}：${row[1]}`),

    "",

    "术前检验筛查汇总",

    ...screeningRows.value.map(row => `${row[0]}：${displayFieldValue(row[2], "未查")}；${row[1]}`),

    `未查项目说明：${displayFieldValue("uncheckedItemsNote", "术后按需择期补查")}`,

    "",

    "术后分级复诊台账",

    ...followupRecords.value.map(
      record =>
        `${record.node || record.type}：${record.date || "待预约"}；${record.project || "待补充"}；${record.management || "待补充"}；${
          record.completed || "未完成"
        }`
    )
  ].join("\n");

  const clinicalSections = formatTextSections(clinicalArchiveSections.value);

  const managementSections = formatTextSections(managementArchiveSections.value);

  const auditSections = formatTextSections(auditArchiveSections.value);

  const attachments = currentAttachments.value.map(attachmentLine).join("\n");

  return `${fieldValues.hospitalName}\n${recordTitle.value}\n本档案用于院内患者健康管理、治疗跟踪和复查随访，不替代 HIS 官方病历。\n状态：${archiveStatusText.value}  版本：${archiveVersion.value}  生成时间：${generatedAt.value}\n患者：${fieldValues.patientName}  ${visitNoLabel.value}：${fieldValues.visitNo}  校验码：${recordSignature.value}\n\n${registrationText}\n\n诊疗检查与治疗记录\n${clinicalSections}\n\n患者管理与随访档案\n${managementSections}\n\n档案审核与归档信息\n${auditSections}\n\n附件索引\n${attachments}`;
};

const copyRecord = async () => {
  await navigator.clipboard.writeText(buildRecordText());

  ElMessage.success("健康档案文本已复制");
};

const medicalRecordStatusLabel = (status: GeneratedMedicalRecord["status"]) => {
  const labels: Record<GeneratedMedicalRecord["status"], string> = {
    draft: "草稿",

    finalized: "已定稿",

    voided: "已作废"
  };

  return labels[status] || status;
};

const medicalRecordStatusType = (status: GeneratedMedicalRecord["status"]): "success" | "warning" | "danger" => {
  if (status === "finalized") return "success";

  if (status === "voided") return "danger";

  return "warning";
};

const refreshMedicalRecordVersions = async () => {
  const { data } = await getGeneratedMedicalRecordVersionsApi(patientId.value);

  const versions = ensureArray(data);

  medicalRecordVersions.value = versions;

  if (!currentMedicalRecord.value || !versions.some(record => record.id === currentMedicalRecord.value?.id)) {
    currentMedicalRecord.value = versions[0];
  } else {
    currentMedicalRecord.value = versions.find(record => record.id === currentMedicalRecord.value?.id);
  }
};

const loadMedicalRecordWorkspace = async () => {
  if (!patientId.value) return;

  medicalRecordLoading.value = true;

  try {
    const [{ data: template }] = await Promise.all([getMedicalRecordTemplateApi(), refreshMedicalRecordVersions()]);

    medicalRecordTemplate.value = template;

    medicalRecordUnboundFields.value = ensureArray(template.unboundFields);

    const sections = ensureArray(template.fieldMatrix);

    const previousSections = new Set(medicalRecordActiveSections.value);

    medicalRecordActiveSections.value = previousSections.size
      ? sections.filter(section => previousSections.has(section.section)).map(section => section.section)
      : sections.slice(0, 3).map(section => section.section);

    await precheckMedicalRecord(false);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "目标病历加载失败");
  } finally {
    medicalRecordLoading.value = false;
  }
};

const openMedicalRecord = async () => {
  detailWorkspaceMode.value = "medicalRecord";

  await loadMedicalRecordWorkspace();
};

const precheckMedicalRecord = async (showMessage = true) => {
  if (!patientId.value) return false;

  const syncedCount = syncMedicalRecordDraftFromArchive(false);
  if (syncedCount) await saveMedicalRecordWorkspace(false, false);

  const { data } = await precheckMedicalRecordApi(patientId.value);

  medicalRecordMissingItems.value = ensureArray(data.missingItems);

  medicalRecordUnboundFields.value = ensureArray(data.unboundFields);

  if (data.fieldMatrix) {
    medicalRecordTemplate.value = {
      ...(medicalRecordTemplate.value || {
        name: "目标病历模板",

        configured: false,

        promptConfigurable: false,

        templateSource: "周xx病历模版.docx",

        commandSource: "",

        disclaimer: "",

        sections: []
      }),

      fieldMatrix: data.fieldMatrix
    };
  }

  if (showMessage) {
    if (data.ready) ElMessage.success("目标病历动态字段已满足生成条件");
    else if (medicalRecordUnboundFields.value.length) {
      ElMessage.error(`模板还有 ${medicalRecordUnboundFields.value.length} 个动态字段未绑定占位符`);
    } else ElMessage.warning(`仍有 ${data.missingItems.length} 个必填项待补齐`);
  }

  return data.ready;
};

const saveMedicalRecordWorkspace = async (showMessage = true, syncArchive = true) => {
  if (!patientId.value || !medicalRecordFields.value.length) return false;

  if (!medicalRecordEditableFields.value.length) {
    if (showMessage) ElMessage.warning("当前岗位暂无可保存的目标病历字段");

    return false;
  }

  if (syncArchive) syncMedicalRecordDraftFromArchive(false);

  const { data } = await saveMedicalRecordWorkspaceApi(patientId.value, medicalRecordWorkspaceValues());

  medicalRecordMissingItems.value = ensureArray(data.missingItems);

  if (showMessage) ElMessage.success("目标病历协作字段已保存");

  return true;
};

const fillMedicalRecordBlanksFromArchive = async () => {
  const syncedCount = syncMedicalRecordDraftFromArchive(true);
  if (syncedCount) {
    await saveMedicalRecordWorkspace(false, false);
    await precheckMedicalRecord(false);
  }
};

const generateMedicalRecord = async () => {
  if (!patientId.value) return;

  if (!canGenerateMedicalRecord.value) {
    ElMessage.warning("当前岗位可维护目标病历字段，但生成 docx 需由医生或管理员操作");

    return;
  }

  medicalRecordLoading.value = true;

  try {
    await saveMedicalRecordWorkspace(false);

    const ready = await precheckMedicalRecord(false);

    if (!ready) {
      if (medicalRecordUnboundFields.value.length) {
        ElMessage.error(`模板还有 ${medicalRecordUnboundFields.value.length} 个动态字段未绑定占位符，暂不生成 docx`);

        return;
      }

      ElMessage.warning(`目标病历还有 ${medicalRecordMissingItems.value.length} 个必填项未补齐，暂不生成 docx`);

      return;
    }

    const { data } = await generateMedicalRecordApi(patientId.value);

    medicalRecordMissingItems.value = data.missingItems || [];

    currentMedicalRecord.value = data.record;

    await refreshMedicalRecordVersions();

    currentMedicalRecord.value = data.record;

    ElMessage.success("目标病历 docx 已生成，请医生确认后定稿");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "目标病历生成失败");
  } finally {
    medicalRecordLoading.value = false;
  }
};

const finalizeMedicalRecord = async () => {
  if (!currentMedicalRecord.value) return;

  if (!canGenerateMedicalRecord.value) {
    ElMessage.warning("目标病历定稿需由医生或管理员操作");

    return;
  }

  medicalRecordLoading.value = true;

  try {
    const { data } = await finalizeMedicalRecordApi(currentMedicalRecord.value.id);

    currentMedicalRecord.value = data;

    await refreshMedicalRecordVersions();

    ElMessage.success("目标病历已定稿并锁定");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "目标病历定稿失败");
  } finally {
    medicalRecordLoading.value = false;
  }
};

const voidMedicalRecord = async () => {
  if (!currentMedicalRecord.value) return;

  if (!canGenerateMedicalRecord.value) {
    ElMessage.warning("目标病历作废需由医生或管理员操作");

    return;
  }

  medicalRecordLoading.value = true;

  try {
    const { data } = await voidMedicalRecordApi(currentMedicalRecord.value.id, "医生端确认作废");

    currentMedicalRecord.value = data;

    await refreshMedicalRecordVersions();

    ElMessage.success("目标病历版本已作废");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "目标病历作废失败");
  } finally {
    medicalRecordLoading.value = false;
  }
};

const downloadMedicalRecord = async () => {
  if (!currentMedicalRecord.value) {
    ElMessage.warning("请先生成医生目标病历 docx，再下载/打开");

    return;
  }

  const { msg } = await downloadMedicalRecordApi(currentMedicalRecord.value);

  if (msg) ElMessage.success(msg);
};

const openCurrentMedicalRecord = async () => downloadMedicalRecord();

const selectMedicalRecordVersion = (record: GeneratedMedicalRecord) => {
  currentMedicalRecord.value = record;
};

const switchDetailWorkspace = async (mode: DetailWorkspaceMode) => {
  detailWorkspaceMode.value = mode;

  if (mode === "medicalRecord") {
    await loadMedicalRecordWorkspace();
  } else if (mode === "timeline") {
    await refreshAuditTimeline();
  }
};

const openRoleView = async (view: RoleViewKey) => {
  activeRoleView.value = view;
  recordViewMode.value = "full";
  await switchDetailWorkspace("archive");
};

const openLabReportWorkbench = () => {
  router.push({
    path: "/workbench/lab-report",
    query: { patientId: patientInfo.value?.id || patientId.value }
  });
};

const loadPatientAuditLogs = async (targetPatientId = patientId.value, targetPatientName = fieldValues.patientName) => {
  auditLoading.value = true;

  try {
    const { data } = await getAuditLogListApi({ pageNum: 1, pageSize: 80, patientId: targetPatientId });

    const legacyLogs = targetPatientName
      ? await getAuditLogListApi({ pageNum: 1, pageSize: 80, patient: targetPatientName })
      : null;

    if (targetPatientId !== patientId.value) return;

    const knownIds = new Set(data.list.map(log => log.id));

    const legacyOnly = (legacyLogs?.data.list || []).filter(log => !log.patientId && !knownIds.has(log.id));

    patientAuditLogs.value = [...data.list, ...legacyOnly].sort((left, right) => right.time.localeCompare(left.time));
  } finally {
    if (targetPatientId === patientId.value) auditLoading.value = false;
  }
};

const loadPatientTimeline = async (targetPatientId = patientId.value) => {
  if (!targetPatientId) {
    patientTimelineEvents.value = [];

    return;
  }

  try {
    const { data } = await getPatientTimelineApi(targetPatientId);

    if (targetPatientId !== patientId.value) return;

    patientTimelineEvents.value = data;
  } catch {
    if (targetPatientId === patientId.value) patientTimelineEvents.value = [];
  }
};

const openAuditTimeline = async () => {
  auditTimelineVisible.value = true;

  await Promise.all([loadPatientAuditLogs(), loadPatientTimeline()]);
};

const refreshAuditTimeline = () => {
  void Promise.all([loadPatientAuditLogs(), loadPatientTimeline()]);
};

const openSupplementUpload = () => {
  router.push({
    path: "/workbench/upload",

    query: {
      patientId: patientInfo.value?.id || patientId.value,

      keyword: fieldValues.visitNo || patientInfo.value?.visitNo || patientId.value
    }
  });
};

const openQualityReview = () => {
  router.push({
    path: "/audit/review",

    query: {
      patientId: patientInfo.value?.id || patientId.value,

      keyword: fieldValues.visitNo || patientInfo.value?.visitNo || fieldValues.patientName || patientId.value
    }
  });
};

let patientDetailLoadSeq = 0;

let autoSaveScheduleToken = 0;

let lastDetailErrorKey = "";

let keydownListenerActive = false;

const cancelPendingPatientDetailWork = () => {
  patientDetailLoadSeq += 1;

  autoSaveScheduleToken += 1;
};

const notifyLoadPatientError = (targetPatientId: string, message: string) => {
  const errorKey = `${targetPatientId || "missing"}:${message}`;

  const now = Date.now();

  const lastGlobalErrorAt = detailErrorCooldowns.get(errorKey) || 0;

  if (lastDetailErrorKey === errorKey || now - lastGlobalErrorAt < DETAIL_ERROR_COOLDOWN_MS) return;

  lastDetailErrorKey = errorKey;

  detailErrorCooldowns.set(errorKey, now);

  ElMessage.error(message);
};

const resetLoadPatientErrorNotification = () => {
  if (detailError.value) detailErrorCooldowns.delete(`${patientId.value || "missing"}:${detailError.value}`);

  lastDetailErrorKey = "";
};

const resetPatientDetailState = () => {
  autoSaveScheduleToken += 1;

  isHydratingRecord.value = true;

  saving.value = false;

  Object.assign(fieldValues, createEmptyFieldValues());

  syncFollowupRecordsFromField();

  patientInfo.value = undefined;

  currentAttachments.value = [];

  patientAuditLogs.value = [];

  patientTimelineEvents.value = [];

  archiveSubmitted.value = false;

  archiveVersion.value = "V0.3-预归档";

  generatedAt.value = "";

  savedSectionKey.value = "";

  highlightedFieldKey.value = "";

  resetArchiveAutosave();

  resetPrintPreview();

  archivePrecheckVisible.value = false;

  auditTimelineVisible.value = false;

  voidDialogVisible.value = false;

  voidTarget.value = undefined;

  voidReason.value = "";

  collapsedSectionKeys.value = [];

  revokeAttachmentBlobUrls();

  const routeSection = String(route.query.section || "");

  activeSectionKey.value = routeSection || defaultSectionKeyForRole();
};

const loadPatientDetail = () =>
  traceAsync("patient.detail.load", async () => {
    const loadSeq = ++patientDetailLoadSeq;

    const targetPatientId = patientId.value;

    detailLoading.value = true;

    detailError.value = "";

    resetPatientDetailState();

    if (!targetPatientId) {
      detailError.value = "缺少患者ID，请从患者列表或今日患者重新打开健康档案";

      detailLoading.value = false;

      isHydratingRecord.value = false;

      return;
    }

    try {
      const [{ data }, { data: rules }] = await Promise.all([getPatientDetailApi(targetPatientId), getTemplateFieldRulesApi()]);

      if (loadSeq !== patientDetailLoadSeq) return;

      lastDetailErrorKey = "";

      templateRules.value = rules;

      const targetSection = String(route.query.section || "");

      patientInfo.value = data.patient;

      Object.assign(fieldValues, data.fieldValues || {});

      detailWorkspaceMode.value = "flow";

      if (medicalRecordFirstRoles.has(currentRole.value)) void loadMedicalRecordWorkspace();

      if (targetSection && recordSectionsByRule.value.some(section => section.key === targetSection)) {
        activeSectionKey.value = targetSection;
      } else if (!recordSectionsByRule.value.some(section => section.key === activeSectionKey.value)) {
        activeSectionKey.value = defaultSectionKeyForRole();
      } else if (!targetSection) {
        activeSectionKey.value = defaultSectionKeyForRole();
      }

      syncFollowupRecordsFromField();

      currentAttachments.value = ensureArray(data.attachments);

      preloadAttachmentPreviews(currentAttachments.value);

      archiveSubmitted.value = data.archiveSubmitted;

      archiveVersion.value = data.archiveVersion;

      generatedAt.value = data.generatedAt;

      hydrateCollapsedSections();

      await Promise.all([
        loadPatientAuditLogs(targetPatientId, data.fieldValues.patientName || data.patient.name),

        loadPatientTimeline(targetPatientId)
      ]);

      if (loadSeq !== patientDetailLoadSeq) return;

      if (targetSection) window.setTimeout(() => scrollToSection(targetSection), 120);
    } catch (error) {
      if (loadSeq !== patientDetailLoadSeq) return;

      detailError.value = (error as Error).message || "请检查网络后重试";

      notifyLoadPatientError(targetPatientId, detailError.value);
    } finally {
      if (loadSeq === patientDetailLoadSeq) {
        detailLoading.value = false;

        window.setTimeout(() => {
          if (loadSeq === patientDetailLoadSeq) isHydratingRecord.value = false;
        }, 0);
      }
    }
  });

const retryLoadPatientDetail = () => {
  resetLoadPatientErrorNotification();

  loadPatientDetail();
};

const isConflictError = (error: unknown) => {
  const message = String((error as Error)?.message || "");

  return (
    message.includes("409") || message.includes("冲突") || message.includes("已被其他终端更新") || message.includes("revision")
  );
};

const saveRecordValues = async (values: Record<string, string>, successText: string) => {
  saving.value = true;

  try {
    const { data } = await savePatientRecordApi({
      id: patientId.value,

      role: currentRole.value,

      operator: roleName.value,

      values
    });

    await Promise.all([loadPatientAuditLogs(), loadPatientTimeline()]);

    clearLocalDraft();

    markRecordSaved(values);

    const issueCount = data.issues.length;

    ElMessage.success(issueCount ? `已保存，仍有 ${issueCount} 个必填字段待补` : successText);

    return true;
  } catch (error) {
    if (isConflictError(error)) {
      persistLocalDraft(values);

      markRecordConflict();

      ElMessage.warning("检测到其他终端已更新，当前填写已保存到本机草稿");

      return false;
    }

    markRecordSaveError(error);

    ElMessage.error((error as Error).message);

    return false;
  } finally {
    saving.value = false;
  }
};

const currentSectionValues = () =>
  activeSection.value.fields.reduce<Record<string, string>>((payload, field) => {
    if (isEditable(field)) payload[field.key] = fieldValues[field.key];

    return payload;
  }, {});

const myFieldValues = () =>
  layeredEditableFields.value.reduce<Record<string, string>>((payload, item) => {
    payload[item.field.key] = fieldValues[item.field.key];

    return payload;
  }, {});

const saveCurrentSection = async () => {
  if (!ensureNoInvalidIssues("section", "保存章节")) return false;

  return saveRecordValues(currentSectionValues(), "当前章节已保存");
};

const saveMyFields = async () => {
  if (!ensureNoInvalidIssues("mine", "保存当前层内容")) return false;

  return saveRecordValues(myFieldValues(), "当前层内容已保存");
};

const saveLabSupplementFields = async () => {
  const values: Record<string, string> = {
    hpTestStatus: fieldValues.hpTestStatus || ""
  };

  if (canEditLabSupplementNote.value) values.uncheckedItemsNote = fieldValues.uncheckedItemsNote || "";

  return saveRecordValues(values, "化验室补充信息已保存");
};

const saveMyFieldsAndBack = async () => {
  if (!ensureNoBlockingIssues("mine", "返回看板")) return;

  const saved = await saveMyFields();

  if (saved) {
    cancelPendingPatientDetailWork();

    router.push("/encounters/active");
  }
};

const saveActiveMode = () => {
  if (detailWorkspaceMode.value === "medicalRecord") return saveMedicalRecordWorkspace();

  if (detailWorkspaceMode.value !== "archive") return Promise.resolve(false);

  return recordViewMode.value === "mine" ? saveMyFields() : saveCurrentSection();
};

const submitArchive = async () => {
  if (fieldIssues.value.length) {
    recordViewMode.value = "full";

    archivePrecheckVisible.value = true;

    return;
  }

  const saved = await saveActiveMode();

  if (!saved) return;

  try {
    const { data } = await submitArchiveApi({ id: patientId.value, role: currentRole.value, operator: roleName.value });

    archiveSubmitted.value = data.archive.submitted;

    archiveVersion.value = data.archive.version;

    generatedAt.value = data.archive.generatedAt;

    await Promise.all([loadPatientAuditLogs(), loadPatientTimeline()]);

    ElMessage.success("已提交档案审核，流程状态已更新");
  } catch (error) {
    ElMessage.error((error as Error).message);
  }
};

const revokeArchive = async () => {
  try {
    const { data } = await revokeArchiveApi({ id: patientId.value, role: currentRole.value, operator: roleName.value });

    archiveSubmitted.value = data.archive.submitted;

    archiveVersion.value = data.archive.version;

    generatedAt.value = data.archive.generatedAt;

    await Promise.all([loadPatientAuditLogs(), loadPatientTimeline()]);

    ElMessage.info("已撤回草稿，可继续修改本岗位内容");
  } catch (error) {
    ElMessage.error((error as Error).message);
  }
};

const openVoid = (attachment: RecordAttachment) => {
  voidTarget.value = attachment;

  voidReason.value = "";

  voidDialogVisible.value = true;
};

const confirmVoidDocument = async () => {
  if (!voidTarget.value) return;

  voiding.value = true;

  try {
    await voidDocumentApi({
      patientId: patientId.value,

      documentKey: voidTarget.value.key,

      reason: voidReason.value,

      role: currentRole.value,

      operator: roleName.value
    });

    voidDialogVisible.value = false;

    ElMessage.success("附件已作废，已移入资料回收站");

    await loadPatientDetail();

    await loadPatientTimeline();
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    voiding.value = false;
  }
};

const fallbackLogo = () => {
  logoIndex.value += 1;

  if (logoCandidates[logoIndex.value]) {
    logoSrc.value = logoCandidates[logoIndex.value];

    return;
  }

  logoVisible.value = false;
};

const logPrintAction = async () => {
  try {
    await logPatientExportApi({ id: patientId.value, role: currentRole.value, operator: roleName.value, action: "print" });

    await Promise.all([loadPatientAuditLogs(), loadPatientTimeline()]);
  } catch (error) {
    ElMessage.warning(`打印留痕失败，已继续打印：${(error as Error).message}`);
  }
};

const debouncedAutoSave = useDebounceFn(async (scheduleToken: number, targetPatientId: string) => {
  if (scheduleToken !== autoSaveScheduleToken || targetPatientId !== patientId.value) return;

  if (saving.value || archiveSubmitted.value || isHydratingRecord.value || detailLoading.value || detailError.value) return;

  if (detailWorkspaceMode.value !== "archive") return;

  autoSaveStatus.value = "saving";

  const values = recordViewMode.value === "mine" ? myFieldValues() : currentSectionValues();

  try {
    if (scheduleToken !== autoSaveScheduleToken || targetPatientId !== patientId.value) return;

    await savePatientRecordApi({ id: targetPatientId, role: currentRole.value, operator: roleName.value, values });

    if (scheduleToken !== autoSaveScheduleToken || targetPatientId !== patientId.value) return;

    clearLocalDraft();

    markRecordSaved(values);

    window.setTimeout(() => {
      if (scheduleToken === autoSaveScheduleToken && targetPatientId === patientId.value && autoSaveStatus.value === "saved") {
        autoSaveStatus.value = "idle";
      }
    }, 3000);
  } catch (error) {
    if (scheduleToken !== autoSaveScheduleToken || targetPatientId !== patientId.value) return;

    if (isConflictError(error)) {
      persistLocalDraft(values);

      markRecordConflict();

      return;
    }

    markRecordSaveError(error);
  }
}, 2000);

watch(fieldValues, () => {
  if (isHydratingRecord.value || detailLoading.value || detailError.value) return;

  const hasEditableField =
    detailWorkspaceMode.value === "medicalRecord"
      ? medicalRecordEditableFields.value.length > 0
      : detailWorkspaceMode.value === "archive" &&
        (recordViewMode.value === "mine" ? myEditableFields.value.length > 0 : activeSection.value.fields.some(isEditable));

  if (hasEditableField) debouncedAutoSave(autoSaveScheduleToken, patientId.value);
});

watch(
  () => route.params.id,

  (id, oldId) => {
    if (String(id || "") === String(oldId || "")) return;

    loadPatientDetail();
  }
);

watch(
  () => route.query.section,

  section => {
    const targetSection = String(section || "");

    if (targetSection && recordSectionsByRule.value.some(item => item.key === targetSection)) {
      window.setTimeout(() => scrollToSection(targetSection), 80);
    }
  }
);

const handleKeydown = (event: KeyboardEvent) => {
  const isCtrlOrMeta = event.ctrlKey || event.metaKey;

  if (isCtrlOrMeta && event.key === "s") {
    event.preventDefault();

    saveActiveMode();

    return;
  }

  if (isCtrlOrMeta && event.key === "ArrowUp") {
    event.preventDefault();

    switchSection(-1);

    scrollToSection(activeSectionKey.value);

    return;
  }

  if (isCtrlOrMeta && event.key === "ArrowDown") {
    event.preventDefault();

    switchSection(1);

    scrollToSection(activeSectionKey.value);

    return;
  }

  if (isCtrlOrMeta && event.key === "p") {
    event.preventDefault();

    openPreviewThenPrint();

    return;
  }

  if (event.key === "Escape" && previewVisible.value) {
    previewVisible.value = false;
  }
};

const addKeydownListener = () => {
  if (keydownListenerActive) return;

  document.addEventListener("keydown", handleKeydown);

  keydownListenerActive = true;
};

const removeKeydownListener = () => {
  if (!keydownListenerActive) return;

  document.removeEventListener("keydown", handleKeydown);

  keydownListenerActive = false;
};

onMounted(() => {
  loadPatientDetail();

  addKeydownListener();
});

onActivated(() => {
  addKeydownListener();
});

onDeactivated(() => {
  removeKeydownListener();

  cancelPendingPatientDetailWork();
});

onBeforeUnmount(() => {
  removeKeydownListener();

  cancelPendingPatientDetailWork();

  if (highlightClearTimer) window.clearTimeout(highlightClearTimer);

  stopAiSummarySpeech();

  revokeAttachmentBlobUrls();
});
</script>

<style scoped lang="scss">
.record-workspace {
  --record-accent: var(--hos-primary-deep);

  --record-accent-soft: var(--hos-primary-soft);

  --record-fixed: var(--hos-text-secondary);

  --record-fixed-soft: var(--hos-accent-soft);

  --record-warning: var(--hos-status-warning);

  --record-warning-soft: var(--hos-status-warning-soft);

  --record-danger: #dc2626;

  display: block;

  color: var(--hos-text-primary);
}

.record-workspace > .table-box {
  width: 100%;

  min-width: 0;

  overflow: auto;

  background: var(--hos-app-bg);
}

.record-workspace > .screen-only {
  display: none;
}

.detail-error-panel {
  display: flex;

  align-items: center;

  justify-content: space-between;

  gap: 12px;

  padding: 14px 16px;

  margin-bottom: 12px;

  background: var(--hos-panel);

  border: 1px solid var(--hos-border);

  border-radius: var(--hos-radius-card);

  box-shadow: var(--hos-shadow-soft);

  div {
    display: grid;

    gap: 4px;
  }

  strong {
    color: var(--hos-text-primary);

    font-size: 15px;
  }

  small {
    color: var(--hos-text-secondary);

    line-height: 1.5;
  }
}

.detail-loading-skeleton {
  display: grid;

  gap: 16px;

  padding: 18px;

  margin-bottom: 12px;

  background: var(--hos-panel);

  border: 1px solid var(--hos-border);

  border-radius: var(--hos-radius-card);

  box-shadow: var(--hos-shadow-soft);
}

.detail-loading-head {
  display: grid;

  max-width: 520px;

  gap: 10px;
}

.record-workbar {
  display: grid;

  gap: 12px;

  padding: 14px 16px;

  margin-bottom: 10px;

  background: var(--hos-panel);

  border: 1px solid var(--hos-border);

  border-radius: var(--hos-radius-card);

  box-shadow: var(--hos-shadow-soft);

  backdrop-filter: blur(16px) saturate(132%);

  -webkit-backdrop-filter: blur(16px) saturate(132%);
}

.detail-workspace-shell {
  display: grid;

  grid-template-columns: 220px minmax(0, 1fr);

  gap: 12px;

  align-items: start;
}

.detail-side-nav {
  position: sticky;

  top: 68px;

  z-index: 8;

  display: grid;

  gap: 8px;

  padding: 10px;

  background: var(--hos-panel);

  border: 1px solid var(--hos-border);

  border-radius: var(--hos-radius-card);

  box-shadow: var(--hos-shadow-soft);

  button {
    display: grid;

    gap: 4px;

    min-width: 0;

    padding: 12px 12px;

    text-align: left;

    cursor: pointer;

    background: var(--hos-glass);

    border: 1px solid var(--hos-border-light);

    border-radius: var(--hos-radius-md);

    transition:
      background-color 0.18s ease,
      border-color 0.18s ease,
      color 0.18s ease;

    strong {
      color: var(--hos-text-primary);

      font-size: 14px;

      line-height: 1.35;
    }

    span {
      overflow: hidden;

      color: var(--hos-text-secondary);

      font-size: 12px;

      line-height: 1.45;

      text-overflow: ellipsis;

      white-space: nowrap;
    }

    &.active {
      background: var(--hos-primary-soft);

      border-color: var(--hos-border-interactive);

      box-shadow: inset 3px 0 0 var(--hos-primary);

      strong {
        color: var(--hos-primary-deep);
      }
    }
  }
}

.detail-workspace-content {
  min-width: 0;
}

.doctor-record-workbench,
.attachment-workbench,
.timeline-workbench {
  display: grid;

  gap: 12px;

  padding-bottom: 24px;
}

.doctor-record-hero {
  display: grid;

  grid-template-columns: minmax(0, 1fr) minmax(240px, 360px);

  gap: 16px;

  align-items: center;

  padding: 16px;

  background: var(--hos-panel);

  border: 1px solid var(--hos-border);

  border-left: 4px solid var(--hos-primary);

  border-radius: var(--hos-radius-card);

  box-shadow: var(--hos-shadow-soft);

  span,
  p {
    color: var(--hos-text-secondary);
  }

  h3,
  p {
    margin: 0;
  }

  h3 {
    margin-top: 4px;

    font-size: 20px;
  }

  p {
    margin-top: 6px;

    line-height: 1.6;
  }
}

.doctor-record-progress {
  display: grid;

  gap: 7px;

  padding: 12px;

  background: var(--hos-glass);

  border: 1px solid var(--hos-border-light);

  border-radius: var(--hos-radius-md);

  strong {
    color: var(--hos-primary-deep);

    font-size: 24px;
  }

  span {
    font-size: 12px;
  }

  i {
    display: block;

    height: 8px;

    overflow: hidden;

    background: var(--hos-primary-muted);

    border-radius: 999px;
  }

  em {
    display: block;

    height: 100%;

    background: var(--hos-primary);

    border-radius: inherit;
  }
}

.medical-record-generator.inline {
  min-height: 420px;
}

.medical-record-focus-strip {
  display: grid;

  grid-template-columns: repeat(3, minmax(0, 1fr));

  gap: 10px;

  article {
    display: grid;

    gap: 4px;

    min-width: 0;

    padding: 12px;

    background: var(--hos-panel);

    border: 1px solid var(--hos-border-light);

    border-radius: var(--hos-radius-md);
  }

  span,
  small {
    color: var(--hos-text-secondary);
  }

  strong,
  small {
    overflow-wrap: anywhere;
  }
}

.attachment-workbench-head {
  display: flex;

  gap: 16px;

  align-items: center;

  justify-content: space-between;

  padding: 16px;

  background: var(--hos-panel);

  border: 1px solid var(--hos-border);

  border-radius: var(--hos-radius-card);

  box-shadow: var(--hos-shadow-soft);

  h3,
  p {
    margin: 0;
  }

  h3 {
    font-size: 18px;
  }

  p {
    margin-top: 4px;

    color: var(--hos-text-secondary);
  }
}

.attachment-workbench-list,
.timeline-workbench-list {
  display: grid;

  gap: 8px;
}

.attachment-workbench-list article,
.timeline-workbench-list article {
  display: flex;

  gap: 14px;

  align-items: center;

  justify-content: space-between;

  padding: 12px 14px;

  background: var(--hos-panel);

  border: 1px solid var(--hos-border-light);

  border-radius: var(--hos-radius-md);

  div {
    display: grid;

    gap: 3px;

    min-width: 0;
  }

  strong,
  p {
    margin: 0;

    overflow-wrap: anywhere;
  }

  span,
  small,
  p {
    color: var(--hos-text-secondary);
  }
}

.timeline-workbench-list article {
  align-items: flex-start;

  justify-content: flex-start;

  > span {
    flex: 0 0 150px;

    color: var(--hos-primary-deep);

    font-size: 13px;

    font-variant-numeric: tabular-nums;
  }
}

.workbar-main {
  display: grid;

  grid-template-columns: minmax(220px, 1fr) minmax(220px, 360px) auto;

  gap: 16px;

  align-items: center;
}

.workbar-title {
  min-width: 0;

  h2 {
    margin: 0;

    color: var(--hos-text-primary);

    font-size: 20px;

    font-weight: 650;

    line-height: 1.35;
  }
}

.workbar-meta {
  display: flex;

  flex-wrap: wrap;

  gap: 8px 12px;

  margin-top: 6px;

  color: var(--hos-text-secondary);

  font-size: 13px;

  span {
    position: relative;

    & + span::before {
      position: absolute;

      top: 50%;

      left: -7px;

      width: 2px;

      height: 2px;

      content: "";

      background: var(--hos-border-interactive);

      border-radius: 999px;

      transform: translateY(-50%);
    }
  }
}

.workbar-progress {
  display: grid;

  gap: 7px;

  div {
    display: flex;

    justify-content: space-between;

    color: var(--hos-text-secondary);

    font-size: 12px;
  }

  strong {
    color: var(--hos-primary-deep);

    font-variant-numeric: tabular-nums;
  }

  i {
    display: block;

    height: 7px;

    overflow: hidden;

    background: var(--hos-primary-muted);

    border-radius: 999px;
  }

  em {
    display: block;

    height: 100%;

    background: var(--hos-primary);

    border-radius: inherit;

    transition: width 0.28s ease;
  }
}

.workbar-actions {
  display: flex;

  flex-wrap: wrap;

  gap: 8px;

  align-items: center;
}

.role-view-select {
  width: 170px;
}

.my-fields-panel {
  display: grid;

  gap: 14px;

  max-width: 1080px;

  padding: 18px;

  margin: 0 auto;

  background: var(--hos-panel);

  border: 1px solid var(--hos-border);

  border-radius: var(--hos-radius-card);

  box-shadow: var(--hos-shadow-soft);

  backdrop-filter: blur(16px) saturate(132%);

  -webkit-backdrop-filter: blur(16px) saturate(132%);
}

.my-fields-head {
  display: flex;

  align-items: flex-start;

  justify-content: space-between;

  gap: 14px;

  padding-bottom: 12px;

  border-bottom: 1px solid var(--hos-border-light);

  h3,
  p {
    margin: 0;
  }

  h3 {
    color: var(--hos-text-primary);

    font-size: 18px;

    font-weight: 650;
  }

  p {
    margin-top: 5px;

    color: var(--hos-text-secondary);
  }
}

.field-issue-banner {
  display: flex;

  align-items: center;

  justify-content: space-between;

  gap: 12px;

  padding: 10px 12px;

  color: #9a3412;

  background: #fff7ed;

  border: 1px solid #fed7aa;

  border-radius: 8px;

  strong,
  span {
    display: block;
  }

  span {
    margin-top: 2px;

    color: #c2410c;

    font-size: 12px;
  }
}

.field-layer-switch {
  display: flex;

  align-items: center;

  justify-content: space-between;

  gap: 12px;

  padding: 10px 12px;

  margin-bottom: 12px;

  background: #f7fbfa;

  border: 1px solid #d8ebe5;

  border-radius: 8px;

  span {
    color: var(--el-text-color-secondary);

    font-size: 13px;

    white-space: nowrap;
  }
}

.nursing-lab-reference {
  display: grid;

  gap: 12px;

  padding: 14px;

  margin-bottom: 14px;

  background: #f8fbff;

  border: 1px solid #dbeafe;

  border-radius: 8px;
}

.nursing-lab-reference-head {
  display: flex;

  gap: 12px;

  align-items: flex-start;

  justify-content: space-between;

  strong,
  span {
    display: block;
  }

  span {
    margin-top: 3px;

    color: var(--el-text-color-secondary);

    font-size: 12px;
  }
}

.my-field-grid {
  display: grid;

  grid-template-columns: repeat(2, minmax(0, 1fr));

  gap: 12px 16px;
}

.my-field-item {
  display: grid;

  gap: 7px;

  min-width: 0;

  padding: 12px;

  background: var(--hos-glass);

  border: 1px solid var(--hos-border-light);

  border-radius: var(--hos-radius-lg);

  transition:
    border-color 160ms ease,
    box-shadow 160ms ease,
    background 160ms ease;

  &:focus-within {
    background: var(--hos-panel-hover);

    border-color: var(--hos-border-interactive);

    box-shadow: var(--hos-shadow-soft);
  }

  &.wide {
    grid-column: 1 / -1;
  }

  &.missing,
  &.invalid {
    background: #fff7ed;

    border-color: #fdba74;

    box-shadow: 0 8px 18px rgb(251 146 60 / 12%);
  }

  &.invalid {
    border-color: #fb923c;
  }

  &.complete {
    background: var(--hos-panel-hover);

    border-color: var(--hos-border-interactive);

    .my-field-label label::after {
      margin-left: 6px;

      color: var(--record-accent);

      content: "已填";

      font-size: 12px;

      font-weight: 400;
    }
  }

  :deep(.el-input__wrapper),
  :deep(.el-select__wrapper),
  :deep(.el-textarea__inner) {
    min-height: 42px;

    background: var(--hos-field-editable-bg);

    border-radius: 8px;

    box-shadow: 0 0 0 1px var(--hos-border) inset;
  }

  :deep(.el-input__wrapper:hover),
  :deep(.el-select__wrapper:hover),
  :deep(.el-textarea__inner:hover) {
    box-shadow: 0 0 0 1px var(--hos-border-interactive) inset;
  }
}

.my-field-label {
  display: flex;

  align-items: baseline;

  justify-content: space-between;

  gap: 10px;

  label {
    color: var(--hos-text-primary);

    font-weight: 650;
  }

  sup {
    margin-left: 3px;

    color: var(--el-color-danger);
  }

  span {
    color: var(--hos-text-muted);

    font-size: 12px;
  }
}

.my-fields-footer {
  display: flex;

  justify-content: flex-end;

  gap: 8px;

  padding-top: 12px;

  border-top: 1px solid var(--hos-border-light);
}

.record-toolbar,
.patient-strip,
.archive-strip,
.section-rail,
.form-panel,
.preview-panel {
  background: var(--hos-panel);

  border: 1px solid var(--hos-border);

  border-radius: var(--hos-radius-card);

  box-shadow: var(--hos-shadow-soft);

  backdrop-filter: blur(16px) saturate(132%);

  -webkit-backdrop-filter: blur(16px) saturate(132%);
}

.record-toolbar {
  display: flex;

  align-items: center;

  justify-content: space-between;

  gap: 16px;

  padding: 14px 16px;

  h2,
  p {
    margin: 0;
  }

  h2 {
    font-size: 20px;
  }

  p {
    margin-top: 4px;

    color: #6b7280;
  }
}

.toolbar-actions {
  display: flex;

  flex-shrink: 0;

  gap: 8px;
}

.patient-strip {
  display: grid;

  grid-template-columns: repeat(5, minmax(0, 1fr));

  overflow: hidden;
}

.summary-cell {
  padding: 10px 14px;

  border-right: 1px solid var(--hos-border-light);

  &:last-child {
    border-right: 0;
  }

  span,
  strong {
    display: block;
  }

  span {
    margin-bottom: 3px;

    color: var(--hos-text-secondary);

    font-size: 12px;
  }
}

.archive-strip {
  display: grid;

  grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr) auto auto;

  gap: 12px;

  align-items: center;

  padding: 10px 14px;
}

.archive-status,
.archive-metrics {
  display: flex;

  flex-wrap: wrap;

  gap: 8px 12px;

  align-items: center;

  min-width: 0;

  color: var(--hos-text-secondary);

  font-size: 13px;

  strong {
    color: var(--hos-text-primary);
  }
}

.archive-metrics {
  justify-content: flex-end;

  span {
    padding-left: 10px;

    border-left: 1px solid var(--hos-border-light);

    &:first-child {
      padding-left: 0;

      border-left: 0;
    }
  }
}

.archive-actions {
  display: flex;

  justify-content: flex-end;
}

.archive-save-status {
  position: relative;

  min-width: 100px;

  text-align: right;
}

.save-indicator {
  font-size: 12px;

  white-space: nowrap;

  &.saving {
    color: var(--el-color-warning);
  }

  &.saved {
    color: var(--el-color-success);
  }

  &.error {
    color: var(--el-color-danger);

    cursor: pointer;

    text-decoration: underline;
  }

  &.conflict {
    color: var(--el-color-warning);

    font-weight: 600;
  }
}

.save-status-fade-enter-active,
.save-status-fade-leave-active {
  transition: opacity 200ms;
}

.save-status-fade-enter-from,
.save-status-fade-leave-to {
  opacity: 0;
}

.conflict-draft-banner {
  display: flex;

  align-items: center;

  gap: 10px;

  padding: 12px 14px;

  margin-bottom: 12px;

  background: rgb(255 251 235 / 92%);

  border: 1px solid rgb(245 158 11 / 28%);

  border-left: 4px solid var(--el-color-warning);

  border-radius: 8px;

  div {
    display: grid;

    flex: 1;

    min-width: 0;

    gap: 2px;
  }

  strong {
    color: #92400e;

    font-size: 14px;
  }

  small {
    color: #78350f;

    line-height: 1.45;
  }
}

.record-context-strip {
  display: grid;

  grid-template-columns: repeat(3, minmax(0, 1fr));

  gap: 10px;

  margin-bottom: 12px;
}

.health-archive-summary {
  display: grid;

  grid-template-columns: repeat(4, minmax(0, 1fr));

  gap: 10px;

  margin-bottom: 12px;

  article {
    min-width: 0;

    padding: 13px 14px;

    background: #f8fafc;

    border: 1px solid #e2e8f0;

    border-radius: 8px;
  }

  span,
  strong,
  small {
    display: block;
  }

  span {
    color: #64748b;

    font-size: 12px;

    font-weight: 700;
  }

  strong {
    margin-top: 4px;

    overflow: hidden;

    color: #0f172a;

    font-size: 16px;

    text-overflow: ellipsis;

    white-space: nowrap;
  }

  small {
    margin-top: 4px;

    display: -webkit-box;

    overflow: hidden;

    color: #475569;

    line-height: 1.4;

    -webkit-box-orient: vertical;

    -webkit-line-clamp: 2;
  }
}

.workflow-hint {
  display: flex;

  align-items: center;

  justify-content: space-between;

  gap: 14px;

  padding: 12px 14px;

  margin-bottom: 12px;

  background: #f8fafc;

  border: 1px solid #e2e8f0;

  border-left: 4px solid #94a3b8;

  border-radius: 8px;

  span,
  strong,
  small {
    display: block;
  }

  span {
    color: #64748b;

    font-size: 12px;

    font-weight: 700;
  }

  strong {
    margin-top: 3px;

    color: #0f172a;

    font-size: 15px;
  }

  small {
    margin-top: 3px;

    color: #475569;
  }

  &.is-warning {
    background: #fffbeb;

    border-color: #fde68a;

    border-left-color: #f59e0b;
  }

  &.is-danger {
    background: #fff7ed;

    border-color: #fed7aa;

    border-left-color: #f97316;
  }

  &.is-success {
    background: #f0fdf4;

    border-color: #bbf7d0;

    border-left-color: #16a34a;
  }
}

.context-card {
  position: relative;

  min-width: 0;

  padding: 13px 14px;

  overflow: hidden;

  background: var(--hos-glass);

  border: 1px solid var(--hos-border-light);

  border-radius: var(--hos-radius-lg);

  box-shadow: var(--hos-shadow-soft);

  &::before {
    position: absolute;

    inset: 0 auto 0 0;

    width: 4px;

    content: "";

    background: #cbd5e1;
  }

  span,
  strong,
  small {
    display: block;
  }

  span {
    color: var(--hos-text-secondary);

    font-size: 12px;

    font-weight: 700;
  }

  strong {
    margin-top: 4px;

    overflow: hidden;

    color: var(--hos-text-primary);

    font-size: 17px;

    text-overflow: ellipsis;

    white-space: nowrap;
  }

  small {
    margin-top: 4px;

    overflow: hidden;

    color: var(--hos-text-secondary);

    text-overflow: ellipsis;

    white-space: nowrap;
  }

  &.fixed {
    background: var(--hos-accent-soft);

    border-color: var(--hos-border);

    &::before {
      background: var(--record-fixed);
    }
  }

  &.editable {
    background: var(--hos-primary-soft);

    border-color: var(--hos-border-interactive);

    &::before {
      background: var(--record-accent);
    }

    span,
    strong {
      color: var(--hos-primary-deep);
    }
  }

  &.attachments {
    background: var(--record-warning-soft);

    border-color: #fed7aa;

    &::before {
      background: var(--record-warning);
    }

    span,
    strong {
      color: #b45309;
    }
  }
}

.followup-editor {
  display: flex;

  flex-direction: column;

  gap: 10px;

  width: 100%;
}

.followup-editor-head,
.followup-record-top {
  display: flex;

  align-items: center;

  justify-content: space-between;

  gap: 10px;
}

.followup-editor-head strong,
.followup-record-top span {
  color: var(--hos-text-primary);

  font-weight: 700;
}

.followup-record-list {
  display: grid;

  gap: 10px;
}

.followup-record-item {
  padding: 12px;

  background: #f8fafc;

  border: 1px solid #e2e8f0;

  border-radius: 8px;
}

.followup-record-grid {
  display: grid;

  grid-template-columns: repeat(4, minmax(0, 1fr));

  gap: 10px;

  margin-top: 10px;

  &.wide {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  label {
    display: flex;

    flex-direction: column;

    min-width: 0;

    gap: 5px;
  }

  span {
    color: #64748b;

    font-size: 12px;

    font-weight: 700;
  }
}

.followup-empty {
  padding: 18px;

  color: #64748b;

  text-align: center;

  background: #f8fafc;

  border: 1px dashed #cbd5e1;

  border-radius: 8px;
}

.archive-scope-note {
  max-width: 680px;

  margin: 12px auto 0;

  color: #475569;

  font-size: 13px;

  line-height: 1.7;

  text-align: center;
}

.record-layout {
  display: grid;

  grid-template-columns: 1fr;

  gap: 12px;

  align-items: start;
}

.section-rail {
  display: none;
}

.lifecycle-rail-summary {
  display: grid;

  gap: 5px;

  padding: 12px;

  margin-bottom: 10px;

  background: linear-gradient(135deg, rgb(var(--hos-primary-rgb) / 12%), rgb(255 255 255 / 48%)), var(--hos-glass);

  border: 1px solid var(--hos-border-interactive);

  border-radius: var(--hos-radius-lg);

  box-shadow: inset 0 1px 0 rgb(255 255 255 / 56%);

  span,
  strong,
  small {
    display: block;
  }

  span {
    color: var(--hos-text-secondary);

    font-size: 12px;

    font-weight: 700;
  }

  strong {
    color: var(--hos-primary-deep);

    font-size: 15px;

    line-height: 1.35;
  }

  small {
    color: var(--hos-text-secondary);

    line-height: 1.45;
  }
}

.rail-item {
  display: grid;

  grid-template-columns: 24px 1fr;

  gap: 2px 8px;

  width: 100%;

  padding: 9px 8px;

  text-align: left;

  cursor: pointer;

  background: var(--hos-glass);

  border: 1px solid transparent;

  border-radius: var(--hos-radius-lg);

  transition:
    background 160ms ease,
    border-color 160ms ease,
    box-shadow 160ms ease,
    transform 160ms ease;

  span {
    grid-row: span 2;

    display: grid;

    place-items: center;

    width: 24px;

    height: 24px;

    color: var(--hos-primary-deep);

    background: var(--hos-primary-soft);

    border: 1px solid var(--hos-border-light);

    border-radius: 999px;

    font-size: 12px;
  }

  strong {
    color: var(--hos-text-primary);

    font-size: 14px;
  }

  small {
    color: var(--hos-text-secondary);
  }

  &.active {
    background: var(--hos-primary-soft);

    border-color: var(--hos-border-interactive);

    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 48%),
      0 8px 18px rgb(var(--hos-primary-rgb) / 5%);

    span {
      color: var(--hos-primary-deep);

      background: var(--hos-primary-muted);

      border-color: var(--hos-border-interactive);
    }
  }
}

.form-panel {
  padding: 16px;
}

.lab-report-overview {
  display: grid;

  gap: 14px;

  padding: 16px;

  margin-bottom: 16px;

  background: linear-gradient(180deg, rgb(255 255 255 / 96%), rgb(248 252 250 / 94%));

  border: 1px solid var(--hos-border);

  border-radius: var(--hos-radius-card);

  box-shadow: var(--hos-shadow-soft);
}

.lab-report-overview-head {
  display: flex;

  align-items: center;

  justify-content: space-between;

  gap: 14px;

  div {
    display: grid;

    gap: 4px;
  }

  strong {
    color: var(--hos-text-primary);

    font-size: 17px;
  }

  span {
    color: var(--hos-text-secondary);

    font-size: 13px;
  }
}

.lab-report-card-grid {
  display: grid;

  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));

  gap: 12px;
}

.lab-report-card {
  display: grid;

  gap: 12px;

  min-width: 0;

  padding: 14px;

  background: #ffffff;

  border: 1px solid var(--hos-border-light);

  border-radius: var(--hos-radius-md);
}

.lab-report-card-head {
  display: flex;

  align-items: center;

  justify-content: space-between;

  gap: 8px;

  strong {
    color: var(--hos-text-primary);

    font-size: 15px;
  }
}

.lab-report-metrics {
  display: grid;

  grid-template-columns: repeat(auto-fit, minmax(92px, 1fr));

  gap: 8px;

  span {
    display: grid;

    gap: 3px;

    min-height: 72px;

    padding: 9px;

    background: var(--hos-accent-soft);

    border: 1px solid var(--hos-border-light);

    border-radius: 6px;
  }

  em {
    color: var(--hos-text-secondary);

    font-style: normal;

    font-size: 12px;
  }

  strong {
    color: var(--hos-text-primary);

    font-size: 17px;

    line-height: 1.2;
  }

  small {
    min-height: 16px;

    color: var(--hos-text-muted);

    font-size: 11px;
  }
}

.lab-report-card p {
  min-height: 34px;

  margin: 0;

  color: var(--hos-text-secondary);

  font-size: 13px;

  line-height: 1.6;
}

.lab-report-supplement {
  display: grid;

  gap: 12px;

  padding: 14px;

  background: #ffffff;

  border: 1px solid var(--hos-border-light);

  border-radius: var(--hos-radius-md);

  strong {
    display: block;

    margin-bottom: 4px;

    color: var(--hos-text-primary);

    font-size: 15px;
  }

  > div:first-child span {
    color: var(--hos-text-secondary);

    font-size: 13px;
  }
}

.lab-report-supplement-grid {
  display: grid;

  grid-template-columns: minmax(180px, 240px) minmax(260px, 1fr);

  gap: 12px;

  align-items: start;

  label {
    display: grid;

    gap: 6px;

    min-width: 0;

    color: var(--hos-text-secondary);

    font-size: 13px;
  }
}

.lab-report-supplement-actions {
  display: flex;

  flex-wrap: wrap;

  gap: 8px;

  justify-content: flex-end;
}

.lab-report-overview-foot {
  display: flex;

  align-items: center;

  justify-content: space-between;

  gap: 12px;

  padding: 8px 12px;

  color: var(--hos-text-secondary);

  background: var(--hos-glass);

  border: 1px dashed var(--hos-border-light);

  border-radius: 6px;

  font-size: 13px;
}

.workspace-anchor {
  display: flex;

  flex-wrap: wrap;

  gap: 6px;

  padding-bottom: 12px;

  margin-bottom: 16px;

  border-bottom: 1px solid var(--hos-border-light);

  button {
    display: inline-flex;

    align-items: center;

    gap: 4px;

    padding: 5px 10px;

    color: var(--hos-text-secondary);

    cursor: pointer;

    background: var(--hos-glass);

    border: 1px solid var(--hos-border-light);

    border-radius: var(--hos-radius-md);

    font-size: 12px;

    transition:
      background 150ms,
      border-color 150ms,
      box-shadow 150ms;

    span {
      display: inline-grid;

      place-items: center;

      width: 18px;

      height: 18px;

      color: var(--hos-primary-deep);

      background: var(--hos-primary-soft);

      border: 1px solid var(--hos-border-light);

      border-radius: 999px;

      font-size: 10px;
    }

    &.active {
      color: var(--hos-primary-deep);

      background: var(--hos-primary-soft);

      border-color: var(--hos-border-interactive);

      box-shadow: 0 8px 18px rgb(var(--hos-primary-rgb) / 5%);

      span {
        color: var(--hos-primary-deep);

        background: var(--hos-primary-muted);

        border-color: var(--hos-border-interactive);
      }
    }
  }
}

.all-section-form {
  display: grid;

  gap: 16px;
}

.section-card {
  padding: 16px;

  background: var(--hos-panel);

  border: 1px solid var(--hos-border);

  border-left: 3px solid transparent;

  border-radius: var(--hos-radius-card);

  box-shadow: var(--hos-shadow-soft);

  transition:
    border-color 200ms,
    box-shadow 200ms;

  &.editable {
    background: var(--hos-panel-hover);

    border-color: var(--hos-border-interactive);

    border-left-color: var(--hos-primary);

    box-shadow: var(--hos-shadow-card-hover);
  }

  &.readonly {
    background: var(--hos-accent-soft);
  }

  &.saved {
    animation: flash-border-green 600ms ease-out;
  }
}

@keyframes flash-border-green {
  0% {
    border-left-color: transparent;
  }

  30% {
    border-left-color: var(--el-color-success);
  }

  100% {
    border-left-color: transparent;
  }
}

.section-card-head {
  display: flex;

  align-items: flex-start;

  justify-content: space-between;

  gap: 12px;

  margin-bottom: 12px;

  h3,
  p {
    margin: 0;
  }

  h3 {
    display: inline-flex;

    align-items: center;

    gap: 8px;

    font-size: 16px;
  }

  p {
    margin-top: 4px;

    color: var(--hos-text-secondary);

    font-size: 13px;
  }
}

.section-index {
  display: inline-grid;

  place-items: center;

  width: 22px;

  height: 22px;

  color: #ffffff;

  background: var(--record-accent);

  border-radius: 999px;

  font-size: 11px;
}

.section-fields {
  display: grid;

  gap: 0;
}

.workbench-field-row {
  display: grid;

  grid-template-columns: 122px minmax(0, 1fr);

  gap: 12px;

  padding: 10px 0;

  border-bottom: 1px solid var(--hos-border-light);

  transition: background 160ms ease;

  &:last-child {
    border-bottom: 0;
  }

  &:focus-within {
    background: var(--hos-primary-soft);

    border-radius: 6px;
  }

  &.missing,
  &.invalid {
    padding-right: 8px;

    padding-left: 8px;

    background: #fff7ed;

    border-radius: 6px;
  }

  &.invalid {
    box-shadow: inset 3px 0 0 #f97316;
  }

  &.locked {
    background: var(--hos-field-locked-bg);

    border-radius: 4px;

    padding: 10px 8px;
  }
}

.locked-note {
  display: flex;

  align-items: center;

  gap: 6px;

  margin-bottom: 6px;

  color: #9ca3af;

  font-size: 12px;
}

.section-card-actions {
  display: flex;

  justify-content: flex-end;

  padding-top: 12px;
}

.section-header {
  display: flex;

  align-items: flex-start;

  justify-content: space-between;

  gap: 12px;

  padding-bottom: 12px;

  border-bottom: 1px solid var(--hos-border-light);

  h3,
  p {
    margin: 0;
  }

  h3 {
    font-size: 18px;
  }

  p {
    margin-top: 5px;

    color: var(--hos-text-secondary);
  }
}

.compact-form {
  display: grid;

  gap: 0;
}

.field-row {
  display: grid;

  grid-template-columns: 122px minmax(0, 1fr);

  gap: 12px;

  padding: 12px 0;

  border-bottom: 1px solid var(--hos-border-light);

  transition: background 160ms ease;

  &:focus-within {
    background: var(--hos-primary-soft);

    border-radius: 6px;
  }

  &.missing,
  &.invalid {
    padding-right: 8px;

    padding-left: 8px;

    background: #fff7ed;

    border-radius: 6px;
  }

  &.invalid {
    box-shadow: inset 3px 0 0 #f97316;
  }

  &.locked {
    .field-label small {
      color: var(--hos-text-muted);
    }
  }
}

.field-label {
  label,
  small {
    display: block;
  }

  label {
    color: var(--hos-text-primary);

    font-weight: 600;

    line-height: 1.35;
  }

  small {
    margin-top: 4px;

    color: var(--record-accent);

    font-size: 12px;
  }
}

.field-input {
  :deep(.el-select),
  :deep(.el-input),
  :deep(.el-textarea) {
    width: 100%;
  }

  :deep(.el-input__wrapper),
  :deep(.el-textarea__inner) {
    background: var(--hos-field-editable-bg);

    box-shadow: 0 0 0 1px var(--hos-border) inset;
  }

  :deep(.is-disabled .el-input__wrapper),
  :deep(.el-textarea.is-disabled .el-textarea__inner) {
    background: var(--hos-field-locked-bg);

    box-shadow: 0 0 0 1px var(--hos-border-light) inset;
  }
}

.field-inline-issue {
  margin: 0;

  color: #c2410c;

  font-size: 12px;

  line-height: 1.45;
}

.preset-select {
  :deep(.el-select__wrapper) {
    min-height: 38px;

    background: var(--hos-field-editable-bg);

    box-shadow: 0 0 0 1px var(--hos-border) inset;
  }

  :deep(.el-select__placeholder),
  :deep(.el-select__selected-item) {
    color: #334155;

    font-size: 13px;
  }
}

.evidence-line {
  display: flex;

  flex-wrap: wrap;

  gap: 6px;

  margin-top: 6px;

  button {
    min-height: 24px;

    padding: 0 8px;

    color: #166534;

    cursor: pointer;

    background: #ecfdf5;

    border: 1px solid #bbf7d0;

    border-radius: 999px;

    font-size: 12px;
  }
}

.field-evidence-grid {
  display: grid;

  grid-template-columns: repeat(auto-fill, minmax(128px, 1fr));

  gap: 8px;

  margin-top: 8px;
}

.field-attachment-uploader {
  display: grid;
  gap: 8px;
}

.field-attachment-actions {
  display: grid;
  grid-template-columns: 156px minmax(180px, 1fr);
  gap: 8px;
  align-items: center;
}

.field-attachment-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 38px;
  padding: 0 14px;
  color: #fff;
  cursor: pointer;
  background: var(--el-color-primary);
  border-radius: 6px;

  input {
    display: none;
  }

  &.disabled {
    cursor: not-allowed;
    opacity: 0.55;
  }
}

.field-attachment-remark {
  width: 100%;
}

.field-attachment-hint {
  color: var(--el-text-color-secondary);
}

.field-evidence-card {
  min-width: 0;

  overflow: hidden;

  text-align: left;

  background: #f8fbfa;

  border: 1px solid #d8ebe5;

  border-radius: 8px;

  strong,
  small {
    display: block;

    min-width: 0;

    overflow: hidden;

    text-overflow: ellipsis;

    white-space: nowrap;
  }

  strong {
    color: #1f3d35;

    font-size: 12px;

    font-weight: 650;
  }

  small {
    margin-top: 2px;

    color: var(--hos-text-secondary);

    font-size: 11px;
  }

  &.image {
    :deep(.el-image) {
      display: block;

      width: 100%;

      height: 84px;

      background: #eef6f3;
    }

    div {
      padding: 7px 8px 8px;
    }
  }

  &.file {
    display: grid;

    gap: 2px;

    width: 100%;

    min-height: 62px;

    padding: 8px 10px;

    cursor: pointer;
  }

  &.disabled {
    cursor: not-allowed;

    opacity: 0.58;
  }
}

.form-footer {
  display: flex;

  justify-content: flex-end;

  gap: 8px;

  padding-top: 14px;
}

.preview-panel {
  padding: 12px;

  background: var(--hos-glass-mist);
}

.preview-panel--overlay {
  position: static;

  max-height: none;

  overflow: visible;
}

.preview-overlay {
  position: fixed;

  inset: 0;

  z-index: 2500;

  display: grid;

  place-items: center;

  background: rgb(var(--hos-primary-rgb) / 8%);

  backdrop-filter: blur(14px) saturate(122%);

  -webkit-backdrop-filter: blur(14px) saturate(122%);
}

.preview-overlay-inner {
  display: flex;

  flex-direction: column;

  width: min(96vw, 1080px);

  height: 92vh;

  background: rgb(250 252 247 / 90%);

  border: 1px solid var(--hos-border-light);

  border-radius: var(--hos-radius-card);

  box-shadow: var(--hos-shadow-soft);

  overflow: hidden;
}

.preview-overlay-toolbar {
  display: flex;

  align-items: center;

  justify-content: space-between;

  flex-shrink: 0;

  padding: 12px 20px;

  border-bottom: 1px solid var(--hos-border-light);

  span {
    font-size: 16px;

    font-weight: 600;

    color: var(--hos-text-primary);
  }

  div {
    display: flex;

    gap: 8px;
  }
}

.preview-overlay-scroll {
  flex: 1;

  overflow-y: auto;

  padding: 20px;

  background: var(--hos-glass-mist);
}

.preview-overlay-enter-active {
  transition: opacity 200ms ease-out;

  .preview-overlay-inner {
    transition:
      transform 200ms ease-out,
      opacity 200ms ease-out;
  }
}

.preview-overlay-leave-active {
  transition: opacity 150ms ease-in;

  .preview-overlay-inner {
    transition:
      transform 150ms ease-in,
      opacity 150ms ease-in;
  }
}

.preview-overlay-enter-from {
  opacity: 0;

  .preview-overlay-inner {
    opacity: 0;

    transform: scale(0.95) translateY(10px);
  }
}

.preview-overlay-leave-to {
  opacity: 0;

  .preview-overlay-inner {
    opacity: 0;

    transform: scale(0.97) translateY(5px);
  }
}

.paper-stack {
  display: grid;

  justify-items: center;

  gap: 14px;
}

.preview-paper {
  position: relative;

  z-index: 0;

  width: min(794px, 100%);

  min-height: 1120px;

  padding: 36px 44px 58px;

  overflow: hidden;

  background: rgb(250 253 249 / 94%);

  border: 1px solid var(--hos-border-light);

  border-radius: 16px;

  box-shadow:
    0 14px 36px rgb(var(--hos-primary-rgb) / 4%),
    0 4px 12px rgb(var(--hos-neutral-rgb) / 3%);

  font-family: Arial, "Microsoft YaHei", sans-serif;

  color: var(--hos-text-primary);
}

.preview-paper > :not(.paper-watermark) {
  position: relative;

  z-index: 1;
}

.paper-watermark {
  position: absolute;

  top: 48%;

  left: 50%;

  z-index: 0;

  color: rgb(var(--hos-primary-rgb) / 6%);

  font-family: Arial, "Microsoft YaHei", sans-serif;

  font-size: 64px;

  font-weight: 700;

  letter-spacing: 10px;

  pointer-events: none;

  white-space: nowrap;

  transform: translate(-50%, -50%) rotate(-28deg);
}

.paper-page-mark,
.paper-footer {
  display: flex;

  align-items: center;

  justify-content: space-between;

  gap: 16px;

  color: var(--hos-text-secondary);

  font-family: Arial, "Microsoft YaHei", sans-serif;

  font-size: 11px;
}

.paper-page-mark {
  margin-bottom: 10px;

  padding-bottom: 8px;

  border-bottom: 1px solid #dcebe5;
}

.paper-footer {
  position: absolute;

  right: 40px;

  bottom: 22px;

  left: 40px;

  padding-top: 8px;

  border-top: 1px solid #dcebe5;
}

.medical-record-head {
  padding: 12px 0 16px;

  margin-bottom: 14px;

  text-align: center;

  border-bottom: 2px solid #55b58a;

  &.compact {
    margin-bottom: 18px;
  }
}

.clinical-record-page {
  background: #fbfcfe;

  .paper-watermark {
    color: rgb(49 70 96 / 5%);
  }
}

.management-record-page {
  background: #fbfefc;

  .paper-watermark {
    color: rgb(39 114 88 / 5%);
  }
}

.registration-record-page {
  background: #fffefb;

  .paper-watermark {
    color: rgb(72 97 83 / 5%);
  }
}

.module-record-head {
  border-bottom-color: #7d91ad;

  .medical-subtitle {
    color: #344b69;

    background: #eef4fb;
  }
}

.management-head {
  border-bottom-color: #58ad91;

  .medical-subtitle {
    color: #25745c;

    background: #edf8f4;
  }
}

.registration-head {
  border-bottom-color: #8aa78f;

  .medical-subtitle {
    color: #536f5b;

    background: #f1f7ef;
  }
}

.medical-brand {
  display: flex;

  align-items: center;

  justify-content: center;

  gap: 12px;
}

.medical-logo {
  width: 52px;

  height: 52px;

  object-fit: contain;
}

.medical-title-block {
  h1,
  h2 {
    margin: 0;

    color: #16352e;

    font-family: Arial, "Microsoft YaHei", sans-serif;

    letter-spacing: 0;
  }

  h1 {
    color: #31564b;

    font-size: 20px;

    font-weight: 700;

    line-height: 1.35;
  }

  h2 {
    margin-top: 4px;

    color: #0f2f27;

    font-size: 27px;

    font-weight: 700;

    line-height: 1.25;
  }
}

.medical-subtitle {
  display: inline-flex;

  align-items: center;

  justify-content: center;

  min-height: 26px;

  padding: 0 14px;

  margin-top: 8px;

  color: #277258;

  background: #edf8f3;

  border-radius: 999px;

  font-size: 13px;

  font-weight: 700;
}

.paper-meta {
  display: grid;

  grid-template-columns: repeat(2, minmax(0, 1fr));

  gap: 0 16px;

  margin-top: 8px;

  text-align: left;

  font-size: 12px;

  b {
    color: #6b837b;

    font-weight: 700;
  }

  span {
    display: flex;

    align-items: center;

    min-height: 34px;

    padding: 6px 0;

    border-bottom: 1px solid #dcebe5;
  }
}

.medical-meta-table {
  grid-template-columns: repeat(4, minmax(0, 1fr));

  margin-top: 14px;

  text-align: left;
}

.paper-legend {
  display: flex;

  justify-content: flex-end;

  gap: 12px;

  margin-top: 10px;

  color: #6b7280;

  font-family: Arial, "Microsoft YaHei", sans-serif;

  font-size: 11px;
}

.legend-fixed,
.legend-filled {
  display: inline-flex;

  align-items: center;

  gap: 5px;

  &::before {
    display: inline-block;

    width: 20px;

    height: 2px;

    content: "";

    background: #b9c9c3;

    border: 0;
  }
}

.legend-filled::before {
  height: 10px;

  background: #e8f8ef;

  border-bottom: 1px solid #7fb99d;
}

.paper-archive-card {
  display: grid;

  grid-template-columns: repeat(3, minmax(0, 1fr));

  gap: 10px;

  margin-top: 12px;

  font-family: Arial, "Microsoft YaHei", sans-serif;

  font-size: 11px;

  span {
    padding: 9px 10px;

    color: #31564b;

    background: linear-gradient(180deg, #f7fbfa, #eef8f3);

    border-left: 3px solid #83c7a8;

    border-radius: 8px;
  }

  b {
    color: #16352e;
  }
}

.clinical-summary-card {
  span {
    color: #31465f;

    background: linear-gradient(180deg, #f8fbff, #eef4fb);

    border-left-color: #8fa7c4;
  }

  b {
    color: #20334d;
  }
}

.management-summary-card {
  span {
    color: #31564b;

    background: linear-gradient(180deg, #f8fdfb, #edf8f4);

    border-left-color: #79bea4;
  }
}

.paper-section {
  padding-top: 16px;

  h3 {
    display: flex;

    align-items: center;

    gap: 8px;

    margin: 0 0 10px;

    padding: 0 0 8px;

    color: #173c34;

    border-bottom: 1px solid #cfe4dc;

    font-size: 15px;

    &::before {
      width: 5px;

      height: 16px;

      content: "";

      background: #55b58a;

      border-radius: 999px;
    }
  }
}

.registration-section {
  padding-top: 12px;

  h3 {
    display: flex;

    align-items: center;

    gap: 8px;

    margin: 0 0 8px;

    color: #263f31;

    font-size: 13px;

    &::before {
      width: 4px;

      height: 14px;

      content: "";

      background: #8aa78f;

      border-radius: 999px;
    }
  }

  &.compact-grid {
    display: grid;

    grid-template-columns: repeat(2, minmax(0, 1fr));

    gap: 12px;
  }
}

.registration-table {
  width: 100%;

  border-collapse: collapse;

  table-layout: fixed;

  background: #ffffff;

  border: 1px solid #c9d8cd;

  font-size: 10px;

  th,
  td {
    min-height: 26px;

    padding: 6px 7px;

    color: #263f31;

    border: 1px solid #c9d8cd;

    line-height: 1.45;

    overflow-wrap: anywhere;

    vertical-align: top;
  }

  th {
    width: 112px;

    color: #375743;

    background: #eff6f0;

    font-weight: 700;

    text-align: left;
  }

  td {
    background: #fffefb;
  }

  &.single {
    th {
      width: 128px;
    }
  }
}

.screening-table {
  th,
  td {
    font-size: 9.5px;
  }

  th:first-child,
  td:first-child {
    width: 132px;
  }

  th:last-child,
  td:last-child {
    width: 72px;

    text-align: center;
  }
}

.screening-status {
  display: inline-grid;

  place-items: center;

  min-width: 46px;

  min-height: 20px;

  padding: 2px 6px;

  border: 1px solid #d6d3d1;

  border-radius: 999px;

  font-size: 10px;

  font-weight: 700;

  &.is-checked {
    color: #166534;

    background: #ecfdf5;

    border-color: #bbf7d0;
  }

  &.is-abnormal {
    color: #991b1b;

    background: #fef2f2;

    border-color: #fecaca;
  }

  &.is-unchecked {
    color: #92400e;

    background: #fffbeb;

    border-color: #fde68a;
  }
}

.registration-note {
  margin: 6px 0 0;

  color: #5f7267;

  font-size: 10px;

  line-height: 1.5;
}

.followup-ledger-table {
  th,
  td {
    font-size: 9.5px;
  }
}

.registration-remarks {
  display: grid;

  gap: 6px;

  padding: 9px 10px;

  color: #31483a;

  background: #ffffff;

  border: 1px solid #c9d8cd;

  font-size: 10px;

  line-height: 1.55;
}

.clinical-section {
  h3 {
    color: #223a55;

    border-bottom-color: #d2deeb;

    &::before {
      background: #7d91ad;
    }
  }

  .paper-field-line {
    border-bottom-color: #d8e2ee;

    strong {
      color: #52697f;
    }

    .paper-value {
      background:
        linear-gradient(0deg, rgb(125 145 173 / 20%) 0 1px, transparent 1px) bottom / 100% 1.85em repeat-y,
        #f6f9fd;
    }
  }
}

.management-section {
  padding: 14px 16px 16px;

  margin-top: 14px;

  background: rgb(246 252 249 / 82%);

  border: 1px solid #d9eee5;

  border-radius: 8px;

  h3 {
    color: #1f5f4d;

    border-bottom-color: #c9e8dc;

    &::before {
      background: #58ad91;
    }
  }

  .paper-field-line {
    grid-template-columns: 136px minmax(0, 1fr);

    border-bottom-color: #d6eadf;

    strong {
      color: #436e61;
    }

    .paper-value {
      background:
        linear-gradient(0deg, rgb(88 173 145 / 18%) 0 1px, transparent 1px) bottom / 100% 1.85em repeat-y,
        #f7fcfa;
    }
  }
}

.archive-timeline-section {
  padding: 14px 16px 16px;

  margin-top: 14px;

  background: #fbfdfc;

  border: 1px solid #d9eee5;

  border-radius: 8px;
}

.paper-archive-timeline {
  display: grid;

  gap: 10px;
}

.paper-archive-timeline-item {
  position: relative;

  display: grid;

  grid-template-columns: 118px minmax(0, 1fr);

  gap: 12px;

  padding: 10px 0 10px 16px;

  border-left: 2px solid #9ed8c1;

  &::before {
    position: absolute;

    top: 16px;

    left: -5px;

    width: 8px;

    height: 8px;

    content: "";

    background: #3b9b7a;

    border: 2px solid #ffffff;

    border-radius: 999px;

    box-shadow: 0 0 0 2px #ccebdd;
  }

  > span {
    color: #497668;

    font-size: 11px;

    line-height: 1.55;
  }

  strong,
  p,
  small {
    display: block;
  }

  strong {
    color: #183f35;

    font-size: 12px;
  }

  p {
    margin: 4px 0;

    color: #31564b;

    font-size: 11px;

    line-height: 1.65;

    overflow-wrap: anywhere;
  }

  small {
    color: #6b8d83;

    font-size: 10px;

    line-height: 1.5;
  }
}

.followup-line {
  align-items: stretch;
}

.paper-followup-timeline {
  display: grid;

  gap: 8px;

  min-width: 0;

  padding: 8px 10px;

  background: #f7fcfa;

  border-radius: 8px 8px 0 0;
}

.paper-followup-item {
  position: relative;

  display: grid;

  grid-template-columns: minmax(0, 1fr) auto;

  gap: 3px 12px;

  min-width: 0;

  padding: 8px 10px 8px 14px;

  background: #ffffff;

  border: 1px solid #d8eee5;

  border-left: 3px solid #58ad91;

  border-radius: 8px;

  b,
  span,
  p,
  em {
    min-width: 0;

    margin: 0;

    font-size: 11px;

    line-height: 1.55;

    overflow-wrap: anywhere;
  }

  b {
    color: #1f5f4d;

    font-size: 12px;
  }

  span {
    color: #5f776f;

    text-align: right;
  }

  p {
    grid-column: 1 / -1;

    color: #29483f;
  }

  em {
    grid-column: 1 / -1;

    color: #25745c;

    font-style: normal;

    font-weight: 700;
  }
}

.paper-followup-empty {
  display: block;

  padding: 8px 0;

  color: #6b837b;

  font-size: 12px;
}

.audit-summary-section {
  margin-top: 16px;

  h3 {
    color: #4b5563;

    border-bottom-color: #e5e7eb;

    &::before {
      background: #9ca3af;
    }
  }
}

.audit-summary-grid {
  display: grid;

  grid-template-columns: repeat(2, minmax(0, 1fr));

  gap: 10px;

  > div {
    min-width: 0;

    padding: 10px 12px;

    background: #f8fafc;

    border: 1px solid #e5e7eb;

    border-radius: 8px;
  }

  strong {
    display: block;

    margin-bottom: 6px;

    color: #374151;

    font-size: 12px;
  }

  p {
    margin: 5px 0 0;

    color: #4b5563;

    font-size: 11px;

    line-height: 1.65;

    overflow-wrap: anywhere;
  }

  b {
    color: #111827;
  }
}

.paper-grid {
  display: grid;

  grid-template-columns: repeat(2, minmax(0, 1fr));

  gap: 2px 18px;

  div {
    display: grid;

    grid-template-columns: 84px 1fr;

    min-height: 32px;

    overflow: hidden;

    border-bottom: 1px solid #dcebe5;
  }

  strong,
  span {
    padding: 7px 0;

    font-size: 12px;

    line-height: 1.45;
  }

  strong {
    color: #698078;

    font-weight: 700;
  }
}

.paper-value {
  display: inline;

  min-height: 18px;

  padding: 0 4px 1px;

  color: #113b31;

  font-style: normal;

  font-weight: 600;

  background: #eaf8ef;

  border-bottom: 1px solid #83c7a8;

  border-radius: 4px 4px 0 0;

  box-decoration-break: clone;

  -webkit-box-decoration-break: clone;

  &.inline {
    margin-right: 8px;
  }
}

.paper-grid .paper-value {
  display: block;

  min-height: 100%;

  background: transparent;

  border-bottom: 0;

  border-radius: 0;
}

.paper-field-list {
  display: grid;

  gap: 4px;
}

.paper-field-line {
  display: grid;

  grid-template-columns: 120px minmax(0, 1fr);

  min-height: 36px;

  overflow: hidden;

  border-bottom: 1px solid #dcebe5;

  strong {
    display: flex;

    align-items: flex-start;

    justify-content: space-between;

    gap: 5px;

    padding: 8px 10px 8px 0;

    color: #5f776f;

    background: transparent;

    font-size: 12px;

    font-weight: 700;

    line-height: 1.55;
  }

  small {
    flex-shrink: 0;

    padding: 1px 4px;

    color: #166534;

    background: #ecfdf5;

    border: 1px solid #bbf7d0;

    border-radius: 3px;

    font-family: Arial, "Microsoft YaHei", sans-serif;

    font-size: 10px;

    font-weight: 500;

    line-height: 1.3;
  }

  .paper-value {
    display: block;

    min-height: 100%;

    padding: 8px 10px;

    background:
      linear-gradient(0deg, rgb(131 199 168 / 22%) 0 1px, transparent 1px) bottom / 100% 1.85em repeat-y,
      #f4fbf7;

    border-bottom: 0;

    border-radius: 8px 8px 0 0;

    font-weight: 500;

    line-height: 1.7;
  }
}

.paper-sign {
  display: flex;

  justify-content: space-between;

  gap: 16px;

  margin-top: 28px;

  padding-top: 14px;

  border-top: 1px solid #e2e8f0;

  font-size: 13px;
}

.attachment-table {
  width: 100%;

  border-collapse: collapse;

  font-size: 13px;

  th,
  td {
    padding: 9px 8px;

    text-align: left;

    border: 1px solid var(--hos-border-light);
  }

  th {
    background: var(--hos-accent-soft);
  }
}

.attachment-head {
  display: flex;

  align-items: flex-start;

  justify-content: space-between;

  gap: 18px;

  padding-bottom: 12px;

  border-bottom: 1px solid var(--hos-border-light);

  h2,
  p {
    margin: 0;
  }

  h2 {
    font-size: 18px;
  }

  p {
    margin-top: 8px;

    color: var(--hos-text-secondary);

    font-size: 13px;
  }

  span {
    flex-shrink: 0;

    padding: 4px 10px;

    border: 1px solid var(--hos-border);

    border-radius: 999px;

    font-size: 12px;
  }
}

.attachment-image-frame {
  display: grid;

  place-items: center;

  min-height: 900px;

  padding-top: 18px;

  img {
    max-width: 100%;

    max-height: 860px;

    object-fit: contain;

    border: 1px solid var(--hos-border);
  }
}

.attachment-file-placeholder {
  display: grid;

  place-items: center;

  gap: 8px;

  width: 100%;

  min-height: 320px;

  padding: 28px;

  color: var(--hos-text-secondary);

  text-align: center;

  background: var(--hos-accent-soft);

  border: 1px dashed var(--hos-border-interactive);

  strong,
  span {
    display: block;
  }

  strong {
    margin-bottom: 8px;

    color: var(--hos-text-primary);

    font-size: 16px;
  }
}

.record-layout {
  grid-template-columns: minmax(0, 1fr);
}

.form-panel {
  padding: 0;

  overflow: visible;

  background: transparent;

  border: 0;
}

.form-panel > .section-header,
.form-panel > .compact-form,
.form-panel > .form-footer {
  display: none;
}

.workspace-anchor {
  position: sticky;

  top: 68px;

  z-index: 5;

  display: flex;

  gap: 6px;

  padding: 10px;

  margin-bottom: 10px;

  overflow-x: auto;

  background: var(--hos-panel);

  border: 1px solid var(--hos-border);

  border-radius: var(--hos-radius-card);

  box-shadow: var(--hos-shadow-soft);

  backdrop-filter: blur(16px) saturate(132%);

  -webkit-backdrop-filter: blur(16px) saturate(132%);

  button {
    display: inline-flex;

    flex: 0 0 auto;

    align-items: center;

    gap: 6px;

    height: 32px;

    padding: 0 10px;

    color: var(--hos-text-secondary);

    cursor: pointer;

    background: var(--hos-glass);

    border: 1px solid var(--hos-border-light);

    border-radius: var(--hos-radius-md);

    position: relative;

    transition:
      color 0.18s ease,
      border-color 0.18s ease,
      background-color 0.18s ease,
      transform 0.18s ease;

    &::before {
      position: absolute;

      bottom: -11px;

      left: 10px;

      width: calc(100% - 20px);

      height: 2px;

      content: "";

      background: var(--hos-primary);

      border-radius: 999px;

      opacity: 0;

      transform: scaleX(0.45);

      transform-origin: center;

      transition:
        opacity 0.18s ease,
        transform 0.18s ease;
    }

    &:hover {
      transform: translateY(-1px);
    }

    span {
      display: grid;

      place-items: center;

      width: 18px;

      height: 18px;

      color: var(--hos-primary-deep);

      background: var(--hos-primary-soft);

      border-radius: 50%;

      font-size: 12px;
    }

    &.active {
      color: var(--hos-primary-deep);

      background: var(--hos-primary-soft);

      border-color: var(--hos-border-interactive);

      &::before {
        opacity: 1;

        transform: scaleX(1);
      }

      span {
        color: var(--hos-text-primary);

        background: var(--hos-primary-muted);
      }
    }

    &.done:not(.active) {
      color: var(--el-color-success);

      background: var(--el-color-success-light-9);

      border-color: var(--el-color-success-light-7);
    }

    &.attention:not(.active) {
      color: var(--el-color-warning);

      background: var(--el-color-warning-light-9);

      border-color: var(--el-color-warning-light-6);
    }
  }
}

.all-section-form {
  display: grid;

  gap: 12px;
}

.section-card {
  scroll-margin-top: 124px;

  padding: 16px;

  background: var(--hos-panel);

  border: 1px solid var(--hos-border);

  border-radius: var(--hos-radius-card);

  box-shadow: var(--hos-shadow-soft);

  backdrop-filter: blur(16px) saturate(132%);

  -webkit-backdrop-filter: blur(16px) saturate(132%);

  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    background-color 0.2s ease;

  &.saved {
    background: var(--hos-panel-hover);

    border-color: var(--hos-border-interactive);

    box-shadow: var(--hos-shadow-card-hover);
  }

  &.editable {
    border-left: 3px solid var(--hos-primary);
  }

  &.readonly {
    border-left: 3px solid var(--hos-border-interactive);
  }

  &.attention {
    border-color: var(--el-color-warning-light-5);

    box-shadow: 0 0 0 3px rgb(245 158 11 / 8%);
  }

  &.collapsed {
    background: var(--hos-glass);
  }
}

.section-issue-line {
  display: flex;

  align-items: center;

  justify-content: space-between;

  gap: 10px;

  padding: 8px 10px;

  margin-top: 10px;

  color: #9a3412;

  background: #fff7ed;

  border: 1px solid #fed7aa;

  border-radius: 8px;

  font-size: 12px;
}

.section-card-head {
  display: flex;

  align-items: flex-start;

  justify-content: space-between;

  gap: 16px;

  padding-bottom: 12px;

  border-bottom: 1px solid var(--hos-border-light);

  h3,
  p {
    margin: 0;
  }

  h3 {
    display: inline;

    margin-left: 8px;

    font-size: 18px;
  }

  p {
    margin-top: 6px;

    color: var(--hos-text-secondary);

    line-height: 1.6;
  }
}

.section-head-actions {
  display: inline-flex;

  flex-shrink: 0;

  gap: 8px;

  align-items: center;
}

.section-fold-button {
  :deep(.el-icon) {
    transition: transform 0.18s ease;
  }

  :deep(.el-icon.folded) {
    transform: rotate(-90deg);
  }
}

.section-summary-line {
  display: flex;

  flex-wrap: wrap;

  gap: 8px;

  align-items: center;

  padding: 10px 0 2px;

  color: var(--hos-text-secondary);

  font-size: 12px;

  span {
    min-height: 24px;

    padding: 3px 8px;

    background: var(--hos-glass);

    border: 1px solid var(--hos-border-light);

    border-radius: 999px;
  }

  .needs-fill {
    color: #92400e;

    background: #fffbeb;

    border-color: #fde68a;
  }

  .is-complete {
    color: #166534;

    background: #ecfdf5;

    border-color: #bbf7d0;
  }

  .section-save-time {
    color: var(--hos-primary-deep);

    background: rgba(111, 167, 137, 0.12);

    border-color: rgba(111, 167, 137, 0.24);
  }
}

.section-index {
  display: inline-grid;

  place-items: center;

  width: 24px;

  height: 24px;

  color: var(--hos-text-primary);

  background: var(--hos-primary-muted);

  border-radius: 50%;

  font-size: 13px;
}

.section-fields {
  display: grid;
}

.workbench-field-row {
  grid-template-columns: 160px minmax(0, 1fr);

  padding: 14px 0;

  transition:
    background-color 0.18s ease,
    border-color 0.18s ease;

  &.locked {
    padding: 14px 12px;

    margin: 0 -12px;

    background: var(--hos-glass);

    border-bottom-color: var(--hos-border-light);

    border-radius: var(--hos-radius-md);

    .field-label label {
      color: var(--hos-text-secondary);
    }
  }

  &.complete:not(.locked) {
    .field-label small {
      color: var(--el-color-success);
    }
  }
}

.locked-note {
  display: inline-flex;

  align-items: center;

  gap: 5px;

  min-height: 26px;

  padding: 0 8px;

  margin-bottom: 7px;

  color: var(--hos-text-secondary);

  background: var(--hos-glass);

  border: 1px dashed var(--hos-border-interactive);

  border-radius: var(--hos-radius-sm);

  font-size: 12px;
}

.section-card-actions {
  display: flex;

  justify-content: flex-end;

  padding-top: 12px;

  border-top: 1px solid var(--hos-border-light);
}

.audit-timeline-drawer {
  display: grid;

  gap: 14px;
}

.timeline-summary {
  display: flex;

  align-items: center;

  justify-content: space-between;

  gap: 12px;

  padding: 14px;

  background: var(--hos-glass);

  border: 1px solid var(--hos-border-light);

  border-radius: var(--hos-radius-lg);

  strong,
  span {
    display: block;
  }

  strong {
    color: var(--hos-text-primary);

    font-size: 17px;
  }

  span {
    margin-top: 4px;

    color: var(--hos-text-secondary);
  }
}

.patient-audit-timeline {
  padding: 4px 4px 4px 0;
}

.archive-timeline-review {
  display: grid;

  gap: 12px;

  padding: 14px;

  background: #f8fdfb;

  border: 1px solid #d9eee5;

  border-radius: var(--hos-radius-lg);
}

.archive-timeline-review-head {
  display: grid;

  gap: 4px;

  strong {
    color: #1f5f4d;

    font-size: 15px;
  }

  span {
    color: var(--hos-text-secondary);

    font-size: 12px;

    line-height: 1.5;
  }
}

.timeline-event {
  padding: 12px;

  background: var(--hos-glass);

  border: 1px solid var(--hos-border-light);

  border-radius: var(--hos-radius-lg);

  p {
    margin: 8px 0;

    color: var(--hos-text-primary);

    line-height: 1.6;
  }
}

.timeline-event.archive-event {
  background: #ffffff;

  border-color: #cfe9de;
}

.timeline-event-head,
.timeline-meta {
  display: flex;

  flex-wrap: wrap;

  gap: 8px;

  align-items: center;
}

.timeline-event-head {
  justify-content: space-between;
}

.timeline-meta {
  color: var(--el-text-color-secondary);

  font-size: 12px;

  span {
    padding-right: 8px;

    border-right: 1px solid var(--el-border-color-lighter);

    &:last-child {
      border-right: 0;
    }
  }
}

.timeline-change {
  display: grid;

  grid-template-columns: repeat(2, minmax(0, 1fr));

  gap: 8px;

  margin-top: 10px;

  div {
    min-width: 0;

    padding: 9px;

    background: var(--el-fill-color-light);

    border-radius: 6px;
  }

  small,
  span {
    display: block;
  }

  small {
    margin-bottom: 5px;

    color: var(--el-text-color-secondary);
  }

  span {
    word-break: break-word;
  }
}

.void-document-dialog {
  display: grid;

  gap: 12px;

  p {
    display: flex;

    gap: 6px;

    margin: 0;

    color: var(--el-text-color-regular);

    strong {
      color: var(--el-text-color-primary);

      word-break: break-word;
    }
  }
}

.ai-summary-dialog {
  display: grid;

  gap: 14px;

  min-height: 220px;
}

.medical-record-generator {
  display: grid;

  gap: 14px;

  min-height: 260px;
}

.medical-record-template-strip,
.medical-record-current-head {
  display: flex;

  gap: 12px;

  align-items: center;

  justify-content: space-between;
}

.medical-record-template-strip {
  padding: 12px 14px;

  background: var(--hos-glass);

  border: 1px solid var(--hos-border-light);

  border-radius: 8px;

  div {
    display: grid;

    gap: 4px;
  }

  span {
    color: var(--hos-text-secondary);

    font-size: 12px;
  }
}

.medical-record-required,
.medical-record-missing {
  display: flex;

  flex-wrap: wrap;

  gap: 8px;

  align-items: center;

  padding: 10px 12px;

  border-radius: 8px;
}

.medical-record-required {
  background: #f7fbfa;

  border: 1px solid #d8ebe5;

  strong {
    color: var(--hos-text-primary);
  }
}

.medical-record-missing {
  background: #fff8e6;

  border: 1px solid #f3d19e;
}

.medical-record-workspace {
  display: grid;

  gap: 12px;

  padding: 12px;

  background: #ffffff;

  border: 1px solid var(--hos-border-light);

  border-radius: 8px;
}

.medical-record-workspace-head {
  display: flex;

  gap: 12px;

  align-items: center;

  justify-content: space-between;

  > div:first-child {
    display: grid;

    gap: 4px;
  }

  span {
    color: var(--hos-text-secondary);

    font-size: 12px;
  }

  > div:last-child {
    display: flex;

    gap: 8px;
  }
}

.medical-record-sections {
  border-top: 1px solid var(--hos-border-light);
}

.medical-record-field-grid {
  display: grid;

  grid-template-columns: repeat(2, minmax(0, 1fr));

  gap: 12px;
}

.medical-record-field {
  display: grid;

  gap: 6px;

  min-width: 0;

  padding: 10px;

  background: #f8fafc;

  border: 1px solid var(--hos-border-light);

  border-radius: 8px;

  &.wide {
    grid-column: 1 / -1;
  }

  &.missing {
    background: #fff8e6;

    border-color: #f3d19e;
  }

  > span {
    display: flex;

    flex-wrap: wrap;

    gap: 6px;

    align-items: center;

    color: var(--hos-text-primary);

    font-size: 13px;

    font-weight: 700;
  }

  sup {
    color: var(--el-color-danger);
  }

  em {
    padding: 1px 6px;

    color: #047857;

    background: #ecfdf5;

    border-radius: 999px;

    font-size: 11px;

    font-style: normal;

    font-weight: 600;
  }
}

.medical-record-current {
  display: grid;

  gap: 10px;
}

.medical-record-current-head {
  strong {
    display: block;

    margin-bottom: 4px;
  }

  span {
    color: var(--hos-text-secondary);

    font-size: 12px;
  }
}

.medical-record-file-card {
  display: flex;

  gap: 12px;

  align-items: center;

  justify-content: space-between;

  padding: 14px;

  background: #f8fafc;

  border: 1px solid var(--hos-border-light);

  border-radius: 8px;

  div {
    display: grid;

    gap: 4px;

    min-width: 0;
  }

  strong {
    overflow: hidden;

    color: var(--hos-text-primary);

    text-overflow: ellipsis;

    white-space: nowrap;
  }

  span {
    color: var(--hos-text-secondary);

    font-size: 12px;
  }
}

.medical-record-history {
  display: grid;

  gap: 8px;

  > strong {
    font-size: 14px;
  }

  button {
    display: flex;

    justify-content: space-between;

    width: 100%;

    padding: 10px 12px;

    text-align: left;

    cursor: pointer;

    background: #ffffff;

    border: 1px solid var(--hos-border-light);

    border-radius: 8px;

    &.active {
      color: var(--el-color-primary);

      background: #f5f8ff;

      border-color: var(--el-color-primary);
    }

    small {
      color: var(--hos-text-secondary);
    }
  }
}

.ai-summary-head {
  display: flex;

  align-items: center;

  justify-content: space-between;

  gap: 14px;

  padding: 12px 14px;

  background: var(--hos-glass);

  border: 1px solid var(--hos-border-light);

  border-radius: 8px;

  div {
    display: grid;

    gap: 4px;

    min-width: 0;
  }

  strong {
    color: var(--hos-text-primary);

    font-size: 16px;
  }

  span {
    color: var(--hos-text-secondary);

    font-size: 12px;
  }
}

.ai-summary-block,
.ai-summary-grid article,
.ai-summary-lists article {
  min-width: 0;

  padding: 14px;

  background: #ffffff;

  border: 1px solid var(--hos-border-light);

  border-radius: 8px;

  h4 {
    margin: 0 0 8px;

    color: var(--hos-text-primary);

    font-size: 14px;
  }

  p {
    margin: 0;

    color: var(--hos-text-regular);

    font-size: 13px;

    line-height: 1.75;

    overflow-wrap: anywhere;
  }
}

.ai-summary-block.portrait {
  border-color: #bfdbfe;

  background: #eff6ff;
}

.ai-summary-grid,
.ai-summary-lists {
  display: grid;

  grid-template-columns: repeat(3, minmax(0, 1fr));

  gap: 12px;
}

.ai-summary-lists {
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));

  ul {
    display: grid;

    gap: 6px;

    padding-left: 18px;

    margin: 0;

    color: var(--hos-text-regular);

    font-size: 13px;

    line-height: 1.65;
  }

  li {
    overflow-wrap: anywhere;
  }
}

.ai-summary-disclaimer {
  margin: 0;

  color: var(--hos-text-secondary);

  font-size: 12px;

  line-height: 1.6;
}

.record-workspace {
  :deep(.el-drawer__body),
  :deep(.el-dialog__body) {
    background: var(--hos-app-bg);
  }
}

.record-workspace .workspace-anchor {
  background: var(--hos-panel);

  border: 1px solid var(--hos-border-light);

  border-radius: var(--hos-radius-card);

  box-shadow: var(--hos-shadow-soft);

  backdrop-filter: blur(16px) saturate(132%);

  -webkit-backdrop-filter: blur(16px) saturate(132%);

  button {
    color: var(--hos-text-secondary);

    background: var(--hos-glass);

    border-color: var(--hos-border-light);

    border-radius: var(--hos-radius-lg);

    &::before {
      background: var(--hos-primary);
    }

    span {
      color: var(--hos-primary-deep);

      background: var(--hos-primary-soft);

      border: 1px solid var(--hos-border-light);
    }

    &.active {
      color: var(--hos-primary-deep);

      background: var(--hos-primary-soft);

      border-color: var(--hos-border-interactive);

      box-shadow:
        inset 0 1px 0 rgb(255 255 255 / 48%),
        0 8px 18px rgb(var(--hos-primary-rgb) / 5%);

      span {
        color: var(--hos-primary-deep);

        background: var(--hos-primary-muted);

        border-color: var(--hos-border-interactive);
      }
    }

    &.done:not(.active) {
      color: var(--hos-status-success);

      background: var(--hos-status-success-soft);

      border-color: rgb(22 163 74 / 14%);
    }

    &.attention:not(.active) {
      color: var(--hos-status-warning);

      background: var(--hos-status-warning-soft);

      border-color: rgb(217 119 6 / 16%);
    }
  }
}

.record-workspace .section-card {
  background: var(--hos-panel);

  border: 1px solid var(--hos-border);

  border-radius: var(--hos-radius-card);

  box-shadow: var(--hos-shadow-soft);

  backdrop-filter: blur(16px) saturate(132%);

  -webkit-backdrop-filter: blur(16px) saturate(132%);

  &.saved {
    background: var(--hos-panel-hover);

    border-color: var(--hos-border-interactive);

    box-shadow: var(--hos-shadow-card-hover);
  }

  &.editable {
    border-left-color: var(--hos-primary);
  }

  &.readonly {
    background: var(--hos-accent-soft);

    border-left-color: var(--hos-border-interactive);
  }

  &.collapsed {
    background: var(--hos-glass);
  }
}

.record-workspace .section-card-head,
.record-workspace .section-card-actions {
  border-color: var(--hos-border-light);
}

.record-workspace .section-card-head p,
.record-workspace .section-summary-line,
.record-workspace .timeline-meta {
  color: var(--hos-text-secondary);
}

.record-workspace .section-summary-line span,
.record-workspace .workbench-field-row.locked,
.record-workspace .locked-note,
.record-workspace .timeline-summary,
.record-workspace .timeline-change div,
.record-workspace .timeline-event {
  background: var(--hos-glass);

  border-color: var(--hos-border-light);
}

.record-workspace .section-index {
  color: var(--hos-text-primary);

  background: var(--hos-primary-muted);
}

.record-workspace .workbench-field-row.locked .field-label label,
.record-workspace .locked-note {
  color: var(--hos-text-secondary);
}

.record-workspace .timeline-summary,
.record-workspace .timeline-event {
  border: 1px solid var(--hos-border-light);

  border-radius: var(--hos-radius-lg);
}

.record-workspace .preview-panel,
.record-workspace .preview-overlay-inner {
  background: rgb(250 252 247 / 90%);

  border: 1px solid var(--hos-border-light);

  box-shadow: var(--hos-shadow-soft);
}

.record-workspace .preview-overlay {
  background: rgb(var(--hos-primary-rgb) / 8%);

  backdrop-filter: blur(14px) saturate(122%);

  -webkit-backdrop-filter: blur(14px) saturate(122%);
}

.record-workspace .preview-overlay-scroll {
  background: var(--hos-glass-mist);
}

.record-workspace .preview-paper {
  background: rgb(250 253 249 / 94%);

  border-color: var(--hos-border-light);

  box-shadow:
    0 14px 36px rgb(var(--hos-primary-rgb) / 4%),
    0 4px 12px rgb(var(--hos-neutral-rgb) / 3%);
}

.record-workspace .clinical-record-page {
  background: #fbfcfe;
}

.record-workspace .management-record-page {
  background: #fbfefc;
}

.workbar-progress {
  i {
    position: relative;

    background: rgb(var(--hos-primary-rgb) / 10%);
  }

  em {
    position: relative;

    overflow: hidden;

    background: linear-gradient(90deg, var(--hos-primary), var(--hos-primary-deep));

    &::after {
      position: absolute;

      inset: 0;

      content: "";

      background: linear-gradient(90deg, transparent, rgb(255 255 255 / 55%), transparent);

      transform: translateX(-120%);

      animation: record-progress-sheen 1.6s var(--liquid-ease, ease) infinite;
    }
  }

  &.complete {
    strong {
      color: var(--hos-status-success);
    }

    em {
      background: linear-gradient(90deg, var(--hos-status-success), var(--hos-primary-deep));

      animation: record-complete-pulse 1.8s ease-out infinite;
    }
  }
}

.save-check {
  display: inline-grid;

  place-items: center;

  width: 16px;

  height: 16px;

  margin-right: 4px;

  color: #ffffff;

  vertical-align: -3px;

  background: var(--hos-status-success);

  border-radius: 999px;

  animation: save-check-pop 180ms var(--liquid-ease, ease) both;

  &::before {
    content: "✓";

    font-size: 11px;

    font-style: normal;

    line-height: 1;
  }
}

.rail-item {
  position: relative;
}

.rail-missing-badge,
.workspace-anchor button em {
  display: inline-grid;

  place-items: center;

  min-width: 18px;

  height: 18px;

  padding: 0 5px;

  color: #ffffff;

  background: var(--hos-status-danger);

  border-radius: 999px;

  box-shadow: 0 0 0 4px rgb(220 38 38 / 10%);

  font-size: 11px;

  font-style: normal;

  font-weight: 700;

  line-height: 1;
}

.rail-missing-badge {
  position: absolute;

  top: 8px;

  right: 8px;
}

.my-field-grid {
  gap: var(--hos-spacing-lg, 18px);
}

.my-field-item,
.field-row,
.workbench-field-row {
  scroll-margin-top: 156px;

  &.focused {
    position: relative;

    background: var(--hos-primary-soft);

    border-color: var(--hos-primary-deep);

    box-shadow:
      0 0 0 3px rgb(var(--hos-primary-rgb) / 14%),
      var(--hos-shadow-card-hover);

    animation: field-focus-pulse 760ms var(--liquid-ease, ease) 2;
  }
}

.my-field-item,
.field-input {
  :deep(.el-input__wrapper),
  :deep(.el-select__wrapper) {
    min-height: var(--hos-touch-target, 46px);

    border-radius: var(--hos-radius-md);

    font-size: 15px;
  }

  :deep(.el-textarea__inner) {
    min-height: 92px;

    border-radius: var(--hos-radius-md);

    font-size: 15px;

    line-height: 1.65;
  }
}

.conflict-draft-banner {
  background: rgb(255 251 235 / 94%);

  border-color: rgb(217 119 6 / 24%);

  border-left-color: var(--hos-status-warning);

  border-radius: var(--hos-radius-card);

  box-shadow: 0 16px 36px rgb(217 119 6 / 10%);

  &::before {
    display: inline-grid;

    flex: 0 0 34px;

    place-items: center;

    width: 34px;

    height: 34px;

    color: #92400e;

    content: "!";

    background: rgb(217 119 6 / 12%);

    border: 1px solid rgb(217 119 6 / 20%);

    border-radius: 999px;

    font-weight: 800;
  }
}

.preview-overlay-inner {
  position: relative;
}

.preview-overlay-toolbar {
  position: absolute;

  top: 14px;

  right: 18px;

  left: 18px;

  z-index: 8;

  display: grid;

  grid-template-columns: minmax(180px, 1fr) auto auto;

  gap: 12px;

  align-items: center;

  padding: 10px 12px;

  background: rgb(250 255 249 / 78%);

  border: 1px solid rgb(255 255 255 / 72%);

  border-radius: var(--hos-radius-card);

  box-shadow:
    inset 0 1px 0 rgb(255 255 255 / 78%),
    0 18px 42px rgb(var(--hos-primary-rgb) / 10%);

  backdrop-filter: blur(18px) saturate(140%);

  -webkit-backdrop-filter: blur(18px) saturate(140%);

  > div {
    display: flex;

    gap: 8px;
  }
}

.preview-toolbar-title {
  display: grid !important;

  gap: 2px;

  min-width: 0;

  span,
  small {
    overflow: hidden;

    text-overflow: ellipsis;

    white-space: nowrap;
  }

  small {
    color: var(--hos-text-secondary);

    font-size: 12px;
  }
}

.preview-page-pills {
  display: inline-flex;

  max-width: min(480px, 46vw);

  padding: 3px;

  overflow-x: auto;

  background: rgb(var(--hos-primary-rgb) / 8%);

  border: 1px solid var(--hos-border-light);

  border-radius: 8px;

  button {
    display: inline-grid;

    flex: 0 0 auto;

    place-items: center;

    min-width: 64px;

    height: 28px;

    padding: 0 10px;

    color: var(--hos-text-secondary);

    cursor: pointer;

    background: transparent;

    border: 0;

    border-radius: 6px;

    font-size: 12px;

    font-weight: 700;

    white-space: nowrap;

    &.active {
      color: #ffffff;

      background: var(--hos-primary-deep);

      box-shadow: 0 8px 18px rgb(var(--hos-primary-rgb) / 18%);
    }
  }
}

.preview-overlay-scroll {
  padding-top: 92px;

  scroll-behavior: smooth;
}

.paper-stack {
  gap: 24px;

  scroll-snap-type: y proximity;
}

.preview-paper {
  isolation: isolate;

  overflow: hidden;

  scroll-margin-top: 104px;

  scroll-snap-align: start;

  border-radius: 12px;

  box-shadow:
    0 1px 0 rgb(255 255 255 / 80%) inset,
    0 20px 54px rgb(var(--hos-primary-rgb) / 9%),
    0 8px 18px rgb(var(--hos-neutral-rgb) / 5%);

  &::after {
    position: absolute;

    right: 22px;

    bottom: -10px;

    left: 22px;

    z-index: -1;

    height: 26px;

    content: "";

    background: rgb(var(--hos-primary-rgb) / 7%);

    filter: blur(12px);

    border-radius: 50%;
  }
}

.paper-watermark {
  inset: 0;

  color: transparent;

  font-size: 0;

  transform: none;

  &::before {
    position: absolute;

    inset: -80px;

    content: "";

    background-image: repeating-linear-gradient(-28deg, transparent 0 42px, rgb(22 101 52 / 4%) 42px 44px, transparent 44px 98px);
  }

  &::after {
    position: absolute;

    inset: 0;

    display: grid;

    place-items: center;

    color: rgb(var(--hos-primary-rgb) / 6%);

    content: attr(data-watermark) "  " attr(data-watermark) "  " attr(data-watermark);

    font-size: 44px;

    font-weight: 800;

    letter-spacing: 6px;

    white-space: nowrap;

    transform: rotate(-28deg);
  }
}

.print-cover {
  display: flex;

  flex-direction: column;

  justify-content: center;

  gap: 26px;
}

.cover-head {
  border-bottom: 0;
}

.cover-patient-card {
  display: grid;

  grid-template-columns: repeat(2, minmax(0, 1fr));

  gap: 10px;

  padding: 18px;

  background: rgb(var(--hos-primary-rgb) / 6%);

  border: 1px solid var(--hos-border-light);

  border-radius: 14px;

  span,
  b,
  em {
    display: block;
  }

  b {
    color: var(--hos-text-secondary);

    font-size: 12px;
  }

  em {
    margin-top: 4px;

    color: var(--hos-text-primary);

    font-style: normal;

    font-weight: 700;
  }
}

.cover-archive-summary {
  display: flex;

  flex-wrap: wrap;

  justify-content: center;

  gap: 10px;

  span {
    min-height: 30px;

    padding: 6px 12px;

    color: var(--hos-primary-deep);

    background: var(--hos-primary-soft);

    border: 1px solid var(--hos-border-light);

    border-radius: 999px;

    font-size: 12px;

    font-weight: 700;
  }
}

.print-preflight-list {
  display: grid;

  gap: 10px;

  article {
    display: grid;

    grid-template-columns: 12px minmax(0, 1fr);

    gap: 10px;

    align-items: start;

    padding: 12px;

    background: var(--hos-glass);

    border: 1px solid var(--hos-border-light);

    border-radius: var(--hos-radius-lg);

    &.is-warning {
      background: var(--hos-status-warning-soft);

      border-color: rgb(217 119 6 / 20%);

      .preflight-dot {
        background: var(--hos-status-warning);

        box-shadow: 0 0 0 5px rgb(217 119 6 / 10%);
      }
    }

    &.is-success .preflight-dot {
      background: var(--hos-status-success);

      box-shadow: 0 0 0 5px rgb(22 163 74 / 10%);
    }
  }

  strong,
  small {
    display: block;
  }

  small {
    margin-top: 4px;

    color: var(--hos-text-secondary);
  }
}

.last-save-note {
  display: block;

  margin-top: 4px;

  color: var(--hos-text-secondary);

  font-size: 12px;
}

.archive-precheck-list {
  display: grid;

  gap: 10px;

  margin-top: 14px;

  button {
    display: grid;

    gap: 4px;

    width: 100%;

    padding: 12px 14px;

    text-align: left;

    background: var(--hos-status-warning-soft);

    border: 1px solid rgb(217 119 6 / 22%);

    border-radius: var(--hos-radius-lg);

    cursor: pointer;

    transition:
      transform var(--hos-duration-fast) var(--liquid-ease),
      border-color var(--hos-duration-fast) var(--liquid-ease),
      box-shadow var(--hos-duration-fast) var(--liquid-ease);

    &:hover {
      transform: translateY(-1px);

      border-color: rgb(217 119 6 / 36%);

      box-shadow: 0 12px 28px rgb(217 119 6 / 12%);
    }
  }

  span {
    width: fit-content;

    padding: 2px 8px;

    color: var(--hos-status-warning);

    background: rgb(255 255 255 / 70%);

    border-radius: 999px;

    font-size: 12px;

    font-weight: 700;
  }

  strong,
  small {
    display: block;
  }

  strong {
    color: var(--hos-text-primary);
  }

  small {
    color: var(--hos-text-secondary);
  }
}

.preflight-dot {
  width: 10px;

  height: 10px;

  margin-top: 4px;

  border-radius: 999px;
}

@keyframes record-progress-sheen {
  to {
    transform: translateX(120%);
  }
}

@keyframes record-complete-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgb(22 163 74 / 0%);
  }

  45% {
    box-shadow: 0 0 0 5px rgb(22 163 74 / 14%);
  }
}

@keyframes save-check-pop {
  from {
    opacity: 0;

    transform: scale(0.72);
  }

  to {
    opacity: 1;

    transform: scale(1);
  }
}

@keyframes field-focus-pulse {
  0%,
  100% {
    box-shadow:
      0 0 0 0 rgb(var(--hos-primary-rgb) / 0%),
      var(--hos-shadow-soft);
  }

  45% {
    box-shadow:
      0 0 0 5px rgb(var(--hos-primary-rgb) / 18%),
      var(--hos-shadow-card-hover);
  }
}

@media (prefers-reduced-motion: reduce) {
  .workbar-progress em,
  .workbar-progress em::after,
  .save-check,
  .my-field-item.focused,
  .field-row.focused,
  .workbench-field-row.focused {
    animation: none !important;

    transition: none !important;
  }
}

@media (max-width: 1320px) {
  .record-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .preview-panel {
    display: none;
  }
}

@media (max-width: 820px) {
  .record-toolbar,
  .record-workbar,
  .section-header {
    align-items: flex-start;

    flex-direction: column;
  }

  .workbar-main,
  .my-field-grid {
    grid-template-columns: 1fr;
  }

  .workbar-actions,
  .toolbar-actions {
    flex-wrap: wrap;
  }

  .field-layer-switch {
    align-items: flex-start;

    flex-direction: column;

    span {
      white-space: normal;
    }
  }

  .patient-strip,
  .archive-strip,
  .record-context-strip,
  .health-archive-summary,
  .detail-workspace-shell,
  .record-layout,
  .medical-record-field-grid,
  .field-row,
  .paper-field-line,
  .paper-meta,
  .paper-grid,
  .paper-archive-card,
  .audit-summary-grid,
  .ai-summary-grid,
  .ai-summary-lists,
  .followup-record-grid,
  .followup-record-grid.wide,
  .registration-section.compact-grid {
    grid-template-columns: 1fr;
  }

  .detail-side-nav {
    position: static;

    grid-template-columns: repeat(2, minmax(0, 1fr));

    button span {
      white-space: normal;
    }
  }

  .registration-table {
    th,
    td {
      display: block;

      width: 100% !important;
    }

    tr {
      display: block;

      margin-bottom: 8px;

      border: 1px solid #c9d8cd;
    }
  }

  .preview-page-pills {
    order: 3;

    width: 100%;

    max-width: 100%;
  }

  .preview-page-pills button {
    flex: 1 0 auto;
  }

  .management-section {
    padding: 12px;
  }

  .management-section .paper-field-line,
  .paper-followup-item,
  .paper-archive-timeline-item {
    grid-template-columns: 1fr;
  }

  .paper-followup-item span {
    text-align: left;
  }

  .archive-metrics {
    justify-content: flex-start;
  }

  .archive-actions {
    justify-content: flex-start;
  }

  .preview-paper {
    padding: 24px;
  }
}

@media print {
  @page {
    size: A4;

    margin: 12mm;
  }

  :global(body *) {
    visibility: hidden;
  }

  :global(body) {
    margin: 0;

    background: #ffffff;
  }

  .preview-overlay,
  .preview-overlay *,
  .preview-overlay-inner,
  .preview-overlay-scroll,
  .paper-stack,
  .paper-stack * {
    visibility: visible;
  }

  .screen-only {
    display: none !important;
  }

  .record-layout,
  .preview-overlay,
  .preview-overlay-inner,
  .preview-overlay-scroll,
  .paper-stack {
    display: block;
  }

  .preview-overlay,
  .preview-overlay-inner,
  .preview-overlay-scroll {
    position: static;

    width: auto;

    height: auto;

    max-width: none;

    max-height: none;

    padding: 0;

    overflow: visible;

    background: #ffffff;

    border: 0;

    box-shadow: none;
  }

  .preview-overlay-toolbar {
    display: none;
  }

  .preview-paper {
    width: auto;

    min-height: auto;

    min-height: 270mm;

    padding: 0 0 12mm;

    border: 0;

    box-shadow: none;

    break-after: page;

    page-break-after: always;
  }

  .preview-paper:last-child {
    break-after: auto;

    page-break-after: auto;
  }

  .attachment-image-frame {
    min-height: 220mm;

    img {
      max-height: 218mm;
    }
  }

  .paper-footer {
    right: 0;

    bottom: 0;

    left: 0;
  }

  .paper-watermark {
    color: rgb(22 101 52 / 6%);
  }
}
</style>
