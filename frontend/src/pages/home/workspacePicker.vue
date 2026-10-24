<template>
  <el-button v-if="!hideTrigger" text :disabled="disabled" @click="chooseDirectory">选择项目</el-button>
  <el-dialog v-model="visible" title="选择授权项目" width="min(560px, 92vw)" @closed="finish(null)">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-empty v-if="!loading && !store.projectList.length" description="暂无授权项目，请联系管理员" />
    <el-button v-for="project in store.projectList" :key="project.directory" class="projectChoice" @click="finish(project.directory)">{{ project.name }}</el-button>
    <template #footer><el-button :loading="loading" @click="refresh">刷新</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { onBeforeUnmount, ref } from "vue";
import { useWorkspaceStore } from "@/stores/workspace";
defineProps<{ disabled?: boolean; hideTrigger?: boolean }>();
const selected = defineModel<string>({ default: "" });
const store = useWorkspaceStore();
const visible = ref(false);
const loading = ref(false);
const error = ref("");
let pending: ((value: string | null) => void) | undefined;
async function refresh() {
  loading.value = true;
  error.value = "";
  try { await store.loadProjects(); } catch { error.value = "无法读取项目，请重试或重新登录。"; }
  finally { loading.value = false; }
}
async function chooseDirectory(): Promise<string | null> {
  if (pending) return null;
  visible.value = true;
  void refresh();
  return new Promise(resolve => { pending = resolve; });
}
function finish(value: string | null) {
  if (value) selected.value = value;
  pending?.(value);
  pending = undefined;
  visible.value = false;
}
onBeforeUnmount(() => finish(null));
defineExpose({ chooseDirectory });
</script>
<style scoped>.projectChoice { display: block; margin: 12px 0; width: 100%; text-align: left; }</style>
