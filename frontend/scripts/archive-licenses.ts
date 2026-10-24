import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { dirname, join, relative, resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const dist = join(root, "dist");
const archive = join(root, "licenses/artifact");
if (!existsSync(join(dist, "license-inventory.json"))) throw new Error("Run the production build first");
mkdirSync(archive, { recursive: true });
const records: { source: string; file: string; sha256: string }[] = [];
function copy(source: string, destination: string) {
  const target = resolve(archive, destination);
  if (!target.startsWith(archive + "/") && !target.startsWith(archive + "\\")) throw new Error("License target escaped archive");
  const bytes = readFileSync(source);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, bytes);
  records.push({ source: relative(dist, source).replaceAll("\\", "/"),
    file: relative(archive, target).replaceAll("\\", "/"), sha256: createHash("sha256").update(bytes).digest("hex") });
}
function walk(directory: string) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) walk(path);
    else copy(path, "texts/" + relative(join(dist, "licenses"), path).replaceAll("\\", "/") + ".txt");
  }
}
walk(join(dist, "licenses"));
copy(join(dist, "license-inventory.json"), "inventory.json");
copy(join(dist, "THIRD_PARTY_NOTICES.txt"), "THIRD_PARTY_NOTICES.txt");
writeFileSync(join(archive, "manifest.json"), JSON.stringify({ scope: "current browser artifact", files: records }, null, 2) + "\n");
console.log(`Archived ${records.length} license/notice files with SHA-256`);
