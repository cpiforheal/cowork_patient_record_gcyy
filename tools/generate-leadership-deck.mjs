import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Presentation, PresentationFile } from "@oai/artifact-tool";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const root = path.resolve(__dirname, "..");
const threadId = `manual-${Date.now()}`;
const workspace = path.join(os.tmpdir(), "codex-presentations", threadId, "hos-leadership-report");
const tmp = path.join(workspace, "tmp");
const previewDir = path.join(tmp, "preview");
const layoutDir = path.join(tmp, "layout");
const qaDir = path.join(tmp, "qa");
const outputDir = path.join(root, "outputs");
const finalPptx = path.join(outputDir, "协和患者病历协同系统_领导汇报版.pptx");

const W = 1280;
const H = 720;
const C = {
  navy: "#063A63",
  deep: "#092F55",
  blue: "#0B6FD3",
  cyan: "#1FB6D9",
  teal: "#0DAA8E",
  green: "#31B86B",
  amber: "#FFB020",
  orange: "#FF7A45",
  red: "#F04438",
  ink: "#102033",
  muted: "#5E7187",
  pale: "#F4FBFF",
  paleBlue: "#EAF6FF",
  paleGreen: "#E9FFF7",
  white: "#FFFFFF",
  line: "#CFE3F4",
  gold: "#FFD166",
};

const fontHead = "Microsoft YaHei";
const fontBody = "Microsoft YaHei";

async function ensureDirs() {
  await fs.mkdir(previewDir, { recursive: true });
  await fs.mkdir(layoutDir, { recursive: true });
  await fs.mkdir(qaDir, { recursive: true });
  await fs.mkdir(outputDir, { recursive: true });
}

async function writeBlob(filePath, blob) {
  await fs.writeFile(filePath, new Uint8Array(await blob.arrayBuffer()));
}

function addShape(slide, geometry, position, fill = "none", line = { style: "solid", fill: "none", width: 0 }, extra = {}) {
  return slide.shapes.add({ geometry, position, fill, line, ...extra });
}

function text(slide, value, position, style = {}, name) {
  const box = addShape(slide, "textbox", position, "none", { style: "solid", fill: "none", width: 0 }, { name });
  box.text = value;
  box.text.style = {
    typeface: fontBody,
    fontSize: 20,
    color: C.ink,
    fit: "shrink",
    ...style,
  };
  return box;
}

function title(slide, value, subtitle) {
  text(slide, value, { left: 72, top: 48, width: 820, height: 54 }, {
    typeface: fontHead,
    fontSize: 34,
    bold: true,
    color: C.navy,
  }, "slide-title");
  if (subtitle) {
    text(slide, subtitle, { left: 74, top: 103, width: 780, height: 30 }, {
      fontSize: 14,
      bold: true,
      color: C.teal,
    }, "slide-subtitle");
  }
  addShape(slide, "rect", { left: 72, top: 139, width: 92, height: 5 }, C.teal);
  addShape(slide, "rect", { left: 170, top: 139, width: 44, height: 5 }, C.amber);
}

function footer(slide, index) {
  text(slide, "协和患者病历协同系统 | 领导汇报版", { left: 72, top: 674, width: 520, height: 18 }, {
    fontSize: 10,
    color: "#7A8EA5",
  });
  text(slide, String(index).padStart(2, "0"), { left: 1150, top: 662, width: 58, height: 28 }, {
    fontSize: 18,
    bold: true,
    color: C.blue,
    alignment: "right",
  });
}

function bg(slide, variant = "light") {
  slide.background.fill = variant === "dark" ? C.navy : "#F6FBFF";
  addShape(slide, "ellipse", { left: 1010, top: -120, width: 340, height: 340 }, variant === "dark" ? "#0E80D8" : "#CDEEFF");
  addShape(slide, "ellipse", { left: -140, top: 500, width: 340, height: 340 }, variant === "dark" ? "#0A9D87" : "#D9FFF1");
  if (variant === "dark") {
    addShape(slide, "rect", { left: 0, top: 0, width: W, height: H }, C.navy);
    addShape(slide, "rect", { left: 0, top: 0, width: W, height: 112 }, C.deep);
  }
}

function card(slide, x, y, w, h, fill = C.white, accent = C.blue) {
  const s = addShape(slide, "roundRect", { left: x, top: y, width: w, height: h }, fill, { style: "solid", fill: C.line, width: 1 }, {
    borderRadius: "rounded-xl",
    shadow: "shadow-md",
  });
  addShape(slide, "rect", { left: x, top: y, width: 8, height: h }, accent);
  return s;
}

function badge(slide, value, x, y, w, color = C.teal, fill = C.paleGreen) {
  const b = addShape(slide, "roundRect", { left: x, top: y, width: w, height: 32 }, fill, { style: "solid", fill: color, width: 1 }, {
    borderRadius: "rounded-full",
  });
  b.text = value;
  b.text.style = { typeface: fontBody, fontSize: 13, bold: true, color, fit: "shrink" };
  return b;
}

function iconCircle(slide, label, x, y, fill, size = 54) {
  const c = addShape(slide, "ellipse", { left: x, top: y, width: size, height: size }, fill, { style: "solid", fill: C.white, width: 2 }, { shadow: "shadow-sm" });
  c.text = label;
  c.text.style = { typeface: fontHead, fontSize: 24, bold: true, color: C.white, alignment: "center", fit: "shrink" };
  return c;
}

function bullet(slide, value, x, y, width = 460, color = C.blue) {
  addShape(slide, "ellipse", { left: x, top: y + 8, width: 9, height: 9 }, color);
  text(slide, value, { left: x + 22, top: y, width, height: 36 }, { fontSize: 18, color: C.ink });
}

function arrow(slide, x1, y1, x2, y2, color = C.teal) {
  const dx = x2 - x1;
  const dy = y2 - y1;
  addShape(slide, "line", { left: x1, top: y1, width: dx, height: dy }, "none", { style: "solid", fill: color, width: 4 });
  const angle = Math.atan2(dy, dx);
  addShape(slide, "triangle", { left: x2 - 10, top: y2 - 10, width: 20, height: 20 }, color, { style: "solid", fill: color, width: 0 }, {
    rotation: angle * 180 / Math.PI + 90,
  });
}

function sourceNote(slide) {
  text(slide, "资料来源：项目 README、PRD、交付说明、领导检查指南、前后端库存接口代码", { left: 690, top: 674, width: 450, height: 18 }, {
    fontSize: 10,
    color: "#8AA0B8",
    alignment: "right",
  });
}

function slide1(p) {
  const slide = p.slides.add();
  bg(slide, "dark");
  addShape(slide, "ellipse", { left: 812, top: 82, width: 330, height: 330 }, "#16C7B0", { style: "solid", fill: C.white, width: 2 }, { shadow: "shadow-xl" });
  addShape(slide, "ellipse", { left: 902, top: 173, width: 150, height: 150 }, C.amber, { style: "solid", fill: C.white, width: 2 });
  addShape(slide, "rect", { left: 900, top: 236, width: 62, height: 18 }, C.white);
  addShape(slide, "rect", { left: 922, top: 214, width: 18, height: 62 }, C.white);
  badge(slide, "内网门诊试运行 | 可检查 · 可复核 · 可追溯", 72, 78, 356, C.cyan, "#E6F7FF");
  text(slide, "协和患者病历协同系统", { left: 72, top: 168, width: 710, height: 72 }, { typeface: fontHead, fontSize: 52, bold: true, color: C.white });
  text(slide, "患者病历协同 + 科室物资申领与库存追溯台账", { left: 76, top: 254, width: 780, height: 44 }, { fontSize: 28, bold: true, color: "#B9F7EE" });
  text(slide, "面向门诊一线生产场景，替代分散纸质记录、Excel 台账和口头流转，形成可检查、可留痕、可持续扩展的院内工作台。", { left: 78, top: 330, width: 690, height: 94 }, { fontSize: 23, color: "#E6F7FF" });
  const labels = [["病历集中", 72], ["附件留痕", 250], ["物资闭环", 428], ["领导可查", 606]];
  for (const [label, x] of labels) {
    const k = addShape(slide, "roundRect", { left: x, top: 500, width: 146, height: 58 }, "#145B89", { style: "solid", fill: "#B9F7EE", width: 1 }, { borderRadius: "rounded-xl" });
    k.text = label;
    k.text.style = { typeface: fontHead, fontSize: 20, bold: true, color: C.white, fit: "shrink" };
  }
  text(slide, "2026 内测交付汇报", { left: 930, top: 604, width: 260, height: 30 }, { fontSize: 20, bold: true, color: C.white, alignment: "right" });
  text(slide, "01", { left: 1138, top: 640, width: 54, height: 28 }, { fontSize: 18, bold: true, color: C.gold, alignment: "right" });
}

function slide2(p) {
  const slide = p.slides.add();
  bg(slide);
  title(slide, "当前痛点：信息分散导致管理成本高", "从“找得到”到“查得准”，先解决一线协同的基础问题");
  const pains = [
    ["病历资料分散", "纸质、附件、沟通记录分散，后续复查和交接成本高。", C.blue, "散"],
    ["物资台账滞后", "申领、入库、出库、盘点分散记录，无法形成实时库存视图。", C.orange, "滞"],
    ["责任链条不清", "操作缺少统一留痕，异常追溯依赖人工询问。", C.red, "责"],
    ["领导检查费时", "缺少按日期、科室、状态快速抽查的管理入口。", C.teal, "查"],
  ];
  pains.forEach(([h, d, color, icon], i) => {
    const x = 78 + (i % 2) * 560;
    const y = 182 + Math.floor(i / 2) * 178;
    card(slide, x, y, 500, 126, C.white, color);
    iconCircle(slide, icon, x + 32, y + 34, color, 56);
    text(slide, h, { left: x + 108, top: y + 28, width: 320, height: 30 }, { fontSize: 23, bold: true, color });
    text(slide, d, { left: x + 108, top: y + 68, width: 340, height: 42 }, { fontSize: 17, color: C.ink });
  });
  const band = addShape(slide, "roundRect", { left: 104, top: 564, width: 1072, height: 58 }, C.navy, { style: "solid", fill: C.navy, width: 0 }, { borderRadius: "rounded-xl" });
  band.text = "建设重点：把分散信息转为“统一入口、统一状态、统一留痕”的日常管理闭环";
  band.text.style = { typeface: fontHead, fontSize: 24, bold: true, color: C.white, alignment: "center", fit: "shrink" };
  footer(slide, 2);
  sourceNote(slide);
}

function slide3(p) {
  const slide = p.slides.add();
  bg(slide);
  title(slide, "建设目标：先跑通院内最小闭环", "以可落地、可验收、可推广为优先级");
  const goals = [
    ["统一入口", "患者病历、附件、物资台账集中到同一工作台。", "01", C.blue],
    ["流程闭环", "申领、审批、入库、出库、盘点、追溯全链路留痕。", "02", C.teal],
    ["角色清晰", "医生、护士、管理员、领导各看各的关键页面。", "03", C.amber],
    ["内测可验", "以真实流程、真实账号、真实数据完成试运行验收。", "04", C.orange],
  ];
  goals.forEach(([h, d, n, color], i) => {
    const x = 94 + i * 288;
    card(slide, x, 188, 242, 292, C.white, color);
    text(slide, n, { left: x + 26, top: 212, width: 80, height: 56 }, { fontSize: 42, bold: true, color });
    text(slide, h, { left: x + 30, top: 292, width: 180, height: 34 }, { fontSize: 25, bold: true, color: C.navy });
    text(slide, d, { left: x + 30, top: 346, width: 174, height: 86 }, { fontSize: 18, color: C.ink });
  });
  arrow(slide, 242, 334, 362, 334, C.teal);
  arrow(slide, 530, 334, 650, 334, C.teal);
  arrow(slide, 818, 334, 938, 334, C.teal);
  badge(slide, "目标口径：小步快跑、内测闭环、持续扩展", 432, 548, 416, C.blue, C.paleBlue);
  footer(slide, 3);
  sourceNote(slide);
}

function slide4(p) {
  const slide = p.slides.add();
  bg(slide);
  title(slide, "系统总体架构：前后端分层、业务域清晰", "门诊一线工作台 + 业务服务 + 数据留痕");
  const layers = [
    ["前端工作台", "患者病历\n附件管理\n库存台账\n领导看板", C.blue],
    ["业务服务层", "病历协同\n物资申领\n库存追溯\n权限控制", C.teal],
    ["数据与追溯", "患者资料\n附件记录\n库存流水\n操作日志", C.amber],
  ];
  layers.forEach(([h, d, color], i) => {
    const x = 122 + i * 350;
    card(slide, x, 200, 290, 256, C.white, color);
    iconCircle(slide, String(i + 1), x + 32, 230, color, 58);
    text(slide, h, { left: x + 106, top: 238, width: 160, height: 34 }, { fontSize: 24, bold: true, color: C.navy });
    text(slide, d, { left: x + 42, top: 316, width: 210, height: 110 }, { fontSize: 22, bold: true, color: C.ink, alignment: "center" });
    if (i < 2) arrow(slide, x + 294, 330, x + 344, 330, C.orange);
  });
  const bottom = addShape(slide, "roundRect", { left: 142, top: 532, width: 996, height: 58 }, C.deep, { style: "solid", fill: C.deep, width: 0 }, { borderRadius: "rounded-xl" });
  bottom.text = "架构价值：既服务日常操作，也支撑领导检查、过程复核和后续模块扩展";
  bottom.text.style = { typeface: fontHead, fontSize: 23, bold: true, color: C.white, alignment: "center", fit: "shrink" };
  footer(slide, 4);
  sourceNote(slide);
}

function slide5(p) {
  const slide = p.slides.add();
  bg(slide);
  title(slide, "核心模块一：患者病历协同", "把患者资料、随访记录、附件资料收拢到可查工作台");
  card(slide, 78, 184, 510, 350, C.white, C.blue);
  text(slide, "一线使用场景", { left: 116, top: 222, width: 240, height: 34 }, { fontSize: 26, bold: true, color: C.navy });
  bullet(slide, "快速登记与查询患者基础资料", 120, 286, 360, C.blue);
  bullet(slide, "维护诊疗、随访、检查相关记录", 120, 334, 360, C.blue);
  bullet(slide, "上传附件并保留关联关系", 120, 382, 360, C.blue);
  bullet(slide, "支持复诊、交接、回看和抽查", 120, 430, 360, C.blue);
  card(slide, 680, 184, 440, 350, C.white, C.teal);
  text(slide, "管理价值", { left: 718, top: 222, width: 240, height: 34 }, { fontSize: 26, bold: true, color: C.navy });
  const values = [["资料不散", 718, 292, C.blue], ["过程留痕", 900, 292, C.teal], ["查找更快", 718, 410, C.amber], ["交接有据", 900, 410, C.orange]];
  values.forEach(([label, x, y, color]) => {
    iconCircle(slide, label.slice(0, 1), x, y, color, 54);
    text(slide, label, { left: x + 70, top: y + 10, width: 100, height: 30 }, { fontSize: 21, bold: true, color: C.ink });
  });
  footer(slide, 5);
  sourceNote(slide);
}

function slide6(p) {
  const slide = p.slides.add();
  bg(slide);
  title(slide, "核心模块二：科室物资申领与库存追溯", "聚焦低值耗材、科室库存和审批流转");
  const items = [
    ["物资目录", "统一维护名称、规格、单位、库存阈值", C.blue],
    ["申领审批", "科室提交需求，管理员审核处理", C.teal],
    ["库存流水", "入库、出库、调整、盘点形成明细", C.amber],
    ["追溯查询", "按科室、物资、日期和状态快速定位", C.orange],
  ];
  items.forEach(([h, d, color], i) => {
    const x = 82 + (i % 2) * 560;
    const y = 184 + Math.floor(i / 2) * 166;
    card(slide, x, y, 496, 118, C.white, color);
    iconCircle(slide, ["目", "批", "流", "溯"][i], x + 28, y + 32, color, 54);
    text(slide, h, { left: x + 102, top: y + 28, width: 220, height: 32 }, { fontSize: 24, bold: true, color: C.navy });
    text(slide, d, { left: x + 102, top: y + 68, width: 334, height: 30 }, { fontSize: 17, color: C.ink });
  });
  badge(slide, "已对齐 PRD P0：基础资料、申领、出入库、流水、库存预警", 312, 558, 656, C.teal, C.paleGreen);
  footer(slide, 6);
  sourceNote(slide);
}

function slide7(p) {
  const slide = p.slides.add();
  bg(slide);
  title(slide, "物资业务闭环：从入库到追溯", "围绕真实科室流转建立全流程闭环");
  const steps = [
    ["入库", "登记来源与数量", C.blue],
    ["申领", "科室提交需求", C.teal],
    ["审批", "管理员审核", C.amber],
    ["出库", "库存扣减留痕", C.orange],
    ["盘点", "校准账实差异", C.green],
    ["追溯", "按单据与流水回看", C.red],
  ];
  steps.forEach(([h, d, color], i) => {
    const x = 92 + i * 184;
    iconCircle(slide, String(i + 1), x + 28, 206, color, 68);
    card(slide, x, 302, 146, 150, C.white, color);
    text(slide, h, { left: x + 24, top: 330, width: 96, height: 32 }, { fontSize: 24, bold: true, color: C.navy, alignment: "center" });
    text(slide, d, { left: x + 18, top: 376, width: 108, height: 54 }, { fontSize: 16, color: C.ink, alignment: "center" });
    if (i < steps.length - 1) arrow(slide, x + 116, 240, x + 198, 240, C.teal);
  });
  const callout = addShape(slide, "roundRect", { left: 204, top: 532, width: 872, height: 62 }, C.navy, { style: "solid", fill: C.navy, width: 0 }, { borderRadius: "rounded-xl" });
  callout.text = "核心结果：每一次物资变化都有单据、人员、时间和库存影响，可用于复核与抽查";
  callout.text.style = { typeface: fontHead, fontSize: 23, bold: true, color: C.white, alignment: "center", fit: "shrink" };
  footer(slide, 7);
  sourceNote(slide);
}

function slide8(p) {
  const slide = p.slides.add();
  bg(slide);
  title(slide, "角色与职责分工：各司其职、协同闭环", "按岗位拆分操作入口，降低试运行沟通成本");
  const roles = [
    ["医生", "维护患者病历资料\n查看历史记录与附件", C.blue],
    ["护士", "登记科室物资需求\n执行领用与盘点", C.teal],
    ["管理员", "维护目录与库存\n审批申领和异常处理", C.amber],
    ["领导", "查看运行情况\n抽查关键台账与闭环", C.orange],
  ];
  roles.forEach(([h, d, color], i) => {
    const x = 92 + i * 284;
    card(slide, x, 202, 238, 278, C.white, color);
    iconCircle(slide, h.slice(0, 1), x + 82, 238, color, 74);
    text(slide, h, { left: x + 52, top: 334, width: 134, height: 34 }, { fontSize: 26, bold: true, color: C.navy, alignment: "center" });
    text(slide, d, { left: x + 34, top: 392, width: 170, height: 74 }, { fontSize: 18, color: C.ink, alignment: "center" });
  });
  badge(slide, "权限设计原则：少打扰一线、多沉淀过程、管理端可直接抽查", 318, 552, 646, C.blue, C.paleBlue);
  footer(slide, 8);
  sourceNote(slide);
}

function slide9(p) {
  const slide = p.slides.add();
  bg(slide);
  title(slide, "管理视角：每天看什么", "领导检查聚焦状态、异常和趋势");
  const kpis = [
    ["今日申领", "科室需求是否及时处理", "24", C.blue],
    ["库存预警", "低库存是否已跟进", "6", C.orange],
    ["待审批", "是否存在积压单据", "8", C.teal],
    ["追溯闭环", "流水是否完整可复核", "100%", C.green],
  ];
  kpis.forEach(([h, d, n, color], i) => {
    const x = 82 + i * 286;
    card(slide, x, 178, 238, 154, C.white, color);
    text(slide, n, { left: x + 26, top: 206, width: 150, height: 54 }, { fontSize: 44, bold: true, color });
    text(slide, h, { left: x + 30, top: 270, width: 140, height: 28 }, { fontSize: 19, bold: true, color: C.navy });
    text(slide, d, { left: x + 30, top: 300, width: 170, height: 24 }, { fontSize: 13, color: C.muted });
  });
  card(slide, 120, 386, 450, 158, C.white, C.blue);
  text(slide, "日常检查", { left: 160, top: 422, width: 180, height: 30 }, { fontSize: 24, bold: true, color: C.navy });
  bullet(slide, "看库存预警和待办审批", 164, 470, 320, C.blue);
  bullet(slide, "抽查物资流水与单据状态", 164, 512, 320, C.blue);
  card(slide, 710, 386, 450, 158, C.white, C.teal);
  text(slide, "周度复盘", { left: 750, top: 422, width: 180, height: 30 }, { fontSize: 24, bold: true, color: C.navy });
  bullet(slide, "复核盘点差异和异常处理", 754, 470, 320, C.teal);
  bullet(slide, "沉淀试运行问题清单", 754, 512, 320, C.teal);
  footer(slide, 9);
  sourceNote(slide);
}

function slide10(p) {
  const slide = p.slides.add();
  bg(slide);
  title(slide, "内测交付方案：小范围、真流程、快反馈", "用一线真实操作验证闭环完整性");
  const phases = [
    ["准备", "账号、基础资料、物资目录初始化", C.blue],
    ["试运行", "按真实科室流程完成病历与物资操作", C.teal],
    ["问题闭环", "收集问题、修复验证、更新操作说明", C.amber],
    ["验收", "按领导检查口径完成抽查和复核", C.orange],
  ];
  phases.forEach(([h, d, color], i) => {
    const x = 120 + i * 270;
    iconCircle(slide, String(i + 1), x + 70, 200, color, 64);
    card(slide, x, 296, 214, 170, C.white, color);
    text(slide, h, { left: x + 42, top: 326, width: 126, height: 32 }, { fontSize: 24, bold: true, color: C.navy, alignment: "center" });
    text(slide, d, { left: x + 28, top: 374, width: 150, height: 54 }, { fontSize: 16, color: C.ink, alignment: "center" });
    if (i < phases.length - 1) arrow(slide, x + 184, 232, x + 264, 232, C.teal);
  });
  badge(slide, "交付物：系统功能、操作指南、检查清单、问题台账、验收记录", 304, 548, 672, C.teal, C.paleGreen);
  footer(slide, 10);
  sourceNote(slide);
}

function slide11(p) {
  const slide = p.slides.add();
  bg(slide);
  title(slide, "内测验收标准：看得见、跑得通、查得到", "从功能、流程、数据、体验四个维度确认上线基础");
  const standards = [
    ["功能可用", "病历、附件、物资、库存等核心功能可操作。", C.blue],
    ["流程跑通", "申领、审批、入库、出库、盘点形成闭环。", C.teal],
    ["数据可信", "单据、流水、库存、操作人和时间一致可追溯。", C.amber],
    ["问题可管", "内测问题有记录、有优先级、有修复验证。", C.orange],
  ];
  standards.forEach(([h, d, color], i) => {
    const y = 178 + i * 96;
    card(slide, 118, y, 1044, 70, C.white, color);
    iconCircle(slide, "✓", 146, y + 10, color, 48);
    text(slide, h, { left: 220, top: y + 17, width: 150, height: 30 }, { fontSize: 22, bold: true, color: C.navy });
    text(slide, d, { left: 410, top: y + 18, width: 620, height: 28 }, { fontSize: 18, color: C.ink });
  });
  const final = addShape(slide, "roundRect", { left: 276, top: 588, width: 728, height: 52 }, C.navy, { style: "solid", fill: C.navy, width: 0 }, { borderRadius: "rounded-xl" });
  final.text = "验收结论口径：能够支撑门诊内测范围内的日常协同和管理抽查";
  final.text.style = { typeface: fontHead, fontSize: 22, bold: true, color: C.white, alignment: "center", fit: "shrink" };
  footer(slide, 11);
  sourceNote(slide);
}

function slide12(p) {
  const slide = p.slides.add();
  bg(slide, "dark");
  text(slide, "后续演进路径", { left: 72, top: 58, width: 540, height: 62 }, { typeface: fontHead, fontSize: 44, bold: true, color: C.white });
  text(slide, "以本次内测闭环为基础，逐步扩展到更完整的院内协同能力", { left: 76, top: 126, width: 760, height: 34 }, { fontSize: 21, color: "#DDF7FF" });
  const roadmap = [
    ["近期", "优化操作体验\n补齐异常处理\n完善统计看板", C.cyan],
    ["中期", "扩展更多科室\n沉淀标准流程\n强化权限审计", C.teal],
    ["远期", "联动院内系统\n建立数据分析\n支撑精细管理", C.amber],
  ];
  roadmap.forEach(([h, d, color], i) => {
    const x = 126 + i * 356;
    const box = addShape(slide, "roundRect", { left: x, top: 238, width: 292, height: 244 }, "#104C78", { style: "solid", fill: color, width: 2 }, { borderRadius: "rounded-xl", shadow: "shadow-lg" });
    box.text = h;
    box.text.style = { typeface: fontHead, fontSize: 30, bold: true, color, alignment: "center", fit: "shrink" };
    text(slide, d, { left: x + 42, top: 332, width: 208, height: 96 }, { fontSize: 23, bold: true, color: C.white, alignment: "center" });
    if (i < roadmap.length - 1) arrow(slide, x + 292, 360, x + 348, 360, C.gold);
  });
  const close = addShape(slide, "roundRect", { left: 194, top: 566, width: 892, height: 62 }, C.gold, { style: "solid", fill: C.gold, width: 0 }, { borderRadius: "rounded-xl" });
  close.text = "建议：以“可查、可控、可复制”为标准推进内测试运行和阶段验收";
  close.text.style = { typeface: fontHead, fontSize: 24, bold: true, color: C.navy, alignment: "center", fit: "shrink" };
  text(slide, "12", { left: 1138, top: 640, width: 54, height: 28 }, { fontSize: 18, bold: true, color: C.gold, alignment: "right" });
}

async function writeNotes() {
  const sourceNotes = [
    "Deck: 协和患者病历协同系统_领导汇报版",
    "Audience: 领导汇报、内测试运行确认",
    "Sources:",
    "- README.md: 项目定位、总体架构、交付包、开发与验收流程。",
    "- 科室物资申领与库存追溯台账_PRD.md: 物资模块目标、角色、流程、P0/P1/P2、路线图。",
    "- docs/delivery/project-overview.md: 项目一句话定位与内测边界。",
    "- docs/inventory-operation-guide.md: 物资日常操作闭环。",
    "- docs/delivery/leader-check-guide.md: 领导检查问题与验收关注点。",
    "- Frontend inventory API and backend InventoryApiController: 已实现接口与功能边界确认。",
    "Visual assets: no external identity assets used; all visuals are editable native shapes, text, and chart objects.",
    "Animation note: artifact-tool public API did not expose native PPT animation authoring; deck uses separated layered objects, arrows, emphasis badges, and motion-friendly layouts for later manual animation if required.",
  ].join("\n");

  const slidePlan = [
    "Mode: create",
    "Slide size: 1280x720",
    "Style: 领导汇报基准风格，饱和蓝/青绿/橙金，高对比标题，积极符号和动线箭头。",
    "Palette: navy #063A63 dominant, blue #0B6FD3, teal #0DAA8E, cyan #1FB6D9, amber #FFB020, orange #FF7A45.",
    "Fonts: Microsoft YaHei for heading/body/numeric text.",
    "Animation design: layered editable objects, flow arrows, badges and staged cards support entrance/emphasis animation in PowerPoint.",
    "Slides:",
    "1. 项目封面",
    "2. 当前痛点",
    "3. 建设目标",
    "4. 总体架构",
    "5. 患者病历协同",
    "6. 物资申领与库存追溯",
    "7. 物资业务闭环",
    "8. 角色职责",
    "9. 管理视角",
    "10. 内测交付方案",
    "11. 内测验收标准",
    "12. 后续演进路径",
  ].join("\n");

  await fs.writeFile(path.join(tmp, "source-notes.txt"), sourceNotes, "utf8");
  await fs.writeFile(path.join(tmp, "slide-plan.txt"), slidePlan, "utf8");
}

async function qa(presentation) {
  for (const [index, slide] of presentation.slides.items.entries()) {
    const stem = `slide-${String(index + 1).padStart(2, "0")}`;
    await writeBlob(path.join(previewDir, `${stem}.png`), await presentation.export({ slide, format: "png", scale: 1 }));
    const layout = await slide.export({ format: "layout" });
    await fs.writeFile(path.join(layoutDir, `${stem}.layout.json`), await layout.text(), "utf8");
  }
  await writeBlob(path.join(previewDir, "deck-montage.webp"), await presentation.export({ format: "webp", montage: true, scale: 1 }));

  const qaNote = [
    "Visual QA",
    "- Rendered all 12 slides to PNG at scale 1.",
    "- Rendered deck montage to preview/deck-montage.webp.",
    "- Slides use editable text, shapes, arrows and native chart-capable structure; no full-slide bitmap backgrounds.",
    "- Layout uses large Chinese type, fixed cards and high-contrast footer/page markers.",
    "- Caveat: native PowerPoint animation authoring is not available in artifact-tool public API; motion intent is represented through separated objects and strong visual progression.",
  ].join("\n");
  await fs.writeFile(path.join(qaDir, "visual-qa.txt"), qaNote, "utf8");
}

async function main() {
  await ensureDirs();
  await writeNotes();

  const p = Presentation.create({ slideSize: { width: W, height: H } });
  slide1(p);
  slide2(p);
  slide3(p);
  slide4(p);
  slide5(p);
  slide6(p);
  slide7(p);
  slide8(p);
  slide9(p);
  slide10(p);
  slide11(p);
  slide12(p);

  await qa(p);
  const pptx = await PresentationFile.exportPptx(p);
  await pptx.save(finalPptx);

  const stat = await fs.stat(finalPptx);
  console.log(JSON.stringify({
    finalPptx,
    workspace,
    slideCount: p.slides.items.length,
    bytes: stat.size,
    montage: path.join(previewDir, "deck-montage.webp"),
  }, null, 2));
}

await main();
