<template>
  <section><p>在团队管理中维护账号、项目成员、模型密钥和外部客户端授权。</p><router-link to="/team" @click="emit('navigate')">打开团队管理</router-link></section>
</template>
<script setup lang="ts">const emit = defineEmits<{ navigate: [] }>();</script>
