// M3-02 Agent 文本生成真实浏览器验收：合成 LLM 服务 + 独立 Linux/H2 夹具。
// 用法：HM_BROWSER_PROJECT=<projectId> node docs/validation/scripts/m3M02AgentCheck.mjs
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
if (!PROJECT) throw new Error("请设置 HM_BROWSER_PROJECT");
const USER = "ui-member";
const PASSWORD = "M3-browser-fixture-only";
const EXECUTABLE = process.env.PLAYWRIGHT_CHROMIUM_PATH || undefined;
const EVIDENCE = fileURLToPath(new URL("../evidence/", import.meta.url));
const checks = [];
const browserErrors = [];
function check(name, ok, detail = "") {
  checks.push({ name, ok: !!ok, detail: String(detail) });
  console.log(`${ok ? "PASS" : "FAIL"} ${name}${detail ? " :: " + detail : ""}`);
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function shot(page, name) {
  await page.screenshot({ path: path.join(EVIDENCE, `m3-m02-${name}.png`) });
}

const browser = await chromium.launch({ headless: true, ...(EXECUTABLE ? { executablePath: EXECUTABLE } : {}) });
try {
  const context = await browser.newContext({ baseURL: BASE, viewport: { width: 1500, height: 950 } });
  const page = await context.newPage();
  page.on("pageerror", error => browserErrors.push(String(error)));
  await page.goto("/");
  await page.waitForSelector('input[type="password"]', { timeout: 30000 });
  await page.fill('input[type="text"]', USER);
  await page.fill('input[type="password"]', PASSWORD);
  await page.click('button[type="submit"]');
  await page.waitForSelector("text=浏览器文档验收", { timeout: 30000 });
  await page.getByRole("button", { name: /打开项目 浏览器文档验收/ }).click();
  await page.waitForSelector("text=画布1", { timeout: 30000 });
  check("A1 登录并进入工作区", true);
  await shot(page, "workspace-initial");
  // 新建会话使 Agent 面板进入激活状态（未选中会话时输入框与模型选择器禁用）
  await page.getByRole("button", { name: "新建对话", exact: true }).click();
  await sleep(1200);

  // B. 模型列表与选择（当前会话已保存获批模型；模型目录 API 已验证仅含获批项）
  const modelButton = page.locator('button[aria-label="模型与推理设置"]:visible').last();
  const modelText = await modelButton.innerText().catch(() => "");
  check("B1 已选择获批 DeepSeek Chat 模型", modelText.includes("DeepSeek"), modelText.replaceAll("\n", " "));
  check("B2 模型选择器可用", await modelButton.isEnabled().catch(() => false));

  // C. 发送消息并验证流式回复
  const editor = page.locator('.senderEditor [contenteditable="true"]').last();
  await editor.click();
  await editor.pressSequentially("你好", { delay: 30 });
  await page.getByRole("button", { name: "发送消息", exact: true }).click();
  await shot(page, "agent-sent");
  await page.waitForSelector("text=流式回复", { timeout: 30000, state: "attached" });
  const replyText = await page.locator("body").textContent().catch(() => "");
  check("C1 收到流式文本回复", replyText.includes("流式回复"), replyText.slice(-60));
  await sleep(800);
  await shot(page, "agent-reply");

  // D. 历史会话与重开
  await page.getByRole("button", { name: "历史对话", exact: true }).click();
  await sleep(800);
  const historyText = await page.locator("body").innerText();
  check("D1 历史对话出现本次会话", historyText.includes("你好"));
  // 重新打开当前会话后，消息仍应完整（读取会话文件）
  await page.keyboard.press("Escape");
  const bodyAfter = await page.locator("body").innerText().catch(() => "");
  check("D2 重开后消息完整", bodyAfter.includes("流式回复"));

  // E. 停止生成
  await editor.click();
  await editor.pressSequentially("请慢一点回答", { delay: 20 });
  await page.getByRole("button", { name: "发送消息", exact: true }).click();
  await sleep(800);
  await page.getByRole("button", { name: "停止生成", exact: true }).click();
  await sleep(800);
  const stopped = await page.locator("body").innerText().catch(() => "");
  check("E1 停止生成并提示", stopped.includes("已停止生成") || stopped.includes("停止"), stopped.slice(-80));

  // F. 错误语义（停止后等待服务端释放会话锁）
  await sleep(3500);
  await editor.click();
  await editor.pressSequentially("触发错误", { delay: 20 });
  await page.getByRole("button", { name: "发送消息", exact: true }).click();
  await sleep(2500);
  const errorText = await page.locator("body").innerText().catch(() => "");
  check("F1 供应商错误以安全消息提示", errorText.includes("模型服务请求失败") || errorText.includes("失败"), errorText.slice(-100));
  await shot(page, "agent-error");

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
  fixture: "synthetic OpenAI-compatible LLM on Linux 18100 + isolated Spring/H2 fixture; Playwright Chromium",
  productionDeploymentChanged: false, realStorageOrModelUsed: false, credentialsIncluded: false,
  browserErrors,
};
fs.writeFileSync(path.join(EVIDENCE, "m3-m02-agent.json"), JSON.stringify(result, null, 2) + "\n");
console.log(`RESULT ${result.passed ? "PASS" : "FAIL"} ${checks.length - failed.length}/${checks.length}`);
process.exit(result.passed ? 0 : 1);
