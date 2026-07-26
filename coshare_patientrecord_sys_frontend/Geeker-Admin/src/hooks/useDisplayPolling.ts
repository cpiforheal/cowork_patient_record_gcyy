import { onBeforeUnmount, onMounted, ref } from "vue";
import { AuthExpiredError } from "@/api/modules/authToken";

export interface DisplayPollingOptions {
  /** 拉取并落地一轮数据；失败时必须抛出异常。 */
  refresh: () => Promise<void>;
  /** 前台正常轮询间隔（秒），后端快照 refreshSeconds 可通过 setIntervalSeconds 覆盖。 */
  defaultIntervalSeconds?: number;
  /** 页面不可见时的降频间隔（秒）。 */
  hiddenIntervalSeconds?: number;
  /** 连续离线超过该秒数后整页重载自愈；0 表示关闭看门狗。 */
  watchdogReloadSeconds?: number;
}

/**
 * 大屏轮询：间隔跟随服务端节奏、失败指数退避、页面不可见降频、
 * 会话失效单独呈现（配合 setAuthRedirectSuppressed，不跳登录页）。
 */
export function useDisplayPolling(options: DisplayPollingOptions) {
  const offline = ref(false);
  const sessionExpired = ref(false);
  const lastUpdated = ref("--:--");
  const offlineSince = ref<number | null>(null);

  const baseInterval = options.defaultIntervalSeconds ?? 5;
  const hiddenInterval = options.hiddenIntervalSeconds ?? 30;
  const watchdogSeconds = options.watchdogReloadSeconds ?? 0;
  let intervalSeconds = baseInterval;
  let failureCount = 0;
  let timer = 0;
  let running = false;
  let disposed = false;

  /** 采用后端下发的刷新节奏（如快照里的 refreshSeconds）。 */
  const setIntervalSeconds = (value?: number) => {
    if (typeof value === "number" && Number.isFinite(value) && value >= 2 && value <= 120) {
      intervalSeconds = value;
    }
  };

  const currentDelayMs = () => {
    if (document.visibilityState !== "visible") return hiddenInterval * 1000;
    if (sessionExpired.value) return 60 * 1000;
    if (failureCount > 0) return Math.min(intervalSeconds * 2 ** failureCount, 60) * 1000;
    return intervalSeconds * 1000;
  };

  const schedule = () => {
    if (disposed) return;
    window.clearTimeout(timer);
    timer = window.setTimeout(() => void run(), currentDelayMs());
  };

  const run = async () => {
    if (disposed) return;
    if (running) return schedule();
    running = true;
    try {
      await options.refresh();
      failureCount = 0;
      offline.value = false;
      sessionExpired.value = false;
      offlineSince.value = null;
      lastUpdated.value = new Date().toLocaleTimeString("zh-CN", { hour12: false });
    } catch (error) {
      failureCount += 1;
      if (error instanceof AuthExpiredError) {
        // 会话失效不算网络离线：展示引导页并保持低频重试，管理员重新登录后自动恢复。
        sessionExpired.value = true;
        offline.value = false;
        offlineSince.value = null;
      } else {
        offline.value = true;
        if (offlineSince.value === null) offlineSince.value = Date.now();
        if (watchdogSeconds > 0 && Date.now() - offlineSince.value > watchdogSeconds * 1000) {
          window.location.reload();
          return;
        }
      }
    } finally {
      running = false;
      schedule();
    }
  };

  /** 立即刷新一轮（叫号广播、页面回到前台等场景）。 */
  const refreshNow = () => {
    window.clearTimeout(timer);
    void run();
  };

  const handleVisibilityChange = () => {
    if (document.visibilityState === "visible") refreshNow();
  };

  onMounted(() => {
    void run();
    document.addEventListener("visibilitychange", handleVisibilityChange);
    window.addEventListener("focus", handleVisibilityChange);
  });
  onBeforeUnmount(() => {
    disposed = true;
    window.clearTimeout(timer);
    document.removeEventListener("visibilitychange", handleVisibilityChange);
    window.removeEventListener("focus", handleVisibilityChange);
  });

  return { offline, sessionExpired, lastUpdated, offlineSince, refreshNow, setIntervalSeconds };
}
