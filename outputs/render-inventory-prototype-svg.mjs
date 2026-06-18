import { createRequire } from "node:module";
import { writeFile } from "node:fs/promises";

const requireFromSharp = createRequire("file:///C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/sharp/package.json");
const sharp = requireFromSharp("sharp");

const width = 1732;
const height = 924;
const outSvg = "E:/CodeRESPOTORITY/hos_refactor/outputs/inventory-executive-prototype.svg";
const outPng = "E:/CodeRESPOTORITY/hos_refactor/outputs/inventory-executive-prototype.png";

const esc = (value) =>
  String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");

const text = (value, x, y, size = 14, fill = "#111827", weight = 400, anchor = "start") =>
  `<text x="${x}" y="${y}" font-size="${size}" fill="${fill}" font-weight="${weight}" text-anchor="${anchor}">${esc(value)}</text>`;

const rect = (x, y, w, h, fill = "#ffffff", stroke = "#e5e7eb", r = 8) =>
  `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" fill="${fill}" stroke="${stroke}"/>`;

const pill = (value, x, y, w, fill, color, stroke = "none") =>
  `<rect x="${x}" y="${y}" width="${w}" height="24" rx="12" fill="${fill}" stroke="${stroke}"/>${text(value, x + w / 2, y + 16, 12, color, 700, "middle")}`;

const line = (x1, y1, x2, y2, color = "#e5e7eb") =>
  `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${color}"/>`;

const tableRows = [
  ["酒精棉片", "35 盒", "21 盒", "偏高", "#fff7e6", "#b7791f"],
  ["一次性注射器", "184 支", "92 支", "正常", "#eaf6ef", "#2f855a"],
  ["医用纱布块", "76 包", "68 包", "正常", "#eaf6ef", "#2f855a"],
  ["留置针", "19 个", "43 个", "偏低", "#fff1f1", "#c92a2a"],
];

const approvals = [
  ["骨科一病区", "高值耗材领用单", "待护士长签字", "18 分钟"],
  ["急诊科", "补货申请单", "待库管复核", "42 分钟"],
  ["ICU", "科室退库单", "待财务确认", "1 小时"],
];

let svg = `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">
  <defs>
    <style>
      text { font-family: "Microsoft YaHei", "PingFang SC", "Segoe UI", Arial, sans-serif; dominant-baseline: auto; }
      .muted { fill: #667085; }
      .faint { fill: #98a2b3; }
      .label { fill: #475467; font-size: 12px; font-weight: 700; }
    </style>
  </defs>
  <rect width="${width}" height="${height}" fill="#f6f8fa"/>

  <rect x="0" y="0" width="64" height="${height}" fill="#ffffff" stroke="#e5e7eb"/>
  <rect x="12" y="12" width="40" height="40" rx="10" fill="#006d68"/>
  ${text("协", 32, 38, 18, "#ffffff", 800, "middle")}
  ${[78, 130, 182, 234, 286, 338].map((y, i) => `
    <rect x="10" y="${y}" width="44" height="40" rx="8" fill="${i === 2 ? "#e7f3f1" : "transparent"}" stroke="${i === 2 ? "#bfdfdc" : "transparent"}"/>
    <circle cx="32" cy="${y + 20}" r="${i === 2 ? 8 : 6}" fill="${i === 2 ? "#006d68" : "#98a2b3"}"/>
  `).join("")}

  <rect x="64" y="0" width="${width - 64}" height="48" fill="#ffffff" stroke="#e5e7eb"/>
  ${text("首页 / 物资管理 / 进销存管理", 86, 30, 13, "#667085")}
  ${["概览", "患者病历", "会诊协同", "进销存管理", "统计分析", "系统设置"].map((v, i) => {
    const x = 320 + i * 96;
    return `${text(v, x, 30, 13, i === 3 ? "#006d68" : "#475467", i === 3 ? 700 : 400)}
      ${i === 3 ? line(x, 47, x + 72, 47, "#006d68") : ""}`;
  }).join("")}
  <circle cx="1600" cy="24" r="14" fill="#edf2f7"/>
  ${text("管", 1600, 29, 12, "#344054", 700, "middle")}
  ${text("管理员", 1624, 29, 13, "#344054", 600)}

  ${rect(86, 66, 1624, 98)}
  ${text("进销存管理 / 领导驾驶舱", 104, 93, 13, "#006d68", 700)}
  ${text("今天物资运行是否安全", 104, 124, 24, "#111827", 800)}
  ${text("聚焦库存、申领、签字、风险闭环四类关键状态，保留原业务入口与操作路径。", 104, 149, 14, "#667085")}
  ${rect(1376, 92, 92, 32, "#ffffff", "#d1d5db", 6)}
  ${text("导出", 1422, 113, 13, "#344054", 700, "middle")}
  ${rect(1478, 92, 96, 32, "#006d68", "#006d68", 6)}
  ${text("新增单据", 1526, 113, 13, "#ffffff", 700, "middle")}
  ${rect(1584, 92, 104, 32, "#ffffff", "#d1d5db", 6)}
  ${text("刷新数据", 1636, 113, 13, "#344054", 700, "middle")}

  ${rect(86, 178, 1624, 54)}
  ${pill("全部", 106, 193, 56, "#006d68", "#ffffff")}
  ${pill("库存预警", 170, 193, 86, "#ffffff", "#475467", "#d1d5db")}
  ${pill("科室申领", 264, 193, 86, "#ffffff", "#475467", "#d1d5db")}
  ${pill("出入库", 358, 193, 72, "#ffffff", "#475467", "#d1d5db")}
  ${pill("待签字", 438, 193, 72, "#ffffff", "#475467", "#d1d5db")}
  ${text("统计范围", 1222, 211, 12, "#667085", 700)}
  ${rect(1290, 189, 132, 30, "#f9fafb", "#d1d5db", 6)}
  ${text("今日 00:00 至今", 1305, 209, 13, "#344054", 600)}
  ${rect(1434, 189, 118, 30, "#f9fafb", "#d1d5db", 6)}
  ${text("全院科室", 1478, 209, 13, "#344054", 600, "middle")}
  ${rect(1564, 189, 124, 30, "#f9fafb", "#d1d5db", 6)}
  ${text("低库存优先", 1626, 209, 13, "#344054", 600, "middle")}

  ${rect(86, 246, 392, 154)}
  ${text("今日红绿灯", 106, 274, 14, "#111827", 800)}
  ${text("基于库存下限、申领时效、签字积压综合判断", 106, 298, 13, "#667085")}
  <circle cx="140" cy="346" r="30" fill="#eaf6ef" stroke="#c8ead5"/>
  <circle cx="140" cy="346" r="14" fill="#2f855a"/>
  ${text("安全", 188, 338, 20, "#2f855a", 800)}
  ${text("仅 2 项需要当班处理", 188, 364, 13, "#667085")}
  ${pill("闭环率 94%", 106, 374, 88, "#eaf6ef", "#2f855a")}
  ${pill("待签字 12", 204, 374, 86, "#fff7e6", "#b7791f")}

  ${rect(494, 246, 392, 154)}
  ${text("紧急风险", 514, 274, 14, "#111827", 800)}
  ${text("影响手术、抢救、基础护理的关键耗材", 514, 298, 13, "#667085")}
  ${text("3", 514, 358, 52, "#c92a2a", 800)}
  ${text("项", 582, 354, 16, "#c92a2a", 700)}
  ${text("留置针、止血纱布、急救包库存接近下限", 514, 382, 13, "#667085")}
  ${pill("立即处理", 760, 362, 86, "#fff1f1", "#c92a2a")}

  ${rect(902, 246, 392, 154)}
  ${text("关注事项", 922, 274, 14, "#111827", 800)}
  ${text("需要科室或库房协同跟进的单据", 922, 298, 13, "#667085")}
  ${text("27", 922, 358, 52, "#111827", 800)}
  ${text("单", 995, 354, 16, "#475467", 700)}
  ${text("其中 8 单超过 30 分钟未更新状态", 922, 382, 13, "#667085")}
  ${pill("待确认", 1168, 362, 76, "#fff7e6", "#b7791f")}

  ${rect(1310, 246, 400, 154)}
  ${text("关键指标", 1330, 274, 14, "#111827", 800)}
  ${[
    ["库存周转", "8.6 天", "#006d68"],
    ["申领完成", "96.2%", "#2f855a"],
    ["异常单据", "5 单", "#c92a2a"],
  ].map((item, i) => {
    const x = 1330 + i * 122;
    return `${text(item[0], x, 318, 12, "#667085", 700)}${text(item[1], x, 354, 25, item[2], 800)}${line(x, 372, x + 86, 372, "#e5e7eb")}`;
  }).join("")}

  ${rect(86, 414, 754, 236)}
  ${text("科室消耗 TOP", 106, 442, 15, "#111827", 800)}
  ${text("按今日出库金额与高频耗材综合排序", 106, 464, 13, "#667085")}
  ${[
    ["急诊科", 86, "#006d68"],
    ["ICU", 74, "#2f855a"],
    ["骨科一病区", 63, "#2563eb"],
    ["手术室", 52, "#b7791f"],
    ["儿科", 41, "#667085"],
  ].map((d, i) => {
    const y = 505 + i * 28;
    return `${text(d[0], 106, y + 5, 13, "#344054", 600)}
      <rect x="220" y="${y - 8}" width="500" height="10" rx="5" fill="#f1f5f9"/>
      <rect x="220" y="${y - 8}" width="${d[1] * 5}" height="10" rx="5" fill="${d[2]}"/>
      ${text(`${d[1]}%`, 742, y + 5, 13, "#667085", 700)}`;
  }).join("")}

  ${rect(856, 414, 854, 236)}
  ${text("物资库存明细", 876, 442, 15, "#111827", 800)}
  ${text("保留原表格字段，增强风险状态可读性", 876, 464, 13, "#667085")}
  ${line(876, 486, 1688, 486)}
  ${["物资名称", "当前库存", "安全库存", "状态"].map((h, i) => text(h, 892 + i * 190, 510, 12, "#667085", 700)).join("")}
  ${tableRows.map((row, r) => {
    const y = 538 + r * 32;
    return `${r > 0 ? line(876, y - 19, 1688, y - 19) : ""}
      ${text(row[0], 892, y, 13, "#111827", 600)}
      ${text(row[1], 1082, y, 13, "#344054")}
      ${text(row[2], 1272, y, 13, "#344054")}
      ${pill(row[3], 1462, y - 17, 54, row[4], row[5])}`;
  }).join("")}

  ${rect(86, 664, 754, 220)}
  ${text("待签字事项", 106, 692, 15, "#111827", 800)}
  ${text("按超时风险排序，减少领导视角下的信息噪声", 106, 714, 13, "#667085")}
  ${approvals.map((row, i) => {
    const y = 744 + i * 42;
    return `${i > 0 ? line(106, y - 19, 820, y - 19) : ""}
      ${text(row[0], 106, y, 13, "#111827", 700)}
      ${text(row[1], 224, y, 13, "#344054")}
      ${text(row[2], 436, y, 13, "#b7791f", 700)}
      ${text(row[3], 720, y, 13, "#667085", 700)}`;
  }).join("")}
  ${rect(106, 842, 120, 30, "#006d68", "#006d68", 6)}
  ${text("进入签字中心", 166, 862, 13, "#ffffff", 700, "middle")}

  ${rect(856, 664, 854, 220)}
  ${text("风险闭环", 876, 692, 15, "#111827", 800)}
  ${text("把原来的分散卡片收敛成可追踪的处理链路", 876, 714, 13, "#667085")}
  ${[
    ["发现", "低库存预警 3 项", "#c92a2a"],
    ["派发", "已通知库房与责任科室", "#b7791f"],
    ["处理", "2 项补货中，1 项待审批", "#2563eb"],
    ["复核", "预计 16:30 前闭环", "#2f855a"],
  ].map((step, i) => {
    const x = 900 + i * 196;
    return `<circle cx="${x}" cy="778" r="18" fill="#ffffff" stroke="${step[2]}" stroke-width="3"/>
      ${text(String(i + 1), x, 783, 13, step[2], 800, "middle")}
      ${i < 3 ? line(x + 22, 778, x + 174, 778, "#d1d5db") : ""}
      ${text(step[0], x - 30, 820, 13, "#111827", 800)}
      ${text(step[1], x - 30, 844, 12, "#667085", 600)}`;
  }).join("")}
</svg>`;

await writeFile(outSvg, svg, "utf8");
await sharp(Buffer.from(svg)).png().toFile(outPng);

console.log(outPng);
