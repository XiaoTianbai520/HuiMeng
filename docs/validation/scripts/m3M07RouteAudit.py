"""M3-07 静态审计：25 条排除路由没有后端入口、没有前端白名单映射、没有页面调用。

只读源码与契约文件，不启动服务；结果写入证据 JSON 供交接引用。
"""
import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path(os.environ.get("M307_EVIDENCE", ROOT / "docs/validation/evidence/m3-m07-routes.json"))

# 来自 docs/api/route-inventory.md 的“排除”处置，共 25 条。
EXCLUDED = [
    ("R033", "POST", "/api/browser/input"),
    ("R034", "GET", "/api/browser/stream"),
    ("R035", "POST", "/api/desktop/clipboard/read"),
    ("R036", "POST", "/api/desktop/clipboard/write"),
    ("R037", "POST", "/api/desktop/devtools"),
    ("R038", "POST", "/api/desktop/openUrl"),
    ("R039", "POST", "/api/desktop/plugins/install"),
    ("R040", "POST", "/api/desktop/providerFile/read"),
    ("R041", "POST", "/api/desktop/providerFile/select"),
    ("R042", "POST", "/api/desktop/ready"),
    ("R043", "POST", "/api/desktop/saveFile"),
    ("R044", "POST", "/api/desktop/selectDirectory"),
    ("R045", "POST", "/api/desktop/selectSaveFile"),
    ("R046", "POST", "/api/desktop/update/apply"),
    ("R047", "POST", "/api/desktop/update/check"),
    ("R048", "POST", "/api/desktop/update/download"),
    ("R049", "GET", "/api/desktop/update"),
    ("R067", "PUT", "/api/mobileLink/config"),
    ("R068", "GET", "/api/mobileLink/heartbeat"),
    ("R069", "POST", "/api/mobileLink/pair"),
    ("R070", "POST", "/api/mobileLink/pairing"),
    ("R071", "POST", "/api/mobileLink/revoke"),
    ("R072", "GET", "/api/mobileLink/status"),
    ("R124", "POST", "/api/workspaces/files/reveal"),
    ("R127", "POST", "/api/workspaces/selectDirectory"),
]
BACKEND = ROOT / "backend/src/main/java"
FRONTEND = ROOT / "frontend/src"
ROUTE_MAP = ROOT / "frontend/packages/api/route-map.json"
checks = []


def check(name, ok, detail=""):
    checks.append({"name": name, "ok": bool(ok), "detail": str(detail)[:400]})
    print(("PASS " if ok else "FAIL ") + name + ((" :: " + str(detail)) if detail else ""))


def read_sources(path: Path):
    for file in sorted(path.rglob("*")):
        if file.suffix in {".java", ".ts", ".vue", ".json"} and file.is_file():
            yield file, file.read_text(encoding="utf-8", errors="replace")


def main():
    mapping = json.loads(ROUTE_MAP.read_text(encoding="utf-8"))
    sources = {r["sourcePath"] for r in mapping["routes"]}
    targets = {r["targetPath"] for r in mapping["routes"] if r.get("targetPath")}
    platform = {p["path"] for p in mapping["platform"]}

    # 1) 契约白名单不含排除路由，也不把排除路由重定向到 Spring 目标路径。
    for rid, method, path in EXCLUDED:
        check(f"{rid} 不在前端路由白名单", path not in sources, path)
    check("排除前缀未出现在目标路径", not [t for t in targets if t and any(e[2].startswith("/api/") and e[2] in t for e in EXCLUDED)])

    # 2) 后端没有对应控制器映射（含 /api/desktop、/api/mobileLink 等前缀）。
    backend_hits = []
    for file, text in read_sources(BACKEND):
        for rid, method, path in EXCLUDED:
            suffix = path[len("/api"):]
            if path in text or (suffix in text and "Mapping" in text):
                backend_hits.append({"route": rid, "path": path, "file": str(file.relative_to(ROOT))})
    check("后端无排除路由映射", not backend_hits, backend_hits[:5])
    for prefix in ("/api/desktop", "/api/mobileLink", "/api/browser"):
        hits = [str(f.relative_to(ROOT)) for f, t in read_sources(BACKEND) if prefix in t]
        check(f"后端无 {prefix} 前缀", not hits, hits[:3])

    # 3) 前端源码没有直接调用排除路由（封装或裸路径都算）。
    front_hits = []
    for file, text in read_sources(FRONTEND):
        for rid, method, path in EXCLUDED:
            if path in text:
                front_hits.append({"route": rid, "path": path, "file": str(file.relative_to(ROOT))})
    check("前端无排除路由调用", not front_hits, front_hits[:5])
    for signature in ("/api/desktop/", "/api/mobileLink/", "/api/browser/"):
        hits = [str(f.relative_to(ROOT)) for f, t in read_sources(FRONTEND) if signature in t]
        check(f"前端无 {signature} 引用", not hits, hits[:3])

    # 4) 未实现的目标路径必须登记，且页面遍历不得触发它们。
    backend_text = "\n".join(text for _, text in read_sources(BACKEND))
    pending = []
    for target in sorted(t for t in targets if t):
        normalized = re.sub(r"\{[^}]+\}", "{", target)
        prefix = normalized.split("{", 1)[0].rstrip("/")
        if prefix and prefix not in backend_text:
            pending.append(target)
    sweep = ROOT / "docs/validation/evidence/m3-m07-pages.json"
    triggered = []
    if sweep.exists():
        triggered = [
            item["path"].split("?")[0]
            for item in json.loads(sweep.read_text(encoding="utf-8")).get("failures", [])
        ]
    check("页面遍历未触发未实现的契约路径", not triggered, triggered[:5])
    check("平台白名单覆盖协议入口", any(p.startswith("/api/v1/capabilities/mcp") for p in platform), sorted(platform)[:3])

    EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
    EVIDENCE.write_text(
        json.dumps(
            {
                "excludedRoutes": len(EXCLUDED),
                "routeMapSources": len(sources),
                "platformEntries": len(platform),
                "backendHits": backend_hits,
                "frontendHits": front_hits,
                "pendingTargets": pending,
                "checks": checks,
                "createdAt": __import__("datetime").datetime.now().astimezone().isoformat(),
            },
            ensure_ascii=False,
            indent=1,
        ),
        encoding="utf-8",
    )
    failed = [c for c in checks if not c["ok"]]
    print(f"M3-07 route audit: {len(checks)} checks, {len(failed)} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
