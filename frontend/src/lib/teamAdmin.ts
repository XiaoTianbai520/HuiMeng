import client from "@huimeng/api/client";
import { context } from "@huimeng/api/context";

export type Page<T> = { items: T[]; nextCursor: string | null };

export async function readAll<T>(path: string, signal?: AbortSignal): Promise<T[]> {
  const owner = context().actor?.id;
  const items: T[] = [];
  const cursors = new Set<string>();
  let cursor: string | null = null;
  do {
    const response: { data: Page<T> } = await client.get(path, { params: { limit: 50, ...(cursor ? { cursor } : {}) }, signal });
    if (context().actor?.id !== owner) throw new Error("会话已变更，请重新加载");
    items.push(...response.data.items);
    cursor = response.data.nextCursor;
    if (cursor && cursors.has(cursor)) throw new Error("列表游标重复，请重试");
    if (cursor) cursors.add(cursor);
  } while (cursor);
  return items;
}

export function adminError(error: unknown, fallback: string): string {
  if (!client.isAxiosError<{ code?: string }>(error)) return fallback;
  const code = error.response?.data.code;
  return ({ UNAUTHENTICATED: "会话已失效，请重新登录。", CSRF_INVALID: "会话已变化，请刷新页面后重试。", CAPABILITY_FORBIDDEN: "当前账号无权执行此操作。", PROJECT_FORBIDDEN: "你还不是该项目成员。", RATE_LIMITED: "操作过于频繁，请稍后再试。", INVALID_ARGUMENT: "请检查输入；账号名不可重复，且需保留至少一名有效管理员。" } as Record<string, string>)[code ?? ""] ?? fallback;
}
