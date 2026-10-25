import { createRouter, createWebHashHistory } from "vue-router";
import { loadSession } from "@/stores/session";
import { loadSettings } from "@/stores/settings";
const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: "/", redirect: "/home" },
  { path: "/login", component: () => import("@/pages/login.vue") },
  { path: "/home", component: () => import("@/pages/home/index.vue") },
  { path: "/team", component: () => import("@/pages/team/index.vue") },
  { path: "/canvas", redirect: "/workspace" },
  { path: "/workspace", component: () => import("@/pages/workspace/index.vue") },
] });
router.beforeEach(async to => {
  const user = await loadSession();
  if (!user) return to.path === "/login" ? true : { path: "/login" };
  if (to.path === "/team" && user.role !== "ADMIN") return { path: "/home" };
  await loadSettings();
  if (to.path === "/login") return { path: "/home" };
  return true;
});
export default router;
