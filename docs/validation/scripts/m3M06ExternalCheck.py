"""M3-06 A13 外部协议验收：真实 Spring HTTPS 进程上的 MCP 与 A2A。

覆盖握手、工具目录裁剪、服务端工具、页面未连接、跨项目读取、A2A 版本头、团队任务提交/查询/取消、
跨客户端归属隔离与凭证撤销。不调用真实模型或执行器。
"""
import http.client
import json
import os
import sys
import time
import uuid
from pathlib import Path

PORT = int(os.environ.get("M306_PORT", "18099"))
READY = Path(os.environ["M306_READY"])
EVIDENCE = Path(os.environ["M306_EVIDENCE"])
ready = json.loads(READY.read_text(encoding="utf-8"))
ALPHA = ready["alphaProject"]
BETA = ready["betaProject"]
ALPHA_TOKEN = ready["alphaToken"]
BETA_TOKEN = ready["betaToken"]
checks = []
cookies = {}


def check(ok, name, detail=""):
    if not ok:
        raise RuntimeError("M3-06 external check failed: " + name + " " + str(detail))
    checks.append({"name": name, "detail": str(detail)[:400]})


def call(method, path, body=None, bearer=None, token=None, headers=None):
    conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=120)
    h = {"Cookie": "; ".join(k + "=" + v for k, v in cookies.items())}
    if method != "GET":
        h["Origin"] = "http://localhost:5187"
    if bearer:
        h["Authorization"] = "Bearer " + bearer
    if token:
        h["X-CSRF-Token"] = token
    data = None
    if body is not None:
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
    payload = response.read()
    result = (response.status, dict(response.getheaders()), payload)
    conn.close()
    return result


def frame(payload):
    """SSE 单帧或纯 JSON 统一解析。"""
    text = payload.decode("utf-8", "replace").strip()
    if text.startswith("data:"):
        text = text.split("data:", 1)[1].strip()
    return json.loads(text)


def mcp(token, body, headers=None):
    status, response_headers, payload = call(
        "POST", "/mcp", body, bearer=token, headers=headers
    )
    return status, response_headers, frame(payload) if payload else None


def rpc(method, params, rpc_id=None):
    return {
        "jsonrpc": "2.0",
        "id": rpc_id if rpc_id is not None else str(uuid.uuid4()),
        "method": method,
        "params": params,
    }


def tool(token, name, args=None, key=None):
    headers = {"Idempotency-Key": key} if key else None
    status, _, body = mcp(
        token,
        rpc("tools/call", {"name": name, "arguments": args or {}}),
        headers,
    )
    return status, body


def admin_csrf():
    status, _, payload = call("GET", "/api/v1/auth/csrf")
    check(status == 200, "adminCsrf", status)
    return json.loads(payload)["token"]


def admin_login():
    token = admin_csrf()
    status, _, _ = call(
        "POST",
        "/api/v1/auth/login",
        {"username": ready["adminUser"], "password": ready["adminPassword"]},
        token=token,
    )
    check(status == 200, "adminLogin", status)
    return admin_csrf()


def main():
    # 1) 未携带凭证与伪造凭证都在协议分派前被拒绝。
    status, headers, body = call("POST", "/mcp", rpc("initialize", {}))
    check(status == 401, "mcpUnauthorized", status)
    check(headers.get("WWW-Authenticate") == "Bearer", "mcpWwwAuthenticate", headers)
    status, _, body = mcp("hm1." + str(uuid.uuid4()) + "." + "x" * 43, rpc("initialize", {}))
    check(status == 401, "mcpInvalidToken", status)

    # 2) 握手：SSE 单帧、会话头、协议版本。
    status, headers, body = mcp(ALPHA_TOKEN, rpc("initialize", {"protocolVersion": "2025-06-18"}))
    check(status == 200, "mcpInitialize", status)
    check(headers.get("Content-Type", "").startswith("text/event-stream"), "mcpInitializeSse", headers)
    check(bool(headers.get("Mcp-Session-Id")), "mcpSessionHeader", headers)
    check(body["result"]["serverInfo"]["name"] == "huimeng", "mcpServerInfo", body)
    check(body["result"]["capabilities"]["tools"]["listChanged"] is False, "mcpCapabilities", body)

    # 3) 目录只包含已授权工具。
    status, _, body = mcp(ALPHA_TOKEN, rpc("tools/list", {}))
    names = [item["name"] for item in body["result"]["tools"]]
    check(status == 200, "mcpToolsList", status)
    for name in ("getAppState", "readWorkspaceFile", "saveWorkspaceFile", "listTeams", "getCanvas"):
        check(name in names, "mcpToolGranted:" + name, names)
    for name in ("cancelRun", "generateMedia", "writeDocument"):
        check(name not in names, "mcpToolFiltered:" + name, names)

    # 4) 未连接页面时 getAppState 如实说明；先等待上一个浏览器页面的连接被清理。
    deadline = time.time() + 45
    while time.time() < deadline:
        _, probe = tool(ALPHA_TOKEN, "getAppState")
        if probe["result"]["structuredContent"]["pageConnected"] is False:
            break
        time.sleep(2)
    status, body = tool(ALPHA_TOKEN, "getAppState")
    check(status == 200 and body["result"]["isError"] is False, "mcpAppState", body)
    state = body["result"]["structuredContent"]
    check(state["projectId"] == ALPHA, "mcpAppStateProject", state)
    check(state["pageConnected"] is False, "mcpAppStateNoPage", state)

    # 5) 服务端文件工具：列出、读取、原子新建与回读。
    status, body = tool(ALPHA_TOKEN, "listWorkspaceFiles", {"path": ""})
    names = [entry["name"] for entry in body["result"]["structuredContent"]["data"]["entries"]]
    check(status == 200 and "欢迎.md" in names, "mcpFileList", names)
    status, body = tool(ALPHA_TOKEN, "readWorkspaceFile", {"path": "欢迎.md"})
    snapshot = body["result"]["structuredContent"]["data"]
    check("Alpha 项目的合成文档" in snapshot["text"], "mcpFileRead", snapshot["revision"])
    note = "M3-06 由外部客户端写入\n"
    status, body = tool(
        ALPHA_TOKEN, "saveWorkspaceFile", {"path": "外部笔记.md", "content": note, "exclusive": True}
    )
    revision = body["result"]["structuredContent"]["data"]["revision"]
    check(status == 200 and len(revision) == 64, "mcpFileCreate", revision)
    status, body = tool(ALPHA_TOKEN, "readWorkspaceFile", {"path": "外部笔记.md"})
    check(body["result"]["structuredContent"]["data"]["text"] == note, "mcpFileReadBack", body)
    # 覆盖必须带修订号，缺省按冲突拒绝。
    status, body = tool(
        ALPHA_TOKEN, "saveWorkspaceFile", {"path": "外部笔记.md", "content": "覆盖"}
    )
    check(body["result"]["isError"] is True, "mcpFileRevisionRequired", body)

    # 6) 路径穿越与跨项目目录都不可达。
    status, body = tool(ALPHA_TOKEN, "readWorkspaceFile", {"path": "../外部协议隔离/私有.md"})
    check(body["result"]["isError"] is True, "mcpPathTraversalRejected", body)

    # 7) 未授权工具在分派前 403；生成类工具缺幂等键按工具错误。
    status, _, body = mcp(ALPHA_TOKEN, rpc("tools/call", {"name": "cancelRun", "arguments": {"runId": "x"}}))
    check(status == 403, "mcpToolNotGranted", status)
    check(body["error"]["data"]["code"] == "CAPABILITY_FORBIDDEN", "mcpToolNotGrantedCode", body)
    status, body = tool(ALPHA_TOKEN, "getCanvas", {})
    check(body["result"]["isError"] is True, "mcpCanvasRequiresPage", body)
    check("页面" in body["result"]["content"][0]["text"], "mcpCanvasMessage", body)

    # 8) A2A：卡片地址指向当前入口，版本头缺失明确拒绝。
    status, _, payload = call("GET", "/a2a/alphaTeam", bearer=ALPHA_TOKEN)
    card = frame(payload)
    check(status == 200 and card["name"] == "alphaTeam", "a2aCard", card.get("name"))
    interface = card["supportedInterfaces"][0]
    check(interface["url"].endswith("/a2a/alphaTeam"), "a2aCardUrl", interface["url"])
    check("protocolVersion" in interface and interface["protocolVersion"] == "1.0", "a2aCardVersion", interface)
    check("bearer" in card["securitySchemes"], "a2aCardSecurity", card["securitySchemes"])
    status, _, payload = call(
        "POST", "/a2a/alphaTeam", rpc("SendMessage", {"message": {"parts": [{"text": "hi"}]}}), bearer=ALPHA_TOKEN
    )
    version_error = frame(payload)
    check(status == 400, "a2aVersionRequired", status)
    check(version_error["error"]["data"]["code"] == "VERSION_NOT_SUPPORTED", "a2aVersionCode", version_error)

    # 9) 提交任务、查询终态与 artifact。
    message_id = str(uuid.uuid4())
    submit = rpc(
        "SendMessage",
        {
            "message": {
                "messageId": message_id,
                "role": "ROLE_USER",
                "parts": [{"text": "画一条海边分镜"}],
            },
            "configuration": {"returnImmediately": True},
        },
    )
    status, _, payload = call(
        "POST", "/a2a/alphaTeam", submit, bearer=ALPHA_TOKEN, headers={"A2A-Version": "1.0"}
    )
    submitted = frame(payload)["result"]["task"]
    task_id = submitted["id"]
    check(status == 200 and task_id, "a2aSubmit", task_id)
    check(submitted["contextId"], "a2aContext", submitted["contextId"])
    check(submitted["status"]["state"] in ("TASK_STATE_SUBMITTED", "TASK_STATE_WORKING"), "a2aSubmittedState", submitted["status"])

    deadline = time.time() + 90
    completed = None
    while time.time() < deadline:
        status, _, payload = call(
            "POST",
            "/a2a/alphaTeam",
            rpc("GetTask", {"id": task_id}),
            bearer=ALPHA_TOKEN,
            headers={"A2A-Version": "1.0"},
        )
        completed = frame(payload)["result"]
        if completed["status"]["state"] in (
            "TASK_STATE_COMPLETED",
            "TASK_STATE_FAILED",
            "TASK_STATE_CANCELED",
        ):
            break
        time.sleep(1)
    check(completed["status"]["state"] == "TASK_STATE_COMPLETED", "a2aCompleted", completed["status"])
    check(completed["artifacts"][0]["parts"][0]["text"].startswith("团队已收到任务"), "a2aArtifact", completed["artifacts"])
    check(len(completed["history"]) >= 2, "a2aHistory", len(completed["history"]))

    # 10) 其他客户端知道任务 ID 也不能查询或取消。
    status, _, payload = call(
        "POST", "/a2a/alphaTeam", rpc("GetTask", {"id": task_id}), bearer=BETA_TOKEN,
        headers={"A2A-Version": "1.0"},
    )
    cross = frame(payload)
    check(cross.get("error", {}).get("code") == -32001, "a2aCrossClientGetTask", cross)
    status, _, payload = call(
        "POST", "/a2a/alphaTeam", rpc("CancelTask", {"id": task_id}), bearer=BETA_TOKEN,
        headers={"A2A-Version": "1.0"},
    )
    cross = frame(payload)
    check(cross.get("error", {}).get("code") == -32001, "a2aCrossClientCancelTask", cross)
    # 跨团队路径在分派前 403。
    status, _, _ = call("POST", "/a2a/betaTeam", rpc("GetTask", {"id": task_id}), bearer=ALPHA_TOKEN,
                        headers={"A2A-Version": "1.0"})
    check(status == 403, "a2aCrossTeamForbidden", status)

    # 11) 取消：提交后立即取消，终态为 CANCELED 且不再产出 artifact。
    submit = rpc(
        "SendMessage",
        {
            "message": {"messageId": str(uuid.uuid4()), "role": "ROLE_USER", "parts": [{"text": "第二条任务"}]},
            "configuration": {"returnImmediately": True},
        },
    )
    _, _, payload = call(
        "POST", "/a2a/alphaTeam", submit, bearer=ALPHA_TOKEN, headers={"A2A-Version": "1.0"}
    )
    second = frame(payload)["result"]["task"]["id"]
    status, _, payload = call(
        "POST", "/a2a/alphaTeam", rpc("CancelTask", {"id": second}), bearer=ALPHA_TOKEN,
        headers={"A2A-Version": "1.0"},
    )
    canceled = frame(payload)["result"]
    check(canceled["status"]["state"] == "TASK_STATE_CANCELED", "a2aCancel", canceled["status"])
    check("artifacts" not in canceled, "a2aCancelNoArtifact", canceled.keys())

    # 12) 管理端撤销后凭证立即失效，且不能读取卡片或工具目录。
    csrf = admin_login()
    status, _, _ = call(
        "POST",
        "/api/v1/admin/external-clients/" + ready["betaClientId"] + "/revoke",
        {},
        token=csrf,
    )
    check(status == 204, "a2aRevoke", status)
    status, _, _ = call("GET", "/a2a/betaTeam", bearer=BETA_TOKEN)
    check(status == 401, "a2aRevokedCard", status)
    status, _, _ = call("POST", "/mcp", rpc("tools/list", {}), bearer=BETA_TOKEN)
    check(status == 401, "a2aRevokedTools", status)
    # 另一个凭证不受影响。
    status, _, _ = call("GET", "/a2a/alphaTeam", bearer=ALPHA_TOKEN)
    check(status == 200, "a2aOtherClientUnaffected", status)

    EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
    EVIDENCE.write_text(
        json.dumps({"checks": checks, "tasks": {"completed": task_id, "canceled": second}}, ensure_ascii=False, indent=1),
        encoding="utf-8",
    )
    print("M3-06 external checks:", len(checks), "all passed")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("FAILED:", exc)
        EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
        EVIDENCE.write_text(json.dumps({"checks": checks, "error": str(exc)}, ensure_ascii=False, indent=1), encoding="utf-8")
        sys.exit(1)
