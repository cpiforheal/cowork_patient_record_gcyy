import { createServer } from "node:http";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { basename, dirname, extname, isAbsolute, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { normalizeClinicSchema } from "./clinic-schema.mjs";

const __dirname = dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.CLINIC_API_PORT || 7071);
const DATA_FILE = resolve(__dirname, "data/clinic-db.json");
const SEED_FILE = resolve(__dirname, "data/clinic-db.seed.json");
const FILE_DIR = resolve(__dirname, "files/clinic-attachments");
const BODY_LIMIT = 20 * 1024 * 1024;

const sendJson = (res, status, payload) => {
  res.writeHead(status, {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET,POST,PUT,OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Content-Type": "application/json; charset=utf-8",
  });
  res.end(status === 204 ? "" : JSON.stringify(payload, null, 2));
};

const sendFile = async (res, filePath) => {
  const data = await readFile(filePath);
  const ext = extname(filePath).toLowerCase();
  const contentType =
    ext === ".png"
      ? "image/png"
      : ext === ".jpg" || ext === ".jpeg"
        ? "image/jpeg"
        : ext === ".gif"
          ? "image/gif"
          : ext === ".webp"
            ? "image/webp"
            : ext === ".pdf"
              ? "application/pdf"
              : "application/octet-stream";
  res.writeHead(200, {
    "Access-Control-Allow-Origin": "*",
    "Content-Type": contentType,
    "Cache-Control": "public, max-age=31536000, immutable",
  });
  res.end(data);
};

const readJsonFile = async (file) => JSON.parse(await readFile(file, "utf8"));

const writeJsonFile = async (file, payload) => {
  await mkdir(dirname(file), { recursive: true });
  await writeFile(file, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
};

const isPlainObject = (value) => value && typeof value === "object" && !Array.isArray(value);

const normalizeClinicDb = (db) => {
  const normalized = normalizeClinicSchema(isPlainObject(db) ? db : {});
  return {
    ...normalized,
    version: normalized.version || 1,
    patients: Array.isArray(normalized.patients) ? normalized.patients : [],
    records: isPlainObject(normalized.records) ? normalized.records : {},
    archive: isPlainObject(normalized.archive) ? normalized.archive : {},
    documents: isPlainObject(normalized.documents) ? normalized.documents : {},
    accounts: Array.isArray(normalized.accounts) ? normalized.accounts : [],
    auditLogs: Array.isArray(normalized.auditLogs) ? normalized.auditLogs : [],
  };
};

const isInsideDirectory = (parent, target) => {
  const relativePath = relative(parent, target);
  return relativePath === "" || (!relativePath.startsWith("..") && !isAbsolute(relativePath));
};

const safeFileName = (fileName) =>
  basename(String(fileName || "attachment"))
    .replace(/[<>:"/\\|?*\x00-\x1f]/g, "_")
    .replace(/\s+/g, "_")
    .slice(0, 120);

const storeDataUrlFile = async ({ fileName, contentDataUrl }) => {
  const matched = String(contentDataUrl || "").match(
    /^data:([^;]+);base64,(.+)$/,
  );
  if (!matched) throw new Error("Invalid file content");

  const [, mimeType, base64] = matched;
  const buffer = Buffer.from(base64, "base64");
  if (!buffer.length) throw new Error("File content is empty");

  const date = new Date();
  const folder = [
    String(date.getFullYear()),
    String(date.getMonth() + 1).padStart(2, "0"),
    String(date.getDate()).padStart(2, "0"),
  ].join("/");
  const storedName = `${Date.now()}-${Math.random().toString(16).slice(2)}-${safeFileName(fileName)}`;
  const storagePath = `${folder}/${storedName}`;
  const targetPath = resolve(FILE_DIR, ...storagePath.split("/"));
  await mkdir(dirname(targetPath), { recursive: true });
  await writeFile(targetPath, buffer);

  return {
    fileName,
    url: `/clinic-api/files/${storagePath}`,
    storagePath,
    size: buffer.length,
    mimeType,
  };
};

const resetDb = async () => {
  const seed = await readJsonFile(SEED_FILE);
  await writeJsonFile(DATA_FILE, normalizeClinicDb({
    ...seed,
    updatedAt: new Date().toISOString(),
  }));
};

const readDb = async () => {
  try {
    const db = await readJsonFile(DATA_FILE);
    const normalized = normalizeClinicDb(db);
    if (JSON.stringify(db) !== JSON.stringify(normalized)) await writeJsonFile(DATA_FILE, normalized);
    return normalized;
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
    await resetDb();
    return readJsonFile(DATA_FILE);
  }
};

const readBody = (req) =>
  new Promise((resolveBody, rejectBody) => {
    const chunks = [];
    let size = 0;

    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > BODY_LIMIT) {
        rejectBody(new Error("Request body exceeds 20MB"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });

    req.on("end", () => {
      try {
        const body = Buffer.concat(chunks).toString("utf8");
        resolveBody(body ? JSON.parse(body) : null);
      } catch (error) {
        rejectBody(error);
      }
    });

    req.on("error", rejectBody);
  });

const assertClinicDb = (db) => {
  if (!db || typeof db !== "object" || Array.isArray(db)) {
    throw new Error("Clinic database payload must be an object");
  }
  for (const key of ["patients", "records", "archive"]) {
    if (!(key in db)) throw new Error(`Clinic database is missing "${key}"`);
  }
};

const route = async (req, res) => {
  const url = new URL(
    req.url || "/",
    `http://${req.headers.host || "localhost"}`,
  );

  if (req.method === "OPTIONS") {
    sendJson(res, 204);
    return;
  }

  if (req.method === "GET" && url.pathname === "/health") {
    sendJson(res, 200, {
      code: 200,
      msg: "ok",
      data: { service: "clinic-api" },
    });
    return;
  }

  if (req.method === "GET" && url.pathname === "/clinic-api/db") {
    sendJson(res, 200, { code: 200, msg: "success", data: await readDb() });
    return;
  }

  if (req.method === "GET" && url.pathname === "/clinic-api/schema") {
    const db = await readDb();
    const normalized = normalizeClinicSchema(db);
    sendJson(res, 200, {
      code: 200,
      msg: "success",
      data: {
        roles: normalized.roles,
        fieldRules: normalized.templateFieldRules,
      },
    });
    return;
  }

  if (req.method === "GET" && url.pathname.startsWith("/clinic-api/files/")) {
    const storagePath = decodeURIComponent(
      url.pathname.replace("/clinic-api/files/", ""),
    );
    const filePath = resolve(FILE_DIR, ...storagePath.split("/"));
    if (!isInsideDirectory(FILE_DIR, filePath)) {
      sendJson(res, 400, { code: 400, msg: "Invalid file path", data: null });
      return;
    }
    try {
      await sendFile(res, filePath);
    } catch (error) {
      if (error.code === "ENOENT") {
        sendJson(res, 404, { code: 404, msg: "File not found", data: null });
        return;
      }
      throw error;
    }
    return;
  }

  if (req.method === "POST" && url.pathname === "/clinic-api/files") {
    const body = await readBody(req);
    const stored = await storeDataUrlFile(body || {});
    sendJson(res, 200, { code: 200, msg: "stored", data: stored });
    return;
  }

  if (req.method === "PUT" && url.pathname === "/clinic-api/db") {
    const body = await readBody(req);
    assertClinicDb(body);
    await writeJsonFile(DATA_FILE, normalizeClinicDb({
      ...body,
      updatedAt: new Date().toISOString(),
    }));
    sendJson(res, 200, { code: 200, msg: "saved", data: null });
    return;
  }

  if (req.method === "POST" && url.pathname === "/clinic-api/reset") {
    await resetDb();
    sendJson(res, 200, { code: 200, msg: "reset", data: await readDb() });
    return;
  }

  sendJson(res, 404, { code: 404, msg: "Not found", data: null });
};

if (process.argv.includes("--reset")) {
  await resetDb();
  console.log(`Clinic database reset at ${DATA_FILE}`);
} else {
  createServer((req, res) => {
    route(req, res).catch((error) => {
      sendJson(res, 500, { code: 500, msg: error.message, data: null });
    });
  }).listen(PORT, () => {
    console.log(`Clinic API listening on http://localhost:${PORT}`);
  });
}
