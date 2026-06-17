<template>
  <div class="main-box record-workspace">
    <TreeFilter
      id="key"
      label="title"
      title="病历章节"
      class="screen-only"
      :data="recordSectionsByRule"
      :default-value="activeSectionKey"
      @change="changeSectionFilter"
    />
    <div class="table-box">
      <section class="record-workbar screen-only">
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

          <div class="workbar-progress" aria-label="病历完成度">
            <div>
              <span>已填 {{ completionStats.completed }}/{{ completionStats.total }}</span>
              <strong>{{ completionPercent }}%</strong>
            </div>
            <i><em :style="{ width: `${completionPercent}%` }"></em></i>
          </div>

          <div class="archive-save-status">
            <Transition name="save-status-fade">
              <span v-if="autoSaveStatus === 'saving'" class="save-indicator saving">正在保存...</span>
              <span v-else-if="autoSaveStatus === 'saved'" class="save-indicator saved">已自动保存</span>
              <span v-else-if="autoSaveStatus === 'conflict'" class="save-indicator conflict">保存冲突，草稿已保留</span>
              <span v-else-if="autoSaveStatus === 'error'" class="save-indicator error" @click="saveActiveMode">
                保存失败，点击重试
              </span>
            </Transition>
          </div>
        </div>

        <div class="workbar-actions">
          <el-radio-group v-model="recordViewMode">
            <el-radio-button label="mine">我的字段</el-radio-button>
            <el-radio-button label="full">完整病历</el-radio-button>
          </el-radio-group>
          <el-button :icon="Upload" @click="router.push('/workbench/upload')">补充图片</el-button>
          <el-button :icon="FolderOpened" @click="router.push('/workbench/legacy')">导入旧共享病历</el-button>
          <el-button @click="router.push('/audit/review')">质控审核</el-button>
          <el-button :icon="Clock" @click="openAuditTimeline">操作轨迹</el-button>
          <el-button :icon="View" @click="previewVisible = true">预览</el-button>
          <el-button v-if="archiveSubmitted" @click="revokeArchive">撤回草稿</el-button>
          <el-button v-else type="primary" @click="submitArchive">提交质控</el-button>
          <el-button :icon="DocumentCopy" @click="copyRecord">复制</el-button>
          <el-button type="primary" :icon="Printer" @click="openPreviewThenPrint">打印/PDF</el-button>
        </div>
      </section>

      <section v-if="autoSaveStatus === 'conflict'" class="conflict-draft-banner screen-only">
        <div>
          <strong>检测到其他终端已更新</strong>
          <small>当前填写已保存在本机草稿{{ conflictDraftSavedAt ? `（${conflictDraftSavedAt}）` : "" }}，先不要刷新页面。</small>
        </div>
        <el-button plain @click="viewServerLatest">查看服务器最新</el-button>
        <el-button type="primary" @click="restoreConflictDraft">恢复本机草稿</el-button>
      </section>

      <section class="record-context-strip screen-only">
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
          <strong>{{ myEditableFields.length }} 项可处理</strong>
          <small>必填待补 {{ myRequiredMissingCount }} 项 · 完整度 {{ completionPercent }}%</small>
        </article>
        <article class="context-card attachments">
          <span>附件区</span>
          <strong>{{ currentAttachments.length }} 份证据</strong>
          <small>{{ validAttachmentCount }} 份可打开 · {{ invalidAttachmentCount }} 份待补源文件</small>
        </article>
      </section>

      <section v-if="workflowHint?.visible" class="workflow-hint screen-only" :class="`is-${workflowHint?.level || 'info'}`">
        <div>
          <span>下一步</span>
          <strong>{{ workflowHint?.title }}</strong>
          <small>{{ workflowHint?.desc }}</small>
        </div>
        <el-button v-if="firstWorkflowIssue" text @click="focusIssue(firstWorkflowIssue)">定位处理</el-button>
      </section>

      <div class="record-layout" :class="`mode-${recordViewMode}`">
        <aside v-if="recordViewMode === 'full'" class="section-rail screen-only">
          <button
            v-for="(section, index) in recordSectionsByRule"
            :key="section.key"
            class="rail-item"
            :class="{ active: activeSectionKey === section.key }"
            @click="activeSectionKey = section.key"
          >
            <span>{{ index + 1 }}</span>
            <strong>{{ shortTitle(section.title) }}</strong>
            <small>{{ section.department }}</small>
          </button>
        </aside>

        <main class="form-panel screen-only">
          <section v-if="recordViewMode === 'mine'" class="my-fields-panel">
            <div class="my-fields-head">
              <div>
                <h3>你需要填写 {{ myEditableFields.length }} 个字段</h3>
                <p>
                  {{ myRequiredMissingCount ? `还有 ${myRequiredMissingCount} 个必填项待补` : "本岗位内容已填齐，可检查后保存" }}
                </p>
              </div>
              <el-tag :type="myRequiredMissingCount ? 'warning' : 'success'" effect="plain">
                {{ myRequiredMissingCount ? "待处理" : "已就绪" }}
              </el-tag>
            </div>

            <div v-if="myFieldIssues.length" class="field-issue-banner">
              <div>
                <strong>先处理 {{ myFieldIssues.length }} 项</strong>
                <span>{{ myFieldIssues[0].fieldLabel }}：{{ myFieldIssues[0].message }}</span>
              </div>
              <el-button text @click="focusIssue(myFieldIssues[0])">定位</el-button>
            </div>

            <el-empty v-if="!myEditableFields.length" description="当前岗位暂无需要填写的字段">
              <el-button type="primary" @click="recordViewMode = 'full'">查看完整病历</el-button>
            </el-empty>

            <div v-else class="my-field-grid">
              <div
                v-for="item in myEditableFields"
                :key="item.field.key"
                class="my-field-item"
                :id="`my-field-${item.field.key}`"
                :class="{
                  wide: item.field.kind === 'textarea' || isLabMetricField(item.field),
                  complete: isFieldComplete(fieldValues[item.field.key] || ''),
                  missing: issueForField(item.field)?.level === 'missing',
                  invalid: issueForField(item.field)?.level === 'invalid'
                }"
              >
                <div class="my-field-label">
                  <label>
                    {{ item.field.label }}
                    <sup v-if="item.field.required">*</sup>
                  </label>
                  <span>{{ shortTitle(item.section.title) }}</span>
                </div>
                <LabMetricEditor v-if="isLabMetricField(item.field)" v-model="fieldValues[item.field.key]" :field="item.field" />
                <el-select
                  v-else-if="selectOptions(item.field).length"
                  v-model="fieldValues[item.field.key]"
                  allow-create
                  clearable
                  default-first-option
                  filterable
                  class="preset-select"
                  :placeholder="item.field.placeholder || '选择常用内容，特殊情况可直接输入'"
                >
                  <el-option v-for="option in selectOptions(item.field)" :key="option" :label="option" :value="option" />
                </el-select>
                <el-input
                  v-else-if="item.field.inputType === 'number'"
                  v-model="fieldValues[item.field.key]"
                  :max="item.field.max"
                  :min="item.field.min"
                  :placeholder="item.field.placeholder"
                  type="number"
                  controls-position="right"
                  class="meta-number-input"
                >
                  <template v-if="item.field.unit" #suffix>{{ item.field.unit }}</template>
                </el-input>
                <el-date-picker
                  v-else-if="item.field.inputType === 'date'"
                  v-model="fieldValues[item.field.key]"
                  type="date"
                  value-format="YYYY-MM-DD"
                  class="meta-date-input"
                  :placeholder="item.field.placeholder || '选择日期'"
                />
                <el-input
                  v-else
                  v-model="fieldValues[item.field.key]"
                  :placeholder="item.field.placeholder"
                  :maxlength="item.field.maxLength"
                  :show-word-limit="item.field.inputType === 'tel'"
                  :rows="item.field.kind === 'textarea' ? 3 : undefined"
                  :type="item.field.kind === 'textarea' ? 'textarea' : item.field.inputType === 'tel' ? 'tel' : 'text'"
                />
                <div v-if="matchedAttachments(item.field.key).length" class="evidence-line">
                  <button
                    v-for="attachment in matchedAttachments(item.field.key)"
                    :key="attachment.key"
                    @click="openAttachment(attachment.url)"
                  >
                    {{ attachment.title }}
                  </button>
                </div>
                <p v-if="issueForField(item.field)" class="field-inline-issue">
                  {{ issueForField(item.field)?.message }}
                </p>
              </div>
            </div>

            <div v-if="myEditableFields.length" class="my-fields-footer">
              <el-button :loading="saving" @click="saveMyFields">保存本岗位内容</el-button>
              <el-button type="primary" :loading="saving" @click="saveMyFieldsAndBack">保存并返回看板</el-button>
            </div>
          </section>

          <div v-if="recordViewMode === 'full'" class="workspace-anchor">
            <button
              v-for="(section, index) in recordSectionsByRule"
              :key="section.key"
              :class="{
                active: activeSectionKey === section.key,
                done: isSectionComplete(section),
                attention: sectionRequiredMissingCount(section) > 0
              }"
              @click="scrollToSection(section.key)"
            >
              <span>{{ index + 1 }}</span>
              {{ shortTitle(section.title) }}
            </button>
          </div>

          <div v-if="recordViewMode === 'full'" class="all-section-form">
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
                <span>{{ canEditRecordSection(section) ? "本岗位可处理" : "由其他岗位维护" }}</span>
              </div>
              <div v-if="sectionIssues(section).length" class="section-issue-line">
                <span>待处理：{{ sectionIssues(section)[0].fieldLabel }} - {{ sectionIssues(section)[0].message }}</span>
                <el-button text @click="focusIssue(sectionIssues(section)[0])">定位</el-button>
              </div>

              <div v-show="canExpandFullSection(section) && !isSectionCollapsed(section)" class="section-fields">
                <div
                  v-for="field in section.fields"
                  :key="field.key"
                  :id="`section-field-${field.key}`"
                  class="field-row workbench-field-row"
                  :class="{
                    locked: !isEditable(field),
                    complete: isFieldComplete(fieldValues[field.key] || ''),
                    missing: issueForField(field)?.level === 'missing',
                    invalid: issueForField(field)?.level === 'invalid'
                  }"
                >
                  <div class="field-label">
                    <label>{{ field.label }}</label>
                    <small>{{ fieldAssistText(field) }}</small>
                  </div>

                  <div class="field-input">
                    <div v-if="!isEditable(field)" class="locked-note">
                      <el-icon><Lock /></el-icon>
                      <span>由 {{ editorLabels(field.editors) }} 填写，当前账号仅可查看</span>
                    </div>
                    <LabMetricEditor
                      v-if="isLabMetricField(field)"
                      v-model="fieldValues[field.key]"
                      :field="field"
                      :disabled="!isEditable(field)"
                    />
                    <el-select
                      v-else-if="selectOptions(field).length"
                      v-model="fieldValues[field.key]"
                      allow-create
                      clearable
                      default-first-option
                      filterable
                      class="preset-select"
                      :disabled="!isEditable(field)"
                      :placeholder="field.placeholder || '选择常用内容，特殊情况可直接输入'"
                    >
                      <el-option v-for="option in selectOptions(field)" :key="option" :label="option" :value="option" />
                    </el-select>
                    <el-input
                      v-else-if="field.inputType === 'number'"
                      v-model="fieldValues[field.key]"
                      :disabled="!isEditable(field)"
                      :max="field.max"
                      :min="field.min"
                      :placeholder="field.placeholder"
                      type="number"
                      controls-position="right"
                      class="meta-number-input"
                    >
                      <template v-if="field.unit" #suffix>{{ field.unit }}</template>
                    </el-input>
                    <el-date-picker
                      v-else-if="field.inputType === 'date'"
                      v-model="fieldValues[field.key]"
                      type="date"
                      value-format="YYYY-MM-DD"
                      class="meta-date-input"
                      :disabled="!isEditable(field)"
                      :placeholder="field.placeholder || '选择日期'"
                    />
                    <el-input
                      v-else
                      v-model="fieldValues[field.key]"
                      :disabled="!isEditable(field)"
                      :placeholder="field.placeholder"
                      :maxlength="field.maxLength"
                      :show-word-limit="field.inputType === 'tel'"
                      :rows="field.kind === 'textarea' ? 2 : undefined"
                      :type="field.kind === 'textarea' ? 'textarea' : field.inputType === 'tel' ? 'tel' : 'text'"
                    />
                    <div v-if="matchedAttachments(field.key).length" class="evidence-line">
                      <button
                        v-for="attachment in matchedAttachments(field.key)"
                        :key="attachment.key"
                        @click="openAttachment(attachment.url)"
                      >
                        {{ attachment.title }}
                      </button>
                    </div>
                    <p v-if="issueForField(field)" class="field-inline-issue">
                      {{ issueForField(field)?.message }}
                    </p>
                  </div>
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
              v-for="field in activeSection.fields"
              :id="`active-field-${field.key}`"
              :key="field.key"
              class="field-row"
              :class="{
                locked: !isEditable(field),
                missing: issueForField(field)?.level === 'missing',
                invalid: issueForField(field)?.level === 'invalid'
              }"
            >
              <div class="field-label">
                <label>{{ field.label }}</label>
                <small>{{ fieldAssistText(field) }}</small>
              </div>

              <div class="field-input">
                <LabMetricEditor
                  v-if="isLabMetricField(field)"
                  v-model="fieldValues[field.key]"
                  :field="field"
                  :disabled="!isEditable(field)"
                />
                <el-select
                  v-else-if="selectOptions(field).length"
                  v-model="fieldValues[field.key]"
                  allow-create
                  clearable
                  default-first-option
                  filterable
                  class="preset-select"
                  :disabled="!isEditable(field)"
                  :placeholder="field.placeholder || '选择常用内容，特殊情况可直接输入'"
                >
                  <el-option v-for="option in selectOptions(field)" :key="option" :label="option" :value="option" />
                </el-select>
                <el-input
                  v-else-if="field.inputType === 'number'"
                  v-model="fieldValues[field.key]"
                  :disabled="!isEditable(field)"
                  :max="field.max"
                  :min="field.min"
                  :placeholder="field.placeholder"
                  type="number"
                  controls-position="right"
                  class="meta-number-input"
                >
                  <template v-if="field.unit" #suffix>{{ field.unit }}</template>
                </el-input>
                <el-date-picker
                  v-else-if="field.inputType === 'date'"
                  v-model="fieldValues[field.key]"
                  type="date"
                  value-format="YYYY-MM-DD"
                  class="meta-date-input"
                  :disabled="!isEditable(field)"
                  :placeholder="field.placeholder || '选择日期'"
                />
                <el-input
                  v-else
                  v-model="fieldValues[field.key]"
                  :disabled="!isEditable(field)"
                  :placeholder="field.placeholder"
                  :maxlength="field.maxLength"
                  :show-word-limit="field.inputType === 'tel'"
                  :rows="field.kind === 'textarea' ? 2 : undefined"
                  :type="field.kind === 'textarea' ? 'textarea' : field.inputType === 'tel' ? 'tel' : 'text'"
                />
                <div v-if="matchedAttachments(field.key).length" class="evidence-line">
                  <button
                    v-for="attachment in matchedAttachments(field.key)"
                    :key="attachment.key"
                    @click="openAttachment(attachment.url)"
                  >
                    {{ attachment.title }}
                  </button>
                </div>
                <p v-if="issueForField(field)" class="field-inline-issue">
                  {{ issueForField(field)?.message }}
                </p>
              </div>
            </div>
          </div>

          <div class="form-footer">
            <el-button type="success" :loading="saving" @click="saveCurrentSection">保存当前章节</el-button>
            <el-button :disabled="isFirstSection" @click="switchSection(-1)">上一段</el-button>
            <el-button :disabled="isLastSection" type="primary" @click="switchSection(1)">下一段</el-button>
          </div>
        </main>
      </div>

      <Teleport to="body">
        <Transition name="preview-overlay">
          <div v-if="previewVisible" class="preview-overlay" @click.self="previewVisible = false">
            <div class="preview-overlay-inner">
              <div class="preview-overlay-toolbar">
                <span>病历预览</span>
                <div>
                  <el-button type="primary" :icon="Printer" @click="printRecord">打印/导出 PDF</el-button>
                  <el-button @click="previewVisible = false">关闭</el-button>
                </div>
              </div>
              <div class="preview-overlay-scroll" id="record-print-area">
                <div class="paper-stack">
                  <article class="preview-paper record-page">
                    <div class="paper-watermark">{{ archiveWatermark }}</div>
                    <div class="paper-page-mark">
                      <span>病历正文</span>
                      <span>第 1 页 / 共 {{ currentAttachments.length + 2 }} 页</span>
                    </div>
                    <div class="medical-record-head">
                      <div class="medical-brand">
                        <img class="medical-logo" src="@/assets/images/logo.jpg" alt="医院标识" />
                        <div class="medical-title-block">
                          <h1>{{ fieldValues.hospitalName }}</h1>
                          <h2>{{ recordTitle }}</h2>
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

                    <div class="paper-archive-card">
                      <span v-for="item in archiveMeta" :key="item.label">
                        <b>{{ item.label }}：</b>{{ item.value }}
                      </span>
                    </div>

                    <section v-for="section in printableRecordSections" :key="section.key" class="paper-section">
                      <h3>{{ section.title }}</h3>
                      <div v-if="section.key === 'basic'" class="paper-grid">
                        <div v-for="field in section.fields" :key="field.key">
                          <strong>{{ field.printLabel || field.label }}</strong>
                          <span class="paper-value">{{ fieldValues[field.key] || "____" }}</span>
                        </div>
                      </div>
                      <div v-else class="paper-field-list">
                        <div v-for="field in section.fields" :key="field.key" class="paper-field-line">
                          <strong>
                            {{ field.printLabel || field.label }}
                            <small v-if="matchedAttachments(field.key).length">有附件</small>
                          </strong>
                          <span class="paper-value inline">{{ fieldValues[field.key] || "____" }}</span>
                        </div>
                      </div>
                    </section>

                    <div class="paper-sign">
                      <span>医师签名：________</span>
                      <span>质控签名：________</span>
                      <span>生成时间：{{ generatedAt }}</span>
                    </div>
                    <div class="paper-footer">
                      <span>{{ fieldValues.hospitalName }} · {{ archiveVersion }}</span>
                      <span>状态：{{ archiveStatusText }} · 录入内容以柔和底纹标识</span>
                    </div>
                  </article>

                  <article class="preview-paper attachment-index">
                    <div class="paper-watermark">{{ archiveWatermark }}</div>
                    <div class="paper-page-mark">
                      <span>附件索引</span>
                      <span>第 2 页 / 共 {{ currentAttachments.length + 2 }} 页</span>
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
                      <span>附件原图随病历归档保存 · 状态：{{ archiveStatusText }}</span>
                    </div>
                  </article>

                  <article
                    v-for="(attachment, index) in currentAttachments"
                    :key="attachment.key"
                    class="preview-paper attachment-page"
                  >
                    <div class="paper-watermark">{{ archiveWatermark }}</div>
                    <div class="paper-page-mark">
                      <span>检查检验附件</span>
                      <span>第 {{ index + 3 }} 页 / 共 {{ currentAttachments.length + 2 }} 页</span>
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
                        <span v-if="canOpenAttachment(attachment)">非图片附件已入档，可打开原文件查看。</span>
                        <span v-else>附件已建档，但原始文件地址无效，请重新上传或从旧共享目录补录。</span>
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
                      <span>关联字段：{{ attachment.fieldLabel }}</span>
                    </div>
                  </article>
                </div>
              </div>
            </div>
          </div>
        </Transition>
      </Teleport>

      <el-drawer v-model="auditTimelineVisible" title="患者操作轨迹" size="620px" destroy-on-close>
        <div class="audit-timeline-drawer">
          <section class="timeline-summary">
            <div>
              <strong>{{ fieldValues.patientName || "当前患者" }}</strong>
              <span>{{ fieldValues.visitNo || patientId }}</span>
            </div>
            <el-button :loading="auditLoading" @click="loadPatientAuditLogs">刷新</el-button>
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

      <el-dialog v-model="voidDialogVisible" title="作废附件" width="460px" destroy-on-close>
        <div class="void-document-dialog">
          <p>
            <span>附件：</span>
            <strong>{{ voidTarget?.fileName }}</strong>
          </p>
          <el-input
            v-model="voidReason"
            type="textarea"
            :rows="4"
            maxlength="120"
            show-word-limit
            placeholder="请填写作废原因，例如：传错患者、重复上传、报告版本错误"
          />
        </div>
        <template #footer>
          <el-button @click="voidDialogVisible = false">取消</el-button>
          <el-button type="danger" :loading="voiding" :disabled="!voidReason.trim()" @click="confirmVoidDocument">
            确认作废
          </el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts" name="patientDetail">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useDebounceFn } from "@vueuse/core";
import { ElMessage } from "element-plus";
import { useRoute, useRouter } from "vue-router";
import { ArrowDown, Clock, DocumentCopy, FolderOpened, Lock, Printer, Upload, View } from "@element-plus/icons-vue";
import TreeFilter from "@/components/TreeFilter/index.vue";
import {
  getAuditLogListApi,
  getPatientDetailApi,
  getTemplateFieldRulesApi,
  logPatientExportApi,
  revokeArchiveApi,
  savePatientRecordApi,
  submitArchiveApi,
  voidDocumentApi,
  type AuditLogRow,
  type PatientRow,
  type TemplateFieldRule
} from "@/api/modules/clinic";
import {
  editorLabels,
  recordAttachments as defaultRecordAttachments,
  recordSections,
  roleLabel,
  type RecordAttachment,
  type RecordField,
  type RecordSection,
  type UserRole
} from "@/config/fieldPermissions";
import { fetchClinicFileBlobUrl } from "@/api/modules/clinic/files";
import { useUserStore } from "@/stores/modules/user";
import medicalLogoUrl from "@/assets/images/logo.jpg";
import LabMetricEditor from "./components/LabMetricEditor.vue";

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();

const userRoles: UserRole[] = ["admin", "frontdesk", "lab", "ecg", "ultrasound", "doctor", "nurse", "quality"];
const normalizeUserRole = (role?: string): UserRole => (userRoles.includes(role as UserRole) ? (role as UserRole) : "frontdesk");
const currentRole = computed<UserRole>(() => normalizeUserRole(userStore.userInfo.role));
const roleName = computed(() => roleLabel(currentRole.value));
const patientId = computed(() => String(route.params.id || "1"));
const recordViewMode = ref<"mine" | "full">("mine");
const activeSectionKey = ref(recordSections[0].key);
const archiveSubmitted = ref(false);
const archiveVersion = ref("V0.3-预归档");
const generatedAt = ref("2026-06-11 09:30:00");
const saving = ref(false);
const savedSectionKey = ref("");
const previewVisible = ref(false);
const auditTimelineVisible = ref(false);
const auditLoading = ref(false);
const voidDialogVisible = ref(false);
const voiding = ref(false);
const voidTarget = ref<RecordAttachment>();
const voidReason = ref("");
const attachmentBlobUrls = ref<Record<string, string>>({});
type AutoSaveStatus = "idle" | "saving" | "saved" | "error" | "conflict";
const autoSaveStatus = ref<AutoSaveStatus>("idle");
const conflictDraftSavedAt = ref("");
const currentAttachments = ref<RecordAttachment[]>(defaultRecordAttachments);
const patientAuditLogs = ref<AuditLogRow[]>([]);
const templateRules = ref<TemplateFieldRule[]>([]);
const collapsedSectionKeys = ref<string[]>([]);
const patientInfo = ref<PatientRow>();
const logoCandidates = [medicalLogoUrl];
const logoIndex = ref(0);
const logoSrc = ref(logoCandidates[0]);
const logoVisible = ref(true);
type FieldIssue = {
  fieldKey: string;
  fieldLabel: string;
  sectionKey: string;
  sectionTitle: string;
  message: string;
  level: "missing" | "invalid";
};
type WorkflowHint = {
  visible: boolean;
  level: "info" | "warning" | "danger" | "success";
  title: string;
  desc: string;
};
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
const applyRuleToField = (field: RecordField): RecordField | null => {
  const rule = ruleMap.value[field.key];
  if (rule && !rule.enabled) return null;
  return {
    ...field,
    editors: rule?.editors || field.editors,
    required: rule?.required ?? field.required,
    evidence: rule?.evidence ?? field.evidence,
    printable: rule?.printable ?? true,
    qualityCheck: rule?.qualityCheck ?? field.qualityCheck,
    enabled: rule?.enabled ?? true
  };
};
const recordSectionsByRule = computed<RecordSection[]>(() =>
  recordSections
    .map(section => ({
      ...section,
      fields: section.fields.map(applyRuleToField).filter(Boolean) as RecordField[]
    }))
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
const activeIndex = computed(() => recordSectionsByRule.value.findIndex(section => section.key === activeSectionKey.value));
const activeSection = computed(
  () => recordSectionsByRule.value[activeIndex.value] || recordSectionsByRule.value[0] || recordSections[0]
);
const isFirstSection = computed(() => activeIndex.value === 0);
const isLastSection = computed(() => activeIndex.value === recordSectionsByRule.value.length - 1);
const conflictDraftKey = computed(() => `clinic-record-draft:${patientId.value}`);

const fieldValues = reactive(
  recordSections.reduce<Record<string, string>>((values, section) => {
    section.fields.forEach(field => {
      values[field.key] = field.key === "hospitalName" ? field.value : "";
    });
    return values;
  }, {})
);

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
    "按质控要求生成完整住院病历。"
  ],
  documentStandard: ["格式、结构、质控标准与院内标准病历一致。", "按医院现行病历书写规范执行。", "待病案室最终复核。"],
  qualityItems: [
    "中西医诊断一一对应；治法匹配；三级查房顺序规范；手术与诊断一致；分组合理、不高套；中医特色治疗齐全；合并病记录完整；肠镜按需选择、符合临床逻辑。",
    "诊断、手术、DIP、附件、时序待逐项复核。"
  ],
  qualityReview: ["待质控复核。", "资料完整，可提交归档。", "需补充检查附件后再归档。", "需医生补充诊断依据。"]
};

const normalizeAttachmentUrl = (url?: string) => String(url || "").trim();
const attachmentUrlBase = (url?: string) => normalizeAttachmentUrl(url).replace(/\/+$/, "");
const isInvalidAttachmentUrl = (url?: string) => {
  const normalized = attachmentUrlBase(url);
  return !normalized || normalized === "/clinic-api/files" || normalized.endsWith("/clinic-api/files");
};
const canOpenAttachment = (attachmentOrUrl?: RecordAttachment | string) => {
  const url = typeof attachmentOrUrl === "string" ? attachmentOrUrl : attachmentOrUrl?.url;
  return !isInvalidAttachmentUrl(url);
};
const imageAttachmentPattern = /\.(png|jpe?g|gif|webp|bmp|svg)(\?.*)?$/i;
const isImageAttachment = (attachment: RecordAttachment) => {
  const url = normalizeAttachmentUrl(attachment.url);
  return url.startsWith("data:image/") || imageAttachmentPattern.test(attachment.fileName) || imageAttachmentPattern.test(url);
};
const attachmentPreviewUrl = (url?: string) => attachmentBlobUrls.value[normalizeAttachmentUrl(url)] || "";
const loadAttachmentBlobUrl = async (url: string) => {
  const normalizedUrl = normalizeAttachmentUrl(url);
  if (!normalizedUrl || normalizedUrl.startsWith("data:")) return normalizedUrl;
  if (attachmentBlobUrls.value[normalizedUrl]) return attachmentBlobUrls.value[normalizedUrl];
  const blobUrl = await fetchClinicFileBlobUrl(normalizedUrl);
  attachmentBlobUrls.value = { ...attachmentBlobUrls.value, [normalizedUrl]: blobUrl };
  return blobUrl;
};
const preloadAttachmentPreviews = () => {
  currentAttachments.value
    .filter(isImageAttachment)
    .filter(canOpenAttachment)
    .forEach(attachment => {
      loadAttachmentBlobUrl(attachment.url).catch(() => {
        // Preview failure should not block record rendering; opening the file still reports a visible error.
      });
    });
};

const currentVisitType = computed(() => patientInfo.value?.visitType || fieldValues.admissionWay || "门诊");
const isInpatientRecord = computed(() => currentVisitType.value.includes("住院"));
const recordTitle = computed(() => (isInpatientRecord.value ? "住院病历" : "门诊病历"));
const recordSubtitle = computed(() => `${currentVisitType.value} · ${recordTitle.value}生成表`);
const visitNoLabel = computed(() => (isInpatientRecord.value ? "住院号" : "门诊号"));
const visitDateLabel = computed(() => (isInpatientRecord.value ? "入院日期" : "就诊日期"));
const encounterCountLabel = computed(() => `累计 ${patientInfo.value?.encounterCount || 1} 次就诊`);
const validAttachmentCount = computed(() => currentAttachments.value.filter(canOpenAttachment).length);
const invalidAttachmentCount = computed(() => currentAttachments.value.length - validAttachmentCount.value);

const paperMeta = computed(() => [
  { label: "患者", value: fieldValues.patientName },
  { label: visitNoLabel.value, value: fieldValues.visitNo },
  { label: visitDateLabel.value, value: fieldValues.admissionDate },
  { label: "就诊类型", value: currentVisitType.value },
  { label: "治疗类别", value: fieldValues.treatmentType }
]);

const allFields = computed(() => recordSectionsByRule.value.flatMap(section => section.fields));
const archiveStatusText = computed(() => (archiveSubmitted.value ? "待质控审核" : "草稿预览"));
const archiveWatermark = computed(() => (archiveSubmitted.value ? "待质控审核" : "草稿预览"));

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

const completionStats = computed(() => {
  const completed = allFields.value.filter(field => isFieldComplete(fieldValues[field.key] || "")).length;
  const requiredMissing = allFields.value.filter(
    field => field.required && !isFieldComplete(fieldValues[field.key] || "")
  ).length;
  return {
    completed,
    requiredMissing,
    total: allFields.value.length
  };
});
const completionPercent = computed(() =>
  completionStats.value.total ? Math.round((completionStats.value.completed / completionStats.value.total) * 100) : 0
);

const recordSignature = computed(() => {
  const seed = `${fieldValues.visitNo}-${completionStats.value.completed}-${currentAttachments.value.length}-${archiveVersion.value}`;
  return Array.from(seed)
    .reduce((sum, char) => sum + char.charCodeAt(0), 0)
    .toString(16)
    .toUpperCase();
});

const archiveMeta = computed(() => [
  { label: "文书状态", value: archiveStatusText.value },
  { label: "预归档版本", value: archiveVersion.value },
  { label: "生成时间", value: generatedAt.value },
  { label: "生成账号", value: roleName.value },
  { label: "完整度", value: `${completionStats.value.completed}/${completionStats.value.total}` },
  { label: "校验码", value: recordSignature.value }
]);

const auditModuleOptions: Record<string, string> = {
  patient: "患者",
  record: "病历",
  document: "附件",
  archive: "归档",
  template: "模板",
  system: "系统"
};
const auditModuleLabel = (module?: string) => auditModuleOptions[module || ""] || module || "旧日志";
const auditTimelineType = (log: AuditLogRow) =>
  log.result === "denied" ? "danger" : log.module === "archive" ? "warning" : "primary";
const shortTitle = (title: string) => title.replace(/^.*?、/, "");
const isEditable = (field: RecordField) =>
  field.enabled !== false && (currentRole.value === "admin" || field.editors.includes(currentRole.value));
const canEditRecordSection = (section: RecordSection) => section.fields.some(isEditable);
const myEditableFields = computed(() =>
  recordSectionsByRule.value.flatMap(section => section.fields.filter(isEditable).map(field => ({ section, field })))
);
const myRequiredMissingCount = computed(
  () => myEditableFields.value.filter(item => item.field.required && !isFieldComplete(fieldValues[item.field.key] || "")).length
);
const fieldIssues = computed<FieldIssue[]>(() =>
  recordSectionsByRule.value.flatMap(section =>
    section.fields.flatMap((field): FieldIssue[] => {
      const value = fieldValues[field.key] || "";
      if (field.required && !isFieldComplete(value)) {
        return [
          {
            fieldKey: field.key,
            fieldLabel: field.label,
            sectionKey: section.key,
            sectionTitle: section.title,
            message: "必填项待补",
            level: "missing" as const
          }
        ];
      }
      const invalidMessage = validateFieldValue(field, value);
      return invalidMessage
        ? [
            {
              fieldKey: field.key,
              fieldLabel: field.label,
              sectionKey: section.key,
              sectionTitle: section.title,
              message: invalidMessage,
              level: "invalid" as const
            }
          ]
        : [];
    })
  )
);
const issueForField = (field: RecordField) => fieldIssues.value.find(issue => issue.fieldKey === field.key);
const sectionIssues = (section: RecordSection) => fieldIssues.value.filter(issue => issue.sectionKey === section.key);
const myFieldIssues = computed(() => {
  const editableKeys = new Set(myEditableFields.value.map(item => item.field.key));
  return fieldIssues.value.filter(issue => editableKeys.has(issue.fieldKey));
});
const firstWorkflowIssue = computed(() => myFieldIssues.value[0] || fieldIssues.value[0]);
const workflowHint = computed<WorkflowHint>(() => {
  if (!recordSectionsByRule.value.length) return emptyWorkflowHint;
  if (myFieldIssues.value.length) {
    return {
      visible: true,
      level: myFieldIssues.value.some(issue => issue.level === "invalid") ? "danger" : "warning",
      title: `${roleName.value}还有 ${myFieldIssues.value.length} 项需要处理`,
      desc: `${myFieldIssues.value[0].sectionTitle}：${myFieldIssues.value[0].fieldLabel} - ${myFieldIssues.value[0].message}`
    };
  }
  if (fieldIssues.value.length) {
    return {
      visible: true,
      level: "info",
      title: "本岗位已处理，等待其他岗位补齐",
      desc: `${fieldIssues.value[0].sectionTitle}：${fieldIssues.value[0].fieldLabel} - ${fieldIssues.value[0].message}`
    };
  }
  return {
    visible: true,
    level: "success",
    title: "病历字段已齐，可提交质控",
    desc: "保存后可预览、打印或提交质控审核。"
  };
});
const isLabMetricField = (field: RecordField) => Boolean(field.labPanel);
const selectOptions = (field: RecordField) => {
  if (isLabMetricField(field)) return [];
  if (field.kind === "select") return field.options || [];
  if (["date", "number", "tel"].includes(field.inputType || "")) return [];
  return field.options?.length ? field.options : fieldPresets[field.key] || [];
};
const fieldAssistText = (field: RecordField) => {
  if (!isEditable(field)) return `锁定：${editorLabels(field.editors)}`;
  if (isLabMetricField(field)) return "指标面板自动生成文本";
  if (field.inputType === "date") return "YYYY-MM-DD";
  if (field.inputType === "number") {
    const range = [field.min, field.max].filter(value => value !== undefined).join("-");
    return range ? `${range}${field.unit || ""}` : field.unit || "数字";
  }
  if (field.inputType === "tel") return "11位手机号";
  if (field.pattern) return field.validationMessage || "按固定格式填写";
  const count = selectOptions(field).length;
  return count ? `选择为主：${count} 个常用项` : "少量手填";
};
const matchedAttachments = (fieldKey: string) => currentAttachments.value.filter(attachment => attachment.fieldKey === fieldKey);
const sectionCompletedCount = (section: RecordSection) =>
  section.fields.filter(field => isFieldComplete(fieldValues[field.key] || "")).length;
const sectionRequiredMissingCount = (section: RecordSection) =>
  section.fields.filter(field => field.required && !isFieldComplete(fieldValues[field.key] || "")).length;
const sectionEvidenceCount = (section: RecordSection) =>
  section.fields.reduce((count, field) => count + matchedAttachments(field.key).length, 0);
const isSectionComplete = (section: RecordSection) =>
  section.fields.length > 0 && sectionCompletedCount(section) === section.fields.length;
const canExpandFullSection = (section: RecordSection) =>
  currentRole.value === "admin" || currentRole.value === "quality" || canEditRecordSection(section);
const isSectionCollapsed = (section: RecordSection) => collapsedSectionKeys.value.includes(section.key);
const shouldCollapseSection = (section: RecordSection) =>
  !canExpandFullSection(section) || (sectionRequiredMissingCount(section) === 0 && isSectionComplete(section));
const hydrateCollapsedSections = () => {
  collapsedSectionKeys.value = recordSectionsByRule.value.filter(shouldCollapseSection).map(section => section.key);
};
const toggleSectionCollapse = (sectionKey: string) => {
  collapsedSectionKeys.value = collapsedSectionKeys.value.includes(sectionKey)
    ? collapsedSectionKeys.value.filter(key => key !== sectionKey)
    : [...collapsedSectionKeys.value, sectionKey];
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
  activeSectionKey.value = issue.sectionKey;
  if (recordViewMode.value === "full" && collapsedSectionKeys.value.includes(issue.sectionKey)) {
    collapsedSectionKeys.value = collapsedSectionKeys.value.filter(key => key !== issue.sectionKey);
  }
  await nextTick();
  const targetIds =
    recordViewMode.value === "mine"
      ? [`my-field-${issue.fieldKey}`, `section-field-${issue.fieldKey}`, `active-field-${issue.fieldKey}`]
      : [`section-field-${issue.fieldKey}`, `active-field-${issue.fieldKey}`, `my-field-${issue.fieldKey}`];
  const target = targetIds.map(id => document.getElementById(id)).find(Boolean);
  target?.scrollIntoView({ behavior: "smooth", block: "center" });
  const input = target?.querySelector<HTMLElement>("input, textarea, .el-select__wrapper");
  input?.focus();
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
    if (section && shouldCollapseSection(section) && !collapsedSectionKeys.value.includes(sectionKey)) {
      collapsedSectionKeys.value = [...collapsedSectionKeys.value, sectionKey];
    }
    window.setTimeout(() => {
      if (savedSectionKey.value === sectionKey) savedSectionKey.value = "";
    }, 1200);
  }
};

const attachmentLine = (attachment: RecordAttachment, index: number) => {
  return `${index + 1}. ${attachment.fileName}，关联${attachment.fieldLabel}，${attachment.department}上传于${attachment.uploadedAt}`;
};

const buildRecordText = () => {
  const sections = printableRecordSections.value
    .map(section => {
      const lines = section.fields.map(field => `${field.printLabel || field.label}：${fieldValues[field.key] || "____"}`);
      return `${section.title}\n${lines.join("\n")}`;
    })
    .join("\n\n");
  const attachments = currentAttachments.value.map(attachmentLine).join("\n");
  return `${fieldValues.hospitalName}\n${recordTitle.value}自动生成表\n状态：${archiveStatusText.value}  版本：${archiveVersion.value}  生成时间：${generatedAt.value}\n患者：${fieldValues.patientName}  ${visitNoLabel.value}：${fieldValues.visitNo}  校验码：${recordSignature.value}\n\n${sections}\n\n附件索引\n${attachments}`;
};

const copyRecord = async () => {
  await navigator.clipboard.writeText(buildRecordText());
  ElMessage.success("病历文本已复制");
};

const loadPatientAuditLogs = async () => {
  auditLoading.value = true;
  try {
    const { data } = await getAuditLogListApi({ pageNum: 1, pageSize: 80, patientId: patientId.value });
    const legacyLogs = fieldValues.patientName
      ? await getAuditLogListApi({ pageNum: 1, pageSize: 80, patient: fieldValues.patientName })
      : null;
    const knownIds = new Set(data.list.map(log => log.id));
    const legacyOnly = (legacyLogs?.data.list || []).filter(log => !log.patientId && !knownIds.has(log.id));
    patientAuditLogs.value = [...data.list, ...legacyOnly].sort((left, right) => right.time.localeCompare(left.time));
  } finally {
    auditLoading.value = false;
  }
};

const openAuditTimeline = async () => {
  auditTimelineVisible.value = true;
  await loadPatientAuditLogs();
};

const loadPatientDetail = async () => {
  try {
    const [{ data }, { data: rules }] = await Promise.all([getPatientDetailApi(patientId.value), getTemplateFieldRulesApi()]);
    templateRules.value = rules;
    if (!recordSectionsByRule.value.some(section => section.key === activeSectionKey.value)) {
      activeSectionKey.value = recordSectionsByRule.value[0]?.key || recordSections[0].key;
    }
    patientInfo.value = data.patient;
    Object.assign(fieldValues, data.fieldValues);
    currentAttachments.value = data.attachments;
    preloadAttachmentPreviews();
    archiveSubmitted.value = data.archiveSubmitted;
    archiveVersion.value = data.archiveVersion;
    generatedAt.value = data.generatedAt;
    hydrateCollapsedSections();
    await loadPatientAuditLogs();
    const targetSection = String(route.query.section || "");
    if (targetSection) window.setTimeout(() => scrollToSection(targetSection), 120);
  } catch (error) {
    ElMessage.error((error as Error).message);
    router.push("/patients/list");
  }
};

const isConflictError = (error: unknown) => {
  const message = String((error as Error)?.message || "");
  return (
    message.includes("409") || message.includes("冲突") || message.includes("已被其他终端更新") || message.includes("revision")
  );
};

const persistLocalDraft = (values: Record<string, string>) => {
  const savedAt = new Date().toLocaleString();
  localStorage.setItem(
    conflictDraftKey.value,
    JSON.stringify({
      patientId: patientId.value,
      savedAt,
      mode: recordViewMode.value,
      sectionKey: activeSectionKey.value,
      values
    })
  );
  conflictDraftSavedAt.value = savedAt;
};

const clearLocalDraft = () => {
  localStorage.removeItem(conflictDraftKey.value);
  conflictDraftSavedAt.value = "";
};

const restoreConflictDraft = () => {
  const raw = localStorage.getItem(conflictDraftKey.value);
  if (!raw) {
    ElMessage.warning("未找到本机草稿");
    return;
  }
  try {
    const draft = JSON.parse(raw) as {
      values?: Record<string, string>;
      mode?: "mine" | "full";
      sectionKey?: string;
      savedAt?: string;
    };
    if (draft.mode) recordViewMode.value = draft.mode;
    if (draft.sectionKey && recordSectionsByRule.value.some(section => section.key === draft.sectionKey)) {
      activeSectionKey.value = draft.sectionKey;
    }
    Object.assign(fieldValues, draft.values || {});
    conflictDraftSavedAt.value = draft.savedAt || conflictDraftSavedAt.value;
    autoSaveStatus.value = "error";
    ElMessage.info("已恢复本机草稿，请检查后重新保存");
  } catch {
    ElMessage.error("本机草稿读取失败");
  }
};

const viewServerLatest = async () => {
  const values = recordViewMode.value === "mine" ? myFieldValues() : currentSectionValues();
  persistLocalDraft(values);
  await loadPatientDetail();
  autoSaveStatus.value = "conflict";
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
    await loadPatientAuditLogs();
    clearLocalDraft();
    autoSaveStatus.value = "saved";
    const issueCount = data.issues.length;
    ElMessage.success(issueCount ? `已保存，仍有 ${issueCount} 个必填字段待补` : successText);
    return true;
  } catch (error) {
    if (isConflictError(error)) {
      persistLocalDraft(values);
      autoSaveStatus.value = "conflict";
      ElMessage.warning("检测到其他终端已更新，当前填写已保存到本机草稿");
      return false;
    }
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
  myEditableFields.value.reduce<Record<string, string>>((payload, item) => {
    payload[item.field.key] = fieldValues[item.field.key];
    return payload;
  }, {});

const saveCurrentSection = async () => {
  if (!ensureNoInvalidIssues("section", "保存章节")) return false;
  return saveRecordValues(currentSectionValues(), "当前章节已保存，字段校验通过");
};

const saveMyFields = async () => {
  if (!ensureNoInvalidIssues("mine", "保存本岗位内容")) return false;
  return saveRecordValues(myFieldValues(), "本岗位内容已保存");
};

const saveMyFieldsAndBack = async () => {
  if (!ensureNoBlockingIssues("mine", "返回看板")) return;
  const saved = await saveMyFields();
  if (saved) router.push("/encounters/active");
};

const saveActiveMode = () => (recordViewMode.value === "mine" ? saveMyFields() : saveCurrentSection());

const submitArchive = async () => {
  if (!ensureNoBlockingIssues("all", "提交质控")) return;
  const saved = await saveActiveMode();
  if (!saved) return;
  try {
    const { data } = await submitArchiveApi({ id: patientId.value, role: currentRole.value, operator: roleName.value });
    archiveSubmitted.value = data.archive.submitted;
    archiveVersion.value = data.archive.version;
    generatedAt.value = data.archive.generatedAt;
    await loadPatientAuditLogs();
    ElMessage.success("已提交质控，流程状态已更新");
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
    await loadPatientAuditLogs();
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
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    voiding.value = false;
  }
};

const openAttachment = async (url: string) => {
  const normalizedUrl = normalizeAttachmentUrl(url);
  if (isInvalidAttachmentUrl(normalizedUrl)) {
    ElMessage.warning("附件已建档，但原始文件地址无效，请重新上传或从旧共享目录补录");
    return;
  }
  try {
    const blobUrl = await loadAttachmentBlobUrl(normalizedUrl);
    window.open(blobUrl, "_blank", "noopener,noreferrer");
  } catch (error) {
    ElMessage.error((error as Error).message);
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
    await loadPatientAuditLogs();
  } catch (error) {
    ElMessage.warning(`打印留痕失败，已继续打印：${(error as Error).message}`);
  }
};

const printRecord = async () => {
  await logPrintAction();
  await nextTick();
  window.setTimeout(() => window.print(), 120);
};

const openPreviewThenPrint = async () => {
  previewVisible.value = true;
  await logPrintAction();
  await nextTick();
  window.setTimeout(() => window.print(), 300);
};

const debouncedAutoSave = useDebounceFn(async () => {
  if (saving.value || archiveSubmitted.value) return;
  autoSaveStatus.value = "saving";
  const values = recordViewMode.value === "mine" ? myFieldValues() : currentSectionValues();
  try {
    await savePatientRecordApi({ id: patientId.value, role: currentRole.value, operator: roleName.value, values });
    clearLocalDraft();
    autoSaveStatus.value = "saved";
    window.setTimeout(() => {
      if (autoSaveStatus.value === "saved") autoSaveStatus.value = "idle";
    }, 3000);
  } catch (error) {
    if (isConflictError(error)) {
      persistLocalDraft(values);
      autoSaveStatus.value = "conflict";
      return;
    }
    autoSaveStatus.value = "error";
  }
}, 2000);

watch(fieldValues, () => {
  const hasEditableField =
    recordViewMode.value === "mine" ? myEditableFields.value.length > 0 : activeSection.value.fields.some(isEditable);
  if (hasEditableField) debouncedAutoSave();
});

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

onMounted(() => {
  loadPatientDetail();
  document.addEventListener("keydown", handleKeydown);
});

onBeforeUnmount(() => {
  document.removeEventListener("keydown", handleKeydown);
  Object.values(attachmentBlobUrls.value).forEach(url => URL.revokeObjectURL(url));
});
</script>

<style scoped lang="scss">
.record-workspace {
  --record-accent: #166534;
  --record-accent-soft: #ecfdf5;
  --record-fixed: #475569;
  --record-fixed-soft: #f8fafc;
  --record-warning: #b45309;
  --record-warning-soft: #fff7ed;
  --record-danger: #dc2626;
  display: block;
  color: #1f2937;
}

.record-workspace > .table-box {
  width: 100%;
  min-width: 0;
  overflow: auto;
}

.record-workspace > .screen-only {
  display: none;
}

.record-workbar {
  display: grid;
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 10px;
  background: linear-gradient(90deg, rgb(236 253 245 / 78%), rgb(255 255 255 / 98%) 52%), #ffffff;
  border: 1px solid rgb(22 101 52 / 14%);
  border-radius: 12px;
  box-shadow: 0 10px 24px rgb(15 23 42 / 5%);
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
    color: #111827;
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
  color: #64748b;
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
      background: #cbd5e1;
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
    color: #64748b;
    font-size: 12px;
  }

  strong {
    color: #166534;
    font-variant-numeric: tabular-nums;
  }

  i {
    display: block;
    height: 7px;
    overflow: hidden;
    background: #edf2f7;
    border-radius: 999px;
  }

  em {
    display: block;
    height: 100%;
    background: linear-gradient(90deg, #22c55e, #0ea5e9);
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

.my-fields-panel {
  display: grid;
  gap: 14px;
  max-width: 1080px;
  padding: 18px;
  margin: 0 auto;
  background: #ffffff;
  border: 1px solid #e8edf3;
  border-radius: 12px;
  box-shadow: 0 8px 22px rgb(15 23 42 / 5%);
}

.my-fields-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  padding-bottom: 12px;
  border-bottom: 1px solid #edf2f7;

  h3,
  p {
    margin: 0;
  }

  h3 {
    color: #111827;
    font-size: 18px;
    font-weight: 650;
  }

  p {
    margin-top: 5px;
    color: #64748b;
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
  background: #ffffff;
  border: 1px solid #edf2f7;
  border-radius: 8px;
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease,
    background 160ms ease;

  &:focus-within {
    background: #f8fffd;
    border-color: rgb(22 101 52 / 28%);
    box-shadow: 0 8px 18px rgb(15 23 42 / 6%);
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
    background: linear-gradient(180deg, #ffffff, #fbfffd);
    border-color: rgb(34 197 94 / 24%);

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
    background: #fbfdff;
    border-radius: 8px;
    box-shadow: 0 0 0 1px #dbe3ec inset;
  }

  :deep(.el-input__wrapper:hover),
  :deep(.el-select__wrapper:hover),
  :deep(.el-textarea__inner:hover) {
    box-shadow: 0 0 0 1px #a9c7f8 inset;
  }
}

.my-field-label {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;

  label {
    color: #334155;
    font-weight: 650;
  }

  sup {
    margin-left: 3px;
    color: var(--el-color-danger);
  }

  span {
    color: #94a3b8;
    font-size: 12px;
  }
}

.my-fields-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid #edf2f7;
}

.record-toolbar,
.patient-strip,
.archive-strip,
.section-rail,
.form-panel,
.preview-panel {
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
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
  border-right: 1px solid #edf0f3;

  &:last-child {
    border-right: 0;
  }

  span,
  strong {
    display: block;
  }

  span {
    margin-bottom: 3px;
    color: #6b7280;
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
  color: #6b7280;
  font-size: 13px;

  strong {
    color: #111827;
  }
}

.archive-metrics {
  justify-content: flex-end;

  span {
    padding-left: 10px;
    border-left: 1px solid #e5e7eb;

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
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  box-shadow: 0 1px 2px rgb(15 23 42 / 4%);

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
    color: #64748b;
    font-size: 12px;
    font-weight: 700;
  }

  strong {
    margin-top: 4px;
    overflow: hidden;
    color: #111827;
    font-size: 17px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    margin-top: 4px;
    overflow: hidden;
    color: #64748b;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &.fixed {
    background: linear-gradient(135deg, var(--record-fixed-soft), #ffffff);
    border-color: #dbe3ea;

    &::before {
      background: var(--record-fixed);
    }
  }

  &.editable {
    background: linear-gradient(135deg, var(--record-accent-soft), #ffffff);
    border-color: #bbf7d0;

    &::before {
      background: var(--record-accent);
    }

    span,
    strong {
      color: #047857;
    }
  }

  &.attachments {
    background: linear-gradient(135deg, var(--record-warning-soft), #ffffff);
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

.record-layout {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
  align-items: start;
}

.section-rail {
  display: none;
}

.rail-item {
  display: grid;
  grid-template-columns: 24px 1fr;
  gap: 2px 8px;
  width: 100%;
  padding: 9px 8px;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 8px;

  span {
    grid-row: span 2;
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    color: #64748b;
    background: #f1f5f9;
    border-radius: 999px;
    font-size: 12px;
  }

  strong {
    color: #111827;
    font-size: 14px;
  }

  small {
    color: #6b7280;
  }

  &.active {
    background: #ecfdf5;

    span {
      color: #ffffff;
      background: var(--record-accent);
    }
  }
}

.form-panel {
  padding: 16px;
}

.workspace-anchor {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding-bottom: 12px;
  margin-bottom: 16px;
  border-bottom: 1px solid #edf0f3;

  button {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 4px 10px;
    color: #6b7280;
    cursor: pointer;
    background: transparent;
    border: 1px solid transparent;
    border-radius: 4px;
    font-size: 12px;
    transition:
      background 150ms,
      border-color 150ms;

    span {
      display: inline-grid;
      place-items: center;
      width: 18px;
      height: 18px;
      background: #f1f5f9;
      border-radius: 999px;
      font-size: 10px;
    }

    &.active {
      color: #111827;
      background: #ecfdf5;
      border-color: #bbf7d0;

      span {
        color: #ffffff;
        background: var(--record-accent);
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
  border: 1px solid #e5e7eb;
  border-left: 3px solid transparent;
  border-radius: 8px;
  box-shadow: 0 1px 2px rgb(15 23 42 / 4%);
  transition:
    border-color 200ms,
    box-shadow 200ms;

  &.editable {
    border-color: #cfe8dd;
    border-left-color: #55b58a;
    background: linear-gradient(180deg, #ffffff, #fbfffd);
    box-shadow: 0 8px 18px rgb(22 101 52 / 5%);
  }

  &.readonly {
    background: #f8fafc;
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
    color: #6b7280;
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
  border-bottom: 1px solid #f0f2f5;
  transition: background 160ms ease;

  &:last-child {
    border-bottom: 0;
  }

  &:focus-within {
    background: #f8fffd;
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
    background: #f8fafc;
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
  border-bottom: 1px solid #edf0f3;

  h3,
  p {
    margin: 0;
  }

  h3 {
    font-size: 18px;
  }

  p {
    margin-top: 5px;
    color: #6b7280;
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
  border-bottom: 1px solid #f0f2f5;
  transition: background 160ms ease;

  &:focus-within {
    background: #f8fffd;
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
      color: #9ca3af;
    }
  }
}

.field-label {
  label,
  small {
    display: block;
  }

  label {
    color: #111827;
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
    background: #ffffff;
    box-shadow: 0 0 0 1px #d7dde5 inset;
  }

  :deep(.is-disabled .el-input__wrapper),
  :deep(.el-textarea.is-disabled .el-textarea__inner) {
    background: #f8fafc;
    box-shadow: 0 0 0 1px #edf0f3 inset;
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
    background: #fbfdfc;
    box-shadow: 0 0 0 1px #cbd5e1 inset;
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

.form-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 14px;
}

.preview-panel {
  padding: 12px;
  background: linear-gradient(180deg, #f8fafc 0%, #eef2f7 100%);
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
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(2px);
}

.preview-overlay-inner {
  display: flex;
  flex-direction: column;
  width: min(96vw, 1080px);
  height: 92vh;
  background: #ffffff;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
  overflow: hidden;
}

.preview-overlay-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
  padding: 12px 20px;
  border-bottom: 1px solid #e5e7eb;

  span {
    font-size: 16px;
    font-weight: 600;
    color: #111827;
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
  background: linear-gradient(180deg, #f8fafc 0%, #eef2f7 100%);
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
  background: linear-gradient(180deg, rgb(255 255 255 / 99%), rgb(249 253 251 / 99%)),
    radial-gradient(circle at 100% 0, rgb(39 174 96 / 8%), transparent 32%), #ffffff;
  border: 1px solid #dcebe5;
  border-radius: 16px;
  box-shadow: 0 22px 60px rgb(28 91 73 / 13%);
  font-family: Arial, "Microsoft YaHei", sans-serif;
  color: #1b2f2a;
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
  color: rgb(15 23 42 / 5%);
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
  color: #64748b;
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
    border: 1px solid #e2e8f0;
  }

  th {
    background: #f8fafc;
  }
}

.attachment-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e2e8f0;

  h2,
  p {
    margin: 0;
  }

  h2 {
    font-size: 18px;
  }

  p {
    margin-top: 8px;
    color: #4b5563;
    font-size: 13px;
  }

  span {
    flex-shrink: 0;
    padding: 4px 10px;
    border: 1px solid #cbd5e1;
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
    border: 1px solid #d1d5db;
  }
}

.attachment-file-placeholder {
  display: grid;
  place-items: center;
  gap: 8px;
  width: 100%;
  min-height: 320px;
  padding: 28px;
  color: #64748b;
  text-align: center;
  background: #f8fafc;
  border: 1px dashed #cbd5e1;

  strong,
  span {
    display: block;
  }

  strong {
    margin-bottom: 8px;
    color: #111827;
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
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;

  button {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 6px;
    height: 32px;
    padding: 0 10px;
    color: #4b5563;
    cursor: pointer;
    background: #ffffff;
    border: 1px solid #dcdfe6;
    border-radius: 6px;
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
      background: var(--el-color-primary);
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
      color: #64748b;
      background: #f1f5f9;
      border-radius: 50%;
      font-size: 12px;
    }

    &.active {
      color: var(--el-color-primary);
      background: var(--el-color-primary-light-9);
      border-color: var(--el-color-primary-light-5);

      &::before {
        opacity: 1;
        transform: scaleX(1);
      }

      span {
        color: #ffffff;
        background: var(--el-color-primary);
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
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    background-color 0.2s ease;

  &.saved {
    background: #f0fdf4;
    border-color: #86efac;
    box-shadow: 0 0 0 3px rgb(34 197 94 / 12%);
  }

  &.editable {
    border-left: 3px solid var(--el-color-primary-light-5);
  }

  &.readonly {
    border-left: 3px solid #cbd5e1;
  }

  &.attention {
    border-color: var(--el-color-warning-light-5);
    box-shadow: 0 0 0 3px rgb(245 158 11 / 8%);
  }

  &.collapsed {
    background: linear-gradient(180deg, #ffffff 0%, #fbfdff 100%);
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
  border-bottom: 1px solid #edf0f3;

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
    color: #6b7280;
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
  color: #64748b;
  font-size: 12px;

  span {
    min-height: 24px;
    padding: 3px 8px;
    background: #f8fafc;
    border: 1px solid #e5e7eb;
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
}

.section-index {
  display: inline-grid;
  place-items: center;
  width: 24px;
  height: 24px;
  color: #ffffff;
  background: var(--el-color-primary);
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
    background: #f8fafc;
    border-bottom-color: #e5e7eb;
    border-radius: 6px;

    .field-label label {
      color: #64748b;
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
  color: #64748b;
  background: #ffffff;
  border: 1px dashed #cbd5e1;
  border-radius: 4px;
  font-size: 12px;
}

.section-card-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
  border-top: 1px solid #edf0f3;
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
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;

  strong,
  span {
    display: block;
  }

  strong {
    color: var(--el-text-color-primary);
    font-size: 17px;
  }

  span {
    margin-top: 4px;
    color: var(--el-text-color-regular);
  }
}

.patient-audit-timeline {
  padding: 4px 4px 4px 0;
}

.timeline-event {
  padding: 12px;
  background: #ffffff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;

  p {
    margin: 8px 0;
    color: var(--el-text-color-primary);
    line-height: 1.6;
  }
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

  .patient-strip,
  .archive-strip,
  .record-context-strip,
  .record-layout,
  .field-row,
  .paper-field-line,
  .paper-meta,
  .paper-grid,
  .paper-archive-card {
    grid-template-columns: 1fr;
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
