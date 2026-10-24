import { existsSync, readFileSync, readdirSync, realpathSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { createHash } from "node:crypto";
import type { Plugin } from "vite";

/** Record only packages contributing modules to this emitted browser artifact. */
export function licenseReport(): Plugin {
  const root = resolve(import.meta.dirname, "..");
  return {
    name: "huimeng-artifact-licenses",
    generateBundle(_options, bundle) {
      const supplements = JSON.parse(readFileSync(join(root, "licenses/supplements.json"), "utf8")) as {
        name: string; version: string; method?: string; files: { file: string; sha256: string; url: string }[];
      }[];
      const sourceReferences = JSON.parse(readFileSync(join(root, "licenses/source-references.json"), "utf8")) as Record<string, unknown>;
      const roots = new Set<string>();
      for (const asset of Object.values(bundle)) {
        if (asset.type !== "chunk") continue;
        for (const id of Object.keys(asset.modules)) {
          const clean = id.replace(/\?.*$/, "");
          if (!clean.includes("node_modules") && !clean.includes("/packages/")) continue;
          if (!existsSync(clean)) continue;
          let path = dirname(realpathSync(clean));
          while (path !== dirname(path) && path !== root) {
            if (existsSync(join(path, "package.json")) && typeof JSON.parse(readFileSync(join(path, "package.json"), "utf8")).name === "string") {
              roots.add(path);
              break;
            }
            path = dirname(path);
          }
        }
      }
      const records = [...roots].map(path => {
        const metadata = JSON.parse(readFileSync(join(path, "package.json"), "utf8")) as { name: string; version?: string; license?: unknown; repository?: unknown };
        const files: { path: string; text: string; sha256: string }[] = [];
        function visit(directory: string, depth = 0) {
          if (depth > 5) return;
          for (const item of readdirSync(directory, { withFileTypes: true })) {
            if (item.name === "node_modules" || item.name.startsWith(".")) continue;
            const target = join(directory, item.name);
            if (item.isDirectory()) visit(target, depth + 1);
            else if (/(?:^|[._-])(licen[cs]e|notice|copying|copyright)(?:$|[._-])/i.test(item.name)) {
              const text = readFileSync(target, "utf8");
              files.push({ path: relative(path, target).replaceAll("\\", "/"), text,
                sha256: createHash("sha256").update(text).digest("hex") });
            }
          }
        }
        visit(path);
        if (files.length === 0) {
          // Some original npm distributions include the complete license in README.
          for (const name of ["README.md", "Readme.md"]) {
            const candidate = join(path, name);
            if (!existsSync(candidate)) continue;
            const text = readFileSync(candidate, "utf8");
            if (text.includes("Permission is hereby granted") && text.includes("THE SOFTWARE IS PROVIDED")) {
              files.push({ path: name, text, sha256: createHash("sha256").update(text).digest("hex") });
              break;
            }
          }
        }
        const supplement = supplements.find(item => item.name === metadata.name && item.version === metadata.version);
        if (supplement) for (const file of supplement.files) {
          const text = readFileSync(join(root, file.file), "utf8");
          const sha256 = createHash("sha256").update(text).digest("hex");
          if (sha256 !== file.sha256) this.error(`License supplement digest mismatch: ${file.file}`);
          files.push({ path: "supplement/" + file.file.split("/").at(-1)!, text, sha256 });
        }
        const internal = metadata.name.startsWith("@toonflow/") || metadata.name.startsWith("@huimeng/");
        return { name: metadata.name, version: metadata.version || "workspace", declaredLicense: metadata.license ?? null,
          attribution: internal ? "Toonflow d2e94aca, MIT; LICENSE.Toonflow" : "package license originals",
          repository: metadata.repository ?? null,
          sourceReference: sourceReferences[metadata.name + "@" + metadata.version] ?? null,
          supplement: supplement ? { method: supplement.method || "official source at npm gitHead", files: supplement.files } : null,
          files, missingOriginal: !internal && files.length === 0 };
      }).sort((a, b) => a.name.localeCompare(b.name));
      const ownLicense = readFileSync(join(root, "LICENSE.Toonflow"), "utf8");
      const flagSvg = readFileSync(join(root, "src/assets/languageFlags.svg"), "utf8");
      const flagNotice = flagSvg.match(/<!--([\s\S]*?)-->/)?.[1]?.trim();
      if (!flagNotice?.includes("Copyright (c) 2013 Panayiotis Lipiridis") || !flagNotice.includes("Permission is hereby granted")) {
        this.error("Vendored flag artwork lost its license notice");
      }
      const staticAssets = [{ name: "flag-icons", version: "7.3.2", license: "MIT",
        source: "https://github.com/lipis/flag-icons/tree/7.3.2", file: "src/assets/languageFlags.svg",
        sha256: createHash("sha256").update(flagSvg).digest("hex") }];
      const notices = ["绘梦 1.0 browser artifact — third-party license originals", "Upstream Toonflow", ownLicense,
        "\nVendored flag artwork\n" + flagNotice,
        ...records.flatMap(record => [`\n${"=".repeat(72)}\n${record.name}@${record.version}\nDeclared: ${JSON.stringify(record.declaredLicense)}\n${record.attribution}\nSource repository: ${JSON.stringify(record.repository)}\nSource reference: ${JSON.stringify(record.sourceReference)}`,
          ...record.files.map(file => `\n--- ${file.path} ---\n${file.text}`)])].join("\n");
      this.emitFile({ type: "asset", fileName: "THIRD_PARTY_NOTICES.txt", source: notices });
      this.emitFile({ type: "asset", fileName: "licenses/flag-icons@7.3.2/LICENSE.txt", source: flagNotice + "\n" });
      this.emitFile({ type: "asset", fileName: "license-inventory.json", source: JSON.stringify({
        scope: "modules in this browser artifact; does not include future executor or dynamically installed plugins",
        staticAssets,
        packages: records.map(({ files, ...record }) => ({ ...record, files: files.map(({ text: _text, ...file }) => file) })),
      }, null, 2) + "\n" });
      for (const record of records) for (const file of record.files) this.emitFile({ type: "asset",
        fileName: `licenses/${record.name.replaceAll("/", "_")}@${record.version}/${file.path}`, source: file.text });
      if (records.some(record => record.missingOriginal)) this.error("Missing distributed license originals: " +
        records.filter(record => record.missingOriginal).map(record => record.name).join(", "));
    },
  };
}
