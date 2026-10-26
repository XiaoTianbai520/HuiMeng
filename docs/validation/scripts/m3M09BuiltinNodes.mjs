// 内置节点包真实加载：随部署分发的 textNode/imageNode 由页面从 nodes/files 取回并执行注册。
//
// 依赖：真实 Spring 夹具（CapabilityHarness，HM_CAPABILITY=true）+ Vite 开发服务器（5187，
// HUIMENG_API_TARGET 指向夹具）。页面进入工作区后由 canvas 注入 window.toonflowNodeHost 与
// window.toonflowTiptapHost；本脚本按 loadNodeComponent.ts 的同一做法加载脚本并检查全局导出。
//
// 用法：HM_NODE_ORIGIN=http://localhost:5187 node docs/validation/scripts/m3M09BuiltinNodes.mjs
//
// 说明：画布自身在进入项目后就会请求 nodes/get 与 nodes/files，并把两个内置节点注册进
// window.toonflowNodes（本脚本随后再独立加载一次以核对字节与导出）。Windows 上
// workspaces/files/list 与 workspaces/files/write 恒返回 503「当前文件系统不支持此安全文件操作」，
// 根因是 Windows JDK 的 Files.newDirectoryStream 返回 sun.nio.fs.WindowsDirectoryStream，不实现
// SecureDirectoryStream；因此画布 .json 无法读写，「放置节点实例并看到渲染」这一步需在 Linux 夹具复跑。
// 503 属已知平台限制：本脚本把它单独计入 platformNoise，不与节点脚本自身的错误混为一谈。
import { createHash } from "node:crypto";
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

const HERE = path.dirname(fileURLToPath(import.meta.url));
const BASE = process.env.HM_NODE_ORIGIN ?? "http://localhost:5187";
const USER = process.env.HM_NODE_USER ?? "cap-admin";
const PASSWORD = process.env.HM_NODE_PASSWORD ?? "M3-capability-fixture-only";
const PROJECT_LABEL = process.env.HM_NODE_PROJECT_LABEL ?? "能力目录验收";
const ARTIFACT_ROOT = process.env.HM_NODE_ARTIFACT_ROOT
  ?? path.resolve(HERE, "../../../backend/src/main/resources/capabilities/nodes");
const EXECUTABLE = process.env.PLAYWRIGHT_CHROMIUM_PATH || undefined;
const BUILTIN = ["textNode", "imageNode"];
const EVIDENCE = path.resolve(HERE, "../evidence");
const checks = [];
const pageErrors = [];
const consoleErrors = [];
const responses = [];
const failures = [];
// Windows 上 workspaces/files/* 恒 503（缺安全目录句柄），属平台限制；单独归类而不丢弃。
const platformNoise = [];
let loggedIn = false;

function check(name, ok, detail = "") {
  checks.push({ name, ok: Boolean(ok), detail: String(detail).slice(0, 400) });
  console.log(`${ok ? "PASS" : "FAIL"} ${name}${detail ? " :: " + String(detail).slice(0, 200) : ""}`);
}
function artifactDigest(name) {
  const file = path.join(ARTIFACT_ROOT, `${name}.umd.js`);
  if (!fs.existsSync(file)) return undefined;
  const bytes = fs.readFileSync(file);
  return { bytes: bytes.length, sha256: createHash("sha256").update(bytes).digest("hex") };
}

const browser = await chromium.launch({ headless: true, ...(EXECUTABLE ? { executablePath: EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL: BASE, viewport: { width: 1500, height: 950 } });
const page = await context.newPage();
page.on("pageerror", error => pageErrors.push(String(error).slice(0, 200)));
page.on("console", message => {
  // 登录前的会话探测 401 属正常，登录成功后仍报错才算问题；平台 503 单独归类，不静默丢弃。
  if (!loggedIn || message.type() !== "error") return;
  const text = message.text().slice(0, 200);
  consoleErrors.push(text);
  if (text.includes("503")) platformNoise.push(text);
});
page.on("response", async response => {
  const url = new URL(response.url());
  if (loggedIn && response.status() >= 400)
    failures.push({
      status: response.status(),
      method: response.request().method(),
      path: url.pathname + url.search,
    });
  if (url.pathname.startsWith("/api/v1/capabilities/")) {
    const entry = { status: response.status(), path: url.pathname + url.search };
    if (url.pathname.endsWith("/nodes/files")) {
      const body = await response.body().catch(() => Buffer.alloc(0));
      entry.bytes = body.length;
      entry.sha256 = body.length ? createHash("sha256").update(body).digest("hex") : "";
    }
    responses.push(entry);
  }
});

try {
  await page.goto("/");
  await page.waitForSelector('input[type="password"]', { timeout: 60000 });
  await page.fill('input[type="text"]', USER);
  await page.fill('input[type="password"]', PASSWORD);
  await page.click('button[type="submit"]');
  await page.waitForSelector(`text=${PROJECT_LABEL}`, { timeout: 60000 });
  loggedIn = true;
  check("登录并进入首页", true);

  await page.getByRole("button", { name: new RegExp(`打开项目 ${PROJECT_LABEL}`) }).click();
  await page.waitForSelector("text=选择画布", { timeout: 60000 });
  check("打开工作区并注入节点宿主", true);

  const host = await page.evaluate(() => ({
    node: Object.keys(window.toonflowNodeHost ?? {}),
    tiptap: Object.keys(window.toonflowTiptapHost ?? {}).length,
  }));
  check("window.toonflowNodeHost 注入 vue/vueFlow/elementPlus/three/ai",
    ["vue", "vueFlow", "elementPlus", "three", "ai"].every(key => host.node.includes(key)), host.node.join(","));

  // 与 loadNodeComponent.ts 相同：先读目录，再用 <script> 加载同一来源的脚本。
  const catalog = await page.evaluate(async () => (await fetch("/api/v1/capabilities/nodes/get")).json());
  const items = catalog.data ?? [];
  for (const name of BUILTIN) {
    const item = items.find(entry => entry.name === name);
    check(`目录发布 ${name} 且启用`, Boolean(item?.enabled), JSON.stringify(item));
  }

  const loaded = await page.evaluate(async names => {
    const results = {};
    for (const name of names) {
      const url = `/api/v1/capabilities/nodes/files?name=${name}`;
      // 与 loadNodeComponent.ts 一致：脚本用到 toonflowTiptapHost 时先加载富文本宿主。
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
            kind: typeof component,
            hasRender: typeof component?.render === "function" || typeof component?.setup === "function",
            name: component?.__name ?? component?.name ?? null,
          });
        };
        script.onerror = () => {
          script.remove();
          resolve({ registered: false, error: "script error" });
        };
        document.head.append(script);
      });
    }
    return results;
  }, BUILTIN);

  const tiptap = await page.evaluate(() => Object.keys(window.toonflowTiptapHost ?? {}).length);
  check("textNode 需要的 toonflowTiptapHost 已按需注入", tiptap > 10, `${tiptap} 项`);

  for (const name of BUILTIN) {
    check(`${name} 脚本执行并注册到 window.toonflowNodes`, loaded[name]?.registered, loaded[name]);
    check(`${name} 导出可挂载组件（render/setup）`, loaded[name]?.hasRender, loaded[name]);
    const script = responses.find(entry => entry.path.startsWith(`/api/v1/capabilities/nodes/files?name=${name}`));
    check(`${name} 脚本返回 200 且体积正常`, script?.status === 200 && script?.bytes > 100000, script && { status: script.status, bytes: script.bytes });
    const expected = artifactDigest(name);
    if (expected) {
      check(`${name} 下发字节与 classpath 产物一致`,
        script?.sha256 === expected.sha256 && script?.bytes === expected.bytes,
        { served: script?.sha256, artifact: expected.sha256 });
    }
  }

  check("无未捕获页面错误", pageErrors.length === 0, pageErrors.slice(0, 3));
  check("无非平台控制台错误", consoleErrors.length === platformNoise.length,
    { platform503: platformNoise.length, others: consoleErrors.filter(text => !text.includes("503")).slice(0, 3) });
  check("能力脚本请求无失败响应",
    failures.filter(item => item.path.startsWith("/api/v1/capabilities/")).length === 0,
    failures.filter(item => item.path.startsWith("/api/v1/capabilities/")).slice(0, 3));

  fs.mkdirSync(EVIDENCE, { recursive: true });
  fs.writeFileSync(
    path.join(EVIDENCE, "m3-m09-builtin-nodes.json"),
    JSON.stringify({ createdAt: new Date().toISOString(), base: BASE, artifactRoot: ARTIFACT_ROOT, checks, pageErrors, consoleErrors, platformNoise, failures, responses }, null, 2)
  );
  if (checks.some(item => !item.ok)) process.exitCode = 1;
  console.log(`M3-09 builtin node checks: ${checks.filter(item => item.ok).length}/${checks.length} passed`);
} catch (error) {
  console.error("FAILED", error);
  process.exitCode = 1;
} finally {
  await browser.close();
}
