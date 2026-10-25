<template>
  <textarea
    class="plainTextEditor"
    :value="context.text"
    :disabled="context.loading"
    :aria-label="`编辑 ${context.resource.label}`"
    spellcheck="false"
    @input="onInput"
  />
</template>

<script setup lang="ts">
import type { ExtContext } from "@toonflow/ext-scaffold/runtime";

// M3-W03 最小内置文本编辑器：远程扩展清单未接通前，用原格式文本完成读取/保存/冲突验证。
// 富文本与扩展市场仍属 M3-05，不在这里声明扩展体系能力。
const props = defineProps<{ context: ExtContext }>();
function onInput(event: Event) {
  props.context.updateText((event.target as HTMLTextAreaElement).value);
}
</script>

<style scoped>
.plainTextEditor {
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  min-height: 0;
  resize: none;
  border: 0;
  padding: 16px;
  outline: none;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  font: 14px/1.7 ui-monospace, SFMono-Regular, Consolas, monospace;
  white-space: pre;
  overflow: auto;
}
.plainTextEditor:disabled { opacity: 0.6; }
</style>
