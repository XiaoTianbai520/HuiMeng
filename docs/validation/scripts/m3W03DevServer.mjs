// 临时验收服务器：生产静态文件 + /api/v1 回环代理。仅监听 127.0.0.1，不进入 Git。
// 用法：node .m2-work/m3w03-dev-server.mjs（HUIMENG_API_TARGET 默认 http://127.0.0.1:18086）
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const dist = fileURLToPath(new URL("../../../frontend/dist/", import.meta.url));
const target = new URL(process.env.HUIMENG_API_TARGET ?? "http://127.0.0.1:18086");
const types = {
  ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8", ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
  ".webp": "image/webp", ".ico": "image/x-icon", ".woff": "font/woff", ".woff2": "font/woff2",
  ".txt": "text/plain; charset=utf-8", ".mp4": "video/mp4", ".map": "application/json",
};

http.createServer((req, res) => {
  const url = new URL(req.url, "http://localhost");
  if (url.pathname.startsWith("/api/v1/")) {
    // 保留原始 Origin/Cookie/CSRF 头；仅转发到已核实的回环夹具，不重定向外部地址。
    const started = Date.now();
    const proxy = http.request({
      hostname: target.hostname, port: target.port, path: url.pathname + url.search,
      method: req.method, headers: req.headers,
    }, upstream => {
      fs.appendFileSync(fileURLToPath(new URL("./m3w03-proxy.log", import.meta.url)),
        `${new Date().toISOString()} ${req.method} ${url.pathname}${url.search} -> ${upstream.statusCode} ${Date.now() - started}ms\n`);
      res.writeHead(upstream.statusCode, upstream.headers);
      upstream.pipe(res);
    });
    proxy.on("error", error => { if (!res.headersSent) res.writeHead(502); res.end(String(error)); });
    req.pipe(proxy);
    return;
  }
  const pathname = decodeURIComponent(url.pathname);
  if (pathname.includes("..") || pathname.includes("\0")) { res.writeHead(400); res.end(); return; }
  let file = path.join(dist, pathname === "/" ? "index.html" : pathname.slice(1));
  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) file = path.join(dist, "index.html");
  const stat = fs.statSync(file);
  res.writeHead(200, {
    "Content-Type": types[path.extname(file)] ?? "application/octet-stream",
    "Content-Length": stat.size,
    // 验收期间 HTML 不缓存，避免重建后旧入口引用已删除的 chunk；hash 资源仍可缓存。
    ...(path.extname(file) === ".html" ? { "Cache-Control": "no-store" } : {}),
  });
  fs.createReadStream(file).pipe(res);
}).listen(5187, "127.0.0.1", () => console.log(`static+proxy ready http://127.0.0.1:5187 -> ${target.origin}`));
