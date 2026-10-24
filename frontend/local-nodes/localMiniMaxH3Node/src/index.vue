<script lang="ts">
import { defineComponent, h } from "vue";
import { useNode } from "@vue-flow/core";
import originalNode from "./index.vue";

const model = JSON.stringify(["localMiniMaxH3", "minimax-h3-local"]);

export default defineComponent({
  name: "localMiniMaxH3Node",
  icon: (originalNode as typeof originalNode & { icon?: unknown }).icon,
  handles: (originalNode as typeof originalNode & { handles?: unknown }).handles,
  setup() {
    const { node } = useNode();
    if (!node.data.model) node.data.model = model;
    return () => h(originalNode);
  },
});
</script>
