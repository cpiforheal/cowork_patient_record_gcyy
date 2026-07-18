import { ElMessage } from "element-plus";

type CsvColumn = { key: string; title: string };

const csvColumnsByFile: Record<string, CsvColumn[]> = {
  "inventory-risk.csv": [
    { key: "type", title: "类型" },
    { key: "subject", title: "对象" },
    { key: "department", title: "科室" },
    { key: "status", title: "状态" },
    { key: "suggestion", title: "处理建议" }
  ],
  "inventory-stock.csv": [
    { key: "name", title: "物资" },
    { key: "category", title: "分类" },
    { key: "spec", title: "规格" },
    { key: "unit", title: "单位" },
    { key: "stock", title: "当前库存" },
    { key: "lowStockThreshold", title: "预警线" },
    { key: "location", title: "位置" }
  ],
  "inventory-trace.csv": [
    { key: "createdAt", title: "时间" },
    { key: "typeLabel", title: "类型" },
    { key: "itemName", title: "物资" },
    { key: "quantity", title: "数量变化" },
    { key: "department", title: "科室" },
    { key: "operator", title: "操作人" },
    { key: "reason", title: "原因" },
    { key: "relatedId", title: "关联单号" }
  ],
  "inventory-weekly-report.csv": [
    { key: "section", title: "模块" },
    { key: "metric", title: "指标" },
    { key: "value", title: "数值" },
    { key: "note", title: "说明" }
  ]
};

export const exportCsv = (rows: Record<string, unknown>[], fileName: string) => {
  if (!rows.length) return ElMessage.warning("暂无可导出的数据");
  const columns = csvColumnsByFile[fileName] || Object.keys(rows[0]).map(key => ({ key, title: key }));
  const content = [
    columns.map(column => column.title).join(","),
    ...rows.map(row => columns.map(column => `"${String(row[column.key] ?? "").replace(/"/g, '""')}"`).join(","))
  ].join("\n");
  const blob = new Blob([`\uFEFF${content}`], { type: "text/csv;charset=utf-8" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(link.href);
};
