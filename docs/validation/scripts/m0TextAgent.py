"""Record Toonflow M0 text SSE and Agent NDJSON behavior with a real model.

Run against the disposable fixed-commit upstream server, never the planned 1.0 API.
"""

import argparse
import json
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def stream_request(base_url, route, payload, timeout):
    request = Request(
        base_url.rstrip("/") + route,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Accept": "text/event-stream" if route == "/api/ai/generate" else "application/x-ndjson",
            "Origin": base_url.rstrip("/"),
            "Referer": base_url.rstrip("/") + "/#/workspace",
            "x-toonflow-workspace": "1",
        },
        method="POST",
    )
    try:
        response = urlopen(request, timeout=timeout)
    except HTTPError as error:
        response = error
    except URLError as error:
        return {"transportError": str(error.reason), "events": []}

    with response:
        result = {
            "httpStatus": response.status,
            "contentType": response.headers.get("Content-Type", ""),
            "events": [],
        }
        for raw_line in response:
            line = raw_line.decode("utf-8", errors="replace").strip()
            if not line or line.startswith(":"):
                continue
            if line.startswith("data:"):
                line = line[5:].strip()
            try:
                result["events"].append(json.loads(line))
            except json.JSONDecodeError:
                result["events"].append({"raw": line})
        result["eventTypes"] = [event.get("type") for event in result["events"]]
        result["succeeded"] = result["httpStatus"] == 200 and "done" in result["eventTypes"] and "error" not in result["eventTypes"]
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:3000")
    parser.add_argument("--provider-id", required=True)
    parser.add_argument("--model-id", required=True)
    parser.add_argument("--project-directory", required=True, help="Upstream test project's absolute directory")
    parser.add_argument("--output", type=Path, required=True, help="Private raw evidence JSON path")
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()

    text = stream_request(args.base_url, "/api/ai/generate", {
        "providerId": args.provider_id,
        "modelId": args.model_id,
        "context": {"messages": [{"role": "user", "content": "请用一句话描述画布上的一个角色。", "timestamp": 0}]},
    }, args.timeout)
    agent = stream_request(args.base_url, "/api/agent", {
        "providerId": args.provider_id,
        "modelId": args.model_id,
        "directory": args.project_directory,
        "prompt": "请简短回复：M0 Agent 连通成功。不要修改项目文件。",
        "thinkingLevel": "off",
    }, args.timeout)
    evidence = {"upstreamBaseUrl": args.base_url, "providerId": args.provider_id, "modelId": args.model_id,
                "textSse": text, "agentNdjson": agent}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({name: {"httpStatus": result.get("httpStatus"), "eventTypes": result.get("eventTypes"),
                             "succeeded": result.get("succeeded", False), "transportError": result.get("transportError")}
                      for name, result in (("textSse", text), ("agentNdjson", agent))}, ensure_ascii=False, indent=2))
    raise SystemExit(0 if text.get("succeeded") and agent.get("succeeded") else 1)


if __name__ == "__main__":
    main()
