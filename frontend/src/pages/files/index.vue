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
      <form aria-label="受控图片生成" class="inlineForm" @submit.prevent="generate">
        <div><label for="imageModel">获批文生图模型</label><select id="imageModel" v-model="selectedModel" :disabled="busy || !authorized"><option value="">请选择模型</option><option v-for="model in models" :key="model.modelId" :value="model.modelId">{{ model.label }}</option></select></div>
        <div><label for="imagePrompt">图片描述</label><el-input id="imagePrompt" v-model="prompt" type="textarea" maxlength="8000" :disabled="busy || !authorized" /></div>
        <el-button native-type="submit" type="primary" :disabled="busy || !authorized || !selectedModel || !prompt.trim()">生成图片</el-button>
        <el-button v-if="running" :disabled="cancelling" @click="cancelGeneration">{{ cancelling ? '正在确认取消' : '取消生成' }}</el-button>
      </form>
      <p class="muted">当前接通 Qwen 文生图 1K、1:1。生成结果自动归入本项目；未获批模型无法调用。</p>
      <p v-if="generationStatus" role="status">{{ generationStatus }}</p>
      <el-table :data="items" empty-text="当前项目还没有文件">
        <el-table-column prop="filename" label="文件名" min-width="180" />
        <el-table-column label="大小"><template #default="{ row }">{{ formatBytes(row.byteSize) }}</template></el-table-column>
        <el-table-column prop="createdAt" label="上传时间" min-width="180" />
        <el-table-column label="操作" width="160"><template #default="{ row }"><el-button text :disabled="busy" @click="preview(String(row.id))">查看</el-button><el-button text :disabled="busy" @click="download(String(row.id))">下载</el-button></template></el-table-column>
      </el-table>
      <section v-if="previewItem" aria-label="文件预览" class="filePreview"><header><h3>{{ previewItem.filename }}</h3><el-button text @click="closePreview">关闭预览</el-button></header><img v-if="previewUrl" :src="previewUrl" :alt="previewItem.filename" /><p v-else>此类型请下载后查看。</p><p class="muted">SHA-256：{{ previewItem.sha256 }}</p></section>
    </section>
  </main>
</template>
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
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
const models = ref<Model[]>([]), selectedModel = ref(""), prompt = ref(""), running = ref(""), cancelling = ref(false), generationStatus = ref("");
let lifetime = new AbortController();
const projectId = () => String(route.params.projectId ?? "");
const base = () => `/api/v1/projects/${encodeURIComponent(projectId())}/media`;
function closePreview() { if (previewUrl.value) URL.revokeObjectURL(previewUrl.value); previewUrl.value = ""; previewItem.value = undefined; }
function formatBytes(size: number) { return size < 1024 ? `${size} B` : size < 1048576 ? `${(size / 1024).toFixed(1)} KiB` : `${(size / 1048576).toFixed(1)} MiB`; }
async function refresh() {
  closePreview(); error.value = ""; authorized.value = false; items.value = [];
  const signal = lifetime.signal;
  try { const rows = await readAll<ProjectMedia>(base(), signal); const result = await client.get<{ items: Model[] }>("/api/v1/models", { signal }); signal.throwIfAborted(); items.value = rows; models.value = result.data.items.filter(model => model.providerId === "qwenImage21Local" && model.modelId === "qwenImage21Text"); if (!models.value.some(model => model.modelId === selectedModel.value)) selectedModel.value = models.value[0]?.modelId ?? ""; authorized.value = true; }
  catch (ex) { if (!signal.aborted) error.value = adminError(ex, "文件列表加载失败，请重试。"); }
}
async function generate() {
  if (busy.value || !selectedModel.value || !prompt.value.trim()) return;
  const signal = lifetime.signal, id = projectId(); busy.value = true; error.value = ""; cancelling.value = false; generationStatus.value = "正在提交生成请求…";
  try {
    const response = await apiFetch(`/api/v1/projects/${encodeURIComponent(id)}/creative/ai/media/generate`, { method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID() }, body: JSON.stringify({ providerId: "qwenImage21Local", modelId: selectedModel.value, mediaType: "image", prompt: prompt.value.trim(), size: "1K", ratio: "1:1" }), signal });
    if (!response.ok) { const result = await response.json() as { message?: string }; throw new Error(result.message ?? "生成请求未获授权"); }
    running.value = response.headers.get("X-Run-Id") ?? ""; generationStatus.value = "模型正在生成，结果将保存到当前项目…";
    const result = await response.json() as MediaResult; signal.throwIfAborted();
    if (result.code !== 200) {
      const state = running.value ? (await client.get<Run>(`/api/v1/projects/${encodeURIComponent(id)}/runs/${running.value}`, { signal })).data.state : "FAILED";
      if (state === "CANCELLED") { generationStatus.value = "已确认取消，名额已释放。"; return; }
      throw new Error(state === "LOST" ? "执行状态待核查，未确认结束前保留名额。" : "生成未完成，请检查模型状态后重试。");
    }
    await refresh(); if (!signal.aborted) generationStatus.value = "图片已生成并保存到当前项目。";
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
    const imageType = extension && item.byteSize <= 16 * 1024 * 1024 ? types[extension] : undefined;
    const { data } = await client.get<Blob>(`${base()}/${encodeURIComponent(item.id)}/content`, { responseType: "blob", signal, headers: !imageType && item.byteSize > 0 ? { Range: "bytes=0-0" } : undefined });
    signal.throwIfAborted(); previewItem.value = item;
    if (imageType) previewUrl.value = URL.createObjectURL(new Blob([data], { type: imageType }));
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
  items.value = []; projectName.value = ""; authorized.value = false; closePreview(); error.value = ""; busy.value = false; selected.value = undefined; models.value = []; selectedModel.value = ""; prompt.value = ""; running.value = ""; cancelling.value = false; generationStatus.value = "";
  try { await workspace.openProject(projectId(), projectId(), signal); signal.throwIfAborted(); projectName.value = workspace.project?.name ?? ""; await refresh(); }
  catch (ex) { if (!signal.aborted) error.value = adminError(ex, "无法打开项目文件，请重新选择授权项目。"); }
}, { immediate: true });
onBeforeUnmount(() => { lifetime.abort(); closePreview(); selected.value = undefined; });
</script>
<style scoped>
.filePreview { margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--el-border-color); }
.filePreview header { display: flex; justify-content: space-between; align-items: center; }
.filePreview img { max-width: 100%; max-height: 520px; object-fit: contain; }
.filePreview p { overflow-wrap: anywhere; }
</style>
