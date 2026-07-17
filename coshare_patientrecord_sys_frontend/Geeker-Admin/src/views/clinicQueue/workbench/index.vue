<template>
  <div class="queue-page">
    <header class="hero-panel">
      <div>
        <p class="eyebrow">INSPECTION & RECEPTION QUEUE</p>
        <h1>检查室与接诊室排队叫号</h1>
        <p>前台一次发号、检查完成自动转接诊、复诊加权与初诊防饥饿、房间级急症暂停。</p>
      </div>
      <div class="hero-actions">
        <el-tag :type="printAgentOnline ? 'success' : 'danger'" effect="dark">
          {{ printAgentOnline ? `打印机：${printAgent?.printerName || "未选择"}` : "本机打印服务离线" }}
        </el-tag>
        <el-button v-if="canIssue" plain size="large" @click="openPrinterSetup">打印设置</el-button>
        <el-button v-if="role === 'admin'" plain size="large" @click="openTemplateSetup">票据模板</el-button>
        <el-button v-if="canIssue" type="primary" size="large" @click="openIssue">前台发号</el-button>
        <el-button size="large" @click="router.push('/tcm-pharmacy/clinic-queue/display')">打开双区大屏</el-button>
        <el-button size="large" plain :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <section class="metric-grid">
      <article v-for="metric in metrics" :key="metric.label" class="metric-card">
        <span>{{ metric.label }}</span
        ><strong>{{ metric.value }}</strong
        ><small>{{ metric.note }}</small>
      </article>
    </section>

    <section class="room-grid">
      <article
        v-for="room in dashboard.rooms"
        :key="room.roomCode"
        v-loading="isRoomPending(room)"
        element-loading-text="处理中..."
        class="room-card"
        :class="room.status.toLowerCase()"
      >
        <div>
          <p class="eyebrow">{{ room.stageCode }}</p>
          <h2>{{ room.roomName }}</h2>
          <span
            >{{ roomStatusLabel(room.status) }}<template v-if="room.pauseReason"> · {{ room.pauseReason }}</template></span
          >
        </div>
        <div class="room-actions">
          <el-button
            v-if="canOperate(room.stageCode)"
            type="primary"
            :disabled="Boolean(operationPending) || room.status !== 'ACTIVE'"
            @click="callNext(room.stageCode)"
          >
            叫下一位
          </el-button>
          <el-button v-if="canControlRoom(room.stageCode)" type="danger" plain @click="roomAction(room, 'emergency')"
            >急症暂停</el-button
          >
          <el-button v-if="canControlRoom(room.stageCode) && room.status === 'ACTIVE'" plain @click="roomAction(room, 'pause')"
            >人工暂停</el-button
          >
          <el-button
            v-if="canControlRoom(room.stageCode) && room.status !== 'ACTIVE'"
            type="success"
            plain
            @click="roomAction(room, 'resume')"
            >恢复</el-button
          >
          <el-button v-if="canControlRoom(room.stageCode)" plain @click="roomAction(room, 'close')">停诊</el-button>
        </div>
      </article>
    </section>

    <section class="workspace-grid">
      <div class="list-panel">
        <div class="panel-heading">
          <div>
            <p class="eyebrow">TODAY QUEUE</p>
            <h2>今日排队单</h2>
          </div>
          <el-input v-model="keyword" clearable placeholder="号码 / 患者 / 就诊ID" @keyup.enter="load" />
        </div>
        <div v-loading="loading" class="ticket-list">
          <button
            v-for="ticket in dashboard.tickets"
            :key="ticket.id"
            class="ticket-card"
            :class="{ active: selectedId === ticket.id }"
            @click="select(ticket.id)"
          >
            <div class="ticket-no">
              <b>{{ ticket.publicNo }}</b
              ><span>{{ visitTypeLabel(ticket.visitType) }}</span>
            </div>
            <div class="ticket-main">
              <strong>{{ ticket.patientName }}</strong
              ><span>{{ ticket.encounterId }}</span
              ><small>{{ ticket.updatedAt }}</small>
            </div>
            <el-tag :type="statusType(ticket.overallStatus)">{{ overallLabel(ticket.overallStatus) }}</el-tag>
          </button>
          <el-empty v-if="!dashboard.tickets.length && !loading" description="今日暂无排队单" />
        </div>
      </div>

      <aside class="detail-panel">
        <template v-if="workspace">
          <div class="detail-heading">
            <div>
              <p class="eyebrow">QUEUE DETAIL</p>
              <h2>{{ workspace.ticket.publicNo }} · {{ workspace.ticket.patientName }}</h2>
            </div>
            <div class="detail-actions">
              <el-button v-if="canIssue" :disabled="!printAgentOnline" @click="reprintCurrentTicket">补打票据</el-button>
              <el-button @click="openEncounter(workspace.ticket.encounterId)">进入病历工作台</el-button>
            </div>
          </div>
          <div class="stage-grid">
            <article
              v-for="task in workspace.tasks"
              :key="task.id"
              v-loading="isTaskPending(task)"
              element-loading-text="处理中..."
              class="stage-card"
            >
              <header>
                <div>
                  <p class="eyebrow">{{ task.stageCode }}</p>
                  <h3>{{ stageLabel(task.stageCode) }}</h3>
                </div>
                <el-tag :type="taskStatusType(task.status)">{{ taskStatusLabel(task.status) }}</el-tag>
              </header>
              <div class="time-list">
                <span>入队：{{ task.queueEnteredAt || "未激活" }}</span
                ><span>叫号：{{ task.calledAt || "-" }}</span
                ><span>开始：{{ task.serviceStartedAt || "-" }}</span>
              </div>
              <p v-if="task.priorityReason" class="reason">优先原因：{{ task.priorityReason }}</p>
              <p v-if="task.exceptionReason" class="reason danger">异常：{{ task.exceptionReason }}</p>
              <p
                v-if="['ARRIVED', 'IN_SERVICE'].includes(task.status) && canOperate(task.stageCode)"
                class="clinical-action-hint"
              >
                请进入病历工作台完成{{ stageLabel(task.stageCode) }}临床阶段；提交完成后，队列会自动流转。
              </p>
              <div v-if="canOperate(task.stageCode) || canFrontdeskIntervene" class="task-actions">
                <el-button
                  v-if="['WAITING', 'MISSED'].includes(task.status) && canOperate(task.stageCode)"
                  type="primary"
                  @click="taskAction(task, 'call')"
                  >叫号</el-button
                >
                <el-button
                  v-if="['CALLED', 'MISSED'].includes(task.status) && canOperate(task.stageCode)"
                  @click="taskAction(task, 'recall')"
                  >重呼</el-button
                >
                <el-button
                  v-if="['CALLED', 'MISSED'].includes(task.status) && canOperate(task.stageCode)"
                  type="success"
                  @click="taskAction(task, 'arrive')"
                  >到场</el-button
                >
                <el-button
                  v-if="task.status === 'ARRIVED' && canOperate(task.stageCode)"
                  type="success"
                  @click="taskAction(task, 'start')"
                  >开始办理</el-button
                >
                <el-button
                  v-if="task.status === 'INTERRUPTED' && canOperate(task.stageCode)"
                  type="success"
                  @click="taskAction(task, 'restore')"
                  >{{ interruptedRestoreLabel(task) }}</el-button
                >
                <el-button
                  v-if="task.status === 'CALLED' && canOperate(task.stageCode)"
                  type="warning"
                  @click="taskAction(task, 'missed')"
                  >过号</el-button
                >
                <el-button
                  v-if="['WAITING', 'MISSED', 'ARRIVED'].includes(task.status) && canOperate(task.stageCode)"
                  @click="taskAction(task, 'away', true)"
                  >暂离</el-button
                >
                <el-button
                  v-if="['TEMPORARILY_AWAY', 'ON_HOLD', 'MISSED'].includes(task.status)"
                  @click="taskAction(task, 'resume')"
                  >恢复排队</el-button
                >
                <el-button
                  v-if="
                    ['WAITING', 'CALLED', 'ARRIVED', 'IN_SERVICE', 'INTERRUPTED'].includes(task.status) &&
                    canOperate(task.stageCode)
                  "
                  @click="taskAction(task, 'hold', true)"
                  >挂起</el-button
                >
                <el-button
                  v-if="task.status === 'WAITING' && canFrontdeskIntervene"
                  type="warning"
                  plain
                  @click="taskAction(task, 'prioritize', true)"
                  >人工优先</el-button
                >
                <el-button
                  v-if="role === 'admin' && ['IN_SERVICE', 'ARRIVED', 'ON_HOLD'].includes(task.status)"
                  type="warning"
                  plain
                  @click="taskAction(task, 'complete')"
                  >异常恢复：同步完成</el-button
                >
                <el-button
                  v-if="
                    task.stageCode === 'RECEPTION' &&
                    ['ARRIVED', 'IN_SERVICE', 'ON_HOLD'].includes(task.status) &&
                    canOperate(task.stageCode)
                  "
                  type="warning"
                  @click="taskAction(task, 'supplement', true)"
                  >发起补检</el-button
                >
                <el-button
                  v-if="canFrontdeskIntervene && !['COMPLETED', 'CANCELLED'].includes(task.status)"
                  type="danger"
                  plain
                  @click="taskAction(task, 'cancel', true)"
                  >取消</el-button
                >
                <el-button
                  v-if="canFrontdeskIntervene && !['COMPLETED', 'CANCELLED'].includes(task.status)"
                  type="danger"
                  plain
                  @click="taskAction(task, 'leave', true)"
                  >离院</el-button
                >
              </div>
            </article>
          </div>
          <section v-if="workspace.ticket.overallStatus === 'COMPLETED'" class="queue-completion-card">
            <div>
              <strong>接诊已完成，当前患者已退出候诊队列</strong>
              <span>下一临床环节：中医辨证；病例已交接至中医岗位。</span>
            </div>
            <div class="queue-completion-actions">
              <el-button
                v-if="canContinueNextClinicalStage"
                :disabled="Boolean(operationPending)"
                @click="openEncounter(workspace.ticket.encounterId)"
                >继续当前患者下一环节</el-button
              >
              <el-button
                v-if="canOperate('RECEPTION')"
                type="primary"
                :loading="operationPending === 'call-next:RECEPTION'"
                :disabled="Boolean(operationPending)"
                @click="callNext('RECEPTION')"
                >叫下一位</el-button
              >
            </div>
          </section>
          <section class="audit-panel">
            <h3>审计轨迹</h3>
            <el-timeline>
              <el-timeline-item
                v-for="log in workspace.audits.slice(0, 16)"
                :key="log.id"
                :timestamp="log.createdAt"
                placement="top"
              >
                <b>{{ log.detail || log.actionCode }}</b
                ><small>{{ log.operatorName }} · {{ log.operatorRole }} · {{ log.fromStatus }} → {{ log.toStatus }}</small>
              </el-timeline-item>
            </el-timeline>
          </section>
        </template>
        <el-empty v-else description="选择排队单查看两阶段任务" />
      </aside>
    </section>

    <el-dialog v-model="issueVisible" title="前台发号" width="760px">
      <el-form label-position="top">
        <el-form-item label="选择当日就诊记录">
          <el-select
            v-model="issueForm.encounterId"
            filterable
            placeholder="患者姓名 / 就诊ID"
            style="width: 100%"
            @change="syncIssueVisitType"
          >
            <el-option
              v-for="item in encounters"
              :key="item.id"
              :value="item.id"
              :label="`${item.patientName} · 第${item.visitNo}次 · ${item.id}`"
            />
          </el-select>
        </el-form-item>
        <el-alert
          :title="
            selectedIssueEncounter?.visitNo && selectedIssueEncounter.visitNo > 1
              ? '系统已识别为后续来诊，本轮按复诊排队。'
              : '首次来诊按初诊排队；后续周期新建的复诊病历会自动按复诊排队。'
          "
          type="info"
          :closable="false"
          show-icon
        />
        <el-form-item label="本轮排队类型">
          <el-radio-group v-model="issueForm.visitType">
            <el-radio-button value="FIRST_VISIT">初诊</el-radio-button>
            <el-radio-button value="FOLLOW_UP">复诊</el-radio-button>
          </el-radio-group>
          <div class="issue-type-hint">通常无需手工修改；仅患者本轮就诊性质与系统识别不一致时调整。</div>
        </el-form-item>
        <el-alert
          title="若该就诊记录已经发过号，系统只返回原号码，不会自动补打；可在右侧排队单详情点击“补打票据”。"
          type="warning"
          :closable="false"
        />
      </el-form>
      <template #footer
        ><el-button @click="issueVisible = false">取消</el-button
        ><el-button type="primary" :loading="submitting" @click="issueTicket">确认发号</el-button></template
      >
    </el-dialog>

    <el-dialog v-model="printerSetupVisible" title="本机热敏打印设置" width="620px">
      <el-alert
        title="打印服务仅监听本机 127.0.0.1，不向局域网开放。终端与打印机绑定后，发号成功将自动出票。"
        type="info"
        :closable="false"
        show-icon
      />
      <el-form label-position="top" class="printer-form">
        <el-form-item label="终端编号">
          <el-input v-model="printerForm.terminalId" placeholder="例如 FRONT_DESK_01" />
        </el-form-item>
        <el-form-item label="终端名称">
          <el-input v-model="printerForm.terminalName" placeholder="例如 一楼前台一号机" />
        </el-form-item>
        <el-form-item label="Windows 打印机">
          <el-select v-model="printerForm.printerName" filterable placeholder="选择已安装的打印机" style="width: 100%">
            <el-option v-for="name in printAgent?.printers || []" :key="name" :label="name" :value="name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="printerSetupVisible = false">取消</el-button>
        <el-button type="primary" :loading="printerSaving" @click="savePrinterSetup">保存并绑定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="templateVisible" title="排队票据模板" width="940px" destroy-on-close>
      <div class="template-editor-grid">
        <el-form label-position="top" class="template-form">
          <div class="template-form-row">
            <el-form-item label="机构名称"><el-input v-model="templateForm.institutionName" maxlength="24" /></el-form-item>
            <el-form-item label="票据标题"><el-input v-model="templateForm.title" maxlength="16" /></el-form-item>
          </div>
          <div class="template-form-row">
            <el-form-item label="纸张宽度">
              <el-radio-group v-model="templateForm.paperWidth">
                <el-radio-button :value="58">58mm 短票</el-radio-button>
                <el-radio-button :value="80">80mm</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="号码字号">
              <el-slider v-model="templateForm.numberFontSize" :min="30" :max="64" show-input />
            </el-form-item>
          </div>
          <el-form-item label="显示字段">
            <el-checkbox v-model="templateForm.showMaskedName">脱敏姓名</el-checkbox>
            <el-checkbox v-model="templateForm.showVisitType">初诊/复诊</el-checkbox>
            <el-checkbox v-model="templateForm.showFirstStage">首个候诊区</el-checkbox>
            <el-checkbox v-model="templateForm.showIssuedAt">取号时间</el-checkbox>
            <el-checkbox v-model="templateForm.showNotice">底部提示</el-checkbox>
          </el-form-item>
          <el-form-item label="主提示语"><el-input v-model="templateForm.notice" maxlength="40" /></el-form-item>
          <el-form-item label="次提示语"><el-input v-model="templateForm.secondaryNotice" maxlength="40" /></el-form-item>
          <el-switch v-model="templateForm.compact" active-text="紧凑短票模式" inactive-text="标准间距" />
        </el-form>
        <div class="ticket-preview-wrap">
          <p class="eyebrow">LIVE PREVIEW</p>
          <div
            class="ticket-preview"
            :class="{ compact: templateForm.compact }"
            :style="{ width: `${templateForm.paperWidth === 58 ? 232 : 320}px` }"
          >
            <strong class="preview-institution">{{ templateForm.institutionName || "门诊部" }}</strong>
            <span class="preview-title">{{ templateForm.title || "排队凭证" }}</span>
            <b class="preview-number" :style="{ fontSize: `${templateForm.numberFontSize}px` }">A023</b>
            <div class="preview-meta">
              <span v-if="templateForm.showMaskedName">患者：张*明</span>
              <span v-if="templateForm.showVisitType">类型：初诊</span>
              <span v-if="templateForm.showFirstStage">请前往：检查室</span>
              <span v-if="templateForm.showIssuedAt">取号：2026-07-15 16:30</span>
            </div>
            <div v-if="templateForm.showNotice" class="preview-notice">
              <span>{{ templateForm.notice }}</span
              ><span>{{ templateForm.secondaryNotice }}</span>
            </div>
          </div>
          <small>预览按实际纸宽比例缩放，内容越少，最终票据越短。</small>
        </div>
      </div>
      <template #footer>
        <el-button @click="templateVisible = false">取消</el-button>
        <el-button :disabled="!printAgentOnline" @click="testPrintTemplate">打印测试票</el-button>
        <el-button type="primary" :loading="templateSaving" @click="saveTemplate">保存模板</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="clinicQueueWorkbench">
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { useUserStore } from "@/stores/modules/user";
import { getPreAiEncountersApi, type PreAiEncounterSummary } from "@/api/modules/clinic/preAi";
import {
  callNextQueueApi,
  completeQueuePrintTaskApi,
  createQueuePrintTaskApi,
  getQueueDashboardApi,
  getQueuePrintTemplateApi,
  getQueueTestPrintPayloadApi,
  getQueueWorkspaceApi,
  issueQueueTicketApi,
  registerQueuePrintTerminalApi,
  saveQueuePrintTemplateApi,
  runQueueRoomActionApi,
  runQueueTaskActionApi,
  type QueueDashboard,
  type QueueRoom,
  type QueueStage,
  type QueueTask,
  type QueuePrintTemplate,
  type QueueVisitType,
  type QueueWorkspace
} from "@/api/modules/clinic/clinicQueue";
import {
  configureLocalPrintAgent,
  getLocalPrintAgentStatus,
  getStoredPrintTerminalId,
  printQueueTicketLocally,
  storePrintTerminalId,
  type LocalPrintAgentStatus
} from "../printAgent";

const router = useRouter();
const userStore = useUserStore();
const role = computed(() => userStore.userInfo.role || "frontdesk");
const loading = ref(false);
const submitting = ref(false);
const operationPending = ref("");
let dashboardRequestSeq = 0;
let workspaceRequestSeq = 0;
const keyword = ref("");
const selectedId = ref("");
const workspace = ref<QueueWorkspace>();
const encounters = ref<PreAiEncounterSummary[]>([]);
const issueVisible = ref(false);
const issueForm = reactive<{ encounterId: string; visitType: QueueVisitType }>({ encounterId: "", visitType: "FIRST_VISIT" });
const selectedIssueEncounter = computed(() => encounters.value.find(item => item.id === issueForm.encounterId));
const printAgent = ref<LocalPrintAgentStatus>();
const printAgentOnline = computed(() => printAgent.value?.status === "ok" && Boolean(printAgent.value.terminalId));
const printerSetupVisible = ref(false);
const printerSaving = ref(false);
const printerForm = reactive({ terminalId: "", terminalName: "", printerName: "" });
const templateVisible = ref(false);
const templateSaving = ref(false);
const templateForm = reactive<QueuePrintTemplate>({
  institutionName: "门诊部",
  title: "排队凭证",
  paperWidth: 58,
  numberFontSize: 42,
  compact: true,
  showMaskedName: true,
  showVisitType: true,
  showFirstStage: true,
  showIssuedAt: true,
  showNotice: true,
  notice: "请留意大屏及语音叫号",
  secondaryNotice: "检查完成后沿用本号码"
});
const dashboard = reactive<QueueDashboard>({
  tickets: [],
  rooms: [],
  counts: {
    inspectionWaiting: 0,
    inspectionActive: 0,
    receptionWaiting: 0,
    receptionActive: 0,
    completedToday: 0,
    exceptions: 0
  },
  currentUserRole: "",
  serverTime: ""
});

const canIssue = computed(() => ["admin", "frontdesk"].includes(role.value));
const canFrontdeskIntervene = computed(() => ["admin", "frontdesk"].includes(role.value));
const canContinueNextClinicalStage = computed(() => ["admin", "tcm"].includes(role.value));
const metrics = computed(() => [
  { label: "待检查", value: dashboard.counts.inspectionWaiting, note: `办理中 ${dashboard.counts.inspectionActive}` },
  { label: "待接诊", value: dashboard.counts.receptionWaiting, note: `办理中 ${dashboard.counts.receptionActive}` },
  { label: "今日完成", value: dashboard.counts.completedToday, note: "双阶段闭环" },
  { label: "异常待处理", value: dashboard.counts.exceptions, note: "过号 / 暂离 / 挂起 / 中断" }
]);

function canOperate(stage: QueueStage) {
  return (
    role.value === "admin" ||
    (stage === "INSPECTION" ? role.value === "inspection" : ["reception", "doctor"].includes(role.value))
  );
}
function canControlRoom(stage: QueueStage) {
  return canFrontdeskIntervene.value || canOperate(stage);
}
function isRoomPending(room: QueueRoom) {
  return operationPending.value === `call-next:${room.stageCode}` || operationPending.value.startsWith(`room:${room.roomCode}:`);
}
function isTaskPending(task: QueueTask) {
  return operationPending.value.startsWith(`task:${task.id}:`);
}
function interruptedRestoreLabel(task: QueueTask) {
  return (
    (
      {
        CALLED: "恢复叫号",
        ARRIVED: "恢复到场",
        IN_SERVICE: "继续办理"
      } as Record<string, string>
    )[task.interruptedFromStatus] || "恢复中断前状态"
  );
}
function actionSuccessMessage(action: string) {
  return (
    (
      {
        call: "叫号成功",
        recall: "已重新呼叫",
        arrive: "已确认到场",
        start: "已开始办理",
        missed: "已登记过号",
        away: "已登记暂离",
        resume: "已恢复排队",
        restore: "已恢复急症中断前状态",
        hold: "已挂起",
        prioritize: "已设为人工优先",
        complete: "临床完成状态已同步",
        supplement: "已发起补检",
        cancel: "排队流程已取消",
        leave: "已办理离院"
      } as Record<string, string>
    )[action] || "操作成功"
  );
}
async function refreshAfterFailure() {
  try {
    await load();
  } catch {
    ElMessage.warning("最新队列状态刷新失败，请稍后手动刷新");
  }
}
async function load() {
  const requestSeq = ++dashboardRequestSeq;
  ++workspaceRequestSeq;
  loading.value = true;
  try {
    const { data } = await getQueueDashboardApi(keyword.value);
    if (requestSeq !== dashboardRequestSeq) return;
    Object.assign(dashboard, data);
    if (selectedId.value && dashboard.tickets.some(item => item.id === selectedId.value)) await select(selectedId.value);
    else if (dashboard.tickets[0]) await select(dashboard.tickets[0].id);
    else {
      selectedId.value = "";
      workspace.value = undefined;
      ++workspaceRequestSeq;
    }
  } catch (error: any) {
    if (requestSeq === dashboardRequestSeq) ElMessage.error(error?.message || "队列数据加载失败，请稍后重试");
  } finally {
    if (requestSeq === dashboardRequestSeq) loading.value = false;
  }
}
async function select(id: string) {
  selectedId.value = id;
  const requestSeq = ++workspaceRequestSeq;
  try {
    const { data } = await getQueueWorkspaceApi(id);
    if (requestSeq !== workspaceRequestSeq || selectedId.value !== id) return;
    workspace.value = data;
  } catch (error: any) {
    if (requestSeq === workspaceRequestSeq && selectedId.value === id) {
      ElMessage.error(error?.message || "患者队列详情加载失败，请刷新后重试");
    }
  }
}
async function openIssue() {
  encounters.value = (await getPreAiEncountersApi()).data.list.filter(item => item.status !== "CANCELLED");
  issueForm.encounterId = "";
  issueForm.visitType = "FIRST_VISIT";
  issueVisible.value = true;
}
function syncIssueVisitType() {
  issueForm.visitType =
    selectedIssueEncounter.value?.visitNo && selectedIssueEncounter.value.visitNo > 1 ? "FOLLOW_UP" : "FIRST_VISIT";
}
async function refreshPrintAgent(silent = true) {
  try {
    printAgent.value = await getLocalPrintAgentStatus();
    if (printAgent.value.terminalId) storePrintTerminalId(printAgent.value.terminalId);
  } catch (error: any) {
    printAgent.value = undefined;
    if (!silent) ElMessage.error(error.message || "本机打印服务未启动");
  }
}
async function openPrinterSetup() {
  await refreshPrintAgent(false);
  if (!printAgent.value) return;
  printerForm.terminalId = printAgent.value.terminalId || getStoredPrintTerminalId() || "FRONT_DESK_01";
  printerForm.terminalName = printAgent.value.terminalName || "前台发号终端";
  printerForm.printerName = printAgent.value.printerName || printAgent.value.printers[0] || "";
  printerSetupVisible.value = true;
}
async function savePrinterSetup() {
  if (!printerForm.terminalId.trim() || !printerForm.terminalName.trim() || !printerForm.printerName) {
    return ElMessage.warning("请完整填写终端编号、名称并选择打印机");
  }
  printerSaving.value = true;
  try {
    const local = await configureLocalPrintAgent(printerForm);
    await registerQueuePrintTerminalApi({
      terminalId: local.terminalId,
      terminalName: local.terminalName,
      printerName: local.printerName,
      agentVersion: local.version
    });
    printAgent.value = local;
    storePrintTerminalId(local.terminalId);
    printerSetupVisible.value = false;
    ElMessage.success("本机打印终端已绑定");
  } finally {
    printerSaving.value = false;
  }
}
async function openTemplateSetup() {
  const { data } = await getQueuePrintTemplateApi();
  Object.assign(templateForm, data.config);
  templateVisible.value = true;
}
async function saveTemplate() {
  templateSaving.value = true;
  try {
    const { data } = await saveQueuePrintTemplateApi({ ...templateForm });
    Object.assign(templateForm, data.config);
    ElMessage.success("票据模板已保存");
  } finally {
    templateSaving.value = false;
  }
}
async function testPrintTemplate() {
  if (!printAgentOnline.value || !printAgent.value) return ElMessage.warning("请先连接并绑定本机打印机");
  await saveTemplate();
  try {
    const { data } = await getQueueTestPrintPayloadApi(printAgent.value.terminalId);
    await printQueueTicketLocally(data.payload);
    ElMessage.success("测试票已发送到打印机");
  } catch (error: any) {
    ElMessage.error(`测试打印失败：${error.message || error}`);
  }
}
async function executePrint(ticketId: string, reason = "") {
  if (!printAgentOnline.value || !printAgent.value) throw new Error("本机打印服务未连接");
  const { data: task } = await createQueuePrintTaskApi(ticketId, printAgent.value.terminalId, reason);
  try {
    const result = await printQueueTicketLocally(task.payload);
    await completeQueuePrintTaskApi(task.id, result);
    return result;
  } catch (error: any) {
    await completeQueuePrintTaskApi(task.id, {
      status: "FAILED",
      printerName: printAgent.value.printerName,
      errorMessage: error.message || "打印失败"
    });
    throw error;
  }
}
async function reprintCurrentTicket() {
  if (!workspace.value) return;
  try {
    const { value } = await ElMessageBox.prompt("请输入补打原因", `补打 ${workspace.value.ticket.publicNo}`, {
      inputValidator: value => !!value?.trim() || "补打原因不能为空"
    });
    await executePrint(workspace.value.ticket.id, value);
    ElMessage.success("票据已补打");
    await select(workspace.value.ticket.id);
  } catch (error: any) {
    if (error === "cancel" || error === "close") return;
    ElMessage.error(`补打失败：${error.message || error}`);
  }
}
async function issueTicket() {
  if (!issueForm.encounterId) return ElMessage.warning("请选择就诊记录");
  submitting.value = true;
  try {
    const { data } = await issueQueueTicketApi(issueForm.encounterId, issueForm.visitType);
    issueVisible.value = false;
    selectedId.value = data.ticket.id;
    if (data.newlyIssued === false) {
      ElMessage.warning(`该患者本轮已有号码 ${data.ticket.publicNo}，未重复打印；如票据遗失请点击“补打票据”并填写原因`);
      await load();
      return;
    }
    if (printAgentOnline.value) {
      try {
        await executePrint(data.ticket.id);
        ElMessage.success(`已发号并打印：${data.ticket.publicNo}`);
      } catch (error: any) {
        ElMessage.warning(`已发号 ${data.ticket.publicNo}，但打印失败：${error.message || error}`);
      }
    } else {
      ElMessage.warning(`已发号 ${data.ticket.publicNo}，当前电脑未连接本机打印服务，可在安装打印服务后补打`);
    }
    await load();
  } finally {
    submitting.value = false;
  }
}
async function callNext(stage: QueueStage) {
  if (operationPending.value) return ElMessage.info("上一项队列操作正在处理中，请稍候");
  operationPending.value = `call-next:${stage}`;
  try {
    const { data } = await callNextQueueApi(stage);
    selectedId.value = data.ticket.id;
    ElMessage.success(`已叫号 ${data.ticket.publicNo}`);
    await load();
  } catch (error: any) {
    ElMessage.error(error?.message || "叫下一位失败，请刷新后重试");
    await refreshAfterFailure();
  } finally {
    operationPending.value = "";
  }
}
async function taskAction(task: QueueTask, action: string, requireReason = false) {
  if (operationPending.value) return ElMessage.info("上一项队列操作正在处理中，请稍候");
  let reason = "";
  if (requireReason) {
    try {
      reason = (
        await ElMessageBox.prompt("请输入操作原因", "操作确认", { inputValidator: value => !!value?.trim() || "原因不能为空" })
      ).value;
    } catch {
      return;
    }
  }
  if (["cancel", "leave"].includes(action)) {
    try {
      await ElMessageBox.confirm(`此操作会终止本次排队流程，已填写原因：“${reason}”。是否确认继续？`, "高风险操作二次确认", {
        type: "warning",
        confirmButtonText: "确认终止",
        cancelButtonText: "返回检查"
      });
    } catch {
      return;
    }
  }
  operationPending.value = `task:${task.id}:${action}`;
  try {
    const { data } = await runQueueTaskActionApi(task.id, action, reason);
    workspace.value = data;
    selectedId.value = data.ticket.id;
    ElMessage.success(actionSuccessMessage(action));
    await load();
  } catch (error: any) {
    const message = error?.message || "操作失败，请刷新后重试";
    if (action === "complete" && message.includes("病历工作台")) {
      try {
        await ElMessageBox.confirm(`${message}。是否现在前往完成？`, "临床阶段尚未完成", {
          type: "warning",
          confirmButtonText: "进入病历工作台",
          cancelButtonText: "留在排队台"
        });
        if (workspace.value) openEncounter(workspace.value.ticket.encounterId);
      } catch {
        // 用户选择继续留在排队工作台。
      }
    } else {
      ElMessage.error(message);
    }
    await refreshAfterFailure();
  } finally {
    operationPending.value = "";
  }
}
async function roomAction(room: QueueRoom, action: string) {
  if (operationPending.value) return ElMessage.info("上一项队列操作正在处理中，请稍候");
  let reason = "";
  if (action !== "resume") {
    try {
      reason = (
        await ElMessageBox.prompt("请输入房间状态变更原因", room.roomName, {
          inputValidator: value => !!value?.trim() || "原因不能为空"
        })
      ).value;
    } catch {
      return;
    }
  }
  operationPending.value = `room:${room.roomCode}:${action}`;
  try {
    await runQueueRoomActionApi(room.roomCode, action, reason);
    ElMessage.success("房间状态已更新");
    await load();
  } catch (error: any) {
    ElMessage.error(error?.message || "房间状态更新失败，请刷新后重试");
    await refreshAfterFailure();
  } finally {
    operationPending.value = "";
  }
}
function openEncounter(encounterId: string) {
  router.push(`/pre-ai/encounters?id=${encodeURIComponent(encounterId)}`);
}
function visitTypeLabel(value: QueueVisitType) {
  return value === "FOLLOW_UP" ? "复诊" : "初诊";
}
function stageLabel(value: QueueStage) {
  return value === "INSPECTION" ? "检查室" : "接诊室";
}
function roomStatusLabel(value: string) {
  return (
    (
      {
        ACTIVE: "正常服务",
        EMERGENCY_PAUSED: "急症处理中",
        MANUAL_PAUSED: "人工暂停",
        CLOSED: "停诊",
        OFFLINE: "终端离线"
      } as Record<string, string>
    )[value] || value
  );
}
function overallLabel(value: string) {
  return (
    (
      {
        WAITING_INSPECTION: "待检查",
        INSPECTION_CALLED: "检查已叫号",
        INSPECTION_IN_SERVICE: "检查中",
        WAITING_RECEPTION: "待接诊",
        RECEPTION_CALLED: "接诊已叫号",
        RECEPTION_IN_SERVICE: "接诊中",
        COMPLETED: "已完成",
        ON_HOLD: "挂起",
        LEFT: "已离院",
        CANCELLED: "已取消"
      } as Record<string, string>
    )[value] || value
  );
}
function taskStatusLabel(value: string) {
  return (
    (
      {
        INACTIVE: "未激活",
        WAITING: "等候中",
        CALLED: "已叫号",
        ARRIVED: "已到场",
        IN_SERVICE: "办理中",
        COMPLETED: "已完成",
        MISSED: "已过号",
        TEMPORARILY_AWAY: "暂离",
        INTERRUPTED: "急症中断",
        ON_HOLD: "挂起",
        CANCELLED: "已取消"
      } as Record<string, string>
    )[value] || value
  );
}
function statusType(value: string) {
  return value === "COMPLETED"
    ? "success"
    : value.includes("CALLED")
      ? "warning"
      : ["ON_HOLD", "LEFT", "CANCELLED"].includes(value)
        ? "danger"
        : "primary";
}
function taskStatusType(value: string) {
  return value === "COMPLETED"
    ? "success"
    : ["CALLED", "MISSED", "INTERRUPTED"].includes(value)
      ? "warning"
      : ["ON_HOLD", "CANCELLED"].includes(value)
        ? "danger"
        : "primary";
}
onMounted(async () => {
  await Promise.all([load(), refreshPrintAgent()]);
  if (printAgent.value?.terminalId) {
    try {
      await registerQueuePrintTerminalApi({
        terminalId: printAgent.value.terminalId,
        terminalName: printAgent.value.terminalName,
        printerName: printAgent.value.printerName,
        agentVersion: printAgent.value.version
      });
    } catch {
      // 终端心跳登记失败不影响队列查看，实际打印时仍会再次校验。
    }
  }
});
</script>

<style scoped lang="scss">
.queue-page {
  min-height: 100%;
  padding: 22px;
  color: #29453f;
  background: #f4f8f6;
}
.hero-panel,
.room-card,
.list-panel,
.detail-panel,
.metric-card {
  border: 1px solid #dcebe5;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 18px 45px rgba(40, 78, 66, 0.08);
}
.hero-panel {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 28px 32px;
  background: linear-gradient(135deg, #24483f, #3f7565);
  color: white;
}
.hero-panel h1 {
  margin: 5px 0 8px;
  font-size: 30px;
}
.hero-panel p {
  margin: 0;
}
.hero-actions,
.room-actions,
.task-actions,
.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.eyebrow {
  margin: 0;
  color: #79b79e;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}
.hero-panel .eyebrow {
  color: #bfe5d6;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin: 18px 0;
}
.metric-card {
  padding: 20px;
}
.metric-card span,
.metric-card small {
  display: block;
  color: #789089;
}
.metric-card strong {
  display: block;
  margin: 7px 0;
  font-size: 30px;
}
.room-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 18px;
}
.room-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 22px 24px;
  border-left: 6px solid #79b79e;
}
.room-card.emergency_paused {
  border-color: #d95d55;
  background: #fff8f7;
}
.room-card.manual_paused,
.room-card.closed {
  border-color: #d6a44b;
  background: #fffaf1;
}
.room-card h2 {
  margin: 4px 0;
}
.workspace-grid {
  display: grid;
  grid-template-columns: minmax(430px, 0.85fr) minmax(620px, 1.15fr);
  gap: 18px;
}
.list-panel,
.detail-panel {
  min-height: 620px;
  padding: 20px;
}
.panel-heading,
.detail-heading,
.stage-card header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 18px;
}
.panel-heading .el-input {
  width: 260px;
}
.panel-heading h2,
.detail-heading h2 {
  margin: 4px 0;
}
.ticket-list {
  display: grid;
  gap: 10px;
  margin-top: 16px;
  max-height: 720px;
  overflow: auto;
}
.ticket-card {
  width: 100%;
  display: grid;
  grid-template-columns: 92px 1fr auto;
  gap: 14px;
  align-items: center;
  padding: 15px;
  border: 1px solid #e1eee9;
  border-radius: 16px;
  background: #fbfdfc;
  text-align: left;
  color: inherit;
  cursor: pointer;
}
.ticket-card.active {
  border-color: #79b79e;
  background: #edf7f3;
}
.ticket-no b {
  display: block;
  font-size: 27px;
  color: #2e7560;
}
.ticket-no span,
.ticket-main span,
.ticket-main small {
  display: block;
  color: #789089;
}
.ticket-main strong {
  display: block;
  font-size: 17px;
}
.stage-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px;
  margin-top: 18px;
}
.stage-card {
  padding: 18px;
  border: 1px solid #e1eee9;
  border-radius: 18px;
  background: #fbfdfc;
}
.stage-card h3 {
  margin: 4px 0;
}
.time-list {
  display: grid;
  gap: 6px;
  margin: 14px 0;
  color: #607970;
  font-size: 13px;
}
.reason {
  padding: 9px 11px;
  border-radius: 10px;
  background: #edf7f3;
  color: #397663;
}
.reason.danger {
  background: #fff0ed;
  color: #b64d45;
}
.audit-panel {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid #e1eee9;
}
.queue-completion-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-top: 18px;
  padding: 16px 18px;
  border: 1px solid #bfe3d3;
  border-radius: 16px;
  background: #edf8f3;
}
.queue-completion-card strong,
.queue-completion-card span {
  display: block;
}
.queue-completion-card strong {
  color: #256d56;
  font-size: 16px;
}
.queue-completion-card span {
  margin-top: 4px;
  color: #66837a;
  font-size: 13px;
}
.queue-completion-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}
.clinical-action-hint {
  margin: 10px 0 0;
  padding: 10px 12px;
  border: 1px solid #cfe8df;
  border-radius: 10px;
  color: #39735f;
  background: #f1f9f6;
  font-size: 13px;
  line-height: 1.55;
}
.audit-panel small {
  display: block;
  color: #789089;
  margin-top: 4px;
}
.issue-type-hint {
  margin-top: 8px;
  color: #789089;
  font-size: 12px;
}
.printer-form {
  margin-top: 18px;
}
.template-editor-grid {
  display: grid;
  grid-template-columns: 1.1fr 0.9fr;
  gap: 28px;
}
.template-form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.ticket-preview-wrap {
  display: grid;
  align-content: start;
  justify-items: center;
  gap: 10px;
  padding: 20px;
  border-radius: 18px;
  background: #edf3f0;
}
.ticket-preview {
  box-sizing: border-box;
  padding: 18px 14px;
  color: #111;
  background: #fff;
  box-shadow: 0 8px 28px rgb(28 55 46 / 18%);
  text-align: center;
  font-family: "Microsoft YaHei", sans-serif;
}
.ticket-preview.compact {
  padding-top: 10px;
  padding-bottom: 10px;
}
.preview-institution,
.preview-title,
.preview-meta span,
.preview-notice span {
  display: block;
}
.preview-institution {
  font-size: 16px;
}
.preview-title {
  margin-top: 3px;
  font-size: 12px;
}
.preview-number {
  display: block;
  margin: 8px 0;
  padding: 5px 0;
  border-top: 1px dashed #222;
  border-bottom: 1px dashed #222;
  line-height: 1;
}
.preview-meta {
  display: grid;
  gap: 3px;
  text-align: left;
  font-size: 12px;
}
.preview-notice {
  display: grid;
  gap: 2px;
  margin-top: 7px;
  padding-top: 6px;
  border-top: 1px dashed #222;
  font-size: 11px;
}
.ticket-preview-wrap > small {
  color: #789089;
  text-align: center;
}
@media (max-width: 1200px) {
  .workspace-grid {
    grid-template-columns: 1fr;
  }
  .metric-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 760px) {
  .hero-panel,
  .room-card {
    align-items: flex-start;
    flex-direction: column;
    gap: 18px;
  }
  .room-grid,
  .stage-grid,
  .metric-grid {
    grid-template-columns: 1fr;
  }
  .queue-page {
    padding: 12px;
  }
  .ticket-card {
    grid-template-columns: 76px 1fr;
  }
  .ticket-card .el-tag {
    grid-column: 1/-1;
    justify-self: start;
  }
}
</style>
