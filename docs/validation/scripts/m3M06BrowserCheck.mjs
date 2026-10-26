// M3-06 浏览器验收：外部 MCP 客户端经 Spring 控制桥驱动真实页面画布。
// 依赖 ExternalProtocolHarness + M3W03DevServer 静态代理 + 本地端口转发。
// 用法：HM_BROWSER_PROJECT=<alphaProject> HM_MCP_TOKEN=<alphaToken> HM_MCP_ORIGIN=http://127.0.0.1:18086 node docs/validation/scripts/m3M06BrowserCheck.mjs
import { createRequire } from "node:module";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
let chromium;
try { ({ chromium } = require("playwright-core")); }
catch {
  if (!process.env.PLAYWRIGHT_CORE_PATH) throw new Error("请安装 playwright-core 或设置 PLAYWRIGHT_CORE_PATH");
  ({ chromium } = require(process.env.PLAYWRIGHT_CORE_PATH));
}
const BASE = process.env.HM_BROWSER_ORIGIN ?? "http://localhost:5187";
const PROJECT = process.env.HM_BROWSER_PROJECT;
if (!PROJECT) throw new Error("请设置 HM_BROWSER_PROJECT");
const TOKEN = process.env.HM_MCP_TOKEN;
if (!TOKEN) throw new Error("请设置 HM_MCP_TOKEN");
const MCP_ORIGIN = process.env.HM_MCP_ORIGIN ?? "http://127.0.0.1:18086";
const USER = process.env.HM_BROWSER_USER ?? "ext-member";
const PASSWORD = process.env.HM_BROWSER_PASSWORD ?? "M3-external-fixture-only";
const EXECUTABLE = process.env.PLAYWRIGHT_CHROMIUM_PATH || undefined;
const EVIDENCE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../evidence");
const checks = [];
const pageErrors = [];
function check(name, ok, detail = "") {
  checks.push({ name, ok: Boolean(ok), detail: String(detail) });
  console.log(`${ok ? "PASS" : "FAIL"} ${name}${detail ? " :: " + detail : ""}`);
  if (!ok) throw new Error(`M3-06 browser check failed: ${name}`);
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

let sequence = 1;
async function mcpTool(name, args = {}, key) {
  const response = await fetch(`${MCP_ORIGIN}/mcp`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      "Content-Type": "application/json",
      ...(key ? { "Idempotency-Key": key } : {}),
    },
    body: JSON.stringify({ jsonrpc: "2.0", id: sequence++, method: "tools/call", params: { name, arguments: args } }),
  });
  const text = await response.text();
  const frame = JSON.parse(text.replace(/^data:\s*/, "").trim());
  return { status: response.status, frame };
}

const browser = await chromium.launch({ headless: true, ...(EXECUTABLE ? { executablePath: EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL: BASE, viewport: { width: 1500, height: 950 } });
const page = await context.newPage();
page.on("pageerror", error => pageErrors.push(String(error)));
try {
  await page.goto("/");
  await page.waitForSelector('input[type="password"]', { timeout: 30000 });
  await page.fill('input[type="text"]', USER);
  await page.fill('input[type="password"]', PASSWORD);
  await page.click('button[type="submit"]');
  await page.waitForSelector("text=外部协议验收", { timeout: 30000 });
  await page.getByRole("button", { name: /打开项目 外部协议验收/ }).click();
  await page.waitForSelector("text=画布1", { timeout: 30000 });
  // 控制连接由页面挂载后异步建立，留出重连窗口。
  await sleep(2500);
  check("A1 页面打开绑定项目并显示默认画布", true);

  const appState = await mcpTool("getAppState");
  const state = appState.frame.result.structuredContent;
  check("B1 MCP 读到页面已连接", appState.status === 200 && state.pageConnected === true, JSON.stringify(state).slice(0, 200));
  check("B2 页面状态含画布与项目", state.page?.canvasId === "画布1.json" && state.page?.projectId === PROJECT, JSON.stringify(state.page ?? {}).slice(0, 200));

  const overview = await mcpTool("getCanvas", {});
  const canvas = overview.frame.result.structuredContent;
  check("C1 外部调用在页面执行 getCanvas", overview.frame.result.isError === false && canvas.canvasId === "画布1.json", JSON.stringify(canvas).slice(0, 200));
  check("C2 返回真实节点与连线计数", typeof canvas.nodeCount === "number" && typeof canvas.edgeCount === "number", JSON.stringify(canvas).slice(0, 160));

  const added = await mcpTool("addCanvas", { name: "MCP验收" });
  const created = added.frame.result?.structuredContent;
  check(
    "D1 外部创建画布并切换",
    added.frame.result?.isError === false && Boolean(created?.canvasId),
    JSON.stringify(added.frame).slice(0, 300),
  );
  await sleep(1500);
  const after = await mcpTool("getAppState");
  check("D2 页面确实切到新画布", after.frame.result.structuredContent.page?.canvasId === created.canvasId, after.frame.result.structuredContent.page?.canvasId);
  const visible = await page.locator("body").textContent();
  check("D3 画布菜单出现新画布", visible.includes("MCP验收"), visible.slice(-200));

  const restored = await mcpTool("switchCanvas", { canvasId: "画布1.json" });
  check("E1 外部切回原画布", restored.frame.result.isError === false, JSON.stringify(restored.frame.result).slice(0, 160));
  await sleep(1000);
  const finalState = await mcpTool("getAppState");
  check("E2 页面状态与外部结果一致", finalState.frame.result.structuredContent.page?.canvasId === "画布1.json", finalState.frame.result.structuredContent.page?.canvasId);

  check("F1 页面无未捕获错误", pageErrors.length === 0, pageErrors.join(" | "));
  fs.mkdirSync(EVIDENCE, { recursive: true });
  await page.screenshot({ path: path.join(EVIDENCE, "m3-m06-mcp-canvas.png") });
  fs.writeFileSync(
    path.join(EVIDENCE, "m3-m06-browser.json"),
    JSON.stringify({ checks, pageErrors, createdAt: new Date().toISOString() }, null, 1),
  );
  console.log("M3-06 browser checks:", checks.length, "all passed");
} finally {
  await context.close();
  await browser.close();
}
