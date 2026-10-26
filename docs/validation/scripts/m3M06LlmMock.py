"""M3-06 合成文本供应商：只复刻 OpenAI 兼容流式接口，供 A2A 团队任务验收。"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("M306_LLM_PORT", "18140"))
stats = {"requests": 0, "prompts": []}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        return

    def _json(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/_stats":
            self._json(200, stats)
            return
        self._json(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        if not self.path.endswith("/chat/completions"):
            self._json(404, {"error": "not found"})
            return
        body = json.loads(raw or b"{}")
        messages = body.get("messages") or []
        prompt = ""
        for message in reversed(messages):
            if message.get("role") == "user":
                prompt = str(message.get("content") or "")
                break
        stats["requests"] += 1
        stats["prompts"].append(prompt[:200])
        text = "团队已收到任务：" + prompt[:120]
        frames = [
            {"choices": [{"delta": {"content": text}}]},
            {"choices": [{"delta": {}, "finish_reason": "stop"}]},
        ]
        payload = "".join("data: " + json.dumps(frame, ensure_ascii=False) + "\n\n" for frame in frames)
        payload += "data: [DONE]\n\n"
        data = payload.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
