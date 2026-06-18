import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";

const require = createRequire(import.meta.url);
const { chromium } = require("C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright");

const htmlPath = "E:/CodeRESPOTORITY/hos_refactor/outputs/login-medical-prototype.html";
const pngPath = "E:/CodeRESPOTORITY/hos_refactor/outputs/login-medical-prototype.png";

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({
  viewport: { width: 1600, height: 980 },
  deviceScaleFactor: 1
});

await page.goto(pathToFileURL(htmlPath).href, { waitUntil: "networkidle" });
await page.screenshot({ path: pngPath, fullPage: false });
await browser.close();
