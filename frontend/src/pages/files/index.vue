<template>
  <main class="teamPage">
    <header class="teamHeader"><div><span class="teamBrand">绘梦 <small>项目文件</small></span><h1>{{ projectName || '项目文件' }}</h1></div><router-link to="/home">返回项目</router-link></header>
    <WorkspaceDocuments v-if="projectName" :project-id="projectId()" />
    <section class="teamPanel" aria-label="项目文件与素材">
      <header class="panelHeading"><div><h2>文件与素材</h2><p>上传素材和生成结果保存在当前项目，访问时重新检查成员权限。</p></div><el-button :disabled="busy" @click="refresh">刷新文件</el-button></header>
      <p v-if="error" role="alert" class="panelError">{{ error }}</p>
      <form aria-label="上传项目文件" class="inlineForm" @submit.prevent="upload">
        <div><label for="projectUpload">选择文件（最大 512 MiB）</label><input id="projectUpload" ref="uploadInput" type="file" :disabled="busy || !authorized" @change="selectFile" /></div>
        <el-button native-type="submit" type="primary" :loading="busy" :disabled="!selected || !authorized">上传文件</el-button>
      </form>
      <form aria-label="受控媒体生成" class="inlineForm" @submit.prevent="generate">
        <div><label for="imageModel">获批模型</label><select id="imageModel" v-model="selectedModel" :disabled="busy || !authorized"><option value="">请选择模型</option><option v-for="model in models" :key="model.modelId" :value="model.modelId">{{ model.label }}</option></select></div>
        <div v-if="isVideoModel"><label for="videoDuration">时长</label><select id="videoDuration" v-model="videoDuration" :disabled="busy || !authorized"><option v-for="value in videoDurations" :key="value" :value="value">{{ value }} 秒</option></select></div>
        <div><label for="imageSize">{{ isVideoModel ? '分辨率' : '尺寸' }}</label><select id="imageSize" v-model="selectedSize" :disabled="busy || !authorized"><option v-for="size in sizeOptions" :key="size" :value="size">{{ size }}</option></select></div>
        <div><label for="imageRatio">画幅</label><select id="imageRatio" v-model="selectedRatio" :disabled="busy || !authorized"><option v-for="ratio in ratioOptions" :key="ratio" :value="ratio">{{ ratio }}</option></select></div>
        <div v-if="needsReference"><label for="imageReference">{{ referenceLabel }}</label><select id="imageReference" v-model="referenceId" :disabled="busy || !authorized"><option value="">请选择参考图</option><option v-for="item in referenceCandidates" :key="item.id" :value="String(item.id)">{{ item.filename }}</option></select></div>
        <div v-if="needsSecondFrame"><label for="imageReferenceLast">尾帧（项目内 PNG）</label><select id="imageReferenceLast" v-model="referenceIdLast" :disabled="busy || !authorized"><option value="">请选择尾帧</option><option v-for="item in referenceCandidates" :key="item.id" :value="String(item.id)">{{ item.filename }}</option></select></div>
        <div><label for="imagePrompt">{{ promptLabel }}</label><el-input id="imagePrompt" v-model="prompt" type="textarea" maxlength="8000" :disabled="busy || !authorized" /></div>
        <el-button native-type="submit" type="primary" :disabled="busy || !authorized || !selectedModel || !prompt.trim() || (needsReference && !referenceId) || (needsSecondFrame && !referenceIdLast)">{{ isVideoModel ? '生成视频' : '生成图片' }}</el-button>
        <el-button v-if="running" :disabled="cancelling" @click="cancelGeneration">{{ cancelling ? '正在确认取消' : '取消生成' }}</el-button>
      </form>
      <p class="muted">{{ hint }}</p>
      <p v-if="generationStatus" role="status">{{ generationStatus }}</p>
      <el-table :data="items" empty-text="当前项目还没有文件">
        <el-table-column prop="filename" label="文件名" min-width="180" />
        <el-table-column label="大小"><template #default="{ row }">{{ formatBytes(row.byteSize) }}</template></el-table-column>
        <el-table-column prop="createdAt" label="上传时间" min-width="180" />
        <el-table-column label="操作" width="160"><template #default="{ row }"><el-button text :disabled="busy" @click="preview(String(row.id))">查看</el-button><el-button text :disabled="busy" @click="download(String(row.id))">下载</el-button></template></el-table-column>
      </el-table>
      <section v-if="previewItem" aria-label="文件预览" class="filePreview"><header><h3>{{ previewItem.filename }}</h3><el-button text @click="closePreview">关闭预览</el-button></header><img v-if="previewUrl && previewKind === 'image'" :src="previewUrl" :alt="previewItem.filename" /><video v-else-if="previewUrl && previewKind === 'video'" :src="previewUrl" controls playsinline preload="metadata" /><p v-else>此类型请下载后查看。</p><p class="muted">SHA-256：{{ previewItem.sha256 }}</p></section>
    </section>
  </main>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import client, { apiFetch, cancelRun } from "@huimeng/api/client";
import type { ProjectMedia, Model, MediaResult, Run } from "@huimeng/contracts/platform";
import { readAll, adminError } from "@/lib/teamAdmin";
import { useWorkspaceStore } from "@/stores/workspace";
import "@/components/team/team.scss";
import WorkspaceDocuments from "./WorkspaceDocuments.vue";
const route = useRoute(), workspace = useWorkspaceStore();
const projectName = ref(""), items = ref<ProjectMedia[]>([]), busy = ref(false), error = ref(""), authorized = ref(false);
const selected = ref<File>(), uploadInput = ref<HTMLInputElement>(), previewItem = ref<ProjectMedia>(), previewUrl = ref("");
// 预览区分图片与视频：视频走内联播放，超过 128 MiB 只取首字节占位，避免把大文件读进内存。
const previewKind = ref<"image" | "video" | "">("");
const models = ref<Model[]>([]), selectedModel = ref(""), prompt = ref(""), running = ref(""), cancelling = ref(false), generationStatus = ref("");
// 规格与参考图始终显式上送；服务端再按模型校验，省略时才回落到 1K、1:1。
const imageSizes = ["1K", "0.5MP", "2K"], imageRatios = ["1:1", "2:3", "3:2", "3:4", "4:3", "9:16", "16:9", "21:9"];
// H3 五模式：分辨率、比例与时长由服务端固定校验，页面只提供合法枚举。
const videoSizes = ["352p", "480p", "544p"], videoRatios = ["16:9", "9:16", "1:1", "4:3", "3:4"], videoDurations = [5, 8, 10, 15];
const videoModels = new Set(["h3T2v", "h3I2v", "h3Fl2v", "h3L2v", "h3R2v"]);
const videoModes: Record<string, string> = { h3T2v: "t2v", h3I2v: "i2v", h3Fl2v: "fl2v", h3L2v: "l2v", h3R2v: "r2v" };
const selectedSize = ref("1K"), selectedRatio = ref("1:1"), referenceId = ref(""), referenceIdLast = ref(""), videoDuration = ref(5);
const isEditModel = computed(() => selectedModel.value === "qwenImage21Edit");
const isVideoModel = computed(() => videoModels.has(selectedModel.value));
const videoMode = computed(() => videoModes[selectedModel.value] ?? "");
const sizeOptions = computed(() => (isVideoModel.value ? videoSizes : imageSizes));
const ratioOptions = computed(() => (isVideoModel.value ? videoRatios : imageRatios));
const needsReference = computed(() => isEditModel.value || ["i2v", "l2v", "fl2v", "r2v"].includes(videoMode.value));
const needsSecondFrame = computed(() => videoMode.value === "fl2v");
const referenceLabel = computed(() => (isVideoModel.value ? (videoMode.value === "l2v" ? "尾帧（项目内 PNG）" : videoMode.value === "r2v" ? "参考图（项目内 PNG）" : "首帧（项目内 PNG）") : "参考图（项目内 PNG）"));
const promptLabel = computed(() => (isEditModel.value ? "编辑指令" : isVideoModel.value ? "视频描述" : "图片描述"));
const hint = computed(() => isVideoModel.value
  ? "当前选中本地 MiniMax H3：352p、480p、544p 与 5/8/10/15 秒，五种模式按所选模型决定参考图数量；结果页显示实际执行的规格。生成结果自动归入本项目；未获批模型无法调用。"
  : "当前接通 Qwen 文生图与单图编辑：1K、0.5MP、2K 与 8 种画幅，省略时按 1K、1:1 提交；结果页显示实际执行的规格。生成结果自动归入本项目；未获批模型无法调用。");

// 切换模型时把规格拉回该模型支持的默认值，避免带着图片规格去请求视频。
watch(selectedModel, () => {
  if (isVideoModel.value) { selectedSize.value = "352p"; selectedRatio.value = "16:9"; }
  else { selectedSize.value = "1K"; selectedRatio.value = "1:1"; }
  referenceId.value = "";
  referenceIdLast.value = "";
});
const referenceCandidates = computed(() => items.value.filter(item => item.filename.toLowerCase().endsWith(".png")));
let lifetime = new AbortController();
const projectId = () => String(route.params.projectId ?? "");
const base = () => `/api/v1/projects/${encodeURIComponent(projectId())}/media`;
function closePreview() { if (previewUrl.value) URL.revokeObjectURL(previewUrl.value); previewUrl.value = ""; previewKind.value = ""; previewItem.value = undefined; }
function formatBytes(size: number) { return size < 1024 ? `${size} B` : size < 1048576 ? `${(size / 1024).toFixed(1)} KiB` : `${(size / 1048576).toFixed(1)} MiB`; }
async function refresh() {
  closePreview(); error.value = ""; authorized.value = false; items.value = [];
  const signal = lifetime.signal;
  try { const rows = await readAll<ProjectMedia>(base(), signal); const result = await client.get<{ items: Model[] }>("/api/v1/models", { signal }); signal.throwIfAborted(); items.value = rows; models.value = result.data.items.filter(model => (model.providerId === "qwenImage21Local" && (model.modelId === "qwenImage21Text" || model.modelId === "qwenImage21Edit")) || (model.providerId === "h3Local3060" && videoModels.has(model.modelId))); if (!models.value.some(model => model.modelId === selectedModel.value)) selectedModel.value = models.value[0]?.modelId ?? ""; authorized.value = true; }
  catch (ex) { if (!signal.aborted) error.value = adminError(ex, "文件列表加载失败，请重试。"); }
}
// 运行失败原因由服务端给固定词表代码，页面负责翻成人话；服务端不下发异常文本。
// 前缀区分三类：USER_ 操作问题、MODEL_ 模型服务异常、EXECUTION_ 任务执行异常。
const reasonTexts: Record<string, string> = {
  USER_INVALID_REQUEST: "请求参数或参考图不符合要求，请调整后重试。",
  USER_NOT_PERMITTED: "当前账号或模型授权不足，请联系管理员开通该模型或加入项目。",
  USER_CAPACITY_EXCEEDED: "项目已有五个活跃任务，请等待其中一个结束后再提交。",
  MODEL_UNAVAILABLE: "执行器不可用或未能产出结果，请稍后重试或联系管理员检查本地模型服务。",
  MODEL_TIMEOUT: "模型超时未返回结果，请稍后重试。",
  EXECUTION_STORAGE_UNAVAILABLE: "产物入库失败（媒体存储不可用），请联系管理员排查存储后再重试。",
  EXECUTION_BUSY: "服务繁忙，请稍后重试。",
  EXECUTION_LOST: "执行状态待核查，未确认结束前保留名额。",
  EXECUTION_INTERRUPTED: "服务重启导致任务中断，请重新提交。",
  EXECUTION_FAILED: "任务执行出错，请稍后重试。",
  EXECUTION_INTERNAL: "服务内部异常，请联系管理员查看日志。",
};
function reasonText(reason?: string | null) {
  if (!reason) return "";
  const group = reason.startsWith("USER_") ? "操作问题" : reason.startsWith("MODEL_") ? "模型服务异常" : "任务执行异常";
  return `${group}：${reasonTexts[reason] ?? "请联系管理员查看服务端日志。"}`;
}
function runFailureText(run?: Run) {
  if (!run) return "生成未完成，请重试。";
  const detail = reasonText(run.reason);
  if (run.state === "LOST") return detail || "执行状态待核查，未确认结束前保留名额。";
  return detail ? `生成失败。${detail}` : "生成未完成，请检查模型状态后重试。";
}
function requestFailureText(status: number, result: { message?: string }) {
  const base = result.message?.trim();
  if (status === 429) return `${base ?? "项目生成名额已满"}（每个项目最多 5 个活跃任务，请等待其中一个结束后再提交。）`;
  if (status === 403) return `${base ?? "无权执行此操作"}（可能是模型未启用，或你不是该项目成员。）`;
  if (status === 503) return `${base ?? "服务暂不可用"}（执行器或媒体存储未就绪，请稍后重试。）`;
  return base ?? "生成请求未获授权";
}
async function generate() {
  if (busy.value || !selectedModel.value || !prompt.value.trim()) return;
  const edit = isEditModel.value;
  if ((edit || needsReference.value) && !referenceId.value) { error.value = "该模型必须选择项目内的 PNG 参考图。"; return; }
  if (needsSecondFrame.value && !referenceIdLast.value) { error.value = "首尾帧模式必须选择尾帧。"; return; }
  const signal = lifetime.signal, id = projectId(); busy.value = true; error.value = ""; cancelling.value = false;
  const frames = !isVideoModel.value ? undefined
    : needsSecondFrame.value ? [referenceId.value, referenceIdLast.value]
    : needsReference.value ? [referenceId.value] : [];
  const body = {
    providerId: isVideoModel.value ? "h3Local3060" : "qwenImage21Local",
    modelId: selectedModel.value,
    mediaType: isVideoModel.value ? "video" : "image",
    prompt: prompt.value.trim(),
    size: selectedSize.value,
    ratio: selectedRatio.value,
    images: frames?.length ? frames.map(value => ({ path: `.huimeng-media/${value}.png`, mimeType: "image/png" })) : undefined,
    duration: isVideoModel.value ? videoDuration.value : undefined,
  };
  generationStatus.value = `正在提交${isVideoModel.value ? "视频" : "图片"}生成请求（${isVideoModel.value ? `${videoDuration.value} 秒 · ` : ""}${selectedSize.value} · ${selectedRatio.value}）…`;
  try {
    const response = await apiFetch(`/api/v1/projects/${encodeURIComponent(id)}/creative/ai/media/generate`, { method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID() }, body: JSON.stringify(body), signal });
    if (!response.ok) { const result = await response.json() as { message?: string }; throw new Error(requestFailureText(response.status, result)); }
    running.value = response.headers.get("X-Run-Id") ?? ""; generationStatus.value = "模型正在生成，结果将保存到当前项目…";
    const result = await response.json() as MediaResult; signal.throwIfAborted();
    if (result.code !== 200) {
      const run = running.value ? (await client.get<Run>(`/api/v1/projects/${encodeURIComponent(id)}/runs/${running.value}`, { signal })).data : undefined;
      if (run?.state === "CANCELLED") { generationStatus.value = "已确认取消，名额已释放。"; return; }
      throw new Error(runFailureText(run));
    }
    const spec = (result as { code: 200; data: { spec?: string }[] }).data?.[0]?.spec;
    await refresh(); if (!signal.aborted) generationStatus.value = spec ? `${isVideoModel.value ? "视频" : "图片"}已生成并保存到当前项目（实际规格 ${spec}）。` : "生成结果已保存到当前项目。";
  } catch (ex) { if (!signal.aborted) { error.value = ex instanceof Error ? ex.message : "生成失败，请检查权限和模型状态。"; generationStatus.value = ""; } }
  finally { if (!signal.aborted) { busy.value = false; running.value = ""; cancelling.value = false; } }
}
async function cancelGeneration() {
  const signal = lifetime.signal;
  if (!running.value || cancelling.value) return;
  cancelling.value = true;
  try { await cancelRun(running.value); if (!signal.aborted) generationStatus.value = "正在等待执行器确认取消，名额暂不释放…"; }
  catch (ex) { if (!signal.aborted) { error.value = adminError(ex, "取消请求未完成，请查询运行状态。"); cancelling.value = false; } }
}
function selectFile(event: Event) { selected.value = (event.target as HTMLInputElement).files?.[0]; }
async function upload() {
  const file = selected.value;
  const signal = lifetime.signal;
  if (!file || busy.value) return;
  if (file.size > 512 * 1024 * 1024) { error.value = "文件超过 512 MiB，请选择较小的文件。"; return; }
  busy.value = true; error.value = "";
  try { await client.post(base(), file, { params: { filename: file.name }, headers: { "Content-Type": "application/octet-stream" }, signal }); signal.throwIfAborted(); selected.value = undefined; if (uploadInput.value) uploadInput.value.value = ""; await refresh(); if (!signal.aborted) ElMessage.success("文件已上传。"); }
  catch (ex) { if (!signal.aborted) error.value = adminError(ex, "上传失败，请刷新列表后检查。"); }
  finally { if (!signal.aborted) busy.value = false; }
}
async function preview(id: string) {
  const item = items.value.find(value => value.id === id); if (!item) return;
  const signal = lifetime.signal;
  closePreview(); error.value = ""; busy.value = true;
  try {
    const extension = item.filename.split(".").pop()?.toLowerCase();
    const types: Record<string, string> = { png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", webp: "image/webp", gif: "image/gif", avif: "image/avif" };
    const videoTypes: Record<string, string> = { mp4: "video/mp4", webm: "video/webm", mov: "video/quicktime", m4v: "video/x-m4v" };
    const imageType = extension ? types[extension] : undefined;
    const videoType = extension ? videoTypes[extension] : undefined;
    const inline = (imageType && item.byteSize <= 16 * 1024 * 1024) || (videoType && item.byteSize <= 128 * 1024 * 1024);
    const { data } = await client.get<Blob>(`${base()}/${encodeURIComponent(item.id)}/content`, { responseType: "blob", signal, headers: !inline && item.byteSize > 0 ? { Range: "bytes=0-0" } : undefined });
    signal.throwIfAborted(); previewItem.value = item;
    if (inline) {
      previewKind.value = imageType ? "image" : "video";
      previewUrl.value = URL.createObjectURL(new Blob([data], { type: (imageType ?? videoType) as string }));
    }
  } catch (ex) { if (!signal.aborted) error.value = adminError(ex, "无法读取文件，请刷新权限后重试。"); }
  finally { if (!signal.aborted) busy.value = false; }
}
async function download(id: string) {
  const item = items.value.find(value => value.id === id); if (!item) return;
  const signal = lifetime.signal;
  busy.value = true; error.value = "";
  try {
    const { data } = await client.get<Blob>(`${base()}/${encodeURIComponent(item.id)}/content`, { responseType: "blob", signal });
    signal.throwIfAborted();
    const url = URL.createObjectURL(data), link = document.createElement("a"); link.href = url; link.download = item.filename;
    document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (ex) { if (!signal.aborted) error.value = adminError(ex, "下载失败，请检查当前项目权限。"); }
  finally { if (!signal.aborted) busy.value = false; }
}
watch(() => route.params.projectId, async () => {
  lifetime.abort(); lifetime = new AbortController(); const signal = lifetime.signal;
  items.value = []; projectName.value = ""; authorized.value = false; closePreview(); error.value = ""; busy.value = false; selected.value = undefined; models.value = []; selectedModel.value = ""; prompt.value = ""; running.value = ""; cancelling.value = false; generationStatus.value = ""; selectedSize.value = "1K"; selectedRatio.value = "1:1"; referenceId.value = ""; referenceIdLast.value = ""; videoDuration.value = 5;
  try { await workspace.openProject(projectId(), projectId(), signal); signal.throwIfAborted(); projectName.value = workspace.project?.name ?? ""; await refresh(); }
  catch (ex) { if (!signal.aborted) error.value = adminError(ex, "无法打开项目文件，请重新选择授权项目。"); }
}, { immediate: true });
onBeforeUnmount(() => { lifetime.abort(); closePreview(); selected.value = undefined; });
</script>
<style scoped>
.filePreview { margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--el-border-color); }
.filePreview header { display: flex; justify-content: space-between; align-items: center; }
.filePreview img { max-width: 100%; max-height: 520px; object-fit: contain; }
.filePreview video { max-width: 100%; max-height: 520px; background: #000; }
.filePreview p { overflow-wrap: anywhere; }
</style>
