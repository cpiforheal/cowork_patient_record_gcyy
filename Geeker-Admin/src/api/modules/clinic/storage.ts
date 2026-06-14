import type { ResultData } from "@/api/interface";
import { createSeedDb, hydrateDb } from "./seed";
import type { ClinicDb } from "./types";

const STORAGE_KEY = "hos-unitywork-clinic-db";
const CLINIC_API_DB_URL = import.meta.env.VITE_CLINIC_API_DB_URL || "/clinic-api/db";

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

export const readDb = async (): Promise<ClinicDb> => {
  try {
    const result = await fetch(CLINIC_API_DB_URL, { method: "GET" });
    if (result.ok) {
      const payload = (await result.json()) as ResultData<ClinicDb>;
      const beforeHydrate = JSON.stringify(payload.data);
      const db = hydrateDb(payload.data);
      cacheDb(db);
      if (beforeHydrate !== JSON.stringify(db)) await writeDb(db);
      return db;
    }

    const seed = readLocalDb();
    await writeDb(seed);
    return seed;
  } catch {
    return readLocalDb();
  }
};

export const writeDb = async (db: ClinicDb) => {
  cacheDb(db);
  try {
    await fetch(CLINIC_API_DB_URL, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(db)
    });
  } catch {
    // 本地 API 未启动时允许继续使用 localStorage，避免影响现场演示。
  }
};
