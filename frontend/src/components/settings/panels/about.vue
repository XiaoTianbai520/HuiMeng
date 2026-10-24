<template><section><h3>绘梦 1.0</h3><p>公司内部创作平台。基于 Toonflow MIT 开源版本迁移。</p><el-link href="https://github.com/HBAI-Ltd/Toonflow-app" target="_blank" rel="noopener noreferrer">Toonflow 开源项目（外部网站）</el-link><p>{{ sessionUser?.username }}</p><el-button @click="logout">退出登录</el-button></section></template>
<script setup lang="ts">
import { logout, sessionUser } from "@/stores/session";
</script>
