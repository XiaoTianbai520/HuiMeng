<template>
  <section class="modelCatalog" :aria-label="title">
    <p class="intro">{{ intro }}</p>
    <p v-if="error" class="panelError" role="alert">{{ error }}</p>

    <template v-if="isAdmin">
      <h3>供应商密钥</h3>
      <el-table :data="providerRows" empty-text="暂无供应商" size="small">
        <el-table-column prop="label" label="供应商" min-width="150" />
        <el-table-column label="密钥" min-width="140">
          <template #default="{ row }">
            <el-tag v-if="row.local" size="small" type="info" effect="plain">本地执行器，无需密钥</el-tag>
            <el-tag v-else size="small" :type="row.configured ? 'success' : 'warning'" effect="plain">{{ row.configured ? "已配置" : "未配置" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button v-if="!row.local" text :disabled="busy" @click="openSecret(row.providerId)">{{ row.configured ? "更新密钥" : "设置密钥" }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <form v-if="secretProvider" class="secretForm" @submit.prevent="saveSecret">
        <div>
          <label for="modelSecret">{{ secretProvider }} 的新 API Key</label>
          <el-input id="modelSecret" v-model="secretValue" type="password" autocomplete="new-password" maxlength="16384" :disabled="busy" placeholder="保存后不再展示" />
        </div>
        <el-space>
          <el-button native-type="submit" type="primary" :loading="busy" :disabled="!secretValue.trim()">保存密钥</el-button>
          <el-button :disabled="busy" @click="cancelSecret">取消</el-button>
        </el-space>
      </form>
    </template>

    <h3>已登记模型</h3>
    <p v-if="!models.length" class="emptyHint">{{ emptyHint }}</p>
    <el-table v-else :data="models" size="small" empty-text="暂无模型">
      <el-table-column prop="label" label="模型" min-width="180" />
      <el-table-column prop="providerId" label="供应商" min-width="150" />
      <el-table-column label="能力" min-width="110">
        <template #default="{ row }">{{ row.capabilities.join(" / ") }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled ? 'success' : 'info'" effect="plain">{{ row.enabled ? "已启用" : "未启用" }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="isAdmin" label="操作" width="110">
        <template #default="{ row }">
          <el-button text :disabled="busy" @click="toggle(row as Model)">{{ row.enabled ? "停用" : "启用" }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <p class="footnote">{{ footnote }}</p>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import client from "@huimeng/api/client";
import type { Model, ProviderStatus } from "@huimeng/contracts/platform";
import { adminError } from "@/lib/teamAdmin";
import { isAdmin } from "@/stores/session";
import { loadSettings } from "@/stores/settings";

const { kind = "text" } = defineProps<{ kind?: "text" | "media" }>();
const isMedia = computed(() => kind === "media");
const title = computed(() => (isMedia.value ? "媒体模型" : "文本模型"));
const intro = computed(() =>
  isMedia.value
    ? "图片与视频模型由团队统一登记和启停；本地 ComfyUI 执行器不需要远程密钥，但要求执行器在线。"
    : "文本与 Agent 模型由团队统一登记和启停；远程供应商需要先在下方保存密钥，成员只能使用已启用模型。");
const emptyHint = computed(() => (isMedia.value ? "尚未登记任何图片、视频或音频模型。" : "尚未登记任何文本模型。"));
const footnote = computed(() =>
  isAdmin.value
    ? "启停会立即影响成员侧的模型列表；密钥只保存在服务端并加密，保存后不再回显。"
    : "只有管理员可以启停模型或修改密钥；需要新模型时请联系管理员。");

const allModels = ref<Model[]>([]);
const providers = ref<ProviderStatus[]>([]);
const busy = ref(false);
const error = ref("");
const secretProvider = ref("");
const secretValue = ref("");

const models = computed(() =>
  allModels.value.filter(model =>
    isMedia.value
      ? model.capabilities.some(capability => capability === "image" || capability === "video" || capability === "audio")
      : model.capabilities.includes("text")));

const providerRows = computed(() => {
  const configured = new Set(providers.value.map(item => item.providerId));
  const ids = [...new Set(models.value.map(model => model.providerId))].sort();
  return ids.map(providerId => ({
    providerId,
    label: providerId,
    local: /Local/i.test(providerId),
    configured: configured.has(providerId),
  }));
});

async function refresh() {
  error.value = "";
  try {
    const catalog = isAdmin.value
      ? await client.get<{ items: Model[] }>("/api/v1/admin/models")
      : await client.get<{ items: Model[] }>("/api/v1/models");
    allModels.value = catalog.data.items;
    if (isAdmin.value) {
      const status = await client.get<{ items: ProviderStatus[] }>("/api/v1/admin/providers");
      providers.value = status.data.items;
    }
  } catch (ex) {
    error.value = adminError(ex, "模型配置加载失败，请重试。");
  }
}

function openSecret(providerId: string) {
  secretProvider.value = providerId;
  secretValue.value = "";
}

function cancelSecret() {
  secretProvider.value = "";
  secretValue.value = "";
}

async function saveSecret() {
  if (busy.value || !secretProvider.value || !secretValue.value.trim()) return;
  busy.value = true;
  error.value = "";
  try {
    await client.put(`/api/v1/admin/providers/${encodeURIComponent(secretProvider.value)}/secret`, { apiKey: secretValue.value });
    ElMessage.success("密钥已保存。");
    cancelSecret();
    await refresh();
    await loadSettings(true);
  } catch (ex) {
    error.value = adminError(ex, "密钥保存失败，请重试。");
  } finally {
    secretValue.value = "";
    busy.value = false;
  }
}

async function toggle(model: Model) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await client.put("/api/v1/admin/models/approval", { providerId: model.providerId, modelId: model.modelId, enabled: !model.enabled });
    await refresh();
    await loadSettings(true);
  } catch (ex) {
    error.value = adminError(ex, "模型状态更新失败，请刷新后重试。");
  } finally {
    busy.value = false;
  }
}

onMounted(refresh);
</script>

<style lang="scss" scoped>
.modelCatalog {
  display: flex;
  flex-direction: column;
  gap: 8px;

  .intro,
  .footnote,
  .emptyHint {
    margin: 0;
    color: var(--el-text-color-regular);
    font-size: 13px;
    line-height: 1.7;
  }

  .footnote { margin-top: 4px; color: var(--el-text-color-secondary); }

  h3 {
    margin: 12px 0 0;
    font-size: 14px;
    font-weight: 600;
  }

  .panelError {
    margin: 0;
    color: var(--el-color-danger);
    font-size: 13px;
  }

  .secretForm {
    display: flex;
    align-items: flex-end;
    flex-wrap: wrap;
    gap: 12px;
    padding: 12px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;

    > div {
      display: flex;
      flex: 1;
      flex-direction: column;
      gap: 6px;
      min-width: 220px;

      label { font-size: 13px; color: var(--el-text-color-regular); }
    }
  }
}
</style>
