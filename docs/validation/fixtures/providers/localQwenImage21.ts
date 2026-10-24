/** M0-only Toonflow 2.0 media provider for the local Qwen-Image-2.1 API workflow. */
const COMFY_BASE = "http://127.0.0.1:8188";
const RATIO_OPTIONS: Record<string, string> = {
  "1:1": "1:1 (Square)",
  "2:3": "2:3 (Portrait Photo)",
  "3:2": "3:2 (Photo)",
  "3:4": "3:4 (Portrait Standard)",
  "4:3": "4:3 (Standard)",
  "9:16": "9:16 (Portrait Widescreen)",
  "16:9": "16:9 (Widescreen)",
  "21:9": "21:9 (Ultrawide)",
};

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason);
    const timer = setTimeout(done, ms);
    function done() {
      signal?.removeEventListener("abort", abort);
      resolve();
    }
    function abort() {
      clearTimeout(timer);
      reject(signal?.reason);
    }
    signal?.addEventListener("abort", abort, { once: true });
  });
}

/** 把 MediaInput 转成字节数组；url 类型现拉取。 */
async function mediaToBytes(ctx: Context, input: MediaInput): Promise<Uint8Array> {
  if (input.type === "binary") return input.data;
  if (input.type === "base64") {
    return new Uint8Array(Buffer.from(input.data, "base64"));
  }
  const res = await ctx.tool.fetch(input.url);
  if (!res.ok) throw new Error(`参考图拉取失败：HTTP ${res.status}`);
  return new Uint8Array(await res.arrayBuffer());
}

/** 上传参考图到本机 ComfyUI，返回 LoadImage 可用的文件名。 */
async function uploadImage(ctx: Context, bytes: Uint8Array, mimeType: string): Promise<string> {
  const ext = mimeType.includes("jpeg") || mimeType.includes("jpg") ? "jpg" : "png";
  const boundary = `qwen21Edit${Date.now()}${Math.floor(Math.random() * 1e9)}`;
  const body = Buffer.concat([
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="image"; filename="qwen21-edit-${Date.now()}.${ext}"\r\nContent-Type: ${mimeType || "image/png"}\r\n\r\n`),
    Buffer.from(bytes),
    Buffer.from(`\r\n--${boundary}--\r\n`),
  ]);
  const res = await ctx.tool.fetch(`${COMFY_BASE}/upload/image`, {
    method: "POST", headers: { "Content-Type": `multipart/form-data; boundary=${boundary}` }, body,
  });
  if (!res.ok) throw new Error(`ComfyUI 图片上传失败：HTTP ${res.status} ${await res.text()}`);
  const json = (await res.json()) as { name?: string };
  if (!json.name) throw new Error("ComfyUI 未返回上传文件名");
  return json.name;
}

/** 文生图工作流（Qwen-Image-2.1，本地 ComfyUI 节点）。 */
function textWorkflow(prompt: string, ratio: string, megapixels: number, seed: number) {
  return {
    "1": { class_type: "UNETLoader", inputs: { unet_name: "qwen_image_2.1_int8_convrot.safetensors", weight_dtype: "default" } },
    "2": { class_type: "CLIPLoader", inputs: { clip_name: "qwen3vl_8b_w4a8.safetensors", type: "qwen_image", device: "default" } },
    "3": { class_type: "VAELoader", inputs: { vae_name: "qwen_image_2.1_vae_bf16.safetensors" } },
    "4": { class_type: "ResolutionSelector", inputs: { aspect_ratio: ratio, megapixels, multiple: 32 } },
    "5": { class_type: "EmptyLatentImage", inputs: { width: ["4", 0], height: ["4", 1], batch_size: 1 } },
    "6": { class_type: "TextEncodeQwenImage21", inputs: { clip: ["2", 0], prompt, negative_prompt: "", resolution: 1024 } },
    "11": { class_type: "RepeatLatentBatch", inputs: { samples: ["5", 0], amount: 1 } },
    "12": { class_type: "QwenImage21Cache", inputs: { model: ["1", 0], device: "auto", dtype: "default" } },
    "13": { class_type: "KSampler", inputs: { model: ["12", 0], positive: ["6", 0], negative: ["6", 1], latent_image: ["11", 0], seed, steps: 25, cfg: 1, sampler_name: "euler", scheduler: "simple", denoise: 1 } },
    "14": { class_type: "VAEDecode", inputs: { samples: ["13", 0], vae: ["3", 0] } },
    "15": { class_type: "SaveImage", inputs: { images: ["14", 0], filename_prefix: "Huimeng-M0-Qwen21" } },
  };
}

/**
 * 图生图（图片编辑）工作流：单张参考图经 LoadImage 进入 TextEncodeQwenImage21，
 * 以 VAE 编码为参考潜变量注入条件，并按参考图尺寸出图（resolution=0 时保持原图尺寸）。
 */
function editWorkflow(refName: string, prompt: string, negativePrompt: string, seed: number, denoise: number, resolution: number) {
  return {
    "1": { class_type: "UNETLoader", inputs: { unet_name: "qwen_image_2.1_int8_convrot.safetensors", weight_dtype: "default" } },
    "2": { class_type: "CLIPLoader", inputs: { clip_name: "qwen3vl_8b_w4a8.safetensors", type: "qwen_image", device: "default" } },
    "3": { class_type: "VAELoader", inputs: { vae_name: "qwen_image_2.1_vae_bf16.safetensors" } },
    "4": { class_type: "LoadImage", inputs: { image: refName } },
    "5": { class_type: "TextEncodeQwenImage21", inputs: { clip: ["2", 0], prompt, negative_prompt: negativePrompt, resolution, vae: ["3", 0], images: { image_1: ["4", 0] } } },
    "6": { class_type: "QwenImage21Cache", inputs: { model: ["1", 0], device: "auto", dtype: "default" } },
    "7": { class_type: "KSampler", inputs: { model: ["6", 0], positive: ["5", 0], negative: ["5", 1], latent_image: ["5", 2], seed, steps: 25, cfg: 1, sampler_name: "euler", scheduler: "simple", denoise } },
    "8": { class_type: "VAEDecode", inputs: { samples: ["7", 0], vae: ["3", 0] } },
    "9": { class_type: "SaveImage", inputs: { images: ["8", 0], filename_prefix: "Huimeng-M0-Qwen21-Edit" } },
  };
}

/** 提交 ComfyUI 任务并轮询取回 PNG。 */
async function runComfy(ctx: Context, graph: Record<string, any>, saveNodeId: string): Promise<MediaAsset[]> {
  const queued = await ctx.tool.fetch(`${COMFY_BASE}/prompt`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ prompt: graph, client_id: "huimeng-m0-qwen21" }),
  });
  if (!queued.ok) throw new Error(`ComfyUI 提交失败：HTTP ${queued.status} ${await queued.text()}`);
  const { prompt_id: promptId } = (await queued.json()) as { prompt_id?: string };
  if (!promptId || !/^[a-f0-9-]{36}$/i.test(promptId)) throw new Error("ComfyUI 未返回有效任务 ID");

  const deadline = Date.now() + 15 * 60_000;
  while (Date.now() < deadline) {
    ctx.signal?.throwIfAborted();
    const response = await ctx.tool.fetch(`${COMFY_BASE}/history/${promptId}`);
    if (!response.ok) throw new Error(`ComfyUI 查询失败：HTTP ${response.status}`);
    const record = (await response.json() as Record<string, any>)[promptId];
    if (record) {
      if (record.status?.status_str === "error" || record.status?.completed === false) {
        throw new Error(`ComfyUI 生成失败：${JSON.stringify(record.status?.messages ?? []).slice(0, 700)}`);
      }
      const image = record.outputs?.[saveNodeId]?.images?.[0];
      if (!image?.filename || image.type !== "output") throw new Error("ComfyUI 已结束但未返回图片");
      const query = new URLSearchParams({ filename: image.filename, subfolder: image.subfolder ?? "", type: "output" });
      const file = await ctx.tool.fetch(`${COMFY_BASE}/view?${query}`);
      if (!file.ok) throw new Error(`ComfyUI 图片读取失败：HTTP ${file.status}`);
      const bytes = new Uint8Array(await file.arrayBuffer());
      if (bytes.length < 8 || bytes[0] !== 137 || bytes[1] !== 80 || bytes[2] !== 78 || bytes[3] !== 71) {
        throw new Error("ComfyUI 返回的不是 PNG 图片");
      }
      return [{ type: "base64", data: Buffer.from(bytes).toString("base64"), mimeType: "image/png", mediaType: "image" }];
    }
    await sleep(1500, ctx.signal);
  }
  throw new Error("等待 ComfyUI 图片生成超时");
}

export default {
  id: "localQwenImage21",
  label: "本地 Qwen-Image-2.1（M0 测试）",
  version: "0.1.2",
  readme: "仅用于固定提交 M0 图片生成取证。连接本机 ComfyUI 127.0.0.1:8188；支持文生图与图生图（单图编辑）。",
  rules: [],
  models: [{
    id: "qwen-image-2.1",
    label: "Qwen-Image-2.1 本地文生图 / 图生图编辑",
    type: "image",
    mode: ["text", "singleImage"],
    imageSizes: ["0.3MP", "1MP"],
    imageRatios: ["1:1", "2:3", "3:2", "3:4", "4:3", "9:16", "16:9", "21:9"],
  }],
  async generateImage(this: Context, request: ImageRequest): Promise<MediaAsset[]> {
    if (request.model !== "qwen-image-2.1") throw new Error("不支持的模型");
    if (!request.prompt?.trim()) throw new Error("提示词不能为空");
    if (request.mask) throw new Error("此测试适配器暂不支持蒙版编辑");
    const ctx = this;
    const seed = Math.floor(Math.random() * 2_147_483_647);

    if (request.images?.length) {
      // 图生图（图片编辑）：单张参考图 + 提示词
      if (request.images.length > 1) throw new Error("此测试适配器只支持单张参考图编辑");
      const refBytes = await mediaToBytes(ctx, request.images[0]);
      if (!refBytes.length) throw new Error("参考图数据为空");
      const refName = await uploadImage(ctx, refBytes, request.images[0].mimeType || "image/png");
      const negativePrompt = (request.other?.negativePrompt as string) ?? "";
      const denoise = typeof request.other?.denoise === "number" ? request.other.denoise : 1;
      const resolution = typeof request.other?.resolution === "number" ? request.other.resolution : 0;
      return await runComfy(ctx, editWorkflow(refName, request.prompt, negativePrompt, seed, denoise, resolution), "9");
    }

    // 文生图
    const ratio = RATIO_OPTIONS[request.ratio ?? "1:1"];
    if (!ratio) throw new Error("不支持的画幅比例");
    const size = request.size ?? "0.3MP";
    const megapixels = size === "0.3MP" ? 0.3 : size === "1MP" ? 1 : null;
    if (megapixels === null) throw new Error("不支持的图片尺寸");
    return await runComfy(ctx, textWorkflow(request.prompt, ratio, megapixels, seed), "15");
  },
};
