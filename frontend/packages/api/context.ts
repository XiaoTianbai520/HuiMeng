import type { User } from "@huimeng/contracts/platform";
export type Actor = User;
let actor: Actor | null = null;
let projectId: string | null = null;
let csrfToken = "";
let authVersion = 0;
const listeners = new Set<() => void>();
const projectControllers = new Set<AbortController>();

export function context() { return { actor, projectId, csrfToken, authVersion }; }
export function setActor(value: Actor | null) {
  authVersion++;
  actor = value;
  if (!value) { csrfToken = ""; setProject(null); }
  for (const notify of listeners) notify();
}
export function setCsrf(value: string) { csrfToken = value; }
export function setProject(value: string | null) {
  if (value !== null && !/^[A-Za-z0-9_-]{1,128}$/.test(value)) throw new Error("项目 ID 无效");
  if (projectId === value) return;
  for (const controller of projectControllers) controller.abort(new Error("项目已切换，请查询原运行状态"));
  projectControllers.clear();
  projectId = value;
  for (const notify of listeners) notify();
}
export function subscribeContext(notify: () => void) { listeners.add(notify); return () => { listeners.delete(notify); }; }
export function projectSignal() {
  const controller = new AbortController();
  projectControllers.add(controller);
  return { signal: controller.signal, dispose: () => { projectControllers.delete(controller); } };
}
