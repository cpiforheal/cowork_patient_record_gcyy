<template>
  <!-- eslint-disable vue/html-closing-bracket-newline -->
  <div class="pre-ai-page">
    <header class="page-hero">
      <div>
        <el-tag type="primary" effect="dark">前置环节</el-tag>
        <h2>病历事实采集与脱敏资料</h2>
        <p>各岗位只维护本阶段事实；医生复核后生成脱敏 DOCX，本系统流程随即结束。</p>
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
      <aside
        class="encounter-sidebar"
        :class="{ expanded: patientDrawerOpen }"
        tabindex="0"
        @mouseenter="openPatientDrawer"
        @mouseleave="schedulePatientDrawerClose"
        @focusin="openPatientDrawer"
        @focusout="schedulePatientDrawerClose"
      >
        <button type="button" class="patient-drawer-rail" @click="togglePatientDrawer">
          <el-icon><User /></el-icon>
          <strong>{{ patientCases.length }}</strong>
          <span>{{ selectedPatientCase?.patientName || "患者" }}</span>
        </button>
        <div class="sidebar-title">
          <strong>患者主档案</strong>
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

      <aside v-if="workspace" class="workflow-sidebar">
        <section class="workflow-patient-card">
          <span>当前患者</span>
          <strong>{{ workspace.encounter.patient.patientName || "待补姓名" }}</strong>
          <small>{{ workspace.encounter.caseToken }}</small>
          <div>
            <el-tag size="small" :type="encounterStatusType(workspace.encounter.status)">
              {{ encounterStatusLabel[workspace.encounter.status] || workspace.encounter.status }}
            </el-tag>
            <em>{{ routeLabel(workspace.encounter.route) }}</em>
          </div>
        </section>
        <div class="workflow-title">
          <strong>院内流转</strong>
          <span>点击岗位卡片进入办理</span>
        </div>
        <el-scrollbar height="calc(100vh - 385px)">
          <div class="workflow-flow">
            <div v-for="card in workflowCards" :key="card.key" class="workflow-card-wrap">
              <button
                type="button"
                class="workflow-card"
                :class="{
                  active: isWorkflowCardActive(card),
                  mine: card.roles.includes(currentRole),
                  current: isCurrentWorkflowCard(card),
                  skipped: workflowCardStatus(card) === 'SKIPPED'
                }"
                @click="selectWorkflowCard(card)"
              >
                <span class="workflow-order">{{ card.order }}</span>
                <div class="workflow-card-main">
                  <strong>{{ card.title }}</strong>
                  <small>{{ card.owner }}</small>
                  <em v-if="card.roles.includes(currentRole)">当前岗位可办理</em>
                </div>
                <el-tag size="small" :type="stageStatusType(workflowCardStatus(card))">
                  {{ workflowCardStatusLabel(card) }}
                </el-tag>
              </button>
            </div>
          </div>
        </el-scrollbar>
      </aside>

      <main v-loading="workspaceLoading" class="encounter-workspace">
        <el-empty v-if="!workspace" description="请从左侧选择患者，或新建前置病历" />
        <section v-else-if="!workflowSelected" class="workflow-empty-panel">
          <el-empty :image-size="112" description="请点击左侧院内流转岗位卡片">
            <template #description>
              <strong>请选择需要查看或填写的岗位节点</strong>
              <p>患者资料、岗位流转和填写内容已整合在同一个工作台中。</p>
            </template>
          </el-empty>
        </section>
        <template v-else>
          <section class="patient-banner">
            <div>
              <h3>{{ workspace.encounter.patient.patientName || "待补姓名" }}</h3>
              <p>
                {{ workspace.encounter.caseToken }} · {{ workspace.encounter.patient.gender || "待补性别" }} ·
                {{ workspace.encounter.patient.age || "待补年龄" }}
              </p>
            </div>
            <div class="patient-banner__meta">
              <el-tag :type="encounterStatusType(workspace.encounter.status)">
                {{ encounterStatusLabel[workspace.encounter.status] || workspace.encounter.status }}
              </el-tag>
              <span>{{ routeLabel(workspace.encounter.route) }}</span>
              <span>{{ treatmentPathLabel(workspace.encounter.treatmentPath) }}</span>
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
                @click="editorMode = 'PREVIEW'"
              >
                模板预览态
              </button>
            </div>
            <el-tag :type="stageStatusType(workflowCardStatus(activeWorkflowCard))">
              {{ workflowCardStatusLabel(activeWorkflowCard) }}
            </el-tag>
          </div>

          <section v-if="editorMode === 'PREVIEW'" class="template-preview-panel">
            <div class="document-preview-toolbar">
              <div>
                <strong>前置病历事实资料预览</strong>
                <span>内部预览使用真实资料，正式导出时由系统自动脱敏</span>
              </div>
              <el-tag type="warning" effect="plain">非正式住院病历</el-tag>
            </div>
            <article class="document-sheet">
              <header class="document-header">
                <h2>前置病历事实资料</h2>
                <p>供医生复核并作为外部病历生成前的结构化事实来源</p>
                <div class="document-meta">
                  <span>病例标识：{{ workspace.encounter.caseToken }}</span>
                  <span>就诊日期：{{ workspace.encounter.patient.visitDate || "待填写" }}</span>
                  <span>就诊分支：{{ routeLabel(workspace.encounter.route) }}</span>
                </div>
              </header>
              <section v-for="section in documentPreviewSections" :key="section.key" class="document-section">
                <h3>{{ section.title }}</h3>
                <p v-if="section.note" class="document-section-note">{{ section.note }}</p>
                <div v-if="section.rows.length" class="document-fields">
                  <div v-for="row in section.rows" :key="row.key" :class="{ wide: row.wide }">
                    <strong>{{ row.label }}：</strong>
                    <span :class="{ empty: row.empty }">{{ row.value }}</span>
                  </div>
                </div>
                <p v-else class="document-empty">本节暂无已维护内容</p>
              </section>
              <footer class="document-footer">各岗位维护事实 · 医生统一复核 · 导出自动脱敏</footer>
            </article>
          </section>

          <template v-else>
            <section v-if="selectedPanel === 'STAGE'" class="stage-panel">
              <template v-if="selectedStageCode !== 'REVIEW'">
                <div class="panel-heading">
                  <div>
                    <h3>{{ selectedStage.title }}</h3>
                    <p>{{ selectedStage.description }}</p>
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
                  <button type="button" :class="{ active: inspectionView === 'CURRENT' }" @click="inspectionView = 'CURRENT'">
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
                      <el-input
                        v-if="field.kind === 'input' || field.kind === 'number'"
                        v-model="stageForms[selectedStageCode][field.key]"
                        :type="field.kind === 'number' ? 'number' : 'text'"
                        :placeholder="field.placeholder"
                        :disabled="!canEditSelectedStage"
                      />
                      <el-input
                        v-else-if="field.kind === 'textarea'"
                        v-model="stageForms[selectedStageCode][field.key]"
                        type="textarea"
                        :rows="field.rows || 3"
                        :placeholder="field.placeholder"
                        :disabled="!canEditSelectedStage"
                      />
                      <el-select
                        v-else-if="field.kind === 'select'"
                        v-model="stageForms[selectedStageCode][field.key]"
                        clearable
                        filterable
                        :allow-create="field.creatable"
                        default-first-option
                        :placeholder="field.placeholder || `请选择${field.label}`"
                        :disabled="!canEditSelectedStage"
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
                        :disabled="!canEditSelectedStage"
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
                        :disabled="!canEditSelectedStage"
                      />
                    </el-form-item>
                  </div>
                </el-form>

                <section
                  v-if="
                    (selectedStageCode !== 'INSPECTION' || inspectionView === 'CURRENT') &&
                    (selectedStageCode === 'INSPECTION' || selectedStageCode === 'SURGERY')
                  "
                  class="attachment-section"
                >
                  <div class="section-caption">本阶段附件（不会进入外部 DOCX）</div>
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

                <footer v-if="selectedStageCode !== 'INSPECTION' || inspectionView === 'CURRENT'" class="panel-actions">
                  <el-button v-if="canReturnSelectedStage" type="warning" plain @click="returnStage(selectedStageCode)"
                    >退回修改</el-button
                  >
                  <div></div>
                  <el-button v-if="canEditSelectedStage" :loading="actionLoading" @click="saveSelectedStage">保存草稿</el-button>
                  <el-button v-if="canEditSelectedStage" type="primary" :loading="actionLoading" @click="completeSelectedStage"
                    >完成并交接</el-button
                  >
                </footer>
              </template>

              <template v-else>
                <div class="panel-heading">
                  <div>
                    <h3>医生最终复核</h3>
                    <p>下面预览的是即将进入脱敏 DOCX 的内容；身份证、详细地址、真实业务编号、人员姓名和原图不会输出。</p>
                  </div>
                  <el-button :icon="Refresh" @click="loadReviewPreview">刷新预览</el-button>
                </div>
                <el-alert
                  v-if="reviewPreview?.blockers?.length"
                  type="warning"
                  show-icon
                  :closable="false"
                  title="当前不能完成复核"
                >
                  <template #default>{{ reviewPreview.blockers.join("；") }}</template>
                </el-alert>
                <div v-if="reviewPreview" class="masked-preview">
                  <section v-for="section in maskedSections" :key="section.title">
                    <h4>{{ section.title }}</h4>
                    <div class="read-only-grid">
                      <div v-for="entry in section.entries" :key="entry[0]">
                        <span>{{ reviewFieldLabel(entry[0]) }}</span>
                        <p>{{ humanValue(entry[1]) }}</p>
                      </div>
                    </div>
                  </section>
                </div>
                <el-input v-if="canReview" v-model="reviewStatement" type="textarea" :rows="3" placeholder="复核说明（选填）" />
                <footer class="panel-actions">
                  <div></div>
                  <el-button
                    v-if="canReview && workspace.encounter.status !== 'REVIEWED' && workspace.encounter.status !== 'EXPORTED'"
                    type="primary"
                    :disabled="!reviewPreview?.ready"
                    :loading="actionLoading"
                    @click="confirmReview"
                    >确认事实无误</el-button
                  >
                  <el-button
                    v-if="canReview && ['REVIEWED', 'EXPORTED'].includes(workspace.encounter.status)"
                    type="success"
                    :loading="actionLoading"
                    @click="generateExport"
                    >生成脱敏 DOCX</el-button
                  >
                </footer>
                <section v-if="workspace.exports.length" class="export-list">
                  <div class="section-caption">历史导出版本（新版本不覆盖旧文件）</div>
                  <div v-for="version in workspace.exports" :key="version.id" class="export-row">
                    <div>
                      <strong>{{ version.fileName }}</strong>
                      <small>{{ version.generatedAt }} · {{ version.generatedByRole || "医生" }}</small>
                    </div>
                    <el-button type="primary" plain @click="downloadPreAiExportApi(version)">下载</el-button>
                  </div>
                </section>
              </template>
            </section>

            <section v-else class="stage-panel auxiliary-panel">
              <div class="panel-heading">
                <div>
                  <h3>化验室检验报告</h3>
                  <p>检验数值仍在原化验报告模板中填写；此处展示已经保存的完整报告并负责完成交接。</p>
                </div>
                <el-button v-if="canOpenLabWorkbench" type="primary" @click="openLabWorkbench">去填写/继续填写</el-button>
              </div>
              <el-alert
                v-if="labTask?.status === 'RETURNED'"
                type="warning"
                show-icon
                :closable="false"
                title="医生已退回化验室，请补充或更正报告后重新完成交接。"
              />
              <el-empty
                v-if="!workspace.labReports.length"
                :image-size="72"
                description="尚未保存检验报告，请进入化验报告模板填写"
              />
              <el-tabs v-else v-model="activeLabReportId" class="lab-report-tabs">
                <el-tab-pane
                  v-for="report in workspace.labReports"
                  :key="report.id"
                  :name="report.id"
                  :label="`${report.templateName} · ${report.reportDate}`"
                >
                  <article class="lab-report-paper">
                    <header>
                      <h3>固始中医肛肠医院检验报告单</h3>
                      <p>{{ report.templateName }}</p>
                    </header>
                    <div class="lab-patient-line">
                      <span>姓名：{{ workspace.encounter.patient.patientName }}</span>
                      <span>性别：{{ workspace.encounter.patient.gender || "待补充" }}</span>
                      <span>病例标识：{{ workspace.encounter.caseToken }}</span>
                      <span>日期：{{ report.reportDate }}</span>
                    </div>
                    <table>
                      <thead>
                        <tr>
                          <th>项目</th>
                          <th>简称</th>
                          <th>结果</th>
                          <th>单位</th>
                          <th>参考范围</th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr v-for="metric in report.metrics" :key="metric.key">
                          <td>{{ metric.name }}</td>
                          <td>{{ metric.shortName }}</td>
                          <td>{{ metric.value }}</td>
                          <td>{{ metric.unit }}</td>
                          <td>{{ metric.reference }}</td>
                        </tr>
                      </tbody>
                    </table>
                    <footer>
                      <span>报告版本：v{{ report.version }}</span
                      ><span>备注：{{ report.remark || "无" }}</span>
                    </footer>
                  </article>
                </el-tab-pane>
              </el-tabs>
              <section v-if="legacyAuxiliaryTasks.length" class="legacy-auxiliary">
                <div class="section-caption">旧辅助资料（只读保留）</div>
                <div v-for="task in legacyAuxiliaryTasks" :key="task.id" class="read-only-grid">
                  <div>
                    <span>{{ auxiliaryTaskLabel[task.taskType] }}</span>
                    <p>{{ humanValue(task.data) }}</p>
                  </div>
                </div>
              </section>
              <footer class="panel-actions compact-actions">
                <el-button
                  v-if="canReview && labTask?.status === 'COMPLETED'"
                  type="warning"
                  plain
                  @click="returnAuxTask(labTask.id)"
                  >退回化验室</el-button
                >
                <div></div>
                <el-button
                  v-if="canCompleteLab"
                  type="primary"
                  :loading="actionLoading"
                  :disabled="!workspace.labReports.length || labTask?.status === 'COMPLETED'"
                  @click="completeLab"
                  >完成并交接</el-button
                >
              </footer>
            </section>
          </template>
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
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { FolderOpened, Plus, Refresh, Search, Upload, User } from "@element-plus/icons-vue";
import { useUserStore } from "@/stores/modules/user";
import { useRouter } from "vue-router";
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
  savePreAiStageApi,
  uploadPreAiAttachmentApi,
  voidPreAiAttachmentApi,
  type PatientRow,
  type InspectionTimelineNode,
  type PreAiAttachment,
  type PreAiEncounterStatus,
  type PreAiEncounterSummary,
  type PreAiExportVersion,
  type PreAiPatientCase,
  type PreAiReviewPreview,
  type PreAiStageCode,
  type PreAiStageStatus,
  type PreAiWorkspace
} from "@/api/modules/clinic";
import {
  auxiliaryTaskFields,
  auxiliaryTaskLabel,
  encounterStatusLabel,
  preAiStages,
  stageByCode,
  stageStatusLabel,
  type PreAiFieldConfig
} from "./fieldConfig";

const userStore = useUserStore();
const router = useRouter();
const currentRole = computed(() => userStore.userInfo.role || "frontdesk");
const canCreateEncounter = computed(() => ["admin", "frontdesk"].includes(currentRole.value));
const canImportLegacy = computed(() => ["admin", "frontdesk", "doctor"].includes(currentRole.value));
const canReview = computed(() => ["admin", "doctor"].includes(currentRole.value));
const canOpenLabWorkbench = computed(() => ["admin", "doctor", "lab"].includes(currentRole.value));
const canCompleteLab = computed(() => ["admin", "doctor", "lab"].includes(currentRole.value));

const encounters = ref<PreAiEncounterSummary[]>([]);
const patientCases = ref<PreAiPatientCase[]>([]);
const keyword = ref("");
const selectedPatientCaseId = ref("");
const patientDrawerOpen = ref(false);
let patientDrawerCloseTimer: ReturnType<typeof setTimeout> | undefined;
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

const createDialogVisible = ref(false);
const createForm = reactive<Record<string, any>>({ visitDate: new Date().toISOString().slice(0, 10) + " 08:00:00" });
const legacyDialogVisible = ref(false);
const selectedLegacyPatientId = ref("");
const legacyPatients = ref<PatientRow[]>([]);
const followUpDialogVisible = ref(false);
const followUpPatientCase = ref<PreAiPatientCase>();
const followUpForm = reactive<Record<string, any>>({ visitDate: new Date().toISOString().slice(0, 10) + " 08:00:00" });

type WorkflowCard = {
  key: string;
  order: number;
  kind: "STAGE" | "AUX";
  title: string;
  owner: string;
  roles: string[];
  stageCode?: PreAiStageCode;
};

type DocumentPreviewRow = { key: string; label: string; value: string; empty: boolean; wide: boolean };
type DocumentPreviewSection = { key: string; title: string; note?: string; rows: DocumentPreviewRow[] };

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
    roles: ["admin", "reception"]
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
const canEditSelectedStage = computed(() => {
  if (!workspace.value || selectedStageCode.value === "REVIEW") return false;
  const submission = selectedStageSubmission.value;
  if (!selectedStage.value.roles.includes(currentRole.value)) return false;
  return submission.status !== "COMPLETED" || currentRole.value === "admin";
});
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
const selectedStageAttachments = computed(
  () => workspace.value?.attachments.filter(item => item.stageCode === selectedStageCode.value && !item.taskId) || []
);
const selectedAttachmentGroups = computed(() => {
  const groups = new Map<string, { id: string; name: string; items: typeof selectedStageAttachments.value }>();
  selectedStageAttachments.value.forEach(item => {
    const id = item.batchId || item.id;
    const current = groups.get(id) || { id, name: item.batchName || "独立上传", items: [] };
    current.items.push(item);
    groups.set(id, current);
  });
  return Array.from(groups.values()).map(group => ({
    ...group,
    items: group.items.sort((left, right) => (left.sequenceNo || 0) - (right.sequenceNo || 0))
  }));
});
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
const documentPreviewSections = computed<DocumentPreviewSection[]>(() => {
  if (!workspace.value) return [];
  const sections: DocumentPreviewSection[] = [
    previewStageSection("REGISTRATION", "患者基础信息", "身份信息仅用于院内协作，正式导出时自动脱敏。", ["identityNumber"]),
    previewStageSection("RECEPTION", "主诉和现病情况"),
    previewStageSection("INSPECTION", "专科检查事实", "原始图片不进入导出文档，仅呈现确认后的文字所见。")
  ];
  const auxiliaryRows: DocumentPreviewRow[] = workspace.value.labReports.map(report => {
    const values = report.metrics.map(
      metric => `${metric.name}${metric.shortName ? `（${metric.shortName}）` : ""}：${metric.value}${metric.unit || ""}`
    );
    return {
      key: report.id,
      label: `${report.templateName}｜${report.reportDate}`,
      value: values.length ? values.join("；") : "________________",
      empty: !values.length,
      wide: true
    };
  });
  sections.push({
    key: "AUX",
    title: "化验室检验报告",
    note: "仅展示化验报告模板中已经保存的真实结果。",
    rows: auxiliaryRows
  });
  sections.push(previewStageSection("TCM", "中医四诊、病名、证候和治法"));
  sections.push(previewStageSection("DOCTOR", "西医诊断与治疗方案"));
  const surgeryData = stageForms.SURGERY;
  const showSurgery =
    workspace.value.encounter.treatmentPath === "SURGICAL" ||
    Object.values(surgeryData).some(value => value !== undefined && value !== null && value !== "");
  if (showSurgery) sections.push(previewStageSection("SURGERY", "实际手术情况"));
  sections.push({
    key: "REVIEW",
    title: "医生复核信息",
    rows: [
      {
        key: "reviewStatus",
        label: "复核状态",
        value: workspace.value.encounter.reviewedAt ? "已复核" : "待医生复核",
        empty: !workspace.value.encounter.reviewedAt,
        wide: false
      },
      {
        key: "reviewedAt",
        label: "复核时间",
        value: workspace.value.encounter.reviewedAt || "________________",
        empty: !workspace.value.encounter.reviewedAt,
        wide: false
      },
      {
        key: "reviewStatement",
        label: "复核说明",
        value: reviewStatement.value || stageForms.REVIEW.reviewStatement || "________________",
        empty: !(reviewStatement.value || stageForms.REVIEW.reviewStatement),
        wide: true
      }
    ]
  });
  return sections;
});

const deepCopy = <T,>(value: T): T => JSON.parse(JSON.stringify(value ?? {}));
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
const nonEmptyEntries = (value: Record<string, any> = {}) =>
  Object.entries(value).filter(
    ([, item]) => item !== undefined && item !== null && item !== "" && (!Array.isArray(item) || item.length)
  );
const humanValue = (value: any) =>
  Array.isArray(value) ? value.join("、") : typeof value === "object" ? JSON.stringify(value) : String(value ?? "");
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
const formatPreviewValue = (key: string, value: any) => {
  if (value === undefined || value === null || value === "" || (Array.isArray(value) && !value.length)) return "________________";
  if (["finalRoute", "dispositionSuggestion"].includes(key)) return routeLabel(String(value));
  if (key === "treatmentPath") return treatmentPathLabel(String(value));
  if (key === "examinationTypes" && Array.isArray(value)) {
    const labels: Record<string, string> = {
      VISUAL: "外观检查",
      DIGITAL: "指检",
      ANOSCOPY: "肛门镜/镜下检查",
      OTHER: "其他检查"
    };
    return value.map(item => labels[item] || item).join("、");
  }
  return humanValue(value);
};
const previewStageSection = (
  code: PreAiStageCode,
  title: string,
  note = "",
  excludedKeys: string[] = []
): DocumentPreviewSection => {
  const form = stageForms[code];
  const rows = stageByCode(code)
    .fields.filter(field => !excludedKeys.includes(field.key))
    .filter(field => !field.visible || field.visible(form))
    .map(field => {
      const value = form[field.key];
      const empty = value === undefined || value === null || value === "" || (Array.isArray(value) && !value.length);
      return {
        key: `${code}-${field.key}`,
        label: field.label,
        value: empty ? "________________" : formatPreviewValue(field.key, value),
        empty,
        wide: field.span === 2 || field.kind === "textarea" || field.kind === "multi"
      };
    });
  return { key: code, title, note, rows };
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
  } catch (error: any) {
    ElMessage.error(error.message || "患者列表加载失败");
  }
};

const hydrateWorkspace = (value: PreAiWorkspace) => {
  workspace.value = value;
  value.stages.forEach(stage => {
    const normalized = deepCopy(stage.data);
    stageByCode(stage.stageCode).fields.forEach(field => {
      if (field.kind === "multi" && normalized[field.key] && !Array.isArray(normalized[field.key])) {
        normalized[field.key] = [String(normalized[field.key])];
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
};

const selectEncounter = async (id: string) => {
  selectedEncounterId.value = id;
  workspaceLoading.value = true;
  try {
    const { data } = await getPreAiWorkspaceApi(id);
    hydrateWorkspace(data);
    selectedPatientCaseId.value = data.encounter.patientCaseId;
    selectedStageCode.value = data.encounter.currentStage || "REGISTRATION";
    selectedPanel.value = "STAGE";
    workflowSelected.value = false;
    editorMode.value = "EDIT";
    reviewPreview.value = undefined;
  } catch (error: any) {
    ElMessage.error(error.message || "前置病历加载失败");
  } finally {
    workspaceLoading.value = false;
  }
};

const selectPatientCase = async (patientCase: PreAiPatientCase) => {
  selectedPatientCaseId.value = patientCase.id;
  patientDrawerOpen.value = false;
  if (!patientCase.latestEncounter) return;
  await selectEncounter(patientCase.latestEncounter.id);
};

const openPatientDrawer = () => {
  if (patientDrawerCloseTimer) clearTimeout(patientDrawerCloseTimer);
  patientDrawerOpen.value = true;
};

const schedulePatientDrawerClose = () => {
  if (patientDrawerCloseTimer) clearTimeout(patientDrawerCloseTimer);
  patientDrawerCloseTimer = setTimeout(() => {
    patientDrawerOpen.value = false;
  }, 360);
};

const togglePatientDrawer = () => {
  if (window.matchMedia("(max-width: 680px)").matches) patientDrawerOpen.value = !patientDrawerOpen.value;
  else openPatientDrawer();
};

const selectStage = async (code: PreAiStageCode) => {
  selectedStageCode.value = code;
  inspectionView.value = "CURRENT";
  selectedPanel.value = "STAGE";
  workflowSelected.value = true;
  editorMode.value = "EDIT";
  if (code === "REVIEW") await loadReviewPreview();
};

const clearTimelineImageUrls = () => {
  Object.values(timelineImageUrls).forEach(url => URL.revokeObjectURL(url));
  Object.keys(timelineImageUrls).forEach(key => delete timelineImageUrls[key]);
};

const showInspectionTimeline = async () => {
  inspectionView.value = "HISTORY";
  const patientCaseId = workspace.value?.encounter.patientCaseId;
  if (!patientCaseId) return;
  timelineLoading.value = true;
  clearTimelineImageUrls();
  try {
    const { data } = await getPreAiInspectionTimelineApi(patientCaseId);
    inspectionTimeline.value = data.nodes;
    const imageAttachments = data.nodes.flatMap(node => node.attachments).filter(item => item.mimeType?.startsWith("image/"));
    await Promise.all(
      imageAttachments.map(async attachment => {
        try {
          timelineImageUrls[attachment.id] = await getPreAiAttachmentObjectUrlApi(attachment);
        } catch {
          // 单张历史图片加载失败不影响其余时间轴内容。
        }
      })
    );
  } catch (error: any) {
    ElMessage.error(error.message || "检查室历史时间轴加载失败");
  } finally {
    timelineLoading.value = false;
  }
};

const openTimelineAttachment = async (attachment: PreAiAttachment) => {
  const url = timelineImageUrls[attachment.id];
  if (url) {
    window.open(url, "_blank", "noopener,noreferrer");
    return;
  }
  await downloadPreAiAttachmentApi(attachment);
};

const timelineAttachmentGroups = (attachments: PreAiAttachment[]) => {
  const groups = new Map<string, { id: string; name: string; items: PreAiAttachment[] }>();
  attachments.forEach(attachment => {
    const id = attachment.batchId || attachment.id;
    const group = groups.get(id) || { id, name: attachment.batchName || "独立上传", items: [] };
    group.items.push(attachment);
    groups.set(id, group);
  });
  return Array.from(groups.values());
};

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
  if (card.kind === "AUX") {
    selectedPanel.value = "AUX";
    workflowSelected.value = true;
    editorMode.value = "EDIT";
    return;
  }
  if (card.stageCode) await selectStage(card.stageCode);
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
    const { data } = await completePreAiStageApi(
      selectedEncounterId.value,
      selectedStageCode.value,
      cleanStageForm(selectedStageCode.value)
    );
    hydrateWorkspace(data);
    await loadEncounterList();
    ElMessage.success("本阶段已完成并交接");
  });

const cleanStageForm = (code: PreAiStageCode) => {
  const fields = stageByCode(code).fields;
  const result: Record<string, any> = {};
  fields.forEach(field => {
    if (field.visible && !field.visible(stageForms[code])) return;
    const value = stageForms[code][field.key];
    if (value !== undefined && value !== null && value !== "" && (!Array.isArray(value) || value.length))
      result[field.key] = value;
  });
  return result;
};

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
    const { value } = await ElMessageBox.prompt("请填写退回原因", "退回化验室", {
      inputPattern: /\S+/,
      inputErrorMessage: "退回原因不能为空"
    });
    await runAction(async () => {
      const { data } = await returnPreAiAuxiliaryTaskApi(selectedEncounterId.value, taskId, value);
      hydrateWorkspace(data);
      await loadEncounterList();
      ElMessage.success("化验室已退回");
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
  if (!selectedEncounterId.value || !canReview.value) return;
  try {
    const { data } = await getPreAiReviewPreviewApi(selectedEncounterId.value);
    reviewPreview.value = data;
  } catch (error: any) {
    ElMessage.error(error.message || "脱敏预览加载失败");
  }
};

const confirmReview = async () =>
  runAction(async () => {
    const { data } = await confirmPreAiReviewApi(selectedEncounterId.value, reviewStatement.value);
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

onMounted(loadEncounterList);
onBeforeUnmount(() => {
  clearTimelineImageUrls();
  if (patientDrawerCloseTimer) clearTimeout(patientDrawerCloseTimer);
});
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
.workflow-sidebar,
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
  margin: 8px 0 4px;
  font-size: 24px;
}
.page-hero p,
.panel-heading p {
  margin: 0;
  color: var(--el-text-color-secondary);
}
.hero-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
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
.encounter-sidebar:hover,
.encounter-sidebar:focus-within,
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
.encounter-sidebar:hover .sidebar-title,
.encounter-sidebar:hover :deep(.el-scrollbar),
.encounter-sidebar:focus-within .sidebar-title,
.encounter-sidebar:focus-within :deep(.el-scrollbar),
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
.workflow-sidebar {
  padding: 14px;
}
.sidebar-title {
  display: grid;
  gap: 10px;
  margin-bottom: 12px;
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
.workflow-patient-card {
  display: grid;
  gap: 5px;
  padding: 13px;
  margin-bottom: 14px;
  color: white;
  border-radius: 12px;
  background: linear-gradient(135deg, #2f6da5, #4f93c8);
  box-shadow: 0 9px 20px rgb(47 109 165 / 20%);
}
.workflow-patient-card > span,
.workflow-patient-card > small {
  color: rgb(255 255 255 / 78%);
}
.workflow-patient-card > strong {
  font-size: 18px;
}
.workflow-patient-card > div {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}
.workflow-patient-card em {
  font-style: normal;
  font-size: 12px;
}
.workflow-title {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 10px;
  padding: 0 2px 10px;
}
.workflow-title span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.workflow-flow {
  display: grid;
  gap: 15px;
  padding: 2px 3px 10px;
}
.workflow-card-wrap {
  position: relative;
}
.workflow-card-wrap:not(:last-child)::after {
  position: absolute;
  left: 19px;
  top: calc(100% + 2px);
  width: 2px;
  height: 12px;
  content: "";
  background: linear-gradient(var(--el-border-color), var(--el-color-primary-light-7));
}
.workflow-card-wrap:not(:last-child)::before {
  position: absolute;
  z-index: 2;
  left: 15px;
  top: calc(100% + 8px);
  width: 8px;
  height: 8px;
  content: "";
  border-right: 2px solid var(--el-color-primary-light-5);
  border-bottom: 2px solid var(--el-color-primary-light-5);
  transform: rotate(45deg);
}
.workflow-card {
  position: relative;
  width: 100%;
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) auto;
  align-items: center;
  gap: 9px;
  padding: 11px 9px;
  text-align: left;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  background: var(--el-bg-color);
  cursor: pointer;
  transition: 0.2s ease;
}
.workflow-card:hover,
.workflow-card.active {
  border-color: var(--el-color-primary);
  box-shadow: 0 8px 20px rgb(64 158 255 / 14%);
  transform: translateX(4px);
}
.workflow-card.active {
  background: linear-gradient(135deg, var(--el-color-primary-light-9), var(--el-bg-color));
}
.workflow-card.current::after {
  position: absolute;
  inset: 7px auto 7px 0;
  width: 3px;
  content: "";
  border-radius: 0 4px 4px 0;
  background: var(--el-color-warning);
  animation: current-stage-pulse 1.8s ease-in-out infinite;
}
@keyframes current-stage-pulse {
  50% {
    opacity: 0.42;
    box-shadow: 0 0 0 5px rgb(230 162 60 / 10%);
  }
}
.workflow-card.mine .workflow-order {
  color: white;
  background: var(--el-color-primary);
}
.workflow-card.skipped {
  opacity: 0.58;
}
.workflow-order {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  color: var(--el-color-primary);
  font-weight: 700;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
}
.workflow-card-main {
  min-width: 0;
  display: grid;
  gap: 3px;
}
.workflow-card-main strong,
.workflow-card-main small,
.workflow-card-main em {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.workflow-card-main small {
  color: var(--el-text-color-secondary);
}
.workflow-card-main em {
  color: var(--el-color-primary);
  font-style: normal;
  font-size: 11px;
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
  gap: 16px;
  padding: 16px 20px;
  margin-bottom: 10px;
}
.patient-banner h3 {
  margin: 0 0 5px;
  font-size: 21px;
}
.patient-banner p {
  margin: 0;
  color: var(--el-text-color-secondary);
}
.patient-banner__meta {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
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
.template-preview-panel {
  padding: 18px;
  background: var(--el-fill-color-light);
}
.document-preview-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}
.document-preview-toolbar > div {
  display: grid;
  gap: 3px;
}
.document-preview-toolbar span {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.document-sheet {
  width: min(100%, 860px);
  min-height: 1120px;
  margin: 0 auto;
  padding: 52px 58px;
  color: #252525;
  border: 1px solid #d9d9d9;
  background: white;
  box-shadow: 0 16px 45px rgb(0 0 0 / 10%);
  font-family: "SimSun", "宋体", serif;
}
.document-header {
  padding-bottom: 20px;
  text-align: center;
  border-bottom: 2px solid #2f2f2f;
}
.document-header h2 {
  margin: 0 0 9px;
  font-family: "SimHei", "黑体", sans-serif;
  font-size: 25px;
  letter-spacing: 4px;
}
.document-header p {
  margin: 0 0 17px;
  color: #666;
  font-size: 13px;
}
.document-meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  text-align: left;
  font-size: 13px;
}
.document-section {
  padding-top: 21px;
}
.document-section h3 {
  margin: 0 0 12px;
  padding: 7px 10px;
  font-family: "SimHei", "黑体", sans-serif;
  font-size: 16px;
  border-left: 4px solid #335d82;
  background: #edf3f7;
}
.document-section-note {
  margin: -3px 0 12px;
  color: #777;
  font-size: 12px;
}
.document-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 11px 20px;
  line-height: 1.8;
  font-size: 14px;
}
.document-fields > div {
  min-width: 0;
  padding-bottom: 5px;
  border-bottom: 1px dotted #aaa;
}
.document-fields > div.wide {
  grid-column: span 2;
}
.document-fields strong {
  font-family: "SimHei", "黑体", sans-serif;
  font-weight: 500;
}
.document-fields span {
  white-space: pre-wrap;
}
.document-fields span.empty,
.document-empty {
  color: #aaa;
}
.document-empty {
  margin: 0;
  font-size: 13px;
}
.document-footer {
  padding-top: 36px;
  margin-top: 28px;
  color: #777;
  text-align: center;
  border-top: 1px solid #bbb;
  font-size: 12px;
  letter-spacing: 1px;
}
.stage-panel {
  padding: 20px;
}
.panel-heading {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: flex-start;
  margin-bottom: 16px;
}
.panel-heading h3 {
  margin: 0 0 6px;
  font-size: 20px;
}
.heading-tags {
  display: flex;
  gap: 7px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}
.form-grid .span-2 {
  grid-column: span 2;
}
.stage-form {
  margin-top: 18px;
}
.stage-form :deep(.el-select),
.stage-form :deep(.el-date-editor),
.dialog-grid :deep(.el-select),
.dialog-grid :deep(.el-date-editor),
.aux-card :deep(.el-date-editor) {
  width: 100%;
}
.section-caption {
  margin: 18px 0 10px;
  padding-left: 9px;
  border-left: 3px solid var(--el-color-primary);
  font-weight: 650;
}
.upstream-section {
  margin-bottom: 16px;
}
.read-only-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 18px;
}
.read-only-grid div {
  padding: 10px 12px;
  border-radius: 9px;
  background: var(--el-fill-color-light);
}
.read-only-grid span {
  display: block;
  margin-bottom: 4px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.read-only-grid p {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.7;
}
.panel-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 18px;
  margin-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.panel-actions > div {
  flex: 1;
}
.attachment-list {
  display: grid;
  gap: 8px;
}
.attachment-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: 9px;
  background: var(--el-fill-color-light);
}
.attachment-row span {
  flex: 1;
}
.attachment-batch {
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}
.attachment-batch > header {
  display: flex;
  justify-content: space-between;
  padding: 10px 12px;
  background: var(--el-fill-color-lighter);
}
.attachment-name {
  display: grid;
  flex: 1;
  min-width: 0;
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
  gap: 8px;
}
.upload-summary {
  color: var(--el-text-color-secondary);
}
.attachment-row small {
  color: var(--el-text-color-secondary);
}
.upload-button {
  display: inline-flex;
  width: fit-content;
  align-items: center;
  gap: 6px;
  padding: 8px 13px;
  color: var(--el-color-primary);
  border: 1px dashed var(--el-color-primary);
  border-radius: 8px;
  cursor: pointer;
}
.upload-button input {
  display: none;
}
.aux-card {
  margin-top: 14px;
  padding: 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
}
.aux-card header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.aux-card header > div {
  display: flex;
  align-items: center;
  gap: 9px;
}
.compact {
  margin-top: 12px;
}
.compact-actions {
  margin-top: 10px;
}
.lab-report-tabs {
  margin-top: 14px;
}
.lab-report-paper {
  padding: 22px;
  color: #1f2937;
  border: 1px solid #d6dce5;
  background: #fff;
  box-shadow: 0 10px 24px rgb(15 23 42 / 8%);
}
.lab-report-paper header {
  display: block;
  margin-bottom: 14px;
  text-align: center;
}
.lab-report-paper h3,
.lab-report-paper p {
  margin: 0 0 6px;
}
.lab-patient-line {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  padding: 10px 0;
  font-size: 13px;
}
.lab-report-paper table {
  width: 100%;
  border-collapse: collapse;
}
.lab-report-paper th,
.lab-report-paper td {
  padding: 8px;
  text-align: center;
  border: 1px solid #4b5563;
}
.lab-report-paper footer {
  display: flex;
  justify-content: space-between;
  margin-top: 14px;
}
.legacy-auxiliary {
  margin-top: 18px;
}
.masked-preview {
  display: grid;
  gap: 14px;
  margin-top: 15px;
}
.masked-preview section {
  padding: 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
}
.masked-preview h4 {
  margin: 0 0 10px;
  color: var(--el-color-primary);
}
.export-list {
  margin-top: 20px;
}
.export-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 15px;
  padding: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.export-row div {
  display: grid;
  gap: 4px;
}
.export-row small {
  color: var(--el-text-color-secondary);
}
.legacy-select {
  width: 100%;
  margin-top: 18px;
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
  .panel-heading {
    flex-direction: column;
    align-items: stretch;
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
  .workflow-sidebar,
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
  .workspace-modebar,
  .document-preview-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
  .workspace-modebar {
    grid-template-columns: 1fr;
  }
  .workspace-modebar > .el-tag:last-child {
    justify-self: start;
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
