import argparse
import base64
import hashlib
import http.server
import io
import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser(description="M0-06 disposable instance protocol verification")
parser.add_argument("phase", choices=["catalog", "plugins"])
parser.add_argument("--runtime", required=True, help="JSON manifest for a prepared huimeng-m0-market-* temporary instance")
args = parser.parse_args()
root = Path(__file__).resolve().parents[3]
state = json.loads(Path(args.runtime).read_text(encoding="utf8"))
runtime = Path(state["runtime"]).resolve()
assert runtime.name.startswith("huimeng-m0-market-") and Path(state["data"]).resolve().is_relative_to(runtime) and Path(state["project"]).resolve().is_relative_to(runtime)
evidence = root / "docs/validation/evidence"
base = f"http://127.0.0.1:{state['port']}"
headers = {"Content-Type": "application/json", "x-toonflow-workspace": "1", "Origin": base, "Referer": base + "/"}
settings = json.loads((Path(state["data"]) / "settings.json").read_text(encoding="utf8"))["settings"]
key = settings["mediaProviderConfigs"]["tfRouter"]["apiKey"].strip().removeprefix("Bearer ").strip()


def safe(value):
    if isinstance(value, dict):
        return {k: ("[REDACTED]" if k.lower() in ["apikey", "authorization", "token"] else
                    urllib.parse.urlsplit(v).hostname + "/[artifact]" if k in ["link", "url"] and isinstance(v, str) and v.startswith("http") else safe(v)) for k, v in value.items()}
    if isinstance(value, list):
        return [safe(v) for v in value]
    if isinstance(value, str):
        return value.replace(key, "[REDACTED]") if key else value
    return value


def request(method, url, body=None, request_headers=None):
    data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    start = time.monotonic()
    try:
        response = urllib.request.urlopen(urllib.request.Request(url, data=data, method=method, headers=request_headers or headers), timeout=45)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        try:
            value = json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            value = None
        return {"httpStatus": response.status, "contentType": response.headers.get("Content-Type"), "disposition": response.headers.get("Content-Disposition"), "body": value, "bytes": len(raw), "elapsedSeconds": round(time.monotonic()-start, 3)}, raw


def catalog():
    results = []

    def query(label, payload, auth=None):
        response, _ = request("POST", "https://api.toonflow.net/v1/plugin/getPlugin", payload,
                              {"Content-Type": "application/json", "Authorization": "Bearer " + (key if auth is None else auth)})
        results.append({"case": label, "request": payload, **safe(response)})
        return response

    first = query("discover", {"page": 1, "limit": 20, "type": "all"})
    assert first["httpStatus"] == 200 and first["body"]["code"] == 200
    for category in ["node", "tool", "skill", "ext", "agent", "my", "collection"]:
        query("category-" + category, {"page": 1, "limit": 5, "type": category})
    one = query("page-one", {"page": 1, "limit": 5, "type": "all"})
    two = query("page-two", {"page": 2, "limit": 5, "type": "all"})
    assert not ({p["id"] for p in one["body"]["data"]["list"]} & {p["id"] for p in two["body"]["data"]["list"]})
    query("search", {"page": 1, "limit": 20, "type": "all", "searchKeyword": "Toonflow"})
    empty = query("search-empty", {"page": 1, "limit": 20, "type": "all", "searchKeyword": "m0SyntheticNoSuchPlugin20261022"})
    assert empty["body"]["data"]["total"] == 0
    invalid = query("invalid-key", {"page": 1, "limit": 1, "type": "all"}, "m0-invalid-not-a-real-key")
    assert invalid["httpStatus"] != 200 or invalid["body"]["code"] != 200
    selected = next(p for p in first["body"]["data"]["list"] if p["supplier"] == "Toonflow" and p["type"] == "skill")
    private = runtime / "selectedMarketPlugin.json"
    private.write_text(json.dumps(selected, ensure_ascii=False), encoding="utf8")
    favorite_initial = selected["isCollected"]
    toggle_headers = {"Content-Type": "application/json", "Authorization": "Bearer " + key}
    changed = False
    try:
        response, _ = request("POST", "https://api.toonflow.net/v1/plugin/toggleCollection", {"pluginId": selected["id"]}, toggle_headers)
        results.append({"case": "favorite-toggle", "request": {"pluginId": selected["id"]}, **safe(response)})
        assert response["body"]["code"] == 200
        changed = response["body"]["data"]["collected"] != favorite_initial
        assert changed
        query("favorite-query", {"page": 1, "limit": 50, "type": "collection"})
    finally:
        # Re-read the actual state before restoration; no blind retry of a toggle.
        current = query("favorite-state-before-restore", {"page": 1, "limit": 50, "type": "all", "searchKeyword": selected["name"]})
        item = next(p for p in current["body"]["data"]["list"] if p["id"] == selected["id"])
        if item["isCollected"] != favorite_initial:
            response, _ = request("POST", "https://api.toonflow.net/v1/plugin/toggleCollection", {"pluginId": selected["id"]}, toggle_headers)
            results.append({"case": "favorite-restore", **safe(response)})
        final = query("favorite-restored-check", {"page": 1, "limit": 50, "type": "all", "searchKeyword": selected["name"]})
        restored = next(p for p in final["body"]["data"]["list"] if p["id"] == selected["id"])["isCollected"] == favorite_initial
        assert restored
        (evidence / "upstream-market-catalog.json").write_text(json.dumps({"fixedCommit": "d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d", "date": "2026-10-22", "cases": results, "favoriteRestored": restored, "selected": safe(selected)}, ensure_ascii=False, indent=2), encoding="utf8")
    # Download receives no model/market credentials. Inspect archive, never execute its contents.
    with urllib.request.urlopen(selected["link"], timeout=45) as response:
        blob = response.read(20*1024*1024 + 1)
        assert len(blob) <= 20*1024*1024
        host = urllib.parse.urlsplit(response.url).hostname
    digest = hashlib.sha256(blob).hexdigest()
    # The observed fileHash algorithm is not documented; record comparison, do not invent its meaning.
    catalog_hash_matches = digest == selected["fileHash"]
    with zipfile.ZipFile(io.BytesIO(blob)) as archive:
        entries = archive.namelist()
        assert all(not p.startswith(("/", "\\")) and ".." not in p.replace("\\", "/").split("/") for p in entries)
        manifests = [p for p in entries if p.endswith("SKILL.md")]
        assert len(manifests) == 1
        text = archive.read(manifests[0]).decode("utf8")
        (runtime / "downloadedSkillManifest.txt").write_text(text, encoding="utf8")
    (runtime / selected["fileName"]).write_bytes(blob)
    (evidence / "upstream-market-artifact.json").write_text(json.dumps({"pluginId": selected["id"], "fileName": selected["fileName"], "host": host, "sha256": digest, "catalogHashMatches": catalog_hash_matches, "bytes": len(blob), "entries": entries, "manifestSha256": hashlib.sha256(text.encode()).hexdigest(), "executed": False}, ensure_ascii=False, indent=2), encoding="utf8")
    print(json.dumps({"catalogCases": len(results), "favoriteRestored": restored, "downloadBytes": len(blob), "catalogHashMatches": catalog_hash_matches}))


def source(version="1.0.0", suffix="v1"):
    meta = {"name": "m0MarketProbe", "version": version, "displayName": "M0 Market Probe", "description": "Synthetic verification plugin. Echo only; no file or network access.", "author": "M0", "github": "", "components": [], "configRules": [{"type": "input", "field": "prefix", "title": "Prefix", "value": "M0"}]}
    code = 'export default {validateConfig(c){if(c.prefix!==undefined&&typeof c.prefix!=="string")throw new Error("prefix must be string");return {prefix:c.prefix??"M0"}},createTools({config}){return [{name:"m0MarketEcho",description:"Return synthetic echo verification text. No file or network access.",parameters:{type:"object",properties:{text:{type:"string"}},required:["text"],additionalProperties:false},async execute(id,p){return {content:[{type:"text",text:config.prefix+":"+p.text+":SUFFIX"}],details:{version:"VERSION"}}}}]} };'
    return "/*! toonflowTool:" + json.dumps(meta, separators=(",", ":")) + " */\n" + code.replace("SUFFIX", suffix).replace("VERSION", version)


def plugins():
    cases = []
    fixture = root / "docs/validation/fixtures/market"
    fixture.mkdir(exist_ok=True)

    def call(label, method, path, payload=None, manage=True):
        h = headers if manage else {"Content-Type": "application/json", "Origin": base, "x-toonflow-local-client": "0"}
        response, raw = request(method, base + path, payload, h)
        recording = {"case": label, "method": method, "path": path, **safe(response)}
        if payload is not None:
            recording["request"] = {k: {"bytes": len(v.encode()), "sha256": hashlib.sha256(v.encode()).hexdigest()} if k in ["source", "base64", "content"] else safe({k:v})[k] for k, v in payload.items()}
        cases.append(recording)
        (evidence / "upstream-market-plugins.json").write_text(json.dumps({"date": "2026-10-22", "scope": "Disposable data only; upstream baseline, not Spring authorization acceptance", "cases": cases}, ensure_ascii=False, indent=2), encoding="utf8")
        return response, raw

    def ok(value):
        assert value["httpStatus"] == 200 and value["body"]["code"] == 200, safe(value)

    if (Path(state["data"]) / "tools/m0MarketProbe.tool.js").exists():
        response, _ = call("reset-owned-synthetic-probe", "DELETE", "/api/tools/uninstall", {"name": "m0MarketProbe"})
        ok(response)
    v1, v2 = source(), source("1.1.0", "v2")
    for name, text in [("m0MarketProbe.tool.js", v1), ("m0MarketProbeUpdate.tool.js", v2)]:
        (fixture / name).write_text(text, encoding="utf8")
    for label, src in [("file-install", v1), ("same-version", v1), ("upgrade", v2), ("downgrade", v1)]:
        response, _ = call(label, "POST", "/api/tools/install", {"fileName": "m0MarketProbe.tool.js", "source": src})
        if label in ["same-version", "downgrade"]:
            assert response["httpStatus"] == 409
        else:
            ok(response)
    good_path = Path(state["data"]) / "tools/m0MarketProbe.tool.js"
    expected = good_path.read_bytes()
    for label, src in [("bad-metadata", "not a valid plugin"), ("invalid-script", v2.split("\n", 1)[0]+"\nexport default { broken ???")]:
        response, _ = call(label, "POST", "/api/tools/install", {"fileName": "m0MarketProbe.tool.js", "source": src, "force": True})
        assert response["httpStatus"] == 400 and good_path.read_bytes() == expected
    response, _ = call("save-config", "PUT", "/api/tools/save", {"name": "m0MarketProbe", "config": {"prefix": "M0-CONFIGURED"}})
    ok(response)
    response, _ = call("bad-config", "PUT", "/api/tools/save", {"name": "m0MarketProbe", "config": {"prefix": 123}})
    assert response["httpStatus"] == 400
    for enabled in [False, True]:
        response, _ = call("enable-" + str(enabled), "PUT", "/api/tools/setEnabled", {"name": "m0MarketProbe", "enabled": enabled})
        ok(response)
        response, _ = call("list-after-enable-" + str(enabled), "GET", "/api/tools/get")
        item = next(p for p in response["body"]["data"]["tools"] if p["name"] == "m0MarketProbe")
        assert item["enabled"] == enabled and not item["loadError"]
    response, raw = call("export", "GET", "/api/plugins/export?type=tool&name=m0MarketProbe")
    assert raw == expected
    (fixture / "m0MarketProbeExport.tool.js").write_bytes(raw)
    # Original guard negative cases: no company identity exists yet.
    for method, path, payload in [("POST", "/api/tools/install", {"fileName": "m0MarketProbe.tool.js", "source": v2}), ("PUT", "/api/tools/save", {"name": "m0MarketProbe", "config": {}}), ("PUT", "/api/tools/setEnabled", {"name": "m0MarketProbe", "enabled": False}), ("DELETE", "/api/tools/uninstall", {"name": "m0MarketProbe"}), ("GET", "/api/plugins/export?type=tool&name=m0MarketProbe", None)]:
        response, _ = call("nonlocal-rejected-" + path, method, path, payload, manage=False)
        assert response["httpStatus"] == 403
    # Exercise the original HTTP downloader against synthetic controlled content.
    class ArtifactHandler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def do_GET(self):
            if self.path == "/redirect":
                self.send_response(302); self.send_header("Location", "/probe.tool.js"); self.end_headers(); return
            if self.path == "/probe.tool.js":
                blob = source("1.2.0", "http").encode(); self.send_response(200); self.send_header("Content-Length", str(len(blob))); self.end_headers(); self.wfile.write(blob); return
            self.send_response(404); self.end_headers()

    mirror = http.server.ThreadingHTTPServer(("127.0.0.1", 0), ArtifactHandler)
    threading.Thread(target=mirror.serve_forever, daemon=True).start()
    address = f"http://127.0.0.1:{mirror.server_port}"
    try:
        response, _ = call("controlled-http-redirect-install", "POST", "/api/tools/install", {"fileName": "m0MarketProbe.tool.js", "url": address + "/redirect"})
        ok(response)
        updated = good_path.read_bytes()
        response, _ = call("download-404-preserves-version", "POST", "/api/tools/install", {"fileName": "m0MarketProbe.tool.js", "url": address + "/missing"})
        assert response["httpStatus"] == 502 and good_path.read_bytes() == updated
    finally:
        mirror.shutdown(); mirror.server_close()
    response, _ = call("download-unreachable-preserves-version", "POST", "/api/tools/install", {"fileName": "m0MarketProbe.tool.js", "url": address + "/probe.tool.js"})
    assert response["httpStatus"] in [502, 504] and good_path.read_bytes() == updated
    # Install the reviewed official skill through the real remote path, then export/disable/remove.
    selected = json.loads((runtime / "selectedMarketPlugin.json").read_text(encoding="utf8"))
    response, _ = call("official-market-remote-skill-install", "POST", "/api/skills/install", {"fileName": selected["fileName"], "url": selected["link"]})
    if response["httpStatus"] == 409:
        response, _ = call("official-skill-confirmed-force-in-disposable-instance", "POST", "/api/skills/install", {"fileName": selected["fileName"], "url": selected["link"], "force": True})
    ok(response)
    skill_name = response["body"]["data"]["name"]
    for enabled in [False, True]:
        response, _ = call("official-skill-enable-"+str(enabled), "PUT", "/api/skills/setEnabled", {"name": skill_name, "enabled": enabled}); ok(response)
    response, blob = call("official-skill-export", "GET", "/api/plugins/export?type=skill&name=" + urllib.parse.quote(skill_name)); assert response["httpStatus"] == 200 and zipfile.is_zipfile(io.BytesIO(blob))
    response, _ = call("official-skill-uninstall", "DELETE", "/api/skills/uninstall", {"name": skill_name}); ok(response)
    # Malformed archive traversal must be rejected before any write outside the skill.
    bad_zip = io.BytesIO()
    with zipfile.ZipFile(bad_zip, "w") as archive:
        archive.writestr("../m0Escape.txt", "synthetic")
        archive.writestr("SKILL.md", "---\nname: m0BadArchive\ndescription: synthetic\n---\n")
    response, _ = call("skill-zip-traversal-rejected", "POST", "/api/skills/install", {"fileName": "m0BadArchive.zip", "base64": base64.b64encode(bad_zip.getvalue()).decode()})
    assert response["httpStatus"] == 400 and not (Path(state["data"]) / "m0Escape.txt").exists()
    print(json.dumps({"pluginCases": len(cases), "officialSkillInstalledExportedRemoved": True, "badUpdatesPreserveCurrent": True, "syntheticProbeRetainedForAgentTest": True}))


if __name__ == "__main__":
    {"catalog": catalog, "plugins": plugins}[args.phase]()
