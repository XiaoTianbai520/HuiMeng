import realAxios, { AxiosHeaders, type AxiosInstance, type CreateAxiosDefaults, type InternalAxiosRequestConfig } from "axios";
import mapping from "./route-map.json";
import { context, projectSignal, setActor, setCsrf } from "./context";
import { ProtocolError } from "./streams";
export * from "axios";

export type FetchTransport = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;
let nativeFetch: FetchTransport = globalThis.fetch.bind(globalThis);
/** Dependency injection for offline transport tests; production keeps the native transport. */
export function setFetchTransport(transport: FetchTransport) { nativeFetch = transport; }
const routes = new Map(mapping.routes.filter(route => route.targetPath).map(route => [`${route.sourceMethod} ${route.sourcePath}`, route]));
const counted = new Set(["/api/agent", "/api/ai/media/generate", "/api/singleAgent/run"]);
const safeMethods = new Set(["GET", "HEAD", "OPTIONS"]);
const allowedPlatform = [
  ...mapping.platform,
  ...mapping.routes.filter(route => route.targetPath).map(route => ({ method: route.sourceMethod, path: route.targetPath! })),
].map(route => ({ method: route.method, pattern: new RegExp("^" + route.path.replace(/\{\w+\}/g, "[A-Za-z0-9_-]{1,128}") + "$") }));
let csrfPending: Promise<string> | undefined;

export async function ensureCsrf() {
  if (context().csrfToken) return context().csrfToken;
  const authVersion = context().authVersion;
  if (!csrfPending) csrfPending = nativeFetch("/api/v1/auth/csrf", { credentials: "same-origin", cache: "no-store", redirect: "error" })
    .then(async response => {
      if (!response.ok) throw new ProtocolError("UNAUTHENTICATED", "无法获取会话防伪令牌");
      const value = await response.json() as { token?: string };
      if (!value.token) throw new ProtocolError("INVALID_RESPONSE", "会话防伪令牌无效");
      if (context().authVersion !== authVersion) throw new ProtocolError("SESSION_CHANGED", "会话已变更，请重试");
      setCsrf(value.token);
      return value.token;
    }).finally(() => { csrfPending = undefined; });
  return csrfPending;
}

function relative(value: string) {
  let decoded = value;
  for (let i = 0; i < 3; i++) {
    if (!/%[a-f0-9]{2}/i.test(decoded)) break;
    let next: string;
    try { next = decodeURIComponent(decoded); } catch { throw new ProtocolError("PATH_NOT_ALLOWED", "路径编码无效"); }
    if (next === decoded) break;
    decoded = next;
  }
  if (/^[\\/]|[:\u0000-\u001f]/.test(decoded) || decoded.split(/[\\/]/).includes("..") || /%[a-f0-9]{2}/i.test(decoded)) {
    throw new ProtocolError("PATH_NOT_ALLOWED", "只允许项目内的相对路径");
  }
}

function sanitize(value: unknown, projectId: string | null): unknown {
  if (Array.isArray(value)) return value.map(item => sanitize(item, projectId));
  if (!value || typeof value !== "object" || value instanceof Blob || value instanceof ArrayBuffer || ArrayBuffer.isView(value) || value instanceof FormData) return value;
  const result: Record<string, unknown> = {};
  for (const [key, child] of Object.entries(value)) {
    if (key === "absolutePath") throw new ProtocolError("PATH_NOT_ALLOWED", "请求包含服务器路径");
    if (key === "directory") {
      if (child !== projectId) throw new ProtocolError("PATH_NOT_ALLOWED", "项目上下文不匹配");
      continue;
    }
    if (["path", "target", "outputDirectory", "cwd"].includes(key) && typeof child === "string") relative(child);
    result[key] = sanitize(child, projectId);
  }
  return result;
}

export function prepareRequest(input: string, method: string, body?: unknown, headers = new Headers()) {
  const origin = typeof window === "undefined" ? "https://huimeng.invalid" : window.location.origin;
  const url = new URL(input, origin);
  if (url.origin !== origin || !url.pathname.startsWith("/api/")) throw new ProtocolError("CAPABILITY_FORBIDDEN", "请求必须经过绘梦 API");
  const state = context();
  const isPlatform = url.pathname.startsWith("/api/v1/");
  if (isPlatform && !allowedPlatform.some(route => route.method === method.toUpperCase() && route.pattern.test(url.pathname))) {
    throw new ProtocolError("CAPABILITY_FORBIDDEN", "该入口未纳入 Web 契约");
  }
  const route = routes.get(`${method.toUpperCase()} ${url.pathname}`);
  if (!isPlatform && !route) throw new ProtocolError("CAPABILITY_FORBIDDEN", "该入口未纳入 Web 契约");
  const legacyPath = url.pathname;
  if (route) {
    if (route.disposition === "管理员适配" && state.actor?.role !== "ADMIN") throw new ProtocolError("CAPABILITY_FORBIDDEN", "需要管理员权限");
    if (route.targetPath?.includes("{projectId}") && !state.projectId) throw new ProtocolError("PROJECT_REQUIRED", "请先选择项目");
    url.pathname = route.targetPath!.replace("{projectId}", state.projectId ?? "");
  }
  if (url.pathname.includes("/admin/") && state.actor?.role !== "ADMIN") throw new ProtocolError("CAPABILITY_FORBIDDEN", "需要管理员权限");
  const projectMatch = url.pathname.match(/^\/api\/v1\/projects\/([^/]+)\/(?:creative|runs)\//);
  if (projectMatch && projectMatch[1] !== state.projectId) throw new ProtocolError("PROJECT_FORBIDDEN", "请求与当前项目不一致");
  for (const [key, value] of [...url.searchParams]) {
    if (key === "directory") {
      if (value !== state.projectId) throw new ProtocolError("PATH_NOT_ALLOWED", "项目上下文不匹配");
      url.searchParams.delete(key);
    } else if (key === "absolutePath") throw new ProtocolError("PATH_NOT_ALLOWED", "请求包含服务器路径");
    else if (["path", "target"].includes(key)) relative(value);
  }
  headers.delete("Authorization");
  for (const name of [...headers.keys()]) if (name.toLowerCase().startsWith("x-toonflow-")) headers.delete(name);
  if (counted.has(legacyPath) || /\/creative\/(agent|singleAgent\/run|ai\/media\/generate)$/.test(url.pathname)) {
    // One invocation keeps its key; no automatic retry is performed here.
    if (!headers.has("Idempotency-Key")) headers.set("Idempotency-Key", crypto.randomUUID());
  }
  headers.set("X-Request-Id", headers.get("X-Request-Id") || crypto.randomUUID());
  return { url: url.pathname + url.search, body: sanitize(body, state.projectId), headers,
    projectScoped: !!projectMatch || route?.targetPath?.includes("{projectId}") === true };
}

export async function apiFetch(input: string | URL | Request, init: RequestInit = {}): Promise<Response> {
  if (input instanceof Request) throw new ProtocolError("INVALID_ARGUMENT", "请使用 API 路径与显式请求参数");
  const method = (init.method || "GET").toUpperCase();
  const headers = new Headers(init.headers);
  let body: unknown = init.body;
  const json = typeof body === "string" && headers.get("Content-Type")?.includes("application/json");
  if (json) body = JSON.parse(body as string);
  const request = prepareRequest(String(input), method, body, headers);
  const requestOwner = context();
  if (!safeMethods.has(method)) request.headers.set("X-CSRF-Token", await ensureCsrf());
  if (context().authVersion !== requestOwner.authVersion || (request.projectScoped && context().projectId !== requestOwner.projectId)) {
    throw new ProtocolError("SESSION_CHANGED", "会话或项目已变更，请重试");
  }
  const lifetime = request.projectScoped ? projectSignal() : undefined;
  let detachCancel = () => {};
  try {
    const response = await nativeFetch(request.url, { ...init, method, headers: request.headers, credentials: "same-origin", redirect: "error",
      body: json ? JSON.stringify(request.body) : request.body as BodyInit | undefined,
      signal: lifetime ? AbortSignal.any([lifetime.signal, ...(init.signal ? [init.signal] : [])]) : init.signal });
    if (response.status === 401 && context().authVersion === requestOwner.authVersion) setActor(null);
    const runId = response.headers.get("X-Run-Id");
    if (response.ok && runId && request.headers.has("Idempotency-Key") && init.signal) {
      const owner = requestOwner;
      const signal = init.signal;
      const cancel = () => {
        // Only the caller's explicit AbortSignal requests cancellation. A network
        // failure or project lifetime abort never does, and never releases a slot.
        if (context().authVersion !== owner.authVersion || context().projectId !== owner.projectId) return;
        if (!owner.projectId || !/^[A-Za-z0-9_-]{1,128}$/.test(runId)) return;
        void nativeFetch(`/api/v1/projects/${owner.projectId}/runs/${runId}/cancel`, {
          method: "POST", credentials: "same-origin", redirect: "error", headers: { "X-CSRF-Token": request.headers.get("X-CSRF-Token") || "",
            "X-Request-Id": crypto.randomUUID() },
        }).catch(() => {});
      };
      signal.addEventListener("abort", cancel, { once: true });
      detachCancel = () => signal.removeEventListener("abort", cancel);
      if (signal.aborted) cancel();
    }
    // Stream lifetime must survive until consumed; releasing a response header is not completion.
    if (lifetime && response.body) {
      const reader = response.body.getReader();
      return new Response(new ReadableStream<Uint8Array>({
        async pull(controller) {
          try {
            const item = await reader.read();
            if (item.done) { detachCancel(); lifetime.dispose(); controller.close(); }
            else controller.enqueue(item.value);
          } catch (error) { detachCancel(); lifetime.dispose(); controller.error(error); }
        },
        async cancel(reason) { detachCancel(); lifetime.dispose(); await reader.cancel(reason); },
      }), { status: response.status, statusText: response.statusText, headers: response.headers });
    }
    detachCancel();
    lifetime?.dispose();
    return response;
  } catch (error) { detachCancel(); lifetime?.dispose(); throw error; }
}

export function openEventSource(path: string): EventSource {
  const request = prepareRequest(path, "GET");
  return new EventSource(request.url, { withCredentials: true });
}

export function resourceUrl(path: string): string { return prepareRequest(path, "GET").url; }

export async function cancelRun(runId: string) {
  const projectId = context().projectId;
  if (!projectId || !/^[A-Za-z0-9_-]{1,128}$/.test(runId)) throw new ProtocolError("INVALID_ARGUMENT", "运行标识无效");
  return client.post(`/api/v1/projects/${projectId}/runs/${runId}/cancel`);
}

function create(defaults: CreateAxiosDefaults = {}): AxiosInstance {
  const instance = realAxios.create({ adapter: "fetch", fetchOptions: { redirect: "error" }, ...defaults });
  instance.interceptors.request.use(async (config: InternalAxiosRequestConfig) => {
    const raw = instance.getUri(config);
    const method = (config.method || "GET").toUpperCase();
    const headers = new Headers(config.headers.toJSON() as Record<string, string>);
    const request = prepareRequest(raw, method, config.data, headers);
    const requestOwner = context();
    if (!safeMethods.has(method)) request.headers.set("X-CSRF-Token", await ensureCsrf());
    if (context().authVersion !== requestOwner.authVersion || (request.projectScoped && context().projectId !== requestOwner.projectId)) {
      throw new ProtocolError("SESSION_CHANGED", "会话或项目已变更，请重试");
    }
    (config as InternalAxiosRequestConfig & { hmAuthVersion?: number }).hmAuthVersion = requestOwner.authVersion;
    config.url = request.url;
    config.baseURL = "";
    config.params = undefined;
    config.data = request.body;
    config.headers = AxiosHeaders.from(Object.fromEntries(request.headers));
    config.withCredentials = true;
    const lifetime = request.projectScoped ? projectSignal() : undefined;
    if (lifetime) {
      config.signal = AbortSignal.any([lifetime.signal, ...(config.signal ? [config.signal as AbortSignal] : [])]);
      (config as InternalAxiosRequestConfig & { releaseProject?: () => void }).releaseProject = lifetime.dispose;
    }
    return config;
  });
  instance.interceptors.response.use(response => {
    (response.config as InternalAxiosRequestConfig & { releaseProject?: () => void }).releaseProject?.();
    if (response.data && typeof response.data.code === "number" && response.data.code !== 200) {
      throw new ProtocolError("EXECUTION_FAILED", response.data.message || "操作失败");
    }
    return response;
  }, error => {
    error.config?.releaseProject?.();
    if (error.response?.status === 401 && error.config?.hmAuthVersion === context().authVersion) setActor(null);
    return Promise.reject(error);
  });
  return instance;
}

const client = Object.assign(create(), {
  create, isAxiosError: realAxios.isAxiosError, isCancel: realAxios.isCancel,
  AxiosError: realAxios.AxiosError, AxiosHeaders: realAxios.AxiosHeaders, CancelToken: realAxios.CancelToken,
  all: realAxios.all, spread: realAxios.spread, toFormData: realAxios.toFormData, formToJSON: realAxios.formToJSON,
  mergeConfig: realAxios.mergeConfig, HttpStatusCode: realAxios.HttpStatusCode, VERSION: realAxios.VERSION,
});
export default client;
