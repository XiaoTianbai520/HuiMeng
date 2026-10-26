// M3-09 节点运行期加载：已安装节点由页面取回脚本并在浏览器注册为 remote-<name>。
// 依赖：真实 Spring 夹具（CapabilityHarness，已安装合成节点）+ Vite 开发服务器（5187）。
// 用法：HM_NODE_ORIGIN=http://localhost:5187 HM_NODE_PROJECT=<projectId> node docs/validation/scripts/m3M09NodeLoad.mjs
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

const BASE = process.env.HM_NODE_ORIGIN ?? "http://localhost:5187";
const PROJECT = process.env.HM_NODE_PROJECT;
if (!PROJECT) throw new Error("请设置 HM_NODE_PROJECT");
const USER = process.env.HM_NODE_USER ?? "cap-admin";
const PASSWORD = process.env.HM_NODE_PASSWORD ?? "M3-capability-fixture-only";
const EXECUTABLE = process.env.PLAYWRIGHT_CHROMIUM_PATH || undefined;
const EVIDENCE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../evidence");
const checks = [];
const pageErrors = [];
const consoleErrors = [];
const responses = [];
const failures = [];
let loggedIn = false;

function check(name, ok, detail = "") {
  checks.push({ name, ok: Boolean(ok), detail: String(detail).slice(0, 400) });
  console.log(`${ok ? "PASS" : "FAIL"} ${name}${detail ? " :: " + String(detail).slice(0, 200) : ""}`);
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

const browser = await chromium.launch({ headless: true, ...(EXECUTABLE ? { executablePath: EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL: BASE, viewport: { width: 1500, height: 950 } });
const page = await context.newPage();
page.on("pageerror", error => pageErrors.push(String(error).slice(0, 200)));
page.on("console", message => {
  // 登录前的会话探测 401 与 favicon 404 属正常，登录成功后仍报错才算问题。
  if (loggedIn && message.type() === "error") consoleErrors.push(message.text().slice(0, 200));
});
page.on("response", async response => {
  const url = new URL(response.url());
  if (url.pathname.startsWith("/api/v1/capabilities/"))
    responses.push({ status: response.status(), path: url.pathname + url.search });
  if (loggedIn && response.status() >= 400)
    failures.push({ status: response.status(), method: response.request().method(), path: url.pathname + url.search });
});

try {
  await page.goto("/");
  await page.waitForSelector('input[type="password"]', { timeout: 30000 });
  await page.fill('input[type="text"]', USER);
  await page.fill('input[type="password"]', PASSWORD);
  await page.click('button[type="submit"]');
  await page.waitForSelector("text=能力目录验收", { timeout: 30000 });
  loggedIn = true;
  check("登录并进入首页", true);
  await sleep(800);

  await page.getByRole("button", { name: /打开项目 能力目录验收/ }).click();
  await page.waitForSelector("text=画布1", { timeout: 30000 });
  check("打开画布工作区", true);

  await page.waitForFunction(() => window.__hmDemoNodeLoaded === true, null, { timeout: 30000 });
  const loaded = await page.evaluate(() => ({
    registered: Boolean(window.toonflowNodes && window.toonflowNodes.demoNode),
    renderable: typeof (window.toonflowNodes?.demoNode?.render) === "function",
  }));
  check("节点脚本执行并注册 demoNode", loaded.registered, loaded);
  check("注册的节点带有渲染入口", loaded.renderable, loaded);

  const catalog = responses.find(item => item.path.startsWith("/api/v1/capabilities/nodes/get"));
  check("节点目录返回 200", catalog?.status === 200, catalog);
  const script = responses.find(item => item.path.startsWith("/api/v1/capabilities/nodes/files"));
  check("节点脚本返回 200", script?.status === 200, script);
  const renderers = responses.find(item => item.path.startsWith("/api/v1/capabilities/tools/renderers"));
  check("工具界面目录未报错", !renderers || renderers.status === 200, renderers);
  check("无未捕获页面错误", pageErrors.length === 0, pageErrors.slice(0, 3));
  check("无控制台错误", consoleErrors.length === 0, consoleErrors.slice(0, 3));
  check("页面无失败请求", failures.length === 0, failures.slice(0, 5));

  fs.mkdirSync(EVIDENCE, { recursive: true });
  fs.writeFileSync(
    path.join(EVIDENCE, "m3-m09-node-load.json"),
    JSON.stringify({ createdAt: new Date().toISOString(), checks, pageErrors, consoleErrors, failures, responses }, null, 2)
  );
  if (checks.some(item => !item.ok)) process.exitCode = 1;
  console.log(`M3-09 node load checks: ${checks.filter(item => item.ok).length}/${checks.length} passed`);
} catch (error) {
  console.error("FAILED", error);
  process.exitCode = 1;
} finally {
  await browser.close();
}
