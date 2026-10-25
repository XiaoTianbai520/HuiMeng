<template>
  <section class="teamPanel" aria-label="模型与密钥管理">
    <header class="panelHeading"><div><h2>模型与密钥</h2><p>团队统一配置。密钥保存后不再展示，成员仅能使用已批准模型。</p></div><el-button :loading="busy" @click="refresh">刷新配置</el-button></header>
    <p v-if="error" role="alert" class="panelError">{{ error }}</p>
    <form class="inlineForm" aria-label="保存供应商密钥" @submit.prevent="saveSecret(false)">
      <div><label for="providerId">供应商标识</label><el-input id="providerId" v-model="providerId" placeholder="例如 deepseek" maxlength="128" :disabled="busy" /></div>
      <div><label for="providerKey">新的 API Key</label><el-input id="providerKey" v-model="apiKey" type="password" autocomplete="new-password" maxlength="16384" :disabled="busy" /></div>
      <el-button type="primary" native-type="submit" :loading="busy" :disabled="!validProvider || !apiKey.trim()">保存供应商密钥</el-button>
    </form>
    <el-table :data="providers" empty-text="尚未配置供应商密钥；本地媒体供应商不需要远程 Key。">
      <el-table-column prop="providerId" label="供应商" />
      <el-table-column label="配置状态"><template #default="{ row }">{{ row.configured ? '已配置' : '未配置' }}</template></el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" />
    </el-table>
    <h3>获批模型</h3>
    <el-table :data="models" empty-text="暂无登记模型">
      <el-table-column prop="label" label="模型" min-width="170" />
      <el-table-column prop="providerId" label="供应商" min-width="140" />
      <el-table-column label="能力" min-width="100"><template #default="{ row }">{{ row.capabilities.join(' / ') }}</template></el-table-column>
      <el-table-column label="批准状态" min-width="130"><template #default="{ row }"><el-button :disabled="busy" @click="approve(row.providerId, row.modelId)">{{ row.enabled ? '停用模型' : '批准模型' }}</el-button><span class="muted">{{ row.enabled ? ' 已批准' : ' 未批准' }}</span></template></el-table-column>
    </el-table>
    <h3>TF-Router 插件市场</h3>
    <p class="muted">{{ marketConfigured ? '市场密钥已配置。' : '尚未配置市场密钥。' }} 市场密钥与模型 API Key 分开管理。</p>
    <form class="inlineForm" @submit.prevent="saveSecret(true)"><div><label for="marketKey">新的市场 Key</label><el-input id="marketKey" v-model="marketKey" type="password" autocomplete="new-password" maxlength="16384" :disabled="busy" /></div><el-button native-type="submit" :loading="busy" :disabled="!marketKey.trim()">保存市场密钥</el-button></form>
  </section>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import client from "@huimeng/api/client";
import type { Model, ProviderStatus } from "@huimeng/contracts/platform";
import { adminError } from "@/lib/teamAdmin";
import { loadSettings } from "@/stores/settings";
const providers = ref<ProviderStatus[]>([]), models = ref<Model[]>([]);
const providerId = ref("deepseek"), apiKey = ref(""), marketKey = ref(""), marketConfigured = ref(false), busy = ref(false), error = ref("");
const validProvider = computed(() => /^[A-Za-z0-9_-]{1,128}$/.test(providerId.value.trim()));
const controller = new AbortController();
async function refresh() {
  error.value = "";
  try {
    const [providerResult, modelResult, market] = await Promise.all([client.get<{ items: ProviderStatus[] }>("/api/v1/admin/providers", { signal: controller.signal }), client.get<{ items: Model[] }>("/api/v1/admin/models", { signal: controller.signal }), client.get<{ configured: boolean }>("/api/v1/admin/plugin-market/status", { signal: controller.signal })]);
    providers.value = providerResult.data.items; models.value = modelResult.data.items; marketConfigured.value = market.data.configured;
  } catch (ex) { if (!controller.signal.aborted) error.value = adminError(ex, "配置加载失败，请重试。"); }
}
async function saveSecret(market: boolean) {
  if (busy.value) return;
  busy.value = true; error.value = "";
  try { await client.put(market ? "/api/v1/admin/plugin-market/secret" : `/api/v1/admin/providers/${encodeURIComponent(providerId.value.trim())}/secret`, { apiKey: market ? marketKey.value : apiKey.value }, { signal: controller.signal }); ElMessage.success("密钥已保存。"); await refresh(); }
  catch (ex) { error.value = adminError(ex, "密钥保存失败，请重试。"); }
  finally { apiKey.value = ""; marketKey.value = ""; busy.value = false; }
}
async function approve(providerId: string, modelId: string) {
  const model = models.value.find(item => item.providerId === providerId && item.modelId === modelId);
  if (!model) return;
  if (busy.value) return;
  busy.value = true; error.value = "";
  try { await client.put("/api/v1/admin/models/approval", { providerId: model.providerId, modelId: model.modelId, enabled: !model.enabled }, { signal: controller.signal }); await refresh(); await loadSettings(true); }
  catch (ex) { error.value = adminError(ex, "批准状态更新失败，请刷新检查。"); }
  finally { busy.value = false; }
}
onMounted(refresh); onBeforeUnmount(() => { controller.abort(); apiKey.value = ""; marketKey.value = ""; });
</script>
