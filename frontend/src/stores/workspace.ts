import { defineStore } from "pinia";
import { ref } from "vue";
import client from "@huimeng/api/client";
import { context, subscribeContext, setProject } from "@huimeng/api/context";
import type { AgentAttachment } from "@/components/agent/types";
// Source compatibility: directory carries an opaque project ID, never a physical path.
export type Project = { directory: string; name: string; lastOpenedAt: number };
type ProjectDto = { id: string; name: string; status: "ACTIVE" | "ARCHIVED" };
export const useWorkspaceStore = defineStore("workspace", () => {
  const project = ref<Project | null>(null);
  const projectList = ref<Project[]>([]);
  const pendingAgentMessage = ref<{ directory: string; prompt: string; attachments?: AgentAttachment[]; model: string; reasoningEffort: string } | null>(null);
  let owner = context().actor?.id;
  subscribeContext(() => {
    if (owner !== context().actor?.id) {
      owner = context().actor?.id;
      project.value = null;
      projectList.value = [];
      pendingAgentMessage.value = null;
    }
  });
  async function loadProjects() {
    const actorId = context().actor?.id;
    const items: ProjectDto[] = [];
    let cursor: string | null = null;
    const seen = new Set<string>();
    do {
      const response: { data: { items: ProjectDto[]; nextCursor: string | null } } = await client.get("/api/v1/projects", { params: { limit: 50, ...(cursor ? { cursor } : {}) } });
      items.push(...response.data.items.filter(item => item.status === "ACTIVE"));
      cursor = response.data.nextCursor;
      if (cursor && seen.has(cursor)) throw new Error("项目分页游标重复");
      if (cursor) seen.add(cursor);
    } while (cursor);
    if (context().actor?.id !== actorId) return;
    projectList.value = items.map(item => ({ directory: item.id, name: item.name, lastOpenedAt: 0 }));
  }
  async function openProject(id: string, _previousId = id, signal?: AbortSignal) {
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(id)) throw new Error("请重新选择授权项目");
    const { data } = await client.get<ProjectDto>(`/api/v1/projects/${encodeURIComponent(id)}`, { signal });
    signal?.throwIfAborted();
    if (data.status !== "ACTIVE") throw new Error("项目已归档");
    setProject(data.id);
    project.value = { directory: data.id, name: data.name, lastOpenedAt: Date.now() };
    pendingAgentMessage.value = null;
    projectList.value = [project.value, ...projectList.value.filter(item => item.directory !== data.id)];
  }
  function renameProject(id: string, name: string) {
    const item = projectList.value.find(value => value.directory === id);
    if (item) item.name = name;
  }
  function removeProject(id: string) {
    projectList.value = projectList.value.filter(item => item.directory !== id);
    if (project.value?.directory === id) { project.value = null; setProject(null); }
  }
  return { project, projectList, pendingAgentMessage, loadProjects, openProject, renameProject, removeProject };
});
