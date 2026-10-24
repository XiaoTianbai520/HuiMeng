/** Transport parsers shared by the Web client and offline M1 fixtures. */
export class ProtocolError extends Error {
  constructor(public readonly code: string, message: string) {
    super(message);
    this.name = "ProtocolError";
  }
}

export type JsonEvent = Record<string, unknown>;

function eventJson(text: string): JsonEvent {
  let value: unknown;
  try { value = JSON.parse(text); }
  catch { throw new ProtocolError("INVALID_STREAM", "响应包含不完整或无效的 JSON"); }
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new ProtocolError("INVALID_STREAM", "响应事件必须是对象");
  }
  return value as JsonEvent;
}

async function* lines(body: ReadableStream<Uint8Array>, maxBytes = 8 * 1024 * 1024) {
  const reader = body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let pending = "";
  try {
    while (true) {
      const { value, done } = await reader.read();
      pending += done ? decoder.decode() : decoder.decode(value, { stream: true });
      let start = 0;
      for (let i = 0; i < pending.length; i++) {
        if (pending[i] !== "\n" && pending[i] !== "\r") continue;
        if (!done && pending[i] === "\r" && i === pending.length - 1) break;
        if (i - start > maxBytes || new TextEncoder().encode(pending.slice(start, i)).byteLength > maxBytes) {
          throw new ProtocolError("RESPONSE_TOO_LARGE", "流式记录过大");
        }
        yield pending.slice(start, i);
        if (pending[i] === "\r" && pending[i + 1] === "\n") i++;
        start = i + 1;
      }
      pending = pending.slice(start);
      if (pending.length > maxBytes || new TextEncoder().encode(pending).byteLength > maxBytes) {
        throw new ProtocolError("RESPONSE_TOO_LARGE", "流式记录过大");
      }
      if (done) break;
    }
    if (pending) yield pending;
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

export async function* parseNdjson(body: ReadableStream<Uint8Array>): AsyncGenerator<JsonEvent> {
  for await (const line of lines(body)) if (line.trim()) yield eventJson(line);
}

export async function* parseSse(body: ReadableStream<Uint8Array>): AsyncGenerator<JsonEvent> {
  let data: string[] = [];
  let size = 0;
  for await (const line of lines(body)) {
    if (!line) {
      if (data.length) yield eventJson(data.join("\n"));
      data = [];
      size = 0;
    } else if (line === "data" || line.startsWith("data:")) {
      const part = line === "data" ? "" : line.slice(5).replace(/^ /, "");
      size += new TextEncoder().encode(part).byteLength;
      if (size > 8 * 1024 * 1024) throw new ProtocolError("RESPONSE_TOO_LARGE", "SSE 事件过大");
      data.push(part);
    }
  }
  if (data.length) throw new ProtocolError("STREAM_INTERRUPTED", "SSE 帧尚未结束，连接已断开");
}

export async function* checkedEvents(body: ReadableStream<Uint8Array>, protocol: "text" | "agent" | "singleAgent" | "ffmpeg") {
  let terminal = false;
  const parser = protocol === "agent" || protocol === "singleAgent" ? parseNdjson : parseSse;
  for await (const event of parser(body)) {
    if (terminal) throw new ProtocolError("INVALID_STREAM", "终态后收到多余事件");
    const tag = protocol === "ffmpeg" ? event.event : event.type;
    if (tag === "error") {
      throw new ProtocolError("EXECUTION_FAILED", typeof event.message === "string" ? event.message : "执行失败");
    }
    terminal = tag === (protocol === "ffmpeg" ? "end" : protocol === "singleAgent" ? "complete" : "done");
    yield event;
  }
  if (!terminal) throw new ProtocolError("STREAM_INTERRUPTED", "没有收到完成事件，请查询运行状态");
}

export async function mediaJson(response: Response): Promise<JsonEvent> {
  const value = eventJson(await response.text());
  if (!response.ok || value.code !== 200) {
    throw new ProtocolError("EXECUTION_FAILED", typeof value.message === "string" ? value.message : "媒体生成失败");
  }
  return value;
}

export function rpcResult(value: JsonEvent): JsonEvent {
  if (value.error) throw new ProtocolError("RPC_ERROR", "协议调用失败");
  const result = value.result;
  if (!result || typeof result !== "object" || Array.isArray(result)) throw new ProtocolError("INVALID_STREAM", "协议缺少 result");
  if ((result as JsonEvent).isError === true) throw new ProtocolError("TOOL_ERROR", "工具执行失败");
  return result as JsonEvent;
}

export function a2aState(task: JsonEvent): "active" | "succeeded" | "failed" | "cancelled" {
  const status = task.status as JsonEvent | undefined;
  const state = typeof status?.state === "string" ? status.state.toLowerCase().replace(/^task_state_/, "") : "";
  if (state === "completed") return "succeeded";
  if (state === "canceled" || state === "cancelled") return "cancelled";
  if (state === "failed" || state === "rejected") return "failed";
  if (["submitted", "working", "input-required", "input_required", "auth-required", "auth_required"].includes(state)) return "active";
  throw new ProtocolError("INVALID_STREAM", "未知 A2A 任务状态");
}
