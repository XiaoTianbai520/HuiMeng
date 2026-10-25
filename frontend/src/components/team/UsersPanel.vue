<template>
  <section class="teamPanel" aria-label="账号管理">
    <header class="panelHeading"><div><h2>账号</h2><p>为团队成员开通账号，管理使用权限。</p></div><el-button :loading="busy" @click="refresh">刷新账号</el-button></header>
    <p v-if="error" role="alert" class="panelError">{{ error }}</p>
    <form class="inlineForm" aria-label="创建账号" @submit.prevent="createUser">
      <div><label for="newUsername">账号名</label><el-input id="newUsername" v-model="username" maxlength="128" autocomplete="off" :disabled="busy" /></div>
      <div><label for="newPassword">初始密码（至少 12 位）</label><el-input id="newPassword" v-model="password" type="password" maxlength="256" autocomplete="new-password" :disabled="busy" /></div>
      <div><label for="newRole">角色</label><select id="newRole" v-model="role" :disabled="busy"><option value="USER">项目成员</option><option value="ADMIN">管理员</option></select></div>
      <el-button type="primary" native-type="submit" :loading="busy" :disabled="!username.trim() || password.length < 12">创建账号</el-button>
    </form>
    <el-table :data="users" empty-text="还没有账号" row-key="id">
      <el-table-column prop="username" label="账号" min-width="140" />
      <el-table-column label="角色" width="130"><template #default="{ row }">{{ row.role === 'ADMIN' ? '管理员' : '项目成员' }}</template></el-table-column>
      <el-table-column label="状态" width="100"><template #default="{ row }">{{ row.status === 'ACTIVE' ? '已启用' : '已停用' }}</template></el-table-column>
      <el-table-column label="管理" min-width="280"><template #default="{ row }">
        <el-button :disabled="busy" @click="updateUser(row.id, 'status')">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button>
        <el-button :disabled="busy" @click="updateUser(row.id, 'role')">{{ row.role === 'ADMIN' ? '改为成员' : '设为管理员' }}</el-button>
        <el-button :disabled="busy" @click="resetId = row.id; resetName = row.username; resetPassword = ''">重置密码</el-button>
      </template></el-table-column>
    </el-table>
    <el-dialog :model-value="!!resetId" title="重置密码" width="420px" @update:model-value="resetId = ''; resetPassword = ''" destroy-on-close>
      <p>为 {{ resetName }} 设置新密码。该账号的旧会话将失效。</p>
      <p v-if="error" role="alert" class="panelError">{{ error }}</p>
      <form @submit.prevent="reset"><label for="resetPassword">新密码（至少 12 位）</label><el-input id="resetPassword" v-model="resetPassword" type="password" maxlength="256" autocomplete="new-password" :disabled="busy" /><el-button type="primary" native-type="submit" :loading="busy" :disabled="resetPassword.length < 12">保存新密码</el-button></form>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import client from "@huimeng/api/client";
import type { User } from "@huimeng/contracts/platform";
import { readAll, adminError } from "@/lib/teamAdmin";
import { sessionUser } from "@/stores/session";
import { setActor } from "@huimeng/api/context";
const users = ref<User[]>([]), username = ref(""), password = ref(""), role = ref<"USER" | "ADMIN">("USER");
const busy = ref(false), error = ref(""), resetId = ref(""), resetName = ref(""), resetPassword = ref("");
const controller = new AbortController();
async function refresh() {
  error.value = "";
  try { users.value = await readAll<User>("/api/v1/admin/users", controller.signal); }
  catch (ex) { if (!controller.signal.aborted) error.value = adminError(ex, "账号加载失败，请重试。"); }
}
async function createUser() {
  if (busy.value) return;
  busy.value = true; error.value = "";
  try { await client.post("/api/v1/admin/users", { username: username.value.trim(), password: password.value, role: role.value }, { signal: controller.signal }); username.value = ""; ElMessage.success("账号已创建，请单独向成员交付初始密码。"); await refresh(); }
  catch (ex) { error.value = adminError(ex, "创建失败，请检查账号和密码。"); }
  finally { password.value = ""; busy.value = false; }
}
async function updateUser(id: string, field: "status" | "role") {
  const user = users.value.find(item => item.id === id);
  if (!user) return;
  const value = field === "status" ? user.status === "ACTIVE" ? "DISABLED" : "ACTIVE" : user.role === "ADMIN" ? "USER" : "ADMIN";
  const confirmed = await ElMessageBox.confirm(`更改账号“${user.username}”的${field === "status" ? "状态" : "角色"}后，该账号的旧会话将失效。`, "确认更改", { confirmButtonText: "确认更改", cancelButtonText: "取消" }).then(() => true, () => false);
  if (!confirmed || busy.value) return;
  busy.value = true; error.value = "";
  try { await client.patch(`/api/v1/admin/users/${user.id}`, { [field]: value }, { signal: controller.signal }); if (user.id === sessionUser.value?.id) { setActor(null); return; } await refresh(); }
  catch (ex) { error.value = adminError(ex, "更改失败，请重试。"); }
  finally { busy.value = false; }
}
async function reset() {
  if (busy.value) return;
  busy.value = true; error.value = "";
  try { const id = resetId.value; await client.patch(`/api/v1/admin/users/${id}`, { password: resetPassword.value }, { signal: controller.signal }); resetId.value = ""; ElMessage.success("密码已重置。"); if (id === sessionUser.value?.id) setActor(null); }
  catch (ex) { error.value = adminError(ex, "密码重置失败，请重试。"); }
  finally { resetPassword.value = ""; busy.value = false; }
}
onMounted(refresh);
onBeforeUnmount(() => { controller.abort(); password.value = ""; resetPassword.value = ""; });
</script>
