<template>
  <div class="rk">
    <header class="rk-head">
      <h2>复查预约登记</h2>
      <el-radio-group v-model="mode" size="small">
        <el-radio-button value="recent">近期</el-radio-button>
        <el-radio-button value="history">历史回看</el-radio-button>
      </el-radio-group>
    </header>

    <!-- 汇总条：今日待到 / 逾期未补标 / 未来预约 / 到院率 -->
    <nav v-if="mode === 'recent'" class="rk-metrics" aria-label="复查汇总">
      <button type="button" class="rk-metric" @click="scrollToBlock('today')">
        <span>今日待到</span>
        <strong :class="{ 'is-warn': todayPending > 0 }">{{ todayPending }}</strong>
      </button>
      <button
        type="button"
        class="rk-metric"
        :class="{ 'is-alert': pendingPast.length > 0 }"
        :disabled="!pendingPast.length"
        @click="scrollToBlock('pending')"
      >
        <span>逾期未补标</span>
        <strong>{{ pendingPast.length }}</strong>
      </button>
      <div class="rk-metric">
        <span>未来 {{ FUTURE_DAYS }} 天预约</span>
        <strong>{{ upcomingTotal }}</strong>
      </div>
      <div class="rk-metric">
        <span>区间到院率</span>
        <strong>{{ board?.total.arrivalRate != null ? `${board.total.arrivalRate}%` : "—" }}</strong>
      </div>
    </nav>

    <div class="rk-layout">
      <div class="rk-main">
        <!-- 登记：多行表格，每行独立日期，支持粘贴多个姓名 -->
        <EntryForm v-if="canEdit" ref="entryForm" :today="today" :counts="counts" @saved="reload" />

        <!-- 近期：今天 → 待补标 → 接下来（只列有登记的日期） -->
        <div v-if="mode === 'recent'" v-loading="loading" class="rk-body">
          <section ref="todayBlockEl" class="rk-block rk-today" aria-label="今天">
            <h3>
              <span>今天 {{ dateTitle(today, today) }}</span>
              <span class="rk-count">
                预计 <b>{{ todayDay?.stats.planned ?? 0 }}</b> 人 · 已到 <b class="c-ok">{{ todayDay?.stats.arrived ?? 0 }}</b> ·
                没来 <b class="c-no">{{ todayDay?.stats.absent ?? 0 }}</b> · 待到 <b>{{ todayDay?.stats.pending ?? 0 }}</b>
              </span>
            </h3>
            <ul v-if="todayEntries.length" class="rk-list">
              <EntryRow
                v-for="e in todayEntries"
                :key="e.id"
                :entry="e"
                :can-edit="canEdit"
                markable
                @mark="mark"
                @action="act"
              />
            </ul>
            <p v-else class="rk-empty">今天没有登记复查的患者</p>
          </section>

          <section v-if="pendingPast.length" ref="pendingBlockEl" class="rk-block rk-pending" aria-label="待补标">
            <h3>
              <span>以前的还没标到/没来</span>
              <span class="rk-count">{{ pendingPast.length }} 人，请核对后补标</span>
            </h3>
            <ul class="rk-list">
              <EntryRow
                v-for="e in pendingPast"
                :key="e.id"
                :entry="e"
                :can-edit="canEdit"
                markable
                show-date
                @mark="mark"
                @action="act"
              />
            </ul>
          </section>

          <section class="rk-block" aria-label="接下来">
            <h3>
              <span>接下来</span>
              <span class="rk-count">未来 {{ FUTURE_DAYS }} 天共 {{ upcomingTotal }} 人</span>
            </h3>
            <template v-if="upcoming.length">
              <div v-for="day in upcoming" :key="day.date" class="rk-day">
                <div class="rk-day-title">
                  {{ dateTitle(day.date, today) }}<span>{{ day.stats.planned }} 人</span>
                </div>
                <ul class="rk-list">
                  <EntryRow
                    v-for="e in visible(day.entries)"
                    :key="e.id"
                    :entry="e"
                    :can-edit="canEdit"
                    :markable="false"
                    @action="act"
                  />
                </ul>
              </div>
            </template>
            <p v-else class="rk-empty">暂无后续复查登记</p>
          </section>
        </div>

        <!-- 历史回看：按日期倒序，每天一行汇总 + 名单 -->
        <div v-else v-loading="loading" class="rk-body">
          <div class="rk-hist-bar">
            <el-date-picker
              v-model="histRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              :clearable="false"
              style="width: 260px"
              @change="loadHistory"
            />
            <span v-if="history" class="rk-count">
              预计 <b>{{ history.total.planned }}</b> 人 · 已到 <b class="c-ok">{{ history.total.arrived }}</b> · 没来
              <b class="c-no">{{ history.total.absent }}</b>
              <template v-if="history.total.arrivalRate != null"> · 到诊率 {{ history.total.arrivalRate }}%</template>
            </span>
          </div>
          <section class="rk-block">
            <template v-if="historyDays.length">
              <div v-for="day in historyDays" :key="day.date" class="rk-day">
                <div class="rk-day-title">
                  {{ dateTitle(day.date, today) }}
                  <span>
                    预计 {{ day.stats.planned }} · 到 <b class="c-ok">{{ day.stats.arrived }}</b> · 没来
                    <b class="c-no">{{ day.stats.absent }}</b>
                    <template v-if="day.stats.pending"> · 未标 {{ day.stats.pending }}</template>
                  </span>
                </div>
                <ul class="rk-list">
                  <EntryRow
                    v-for="e in day.entries"
                    :key="e.id"
                    :entry="e"
                    :can-edit="canEdit"
                    :markable="day.relation !== 'future'"
                    @mark="mark"
                    @action="act"
                  />
                </ul>
              </div>
            </template>
            <p v-else class="rk-empty">这段时间没有登记</p>
          </section>
        </div>
      </div>

      <!-- 右侧：月历概览，悬停看当天名单，点击看明细 -->
      <aside class="rk-aside">
        <MiniCalendar
          v-model:month="calMonth"
          :today="today"
          :days="calBoard?.days ?? []"
          :can-edit="canEdit"
          :loading="calLoading"
          @register="registerOn"
        />
      </aside>
    </div>

    <!-- 改期 -->
    <el-dialog v-model="resched.visible" title="改期" width="440px" destroy-on-close>
      <p class="rk-dialog-tip">
        {{ resched.entry?.patientName }}，原定 {{ resched.entry && dateTitle(resched.entry.planDate, today) }}
      </p>
      <div class="rk-add-date">
        <button
          v-for="quick in RESCHEDULE_OFFSETS"
          :key="quick.days"
          type="button"
          class="rk-quick"
          :class="{ on: resched.newDate === offsetDate(quick.days) }"
          @click="setReschedDate(offsetDate(quick.days))"
        >
          {{ quick.label }}
        </button>
      </div>
      <el-form label-width="64px" style="margin-top: 12px" @submit.prevent>
        <el-form-item label="新日期">
          <PlanDatePicker
            :model-value="resched.newDate"
            :today="today"
            :counts="counts"
            width="160px"
            aria-label="改期后日期"
            @update:model-value="setReschedDate"
          />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="resched.reason" maxlength="200" placeholder="选填，如：患者来电改约" />
        </el-form-item>
      </el-form>
      <el-checkbox v-model="resched.earlyArrival" @change="onEarlyArrivalChange">
        患者已提前到院：改到今天并自动标记到院
      </el-checkbox>
      <template #footer>
        <el-button @click="resched.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitReschedule">改到 {{ resched.newDate.slice(5) }}</el-button>
      </template>
    </el-dialog>

    <!-- 修改信息 -->
    <el-dialog v-model="edit.visible" title="修改登记" width="440px" destroy-on-close>
      <el-form label-width="64px" @submit.prevent>
        <el-form-item label="日期">
          <PlanDatePicker
            v-if="edit.entry?.status === 'PLANNED'"
            v-model="edit.planDate"
            :today="today"
            :counts="counts"
            width="160px"
          />
          <span v-else>{{ dateTitle(edit.planDate, today) }}（已标记，日期不可改）</span>
        </el-form-item>
        <el-form-item label="姓名"><el-input v-model="edit.patientName" maxlength="50" /></el-form-item>
        <el-form-item label="电话"><el-input v-model="edit.phone" maxlength="30" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="edit.note" maxlength="200" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="edit.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="recheckSchedule">
import { computed, onMounted, reactive, ref, watch } from "vue";
import dayjs from "dayjs";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  cancelRecheckApi,
  getRecheckBoardApi,
  markRecheckApi,
  rescheduleRecheckApi,
  updateRecheckApi,
  type RecheckBoard,
  type RecheckEntry
} from "@/api/modules/clinic/recheckSchedule";
import EntryRow, { type RowAction } from "./EntryRow.vue";
import EntryForm from "./EntryForm.vue";
import MiniCalendar from "./MiniCalendar.vue";
import PlanDatePicker from "./PlanDatePicker.vue";
import { RESCHEDULE_OFFSETS, dateTitle, fmt, monthGrid } from "./dates";

/** 近期视图的加载窗口：往前 30 天找漏标，往后 60 天看预约（后端单次上限 92 天） */
const PAST_DAYS = 30;
const FUTURE_DAYS = 60;

const mode = ref<"recent" | "history">("recent");
const loading = ref(false);
const saving = ref(false);
const board = ref<RecheckBoard | null>(null);
const history = ref<RecheckBoard | null>(null);
const histRange = ref<[string, string]>([fmt(dayjs().subtract(30, "day")), fmt(dayjs())]);

const today = computed(() => board.value?.today ?? fmt(dayjs()));
const canEdit = computed(() => (board.value ?? history.value)?.canEdit === true);
const offsetDate = (days: number) => fmt(dayjs(today.value).add(days, "day"));

/** 已改期的条目排到当天最后，其余保持登记顺序（标记后不跳位） */
const visible = (entries: RecheckEntry[]) => [
  ...entries.filter(e => e.status !== "RESCHEDULED"),
  ...entries.filter(e => e.status === "RESCHEDULED")
];

const todayDay = computed(() => board.value?.days.find(d => d.date === today.value));
const todayEntries = computed(() => visible(todayDay.value?.entries ?? []));
const todayPending = computed(() => todayDay.value?.stats.pending ?? 0);
const pendingPast = computed(() =>
  (board.value?.days ?? []).filter(d => d.relation === "past").flatMap(d => d.entries.filter(e => e.status === "PLANNED"))
);

/** 汇总条点击滚动定位 */
const todayBlockEl = ref<HTMLElement | null>(null);
const pendingBlockEl = ref<HTMLElement | null>(null);
const scrollToBlock = (target: "today" | "pending") => {
  const el = target === "today" ? todayBlockEl.value : pendingBlockEl.value;
  el?.scrollIntoView?.({ behavior: "smooth", block: "start" });
};
const upcoming = computed(() => (board.value?.days ?? []).filter(d => d.relation === "future" && d.entries.length > 0));
const upcomingTotal = computed(() => upcoming.value.reduce((sum, d) => sum + d.stats.planned, 0));
const historyDays = computed(() => [...(history.value?.days ?? [])].reverse().filter(d => d.entries.length > 0));

const loadRecent = async () => {
  loading.value = true;
  try {
    board.value = await getRecheckBoardApi(fmt(dayjs().subtract(PAST_DAYS, "day")), fmt(dayjs().add(FUTURE_DAYS, "day")));
  } catch (error: any) {
    ElMessage.error(error?.message || "加载失败");
  } finally {
    loading.value = false;
  }
};

const loadHistory = async () => {
  loading.value = true;
  try {
    history.value = await getRecheckBoardApi(histRange.value[0], histRange.value[1]);
  } catch (error: any) {
    ElMessage.error(error?.message || "加载失败");
  } finally {
    loading.value = false;
  }
};

// ---------- 右侧月历 ----------

const calMonth = ref(dayjs().startOf("month").format("YYYY-MM-DD"));
const calBoard = ref<RecheckBoard | null>(null);
const calLoading = ref(false);

const loadCalendar = async () => {
  const grid = monthGrid(calMonth.value);
  calLoading.value = true;
  try {
    calBoard.value = await getRecheckBoardApi(grid[0], grid[grid.length - 1]);
  } catch (error: any) {
    ElMessage.error(error?.message || "日历加载失败");
  } finally {
    calLoading.value = false;
  }
};
watch(calMonth, () => void loadCalendar());

/** 日期 → 有效登记人数（已改期走的不算），供日期选择器角标 */
const counts = computed(() => {
  const map: Record<string, number> = {};
  for (const src of [board.value, history.value, calBoard.value]) {
    for (const day of src?.days ?? []) map[day.date] = day.entries.filter(e => e.status !== "RESCHEDULED").length;
  }
  return map;
});

const entryForm = ref<InstanceType<typeof EntryForm> | null>(null);
const registerOn = (date: string) => {
  entryForm.value?.addForDate(date);
  entryForm.value?.$el?.scrollIntoView?.({ behavior: "smooth", block: "nearest" });
};

const reload = () => Promise.all([mode.value === "recent" ? loadRecent() : loadHistory(), loadCalendar()]);

watch(mode, value => {
  if (value === "history") void loadHistory();
  if (value === "recent") void loadRecent();
});

/** 统一执行写操作：成功提示 + 刷新；用户取消弹窗时静默 */
const run = async (task: () => Promise<unknown>, success: string) => {
  saving.value = true;
  try {
    await task();
    ElMessage.success(success);
    await reload();
    return true;
  } catch (error: any) {
    if (error !== "cancel" && error !== "close") ElMessage.error(error?.message || "操作失败");
    return false;
  } finally {
    saving.value = false;
  }
};

// ---------- 标记 / 改期 / 修改 / 撤销 ----------

const mark = (entry: RecheckEntry, status: "ARRIVED" | "ABSENT" | "PLANNED") => {
  const text = status === "ARRIVED" ? "已到" : status === "ABSENT" ? "没来" : "已撤回为待到";
  void run(() => markRecheckApi(entry.id, status), `${entry.patientName}：${text}`);
};

const resched = reactive({ visible: false, entry: null as RecheckEntry | null, newDate: "", reason: "", earlyArrival: false });
const edit = reactive({ visible: false, entry: null as RecheckEntry | null, planDate: "", patientName: "", phone: "", note: "" });

/** 改期日期统一入口：勾选"提前到院"后手动改到其他日期时自动取消勾选 */
const setReschedDate = (value: string) => {
  resched.newDate = value;
  if (resched.earlyArrival && value !== today.value) resched.earlyArrival = false;
};
const onEarlyArrivalChange = (value: unknown) => {
  if (value === true) resched.newDate = today.value;
};

const act = (entry: RecheckEntry, action: RowAction) => {
  if (action === "reschedule") {
    Object.assign(resched, { visible: true, entry, newDate: fmt(dayjs(entry.planDate).add(7, "day")), reason: "", earlyArrival: false });
  } else if (action === "edit") {
    Object.assign(edit, {
      visible: true,
      entry,
      planDate: entry.planDate,
      patientName: entry.patientName,
      phone: entry.phone,
      note: entry.note
    });
  } else {
    void run(async () => {
      await ElMessageBox.confirm(`撤销 ${entry.patientName}（${entry.planDate}）这条登记？撤销后不计入统计。`, "撤销登记", {
        type: "warning",
        confirmButtonText: "撤销",
        cancelButtonText: "不撤销"
      });
      await cancelRecheckApi(entry.id);
    }, "已撤销");
  }
};

const submitReschedule = async () => {
  const entry = resched.entry;
  if (!entry || !resched.newDate) return ElMessage.warning("请选择新日期");
  if (resched.newDate === entry.planDate) return ElMessage.warning("新日期与原日期相同，无需改期");
  const ok = await run(
    () => rescheduleRecheckApi(entry.id, resched.newDate, resched.reason.trim()),
    `${entry.patientName} 已改到 ${dateTitle(resched.newDate, today.value)}`
  );
  // 提前到院：改期刷新后按 sourceId 找到带出的新条目，自动标记到院
  if (ok && resched.earlyArrival) {
    const moved = (board.value?.days ?? [])
      .flatMap(d => d.entries)
      .find(e => e.sourceId === entry.id && e.status === "PLANNED");
    if (moved) await run(() => markRecheckApi(moved.id, "ARRIVED"), `${moved.patientName} 已标记到院`);
  }
  if (ok) resched.visible = false;
};

const submitEdit = async () => {
  const entry = edit.entry;
  if (!entry) return;
  if (!edit.patientName.trim()) return ElMessage.warning("请填写患者姓名");
  const ok = await run(
    () =>
      updateRecheckApi(entry.id, {
        planDate: edit.planDate,
        patientName: edit.patientName.trim(),
        phone: edit.phone.trim(),
        note: edit.note.trim()
      }),
    "已保存"
  );
  if (ok) edit.visible = false;
};

onMounted(() => {
  void loadRecent();
  void loadCalendar();
});
</script>

<style scoped lang="scss">
.rk {
  --brand: #0f766e;

  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 1440px;
  padding: 16px;
}
.rk-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 16px;
  align-items: start;
}
.rk-main {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}
.rk-aside {
  position: sticky;
  top: 12px;
}
.rk-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  h2 {
    margin: 0;
    font-size: 18px;
  }
}
// 汇总条：今日待到 / 逾期未补标 / 未来预约 / 到院率
.rk-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.rk-metric {
  display: grid;
  flex: 1;
  gap: 3px;
  min-width: 130px;
  padding: 10px 14px;
  text-align: left;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  transition:
    border-color var(--motion-control, 180ms) var(--ease-out, ease),
    box-shadow var(--motion-control, 180ms) var(--ease-out, ease),
    transform var(--motion-control, 180ms) var(--ease-out, ease);

  span {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  strong {
    font-size: 22px;
    font-weight: 700;
    line-height: 1.1;
    color: var(--el-text-color-primary);
    font-variant-numeric: tabular-nums;
  }

  strong.is-warn {
    color: #b45309;
  }

  &.is-alert {
    border-color: color-mix(in srgb, #b45309 45%, var(--el-border-color-lighter));

    strong {
      color: #b45309;
    }
  }

  &:not(:disabled) {
    cursor: pointer;

    @media (hover: hover) and (pointer: fine) {
      &:hover {
        border-color: color-mix(in srgb, #0f766e 40%, var(--el-border-color-lighter));
        box-shadow: 0 8px 20px color-mix(in srgb, #0f766e 10%, transparent);
        transform: translateY(-1px);
      }
    }

    &:active {
      transform: translateY(0);
    }
  }

  &:disabled {
    cursor: default;
  }
}
@media (prefers-reduced-motion: reduce) {
  .rk-metric {
    transition: none;
  }
}
.rk-add-date {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
.rk-quick {
  height: 28px;
  padding: 0 12px;
  font: inherit;
  font-size: 13px;
  color: var(--el-text-color-regular);
  cursor: pointer;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 14px;
  transition:
    background-color 150ms ease,
    border-color 150ms ease,
    color 150ms ease;
  &.on {
    color: #ffffff;
    background: var(--brand);
    border-color: var(--brand);
  }
  &:focus-visible {
    outline: 2px solid var(--brand);
    outline-offset: 2px;
  }
}
.rk-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.rk-block {
  overflow: hidden;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  h3 {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    align-items: baseline;
    justify-content: space-between;
    padding: 10px 14px;
    margin: 0;
    font-size: 15px;
    background: var(--el-fill-color-light);
  }
}
.rk-today {
  border-color: color-mix(in srgb, var(--brand) 35%, transparent);
  h3 {
    font-size: 16px;
    color: var(--brand);
    background: color-mix(in srgb, var(--brand) 8%, var(--el-bg-color));
  }
}
.rk-pending {
  border-color: color-mix(in srgb, #b45309 35%, transparent);
  h3 {
    color: #b45309;
    background: color-mix(in srgb, #b45309 7%, var(--el-bg-color));
  }
}
.rk-count {
  font-size: 13px;
  font-weight: 400;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-regular);
  b {
    font-size: 15px;
  }
}
.c-ok {
  color: #15803d;
}
.c-no {
  color: #b45309;
}
.rk-list {
  padding: 0;
  margin: 0;
  list-style: none;
}
.rk-day + .rk-day {
  border-top: 1px solid var(--el-border-color-light);
}
.rk-day-title {
  display: flex;
  gap: 12px;
  align-items: baseline;
  padding: 8px 14px 2px;
  font-size: 14px;
  font-weight: 600;
  span {
    font-size: 12px;
    font-weight: 400;
    color: var(--el-text-color-secondary);
  }
}
.rk-empty {
  padding: 18px 14px;
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.rk-hist-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: center;
}
.rk-dialog-tip {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

@media (width <= 1180px) {
  .rk-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .rk-aside {
    position: static;
    order: -1;
  }
}
</style>
