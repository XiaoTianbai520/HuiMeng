// M3-W03 真实浏览器验收：独立 Linux/H2 夹具 + 本机静态代理 + Playwright Chromium。
// 仅使用合成账号与夹具项目；输出 docs/validation/evidence/m3-w03-browser.json 与截图。
// 用法：先启动夹具与 m3W03DevServer.mjs，再设置 HM_BROWSER_PROJECT=<ready 中的 projectId>。
// 依赖：仓库根安装 playwright-core，或设置 PLAYWRIGHT_CORE_PATH 指向其 node_modules 目录。
// 浏览器默认使用 Playwright 已安装 Chromium；可用 PLAYWRIGHT_CHROMIUM_PATH 指定可执行文件。
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

const BASE = "http://localhost:5187";
const PROJECT = process.env.HM_BROWSER_PROJECT;
if (!PROJECT) throw new Error("请设置 HM_BROWSER_PROJECT 为夹具 workspace-browser.ready 中的 projectId");
const USER = "ui-member";
const PASSWORD = "M3-browser-fixture-only";
const EXECUTABLE = process.env.PLAYWRIGHT_CHROMIUM_PATH || undefined;
const EVIDENCE = fileURLToPath(new URL("../evidence/", import.meta.url));
const checks = [];
const consoleErrors = [];
const browserErrors = [];
function check(name, ok, detail = "") {
  checks.push({ name, ok: !!ok, detail: String(detail) });
  console.log(`${ok ? "PASS" : "FAIL"} ${name}${detail ? " :: " + detail : ""}`);
}
async function shot(page, name) {
  await page.screenshot({ path: path.join(EVIDENCE, `m3-w03-${name}.png`), fullPage: false });
}
async function api(context, method, suffix, options = {}) {
  const response = await context.request.fetch(`/api/v1${suffix}`, { method, ...options });
  const text = await response.text();
  let body;
  try { body = JSON.parse(text); } catch { body = text; }
  return { status: response.status(), body };
}
async function loginApi(context) {
  const csrf = await api(context, "GET", "/auth/csrf");
  const token = csrf.body.token;
  const login = await api(context, "POST", "/auth/login", {
    data: { username: USER, password: PASSWORD },
    headers: { "X-CSRF-Token": token, Origin: BASE },
  });
  if (login.status !== 200) throw new Error(`API 登录失败 ${login.status}`);
  const rotated = await api(context, "GET", "/auth/csrf");
  return rotated.body.token;
}
async function readSnapshot(context, path) {
  const result = await api(context, "GET", `/projects/${PROJECT}/creative/workspaces/files/read?path=${encodeURIComponent(path)}&snapshot=true`);
  if (result.status !== 200) throw new Error(`读取快照失败 ${path}: ${result.status} ${JSON.stringify(result.body)}`);
  return result.body.data;
}
async function writeSnapshot(context, path, text, snapshot, token) {
  const result = await api(context, "PUT", `/projects/${PROJECT}/creative/workspaces/files/write?path=${encodeURIComponent(path)}&expectedRevision=${snapshot.revision}&encoding=${snapshot.encoding}`, {
    data: text,
    headers: { "Content-Type": "application/octet-stream", "X-CSRF-Token": token, Origin: BASE },
  });
  if (result.status !== 200) throw new Error(`写入失败 ${path}: ${result.status} ${JSON.stringify(result.body)}`);
  return result.body.data.revision;
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

const browser = await chromium.launch({ headless: true, ...(EXECUTABLE ? { executablePath: EXECUTABLE } : {}) });
try {
  const context = await browser.newContext({ baseURL: BASE, viewport: { width: 1500, height: 950 } });
  const page = await context.newPage();
  page.on("pageerror", error => browserErrors.push(String(error)));
  page.on("console", message => { if (message.type() === "error") consoleErrors.push(message.text().slice(0, 200)); });

  // A. 登录并进入项目工作区
  await page.goto("/");
  await page.waitForSelector('input[type="password"]', { timeout: 30000 });
  await page.fill('input[type="text"]', USER);
  await page.fill('input[type="password"]', PASSWORD);
  await page.click('button[type="submit"]');
  await page.waitForSelector('text=浏览器文档验收', { timeout: 30000 });
  await page.getByRole("button", { name: /打开项目 浏览器文档验收/ }).click();
  await page.waitForSelector('text=画布1', { timeout: 30000 });
  check("A1 登录并进入工作区，自动创建画布1", true);

  // B. 画布自动保存：连续两次缩放，服务端文件随之更新
  const zoomButton = page.locator('button[aria-label="缩放菜单"]');
  async function zoomAction(name) {
    const item = page.getByRole("button", { name, exact: true });
    if (!await item.isVisible().catch(() => false)) await zoomButton.click();
    await item.click();
  }
  await zoomAction("缩小");
  await sleep(1500);
  const first = await readSnapshot(context, "画布1.json");
  const firstZoom = JSON.parse(first.text).viewport.zoom;
  check("B1 画布视口修改自动保存到服务端", firstZoom < 1, `zoom=${firstZoom}`);
  await zoomAction("缩小");
  await sleep(1500);
  const second = await readSnapshot(context, "画布1.json");
  const secondZoom = JSON.parse(second.text).viewport.zoom;
  check("B2 连续自动保存使用新修订号（无 409）", secondZoom < firstZoom, `zoom ${firstZoom} -> ${secondZoom}; revision 更新=${second.revision !== first.revision}`);

  // C. 文档面板：打开、编辑、自动保存、重新读取
  await page.locator(".panelSwitcher").getByText("文档", { exact: true }).click();
  await page.waitForSelector('text=欢迎.md', { timeout: 15000 });
  await page.dblclick("text=欢迎.md");
  await page.waitForSelector('textarea[aria-label="编辑 欢迎.md"]', { timeout: 20000 });
  const original = await page.getByLabel("编辑 欢迎.md", { exact: true }).inputValue();
  check("C1 内置纯文本编辑器读取原文", original.includes("绘梦验收剧本"), original.slice(0, 40).replaceAll("\n", " "));
  const edited = `${original}\n第二行：M3-W03 文档编辑保存。\n`;
  await page.getByLabel("编辑 欢迎.md", { exact: true }).fill(edited);
  await sleep(1800);
  const saved = await readSnapshot(context, "欢迎.md");
  check("C2 文档编辑自动保存带修订号", saved.text === edited, `revision=${saved.revision.slice(0, 12)}`);
  await shot(page, "document-saved");

  // D. 文档 409：另一会话写入后，本页保存冲突、草稿保留、暂停覆盖、可重读
  const other = await browser.newContext({ baseURL: BASE });
  const otherToken = await loginApi(other);
  const beforeOther = await readSnapshot(other, "欢迎.md");
  const otherText = `${beforeOther.text}第三行：另一会话已保存。\n`;
  await writeSnapshot(other, "欢迎.md", otherText, beforeOther, otherToken);
  await page.getByLabel("编辑 欢迎.md", { exact: true }).fill(`${edited}第四行：本页冲突草稿。\n`);
  await sleep(2000);
  const conflictVisible = await page.getByRole("button", { name: "载入磁盘版本", exact: true }).isVisible().catch(() => false);
  const draftKept = (await page.getByLabel("编辑 欢迎.md", { exact: true }).inputValue()).includes("本页冲突草稿");
  check("D1 文档冲突提示出现", conflictVisible);
  check("D2 冲突后草稿保留", draftKept);
  const afterConflict = await readSnapshot(context, "欢迎.md");
  check("D3 冲突未覆盖另一会话内容", afterConflict.text === otherText);
  await sleep(1500);
  const stillOther = await readSnapshot(context, "欢迎.md");
  check("D4 自动保存已暂停（未继续覆盖）", stillOther.text === otherText);
  await shot(page, "document-conflict");
  await page.getByRole("button", { name: "载入磁盘版本", exact: true }).click();
  await page.getByRole("button", { name: "载入", exact: true }).click();
  await sleep(800);
  const reloaded = await page.getByLabel("编辑 欢迎.md", { exact: true }).inputValue();
  check("D5 载入磁盘版本后显示另一会话内容", reloaded === otherText);
  const resumed = `${otherText}第五行：冲突恢复后保存。\n`;
  await page.getByLabel("编辑 欢迎.md", { exact: true }).fill(resumed);
  await sleep(1800);
  const resumedSaved = await readSnapshot(context, "欢迎.md");
  check("D6 冲突恢复后可继续保存", resumedSaved.text === resumed);

  // E. 画布 409：另一会话修改画布，本页自动保存冲突并重读
  await page.locator(".panelSwitcher").getByText("画布", { exact: true }).click();
  await sleep(800);
  const canvasBefore = await readSnapshot(other, "画布1.json");
  const canvasOther = JSON.parse(canvasBefore.text);
  canvasOther.viewport.zoom = 0.5;
  await writeSnapshot(other, "画布1.json", `${JSON.stringify(canvasOther, null, 2)}\n`, canvasBefore, otherToken);
  await zoomAction("放大");
  await sleep(2000);
  const canvasConflict = await page.getByText("画布保存冲突", { exact: false }).isVisible().catch(() => false);
  check("E1 画布冲突弹窗出现", canvasConflict);
  await shot(page, "canvas-conflict");
  const canvasKept = await readSnapshot(context, "画布1.json");
  check("E2 冲突未覆盖另一会话的画布", JSON.parse(canvasKept.text).viewport.zoom === 0.5);
  await page.getByRole("button", { name: "重新读取画布", exact: true }).click();
  await sleep(1500);
  if (!await page.locator('input[aria-label="缩放百分比"]').isVisible().catch(() => false)) await zoomButton.click();
  const zoomAfterReload = await page.locator('input[aria-label="缩放百分比"]').inputValue().catch(() => "");
  check("E3 重新读取后视口为另一会话版本", zoomAfterReload.startsWith("50"), `缩放百分比=${zoomAfterReload}`);
  await zoomAction("放大");
  await sleep(1800);
  const canvasResumed = await readSnapshot(context, "画布1.json");
  check("E4 冲突恢复后画布可继续自动保存", JSON.parse(canvasResumed.text).viewport.zoom > 0.5, `zoom=${JSON.parse(canvasResumed.text).viewport.zoom}`);

  // F. 素材引用：assets 相对路径文档的创建/读取/编辑保存（写操作使用独立会话，避免挤掉页面会话）
  const assetDir = await api(other, "POST", `/projects/${PROJECT}/creative/workspaces/files/mkdir`, {
    data: { path: "assets" }, headers: { "X-CSRF-Token": otherToken, Origin: BASE },
  });
  check("F1 assets 目录创建或已存在", [200, 409].includes(assetDir.status), `status=${assetDir.status}`);
  const created = await api(other, "PUT", `/projects/${PROJECT}/creative/workspaces/files/write?path=${encodeURIComponent("assets/引用说明.md")}&exclusive=true&encoding=utf-8`, {
    data: "素材相对路径引用说明。\n", headers: { "Content-Type": "application/octet-stream", "X-CSRF-Token": otherToken, Origin: BASE },
  });
  check("F2 assets 相对路径文档创建或已存在", [200, 409].includes(created.status), `status=${created.status}`);
  await page.locator(".panelSwitcher").getByText("文档", { exact: true }).click();
  await page.getByRole("button", { name: "刷新文件树", exact: true }).click();
  await sleep(800);
  await page.locator("text=assets").first().click();
  await sleep(500);
  await page.dblclick("text=引用说明.md");
  await page.waitForSelector('textarea[aria-label="编辑 引用说明.md"]', { timeout: 15000 });
  const activeEditor = page.getByLabel("编辑 引用说明.md", { exact: true });
  const assetOriginal = await activeEditor.inputValue();
  const assetEdited = `${assetOriginal}素材引用编辑保存。\n`;
  await activeEditor.fill(assetEdited);
  await sleep(1800);
  const assetSaved = await readSnapshot(context, "assets/引用说明.md");
  check("F3 assets 相对路径文档编辑保存", assetSaved.text === assetEdited);
  const assetBytes = await api(context, "GET", `/projects/${PROJECT}/creative/workspaces/files/read?path=${encodeURIComponent("assets/引用说明.md")}`);
  check("F4 assets 原字节下载可读", assetBytes.status === 200 && String(assetBytes.body).includes("素材引用编辑保存"));

  await shot(page, "final-workspace");
  await other.close();
  await context.close();
} catch (error) {
  checks.push({ name: "验收脚本异常", ok: false, detail: String(error && error.stack || error) });
  console.error(error);
} finally {
  await browser.close();
}
const failed = checks.filter(item => !item.ok);
const result = {
  passed: failed.length === 0, checks, checkCount: checks.length,
  fixture: "explicit Linux/H2 workspace browser fixture via loopback SSH forwarding; static+proxy on localhost:5187; Playwright Chromium headless",
  productionDeploymentChanged: false, realStorageOrModelUsed: false, credentialsIncluded: false,
  consoleErrors: [...new Set(consoleErrors)], browserErrors,
};
fs.writeFileSync(path.join(EVIDENCE, "m3-w03-browser.json"), JSON.stringify(result, null, 2) + "\n");
console.log(`RESULT ${result.passed ? "PASS" : "FAIL"} ${checks.length - failed.length}/${checks.length}`);
process.exit(result.passed ? 0 : 1);
