import type { ResultData } from "@/api/interface";
import { createSeedDb, hydrateDb } from "./seed";
import type { ClinicDb } from "./types";

const STORAGE_KEY = "hos-unitywork-clinic-db";
const CLINIC_API_DB_URL = import.meta.env.VITE_CLINIC_API_DB_URL || "/clinic-api/db";
const API_UNAVAILABLE_MESSAGE =
  "\u672c\u5730\u75c5\u5386\u6570\u636e\u670d\u52a1\u672a\u8fde\u63a5\uff0c\u8bf7\u786e\u8ba4\u540e\u7aef\u670d\u52a1\u6b63\u5728\u8fd0\u884c";
const INVALID_API_DATA_MESSAGE =
  "\u672c\u5730\u75c5\u5386\u6570\u636e\u670d\u52a1\u8fd4\u56de\u5f02\u5e38\uff0c\u8bf7\u91cd\u542f hos_refactor \u540e\u7aef\u540e\u518d\u8bd5";

const isObjectRecord = (value: unknown): value is Record<string, unknown> =>
  Boolean(value) && typeof value === "object" && !Array.isArray(value);

const assertClinicDbPayload = (value: unknown): ClinicDb => {
  if (!isObjectRecord(value)) throw new Error(INVALID_API_DATA_MESSAGE);
  if ("nodeType" in value || "array" in value || "object" in value) throw new Error(INVALID_API_DATA_MESSAGE);
  if (!Array.isArray(value.patients) || !isObjectRecord(value.records) || !isObjectRecord(value.archive)) {
    throw new Error(INVALID_API_DATA_MESSAGE);
  }
  return value as ClinicDb;
};

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

const needsBaselineMigration = (db: ClinicDb) =>
  !db.accounts?.length ||
  !db.roles?.length ||
  !db.departments?.length ||
  !db.dictionaries?.length ||
  !db.templateFieldRules?.length ||
  !db.auditLogs;

const parseClinicDbResponse = async (result: Response) => {
  const text = await result.text();
  if (!text.trim()) throw new Error(API_UNAVAILABLE_MESSAGE);
  return JSON.parse(text) as ResultData<unknown>;
};

const throwClinicApiError = async (result: Response) => {
  const text = await result.text();
  try {
    const payload = JSON.parse(text) as ResultData<unknown>;
    if (payload?.msg) {
      throw new Error(payload.msg);
    }
  } catch (error) {
    if (error instanceof Error && error.name === "Error" && error.message !== "Unexpected end of JSON input") {
      throw error;
    }
  }
  throw new Error(text || `${API_UNAVAILABLE_MESSAGE} (HTTP ${result.status})`);
};

export const readDb = async (options: { allowLocalFallback?: boolean } = {}): Promise<ClinicDb> => {
  const allowLocalFallback = options.allowLocalFallback ?? false;
  try {
    const result = await fetch(CLINIC_API_DB_URL, { method: "GET" });
    if (result.ok) {
      const payload = await parseClinicDbResponse(result);
      const rawDb = assertClinicDbPayload(payload.data);
      const shouldPersistBaseline = needsBaselineMigration(rawDb);
      const db = hydrateDb(rawDb);
      if (shouldPersistBaseline) {
        await writeDb(db);
      }
      cacheDb(db);
      return db;
    }

    if (allowLocalFallback) return readLocalDb();
    await throwClinicApiError(result);
  } catch (error) {
    if (allowLocalFallback) return readLocalDb();
    throw error instanceof Error ? error : new Error(API_UNAVAILABLE_MESSAGE);
  }

  throw new Error(API_UNAVAILABLE_MESSAGE);
};

export const writeDb = async (db: ClinicDb) => {
  const result = await fetch(CLINIC_API_DB_URL, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(db)
  });

  if (!result.ok) {
    await throwClinicApiError(result);
  }

  const payload = await parseClinicDbResponse(result);
  if (isObjectRecord(payload.data) && typeof payload.data._revision === "string") {
    db._revision = payload.data._revision;
  }
  cacheDb(db);
};
