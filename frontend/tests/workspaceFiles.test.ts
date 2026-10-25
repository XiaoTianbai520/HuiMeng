import "./browserGlobals";
import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { setFetchTransport } from "../packages/api/client";
import { setActor, setCsrf, setProject } from "../packages/api/context";
import useWorkspaceFiles, { fileErrorCode } from "../src/lib/workspaceFiles";

const user = { id: "u1", username: "fixture", role: "USER", status: "ACTIVE" } as const;
const originalFetch = globalThis.fetch.bind(globalThis);
const revision = "a".repeat(64);
const nextRevision = "b".repeat(64);

beforeEach(() => { setActor(user); setProject("p1"); setCsrf("csrf-fixture"); });
afterEach(() => { setActor(null); setFetchTransport(originalFetch); globalThis.fetch = originalFetch; });

function transport(handler: (url: string, init?: RequestInit) => Response) {
  const calls: { url: string; init?: RequestInit }[] = [];
  const mock = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = input instanceof Request ? input.url : String(input);
    const method = init?.method ?? (input instanceof Request ? input.method : "GET");
    const body = init?.body ?? (input instanceof Request ? await input.clone().text() : undefined);
    calls.push({ url, init: { ...init, method, body } });
    return handler(url, { ...init, method, body });
  }) as typeof fetch;
  // axios 的 fetch 适配器使用全局 fetch；客户端的 CSRF/流式路径使用可注入 transport。
  globalThis.fetch = mock;
  setFetchTransport(mock);
  return calls;
}

describe("M3-W03 workspace revision wiring", () => {
  test("snapshot write carries expectedRevision and encoding, and returns the new revision", async () => {
    const calls = transport(() => Response.json({ code: 200, data: { revision: nextRevision } }));
    const files = useWorkspaceFiles("p1");
    const result = await files.writeTextSnapshot("剧本/第一集.md", "正文", { revision, encoding: "utf-8" });
    expect(result).toBe(nextRevision);
    expect(calls).toHaveLength(1);
    const url = new URL(calls[0].url, "https://huimeng.invalid");
    expect(url.pathname).toBe("/api/v1/projects/p1/creative/workspaces/files/write");
    expect(url.searchParams.get("expectedRevision")).toBe(revision);
    expect(url.searchParams.get("encoding")).toBe("utf-8");
    expect(url.searchParams.has("exclusive")).toBeFalse();
    expect(url.searchParams.has("directory")).toBeFalse();
    expect(calls[0].init?.method).toBe("PUT");
    expect(calls[0].init?.body).toBe("正文");
  });

  test("exclusive creation never sends expectedRevision and returns the first revision", async () => {
    const calls = transport(() => Response.json({ code: 200, data: { revision } }));
    const files = useWorkspaceFiles("p1");
    expect(await files.writeExclusiveText("画布1.json", "{}\n")).toBe(revision);
    const url = new URL(calls[0].url, "https://huimeng.invalid");
    expect(url.searchParams.get("exclusive")).toBe("true");
    expect(url.searchParams.get("encoding")).toBe("utf-8");
    expect(url.searchParams.has("expectedRevision")).toBeFalse();
    expect(url.searchParams.has("directory")).toBeFalse();
  });

  test("readJsonSnapshot parses the canvas text and preserves revision and encoding", async () => {
    const calls = transport(() => Response.json({ code: 200, data: { text: '{"toonflowCanvas":true,"nodes":[]}', encoding: "utf-8", revision } }));
    const files = useWorkspaceFiles("p1");
    const { data, snapshot } = await files.readJsonSnapshot<{ toonflowCanvas: boolean }>("画布1.json");
    expect(data.toonflowCanvas).toBeTrue();
    expect(snapshot.revision).toBe(revision);
    expect(snapshot.encoding).toBe("utf-8");
    const url = new URL(calls[0].url, "https://huimeng.invalid");
    expect(url.pathname).toBe("/api/v1/projects/p1/creative/workspaces/files/read");
    expect(url.searchParams.get("snapshot")).toBe("true");
    expect(url.searchParams.has("directory")).toBeFalse();
  });

  test("fileErrorCode recognizes the new top-level conflict code", async () => {
    transport(() => Response.json({ code: "REVISION_CONFLICT", message: "文件已变化", requestId: "r1", details: { currentRevision: nextRevision } }, { status: 409 }));
    const files = useWorkspaceFiles("p1");
    let conflict: unknown;
    try {
      await files.writeTextSnapshot("doc.md", "x", { revision, encoding: "utf-8" });
    } catch (error) { conflict = error; }
    expect(fileErrorCode(conflict)).toBe("REVISION_CONFLICT");
  });

  test("fileErrorCode recognizes legacy nested codes and rejects non-API errors", async () => {
    transport(() => Response.json({ code: 409, data: { code: "ESTALE" } }, { status: 409 }));
    const files = useWorkspaceFiles("p1");
    let conflict: unknown;
    try {
      await files.writeTextSnapshot("doc.md", "x", { revision, encoding: "utf-8" });
    } catch (error) { conflict = error; }
    // 旧包装顶层 code 是 HTTP 数字，不能被误当成业务码返回。
    expect(fileErrorCode(conflict)).toBe("ESTALE");
    expect(fileErrorCode(new Error("local"))).toBeUndefined();
    expect(fileErrorCode(undefined)).toBeUndefined();
  });
});
