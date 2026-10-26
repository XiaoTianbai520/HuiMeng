// M3-04 浏览器验收：Agent 画布工具调用经页面执行并把结果回填给模型。
// 依赖 WorkspaceBrowserAcceptanceHarness + m3M04LlmMock.py + M3W03DevServer 静态代理。
// 用法：HM_BROWSER_PROJECT=<projectId> node docs/validation/scripts/m3M04AgentToolsCheck.mjs
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
const USER = process.env.HM_BROWSER_USER ?? "ui-member";
const PASSWORD = process.env.HM_BROWSER_PASSWORD ?? "M3-browser-fixture-only";
const EXECUTABLE = process.env.PLAYWRIGHT_CHROMIUM_PATH || undefined;
const EVIDENCE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../evidence");
const checks = [];
const pageErrors = [];
function check(name, ok, detail = "") {
  checks.push({ name, ok: Boolean(ok), detail: String(detail) });
  console.log(`${ok ? "PASS" : "FAIL"} ${name}${detail ? " :: " + detail : ""}`);
  if (!ok) throw new Error(`M3-04 browser check failed: ${name}`);
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function shot(page, name) {
  fs.mkdirSync(EVIDENCE, { recursive: true });
  await page.screenshot({ path: path.join(EVIDENCE, `m3-m04-${name}.png`) });
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
  await page.waitForSelector("text=浏览器文档验收", { timeout: 30000 });
  await page.getByRole("button", { name: /打开项目 浏览器文档验收/ }).click();
  await page.waitForSelector("text=画布1", { timeout: 30000 });
  check("A1 登录并进入含画布的工作区", true);

  await page.getByRole("button", { name: "新建对话", exact: true }).click();
  await sleep(1500);
  const editor = page.locator('.senderEditor [contenteditable="true"]').last();
  await editor.click();
  await page.keyboard.type("看看当前画布");
  await page.getByRole("button", { name: "发送消息", exact: true }).click();

  await page.waitForSelector("text=画布工具已执行", { timeout: 60000, state: "attached" });
  const body = await page.locator("body").textContent();
  check("B1 模型返回文本且引用了页面回传结果", body.includes("画布工具已执行"), body.slice(-120));
  check("B2 回填内容含真实画布字段", /canvasId|nodeCount|nodes|edges/.test(body), body.slice(-160));
  check("B3 工具卡片已渲染", body.includes("画布概览") || body.includes("getCanvas"), body.slice(-160));
  await shot(page, "canvas-tool");

  const stats = await fetch("http://127.0.0.1:18119/_stats")
    .then(response => response.json())
    .catch(() => undefined);
  check(
    "C1 供应商收到工具调用与结果回填",
    stats ? stats.requests >= 2 && stats.toolResults >= 1 : false,
    JSON.stringify(stats ?? "统计端口未转发"),
  );

  // 会话以 .jsonl 落盘：用同一浏览器会话的 Cookie 直接读接口，确认工具调用与结果都已持久化。
  const listed = await context.request.get(`${BASE}/api/v1/projects/${PROJECT}/creative/agent/list`);
  const sessions = (await listed.json()).data ?? [];
  check("D1 会话列表包含本次对话", sessions.length >= 1 && sessions[0].file.endsWith(".jsonl"), JSON.stringify(sessions));
  const detail = await context.request.get(
    `${BASE}/api/v1/projects/${PROJECT}/creative/agent/get?sessionFile=${encodeURIComponent(sessions[0].file)}`,
  );
  const conversation = (await detail.json()).data;
  const parts = (conversation.messages?.[1]?.parts ?? []);
  const toolPart = parts.find(part => part.type === "tool");
  check("D2 助手消息持久化工具调用与结果", Boolean(toolPart), JSON.stringify(toolPart ?? {}).slice(0, 160));
  check("D3 工具结果为真实画布数据", String(toolPart?.tool?.result ?? "").includes("viewport"), String(toolPart?.tool?.result ?? "").slice(0, 120));
  check("E1 页面无未捕获错误", pageErrors.length === 0, pageErrors.join(" | "));
  // M3-05 起能力目录返回空列表，页面应使用内置卡片而不是报「工具界面加载失败」。
  check("E2 不再出现工具界面加载失败提示", !body.includes("工具界面加载失败"), body.slice(0, 80));
  check("E3 不再出现 403/404 请求失败提示", !/Request failed with status code (403|404)/.test(body), body.slice(0, 80));
} finally {
  fs.mkdirSync(EVIDENCE, { recursive: true });
  fs.writeFileSync(
    path.join(EVIDENCE, "m3-m04-browser.json"),
    `${JSON.stringify({ generatedAt: new Date().toISOString(), project: PROJECT, checks, pageErrors }, null, 2)}\n`,
  );
  await context.close();
  await browser.close();
}
