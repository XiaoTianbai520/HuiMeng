"""Record real upstream FFmpeg and A2A behavior in the disposable M0 runtime.

Run from the repository root with --runtime pointing to a freshly prepared
disposable instance manifest. Historical temporary manifests are not reused.
No provider keys or A2A bearer tokens are written into evidence.
"""
import argparse
import concurrent.futures
import hashlib
import json
import sys
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

workspace = Path(__file__).resolve().parents[3]
evidence = workspace / "docs/validation/evidence"


def request(route, payload=None, method=None, extra=None, timeout=180):
    req = Request(base + route, data=None if payload is None else json.dumps(payload).encode(),
                  method=method or ("POST" if payload is not None else "GET"), headers={**headers, **(extra or {})})
    try:
        return urlopen(req, timeout=timeout)
    except HTTPError as error:
        return error


def call(route, payload=None, method=None, extra=None, timeout=180):
    with request(route, payload, method, extra, timeout) as response:
        raw = response.read().decode()
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            body = raw
        return {"httpStatus": response.status, "contentType": response.headers.get("Content-Type"), "body": body}


def save(name, data):
    (evidence / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sse(route, payload, on_event=None):
    events = []
    with request(route, payload, timeout=180) as response:
        for line in response:
            line = line.decode().strip()
            if line.startswith("data:"):
                event = json.loads(line[5:].strip())
                events.append(event)
                if on_event:
                    on_event(event)
        return {"httpStatus": response.status, "contentType": response.headers.get("Content-Type"), "events": events}


def ffmpeg():
    result = {"date": "2026-10-22", "fixedCommit": "d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d", "baseUrl": base}
    result["statusBefore"] = call("/api/ffmpeg/status")
    result["downloads"] = []
    for source in ("npmmirror", "github"):
        current = call("/api/ffmpeg/status")["body"]["data"]
        if all(current["tools"][name].get("path") and not current["tools"][name].get("error") for name in ("ffmpeg", "ffprobe")):
            break
        started = call("/api/ffmpeg/download", {"source": source})
        print("ffmpeg download", source, started["httpStatus"], flush=True)
        phases = []
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            time.sleep(2)
            status = call("/api/ffmpeg/status")["body"]["data"]
            progress = status["download"]
            if not phases or progress["phase"] != phases[-1]["phase"]:
                phases.append(progress)
                print("ffmpeg phase", progress["phase"], flush=True)
            if progress["phase"] in ("completed", "error", "cancelled"):
                break
        else:
            call("/api/ffmpeg/cancel", {})
        result["downloads"].append({"source": source, "start": started, "phases": phases})
        save("upstream-ffmpeg-closeout.json", result)
    result["statusAfter"] = call("/api/ffmpeg/status")
    tools = result["statusAfter"]["body"]["data"]["tools"]
    if not all(tools[name].get("path") and not tools[name].get("error") for name in ("ffmpeg", "ffprobe")):
        save("upstream-ffmpeg-closeout.json", result)
        raise SystemExit("FFmpeg binary installation did not complete")
    import shutil
    shutil.copyfile(evidence / "upstream-h3-toonflow.mp4", project / "assets/input.mp4")
    prep = {"requestId": str(uuid.uuid4()), "directory": str(project), "options": {}, "calls": [], "operation": {"method": "prepare", "args": []}}
    result["prepare"] = call("/api/ffmpeg/execute", prep)
    payload = {**prep, "requestId": str(uuid.uuid4()), "options": {"source": "assets/input.mp4"},
               "calls": [{"method": "size", "args": ["320x?"]}, {"method": "videoCodec", "args": ["libx264"]},
                         {"method": "audioCodec", "args": ["aac"]}],
               "operation": {"method": "save", "args": ["assets/transcoded.mp4"]}}
    result["transcode"] = {"request": payload, **sse("/api/ffmpeg/execute", payload)}
    result["probe"] = sse("/api/ffmpeg/execute", {**prep, "requestId": str(uuid.uuid4()), "options": {"source": "assets/transcoded.mp4"},
                                                 "operation": {"method": "ffprobe", "args": []}})
    route = "/api/workspaces/files/read?" + urlencode({"directory": str(project), "path": "assets/transcoded.mp4"})
    with request(route) as response:
        blob = response.read()
        result["downloadOutput"] = {"httpStatus": response.status, "bytes": len(blob), "sha256": hashlib.sha256(blob).hexdigest(),
                                    "matchesDisk": blob == (project / "assets/transcoded.mp4").read_bytes()}
        (evidence / "upstream-ffmpeg-transcoded.mp4").write_bytes(blob)
    cancel_payload = {**prep, "requestId": str(uuid.uuid4()), "options": {"source": "assets/input.mp4"},
                      "calls": [{"method": "inputOptions", "args": [["-re", "-stream_loop", "-1"]]},
                                {"method": "duration", "args": [120]}, {"method": "videoCodec", "args": ["libx264"]}],
                      "operation": {"method": "save", "args": ["assets/cancelled.mp4"]}}
    import threading
    started = threading.Event()
    def on_event(event):
        if event.get("event") == "progress":
            started.set()
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(sse, "/api/ffmpeg/execute", cancel_payload, on_event)
        if not started.wait(30):
            raise RuntimeError("No progress before cancellation")
        result["cancelReply"] = call("/api/ffmpeg/execute", {**prep, "requestId": cancel_payload["requestId"], "operation": {"method": "cancel", "args": []}})
        result["cancelStream"] = future.result(timeout=30)
    event_names = [event["event"] for event in result["transcode"]["events"]]
    cancel_names = [event["event"] for event in result["cancelStream"]["events"]]
    result["checks"] = {"prepare": result["prepare"]["httpStatus"] == 200,
                        "transcode": "end" in event_names and "error" not in event_names,
                        "progress": "progress" in event_names, "probe": any(event["event"] == "result" for event in result["probe"]["events"]),
                        "download": result["downloadOutput"]["matchesDisk"],
                        "cancel": result["cancelReply"]["httpStatus"] == 200 and "error" in cancel_names and "end" not in cancel_names}
    save("upstream-ffmpeg-closeout.json", result)
    print(json.dumps(result["checks"], ensure_ascii=False), flush=True)
    if not all(result["checks"].values()):
        raise SystemExit(1)


def a2a():
    settings = json.loads((Path(state["data"]) / "settings.json").read_text(encoding="utf-8"))
    authorization = {"Authorization": "Bearer " + settings["a2a"]["token"], "A2A-Version": "1.0"}
    endpoint = "/a2a/m0Verifier"
    result = {"date": "2026-10-22", "fixedCommit": "d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d", "team": "m0Verifier", "modelId": state["modelId"]}
    def rpc(method, params, authorized=True):
        payload = {"jsonrpc": "2.0", "id": str(uuid.uuid4()), "method": method, "params": params}
        reply = call(endpoint, payload, extra=authorization if authorized else {})
        return {"request": payload, **reply}
    def send(text):
        return rpc("SendMessage", {"message": {"messageId": str(uuid.uuid4()), "role": "ROLE_USER", "parts": [{"text": text}]},
                                   "configuration": {"returnImmediately": True}})
    result["card"] = call(endpoint + "/.well-known/agent-card.json")
    result["unauthorized"] = rpc("ListTasks", {}, False)
    result["submit"] = send("Reply exactly M0_A2A_OK. This is a synthetic protocol test. Do not use tools.")
    save("upstream-a2a-closeout.json", result)
    print("a2a submit", json.dumps(result["submit"]["body"], ensure_ascii=False)[:600], flush=True)
    task = result["submit"]["body"]["result"]["task"]
    result["queries"] = []
    for _ in range(90):
        query = rpc("GetTask", {"id": task["id"]})
        result["queries"].append(query)
        status = query["body"].get("result", {}).get("status", {}).get("state")
        if status in ("TASK_STATE_COMPLETED", "TASK_STATE_FAILED", "TASK_STATE_CANCELED", "TASK_STATE_INPUT_REQUIRED"):
            break
        time.sleep(1)
    print("a2a completion", status, flush=True)
    result["cancelSubmit"] = send("Synthetic cancellation test: write 500 numbered sentences about a paper boat, one sentence per line. Do not summarize or use tools.")
    cancel_task = result["cancelSubmit"]["body"]["result"]["task"]
    time.sleep(1)
    result["beforeCancel"] = rpc("GetTask", {"id": cancel_task["id"]})
    result["cancel"] = rpc("CancelTask", {"id": cancel_task["id"]})
    result["afterCancel"] = rpc("GetTask", {"id": cancel_task["id"]})
    result["missingTask"] = rpc("GetTask", {"id": str(uuid.uuid4())})
    result["checks"] = {"card": result["card"]["httpStatus"] == 200,
                        "unauthorized": result["unauthorized"]["httpStatus"] == 401,
                        "completed": status == "TASK_STATE_COMPLETED",
                        "artifact": bool(result["queries"][-1]["body"].get("result", {}).get("artifacts")),
                        "cancel": result["afterCancel"]["body"].get("result", {}).get("status", {}).get("state") == "TASK_STATE_CANCELED",
                        "missingTask": "error" in result["missingTask"]["body"]}
    save("upstream-a2a-closeout.json", result)
    print(json.dumps(result["checks"], ensure_ascii=False), flush=True)
    if not all(result["checks"].values()):
        raise SystemExit(1)


def mcp():
    import secrets
    settings = call("/api/settings/get")["body"]["data"]
    previous = settings.get("mcp")
    token = secrets.token_hex(32)
    settings["mcp"] = {"enabled": True, "token": token}
    assert call("/api/settings/save", {"settings": settings}, "PUT")["httpStatus"] == 200
    result = {"date": "2026-10-22", "records": []}
    auth = {"Authorization": "Bearer " + token, "Accept": "application/json, text/event-stream"}
    counter = 0
    def rpc(method, params, authenticated=True):
        nonlocal counter
        counter += 1
        payload = {"jsonrpc": "2.0", "id": counter, "method": method, "params": params}
        with request("/mcp", payload, extra=auth if authenticated else {"Accept": auth["Accept"]}) as response:
            raw = response.read().decode()
            session = response.headers.get("mcp-session-id")
            if session:
                auth["Mcp-Session-Id"] = session
            events = [json.loads(line[5:].strip()) for line in raw.splitlines() if line.startswith("data:")]
            if not events and raw.strip():
                events = [json.loads(raw)]
            record = {"method": method, "params": params, "httpStatus": response.status,
                      "contentType": response.headers.get("Content-Type"), "hasSessionId": bool(auth.get("Mcp-Session-Id")), "events": events}
            result["records"].append(record)
            return record
    try:
        init = {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "huimeng-m0-closeout", "version": "1.0"}}
        denied = rpc("initialize", init, False)
        rpc("initialize", init)
        listing = rpc("tools/list", {})
        tool_names = [tool["name"] for event in listing["events"] for tool in event.get("result", {}).get("tools", [])]
        files = rpc("tools/call", {"name": "workspaceFiles", "arguments": {"target": {"directory": str(project)}, "args": {"action": "list", "path": ""}}})
        read = rpc("tools/call", {"name": "read", "arguments": {"target": {"directory": str(project)}, "args": {"path": "m0Synthetic.txt"}}})
        traversal = rpc("tools/call", {"name": "workspaceFiles", "arguments": {"target": {"directory": str(project)}, "args": {"action": "readBinary", "path": "../outside.json"}}})
        def is_error(record):
            return any(event.get("error") or event.get("result", {}).get("isError") for event in record["events"])
        result["checks"] = {"unauthorized": denied["httpStatus"] == 401, "discovery": bool(tool_names),
                            "list": not is_error(files), "read": not is_error(read), "traversalDenied": is_error(traversal)}
        with request("/mcp", method="DELETE", extra=auth) as response:
            result["sessionDeleteStatus"] = response.status
    finally:
        current = call("/api/settings/get")["body"]["data"]
        if previous is None:
            current.pop("mcp", None)
        else:
            current["mcp"] = previous
        result["restoreStatus"] = call("/api/settings/save", {"settings": current}, "PUT")["httpStatus"]
        save("upstream-mcp-closeout.json", result)
    print(json.dumps(result.get("checks"), ensure_ascii=False), flush=True)
    if not all(result["checks"].values()):
        raise SystemExit(1)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("part", choices=["ffmpeg", "a2a", "mcp"])
    parser.add_argument("--runtime", type=Path, required=True, help="Fresh disposable runtime manifest, without credentials")
    args = parser.parse_args()
    state = json.loads(args.runtime.read_text(encoding="utf-8"))
    runtime = Path(state["runtime"]).resolve()
    assert runtime.name.startswith("huimeng-m0-"), "Only disposable M0 runtime directories are supported"
    assert Path(state["data"]).resolve().is_relative_to(runtime), "Data must stay within the disposable runtime"
    base = f"http://127.0.0.1:{state['port']}"
    project = Path(state["project"])
    assert project.resolve().is_relative_to(Path(state["data"]).resolve())
    headers = {"Content-Type": "application/json", "x-toonflow-workspace": "1", "Origin": base, "Referer": base + "/#/workspace"}
    globals()[args.part]()
