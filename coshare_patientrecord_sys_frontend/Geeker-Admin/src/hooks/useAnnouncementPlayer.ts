import { computed, onBeforeUnmount, onMounted, ref, type Ref } from "vue";
import { speakAiSummaryApi } from "@/api/modules/clinic/ai";

export interface DisplayAnnouncement {
  id: string;
  content: string;
}

export interface AnnouncementPlayerOptions {
  /** 音频解锁状态的 localStorage 键（断电重启后仍然记住已解锁）。 */
  unlockStorageKey: string;
  /** 播报成功后的回执。失败不阻塞大屏，本页用 played 集合防重播。 */
  markPlayed: (id: string) => Promise<unknown>;
  /** 播报前后触发一轮数据刷新。 */
  requestRefresh: () => void;
  /** 叫号弹窗停留时长（毫秒）。 */
  popupDurationMs?: number;
  /** 同一条播报的朗读遍数（老年患者反应时间长，建议 2 遍）。 */
  repeat?: number;
  /** 工作台跨窗口叫号通知：BroadcastChannel 频道名与消息类型。 */
  channelName?: string;
  channelMessageType?: string;
  /** BroadcastChannel 不可用时的 localStorage 事件键。 */
  storageEventKey?: string;
  /** 手动开启语音成功后的确认播报文本。 */
  confirmationText?: string;
}

const SPEECH_TIMEOUT = 10000;
const REPEAT_GAP_MS = 1500;

/**
 * 大屏叫号播报：豆包 TTS → 浏览器语音合成降级、played 去重、
 * 弹窗节奏与跨窗口叫号广播统一在此维护。
 *
 * 音频状态机：unknown（加载后静默试解锁）→ unlocked / blocked（显示"开启语音"按钮）。
 * kiosk 模式（--autoplay-policy=no-user-gesture-required）下无需任何人工点击。
 */
export function useAnnouncementPlayer<T extends DisplayAnnouncement>(options: AnnouncementPlayerOptions) {
  const audioState: Ref<"unknown" | "unlocked" | "blocked"> = ref("unknown");
  const audioBlocked = computed(() => audioState.value === "blocked");
  const audioEnabling = ref(false);
  const currentCall = ref<T>();
  const played = new Set<string>();
  const popupDurationMs = options.popupDurationMs ?? 3800;
  const repeat = Math.max(1, options.repeat ?? 1);
  let callBusy = false;
  let overlayTimer = 0;
  let audio: HTMLAudioElement | undefined;
  let deferredCall: T | undefined;
  let callChannel: BroadcastChannel | undefined;

  /** 从刷新循环里投喂下一条未播报项。 */
  const playIfNew = (item?: T) => {
    if (!item || played.has(item.id) || callBusy) return;
    void playAnnouncement(item);
  };

  const hasPlayed = (id: string) => played.has(id);

  async function playAnnouncement(item: T) {
    callBusy = true;
    currentCall.value = item;
    played.add(item.id);
    window.clearTimeout(overlayTimer);
    overlayTimer = window.setTimeout(() => {
      if (currentCall.value?.id === item.id) currentCall.value = undefined;
    }, popupDurationMs);

    let spoken = false;
    try {
      if (audioState.value === "blocked") throw new Error("audio interaction required");
      for (let round = 0; round < repeat; round++) {
        if (round > 0) await delay(REPEAT_GAP_MS);
        await speakOnce(item.content);
      }
      spoken = true;
    } catch {
      deferredCall = item;
      audioState.value = "blocked";
    } finally {
      if (spoken) {
        deferredCall = undefined;
        audioState.value = "unlocked";
        persistUnlocked();
        try {
          await options.markPlayed(item.id);
        } catch {
          // 播放确认失败不阻塞大屏，本页通过 played 集合避免重复播报。
        }
      }
      callBusy = false;
      options.requestRefresh();
    }
  }

  async function speakOnce(text: string) {
    try {
      const { data } = await speakAiSummaryApi({ text });
      if (data.audioBase64) return await playBase64(data.audioBase64, data.mimeType || "audio/mpeg");
      return await browserSpeak(text);
    } catch {
      return await browserSpeak(text);
    }
  }

  async function playBase64(base64: string, mime: string) {
    const binary = window.atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
    const url = URL.createObjectURL(new Blob([bytes], { type: mime }));
    audio?.pause();
    audio = new Audio(url);
    audio.preload = "auto";
    try {
      await audio.play();
      await new Promise<void>((resolve, reject) => {
        if (!audio) return resolve();
        const timeout = window.setTimeout(() => reject(new Error("audio timeout")), 15000);
        audio.onended = () => {
          window.clearTimeout(timeout);
          resolve();
        };
        audio.onerror = () => {
          window.clearTimeout(timeout);
          reject(new Error("audio failed"));
        };
      });
    } finally {
      URL.revokeObjectURL(url);
    }
  }

  function browserSpeak(text: string) {
    return new Promise<void>((resolve, reject) => {
      if (!("speechSynthesis" in window)) return reject(new Error("unsupported"));
      window.speechSynthesis.cancel();
      const utterance = new SpeechSynthesisUtterance(text);
      const voices = window.speechSynthesis.getVoices();
      utterance.voice = voices.find(voice => /zh-CN/i.test(voice.lang)) || voices.find(voice => /^zh/i.test(voice.lang)) || null;
      utterance.lang = "zh-CN";
      utterance.rate = 0.88;
      utterance.pitch = 1;
      utterance.volume = 1;
      const timeout = window.setTimeout(() => {
        window.speechSynthesis.cancel();
        reject(new Error("speech timeout"));
      }, SPEECH_TIMEOUT);
      utterance.onend = () => {
        window.clearTimeout(timeout);
        resolve();
      };
      utterance.onerror = () => {
        window.clearTimeout(timeout);
        reject(new Error("speech failed"));
      };
      window.speechSynthesis.speak(utterance);
      window.setTimeout(() => {
        if (window.speechSynthesis.paused) window.speechSynthesis.resume();
      }, 120);
    });
  }

  async function unlockWebAudio() {
    const safariWindow = window as typeof window & { webkitAudioContext?: typeof AudioContext };
    const AudioContextClass = window.AudioContext || safariWindow.webkitAudioContext;
    if (!AudioContextClass) return;
    const context = new AudioContextClass();
    try {
      if (context.state === "suspended") await context.resume();
      const oscillator = context.createOscillator();
      const gain = context.createGain();
      gain.gain.value = 0.0001;
      oscillator.connect(gain);
      gain.connect(context.destination);
      oscillator.start();
      oscillator.stop(context.currentTime + 0.03);
    } finally {
      window.setTimeout(() => void context.close(), 100);
    }
  }

  /**
   * 页面加载即静默尝试解锁：kiosk 参数生效时直接成功；
   * 普通浏览器下 resume() 会一直挂起，用超时判定为需要人工点击。
   */
  async function attemptSilentUnlock() {
    try {
      await Promise.race([unlockWebAudio(), delay(600).then(() => Promise.reject(new Error("unlock timeout")))]);
      audioState.value = "unlocked";
      persistUnlocked();
    } catch {
      if (audioState.value === "unknown") audioState.value = "blocked";
    }
  }

  async function enableAudio() {
    if (audioEnabling.value) return;
    audioEnabling.value = true;
    try {
      await unlockWebAudio();
      audioState.value = "unlocked";
      persistUnlocked();
      if (options.confirmationText && "speechSynthesis" in window) {
        window.speechSynthesis.cancel();
        const confirmation = new SpeechSynthesisUtterance(options.confirmationText);
        confirmation.lang = "zh-CN";
        confirmation.rate = 0.9;
        confirmation.volume = 1;
        window.speechSynthesis.speak(confirmation);
      }
      const pending = deferredCall;
      deferredCall = undefined;
      if (pending) {
        played.delete(pending.id);
        window.setTimeout(() => void playAnnouncement(pending), 800);
      }
    } catch {
      audioState.value = "blocked";
    } finally {
      audioEnabling.value = false;
    }
  }

  function persistUnlocked() {
    try {
      window.localStorage.setItem(options.unlockStorageKey, "1");
    } catch {
      // 隐私模式等场景下写入失败可忽略，仅影响下次启动是否需要重新解锁。
    }
  }

  const receiveExternal = (item?: T) => {
    if (!item || played.has(item.id) || callBusy) return;
    options.requestRefresh();
    void playAnnouncement(item);
  };

  const handleStorageCall = (event: StorageEvent) => {
    if (options.storageEventKey && event.key === options.storageEventKey && event.newValue) options.requestRefresh();
  };

  const delay = (ms: number) => new Promise<void>(resolve => window.setTimeout(resolve, ms));

  onMounted(() => {
    if (window.localStorage.getItem(options.unlockStorageKey) === "1") {
      audioState.value = "unlocked";
    } else {
      void attemptSilentUnlock();
    }
    if (options.channelName && "BroadcastChannel" in window) {
      callChannel = new BroadcastChannel(options.channelName);
      callChannel.onmessage = event => {
        if (options.channelMessageType && event.data?.type === options.channelMessageType) {
          receiveExternal(event.data.announcement as T);
        }
      };
    }
    if (options.storageEventKey) window.addEventListener("storage", handleStorageCall);
    window.speechSynthesis?.getVoices();
  });

  onBeforeUnmount(() => {
    window.clearTimeout(overlayTimer);
    callChannel?.close();
    if (options.storageEventKey) window.removeEventListener("storage", handleStorageCall);
    audio?.pause();
    window.speechSynthesis?.cancel();
  });

  return { currentCall, audioBlocked, audioEnabling, enableAudio, playIfNew, hasPlayed };
}
