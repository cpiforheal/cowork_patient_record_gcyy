import { createServer } from "node:http";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, extname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.CLINIC_API_PORT || 7071);
const DATA_FILE = resolve(__dirname, "data/clinic-db.json");
const SEED_FILE = resolve(__dirname, "data/clinic-db.seed.json");
const PUBLIC_DIR = resolve(__dirname, "../public");
const BODY_LIMIT = 1024 * 1024;

const sendJson = (res, status, payload) => {
  res.writeHead(status, {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET,POST,PUT,OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Content-Type": "application/json; charset=utf-8"
  });
  res.end(status === 204 ? "" : JSON.stringify(payload, null, 2));
};

const readJsonFile = async file => JSON.parse(await readFile(file, "utf8"));

const contentTypes = {
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".png": "image/png",
  ".webp": "image/webp"
};

const writeJsonFile = async (file, payload) => {
  await mkdir(dirname(file), { recursive: true });
  await writeFile(file, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
};

const resetDb = async () => {
  const seed = await readJsonFile(SEED_FILE);
  await writeJsonFile(DATA_FILE, {
    ...seed,
    updatedAt: new Date().toISOString()
  });
};

const readDb = async () => {
  try {
    return await readJsonFile(DATA_FILE);
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
    await resetDb();
    return readJsonFile(DATA_FILE);
  }
};

const readBody = req =>
  new Promise((resolveBody, rejectBody) => {
    const chunks = [];
    let size = 0;

    req.on("data", chunk => {
      size += chunk.length;
      if (size > BODY_LIMIT) {
        rejectBody(new Error("Request body exceeds 1MB"));
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

const assertClinicDb = db => {
  if (!db || typeof db !== "object" || Array.isArray(db)) {
    throw new Error("Clinic database payload must be an object");
  }
  for (const key of ["patients", "records", "archive"]) {
    if (!(key in db)) throw new Error(`Clinic database is missing "${key}"`);
  }
};

const route = async (req, res) => {
  const url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);

  if (req.method === "OPTIONS") {
    sendJson(res, 204);
    return;
  }

  if (req.method === "GET" && url.pathname === "/health") {
    sendJson(res, 200, { code: 200, msg: "ok", data: { service: "clinic-api" } });
    return;
  }

  if (req.method === "GET" && url.pathname.startsWith("/record-samples/")) {
    const file = resolve(PUBLIC_DIR, decodeURIComponent(url.pathname.slice(1)));
    if (!file.startsWith(`${PUBLIC_DIR}${sep}`)) {
      sendJson(res, 403, { code: 403, msg: "Forbidden", data: null });
      return;
    }

    try {
      const content = await readFile(file);
      res.writeHead(200, {
        "Access-Control-Allow-Origin": "*",
        "Content-Type": contentTypes[extname(file).toLowerCase()] || "application/octet-stream"
      });
      res.end(content);
    } catch (error) {
      if (error.code === "ENOENT") {
        sendJson(res, 404, { code: 404, msg: "Static file not found", data: null });
        return;
      }
      throw error;
    }
    return;
  }

  if (req.method === "GET" && url.pathname === "/clinic-api/db") {
    sendJson(res, 200, { code: 200, msg: "success", data: await readDb() });
    return;
  }

  if (req.method === "GET" && url.pathname === "/clinic-api/schema") {
    const db = await readDb();
    sendJson(res, 200, {
      code: 200,
      msg: "success",
      data: {
        roles: db.roles,
        fieldRules: db.fieldRules
      }
    });
    return;
  }

  if (req.method === "PUT" && url.pathname === "/clinic-api/db") {
    const body = await readBody(req);
    assertClinicDb(body);
    await writeJsonFile(DATA_FILE, {
      ...body,
      updatedAt: new Date().toISOString()
    });
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
    route(req, res).catch(error => {
      sendJson(res, 500, { code: 500, msg: error.message, data: null });
    });
  }).listen(PORT, () => {
    console.log(`Clinic API listening on http://localhost:${PORT}`);
  });
}
