import { computed, onBeforeUnmount, onMounted, ref, type Ref } from "vue";
import { ElNotification } from "element-plus";
import type { EyeDropItem } from "@/api/modules/clinic/eyeDrop";

const ACTIVE_START = 7 * 60;
const ACTIVE_END = 23 * 60 + 30;
const REPEAT_MS = 10 * 60 * 1000;

const minutesOf = (value: string) => {
  const [hour, minute] = value.split(":").map(Number);
  return (hour || 0) * 60 + (minute || 0);
};

export const useEyeDropReminder = (items: Ref<EyeDropItem[]>) => {
  const now = ref(new Date());
  const dueItems = ref<EyeDropItem[]>([]);
  const notifiedAt = new Map<string, number>();
  let timer: number | undefined;

  const withinActiveHours = (minutes: number) => minutes >= ACTIVE_START && minutes <= ACTIVE_END;

  const tick = () => {
    now.value = new Date();
    const currentMinutes = now.value.getHours() * 60 + now.value.getMinutes();
    const due = items.value.filter(
      item =>
        (item.state === "DUE" || item.state === "LATE") &&
        currentMinutes >= minutesOf(item.plannedTime) - 5
    );
    dueItems.value = withinActiveHours(currentMinutes) ? due : [];
    if (!withinActiveHours(currentMinutes)) return;
    const timestamp = Date.now();
    for (const item of due) {
      const key = `${item.planId}-${item.plannedTime}`;
      const last = notifiedAt.get(key) || 0;
      if (timestamp - last < REPEAT_MS) continue;
      notifiedAt.set(key, timestamp);
      ElNotification({
        title: "该滴眼药水了",
        message: `${item.drugName} · ${item.slotLabel} ${item.plannedTime}`,
        type: "warning",
        duration: 8000,
        position: "top-right"
      });
    }
    const activeKeys = new Set(due.map(item => `${item.planId}-${item.plannedTime}`));
    for (const key of [...notifiedAt.keys()]) {
      if (!activeKeys.has(key)) notifiedAt.delete(key);
    }
  };

  onMounted(() => {
    tick();
    timer = window.setInterval(tick, 30_000);
  });
  onBeforeUnmount(() => {
    if (timer) window.clearInterval(timer);
  });

  return { dueItems: computed(() => dueItems.value), now };
};
