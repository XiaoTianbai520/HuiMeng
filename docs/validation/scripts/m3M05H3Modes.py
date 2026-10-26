"""M3-05 真实 H3 I2V/FL2V 出片：直连本机 ComfyUI 8188，上传参考帧后各跑一次 5 秒 352p。

只验证模板与权重在首帧/首尾帧模式下可用；产物落 docs/validation/evidence，不写团队存储。
用法：python m3M05H3Modes.py [i2v|fl2v|both]
"""

import hashlib
import json
import struct
import sys
import time
import urllib.parse
import urllib.request
import uuid
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TEMPLATE = ROOT / "backend/src/main/resources/comfy"
EVIDENCE = ROOT / "docs/validation/evidence"
ENDPOINT = "http://127.0.0.1:8188"
SEED = 20261026

PROMPTS = {
    "i2v": (
        "For the target video, at 0.00 seconds into the target video, <Picture 1> (from [Shot 1]) is fully referenced.\n\n"
        "integrated_multimodal_description: [Shot 1] A red ceramic cup on a light wooden table in soft morning side light; "
        "the camera slowly pushes in, quiet and clean, no text, no watermark.\n\n"
        "overall_soundscape: Quiet room ambience with a faint breeze.\n\nnon_diegetic_music: N/A"
    ),
    "fl2v": (
        "How the reference pictures align with the target video — Picture 1 (from [Shot 1]) aligns with the 0.00-second mark "
        "of the target video; Picture 2 (from [Shot 1]) aligns with the 5.17-second mark of the target video.\n\n"
        "integrated_multimodal_description: [Shot 1] A red ceramic cup slowly turns into a blue cup on a light wooden table, "
        "soft morning side light, static camera, one continuous shot, no text, no watermark.\n\n"
        "overall_soundscape: Quiet room ambience.\n\nnon_diegetic_music: N/A"
    ),
}


def request(method, path, payload=None, raw=False):
    data = None
    headers = {}
    if payload is not None:
        data = payload if raw else json.dumps(payload).encode()
        headers["Content-Type"] = "application/octet-stream" if raw else "application/json"
    req = urllib.request.Request(ENDPOINT + path, data=data, method=method, headers=headers)
    with urllib.request.urlopen(req, timeout=120) as response:
        return response.read()


def png(width, height, rgb):
    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def upload(name, data):
    boundary = "----huimeng" + uuid.uuid4().hex
    body = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"{name}\"\r\n"
        f"Content-Type: image/png\r\n\r\n"
    ).encode() + data + f"\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"overwrite\"\r\n\r\ntrue\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(
        ENDPOINT + "/upload/image",
        data=body,
        method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    with urllib.request.urlopen(req, timeout=120) as response:
        return json.loads(response.read())["name"]


def run(mode):
    graph = json.loads((TEMPLATE / f"h3-{mode}.json").read_text(encoding="utf-8"))
    stamp = uuid.uuid4().hex[:8]
    if mode in ("i2v", "fl2v", "r2v"):
        first = upload(f"m3-{mode}-first-{stamp}.png", png(64, 64, (196, 42, 38)))
        graph["15"]["inputs"]["image"] = first
        if mode == "fl2v":
            last = upload(f"m3-{mode}-last-{stamp}.png", png(64, 64, (36, 84, 196)))
            graph["16"]["inputs"]["image"] = last
    if mode == "l2v":
        graph["16"]["inputs"]["image"] = upload(f"m3-l2v-last-{stamp}.png", png(64, 64, (36, 84, 196)))
    size = {"width": 608, "height": 352, "length": 124}
    # L2V/R2V 用模板自带的参考说明，避免把首帧语义写进末帧或多图模式。
    if mode in PROMPTS:
        size["prompt"] = PROMPTS[mode]
    graph["5"]["inputs"].update(size)
    graph["8"]["inputs"]["noise_seed"] = SEED
    graph["14"]["inputs"]["filename_prefix"] = f"HuiMeng/m3-{mode}/" + stamp
    started = time.time()
    prompt_id = json.loads(
        request(
            "POST",
            "/prompt",
            {"prompt": graph, "client_id": "huimeng-m3-" + uuid.uuid4().hex},
        )
    )["prompt_id"]
    print(mode, "submitted", prompt_id, flush=True)
    record = None
    while time.time() - started < 3600:
        history = json.loads(request("GET", f"/history/{prompt_id}"))
        record = history.get(prompt_id)
        if record and record.get("status", {}).get("completed"):
            break
        if record and record.get("status", {}).get("status_str") == "error":
            raise RuntimeError("执行器报错：" + json.dumps(record.get("status"))[:400])
        if int(time.time() - started) % 60 < 5:
            print(mode, "waiting", int(time.time() - started), "s", flush=True)
        time.sleep(5)
    if not record or not record.get("status", {}).get("completed"):
        raise RuntimeError(mode + " 超时未完成")
    outputs = record["outputs"]["14"]
    item = next(
        entry
        for key in ("images", "videos", "gifs")
        for entry in outputs.get(key, [])
        if entry.get("filename", "").endswith(".mp4")
    )
    query = urllib.parse.urlencode(
        {"filename": item["filename"], "subfolder": item.get("subfolder", ""), "type": "output"}
    )
    with urllib.request.urlopen(f"{ENDPOINT}/view?{query}", timeout=300) as response:
        video = response.read()
    out = EVIDENCE / f"m3-m05-h3-{mode}.mp4"
    out.write_bytes(video)
    report = {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "mode": mode,
        "spec": "5s 352p 16:9 608x352 124 frames",
        "promptId": prompt_id,
        "seconds": round(time.time() - started, 1),
        "bytes": len(video),
        "brand": video[4:8].decode("ascii", "replace"),
        "sha256": hashlib.sha256(video).hexdigest(),
        "file": str(out.relative_to(ROOT)),
    }
    (EVIDENCE / f"m3-m05-h3-{mode}.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False), flush=True)
    return report


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "both"
    modes = ["i2v", "fl2v", "l2v", "r2v"] if which == "both" else [which]
    reports = [run(mode) for mode in modes]
    print(json.dumps({"reports": reports}, ensure_ascii=False), flush=True)
