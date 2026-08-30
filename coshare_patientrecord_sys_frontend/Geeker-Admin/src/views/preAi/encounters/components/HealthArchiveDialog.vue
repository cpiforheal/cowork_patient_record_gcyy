<template>
  <el-dialog
    :model-value="modelValue"
    width="calc(100vw - 48px)"
    top="4vh"
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
    class="health-archive-dialog"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <template #header>
      <div class="ha-header">
        <div>
          <strong>健康管理档案</strong>
          <small v-if="draftMeta">{{ archiveMetaText }}</small>
          <small v-else>加载中…</small>
        </div>
        <div class="ha-header-actions">
          <el-button :loading="saving" :disabled="!editable" @click="saveDraft">保存草稿</el-button>
          <el-button v-if="canComplete" type="primary" :loading="completing" @click="completeArchive">
            完成并生成合并文档
          </el-button>
        </div>
      </div>
    </template>

    <div v-loading="loading" class="ha-body" element-loading-text="健康管理档案加载中…">
      <div
        class="ha-input-pane"
        :style="{ width: inputPaneWidth + 'px' }"
        :class="{ 'pane-transition': !resizing }"
      >
        <el-alert type="info" :closable="false" show-icon>
          带出信息已按当前就诊同步，可下拉修正；保存后生效于右侧预览与合并文档。
        </el-alert>

        <section v-show="!loading" class="ha-section">
          <h4>一、基本信息（诊前建档）</h4>
          <div class="ha-grid-4">
            <div><label>姓名</label><el-input v-model="basic.name" :disabled="!editable" /></div>
            <div><label>性别</label>
              <el-select v-model="basic.gender" :disabled="!editable" filterable allow-create>
                <el-option v-for="item in GENDERS" :key="item" :label="item" :value="item" />
              </el-select>
            </div>
            <div><label>年龄</label><el-input v-model="basic.age" :disabled="!editable" /></div>
            <div><label>联系电话</label><el-input v-model="basic.phone" :disabled="!editable" /></div>
            <div class="span2"><label>家庭住址</label><el-input v-model="basic.address" :disabled="!editable" /></div>
            <div><label>医保类型</label>
              <el-select v-model="basic.insurance" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                <el-option v-for="item in INSURANCE_TYPES" :key="item" :label="item" :value="item" />
              </el-select>
            </div>
            <div class="span2"><label>西医诊断（带出·可修正）</label><el-input v-model="basic.westernDx" :disabled="!editable" /></div>
            <div class="span2"><label>中医诊断（带出·可修正）</label><el-input v-model="basic.tcmDx" :disabled="!editable" /></div>
          </div>
          <label class="ha-line-label">客源渠道</label>
          <el-select v-model="form.sourceChannel" :disabled="!editable" placeholder="选择主渠道" clearable>
            <el-option v-for="item in SOURCE_CHANNELS" :key="item" :label="item" :value="item" />
          </el-select>
          <div v-if="form.sourceChannel === '其他'" class="ha-other"><span>其他说明</span><el-input v-model="form.sourceChannelsOther" :disabled="!editable" /></div>
          <label class="ha-line-label">就诊动因</label>
          <el-select v-model="form.visitMotivation" :disabled="!editable" placeholder="选择主要动因" clearable>
            <el-option v-for="item in VISIT_MOTIVATIONS" :key="item" :label="item" :value="item" />
          </el-select>
          <div v-if="form.visitMotivation === '其他'" class="ha-other"><span>其他说明</span><el-input v-model="form.visitMotivationsOther" :disabled="!editable" /></div>
          <label class="ha-line-label">既往病史/过敏史</label>
          <el-input v-model="form.pastHistory" type="textarea" :rows="2" :disabled="!editable" />
        </section>

        <section v-show="!loading" class="ha-section">
          <h4>二、诊中辨证（专科检查与诊疗方案）</h4>
          <label class="ha-line-label">专科检查（阴性填"未见异常"）</label>
          <div class="ha-grid-4">
            <div><label>肛门视诊</label>
              <el-select v-model="form.specialExam.anusVisual" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                <el-option v-for="item in EXAM_FINDINGS" :key="item" :label="item" :value="item" />
              </el-select>
            </div>
            <div><label>直肠指诊</label>
              <el-select v-model="form.specialExam.digitalRectal" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                <el-option v-for="item in EXAM_FINDINGS" :key="item" :label="item" :value="item" />
              </el-select>
            </div>
            <div><label>肛门镜</label>
              <el-select v-model="form.specialExam.anoscope" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                <el-option v-for="item in EXAM_FINDINGS" :key="item" :label="item" :value="item" />
              </el-select>
            </div>
            <div><label>阳性体征</label><el-input v-model="form.specialExam.positiveSigns" :disabled="!editable" /></div>
          </div>
          <label class="ha-line-label">中医体质/证型</label>
          <el-select v-model="form.tcmConstitution" :disabled="!editable" multiple collapse-tags placeholder="可多选">
            <el-option v-for="item in TCM_CONSTITUTIONS" :key="item" :label="item" :value="item" />
          </el-select>
          <div class="ha-other"><span>其他证型</span><el-input v-model="form.tcmConstitutionOther" :disabled="!editable" /></div>
          <label class="ha-line-label">人群分类</label>
          <el-select v-model="form.crowdCategory" :disabled="!editable" placeholder="选择人群分类">
            <el-option label="A类（手术）" value="A" />
            <el-option label="B类（慢病高危）" value="B" />
            <el-option label="C类（亚健康调理）" value="C" />
          </el-select>
          <div class="ha-grid-4">
            <div><label>诊疗路径</label>
              <el-select v-model="form.treatmentPath" :disabled="!editable" placeholder="选择诊疗路径" clearable>
                <el-option v-for="item in TREATMENT_PATHS" :key="item" :label="item" :value="item" />
              </el-select>
            </div>
            <div><label>手术日期（微创时填写）</label><el-date-picker v-model="form.surgeryDate" type="date" value-format="YYYY-MM-DD" :disabled="!editable" style="width: 100%" /></div>
          </div>
          <label class="ha-line-label">个性化干预方案</label>
          <el-select v-model="form.interventions" :disabled="!editable" multiple collapse-tags placeholder="可多选">
            <el-option v-for="item in INTERVENTIONS" :key="item" :label="item" :value="item" />
          </el-select>
        </section>

        <section v-show="!loading" class="ha-section">
          <h4>三、院内康复</h4>
          <el-table :data="form.recoveryRows" size="small" border>
            <el-table-column prop="timeNode" label="时间节点" width="88" />
            <el-table-column label="创面/渗血" min-width="120">
              <template #default="{ row }">
                <el-select v-model="row.wound" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                  <el-option v-for="item in WOUND_OPTIONS" :key="item" :label="item" :value="item" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="疼痛评分" width="104">
              <template #default="{ row }">
                <el-select v-model="row.pain" :disabled="!editable" filterable allow-create placeholder="NRS">
                  <el-option v-for="n in 11" :key="n" :label="String(n - 1)" :value="String(n - 1)" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="排便情况" min-width="112">
              <template #default="{ row }">
                <el-select v-model="row.bowel" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                  <el-option v-for="item in BOWEL_OPTIONS" :key="item" :label="item" :value="item" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="水肿消退" min-width="112">
              <template #default="{ row }">
                <el-select v-model="row.edema" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                  <el-option v-for="item in EDEMA_OPTIONS" :key="item" :label="item" :value="item" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="用药/坐浴" min-width="120">
              <template #default="{ row }">
                <el-select v-model="row.medication" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                  <el-option v-for="item in MEDICATION_OPTIONS" :key="item" :label="item" :value="item" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="提肛训练" min-width="110">
              <template #default="{ row }">
                <el-select v-model="row.training" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                  <el-option v-for="item in TRAINING_OPTIONS" :key="item" :label="item" :value="item" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="110">
              <template #default="{ row }"><el-input v-model="row.remark" :disabled="!editable" /></template>
            </el-table-column>
          </el-table>
        </section>

        <section v-show="!loading" class="ha-section">
          <h4>四、心理疏导</h4>
          <label class="ha-line-label">主要情绪问题</label>
          <el-select v-model="form.emotionIssues" :disabled="!editable" multiple collapse-tags placeholder="可多选">
            <el-option v-for="item in EMOTION_ISSUES" :key="item" :label="item" :value="item" />
          </el-select>
          <div class="ha-other"><span>其他</span><el-input v-model="form.emotionOther" :disabled="!editable" /></div>
          <label class="ha-line-label">干预节点</label>
          <el-select v-model="form.psychInterventions" :disabled="!editable" multiple collapse-tags placeholder="可多选">
            <el-option v-for="item in PSYCH_NODES" :key="item" :label="item" :value="item" />
          </el-select>
          <label class="ha-line-label">疏导记录</label>
          <el-input v-model="form.counselingRecord" type="textarea" :rows="2" :disabled="!editable" />
        </section>

        <section v-show="!loading" class="ha-section">
          <h4>五、标准化宣教</h4>
          <label class="ha-line-label">宣教执行</label>
          <el-select v-model="form.educationItems" :disabled="!editable" multiple collapse-tags placeholder="可多选">
            <el-option v-for="item in EDUCATION_ITEMS" :key="item" :label="item" :value="item" />
          </el-select>
          <label class="ha-line-label">患者是否理解</label>
          <el-radio-group v-model="form.patientUnderstood" :disabled="!editable">
            <el-radio value="是">是</el-radio>
            <el-radio value="否">否</el-radio>
          </el-radio-group>
        </section>

        <section v-show="!loading" class="ha-section">
          <h4>六、分级随访</h4>
          <el-table :data="form.followUpRows" size="small" border>
            <el-table-column prop="timeNode" label="随访时间" width="88" />
            <el-table-column label="随访方式" width="120">
              <template #default="{ row }">
                <el-select v-model="row.method" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                  <el-option v-for="item in FOLLOW_METHODS" :key="item" :label="item" :value="item" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="创面/恢复" min-width="120">
              <template #default="{ row }">
                <el-select v-model="row.recovery" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                  <el-option v-for="item in WOUND_OPTIONS" :key="item" :label="item" :value="item" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="用药依从" min-width="112">
              <template #default="{ row }">
                <el-select v-model="row.adherence" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                  <el-option v-for="item in ADHERENCE_OPTIONS" :key="item" :label="item" :value="item" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="饮食忌口" min-width="112">
              <template #default="{ row }">
                <el-select v-model="row.diet" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                  <el-option v-for="item in DIET_OPTIONS" :key="item" :label="item" :value="item" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="按期复查" width="112">
              <template #default="{ row }">
                <el-select v-model="row.review" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                  <el-option v-for="item in REVIEW_OPTIONS" :key="item" :label="item" :value="item" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="患者反馈" min-width="110">
              <template #default="{ row }"><el-input v-model="row.feedback" :disabled="!editable" /></template>
            </el-table-column>
            <el-table-column label="随访人" width="110">
              <template #default="{ row }">
                <el-select v-model="row.visitor" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                  <el-option v-for="item in VISITOR_OPTIONS" :key="item" :label="item" :value="item" />
                </el-select>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <section v-show="!loading" class="ha-section">
          <h4>七、方案调整记录与签字</h4>
          <label class="ha-line-label">方案调整记录</label>
          <el-input v-model="form.adjustmentRecord" type="textarea" :rows="2" :disabled="!editable" />
          <div class="ha-grid-3">
            <div><label>建档人</label>
              <el-select v-model="form.signFiledBy" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                <el-option v-for="item in VISITOR_OPTIONS" :key="item" :label="item" :value="item" />
              </el-select>
            </div>
            <div><label>主诊医师</label>
              <el-select v-model="form.signAttending" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                <el-option v-for="item in VISITOR_OPTIONS" :key="item" :label="item" :value="item" />
              </el-select>
            </div>
            <div><label>质控审核</label>
              <el-select v-model="form.signQc" :disabled="!editable" filterable allow-create placeholder="选择或输入">
                <el-option v-for="item in VISITOR_OPTIONS" :key="item" :label="item" :value="item" />
              </el-select>
            </div>
          </div>
        </section>
      </div>

      <div
        class="ha-resizer"
        :class="{ 'pane-transition': !resizing }"
        title="拖动调整左右栏宽度"
        @mousedown="startResize"
      ></div>

      <div class="ha-preview-pane" :class="{ 'pane-transition': !resizing }">
        <section class="ha-doc-paper">
          <div class="ha-doc-toolbar">
            <span class="ha-doc-badge">合并文档实时预览</span>
            <small>AI 病历正文原样保留 · 档案内容随左侧填写实时更新</small>
          </div>

          <div class="ha-doc-part">
            <div class="ha-doc-part-head">第一部分 · AI 住院病历（原样保留，不可编辑）</div>
            <el-select
              v-model="selectedRecordId"
              class="ha-version-select"
              placeholder="选择用于合并的 AI 病历版本"
              :disabled="!aiVersions.length"
              @change="onSelectRecord"
            >
              <el-option
                v-for="item in aiVersions"
                :key="item.id"
                :value="item.id"
                :label="`V${item.version} · ${item.generatedAt || item.fileName} · ${item.status === 'finalized' ? '已定稿' : '草稿'}`"
              />
            </el-select>
            <div v-if="selectedVersion" class="ha-doc-ai-meta">
              <p><strong>{{ selectedVersion.fileName || `AI住院病历-V${selectedVersion.version}.docx` }}</strong></p>
              <small>生成：{{ selectedVersion.generatedAt }} · {{ selectedVersion.operatorRole || "医生" }} · 引擎 {{ aiModelLabel(selectedVersion.model) }}</small>
              <small>正文按周xx范本排版整体保留；逐字内容以合并 DOCX 为准。</small>
            </div>
            <el-empty v-else description="该就诊暂无 AI 生成病历，无法合并" :image-size="56" />
          </div>

          <div class="ha-doc-part">
            <div class="ha-doc-part-head">第二部分 · 健康管理档案登记表</div>
            <el-skeleton v-if="loading" :rows="8" animated />
            <transition name="ha-fade" mode="out-in">
              <div v-show="!loading" :key="previewStamp" class="ha-doc-sheet">
                <div class="ha-doc-form-title">固始中医肛肠医院健康管理档案登记表</div>
                <div class="ha-doc-line">档案编号：<b>{{ draftArchiveNo || "自动生成" }}</b>　建档日期：<b>{{ todayText }}</b></div>

                <div class="ha-doc-sec">一、基本信息（诊前建档）</div>
                <div class="ha-doc-line">姓名：<b>{{ basic.name || "—" }}</b>　性别：<b>{{ basic.gender || "—" }}</b>　年龄：<b>{{ basic.age || "—" }}</b>　联系电话：<b>{{ basic.phone || "—" }}</b></div>
                <div class="ha-doc-line">家庭住址：<b>{{ basic.address || "—" }}</b>　医保类型：<b>{{ basic.insurance || "—" }}</b></div>
                <div class="ha-doc-line">客源渠道：{{ markSingle(form.sourceChannel, SOURCE_CHANNELS) }}{{ form.sourceChannelsOther ? "　其他：" + form.sourceChannelsOther : "" }}</div>
                <div class="ha-doc-line">就诊动因：{{ markSingle(form.visitMotivation, VISIT_MOTIVATIONS) }}{{ form.visitMotivationsOther ? "　其他：" + form.visitMotivationsOther : "" }}</div>
                <div class="ha-doc-line">既往病史/过敏史：<b>{{ form.pastHistory || "—" }}</b></div>

                <div class="ha-doc-sec">二、诊中辨证（专科检查与诊疗方案）</div>
                <div class="ha-doc-line">专科检查：肛门视诊 <b>{{ form.specialExam.anusVisual || "—" }}</b>；直肠指诊 <b>{{ form.specialExam.digitalRectal || "—" }}</b>；肛门镜 <b>{{ form.specialExam.anoscope || "—" }}</b>；阳性体征 <b>{{ form.specialExam.positiveSigns || "—" }}</b></div>
                <div class="ha-doc-line">中医体质/证型：{{ markMulti(form.tcmConstitution, TCM_CONSTITUTIONS, form.tcmConstitutionOther) }}</div>
                <div class="ha-doc-line">人群分类：{{ markSingle(form.crowdCategory, [], CROWD_LABELS) }}</div>
                <div class="ha-doc-line">西医诊断：<b>{{ basic.westernDx || "—" }}</b>　中医诊断：<b>{{ basic.tcmDx || "—" }}</b></div>
                <div class="ha-doc-line">诊疗路径：{{ markSingle(form.treatmentPath, TREATMENT_PATHS) }}</div>
                <div class="ha-doc-line">手术日期：<b>{{ form.surgeryDate || "待医生补充" }}</b></div>
                <div class="ha-doc-line">个性化干预方案：{{ markMulti(form.interventions, INTERVENTIONS, "") }}</div>

                <div class="ha-doc-sec">三、院内康复</div>
                <table class="ha-doc-table">
                  <thead><tr><th v-for="h in RECOVERY_HEADERS" :key="h">{{ h }}</th></tr></thead>
                  <tbody>
                    <tr v-for="row in form.recoveryRows" :key="row.timeNode">
                      <td v-for="(cell, key) in row" :key="key">{{ cell || "" }}</td>
                    </tr>
                  </tbody>
                </table>

                <div class="ha-doc-sec">四、心理疏导</div>
                <div class="ha-doc-line">主要情绪问题：{{ markMulti(form.emotionIssues, EMOTION_ISSUES, form.emotionOther) }}</div>
                <div class="ha-doc-line">干预节点：{{ markMulti(form.psychInterventions, PSYCH_NODES, "") }}</div>
                <div class="ha-doc-line">疏导记录：<b>{{ form.counselingRecord || "—" }}</b></div>

                <div class="ha-doc-sec">五、标准化宣教</div>
                <div class="ha-doc-line">宣教执行：{{ markMulti(form.educationItems, EDUCATION_ITEMS, "") }}</div>
                <div class="ha-doc-line">患者是否理解：<b>{{ form.patientUnderstood ? "☑" + form.patientUnderstood : "□是 □否" }}</b></div>

                <div class="ha-doc-sec">六、分级随访</div>
                <table class="ha-doc-table">
                  <thead><tr><th v-for="h in FOLLOW_UP_HEADERS" :key="h">{{ h }}</th></tr></thead>
                  <tbody>
                    <tr v-for="row in form.followUpRows" :key="row.timeNode">
                      <td v-for="(cell, key) in row" :key="key">{{ cell || "" }}</td>
                    </tr>
                  </tbody>
                </table>

                <div class="ha-doc-sec">七、方案调整记录与签字</div>
                <div class="ha-doc-line">方案调整记录：<b>{{ form.adjustmentRecord || "—" }}</b></div>
                <div class="ha-doc-line">建档人：<b>{{ form.signFiledBy || "—" }}</b>　主诊医师：<b>{{ form.signAttending || "—" }}</b>　质控审核：<b>{{ form.signQc || "—" }}</b></div>
              </div>
            </transition>
          </div>
        </section>

        <section class="ha-preview-card">
          <h4>已生成合并文档</h4>
          <div v-if="!documents.length" class="ha-empty">尚未生成合并文档</div>
          <div v-for="item in documents" :key="item.id" class="ha-doc-row">
            <span class="ha-doc-no">V{{ item.version }}</span>
            <div class="ha-doc-detail">
              <strong>{{ item.fileName }}</strong>
              <small>{{ item.createdAt }} · {{ item.createdByRole || "医生" }}</small>
            </div>
            <el-button size="small" type="primary" plain @click="downloadDocument(item)">下载</el-button>
          </div>
        </section>
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts" name="HealthArchiveDialog">
import { computed, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useUserStore } from "@/stores/modules/user";
import {
  completeHealthArchiveApi,
  downloadHealthArchiveDocumentApi,
  loadHealthArchiveApi,
  saveHealthArchiveDraftApi,
  type HealthArchiveAuto,
  type HealthArchiveDocumentItem,
  type HealthArchiveForm,
  type HealthArchiveLoadResult,
  type HealthArchiveVersionItem
} from "@/api/modules/clinic/healthArchive";

// ---------- 下拉选项（均支持输入自定义值） ----------
const GENDERS = ["男", "女"];
const INSURANCE_TYPES = ["城镇职工医保", "城乡居民医保", "自费", "商业保险", "其他"];
const SOURCE_CHANNELS = ["自主到院", "亲友转介绍", "外院转诊", "线上短视频", "社区筛查", "术后复诊", "其他"];
const VISIT_MOTIVATIONS = ["急症", "肠道筛查(40岁以上)", "术后复查", "调理", "其他"];
const TCM_CONSTITUTIONS = ["湿热下注", "脾虚气陷", "气滞血瘀", "血虚", "阳虚", "阴虚"];
const CROWD_LABELS: Record<string, string> = { A: "A类（手术）", B: "B类（慢病高危）", C: "C类（亚健康调理）" };
const TREATMENT_PATHS = ["微创手术治疗", "中医保守治疗(中药内服/坐浴熏洗/艾灸/穴位理疗)"];
const INTERVENTIONS = ["排便管理", "饮食管理", "生活运动(提肛训练)", "中医特色(坐浴/艾灸/穴位/食疗)"];
const EMOTION_ISSUES = ["隐私羞怯", "手术恐惧", "疼痛焦虑", "认知误区", "康复担忧"];
const PSYCH_NODES = ["初诊", "术前", "换药", "出院", "回访"];
const EDUCATION_ITEMS = ["门诊接诊话术", "术前告知话术", "出院宣教话术", "回访二次宣教(居家养护/忌口/复查)"];
const EXAM_FINDINGS = ["未见异常", "齿线上黏膜隆起", "痔核脱出", "肛缘皮赘", "裂口", "截石位7点痔核", "混合痔", "内痔Ⅱ度", "内痔Ⅲ度"];
const WOUND_OPTIONS = ["干燥愈合", "少量渗血", "渗血较多", "无异常", "创面新鲜"];
const BOWEL_OPTIONS = ["通畅", "排便费劲", "腹泻", "未排便", "需辅助通便"];
const EDEMA_OPTIONS = ["完全消退", "轻度水肿", "中度水肿", "较前加重"];
const MEDICATION_OPTIONS = ["中药坐浴", "太宁栓", "马应龙麝香痔疮栓", "地奥司明片", "继续当前用药"];
const TRAINING_OPTIONS = ["已规律执行", "部分执行", "未执行"];
const FOLLOW_METHODS = ["电话", "微信", "门诊复诊", "上门访视"];
const ADHERENCE_OPTIONS = ["规律用药", "间断用药", "未用药"];
const DIET_OPTIONS = ["忌辛辣", "忌辛辣及饮酒", "正常饮食", "流质饮食"];
const REVIEW_OPTIONS = ["按期复查", "已改期", "未复查"];
const RECOVERY_NODES = ["术后当日", "术后3天", "术后7天", "术后15天", "术后30天"];
const FOLLOW_UP_NODES = ["术后1天", "术后3天", "术后7天", "术后15天", "术后30天", "出院3月", "出院6月"];
const RECOVERY_HEADERS = ["时间节点", "创面/渗血", "疼痛评分", "排便情况", "水肿消退", "用药/坐浴", "提肛训练", "备注"];
const FOLLOW_UP_HEADERS = ["随访时间", "随访方式", "创面/恢复", "用药依从", "饮食忌口", "按期复查", "患者反馈", "随访人"];
const STORAGE_WIDTH_KEY = "ha-input-pane-width";

const props = defineProps<{ modelValue: boolean; encounterId: string; encounterPatientName?: string }>();
const emit = defineEmits<{ (event: "update:modelValue", value: boolean): void; (event: "completed"): void }>();

const userStore = useUserStore();
const currentRole = computed(() => userStore.userInfo.role || "");
const editable = computed(() => ["doctor", "nurse", "admin"].includes(currentRole.value));
const canComplete = computed(() => ["doctor", "admin"].includes(currentRole.value));
const currentUserName = computed(() => (userStore.userInfo.name as string) || (userStore.userInfo.username as string) || "");
const VISITOR_OPTIONS = computed(() => Array.from(new Set([currentUserName.value].filter(Boolean))) as string[]);

const loading = ref(false);
const saving = ref(false);
const completing = ref(false);
const resizing = ref(false);
const auto = ref<HealthArchiveAuto>({
  name: "", gender: "", age: "", phone: "", address: "", insurance: "", westernDx: "", tcmDx: ""
});
const basic = reactive<HealthArchiveAuto>({
  name: "", gender: "", age: "", phone: "", address: "", insurance: "", westernDx: "", tcmDx: ""
});
const aiVersions = ref<HealthArchiveVersionItem[]>([]);
const documents = ref<HealthArchiveDocumentItem[]>([]);
const selectedRecordId = ref("");
const draftStatus = ref("");
const draftRevision = ref(0);
const draftArchiveNo = ref("");
const draftUpdatedAt = ref("");
const inputPaneWidth = ref(Number(localStorage.getItem(STORAGE_WIDTH_KEY)) || 620);

const form = reactive<HealthArchiveForm>({
  basic,
  sourceChannel: "",
  sourceChannelsOther: "",
  visitMotivation: "",
  visitMotivationsOther: "",
  pastHistory: "",
  specialExam: { anusVisual: "", digitalRectal: "", anoscope: "", positiveSigns: "" },
  tcmConstitution: [],
  tcmConstitutionOther: "",
  crowdCategory: "",
  treatmentPath: "",
  surgeryDate: "",
  interventions: [],
  recoveryRows: [],
  emotionIssues: [],
  emotionOther: "",
  psychInterventions: [],
  counselingRecord: "",
  educationItems: [],
  patientUnderstood: "",
  followUpRows: [],
  adjustmentRecord: "",
  signFiledBy: "",
  signAttending: "",
  signQc: ""
});

const selectedVersion = computed(() => aiVersions.value.find(item => item.id === selectedRecordId.value) || null);
const draftMeta = computed(() => Boolean(draftStatus.value));
const archiveMetaText = computed(() => {
  const parts = [`档案编号 ${draftArchiveNo.value || "自动生成"}`];
  if (draftRevision.value) parts.push(`已保存 v${draftRevision.value}`);
  if (draftUpdatedAt.value) parts.push(draftUpdatedAt.value);
  if (draftStatus.value === "COMPLETED") parts.push("已完成合并");
  return parts.join(" · ");
});
const todayText = computed(() => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
});
const aiModelLabel = (model: string) => {
  const value = (model || "").toLowerCase();
  if (value === "dify-workflow") return "Dify 工作流";
  if (value === "docx-template") return "模板直出";
  return model || "AI 生成";
};

// 预览防抖：250ms 合并提交一次更新，避免逐键重渲染造成掉帧
const previewStamp = ref(0);
let previewTimer = 0;
watch(
  () => [
    basic.name, basic.gender, basic.age, basic.phone, basic.address, basic.insurance, basic.westernDx, basic.tcmDx,
    form.sourceChannel, form.sourceChannelsOther, form.visitMotivation, form.visitMotivationsOther, form.pastHistory,
    form.specialExam.anusVisual, form.specialExam.digitalRectal, form.specialExam.anoscope, form.specialExam.positiveSigns,
    form.tcmConstitution, form.tcmConstitutionOther, form.crowdCategory, form.treatmentPath, form.surgeryDate,
    form.interventions, form.recoveryRows, form.emotionIssues, form.emotionOther, form.psychInterventions,
    form.counselingRecord, form.educationItems, form.patientUnderstood, form.followUpRows, form.adjustmentRecord,
    form.signFiledBy, form.signAttending, form.signQc
  ],
  () => {
    if (previewTimer) window.clearTimeout(previewTimer);
    previewTimer = window.setTimeout(() => {
      previewStamp.value += 1;
    }, 250);
  },
  { deep: true }
);

// 复选态渲染：与后端 DOCX 渲染保持同一套 ☑/□ 视觉
const markSingle = (selected: string, options: string[], labels?: Record<string, string>) => {
  const list = options.length ? options : Object.keys(labels || {});
  return list
    .map(option => {
      const label = labels ? labels[option] : option;
      return (option === selected ? "☑" : "□") + label;
    })
    .join("　");
};
const markMulti = (selected: string[], options: string[], other: string) => {
  const parts = options.map(option => (selected.includes(option) ? "☑" + option : "□" + option));
  parts.push((other ? "☑" : "□") + "其他" + (other ? "：" + other : ""));
  return parts.join("　");
};

const emptyRecoveryRow = () => ({
  timeNode: "", wound: "", pain: "", bowel: "", edema: "", medication: "", training: "", remark: ""
});
const emptyFollowUpRow = () => ({
  timeNode: "", method: "", recovery: "", adherence: "", diet: "", review: "", feedback: "", visitor: ""
});

const applyDraft = (payload: HealthArchiveLoadResult) => {
  auto.value = { ...auto.value, ...payload.auto };
  aiVersions.value = payload.aiVersions || [];
  documents.value = payload.documents || [];
  const draft = payload.draft;
  draftStatus.value = draft.status || "DRAFT";
  draftRevision.value = draft.revision || 0;
  draftArchiveNo.value = draft.archiveNo || "";
  draftUpdatedAt.value = draft.updatedAt || "";
  selectedRecordId.value = draft.sourceRecordId || aiVersions.value[0]?.id || "";
  const draftForm = (draft.form || {}) as Partial<HealthArchiveForm> & {
    sourceChannels?: string[];
    visitMotivations?: string[];
  };
  Object.assign(basic, { ...auto.value, ...(draftForm.basic || {}) });
  form.sourceChannel = draftForm.sourceChannel || draftForm.sourceChannels?.[0] || "";
  form.sourceChannelsOther = draftForm.sourceChannelsOther || "";
  form.visitMotivation = draftForm.visitMotivation || draftForm.visitMotivations?.[0] || "";
  form.visitMotivationsOther = draftForm.visitMotivationsOther || "";
  form.pastHistory = draftForm.pastHistory || "";
  form.specialExam = { anusVisual: "", digitalRectal: "", anoscope: "", positiveSigns: "", ...(draftForm.specialExam || {}) };
  form.tcmConstitution = draftForm.tcmConstitution || [];
  form.tcmConstitutionOther = draftForm.tcmConstitutionOther || "";
  form.crowdCategory = draftForm.crowdCategory || "";
  form.treatmentPath = draftForm.treatmentPath || "";
  form.surgeryDate = draftForm.surgeryDate || "";
  form.interventions = draftForm.interventions || [];
  form.recoveryRows = RECOVERY_NODES.map((node, index) => ({
    ...emptyRecoveryRow(),
    timeNode: node,
    ...(draftForm.recoveryRows?.[index] || {})
  })) as HealthArchiveForm["recoveryRows"];
  form.followUpRows = FOLLOW_UP_NODES.map((node, index) => ({
    ...emptyFollowUpRow(),
    timeNode: node,
    ...(draftForm.followUpRows?.[index] || {})
  })) as HealthArchiveForm["followUpRows"];
  form.emotionIssues = draftForm.emotionIssues || [];
  form.emotionOther = draftForm.emotionOther || "";
  form.psychInterventions = draftForm.psychInterventions || [];
  form.counselingRecord = draftForm.counselingRecord || "";
  form.educationItems = draftForm.educationItems || [];
  form.patientUnderstood = draftForm.patientUnderstood || "";
  form.adjustmentRecord = draftForm.adjustmentRecord || "";
  form.signFiledBy = draftForm.signFiledBy || currentUserName.value;
  form.signAttending = draftForm.signAttending || "";
  form.signQc = draftForm.signQc || "";
  previewStamp.value += 1;
};

const load = async () => {
  if (!props.encounterId) return;
  loading.value = true;
  try {
    const { data } = await loadHealthArchiveApi(props.encounterId);
    applyDraft(data);
  } catch (error: any) {
    ElMessage.error(error?.message || "健康管理档案加载失败");
  } finally {
    loading.value = false;
  }
};

const saveDraft = async () => {
  if (!editable.value) {
    ElMessage.warning("当前账号无健康管理档案编辑权限");
    return;
  }
  saving.value = true;
  try {
    const { data } = await saveHealthArchiveDraftApi({
      encounterId: props.encounterId,
      sourceRecordId: selectedRecordId.value,
      form: { ...form, basic: { ...basic } }
    });
    draftRevision.value = data.draft.revision;
    draftStatus.value = data.draft.status;
    draftArchiveNo.value = data.draft.archiveNo;
    draftUpdatedAt.value = data.draft.updatedAt;
    ElMessage.success(`草稿已保存（v${data.draft.revision}）`);
  } catch (error: any) {
    ElMessage.error(error?.message || "健康管理档案保存失败");
  } finally {
    saving.value = false;
  }
};

const completeArchive = async () => {
  if (!selectedRecordId.value) {
    ElMessage.warning("该就诊暂无 AI 生成病历，无法合并");
    return;
  }
  try {
    await ElMessageBox.confirm("将按当前所选 AI 病历版本与档案内容生成合并文档（AI 病历正文原样保留、档案附后），确认完成？", "完成健康管理档案", {
      type: "warning",
      confirmButtonText: "确认完成",
      cancelButtonText: "再检查一下"
    });
  } catch {
    return;
  }
  completing.value = true;
  try {
    const { data } = await completeHealthArchiveApi({
      encounterId: props.encounterId,
      sourceRecordId: selectedRecordId.value,
      form: { ...form, basic: { ...basic } }
    });
    ElMessage.success(`合并文档 V${data.document.version} 已生成并固定到系统`);
    const loadResult = await loadHealthArchiveApi(props.encounterId);
    applyDraft(loadResult.data);
    emit("completed");
    const download = await downloadHealthArchiveDocumentApi(data.document.id);
    const url = URL.createObjectURL(download.blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = download.filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
  } catch (error: any) {
    if (error !== "cancel" && error !== "close") ElMessage.error(error?.message || "健康管理档案合并失败");
  } finally {
    completing.value = false;
  }
};

const downloadDocument = async (item: HealthArchiveDocumentItem) => {
  try {
    const download = await downloadHealthArchiveDocumentApi(item.id);
    const url = URL.createObjectURL(download.blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = download.filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
  } catch (error: any) {
    ElMessage.error(error?.message || "合并文档下载失败");
  }
};

const onSelectRecord = () => {
  /* 版本切换仅影响下次合并，历史文档不受影响 */
};

const startResize = (event: MouseEvent) => {
  resizing.value = true;
  const startX = event.clientX;
  const startWidth = inputPaneWidth.value;
  const onMove = (moveEvent: MouseEvent) => {
    inputPaneWidth.value = Math.min(1080, Math.max(480, startWidth + (moveEvent.clientX - startX)));
  };
  const onUp = () => {
    localStorage.setItem(STORAGE_WIDTH_KEY, String(inputPaneWidth.value));
    resizing.value = false;
    window.removeEventListener("mousemove", onMove);
    window.removeEventListener("mouseup", onUp);
  };
  window.addEventListener("mousemove", onMove);
  window.addEventListener("mouseup", onUp);
};

watch(
  () => props.modelValue,
  visible => {
    if (visible) void load();
  }
);
</script>

<style scoped lang="scss">
.ha-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;

  strong {
    font-size: 16px;
  }

  small {
    display: block;
    color: var(--el-text-color-secondary);
  }
}
.ha-header-actions {
  display: flex;
  gap: 8px;
}
.ha-body {
  position: relative;
  display: flex;
  gap: 10px;
  min-height: 420px;
}
.ha-input-pane {
  flex-shrink: 0;
  overflow-y: auto;
  max-height: calc(84vh - 140px);
  padding-right: 8px;
}
.ha-resizer {
  flex-shrink: 0;
  width: 6px;
  cursor: col-resize;
  border-radius: 3px;
  background: var(--el-fill-color);

  &:hover {
    background: var(--el-color-primary-light-5);
  }
}
.pane-transition {
  transition: width 0.18s linear;
}
.ha-preview-pane {
  flex: 1;
  min-width: 340px;
  display: grid;
  gap: 12px;
  align-content: start;
  overflow-y: auto;
  max-height: calc(84vh - 140px);
  contain: content;
}
.ha-section {
  margin-bottom: 18px;

  h4 {
    margin: 0 0 8px;
    padding: 6px 10px;
    border-left: 3px solid var(--el-color-warning);
    background: var(--el-color-warning-light-9);
    border-radius: 4px;
  }
}
.ha-grid-4 {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;

  .span2 {
    grid-column: span 2;
  }
}
.ha-grid-3 {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.ha-grid-4 label,
.ha-grid-3 label,
.ha-line-label {
  display: block;
  margin: 6px 0 4px;
  color: var(--el-text-color-regular);
  font-size: 12px;
}
.ha-grid-4,
.ha-grid-3 {
  margin-bottom: 6px;
}
.ha-other {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 4px 0 6px;

  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
    white-space: nowrap;
  }
}
.ha-preview-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 12px 14px;

  h4 {
    margin: 0 0 10px;
    font-size: 14px;
  }
}
.ha-empty {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.ha-doc-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);

  &:last-child {
    border-bottom: none;
  }
}
.ha-doc-no {
  font-weight: 600;
}
.ha-doc-detail {
  flex: 1;
  min-width: 0;

  strong {
    display: block;
    font-size: 13px;
  }

  small {
    color: var(--el-text-color-secondary);
  }
}

// ---------- 文档纸面预览 ----------
.ha-doc-paper {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: #fff;
  padding: 16px 18px;
  box-shadow: 0 1px 4px rgb(0 0 0 / 4%);
}
.ha-doc-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;

  small {
    color: var(--el-text-color-secondary);
  }
}
.ha-doc-badge {
  font-size: 12px;
  color: var(--el-color-primary);
  border: 1px solid var(--el-color-primary-light-5);
  border-radius: 4px;
  padding: 1px 8px;
  background: var(--el-color-primary-light-9);
}
.ha-doc-part {
  margin-bottom: 16px;

  .ha-version-select {
    width: 100%;
    margin-bottom: 8px;
  }
}
.ha-doc-part-head {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 8px;
  color: var(--el-text-color-primary);
}
.ha-doc-ai-meta {
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  padding: 10px 12px;

  p {
    margin: 0 0 4px;
  }

  small {
    display: block;
    color: var(--el-text-color-secondary);
    line-height: 1.7;
  }
}
.ha-doc-form-title {
  text-align: center;
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 2px;
  margin-bottom: 10px;
}
.ha-doc-sec {
  font-weight: 700;
  font-size: 13px;
  margin: 12px 0 6px;
  padding-bottom: 2px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.ha-doc-line {
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--el-text-color-regular);

  b {
    color: var(--el-text-color-primary);
  }
}
.ha-doc-table {
  width: 100%;
  border-collapse: collapse;
  margin: 4px 0 8px;

  th,
  td {
    border: 1px solid var(--el-border-color);
    font-size: 12px;
    padding: 3px 6px;
    text-align: left;
    word-break: break-all;
  }

  th {
    background: var(--el-fill-color-light);
    font-weight: 600;
  }
}
.ha-fade-enter-active,
.ha-fade-leave-active {
  transition: opacity 0.2s linear;
}
.ha-fade-enter-from,
.ha-fade-leave-to {
  opacity: 0;
}
</style>
