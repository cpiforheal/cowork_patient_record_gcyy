import { ref } from "vue";
import { ElMessage } from "element-plus";
import { speakAiSummaryApi } from "@/api/modules/clinic";

export type AssistantSpeechMessage = {
  id: string;
  content: string;
};

const stripMarkdownForSpeech = (content: string) =>
  content
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/`([^`]+)`/g, "$1")
    .replace(/!\[[^\]]*]\([^)]*\)/g, " ")
    .replace(/\[([^\]]+)]\([^)]*\)/g, "$1")
    .replace(/^#{1,6}\s+/gm, "")
    .replace(/^\s*[-*+]\s+/gm, "")
    .replace(/^\s*\d+\.\s+/gm, "")
    .replace(/[*_~>#|]/g, "")
    .replace(/\r?\n+/g, "。")
    .replace(/\s+/g, " ")
    .trim();

const base64ToBlob = (base64: string, mimeType: string) => {
  const binary = window.atob(base64);
  const chunks: ArrayBuffer[] = [];
  for (let offset = 0; offset < binary.length; offset += 1024) {
    const slice = binary.slice(offset, offset + 1024);
    const bytes = new Uint8Array(slice.length);
    for (let index = 0; index < slice.length; index += 1) {
      bytes[index] = slice.charCodeAt(index);
    }
    chunks.push(bytes.buffer);
  }
  return new Blob(chunks, { type: mimeType || "audio/mpeg" });
};

export const useAssistantSpeech = () => {
  const speakingMessageId = ref("");
  let speechAudio: HTMLAudioElement | undefined;
  let speechAudioUrl = "";
  let speechStoppedManually = false;

  const releaseSpeechAudio = () => {
    if (speechAudioUrl) {
      URL.revokeObjectURL(speechAudioUrl);
      speechAudioUrl = "";
    }
    speechAudio = undefined;
    speakingMessageId.value = "";
  };

  const stopSpeech = () => {
    speechStoppedManually = true;
    if (speechAudio) {
      speechAudio.onended = null;
      speechAudio.onerror = null;
      speechAudio.pause();
    }
    releaseSpeechAudio();
  };

  const toggleSpeech = async (message: AssistantSpeechMessage) => {
    if (speakingMessageId.value === message.id) {
      stopSpeech();
      return;
    }
    const text = stripMarkdownForSpeech(message.content).slice(0, 1800);
    if (!text) {
      ElMessage.warning("暂无可朗读的回答内容");
      return;
    }
    stopSpeech();
    speechStoppedManually = false;
    speakingMessageId.value = message.id;
    try {
      const { data } = await speakAiSummaryApi({ text });
      const blob = base64ToBlob(data.audioBase64, data.mimeType);
      speechAudioUrl = URL.createObjectURL(blob);
      speechAudio = new Audio(speechAudioUrl);
      speechAudio.onended = releaseSpeechAudio;
      speechAudio.onerror = () => {
        releaseSpeechAudio();
        if (!speechStoppedManually) {
          ElMessage.error("语音播放失败，请检查浏览器音频权限或 TTS 配置");
        }
      };
      await speechAudio.play();
    } catch (error) {
      stopSpeech();
      ElMessage.error(error instanceof Error ? error.message : "豆包语音朗读失败");
    }
  };

  return {
    speakingMessageId,
    stopSpeech,
    toggleSpeech
  };
};
