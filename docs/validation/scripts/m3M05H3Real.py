"""M3-05 真实 H3 出片：用仓库内模板直连本机 ComfyUI 8188，跑一次 T2V 并取回 MP4。

只做一次 5 秒 352p 的 T2V，用于验证模板与权重匹配；不写团队存储，产物落在 docs/validation/evidence。
"""

import hashlib
import json
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TEMPLATE = ROOT / "backend/src/main/resources/comfy/h3-t2v.json"
EVIDENCE = ROOT / "docs/validation/evidence"
ENDPOINT = "http://127.0.0.1:8188"
PROMPT = (
    "integrated_multimodal_description: [Shot 1] 一只白色陶瓷茶杯放在浅色木桌上，"
    "清晨柔和的侧光从左侧洒下，镜头缓慢向前推近，画面安静简洁，没有文字，没有水印。\n\n"
    "overall_soundscape: 安静的室内环境声，轻微的风声。\n\n"
    "non_diegetic_music: N/A"
)


def request(method, path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(
        ENDPOINT + path, data=data, method=method, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=120) as response:
        return response.read()


def main():
    graph = json.loads(TEMPLATE.read_text(encoding="utf-8"))
    graph["5"]["inputs"].update({"width": 608, "height": 352, "length": 124, "prompt": PROMPT})
    graph["8"]["inputs"]["noise_seed"] = 20261026
    graph["14"]["inputs"]["filename_prefix"] = "HuiMeng/real-check/" + uuid.uuid4().hex[:8]
    started = time.time()
    prompt_id = json.loads(
        request("POST", "/prompt", {"prompt": graph, "client_id": "huimeng-real-" + uuid.uuid4().hex})
    )["prompt_id"]
    print("submitted", prompt_id, flush=True)
    record = None
    while time.time() - started < 3600:
        history = json.loads(request("GET", f"/history/{prompt_id}"))
        record = history.get(prompt_id)
        if record and record.get("status", {}).get("completed"):
            break
        if record and record.get("status", {}).get("status_str") == "error":
            raise RuntimeError("执行器报错：" + json.dumps(record.get("status"))[:400])
        if int(time.time() - started) % 60 < 5:
            print("waiting", int(time.time() - started), "s", flush=True)
        time.sleep(5)
    if not record or not record.get("status", {}).get("completed"):
        raise RuntimeError("超时未完成")
    files = record["outputs"]["14"]
    item = next(
        entry
        for key in ("images", "videos", "gifs")
        for entry in files.get(key, [])
        if entry.get("filename", "").endswith(".mp4")
    )
    query = urllib.parse.urlencode(
        {"filename": item["filename"], "subfolder": item.get("subfolder", ""), "type": "output"}
    )
    with urllib.request.urlopen(f"{ENDPOINT}/view?{query}", timeout=300) as response:
        video = response.read()
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    out = EVIDENCE / "m3-m05-h3-real.mp4"
    out.write_bytes(video)
    report = {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "endpoint": ENDPOINT,
        "promptId": prompt_id,
        "mode": "t2v turbo8",
        "spec": "5s 352p 16:9 608x352",
        "seconds": round(time.time() - started, 1),
        "bytes": len(video),
        "head": video[4:8].decode("ascii", "replace"),
        "sha256": hashlib.sha256(video).hexdigest(),
        "file": str(out.relative_to(ROOT)),
    }
    (EVIDENCE / "m3-m05-h3-real.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
