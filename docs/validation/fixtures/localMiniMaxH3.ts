/** M0-only Toonflow 2.0 media provider for local MiniMax H3 FL2VA weights in ComfyUI. */
const COMFY_BASE = "http://127.0.0.1:8188";
const MODEL = "minimax-h3-local";
const MAX_VIDEO_BYTES = 100 * 1024 * 1024;
const SIZES: Record<string, Record<string, [number, number]>> = {
  "480p": { "16:9": [832, 480], "9:16": [480, 832], "1:1": [640, 640] },
};

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason);
    const timer = setTimeout(done, ms);
    function done() { signal?.removeEventListener("abort", abort); resolve(); }
    function abort() { clearTimeout(timer); reject(signal?.reason); }
    signal?.addEventListener("abort", abort, { once: true });
  });
}

function frames(seconds: number): number {
  const count = Math.max(5, Math.round(seconds * 24));
  return count + (5 - count % 17 + 17) % 17;
}

function inputBytes(input: MediaInput): { bytes: Uint8Array; mimeType: string } {
  if (input.type === "base64") {
    const match = /^data:([^;,]+);base64,([\s\S]+)$/.exec(input.data);
    return { bytes: Buffer.from(match?.[2] ?? input.data, "base64"), mimeType: match?.[1] ?? input.mimeType };
  }
  if (input.type === "binary") return { bytes: input.data, mimeType: input.mimeType };
  throw new Error("本地 H3 只接受工作区内的图片文件");
}

async function uploadFrame(context: Context, input: MediaInput, label: string): Promise<string> {
  const { bytes, mimeType } = inputBytes(input);
  if (!bytes.length || bytes.length > 20 * 1024 * 1024 || !["image/png", "image/jpeg", "image/webp"].includes(mimeType)) {
    throw new Error("参考帧须为不超过 20 MB 的 PNG、JPEG 或 WebP 图片");
  }
  const boundary = `huimengH3${Date.now()}${Math.floor(Math.random() * 1_000_000)}`;
  const filename = `huimeng-h3-${label}-${boundary}.${mimeType === "image/jpeg" ? "jpg" : mimeType.slice(6)}`;
  const body = Buffer.concat([
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="image"; filename="${filename}"\r\nContent-Type: ${mimeType}\r\n\r\n`),
    Buffer.from(bytes),
    Buffer.from(`\r\n--${boundary}\r\nContent-Disposition: form-data; name="type"\r\n\r\ninput\r\n--${boundary}\r\nContent-Disposition: form-data; name="overwrite"\r\n\r\nfalse\r\n--${boundary}--\r\n`),
  ]);
  const response = await context.tool.fetch(`${COMFY_BASE}/upload/image`, {
    method: "POST", headers: { "Content-Type": `multipart/form-data; boundary=${boundary}` }, body,
  });
  if (!response.ok) throw new Error(`ComfyUI 上传参考帧失败：HTTP ${response.status}`);
  const image = await response.json() as { name?: string; subfolder?: string; type?: string };
  if (!image.name || image.subfolder || image.type !== "input") throw new Error("ComfyUI 未返回有效参考帧文件名");
  return image.name;
}

function workflow(prompt: string, width: number, height: number, duration: number, seed: number, first?: string, last?: string) {
  const condition: Record<string, unknown> = {
    clip: ["2", 0], vae: ["3", 0], prompt, width, height, length: frames(duration),
  };
  const graph: Record<string, { class_type: string; inputs: Record<string, unknown> }> = {
    "1": { class_type: "UNETLoader", inputs: { unet_name: "minimax_h3_fl2va_pruned_int8_convrot.safetensors", weight_dtype: "default" } },
    "2": { class_type: "CLIPLoader", inputs: { clip_name: "qwen3vl_32b_minimax_h3_int8_convrot.safetensors", type: "minimax", device: "default" } },
    "3": { class_type: "VAELoader", inputs: { vae_name: "minimax_h3_video_vae_fp16.safetensors" } },
    "4": { class_type: "VAELoader", inputs: { vae_name: "minimax_h3_audio_vae_fp32.safetensors" } },
    "5": { class_type: "MiniMaxH3ImageToVideo", inputs: condition },
    "6": { class_type: "BasicGuider", inputs: { model: ["1", 0], conditioning: ["5", 0] } },
    "7": { class_type: "BasicScheduler", inputs: { model: ["1", 0], scheduler: "simple", steps: 20, denoise: 1 } },
    "8": { class_type: "RandomNoise", inputs: { noise_seed: seed } },
    "9": { class_type: "KSamplerSelect", inputs: { sampler_name: "res_multistep" } },
    "10": { class_type: "SamplerCustomAdvanced", inputs: { noise: ["8", 0], guider: ["6", 0], sampler: ["9", 0], sigmas: ["7", 0], latent_image: ["5", 1] } },
    "11": { class_type: "VAEDecode", inputs: { samples: ["10", 0], vae: ["3", 0] } },
    "12": { class_type: "VAEDecodeAudio", inputs: { samples: ["10", 0], vae: ["4", 0] } },
    "13": { class_type: "CreateVideo", inputs: { images: ["11", 0], audio: ["12", 0], fps: 24, bit_depth: 8 } },
    "14": { class_type: "SaveVideo", inputs: { video: ["13", 0], filename_prefix: "Huimeng-M0-H3", format: "mp4", codec: { codec: "h264" } } },
  };
  if (first) {
    graph["15"] = { class_type: "LoadImage", inputs: { image: first } };
    condition.first_frame = ["15", 0];
  }
  if (last) {
    graph["16"] = { class_type: "LoadImage", inputs: { image: last } };
    condition.last_frame = ["16", 0];
  }
  return graph;
}

export default {
  id: "localMiniMaxH3",
  label: "本地 MiniMax H3（M0 测试）",
  version: "0.1.0",
  readme: "连接本机 ComfyUI 127.0.0.1:8188；使用本地 H3 权重生成 480p、5 秒、带原生声音的 MP4。支持文生视频、首帧及首尾帧模式。",
  rules: [],
  models: [{
    id: "minimax-h3-local", label: "MiniMax H3 本地视频", type: "video",
    mode: ["text", "singleImage", "startFrameOptional", "startEndRequired"],
    durationResolutionMap: [{ duration: [5], resolution: ["480p"] }],
    audio: true,
  }],
  async getPrice(request: ProviderPriceRequest): Promise<ProviderPrice | null> {
    return request.type === "video" && request.model === MODEL
      ? { price: "本地算力", tips: "使用本机 GPU；无远程视频接口费用" }
      : null;
  },
  async generateVideo(this: Context, request: VideoRequest): Promise<MediaAsset[]> {
    if (request.model !== MODEL) throw new Error("不支持的模型");
    if (!request.prompt?.trim()) throw new Error("视频提示词不能为空");
    if (request.videos?.length || request.audios?.length) throw new Error("当前 H3 适配器不支持视频或音频参考");
    if (request.images && request.images.length > 1) throw new Error("最多只支持一张首帧图片");
    if (request.images?.length && request.firstFrame) throw new Error("首帧图片不能重复提供");
    const first = request.firstFrame ?? request.images?.[0];
    if (request.lastFrame && !first) throw new Error("末帧必须与首帧一同提供");
    if (request.mode === "text" && (first || request.lastFrame)) throw new Error("文生视频模式不能提供参考帧");
    if (request.mode === "singleImage" && !first) throw new Error("图生视频模式需要首帧");
    if (request.mode === "startEndRequired" && (!first || !request.lastFrame)) throw new Error("首尾帧模式需要两张图片");
    if (Array.isArray(request.mode)) throw new Error("当前 H3 适配器不支持参考素材模式");
    if (request.generateAudio === false) throw new Error("本地 H3 的视频与声音联合生成，不能关闭声音");
    if (request.duration !== undefined && request.duration !== 5) throw new Error("当前只支持 5 秒视频");
    if (request.resolution !== undefined && request.resolution !== "480p") throw new Error("当前只支持 480p 视频");
    const ratio = request.ratio ?? "16:9";
    const size = SIZES["480p"][ratio];
    if (!size) throw new Error("当前只支持 16:9、9:16、1:1 画幅");
    this.signal?.throwIfAborted();
    const firstName = first ? await uploadFrame(this, first, "first") : undefined;
    const lastName = request.lastFrame ? await uploadFrame(this, request.lastFrame, "last") : undefined;
    const seed = Math.floor(Math.random() * 2_147_483_647);
    const response = await this.tool.fetch(`${COMFY_BASE}/prompt`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ prompt: workflow(request.prompt, ...size, 5, seed, firstName, lastName), client_id: "huimeng-m0-h3" }),
    });
    if (!response.ok) throw new Error(`ComfyUI 提交失败：HTTP ${response.status} ${String(await response.text()).slice(0, 800)}`);
    const { prompt_id: promptId } = await response.json() as { prompt_id?: string };
    if (!promptId || !/^[a-f0-9-]{36}$/i.test(promptId)) throw new Error("ComfyUI 未返回有效任务 ID");
    // queryFetch is the host's unbound fetch: the normal tool.fetch inherits the cancelled request signal.
    const cancel = () => { void (this as Context & { queryFetch: typeof fetch }).queryFetch(`${COMFY_BASE}/api/jobs/${promptId}/cancel`, { method: "POST" }).catch(() => {}); };
    this.signal?.addEventListener("abort", cancel, { once: true });
    if (this.signal?.aborted) cancel();
    try {
      const deadline = Date.now() + 90 * 60_000;
      while (Date.now() < deadline) {
        this.signal?.throwIfAborted();
        const status = await this.tool.fetch(`${COMFY_BASE}/history/${promptId}`);
        if (!status.ok) throw new Error(`ComfyUI 查询失败：HTTP ${status.status}`);
        const record = (await status.json() as Record<string, any>)[promptId];
        if (record) {
          if (record.status?.status_str === "error" || record.status?.completed === false) {
            throw new Error(`ComfyUI 生成失败：${JSON.stringify(record.status?.messages ?? []).slice(0, 700)}`);
          }
          const video = record.outputs?.["14"]?.images?.[0];
          if (!video?.filename || video.type !== "output") throw new Error("ComfyUI 已结束但未返回 MP4 视频");
          const query = new URLSearchParams({ filename: video.filename, subfolder: video.subfolder ?? "", type: "output" });
          const file = await this.tool.fetch(`${COMFY_BASE}/view?${query}`);
          if (!file.ok) throw new Error(`ComfyUI 视频读取失败：HTTP ${file.status}`);
          if (Number(file.headers.get("content-length")) > MAX_VIDEO_BYTES) throw new Error("生成视频超过 Toonflow 100 MB 限制");
          const bytes = new Uint8Array(await file.arrayBuffer());
          const header = Buffer.from(bytes.subarray(0, 16));
          if (!bytes.length || bytes.length > MAX_VIDEO_BYTES || header.toString("ascii", 4, 8) !== "ftyp") {
            throw new Error("ComfyUI 返回的不是受支持的 MP4 视频");
          }
          return [{ type: "base64", data: Buffer.from(bytes).toString("base64"), mimeType: "video/mp4", mediaType: "video" }];
        }
        await sleep(2000, this.signal);
      }
      cancel();
      throw new Error("等待 ComfyUI 视频生成超时");
    } finally {
      this.signal?.removeEventListener("abort", cancel);
    }
  },
};
