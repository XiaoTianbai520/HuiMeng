<template>
  <main class="teamPage">
    <header class="teamHeader"><div><span class="teamBrand">绘梦 <small>团队管理</small></span><h1>把账号、项目和能力交给合适的人</h1></div><el-space><span>{{ sessionUser?.username }}</span><router-link to="/home">返回项目</router-link><el-button :loading="leaving" @click="leave">退出登录</el-button></el-space></header>
    <nav aria-label="团队管理分类" class="teamTabs"><button v-for="tab in tabs" :key="tab.id" type="button" :aria-pressed="active === tab.id" @click="active = tab.id">{{ tab.label }}</button></nav>
    <UsersPanel v-if="active === 'users'" />
    <ProjectsPanel v-else-if="active === 'projects'" />
    <ProvidersPanel v-else-if="active === 'providers'" />
    <section v-else class="teamPanel" aria-label="外部客户端管理"><header class="panelHeading"><div><h2>外部客户端</h2><p>每份凭证限定一个项目和明确的工具、团队权限。</p></div></header><ExternalClients /></section>
  </main>
</template>
<script setup lang="ts">
import { defineAsyncComponent, ref } from "vue";
import { ElMessage } from "element-plus";
import { logout, sessionUser } from "@/stores/session";
import "@/components/team/team.scss";
const UsersPanel = defineAsyncComponent(() => import("@/components/team/UsersPanel.vue"));
const ProjectsPanel = defineAsyncComponent(() => import("@/components/team/ProjectsPanel.vue"));
const ProvidersPanel = defineAsyncComponent(() => import("@/components/team/ProvidersPanel.vue"));
const ExternalClients = defineAsyncComponent(() => import("@/components/settings/panels/mcp/index.vue"));
const tabs = [{ id: "users", label: "账号" }, { id: "projects", label: "项目与成员" }, { id: "providers", label: "模型与密钥" }, { id: "clients", label: "外部客户端" }] as const;
const active = ref<string>("users"), leaving = ref(false);
async function leave() { leaving.value = true; try { await logout(); } catch { ElMessage.error("退出失败，请重试。"); } finally { leaving.value = false; } }
</script>
