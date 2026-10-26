"""M3-05 合成视频执行器：复刻 H3 用到的 ComfyUI 端点，记录每次提交的图与参数。

支持 [slow] 提示词后缀保持运行，便于验证名额与取消；/_stats 返回提交次数与最近一次规格。
"""

import json
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()
JOBS = {}
STATE = {"prompts": 0, "uploads": 0, "cancels": 0, "last": {}}
MP4 = b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isomiso2" + b"\x00" * 256


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def reply(self, payload, status=200, content_type="application/json"):
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def job_state(self, job):
        if job["cancelled"]:
            return "cancelled"
        if time.time() - job["started"] >= job["delay"]:
            return "completed"
        return "running"

    def do_GET(self):
        if self.path == "/system_stats":
            return self.reply({"system": {"os": "synthetic"}})
        if self.path == "/_stats":
            with LOCK:
                return self.reply(STATE)
        if self.path.startswith("/history/"):
            key = self.path.split("/")[-1]
            with LOCK:
                job = JOBS.get(key)
                if job is None or self.job_state(job) != "completed":
                    return self.reply({})
                return self.reply(
                    {
                        key: {
                            "status": {"status_str": "success", "completed": True},
                            "outputs": {
                                "14": {
                                    "images": [
                                        {
                                            "filename": job["prefix"].rsplit("/", 1)[-1]
                                            + "_00001_.mp4",
                                            "subfolder": job["prefix"].rsplit("/", 1)[0],
                                            "type": "output",
                                        }
                                    ]
                                }
                            },
                        }
                    }
                )
        if self.path.startswith("/view"):
            return self.reply(MP4, content_type="video/mp4")
        if self.path.startswith("/api/jobs/"):
            key = self.path.split("/")[-1]
            with LOCK:
                job = JOBS.get(key)
                if job is None:
                    return self.reply({}, 404)
                return self.reply({"status": self.job_state(job)})
        return self.reply({"error": "not found"}, 404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        if self.path == "/upload/image":
            with LOCK:
                STATE["uploads"] += 1
                index = STATE["uploads"]
            return self.reply({"name": f"ref-{index}.png", "subfolder": "", "type": "input"})
        if self.path == "/prompt":
            body = json.loads(raw)
            graph = body.get("prompt") or {}
            condition = (graph.get("5") or {}).get("inputs") or {}
            save = (graph.get("14") or {}).get("inputs") or {}
            text = str(condition.get("prompt", ""))
            delay = 600 if "[slow]" in text else 2
            key = str(uuid.uuid4())
            with LOCK:
                STATE["prompts"] += 1
                STATE["last"] = {
                    "classType": (graph.get("5") or {}).get("class_type"),
                    "width": condition.get("width"),
                    "height": condition.get("height"),
                    "length": condition.get("length"),
                    "references": sorted(k for k in condition if k.startswith("ref_images.")),
                    "nodes": len(graph),
                    "hasFirstFrame": "15" in graph,
                    "hasLastFrame": "16" in graph,
                }
                JOBS[key] = {
                    "started": time.time(),
                    "delay": delay,
                    "cancelled": False,
                    "prefix": save.get("filename_prefix", "out"),
                }
            return self.reply({"prompt_id": key})
        if self.path.startswith("/api/jobs/") and self.path.endswith("/cancel"):
            key = self.path.split("/")[-2]
            with LOCK:
                STATE["cancels"] += 1
                job = JOBS.get(key)
                if job:
                    job["cancelled"] = True
            return self.reply({"cancelled": True})
        return self.reply({"error": "not found"}, 404)


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 18130), Handler).serve_forever()
