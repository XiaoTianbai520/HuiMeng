"""M3-05 A10 真实转码验收：真实 Spring 入口 + 服务端 ffmpeg。

夹具容器内已安装 ffmpeg 并生成样例视频；本脚本从宿主机调用 HTTP 上传、转码、取消与下载。
"""
import http.client
import json
import os
import threading
import time
import uuid
from pathlib import Path

PORT = int(os.environ.get("M305_PORT", "18097"))
PROJECT = os.environ["M305_PROJECT"]
SOURCE = Path(os.environ["M305_SOURCE"])
LONG_SOURCE = Path(os.environ.get("M305_LONG_SOURCE", "")) if os.environ.get("M305_LONG_SOURCE") else None
EVIDENCE = Path(os.environ["M305_EVIDENCE"])
checks = []
cookies = {}


def check(ok, name, detail=""):
    if not ok:
        raise RuntimeError("M3-05 ffmpeg check failed: " + name + " " + str(detail))
    checks.append({"name": name, "detail": str(detail)})


def call(method, path, body=None, token=None, raw=None, headers=None):
    conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=900)
    h = {"Cookie": "; ".join(k + "=" + v for k, v in cookies.items())}
    if method != "GET":
        h["Origin"] = "http://localhost:5187"
    if token:
        h["X-CSRF-Token"] = token
    data = None
    if raw is not None:
        data = raw
        h["Content-Type"] = "application/octet-stream"
    elif body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    if headers:
        h.update(headers)
    conn.request(method, path, data, h)
    response = conn.getresponse()
    for name, value in response.getheaders():
        if name.lower() == "set-cookie":
            key, val = value.split(";", 1)[0].split("=", 1)
            cookies[key] = val
    try:
        payload = response.read()
    except http.client.IncompleteRead as ex:
        payload = ex.partial
    result = (response.status, dict(response.getheaders()), payload)
    conn.close()
    return result


def csrf():
    status, _, payload = call("GET", "/api/v1/auth/csrf")
    check(status == 200, "csrf", status)
    return json.loads(payload)["token"]


def write(method, path, body=None, raw=None):
    return call(method, path, body, csrf(), raw=raw)


def main():
    status, _, _ = write(
        "POST", "/api/v1/auth/login", {"username": "ui-member", "password": "M3-media-fixture-only"}
    )
    check(status == 200, "login", status)

    status, _, payload = call("GET", "/api/v1/capabilities/nodes/get")
    check(status == 200 and json.loads(payload)["data"] == [], "nodesCatalogEmpty", status)
    status, _, payload = call("GET", "/api/v1/capabilities/tools/renderers")
    check(status == 200 and json.loads(payload)["data"] == [], "toolRenderersEmpty", status)
    status, _, payload = call("GET", "/api/v1/capabilities/ffmpeg/status")
    ffmpeg_status = json.loads(payload)["data"]
    check(status == 200 and ffmpeg_status["available"] is True, "ffmpegAvailable", ffmpeg_status)
    check("mp4-720p" in ffmpeg_status["presets"], "presetsPublished", ffmpeg_status["presets"])

    source = SOURCE.read_bytes()
    status, _, payload = write(
        "POST", f"/api/v1/projects/{PROJECT}/media?filename=source-{uuid.uuid4().hex[:6]}.mp4", raw=source
    )
    check(status in (200, 201), "uploadSource", status)
    media_id = json.loads(payload)["id"] if "id" in json.loads(payload) else json.loads(payload)["data"]["id"]
    source_path = f".huimeng-media/{media_id}.mp4"

    request_id = str(uuid.uuid4())
    body = {
        "requestId": request_id,
        "directory": PROJECT,
        "options": {},
        "calls": [],
        "operation": {"method": "transcode", "args": [{"source": source_path, "preset": "mp4-720p"}]},
    }
    started = time.time()
    status, headers, payload = write(
        "POST", f"/api/v1/projects/{PROJECT}/creative/ffmpeg/execute", body
    )
    check(status == 200, "executeStarted", status)
    check("text/event-stream" in headers.get("Content-Type", ""), "executeStreams", headers.get("Content-Type"))
    events = []
    for line in payload.decode("utf-8", "replace").split("\n"):
        line = line.strip()
        if not line.startswith("data:"):
            continue
        events.append(json.loads(line[5:].strip()))
    kinds = [event["event"] for event in events]
    check("progress" in kinds, "progressReported", kinds[:6])
    check(kinds[-1] == "done", "doneLast", kinds[-3:])
    produced = events[-1]["args"][0]
    check(produced["preset"] == "mp4-720p", "presetEchoed", produced)
    percent = [e["args"][0].get("percent") for e in events if e["event"] == "progress"]
    check(any(p is not None and p > 0 for p in percent), "percentComputed", percent[-3:])
    seconds = round(time.time() - started, 1)

    # 下载成品并核对是真实 MP4（ftyp box）。
    media_id_out = produced["path"].split("/")[-1].split(".")[0]
    status, _, payload = call(
        "GET", f"/api/v1/projects/{PROJECT}/media/{media_id_out}/content"
    )
    check(status == 200 and len(payload) > 10000, "transcodedDownloadable", f"{status} {len(payload)}")
    output_bytes = len(payload)
    check(payload[4:8] == b"ftyp", "outputIsMp4", payload[8:12])

    # 取消：另一条较长素材，提交后立即取消。
    cancel_name = "cancelProbe"
    if LONG_SOURCE and LONG_SOURCE.exists():
        long_bytes = LONG_SOURCE.read_bytes()
        status, _, payload = write(
            "POST", f"/api/v1/projects/{PROJECT}/media?filename=long-{uuid.uuid4().hex[:6]}.mp4", raw=long_bytes
        )
        check(status in (200, 201), "uploadLongSource", status)
        long_id = json.loads(payload).get("id") or json.loads(payload)["data"]["id"]
        cancel_id = str(uuid.uuid4())
        cancel_body = {
            "requestId": cancel_id,
            "directory": PROJECT,
            "options": {},
            "calls": [],
            "operation": {
                "method": "transcode",
                "args": [{"source": f".huimeng-media/{long_id}.mp4", "preset": "webm-720p"}],
            },
        }
        # 生成请求会一直占住连接直到结束，放在线程里跑，再用同 requestId 取消。
        holder = {}

        def run_cancel_probe():
            holder["result"] = write(
                "POST", f"/api/v1/projects/{PROJECT}/creative/ffmpeg/execute", cancel_body
            )

        worker = threading.Thread(target=run_cancel_probe, daemon=True)
        worker.start()
        time.sleep(2)
        check(worker.is_alive(), "cancelRunStillRunning")
        status, _, payload = write(
            "POST",
            f"/api/v1/projects/{PROJECT}/creative/ffmpeg/execute",
            {"requestId": cancel_id, "directory": PROJECT, "options": {}, "calls": [], "operation": {"method": "cancel", "args": []}},
        )
        check(status == 200, "cancelAccepted", status)
        worker.join(120)
        check(not worker.is_alive(), "cancelRunFinished")
        _, _, cancel_payload = holder["result"]
        cancel_events = [
            json.loads(line[5:].strip())
            for line in cancel_payload.decode("utf-8", "replace").split("\n")
            if line.strip().startswith("data:")
        ]
        check(
            cancel_events and cancel_events[-1]["event"] == "error" and "取消" in cancel_events[-1]["args"][0]["message"],
            "cancelEndsStream",
            cancel_events[-1] if cancel_events else "no events",
        )
        cancel_name = "cancelled"
    else:
        cancel_name = "skipped"

    # 参数边界：外部路径与未知预设不触达 ffmpeg。
    status, _, _ = write(
        "POST",
        f"/api/v1/projects/{PROJECT}/creative/ffmpeg/execute",
        {"requestId": str(uuid.uuid4()), "directory": PROJECT, "options": {}, "calls": [], "operation": {"method": "transcode", "args": [{"source": "/etc/passwd"}]}},
    )
    check(status == 400, "foreignPathRejected", status)
    status, _, _ = write(
        "POST",
        f"/api/v1/projects/{PROJECT}/creative/ffmpeg/execute",
        {"requestId": str(uuid.uuid4()), "directory": PROJECT, "options": {}, "calls": [], "operation": {"method": "transcode", "args": [{"source": source_path, "preset": "4k-ultra"}]}},
    )
    check(status == 400, "unknownPresetRejected", status)

    EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
    EVIDENCE.write_text(
        json.dumps(
            {
                "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "project": PROJECT,
                "sourceBytes": len(source),
                "outputBytes": output_bytes,
                "seconds": seconds,
                "events": kinds,
                "percentSamples": percent[-3:],
                "cancel": cancel_name,
                "checks": checks,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n"
    )
    print("M3-05 ffmpeg checks passed:", len(checks))
    for item in checks:
        print(" -", item["name"], item["detail"])


if __name__ == "__main__":
    main()
