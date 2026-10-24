<template><p>供应商凭据由公司管理员统一管理。</p></template>
<script setup lang="ts">
defineProps<{ apiKey?: string; visible?: boolean; saveApiKey?: (key: string) => unknown }>();
defineEmits<{ rechargeVisibleChange: [visible: boolean] }>();
</script>
