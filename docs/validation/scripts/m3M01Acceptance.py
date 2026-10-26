"""M3-01 文件验收（A04/A05/A06/A17）：真实 Spring 进程上的原格式读写、修订冲突、上传/复制/重命名/删除/大文件与路径不泄漏。

只使用夹具项目与合成账号；不连接团队数据库。结果写入证据 JSON。
用法：M301_READY=<ready.json> M301_EVIDENCE=<out.json> python m3M01Acceptance.py
"""
import hashlib
import http.client
import json
import os
import struct
import sys
import zlib
from pathlib import Path
from urllib.parse import quote

PORT = int(os.environ.get("M301_PORT", "18086"))
READY = Path(os.environ["M301_READY"])
EVIDENCE = Path(os.environ["M301_EVIDENCE"])
ready = json.loads(READY.read_text(encoding="utf-8"))
PROJECT = ready["alphaProject"]
OTHER = ready["betaProject"]
USER = ready["adminUser"]
PASSWORD = ready["adminPassword"]
ORIGIN = "http://localhost:5187"
BASE = f"/api/v1/projects/{PROJECT}/creative/workspaces/files"
checks = []
skips = []
cookies = {}


def check(name, ok, detail=""):
    checks.append({"name": name, "ok": bool(ok), "detail": str(detail)[:400]})
    print(("PASS " if ok else "FAIL ") + name + ((" :: " + str(detail)[:200]) if detail else ""))
    if not ok:
        raise SystemExit("M3-01 acceptance failed: " + name)


def call(method, path, body=None, token=None, headers=None, raw=False):
    conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=60)
    h = {"Cookie": "; ".join(f"{k}={v}" for k, v in cookies.items())}
    if method != "GET":
        h["Origin"] = ORIGIN
    if token:
        h["X-CSRF-Token"] = token
    if headers:
        h.update(headers)
    data = body
    if body is not None and not raw:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    conn.request(method, path, data, h)
    response = conn.getresponse()
    for name, value in response.getheaders():
        if name.lower() == "set-cookie":
            key, val = value.split(";", 1)[0].split("=", 1)
            cookies[key] = val
    payload = response.read()
    result = (response.status, {k.lower(): v for k, v in response.getheaders()}, payload)
    conn.close()
    return result


def q(value):
    return quote(str(value), safe="")


def json_body(payload):
    text = payload.decode("utf-8", "replace").strip()
    if text.startswith("data:"):
        text = text.split("data:", 1)[1].strip()
    return json.loads(text) if text else None


def csrf():
    status, _, payload = call("GET", "/api/v1/auth/csrf")
    check("csrf", status == 200, status)
    return json_body(payload)["token"]


def login_as(username, password):
    token = csrf()
    status, _, _ = call(
        "POST", "/api/v1/auth/login", {"username": username, "password": password}, token=token
    )
    check("login " + username, status == 200, status)
    return csrf()


def login():
    return login_as(USER, PASSWORD)


def png(width, height, rgb):
    """最小合法 PNG：用于上传/下载与摘要一致性检查。"""

    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def snapshot(token, path):
    status, _, payload = call("GET", f"{BASE}/read?path={q(path)}&snapshot=true", token=token)
    check(f"snapshot {path}", status == 200, status)
    return json_body(payload)["data"]


def write(token, path, content, revision=None, exclusive=None, encoding=None, raw=False):
    query = [f"path={q(path)}"]
    if revision is not None:
        query.append(f"expectedRevision={q(revision)}")
    if exclusive is not None:
        query.append(f"exclusive={'true' if exclusive else 'false'}")
    if encoding:
        query.append(f"encoding={encoding}")
    status, _, payload = call(
        "PUT",
        f"{BASE}/write?" + "&".join(query),
        content,
        token=token,
        headers={"Content-Type": "application/octet-stream"},
        raw=True,
    )
    return status, (json_body(payload) if payload else None)


def main():
    token = login()

    # A04 原格式：目录、画布 JSON、UTF-8 BOM 与 UTF-16LE 文本。
    # 清理上一轮残留，保证本轮是新目录（删除失败不影响后续断言）。
    call("DELETE", f"{BASE}/remove", {"path": "素材", "recursive": True}, token=token)
    for leftover in ("剧本.md", "剧本16.md", "剧本-副本.md", "剧本-终稿.md", "大文件.bin"):
        call("DELETE", f"{BASE}/remove", {"path": leftover, "recursive": False}, token=token)
    status, _, _ = call("POST", f"{BASE}/mkdir", {"path": "素材"}, token=token)
    check("A04 新建目录", status == 200, status)
    canvas = json.dumps(
        {"version": "1.0", "nodes": [{"id": "n1", "type": "text", "data": {"text": "海边分镜"}}], "edges": []},
        ensure_ascii=False,
    )
    status, result = write(token, "素材/画布1.json", canvas.encode("utf-8"), exclusive=True)
    check("A04 新建画布", status == 200, status)
    revision = result["data"]["revision"]
    check("A04 画布修订号 64 位", len(revision) == 64, revision)
    first = snapshot(token, "素材/画布1.json")
    check("A04 重开画布内容一致", first["text"] == canvas, first["text"][:60])
    check("A04 画布默认编码", first["encoding"] == "utf-8", first["encoding"])

    status, result = write(
        token, "剧本.md", "# 第一幕\n\n海边的分镜。\n".encode("utf-8"), exclusive=True, encoding="utf-8-bom"
    )
    check("A04 写入 BOM 文档", status == 200, status)
    status, _, payload = call("GET", f"{BASE}/read?path={q('剧本.md')}", token=token)
    raw = payload
    check("A04 BOM 原样保留", raw.startswith(b"\xef\xbb\xbf"), raw[:3])
    check("A04 BOM 编码回显", snapshot(token, "剧本.md")["encoding"] == "utf-8-bom")

    # 写入正文按约定是 UTF-8 文本，encoding 决定磁盘编码；回读必须得到 UTF-16LE+BOM。
    status, _ = write(token, "剧本16.md", "# 第二幕\n".encode("utf-8"), exclusive=True, encoding="utf-16le")
    check("A04 写入 UTF-16LE", status == 200, status)
    status, _, payload = call("GET", f"{BASE}/read?path={q('剧本16.md')}", token=token)
    check("A04 UTF-16LE BOM", payload.startswith(b"\xff\xfe"), payload[:2])
    check("A04 UTF-16LE 内容", payload[2:].decode("utf-16-le") == "# 第二幕\n", payload[2:][:20])
    check("A04 UTF-16LE 编码回显", snapshot(token, "剧本16.md")["encoding"] == "utf-16le")

    updated = canvas.replace("海边分镜", "海边分镜（改）")
    status, result = write(token, "素材/画布1.json", updated.encode("utf-8"), revision=revision)
    check("A04 保存画布", status == 200, status)
    next_revision = result["data"]["revision"]
    check("A04 修订号变化", next_revision != revision, next_revision[:12])
    check("A04 重开已保存内容", snapshot(token, "素材/画布1.json")["text"] == updated)

    # A05 双编辑者：旧修订号再提交必须 409，并返回当前修订号。
    stale = revision
    status, _ = write(token, "素材/画布1.json", updated.encode("utf-8"), revision=next_revision)
    check("A05 当前修订可继续保存", status == 200, status)
    status, body = write(token, "素材/画布1.json", canvas.encode("utf-8"), revision=stale)
    check("A05 旧修订冲突 409", status == 409, status)
    check("A05 冲突码", body and body.get("code") == "REVISION_CONFLICT", body)
    check(
        "A05 返回当前修订号",
        body and (body.get("details") or {}).get("currentRevision") is not None,
        body,
    )

    # A06 上传/下载/摘要、复制、重命名、删除与 8 MiB 大文件分段读取。
    # 媒体上传需要对象存储；本夹具关闭存储时记为跳过，真实 MinIO 证据见 M2-A01。
    image = png(12, 8, (32, 96, 168))
    status, _, payload = call(
        "POST",
        f"/api/v1/projects/{PROJECT}/media?filename={q('场景.png')}",
        image,
        token=token,
        headers={"Content-Type": "application/octet-stream"},
        raw=True,
    )
    if status == 503:
        skips.append(
            {
                "name": "A06 媒体上传/下载",
                "reason": "夹具未启用对象存储（huimeng.storage.enabled=false）",
                "fallback": "M2-A01 真实 MinIO 上传/预览/下载证据（M2_ACCEPTANCE.md）",
            }
        )
        print("SKIP A06 媒体上传/下载 :: 夹具未启用对象存储，沿用 M2-A01 真实 MinIO 证据")
    else:
        check("A06 上传素材", status == 201, status)
        media = json_body(payload)["data"] if payload else None
        check("A06 上传摘要一致", media and media["sha256"] == hashlib.sha256(image).hexdigest(), media)
        status, _, payload = call("GET", f"/api/v1/projects/{PROJECT}/media?limit=20", token=token)
        items = json_body(payload)["items"]
        check("A06 素材列表包含新上传", any(item["id"] == media["id"] for item in items), len(items))
        status, headers, payload = call(
            "GET", f"/api/v1/projects/{PROJECT}/media/{media['id']}/content", token=token
        )
        check("A06 下载字节一致", status == 200 and payload == image, (status, len(payload)))
        check(
            "A06 下载声明附件",
            "attachment" in headers.get("content-disposition", ""),
            headers.get("content-disposition"),
        )

    status, _, _ = call("POST", f"{BASE}/copy", {"path": "剧本.md", "target": "剧本-副本.md"}, token=token)
    check("A06 复制文件", status == 200, status)
    check("A06 副本可读", snapshot(token, "剧本-副本.md")["text"].startswith("# 第一幕"))
    status, _, _ = call(
        "POST", f"{BASE}/rename", {"path": "剧本-副本.md", "target": "剧本-终稿.md"}, token=token
    )
    check("A06 重命名", status == 200, status)
    status, _, payload = call("GET", f"{BASE}/list?path=", token=token)
    names = [item["name"] for item in json_body(payload)["data"]["entries"]]
    check("A06 列表出现重命名结果", "剧本-终稿.md" in names, names)
    status, _, _ = call(
        "DELETE", f"{BASE}/remove", {"path": "剧本-终稿.md", "recursive": False}, token=token
    )
    check("A06 删除文件", status == 200 or status == 204, status)
    status, _, payload = call("GET", f"{BASE}/list?path=", token=token)
    names = [item["name"] for item in json_body(payload)["data"]["entries"]]
    check("A06 删除后不可见", "剧本-终稿.md" not in names, names)

    big = (b"0123456789abcdef" * (8 * 1024 * 1024 // 16))
    status, result = write(token, "大文件.bin", big, exclusive=True)
    check("A06 写入 8 MiB", status == 200, status)
    status, headers, payload = call(
        "GET", f"{BASE}/read?path={q('大文件.bin')}", token=token, headers={"Range": "bytes=0-1023"}
    )
    check("A06 分段读取 206", status == 206, status)
    check("A06 分段长度", len(payload) == 1024, len(payload))
    check("A06 Content-Range", headers.get("content-range") == "bytes 0-1023/8388608", headers.get("content-range"))
    check("A06 分段摘要", hashlib.sha256(payload).hexdigest() == hashlib.sha256(big[:1024]).hexdigest())

    # A17 项目列表与文件列表不返回服务器绝对路径。
    status, _, payload = call("GET", "/api/v1/projects", token=token)
    projects = json_body(payload)["items"] if payload else []
    text = payload.decode("utf-8", "replace")
    check("A17 项目列表可读", status == 200 and len(projects) >= 2, len(projects))
    for needle in ("/data/", "/tmp/", "/repo/", "C:\\\\", "workspace-root", "absolutePath", "directory"):
        check(f"A17 不含 {needle}", needle not in text, needle)
    keys = set().union(*(set(item.keys()) for item in projects))
    check("A17 项目字段收敛", keys <= {"id", "name", "status", "role", "createdAt", "memberCount"}, sorted(keys))
    for item in projects:
        check(f"A17 项目名无路径 {item['name']}", "/" not in item["name"] and "\\" not in item["name"])

    status, _, payload = call("GET", f"{BASE}/list?path=", token=token)
    entries = json_body(payload)["data"]["entries"]
    raw = payload.decode("utf-8", "replace")
    check("A17 文件列表不含绝对路径", all(entry["path"] == entry["name"] for entry in entries), entries[:3])
    check("A17 文件列表无 absolutePath", "absolutePath" not in raw)

    # 非成员账号不能读绑定项目文件（夹具里的管理员本身是两个项目的成员，需另建非成员）。
    outsider = "m301-outsider"
    status, _, _ = call(
        "POST",
        "/api/v1/admin/users",
        {"username": outsider, "password": "M3-outsider-fixture-only", "role": "USER"},
        token=token,
    )
    check("A03 新建非成员账号", status in (200, 201, 409), status)
    admin_cookies = dict(cookies)
    cookies.clear()
    outsider_token = login_as(outsider, "M3-outsider-fixture-only")
    status, _, _ = call(
        "GET",
        f"{BASE}/read?path={q('剧本.md')}&snapshot=true",
        token=outsider_token,
    )
    check("A03 非成员读取拒绝", status in (403, 404), status)
    status, _, payload = call("GET", "/api/v1/projects", token=outsider_token)
    outsider_projects = json_body(payload).get("items", []) if status == 200 else []
    check("A03 非成员项目列表为空", status == 200 and outsider_projects == [], len(outsider_projects))
    cookies.clear()
    cookies.update(admin_cookies)

    EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
    EVIDENCE.write_text(
        json.dumps(
            {
                "project": PROJECT,
                "checks": checks,
                "skips": skips,
                "passed": len(checks),
                "createdAt": __import__("datetime").datetime.now().astimezone().isoformat(),
            },
            ensure_ascii=False,
            indent=1,
        ),
        encoding="utf-8",
    )
    print(f"M3-01 acceptance: {len(checks)} checks all passed")


if __name__ == "__main__":
    try:
        main()
    except SystemExit as ex:
        print(ex)
        sys.exit(1)
