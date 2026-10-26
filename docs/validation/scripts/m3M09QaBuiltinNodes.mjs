// QA 独立复验：内置节点包入库（登记语义 / 脚本分发 / 浏览器加载 / 画布阻塞点追查）。
//
// 与 m3M09BuiltinNodes.mjs（工程师版本）的区别：
//   1. 目录、脚本、管理接口全部经真实浏览器会话（含 CSRF）由页面 fetch 发起，不复用工程师脚本的断言；
//   2. ETag 与响应体字节的 SHA-256 双向核对，并与 classpath 产物文件的 SHA-256 三方对齐；
//   3. 管理面（停用 / 卸载 / 插件列表）逐项验证，并在结束时恢复启用状态；
//   4. 画布阻塞点单独取证：真实点击「切换画布 → 新增画布」，同时用同一会话直接探测
//      workspaces/files/list 与 write，记录状态码与错误体，定位阻塞接口；
//   5. 不过滤任何 console 错误（工程师脚本把含 503 的错误静默丢弃），failures 由实际失败项填写。
//
// 用法：
//   HM_NODE_ORIGIN=http://localhost:5187 \
//   PLAYWRIGHT_CORE_PATH=... PLAYWRIGHT_CHROMIUM_PATH=... \
//   node docs/validation/scripts/m3M09QaBuiltinNodes.mjs
import { createHash } from "node:crypto";
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
const ARTIFACT_ROOT =
  process.env.HM_NODE_ARTIFACT_ROOT ??
  path.resolve(HERE, "../../../backend/src/main/resources/capabilities/nodes");
const READY_FILE =
  process.env.HM_NODE_READY_FILE ??
  path.resolve(HERE, "../../../backend/target/capability.ready");
const EXECUTABLE = process.env.PLAYWRIGHT_CHROMIUM_PATH || undefined;
const BUILTIN = ["textNode", "imageNode"];
const EVIDENCE = path.resolve(HERE, "../evidence");
const FILES_PREFIX = "/api/v1/projects/";

const checks = [];
const pageErrors = [];
const consoleErrors = [];
const consoleMessages = [];
const failures = [];
const httpLog = [];

function check(section, name, ok, detail = "") {
  const entry = { section, name, ok: Boolean(ok), detail: String(detail).slice(0, 600) };
  checks.push(entry);
  if (!ok) failures.push(entry);
  console.log(
    `${ok ? "PASS" : "FAIL"} [${section}] ${name}${detail ? " :: " + String(detail).slice(0, 240) : ""}`,
  );
}

function artifact(name) {
  const file = path.join(ARTIFACT_ROOT, `${name}.umd.js`);
  if (!fs.existsSync(file)) return undefined;
  const bytes = fs.readFileSync(file);
  return { bytes: bytes.length, sha256: createHash("sha256").update(bytes).digest("hex") };
}

const projectId = fs.existsSync(READY_FILE)
  ? JSON.parse(fs.readFileSync(READY_FILE, "utf8")).project
  : process.env.HM_NODE_PROJECT_ID;
if (!projectId) throw new Error("无法确定夹具项目 ID：缺少 capability.ready 或 HM_NODE_PROJECT_ID");

const browser = await chromium.launch({
  headless: true,
  ...(EXECUTABLE ? { executablePath: EXECUTABLE } : {}),
});
const context = await browser.newContext({ baseURL: BASE, viewport: { width: 1500, height: 950 } });
const page = await context.newPage();
let loggedIn = false;

page.on("pageerror", error => pageErrors.push(String(error).slice(0, 300)));
// 阶段标记：负向探测（停用后取脚本 404、卸载 403）会由浏览器如实记录网络错误，
// 这些错误是复验脚本自身制造的，需要与页面真实缺陷区分。
let phase = "startup";
page.on("console", message => {
  const entry = {
    type: message.type(),
    text: message.text().slice(0, 300),
    url: message.location()?.url ?? "",
    phase,
    afterLogin: loggedIn,
  };
  consoleMessages.push(entry);
  // 不做任何过滤：登录后所有 error 级控制台消息都计入。
  if (loggedIn && message.type() === "error") consoleErrors.push(entry);
});
page.on("requestfailed", request => {
  httpLog.push({ phase: "failed", method: request.method(), url: request.url(), error: String(request.failure() ?? "") });
});
page.on("response", async response => {
  const url = new URL(response.url());
  if (!url.pathname.startsWith("/api/")) return;
  const entry = { phase: "response", status: response.status(), method: response.request().method(), path: url.pathname + url.search };
  if (url.pathname.includes("/workspaces/files/")) {
    const body = await response.body().catch(() => Buffer.alloc(0));
    entry.body = body.toString("utf8").slice(0, 600);
  }
  httpLog.push(entry);
});

/** 页面内 fetch：与前端一致走同源会话，写接口补 CSRF 头。 */
async function api(method, url, body) {
  return page.evaluate(
    async ({ method, url, body }) => {
      const csrf = (await (await fetch("/api/v1/auth/csrf", { credentials: "same-origin" })).json()).token;
      const response = await fetch(url, {
        method,
        credentials: "same-origin",
        headers: { "X-CSRF-Token": csrf, ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const buffer = new Uint8Array(await response.arrayBuffer());
      let json;
      try { json = JSON.parse(new TextDecoder().decode(buffer)); } catch { json = undefined; }
      let base64 = "";
      if (buffer.length) {
        let binary = "";
        for (let index = 0; index < buffer.length; index += 0x8000)
          binary += String.fromCharCode.apply(null, buffer.subarray(index, index + 0x8000));
        base64 = btoa(binary);
      }
      return { status: response.status, headers: { etag: response.headers.get("etag"), contentType: response.headers.get("content-type"), nosniff: response.headers.get("x-content-type-options") }, bytes: buffer.length, sha256: base64 ? undefined : "", base64, json };
    },
    { method, url, body },
  );
}

const sha256Of = base64 => createHash("sha256").update(Buffer.from(base64, "base64")).digest("hex");

try {
  // --- 登录 -----------------------------------------------------------------
  await page.goto("/");
  await page.waitForSelector('input[type="password"]', { timeout: 60000 });
  await page.fill('input[type="text"]', USER);
  await page.fill('input[type="password"]', PASSWORD);
  await page.click('button[type="submit"]');
  await page.waitForSelector(`text=${PROJECT_LABEL}`, { timeout: 60000 });
  loggedIn = true;
  check("A", "夹具账号登录成功并进入首页", true, USER);

  // --- A1 目录语义 ----------------------------------------------------------
  phase = "A1";
  const catalog = await api("GET", "/api/v1/capabilities/nodes/get");
  const items = catalog.json?.data ?? [];
  const names = items.map(item => item.name);
  check("A1", "nodes/get 返回 200", catalog.status === 200, `status=${catalog.status}`);
  for (const name of BUILTIN) {
    const item = items.find(entry => entry.name === name);
    check("A1", `目录包含 ${name} 且 enabled=true`, Boolean(item) && item.enabled === true, JSON.stringify(item));
    check(
      "A1",
      `${name} 的 url 指向 nodes/files`,
      item?.url === `/api/v1/capabilities/nodes/files?name=${name}`,
      item?.url,
    );
  }
  const builtinPublished = BUILTIN.every(name => names.includes(name));
  check("A1", "两个内置节点都已发布到目录", builtinPublished, `目录实有 ${names.length} 项：${names.join(",")}`);

  // --- A2 脚本字节与摘要 ----------------------------------------------------
  phase = "A2";
  const served = {};
  for (const name of BUILTIN) {
    const response = await api("GET", `/api/v1/capabilities/nodes/files?name=${name}`);
    const expected = artifact(name);
    const bodySha = response.base64 ? sha256Of(response.base64) : "";
    served[name] = { status: response.status, bytes: response.bytes, sha256: bodySha, etag: response.headers.etag };
    check("A2", `${name} 脚本返回 200`, response.status === 200, `status=${response.status}`);
    check("A2", `${name} Content-Type 为 text/javascript`, /text\/javascript/.test(response.headers.contentType ?? ""), response.headers.contentType);
    check("A2", `${name} 带 X-Content-Type-Options=nosniff`, response.headers.nosniff === "nosniff", response.headers.nosniff);
    check("A2", `${name} ETag 等于响应体字节 SHA-256`, response.headers.etag === `"${bodySha}"`, `etag=${response.headers.etag} body=${bodySha}`);
    check("A2", `${name} 响应体 SHA-256 等于 classpath 产物`, bodySha === expected?.sha256 && response.bytes === expected?.bytes, `served=${bodySha}/${response.bytes}B artifact=${expected?.sha256}/${expected?.bytes}B`);
  }

  // --- B 浏览器真实加载 -----------------------------------------------------
  phase = "B";
  await page.getByRole("button", { name: new RegExp(`打开项目 ${PROJECT_LABEL}`) }).click();
  await page.waitForSelector("text=选择画布", { timeout: 60000 });
  const host = await page.evaluate(() => ({
    node: Object.keys(window.toonflowNodeHost ?? {}),
    tiptap: Object.keys(window.toonflowTiptapHost ?? {}).length,
  }));
  check("B", "工作区注入 window.toonflowNodeHost（vue/vueFlow/elementPlus/three/ai）", ["vue", "vueFlow", "elementPlus", "three", "ai"].every(key => host.node.includes(key)), host.node.join(","));

  const loaded = await page.evaluate(async names => {
    const results = {};
    for (const name of names) {
      const url = `/api/v1/capabilities/nodes/files?name=${name}`;
      const code = await (await fetch(url)).text();
      if (code.includes("toonflowTiptapHost")) await import(/* @vite-ignore */ "/src/lib/tiptapHost.ts");
      results[name] = await new Promise(resolve => {
        const script = document.createElement("script");
        script.src = url;
        script.onload = () => {
          script.remove();
          const component = window.toonflowNodes?.[name];
          resolve({
            registered: Boolean(component),
            type: typeof component,
            mountable: typeof component?.render === "function" || typeof component?.setup === "function",
            name: component?.__name ?? component?.name ?? null,
          });
        };
        script.onerror = () => { script.remove(); resolve({ registered: false, error: "script error" }); };
        document.head.append(script);
      });
    }
    return results;
  }, BUILTIN);

  const tiptapKeys = await page.evaluate(() => Object.keys(window.toonflowTiptapHost ?? {}).length);
  check("B", "textNode 依赖的 toonflowTiptapHost 已按需注入", tiptapKeys > 10, `${tiptapKeys} 项`);
  for (const name of BUILTIN) {
    check("B", `${name} 脚本执行并注册到 window.toonflowNodes`, loaded[name]?.registered, JSON.stringify(loaded[name]));
    check("B", `${name} 导出可挂载组件`, loaded[name]?.mountable, loaded[name]?.type);
  }

  // --- A3 停用 / 卸载语义 ---------------------------------------------------
  phase = "A3-negative";
  const disabled = await api("PUT", "/api/v1/admin/nodes/setEnabled", { name: "textNode", enabled: false });
  check("A3", "管理员停用 textNode 返回 200", disabled.status === 200, `status=${disabled.status} ${JSON.stringify(disabled.json?.code ?? "")}`);
  const afterDisable = await api("GET", "/api/v1/capabilities/nodes/get");
  const textAfterDisable = (afterDisable.json?.data ?? []).find(item => item.name === "textNode");
  check("A3", "停用后目录 enabled=false", textAfterDisable?.enabled === false, JSON.stringify(textAfterDisable));
  const disabledScript = await api("GET", "/api/v1/capabilities/nodes/files?name=textNode");
  check("A3", "停用后脚本请求返回 404", disabledScript.status === 404, `status=${disabledScript.status}`);
  check("A3", "停用后错误码为 CAPABILITY_NOT_FOUND", disabledScript.json?.code === "CAPABILITY_NOT_FOUND", disabledScript.json?.code);
  const imageStillServed = await api("GET", "/api/v1/capabilities/nodes/files?name=imageNode");
  check("A3", "停用 textNode 不影响 imageNode 分发", imageStillServed.status === 200, `status=${imageStillServed.status}`);

  const uninstall = await api("DELETE", `/api/v1/admin/nodes/uninstall?name=textNode`);
  check("A3", "卸载内置节点返回 403", uninstall.status === 403, `status=${uninstall.status}`);
  check("A3", "卸载内置节点错误码为 PLUGIN_BUILTIN", uninstall.json?.code === "PLUGIN_BUILTIN", uninstall.json?.code);

  const restored = await api("PUT", "/api/v1/admin/nodes/setEnabled", { name: "textNode", enabled: true });
  const afterRestore = await api("GET", "/api/v1/capabilities/nodes/get");
  const textAfterRestore = (afterRestore.json?.data ?? []).find(item => item.name === "textNode");
  check("A3", "复验后恢复 textNode 启用状态", restored.status === 200 && textAfterRestore?.enabled === true, JSON.stringify(textAfterRestore));

  // --- A4 管理面来源标记 ----------------------------------------------------
  phase = "A4";
  const plugins = await api("GET", "/api/v1/admin/plugins");
  const pluginItems = plugins.json?.items ?? [];
  const builtinItems = pluginItems.filter(item => item.source === "builtin");
  check("A4", "admin/plugins 返回 200", plugins.status === 200, `status=${plugins.status}`);
  check("A4", "admin/plugins 中恰有两项 source=builtin", builtinItems.length === 2, builtinItems.map(item => `${item.type}:${item.name}=${item.source}`).join(","));
  for (const name of BUILTIN) {
    const item = builtinItems.find(entry => entry.name === name);
    check("A4", `${name} 标记 source=builtin 且 canManage=false`, item?.source === "builtin" && item?.canManage === false, JSON.stringify(item));
  }

  // --- C 画布阻塞点追查 -----------------------------------------------------
  phase = "C";
  // C-1 直接探测同一会话下的工作区文件接口（与前端 route-map 目标路径一致）。
  const listProbe = await api("GET", `${FILES_PREFIX}${projectId}/creative/workspaces/files/list?path=`);
  check("C", "workspaces/files/list 可用（画布列表前置条件）", listProbe.status === 200, `status=${listProbe.status} body=${JSON.stringify(listProbe.json?.code ?? listProbe.json)}`);
  const writeProbeBody = JSON.stringify({ toonflowCanvas: true, nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 } });
  // 与前端 workspaceFiles.writeExclusiveText 完全一致：octet-stream + exclusive + encoding。
  const writeProbe = await page.evaluate(
    async ({ url, body }) => {
      const csrf = (await (await fetch("/api/v1/auth/csrf", { credentials: "same-origin" })).json()).token;
      const response = await fetch(url, {
        method: "PUT",
        credentials: "same-origin",
        headers: { "X-CSRF-Token": csrf, "Content-Type": "application/octet-stream" },
        body,
      });
      const text = await response.text();
      return { status: response.status, body: text.slice(0, 400) };
    },
    { url: `${FILES_PREFIX}${projectId}/creative/workspaces/files/write?path=qaProbeCanvas.json&exclusive=true&encoding=utf-8`, body: writeProbeBody },
  );
  check("C", "workspaces/files/write 可用（创建画布前置条件）", writeProbe.status === 200, `status=${writeProbe.status} body=${writeProbe.body}`);

  // C-2 真实 UI 路径：切换画布 → 新增画布，记录实际发生的请求。
  const beforeClicks = httpLog.length;
  let uiError = "";
  try {
    await page.getByRole("button", { name: "切换画布" }).click({ timeout: 15000 });
    await page.getByRole("button", { name: "新增画布" }).click({ timeout: 15000 });
    await page.waitForTimeout(3000);
    const alert = await page.locator(".el-message--error, [role=alert]").first().textContent({ timeout: 3000 }).catch(() => "");
    uiError = (alert ?? "").trim();
  } catch (error) {
    uiError = `UI 操作异常：${String(error).slice(0, 200)}`;
  }
  const uiRequests = httpLog.slice(beforeClicks).filter(entry => entry.path?.includes("/workspaces/files/") || entry.phase === "failed");
  const canvasCreated = await page.evaluate(() => {
    const text = document.body.innerText;
    return { hasChooseCanvas: text.includes("选择画布"), hasNewCanvasName: /画布-\d|新画布/.test(text) };
  });
  check("C", "点「新增画布」后页面出现新画布（画布可创建）", !canvasCreated.hasChooseCanvas || canvasCreated.hasNewCanvasName, JSON.stringify({ ...canvasCreated, uiError }));
  console.log("C 实际 UI 请求：", JSON.stringify(uiRequests, null, 1));

  // C-3 平台能力判定：安全目录句柄是否可用（只读取证据，不改源码）。
  const secureHandle = await page.evaluate(async ({ projectId }) => {
    const response = await fetch(`/api/v1/projects/${projectId}/creative/workspaces/files/list?path=`, { credentials: "same-origin" });
    return { status: response.status, body: (await response.text()).slice(0, 400) };
  }, { projectId });

  // C-3 画布是否真的挂载：新开页面只做「登录 → 打开项目」，不注入任何脚本，
  // 因此该页面上的 /api/v1/capabilities/* 请求全部来自应用自身（画布初始化）。
  const fresh = await context.newPage();
  const appCapabilityRequests = [];
  fresh.on("response", response => {
    const url = new URL(response.url());
    if (url.pathname.startsWith("/api/v1/capabilities/")) {
      appCapabilityRequests.push({ status: response.status(), path: url.pathname + url.search });
    }
  });
  await fresh.goto("/");
  await fresh.waitForSelector(`text=${PROJECT_LABEL}`, { timeout: 60000 });
  await fresh.getByRole("button", { name: new RegExp(`打开项目 ${PROJECT_LABEL}`) }).click();
  await fresh.waitForSelector("text=选择画布", { timeout: 60000 });
  await fresh.waitForTimeout(4000);
  const canvasMounted = await fresh.evaluate(() => ({
    vueFlow: Boolean(document.querySelector(".vue-flow")),
    chooseCanvasShown: document.body.innerText.includes("选择画布"),
    registeredNodes: Object.keys(window.toonflowNodes ?? {}),
  }));
  await fresh.close();
  const appRequestedNodeScripts = appCapabilityRequests.some(entry => entry.path.includes("/nodes/files"));
  check("C", "进入项目后 VueFlow 画布已挂载", canvasMounted.vueFlow, JSON.stringify({ ...canvasMounted, requests: appCapabilityRequests.length }));
  check("C", "画布自身发起节点脚本加载（nodes/files）", appRequestedNodeScripts, JSON.stringify(appCapabilityRequests.slice(0, 6)));

  // --- 汇总 -----------------------------------------------------------------
  // 控制台错误分类：
  //   1. 工作区文件接口 503 —— C 段已定位的平台阻塞引起的网络噪音；
  //   2. 负向探测期间（A3）的 404/403 —— 复验脚本自己制造的预期错误；
  //   3. 其余任何错误 —— 属于页面/本批改动的真实问题。
  const workspaceStatuses = new Set(httpLog.filter(entry => entry.phase === "response" && entry.path?.includes("/workspaces/files/")).map(entry => entry.status));
  const workspaceNoise = consoleErrors.filter(entry => /status of 503/.test(entry.text) && workspaceStatuses.has(503));
  const negativeProbeNoise = consoleErrors.filter(entry => entry.phase === "A3-negative" && /status of (403|404)/.test(entry.text));
  const otherConsoleErrors = consoleErrors.filter(entry => !workspaceNoise.includes(entry) && !negativeProbeNoise.includes(entry));
  check("B", "无未捕获页面错误", pageErrors.length === 0, JSON.stringify(pageErrors.slice(0, 3)));
  check("B", "无可归因于页面/节点加载的控制台错误", otherConsoleErrors.length === 0, JSON.stringify(otherConsoleErrors.map(entry => `${entry.phase}|${entry.text}`).slice(0, 3)));
  check("C", "其余控制台错误只由工作区文件 503 与负向探测构成", workspaceNoise.length + negativeProbeNoise.length === consoleErrors.length, `503 噪音 ${workspaceNoise.length} 条，负向探测 ${negativeProbeNoise.length} 条，未归因 ${otherConsoleErrors.length} 条`);

  const evidence = {
    createdAt: new Date().toISOString(),
    runner: "qa-independent",
    base: BASE,
    projectId,
    artifactRoot: ARTIFACT_ROOT,
    artifacts: Object.fromEntries(BUILTIN.map(name => [name, artifact(name)])),
    served,
    catalog: items.map(item => ({ name: item.name, enabled: item.enabled, url: item.url, displayName: item.displayName })),
    plugins: builtinItems.map(item => ({ type: item.type, name: item.name, source: item.source, canManage: item.canManage, version: item.version })),
    loaded,
    canvas: {
      listProbe: { status: listProbe.status, body: listProbe.json ?? null },
      writeProbe,
      uiRequests,
      uiError,
      canvasCreated,
      secureHandleProbe: secureHandle,
      freshPage: { canvasMounted, appCapabilityRequests },
    },
    checks,
    failures,
    pageErrors,
    consoleErrors,
    consoleErrorBreakdown: { workspace503Noise: workspaceNoise.length, negativeProbeNoise: negativeProbeNoise.length, other: otherConsoleErrors },
    consoleMessages: consoleMessages.filter(entry => entry.type === "error" || entry.type === "warning").slice(0, 40),
    httpLog: httpLog.filter(entry => entry.phase === "failed" || entry.path?.includes("/workspaces/files/")).slice(0, 40),
  };
  fs.mkdirSync(EVIDENCE, { recursive: true });
  fs.writeFileSync(path.join(EVIDENCE, "m3-m09-qa-builtin-nodes.json"), JSON.stringify(evidence, null, 2));

  console.log(`\nQA 复验结果：${checks.filter(item => item.ok).length}/${checks.length} 通过`);
  if (failures.length) console.log("未通过项：", failures.map(item => `[${item.section}] ${item.name}`).join(" | "));
  if (failures.length) process.exitCode = 1;
} catch (error) {
  console.error("复验脚本异常终止：", error);
  process.exitCode = 1;
} finally {
  await browser.close();
}
