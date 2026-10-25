<template>
  <section class="teamPanel" aria-label="项目与成员管理">
    <header class="panelHeading"><div><h2>项目与成员</h2><p>管理员管理项目，加入成员后才能访问创作内容。</p></div><el-button :loading="busy" @click="refresh">刷新项目</el-button></header>
    <p v-if="error" role="alert" class="panelError">{{ error }}</p>
    <form class="inlineForm" @submit.prevent="createProject"><div><label for="newProjectName">项目名称</label><el-input id="newProjectName" v-model="name" maxlength="128" :disabled="busy" /></div><el-button type="primary" native-type="submit" :disabled="!name.trim()" :loading="busy">创建项目</el-button></form>
    <el-table :data="projects" row-key="id" empty-text="创建第一个团队项目">
      <el-table-column prop="name" label="项目" min-width="180" />
      <el-table-column label="状态" width="90"><template #default="{ row }">{{ row.status === 'ACTIVE' ? '进行中' : '已归档' }}</template></el-table-column>
      <el-table-column label="管理" min-width="270"><template #default="{ row }"><el-button :disabled="busy" @click="selectProject(row.id)">管理成员</el-button><el-button :disabled="busy" @click="rename(row.id)">重命名</el-button><el-button :disabled="busy" @click="archive(row.id)">{{ row.status === 'ACTIVE' ? '归档' : '恢复' }}</el-button></template></el-table-column>
    </el-table>
    <section v-if="selected" class="memberArea" aria-label="项目成员">
      <h3>{{ selected.name }} · 成员</h3>
      <form class="inlineForm" @submit.prevent="addMember"><div><label for="memberAccount">选择账号</label><select id="memberAccount" v-model="userId" :disabled="busy"><option value="">请选择</option><option v-for="user in availableUsers" :key="user.id" :value="user.id">{{ user.username }}</option></select></div><el-button type="primary" native-type="submit" :loading="busy" :disabled="!userId">加入项目</el-button></form>
      <ul class="memberList"><li v-for="member in members" :key="member.userId"><span>{{ users.find(user => user.id === member.userId)?.username ?? member.userId }}</span><el-button :disabled="busy" @click="removeMember(member.userId)">移除成员</el-button></li></ul>
      <p v-if="!members.length" class="muted">暂无成员。可以先将自己或制作成员加入项目。</p>
    </section>
  </section>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { ElMessageBox } from "element-plus";
import client from "@huimeng/api/client";
import type { Project, User, Member } from "@huimeng/contracts/platform";
import { readAll, adminError } from "@/lib/teamAdmin";
const projects = ref<Project[]>([]), users = ref<User[]>([]), members = ref<Member[]>([]);
const selected = ref<Project | null>(null), name = ref(""), userId = ref(""), busy = ref(false), error = ref("");
const controller = new AbortController();
const availableUsers = computed(() => users.value.filter(user => user.status === "ACTIVE" && !members.value.some(member => member.userId === user.id)));
async function refresh() {
  error.value = "";
  try { [projects.value, users.value] = await Promise.all([readAll<Project>("/api/v1/projects", controller.signal), readAll<User>("/api/v1/admin/users", controller.signal)]); }
  catch (ex) { if (!controller.signal.aborted) error.value = adminError(ex, "项目或账号加载失败，请重试。"); }
}
async function perform(action: () => Promise<unknown>) {
  if (busy.value) return;
  busy.value = true; error.value = "";
  try { await action(); await refresh(); }
  catch (ex) { error.value = adminError(ex, "操作失败，请重试。"); }
  finally { busy.value = false; }
}
async function createProject() { await perform(async () => { await client.post("/api/v1/projects", { name: name.value.trim() }, { signal: controller.signal }); name.value = ""; }); }
async function selectProject(projectId: string) {
  const project = projects.value.find(item => item.id === projectId);
  if (!project) return;
  selected.value = project; userId.value = ""; members.value = [];
  const id = project.id;
  try { const result = await readAll<Member>(`/api/v1/projects/${id}/members`, controller.signal); if (selected.value?.id === id) members.value = result; }
  catch (ex) { if (!controller.signal.aborted) error.value = adminError(ex, "成员加载失败，请重试。"); }
}
async function addMember() {
  if (!selected.value) return;
  const project = selected.value, id = userId.value;
  await perform(async () => { await client.post(`/api/v1/projects/${project.id}/members`, { userId: id }, { signal: controller.signal }); await selectProject(project.id); });
}
async function removeMember(id: string) {
  if (!selected.value) return;
  const project = selected.value;
  const confirmed = await ElMessageBox.confirm("移除后，该成员的后续项目访问将立即被拒绝。", "移除成员", { confirmButtonText: "移除成员", cancelButtonText: "取消" }).then(() => true, () => false);
  if (confirmed) await perform(async () => { await client.delete(`/api/v1/projects/${project.id}/members/${id}`, { signal: controller.signal }); await selectProject(project.id); });
}
async function rename(id: string) {
  const project = projects.value.find(item => item.id === id);
  if (!project) return;
  const result = await ElMessageBox.prompt("请输入项目名称", "重命名项目", { inputValue: project.name, inputValidator: value => !!value?.trim() && value.length <= 128 || "请输入 1–128 字的名称", confirmButtonText: "保存名称", cancelButtonText: "取消" }).catch(() => null);
  if (result) await perform(async () => { await client.patch(`/api/v1/projects/${project.id}`, { name: result.value.trim() }, { signal: controller.signal }); if (selected.value?.id === project.id) selected.value = { ...project, name: result.value.trim() }; });
}
async function archive(id: string) {
  const project = projects.value.find(item => item.id === id);
  if (!project) return;
  const confirmed = await ElMessageBox.confirm(project.status === "ACTIVE" ? "归档后无法发起新创作，项目内容将保留。" : "恢复后，成员可以继续使用项目。", project.status === "ACTIVE" ? "归档项目" : "恢复项目", { confirmButtonText: "确认", cancelButtonText: "取消" }).then(() => true, () => false);
  if (confirmed) await perform(() => client.patch(`/api/v1/projects/${project.id}`, { status: project.status === "ACTIVE" ? "ARCHIVED" : "ACTIVE" }, { signal: controller.signal }));
}
onMounted(refresh); onBeforeUnmount(() => controller.abort());
</script>
