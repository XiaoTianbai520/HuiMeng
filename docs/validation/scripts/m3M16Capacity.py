"""M3-06 A16 跨入口名额验收：同一项目的五个名额由 Web、MCP、A2A 共用。

夹具（CapacityHarness）内嵌合成 ComfyUI（固定延迟出图）与合成文本供应商（先给 generateImage 工具调用）。
验收只连本地 Spring 进程，不调用真实模型、对象存储或团队数据库。
"""
import http.client
import json
import os
import sys
import threading
import time
import uuid
from pathlib import Path

PORT = int(os.environ.get("M316_PORT", "18095"))
READY = Path(os.environ["M316_READY"])
EVIDENCE = Path(os.environ["M316_EVIDENCE"])
ready = json.loads(READY.read_text(encoding="utf-8"))
ALPHA = ready["alphaProject"]
BETA = ready["betaProject"]
ALPHA_TOKEN = ready["alphaToken"]
BETA_TOKEN = ready["betaToken"]
ADMIN = (ready["adminUser"], ready["adminPassword"])
MEMBER = (ready["memberUser"], ready["memberPassword"])
COMFY = "http://127.0.0.1:%d" % ready["comfyPort"]
LLM = "http://127.0.0.1:%d" % ready["llmPort"]
checks = []
skips = []
STARTED = time.time()


def mark(label):
    print("[%5.1fs] %s" % (time.time() - STARTED, label))


def check(ok, name, detail=""):
    if not ok:
        raise RuntimeError("A16 capacity check failed: " + name + " " + str(detail))
    checks.append({"name": name, "detail": str(detail)[:400]})


def mock_get(base, path):
    host, port = base.rsplit(":", 1)
    conn = http.client.HTTPConnection(host.split("//")[1], int(port), timeout=10)
    conn.request("GET", path)
    response = conn.getresponse()
    body = json.loads(response.read() or b"{}")
    conn.close()
    return body


def comfy_stats():
    return mock_get(COMFY, "/stats")


def llm_stats():
    return mock_get(LLM, "/stats")


def wait_for(predicate, timeout=30, interval=0.25, label=""):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(interval)
    raise RuntimeError("A16 timeout waiting for " + label)


class Web:
    """独立浏览器会话：各自持有 Cookie 与 CSRF，模拟两个成员同时操作。"""

    def __init__(self, user, password):
        self.cookies = {}
        self.token = None
        self._refresh()
        status, _, payload = self.call(
            "POST", "/api/v1/auth/login", {"username": user, "password": password}
        )
        check(status == 200, "webLogin " + user, status)
        self._refresh()

    def _refresh(self):
        status, _, payload = self.call("GET", "/api/v1/auth/csrf")
        check(status == 200, "webCsrf", status)
        self.token = json.loads(payload)["token"]

    def call(self, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=120)
        h = {"Cookie": "; ".join(k + "=" + v for k, v in self.cookies.items())}
        if method != "GET":
            h["Origin"] = "http://localhost:5187"
        if self.token:
            h["X-CSRF-Token"] = self.token
        if headers:
            h.update(headers)
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        conn.request(method, path, data, h)
        response = conn.getresponse()
        for name, value in response.getheaders():
            if name.lower() == "set-cookie":
                key, val = value.split(";", 1)[0].split("=", 1)
                self.cookies[key] = val
        payload = response.read()
        result = (response.status, dict(response.getheaders()), payload)
        conn.close()
        return result

    def generate(self, project, prompt, slot):
        """SSE 生成：拿到响应头即视为准入，再读完流让服务端执行完整个生命周期。"""
        body = {
            "providerId": "qwenImage21Local",
            "modelId": "qwenImage21Text",
            "mediaType": "image",
            "prompt": prompt,
            "size": "1K",
            "ratio": "1:1",
        }
        data = json.dumps(body).encode()
        headers = {
            "Cookie": "; ".join(k + "=" + v for k, v in self.cookies.items()),
            "Origin": "http://localhost:5187",
            "X-CSRF-Token": self.token or "",
            "Content-Type": "application/json",
            "Idempotency-Key": "web-" + slot["name"] + "-" + uuid.uuid4().hex,
        }
        conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=120)
        conn.request(
            "POST",
            "/api/v1/projects/" + project + "/creative/ai/media/generate",
            data,
            headers,
        )
        response = conn.getresponse()
        slot.update(status=response.status, runId=response.getheader("X-Run-Id", ""))
        slot["admitted"].set()
        slot["body"] = response.read()
        conn.close()
        return response.status


def call(method, path, body=None, bearer=None, headers=None):
    conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=120)
    h = {}
    if method != "GET":
        h["Origin"] = "http://localhost:5187"
    if bearer:
        h["Authorization"] = "Bearer " + bearer
    if headers:
        h.update(headers)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    conn.request(method, path, data, h)
    response = conn.getresponse()
    payload = response.read()
    result = (response.status, dict(response.getheaders()), payload)
    conn.close()
    return result


def frame(payload):
    text = payload.decode("utf-8", "replace").strip()
    if text.startswith("event:"):
        lines = [line for line in text.splitlines() if line.startswith("data:")]
        text = "\n".join(line[5:].strip() for line in lines)
    if text.startswith("data:"):
        text = text.split("data:", 1)[1].strip()
    return json.loads(text)


def rpc(method, params):
    return {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}


def mcp(token, body, headers=None):
    status, response_headers, payload = call(
        "POST", "/mcp", body, bearer=token, headers=headers
    )
    return status, response_headers, frame(payload) if payload else None


def tool(token, name, args=None, idem=None):
    headers = {"Idempotency-Key": idem} if idem else None
    status, _, body = mcp(token, rpc("tools/call", {"name": name, "arguments": args or {}}), headers)
    return status, body


def a2a(token, team, method, params):
    status, _, payload = call(
        "POST",
        "/a2a/" + team,
        {"jsonrpc": "2.0", "id": 1, "method": method, "params": params},
        bearer=token,
        headers={"A2A-Version": "1.0"},
    )
    return status, frame(payload) if payload else None


def generated_media(token, project):
    status, body = tool(token, "listMedia", {"limit": 50})
    check(status == 200, "listMedia", status)
    items = body["result"]["structuredContent"]["items"]
    return [item for item in items if item["filename"].startswith("generated-")]


def run_state(web, project, run_id):
    status, _, payload = web.call("GET", "/api/v1/projects/%s/runs/%s" % (project, run_id))
    return status, (json.loads(payload) if payload else None)


def main():
    admin = Web(*ADMIN)
    member = Web(*MEMBER)
    check(True, "两个 Web 会话独立登录")

    # 1) 握手并确认生成工具已授权。
    status, _, body = mcp(ALPHA_TOKEN, rpc("initialize", {"protocolVersion": "2025-06-18"}))
    check(status == 200, "mcpInitialize", status)
    status, _, body = mcp(ALPHA_TOKEN, rpc("tools/list", {}))
    names = [item["name"] for item in body["result"]["tools"]]
    check("generateMedia" in names and "cancelRun" in names, "mcp生成工具已授权", names)

    base = comfy_stats()
    check(base["submits"] == 0, "执行器起点为空", base)

    # 2) Web 两个成员各占一个名额（SSE 连接保持到出图完成）。
    slots = []
    threads = []
    for web, name in ((admin, "admin"), (member, "member")):
        slot = {"admitted": threading.Event(), "name": name}
        slots.append(slot)

        def run(web=web, slot=slot):
            try:
                web.generate(ALPHA, "Web 入口并发图 " + slot["name"], slot)
            except Exception as error:  # noqa: BLE001 - 线程异常留给主线程断言
                slot["error"] = repr(error)
                slot["admitted"].set()

        thread = threading.Thread(target=run, daemon=True)
        thread.start()
        threads.append(thread)
    for slot in slots:
        slot["admitted"].wait(60)
    for slot in slots:
        check(slot.get("status") == 200, "Web 生成准入 " + slot["name"], slot.get("status"))
        check(bool(slot.get("runId")), "Web 返回运行 ID " + slot["name"], slot.get("runId"))
    mark("web admitted")
    check(True, "Web 两路共占两个名额", [slot["runId"] for slot in slots])

    # 3) MCP 外部客户端在同一项目再占两个名额。
    mcp_runs = []
    for index in (1, 2):
        status, body = tool(
            ALPHA_TOKEN,
            "generateMedia",
            {
                "providerId": "qwenImage21Local",
                "modelId": "qwenImage21Text",
                "mediaType": "image",
                "prompt": "MCP 入口并发图 %d" % index,
                "size": "1K",
                "ratio": "1:1",
            },
            idem="mcp-alpha-" + uuid.uuid4().hex,
        )
        check(status == 200, "MCP 生成准入 %d" % index, (status, body))
        result = body["result"]["structuredContent"]
        mcp_runs.append(result["id"])
        check(result["capacityHeld"] is True, "MCP 运行占位 %d" % index, result)
    wait_for(lambda: comfy_stats()["submits"] == 4, 20, label="四次执行器提交")
    mark("mcp admitted, four submits")
    check(True, "Web 与 MCP 已占满四个名额", comfy_stats())

    # 4) A2A 团队任务由 Agent 内部生图占第五个名额。
    status, body = a2a(
        ALPHA_TOKEN,
        "capacityTeam",
        "SendMessage",
        {
            "message": {
                "parts": [{"text": "请生成一张用于容量验收的图片"}],
                "contextId": uuid.uuid4().hex,
            },
            "configuration": {"returnImmediately": True},
        },
    )
    check(status == 200, "A2A 任务提交", (status, body))
    task = body["result"]["task"]
    mark("a2a submitted")
    check(task["status"]["state"] in ("TASK_STATE_SUBMITTED", "TASK_STATE_WORKING"), "A2A 任务已受理", task)
    wait_for(lambda: llm_stats()["toolCalls"] >= 1, 40, label="A2A 触发工具调用")
    wait_for(lambda: comfy_stats()["submits"] == 5, 40, label="A2A 内部生图提交")
    mark("a2a internal submit, five slots held")
    check(True, "A2A 内部生图占第五个名额", comfy_stats())

    # 5) 第六个并发必须拒绝且不触达执行器。
    before = comfy_stats()["submits"]
    status, body = tool(
        ALPHA_TOKEN,
        "generateMedia",
        {
            "providerId": "qwenImage21Local",
            "modelId": "qwenImage21Text",
            "mediaType": "image",
            "prompt": "超出名额的第六张图",
            "size": "1K",
            "ratio": "1:1",
        },
        idem="mcp-alpha-" + uuid.uuid4().hex,
    )
    mark("sixth attempt")
    check(status == 429, "第六个并发被拒绝", status)
    check(
        body["error"]["data"]["code"] == "PROJECT_CAPACITY_EXCEEDED",
        "拒绝码为项目容量超限",
        body,
    )
    check(comfy_stats()["submits"] == before, "拒绝未触达执行器", comfy_stats())

    # 6) 取消一个 MCP 运行后名额释放，新的生成可以准入。
    status, body = tool(ALPHA_TOKEN, "cancelRun", {"runId": mcp_runs[0]})
    check(status == 200, "取消 MCP 运行", (status, body))
    released = wait_for(
        lambda: run_released(ALPHA_TOKEN, ALPHA, mcp_runs[0]), 40, label="取消后释放名额"
    )
    mark("cancel released")
    check(released, "取消确认终态后释放名额", mcp_runs[0])

    status, body = tool(
        ALPHA_TOKEN,
        "generateMedia",
        {
            "providerId": "qwenImage21Local",
            "modelId": "qwenImage21Text",
            "mediaType": "image",
            "prompt": "释放名额后的补位生成",
            "size": "1K",
            "ratio": "1:1",
        },
        idem="mcp-alpha-" + uuid.uuid4().hex,
    )
    check(status == 200, "释放后重新准入", (status, body))
    refill = body["result"]["structuredContent"]["id"]
    status, _ = tool(ALPHA_TOKEN, "cancelRun", {"runId": refill})
    check(status == 200, "取消补位运行", status)

    # 7) 另一个项目独立计数：本项目满额时 Beta 仍可准入。
    status, body = tool(
        BETA_TOKEN,
        "generateMedia",
        {
            "providerId": "qwenImage21Local",
            "modelId": "qwenImage21Text",
            "mediaType": "image",
            "prompt": "另一个项目的独立名额",
            "size": "1K",
            "ratio": "1:1",
        },
        idem="mcp-beta-" + uuid.uuid4().hex,
    )
    mark("beta admitted")
    check(status == 200, "不同项目独立计数", (status, body))
    beta_run = body["result"]["structuredContent"]["id"]
    tool(BETA_TOKEN, "cancelRun", {"runId": beta_run})

    # 8) Web 两路执行完成，名额按成功路径释放。
    for thread in threads:
        thread.join(120)
    for slot in slots:
        status, run = run_state(admin if slot["name"] == "admin" else member, ALPHA, slot["runId"])
        check(status == 200, "Web 运行可查询 " + slot["name"], status)
        check(run["state"] == "SUCCEEDED", "Web 运行成功 " + slot["name"], run)
        check(run["capacityHeld"] is False, "Web 成功后释放名额 " + slot["name"], run)

    # 9) A2A 任务完成并留下真实产物。
    task_id = task["id"]
    finished = wait_for(
        lambda: task_terminal(ALPHA_TOKEN, "capacityTeam", task_id), 120, label="A2A 任务终态"
    )
    check(finished["status"]["state"] == "TASK_STATE_COMPLETED", "A2A 任务完成", finished)
    check(bool(finished.get("artifacts")), "A2A 返回结果", finished)
    media = generated_media(ALPHA_TOKEN, ALPHA)
    check(len(media) >= 1, "A2A 生图进入项目素材", media[:3])
    check(
        any(item["filename"].startswith("generated-") for item in media),
        "素材为受控生成命名",
        media[:1],
    )

    stats = comfy_stats()
    check(stats["submits"] == 7, "执行器提交数与准入一致", stats)
    check(stats["completed"] == 4, "两 Web 与 MCP、A2A 各有真实产物", stats)
    check(stats["cancelled"] == 3, "三次取消都下发到执行器", stats)
    check(stats["active"] == "", "结束时没有遗留活跃任务", stats)

    EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
    EVIDENCE.write_text(
        json.dumps(
            {
                "createdAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                "checks": checks,
                "skips": skips,
                "stats": stats,
                "project": ALPHA,
                "otherProject": BETA,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    print("A16 capacity checks: %d passed" % len(checks))
    for item in checks:
        print("OK  " + item["name"])


def run_released(token, project, run_id):
    status, body = tool(token, "getRun", {"runId": run_id})
    if status != 200:
        return False
    run = body["result"]["structuredContent"]
    return run["state"] in ("CANCELLED", "SUCCEEDED", "FAILED") and run["capacityHeld"] is False


def task_terminal(token, team, task_id):
    status, body = a2a(token, team, "GetTask", {"id": task_id})
    if status != 200:
        return None
    task = body["result"]
    return task if task["status"]["state"].startswith("TASK_STATE_") and task["status"][
        "state"
    ] in (
        "TASK_STATE_COMPLETED",
        "TASK_STATE_FAILED",
        "TASK_STATE_CANCELED",
        "TASK_STATE_REJECTED",
    ) else None


if __name__ == "__main__":
    try:
        main()
    except Exception as error:  # noqa: BLE001 - 验收失败要带明细退出
        print("FAILED: " + str(error))
        print("checks passed before failure: %d" % len(checks))
        for item in checks:
            print("OK  " + item["name"])
        sys.exit(1)
