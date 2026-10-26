"""M3-05 A08 视频部分验收：H3 五模式经真实 Spring 入口写入合成执行器。

夹具为 MediaBrowserAcceptanceHarness（已放行 h3Local3060 五模型），执行器为 m3M05H3Mock.py。
"""

import http.client
import json
import os
import threading
import time
import uuid
import zlib
from pathlib import Path

PORT = int(os.environ.get("M305_PORT", "18097"))
MOCK_PORT = int(os.environ.get("M305_MOCK_PORT", "18130"))
PROJECT = os.environ["M305_PROJECT"]
EVIDENCE = Path(os.environ["M305_EVIDENCE"])
checks = []
cookies = {}


def check(ok, name, detail=""):
    if not ok:
        raise RuntimeError("M3-05 h3 check failed: " + name + " " + str(detail))
    checks.append({"name": name, "detail": str(detail)})


def call(method, path, body=None, token=None, raw=None, extra=None):
    conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=180)
    headers = {"Cookie": "; ".join(k + "=" + v for k, v in cookies.items())}
    if extra:
        headers.update(extra)
    if method != "GET":
        headers["Origin"] = "http://localhost:5187"
    if token:
        headers["X-CSRF-Token"] = token
    payload = None
    if raw is not None:
        payload = raw
        headers["Content-Type"] = "application/octet-stream"
    elif body is not None:
        payload = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    conn.request(method, path, payload, headers)
    response = conn.getresponse()
    for name, value in response.getheaders():
        if name.lower() == "set-cookie":
            key, val = value.split(";", 1)[0].split("=", 1)
            cookies[key] = val
    try:
        content = response.read()
    except http.client.IncompleteRead as ex:
        content = ex.partial
    result = (response.status, dict(response.getheaders()), content)
    conn.close()
    return result


def csrf():
    status, _, payload = call("GET", "/api/v1/auth/csrf")
    check(status == 200, "csrf", status)
    return json.loads(payload)["token"]


def mock_stats():
    conn = http.client.HTTPConnection("127.0.0.1", MOCK_PORT, timeout=15)
    conn.request("GET", "/_stats")
    stats = json.loads(conn.getresponse().read())
    conn.close()
    return stats


def png_bytes():
    raw = b""
    for _ in range(8):
        raw += b"\x00" + bytes((220, 180, 120)) * 8

    def chunk(kind, data):
        return (
            b"\x00\x00\x00\x00"[:0]
            + len(data).to_bytes(4, "big")
            + kind
            + data
            + (zlib.crc32(kind + data) & 0xFFFFFFFF).to_bytes(4, "big")
        )

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", (8).to_bytes(4, "big") + (8).to_bytes(4, "big") + bytes((8, 2, 0, 0, 0)))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def main():
    status, _, body = call(
        "POST",
        "/api/v1/auth/login",
        {"username": "ui-member", "password": "M3-media-fixture-only"},
        csrf(),
    )
    check(status == 200, "login", status)

    status, _, payload = call(
        "POST",
        f"/api/v1/projects/{PROJECT}/media?filename=frame.png",
        raw=png_bytes(),
        token=csrf(),
    )
    check(status in (200, 201), "uploadFrame", status)
    frame = f".huimeng-media/{json.loads(payload)['id']}.png"

    def generate(model, duration, size, ratio, prompt, images=None, key=None):
        body = {
            "providerId": "h3Local3060",
            "modelId": model,
            "mediaType": "video",
            "prompt": prompt,
            "size": size,
            "ratio": ratio,
            "duration": duration,
        }
        if images:
            body["images"] = [
                {"path": frame, "mimeType": "image/png"} for _ in range(images)
            ]
        status, headers, raw = call(
            "POST",
            f"/api/v1/projects/{PROJECT}/creative/ai/media/generate",
            body,
            csrf(),
            extra={"Idempotency-Key": str(uuid.uuid4())},
        )
        return status, headers.get("X-Run-Id"), raw

    cases = [
        ("h3T2v", 5, "352p", "16:9", "文生视频：红色玩具车向前滚动", None, "MiniMaxH3ImageToVideo", 608, 352, 124),
        ("h3I2v", 8, "480p", "16:9", "首帧图生视频：相机缓慢推近", 1, "MiniMaxH3ImageToVideo", 864, 480, None),
        ("h3Fl2v", 5, "352p", "9:16", "首尾帧过渡：白天到夜晚", 2, "MiniMaxH3ImageToVideo", 352, 608, None),
        ("h3L2v", 10, "544p", "1:1", "尾帧生视频：角色转身", 1, "MiniMaxH3ImageToVideo", 544, 544, None),
        ("h3R2v", 5, "352p", "16:9", "参考生视频：两个角色同框对话", 2, "MiniMaxH3ReferenceToVideo", 608, 352, None),
    ]
    specs = {}
    for model, duration, size, ratio, prompt, images, klass, width, height, frames in cases:
        before = mock_stats()["prompts"]
        status, run, raw = generate(model, duration, size, ratio, prompt, images)
        check(status == 200, f"{model}Called", status)
        result = json.loads(raw)
        check(result["code"] == 200, f"{model}Result", result.get("message"))
        media = result["data"][0]
        check(media["mediaType"] == "video" and media["mimeType"] == "video/mp4", f"{model}VideoEcho", media)
        check(media["path"].endswith(".mp4"), f"{model}Path", media["path"])
        specs[model] = media["spec"]
        check(media["spec"].startswith(f"{duration}s {size}"),
              f"{model}Spec", media["spec"])
        if frames:
            check(media["spec"].endswith(f"{width}×{height}"), f"{model}Pixels", media["spec"])
        stats = mock_stats()
        check(stats["prompts"] == before + 1, f"{model}ReachedExecutor")
        check(stats["last"]["classType"] == klass, f"{model}Template", stats["last"]["classType"])
        check(stats["last"]["width"] == width and stats["last"]["height"] == height,
              f"{model}PixelsSubmitted", f"{stats['last']['width']}×{stats['last']['height']}")
        if model == "h3Fl2v":
            check(stats["last"]["hasFirstFrame"] and stats["last"]["hasLastFrame"], "fl2vTwoFrames")
        if model == "h3R2v":
            check(len(stats["last"]["references"]) == 2, "r2vReferenceNodes", stats["last"]["references"])
        media_id = media["path"].split("/")[-1].split(".")[0]
        status, _, content = call("GET", f"/api/v1/projects/{PROJECT}/media/{media_id}/content")
        check(status == 200 and content[4:8] == b"ftyp", f"{model}Downloadable", f"{status} {len(content)}")

    # 参数边界：不触达执行器。
    before = mock_stats()["prompts"]
    for name, args in {
        "badDuration": ("h3T2v", 7, "352p", "16:9", "x", None),
        "badResolution": ("h3T2v", 5, "1080p", "16:9", "x", None),
        "badRatio": ("h3T2v", 5, "352p", "21:9", "x", None),
        "missingFrame": ("h3I2v", 5, "352p", "16:9", "x", None),
        "unexpectedFrame": ("h3T2v", 5, "352p", "16:9", "x", 1),
    }.items():
        status, _, _ = generate(*args)
        check(status == 400, name, status)
    check(mock_stats()["prompts"] == before, "invalidNeverReachedExecutor")

    # 第六个请求拒绝且不触达执行器。
    slow = []
    for index in range(5):
        body = {
            "providerId": "h3Local3060",
            "modelId": "h3T2v",
            "mediaType": "video",
            "prompt": f"[slow] 名额占位 {index}",
            "size": "352p",
            "ratio": "16:9",
            "duration": 5,
        }
        token = csrf()
        conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=20)
        conn.request(
            "POST",
            f"/api/v1/projects/{PROJECT}/creative/ai/media/generate",
            json.dumps(body).encode(),
            {
                "Cookie": "; ".join(k + "=" + v for k, v in cookies.items()),
                "Origin": "http://localhost:5187",
                "X-CSRF-Token": token,
                "Idempotency-Key": f"vid-slot-{index}",
                "Content-Type": "application/json",
            },
        )
        response = conn.getresponse()
        slow.append(response.getheader("X-Run-Id"))
        conn.close()
    sixth = {
        "providerId": "h3Local3060",
        "modelId": "h3T2v",
        "mediaType": "video",
        "prompt": "[slow] 第六个",
        "size": "352p",
        "ratio": "16:9",
        "duration": 5,
    }
    status, _, raw = call(
        "POST",
        f"/api/v1/projects/{PROJECT}/creative/ai/media/generate",
        sixth,
        csrf(),
        extra={"Idempotency-Key": "vid-sixth"},
    )
    check(status == 429, "sixthVideoRejected", status)
    for run in slow:
        call("POST", f"/api/v1/projects/{PROJECT}/runs/{run}/cancel", None, csrf())
    deadline = time.time() + 60
    released = 0
    while time.time() < deadline and released < 5:
        released = 0
        for run in slow:
            status, _, raw = call("GET", f"/api/v1/projects/{PROJECT}/runs/{run}")
            body = json.loads(raw)
            if status == 200 and body["state"] == "CANCELLED" and body["capacityHeld"] is False:
                released += 1
        time.sleep(1)
    check(released == 5, "videoSlotsReleased", released)

    EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
    EVIDENCE.write_text(
        json.dumps(
            {
                "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "project": PROJECT,
                "specs": specs,
                "stats": mock_stats(),
                "checks": checks,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n"
    )
    print("M3-05 H3 checks passed:", len(checks))
    for item in checks:
        print(" -", item["name"], item["detail"])


if __name__ == "__main__":
    main()
