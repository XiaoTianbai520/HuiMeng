import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import client, { apiFetch, cancelRun, prepareRequest, setFetchTransport } from "../packages/api/client";
import { context, setActor, setCsrf, setProject } from "../packages/api/context";
import { checkedEvents } from "../packages/api/streams";

const user = { id: "u1", username: "fixture", role: "USER", status: "ACTIVE" } as const;
const calls: { url: string; init?: RequestInit }[] = [];
const originalAdapter = client.defaults.adapter;
const originalFetch = globalThis.fetch.bind(globalThis);

beforeEach(() => { setActor(user); setProject("p1"); setCsrf("csrf-fixture"); calls.length = 0; });
afterEach(() => { setActor(null); client.defaults.adapter = originalAdapter; setFetchTransport(originalFetch); });

describe("M1 single API boundary", () => {
  test("maps opaque project context and removes legacy headers and directory", () => {
    const request = prepareRequest("/api/agent", "POST", { directory: "p1", prompt: "test" },
      new Headers({ Authorization: "Bearer never-forward", "x-toonflow-workspace": "1" }));
    expect(request.url).toBe("/api/v1/projects/p1/creative/agent");
    expect(request.body).toEqual({ prompt: "test" });
    expect(request.headers.has("Authorization")).toBeFalse();
    expect(request.headers.has("x-toonflow-workspace")).toBeFalse();
    expect(request.headers.get("Idempotency-Key")).toBeTruthy();
    expect(prepareRequest("/api/agent", "POST", {}, new Headers({ "Idempotency-Key": "same-invocation" })).headers.get("Idempotency-Key")).toBe("same-invocation");
  });
  test("rejects excluded, unknown, cross-project and absolute paths before transport", () => {
    for (const path of ["/api/desktop/ready", "/api/mobileLink/status", "/api/workspaces/selectDirectory",
      "/api/browser/stream", "/api/v1/desktop/ready", "/api/v1/projects/p2/creative/agent", "https://external.invalid/api/agent"]) {
      expect(() => prepareRequest(path, "POST")).toThrow();
    }
    for (const path of ["../secret", "C:/data", "/etc/passwd", "%252e%252e/secret", "%252525252e%252525252e/secret", "..\\private"]) {
      expect(() => prepareRequest(`/api/workspaces/files/read?path=${encodeURIComponent(path)}`, "GET")).toThrow();
    }
    expect(() => prepareRequest("/api/agent", "POST", { directory: "p2" })).toThrow();
    expect(() => prepareRequest("/api/agent", "POST", { absolutePath: "private" })).toThrow();
    expect(() => prepareRequest("/api/v1/admin/users", "GET")).toThrow();
    expect(prepareRequest("/api/workspaces/files/read?path=" + encodeURIComponent("素材/50%.png"), "GET").url).toContain("/creative/workspaces/files/read");
  });
  test("Axios instances share project mapping, CSRF and caller idempotency", async () => {
    let captured: unknown;
    const http = client.create({ baseURL: "/api/workspaces/files", adapter: async config => {
      captured = config;
      return { status: 200, statusText: "OK", config, headers: {}, data: { code: 200, data: { revision: "new" } } };
    } });
    await http.put("/write", "bytes", { params: { directory: "p1", path: "画布.json", expectedRevision: "a".repeat(64) }, headers: { "Content-Type": "application/octet-stream" } });
    const request = captured as { url: string; headers: Record<string, string>; fetchOptions: RequestInit };
    expect(request.url).toContain("/api/v1/projects/p1/creative/workspaces/files/write?");
    expect(request.url).not.toContain("directory");
    expect(request.headers["x-csrf-token"] ?? request.headers["X-CSRF-Token"]).toBe("csrf-fixture");
    expect(request.fetchOptions.redirect).toBe("error");
  });
  test("SSE through Fetch stays streaming and is parsed by the shared parser", async () => {
    setFetchTransport((async (url, init) => {
      calls.push({ url: String(url), init });
      return new Response('data: {"type":"text","delta":"绘梦"}\n\ndata: {"type":"done","message":{}}\n\n', { headers: { "Content-Type": "text/event-stream" } });
    }));
    const response = await apiFetch("/api/ai/generate", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ directory: "p1" }) });
    expect(await Array.fromAsync(checkedEvents(response.body!, "text"))).toHaveLength(2);
    expect(calls[0].url).toBe("/api/v1/projects/p1/creative/ai/generate");
    expect(calls[0].init?.body).toBe("{}");
    expect(calls[0].init?.redirect).toBe("error");
  });
  test("explicit cancellation sends one request, and does not infer released capacity", async () => {
    const controller = new AbortController();
    setFetchTransport((async (url, init) => {
      calls.push({ url: String(url), init });
      if (String(url).endsWith("/cancel")) return Response.json({ state: "CANCELLING", capacityHeld: true }, { status: 202 });
      return new Response(new ReadableStream<Uint8Array>({ start(stream) { stream.enqueue(new TextEncoder().encode("\n")); } }),
        { headers: { "X-Run-Id": "r1" } });
    }));
    const response = await apiFetch("/api/agent", { method: "POST", signal: controller.signal });
    controller.abort();
    await Promise.resolve();
    expect(calls.filter(call => call.url.endsWith("/cancel"))).toHaveLength(1);
    expect(calls.at(-1)?.url).toBe("/api/v1/projects/p1/runs/r1/cancel");
    await response.body?.cancel();
  });
  test("project changes abort stale transport without sending a cancellation request", async () => {
    let signal: AbortSignal | undefined;
    setFetchTransport((async (url, init) => {
      calls.push({ url: String(url), init });
      signal = init?.signal as AbortSignal;
      return new Response(new ReadableStream<Uint8Array>(), { headers: { "X-Run-Id": "r1" } });
    }));
    const response = await apiFetch("/api/agent", { method: "POST", signal: new AbortController().signal });
    setProject("p2");
    expect(signal?.aborted).toBeTrue();
    expect(calls).toHaveLength(1);
    await response.body?.cancel();
  });
  test("platform cancel returns CANCELLING unchanged and business-200 errors reject", async () => {
    client.defaults.adapter = async config => ({ status: 202, statusText: "Accepted", config, headers: {},
      data: { state: "CANCELLING", capacityHeld: true } });
    expect((await cancelRun("r1")).data).toEqual({ state: "CANCELLING", capacityHeld: true });
    const http = client.create({ adapter: async config => ({ status: 200, statusText: "OK", config, headers: {}, data: { code: 404, message: "no provider" } }) });
    await expect(http.get("/api/v1/models")).rejects.toMatchObject({ code: "EXECUTION_FAILED" });
    setActor(null);
    expect(context().projectId).toBeNull();
    expect(context().csrfToken).toBe("");
  });
  test("a late 401 from an earlier session cannot clear a newly logged-in actor", async () => {
    let finish!: (response: Response) => void;
    setFetchTransport((() => new Promise<Response>(resolve => { finish = resolve; })));
    const pending = apiFetch("/api/v1/models");
    setActor({ ...user, id: "u2" });
    finish(new Response("{}", { status: 401 }));
    await pending;
    expect(context().actor?.id).toBe("u2");
  });
  test("a session change while obtaining CSRF cannot dispatch an old write", async () => {
    setCsrf("");
    let finish!: (response: Response) => void;
    setFetchTransport(((url, init) => {
      calls.push({ url: String(url), init });
      return new Promise<Response>(resolve => { finish = resolve; });
    }));
    const pending = apiFetch("/api/agent", { method: "POST" });
    setActor({ ...user, id: "u2" });
    finish(Response.json({ token: "earlier-session-token" }));
    await expect(pending).rejects.toMatchObject({ code: "SESSION_CHANGED" });
    expect(calls.map(call => call.url)).toEqual(["/api/v1/auth/csrf"]);
    expect(context().csrfToken).toBe("");
  });
});
