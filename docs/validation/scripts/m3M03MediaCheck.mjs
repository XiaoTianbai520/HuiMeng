// M3-03 真实浏览器验收：项目文件页的 Qwen 文生图/单图编辑、规格回显与取消。
// 依赖 MediaBrowserAcceptanceHarness（合成 ComfyUI + 内存对象存储）与 M3W03DevServer 静态代理。
// 用法：HM_BROWSER_PROJECT=<项目ID> node docs/validation/scripts/m3M03MediaCheck.mjs
import { createRequire } from "node:module";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(
  process.env.PLAYWRIGHT_CORE_PATH ?? "C:/Users/c1338/AppData/Local/npm-cache/_npx/31e32ef8478fbf80/node_modules/playwright-core",
);
const { chromium } = require("playwright-core");

const BASE = process.env.HM_BROWSER_ORIGIN ?? "http://localhost:5187";
const PROJECT = process.env.HM_BROWSER_PROJECT ?? "";
const USER = process.env.HM_BROWSER_USER ?? "ui-member";
const PASSWORD = process.env.HM_BROWSER_PASSWORD ?? "M3-media-fixture-only";
const EXECUTABLE =
  process.env.PLAYWRIGHT_CHROMIUM_PATH ??
  "C:/Users/c1338/AppData/Local/ms-playwright/chromium_headless_shell-1208/chrome-headless-shell-win64/chrome-headless-shell.exe";
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..");
const EVIDENCE = path.join(ROOT, "docs/validation/evidence");

const results = [];
function check(name, ok, detail = "") {
  results.push({ name, ok: Boolean(ok), detail });
  console.log(`${ok ? "PASS" : "FAIL"} ${name}${detail ? ` :: ${detail}` : ""}`);
  if (!ok) throw new Error(`M3-03 browser check failed: ${name}`);
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function shot(page, name) {
  fs.mkdirSync(EVIDENCE, { recursive: true });
  await page.screenshot({ path: path.join(EVIDENCE, `m3-m03-${name}.png`), fullPage: true });
}

async function statusText(page) {
  return (await page.locator('p[role="status"]').innerText().catch(() => "")) || "";
}

async function waitStatus(page, needle, timeout = 60000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if ((await statusText(page)).includes(needle)) return true;
    await sleep(300);
  }
  return false;
}

const browser = await chromium.launch({ headless: true, executablePath: EXECUTABLE });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
try {
  if (!PROJECT) throw new Error("缺少 HM_BROWSER_PROJECT");
  await page.goto(`${BASE}/#/login`, { waitUntil: "domcontentloaded" });
  await page.getByRole("textbox").first().fill(USER);
  await page.locator('input[type="password"]').fill(PASSWORD);
  await page.getByRole("button", { name: /登录|登 录/ }).click();
  await page.waitForURL(/#\/(home|workspace)/, { timeout: 30000 });
  check("A1 成员登录成功", true);

  await page.goto(`${BASE}/#/projects/${PROJECT}/files`, { waitUntil: "domcontentloaded" });
  await page.getByRole("form", { name: "受控图片生成" }).waitFor({ timeout: 30000 });
  await page.waitForFunction(() =>
    Array.from(document.querySelectorAll("#imageModel option")).some(option => option.value === "qwenImage21Text"),
  { timeout: 30000 });
  const modelOptions = await page.locator("#imageModel option").evaluateAll(nodes => nodes.map(n => n.value));
  check("A2 两种 Qwen 模型可选", modelOptions.includes("qwenImage21Text") && modelOptions.includes("qwenImage21Edit"), modelOptions.join(","));
  const sizes = await page.locator("#imageSize option").evaluateAll(nodes => nodes.map(n => n.value));
  const ratios = await page.locator("#imageRatio option").evaluateAll(nodes => nodes.map(n => n.value));
  check("A3 规格选项完整", sizes.join(",") === "1K,0.5MP,2K" && ratios.length === 8, `${sizes.join("/")} ${ratios.length}`);

  await page.selectOption("#imageModel", "qwenImage21Text");
  await page.selectOption("#imageSize", "1K");
  await page.selectOption("#imageRatio", "1:1");
  await page.locator("#imagePrompt").fill("合成验收：浅色木桌上的白色陶瓷杯，简洁静物");
  await page.getByRole("button", { name: "生成图片", exact: true }).click();
  const textDone = await waitStatus(page, "实际规格 1K 1:1 1024×1024", 90000);
  check("B1 文生图完成并回显 1K 1:1 规格", textDone, await statusText(page));
  await shot(page, "text-square");

  const rows = await page.locator(".el-table__row").count();
  check("B2 结果进入项目素材列表", rows >= 1, `rows=${rows}`);

  await page.selectOption("#imageModel", "qwenImage21Edit");
  const referenceOptions = await page.locator("#imageReference option").evaluateAll(nodes => nodes.map(n => n.value));
  check("C1 单图编辑列出项目内 PNG 参考图", referenceOptions.filter(Boolean).length >= 1, referenceOptions.join(","));
  await page.selectOption("#imageReference", referenceOptions.filter(Boolean)[0]);
  await page.selectOption("#imageSize", "0.5MP");
  await page.selectOption("#imageRatio", "16:9");
  await page.locator("#imagePrompt").fill("把杯子改成蓝色，保留桌面与光线");
  await page.getByRole("button", { name: "生成图片", exact: true }).click();
  const editDone = await waitStatus(page, "实际规格 0.5MP 16:9", 90000);
  check("C2 单图编辑完成并回显 0.5MP 16:9", editDone, await statusText(page));
  const wideSpec = (await statusText(page)).match(/0\.5MP 16:9 (\d+)×(\d+)/);
  check("C3 宽画幅按 32 对齐且更宽", wideSpec && Number(wideSpec[1]) % 32 === 0 && Number(wideSpec[1]) > Number(wideSpec[2]), wideSpec?.[0] ?? "");
  await shot(page, "edit-wide");

  await page.selectOption("#imageModel", "qwenImage21Text");
  await page.selectOption("#imageSize", "1K");
  await page.selectOption("#imageRatio", "1:1");
  await page.locator("#imagePrompt").fill("[slow] 取消验收");
  await page.getByRole("button", { name: "生成图片", exact: true }).click();
  const cancelButton = page.getByRole("button", { name: "取消生成", exact: true });
  await cancelButton.waitFor({ timeout: 30000 });
  await cancelButton.click();
  const cancelled = await waitStatus(page, "已确认取消", 60000);
  check("D1 取消经执行器确认后释放名额", cancelled, await statusText(page));
  await shot(page, "cancelled");

  const specEcho = await page.locator("p.muted").first().innerText();
  check("E1 页面说明默认规格策略", specEcho.includes("1K") && specEcho.includes("0.5MP"), specEcho.slice(0, 60));
  check("E2 全部检查通过", results.every(item => item.ok), `${results.length}`);
} catch (error) {
  await shot(page, "failure").catch(() => {});
  results.push({ name: "FAILURE", ok: false, detail: String(error && error.message ? error.message : error) });
  throw error;
} finally {
  fs.mkdirSync(EVIDENCE, { recursive: true });
  fs.writeFileSync(
    path.join(EVIDENCE, "m3-m03-browser.json"),
    `${JSON.stringify({ generatedAt: new Date().toISOString(), origin: BASE, project: PROJECT, results }, null, 2)}\n`,
  );
  await context.close();
  await browser.close();
}
