import { computed, shallowRef } from "vue";
import client, { ensureCsrf } from "@huimeng/api/client";
import { context, setActor, setCsrf, subscribeContext, type Actor } from "@huimeng/api/context";

export const sessionUser = shallowRef<Actor | null>(context().actor);
subscribeContext(() => {
  sessionUser.value = context().actor;
  if (!sessionUser.value && loaded && typeof window !== "undefined" && window.location.hash !== "#/login") {
    window.location.hash = "/login";
  }
});
export const isAdmin = computed(() => sessionUser.value?.role === "ADMIN");
let loaded = false;
function validActor(value: unknown): value is Actor {
  if (!value || typeof value !== "object") return false;
  const actor = value as Partial<Actor>;
  return typeof actor.id === "string" && typeof actor.username === "string"
    && (actor.role === "ADMIN" || actor.role === "USER") && actor.status === "ACTIVE";
}
export async function loadSession() {
  if (loaded && sessionUser.value) return sessionUser.value;
  try {
    const { data } = await client.get<unknown>("/api/v1/auth/me");
    if (!validActor(data)) throw new Error("账号响应格式无效");
    setActor(data);
  }
  catch { setActor(null); }
  loaded = true;
  return sessionUser.value;
}
export async function login(username: string, password: string) {
  await ensureCsrf();
  const { data } = await client.post<{ user: Actor }>("/api/v1/auth/login", { username, password });
  if (!validActor(data.user)) throw new Error("账号响应格式无效");
  setCsrf("");
  setActor(data.user);
  await ensureCsrf();
  loaded = true;
}
export async function logout() {
  await client.post("/api/v1/auth/logout");
  setActor(null);
  loaded = false;
  window.location.hash = "/login";
}
