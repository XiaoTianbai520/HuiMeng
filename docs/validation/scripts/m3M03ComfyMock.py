"""M3-03 合成 ComfyUI：只复刻本批用到的协议端点，不加载模型、不写用户目录。

行为：
- /prompt 记录提交图（含 resolution/width/height/参考图名），返回一个与请求不同的 UUID；
  提示词含 [slow] 时长时间保持运行，便于验证名额与取消。
- /history/<id>、/view、/api/jobs/<id>、/api/jobs/<id>/cancel、/upload/image、/system_stats。
- /_stats 返回提交/上传/取消次数，用于证明第 6 个请求没有触达执行器。
"""

import json
import struct
import threading
import time
import uuid
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

LOCK = threading.Lock()
JOBS = {}
STATE = {"promptCount": 0, "uploadCount": 0, "cancelCount": 0}
PNG = None


def make_png(width=64, height=64, rgb=(60, 120, 200)):
    raw = b""
    for _ in range(height):
        raw += b"\x00" + bytes(rgb) * width

    def chunk(kind, data):
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def job_state(job):
    if job["cancelled"]:
        return "cancelled"
    if time.time() - job["started"] >= job["delay"]:
        return "completed"
    return "in_progress"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def body(self, length):
        return self.rfile.read(length) if length else b""

    def reply(self, payload, status=200, content_type="application/json"):
        if isinstance(payload, (dict, list)):
            payload = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        url = urlparse(self.path)
        path = url.path
        if path == "/system_stats":
            return self.reply({"system": {"os": "synthetic", "comfyui_version": "0.0.0"}})
        if path == "/_stats":
            with LOCK:
                jobs = {
                    key: {"prefix": value["prefix"], "state": job_state(value), "hasReference": value["hasReference"]}
                    for key, value in JOBS.items()
                }
            return self.reply({**STATE, "jobs": jobs})
        if path.startswith("/history/"):
            key = path.split("/")[-1]
            with LOCK:
                job = JOBS.get(key)
                if job is None or job_state(job) != "completed":
                    return self.reply({})
                record = {
                    key: {
                        "status": {"status_str": "success", "completed": True},
                        "outputs": {
                            "9": {
                                "images": [
                                    {
                                        "filename": job["filename"],
                                        "subfolder": job["subfolder"],
                                        "type": "output",
                                    }
                                ]
                            }
                        },
                    }
                }
            return self.reply(record)
        if path == "/view":
            query = parse_qs(url.query)
            name = (query.get("filename") or [""])[0]
            if not name.endswith(".png"):
                return self.reply({"error": "bad name"}, 400)
            return self.reply(PNG, content_type="image/png")
        if path.startswith("/api/jobs/"):
            key = path.split("/")[-1]
            with LOCK:
                job = JOBS.get(key)
                if job is None:
                    return self.reply({}, 404)
                return self.reply({"status": job_state(job)})
        return self.reply({"error": "not found"}, 404)

    def do_POST(self):
        url = urlparse(self.path)
        path = url.path
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.body(length)
        if path == "/prompt":
            graph = json.loads(raw)
            prompt = graph.get("prompt") or {}
            encoder = prompt.get("4", {}).get("inputs", {})
            canvas = prompt.get("5", {}).get("inputs", {})
            prefix = prompt.get("9", {}).get("inputs", {}).get("filename_prefix", "")
            folder, _, base = prefix.rpartition("/")
            key = str(uuid.uuid4())
            text = str(encoder.get("prompt", ""))
            delay = 600 if ("[slow]" in text or "[cancel]" in text) else 3
            with LOCK:
                STATE["promptCount"] += 1
                JOBS[key] = {
                    "prefix": prefix,
                    "subfolder": folder,
                    "filename": base + "_00001_.png",
                    "started": time.time(),
                    "delay": delay,
                    "cancelled": False,
                    "hasReference": bool(prompt.get("10", {}).get("inputs", {}).get("image")),
                    "spec": {
                        "resolution": encoder.get("resolution"),
                        "width": canvas.get("width"),
                        "height": canvas.get("height"),
                        "prompt": text,
                    },
                }
            return self.reply({"prompt_id": key, "number": STATE["promptCount"]})
        if path == "/upload/image":
            name = "unknown.png"
            if b'filename="' in raw:
                name = raw.split(b'filename="', 1)[1].split(b'"', 1)[0].decode()
            with LOCK:
                STATE["uploadCount"] += 1
            return self.reply({"name": name, "subfolder": "", "type": "input"})
        if path.startswith("/api/jobs/") and path.endswith("/cancel"):
            key = path.split("/")[-2]
            with LOCK:
                job = JOBS.get(key)
                STATE["cancelCount"] += 1
                if job is None:
                    return self.reply({"cancelled": False})
                job["cancelled"] = True
            return self.reply({"cancelled": True})
        return self.reply({"error": "not found"}, 404)


if __name__ == "__main__":
    PNG = make_png()
    ThreadingHTTPServer(("127.0.0.1", 18110), Handler).serve_forever()
