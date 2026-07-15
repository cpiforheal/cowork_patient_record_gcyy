<template>
  <div class="queue-page">
    <header class="hero-panel">
      <div>
        <p class="eyebrow">INSPECTION & RECEPTION QUEUE</p>
        <h1>检查室与接诊室排队叫号</h1>
        <p>前台一次发号、检查完成自动转接诊、复诊加权与初诊防饥饿、房间级急症暂停。</p>
      </div>
      <div class="hero-actions">
        <el-button v-if="canIssue" type="primary" size="large" @click="openIssue">前台发号</el-button>
        <el-button size="large" @click="router.push('/tcm-pharmacy/clinic-queue/display')">打开双区大屏</el-button>
        <el-button size="large" plain @click="load">刷新</el-button>
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
      <article v-for="room in dashboard.rooms" :key="room.roomCode" class="room-card" :class="room.status.toLowerCase()">
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
            :disabled="room.status !== 'ACTIVE'"
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
            <el-button @click="openEncounter(workspace.ticket.encounterId)">进入病历工作台</el-button>
          </div>
          <div class="stage-grid">
            <article v-for="task in workspace.tasks" :key="task.id" class="stage-card">
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
                  v-if="['ARRIVED', 'INTERRUPTED'].includes(task.status) && canOperate(task.stageCode)"
                  type="success"
                  @click="taskAction(task, 'start')"
                  >开始办理</el-button
                >
                <el-button
                  v-if="task.status === 'CALLED' && canOperate(task.stageCode)"
                  type="warning"
                  @click="taskAction(task, 'missed')"
                  >过号</el-button
                >
                <el-button v-if="['WAITING', 'MISSED', 'ARRIVED'].includes(task.status)" @click="taskAction(task, 'away', true)"
                  >暂离</el-button
                >
                <el-button
                  v-if="['TEMPORARILY_AWAY', 'ON_HOLD', 'MISSED'].includes(task.status)"
                  @click="taskAction(task, 'resume')"
                  >恢复排队</el-button
                >
                <el-button
                  v-if="['WAITING', 'CALLED', 'ARRIVED', 'IN_SERVICE', 'INTERRUPTED'].includes(task.status)"
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
                  v-if="['IN_SERVICE', 'ARRIVED', 'ON_HOLD'].includes(task.status) && canOperate(task.stageCode)"
                  type="success"
                  @click="taskAction(task, 'complete')"
                  >完成队列阶段</el-button
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
          <el-select v-model="issueForm.encounterId" filterable placeholder="患者姓名 / 就诊ID" style="width: 100%">
            <el-option
              v-for="item in encounters"
              :key="item.id"
              :value="item.id"
              :label="`${item.patientName} · 第${item.visitNo}次 · ${item.id}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="就诊类型">
          <el-radio-group v-model="issueForm.visitType"
            ><el-radio-button value="FIRST_VISIT">初诊</el-radio-button
            ><el-radio-button value="FOLLOW_UP">复诊</el-radio-button></el-radio-group
          >
        </el-form-item>
        <el-alert title="同一就诊记录重复发号时将返回原号码，不会生成重复排队单。" type="info" :closable="false" />
      </el-form>
      <template #footer
        ><el-button @click="issueVisible = false">取消</el-button
        ><el-button type="primary" :loading="submitting" @click="issueTicket">确认发号</el-button></template
      >
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
  getQueueDashboardApi,
  getQueueWorkspaceApi,
  issueQueueTicketApi,
  runQueueRoomActionApi,
  runQueueTaskActionApi,
  type QueueDashboard,
  type QueueRoom,
  type QueueStage,
  type QueueTask,
  type QueueVisitType,
  type QueueWorkspace
} from "@/api/modules/clinic/clinicQueue";

const router = useRouter();
const userStore = useUserStore();
const role = computed(() => userStore.userInfo.role || "frontdesk");
const loading = ref(false);
const submitting = ref(false);
const keyword = ref("");
const selectedId = ref("");
const workspace = ref<QueueWorkspace>();
const encounters = ref<PreAiEncounterSummary[]>([]);
const issueVisible = ref(false);
const issueForm = reactive<{ encounterId: string; visitType: QueueVisitType }>({ encounterId: "", visitType: "FIRST_VISIT" });
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
async function load() {
  loading.value = true;
  try {
    const { data } = await getQueueDashboardApi(keyword.value);
    Object.assign(dashboard, data);
    if (selectedId.value && dashboard.tickets.some(item => item.id === selectedId.value)) await select(selectedId.value);
    else if (dashboard.tickets[0]) await select(dashboard.tickets[0].id);
    else workspace.value = undefined;
  } finally {
    loading.value = false;
  }
}
async function select(id: string) {
  selectedId.value = id;
  workspace.value = (await getQueueWorkspaceApi(id)).data;
}
async function openIssue() {
  encounters.value = (await getPreAiEncountersApi()).data.list.filter(item => item.status !== "CANCELLED");
  issueForm.encounterId = "";
  issueForm.visitType = "FIRST_VISIT";
  issueVisible.value = true;
}
async function issueTicket() {
  if (!issueForm.encounterId) return ElMessage.warning("请选择就诊记录");
  submitting.value = true;
  try {
    const { data } = await issueQueueTicketApi(issueForm.encounterId, issueForm.visitType);
    issueVisible.value = false;
    selectedId.value = data.ticket.id;
    ElMessage.success(`已发号：${data.ticket.publicNo}`);
    await load();
  } finally {
    submitting.value = false;
  }
}
async function callNext(stage: QueueStage) {
  try {
    const { data } = await callNextQueueApi(stage);
    selectedId.value = data.ticket.id;
    ElMessage.success(`已叫号 ${data.ticket.publicNo}`);
    await load();
  } catch {
    /* 全局请求层提示 */
  }
}
async function taskAction(task: QueueTask, action: string, requireReason = false) {
  let reason = "";
  if (requireReason) {
    try {
      reason = (
        await ElMessageBox.prompt("请输入操作原因", "操作确认", { inputValidator: value => !!value?.trim() || "原因不能为空" })
      ).value;
    } catch {
      return;
    }
  } else if (["cancel", "leave"].includes(action)) {
    try {
      await ElMessageBox.confirm("此操作会终止本次排队流程，是否继续？", "高风险操作", { type: "warning" });
    } catch {
      return;
    }
  }
  try {
    const { data } = await runQueueTaskActionApi(task.id, action, reason);
    workspace.value = data;
    ElMessage.success("操作成功");
    await load();
  } catch {
    /* 全局请求层提示 */
  }
}
async function roomAction(room: QueueRoom, action: string) {
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
  try {
    await runQueueRoomActionApi(room.roomCode, action, reason);
    ElMessage.success("房间状态已更新");
    await load();
  } catch {
    /* 全局请求层提示 */
  }
}
function openEncounter(encounterId: string) {
  router.push(`/encounters/active?id=${encodeURIComponent(encounterId)}`);
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
onMounted(load);
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
.task-actions {
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
.audit-panel small {
  display: block;
  color: #789089;
  margin-top: 4px;
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
