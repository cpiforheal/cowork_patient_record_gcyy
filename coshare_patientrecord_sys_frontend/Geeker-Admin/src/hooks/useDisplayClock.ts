import { onBeforeUnmount, onMounted, ref } from "vue";

/**
 * 大屏时钟：以服务器时间校准偏移，本地仅做秒级递增，
 * 避免播放主机（电视盒子）时钟漂移导致大屏时间与现场不一致。
 */
export function useDisplayClock() {
  const clock = ref("");
  const dateText = ref("");
  let offsetMs = 0;
  let timer = 0;

  const tick = () => {
    const now = new Date(Date.now() + offsetMs);
    clock.value = now.toLocaleTimeString("zh-CN", {
      hour12: false,
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit"
    });
    dateText.value = now.toLocaleDateString("zh-CN", {
      year: "numeric",
      month: "long",
      day: "numeric",
      weekday: "long"
    });
  };

  /** 用快照里的 serverTime（格式 yyyy-MM-dd HH:mm:ss，服务器本地时区）校准。 */
  const calibrate = (serverTime?: string) => {
    if (!serverTime) return;
    const parsed = new Date(serverTime.replace(" ", "T")).getTime();
    if (Number.isNaN(parsed)) return;
    offsetMs = parsed - Date.now();
    tick();
  };

  onMounted(() => {
    tick();
    timer = window.setInterval(tick, 1000);
  });
  onBeforeUnmount(() => window.clearInterval(timer));

  return { clock, dateText, calibrate };
}
