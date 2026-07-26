import { computed, onBeforeUnmount, onMounted, ref } from "vue";

/**
 * 时段问候与关怀提示（非业务元素，缓解工作平台的枯燥感）。
 * 文案集中在此，院方可自行增改；30 秒轮换一条。
 */
const CARE_TIPS: Record<TimeSlot, string[]> = {
  dawn: ["夜班辛苦了，注意保暖", "凌晨时分，让眼睛休息片刻", "低峰时段，可以整理一下今日待办"],
  morning: ["新的一天，先喝杯温水再开始", "交接班信息核对一遍，今天会更顺", "开诊前检查一下打印纸和耗材"],
  forenoon: ["接诊间隙记得起身活动两分钟", "忙碌时也别忘了补充水分", "遇到拿不准的流程，先看操作指引"],
  noon: ["午间小憩十分钟，下午更有精神", "按时吃午饭，肠胃健康从自己做起", "午后复诊高峰前，先清一清待办"],
  afternoon: ["下午容易疲劳，伸个懒腰再继续", "复杂病历不着急，逐项核对更稳妥", "给候诊久的患者一句安抚，胜过十句解释"],
  evening: ["下班前记得交接与锁屏", "今天辛苦了，整理好台面明天更轻松", "离开前确认无未提交的草稿"]
};

type TimeSlot = "dawn" | "morning" | "forenoon" | "noon" | "afternoon" | "evening";

function slotOfHour(hour: number): TimeSlot {
  if (hour < 6) return "dawn";
  if (hour < 9) return "morning";
  if (hour < 12) return "forenoon";
  if (hour < 14) return "noon";
  if (hour < 18) return "afternoon";
  return "evening";
}

function greetingOfHour(hour: number) {
  if (hour < 6) return "凌晨好";
  if (hour < 9) return "早上好";
  if (hour < 12) return "上午好";
  if (hour < 14) return "中午好";
  if (hour < 18) return "下午好";
  return "晚上好";
}

const TIP_ROTATE_MS = 30 * 1000;

export function useGreeting() {
  const now = ref(new Date());
  const tipIndex = ref(0);
  let clockTimer = 0;
  let tipTimer = 0;

  const greeting = computed(() => greetingOfHour(now.value.getHours()));
  const clock = computed(() => now.value.toLocaleTimeString("zh-CN", { hour12: false, hour: "2-digit", minute: "2-digit" }));
  const dateText = computed(() => now.value.toLocaleDateString("zh-CN", { month: "long", day: "numeric", weekday: "long" }));
  const careTip = computed(() => {
    const tips = CARE_TIPS[slotOfHour(now.value.getHours())];
    return tips[tipIndex.value % tips.length];
  });

  onMounted(() => {
    clockTimer = window.setInterval(() => (now.value = new Date()), 1000);
    tipTimer = window.setInterval(() => (tipIndex.value += 1), TIP_ROTATE_MS);
  });
  onBeforeUnmount(() => {
    window.clearInterval(clockTimer);
    window.clearInterval(tipTimer);
  });

  return { greeting, clock, dateText, careTip };
}
