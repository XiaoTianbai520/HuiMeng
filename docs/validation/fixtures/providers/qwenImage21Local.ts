type MediaInput = { type: "url"; url: string; mimeType?: string } | { type: "base64"; data: string; mimeType: string } | { type: "binary"; data: Uint8Array; mimeType: string };
type ImageRequest = { model: string; prompt: string; images?: MediaInput[]; mask?: MediaInput; n?: number; ratio?: string; size?: string; quality?: string; outputFormat?: string; other?: Record<string, unknown> };
type Context = { signal?: AbortSignal; queryFetch: typeof fetch; tool: { fetch: typeof fetch; errorMessage(value: unknown): string } };
type Graph = Record<string, { class_type: string; inputs: Record<string, any>; _meta?: { title: string } }>;
const workflowTemplates: Record<string, Graph> = {"textToImage":{"1":{"class_type":"UNETLoader","_meta":{"title":"Qwen 2.1 INT8"},"inputs":{"unet_name":"qwen_image_2.1_int8_convrot.safetensors","weight_dtype":"default"}},"2":{"class_type":"CLIPLoader","_meta":{"title":"Qwen3-VL 8B W4A8"},"inputs":{"clip_name":"qwen3vl_8b_w4a8.safetensors","type":"qwen_image","device":"default"}},"3":{"class_type":"VAELoader","_meta":{"title":"Qwen 2.1 VAE"},"inputs":{"vae_name":"qwen_image_2.1_vae_bf16.safetensors"}},"4":{"class_type":"TextEncodeQwenImage21","_meta":{"title":"文生图提示词"},"inputs":{"clip":["2",0],"prompt":"一辆红色复古玩具汽车停在浅色木桌上，背景为米白色墙面，柔和的下午阳光从左侧照入，固定视角的写实产品摄影，车身和四个轮子清晰可见，画面简洁，没有文字。","negative_prompt":"","resolution":1024}},"5":{"class_type":"EmptyLatentImage","_meta":{"title":"输出画布（32对齐）"},"inputs":{"width":1024,"height":1024,"batch_size":1}},"6":{"class_type":"QwenImage21Cache","_meta":{"title":"原生 KV Cache"},"inputs":{"model":["1",0],"device":"auto","dtype":"default"}},"7":{"class_type":"KSampler","_meta":{"title":"基础模型 / 25步 / CFG1"},"inputs":{"model":["6",0],"positive":["4",0],"negative":["4",1],"latent_image":["5",0],"seed":1338,"steps":25,"cfg":1.0,"sampler_name":"euler","scheduler":"simple","denoise":1.0}},"8":{"class_type":"VAEDecode","_meta":{"title":"VAE 解码"},"inputs":{"samples":["7",0],"vae":["3",0]}},"9":{"class_type":"SaveImage","_meta":{"title":"保存 PNG"},"inputs":{"images":["8",0],"filename_prefix":"QwenImage21Local/textToImage"}}},"imageToImage":{"1":{"class_type":"UNETLoader","_meta":{"title":"Qwen 2.1 INT8"},"inputs":{"unet_name":"qwen_image_2.1_int8_convrot.safetensors","weight_dtype":"default"}},"2":{"class_type":"CLIPLoader","_meta":{"title":"Qwen3-VL 8B W4A8"},"inputs":{"clip_name":"qwen3vl_8b_w4a8.safetensors","type":"qwen_image","device":"default"}},"3":{"class_type":"VAELoader","_meta":{"title":"Qwen 2.1 VAE"},"inputs":{"vae_name":"qwen_image_2.1_vae_bf16.safetensors"}},"4":{"class_type":"TextEncodeQwenImage21","_meta":{"title":"编辑指令 / 参考图条件"},"inputs":{"clip":["2",0],"prompt":"将图中红色玩具汽车改成蓝色，保留汽车造型、木质桌面、背景、光线和构图。","negative_prompt":"","resolution":1024,"vae":["3",0],"images.image_1":["10",0]}},"5":{"class_type":"EmptyLatentImage","_meta":{"title":"输出画布（32对齐）"},"inputs":{"width":1024,"height":1024,"batch_size":1}},"6":{"class_type":"QwenImage21Cache","_meta":{"title":"原生 KV Cache"},"inputs":{"model":["1",0],"device":"auto","dtype":"default"}},"7":{"class_type":"KSampler","_meta":{"title":"基础模型 / 25步 / CFG1"},"inputs":{"model":["6",0],"positive":["4",0],"negative":["4",1],"latent_image":["5",0],"seed":1338,"steps":25,"cfg":1.0,"sampler_name":"euler","scheduler":"simple","denoise":1.0}},"8":{"class_type":"VAEDecode","_meta":{"title":"VAE 解码"},"inputs":{"samples":["7",0],"vae":["3",0]}},"9":{"class_type":"SaveImage","_meta":{"title":"保存 PNG"},"inputs":{"images":["8",0],"filename_prefix":"QwenImage21Local/imageToImage"}},"10":{"class_type":"LoadImage","_meta":{"title":"参考图片"},"inputs":{"image":"qwenImage21Reference.png"}}}};
const ratios = ["1:1", "2:3", "3:2", "3:4", "4:3", "9:16", "16:9", "21:9"];
const sizes: Record<string, number> = { "0.5MP": 736, "1K": 1024, "2K": 2048 };
const comfyBase = "http://127.0.0.1:8188";

function pause(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    signal.throwIfAborted();
    const done = () => { signal.removeEventListener("abort", abort); resolve(); };
    const timer = setTimeout(done, ms);
    const abort = () => { clearTimeout(timer); signal.removeEventListener("abort", abort); reject(signal.reason); };
    signal.addEventListener("abort", abort, { once: true });
  });
}

async function jsonRequest(context: Context, path: string, signal: AbortSignal, init: RequestInit = {}) {
  const response = await context.tool.fetch(`${comfyBase}${path}`, { ...init, signal: AbortSignal.any([signal, AbortSignal.timeout(60000)]) });
  if (!response.ok) throw new Error(`ComfyUI ${path.split("?")[0]}：HTTP ${response.status} ${context.tool.errorMessage(await response.text())}`);
  return await response.json();
}

async function uploadImage(context: Context, input: MediaInput, signal: AbortSignal) {
  let bytes: Uint8Array;
  if (input.type === "url") {
    const url = new URL(input.url);
    if (!["http:", "https:"].includes(url.protocol)) throw new Error("参考图片 URL 须使用 HTTP(S)");
    const response = await context.tool.fetch(url, { signal: AbortSignal.any([signal, AbortSignal.timeout(120000)]) });
    if (!response.ok) throw new Error(`读取参考图片失败：HTTP ${response.status}`);
    if (Number(response.headers.get("content-length")) > 100 * 1024 * 1024) throw new Error("参考图片超过 100MB");
    bytes = new Uint8Array(await response.arrayBuffer());
  } else if (input.type === "base64") {
    bytes = Buffer.from(input.data.replace(/^data:[^;,]+;base64,/, ""), "base64");
  } else bytes = input.data;
  if (!bytes?.length || bytes.length > 100 * 1024 * 1024) throw new Error("参考图片为空或超过 100MB");
  const head = Buffer.from(bytes.subarray(0, 16));
  const png = head.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
  const jpeg = head[0] === 255 && head[1] === 216 && head[2] === 255;
  const webp = head.subarray(0, 4).toString("ascii") === "RIFF" && head.subarray(8, 12).toString("ascii") === "WEBP";
  if (!png && !jpeg && !webp) throw new Error("参考图片须为 PNG、JPEG 或 WebP");
  const ext = png ? "png" : jpeg ? "jpg" : "webp";
  const mime = png ? "image/png" : jpeg ? "image/jpeg" : "image/webp";
  const boundary = `qwenImage21${Date.now()}${Math.floor(Math.random() * 1e9)}`;
  const body = Buffer.concat([
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="image"; filename="${boundary}.${ext}"\r\nContent-Type: ${mime}\r\n\r\n`),
    Buffer.from(bytes), Buffer.from(`\r\n--${boundary}\r\nContent-Disposition: form-data; name="type"\r\n\r\ninput\r\n--${boundary}--\r\n`),
  ]);
  const result = await jsonRequest(context, "/upload/image", signal, { method: "POST", headers: { "Content-Type": `multipart/form-data; boundary=${boundary}` }, body });
  if (!result.name || result.type !== "input") throw new Error("ComfyUI 未返回有效的上传图片");
  return result.subfolder ? `${result.subfolder}/${result.name}` : result.name;
}

export default {
  id: "qwenImage21Local",
  label: "本地 Qwen-Image-2.1 · RTX3060",
  version: "1.0.0",
  readme: "连接本机 ComfyUI 127.0.0.1:8188，无需 API Key。TEXT_TO_IMAGE 文生图、IMAGE_TO_IMAGE 单图指令编辑；基础 INT8 模型，25步/CFG1/Euler/simple，默认1K（约1MP）。2K为约4MP，3060上较慢且尚未实测。图生图保留完整参考图，输出按所选比例和像素预算生成；与参考图比例差异大时构图可能变化。不支持蒙版。other 可设 seed、steps、negativePrompt、cacheDevice（auto/cpu/off），单次等待上限2小时。",
  rules: [],
  models: [
    { id: "qwenImage21Text", label: "Qwen 2.1 文生图 · TEXT_TO_IMAGE", type: "image", mode: ["text"], imageSizes: ["1K", "0.5MP", "2K"], imageRatios: ["1:1", "2:3", "3:2", "3:4", "4:3", "9:16", "16:9", "21:9"] },
    { id: "qwenImage21Edit", label: "Qwen 2.1 图生图 · IMAGE_TO_IMAGE", type: "image", mode: ["singleImage"], imageSizes: ["1K", "0.5MP", "2K"], imageRatios: ["1:1", "2:3", "3:2", "3:4", "4:3", "9:16", "16:9", "21:9"] },
  ],
  async generateImage(this: Context, request: ImageRequest) {
    const edit = request.model === "qwenImage21Edit";
    if (!edit && request.model !== "qwenImage21Text") throw new Error("不支持的 Qwen 模型");
    if (typeof request.prompt !== "string" || !request.prompt.trim()) throw new Error("提示词不能为空");
    if (request.mask) throw new Error("此工作流不支持蒙版，请使用整图编辑指令");
    if (request.n !== undefined && request.n !== 1) throw new Error("3060 配置每次生成1张图片");
    if (request.quality) throw new Error("此基础模型使用固定画质配置，请勿传 quality");
    if (request.outputFormat && request.outputFormat !== "png") throw new Error("此工作流输出 PNG");
    const images = request.images ?? [];
    if (edit && images.length !== 1) throw new Error("IMAGE_TO_IMAGE 必须提供1张参考图片");
    if (!edit && images.length) throw new Error("TEXT_TO_IMAGE 不接收参考图片，请选择图生图模型");
    const ratio = request.ratio ?? "1:1";
    if (!ratios.includes(ratio)) throw new Error(`不支持的比例：${ratio}`);
    const resolution = sizes[request.size ?? "1K"];
    if (!resolution) throw new Error(`不支持的尺寸：${request.size}`);
    const other = request.other ?? {};
    const seed = other.seed ?? Math.floor(Math.random() * 2 ** 32);
    const steps = other.steps ?? 25;
    const negativePrompt = other.negativePrompt ?? "";
    const cacheDevice = other.cacheDevice ?? "auto";
    if (typeof seed !== "number" || !Number.isSafeInteger(seed) || seed < 0) throw new Error("seed 须为非负安全整数");
    if (typeof steps !== "number" || !Number.isInteger(steps) || steps < 1 || steps > 50) throw new Error("steps 须为1–50的整数，基础模型建议25步");
    if (typeof negativePrompt !== "string") throw new Error("negativePrompt 须为字符串");
    if (!["auto", "cpu", "off"].includes(String(cacheDevice))) throw new Error("cacheDevice 只支持 auto/cpu/off");
    const timeout = AbortSignal.timeout(2 * 60 * 60 * 1000);
    const signal = this.signal ? AbortSignal.any([this.signal, timeout]) : timeout;
    const graph: Graph = JSON.parse(JSON.stringify(workflowTemplates[edit ? "imageToImage" : "textToImage"]));
    const [rw, rh] = ratio.split(":").map(Number);
    Object.assign(graph["5"].inputs, { width: Math.max(32, Math.round(resolution * Math.sqrt(rw / rh) / 32) * 32), height: Math.max(32, Math.round(resolution / Math.sqrt(rw / rh) / 32) * 32) });
    Object.assign(graph["4"].inputs, { prompt: request.prompt.trim(), negative_prompt: negativePrompt, resolution });
    Object.assign(graph["7"].inputs, { seed, steps });
    graph["6"].inputs.device = cacheDevice;
    const info = await jsonRequest(this, "/object_info", signal);
    for (const node of Object.values(graph)) {
      const schema = info[node.class_type];
      if (!schema) throw new Error(`ComfyUI 缺少节点：${node.class_type}`);
      for (const field of ["unet_name", "clip_name", "vae_name"]) {
        const filename = node.inputs[field];
        if (!filename) continue;
        const spec = schema.input.required?.[field] ?? schema.input.optional?.[field];
        const options = Array.isArray(spec?.[0]) ? spec[0] : spec?.[1]?.options;
        if (!options?.includes(filename)) throw new Error(`ComfyUI 缺少模型：${filename}`);
      }
    }
    if (edit) graph["10"].inputs.image = await uploadImage(this, images[0], signal);
    const queued = await jsonRequest(this, "/prompt", signal, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ prompt: graph, client_id: `qwenImage21Local${Date.now()}` }) });
    const promptId = queued.prompt_id;
    if (typeof promptId !== "string" || !/^[a-f0-9-]{36}$/i.test(promptId)) throw new Error("ComfyUI 未返回有效任务 ID");
    let completed = false;
    const cancel = () => { void this.queryFetch(`${comfyBase}/api/jobs/${promptId}/cancel`, { method: "POST", signal: AbortSignal.timeout(10000) }).catch(() => {}); };
    signal.addEventListener("abort", cancel, { once: true });
    if (signal.aborted) cancel();
    try {
      while (true) {
        signal.throwIfAborted();
        const history = await jsonRequest(this, `/history/${promptId}`, signal);
        const record = history[promptId];
        if (record?.status?.status_str === "error") {
          const failure = record.status.messages?.find((entry: any) => entry[0] === "execution_error")?.[1];
          const detail = this.tool.errorMessage(failure?.exception_message ?? record.status.messages);
          throw new Error(`Qwen 图片生成失败（${promptId}）：${detail}`);
        }
        if (record?.status?.completed) {
          completed = true;
          const image = record.outputs?.["9"]?.images?.[0];
          if (!image?.filename || image.type !== "output") throw new Error("ComfyUI 已结束，但未返回 PNG 图片");
          const query = new URLSearchParams({ filename: image.filename, subfolder: image.subfolder ?? "", type: "output" });
          const response = await this.tool.fetch(`${comfyBase}/view?${query}`, { signal: AbortSignal.any([signal, AbortSignal.timeout(120000)]) });
          if (!response.ok) throw new Error(`读取生成图片失败：HTTP ${response.status}`);
          const bytes = new Uint8Array(await response.arrayBuffer());
          if (bytes.length < 24 || bytes.length > 100 * 1024 * 1024 || !Buffer.from(bytes.subarray(0, 8)).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) throw new Error("返回的图片不是有效 PNG 或超过100MB");
          return [{ mediaType: "image" as const, type: "binary" as const, data: bytes, mimeType: "image/png" }];
        }
        await pause(2000, signal);
      }
    } finally {
      signal.removeEventListener("abort", cancel);
      if (!completed) cancel();
    }
  },
};
