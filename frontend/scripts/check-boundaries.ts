import { readFileSync, readdirSync } from "node:fs";
import { join, relative, resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const errors: string[] = [];
function visit(directory: string) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (entry.name === "node_modules") continue;
    const path = join(directory, entry.name);
    if (entry.isDirectory()) visit(path);
    else if (/\.(ts|vue)$/.test(entry.name)) {
      const source = readFileSync(path, "utf8");
      const name = relative(root, path).replaceAll("\\", "/");
      if (/@toonflow\/server|@toonflow\/tool-browser/.test(source)) errors.push(`${name}: server/desktop-browser import`);
      if (name.startsWith("src/") && /["'`]\/api\/(desktop|mobileLink|mobile\/|connection|browser\/|workspaces\/selectDirectory|workspaces\/files\/reveal)/.test(source)) {
        errors.push(`${name}: excluded endpoint literal`);
      }
      if (name.startsWith("src/") && /(?:\bfetch\(|new EventSource\(|from ["']axios["'])/.test(source)) errors.push(`${name}: bypasses shared API client`);
      if (!source.endsWith("\n")) errors.push(`${name}: missing final newline`);
    }
  }
}
visit(join(root, "src"));
visit(join(root, "packages/contracts"));
if (errors.length) { console.error(errors.join("\n")); process.exit(1); }
console.log("Web boundaries and source formatting verified");
