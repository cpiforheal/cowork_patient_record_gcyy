import { computed, type ComputedRef, type Ref } from "vue";

import type {
  InventoryBatch,
  InventoryCount,
  InventoryDb,
  InventoryItem,
  InventoryMovement,
  InventoryRequest,
  InventoryRequestLine,
  WeeklyConsumption
} from "@/api/modules/inventory";

type InventoryUserInfo = {
  name?: string;
  department?: string;
};

export type InventoryStockRow = InventoryItem &
  Record<string, unknown> & {
    item: InventoryItem;
    stock: number;
    lowStock: boolean;
  };

export type InventoryBatchRow = InventoryBatch &
  Record<string, unknown> & {
    itemName: string;
    expired: boolean;
    expirySoon: boolean;
  };

export type InventoryRequestRow = InventoryRequest &
  Record<string, unknown> & {
    lines: InventoryRequestLine[];
    itemName: string;
    itemSummary: string;
    itemCount: number;
    unit: string;
  };

export type InventoryWeeklyRow = WeeklyConsumption &
  Record<string, unknown> & {
    itemName: string;
  };

export type InventoryCountRow = InventoryCount &
  Record<string, unknown> & {
    itemName: string;
  };

export type InventoryTraceRow = InventoryMovement &
  Record<string, unknown> & {
    typeLabel: string;
    itemName: string;
  };

type InventoryDerivedRowsOptions = {
  db: Ref<InventoryDb> | ComputedRef<InventoryDb>;
  belongsToDepartment: (department?: string) => boolean;
  itemName: (itemId?: string) => string;
  itemUnit: (itemId?: string) => string;
  movementTypeLabel: (type: string) => string;
};

export const createEmptyInventoryDb = (): InventoryDb => ({
  items: [],
  batches: [],
  requests: [],
  weeklyConsumptions: [],
  counts: [],
  movements: [],
  auditLogs: [],
  summary: {
    itemCount: 0,
    batchCount: 0,
    pendingRequestCount: 0,
    approvedRequestCount: 0,
    lowStockCount: 0,
    expirySoonCount: 0,
    movementCount: 0
  }
});

const padWeek = (value: number) => String(value).padStart(2, "0");

export const useInventoryManage = (userInfo: ComputedRef<InventoryUserInfo>) => {
  const operatorName = computed(() => userInfo.value.name || userInfo.value.department || "当前账号");
  const currentDepartment = computed(() => userInfo.value.department || "");

  const today = () => new Date().toISOString().slice(0, 10);

  const currentWeekNo = () => {
    const date = new Date();
    const utcDate = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()));
    const day = utcDate.getUTCDay() || 7;

    utcDate.setUTCDate(utcDate.getUTCDate() + 4 - day);

    const yearStart = new Date(Date.UTC(utcDate.getUTCFullYear(), 0, 1));
    const week = Math.ceil(((utcDate.getTime() - yearStart.getTime()) / 86400000 + 1) / 7);

    return `${utcDate.getUTCFullYear()}-W${padWeek(week)}`;
  };

  const newRequestLine = () => ({ localId: `line-${Date.now()}-${Math.random()}`, itemId: "", quantity: 0 });

  const createDerivedRows = ({ db, belongsToDepartment, itemName, itemUnit, movementTypeLabel }: InventoryDerivedRowsOptions) => {
    const daysFromToday = (date?: string) => {
      if (!date) return Number.POSITIVE_INFINITY;
      return Math.ceil((new Date(date).getTime() - new Date(today()).getTime()) / 86400000);
    };

    const normalizedRequestLines = (row: InventoryRequest): InventoryRequestLine[] => {
      if (row.lines?.length) {
        return row.lines.map(line => ({
          ...line,
          quantity: Number(line.quantity || 0),
          issuedQuantity: Number(line.issuedQuantity || 0)
        }));
      }
      return [
        {
          id: `${row.id}-legacy-line`,
          itemId: row.itemId,
          quantity: Number(row.quantity || 0),
          issuedQuantity: Number(row.issuedQuantity || 0),
          status: row.status
        }
      ];
    };

    const requestItemSummary = (lines: InventoryRequestLine[]) => {
      const summary = lines
        .slice(0, 3)
        .map(line => `${itemName(line.itemId)} ${line.quantity}${itemUnit(line.itemId)}`)
        .join("、");
      return lines.length > 3 ? `${summary} 等 ${lines.length} 项` : summary;
    };

    const requestLineRemaining = (line: InventoryRequestLine) =>
      Math.max(0, Number(line.quantity || 0) - Number(line.issuedQuantity || 0));

    const requestRemainingLines = (row?: InventoryRequest) =>
      row ? normalizedRequestLines(row).filter(line => requestLineRemaining(line) > 0) : [];

    const batchesByItem = computed(() =>
      db.value.batches.reduce<Record<string, InventoryBatch[]>>((index, batch) => {
        (index[batch.itemId] ||= []).push(batch);
        return index;
      }, {})
    );

    const stockByItem = computed(() =>
      Object.entries(batchesByItem.value).reduce<Record<string, number>>((index, [itemId, batches]) => {
        index[itemId] = batches.reduce((sum, batch) => sum + Number(batch.quantity || 0), 0);
        return index;
      }, {})
    );

    const stockRows = computed<InventoryStockRow[]>(() =>
      db.value.items.map(item => {
        const stock = stockByItem.value[item.id] || 0;
        return {
          ...item,
          item,
          stock,
          lowStock: stock <= Number(item.lowStockThreshold || 0)
        };
      })
    );

    const batchRows = computed<InventoryBatchRow[]>(() =>
      db.value.batches.map(batch => {
        const days = daysFromToday(batch.expiryDate);
        return {
          ...batch,
          itemName: itemName(batch.itemId),
          expired: days < 0,
          expirySoon: days >= 0 && days <= 30
        };
      })
    );

    const requestRows = computed<InventoryRequestRow[]>(() =>
      db.value.requests
        .filter(row => belongsToDepartment(row.department))
        .map(row => {
          const lines = normalizedRequestLines(row);
          return {
            ...row,
            lines,
            itemName: itemName(row.itemId),
            itemSummary: row.itemSummary || requestItemSummary(lines),
            itemCount: row.itemCount || lines.length,
            unit: itemUnit(row.itemId)
          };
        })
    );

    const weeklyRows = computed<InventoryWeeklyRow[]>(() =>
      db.value.weeklyConsumptions
        .filter(row => belongsToDepartment(row.department))
        .map(row => ({
          ...row,
          itemName: itemName(row.itemId)
        }))
    );

    const countRows = computed<InventoryCountRow[]>(() =>
      db.value.counts.map(row => ({
        ...row,
        itemName: itemName(row.itemId)
      }))
    );

    const traceRows = computed<InventoryTraceRow[]>(() =>
      db.value.movements
        .filter(row => belongsToDepartment(row.department))
        .map(row => ({
          ...row,
          typeLabel: movementTypeLabel(row.type),
          itemName: itemName(row.itemId)
        }))
    );

    const lowStockRows = computed(() => stockRows.value.filter(row => row.lowStock));
    const expirySoonRows = computed(() => batchRows.value.filter(row => row.expirySoon));
    const pendingRequestRows = computed(() => requestRows.value.filter(row => row.status === "pending"));
    const approvedRequestRows = computed(() => requestRows.value.filter(row => row.status === "approved"));
    const partiallyIssuedRequestRows = computed(() => requestRows.value.filter(row => row.status === "partially_issued"));
    const issuedRequestRows = computed(() => requestRows.value.filter(row => row.status === "issued"));
    const expiredRows = computed(() => batchRows.value.filter(row => row.expired));
    const countDiffRows = computed(() => countRows.value.filter(row => Number(row.differenceQuantity || 0) !== 0));
    const scrapRows = computed(() => traceRows.value.filter(row => row.type === "scrap"));
    const traceRowsByType = computed(() =>
      traceRows.value.reduce<Record<string, InventoryTraceRow[]>>((index, row) => {
        (index[row.type] ||= []).push(row);
        return index;
      }, {})
    );
    const weeklySummary = computed(() => {
      const departments = new Set<string>();
      let nextWeekTotal = 0;
      let abnormalCount = 0;
      for (const row of weeklyRows.value) {
        if (row.department) departments.add(row.department);
        nextWeekTotal += Number(row.nextWeekQuantity || 0);
        if (row.abnormalReason) abnormalCount += 1;
      }
      return {
        departmentCount: departments.size,
        nextWeekTotal,
        abnormalCount
      };
    });
    const itemFlagCounts = computed(() =>
      db.value.items.reduce(
        (result, row) => {
          if (row.sensitive) result.sensitive += 1;
          if (row.batchRequired) result.batchRequired += 1;
          if (row.expiryRequired) result.expiryRequired += 1;
          return result;
        },
        { sensitive: 0, batchRequired: 0, expiryRequired: 0 }
      )
    );

    return {
      batchesByItem,
      stockByItem,
      stockRows,
      batchRows,
      requestRows,
      weeklyRows,
      countRows,
      traceRows,
      lowStockRows,
      expirySoonRows,
      pendingRequestRows,
      approvedRequestRows,
      partiallyIssuedRequestRows,
      issuedRequestRows,
      expiredRows,
      countDiffRows,
      scrapRows,
      traceRowsByType,
      weeklySummary,
      itemFlagCounts,
      normalizedRequestLines,
      requestItemSummary,
      requestLineRemaining,
      requestRemainingLines
    };
  };

  return {
    operatorName,
    currentDepartment,
    today,
    currentWeekNo,
    newRequestLine,
    createDerivedRows
  };
};
