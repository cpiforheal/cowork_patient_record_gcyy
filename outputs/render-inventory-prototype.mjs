import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";

const require = createRequire(import.meta.url);
const { chromium } = require("C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright");

const htmlPath = "E:/CodeRESPOTORITY/hos_refactor/outputs/inventory-executive-prototype.html";
const pngPath = "E:/CodeRESPOTORITY/hos_refactor/outputs/inventory-executive-prototype.png";

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({
  viewport: { width: 1732, height: 924 },
  deviceScaleFactor: 1,
});

await page.goto(pathToFileURL(htmlPath).href, { waitUntil: "networkidle" });
await page.screenshot({ path: pngPath, fullPage: false });
await browser.close();
