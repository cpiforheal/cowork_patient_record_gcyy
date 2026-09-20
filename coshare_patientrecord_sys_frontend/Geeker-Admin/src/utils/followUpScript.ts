import { ref } from "vue";
import { clinicFetch, parseClinicApiResponse } from "@/api/modules/clinic/http";
import { authHeaders } from "@/api/modules/authToken";
import type { FollowUpVisit } from "@/api/modules/clinic/followUp";

/** 话术模板条目：key 为随访节点，value 为模板文本（含占位符） */
export type FollowUpScriptTemplate = Record<string, string>;

export type ScriptContext = {
  patientName?: string;
  gender?: string;
  department?: string;
  userName?: string;
};

export type FollowUpScriptSection = {
  title: string;

  body: string;
};

/**
 * 话术模板运行时数据源（照病种模板库 diseaseTemplateSource 模式）：
 * 内置默认兜底，挂载时接口热加载覆盖——管理页保存即生效（无缓存）。
 */
export const followUpScriptSource = ref<FollowUpScriptTemplate | null>(null);

export const loadFollowUpScriptTemplates = async () => {
  try {
    const result = await clinicFetch("/follow-up-script-templates", { headers: authHeaders() });
    const list = await parseClinicApiResponse<{ id: string; label: string; content: string; status: string; nodeKey: string }[]>(result);
    if (Array.isArray(list) && list.length) {
      const mapped: FollowUpScriptTemplate = {};
      list
        .filter(item => item.status === "ACTIVE")
        .forEach(item => {
          mapped[item.nodeKey || item.id] = item.content;
        });
      if (Object.keys(mapped).length) followUpScriptSource.value = mapped;
    }
  } catch {
    // 接口失败时保留内置兜底
  }
};

/** 内置默认话术模板（护理部审核稿的机器可读版），字典「随访话术」缺失时兜底 */
export const DEFAULT_FOLLOW_UP_SCRIPT_TEMPLATES: FollowUpScriptTemplate = {
  默认随访: [
    "【开场白】您好，请问是{{患者尊称}}本人吗？我是{{科室}}的责任护士，我姓{{护士姓名}}。今天给您打电话是想了解一下您上次复诊后恢复的情况。为保障您的安全，提醒您一下：我不会在电话里索要任何验证码、密码，或让您转账，如有疑问您可以挂断后拨打我院总机核实后再联系我。",
    "【病情询问】{{患者尊称}}，您现在恢复得怎么样？伤口有没有疼痛或者渗液？按 0 到 10 分打分，大概是几分？日常起居还顺利吗？",
    "【异常分支·发现疼痛加重/渗液/出血时使用】别紧张，您说的这个情况我们记录下来了，我先不做判断，医生建议您尽快回来让大夫当面看一下，我们好及时处理。您看明天上午或下午哪个时间方便，我帮您和医生确认好时间。",
    "【指导内容】{{健康指导}}。另外{{医生叮嘱}}。",
    "【结尾预约】{{患者尊称}}，按您的恢复计划，本次复诊是{{复诊原因}}，请您在{{下次复查}}过来。最近几天饮食清淡一些、多喝水、避免久坐。谢谢您配合，有任何情况随时给我留言。"
  ].join("\n\n")
};

/** 姓氏尊称：姓 + 先生/女士，性别缺失时降级为全名 */
export const buildPatientSalutation = (patientName?: string, gender?: string): string => {
  const name = String(patientName || "").trim();

  if (!name) return "";

  if (gender?.includes("男")) return `${name.slice(0, 1)}先生`;
  if (gender?.includes("女")) return `${name.slice(0, 1)}女士`;
  return name;
};

const buildScriptVariables = (visit: FollowUpVisit, context: ScriptContext): Record<string, string> => ({
  患者尊称: buildPatientSalutation(context.patientName, context.gender),
  复诊原因: visit.reason || "",
  健康指导: visit.conditionNote || "",
  医生叮嘱: visit.conditionNote || "",
  下次复查: visit.nextReviewDate || "",
  护士姓名: context.userName || "",
  科室: context.department || ""
});

const isExceptionSection = (paragraph: string) => paragraph.startsWith("【异常分支");

/**
 * 按复诊记录渲染话术全文。
 * - 模板优先取字典「随访话术」（templates 参数），未配置时用内置默认模板；
 * - 每段替换占位符，空值段落整句跳过；异常分支段仅在文本中省略，供护士按需口头使用。
 */
export const renderFollowUpScript = (
  visit: FollowUpVisit,
  context: ScriptContext,
  templates?: FollowUpScriptTemplate
): FollowUpScriptSection[] => {
  const source = templates || followUpScriptSource.value || DEFAULT_FOLLOW_UP_SCRIPT_TEMPLATES;
  const template = source["默认随访"] || source["default"] || DEFAULT_FOLLOW_UP_SCRIPT_TEMPLATES["默认随访"];
  const variables = buildScriptVariables(visit, context);

  return template
    .split(/\n{2,}/)
    .map(paragraph => paragraph.trim())
    .filter(Boolean)
    .map(paragraph => {
      const filled = paragraph.replace(/\{\{([^}]+)\}\}/g, (_match, rawKey: string) => variables[String(rawKey).trim()] ?? "");
      return { paragraph, filled };
    })
    .filter(({ paragraph, filled }) => {
      if (isExceptionSection(paragraph)) return false;
      if (paragraph.includes("{{健康指导}}") && !variables["健康指导"]) return false;
      if (paragraph.includes("{{复诊原因}}") && !variables["复诊原因"]) return false;
      if (paragraph.includes("{{下次复查}}") && !variables["下次复查"]) return false;
      return Boolean(filled);
    })
    .map(({ filled }) => {
      const titleMatch = filled.match(/^【([^】]+)】/);
      return {
        title: titleMatch ? titleMatch[1] : "",
        body: filled.replace(/^【[^】]+】/, "").trim()
      };
    });
};
