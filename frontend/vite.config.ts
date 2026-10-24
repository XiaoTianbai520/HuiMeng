import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import components from "unplugin-vue-components/vite";
import { ElementPlusResolver } from "unplugin-vue-components/resolvers";
import { i18nPlugin } from "@toonflow/i18n/vite";
import postcssConfig from "./postcss.config.ts";
import { licenseReport } from "./scripts/licenses.ts";

export default defineConfig(() => {
  // Only a verified Spring endpoint may be supplied. No built-in Bun proxy.
  const target = process.env.HUIMENG_API_TARGET;
  if (target && (!URL.canParse(target) || !["http:", "https:"].includes(new URL(target).protocol))) {
    throw new Error("HUIMENG_API_TARGET must be a Spring HTTP(S) origin");
  }
  return {
    css: { postcss: postcssConfig },
    define: { "import.meta.env.appVersion": JSON.stringify("1.0.0-m1") },
    server: { proxy: target ? { "/api/v1": { target, changeOrigin: false } } : undefined },
    resolve: { alias: [
      { find: "@", replacement: fileURLToPath(new URL("./src", import.meta.url)) },
      { find: /^shiki$/, replacement: fileURLToPath(new URL("./src/lib/shiki.ts", import.meta.url)) },
    ] },
    build: { outDir: "dist", emptyOutDir: true, sourcemap: false },
    plugins: [i18nPlugin(), vue(), licenseReport(), components({
      globsExclude: ["src/components/settings/panels/**/*Dialog.vue"],
      dts: "src/types/components.d.ts", resolvers: [ElementPlusResolver(), name =>
        name.startsWith("Icon") ? { name, from: "@tabler/icons-vue" } : undefined],
    })],
  };
});
