"""M3-04 合成文本供应商：首轮返回画布工具调用，收到工具结果后回显真实画布数据。

只复刻 OpenAI 兼容流式协议，不连接真实模型；用于验证 NDJSON 顺序、画布回执与工具结果回填。
"""

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = {"requests": 0, "toolCalls": 0, "toolResults": 0}


def sse(payload):
    return ("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n").encode()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == "/_stats":
            body = json.dumps(STATE).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        messages = body.get("messages") or []
        with_tools = bool(body.get("tools"))
        last = messages[-1] if messages else {}
        responded = last.get("role") == "tool"
        out = b""
        STATE["requests"] += 1
        if responded:
            STATE["toolResults"] += 1
            content = str(last.get("content") or "")
            snippet = content[:400]
            out += sse({"choices": [{"delta": {"content": "画布工具已执行，页面回传：" + snippet}}]})
            out += sse({"choices": [{"delta": {}, "finish_reason": "stop"}]})
        else:
            STATE["toolCalls"] += 1
            out += sse(
                {
                    "choices": [
                        {
                            "delta": {
                                "tool_calls": [
                                    {
                                        "index": 0,
                                        "id": "call-canvas-1",
                                        "function": {"name": "getCanvas", "arguments": "{}"},
                                    }
                                ]
                            }
                        }
                    ]
                }
            )
            out += sse({"choices": [{"delta": {}, "finish_reason": "tool_calls"}]})
        out += b"data: [DONE]\n\n"
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)


if __name__ == "__main__":
    print("tools sent:", STATE, flush=True)
    ThreadingHTTPServer(("127.0.0.1", 18120), Handler).serve_forever()
