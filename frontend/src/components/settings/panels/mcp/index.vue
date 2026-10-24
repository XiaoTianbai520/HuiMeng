<template>
  <div class="externalClients">
    <p>为局域网客户端授予单个项目及明确的 MCP 工具、A2A 团队权限。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <form class="clientForm" @submit.prevent="issue">
      <label for="clientName">客户端名称</label><el-input id="clientName" v-model="name" />
      <label for="clientProject">项目 ID</label><el-input id="clientProject" v-model="projectId" />
      <label for="clientTools">允许的 MCP 工具（逗号分隔）</label><el-input id="clientTools" v-model="tools" />
      <label for="clientTeams">允许的 A2A 团队（逗号分隔）</label><el-input id="clientTeams" v-model="teams" />
      <el-button type="primary" native-type="submit" :loading="busy" :disabled="!name.trim() || !projectId.trim()">签发凭证</el-button>
    </form>
    <el-alert v-if="issuedToken" title="凭证仅在本次签发后显示，请通过受控渠道交付给客户端。" type="info" :closable="false" />
    <el-input v-if="issuedToken" :model-value="issuedToken" readonly aria-label="本次签发的凭证" />
    <el-button v-if="issuedToken" @click="issuedToken = ''">关闭凭证展示</el-button>
    <el-divider />
    <el-button :loading="busy" @click="refresh">刷新授权列表</el-button>
    <el-table :data="clients" empty-text="暂无外部客户端">
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="projectId" label="项目" />
      <el-table-column label="状态"><template #default="{ row }">{{ row.revokedAt ? '已撤销' : '有效' }}</template></el-table-column>
      <el-table-column label="操作"><template #default="{ row }"><el-button :disabled="!!row.revokedAt || busy" @click="revoke(row.id)">撤销</el-button></template></el-table-column>
    </el-table>
    <p>MCP：{{ endpoint }}</p>
  </div>
</template>
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import client from "@huimeng/api/client";
import type { Client, ClientIssued, ClientPage } from "@huimeng/contracts/platform";
const name = ref("");
const projectId = ref("");
const tools = ref("");
const teams = ref("");
const issuedToken = ref("");
const clients = ref<Client[]>([]);
const busy = ref(false);
const error = ref("");
const endpoint = new URL("/mcp", window.location.origin).href;
const controller = new AbortController();
const list = (value: string) => [...new Set(value.split(/[,，]/).map(item => item.trim()).filter(Boolean))];
async function refresh() {
  error.value = "";
  try { clients.value = (await client.get<ClientPage>("/api/v1/admin/external-clients", { signal: controller.signal })).data.items; }
  catch { if (!controller.signal.aborted) error.value = "无法读取授权列表，请重试。"; }
}
async function issue() {
  busy.value = true;
  error.value = "";
  issuedToken.value = "";
  try {
    const { data } = await client.post<ClientIssued>("/api/v1/admin/external-clients", { name: name.value.trim(), projectId: projectId.value.trim(), mcpTools: list(tools.value), a2aTeams: list(teams.value) }, { signal: controller.signal });
    issuedToken.value = data.token;
    await refresh();
  } catch { if (!controller.signal.aborted) error.value = "签发失败，请检查项目及能力标识。"; }
  finally { busy.value = false; }
}
async function revoke(id: string) {
  busy.value = true;
  try { await client.post(`/api/v1/admin/external-clients/${encodeURIComponent(id)}/revoke`); await refresh(); }
  catch { error.value = "撤销失败，请重试。"; }
  finally { busy.value = false; }
}
onMounted(refresh);
onBeforeUnmount(() => { issuedToken.value = ""; controller.abort(); });
</script>
<style scoped>.clientForm { display: grid; gap: 10px; margin: 20px 0; } .clientForm label { font-size: 13px; }</style>
