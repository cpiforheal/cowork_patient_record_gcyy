import { pinyin } from "pinyin-pro";

export interface BulkItemOption {
  itemId: string;
  name: string;
  spec?: string;
  issueUnit?: string;
  baseUnit?: string;
  conversionFactor?: number;
  batchRequired?: boolean;
  expiryRequired?: boolean;
}

export interface ParsedBulkRow {
  raw: string;
  name: string;
  spec: string;
  quantity: number;
  batchNo: string;
  expiryDate: string;
  item?: BulkItemOption;
  candidates?: BulkItemOption[];
  error?: string;
}

const HEADER_PATTERN = /名称|物资|耗材|数量/;

export const normalizeBulkText = (value: string) => String(value || "").trim().replace(/\s+/g, " ").toLowerCase();

const splitColumns = (line: string) => {
  if (line.includes("\t")) return line.split("\t").map(part => part.trim());
  if (/[，,;；]/.test(line)) return line.split(/[，,;；]/).map(part => part.trim());
  const match = line.match(/^(.+?)\s+(\d+(?:\.\d+)?)\s*(.*)$/);
  if (!match) return [line.trim()];
  const rest = match[3].trim().split(/\s+/).filter(Boolean);
  return [match[1].trim(), match[2], ...rest];
};

const splitNameSpec = (value: string) => {
  const match = value.match(/^(.+?)[（(]([^）)]*)[）)]\s*$/);
  return match ? { name: match[1].trim(), spec: match[2].trim() } : { name: value.trim(), spec: "" };
};

export const parseBulkText = (text: string, withBatch = false): ParsedBulkRow[] => {
  const rows: ParsedBulkRow[] = [];
  const lines = String(text || "").split(/\r?\n/).map(line => line.trim()).filter(Boolean);
  for (const line of lines) {
    if (!rows.length && HEADER_PATTERN.test(line) && /数量/.test(line)) continue;
    const columns = splitColumns(line);
    if (!columns[0]) continue;
    const parsed = splitNameSpec(columns[0]);
    const quantity = Number(String(columns[1] || "").replace(/[^\d.]/g, ""));
    if (!parsed.name || !Number.isFinite(quantity) || quantity <= 0) {
      rows.push({
        raw: line, name: parsed.name || columns[0], spec: parsed.spec, quantity: 0,
        batchNo: "", expiryDate: "", error: "缺少物资名称或数量"
      });
      continue;
    }
    rows.push({
      raw: line,
      name: parsed.name,
      spec: parsed.spec,
      quantity,
      batchNo: withBatch ? String(columns[2] || "").trim() : "",
      expiryDate: withBatch ? String(columns[3] || "").trim() : ""
    });
  }
  return rows;
};

const matchesName = (row: ParsedBulkRow, item: BulkItemOption) => {
  const rowName = normalizeBulkText(row.name);
  const itemName = normalizeBulkText(item.name);
  if (rowName === itemName || rowName === normalizeBulkText(`${item.name}（${item.spec || ""}）`)) return true;
  if (row.spec) return rowName === itemName && normalizeBulkText(row.spec) === normalizeBulkText(item.spec || "");
  return false;
};

export const matchBulkRows = (rows: ParsedBulkRow[], items: BulkItemOption[]): ParsedBulkRow[] =>
  rows.map(row => {
    if (row.error) return row;
    const nameMatches = items.filter(item => matchesName(row, item));
    const exact = row.spec
      ? nameMatches.filter(item => normalizeBulkText(item.spec || "") === normalizeBulkText(row.spec))
      : nameMatches;
    if (exact.length === 1) return { ...row, item: exact[0] };
    if (exact.length > 1) return { ...row, candidates: exact, error: "存在多个同名规格，请选择" };
    if (row.spec && nameMatches.length) return { ...row, error: "规格不匹配，请检查名称或规格" };
    const initials = pinyin(row.name, { pattern: "first", toneType: "none" }).toLowerCase().replace(/\s+/g, "");
    const pinyinMatches = items.filter(item =>
      pinyin(item.name, { pattern: "first", toneType: "none" }).toLowerCase().replace(/\s+/g, "") === initials
    );
    if (pinyinMatches.length === 1) return { ...row, item: pinyinMatches[0] };
    if (pinyinMatches.length > 1) return { ...row, candidates: pinyinMatches, error: "匹配到多个物资，请选择" };
    return { ...row, error: "没有匹配到物资" };
  });

export const conversionFactor = (item?: BulkItemOption) => {
  const factor = Number(item?.conversionFactor || 1);
  return Number.isFinite(factor) && factor > 0 ? factor : 1;
};
