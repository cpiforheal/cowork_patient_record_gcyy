import { computed, ref } from "vue";
import { pinyin } from "pinyin-pro";
import {
  getTcmPharmacyConsoleLayoutApi,
  saveTcmPharmacyConsoleLayoutApi,
  type TcmHerbLayout,
  type TcmHerbShelf,
  type TcmHerbSlot
} from "@/api/modules/clinic/tcmPharmacyConsole";

export interface HerbSearchItem {
  slot: TcmHerbSlot;
  name: string;
  fullPinyin: string;
  initials: string;
  aliases: string[];
}

export interface PrescriptionParseResult {
  matches: HerbSearchItem[];
  unmatched: string[];
}

export interface HerbSlotPatch {
  name: string;
  alias: string;
  pinyin: string;
  stock: number;
  warnThreshold: number;
}

export const slotKeyOf = (slot: Pick<TcmHerbSlot, "shelfNo" | "layerNo" | "columnNo">) =>
  `${slot.shelfNo}-${slot.layerNo}-${slot.columnNo}`;

const cleanPinyin = (value: string) => value.replace(/\s+/g, "").toLowerCase();

const pinyinCache = new Map<string, { full: string; initials: string }>();

const pinyinOf = (name: string, manual?: string | null) => {
  const key = `${name}|${manual || ""}`;
  const cached = pinyinCache.get(key);
  if (cached) return cached;
  const full = cleanPinyin(manual?.trim() || String(pinyin(name, { toneType: "none" })));
  const initials = cleanPinyin(String(pinyin(name, { pattern: "first", toneType: "none" })));
  const value = { full, initials };
  pinyinCache.set(key, value);
  return value;
};

const compareSlots = (a: TcmHerbSlot, b: TcmHerbSlot) =>
  a.shelfNo - b.shelfNo || a.layerNo - b.layerNo || a.columnNo - b.columnNo;

const cloneSlots = (slots: TcmHerbSlot[]) => slots.map(slot => ({ ...slot }));

export const usePharmacyConsole = () => {
  const shelves = ref<TcmHerbShelf[]>([]);
  const slots = ref<TcmHerbSlot[]>([]);
  const revision = ref(1);
  const updatedBy = ref("");
  const updatedAt = ref("");
  const baseline = ref("[]");
  const loading = ref(false);
  const saving = ref(false);
  const error = ref("");
  const editMode = ref(false);
  const searchKeyword = ref("");
  const selectedShelfNo = ref<number | null>(null);
  const selectedKey = ref("");
  const prescriptionCodes = ref<string[]>([]);

  const shelfByNo = computed(() => {
    const map = new Map<number, TcmHerbShelf>();
    for (const shelf of shelves.value) map.set(shelf.shelfNo, shelf);
    return map;
  });

  const slotMap = computed(() => {
    const map = new Map<string, TcmHerbSlot>();
    for (const slot of slots.value) map.set(slotKeyOf(slot), slot);
    return map;
  });

  const slotByCode = computed(() => {
    const map = new Map<string, TcmHerbSlot>();
    for (const slot of slots.value) if (slot.herbCode) map.set(slot.herbCode, slot);
    return map;
  });

  const dirty = computed(() => JSON.stringify(slots.value) !== baseline.value);

  const stats = computed(() => {
    let occupied = 0;
    let outOfStock = 0;
    let lowStock = 0;
    for (const slot of slots.value) {
      if (!slot.herbCode) continue;
      occupied++;
      if (Number(slot.stock || 0) <= 0) outOfStock++;
      else if (Number(slot.stock || 0) <= Number(slot.warnThreshold || 0)) lowStock++;
    }
    return {
      total: slots.value.length,
      occupied,
      empty: slots.value.length - occupied,
      outOfStock,
      lowStock
    };
  });

  const herbIndex = computed<HerbSearchItem[]>(() => {
    const items: HerbSearchItem[] = [];
    for (const slot of slots.value) {
      if (!slot.herbCode || !slot.name) continue;
      const { full, initials } = pinyinOf(slot.name, slot.pinyin);
      items.push({
        slot,
        name: slot.name,
        fullPinyin: full,
        initials,
        aliases: (slot.alias || "")
          .split(/[,，、;；\s]+/)
          .map(alias => alias.trim().toLowerCase())
          .filter(Boolean)
      });
    }
    return items.sort((a, b) => compareSlots(a.slot, b.slot));
  });

  const searchMatches = computed(() => {
    const keyword = searchKeyword.value.trim().toLowerCase();
    if (!keyword) return [];
    const scored: Array<{ item: HerbSearchItem; score: number }> = [];
    for (const item of herbIndex.value) {
      const score = matchScore(item, keyword);
      if (score < 99) scored.push({ item, score });
    }
    return scored
      .sort((a, b) => a.score - b.score || compareSlots(a.item.slot, b.item.slot))
      .map(entry => entry.item);
  });

  const searchResults = computed(() => searchMatches.value.slice(0, 50));
  const searchMatchCount = computed(() => searchMatches.value.length);

  const shelfHitCounts = computed(() => {
    const counts = new Map<number, number>();
    for (const item of searchMatches.value) {
      counts.set(item.slot.shelfNo, (counts.get(item.slot.shelfNo) || 0) + 1);
    }
    return counts;
  });

  const highlightCodes = computed(() => {
    const codes = new Set<string>();
    for (const item of searchMatches.value) {
      if (item.slot.herbCode) codes.add(item.slot.herbCode);
    }
    return codes;
  });

  const prescriptionItems = computed(() => {
    const items: TcmHerbSlot[] = [];
    for (const code of prescriptionCodes.value) {
      const slot = slotByCode.value.get(code);
      if (slot) items.push(slot);
    }
    return items.sort(compareSlots);
  });

  const selectedSlot = computed(() => slotMap.value.get(selectedKey.value) || null);
  const selectedShelf = computed(() =>
    selectedShelfNo.value == null ? null : shelfByNo.value.get(selectedShelfNo.value) || null
  );

  const positionLabel = (slot: TcmHerbSlot) => {
    const shelf = shelfByNo.value.get(slot.shelfNo);
    return `${shelf?.name || `${slot.shelfNo}号架`} ${slot.layerNo}层 ${slot.columnNo}格`;
  };

  const applyLayout = (data: TcmHerbLayout) => {
    shelves.value = data.shelves;
    slots.value = cloneSlots(data.slots);
    revision.value = data.revision;
    updatedBy.value = data.updatedBy || "";
    updatedAt.value = data.updatedAt || "";
    baseline.value = JSON.stringify(slots.value);
  };

  const load = async () => {
    loading.value = true;
    error.value = "";
    try {
      applyLayout(await getTcmPharmacyConsoleLayoutApi());
    } catch (loadError: any) {
      error.value = loadError?.message || "药架地图加载失败";
    } finally {
      loading.value = false;
    }
  };

  const save = async () => {
    saving.value = true;
    try {
      const data = await saveTcmPharmacyConsoleLayoutApi({
        revision: revision.value,
        slots: cloneSlots(slots.value)
      });
      applyLayout(data);
      return true;
    } finally {
      saving.value = false;
    }
  };

  const selectShelf = (shelfNo: number) => {
    selectedShelfNo.value = shelfNo;
  };

  const closeShelf = () => {
    selectedShelfNo.value = null;
    selectedKey.value = "";
  };

  const focusSlot = (slot: TcmHerbSlot) => {
    selectedShelfNo.value = slot.shelfNo;
    selectedKey.value = slotKeyOf(slot);
  };

  const addToPrescription = (code?: string | null) => {
    if (!code || prescriptionCodes.value.includes(code)) return;
    if (prescriptionCodes.value.length >= 100) return;
    prescriptionCodes.value = [...prescriptionCodes.value, code];
  };

  const removeFromPrescription = (code: string) => {
    prescriptionCodes.value = prescriptionCodes.value.filter(item => item !== code);
  };

  const clearPrescription = () => {
    prescriptionCodes.value = [];
  };

  const setPrescription = (matches: HerbSearchItem[]) => {
    const codes: string[] = [];
    for (const item of matches) {
      if (item.slot.herbCode && !codes.includes(item.slot.herbCode)) codes.push(item.slot.herbCode);
    }
    prescriptionCodes.value = codes;
  };

  const parsePrescription = (text: string): PrescriptionParseResult => {
    const tokens: string[] = [];
    const normalized = text
      .replace(/[，、；;]/g, "\n")
      .replace(/[（(][^）)]*[）)]/g, " ")
      .replace(/先煎|后下|包煎|另煎|烊化|冲服|兑服|捣碎|去心|生用|炒制|炙制|打碎|布包/g, " ");
    for (const line of normalized.split(/\r?\n/)) {
      const cleaned = line
        .replace(/^\s*\d+[.、)．]\s*/, "")
        .replace(/\d+(\.\d+)?\s*(g|克|ml|毫升|钱|两|枚|片|粒|包|袋|条|只|对|升|l)(?![a-z])/gi, " ");
      for (const piece of cleaned.split(/\s+/)) {
        const token = piece.trim();
        if (token && !/^\d+(\.\d+)?$/.test(token)) tokens.push(token);
      }
    }

    const matches: HerbSearchItem[] = [];
    const unmatched: string[] = [];
    const seenCodes = new Set<string>();
    for (const token of tokens) {
      const keyword = token.toLowerCase();
      const best = herbIndex.value
        .map(item => ({ item, score: matchScore(item, keyword) }))
        .filter(entry => entry.score <= 4)
        .sort((a, b) => a.score - b.score || compareSlots(a.item.slot, b.item.slot))[0];
      if (!best?.item.slot.herbCode) {
        unmatched.push(token);
        continue;
      }
      if (!seenCodes.has(best.item.slot.herbCode)) {
        seenCodes.add(best.item.slot.herbCode);
        matches.push(best.item);
      }
    }
    return { matches, unmatched };
  };

  const updateSlot = (key: string, patch: HerbSlotPatch) => {
    const slot = slotMap.value.get(key);
    if (!slot) return;
    if (!slot.herbCode) {
      slot.herbCode = `M-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
    }
    slot.name = patch.name.trim();
    slot.alias = patch.alias.trim() || null;
    slot.pinyin = patch.pinyin.trim() || null;
    slot.stock = Number(patch.stock || 0);
    slot.warnThreshold = Number(patch.warnThreshold || 0);
    slot.dataSource = "MANUAL";
  };

  const clearSlot = (key: string) => {
    const slot = slotMap.value.get(key);
    if (!slot) return;
    slot.herbCode = null;
    slot.name = null;
    slot.alias = null;
    slot.pinyin = null;
    slot.stock = 0;
    slot.warnThreshold = 0;
    slot.dataSource = "EMPTY";
  };

  const swapSlots = (fromKey: string, toKey: string) => {
    if (fromKey === toKey) return;
    const from = slotMap.value.get(fromKey);
    const to = slotMap.value.get(toKey);
    if (!from || !to) return;
    const fromFields = herbFields(from);
    const toFields = herbFields(to);
    Object.assign(from, toFields);
    Object.assign(to, fromFields);
  };

  return {
    shelves,
    slots,
    revision,
    updatedBy,
    updatedAt,
    loading,
    saving,
    error,
    editMode,
    searchKeyword,
    selectedShelfNo,
    selectedKey,
    dirty,
    stats,
    shelfByNo,
    slotMap,
    herbIndex,
    searchResults,
    searchMatchCount,
    shelfHitCounts,
    highlightCodes,
    prescriptionItems,
    selectedSlot,
    selectedShelf,
    positionLabel,
    load,
    save,
    selectShelf,
    closeShelf,
    focusSlot,
    addToPrescription,
    removeFromPrescription,
    clearPrescription,
    setPrescription,
    parsePrescription,
    updateSlot,
    clearSlot,
    swapSlots
  };
};

const herbFields = (slot: TcmHerbSlot) => ({
  herbCode: slot.herbCode,
  name: slot.name,
  alias: slot.alias,
  pinyin: slot.pinyin,
  stock: slot.stock,
  warnThreshold: slot.warnThreshold,
  dataSource: slot.dataSource
});

const matchScore = (item: HerbSearchItem, keyword: string) => {
  const name = item.name.toLowerCase();
  if (name === keyword) return 0;
  if (item.aliases.some(alias => alias === keyword)) return 1;
  if (name.startsWith(keyword)) return 2;
  if (item.aliases.some(alias => alias.startsWith(keyword))) return 3;
  if (item.initials === keyword) return 4;
  if (item.initials.startsWith(keyword)) return 5;
  if (item.fullPinyin.startsWith(keyword)) return 6;
  if (name.includes(keyword)) return 7;
  if (item.aliases.some(alias => alias.includes(keyword)) || item.fullPinyin.includes(keyword)) return 8;
  return 99;
};
