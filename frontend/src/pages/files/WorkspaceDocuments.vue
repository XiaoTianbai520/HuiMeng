<template>
  <section class="teamPanel documents" aria-label="项目文档">
    <header class="panelHeading"><div><h2>项目文档</h2><p>编辑剧本和原格式文件。保存时检查是否已有其他成员修改。</p></div><el-button :disabled="busy" @click="refresh">刷新文档</el-button></header>
    <p v-if="error" role="alert" class="panelError">{{ error }}</p>
    <div class="documentToolbar"><span aria-label="当前目录">{{ folder || '项目根目录' }}</span><el-button v-if="folder" :disabled="busy" @click="navigate(parentFolder)">返回上级</el-button><el-button :disabled="busy || !authorized" @click="begin('create')">新建文档</el-button><el-button :disabled="busy || !authorized" @click="begin('mkdir')">新建文件夹</el-button></div>
    <form v-if="operation" class="inlineForm operation" :aria-label="operationTitle" @submit.prevent="submitOperation">
      <div v-if="operation !== 'remove'"><label for="workspaceTarget">{{ operation === 'create' || operation === 'mkdir' ? '名称' : '目标路径（项目内）' }}</label><el-input id="workspaceTarget" v-model="target" :disabled="busy" maxlength="2048" /></div>
      <div v-else><p>将删除“{{ operationEntry?.name }}”。请确认已保留需要的内容。</p><el-checkbox v-if="operationEntry?.type === 'directory'" v-model="recursive">连同目录内文件一起删除</el-checkbox></div>
      <el-button native-type="submit" :type="operation === 'remove' ? 'danger' : 'primary'" :disabled="busy || (operation !== 'remove' && !target.trim())">{{ operationTitle }}</el-button><el-button :disabled="busy" @click="cancelOperation">取消操作</el-button>
    </form>
    <el-table :data="entries" :empty-text="busy ? '正在读取目录…' : authorized ? '当前目录没有文档，可新建文档或文件夹。' : '当前目录不可访问，请重新选择已授权项目。'">
      <el-table-column label="名称" min-width="210"><template #default="{ row }"><button class="fileName" :aria-label="`打开 ${row.name}`" :disabled="busy" @click="openByPath(String(row.path))">{{ row.name }}<span v-if="row.type === 'directory'"> /</span></button></template></el-table-column>
      <el-table-column label="类型" width="90"><template #default="{ row }">{{ row.type === 'directory' ? '文件夹' : '文件' }}</template></el-table-column>
      <el-table-column label="操作" min-width="240"><template #default="{ row }"><el-button text :aria-label="`复制 ${row.name}`" :disabled="busy" @click="beginEntry('copy', String(row.path))">复制</el-button><el-button text :aria-label="`重命名 ${row.name}`" :disabled="busy" @click="beginEntry('rename', String(row.path))">重命名</el-button><el-button text type="danger" :aria-label="`删除 ${row.name}`" :disabled="busy" @click="beginEntry('remove', String(row.path))">删除</el-button></template></el-table-column>
    </el-table>
    <section v-if="opened" class="documentEditor" aria-label="文档编辑器">
      <header><h3>{{ opened }}</h3><span role="status">{{ conflict ? '保存已暂停' : dirty ? '有未保存的修改' : '已与文件同步' }}</span></header>
      <p v-if="conflict" role="alert" class="conflict">文件已被其他成员修改或移除。你的修改仍保留；保存已暂停，请重新读取后处理，或另存为副本。</p>
      <label for="workspaceContent">文档内容</label><textarea id="workspaceContent" v-model="text" :disabled="busy" spellcheck="false" />
      <div class="editorActions"><el-button type="primary" :disabled="busy || !dirty || conflict || !authorized" @click="save">保存文档</el-button><el-button :disabled="busy" @click="reloadDocument">重新读取文件</el-button><el-button :disabled="busy || !authorized" @click="saveAs">另存为副本</el-button><el-button :disabled="busy" @click="closeEditor">关闭文档</el-button></div>
    </section>
  </section>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { onBeforeRouteLeave, onBeforeRouteUpdate } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import client from "@huimeng/api/client";
import { context, subscribeContext } from "@huimeng/api/context";
import type { Snapshot, FileListResult, FileOperationResult, WriteResult } from "@huimeng/contracts/platform";
type Entry = { name: string; path: string; type: "file" | "directory" };
type Operation = "create" | "mkdir" | "copy" | "rename" | "remove";
const props = defineProps<{ projectId: string }>();
const entries = ref<Entry[]>([]), folder = ref(""), busy = ref(false), authorized = ref(false), error = ref("");
const opened = ref(""), snapshot = ref<Snapshot>(), text = ref(""), conflict = ref(false);
const operation = ref<Operation>(), operationEntry = ref<Entry>(), target = ref(""), recursive = ref(false);
const creationText = ref("");
const titles: Record<Operation,string> = { create: "创建文档", mkdir: "创建文件夹", copy: "复制文件", rename: "重命名", remove: "确认删除" };
const operationTitle = computed(() => operation.value ? titles[operation.value] : "");
const dirty = computed(() => !!snapshot.value && text.value !== snapshot.value.text);
const parentFolder = computed(() => folder.value.split("/").slice(0,-1).join("/"));
let lifetime = new AbortController(), actorId = context().actor?.id;
const base = () => `/api/v1/projects/${encodeURIComponent(props.projectId)}/creative/workspaces/files`;
const joined = (name: string) => folder.value ? `${folder.value}/${name}` : name;
function clearEditor() { opened.value = ""; snapshot.value = undefined; text.value = ""; conflict.value = false; }
function failure(ex: unknown, fallback: string) {
  if (client.isAxiosError<{ code?: string; message?: string }>(ex)) {
    const code = ex.response?.data.code;
    if (["PROJECT_FORBIDDEN", "CAPABILITY_FORBIDDEN", "UNAUTHENTICATED"].includes(code ?? "")) {
      authorized.value = false; entries.value = []; clearEditor(); operation.value = undefined;
      folder.value = ""; operationEntry.value = undefined; creationText.value = ""; target.value = "";
    }
    return ex.response?.data.message || fallback;
  }
  return fallback;
}
async function allowDiscard() {
  if (!dirty.value) return true;
  try { await ElMessageBox.confirm("继续会丢弃本页尚未保存的修改。可取消后先保存或另存为副本。", "保留未保存的修改", { confirmButtonText: "丢弃修改并继续", cancelButtonText: "返回编辑", type: "warning" }); return true; }
  catch { return false; }
}
async function refresh() {
  const signal = lifetime.signal; busy.value = true; error.value = "";
  try {
    const { data } = await client.get<FileListResult>(`${base()}/list`, { params: { path: folder.value }, signal });
    signal.throwIfAborted(); entries.value = data.data.entries; authorized.value = true;
  } catch (ex) { if (!signal.aborted) { entries.value = []; error.value = failure(ex, "无法读取文档目录，请检查当前项目权限。"); } }
  finally { if (!signal.aborted) busy.value = false; }
}
async function navigate(path: string) {
  if (!await allowDiscard()) return;
  clearEditor(); operation.value = undefined; folder.value = path; await refresh();
}
async function load(path: string) {
  const signal = lifetime.signal; busy.value = true; error.value = "";
  try {
    const { data } = await client.get<{ data: Snapshot }>(`${base()}/read`, { params: { path, snapshot: true }, signal });
    signal.throwIfAborted(); snapshot.value = data.data; text.value = data.data.text; opened.value = path; conflict.value = false;
  } catch (ex) { if (!signal.aborted) error.value = failure(ex, "该文件无法作为文档打开，请使用下载查看。"); }
  finally { if (!signal.aborted) busy.value = false; }
}
async function openEntry(entry: Entry) {
  if (entry.type === "directory") return navigate(entry.path);
  if (await allowDiscard()) await load(entry.path);
}
async function openByPath(path: string) { const entry = entries.value.find(value => value.path === path); if (entry) await openEntry(entry); }
async function beginEntry(kind: Operation, path: string) { const entry = entries.value.find(value => value.path === path); if (entry) await begin(kind, entry); }
async function reloadDocument() { if (opened.value && await allowDiscard()) await load(opened.value); }
async function closeEditor() { if (await allowDiscard()) clearEditor(); }
async function save() {
  if (!snapshot.value || !opened.value || conflict.value || busy.value) return;
  const signal = lifetime.signal, path = opened.value, draft = text.value;
  busy.value = true; error.value = "";
  try {
    const { data } = await client.put<WriteResult>(`${base()}/write`, draft, { params: { path, expectedRevision: snapshot.value.revision, encoding: snapshot.value.encoding }, headers: { "Content-Type": "application/octet-stream" }, signal });
    signal.throwIfAborted(); snapshot.value = { ...snapshot.value, text: draft, revision: data.data.revision }; ElMessage.success("文档已保存。");
  } catch (ex) {
    if (!signal.aborted) {
      if (client.isAxiosError<{code?:string}>(ex) && ex.response?.data.code === "REVISION_CONFLICT") conflict.value = true;
      error.value = failure(ex, "保存未确认，请重新读取后核对。");
    }
  } finally { if (!signal.aborted) busy.value = false; }
}
async function begin(kind: Operation, entry?: Entry, draft?: string) {
  if (kind === "create" && draft === undefined && !await allowDiscard()) return;
  operation.value = kind; operationEntry.value = entry; error.value = ""; recursive.value = false;
  creationText.value = draft ?? "";
  target.value = entry ? entry.path : "";
  if (kind === "copy" && entry) target.value = entry.type === "directory" ? `${entry.path}-副本` : copyName(entry.path);
}
function cancelOperation() { operation.value = undefined; operationEntry.value = undefined; creationText.value = ""; target.value = ""; error.value = ""; }
function copyName(path: string) { const dot = path.lastIndexOf("."); return dot > path.lastIndexOf("/") + 1 ? `${path.slice(0,dot)}-副本${path.slice(dot)}` : `${path}-副本`; }
async function saveAs() { await begin("create", undefined, text.value); target.value = copyName(opened.value.split("/").at(-1) ?? "文档"); }
async function submitOperation() {
  const kind = operation.value, entry = operationEntry.value;
  if (!kind || busy.value) return;
  const signal = lifetime.signal; busy.value = true; error.value = "";
  try {
    if (kind === "create") {
      const path = joined(target.value.trim()), draft = creationText.value;
      if (target.value.includes("/")) throw new Error("新建文档请填写名称，先进入目标文件夹。");
      const { data } = await client.put<WriteResult>(`${base()}/write`, draft, { params: { path, exclusive: true, encoding: "utf-8" }, headers: { "Content-Type": "application/octet-stream" }, signal });
      signal.throwIfAborted(); opened.value = path; snapshot.value = { text: draft, encoding: "utf-8", revision: data.data.revision }; text.value = draft; conflict.value = false;
    } else if (kind === "mkdir") {
      await client.post<FileOperationResult>(`${base()}/mkdir`, { path: joined(target.value.trim()) }, { signal });
    } else if (kind === "remove" && entry) {
      await client.delete<FileOperationResult>(`${base()}/remove`, { data: { path: entry.path, recursive: recursive.value }, signal });
      if (opened.value === entry.path || opened.value.startsWith(entry.path+"/")) clearEditor();
    } else if (entry) {
      const destination = target.value.trim();
      await client.post<FileOperationResult>(`${base()}/${kind}`, { path: entry.path, target: destination }, { signal });
      if (kind === "rename" && (opened.value === entry.path || opened.value.startsWith(entry.path+"/"))) opened.value = destination+opened.value.slice(entry.path.length);
    }
    signal.throwIfAborted(); operation.value = undefined; await refresh(); if (!signal.aborted) ElMessage.success("文件操作已完成。");
  } catch (ex) { if (!signal.aborted) error.value = ex instanceof Error && !client.isAxiosError(ex) ? ex.message : failure(ex, "操作未确认完成，请刷新目录核对。"); }
  finally { if (!signal.aborted) busy.value = false; }
}
const unsubscribe = subscribeContext(() => {
  if (context().actor?.id === actorId) return;
  actorId = context().actor?.id; lifetime.abort(); lifetime = new AbortController(); entries.value = []; authorized.value = false; clearEditor(); operation.value = undefined; creationText.value = ""; target.value = ""; folder.value = ""; busy.value = false; error.value = "";
});
watch(() => props.projectId, async () => {
  lifetime.abort(); lifetime = new AbortController(); entries.value = []; folder.value = ""; busy.value = false; clearEditor(); operation.value = undefined; await refresh();
}, { immediate: true });
onBeforeRouteLeave(() => allowDiscard());
onBeforeRouteUpdate((to, from) => to.params.projectId === from.params.projectId || allowDiscard());
onBeforeUnmount(() => { lifetime.abort(); unsubscribe(); clearEditor(); });
</script>
<style scoped>
.documents { margin-bottom: 24px; }
.documentToolbar, .editorActions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin: 16px 0; }
.documentToolbar > span { margin-right: auto; overflow-wrap: anywhere; }
.fileName { border: 0; padding: 8px 0; text-align: left; color: var(--el-text-color-primary); background: transparent; font: inherit; cursor: pointer; overflow-wrap: anywhere; }
.fileName:hover { text-decoration: underline; }
.fileName:focus-visible, textarea:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.documentEditor { border-top: 1px solid var(--el-border-color); padding-top: 18px; margin-top: 20px; }
.documentEditor header { display: flex; justify-content: space-between; gap: 16px; align-items: center; }
.documentEditor h3 { overflow-wrap: anywhere; margin: 0 0 16px; }
.documentEditor header span { font-size: 13px; color: var(--el-text-color-secondary); flex-shrink: 0; }
textarea { box-sizing: border-box; width: 100%; min-height: 280px; resize: vertical; padding: 12px; font: 14px/1.7 ui-monospace, monospace; border: 1px solid var(--el-border-color); background: var(--el-bg-color); color: var(--el-text-color-primary); border-radius: 4px; }
.conflict { padding: 12px; color: var(--el-color-warning-dark-2); background: var(--el-color-warning-light-9); }
@media(max-width:760px) { .documentEditor header { align-items: flex-start; flex-direction: column; gap: 0; } .editorActions .el-button { margin-left: 0; } }
</style>
