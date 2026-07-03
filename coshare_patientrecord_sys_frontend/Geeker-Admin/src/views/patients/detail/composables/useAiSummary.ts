import { computed, ref, unref, type ComputedRef } from "vue";

import { ElMessage } from "element-plus";

import { generateRecordAiSummaryApi, speakAiSummaryApi, type AiRecordSummary } from "@/api/modules/clinic";

type UseAiSummaryOptions = {
  patientId: ComputedRef<string>;
  fieldValues: Record<string, string>;
};

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

export const useAiSummary = ({ patientId, fieldValues }: UseAiSummaryOptions) => {
  const visible = ref(false);
  const loading = ref(false);
  const speaking = ref(false);
  const summary = ref<AiRecordSummary>();

  let audio: HTMLAudioElement | undefined;
  let audioUrl = "";
  let speechStoppedManually = false;

  const formatText = () => {
    if (!summary.value) return "";

    const numbered = (items: string[] | undefined) =>
      items?.length ? items.map((item, index) => `${index + 1}. ${item}`).join("\n") : "暂无";

    return [
      `AI健康档案总结（${fieldValues.patientName || "当前患者"} / ${fieldValues.visitNo || unref(patientId)}）`,

      `生成时间：${summary.value.generatedAt}`,

      `模型：${summary.value.model}`,

      "",

      `患者概况：${summary.value.summary}`,

      summary.value.patientPortrait ? `患者画像：${summary.value.patientPortrait}` : "",

      `诊疗摘要：${summary.value.clinicalSummary}`,

      `管理随访摘要：${summary.value.managementSummary}`,

      `复查随访：${summary.value.followupSummary}`,

      "",

      `优先关注：\n${numbered(summary.value.priorityFocus)}`,

      "",

      `容易忽略：\n${numbered(summary.value.overlookedInsights)}`,

      "",

      `缺失/待补充：\n${numbered(summary.value.missingItems)}`,

      "",

      `风险提醒：\n${numbered(summary.value.riskHints)}`,

      "",

      `沟通建议：\n${numbered(summary.value.communicationTips)}`,

      "",

      `下一步随访：\n${numbered(summary.value.nextFollowupSuggestions)}`,

      "",

      `医生提醒：\n${numbered(summary.value.doctorTips)}`,

      "",

      summary.value.disclaimer
    ]

      .filter(Boolean)

      .join("\n");
  };

  const speechText = computed(() => {
    if (!summary.value) return "";

    const numbered = (title: string, items: string[] | undefined) =>
      items?.length ? `${title}。${items.map((item, index) => `${index + 1}、${item}`).join("。")}` : "";

    return [
      `患者${fieldValues.patientName || "当前患者"}的AI健康档案总结。`,

      summary.value.patientPortrait ? `患者画像：${summary.value.patientPortrait}` : "",

      `患者概况：${summary.value.summary}`,

      summary.value.priorityFocus?.length ? numbered("优先关注", summary.value.priorityFocus) : "",

      summary.value.riskHints?.length ? numbered("风险提醒", summary.value.riskHints) : "",

      summary.value.communicationTips?.length ? numbered("沟通建议", summary.value.communicationTips) : "",

      summary.value.nextFollowupSuggestions?.length ? numbered("下一步随访建议", summary.value.nextFollowupSuggestions) : "",

      summary.value.doctorTips?.length ? numbered("医生提醒", summary.value.doctorTips) : ""
    ]

      .filter(Boolean)

      .join("\n")

      .slice(0, 1800);
  });

  const releaseSpeech = () => {
    if (audioUrl) {
      URL.revokeObjectURL(audioUrl);

      audioUrl = "";
    }

    audio = undefined;

    speaking.value = false;
  };

  const stopSpeech = () => {
    speechStoppedManually = true;

    if (audio) {
      audio.onended = null;

      audio.onerror = null;

      audio.pause();
    }

    releaseSpeech();
  };

  const toggleSpeech = async () => {
    if (speaking.value) {
      stopSpeech();

      return;
    }

    if (!summary.value) return;

    const text = speechText.value;

    if (!text.trim()) {
      ElMessage.warning("暂无可朗读的 AI 总结内容");

      return;
    }

    speaking.value = true;

    speechStoppedManually = false;

    try {
      const { data } = await speakAiSummaryApi({ text });

      const blob = base64ToBlob(data.audioBase64, data.mimeType);

      audioUrl = URL.createObjectURL(blob);

      audio = new Audio(audioUrl);

      audio.onended = releaseSpeech;

      audio.onerror = () => {
        releaseSpeech();

        if (!speechStoppedManually) {
          ElMessage.error("语音播放失败，请检查浏览器音频权限或 TTS 配置");
        }
      };

      await audio.play();
    } catch (error) {
      stopSpeech();

      ElMessage.error(error instanceof Error ? error.message : "豆包语音朗读失败");
    }
  };

  const generate = async () => {
    stopSpeech();

    loading.value = true;

    try {
      const { data } = await generateRecordAiSummaryApi({ patientId: unref(patientId), mode: "summary" });

      summary.value = data;

      ElMessage.success("AI总结已生成");
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : "AI总结生成失败");
    } finally {
      loading.value = false;
    }
  };

  const open = () => {
    visible.value = true;

    if (!summary.value) void generate();
  };

  const close = () => {
    stopSpeech();

    visible.value = false;
  };

  const copy = async () => {
    if (!summary.value) return;

    await navigator.clipboard.writeText(formatText());

    ElMessage.success("AI总结已复制");
  };

  return {
    visible,
    loading,
    speaking,
    summary,
    stopSpeech,
    toggleSpeech,
    generate,
    open,
    close,
    copy
  };
};
