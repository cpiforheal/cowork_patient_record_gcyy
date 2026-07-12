<template>
  <div class="tcm-page">
    <header class="hero-panel">
      <div>
        <p class="eyebrow">TRADITIONAL CHINESE MEDICINE PHARMACY</p>
        <h1>中药房协同工作台</h1>
        <p>电子开方、收费门控、药师审方、调剂代煎、叫号领取统一闭环。</p>
      </div>
      <div class="hero-actions">
        <el-button v-if="canCreatePrescription" type="primary" size="large" @click="openCreateDialog">新建电子处方</el-button>
        <el-button v-if="canViewDisplay" size="large" @click="openDisplay">打开取药大屏</el-button>
        <el-button v-if="currentRole === 'admin'" size="large" plain @click="resetDemo">重置演示数据</el-button>
      </div>
    </header>

    <section class="metric-grid">
      <article v-for="metric in metrics" :key="metric.key" class="metric-card" :class="metric.tone">
        <span>{{ metric.label }}</span
        ><strong>{{ metric.value }}</strong
        ><small>{{ metric.note }}</small>
      </article>
    </section>

    <section class="workspace-grid">
      <div class="queue-panel">
        <div class="panel-heading">
          <div>
            <p class="eyebrow">LIVE WORKFLOW</p>
            <h2>处方与生产队列</h2>
          </div>
          <div class="filters">
            <el-input v-model="keyword" clearable placeholder="患者 / 取药号 / 处方号" @keyup.enter="loadList" />
            <el-select v-model="statusFilter" clearable placeholder="全部状态" @change="loadList">
              <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-button @click="loadList">刷新</el-button>
          </div>
        </div>
        <div v-loading="loading" class="prescription-list">
          <button
            v-for="row in rows"
            :key="row.id"
            class="prescription-card"
            :class="{ active: selected?.id === row.id }"
            @click="selectRow(row.id)"
          >
            <div class="card-number">
              <b>{{ row.pickupNo || "待编号" }}</b
              ><span>{{ typeLabel(row.dispenseType) }}</span>
            </div>
            <div class="card-main">
              <strong>{{ row.patientName }}</strong>
              <span>{{ row.prescriptionNo }} · {{ row.herbCount }} 味 / {{ row.doseCount }} 帖</span>
              <small>{{ row.doctorName }} · {{ row.updatedAt }}</small>
            </div>
            <el-tag :type="statusType(row.prescriptionStatus)" effect="light">{{ statusLabel(row.prescriptionStatus) }}</el-tag>
          </button>
          <el-empty v-if="!rows.length && !loading" description="暂无符合条件的处方" />
        </div>
      </div>

      <aside class="detail-panel">
        <template v-if="selected">
          <div class="detail-heading">
            <div>
              <p class="eyebrow">PRESCRIPTION DETAIL</p>
              <h2>{{ selected.patientName }} · {{ selected.pickupNo || "待编号" }}</h2>
            </div>
            <el-tag size="large" :type="statusType(selected.prescriptionStatus)">{{
              statusLabel(selected.prescriptionStatus)
            }}</el-tag>
          </div>
          <div class="stage-strip">
            <div v-for="stage in stages" :key="stage.label" :class="stage.state">
              <i></i><span>{{ stage.label }}</span
              ><small>{{ stage.value }}</small>
            </div>
          </div>
          <div class="info-grid">
            <div>
              <span>处方号</span><b>{{ selected.prescriptionNo }}</b>
            </div>
            <div>
              <span>调剂方式</span><b>{{ typeLabel(selected.dispenseType) }}</b>
            </div>
            <div>
              <span>金额</span><b>¥ {{ Number(selected.amount || 0).toFixed(2) }}</b>
            </div>
            <div>
              <span>医师</span><b>{{ selected.doctorName }}</b>
            </div>
          </div>
          <section class="herb-section">
            <h3>处方明细</h3>
            <div class="herb-grid">
              <span v-for="(item, index) in selected.items" :key="`${item.name}-${index}`">
                <b>{{ item.name }}</b
                >{{ item.dose }}{{ item.unit || "g" }} <small>{{ item.method || "" }}</small>
              </span>
            </div>
          </section>
          <section class="action-section">
            <h3>当前可执行操作</h3>
            <div class="action-buttons">
              <el-button v-if="canSubmitPrescription" type="primary" @click="run(() => submitTcmPrescriptionApi(selected!.id))"
                >签署提交</el-button
              >
              <el-button
                v-if="canCharge && selected.prescriptionStatus === 'WAITING_CHARGE'"
                type="success"
                @click="run(() => confirmTcmChargeApi(selected!.id))"
                >确认收费</el-button
              >
              <template v-if="canReview && ['WAITING_REVIEW', 'REVIEW_HOLD'].includes(selected.prescriptionStatus)">
                <el-button type="success" @click="review('APPROVE')">审方通过</el-button>
                <el-button type="warning" @click="reviewWithReason('HOLD')">挂起</el-button>
                <el-button type="danger" @click="reviewWithReason('RETURN')">退回医师</el-button>
              </template>
              <el-button v-if="canDispense && selected.dispensingStatus === 'WAITING'" type="primary" @click="dispense('start')"
                >开始抓药</el-button
              >
              <el-button
                v-if="canDispense && selected.dispensingStatus === 'IN_PROGRESS'"
                type="primary"
                @click="dispense('complete')"
                >抓药完成</el-button
              >
              <el-button
                v-if="canDispense && selected.dispensingStatus === 'COMPLETED'"
                type="success"
                @click="dispense('verify')"
                >调剂复核通过</el-button
              >
              <el-button v-if="canDecoct && selected.decoctionStatus === 'WAITING_SOAK'" type="primary" @click="decoct('soak')"
                >开始浸泡</el-button
              >
              <el-button v-if="canDecoct && selected.decoctionStatus === 'SOAKING'" type="primary" @click="decoct('decoct')"
                >开始煎制</el-button
              >
              <el-button v-if="canDecoct && selected.decoctionStatus === 'DECOCTING'" type="primary" @click="decoct('pack')"
                >进入包装</el-button
              >
              <el-button v-if="canDecoct && selected.decoctionStatus === 'PACKAGING'" type="primary" @click="decoct('complete')"
                >生产完成</el-button
              >
              <el-button v-if="canDecoct && selected.decoctionStatus === 'COMPLETED'" type="success" @click="decoct('verify')"
                >成品复核通过</el-button
              >
              <el-button
                v-if="canPickup && ['READY', 'CALLED'].includes(selected.prescriptionStatus)"
                type="warning"
                @click="callPickup"
                >{{ selected.prescriptionStatus === "CALLED" ? "再次叫号" : "叫号取药" }}</el-button
              >
              <el-button
                v-if="canPickup && ['READY', 'CALLED'].includes(selected.prescriptionStatus)"
                type="success"
                @click="run(() => collectTcmPickupApi(selected!.id))"
                >核验领取</el-button
              >
              <el-button
                v-if="canMarkException && !['COLLECTED', 'DRAFT'].includes(selected.prescriptionStatus)"
                plain
                type="danger"
                @click="markException"
                >登记异常</el-button
              >
            </div>
          </section>
          <section class="audit-section">
            <h3>操作轨迹</h3>
            <el-timeline>
              <el-timeline-item
                v-for="log in workspace?.audits.slice(0, 8)"
                :key="log.id"
                :timestamp="log.createdAt"
                placement="top"
              >
                <b>{{ log.detail }}</b
                ><small>{{ log.operatorName }} · {{ log.operatorRole }}</small>
              </el-timeline-item>
            </el-timeline>
          </section>
        </template>
        <el-empty v-else description="从左侧选择一张处方查看完整工作流" />
      </aside>
    </section>

    <el-dialog v-model="createVisible" title="新建中药电子处方" width="1040px" destroy-on-close>
      <el-form label-position="top" class="prescription-form">
        <el-alert
          title="患者信息直接关联今日院内患者主档；药味支持中药索引检索，也可录入索引外药材。"
          type="info"
          :closable="false"
        />
        <div class="form-section-title"><b>患者与处方信息</b><span>先选择患者，系统自动带入患者 ID 与就诊号</span></div>
        <div class="form-grid">
          <el-form-item label="今日患者" class="patient-selector">
            <el-select
              v-model="form.patientId"
              filterable
              clearable
              :loading="patientLoading"
              placeholder="输入姓名或就诊号检索"
              @change="selectPatient"
            >
              <el-option
                v-for="patient in todayPatients"
                :key="patient.id"
                :value="patient.id"
                :label="`${patient.name} · ${patient.visitNo}`"
              >
                <div class="patient-option">
                  <b>{{ patient.name }}</b
                  ><span>{{ patient.visitNo }} · {{ patient.doctor || "未分配医师" }}</span>
                </div>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="患者姓名"><el-input v-model="form.patientName" placeholder="选择患者后自动带入" /></el-form-item>
          <el-form-item label="就诊号"><el-input v-model="form.visitNo" placeholder="选择患者后自动带入" /></el-form-item>
          <el-form-item label="调剂方式">
            <el-radio-group v-model="form.dispenseType"
              ><el-radio-button value="SELF_DECOCTION">患者自煎</el-radio-button
              ><el-radio-button value="HOSPITAL_DECOCTION">院内代煎</el-radio-button></el-radio-group
            >
          </el-form-item>
          <el-form-item label="帖数"><el-input-number v-model="form.doseCount" :min="1" :max="60" /></el-form-item>
          <el-form-item label="处方金额"><el-input-number v-model="form.amount" :min="0" :precision="2" /></el-form-item>
        </div>
        <div class="herb-editor-heading">
          <div>
            <h3>药味明细</h3>
            <span>选择药名后按 Enter 进入剂量；剂量按 Enter 自动新增下一味并聚焦药名</span>
          </div>
          <div class="herb-heading-actions">
            <el-button @click="batchVisible = !batchVisible">{{ batchVisible ? "收起批量录入" : "批量录入" }}</el-button>
            <el-button type="primary" plain @click="addHerb(true)">添加药味</el-button>
          </div>
        </div>
        <div v-if="batchVisible" class="batch-herb-panel">
          <el-input
            v-model="batchHerbText"
            type="textarea"
            :rows="5"
            placeholder="每行一味，例如：&#10;黄芪 15g&#10;当归 10g 后下&#10;甘草 6"
          />
          <div>
            <span>支持空格、逗号或制表符分隔；格式：药名 剂量 单位 特殊煎法</span>
            <el-button type="primary" @click="applyBatchHerbs">导入药味</el-button>
          </div>
        </div>
        <div class="herb-column-labels">
          <span>中药名称</span><span>剂量</span><span>单位</span><span>炮制 / 特殊煎法</span><span></span>
        </div>
        <div v-for="(item, index) in form.items" :key="index" class="herb-editor-row" :data-herb-row="index">
          <el-select
            v-model="item.name"
            class="herb-name-input"
            filterable
            allow-create
            default-first-option
            placeholder="检索中药名称"
            @change="focusHerbDose(index)"
          >
            <el-option v-for="herb in herbIndex" :key="herb" :label="herb" :value="herb" />
          </el-select>
          <el-input-number
            v-model="item.dose"
            class="herb-dose-input"
            :min="0.1"
            :precision="1"
            @keyup.enter="completeHerbRow(index)"
          />
          <el-select v-model="item.unit"
            ><el-option v-for="unit in herbUnits" :key="unit" :label="unit" :value="unit"
          /></el-select>
          <el-select v-model="item.method" filterable allow-create clearable placeholder="如：先煎、后下、包煎">
            <el-option v-for="method in herbMethods" :key="method" :label="method" :value="method" />
          </el-select>
          <el-button type="danger" text :disabled="form.items.length === 1" @click="form.items.splice(index, 1)">删除</el-button>
        </div>
        <el-form-item label="煎服与生产要求"
          ><el-input v-model="requirementsText" type="textarea" :rows="3" placeholder="用法、频次、每次用量、浸泡时长及其他医嘱"
        /></el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="createVisible = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="createPrescription">保存草稿</el-button></template
      >
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="tcmPharmacyWorkbench">
import { computed, nextTick, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useUserStore } from "@/stores/modules/user";
import { getPatientListApi } from "@/api/modules/clinic/patients";
import type { PatientRow } from "@/api/modules/clinic/types";
import {
  advanceTcmDecoctionApi,
  advanceTcmDispensingApi,
  callTcmPickupApi,
  collectTcmPickupApi,
  confirmTcmChargeApi,
  createTcmPrescriptionApi,
  getTcmDashboardApi,
  getTcmPrescriptionsApi,
  getTcmWorkspaceApi,
  markTcmExceptionApi,
  resetTcmDemoApi,
  reviewTcmPrescriptionApi,
  submitTcmPrescriptionApi,
  type TcmPrescription,
  type TcmPrescriptionPayload,
  type TcmReviewDecision,
  type TcmStatusCounts,
  type TcmWorkspace
} from "@/api/modules/clinic/tcmPharmacy";

const userStore = useUserStore();
const currentRole = computed(() => userStore.userInfo.role || "frontdesk");
const isAdmin = computed(() => currentRole.value === "admin");
const isDedicatedPharmacyOperator = computed(() => currentRole.value === "tcmPharmacyOperator");
const canCreatePrescription = computed(() => isAdmin.value || ["tcm", "doctor"].includes(currentRole.value));
const canViewDisplay = computed(
  () =>
    isAdmin.value || ["tcm", "doctor", "tcmPharmacyOperator", "pharmacist", "pharmacy", "decoction"].includes(currentRole.value)
);
const canCharge = computed(() => isAdmin.value || currentRole.value === "frontdesk" || isDedicatedPharmacyOperator.value);
const canReview = computed(() => isAdmin.value || isDedicatedPharmacyOperator.value);
const canDispense = computed(() => isAdmin.value || isDedicatedPharmacyOperator.value);
const canDecoct = computed(() => isAdmin.value || isDedicatedPharmacyOperator.value);
const canPickup = computed(() => isAdmin.value || isDedicatedPharmacyOperator.value);
const canMarkException = computed(() => isAdmin.value || isDedicatedPharmacyOperator.value);
const loading = ref(false);
const saving = ref(false);
const patientLoading = ref(false);
const createVisible = ref(false);
const batchVisible = ref(false);
const batchHerbText = ref("");
const todayPatients = ref<PatientRow[]>([]);
const keyword = ref("");
const statusFilter = ref("");
const rows = ref<TcmPrescription[]>([]);
const workspace = ref<TcmWorkspace>();
const counts = ref<TcmStatusCounts>({
  waitingCharge: 0,
  waitingReview: 0,
  dispensing: 0,
  decocting: 0,
  ready: 0,
  collectedToday: 0,
  exception: 0
});
const requirementsText = ref("");
const form = reactive<TcmPrescriptionPayload>({
  patientId: "",
  patientName: "",
  visitNo: "",
  dispenseType: "SELF_DECOCTION",
  doseCount: 7,
  amount: 0,
  items: [{ name: "", dose: 10, unit: "g", method: "" }],
  requirements: {}
});
const herbIndex = [
  "人参",
  "党参",
  "太子参",
  "黄芪",
  "白术",
  "茯苓",
  "甘草",
  "当归",
  "熟地黄",
  "生地黄",
  "白芍",
  "赤芍",
  "川芎",
  "丹参",
  "桃仁",
  "红花",
  "鸡血藤",
  "枸杞子",
  "麦冬",
  "天冬",
  "石斛",
  "玉竹",
  "黄精",
  "山药",
  "陈皮",
  "青皮",
  "枳实",
  "枳壳",
  "木香",
  "香附",
  "厚朴",
  "砂仁",
  "半夏",
  "竹茹",
  "桔梗",
  "杏仁",
  "麻黄",
  "桂枝",
  "紫苏叶",
  "荆芥",
  "防风",
  "羌活",
  "独活",
  "白芷",
  "细辛",
  "薄荷",
  "菊花",
  "柴胡",
  "葛根",
  "升麻",
  "黄芩",
  "黄连",
  "黄柏",
  "栀子",
  "金银花",
  "连翘",
  "板蓝根",
  "蒲公英",
  "鱼腥草",
  "大黄",
  "芒硝",
  "火麻仁",
  "郁李仁",
  "泽泻",
  "车前子",
  "薏苡仁",
  "猪苓",
  "茵陈",
  "附子",
  "干姜",
  "肉桂",
  "吴茱萸",
  "艾叶",
  "酸枣仁",
  "柏子仁",
  "远志",
  "龙骨",
  "牡蛎",
  "钩藤",
  "天麻",
  "地龙",
  "三七",
  "延胡索",
  "牛膝",
  "杜仲",
  "续断",
  "桑寄生",
  "淫羊藿",
  "补骨脂",
  "山茱萸",
  "五味子"
];
const herbUnits = ["g", "mg", "枚", "粒", "片", "段", "个", "包"];
const herbMethods = ["先煎", "后下", "包煎", "另煎", "烊化", "冲服", "兑服", "捣碎", "去心", "生用", "炒制", "炙制"];
const selected = computed(() => workspace.value?.prescription);
const canSubmitPrescription = computed(
  () => canCreatePrescription.value && selected.value && ["DRAFT", "RETURNED"].includes(selected.value.prescriptionStatus)
);
const metrics = computed(() => [
  { key: "charge", label: "待收费", value: counts.value.waitingCharge, note: "收费门控", tone: "amber" },
  { key: "review", label: "待审方", value: counts.value.waitingReview, note: "药师审核", tone: "purple" },
  { key: "dispensing", label: "调剂中", value: counts.value.dispensing, note: "抓药与复核", tone: "blue" },
  { key: "decocting", label: "代煎中", value: counts.value.decocting, note: "浸泡 / 煎制 / 包装", tone: "cyan" },
  { key: "ready", label: "可领取", value: counts.value.ready, note: "等待叫号", tone: "green" },
  { key: "done", label: "今日已发", value: counts.value.collectedToday, note: `异常 ${counts.value.exception}`, tone: "slate" }
]);
const statusOptions = [
  "WAITING_CHARGE",
  "WAITING_REVIEW",
  "DISPENSING",
  "DECOCTING",
  "READY",
  "CALLED",
  "COLLECTED",
  "EXCEPTION"
].map(value => ({ value, label: statusLabel(value) }));
const stages = computed(() =>
  selected.value
    ? [
        { label: "收费", value: selected.value.chargeStatus, state: selected.value.chargeStatus === "PAID" ? "done" : "active" },
        {
          label: "审方",
          value: selected.value.reviewStatus,
          state: selected.value.reviewStatus === "APPROVED" ? "done" : "active"
        },
        {
          label: "调剂",
          value: selected.value.dispensingStatus,
          state: selected.value.dispensingStatus === "VERIFIED" ? "done" : "active"
        },
        {
          label: selected.value.dispenseType === "HOSPITAL_DECOCTION" ? "代煎" : "自煎",
          value: selected.value.decoctionStatus,
          state: ["VERIFIED", "NOT_REQUIRED"].includes(selected.value.decoctionStatus) ? "done" : "active"
        },
        {
          label: "取药",
          value: selected.value.pickupStatus,
          state: selected.value.pickupStatus === "COLLECTED" ? "done" : "active"
        }
      ]
    : []
);

function statusLabel(status: string) {
  return (
    (
      {
        DRAFT: "草稿",
        WAITING_CHARGE: "待收费",
        WAITING_REVIEW: "待审方",
        REVIEW_HOLD: "审核挂起",
        RETURNED: "退回修改",
        DISPENSING: "调剂中",
        DECOCTING: "代煎中",
        READY: "可领取",
        CALLED: "已叫号",
        COLLECTED: "已领取",
        EXCEPTION: "异常"
      } as Record<string, string>
    )[status] || status
  );
}
function statusType(status: string) {
  return (
    (
      {
        WAITING_CHARGE: "warning",
        WAITING_REVIEW: "warning",
        DISPENSING: "primary",
        DECOCTING: "primary",
        READY: "success",
        CALLED: "success",
        COLLECTED: "info",
        EXCEPTION: "danger",
        RETURNED: "danger"
      } as Record<string, any>
    )[status] || "info"
  );
}
function typeLabel(type: string) {
  return type === "HOSPITAL_DECOCTION" ? "院内代煎" : "患者自煎";
}
function focusHerbInput(index: number, className: string) {
  void nextTick(() => {
    const row = document.querySelector<HTMLElement>(`[data-herb-row="${index}"]`);
    row?.querySelector<HTMLInputElement>(`${className} input`)?.focus();
  });
}
function focusHerbDose(index: number) {
  focusHerbInput(index, ".herb-dose-input");
}
function addHerb(focus = false) {
  form.items.push({ name: "", dose: 10, unit: "g", method: "" });
  if (focus) focusHerbInput(form.items.length - 1, ".herb-name-input");
}
function completeHerbRow(index: number) {
  if (!form.items[index]?.name.trim()) {
    focusHerbInput(index, ".herb-name-input");
    return;
  }
  const nextIndex = index + 1;
  if (!form.items[nextIndex]) addHerb();
  focusHerbInput(nextIndex, ".herb-name-input");
}
function applyBatchHerbs() {
  const parsed = batchHerbText.value
    .split(/\r?\n/)
    .map(line => line.trim())
    .filter(Boolean)
    .map(line => {
      const parts = line.split(/[\s,，\t]+/).filter(Boolean);
      const doseToken = parts.find(part => /^\d+(?:\.\d+)?(?:g|mg)?$/i.test(part));
      const dose = Number(doseToken?.match(/^\d+(?:\.\d+)?/)?.[0] || 10);
      const unit = doseToken?.toLowerCase().endsWith("mg") ? "mg" : "g";
      const doseIndex = doseToken ? parts.indexOf(doseToken) : -1;
      return {
        name: parts[0] || "",
        dose,
        unit,
        method: doseIndex >= 0 ? parts.slice(doseIndex + 1).join(" ") : parts.slice(1).join(" ")
      };
    })
    .filter(item => item.name);
  if (!parsed.length) {
    ElMessage.warning("未识别到可导入的药味，请按每行一味填写");
    return;
  }
  const existing = form.items.filter(item => item.name.trim());
  form.items.splice(0, form.items.length, ...existing, ...parsed);
  batchHerbText.value = "";
  batchVisible.value = false;
  ElMessage.success(`已导入 ${parsed.length} 味中药`);
  addHerb(true);
}
function localDate() {
  const now = new Date();
  const offset = now.getTimezoneOffset() * 60000;
  return new Date(now.getTime() - offset).toISOString().slice(0, 10);
}
async function loadTodayPatients() {
  patientLoading.value = true;
  try {
    const today = localDate();
    const { data } = await getPatientListApi({ pageNum: 1, pageSize: 500, visitDateFrom: today, visitDateTo: today });
    todayPatients.value = data.list;
  } catch (error: any) {
    ElMessage.error(error?.message || "今日患者加载失败");
  } finally {
    patientLoading.value = false;
  }
}
function selectPatient(patientId?: string) {
  const patient = todayPatients.value.find(item => item.id === patientId);
  if (!patient) {
    form.patientId = "";
    form.patientName = "";
    form.visitNo = "";
    return;
  }
  form.patientId = patient.id;
  form.patientName = patient.name;
  form.visitNo = patient.visitNo;
}
async function openCreateDialog() {
  createVisible.value = true;
  batchVisible.value = false;
  batchHerbText.value = "";
  await loadTodayPatients();
}
function openDisplay() {
  window.open("/tcm-pharmacy/display", "_blank", "noopener,noreferrer");
}
function notifyDisplayCall(workspaceData: TcmWorkspace) {
  const announcement = workspaceData.announcements[0];
  if (!announcement) return;
  try {
    const channel = new BroadcastChannel("tcm-pharmacy-calls");
    channel.postMessage({ type: "TCM_CALL_CREATED", announcement });
    channel.close();
  } catch {
    localStorage.setItem("tcm-pharmacy-call-event", JSON.stringify({ id: announcement.id, sentAt: Date.now() }));
  }
}

async function loadDashboard() {
  const { data } = await getTcmDashboardApi();
  counts.value = data.counts;
}
async function loadList() {
  loading.value = true;
  try {
    const { data } = await getTcmPrescriptionsApi({ status: statusFilter.value, keyword: keyword.value });
    rows.value = data.rows;
    counts.value = data.counts;
    if (!selected.value && rows.value[0]) await selectRow(rows.value[0].id);
  } finally {
    loading.value = false;
  }
}
async function selectRow(id: string) {
  const { data } = await getTcmWorkspaceApi(id);
  workspace.value = data;
}
async function refresh(id?: string) {
  await Promise.all([loadDashboard(), loadList()]);
  if (id) await selectRow(id);
}
async function run(action: () => Promise<{ data: TcmWorkspace }>) {
  try {
    const { data } = await action();
    workspace.value = data;
    await refresh(data.prescription.id);
    ElMessage.success("操作已完成");
    return data;
  } catch (error: any) {
    ElMessage.error(error?.message || "操作失败");
    return undefined;
  }
}
async function callPickup() {
  if (!selected.value) return;
  const data = await run(() => callTcmPickupApi(selected.value!.id));
  if (data) notifyDisplayCall(data);
}
async function review(decision: TcmReviewDecision) {
  await run(() => reviewTcmPrescriptionApi(selected.value!.id, decision));
}
async function reviewWithReason(decision: TcmReviewDecision) {
  const { value } = await ElMessageBox.prompt(decision === "RETURN" ? "请输入退回原因" : "请输入挂起原因", "药师审方", {
    inputPattern: /\S+/,
    inputErrorMessage: "原因不能为空"
  });
  await run(() => reviewTcmPrescriptionApi(selected.value!.id, decision, value));
}
async function dispense(action: "start" | "complete" | "verify") {
  await run(() => advanceTcmDispensingApi(selected.value!.id, action));
}
async function decoct(action: "soak" | "decoct" | "pack" | "complete" | "verify") {
  await run(() => advanceTcmDecoctionApi(selected.value!.id, action));
}
async function markException() {
  const { value } = await ElMessageBox.prompt("请输入缺药、设备或生产异常说明", "登记异常", {
    inputPattern: /\S+/,
    inputErrorMessage: "异常原因不能为空"
  });
  await run(() => markTcmExceptionApi(selected.value!.id, value));
}
async function resetDemo() {
  await ElMessageBox.confirm("将清空当前中药房数据并恢复演示队列，是否继续？", "重置演示数据", { type: "warning" });
  await resetTcmDemoApi();
  workspace.value = undefined;
  await refresh();
}
async function createPrescription() {
  if (!form.patientName.trim() || !form.items.length || form.items.some(item => !item.name.trim()))
    return ElMessage.warning("请完整填写患者姓名和药味");
  saving.value = true;
  try {
    form.requirements = { instruction: requirementsText.value };
    const { data } = await createTcmPrescriptionApi(form);
    createVisible.value = false;
    workspace.value = data;
    await refresh(data.prescription.id);
    ElMessage.success("处方草稿已创建");
  } finally {
    saving.value = false;
  }
}

onMounted(() => refresh());
</script>

<style scoped lang="scss">
.tcm-page {
  min-height: 100%;
  padding: 24px;
  background: #f2f6f5;
  color: #17362f;
}
.hero-panel {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  padding: 28px 32px;
  border-radius: 24px;
  color: white;
  background: linear-gradient(125deg, #123f35, #1e6b59 58%, #b6873f);
  box-shadow: 0 18px 50px rgba(20, 63, 53, 0.18);
  h1 {
    margin: 6px 0 8px;
    font-size: 30px;
  }
  p {
    margin: 0;
    opacity: 0.82;
  }
}
.eyebrow {
  margin: 0;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.16em;
  opacity: 0.65;
}
.hero-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 14px;
  margin: 18px 0;
}
.metric-card {
  padding: 18px;
  border: 1px solid rgba(26, 73, 61, 0.08);
  border-radius: 18px;
  background: white;
  box-shadow: 0 8px 28px rgba(18, 63, 53, 0.06);
  span,
  small {
    display: block;
    color: #70827d;
  }
  strong {
    display: block;
    margin: 6px 0;
    font-size: 30px;
  }
  &.green strong {
    color: #17845e;
  }
  &.amber strong {
    color: #c18429;
  }
  &.purple strong {
    color: #7253b8;
  }
  &.blue strong {
    color: #3277b8;
  }
  &.cyan strong {
    color: #158b98;
  }
}
.workspace-grid {
  display: grid;
  grid-template-columns: minmax(520px, 1.1fr) minmax(500px, 0.9fr);
  gap: 18px;
  min-height: 640px;
}
.queue-panel,
.detail-panel {
  border: 1px solid rgba(26, 73, 61, 0.08);
  border-radius: 22px;
  background: white;
  box-shadow: 0 10px 34px rgba(18, 63, 53, 0.06);
  overflow: hidden;
}
.panel-heading,
.detail-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 22px 24px;
  border-bottom: 1px solid #edf1ef;
  h2 {
    margin: 4px 0 0;
    font-size: 20px;
  }
}
.filters {
  display: grid;
  grid-template-columns: 210px 140px auto;
  gap: 8px;
}
.prescription-list {
  padding: 14px;
  max-height: 680px;
  overflow: auto;
}
.prescription-card {
  width: 100%;
  display: grid;
  grid-template-columns: 90px 1fr auto;
  align-items: center;
  gap: 16px;
  padding: 16px;
  margin-bottom: 10px;
  text-align: left;
  border: 1px solid #e8efec;
  border-radius: 16px;
  background: #fbfdfc;
  cursor: pointer;
  transition: 0.2s ease;
  &:hover,
  &.active {
    transform: translateY(-1px);
    border-color: #2f8b73;
    box-shadow: 0 10px 24px rgba(47, 139, 115, 0.1);
  }
}
.card-number {
  b {
    display: block;
    font-size: 24px;
    color: #1f725f;
  }
  span {
    font-size: 11px;
    color: #81908c;
  }
}
.card-main {
  strong,
  span,
  small {
    display: block;
  }
  strong {
    font-size: 16px;
  }
  span {
    margin: 4px 0;
    color: #566b65;
  }
  small {
    color: #97a39f;
  }
}
.detail-panel {
  padding-bottom: 20px;
}
.stage-strip {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  padding: 18px 24px;
  background: #f7faf9;
  div {
    position: relative;
    text-align: center;
    color: #7c8b87;
    &:not(:last-child)::after {
      content: "";
      position: absolute;
      top: 7px;
      left: 58%;
      width: 84%;
      height: 2px;
      background: #dce6e2;
    }
    &.done {
      color: #19775f;
      i,
      &::after {
        background: #28a47e;
      }
    }
  }
  i {
    position: relative;
    z-index: 1;
    display: block;
    width: 14px;
    height: 14px;
    margin: 0 auto 6px;
    border-radius: 50%;
    background: #b7c6c1;
  }
  span,
  small {
    display: block;
  }
  small {
    margin-top: 2px;
    font-size: 9px;
  }
}
.info-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  padding: 20px 24px;
  div {
    padding: 12px;
    border-radius: 12px;
    background: #f5f8f7;
  }
  span,
  b {
    display: block;
  }
  span {
    margin-bottom: 5px;
    font-size: 12px;
    color: #81908c;
  }
}
.herb-section,
.action-section,
.audit-section {
  padding: 0 24px 20px;
  h3 {
    margin: 8px 0 12px;
    font-size: 15px;
  }
}
.herb-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  span {
    padding: 10px;
    border: 1px solid #e5ece9;
    border-radius: 10px;
    color: #536862;
  }
  b {
    margin-right: 6px;
    color: #17362f;
  }
}
.action-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 16px;
  border-radius: 14px;
  background: #f5f8f7;
}
.audit-section small {
  display: block;
  margin-top: 4px;
  color: #86958f;
}
.prescription-form .el-alert {
  margin-bottom: 18px;
}
.form-section-title,
.herb-editor-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 12px 0;
}
.form-section-title span,
.herb-editor-heading span {
  color: #86958f;
  font-size: 12px;
}
.herb-editor-heading h3 {
  display: inline;
  margin-right: 10px;
}
.herb-heading-actions {
  display: flex;
  gap: 8px;
}
.batch-herb-panel {
  margin: 8px 0 16px;
  padding: 14px;
  border: 1px solid #dce8e4;
  border-radius: 12px;
  background: #f7faf9;
  > div:last-child {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 16px;
    margin-top: 10px;
    color: #78857f;
    font-size: 12px;
  }
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0 16px;
}
.patient-selector {
  grid-column: span 2;
}
.patient-selector .el-select {
  width: 100%;
}
.patient-option {
  display: flex;
  justify-content: space-between;
  gap: 24px;
}
.patient-option span {
  color: #86958f;
}
.herb-column-labels,
.herb-editor-row {
  display: grid;
  grid-template-columns: 1.5fr 120px 90px 1.5fr auto;
  gap: 8px;
}
.herb-column-labels {
  padding: 8px 0 0;
  color: #78857f;
  font-size: 12px;
}
.herb-editor-row {
  margin: 8px 0;
}
@media (max-width: 1300px) {
  .metric-grid {
    grid-template-columns: repeat(3, 1fr);
  }
  .workspace-grid {
    grid-template-columns: 1fr;
  }
}
</style>
