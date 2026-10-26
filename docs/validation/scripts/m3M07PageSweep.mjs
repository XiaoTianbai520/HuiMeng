// M3-07 页面遍历：管理端把每个页面与设置面板走一遍，记录失败请求与排除路由命中。
// 依赖：真实 Spring 夹具（外部协议夹具，含管理员/成员/项目）+ Vite 开发服务器（5187）。
// 用法：HM_BROWSER_ORIGIN=http://localhost:5187 HM_BROWSER_PROJECT=<projectId> node docs/validation/scripts/m3M07PageSweep.mjs
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
const USER = process.env.HM_BROWSER_USER ?? "ext-admin";
const PASSWORD = process.env.HM_BROWSER_PASSWORD ?? "M3-external-fixture-only";
const EXECUTABLE = process.env.PLAYWRIGHT_CHROMIUM_PATH || undefined;
const EVIDENCE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../evidence");
const EXCLUDED = ["/api/browser/", "/api/desktop/", "/api/mobileLink/", "/api/workspaces/files/reveal", "/api/workspaces/selectDirectory"];

const checks = [];
const failures = [];
const excludedHits = [];
const consoleErrors = [];
const pageErrors = [];
let sweeps = 0;

function check(name, ok, detail = "") {
  checks.push({ name, ok: Boolean(ok), detail: String(detail).slice(0, 400) });
  console.log(`${ok ? "PASS" : "FAIL"} ${name}${detail ? " :: " + String(detail).slice(0, 200) : ""}`);
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

const browser = await chromium.launch({ headless: true, ...(EXECUTABLE ? { executablePath: EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL: BASE, viewport: { width: 1500, height: 950 } });
const page = await context.newPage();
let loggedIn = false;
page.on("pageerror", error => pageErrors.push(String(error).slice(0, 200)));
page.on("console", message => { if (message.type() === "error") consoleErrors.push(message.text().slice(0, 200)); });
page.on("response", response => {
  const url = new URL(response.url());
  if (EXCLUDED.some(prefix => url.pathname.startsWith(prefix))) excludedHits.push({ status: response.status(), path: url.pathname });
  if (!loggedIn || response.status() < 400) return;
  // 登录前的会话探测 401 属正常；页面内 4xx/5xx 才算失败。
  failures.push({ status: response.status(), method: response.request().method(), path: url.pathname + url.search });
});

async function go(route, readySelector, name) {
  sweeps++;
  await page.goto(route, { waitUntil: "domcontentloaded" });
  if (readySelector) await page.waitForSelector(readySelector, { timeout: 30000 });
  await sleep(900);
  check(`${name} 打开`, true, route);
}

async function openSettings() {
  await page.getByRole("button", { name: /^设置/ }).first().click();
  await page.waitForSelector(".settings .sidebar", { timeout: 15000 });
  await sleep(600);
}

try {
  await page.goto("/");
  await page.waitForSelector('input[type="password"]', { timeout: 30000 });
  await page.fill('input[type="text"]', USER);
  await page.fill('input[type="password"]', PASSWORD);
  await page.click('button[type="submit"]');
  await page.waitForSelector("text=外部协议验收", { timeout: 30000 });
  loggedIn = true;
  await sleep(1200);

  await go("/home", "text=外部协议验收", "首页项目列表");
  await go("/team", "text=团队", "团队管理页");
  await go(`/projects/${PROJECT}/files`, "text=文件", "项目文件页");
  // 工作区需要先经首页打开项目，直接导航不会带入当前项目。
  await page.goto("/home", { waitUntil: "domcontentloaded" });
  await page.getByRole("button", { name: /打开项目 外部协议验收/ }).click();
  await page.waitForSelector("text=画布1", { timeout: 30000 });
  await sleep(1200);
  sweeps++;
  check("画布工作区 打开", true, "/workspace");

  await openSettings();
  const panels = await page.locator(".settings .sidebar button").allInnerTexts();
  check("设置面板不少于 7 个", panels.length >= 7, panels.join(" | "));
  for (const label of panels) {
    sweeps++;
    await page.locator(".settings .sidebar button", { hasText: label }).first().click();
    await sleep(1200);
    check(`设置面板「${label.trim()}」打开`, true);
  }
  await page.screenshot({ path: path.join(EVIDENCE, "m3-m07-settings.png") });

  // 市场面板的已安装/市场两个分页
  const marketTab = page.locator(".settings .sidebar button", { hasText: "插件市场" }).first();
  await marketTab.click();
  await sleep(800);
  for (const label of ["已安装", "发现"]) {
    const tab = page.getByRole("tab", { name: new RegExp(label) });
    if (await tab.count()) {
      sweeps++;
      await tab.first().click();
      await sleep(1500);
      check(`插件市场「${label}」分页打开`, true);
    }
  }
  await page.screenshot({ path: path.join(EVIDENCE, "m3-m07-market.png") });
  await page.keyboard.press("Escape");
  await sleep(500);

  check("页面未命中任何排除路由", excludedHits.length === 0, JSON.stringify(excludedHits.slice(0, 5)));
  check("页面无失败请求", failures.length === 0, JSON.stringify(failures.slice(0, 8)));
  check("页面无未捕获错误", pageErrors.length === 0, pageErrors.join(" | "));
  fs.mkdirSync(EVIDENCE, { recursive: true });
  fs.writeFileSync(
    path.join(EVIDENCE, "m3-m07-pages.json"),
    JSON.stringify({ sweeps, checks, failures, excludedHits, consoleErrors, pageErrors, createdAt: new Date().toISOString() }, null, 1),
  );
  console.log(`M3-07 page sweep: ${sweeps} 次遍历, ${failures.length} 个失败请求, ${excludedHits.length} 个排除命中`);
  process.exitCode = failures.length || excludedHits.length || pageErrors.length ? 1 : 0;
} finally {
  await context.close();
  await browser.close();
}
