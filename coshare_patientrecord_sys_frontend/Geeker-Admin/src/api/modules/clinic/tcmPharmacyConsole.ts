import { clinicFetch, clinicJsonHeaders, parseClinicApiResponse } from "./http";

export interface TcmHerbShelf {
  shelfNo: number;
  name: string;
  orientation: "H" | "V";
  layerCount: number;
  columnCount: number;
  sortOrder: number;
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface TcmHerbSlot {
  shelfNo: number;
  layerNo: number;
  columnNo: number;
  herbCode: string | null;
  name: string | null;
  alias: string | null;
  pinyin: string | null;
  stock: number;
  warnThreshold: number;
  dataSource: string;
}

export interface TcmHerbStats {
  total: number;
  occupied: number;
  empty: number;
  outOfStock: number;
  lowStock: number;
}

export interface TcmHerbLayout {
  revision: number;
  updatedBy: string;
  updatedAt: string;
  shelves: TcmHerbShelf[];
  slots: TcmHerbSlot[];
  stats: TcmHerbStats;
}

export const getTcmPharmacyConsoleLayoutApi = async () => {
  const response = await clinicFetch("/tcm-pharmacy/console/layout", {
    headers: clinicJsonHeaders()
  });
  return parseClinicApiResponse<TcmHerbLayout>(response);
};

export class TcmHerbMapConflictError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "TcmHerbMapConflictError";
  }
}

export const saveTcmPharmacyConsoleLayoutApi = async (payload: {
  revision: number;
  slots: TcmHerbSlot[];
}) => {
  const response = await clinicFetch("/tcm-pharmacy/console/layout", {
    method: "PUT",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload)
  });
  if (response.status === 409) {
    throw new TcmHerbMapConflictError("地图已被其他管理员修改，请刷新后重试");
  }
  return parseClinicApiResponse<TcmHerbLayout>(response);
};
