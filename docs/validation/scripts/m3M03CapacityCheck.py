"""M3-03 名额/幂等/取消 HTTP 验收：真实 Spring 入口 + 合成 ComfyUI（m3M03ComfyMock.py）。

只使用合成账号与夹具项目；通过 /_stats 证明第 6 个请求没有触达执行器。
"""
import http.client
import json
import os
import time
import uuid
from pathlib import Path

import m2MinioDev as fixture

ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = ROOT / "docs/validation/evidence/m3-m03-capacity.json"
MOCK = os.environ.get("M303_MOCK", "http://127.0.0.1:18109")
MOCK_PORT = int(os.environ.get("M303_MOCK_PORT", "18109"))
BACKEND_PORT = int(os.environ.get("M303_PORT", "18086"))
CHECKS = []


def check(ok, name, detail=""):
    if not ok:
        raise RuntimeError("M3-03 capacity check failed: " + name + (" " + detail if detail else ""))
    CHECKS.append({"name": name, "detail": detail})


def fire(session, target, body, key):
    """只读到响应头就断开：生成流会保持到任务结束，占位验证不需要等待正文。"""
    status, _, raw = session.call("GET", "/api/v1/auth/csrf")
    token = json.loads(raw)["token"]
    conn = http.client.HTTPConnection("127.0.0.1", BACKEND_PORT, timeout=20)
    conn.request(
        "POST",
        target,
        json.dumps(body).encode(),
        {
            "Cookie": "; ".join("%s=%s" % (k, v) for k, v in session.cookies.items()),
            "Origin": session.origin,
            "X-CSRF-Token": token,
            "Idempotency-Key": key,
            "Content-Type": "application/json",
        },
    )
    response = conn.getresponse()
    code = response.status
    run = response.getheader("X-Run-Id")
    conn.close()
    return code, run, b""


def stats():
    import http.client

    conn = http.client.HTTPConnection("127.0.0.1", MOCK_PORT, timeout=15)
    conn.request("GET", "/_stats")
    result = json.loads(conn.getresponse().read())
    conn.close()
    return result


def main():
    fixture.PORT = BACKEND_PORT
    project = os.environ.get("M303_PROJECT") or json.loads(
        Path(os.environ["M303_READY"]).read_text()
    )["projectId"]
    member = fixture.Session("http://localhost:5187")
    member.login("ui-member", "M3-media-fixture-only")
    base = "/api/v1/projects/" + project
    path = base + "/creative/ai/media/generate"

    def _post(session, target, body, key):
        status, _, raw = session.call("GET", "/api/v1/auth/csrf")
        token = json.loads(raw)["token"]
        return session.call(
            "POST", target, body, False, token, {"Idempotency-Key": key}
        )

    before = stats()
    nonce = uuid.uuid4().hex[:8]
    keys = ["cap-%s-%d" % (nonce, index) for index in range(5)]
    runs = []
    for index in range(5):
        status, run, raw = fire(member, path, {
            "providerId": "qwenImage21Local",
            "modelId": "qwenImage21Text",
            "mediaType": "image",
            "prompt": "[slow] 名额占位 %d" % index,
            "size": "1K",
            "ratio": "1:1",
        }, keys[index])
        check(status == 200, "admitSlot%d" % index, "status=%d" % status)
        runs.append(run)
    check(len(set(runs)) == 5, "fiveDistinctRuns")
    after_admit = stats()
    check(after_admit["promptCount"] - before["promptCount"] == 5, "vendorCalledFiveTimes",
          str(after_admit["promptCount"] - before["promptCount"]))

    status, headers, raw = _post(member, path, {
        "providerId": "qwenImage21Local",
        "modelId": "qwenImage21Text",
        "mediaType": "image",
        "prompt": "[slow] 第六个",
        "size": "1K",
        "ratio": "1:1",
    }, "cap-%s-sixth" % nonce)
    check(status == 429, "sixthRejected429", "status=%d" % status)
    after_reject = stats()
    check(after_reject["promptCount"] == after_admit["promptCount"], "sixthNeverReachedVendor")

    status, _, raw = _post(member, path, {
        "providerId": "qwenImage21Local",
        "modelId": "qwenImage21Text",
        "mediaType": "image",
        "prompt": "[slow] 名额占位 0",
        "size": "1K",
        "ratio": "1:1",
    }, keys[0])
    check(status == 202, "idempotentReplayAccepted202", "status=%d" % status)
    check(json.loads(raw)["id"] == runs[0], "idempotentReplayReturnsSameRun")
    check(stats()["promptCount"] == after_admit["promptCount"], "replayNeverReachedVendor")

    status, _, _ = _post(member, path, {
        "providerId": "qwenImage21Local",
        "modelId": "qwenImage21Text",
        "mediaType": "image",
        "prompt": "非法比例",
        "size": "2K",
        "ratio": "5:4",
    }, "cap-%s-bad-ratio" % nonce)
    check(status == 400, "unknownRatio400", "status=%d" % status)
    status, _, _ = _post(member, path, {
        "providerId": "qwenImage21Local",
        "modelId": "qwenImage21Edit",
        "mediaType": "image",
        "prompt": "缺少参考图",
        "size": "1K",
        "ratio": "1:1",
    }, "cap-%s-edit-missing" % nonce)
    check(status == 400, "editWithoutReference400", "status=%d" % status)

    for run in runs:
        status, _, _ = member.write("POST", base + "/runs/" + run + "/cancel", None)
        check(status == 202, "cancelAccepted", "status=%d" % status)
    deadline = time.time() + 60
    released = set()
    while time.time() < deadline and len(released) < len(runs):
        for run in runs:
            if run in released:
                continue
            status, _, raw = member.call("GET", base + "/runs/" + run)
            body = json.loads(raw)
            if status == 200 and body["state"] == "CANCELLED" and body["capacityHeld"] is False:
                released.add(run)
        time.sleep(1)
    check(len(released) == 5, "allCancelledAndReleased", str(len(released)))
    after_cancel = stats()
    check(after_cancel["cancelCount"] - before["cancelCount"] == 5, "vendorCancelConfirmed")
    check(after_cancel["promptCount"] == after_admit["promptCount"], "cancelNeverResubmitted")

    status, after_run, _ = fire(member, path, {
        "providerId": "qwenImage21Local",
        "modelId": "qwenImage21Text",
        "mediaType": "image",
        "prompt": "[slow] 释放后重新占位",
        "size": "1K",
        "ratio": "1:1",
    }, "cap-%s-after-release" % nonce)
    check(status == 200, "slotReusableAfterCancel", "status=%d" % status)
    member.write("POST", base + "/runs/" + after_run + "/cancel", None)

    EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
    EVIDENCE.write_text(
        json.dumps(
            {
                "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "project": project,
                "checks": CHECKS,
                "stats": {"before": before, "afterAdmit": after_admit, "afterCancel": after_cancel},
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n"
    )
    print("M3-03 capacity checks passed:", len(CHECKS))
    for item in CHECKS:
        print(" -", item["name"], item["detail"])


if __name__ == "__main__":
    main()
