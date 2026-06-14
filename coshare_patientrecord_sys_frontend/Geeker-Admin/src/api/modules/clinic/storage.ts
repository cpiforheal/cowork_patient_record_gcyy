import type { ResultData } from "@/api/interface";
import { createSeedDb, hydrateDb } from "./seed";
import type { ClinicDb } from "./types";

const STORAGE_KEY = "hos-unitywork-clinic-db";
const CLINIC_API_DB_URL = import.meta.env.VITE_CLINIC_API_DB_URL || "/clinic-api/db";
const API_UNAVAILABLE_MESSAGE = "本地病历数据服务未连接，请确认 npm run api 正在运行";

const cacheDb = (db: ClinicDb) => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(db));
  } catch {
    localStorage.removeItem(STORAGE_KEY);
  }
};

const readLocalDb = (): ClinicDb => {
  const cached = localStorage.getItem(STORAGE_KEY);
  if (!cached) {
    const seed = createSeedDb();
    cacheDb(seed);
    return seed;
  }
  try {
    const db = hydrateDb(JSON.parse(cached) as ClinicDb);
    cacheDb(db);
    return db;
  } catch {
    const seed = createSeedDb();
    cacheDb(seed);
    return seed;
  }
};

const parseClinicDbResponse = async (result: Response) => {
  const text = await result.text();
  if (!text.trim()) throw new Error(API_UNAVAILABLE_MESSAGE);
  return JSON.parse(text) as ResultData<ClinicDb>;
};

export const readDb = async (): Promise<ClinicDb> => {
  try {
    const result = await fetch(CLINIC_API_DB_URL, { method: "GET" });
    if (result.ok) {
      const payload = await parseClinicDbResponse(result);
      const beforeHydrate = JSON.stringify(payload.data);
      const db = hydrateDb(payload.data);
      cacheDb(db);
      if (beforeHydrate !== JSON.stringify(db)) await writeDb(db);
      return db;
    }

    return readLocalDb();
  } catch {
    return readLocalDb();
  }
};

export const writeDb = async (db: ClinicDb) => {
  cacheDb(db);
  try {
    const result = await fetch(CLINIC_API_DB_URL, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(db)
    });
    if (!result.ok) {
      const text = await result.text();
      console.warn(text || `${API_UNAVAILABLE_MESSAGE}（HTTP ${result.status}）`);
    }
  } catch {
    // 本地 API 未启动时允许继续使用 localStorage，避免影响现场演示。
  }
};
