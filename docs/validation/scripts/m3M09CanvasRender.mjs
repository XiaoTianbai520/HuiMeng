// M3-05 补验：内置节点在 VueFlow 画布内以实例形式渲染。
//
// 背景：Windows 上 workspaces/files/* 恒 503（JDK 未实现 SecureDirectoryStream），
// 画布 .json 无法创建，因此「放实例看渲染」这一步只能在 Linux 后端上验证。
// 本脚本由 Windows 侧的 Vite dev server 代理到 Linux 夹具，浏览器仍是本机 Chromium。
//
// 用法：
//   HM_NODE_ORIGIN=http://localhost:5187 PLAYWRIGHT_CORE_PATH=... PLAYWRIGHT_CHROMIUM_PATH=... \
//   node docs/validation/scripts/m3M09CanvasRender.mjs
import { createRequire } from "node:module";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
let chromium;
try {
  ({ chromium } = require("playwright-core"));
} catch {
  if (!process.env.PLAYWRIGHT_CORE_PATH)
    throw new Error("请安装 playwright-core 或设置 PLAYWRIGHT_CORE_PATH");
  ({ chromium } = require(process.env.PLAYWRIGHT_CORE_PATH));
}

const HERE = path.dirname(fileURLToPath(import.meta.url));
const BASE = process.env.HM_NODE_ORIGIN ?? "http://localhost:5187";
const USER = process.env.HM_NODE_USER ?? "cap-admin";
const PASSWORD = process.env.HM_NODE_PASSWORD ?? "M3-capability-fixture-only";
const PROJECT_LABEL = process.env.HM_NODE_PROJECT_LABEL ?? "能力目录验收";
const EVIDENCE = path.resolve(HERE, "../evidence");
const SHOT_DIR = process.env.HM_SHOT_DIR ?? path.resolve(HERE, "../../../backend/target/canvas-shots");
const EXECUTABLE = process.env.PLAYWRIGHT_CHROMIUM_PATH || undefined;

fs.mkdirSync(SHOT_DIR, { recursive: true });
fs.mkdirSync(EVIDENCE, { recursive: true });

const checks = [];
const failures = [];
const pageErrors = [];
const consoleErrors = [];
const httpLog = [];
const httpErrors = [];

function check(section, name, ok, detail = "") {
  const entry = { section, name, ok: Boolean(ok), detail: String(detail).slice(0, 800) };
  checks.push(entry);
  if (!ok) failures.push(entry);
  console.log(`${ok ? "PASS" : "FAIL"} [${section}] ${name}${detail ? " :: " + String(detail).slice(0, 300) : ""}`);
}

const browser = await chromium.launch({ executablePath: EXECUTABLE, headless: true });
const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
const page = await context.newPage();
let shotIndex = 0;
async function shot(label) {
  const name = `${String(++shotIndex).padStart(2, "0")}-${label}.png`;
  await page.screenshot({ path: path.join(SHOT_DIR, name), fullPage: false });
  return name;
}

page.on("pageerror", error => pageErrors.push(String(error).slice(0, 300)));
page.on("console", message => {
  if (message.type() === "error") consoleErrors.push(message.text().slice(0, 300));
});
page.on("response", response => {
  const url = new URL(response.url());
  if (response.status() >= 400) httpErrors.push({ status: response.status(), method: response.request().method(), path: url.pathname + url.search });
  if (url.pathname.includes("/workspaces/files/") || url.pathname.includes("/capabilities/nodes"))
    httpLog.push({ status: response.status(), path: url.pathname + url.search });
});

const result = { createdAt: new Date().toISOString(), base: BASE, steps: [] };
try {
  // 1. 登录并打开项目
  await page.goto(BASE + "/");
  await page.waitForSelector('input[type="password"]', { timeout: 60000 });
  await page.fill('input[type="text"]', USER);
  await page.fill('input[type="password"]', PASSWORD);
  await page.click('button[type="submit"]');
  await page.waitForSelector(`text=${PROJECT_LABEL}`, { timeout: 60000 });
  await page.getByRole("button", { name: new RegExp(`打开项目 ${PROJECT_LABEL}`) }).click();
  await page.waitForSelector("text=选择画布", { timeout: 60000 });
  await shot("project-opened");
  result.steps.push({ step: "open-project", ok: true });

  // 2. 新增画布（Windows 上这一步会 503，Linux 上应成功）
  const before = httpLog.length;
  await page.getByRole("button", { name: "切换画布" }).click({ timeout: 20000 });
  await page.getByRole("button", { name: "新增画布" }).click({ timeout: 20000 });
  await page.waitForTimeout(3500);
  const writeRequests = httpLog.slice(before).filter(entry => entry.path.includes("/workspaces/files/"));
  const alert = await page.locator(".el-message--error, [role=alert]").first().textContent({ timeout: 2000 }).catch(() => "");
  check("A", "新增画布未报错", !String(alert ?? "").trim(), String(alert ?? "").trim());
  // 409 表示同名画布已存在（exclusive 创建的正常拒绝），关键是没有 503。
  check(
    "A",
    "画布写入未出现平台 503",
    writeRequests.every(entry => entry.status !== 503),
    JSON.stringify(writeRequests.slice(0, 4)),
  );
  check(
    "A",
    "画布写入成功（至少一次 200/201）",
    writeRequests.some(entry => entry.status === 200 || entry.status === 201),
    JSON.stringify(writeRequests.map(entry => entry.status)),
  );
  const afterCreate = await page.evaluate(() => ({
    text: document.body.innerText.slice(0, 400),
    chooseCanvas: document.body.innerText.includes("选择画布"),
    nodes: document.querySelectorAll(".vue-flow__node").length,
    vueFlow: Boolean(document.querySelector(".vue-flow")),
  }));
  await shot("canvas-created");
  check("A", "画布已创建（不再停留在「选择画布」）", !afterCreate.chooseCanvas, JSON.stringify({ chooseCanvas: afterCreate.chooseCanvas, vueFlow: afterCreate.vueFlow }));
  check("A", "VueFlow 容器已挂载", afterCreate.vueFlow, String(afterCreate.vueFlow));

  // 3. 双击画布打开节点菜单
  const pane = page.locator(".vue-flow__pane").first();
  await pane.waitFor({ timeout: 20000 });
  const box = await pane.boundingBox();
  const point = { x: (box?.x ?? 400) + (box?.width ?? 800) / 2, y: (box?.y ?? 300) + (box?.height ?? 600) / 2 };
  await page.mouse.dblclick(point.x, point.y);
  await page.waitForTimeout(1200);
  await shot("node-menu");
  const menuText = await page.evaluate(() => document.body.innerText.slice(0, 800));
  result.menuText = menuText;

  // 4. 点「文本」放入内置 textNode 实例
  let placed = "";
  const wantLabel = process.env.HM_NODE_LABEL ?? "文本";
  for (const label of [wantLabel, ...["文本", "图片"].filter(item => item !== wantLabel)]) {
    const item = page.getByText(label, { exact: true }).first();
    if ((await item.count()) > 0) {
      await item.click({ timeout: 8000 }).catch(() => {});
      await page.waitForTimeout(2500);
      const count = await page.evaluate(() => document.querySelectorAll(".vue-flow__node").length);
      if (count > 0) {
        placed = label;
        break;
      }
    }
  }
  await shot("node-placed");
  const rendered = await page.evaluate(() => {
    const nodes = [...document.querySelectorAll(".vue-flow__node")];
    return {
      count: nodes.length,
      types: nodes.map(node => node.getAttribute("data-id") ?? node.className),
      html: nodes.map(node => node.innerHTML.slice(0, 500)),
      text: nodes.map(node => node.innerText.slice(0, 300)),
    };
  });
  check("B", "画布内已放入节点实例", rendered.count > 0, JSON.stringify({ placed, count: rendered.count }));
  check(
    "B",
    "节点实例渲染出非空 DOM（不只是空壳）",
    rendered.html.some(html => (html ?? "").length > 200),
    `html长度=${rendered.html.map(html => (html ?? "").length).join(",")}`,
  );
  check(
    "B",
    "节点内容可见（出现标题或交互元素）",
    rendered.text.some(text => (text ?? "").trim().length > 0) || rendered.html.some(html => /textarea|input|button/i.test(html ?? "")),
    JSON.stringify(rendered.text).slice(0, 300),
  );
  result.rendered = rendered;

  // 5. 回读画布文件：确认节点确实落盘，而不是只存在于前端内存
  const persisted = await page.evaluate(async label => {
    const csrf = (await (await fetch("/api/v1/auth/csrf", { credentials: "same-origin" })).json()).token;
    const projects = await (await fetch("/api/v1/projects", { credentials: "same-origin" })).json();
    const list = projects?.items ?? projects?.data?.items ?? projects?.data ?? [];
    const project = (Array.isArray(list) ? list : []).find(item => item.name === label);
    if (!project) return { error: "project not found", raw: JSON.stringify(projects).slice(0, 600) };
    const base = `/api/v1/projects/${project.id}/creative/workspaces`;
    const files = await (await fetch(`${base}/files/list?path=`, { credentials: "same-origin" })).json();
    const entries = files?.data?.entries ?? [];
    const canvasFiles = (Array.isArray(entries) ? entries : []).filter(item =>
      /^画布.*\.json$|^canvas.*\.json$/i.test(item.name ?? ""),
    );
    if (!canvasFiles.length) return { error: "canvas file not found", entries: (entries ?? []).map(item => item.name).slice(0, 10) };
    // 有多张画布时，读取「含节点实例」的那一张（新建画布的顺序不固定）。
    let picked = null;
    for (const file of canvasFiles) {
      const read = await fetch(`${base}/files/read?path=${encodeURIComponent(file.name)}`, {
        credentials: "same-origin",
        headers: { "X-CSRF-Token": csrf },
      });
      const text = await read.text();
      let doc = null;
      try { doc = JSON.parse(text); } catch { /* 非 JSON */ }
      const count = Array.isArray(doc?.nodes) ? doc.nodes.length : 0;
      // 取编号最大的画布：它是本次运行新建的那张，避免读到历史画布。
      const index = Number((file.name.match(/(\d+)/) ?? [0, 0])[1]);
      if (count > 0 && (!picked || index > picked.index)) picked = { file: file.name, doc, status: read.status, count, index };
    }
    if (!picked) return { projectId: project.id, canvasFiles: canvasFiles.map(file => file.name), nodeCount: 0, nodeTypes: [] };
    return {
      projectId: project.id,
      fileName: picked.file,
      canvasFiles: canvasFiles.map(file => file.name),
      readStatus: picked.status,
      nodeCount: picked.count,
      nodeTypes: picked.doc.nodes.map(node => node.type),
      nodeIds: picked.doc.nodes.map(node => node.id),
    };
  }, PROJECT_LABEL).catch(error => ({ error: String(error).slice(0, 200) }));
  result.persisted = persisted;
  check(
    "C",
    "画布文件已落盘且包含该节点实例",
    (persisted?.nodeCount ?? 0) > 0,
    JSON.stringify(persisted).slice(0, 400),
  );
  check(
    "C",
    "落盘的节点类型为内置节点（remote-textNode / remote-imageNode）",
    (persisted?.nodeTypes ?? []).every(type => /^remote-(text|image)Node$/.test(type ?? "")),
    JSON.stringify(persisted?.nodeTypes),
  );

  await page.screenshot({ path: path.join(EVIDENCE, process.env.HM_EVIDENCE_SHOT ?? "m3-m09-canvas-render.png"), fullPage: false });
} catch (error) {
  check("X", "脚本执行未抛出异常", false, String(error).slice(0, 400));
  await shot("failure").catch(() => {});
} finally {
  result.checks = checks;
  result.failures = failures;
  result.pageErrors = pageErrors;
  result.consoleErrors = consoleErrors;
  result.httpLog = httpLog;
  result.httpErrors = httpErrors;
  fs.writeFileSync(path.join(EVIDENCE, process.env.HM_EVIDENCE ?? "m3-m09-canvas-render.json"), JSON.stringify(result, null, 2));
  console.log(`\n合计 ${checks.length} 项，失败 ${failures.length} 项；截图目录 ${SHOT_DIR}`);
  await browser.close();
}
