<template>
  <main class="loginPage">
    <form class="loginCard" @submit.prevent="submit">
      <span class="wordmark">绘梦 <small>1.0</small></span>
      <h1>登录内部创作平台</h1>
      <label for="username">账号</label>
      <el-input id="username" v-model="username" autocomplete="username" autofocus :disabled="busy" />
      <label for="password">密码</label>
      <el-input id="password" v-model="password" type="password" autocomplete="current-password" show-password :disabled="busy" />
      <p v-if="error" role="alert" class="loginError">{{ error }}</p>
      <el-button native-type="submit" type="primary" :loading="busy" :disabled="!username.trim() || !password">登录</el-button>
      <p class="loginHint">使用管理员为你开通的团队账号。</p>
    </form>
  </main>
</template>
<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { login } from "@/stores/session";
const router = useRouter();
const username = ref("");
const password = ref("");
const busy = ref(false);
const error = ref("");
async function submit() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await login(username.value.trim(), password.value);
    password.value = "";
    await router.push("/home");
  } catch { error.value = "登录失败，请检查账号、密码或联系管理员确认服务状态。"; }
  finally { busy.value = false; }
}
</script>
<style scoped>
.loginPage { min-height: 100dvh; display: grid; place-items: center; background: var(--el-fill-color-light); }
.loginCard { display: grid; gap: 14px; width: min(360px, calc(100vw - 64px)); padding: 36px; border: 1px solid var(--el-border-color); border-radius: 16px; background: var(--el-bg-color); }
.wordmark { font-size: 28px; font-weight: 700; letter-spacing: 4px; }
.wordmark small { font-size: 12px; letter-spacing: 0; color: var(--el-text-color-secondary); }
h1 { margin: 8px 0; font-size: 18px; font-weight: 500; }
label { font-size: 13px; }
.loginError { color: var(--el-color-danger); font-size: 13px; }
.loginHint { margin: 0; color: var(--el-text-color-secondary); font-size: 12px; }
</style>
