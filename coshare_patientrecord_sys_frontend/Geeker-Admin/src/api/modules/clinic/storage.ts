import type { ResultData } from "@/api/interface";
import { authHeaders } from "../authToken";
import { createSeedDb, hydrateDb } from "./seed";
import type { ClinicDb } from "./types";

const STORAGE_KEY = "hos-unitywork-clinic-db";
const CLINIC_API_DB_URL = import.meta.env.VITE_CLINIC_API_DB_URL || "/clinic-api/db";
const CLINIC_API_BASE_URL = CLINIC_API_DB_URL.replace(/\/db\/?$/, "");
const API_UNAVAILABLE_MESSAGE =
  "\u672c\u5730\u75c5\u5386\u6570\u636e\u670d\u52a1\u672a\u8fde\u63a5\uff0c\u8bf7\u786e\u8ba4\u540e\u7aef\u670d\u52a1\u6b63\u5728\u8fd0\u884c";
const INVALID_API_DATA_MESSAGE =
  "\u672c\u5730\u75c5\u5386\u6570\u636e\u670d\u52a1\u8fd4\u56de\u5f02\u5e38\uff0c\u8bf7\u91cd\u542f hos_refactor \u540e\u7aef\u540e\u518d\u8bd5";

let baselineDb: ClinicDb | undefined;

const isObjectRecord = (value: unknown): value is Record<string, unknown> =>
  Boolean(value) && typeof value === "object" && !Array.isArray(value);

const assertClinicDbPayload = (value: unknown): ClinicDb => {
  if (!isObjectRecord(value)) throw new Error(INVALID_API_DATA_MESSAGE);
  if ("nodeType" in value || "array" in value || "object" in value) throw new Error(INVALID_API_DATA_MESSAGE);
  if (!Array.isArray(value.patients) || !isObjectRecord(value.records) || !isObjectRecord(value.archive)) {
    throw new Error(INVALID_API_DATA_MESSAGE);
  }
  return value as unknown as ClinicDb;
};

const cacheDb = (db: ClinicDb) => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(db));
  } catch {
    localStorage.removeItem(STORAGE_KEY);
  }
};

const cloneDb = (db: ClinicDb): ClinicDb => JSON.parse(JSON.stringify(db)) as ClinicDb;

const rememberBaseline = (db: ClinicDb) => {
  baselineDb = cloneDb(db);
};

const sameJson = (left: unknown, right: unknown) => JSON.stringify(left ?? null) === JSON.stringify(right ?? null);

const diffArrayById = <T extends { id: string }>(nextRows: T[] = [], previousRows: T[] = []) => {
  const previousMap = new Map(previousRows.map(row => [row.id, row]));
  return nextRows.filter(row => !sameJson(row, previousMap.get(row.id)));
};

const diffObjectByKey = <T>(nextRows: Record<string, T> = {}, previousRows: Record<string, T> = {}) =>
  Object.entries(nextRows).reduce<Record<string, T>>((result, [key, value]) => {
    if (!sameJson(value, previousRows[key])) result[key] = value;
    return result;
  }, {});

const buildMergePayload = (db: ClinicDb): Partial<ClinicDb> => {
  if (!baselineDb) return db;
  const payload: Partial<ClinicDb> = {};
  const patients = diffArrayById(db.patients, baselineDb.patients);
  if (patients.length) payload.patients = patients;

  const records = diffObjectByKey(db.records, baselineDb.records);
  if (Object.keys(records).length) payload.records = records;

  const archive = diffObjectByKey(db.archive, baselineDb.archive);
  if (Object.keys(archive).length) payload.archive = archive;

  const documents = diffObjectByKey(db.documents ?? {}, baselineDb.documents ?? {});
  if (Object.keys(documents).length) payload.documents = documents;

  const accounts = diffArrayById(db.accounts ?? [], baselineDb.accounts ?? []);
  if (accounts.length) payload.accounts = accounts;

  const roles = diffArrayById(db.roles ?? [], baselineDb.roles ?? []);
  if (roles.length) payload.roles = roles;

  const departments = diffArrayById(db.departments ?? [], baselineDb.departments ?? []);
  if (departments.length) payload.departments = departments;

  const dictionaries = diffArrayById(db.dictionaries ?? [], baselineDb.dictionaries ?? []);
  if (dictionaries.length) payload.dictionaries = dictionaries;

  const templateFieldRules = diffArrayById(db.templateFieldRules ?? [], baselineDb.templateFieldRules ?? []);
  if (templateFieldRules.length) payload.templateFieldRules = templateFieldRules;

  const auditLogs = diffArrayById(db.auditLogs ?? [], baselineDb.auditLogs ?? []);
  if (auditLogs.length) payload.auditLogs = auditLogs;

  return payload;
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
  if (text.trim().startsWith("<")) {
    throw new Error(`${API_UNAVAILABLE_MESSAGE}，请检查 /clinic-api 代理或部署转发配置`);
  }
  try {
    return JSON.parse(text) as ResultData<unknown>;
  } catch {
    throw new Error("业务数据接口返回格式异常，请检查后端服务状态");
  }
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
    const result = await fetch(CLINIC_API_DB_URL, { method: "GET", headers: authHeaders() });
    if (result.ok) {
      const payload = await parseClinicDbResponse(result);
      const rawDb = assertClinicDbPayload(payload.data);
      const shouldPersistBaseline = needsBaselineMigration(rawDb);
      let db = hydrateDb(rawDb);
      if (shouldPersistBaseline) {
        db = await writeDb(db);
      }
      cacheDb(db);
      rememberBaseline(db);
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

export const writeDb = async (db: ClinicDb): Promise<ClinicDb> => {
  const mergePayload = buildMergePayload(db);
  const result = await fetch(`${CLINIC_API_BASE_URL}/db/merge`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(mergePayload)
  });

  if (!result.ok) {
    await throwClinicApiError(result);
  }

  const payload = await parseClinicDbResponse(result);
  if (isObjectRecord(payload.data) && isObjectRecord(payload.data.db)) {
    const mergedDb = hydrateDb(assertClinicDbPayload(payload.data.db));
    if (typeof payload.data._revision === "string") {
      mergedDb._revision = payload.data._revision;
    }
    cacheDb(mergedDb);
    rememberBaseline(mergedDb);
    return mergedDb;
  }
  cacheDb(db);
  rememberBaseline(db);
  return db;
};

export const patchDb = async (patch: Partial<ClinicDb>) => {
  const result = await fetch(`${CLINIC_API_BASE_URL}/db/patch`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(patch)
  });

  if (!result.ok) {
    await throwClinicApiError(result);
  }

  const payload = await parseClinicDbResponse(result);
  const data = isObjectRecord(payload.data) ? payload.data : {};
  const db = assertClinicDbPayload(data.db);
  if (typeof data._revision === "string") {
    db._revision = data._revision;
  }
  const hydrated = hydrateDb(db);
  cacheDb(hydrated);
  rememberBaseline(hydrated);
  return hydrated;
};

export const getClinicApiBaseUrl = () => CLINIC_API_BASE_URL;
