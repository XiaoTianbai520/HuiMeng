import { createHash } from "node:crypto";
import { copyFile, mkdir, readFile, readdir, rm, stat } from "node:fs/promises";
import { join, resolve } from "node:path";

/**
 * 构建内置节点包并把可分发产物收口到后端 classpath。
 *
 * 产物由各节点包自己的 `vite build`（`@toonflow/nodes-scaffold` 的 `createNodeConfig`）生成，
 * 文件名固定为 `<name>.umd.js`，包头 `/*! toonflowNode:{...}*\/` 带 version/displayName/author/configRules。
 * 本脚本只做三件事：清理旧产物、按包顺序构建、把字节原样复制到后端资源目录并打印摘要。
 */
const root = resolve(import.meta.dirname, "..");
const buildDir = join(root, "build", "nodes");
const targetDir = resolve(
  root,
  "..",
  "backend",
  "src",
  "main",
  "resources",
  "capabilities",
  "nodes",
);
const nodePackages = [
  { directory: "textNode", name: "textNode" },
  { directory: "imageNode", name: "imageNode" },
  { directory: "imageGenerationNode", name: "imageGenerationNode" },
  { directory: "videoNode", name: "videoNode" },
  { directory: "videoGenerationNode", name: "videoGenerationNode" },
  { directory: "audioNode", name: "audioNode" },
  { directory: "audioGenerationNode", name: "audioGenerationNode" },
  { directory: "director3dNode", name: "director3dNode" },
] as const;
const HEADER = "/*! toonflowNode:";

/** 从产物包头解析构建期元数据；缺失时直接失败，避免登记出没有版本/显示名的节点。 */
export function readNodeMetadata(source: string): Record<string, unknown> {
  if (!source.startsWith(HEADER))
    throw new Error("节点产物缺少 toonflowNode 元数据包头，请用 @toonflow/nodes-scaffold 构建");
  const end = source.indexOf("*/", HEADER.length);
  if (end < 0) throw new Error("节点产物元数据包头没有结束标记");
  const raw = source.slice(HEADER.length, end).trim();
  return JSON.parse(raw) as Record<string, unknown>;
}

async function buildOne(directory: string) {
  const cwd = join(root, "packages", "nodes", directory);
  const info = await stat(join(cwd, "package.json"));
  if (!info.isFile()) throw new Error(`节点包缺少 package.json：${cwd}`);
  const child = Bun.spawn(["bun", "run", "build"], {
    cwd,
    stdout: "inherit",
    stderr: "inherit",
    env: { ...process.env, NODE_ENV: "production" },
  });
  const code = await child.exited;
  if (code !== 0) throw new Error(`节点 ${directory} 构建失败，退出码 ${code}`);
}

async function main() {
  await rm(buildDir, { recursive: true, force: true });
  await mkdir(buildDir, { recursive: true });
  for (const node of nodePackages) await buildOne(node.directory);

  const produced = (await readdir(buildDir)).filter(name => name.endsWith(".umd.js")).sort();
  const expected = nodePackages.map(node => `${node.name}.umd.js`).sort();
  if (produced.join(",") !== expected.join(","))
    throw new Error(`产物与预期不一致：期望 ${expected.join(",")}，实际 ${produced.join(",")}`);

  await rm(targetDir, { recursive: true, force: true });
  await mkdir(targetDir, { recursive: true });
  const rows: string[] = [];
  for (const fileName of expected) {
    const source = join(buildDir, fileName);
    const bytes = await readFile(source);
    const metadata = readNodeMetadata(bytes.toString("utf8"));
    const sha256 = createHash("sha256").update(bytes).digest("hex");
    await copyFile(source, join(targetDir, fileName));
    rows.push(
      `${fileName}\t${String(metadata.version ?? "")}\t${String(metadata.displayName ?? "")}\t${bytes.length}B\t${sha256}`,
    );
  }
  console.log("file\tversion\tdisplayName\tbytes\tsha256");
  for (const row of rows) console.log(row);
  console.log(`内置节点产物已写入 ${targetDir}`);
}

if (import.meta.main) await main();
