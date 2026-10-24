import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { a2aState, checkedEvents, mediaJson, parseSse, rpcResult } from "../packages/api/streams";

const evidence = (name: string) => JSON.parse(readFileSync(new URL(`../../docs/validation/evidence/${name}`, import.meta.url), "utf8"));

function chunks(text: string, step = 1) {
  const bytes = new TextEncoder().encode(text);
  let offset = 0;
  return new ReadableStream<Uint8Array>({ pull(controller) {
    if (offset >= bytes.length) return controller.close();
    controller.enqueue(bytes.slice(offset, offset += step));
  } });
}

async function events(text: string, protocol: "text" | "agent" | "singleAgent" | "ffmpeg", step = 1) {
  return Array.fromAsync(checkedEvents(chunks(text, step), protocol));
}

describe("M1 transport compatibility", () => {
  test("SSE preserves Chinese UTF-8 split at every byte, CRLF and heartbeats", async () => {
    const text = ': keepalive\r\n\r\ndata: {"type":"text",\r\ndata: "delta":"绘梦"}\r\n\r\ndata: {"type":"done","message":{}}\r\n\r\n';
    expect(await events(text, "text")).toEqual([{ type: "text", delta: "绘梦" }, { type: "done", message: {} }]);
    expect(await Array.fromAsync(parseSse(chunks(text.replaceAll("\r\n", "\r"))))).toHaveLength(2);
  });
  test("HTTP 200 Agent error remains a failure using the recorded M0 sample", async () => {
    const sample = evidence("upstream-protocol-limits.json").find((item: { case: string }) => item.case === "agent-no-configured-model");
    expect(sample.status).toBe(200);
    await expect(events(sample.raw, "agent")).rejects.toMatchObject({ code: "EXECUTION_FAILED" });
  });
  test("Agent blank lines and nested done do not terminate the parent", async () => {
    const wire = '\n{"type":"subAgentEvent","file":"child","event":{"type":"done"}}\n\n{"type":"text","delta":"完成"}\n{"type":"done"}\n';
    expect(await events(wire, "agent")).toHaveLength(3);
    await expect(events(wire.replace('{"type":"done"}\n', ""), "agent")).rejects.toMatchObject({ code: "STREAM_INTERRUPTED" });
  });
  test("single Agent requires complete, and FFmpeg requires end", async () => {
    expect(await events('{"type":"complete","text":"ok","toolResults":[]}\n', "singleAgent")).toHaveLength(1);
    const recorded = evidence("upstream-ffmpeg-closeout.json").transcode.events;
    const wire = recorded.map((event: unknown) => `data: ${JSON.stringify(event)}\n\n`).join("");
    expect((await events(wire, "ffmpeg", 37)).at(-1)?.event).toBe("end");
  });
  test("truncation, stream error and extra events cannot become success", async () => {
    for (const text of ['data: {"type":"text","delta":"x"}\n\n', 'data: {"type":"done"}',
      'data: {"type":"error","message":"失败"}\n\n', 'data: {"type":"done"}\n\ndata: {"type":"text","delta":"late"}\n\n']) {
      await expect(events(text, "text")).rejects.toBeInstanceOf(Error);
    }
    await expect(events('{"type":"text"', "agent")).rejects.toMatchObject({ code: "INVALID_STREAM" });
  });
  test("current Qwen JSON accepts whitespace and preserves result paths", async () => {
    for (const sample of evidence("qwen-final-closeout.json").tests) {
      const response = new Response("\n".repeat(sample.leadingWhitespaceBytes) + JSON.stringify(sample.response));
      expect(await mediaJson(response)).toEqual(sample.response);
    }
  });
  test("HTTP 200 media error and truncated JSON are failures", async () => {
    for (const sample of evidence("upstream-protocol-limits.json").filter((item: { case: string }) => item.case.startsWith("media-"))) {
      await expect(mediaJson(new Response(sample.raw))).rejects.toMatchObject({ code: "EXECUTION_FAILED" });
    }
    await expect(mediaJson(new Response("\n\n"))).rejects.toMatchObject({ code: "INVALID_STREAM" });
  });
  test("recorded MCP initialize/list/call keep JSON-RPC semantics", () => {
    const samples = evidence("upstream-mcp-closeout.json").records;
    for (const sample of samples) for (const event of sample.events ?? []) {
      if (event.error || event.result?.isError === true) expect(() => rpcResult(event)).toThrow();
      else if (event.result) expect(rpcResult(event)).toEqual(event.result);
    }
    expect(() => rpcResult({ result: { isError: true } })).toThrow();
  });
  test("recorded A2A query/cancel states remain distinct", () => {
    const sample = evidence("upstream-a2a-closeout.json");
    const tasks: Record<string, unknown>[] = [];
    const visit = (value: unknown) => {
      if (!value || typeof value !== "object") return;
      const item = value as Record<string, unknown>;
      if (item.status && typeof item.status === "object" && "state" in item.status) tasks.push(item);
      for (const child of Object.values(item)) visit(child);
    };
    visit(sample);
    expect(tasks.length).toBeGreaterThan(0);
    const states = tasks.map(a2aState);
    expect(states).toContain("succeeded");
    expect(states).toContain("cancelled");
    expect(states).toContain("active");
    expect(() => a2aState({ status: { state: "unknown" } })).toThrow();
  });
});
